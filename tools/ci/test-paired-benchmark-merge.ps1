param([string]$Directory = (Join-Path ([IO.Path]::GetTempPath()) "cachedb-paired-merge-$([guid]::NewGuid().ToString('N'))"))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/jmh-evidence-test-fixtures.ps1"
. "$PSScriptRoot/jmh-instrumentation.ps1"
if (Test-Path -LiteralPath $Directory) { throw 'Self-test requires a new disposable directory.' }
New-Item -ItemType Directory -Path $Directory | Out-Null
$scenarios = [ordered]@{ hit = 'l1HitFindById'; miss1 = 'l1MissFindById'; miss16 = 'l1MissFindById';
    miss64 = 'l1MissFindById'; virtual = 'l1MissFindByIdVirtual1000'; mixed = 'findAllMixed100';
    count = 'aggregateCount10k'; facet = 'facet10k20Groups'; write = 'saveSingle' }

function New-Fixture([string]$Name) {
    $root = Join-Path $Directory $Name
    New-Item -ItemType Directory -Path $root | Out-Null
    foreach ($version in @('baseline', 'candidate')) {
        foreach ($entry in $scenarios.GetEnumerator()) {
            $rows = [Collections.Generic.List[object]]::new()
            $parameters = if ($entry.Key -in @('count', 'facet')) { @('false', 'true') } else { @('-') }
            foreach ($mode in @('thrpt', 'sample')) {
                foreach ($tenant in $parameters) {
                    $threads = switch ($entry.Key) { miss16 { 16 }; miss64 { 64 }; virtual { 1000 }; default { 1 } }
                    $row = New-JmhTestRow -Mode $mode -Score 100 -Threads $threads `
                        -Benchmark "com.reactor.cachedb.benchmarks.redis.RedisRepositoryBenchmark.$($entry.Value)"
                    if ($tenant -ne '-') {
                        $row | Add-Member -NotePropertyName params -NotePropertyValue ([pscustomobject]@{ tenant = $tenant })
                    }
                    $rows.Add($row)
                }
            }
            $path = Join-Path $root "$($entry.Key)-$version.json"
            [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $rows.ToArray() -Depth 20))
            Write-JmhTestInstrumentation $path $rows.ToArray()
        }
    }
    return $root
}

function Must-Reject([scriptblock]$Action, [string]$Case, [string]$Expected) {
    $rejected = $false
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notlike $Expected) { throw "Wrong rejection for ${Case}: $($_.Exception.Message)" }
        $rejected = $true
    }
    if (!$rejected) { throw "Invalid synthetic evidence was accepted: $Case" }
}

$valid = New-Fixture 'valid'
$originals = @{}
foreach ($file in Get-ChildItem -LiteralPath $valid -File) { $originals[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName).Hash }
& "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $valid | Out-Null
foreach ($path in $originals.Keys) {
    if ((Get-FileHash -LiteralPath $path).Hash -cne $originals[$path]) { throw 'Merge modified original raw evidence or instrumentation.' }
}
foreach ($version in @('baseline', 'candidate')) {
    $path = Join-Path $valid "$version.json"
    $rows = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
    $contracts = Read-JmhInstrumentation $path $rows
    if ($contracts.Count -ne 22) { throw 'Merge lost case instrumentation contracts.' }
    foreach ($row in $rows) {
        Assert-MatchingJmhInstrumentation (New-JmhTestInstrumentationContract) $contracts[(Get-JmhCaseKey $row)] (Get-JmhCaseKey $row)
    }
}
$before = (Get-FileHash (Join-Path $valid 'baseline.json')).Hash
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $valid } 'overwrite' 'Refusing to overwrite evidence:*'
if ((Get-FileHash (Join-Path $valid 'baseline.json')).Hash -ne $before) { throw 'Existing evidence was modified.' }

$incomplete = New-Fixture 'incomplete'
$path = Join-Path $incomplete 'miss64-candidate.json'
$items = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject @($items[0]) -Depth 20))
Write-JmhTestInstrumentation $path @($items[0])
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $incomplete } 'missing measurement' 'Incomplete paired scenario:*'
if ((Test-Path (Join-Path $incomplete 'baseline.json')) -or (Test-Path (Join-Path $incomplete 'candidate.json'))) {
    throw 'Incomplete evidence published a final merged report.'
}

$wrongCpu = New-Fixture 'wrong-cpu'
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $wrongCpu -ProcessorCount 1 } 'CPU mismatch' 'JVM resource settings differ*'
if ((Test-Path (Join-Path $wrongCpu 'baseline.json')) -or (Test-Path (Join-Path $wrongCpu 'candidate.json'))) {
    throw 'Invalid resource settings published a final merged report.'
}

$regression = New-Fixture 'regression'
$path = Join-Path $regression 'hit-candidate.json'
$items = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
$items[0] = New-JmhTestRow -Mode 'thrpt' -Score 80 -Benchmark $items[0].benchmark
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $items -Depth 20))
Write-JmhTestInstrumentation $path $items
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $regression } '20 percent throughput regression' '*throughput regression -20*'
function New-LegacyFailure([string]$Root) {
    $proof = Join-Path $Root 'legacy-proof'
    New-Item -ItemType Directory -Path $proof | Out-Null
    $threads = @(0..999 | ForEach-Object { @{ name = "jmh-virtual-$_"; stack = @('VirtualThread.park') } })
    $threads[0].stack = @('VirtualThread.parkOnCarrierThread','RedisProducerGuard.sampleSafely','GenericObjectPool.borrowObject')
    $dump = Join-Path $proof 'threads.json'
    @{ threadDump = @{ threadContainers = @(@{ threads = $threads }) } } | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $dump -Encoding utf8
    $log = Join-Path $proof 'run.log'
    'l1MissFindByIdVirtual1000; Benchmark had encountered error; forked VM failed with exit code 143' |
        Set-Content -LiteralPath $log -Encoding utf8
    $path = Join-Path $proof 'failure.json'
    @{ schemaVersion = 1; result = 'LEGACY_VIRTUAL_BASELINE_UNAVAILABLE'; baselineTag = 'v0.18.1'
        baselineCommit = '3a212461fd835c783cf84d3c6b656b375df40a19'; runtimeSourcesUnmodified = $true
        operatorAttestedStallSeconds = 60; workerCount = 1000; baselineJarSha256 = 'a' * 64
        threadDump = @{ file = 'threads.json'; sha256 = (Get-FileHash $dump).Hash.ToLowerInvariant() }
        runLog = @{ file = 'run.log'; sha256 = (Get-FileHash $log).Hash.ToLowerInvariant() }
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding utf8
    return $path
}
$legacy = New-Fixture 'legacy-liveness'
$proof = New-LegacyFailure $legacy
$virtual = Join-Path $legacy 'virtual-candidate.json'
& "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $legacy `
    -LegacyVirtualLivenessEvidence $proof -CandidateVirtualResult $virtual | Out-Null
if (@(Get-Content (Join-Path $legacy 'baseline.json') -Raw | ConvertFrom-Json).Count -ne 20 -or
        @(Get-Content (Join-Path $legacy 'candidate.json') -Raw | ConvertFrom-Json).Count -ne 22) { throw 'Liveness exception changed the wrong cases.' }

$legacyRegression = New-Fixture 'legacy-regression'
$proof = New-LegacyFailure $legacyRegression
$path = Join-Path $legacyRegression 'hit-candidate.json'
$items = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
$items[0] = New-JmhTestRow -Mode 'thrpt' -Score 80 -Benchmark $items[0].benchmark
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $items -Depth 20))
Write-JmhTestInstrumentation $path $items
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $legacyRegression `
    -LegacyVirtualLivenessEvidence $proof -CandidateVirtualResult (Join-Path $legacyRegression 'virtual-candidate.json') } `
    'legacy exception preserves numeric regressions' '*throughput regression -20*'
if ((Get-Content (Join-Path $legacyRegression 'comparison.md') -Raw) -notmatch 'Legacy virtual baseline: UNAVAILABLE') {
    throw 'Failed numeric comparison lost its legacy liveness scope note.'
}

$unpaired = New-Fixture 'missing-candidate-virtual'
$proof = New-LegacyFailure $unpaired
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $unpaired `
    -LegacyVirtualLivenessEvidence $proof } 'missing candidate liveness' 'Legacy liveness evidence and a complete candidate virtual result*'

$tampered = New-Fixture 'changed-proof'
$proof = New-LegacyFailure $tampered
'changed' | Set-Content (Join-Path $tampered 'legacy-proof/threads.json') -Encoding utf8
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $tampered `
    -LegacyVirtualLivenessEvidence $proof -CandidateVirtualResult (Join-Path $tampered 'virtual-candidate.json') } `
    'tampered liveness evidence' 'Legacy liveness evidence is missing, oversized or changed.'

$unrelated = New-Fixture 'legacy-still-rejects-missing-case'
$proof = New-LegacyFailure $unrelated
$path = Join-Path $unrelated 'miss64-candidate.json'
@(Get-Content $path -Raw | ConvertFrom-Json)[0] | ConvertTo-Json -Depth 20 | Set-Content $path -Encoding utf8
Write-JmhTestInstrumentation $path @(Get-Content $path -Raw | ConvertFrom-Json)
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $unrelated `
    -LegacyVirtualLivenessEvidence $proof -CandidateVirtualResult (Join-Path $unrelated 'virtual-candidate.json') } `
    'unrelated missing case' 'Incomplete paired scenario:*'
foreach ($defect in @('raw-forks', 'confidence', 'histogram', 'conflicting-jvm')) {
    $invalid = New-Fixture "invalid-$defect"
    $path = Join-Path $invalid 'hit-candidate.json'
    $items = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
    $expected = switch ($defect) {
        'raw-forks' { $items[0].primaryMetric.rawData = @(); 'JMH raw fork shape mismatch:*' }
        'confidence' { $items[0].primaryMetric.scoreConfidence = @(101, 99); 'JMH confidence interval must be ordered*' }
        'histogram' { $items[1].primaryMetric.scorePercentiles.'99.0' = 99; 'JMH histogram percentile mismatch:*' }
        'conflicting-jvm' { $items[0].jvmArgs += '-Xmx1g'; 'Conflicting duplicate JVM setting:*' }
    }
    [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $items -Depth 20))
    Write-JmhTestInstrumentation $path $items
    Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $invalid } $defect $expected
    if ((Test-Path (Join-Path $invalid 'baseline.json')) -or (Test-Path (Join-Path $invalid 'candidate.json'))) {
        throw "Invalid $defect evidence published a final merged report."
    }
}
$legacyOrder = New-Fixture 'legacy-virtual-argument-order'
$proof = New-LegacyFailure $legacyOrder
$virtual = Join-Path $legacyOrder 'virtual-candidate.json'
$items = @(Get-Content -LiteralPath $virtual -Raw | ConvertFrom-Json)
foreach ($item in $items) {
    $argsCopy = @($item.jvmArgs)
    $item.jvmArgs = @($argsCopy[1], $argsCopy[0]) + @($argsCopy | Select-Object -Skip 2)
}
[IO.File]::WriteAllText($virtual, (ConvertTo-Json -InputObject $items -Depth 20))
Write-JmhTestInstrumentation $virtual $items
Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $legacyOrder `
    -LegacyVirtualLivenessEvidence $proof -CandidateVirtualResult $virtual } `
    'legacy virtual argument order' 'Candidate virtual JVM settings mismatch.'
foreach ($defect in @('missing-instrumentation', 'changed-instrumentation')) {
    $root = New-Fixture $defect
    $path = Join-Path $root 'hit-candidate.json.instrumentation'
    $expected = 'Missing instrumentation sidecar:*'
    if ($defect -eq 'missing-instrumentation') { [IO.File]::Delete($path) } else {
        $document = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        $document.cases[0].contract.sampler.intervalMs = 500
        [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $document -Depth 20))
        $expected = 'Instrumentation settings mismatch:*'
    }
    Must-Reject { & "$PSScriptRoot/merge-paired-benchmarks.ps1" -Directory $root } $defect $expected
    if ((Test-Path (Join-Path $root 'baseline.json')) -or (Test-Path (Join-Path $root 'candidate.json'))) {
        throw 'Invalid instrumentation published merged evidence.'
    }
}
Write-Host 'Synthetic merge contracts passed: bound instrumentation preserved/rebound, raw evidence rejection before publication, unchanged regression gates, ordered settings and narrow legacy liveness exception. Not live performance evidence.'
