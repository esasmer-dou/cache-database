#requires -Version 7.2
# Run in the validation pod: timeout 45s pwsh -NoProfile -File tools/k8s/test-source-archive-guard.ps1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'source-archive-guard.ps1')
$clock = [Diagnostics.Stopwatch]::StartNew()
function Require([bool]$Condition, [string]$Message) {
    if ($clock.Elapsed.TotalSeconds -ge 30) { throw 'Source archive guard test exceeded 30 seconds.' }
    if (!$Condition) { throw $Message }
}
function Fingerprint([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash + ':' +
        [IO.File]::GetLastWriteTimeUtc($Path).Ticks
}
$fakeKubectl = [pscustomobject]@{ Calls = 0; Mode = 'Boundary'; Response = @(); ExitCode = 0 }
function Invoke-FakeSourceKubectl {
    $fakeKubectl.Calls++
    if ($fakeKubectl.Mode -ceq 'Usage') {
        if ($fakeKubectl.Calls -gt 1) { throw 'EXPECTED_FAKE_SOURCE_COPY_BOUNDARY' }
        $global:LASTEXITCODE = $fakeKubectl.ExitCode
        return $fakeKubectl.Response
    }
    throw 'EXPECTED_FAKE_KUBECTL_BOUNDARY'
}
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $tempRoot ('cachedb-source-guard-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixture
try {
    $toolDir = Join-Path $fixture 'tools/k8s'
    $null = New-Item -ItemType Directory -Path $toolDir -Force
    foreach ($name in @('sync-workspace.ps1', 'source-archive-guard.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $toolDir $name)
    }
    $ignore = Join-Path $fixture '.gitignore'
    $tracked = Join-Path $fixture 'tracked.txt'
    $deleted = Join-Path $fixture 'deleted.txt'
    $untracked = Join-Path $fixture 'untracked.txt'
    $ignored = Join-Path $fixture 'ignored.txt'
    [IO.File]::WriteAllText($ignore, "tools/`ntarget/`nignored.txt`n")
    [IO.File]::WriteAllText($tracked, 'original tracked content')
    [IO.File]::WriteAllText($deleted, 'deleted tracked content')
    & git -C $fixture init --quiet
    Require ($LASTEXITCODE -eq 0) 'Fixture git init failed.'
    & git -C $fixture add -- .gitignore tracked.txt deleted.txt
    Require ($LASTEXITCODE -eq 0) 'Fixture git add failed.'
    [IO.File]::WriteAllText($tracked, 'dirty tracked content')
    [IO.File]::Delete($deleted)
    [IO.File]::WriteAllText($untracked, 'untracked content')
    [IO.File]::WriteAllText($ignored, 'ignored content')
    $epoch = [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
    [IO.File]::SetLastWriteTimeUtc($ignored, $epoch)
    $normalTime = [DateTime]::UtcNow.AddMinutes(-1)
    foreach ($path in @($ignore, $tracked, $untracked)) {
        [IO.File]::SetLastWriteTimeUtc($path, $normalTime)
    }
    $gitRoster = @(& git -C $fixture -c core.quotepath=false ls-files --cached --others --exclude-standard)
    Require ($LASTEXITCODE -eq 0) 'Fixture Git roster failed.'
    $expected = @($gitRoster | Where-Object { [IO.File]::Exists([IO.Path]::Combine($fixture, $_)) })
    $roster = @(Get-K8sSourceArchiveFiles -Root $fixture)
    Require (($roster -join "`n") -ceq ($expected -join "`n")) 'Git roster order or membership changed.'
    Require ((($roster | Sort-Object) -join "`n") -ceq ".gitignore`ntracked.txt`nuntracked.txt") 'Dirty/untracked Git roster changed.'
    $otherBefore = @((Fingerprint $ignore), (Fingerprint $untracked), (Fingerprint $ignored)) -join "`n"
    $cases = @(
        @{ Name = 'negative'; Time = $epoch.AddSeconds(-1); Allowed = $false },
        @{ Name = 'zero'; Time = $epoch; Allowed = $false },
        @{ Name = 'fractional-zero'; Time = $epoch.AddMilliseconds(500); Allowed = $false },
        @{ Name = 'one'; Time = $epoch.AddSeconds(1); Allowed = $true },
        @{ Name = 'normal'; Time = $normalTime; Allowed = $true },
        @{ Name = 'future'; Time = [DateTime]::UtcNow.AddDays(1); Allowed = $true }
    )
    foreach ($case in $cases) {
        [IO.File]::SetLastWriteTimeUtc($tracked, $case.Time)
        Require ([IO.File]::GetLastWriteTimeUtc($tracked).Ticks -eq $case.Time.Ticks) 'Fixture filesystem changed the test timestamp.'
        $before = Fingerprint $tracked
        $fakeKubectl.Calls = 0
        $failure = $null
        try {
            & (Join-Path $toolDir 'sync-workspace.ps1') -Kubectl Invoke-FakeSourceKubectl `
                -Context fixture-only -Namespace cachedb-fixture -SourceId source-guard
        } catch { $failure = $_.Exception.Message }
        if ($case.Allowed) {
            Require ($failure -ceq 'EXPECTED_FAKE_KUBECTL_BOUNDARY' -and $fakeKubectl.Calls -eq 1) "Valid timestamp rejected: $($case.Name)"
        } else {
            Require ($failure -like 'Source timestamp must be at least*' -and $fakeKubectl.Calls -eq 0) "Unsafe timestamp reached Kubernetes: $($case.Name)"
        }
        Require ((Fingerprint $tracked) -ceq $before) "Guard changed source bytes or mtime: $($case.Name)"
        Require ((@((Fingerprint $ignore), (Fingerprint $untracked), (Fingerprint $ignored)) -join "`n") -ceq $otherBefore) 'Guard changed another source file.'
        Require (!(Test-Path -LiteralPath (Join-Path $fixture 'target'))) 'Guard created transfer output before Kubernetes preflight.'
    }
    [IO.File]::SetLastWriteTimeUtc($tracked, $normalTime)
    $before = Fingerprint $tracked
    $usageCases = @(
        @{ Response = @("6144`t/workspace"); ExitCode = 0; Error = 'Workspace uses 6144 MiB;*'; Calls = 1 },
        @{ Response = @('6145 /workspace'); ExitCode = 0; Error = 'Workspace uses 6145 MiB;*'; Calls = 1 },
        @{ Response = @('invalid /workspace'); ExitCode = 0; Error = 'Invalid workspace disk usage response.'; Calls = 1 },
        @{ Response = @('-1 /workspace'); ExitCode = 0; Error = 'Invalid workspace disk usage response.'; Calls = 1 },
        @{ Response = @('1 /workspace', 'extra row'); ExitCode = 0; Error = 'Cannot establish workspace disk usage*'; Calls = 1 },
        @{ Response = @('1 /workspace'); ExitCode = 1; Error = 'Cannot establish workspace disk usage*'; Calls = 1 },
        @{ Response = @("1`t/workspace"); ExitCode = 0; Error = 'EXPECTED_FAKE_SOURCE_COPY_BOUNDARY'; Calls = 2 }
    )
    $fakeKubectl.Mode = 'Usage'
    foreach ($case in $usageCases) {
        $fakeKubectl.Calls = 0
        $fakeKubectl.Response = $case.Response
        $fakeKubectl.ExitCode = $case.ExitCode
        $failure = $null
        try {
            & (Join-Path $toolDir 'sync-workspace.ps1') -Kubectl Invoke-FakeSourceKubectl `
                -Context fixture-only -Namespace cachedb-fixture -SourceId source-guard
        } catch { $failure = $_.Exception.Message }
        Require ($failure -like $case.Error -and $fakeKubectl.Calls -eq $case.Calls) 'Workspace usage parsing or transfer admission changed.'
        Require ((Fingerprint $tracked) -ceq $before) 'Disk preflight changed source bytes or mtime.'
        if ($case.Calls -eq 1) {
            Require (!(Test-Path -LiteralPath (Join-Path $fixture 'target'))) 'Rejected disk preflight created transfer output.'
        } else {
            Require (Test-Path -LiteralPath (Join-Path $fixture 'target/k8s-transfer/source.tgz')) 'Valid disk usage did not reach source archive creation.'
        }
    }
    # A Git failure must not turn into an empty, apparently valid source roster.
    $null = Rename-Item -LiteralPath (Join-Path $fixture '.git') -NewName '.fixture-git'
    $fakeKubectl.Calls = 0
    $failure = $null
    try {
        & (Join-Path $toolDir 'sync-workspace.ps1') -Kubectl Invoke-FakeSourceKubectl `
            -Context fixture-only -Namespace cachedb-fixture -SourceId source-guard
    } catch { $failure = $_.Exception.Message }
    Require ($null -ne $failure -and $fakeKubectl.Calls -eq 0) 'Git failure reached Kubernetes.'
    Write-Output 'PASS: timestamps, Git roster/failure, source retention, disk parsing/admission, and mocked transfer boundary.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $relative = [IO.Path]::GetRelativePath($tempRoot, $resolved)
    if ([IO.Path]::IsPathRooted($relative) -or $relative -notmatch '^cachedb-source-guard-[a-f0-9]{32}$') {
        throw 'Refusing cleanup outside the owned fixture directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
