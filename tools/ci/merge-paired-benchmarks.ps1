param(
    [Parameter(Mandatory)][string]$Directory,
    [ValidateSet(1, 2)][int]$ProcessorCount = 2,
    [string]$ContinuationDirectory = '',
    [string]$LegacyVirtualLivenessEvidence = '',
    [string]$CandidateVirtualResult = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/jmh-evidence-validation.ps1"
. "$PSScriptRoot/jmh-instrumentation.ps1"
$root = (Get-Item -LiteralPath $Directory).FullName
$directories = @($root)
if ($ContinuationDirectory) {
    $continuation = (Get-Item -LiteralPath $ContinuationDirectory).FullName
    if ($continuation -eq $root) { throw 'Continuation must be a distinct evidence directory.' }
    $directories += $continuation
}
$legacyUnavailable = ![string]::IsNullOrWhiteSpace($LegacyVirtualLivenessEvidence)
if ($legacyUnavailable -ne ![string]::IsNullOrWhiteSpace($CandidateVirtualResult)) {
    throw 'Legacy liveness evidence and a complete candidate virtual result are required together.'
}
if ($legacyUnavailable) { & "$PSScriptRoot/check-legacy-virtual-liveness.ps1" -Path $LegacyVirtualLivenessEvidence | Out-Null }
$scenarios = @('hit', 'miss1', 'miss16', 'miss64', 'virtual', 'mixed', 'count', 'facet', 'write')
foreach ($version in @('baseline', 'candidate')) {
    $destination = Join-Path $root "$version.json"
    if ((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath "$destination.instrumentation")) {
        throw "Refusing to overwrite evidence: $destination"
    }
}
$reports = @{}
$instrumentation = @{}
foreach ($version in @('baseline', 'candidate')) {
    $results = [Collections.Generic.List[object]]::new()
    $contracts = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($scenario in $scenarios) {
        if ($legacyUnavailable -and $scenario -eq 'virtual' -and $version -eq 'baseline') { continue }
        $paths = @(if ($legacyUnavailable -and $scenario -eq 'virtual') { $CandidateVirtualResult }
            else { $directories | ForEach-Object { Join-Path $_ "$scenario-$version.json" } | Where-Object { Test-Path -LiteralPath $_ } })
        if ($paths.Count -ne 1) { throw "Expected exactly one raw result for $scenario/$version; missing or duplicate evidence is not selectable." }
        $path = $paths[0]
        $file = Get-Item -LiteralPath $path
        if ($file.Length -le 0 -or $file.Length -gt 64MB) { throw "Invalid JMH file size: $path" }
        $cases = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
        $expected = if ($scenario -in @('count', 'facet')) { 4 } else { 2 }
        if ($cases.Count -ne $expected) { throw "Incomplete paired scenario: $scenario/$version" }
        $cellContracts = Read-JmhInstrumentation $path $cases
        foreach ($key in $cellContracts.Keys) {
            if ($contracts.ContainsKey($key)) { throw "Duplicate instrumentation case across raw cells: $key" }
            $contracts.Add($key, $cellContracts[$key])
        }
        if ($legacyUnavailable -and $scenario -eq 'virtual') {
            $reference = $results[0]
            foreach ($case in $cases) {
                foreach ($setting in @('jmhVersion','jdkVersion','vmName','vmVersion','forks','warmupIterations',
                        'warmupTime','warmupBatchSize','measurementIterations','measurementTime','measurementBatchSize')) {
                    if ([string]$case.$setting -cne [string]$reference.$setting) { throw "Candidate virtual settings mismatch: $setting" }
                }
                $normal = @(Get-JmhComparableArguments $case.jvmArgs -IgnoredProperties @('jmh.executor', 'jmh.executor.class'))
                $expectedArgs = @(Get-JmhComparableArguments $reference.jvmArgs -IgnoredProperties @())
                if (($normal -join "`n") -cne ($expectedArgs -join "`n")) { throw 'Candidate virtual JVM settings mismatch.' }
            }
        }
        foreach ($case in $cases) { $results.Add($case) }
    }
    $expected = if ($legacyUnavailable -and $version -eq 'baseline') { 20 } else { 22 }
    if ($results.Count -ne $expected) { throw "Expected exactly $expected measurements for $version." }
    $json = ConvertTo-Json -InputObject $results.ToArray() -Depth 100
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 64MB) { throw 'Merged JMH evidence exceeds 64 MiB.' }
    $reports[$version] = $json
    $instrumentation[$version] = $contracts
}
foreach ($key in $instrumentation.baseline.Keys) {
    if (!$instrumentation.candidate.ContainsKey($key)) { throw "Missing current instrumentation case: $key" }
    Assert-MatchingJmhInstrumentation $instrumentation.baseline[$key] $instrumentation.candidate[$key] $key
}
# Validate both reports before publishing either final file. Invalid staged evidence is retained.
$staging = Join-Path $root ('.merge-validation-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
foreach ($version in @('baseline', 'candidate')) {
    $destination = Join-Path $staging "$version.json"
    $json = $reports[$version]
    [IO.File]::WriteAllText($destination, $json, [Text.UTF8Encoding]::new($false))
    $rows = @($json | ConvertFrom-Json)
    Write-JmhInstrumentation $destination $rows $instrumentation[$version]
    $null = Read-JmhInstrumentation $destination $rows
    if ($legacyUnavailable -and $version -eq 'baseline') {
        & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $destination -ProcessorCount $ProcessorCount `
            -LegacyVirtualLivenessEvidence $LegacyVirtualLivenessEvidence
    } else { & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $destination -ProcessorCount $ProcessorCount }
}
foreach ($version in @('baseline', 'candidate')) {
    Move-Item -LiteralPath (Join-Path $staging "$version.json") -Destination (Join-Path $root "$version.json")
    Move-Item -LiteralPath (Join-Path $staging "$version.json.instrumentation") -Destination (Join-Path $root "$version.json.instrumentation")
}
[IO.Directory]::Delete($staging)
try {
    & "$PSScriptRoot/compare-benchmarks.ps1" -Baseline (Join-Path $root 'baseline.json') `
        -Current (Join-Path $root 'candidate.json') -Summary (Join-Path $root 'comparison.md')
} finally {
    if ($legacyUnavailable -and (Test-Path -LiteralPath (Join-Path $root 'comparison.md'))) {
        $note = "`n`nLegacy virtual baseline: UNAVAILABLE (verified pinned guardrail path). " +
            'The complete candidate virtual result is required, but no numeric legacy delta or regression PASS is assigned to it. ' +
            'All 20 comparable cases retain the unchanged 10% throughput / 15% p99 thresholds.'
        [IO.File]::AppendAllText((Join-Path $root 'comparison.md'), $note, [Text.UTF8Encoding]::new($false))
        Write-Output $note
    }
}
