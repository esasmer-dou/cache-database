#requires -Version 7.2
param([string]$Directory = (Join-Path ([IO.Path]::GetTempPath()) "cachedb-jmh-validation-$([guid]::NewGuid().ToString('N'))"))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/jmh-evidence-validation.ps1"
. "$PSScriptRoot/jmh-evidence-test-fixtures.ps1"
if (Test-Path -LiteralPath $Directory) { throw 'Self-test requires a new disposable directory.' }
$null = New-Item -ItemType Directory -Path $Directory
$passed = [Collections.Generic.List[string]]::new()
function Pass([string]$Name) { $passed.Add($Name); Write-Output "PASS $Name" }
function Reject([string]$Name, [scriptblock]$Mutation, [string]$Expected, [string]$Mode = 'thrpt') {
    $row = New-JmhTestRow -Mode $Mode
    & $Mutation $row
    $rejected = $false
    try { Assert-JmhEvidenceRow $row } catch {
        if ($_.Exception.Message -notlike $Expected) { throw "Wrong rejection for ${Name}: $($_.Exception.Message)" }
        $rejected = $true
    }
    if (!$rejected) { throw "Invalid synthetic evidence accepted: $Name" }
    Pass $Name
}
foreach ($mode in @('thrpt', 'sample')) {
    Assert-JmhEvidenceRow (New-JmhTestRow -Mode $mode)
    Pass "valid-$mode"
}
$row = New-JmhTestRow
$gc = $row.secondaryMetrics.'gc.alloc.rate.norm'
$gc.score = 0; $gc.scoreConfidence = @(0, 0)
foreach ($fork in $gc.rawData) { for ($i = 0; $i -lt $fork.Count; $i++) { $fork[$i] = 0 } }
Assert-JmhEvidenceRow $row
Pass 'zero-allocation-valid'
$row = New-JmhTestRow
$row.primaryMetric.scoreError = 200; $row.primaryMetric.scoreConfidence = @(-100, 300)
Assert-JmhEvidenceRow $row
Pass 'negative-lower-confidence-bound-valid'
Reject 'missing-primary-score' { param($r) $r.primaryMetric.PSObject.Properties.Remove('score') } 'JMH evidence missing property:*'
Reject 'null-score' { param($r) $r.primaryMetric.score = $null } 'JMH evidence missing property:*'
foreach ($value in @('100', $true)) {
    Reject "nonnumeric-score-$value" { param($r) $r.primaryMetric.score = $value } 'JMH evidence requires a JSON number:*'
}
foreach ($value in @([double]::NaN, [double]::PositiveInfinity, [double]::NegativeInfinity, 0, -1)) {
    Reject "invalid-score-$value" { param($r) $r.primaryMetric.score = $value } 'JMH evidence requires a finite value in range:*'
}
Reject 'negative-error' { param($r) $r.primaryMetric.scoreError = -1 } 'JMH evidence requires a finite value in range:*'
Reject 'nonfinite-error' { param($r) $r.primaryMetric.scoreError = [double]::NaN } 'JMH evidence requires a finite value in range:*'
Reject 'string-error' { param($r) $r.primaryMetric.scoreError = '0' } 'JMH evidence requires a JSON number:*'
Reject 'missing-confidence' { param($r) $r.primaryMetric.PSObject.Properties.Remove('scoreConfidence') } 'JMH evidence missing property:*'
Reject 'one-confidence-bound' { param($r) $r.primaryMetric.scoreConfidence = @(100) } 'JMH confidence interval requires two bounds:*'
Reject 'string-confidence-bound' { param($r) $r.primaryMetric.scoreConfidence = @('100', 100) } 'JMH evidence requires a JSON number:*'
Reject 'nonfinite-confidence' { param($r) $r.primaryMetric.scoreConfidence = @([double]::NegativeInfinity, 100) } 'JMH evidence requires a finite value in range:*'
Reject 'reversed-confidence' { param($r) $r.primaryMetric.scoreConfidence = @(101, 99) } 'JMH confidence interval must be ordered*'
Reject 'confidence-excludes-score' { param($r) $r.primaryMetric.scoreConfidence = @(1, 2) } 'JMH confidence interval must be ordered*'
Reject 'confidence-error-incoherent' { param($r) $r.primaryMetric.scoreConfidence = @(99, 101) } 'JMH evidence summary mismatch:*'
Reject 'missing-raw-throughput' { param($r) $r.primaryMetric.PSObject.Properties.Remove('rawData') } 'JMH evidence missing property:*'
Reject 'missing-raw-fork' { param($r) $r.primaryMetric.rawData = @($r.primaryMetric.rawData | Select-Object -First 2) } 'JMH raw fork shape mismatch:*'
Reject 'extra-raw-fork' { param($r) $r.primaryMetric.rawData += ,$r.primaryMetric.rawData[0] } 'JMH raw fork shape mismatch:*'
Reject 'missing-iteration' { param($r) $r.primaryMetric.rawData[0] = @(100, 100, 100, 100) } 'JMH raw iteration shape mismatch:*'
Reject 'extra-iteration' { param($r) $r.primaryMetric.rawData[0] += 100 } 'JMH raw iteration shape mismatch:*'
Reject 'scalar-fork' { param($r) $r.primaryMetric.rawData[0] = 100 } 'JMH raw iteration shape mismatch:*'
Reject 'nonfinite-raw-throughput' { param($r) $r.primaryMetric.rawData[0][0] = [double]::NaN } 'JMH evidence requires a finite value in range:*'
Reject 'zero-raw-throughput' { param($r) $r.primaryMetric.rawData[0][0] = 0 } 'JMH evidence requires a finite value in range:*'
Reject 'forged-throughput-summary' {
    param($r) $r.primaryMetric.score = 50; $r.primaryMetric.scoreConfidence = @(50, 50)
} 'JMH evidence summary mismatch:*'
Reject 'declared-forks-do-not-match-raw' { param($r) $r.forks = 4 } 'JMH raw fork shape mismatch:*'
Reject 'declared-iterations-do-not-match-raw' { param($r) $r.measurementIterations = 6 } 'JMH raw iteration shape mismatch:*'
Reject 'insufficient-warmup' { param($r) $r.warmupIterations = 4 } 'JMH evidence integer outside*'
Reject 'fractional-forks' { param($r) $r.forks = 3.5 } 'JMH evidence integer outside*'
Reject 'incorrect-throughput-unit' { param($r) $r.primaryMetric.scoreUnit = 'ops/s' } 'Unexpected JMH metric unit:*'
Reject 'incorrect-latency-unit' { param($r) $r.primaryMetric.scoreUnit = 'ns/op' } 'Unexpected JMH metric unit:*' 'sample'
Reject 'sample-histogram-fork-missing' { param($r) $r.primaryMetric.rawDataHistogram = @() } 'JMH histogram requires three raw forks.' 'sample'
Reject 'sample-histogram-iteration-missing' { param($r) $r.primaryMetric.rawDataHistogram[0] = @() } 'JMH histogram requires five iterations per fork.' 'sample'
Reject 'forged-p99' { param($r) $r.primaryMetric.scorePercentiles.'99.0' = 99 } 'JMH histogram percentile mismatch:*' 'sample'
Reject 'fractional-histogram-count' { param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1] = 1.5 } 'JMH histogram count must be a positive int32*' 'sample'
Reject 'forged-sample-mean' {
    param($r) $r.primaryMetric.score = 99; $r.primaryMetric.scoreConfidence = @(99, 99)
} 'JMH evidence summary mismatch:*' 'sample'
Reject 'missing-gc' { param($r) $r.secondaryMetrics.PSObject.Properties.Remove('gc.alloc.rate.norm') } 'JMH evidence missing property:*'
Reject 'nonfinite-gc' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.score = [double]::NaN } 'JMH evidence requires a finite value in range:*'
Reject 'negative-gc' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.score = -1 } 'JMH evidence requires a finite value in range:*'
Reject 'nonfinite-gc-error' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.scoreError = [double]::NaN } 'JMH evidence requires a finite value in range:*'
Reject 'nonfinite-gc-confidence' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.scoreConfidence = @(10, [double]::PositiveInfinity) } 'JMH evidence requires a finite value in range:*'
Reject 'forged-gc-summary' {
    param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.score = 9; $r.secondaryMetrics.'gc.alloc.rate.norm'.scoreConfidence = @(9, 9)
} 'JMH evidence summary mismatch:*'
Reject 'wrong-gc-unit' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.scoreUnit = 'MB/sec' } 'Unexpected JMH metric unit:*'
Reject 'missing-gc-raw' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.rawData = @() } 'JMH raw fork shape mismatch:*'
Reject 'nonfinite-gc-raw' { param($r) $r.secondaryMetrics.'gc.alloc.rate.norm'.rawData[0][0] = [double]::PositiveInfinity } 'JMH evidence requires a finite value in range:*'
Reject 'wrong-jmh-version' { param($r) $r.jmhVersion = '1.36' } 'Unsupported JMH evidence version.'
foreach ($option in @('-Xmx1g', '-Xms1g', '-XX:ActiveProcessorCount=1', '-Dcachedb.bench.redis=other:6379')) {
    Reject "conflicting-option-$option" { param($r) $r.jvmArgs += $option } 'Conflicting duplicate JVM setting:*'
}
Reject 'conflicting-boolean-xx' { param($r) $r.jvmArgs += @('-XX:+UseG1GC', '-XX:-UseG1GC') } 'Conflicting duplicate JVM setting:*'
Reject 'conflicting-execution-modes' { param($r) $r.jvmArgs += @('-Xint', '-Xcomp') } 'Conflicting duplicate JVM setting:*'
Reject 'conflicting-ignored-property' { param($r) $r.jvmArgs += @('-Dcachedb.bench.output=a', '-Dcachedb.bench.output=b') } 'Conflicting duplicate JVM setting:*'
$row = New-JmhTestRow
$row.jvmArgs += @('-Dfixture', '-Dfixture=', '-DCase=A', '-Dcase=B', $row.jvmArgs[3])
Assert-JmhEvidenceRow $row
Pass 'same-value-duplicates-and-case-sensitive-property-names-valid'

