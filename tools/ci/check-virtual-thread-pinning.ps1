#requires -Version 7.2
<#
.SYNOPSIS
Checks completed JDK 21 recordings for CacheDB virtual-thread pinning.
.DESCRIPTION
Runs only the JDK jfr reader, never builds, starts tests, attaches to a JVM or
contacts Kubernetes. Run in the approved evidence environment. The context
argument is an operator attestation, not discovery of the pod's cluster identity.
Exit 0: no CacheDB pinned frames observed; 1: CacheDB pinning; 2: invalid evidence.
Zero events do not establish test coverage, event enablement or VM capability.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateCount(1, 32)]
    [string[]]$RecordingPath,
    [ValidateLength(1, 128)]
    [string]$KubernetesContext = 'unspecified',
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1, 64)]
    [int]$MaxFileMiB = 64,
    [ValidateRange(1, 256)]
    [int]$MaxTotalMiB = 256,
    [ValidateRange(1, 32)]
    [int]$MaxJsonMiB = 32,
    [ValidateRange(1, 10000)]
    [int]$MaxEvents = 10000,
    [ValidateRange(1, 60)]
    [int]$ProcessTimeoutSeconds = 30,
    [ValidateRange(1, 300)]
    [int]$TotalTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$deadline = [Diagnostics.Stopwatch]::StartNew()
$printDepth = 256

function Assert-Deadline {
    if ($deadline.Elapsed.TotalSeconds -ge $TotalTimeoutSeconds) {
        throw 'Total evidence-processing deadline exceeded.'
    }
}

function Get-BoundedInputHash([IO.FileStream]$Stream, [long]$ExpectedLength) {
    if ($Stream.Length -ne $ExpectedLength) { throw 'Recording size changed before hashing.' }
    $Stream.Position = 0
    $hasher = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        $buffer = [byte[]]::new(8192)
        $remaining = $ExpectedLength
        while ($remaining -gt 0) {
            Assert-Deadline
            $count = $Stream.Read($buffer, 0, [int][Math]::Min($remaining, $buffer.Length))
            if ($count -le 0) { throw 'Recording ended before its declared size.' }
            $hasher.AppendData($buffer, 0, $count)
            $remaining -= $count
        }
        if ($Stream.Length -ne $ExpectedLength) { throw 'Recording size changed while hashing.' }
        return [Convert]::ToHexString($hasher.GetHashAndReset()).ToLowerInvariant()
    } finally { $hasher.Dispose() }
}

