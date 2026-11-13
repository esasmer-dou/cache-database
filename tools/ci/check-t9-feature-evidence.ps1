param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$CandidateJar,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedBaselineSha256,
    [ValidateSet(1, 2)][int]$ProcessorCount = 1,
    [ValidateSet('features', 'all', 'observation', 'async', 'tracking')][string]$Group = 'features'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'jmh-histogram-consistency.ps1')
$root = (Get-Item -LiteralPath $Path).FullName
if (!(Test-Path -LiteralPath $root -PathType Container) -or
        (Test-Path -LiteralPath (Join-Path $root 'FAILED.txt')) -or
        !(Test-Path -LiteralPath (Join-Path $root 'COMPLETE.txt'))) { throw 'Incomplete/failed attempts cannot qualify.' }
$script:remainingBytes = 768MB
function Read-Bounded([string]$Name, [long]$Limit = 16MB) {
    $file = Get-Item -LiteralPath (Join-Path $root $Name)
    if ($file.PSIsContainer -or $file.LinkType -or $file.Length -lt 1 -or $file.Length -gt $Limit) { throw "Invalid/oversized evidence: $Name" }
    $script:remainingBytes -= $file.Length
    if ($script:remainingBytes -lt 0) { throw 'Evidence exceeds the fixed 768 MiB total input budget.' }
    [IO.File]::ReadAllText($file.FullName)
}
function Fields([string]$Text) {
    $f = @{}
    foreach ($part in $Text.Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries)) {
        if ($part -notmatch '^([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.-]+)$' -or $f.ContainsKey($Matches[1])) {
            throw 'Malformed/duplicate raw proof field.'
        }
        $f[$Matches[1]] = $Matches[2]
    }
    return ,$f
}
function Require($F, $Expected) {
    foreach ($k in $Expected.Keys) {
        if (!$F.ContainsKey($k) -or [string]$F[$k] -cne [string]$Expected[$k]) { throw "Proof mismatch: $k" }
    }
}
function Count($F, [string]$Key, [switch]$Positive) {
    if (!$F.ContainsKey($Key) -or $F[$Key] -notmatch '^(0|[1-9][0-9]{0,17})$') { throw "Invalid integer counter: $Key" }
    $n = [long]$F[$Key]
    if ($Positive -and $n -eq 0) { throw "No progress: $Key" }
    return $n
}
function Number($Value, [string]$Name, [switch]$Zero, [switch]$Signed) {
    $s = [Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)
    $n = 0.0
    if (![double]::TryParse($s, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$n) -or
            [double]::IsNaN($n) -or [double]::IsInfinity($n) -or (!$Signed -and $n -lt 0) -or (!$Zero -and $n -eq 0)) { throw "Invalid number: $Name" }
    return $n
}
function Common($F) {
    Require $F @{cpus="$ProcessorCount"; fairness='true'; maxTotal='32'; maxWaitMillis='10000';
        connectMillis='5000'; socketMillis='10000'; blockingMillis='15000'; source='REDIS_ONLY'; guard='UNCHANGED'}
}
function Zero-Reads($F) {
    Require $F @{batches='0'; coalesced='0'; disabled='0'; causal='0'; unitOfWork='0'; transportBypassed='0'}
}
function Async-Proof($F, [bool]$Enabled) {
    $total = Count $F total -Positive
    $batches = Count $F batches -Positive; $coalesced = Count $F coalesced; $bypass = Count $F transportBypassed
    Require $F @{disabled='0'; causal='0'; unitOfWork='0'; externalRejected='0'; externalFailed='0';
        externalCancelled='0'; offload='0'; outstanding='0'; reservedBytes='0'; deliveries='0'; expiryWorkers='0';
        logicalInFlight='1000'; operationsPerInvocation='1000'; threads='1'; virtual='false'}
    if ((Count $F externalAccepted) -ne $total -or (Count $F externalCompleted) -ne $total -or
            $total % 1000 -ne 0 -or $batches + $coalesced -ne $total -or
            ($Enabled -and ($coalesced -eq 0 -or $bypass -ne 0)) -or
            (!$Enabled -and ($coalesced -ne 0 -or $batches -ne $total -or $bypass -ne $total))) {
        throw 'Native bounded singleton/coalesced transport or physical completion proof failed.'
    }
}
function Health($F, [string]$Mode) {
    $tracking = $Mode -ceq 'SERVER_TRACKING'
    Require $F @{actualMode=$Mode; connected='true'; tracking="$tracking".ToLowerInvariant();
        publishLegacy="$(!$tracking)".ToLowerInvariant(); flushDelta='0'; reconnectDelta='0'; generationStable='true'}
}
function Publication($F, [string]$Mode, [bool]$Write) {
    $delta = Count $F publishDelta
    $expected = if ($Write -and $Mode -ceq 'PUBSUB') { Count $F total -Positive } else { 0 }
    if ($delta -ne $expected) { throw 'Every actual write must match the strict INFO PUBLISH contract; exclusive Redis required.' }
}
function Observation-Proof($F, [bool]$Hit, [string]$Variant) {
    Require $F @{observationEnabled="$($Variant -ceq 'handler')".ToLowerInvariant(); instrumentationFailures='0'; scopeLeaked='false'}
    if ($Hit) { Zero-Reads $F }
    else {
        Require $F @{batches='0'; coalesced='0'; causal='0'; unitOfWork='0'; transportBypassed='0'}
        if ((Count $F disabled) -ne (Count $F total -Positive)) { throw 'Observation Redis miss left the direct sync path.' }
    }
}
$manifest = @{}
foreach ($line in (Read-Bounded 'manifest.txt' 32KB).Trim().Split([char]10)) {
    if ($line.Trim() -notmatch '^([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.-]+)$' -or $manifest.ContainsKey($Matches[1])) {
        throw 'Malformed/duplicate manifest key.'
    }
    $manifest[$Matches[1]] = $Matches[2]
}
Require $manifest @{schema='T9_FEATURES_V1'; group=$Group; controllerHeapMiB='1024'; forkHeapMiB='512';
    cpus="$ProcessorCount"; fairness='true'; maxTotal='32'; maxWaitMillis='10000'; connectMillis='5000';
    socketMillis='10000'; blockingMillis='15000'; source='REDIS_ONLY'; guard='UNCHANGED'}
