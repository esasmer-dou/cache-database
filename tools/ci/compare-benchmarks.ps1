param(
    [string]$Baseline,
    [string]$Current,
    [string]$Summary,
    [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/jmh-evidence-validation.ps1"
. "$PSScriptRoot/jmh-instrumentation.ps1"

function Get-CaseKey($Result) {
    return Get-JmhCaseKey $Result
}

function Get-PositiveNumber($Value, [string]$Label) {
    $number = 0.0
    if (![double]::TryParse([Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture), [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or
            [double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -le 0) {
        throw "Invalid benchmark value: $Label"
    }
    return $number
}

function Assert-MatchingSettings($Before, $After, [string]$Key) {
    foreach ($name in @('jmhVersion', 'jdkVersion', 'vmName', 'vmVersion', 'forks', 'warmupIterations',
            'warmupTime', 'warmupBatchSize', 'measurementIterations', 'measurementTime', 'measurementBatchSize')) {
        if ($Before.PSObject.Properties.Name -notcontains $name -or $After.PSObject.Properties.Name -notcontains $name -or
                [string]$Before.$name -cne [string]$After.$name) {
            throw "Benchmark settings mismatch or missing: $name / $Key"
        }
    }
    if ($Before.PSObject.Properties.Name -notcontains 'jvmArgs' -or $After.PSObject.Properties.Name -notcontains 'jvmArgs') {
        throw "Benchmark settings missing: jvmArgs / $Key"
    }
    # Output paths select evidence files, not the measured implementation or resources.
    $old = @(Get-JmhComparableArguments $Before.jvmArgs)
    $new = @(Get-JmhComparableArguments $After.jvmArgs)
    if (($old -join "`n") -cne ($new -join "`n")) { throw "Benchmark settings mismatch: jvmArgs / $Key" }
}

function Compare-Results([object[]]$Before, [object[]]$After, $BeforeInstrumentation, $AfterInstrumentation) {
    if ($Before.Count -eq 0 -or $After.Count -eq 0) { throw 'Empty benchmark report.' }
    Assert-JmhInstrumentationCoverage $Before $BeforeInstrumentation
    Assert-JmhInstrumentationCoverage $After $AfterInstrumentation
    $index = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($item in $After) {
        Assert-JmhEvidenceRow $item
        $key = Get-CaseKey $item
        if ($index.ContainsKey($key)) { throw "Duplicate current case: $key" }
        $index[$key] = $item
    }
    $seen = @{}
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('Point-estimate regression gate only: PASS is not confidence-backed non-regression or capacity acceptance.')
    $lines.Add('Thresholds unchanged: throughput decrease >10% or sampled p99 increase >15% fails. Confidence intervals describe the mean score, not p99 uncertainty.')
    $lines.Add('')
    $lines.Add('| Case | Unit | Baseline | Current | Change | Gate |')
    $lines.Add('| --- | --- | ---: | ---: | ---: | --- |')
    $failures = [Collections.Generic.List[string]]::new()
    foreach ($old in $Before) {
        Assert-JmhEvidenceRow $old
        $key = Get-CaseKey $old
        if ($seen.ContainsKey($key)) { throw "Duplicate baseline case: $key" }
        $seen[$key] = $true
        if (!$index.ContainsKey($key)) { throw "Missing current benchmark case: $key" }
        $new = $index[$key]
        Assert-MatchingSettings $old $new $key
        Assert-MatchingJmhInstrumentation $BeforeInstrumentation[$key] $AfterInstrumentation[$key] $key
        if ($old.primaryMetric.scoreUnit -ne $new.primaryMetric.scoreUnit) { throw "Unit mismatch: $key" }
        if ($old.mode -notin @('thrpt', 'sample')) { throw "Unsupported comparison mode: $($old.mode)" }
        if ($old.mode -eq 'thrpt') {
            $previous = Get-PositiveNumber $old.primaryMetric.score $key
            $next = Get-PositiveNumber $new.primaryMetric.score $key
            $failed = $next / $previous -lt 0.90
            $metric = 'throughput'
        } else {
            $previous = Get-PositiveNumber $old.primaryMetric.scorePercentiles.'99.0' $key
            $next = Get-PositiveNumber $new.primaryMetric.scorePercentiles.'99.0' $key
            $failed = $next / $previous -gt 1.15
            $metric = 'p99'
        }
        $delta = ($next / $previous - 1) * 100
        $gate = if ($failed) { 'FAIL' } else { 'PASS' }
        $description = $key.Replace('|', ' / ')
        $lines.Add(('| {0} ({1}) | {2} | {3:G6} | {4:G6} | {5:+0.00;-0.00;0.00}% | {6} |' -f
                $description, $metric, $old.primaryMetric.scoreUnit, $previous, $next, $delta, $gate))
        if ($failed) { $failures.Add("${key}: $metric regression $delta percent") }
    }
    return [pscustomobject]@{ Markdown = $lines -join "`n"; Failures = $failures.ToArray() }
}

if ($SelfTest) {
    . "$PSScriptRoot/jmh-evidence-test-fixtures.ps1"
    function Compare-Fixtures([object[]]$Before, [object[]]$After) {
        return Compare-Results $Before $After (New-JmhTestInstrumentation $Before) (New-JmhTestInstrumentation $After)
    }
    function Fixture([string]$Mode, [double]$Score, [double]$P99 = 100) {
        $value = if ($Mode -ceq 'sample') { $P99 } else { $Score }
        return (New-JmhTestRow -Mode $Mode -Score $value)
    }
    if (@((Compare-Fixtures @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 80))).Failures).Count -ne 1) {
        throw 'Self-test failed: 20 percent throughput regression was not detected.'
    }
    if (@((Compare-Fixtures @((Fixture 'sample' 1 100)) @((Fixture 'sample' 1 120))).Failures).Count -ne 1) {
        throw 'Self-test failed: 20 percent p99 regression was not detected.'
    }
    if (@((Compare-Fixtures @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 95))).Failures).Count -ne 0) {
        throw 'Self-test failed: acceptable drift rejected.'
    }
    if (@((Compare-Fixtures @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 90))).Failures).Count -ne 0 -or
            @((Compare-Fixtures @((Fixture 'sample' 1 100)) @((Fixture 'sample' 1 115))).Failures).Count -ne 0) {
        throw 'Self-test failed: exact threshold must not be rejected.'
    }
    $rejectedMissing = $false
    try { Compare-Fixtures @((Fixture 'thrpt' 100)) @((Fixture 'sample' 1)) | Out-Null }
    catch { $rejectedMissing = $true }
    if (!$rejectedMissing) { throw 'Self-test failed: missing case accepted.' }
    $mismatch = Fixture 'thrpt' 100
    $mismatch.warmupTime = '1 s'
    $rejectedMismatch = $false
    try { Compare-Fixtures @((Fixture 'thrpt' 100)) @($mismatch) | Out-Null }
    catch { $rejectedMismatch = $_.Exception.Message -like 'Benchmark settings mismatch or missing: warmupTime*' }
    if (!$rejectedMismatch) { throw 'Self-test failed: incompatible measurement settings accepted.' }
    function Reject-Comparison([string]$Name, [scriptblock]$Mutation, [string]$Expected) {
        $before = Fixture 'thrpt' 100
        $after = Fixture 'thrpt' 100
        & $Mutation $before $after
        $rejected = $false
        try { Compare-Fixtures @($before) @($after) | Out-Null } catch {
            if ($_.Exception.Message -notlike $Expected) { throw "Wrong rejection for ${Name}: $($_.Exception.Message)" }
            $rejected = $true
        }
        if (!$rejected) { throw "Self-test failed: $Name accepted." }
    }
    Reject-Comparison 'reordered JVM options' {
        param($b, $a) $a.jvmArgs = @($a.jvmArgs[1], $a.jvmArgs[0], $a.jvmArgs[2], $a.jvmArgs[3])
    } 'Benchmark settings mismatch: jvmArgs*'
    Reject-Comparison 'conflicting heaps on both sides' {
        param($b, $a) $b.jvmArgs += '-Xmx1g'; $a.jvmArgs = @('-Xmx1g') + $a.jvmArgs
    } 'Conflicting duplicate JVM setting: X:mx'
    Reject-Comparison 'conflicting properties on both sides' {
        param($b, $a) $b.jvmArgs += @('-Dfixture=A', '-Dfixture=B'); $a.jvmArgs += @('-Dfixture=B', '-Dfixture=A')
    } 'Conflicting duplicate JVM setting: D:fixture'
    Reject-Comparison 'same-valued duplicate removed' {
        param($b, $a) $a.jvmArgs += $a.jvmArgs[3]
    } 'Benchmark settings mismatch: jvmArgs*'
    Reject-Comparison 'case-sensitive property value mismatch' {
        param($b, $a) $b.jvmArgs += '-Dfixture=A'; $a.jvmArgs += '-Dfixture=a'
    } 'Benchmark settings mismatch: jvmArgs*'
    Reject-Comparison 'invalid raw evidence in current row' {
        param($b, $a) $a.primaryMetric.rawData = @()
    } 'JMH raw fork shape mismatch:*'
    Reject-Comparison 'matching but incorrect units' {
        param($b, $a) $b.primaryMetric.scoreUnit = 'ops/s'; $a.primaryMetric.scoreUnit = 'ops/s'
    } 'Unexpected JMH metric unit:*'
    $extra = Fixture 'thrpt' 100
    $extra.benchmark = 'extra'
    $extra.primaryMetric.rawData = @()
    $rejectedExtra = $false
    try { Compare-Fixtures @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 100), $extra) | Out-Null } catch {
        if ($_.Exception.Message -notlike 'JMH raw fork shape mismatch:*') { throw }
        $rejectedExtra = $true
    }
    if (!$rejectedExtra) { throw 'Self-test failed: invalid extra current evidence accepted.' }
    $before = Fixture 'thrpt' 100
    $after = Fixture 'thrpt' 100
    $before.jvmArgs += @('-Dcachedb.bench.output=/baseline.json', '-Dcachedb.bench.case=baseline')
    $after.jvmArgs += @('-Dcachedb.bench.output=/candidate.json', '-Dcachedb.bench.case=candidate')
    if (@((Compare-Fixtures @($before) @($after)).Failures).Count -ne 0) {
        throw 'Self-test failed: permitted evidence selectors were compared.'
    }
    $before = Fixture 'thrpt' 100
    $after = Fixture 'thrpt' 100
    $before.jvmArgs += $before.jvmArgs[3]
    $after.jvmArgs += $after.jvmArgs[3]
    $rendered = Compare-Fixtures @($before) @($after)
    if ($rendered.Failures.Count -ne 0 -or $rendered.Markdown -notmatch '\| Unit \|' -or
            $rendered.Markdown -notmatch '\| ops/us \|' -or $rendered.Markdown -notmatch 'Point-estimate regression gate only' -or
            $rendered.Markdown -notmatch 'not p99 uncertainty') {
        throw 'Self-test failed: duplicate-compatible settings, units or point-estimate semantics lost.'
    }
    $renderedSample = Compare-Fixtures @((Fixture 'sample' 1)) @((Fixture 'sample' 1))
    if ($renderedSample.Markdown -notmatch '\| us/op \|') { throw 'Self-test failed: sampled latency unit missing.' }
    Write-Host 'Benchmark gate self-test passed: unchanged thresholds, complete evidence, ordered/conflict-free JVM settings, units and point-estimate semantics. Synthetic only.'
    return
}
if (!$Baseline -or !$Current) { throw 'Specify -Baseline and -Current, or -SelfTest.' }
function Read-Report([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt 64MB) {
        throw 'Benchmark report must be a nonempty file, at most 64 MiB.'
    }
    $items = @(Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json)
    if ($items.Count -gt 256) { throw 'Benchmark report exceeds 256 cases.' }
    return $items
}
$before = @(Read-Report $Baseline)
$after = @(Read-Report $Current)
$result = Compare-Results $before $after (Read-JmhInstrumentation $Baseline $before) (Read-JmhInstrumentation $Current $after)
if ($Summary) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($Summary), $result.Markdown, [Text.UTF8Encoding]::new($false)) }
Write-Output $result.Markdown
if ($result.Failures.Count -gt 0) { throw ($result.Failures -join "`n") }
