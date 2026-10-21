param(
    [ValidateRange(3, 20)][int]$Runs = 5,
    [ValidateRange(1, 1000)][double]$MaximumMilliseconds = 50,
    [switch]$SkipBuild,
    [switch]$PackagedArtifacts,
    [switch]$ReportOnly,
    [string]$MavenRepository = '',
    [string]$ResultsDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (!$ResultsDirectory) { $ResultsDirectory = Join-Path $root 'target/jackson-free-latency' }
$ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
$null = New-Item -ItemType Directory -Force -Path $ResultsDirectory
# A failed or interrupted rerun must never leave an older successful summary behind.
[ordered]@{
    generatedUtc = [DateTime]::UtcNow.ToString('o'); completed = $false
    fullTargetPasses = $false; exclusiveMaximumMillis = $MaximumMilliseconds
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDirectory 'summary.json') -Encoding utf8
$java = Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
if (!(Test-Path -LiteralPath $java)) { throw 'JAVA_HOME must identify the target test JDK.' }
Push-Location $root
try {
    if (!$SkipBuild) {
        & mvn -B -ntp -pl cachedb-examples -am package '-DskipTests' `
            2>&1 | Tee-Object -FilePath (Join-Path $ResultsDirectory 'build.log') | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Cannot build generated codec example.' }
    }
    if (!$MavenRepository) {
        $resolved = @(& mvn -N -q -ntp help:evaluate '-Dexpression=settings.localRepository' '-DforceStdout')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the configured local Maven repository; supply -MavenRepository.' }
        $candidates = @($resolved | ForEach-Object { ([string]$_ -replace '\x1B\[[0-?]*[ -/]*[@-~]', '').Trim() } |
            Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) })
        if ($candidates.Count -ne 1) { throw 'Ambiguous Maven repository; supply -MavenRepository explicitly.' }
        $MavenRepository = $candidates[0]
    }
    # Deliberately no Jackson, Spring, JDBC, Redis client or test framework JARs.
    [xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
    $version = [string]$pom.project.version
    $entries = @('cachedb-examples', 'cachedb-core', 'cachedb-json', 'cachedb-spring-boot-starter') |
        ForEach-Object {
            $path = if ($PackagedArtifacts) { "$_/target/$_-$version.jar" } else { "$_/target/classes" }
            Join-Path $root $path
        }
    $gsonVersion = [string]$pom.project.properties.'cachedb.gson.version'
    if ($gsonVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid pinned Gson version.' }
    $entries += Join-Path $MavenRepository "com/google/code/gson/gson/$gsonVersion/gson-$gsonVersion.jar"
    foreach ($entry in $entries) {
        if (!(Test-Path -LiteralPath $entry)) { throw "Missing runtime dependency: $entry" }
    }
    $cp = $entries -join [IO.Path]::PathSeparator
    $samples = @()
    foreach ($mode in @('decode', 'roundtrip', 'snapshot')) {
        for ($run = 1; $run -le $Runs; $run++) {
            $info = [Diagnostics.ProcessStartInfo]::new($java)
            $info.UseShellExecute = $false
            $info.CreateNoWindow = $true
            $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
            $info.RedirectStandardOutput = $true
            $info.RedirectStandardError = $true
            foreach ($argument in @('-cp', $cp,
                'com.reactor.cachedb.examples.snapshotjson.JacksonFreeSnapshotExample', $mode, '20')) {
                $info.ArgumentList.Add($argument)
            }
            $process = [Diagnostics.Process]::new()
            $process.StartInfo = $info
            try {
                if (!$process.Start()) { throw 'Could not start target JVM.' }
                $outputTask = $process.StandardOutput.ReadToEndAsync()
                $errorTask = $process.StandardError.ReadToEndAsync()
                if (!$process.WaitForExit(60000)) {
                    $process.Kill($true)
                    throw "Probe timed out: $mode run $run"
                }
                $output = $outputTask.GetAwaiter().GetResult()
                $errors = $errorTask.GetAwaiter().GetResult()
                ($output + $errors) | Set-Content -LiteralPath (Join-Path $ResultsDirectory "$mode-$run.log") -Encoding utf8
                if ($process.ExitCode -ne 0) { throw "Functional probe failed: $mode run $run. $errors" }
                $lines = @($output -split '\r?\n' | Where-Object { $_.Length -gt 0 })
                if ($lines.Count -ne 21) { throw 'Incomplete probe output.' }
                for ($i = 0; $i -lt $lines.Count; $i++) {
                    if ($lines[$i] -notmatch "^mode=$mode(?: repeat=\d+)? nanos=(\d+)$") {
                        throw "Unexpected probe output: $($lines[$i])"
                    }
                    $samples += [pscustomobject]@{
                        mode = $mode; run = $run; phase = $(if ($i -eq 0) { 'cold' } else { 'repeated' })
                        millis = [long]$Matches[1] / 1000000.0
                    }
                }
            } finally { $process.Dispose() }
        }
    }
    $summary = @($samples | Group-Object mode, phase | ForEach-Object {
        $times = @($_.Group.millis | Sort-Object)
        [pscustomobject]@{
            mode = $_.Group[0].mode; phase = $_.Group[0].phase; samples = $times.Count
            medianMillis = ($times[[int][Math]::Floor(($times.Count - 1) / 2)] +
                $times[[int][Math]::Floor($times.Count / 2)]) / 2
            maxMillis = $times[-1]; passes = $times[-1] -lt $MaximumMilliseconds
        }
    })
    $passed = @($summary | Where-Object { !$_.passes }).Count -eq 0
    [ordered]@{
        generatedUtc = [DateTime]::UtcNow.ToString('o'); completed = $true; javaHome = $env:JAVA_HOME
        runs = $Runs; exclusiveMaximumMillis = $MaximumMilliseconds; fullTargetPasses = $passed
        scope = 'Correctness-checked generated codec probes. Cold includes all scenario first-use initialization, not JVM launch/compilation. Every cold and repeated sample is retained; no discarded warmup or percentile acceptance. Not a throughput/production benchmark.'
        packagedArtifacts = [bool]$PackagedArtifacts
        classpath = $entries
        artifactSha256 = @($entries | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | ForEach-Object {
            [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
        })
        summary = $summary; measurements = $samples
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ResultsDirectory 'summary.json') -Encoding utf8
    $summary | Format-Table -AutoSize | Out-Host
    if (!$passed -and !$ReportOnly) { throw "Strict <$MaximumMilliseconds ms target failed. See $ResultsDirectory" }
    Write-Host "Full cold and repeated target passes: $passed. Results: $ResultsDirectory"
} finally { Pop-Location }