if (!$manifest.ContainsKey('defaultCoalescing') -or $manifest.defaultCoalescing -cnotin @('false', 'true')) { throw 'Artifact default proof is missing.' }
$jar = Get-Item -LiteralPath $CandidateJar
if ($jar.PSIsContainer -or $jar.Length -lt 1 -or $jar.Length -gt 256MB -or
        (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash -ine $manifest.jarSha256 -or
        $manifest.baselineSha256 -ine $ExpectedBaselineSha256) { throw 'Candidate/preserved baseline identity mismatch.' }
$expectedSources = @(
    'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java',
    'tools/benchmarks/redis/pom.xml',
    'tools/benchmarks/redis/run-coalescing-paired.sh',
    'tools/benchmarks/redis/run-features-paired.sh',
    'tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingBenchmark.java',
    'tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingFixtures.java',
    'tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingPreflight.java',
    'tools/benchmarks/redis/src/features/README.md',
    'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisFeatureFixtures.java',
    'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisFeaturePreflight.java',
    'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisNativeAsyncBenchmark.java',
    'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisObservationBenchmark.java',
    'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisTrackingBenchmark.java',
    'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkFixture.java',
    'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkRunner.java',
    'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisRepositoryBenchmark.java',
    'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/VirtualBenchmarkExecutor.java',
    'tools/ci/check-t1-coalescing-evidence.ps1',
    'tools/ci/check-t9-feature-evidence.ps1',
    'tools/ci/jmh-histogram-consistency.ps1',
    'tools/ci/test-t1-default-acceptance.ps1',
    'tools/ci/test-t9-feature-evidence.ps1',
    'tools/ci/test-jmh-histogram-consistency.ps1',
    'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java',
    'tools/benchmarks/redis/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java'
)
$sourceLines = @((Read-Bounded 'sources.sha256' 32KB).Trim().Split([char]10) | ForEach-Object { $_.Trim() })
if ($sourceLines.Count -ne $expectedSources.Count -or
        (Get-FileHash -LiteralPath (Join-Path $root 'sources.sha256') -Algorithm SHA256).Hash -ine $manifest.sourcesSha256) {
    throw 'Missing/changed fixed source manifest.'
}
$digests = @{}
for ($i = 0; $i -lt $expectedSources.Count; $i++) {
    if ($sourceLines[$i] -notmatch '^([0-9a-fA-F]{64})  (.+)$' -or $Matches[2] -cne $expectedSources[$i]) { throw 'Changed/unsafe source path set or order.' }
    $digest = $Matches[1]; $source = $Matches[2]; $digests[$source] = $digest
    Read-Bounded "sources/$source" 512KB | Out-Null
    if ((Get-FileHash -LiteralPath (Join-Path $root "sources/$source") -Algorithm SHA256).Hash -ine $digest) { throw "Source snapshot digest mismatch: $source" }
}
$fixture = Read-Bounded 'sources/tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkFixture.java' 256KB
if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'jmh-histogram-consistency.ps1')).Hash -ine
        $digests['tools/ci/jmh-histogram-consistency.ps1']) { throw 'Use the frozen matching histogram helper.' }
