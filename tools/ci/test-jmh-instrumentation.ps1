param([string]$Directory = (Join-Path ([IO.Path]::GetTempPath()) "cachedb-instrumentation-$([guid]::NewGuid().ToString('N'))"))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/jmh-instrumentation.ps1"
. "$PSScriptRoot/jmh-evidence-test-fixtures.ps1"
if (Test-Path -LiteralPath $Directory) { throw 'Instrumentation controls require a new disposable directory.' }
$null = New-Item -ItemType Directory -Path $Directory
$checks = [Collections.Generic.List[string]]::new()

function New-Pair([string]$Name) {
    $root = Join-Path $Directory $Name
    $null = New-Item -ItemType Directory -Path $root
    foreach ($side in @('baseline', 'candidate')) {
        $path = Join-Path $root "$side.json"
        $rows = @((New-JmhTestRow -Mode thrpt -Score 100 -Benchmark 'fixture.Case'),
            (New-JmhTestRow -Mode sample -Score 100 -Benchmark 'fixture.Case'))
        [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $rows -Depth 20))
        Write-JmhTestInstrumentation $path $rows
    }
    return $root
}

function Compare-Pair([string]$Root) {
    & "$PSScriptRoot/compare-benchmarks.ps1" -Baseline (Join-Path $Root 'baseline.json') -Current (Join-Path $Root 'candidate.json')
}

function Must-Reject([scriptblock]$Action, [string]$Name, [string]$Expected) {
    $rejected = $false
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notlike $Expected) { throw "Wrong instrumentation rejection for ${Name}: $($_.Exception.Message)" }
        $rejected = $true
    }
    if (!$rejected) { throw "Instrumentation accepted invalid evidence: $Name" }
    $checks.Add($Name)
}

$valid = New-Pair 'valid'
Compare-Pair $valid | Out-Null
$checks.Add('matching-bound-contracts')
$identity = New-JmhTestRow -Mode thrpt -Score 100 -Benchmark 'fixture.Case'
$identity | Add-Member -NotePropertyName params -NotePropertyValue ([pscustomobject]@{ tenant = 'true'; cache = 'false' })
if ((Get-JmhCaseKey $identity) -cne 'fixture.Case|thrpt|threads=1|cache=false,tenant=true') {
    throw 'Instrumentation/comparator fixture case-key contract changed.'
}
$checks.Add('shared-fixture-case-key')

foreach ($defect in @('missing', 'profiler', 'profiler-order', 'helper-hash', 'interval', 'redis-target', 'raw-hash',
        'missing-case', 'duplicate-case', 'extra-case', 'missing-helper', 'unknown-sampler-setting')) {
    $root = New-Pair $defect
    $raw = Join-Path $root 'candidate.json'
    $path = "$raw.instrumentation"
    $document = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $expected = 'Instrumentation settings mismatch:*'
    switch ($defect) {
        missing { [IO.File]::Delete($path); $expected = 'Missing instrumentation sidecar:*' }
        profiler { $document.cases[0].contract.controllerProfilers[1] = 'gc:churn=true' }
        'profiler-order' { $document.cases[0].contract.controllerProfilers = @('gc', $document.cases[0].contract.controllerProfilers[0]) }
        'helper-hash' { $document.cases[0].contract.helpers[0].sha256 = 'b' * 64 }
        interval { $document.cases[0].contract.sampler.intervalMs = 500 }
        'redis-target' { $document.cases[0].contract.sampler.redisTarget = 'EXPLICIT_LOCAL_PID_TARGET_OPERATOR_ENDPOINT_BINDING_NOT_VERIFIED' }
        'raw-hash' { [IO.File]::AppendAllText($raw, "`n"); $expected = 'Instrumentation raw SHA256 mismatch*' }
        'missing-case' { $document.cases = @($document.cases[0]); $expected = 'Missing or extra instrumentation cases.' }
        'duplicate-case' { $document.cases[1].key = $document.cases[0].key; $expected = 'Invalid or duplicate instrumentation case key.' }
        'extra-case' { $document.cases += $document.cases[0]; $expected = 'Invalid or duplicate instrumentation case key.' }
        'missing-helper' { $document.cases[0].contract.helpers = @(); $expected = 'Instrumentation helper roster*' }
        'unknown-sampler-setting' {
            $document.cases[0].contract.sampler | Add-Member -NotePropertyName unexpected -NotePropertyValue 1
            $expected = 'Invalid instrumentation fields: sampler'
        }
    }
    if ($defect -ne 'missing') { [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $document -Depth 20)) }
    Must-Reject { Compare-Pair $root } $defect $expected
}

$extra = New-Pair 'extra-candidate-virtual'
$path = Join-Path $extra 'candidate.json'
$rows = @(Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
$rows += New-JmhTestRow -Mode thrpt -Score 100 -Benchmark 'fixture.Virtual' -Threads 1000
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $rows -Depth 20))
Write-JmhTestInstrumentation $path $rows
Compare-Pair $extra | Out-Null
$checks.Add('legacy-extra-candidate-contract-required-and-valid')
$document = Get-Content -LiteralPath "$path.instrumentation" -Raw | ConvertFrom-Json
$document.cases[2].contract.helpers[0].sha256 = 'not-a-hash'
[IO.File]::WriteAllText("$path.instrumentation", (ConvertTo-Json -InputObject $document -Depth 20))
Must-Reject { Compare-Pair $extra } 'legacy-extra-candidate-still-validated' 'Invalid or duplicate instrumentation helper identity/hash.'

$rows = @(Get-Content -LiteralPath (Join-Path $valid 'baseline.json') -Raw | ConvertFrom-Json)
$contracts = Read-JmhInstrumentation (Join-Path $valid 'baseline.json') $rows
Must-Reject { Write-JmhInstrumentation (Join-Path $valid 'baseline.json') $rows $contracts } 'sidecar-overwrite' '*'
[IO.File]::WriteAllText((Join-Path $Directory 'summary.json'), (ConvertTo-Json -InputObject @{
    result = 'PASS'; checks = $checks.ToArray(); acceptance = 'SYNTHETIC_ONLY_NOT_PERFORMANCE_ACCEPTANCE'
} -Depth 8))
Write-Output "JMH_INSTRUMENTATION_CONTROLS_PASS=$($checks.Count); SYNTHETIC_ONLY"
