#requires -Version 7.2
param(
    [string]$Directory = (Join-Path ([IO.Path]::GetTempPath()) "cachedb-jmh-gates-$([guid]::NewGuid().ToString('N'))"),
    [string]$RetainedBaselinePath = (Join-Path $PSScriptRoot '../benchmarks/baseline/v0.18.1.json')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Test-Path -LiteralPath $Directory) { throw 'Focused suite requires a new disposable directory.' }
$baseline = Get-Item -LiteralPath $RetainedBaselinePath
if ($baseline.PSIsContainer -or $baseline.Length -le 0 -or $baseline.Length -gt 64MB -or
    ($baseline.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Retained baseline must be a nonempty regular JSON file, at most 64 MiB.'
}
$sources = @(
    'test-jmh-measurement-gates.ps1', 'test-jmh-evidence-validation.ps1',
    'test-paired-benchmark-merge.ps1', 'test-jmh-histogram-consistency.ps1',
    'check-jmh-evidence.ps1', 'compare-benchmarks.ps1', 'merge-paired-benchmarks.ps1',
    'jmh-evidence-validation.ps1', 'jmh-evidence-test-fixtures.ps1',
    'jmh-histogram-consistency.ps1', 'check-legacy-virtual-liveness.ps1',
    'test-t1-default-acceptance.ps1', 'check-t1-coalescing-evidence.ps1',
    'jmh-instrumentation.ps1', 'test-jmh-instrumentation.ps1',
    '../benchmarks/redis/write-jmh-instrumentation.py', '../benchmarks/redis/test-jmh-instrumentation.py',
    '../benchmarks/redis/jmh-instrumentation.sh'
)
$sourceHashes = [ordered]@{}
foreach ($source in $sources) {
    $sourceHashes[$source] = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $source) -Algorithm SHA256).Hash
}
$before = (Get-FileHash -LiteralPath $baseline.FullName -Algorithm SHA256).Hash
$null = New-Item -ItemType Directory -Path $Directory
$checks = [Collections.Generic.List[string]]::new()
try {
    & "$PSScriptRoot/test-jmh-instrumentation.ps1" -Directory (Join-Path $Directory 'instrumentation')
    $checks.Add('raw-bound-instrumentation-fail-closed-controls')
    & python3 -B "$PSScriptRoot/../benchmarks/redis/test-jmh-instrumentation.py"
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation writer synthetic controls failed.' }
    $checks.Add('actual-controller-instrumentation-writer-controls')
    & "$PSScriptRoot/test-t1-default-acceptance.ps1"
    $checks.Add('t1-profiler-classpath-duration-and-default-controls')
    & "$PSScriptRoot/test-jmh-evidence-validation.ps1" -Directory (Join-Path $Directory 'validator')
    $checks.Add('validator-and-comparator-controls')
    & "$PSScriptRoot/test-paired-benchmark-merge.ps1" -Directory (Join-Path $Directory 'merge')
    $checks.Add('original-and-extended-paired-merge-controls')
    # Validate retained histograms in format-only mode, then prove strict duration rejection separately.
    & "$PSScriptRoot/test-jmh-histogram-consistency.ps1" -EvidenceDirectory (Join-Path $Directory 'histogram')
    $checks.Add('shared-histogram-controls')
    & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $baseline.FullName -ProcessorCount 2 -RetainedBaselineFormatOnly
    $checks.Add('retained-baseline-format-once')
    $strictDurationRejection = $null
    try {
        & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $baseline.FullName -ProcessorCount 2
    } catch {
        if ($_.Exception.Message -notlike 'Strict JMH acceptance requires 3 s for warmupTime:*') { throw }
        $strictDurationRejection = $_.Exception.Message
    }
    if ($null -eq $strictDurationRejection) { throw 'Strict acceptance admitted the retained legacy-duration baseline.' }
    $checks.Add('retained-baseline-strict-rejects-legacy-duration')
} finally {
    $after = (Get-FileHash -LiteralPath $baseline.FullName -Algorithm SHA256).Hash
    if ($after -cne $before) { throw 'Focused suite changed retained baseline evidence.' }
}
foreach ($source in $sources) {
    $afterSource = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $source) -Algorithm SHA256).Hash
    if ($afterSource -cne $sourceHashes[$source]) { throw "Source changed during focused suite: $source" }
}
$summary = [ordered]@{
    result = 'PASS'
    acceptance = 'SYNTHETIC_FORMAT_ONLY_NOT_PERFORMANCE_ACCEPTANCE'
    completedUtc = [DateTime]::UtcNow.ToString('o')
    checks = $checks.ToArray()
    retainedBaseline = [ordered]@{
        path = $baseline.FullName; sha256 = $before; validationCalls = 2; unchanged = $true
        formatOnly = 'PASS_NOT_PERFORMANCE_ACCEPTANCE'
        strictAcceptance = 'REJECTED_LEGACY_DURATION'
        strictRejection = $strictDurationRejection
    }
    benchmarkExecuted = $false
    sourceSha256 = $sourceHashes
}
[IO.File]::WriteAllText((Join-Path $Directory 'summary.json'), (ConvertTo-Json -InputObject $summary -Depth 8))
Write-Output "JMH_MEASUREMENT_GATES_PASS=$($checks.Count); SYNTHETIC_FORMAT_ONLY_NOT_PERFORMANCE_ACCEPTANCE; DIRECTORY=$Directory"