function Invoke-BoundedJfr([string]$Executable, [string]$Path) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('print', '--json', '--events',
            'jdk.VirtualThreadPinned,jdk.DataLoss', '--stack-depth', "$printDepth", $Path)) {
        $start.ArgumentList.Add($argument)
    }
    # Bound the reader's Java heap; the job must also impose an OS/container limit.
    $start.Environment['JAVA_TOOL_OPTIONS'] = '-Xms16m -Xmx256m'
    $start.Environment.Remove('_JAVA_OPTIONS') | Out-Null
    $start.Environment.Remove('JDK_JAVA_OPTIONS') | Out-Null
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $out = [IO.MemoryStream]::new()
    $err = [IO.MemoryStream]::new()
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Unable to start the JFR reader.' }
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $outBytes = [byte[]]::new(8192)
        $errBytes = [byte[]]::new(8192)
        $outTask = $process.StandardOutput.BaseStream.ReadAsync($outBytes, 0, $outBytes.Length)
        $errTask = $process.StandardError.BaseStream.ReadAsync($errBytes, 0, $errBytes.Length)
        $outDone = $false
        $errDone = $false
        # Drain both pipes concurrently without an unbounded ReadToEnd or log file.
        while (-not ($outDone -and $errDone -and $process.HasExited)) {
            Assert-Deadline
            if ($timer.Elapsed.TotalSeconds -ge $ProcessTimeoutSeconds) {
                throw 'JFR reader deadline exceeded.'
            }
            if (-not $outDone -and $outTask.IsCompleted) {
                $count = $outTask.GetAwaiter().GetResult()
                if ($count -eq 0) { $outDone = $true }
                else {
                    if ($out.Length + $count -gt $MaxJsonMiB * 1MB) {
                        throw 'JFR JSON output exceeds the byte budget.'
                    }
                    $out.Write($outBytes, 0, $count)
                    $outTask = $process.StandardOutput.BaseStream.ReadAsync($outBytes, 0, $outBytes.Length)
                }
            }
            if (-not $errDone -and $errTask.IsCompleted) {
                $count = $errTask.GetAwaiter().GetResult()
                if ($count -eq 0) { $errDone = $true }
                else {
                    if ($err.Length + $count -gt 64KB) { throw 'JFR stderr exceeds 64 KiB.' }
                    $err.Write($errBytes, 0, $count)
                    $errTask = $process.StandardError.BaseStream.ReadAsync($errBytes, 0, $errBytes.Length)
                }
            }
            Start-Sleep -Milliseconds 10
        }
        if ($process.ExitCode -ne 0) { throw "JFR reader failed: exit=$($process.ExitCode)." }
        if ($out.Length -eq 0) { throw 'JFR reader produced no JSON.' }
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        return $utf8.GetString($out.GetBuffer(), 0, [int]$out.Length)
    } finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true)
            if (-not $process.WaitForExit(2000)) { Write-Warning 'JFR reader did not exit within the cleanup deadline.' }
        }
        $process.Dispose()
        $out.Dispose()
        $err.Dispose()
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Specify JavaHome for the evidence JDK 21.' }
    $release = Get-Item -LiteralPath (Join-Path $JavaHome 'release')
    if ($release.Length -gt 64KB) { throw 'JDK release metadata exceeds 64 KiB.' }
    if ((Get-Content -LiteralPath $release.FullName -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:\.|[+"]|-)') {
        throw 'This J-0 checker requires the JDK 21 reader; other VM versions need separate qualification.'
    }
    $jfr = Join-Path $JavaHome 'bin/jfr'
    if ($IsWindows) { $jfr += '.exe' }
    if (-not (Test-Path -LiteralPath $jfr -PathType Leaf)) { throw 'JDK jfr executable is missing.' }

    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $files = [Collections.Generic.List[object]]::new()
    $totalBytes = 0L
    foreach ($path in $RecordingPath) {
        Assert-Deadline
        $file = Get-Item -LiteralPath $path -Force
        if ($file.PSIsContainer -or $file.Extension -ine '.jfr' -or $file.Length -le 0) {
            throw 'Each explicit input must be a nonempty completed .jfr file.'
        }
        if (-not $paths.Add($file.FullName)) { throw 'Duplicate recording path.' }
        $totalBytes += $file.Length
        if ($file.Length -gt $MaxFileMiB * 1MB -or $totalBytes -gt $MaxTotalMiB * 1MB) {
            throw 'JFR inputs exceed the per-file or total byte budget.'
        }
        $files.Add($file)
    }

    $events = 0
    $pins = 0
    $cachedbPins = 0
    $incomplete = 0
    $examples = [Collections.Generic.List[string]]::new()
    $evidence = [Collections.Generic.List[object]]::new()
    foreach ($file in $files) {
        Assert-Deadline
        # Keep an input read handle open and reject changed content, including native writers.
        $inputStream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $document = $null
        try {
            $hash = Get-BoundedInputHash $inputStream $file.Length
            $json = Invoke-BoundedJfr $jfr $file.FullName
            Assert-Deadline
            $options = [Text.Json.JsonDocumentOptions]::new()
            $options.MaxDepth = 48
            $document = [Text.Json.JsonDocument]::Parse([string]$json, $options)
            $root = $document.RootElement.GetProperty('recording').GetProperty('events')
            if ($root.ValueKind -ne [Text.Json.JsonValueKind]::Array) { throw 'JFR recording.events must be an array.' }
            foreach ($event in $root.EnumerateArray()) {
                Assert-Deadline
                if (++$events -gt $MaxEvents) { throw 'JFR evidence exceeds the total event budget.' }
                $type = $event.GetProperty('type').GetString()
                if ($type -eq 'jdk.DataLoss') { throw 'JFR reports data loss; absence of pinning is not reliable.' }
                if ($type -ne 'jdk.VirtualThreadPinned') { throw 'Unexpected event type from the JFR filter.' }
                $pins++
                $stack = $event.GetProperty('values').GetProperty('stackTrace')
                if ($stack.ValueKind -eq [Text.Json.JsonValueKind]::Null) { $incomplete++; continue }
                $truncated = $stack.GetProperty('truncated').GetBoolean()
                $frames = $stack.GetProperty('frames')
                if ($frames.ValueKind -ne [Text.Json.JsonValueKind]::Array) { throw 'Invalid JFR stack frames.' }
                $frameCount = $frames.GetArrayLength()
                if ($truncated -or $frameCount -eq 0 -or $frameCount -ge $printDepth) { $incomplete++ }
                $matched = $false
                foreach ($frame in $frames.EnumerateArray()) {
                    $method = $frame.GetProperty('method')
                    $className = $method.GetProperty('type').GetProperty('name').GetString()
                    if ([string]::IsNullOrWhiteSpace($className)) { throw 'JFR frame has no class name.' }
                    $normalized = $className.Replace('/', '.')
                    if ($normalized.StartsWith('com.reactor.cachedb.', [StringComparison]::Ordinal)) {
                        $matched = $true
                        if ($examples.Count -lt 8) { $examples.Add($normalized.Substring(0, [Math]::Min(240, $normalized.Length))) }
                    }
                }
                if ($matched) { $cachedbPins++ }
            }
            $after = Get-BoundedInputHash $inputStream $file.Length
            $current = Get-Item -LiteralPath $file.FullName
            if ($after -ne $hash -or $current.Length -ne $file.Length -or $inputStream.Length -ne $file.Length) {
                throw 'Recording changed during inspection; only finalized evidence is accepted.'
            }
            $currentStream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            try {
                if ((Get-BoundedInputHash $currentStream $file.Length) -ne $hash) {
                    throw 'Recording path was replaced during inspection.'
                }
            } finally { $currentStream.Dispose() }
            $evidence.Add([ordered]@{ path = $file.FullName; bytes = $file.Length; sha256 = $hash })
        } finally {
            if ($null -ne $document) { $document.Dispose() }
            $inputStream.Dispose()
        }
    }
    Assert-Deadline
    $result = if ($cachedbPins -gt 0) { 'FAIL_PINNING' } elseif ($incomplete -gt 0) { 'INVALID_STACK_EVIDENCE' } else { 'NO_CACHEDB_PINNING_OBSERVED' }
    [ordered]@{
        result = $result; kubernetesContextAttestation = $KubernetesContext
        recordings = $evidence; pinnedEvents = $pins; cachedbPinnedEvents = $cachedbPins
        incompleteStacks = $incomplete; exampleClasses = $examples
        coverageEstablished = $false
    } | ConvertTo-Json -Depth 6
    if ($cachedbPins -gt 0) { exit 1 }
    if ($incomplete -gt 0) { exit 2 }
    exit 0
} catch {
    Write-Error -Message "Invalid JFR evidence: $($_.Exception.Message)" -ErrorAction Continue
    exit 2
}