$matrix = [Collections.Generic.List[object]]::new()
$cases = @('l1HitFindById/1/-', 'findAllMixed100/1/-', 'saveSingle/1/-', 'l1MissFindByIdVirtual1000/1000/-',
    'l1MissFindById/1/-', 'l1MissFindById/16/-', 'l1MissFindById/64/-', 'aggregateCount10k/1/false',
    'aggregateCount10k/1/true', 'facet10k20Groups/1/false', 'facet10k20Groups/1/true')
foreach ($mode in @('thrpt', 'sample')) {
    foreach ($case in $cases) {
        $parts = $case.Split('/')
        $row = New-JmhTestRow -Mode $mode -Threads ([int]$parts[1]) `
            -Benchmark "com.reactor.cachedb.benchmarks.redis.RedisRepositoryBenchmark.$($parts[0])"
        if ($parts[2] -ne '-') {
            $row | Add-Member -NotePropertyName params -NotePropertyValue ([pscustomobject]@{ tenant = $parts[2] })
        }
        $matrix.Add($row)
    }
}
$path = Join-Path $Directory 'valid-matrix.json'
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $matrix.ToArray() -Depth 20))
$before = (Get-FileHash -LiteralPath $path).Hash
& "$PSScriptRoot/check-jmh-evidence.ps1" -Path $path
if ((Get-FileHash -LiteralPath $path).Hash -cne $before) { throw 'Checker changed its input evidence.' }
Pass 'full-matrix-accepted-without-input-mutation'
foreach ($index in @(0, $cases.Count)) {
    foreach ($field in @('warmupTime', 'measurementTime')) {
        foreach ($duration in @('1 s', '0 s', '3 ms', 'not-duration', "3 s`n", 3)) {
            $matrix[$index].$field = $duration
            $invalidDuration = Join-Path $Directory 'invalid-duration-matrix.json'
            [IO.File]::WriteAllText($invalidDuration, (ConvertTo-Json -InputObject $matrix.ToArray() -Depth 20))
            $durationHash = (Get-FileHash -LiteralPath $invalidDuration).Hash
            $rejected = $false
            $expected = if ($duration -is [string]) {
                "Strict JMH acceptance requires 3 s for ${field}:*"
            } else { "JMH evidence requires a nonempty string: $field" }
            try { & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $invalidDuration } catch {
                if ($_.Exception.Message -notlike $expected) { throw }
                $rejected = $true
            }
            if (!$rejected) { throw "Strict matrix accepted invalid ${field}: $duration" }
            if ((Get-FileHash -LiteralPath $invalidDuration).Hash -cne $durationHash) { throw 'Checker changed rejected evidence.' }
            $matrix[$index].$field = '3 s'
            Pass "strict-$($matrix[$index].mode)-$field-rejects-$($duration.ToString().Replace("`n", '<LF>'))"
        }
    }
}
foreach ($item in $matrix) { $item.warmupTime = '1 s'; $item.measurementTime = '1 s' }
$retained = Join-Path $Directory 'retained-duration-matrix.json'
[IO.File]::WriteAllText($retained, (ConvertTo-Json -InputObject $matrix.ToArray() -Depth 20))
$retainedHash = (Get-FileHash -LiteralPath $retained).Hash
$formatOutput = @(& "$PSScriptRoot/check-jmh-evidence.ps1" -Path $retained -RetainedBaselineFormatOnly 6>&1) -join "`n"
if ($formatOutput -notmatch 'JMH_FORMAT_ONLY_NOT_ACCEPTANCE' -or $formatOutput -match 'JMH acceptance matrix verified') {
    throw 'Retained format-only validation must not claim strict acceptance.'
}
if ((Get-FileHash -LiteralPath $retained).Hash -cne $retainedHash) { throw 'Format-only checker changed retained evidence.' }
$rejected = $false
try { & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $retained } catch {
    if ($_.Exception.Message -notlike 'Strict JMH acceptance requires 3 s for warmupTime:*') { throw }
    $rejected = $true
}
if (!$rejected) { throw 'Strict matrix accepted retained one-second durations.' }
Pass 'retained-one-second-matrix-format-only-not-strict-acceptance'
foreach ($item in $matrix) { $item.warmupTime = '3 s'; $item.measurementTime = '3 s' }
$matrix[0].primaryMetric.rawData = @()
$invalid = Join-Path $Directory 'invalid-matrix.json'
[IO.File]::WriteAllText($invalid, (ConvertTo-Json -InputObject $matrix.ToArray() -Depth 20))
$rejected = $false
try { & "$PSScriptRoot/check-jmh-evidence.ps1" -Path $invalid } catch {
    if ($_.Exception.Message -notlike 'JMH raw fork shape mismatch:*') { throw }
    $rejected = $true
}
if (!$rejected) { throw 'Matrix checker accepted declared forks without raw evidence.' }
Pass 'matrix-checker-rejects-metadata-only-evidence'
& "$PSScriptRoot/compare-benchmarks.ps1" -SelfTest
Pass 'comparator-contracts'
Write-Output "JMH_EVIDENCE_CONTROLS_PASS=$($passed.Count); SYNTHETIC_FORMAT_ONLY_NOT_PERFORMANCE_ACCEPTANCE; DIRECTORY=$Directory"
