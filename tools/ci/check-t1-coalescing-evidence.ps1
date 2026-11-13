param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$CandidateJar,
    [ValidateSet(1, 2)][int]$ProcessorCount = 2,
    [string]$ExpectedBaselineSha256 = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'jmh-histogram-consistency.ps1')
$root = (Get-Item -LiteralPath $Path).FullName
if (!(Test-Path -LiteralPath $root -PathType Container)) { throw 'Evidence path must be a directory.' }
if (Test-Path -LiteralPath (Join-Path $root 'FAILED.txt')) { throw 'Failed/incomplete attempt cannot qualify.' }
if (!(Test-Path -LiteralPath (Join-Path $root 'COMPLETE.txt'))) { throw 'Completed-run marker is missing.' }
$script:remainingBytes = 256MB
function Read-Bounded([string]$Name, [long]$Limit = 16MB) {
    $file = Get-Item -LiteralPath (Join-Path $root $Name)
    if ($file.PSIsContainer -or $file.Length -lt 1 -or $file.Length -gt $Limit) { throw "Invalid/oversized evidence: $Name" }
    $script:remainingBytes -= $file.Length
    if ($script:remainingBytes -lt 0) { throw 'Evidence exceeds the 256 MiB total input budget.' }
    return [IO.File]::ReadAllText($file.FullName)
}
function Parse-Fields([string]$Text) {
    $fields = @{}
    foreach ($part in $Text.Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries)) {
        if ($part -notmatch '^([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.-]+)$' -or $fields.ContainsKey($Matches[1])) {
            throw 'Malformed or duplicate qualification field.'
        }
        $fields[$Matches[1]] = $Matches[2]
    }
    return ,$fields
}
function Require-Fields($Fields, $Expected) {
    foreach ($key in $Expected.Keys) {
        if (!$Fields.ContainsKey($key) -or [string]$Fields[$key] -cne [string]$Expected[$key]) { throw "Qualification mismatch: $key" }
    }
}
function Number($Value, [string]$Label, [switch]$ZeroAllowed) {
    $valueString = [Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)
    $number = 0.0
    if (![double]::TryParse($valueString, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or
            [double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt 0 -or
            (!$ZeroAllowed -and $number -eq 0)) { throw "Invalid numeric evidence: $Label" }
    return $number
}
function Require-Counters($Fields, [bool]$Enabled, [bool]$Positive) {
    $total = Number $Fields.total 'operations'
    $batches = Number $Fields.batches 'batches' -ZeroAllowed
    $coalesced = Number $Fields.coalesced 'coalesced' -ZeroAllowed
    $disabled = Number $Fields.disabled 'disabled' -ZeroAllowed
    Require-Fields $Fields @{causal='0'; unitOfWork='0'; transportBypassed='0'}
    if ($Enabled) {
        if ($disabled -ne 0 -or $batches -lt 1 -or $batches + $coalesced -ne $total -or ($Positive -and $coalesced -lt 1)) {
            throw 'Enabled measurement did not prove real coalescing transport.'
        }
    } elseif ($disabled -ne $total -or $batches -ne 0 -or $coalesced -ne 0) {
        throw 'Disabled measurement did not use the original direct repository path.'
    }
}

$manifest = @{}
foreach ($line in (Read-Bounded 'manifest.txt' 16KB).Trim().Split([char]10)) {
    if ($line.Trim() -notmatch '^([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.-]+)$' -or $manifest.ContainsKey($Matches[1])) {
        throw 'Malformed/duplicate manifest key.'
    }
    $manifest[$Matches[1]] = $Matches[2]
}
Require-Fields $manifest @{schema='T1_COALESCING_V4'; controllerHeapMiB='512'; forkHeapMiB='512';
    cpus="$ProcessorCount"; fairness='true'; maxTotal='32';
    maxWaitMillis='10000'; connectMillis='5000'; socketMillis='10000'; blockingMillis='15000';
    near='false'; source='REDIS_ONLY'; guard='UNCHANGED'}
if (!$manifest.ContainsKey('defaultCoalescing') -or $manifest.defaultCoalescing -cnotin @('false', 'true')) {
    throw 'Candidate artifact default proof is missing or invalid.'
}
$jar = Get-Item -LiteralPath $CandidateJar
if ($jar.PSIsContainer -or $jar.Length -le 0 -or $jar.Length -gt 256MB -or
        (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash -ine $manifest.jarSha256) {
    throw 'Candidate JAR identity mismatch.'
}
$compactorEntry = 'com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class'
$compactorRoot = Join-Path $root 'compactor'
$compactorFile = Join-Path $compactorRoot $compactorEntry
if (!(Test-Path -LiteralPath $compactorRoot -PathType Container) -or (Get-Item -LiteralPath $compactorRoot).LinkType -or
        !(Test-Path -LiteralPath $compactorFile -PathType Leaf) -or
        @(Get-ChildItem -LiteralPath $compactorRoot -Recurse -Force -File).Count -ne 1 -or
        @(Get-ChildItem -LiteralPath $compactorRoot -Recurse -Force | Where-Object LinkType).Count -ne 0) {
    throw 'Helper-only compactor identity/roster mismatch.'
}
Read-Bounded "compactor/$compactorEntry" 64KB | Out-Null
if (!$manifest.ContainsKey('compactorClassSha256') -or $manifest.compactorClassSha256 -cnotmatch '^[a-f0-9]{64}$' -or
        (Get-FileHash -LiteralPath $compactorFile).Hash -ine $manifest.compactorClassSha256) {
    throw 'Helper-only compactor identity/roster mismatch.'
}
# Preserve the original command paths when checking a relocated evidence archive.
$controllerPaths = @((Read-Bounded 'controller-paths.txt' 16KB).TrimEnd([char]10, [char]13).Split([char]10) | ForEach-Object { $_.TrimEnd([char]13) })
if ($controllerPaths.Count -ne 2 -or @($controllerPaths | Where-Object {
            $_ -cnotmatch '^/[^/]' -or $_ -match '[:\x00-\x1f\x7f]' -or $_ -match '[/\\]$'
        }).Count -ne 0) { throw 'Invalid original controller paths.' }
foreach ($pair in @(@('RedisBenchmarkFixture.java', 'fixtureSha256'), @('RedisProducerGuard.java', 'guardSourceSha256'))) {
    Read-Bounded $pair[0] 256KB | Out-Null
    if ((Get-FileHash -LiteralPath (Join-Path $root $pair[0]) -Algorithm SHA256).Hash -ine $manifest[$pair[1]]) {
        throw 'Frozen source digest mismatch.'
    }
}
if ($ExpectedBaselineSha256) {
    if ($ExpectedBaselineSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or $manifest.baselineSha256 -ine $ExpectedBaselineSha256) {
        throw 'Preserved baseline JAR SHA does not match the supplied immutable reference.'
    }
}
$fixture = [IO.File]::ReadAllText((Join-Path $root 'RedisBenchmarkFixture.java'))
foreach ($required in @('pool.setMaxTotal(32)', 'pool.setFairness(true)', 'pool.setMaxWait(java.time.Duration.ofSeconds(10))',
        '.connectionTimeoutMillis(5000).socketTimeoutMillis(10000).blockingSocketTimeoutMillis(15000)', 'ForbiddenDataSource')) {
    if (!$fixture.Contains($required)) { throw 'Fixture connection/source contract changed.' }
}
if ($fixture -match 'CacheRuntimeOptions|NearCacheInvalidationMode|ObservationRegistry|java\.lang\.reflect') {
    throw 'Default benchmark source must remain old-API-compatible, not emulate new APIs.'
}
foreach ($enabled in @('false', 'true')) {
    $log = Read-Bounded "preflight-$enabled.log"
    $defaults = @([regex]::Matches($log, '(?m)^T1_DEFAULT ([^\r\n]+)'))
    if ($defaults.Count -ne 1) { throw 'Exactly one candidate-JAR default proof per preflight is required.' }
    Require-Fields (Parse-Fields $defaults[0].Groups[1].Value) @{
        coalescing=$manifest.defaultCoalescing; configBuilder=$manifest.defaultCoalescing; runtimeOptions=$manifest.defaultCoalescing
    }
    $proof = @([regex]::Matches($log, 'T1_PREFLIGHT ([^\r\n]+)'))
    if ($proof.Count -ne 1) { throw 'Exactly one complete preflight proof per toggle is required.' }
    $fields = Parse-Fields $proof[0].Groups[1].Value
    Require-Fields $fields @{coalescing=$enabled; workers='1000'; min='16'; total='16000'; checksum='8008000';
        fairness='true'; maxTotal='32'; maxWaitMillis='10000'; socketMillis='10000'; near='false';
        source='REDIS_ONLY'; guard='UNCHANGED'}
    Require-Counters $fields ($enabled -eq 'true') $true
}

$order = @((Read-Bounded 'order.txt' 16KB).Trim().Split([char]10) | ForEach-Object { $_.Trim() })
$expectedOrder = [Collections.Generic.List[string]]::new()
$reverse = $false
foreach ($mode in @('thrpt', 'sample')) {
    foreach ($scenario in @('point1', 'point16', 'point64', 'virtual1000')) {
        $toggles = if ($reverse) { @('true', 'false') } else { @('false', 'true') }
        $reverse = !$reverse
        foreach ($toggle in $toggles) { $expectedOrder.Add("$scenario-$mode-$toggle") }
    }
}
if (($order -join ',') -cne ($expectedOrder -join ',')) { throw 'Missing, duplicated or non-alternating off/on pairs.' }
if (@(Get-ChildItem -LiteralPath $root -Filter '*.json' -File).Count -ne 16) { throw 'Expected exactly 16 raw result files.' }
$results = @{}
$settings = $null
foreach ($label in $expectedOrder) {
    $pieces = $label.Split('-')
    $scenario = $pieces[0]; $mode = $pieces[1]; $enabled = $pieces[2]
    $threads = switch ($scenario) { point1 {1}; point16 {16}; point64 {64}; virtual1000 {1000} }
    $virtual = $threads -eq 1000
    $method = if ($virtual) { 'l1MissFindByIdVirtual1000' } else { 'l1MissFindById' }
    $compacting = $virtual -and $mode -ceq 'sample'
    $controllerArgs = @((Read-Bounded "$label.controller.args" 32KB).TrimEnd([char]10, [char]13).Split([char]10) | ForEach-Object { $_.TrimEnd([char]13) })
    $profilers = [Collections.Generic.List[string]]::new()
    for ($a = 0; $a -lt $controllerArgs.Count; $a++) {
        if ($controllerArgs[$a] -ceq '-prof') {
            if (++$a -ge $controllerArgs.Count) { throw 'Incomplete controller profiler argument.' }
            $profilers.Add($controllerArgs[$a])
        }
    }
    $resourceProfiler = 'com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler'
    $expectedProfilers = if ($compacting) {
        @('com.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfiler', $resourceProfiler, 'gc')
    } else { @($resourceProfiler, 'gc') }
    if (($profilers -join "`0") -cne ($expectedProfilers -join "`0")) { throw "Unexpected profiler list/order: $label" }
    $resourceClasspath = $controllerPaths[0] + '/resource-tools/classes:' + $controllerPaths[1]
    $cellClasspath = if ($compacting) { $controllerPaths[0] + '/compactor:' + $resourceClasspath } else { $resourceClasspath }
    $expectedArgs = @($controllerArgs[0], '-Xms512m', '-Xmx512m', "-XX:ActiveProcessorCount=$ProcessorCount",
        '-cp', $cellClasspath, 'org.openjdk.jmh.Main', ".*RedisCoalescingBenchmark\.$method`$",
        '-t', "$threads", '-bm', $mode, '-p', "coalescing=$enabled", '-f', '3', '-wi', '5', '-i', '5',
        '-w', '3s', '-r', '3s', '-to', '60s', '-foe', 'true')
    foreach ($profiler in $expectedProfilers) { $expectedArgs += @('-prof', $profiler) }
    if ($virtual) { $expectedArgs += @('-si', 'false') }
    $rows = @((Read-Bounded "$label.json" 64MB) | ConvertFrom-Json)
    if ($rows.Count -ne 1) { throw "Missing/extra parameter or mode rows: $label" }
    $item = $rows[0]
    if ($item.jmhVersion -ne '1.37' -or $item.benchmark -cne "com.reactor.cachedb.benchmarks.redis.RedisCoalescingBenchmark.$method" -or
            $item.mode -ne $mode -or $item.threads -ne $threads -or $item.params.coalescing -cne $enabled -or
            @($item.params.PSObject.Properties).Count -ne 1 -or $item.forks -ne 3 -or
            $item.warmupIterations -ne 5 -or $item.measurementIterations -ne 5 -or
            $item.warmupTime -cnotmatch '\A3 ?s\z' -or $item.measurementTime -cnotmatch '\A3 ?s\z' -or
            $item.warmupBatchSize -ne 1 -or $item.measurementBatchSize -ne 1) {
        throw "Wrong fixed JMH settings: $label"
    }
    $signature = @($item.jdkVersion, $item.vmName, $item.vmVersion, $item.jvm) -join '|'
    if ($null -eq $settings) { $settings = $signature }
    if ($settings -cne $signature -or $item.jdkVersion -notmatch '^21(?:[.+-]|$)') { throw 'JDK21 / identical VM evidence required.' }
    $requiredArgs = @('-Xms512m', '-Xmx512m', "-XX:ActiveProcessorCount=$ProcessorCount")
    foreach ($arg in $requiredArgs) { if (@($item.jvmArgs | Where-Object { $_ -ceq $arg }).Count -ne 1) { throw 'Heap/CPU settings differ.' } }
    $endpointArgs = @($item.jvmArgs | Where-Object { $_ -like '-Dcachedb.bench.redis=*' })
    if ($endpointArgs.Count -ne 1 -or $endpointArgs[0] -match '@|\s') { throw 'Dedicated endpoint missing or contains credentials/whitespace.' }
    $allowed = $requiredArgs + $endpointArgs
    if ($virtual) {
        $allowed += @('-Djmh.executor=CUSTOM', '-Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor')
    }
    if (@($item.jvmArgs).Count -ne $allowed.Count -or
            @($item.jvmArgs | Where-Object { $_ -cnotin $allowed }).Count -ne 0) { throw 'Unexpected JVM override/executor/guard setting.' }
    $endpointSignature = $endpointArgs[0]
    $append = $endpointSignature
    if ($virtual) { $append += ' -Djmh.executor=CUSTOM -Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor' }
    $expectedArgs += @('-jvmArgs', "-Xms512m -Xmx512m -XX:ActiveProcessorCount=$ProcessorCount", '-jvmArgsAppend', $append,
        '-rf', 'json', '-rff', ($controllerPaths[0] + "/$label.json"))
    if (($controllerArgs -join "`0") -cne ($expectedArgs -join "`0")) { throw "Unexpected controller arguments: $label" }
    if (!$results.ContainsKey('endpoint')) { $results.endpoint = $endpointSignature }
    if ($results.endpoint -cne $endpointSignature) { throw 'Off/on Redis endpoints differ.' }
    $score = Number $item.primaryMetric.score "$label score"
    Number $item.primaryMetric.scoreError "$label error" -ZeroAllowed | Out-Null
    $unit = if ($mode -eq 'thrpt') { 'ops/us' } else { 'us/op' }
    if ($item.primaryMetric.scoreUnit -cne $unit) { throw 'Unexpected score units.' }
    if ($item.secondaryMetrics.PSObject.Properties.Name -notcontains 'gc.alloc.rate.norm') { throw 'GC profiler evidence is missing.' }
    Number $item.secondaryMetrics.'gc.alloc.rate.norm'.score "$label allocation" -ZeroAllowed | Out-Null
    $raw = if ($mode -eq 'thrpt') { $item.primaryMetric.rawData } else { $item.primaryMetric.rawDataHistogram }
    if ($raw.Count -ne 3) { throw 'Exactly three complete raw forks required.' }
    foreach ($fork in $raw) {
        if ($fork.Count -ne 5) { throw 'Every raw fork must retain five measured iterations.' }
        foreach ($iteration in $fork) {
            if ($mode -eq 'thrpt') { Number $iteration "$label raw throughput" | Out-Null }
            else {
                if ($iteration.Count -lt 1 -or $iteration.Count -gt 1000000) { throw 'Missing/oversized sampled histogram.' }
                foreach ($bucket in $iteration) {
                    if ($bucket.Count -ne 2) { throw 'Malformed sampled histogram bucket.' }
                    Number $bucket[0] 'sample latency' -ZeroAllowed | Out-Null
                    Number $bucket[1] 'sample count' | Out-Null
                }
            }
        }
    }
    $log = Read-Bounded "$label.log"
    $controllers = @([regex]::Matches($log, '(?m)^T1_CONTROLLER ([^\r\n]+)'))
    if ($controllers.Count -ne 1) { throw 'Exactly one configured controller-heap record per fresh cell is required.' }
    Require-Fields (Parse-Fields $controllers[0].Groups[1].Value) @{
        xmsMiB='512'; xmxMiB='512'; cpus="$ProcessorCount"; forkHeapMiB='512'
    }
    if ($virtual -and $log -notmatch 'Synchronize iterations are disabled!') { throw 'Virtual CUSTOM iteration spin rendezvous was not disabled.' }
    if (!$virtual -and $log -notmatch 'will synchronize iterations') { throw 'Platform iteration synchronization changed.' }
    $trials = @([regex]::Matches($log, 'T1_TRIAL ([^\r\n]+)'))
    $iterations = @([regex]::Matches($log, 'T1_ITER ([^\r\n]+)'))
    if ($trials.Count -ne 3 -or $iterations.Count -ne 30) { throw 'Missing fork/trial/iteration progress qualification.' }
    $pids = @{}
    foreach ($trial in $trials) {
        $fields = Parse-Fields $trial.Groups[1].Value
        Require-Fields $fields @{coalescing=$enabled; mode=$mode; threads="$threads"; virtual="$virtual".ToLowerInvariant()}
        if ($pids.ContainsKey($fields.pid)) { throw 'Duplicate fork progress record.' }
        $pids[$fields.pid] = @{}
    }
    foreach ($iteration in $iterations) {
        $fields = Parse-Fields $iteration.Groups[1].Value
        Require-Fields $fields @{coalescing=$enabled; mode=$mode; threads="$threads"; virtual="$virtual".ToLowerInvariant()}
        if (!$pids.ContainsKey($fields.pid) -or $fields.ordinal -notmatch '^[0-9]$' -or $pids[$fields.pid].ContainsKey($fields.ordinal)) {
            throw 'Unknown fork or duplicate/out-of-range iteration progress.'
        }
        $pids[$fields.pid][$fields.ordinal] = $true
        Number $fields.min 'minimum worker progress' | Out-Null
        Require-Counters $fields ($enabled -eq 'true') ($threads -ge 64)
    }
    foreach ($pidProgress in $pids.Values) { if ($pidProgress.Count -ne 10) { throw 'Not every worker/fork completed all iterations.' } }
    if ($mode -eq 'sample') { Assert-Jmh137HistogramConsistency $item | Out-Null }
    $p50 = if ($mode -eq 'sample') { Number $item.primaryMetric.scorePercentiles.'50.0' "$label p50" } else { 0 }
    $p99 = if ($mode -eq 'sample') { Number $item.primaryMetric.scorePercentiles.'99.0' "$label p99" } else { 0 }
    $results[$label] = [pscustomobject]@{score=$score; p50=$p50; p99=$p99; confidence=$item.primaryMetric.scoreConfidence}
}

$failures = [Collections.Generic.List[string]]::new()
Write-Output '| Case | Throughput On/Off | p50 On/Off | p99 On/Off | Gate |'
Write-Output '| --- | ---: | ---: | ---: | --- |'
foreach ($scenario in @('point1', 'point16', 'point64', 'virtual1000')) {
    $throughput = $results["$scenario-thrpt-true"].score / $results["$scenario-thrpt-false"].score
    $p50 = $results["$scenario-sample-true"].p50 / $results["$scenario-sample-false"].p50
    $p99 = $results["$scenario-sample-true"].p99 / $results["$scenario-sample-false"].p99
    $gate = 'REPORT_ONLY'
    if ($scenario -eq 'point1') {
        $gate = if ($p50 -le 1.03) { 'PASS_DEFAULT_ENABLED' } else { 'FAIL_DEFAULT_ENABLED' }
        if ($p50 -gt 1.03 -and $manifest.defaultCoalescing -cne 'false') {
            $failures.Add('point1 p50 regression exceeds 3%; artifact default is not false.')
        }
    } elseif ($scenario -in @('point64', 'virtual1000')) {
        $gate = if ($throughput -ge 2.0) { 'PASS' } else { 'FAIL' }
        if ($gate -eq 'FAIL') { $failures.Add("$scenario throughput gain is below 2x.") }
    }
    Write-Output ('| {0} | {1:F4}x | {2:F4}x | {3:F4}x | {4} |' -f $scenario, $throughput, $p50, $p99, $gate)
}
foreach ($scenario in @('point1', 'point16', 'point64', 'virtual1000')) {
    Write-Output ("{0} throughput confidence intervals (off/on): [{1}] / [{2}]" -f $scenario,
        ($results["$scenario-thrpt-false"].confidence -join ', '), ($results["$scenario-thrpt-true"].confidence -join ', '))
}
if ($failures.Count) { throw ($failures -join ' ') }
if ($results['point1-sample-true'].p50 / $results['point1-sample-false'].p50 -gt 1.03) {
    Write-Output 'FAIL_DEFAULT_ENABLED: point1 p50 exceeds 3%; artifact default=false permits opt-in acceptance only.'
    Write-Output 'NUMERIC_T1_OPT_IN_GATE_PASS_DEFAULT_DISABLED_NOT_RELEASE_ACCEPTANCE'
} else {
    Write-Output 'NUMERIC_T1_GATE_PASS_NOT_RELEASE_ACCEPTANCE'
}
Write-Output ("Candidate artifact defaultCoalescing={0}; explicit on/off measurements are unchanged." -f $manifest.defaultCoalescing)
Write-Output '16 rows; fixed resources/forks/iterations, positive controls and all-worker progress verified.'
Write-Output 'Review confidence intervals, deployment parity and separate functional/JFR evidence before qualification.'