foreach ($s in @('pool.setFairness(true)', 'pool.setMaxTotal(32)', 'pool.setMaxWait(java.time.Duration.ofSeconds(10))',
        '.connectionTimeoutMillis(5000).socketTimeoutMillis(10000).blockingSocketTimeoutMillis(15000)', 'ForbiddenDataSource')) {
    if (!$fixture.Contains($s)) { throw 'Fixed fair/source fixture contract changed.' }
}
if ($fixture -match 'CacheRuntimeOptions|ObservationRegistry|NearCacheInvalidationMode|java\.lang\.reflect') { throw 'Old-API default source set must remain untouched.' }
$capabilities = if ($Group -in @('features', 'all')) { @('observation', 'async', 'tracking') } else { @($Group) }
foreach ($capability in $capabilities) {
    $log = Read-Bounded "preflight-$capability.log"
    $default = @([regex]::Matches($log, '(?m)^T9_DEFAULT ([^\r\n]+)'))
    if ($default.Count -ne 1) { throw 'Missing/duplicate preflight artifact default record.' }
    Require (Fields $default[0].Groups[1].Value) @{coalescing=$manifest.defaultCoalescing; configBuilder=$manifest.defaultCoalescing; runtimeOptions=$manifest.defaultCoalescing}
    $proofs = @([regex]::Matches($log, '(?m)^T9_PREFLIGHT ([^\r\n]+)'))
    $expected = if ($capability -eq 'observation') {6} else {2}
    if ($proofs.Count -ne $expected) { throw "Incomplete capability preflight: $capability" }
    $seen = @{}
    foreach ($proof in $proofs) {
        $f = Fields $proof.Groups[1].Value
        Common $f; Require $f @{feature=$capability}
        $key = "$($f.path)-$($f.variant)"
        if ($seen.ContainsKey($key)) { throw 'Duplicate preflight variant.' }; $seen[$key] = $true
        switch ($capability) {
            observation {
                if ($f.path -cnotin @('hit', 'miss') -or $f.variant -cnotin @('disabled', 'noop', 'handler')) { throw 'Unknown observation control.' }
                Require $f @{total='1'; checksum='1'}
                Observation-Proof $f ($f.path -ceq 'hit') $f.variant
            }
            async {
                if ($f.variant -cnotin @('false', 'true')) { throw 'Missing native async toggle.' }
                Require $f @{path='miss'; workers='1'; total='16000'; checksum='8008000'; min='1000'; threads='1'}
                Async-Proof $f ($f.variant -ceq 'true')
            }
            tracking {
                if ($f.variant -cnotin @('PUBSUB', 'SERVER_TRACKING')) { throw 'Missing explicit tracking mode.' }
                Require $f @{path='write'; total='1000'; versions='1000'; rawInvalidation=$(if ($f.variant -ceq 'SERVER_TRACKING') {'1'} else {'0'})}
                Health $f $f.variant; Publication $f $f.variant $true
            }
        }
    }
    if ($capability -eq 'observation') {
        $controls = @([regex]::Matches($log, '(?m)^T9_OBSERVATION_CONTROL ([^\r\n]+)'))
        if ($controls.Count -ne 2) { throw 'Real native observation tier controls missing.' }
        $paths = @{}
        foreach ($control in $controls) {
            $f = Fields $control.Groups[1].Value
            if ($f.path -cnotin @('hit', 'miss') -or $paths.ContainsKey($f.path)) { throw 'Duplicate/invalid native observation tier control.' }
            $paths[$f.path] = $true
            Require $f @{scopes='1'; nativeHook='true'; tier=$(if ($f.path -ceq 'hit') {'L1'} else {'REDIS'});
                outcome='hit'; instrumentationFailures='0'; scopeLeaked='false'}
        }
    }
    if ($capability -eq 'tracking') {
        $controls = @([regex]::Matches($log, '(?m)^T9_FLEET_CONTROL ([^\r\n]+)'))
        if ($controls.Count -ne 1) { throw 'Mixed-fleet fail-closed control missing.' }
        Require (Fields $controls[0].Groups[1].Value) @{rejectedAutoLegacyFalse='true'; strictOnly='true'}
    }
}
$cases = [Collections.Generic.List[object]]::new()
foreach ($feature in $capabilities) {
    $reverse = $false
    $paths = switch ($feature) { observation {@('hit', 'miss')}; async {@('miss')}; tracking {@('hit', 'write')} }
    foreach ($mode in @('thrpt', 'sample')) {
        foreach ($pathName in $paths) {
            $variants = switch ($feature) {
                observation { if ($reverse) {@('noop', 'disabled', 'handler')} else {@('disabled', 'noop', 'handler')} }
                async { if ($reverse) {@('true', 'false')} else {@('false', 'true')} }
                tracking { if ($reverse) {@('SERVER_TRACKING', 'PUBSUB')} else {@('PUBSUB', 'SERVER_TRACKING')} }
            }
            $reverse = !$reverse
            foreach ($variant in $variants) {
                $clazz = switch ($feature) {observation {'RedisObservationBenchmark'}; async {'RedisNativeAsyncBenchmark'}; tracking {'RedisTrackingBenchmark'}}
                $method = if ($feature -eq 'async') {'nativeL1MissWindow1000'} elseif ($pathName -eq 'hit') {'l1HitFindById'} elseif ($feature -eq 'tracking') {'saveSingle'} else {'l1MissFindById'}
                $parameter = switch ($feature) {observation {'observation'}; async {'coalescing'}; tracking {'invalidation'}}
                $cases.Add([pscustomobject]@{label="$feature-$pathName-$mode-$variant"; feature=$feature; path=$pathName;
                    mode=$mode; variant=$variant; clazz=$clazz; method=$method; parameter=$parameter})
            }
        }
    }
}
$order = @((Read-Bounded 'order.txt' 32KB).Trim().Split([char]10) | ForEach-Object {$_.Trim()})
if (($order -join ',') -cne (($cases | ForEach-Object {$_.label}) -join ',') -or
        @(Get-ChildItem -LiteralPath $root -Filter '*.json' -File).Count -ne $cases.Count) { throw 'Incomplete/extra/reordered paired matrix.' }
$results = @{}; $vm = $null; $endpoint = $null
foreach ($case in $cases) {
    $label = $case.label
    $rows = @((Read-Bounded "$label.json" 64MB) | ConvertFrom-Json)
    if ($rows.Count -ne 1) { throw "Missing/extra JMH result row: $label" }; $r = $rows[0]
    if ($r.jmhVersion -cne '1.37' -or $r.benchmark -cne "com.reactor.cachedb.benchmarks.redis.$($case.clazz).$($case.method)" -or
            $r.mode -cne $case.mode -or $r.threads -ne 1 -or $r.forks -ne 3 -or
            @($r.params.PSObject.Properties).Count -ne 1 -or $r.params.($case.parameter) -cne $case.variant -or
            $r.warmupIterations -ne 5 -or $r.measurementIterations -ne 5 -or
            $r.warmupTime -notmatch '^3\s*s$' -or $r.measurementTime -notmatch '^3\s*s$' -or
            $r.warmupBatchSize -ne 1 -or $r.measurementBatchSize -ne 1) { throw "Changed fixed JMH controls: $label" }
    $sig = @($r.jdkVersion, $r.vmName, $r.vmVersion, $r.jvm) -join '|'
    if ($null -eq $vm) { $vm = $sig }
    if ($sig -cne $vm -or $r.jdkVersion -notmatch '^21(?:[.+-]|$)') { throw 'JDK21 / identical child VM required.' }
    $required = @('-Xms512m', '-Xmx512m', "-XX:ActiveProcessorCount=$ProcessorCount")
    foreach ($arg in $required) { if (@($r.jvmArgs | Where-Object {$_ -ceq $arg}).Count -ne 1) { throw 'Changed fork heap/CPU.' } }
    $ep = @($r.jvmArgs | Where-Object {$_ -like '-Dcachedb.bench.redis=*'})
    if ($ep.Count -ne 1 -or $ep[0] -match '@|\s' -or @($r.jvmArgs).Count -ne 4 -or
            @($r.jvmArgs | Where-Object {$_ -cnotin ($required + $ep)}).Count -ne 0) { throw 'Unexpected executor/guard/JVM override.' }
    if ($null -eq $endpoint) { $endpoint = $ep[0] }; if ($endpoint -cne $ep[0]) { throw 'Redis endpoint changed across pairs.' }
    $unit = if ($case.mode -eq 'thrpt') {'ops/us'} else {'us/op'}
    if ($r.primaryMetric.scoreUnit -cne $unit) { throw 'Wrong JMH units.' }
    $score = Number $r.primaryMetric.score $label
    Number $r.primaryMetric.scoreError 'score error' -Zero | Out-Null
    if (@($r.primaryMetric.scoreConfidence).Count -ne 2) { throw 'Confidence interval missing.' }
    foreach ($n in $r.primaryMetric.scoreConfidence) { Number $n 'confidence bound' -Zero -Signed | Out-Null }
    if ($r.secondaryMetrics.PSObject.Properties.Name -cnotcontains 'gc.alloc.rate.norm') { throw 'GC allocation profiler missing.' }
    $allocation = Number $r.secondaryMetrics.'gc.alloc.rate.norm'.score 'allocation' -Zero
    $raw = if ($case.mode -eq 'thrpt') {$r.primaryMetric.rawData} else {$r.primaryMetric.rawDataHistogram}
    if ($raw.Count -ne 3) { throw 'Exactly three complete raw forks required.' }
    foreach ($fork in $raw) {
        if ($fork.Count -ne 5) { throw 'Each raw fork must retain all five measured iterations.' }
        foreach ($iteration in $fork) {
            if ($case.mode -eq 'thrpt') { Number $iteration 'raw throughput' | Out-Null }
            else {
                if ($iteration.Count -lt 1 -or $iteration.Count -gt 1000000) { throw 'Missing/oversized sample histogram.' }
                foreach ($bucket in $iteration) {
                    if ($bucket.Count -ne 2) { throw 'Malformed sample histogram.' }
                    Number $bucket[0] 'sample latency' -Zero | Out-Null
                    Number $bucket[1] 'sample count' | Out-Null
                }
            }
        }
    }
    $log = Read-Bounded "$label.log"
    if ($log -match 'OutOfMemoryError|Exception in thread|<failure>|<timeout>|<forked VM failed') { throw 'Failure text in raw fork output.' }
    if ($log -notmatch 'will synchronize iterations') { throw 'Platform iteration synchronization changed.' }
    $controllers = @([regex]::Matches($log, '(?m)^T9_CONTROLLER ([^\r\n]+)'))
    if ($controllers.Count -ne 1) { throw 'Controller resource record missing/duplicated.' }
    Require (Fields $controllers[0].Groups[1].Value) @{xmsMiB='1024'; xmxMiB='1024'; cpus="$ProcessorCount"; forkHeapMiB='512'}
    # JMH prints the iteration label before invoking fixture setup on the same console line.
    $controlPrefix = '(?m)^(?:(?:# Warmup Iteration|Iteration)[ \t]+[1-9][0-9]*:[ \t]*)?'
    $trials = @([regex]::Matches($log, $controlPrefix + 'T9_TRIAL ([^\r\n]+)'))
    $iterations = @([regex]::Matches($log, $controlPrefix + 'T9_ITER ([^\r\n]+)'))
    if ($trials.Count -ne 3 -or $iterations.Count -ne 30) { throw 'Every fork must complete all warmup and measurement controls.' }
    $pids = @{}
    foreach ($trial in $trials) {
        $f = Fields $trial.Groups[1].Value; Common $f
        Require $f @{feature=$case.feature; path=$case.path; variant=$case.variant; mode=$case.mode; threads='1'; virtual='false'}
        Count $f pid -Positive | Out-Null
        if ($pids.ContainsKey($f.pid)) { throw 'Duplicate fork identity.' }; $pids[$f.pid] = @{}
    }
    foreach ($iteration in $iterations) {
        $f = Fields $iteration.Groups[1].Value
        Require $f @{feature=$case.feature; path=$case.path; variant=$case.variant; mode=$case.mode; threads='1'; virtual='false'}
        if (!$pids.ContainsKey($f.pid) -or $f.ordinal -notmatch '^[0-9]$' -or $pids[$f.pid].ContainsKey($f.ordinal)) { throw 'Duplicate/unknown fork or iteration ordinal.' }
        $pids[$f.pid][$f.ordinal] = $true
        $total = Count $f total -Positive
        if ((Count $f min -Positive) -ne $total) { throw 'Single platform worker progress mismatch.' }
        switch ($case.feature) {
            observation { Observation-Proof $f ($case.path -ceq 'hit') $case.variant
                if ($case.path -ceq 'hit') { Health $f 'PUBSUB' } }
            async { Async-Proof $f ($case.variant -ceq 'true') }
            tracking { Health $f $case.variant; Publication $f $case.variant ($case.path -ceq 'write')
                if ($case.path -ceq 'hit') { Zero-Reads $f } }
        }
    }
    foreach ($progress in $pids.Values) { if ($progress.Count -ne 10) { throw 'Incomplete per-fork progress.' } }
    if ($case.mode -eq 'sample') { Assert-Jmh137HistogramConsistency $r | Out-Null }
    $p50 = if ($case.mode -eq 'sample') { Number $r.primaryMetric.scorePercentiles.'50.0' 'p50' } else {0}
    $p99 = if ($case.mode -eq 'sample') { Number $r.primaryMetric.scorePercentiles.'99.0' 'p99' } else {0}
    $results[$label] = [pscustomobject]@{score=$score; p50=$p50; p99=$p99; allocation=$allocation; confidence=$r.primaryMetric.scoreConfidence}
}
$failures = [Collections.Generic.List[string]]::new()
Write-Output '| Feature/Path | Variant | Throughput | p50 us | p99 us | B/op | Score Confidence |'
Write-Output '| --- | --- | ---: | ---: | ---: | ---: | --- |'
foreach ($case in ($cases | Where-Object {$_.mode -eq 'sample'})) {
    $s = $results[$case.label]; $t = $results["$($case.feature)-$($case.path)-thrpt-$($case.variant)"]
    Write-Output ('| {0}/{1} | {2} | {3:F6} | {4:F6} | {5:F6} | {6:F2} | [{7}] |' -f
        $case.feature, $case.path, $case.variant, $t.score, $s.p50, $s.p99, $s.allocation, ($s.confidence -join ', '))
}
if ($capabilities -contains 'observation') {
    foreach ($pathName in @('hit', 'miss')) {
        $disabled = $results["observation-$pathName-sample-disabled"].p50
        $noop = $results["observation-$pathName-sample-noop"].p50 / $disabled
        $handler = $results["observation-$pathName-sample-handler"].p50 / $disabled
        Write-Output ("Observation {0}: NOOP/disabled p50={1:F6}x (gate <=1.03), real no-op-handler/disabled={2:F6}x (REPORT_ONLY)." -f $pathName, $noop, $handler)
        if ($noop -gt 1.03) { $failures.Add("Observation $pathName NOOP p50 exceeds 3%.") }
    }
}
if ($capabilities -contains 'async') { Write-Output 'Async p50/p99 are window latency / 1000, NOT per-request latency percentiles. Native async speed is REPORT_ONLY.' }
if ($capabilities -contains 'tracking') { Write-Output 'Tracking throughput/latency is REPORT_ONLY; actual mode/readiness, stable generations and every INFO PUBLISH delta are mandatory.' }
if ($Group -eq 'all') {
    $trustedT1 = Join-Path $PSScriptRoot 'check-t1-coalescing-evidence.ps1'
    if ((Get-FileHash -LiteralPath $trustedT1 -Algorithm SHA256).Hash -ine $digests['tools/ci/check-t1-coalescing-evidence.ps1']) { throw 'Use the frozen matching T1 checker; never execute checker code from untrusted evidence.' }
    $t1Manifest = @{}
    foreach ($line in (Read-Bounded 'coalescing/manifest.txt' 32KB).Trim().Split([char]10)) {
        if ($line.Trim() -notmatch '^([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.-]+)$' -or $t1Manifest.ContainsKey($Matches[1])) {
            throw 'Malformed nested T1 manifest.'
        }
        $t1Manifest[$Matches[1]] = $Matches[2]
    }
    Require $t1Manifest @{schema='T1_COALESCING_V4'; jarSha256=$manifest.jarSha256; baselineSha256=$manifest.baselineSha256;
        cpus="$ProcessorCount"; defaultCoalescing=$manifest.defaultCoalescing; controllerHeapMiB='512'; forkHeapMiB='512'}
    $t1Rows = @((Read-Bounded 'coalescing/point1-thrpt-false.json' 64MB) | ConvertFrom-Json)
    if ($t1Rows.Count -ne 1) { throw 'Missing cross-profile T1 resource control.' }
    $t1Vm = @($t1Rows[0].jdkVersion, $t1Rows[0].vmName, $t1Rows[0].vmVersion, $t1Rows[0].jvm) -join '|'
    $t1Endpoint = @($t1Rows[0].jvmArgs | Where-Object { $_ -like '-Dcachedb.bench.redis=*' })
    if ($t1Vm -cne $vm -or $t1Endpoint.Count -ne 1 -or $t1Endpoint[0] -cne $endpoint) { throw 'T1/feature deployment or VM endpoint mismatch.' }
    try {
        & $trustedT1 -Path (Join-Path $root 'coalescing') -CandidateJar $CandidateJar -ProcessorCount $ProcessorCount -ExpectedBaselineSha256 $ExpectedBaselineSha256
    } catch { $failures.Add("T1: $($_.Exception.Message)") }
}
if ($failures.Count) { throw ($failures -join ' ') }
Write-Output ("T9_{0}_RAW_COVERAGE_AND_NUMERIC_GATES_PASS_NOT_RELEASE_ACCEPTANCE" -f $Group.ToUpperInvariant())
Write-Output 'Review confidence intervals and separately approved provider/Cluster, failure, native-memory, JFR and deployment evidence.'
