param(
    [string]$Checker = (Join-Path $PSScriptRoot 'check-t9-feature-evidence.ps1'),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# All rows/JAR placeholders below are SYNTHETIC parser/gate fixtures, NEVER performance evidence.
$temporary = !$EvidenceDirectory
$base = if ($temporary) {
    [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('cachedb-t9-checker-' + [guid]::NewGuid().ToString('N'))))
} else { [IO.Path]::GetFullPath($EvidenceDirectory) }
if (Test-Path -LiteralPath $base) { throw 'Refusing to overwrite existing self-test evidence.' }
[void][IO.Directory]::CreateDirectory($base)
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourcePaths = @(
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
$script:passed = 0
function Write-Utf8($Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false)) }
function Hash($Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Replace-Evidence($Dir, [string]$File, [string]$Old, [string]$New) {
    $path = Join-Path $Dir $File
    $text = [IO.File]::ReadAllText($path)
    if (!$text.Contains($Old)) { throw "Self-test mutation not found: $File / $Old" }
    Write-Utf8 $path ($text.Replace($Old, $New))
}
function Change-Row($Dir, [string]$Label, [scriptblock]$Change) {
    $path = Join-Path $Dir "$Label.json"
    $rows = @([IO.File]::ReadAllText($path) | ConvertFrom-Json)
    & $Change $rows[0] | Out-Null
    Write-Utf8 $path (ConvertTo-Json -InputObject $rows -Depth 24)
}
function Cases([string]$Group) {
    $features = if ($Group -in @('features', 'all')) { @('observation','async','tracking') } else { @($Group) }
    foreach ($f in $features) {
        $reverse = $false
        $paths = switch ($f) {observation {@('hit','miss')}; async {@('miss')}; tracking {@('hit','write')}}
        foreach ($mode in @('thrpt','sample')) {
            foreach ($path in $paths) {
                $variants = switch ($f) {
                    observation { if ($reverse) {@('noop','disabled','handler')} else {@('disabled','noop','handler')} }
                    async { if ($reverse) {@('true','false')} else {@('false','true')} }
                    tracking { if ($reverse) {@('SERVER_TRACKING','PUBSUB')} else {@('PUBSUB','SERVER_TRACKING')} }
                }
                $reverse = !$reverse
                foreach ($v in $variants) {
                    $clazz = switch ($f) {observation {'RedisObservationBenchmark'}; async {'RedisNativeAsyncBenchmark'}; tracking {'RedisTrackingBenchmark'}}
                    $method = if ($f -eq 'async') {'nativeL1MissWindow1000'} elseif ($path -eq 'hit') {'l1HitFindById'} elseif ($f -eq 'tracking') {'saveSingle'} else {'l1MissFindById'}
                    $param = switch ($f) {observation {'observation'}; async {'coalescing'}; tracking {'invalidation'}}
                    [pscustomobject]@{label="$f-$path-$mode-$v"; feature=$f; path=$path; mode=$mode; variant=$v; clazz=$clazz; method=$method; parameter=$param}
                }
            }
        }
    }
}
function Common { 'cpus=1 fairness=true maxTotal=32 maxWaitMillis=10000 connectMillis=5000 socketMillis=10000 blockingMillis=15000 source=REDIS_ONLY guard=UNCHANGED' }
function Health([string]$Mode) {
    $tracking = $Mode -eq 'SERVER_TRACKING'
    "actualMode=$Mode connected=true tracking=$("$tracking".ToLowerInvariant()) publishLegacy=$("$(!$tracking)".ToLowerInvariant()) flushDelta=0 reconnectDelta=0 generationStable=true"
}
function Read-Proof([bool]$Hit, [long]$Total) {
    $disabled = if ($Hit) {0} else {$Total}
    "batches=0 coalesced=0 disabled=$disabled causal=0 unitOfWork=0 transportBypassed=0"
}
function Async-Proof([bool]$Enabled, [long]$Total) {
    $batches = if ($Enabled) {10} else {$Total}
    $coalesced = $Total - $batches
    $bypassed = if ($Enabled) {0} else {$Total}
    "batches=$batches coalesced=$coalesced disabled=0 causal=0 unitOfWork=0 transportBypassed=$bypassed externalAccepted=$Total externalCompleted=$Total externalRejected=0 externalFailed=0 externalCancelled=0 offload=0 outstanding=0 reservedBytes=0 deliveries=0 expiryWorkers=0"
}
function T1-Fixture([string]$Name, [string]$Default, [string]$Proof, [string]$Slow = '') {
    $dir = Join-Path $base $Name
    [void][IO.Directory]::CreateDirectory($dir)
    Write-Utf8 (Join-Path $dir 'COMPLETE.txt') 'SYNTHETIC_CHECKER_SELFTEST_NOT_PERFORMANCE_EVIDENCE'
    Write-Utf8 (Join-Path $dir 'candidate.jar') 'SYNTHETIC_NOT_AN_EXECUTABLE_JAR'
    $entry = 'com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class'
    [void][IO.Directory]::CreateDirectory((Join-Path $dir ('compactor/' + (Split-Path -Parent $entry))))
    Write-Utf8 (Join-Path $dir "compactor/$entry") 'SYNTHETIC_HELPER_DIGEST_ONLY'
    Write-Utf8 (Join-Path $dir 'controller-paths.txt') "/synthetic/evidence/$Name`n/synthetic/candidate.jar"
    Write-Utf8 (Join-Path $dir 'RedisBenchmarkFixture.java') @'
pool.setMaxTotal(32)
pool.setFairness(true)
pool.setMaxWait(java.time.Duration.ofSeconds(10))
.connectionTimeoutMillis(5000).socketTimeoutMillis(10000).blockingSocketTimeoutMillis(15000)
ForbiddenDataSource
'@
    Write-Utf8 (Join-Path $dir 'RedisProducerGuard.java') 'SYNTHETIC_SOURCE_DIGEST_ONLY'
    $manifest = @"
schema=T1_COALESCING_V4
controllerHeapMiB=512
forkHeapMiB=512
defaultCoalescing=$Default
cpus=1
fairness=true
maxTotal=32
maxWaitMillis=10000
connectMillis=5000
socketMillis=10000
blockingMillis=15000
near=false
source=REDIS_ONLY
guard=UNCHANGED
jarSha256=$(Hash (Join-Path $dir 'candidate.jar'))
fixtureSha256=$(Hash (Join-Path $dir 'RedisBenchmarkFixture.java'))
guardSourceSha256=$(Hash (Join-Path $dir 'RedisProducerGuard.java'))
baselineSha256=absent
compactorClassSha256=$((Hash (Join-Path $dir "compactor/$entry")).ToLowerInvariant())
"@
    Write-Utf8 (Join-Path $dir 'manifest.txt') $manifest
    foreach ($enabled in @('false', 'true')) {
        $c = if ($enabled -eq 'true') { 'batches=100 coalesced=15900 disabled=0' } else { 'batches=0 coalesced=0 disabled=16000' }
        $defaultLine = if ($Proof -eq 'missing') { '' } else { "T1_DEFAULT coalescing=$Proof configBuilder=$Proof runtimeOptions=$Proof" }
        Write-Utf8 (Join-Path $dir "preflight-$enabled.log") @"
$defaultLine
T1_PREFLIGHT coalescing=$enabled workers=1000 min=16 total=16000 checksum=8008000 $c causal=0 unitOfWork=0 transportBypassed=0 fairness=true maxTotal=32 maxWaitMillis=10000 socketMillis=10000 near=false source=REDIS_ONLY guard=UNCHANGED
"@
    }
    $order = [Collections.Generic.List[string]]::new()
    $reverse = $false
    foreach ($mode in @('thrpt', 'sample')) {
        foreach ($scenario in @('point1', 'point16', 'point64', 'virtual1000')) {
            $toggles = if ($reverse) { @('true', 'false') } else { @('false', 'true') }
            $reverse = !$reverse
            foreach ($toggle in $toggles) {
                $label = "$scenario-$mode-$toggle"; $order.Add($label)
                $threads = switch ($scenario) {point1 {1}; point16 {16}; point64 {64}; virtual1000 {1000}}
                $virtual = $threads -eq 1000; $vt = "$virtual".ToLowerInvariant()
                $method = if ($virtual) { 'l1MissFindByIdVirtual1000' } else { 'l1MissFindById' }
                $score = 2.0
                if ($mode -eq 'thrpt' -and $toggle -eq 'true' -and $scenario -in @('point64', 'virtual1000')) {
                    $score = if ($Slow -eq $scenario) { 3.0 } else { 4.4 }
                }
                $p50 = if ($scenario -eq 'point1' -and $toggle -eq 'true') { 12.0 } else { 10.0 }
                $metric = @{score=$score; scoreError=0.01; scoreConfidence=@(1.0, 5.0);
                    scoreUnit=$(if ($mode -eq 'thrpt') {'ops/us'} else {'us/op'});
                    scorePercentiles=@{'0.0'=$p50; '50.0'=$p50; '90.0'=$p50; '95.0'=$p50; '99.0'=$p50;
                        '99.9'=$p50; '99.99'=$p50; '99.999'=$p50; '99.9999'=$p50; '100.0'=$p50}}
                $raw = [Collections.Generic.List[object]]::new()
                for ($f = 0; $f -lt 3; $f++) {
                    $iterations = [Collections.Generic.List[object]]::new()
                    for ($i = 0; $i -lt 5; $i++) {
                        if ($mode -eq 'thrpt') { $iterations.Add($score) }
                        else {
                            $buckets = [Collections.Generic.List[object]]::new()
                            $buckets.Add([object[]]@($p50, 100))
                            $iterations.Add($buckets)
                        }
                    }
                    $raw.Add($iterations)
                }
                if ($mode -eq 'thrpt') { $metric.rawData = $raw } else { $metric.rawDataHistogram = $raw }
                $jvmArgs = @('-Xms512m', '-Xmx512m', '-XX:ActiveProcessorCount=1', '-Dcachedb.bench.redis=localhost:1')
                if ($virtual) { $jvmArgs += @('-Djmh.executor=CUSTOM', '-Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor') }
                $item = @{jmhVersion='1.37'; benchmark="com.reactor.cachedb.benchmarks.redis.RedisCoalescingBenchmark.$method";
                    mode=$mode; threads=$threads; params=@{coalescing=$toggle}; forks=3; warmupIterations=5; measurementIterations=5;
                    warmupTime='3 s'; measurementTime='3 s'; warmupBatchSize=1; measurementBatchSize=1;
                    jdkVersion='21.0.8'; vmName='SYNTHETIC'; vmVersion='SYNTHETIC'; jvm='/synthetic/java';
                    jvmArgs=$jvmArgs; primaryMetric=$metric; secondaryMetrics=@{'gc.alloc.rate.norm'=@{score=1.0}; 'gc.count'=@{score=0.0}}}
                Write-Utf8 (Join-Path $dir "$label.json") (ConvertTo-Json -InputObject @($item) -Depth 20)
                $lines = [Collections.Generic.List[string]]::new()
                $lines.Add('T1_CONTROLLER xmsMiB=512 xmxMiB=512 cpus=1 forkHeapMiB=512')
                $lines.Add($(if ($virtual) { 'Synchronize iterations are disabled!' } else { 'will synchronize iterations' }))
                for ($fork = 1; $fork -le 3; $fork++) {
                    $fields = "pid=$fork coalescing=$toggle mode=$mode threads=$threads virtual=$vt"
                    $lines.Add("T1_TRIAL $fields")
                    for ($i = 0; $i -lt 10; $i++) {
                        $c = if ($toggle -eq 'true') { 'batches=10 coalesced=990 disabled=0' } else { 'batches=0 coalesced=0 disabled=1000' }
                        $lines.Add("T1_ITER $fields ordinal=$i min=1 total=1000 $c causal=0 unitOfWork=0 transportBypassed=0")
                    }
                }
                Write-Utf8 (Join-Path $dir "$label.log") ($lines -join [char]10)
                $compacting = $virtual -and $mode -ceq 'sample'
                $resourceCp = "/synthetic/evidence/$Name/resource-tools/classes:/synthetic/candidate.jar"
                $cp = if ($compacting) { "/synthetic/evidence/$Name/compactor:$resourceCp" } else { $resourceCp }
                $controller = @('/synthetic/java', '-Xms512m', '-Xmx512m', '-XX:ActiveProcessorCount=1', '-cp', $cp,
                    'org.openjdk.jmh.Main', ".*RedisCoalescingBenchmark\.$method`$", '-t', "$threads", '-bm', $mode,
                    '-p', "coalescing=$toggle", '-f', '3', '-wi', '5', '-i', '5', '-w', '3s', '-r', '3s', '-to', '60s', '-foe', 'true')
                if ($compacting) { $controller += @('-prof', 'com.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfiler') }
                $controller += @('-prof', 'com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler', '-prof', 'gc')
                $append = '-Dcachedb.bench.redis=localhost:1'
                if ($virtual) {
                    $controller += @('-si', 'false')
                    $append += ' -Djmh.executor=CUSTOM -Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor'
                }
                $controller += @('-jvmArgs', '-Xms512m -Xmx512m -XX:ActiveProcessorCount=1', '-jvmArgsAppend', $append,
                    '-rf', 'json', '-rff', "/synthetic/evidence/$Name/$label.json")
                Write-Utf8 (Join-Path $dir "$label.controller.args") ($controller -join [char]10)
            }
        }
    }
    Write-Utf8 (Join-Path $dir 'order.txt') ($order -join [char]10)
    return $dir
}

function Fixture([string]$Name, [string]$Group = 'features') {
    $dir = Join-Path $base $Name
    [void][IO.Directory]::CreateDirectory($dir)
    Write-Utf8 (Join-Path $dir 'COMPLETE.txt') 'SYNTHETIC_CHECKER_SELFTEST_NOT_PERFORMANCE_EVIDENCE'
    Write-Utf8 (Join-Path $dir 'candidate.jar') 'SYNTHETIC_NOT_AN_EXECUTABLE_JAR'
    Write-Utf8 (Join-Path $dir 'baseline.jar') 'SYNTHETIC_NOT_A_BASELINE'
    $sourceLines = [Collections.Generic.List[string]]::new()
    foreach ($source in $sourcePaths) {
        $target = Join-Path $dir "sources/$source"
        [void][IO.Directory]::CreateDirectory((Split-Path -Parent $target))
        [IO.File]::Copy((Join-Path $repo $source), $target)
        $sourceLines.Add("$(Hash $target)  $source")
    }
    Write-Utf8 (Join-Path $dir 'sources.sha256') ($sourceLines -join [char]10)
    Write-Utf8 (Join-Path $dir 'manifest.txt') @"
schema=T9_FEATURES_V1
group=$Group
controllerHeapMiB=1024
forkHeapMiB=512
cpus=1
defaultCoalescing=false
jarSha256=$(Hash (Join-Path $dir 'candidate.jar'))
baselineSha256=$(Hash (Join-Path $dir 'baseline.jar'))
sourcesSha256=$(Hash (Join-Path $dir 'sources.sha256'))
fairness=true
maxTotal=32
maxWaitMillis=10000
connectMillis=5000
socketMillis=10000
blockingMillis=15000
source=REDIS_ONLY
guard=UNCHANGED
"@
    $features = if ($Group -in @('features','all')) {@('observation','async','tracking')} else {@($Group)}
    foreach ($feature in $features) {
        $lines = [Collections.Generic.List[string]]::new()
        $lines.Add('T9_DEFAULT coalescing=false configBuilder=false runtimeOptions=false')
        if ($feature -eq 'observation') {
            foreach ($path in @('hit','miss')) {
                foreach ($v in @('disabled','noop','handler')) {
                    $enabled = "$($v -eq 'handler')".ToLowerInvariant()
                    $lines.Add("T9_PREFLIGHT feature=observation path=$path variant=$v total=1 checksum=1 observationEnabled=$enabled instrumentationFailures=0 scopeLeaked=false $(Read-Proof ($path -eq 'hit') 1) $(Common)")
                }
                $tier = if ($path -eq 'hit') {'L1'} else {'REDIS'}
                $lines.Add("T9_OBSERVATION_CONTROL path=$path scopes=1 nativeHook=true tier=$tier outcome=hit instrumentationFailures=0 scopeLeaked=false")
            }
        } elseif ($feature -eq 'async') {
            foreach ($v in @('false','true')) {
                $lines.Add("T9_PREFLIGHT feature=async path=miss variant=$v workers=1 threads=1 virtual=false logicalInFlight=1000 operationsPerInvocation=1000 min=1000 total=16000 checksum=8008000 $(Async-Proof ($v -eq 'true') 16000) $(Common)")
            }
        } else {
            $lines.Add('T9_FLEET_CONTROL rejectedAutoLegacyFalse=true strictOnly=true')
            foreach ($v in @('PUBSUB','SERVER_TRACKING')) {
                $delta = if ($v -eq 'PUBSUB') {1000} else {0}
                $raw = if ($v -eq 'SERVER_TRACKING') {1} else {0}
                $lines.Add("T9_PREFLIGHT feature=tracking path=write variant=$v total=1000 versions=1000 publishDelta=$delta rawInvalidation=$raw $(Health $v) $(Common)")
            }
        }
        Write-Utf8 (Join-Path $dir "preflight-$feature.log") ($lines -join [char]10)
    }
    $order = [Collections.Generic.List[string]]::new()
    foreach ($c in (Cases $Group)) {
        $order.Add($c.label)
        $p50 = if ($c.feature -eq 'observation' -and $c.variant -eq 'noop') {10.2}
            elseif ($c.feature -eq 'observation' -and $c.variant -eq 'handler') {100.0} else {10.0}
        $score = if ($c.mode -eq 'thrpt') {2.0} else {$p50}
        $metric = @{score=$score; scoreError=0.0; scoreConfidence=@($score,$score);
            scoreUnit=$(if ($c.mode -eq 'thrpt') {'ops/us'} else {'us/op'});
            scorePercentiles=@{'0.0'=$p50; '50.0'=$p50; '90.0'=$p50; '95.0'=$p50; '99.0'=$p50;
                '99.9'=$p50; '99.99'=$p50; '99.999'=$p50; '99.9999'=$p50; '100.0'=$p50}}
        $raw = [Collections.Generic.List[object]]::new()
        for ($f = 0; $f -lt 3; $f++) {
            $iterations = [Collections.Generic.List[object]]::new()
            for ($i = 0; $i -lt 5; $i++) {
                if ($c.mode -eq 'thrpt') { $iterations.Add($score) }
                else {
                    $buckets = [Collections.Generic.List[object]]::new()
                    $buckets.Add([object[]]@($p50, 100))
                    $iterations.Add($buckets)
                }
            }
            $raw.Add($iterations)
        }
        if ($c.mode -eq 'thrpt') {$metric.rawData=$raw} else {$metric.rawDataHistogram=$raw}
        $params = @{}; $params[$c.parameter] = $c.variant
        $row = @{jmhVersion='1.37'; benchmark="com.reactor.cachedb.benchmarks.redis.$($c.clazz).$($c.method)";
            mode=$c.mode; threads=1; forks=3; params=$params; warmupIterations=5; measurementIterations=5;
            warmupTime='3 s'; measurementTime='3 s'; warmupBatchSize=1; measurementBatchSize=1;
            jdkVersion='21.0.8'; vmName='SYNTHETIC'; vmVersion='SYNTHETIC'; jvm='/synthetic/java';
            jvmArgs=@('-Xms512m','-Xmx512m','-XX:ActiveProcessorCount=1','-Dcachedb.bench.redis=localhost:1');
            primaryMetric=$metric; secondaryMetrics=@{'gc.alloc.rate.norm'=@{score=1.0}; 'gc.count'=@{score=0.0}}}
        Write-Utf8 (Join-Path $dir "$($c.label).json") (ConvertTo-Json -InputObject @($row) -Depth 24)
        $log = [Collections.Generic.List[string]]::new()
        $log.Add('T9_CONTROLLER xmsMiB=1024 xmxMiB=1024 cpus=1 forkHeapMiB=512')
        $log.Add('will synchronize iterations')
        for ($fork = 101; $fork -le 103; $fork++) {
            $fields = "pid=$fork feature=$($c.feature) path=$($c.path) variant=$($c.variant) mode=$($c.mode) threads=1 virtual=false"
            $extra = if ($c.feature -eq 'async') {'logicalInFlight=1000 operationsPerInvocation=1000'} else {''}
            $log.Add("T9_TRIAL $fields $extra $(Common)")
            for ($i = 0; $i -lt 10; $i++) {
                $proof = switch ($c.feature) {
                    observation {
                        "$(Read-Proof ($c.path -eq 'hit') 1000) observationEnabled=$("$($c.variant -eq 'handler')".ToLowerInvariant()) instrumentationFailures=0 scopeLeaked=false"
                        if ($c.path -eq 'hit') {Health 'PUBSUB'}
                    }
                    async { Async-Proof ($c.variant -eq 'true') 1000 }
                    tracking {
                        $delta = if ($c.path -eq 'write' -and $c.variant -eq 'PUBSUB') {1000} else {0}
                        "publishDelta=$delta $(Health $c.variant)"
                        if ($c.path -eq 'hit') {Read-Proof $true 1000}
                    }
                }
                $log.Add("T9_ITER $fields $extra ordinal=$i min=1000 total=1000 $($proof -join ' ')")
            }
        }
        Write-Utf8 (Join-Path $dir "$($c.label).log") ($log -join [char]10)
    }
    Write-Utf8 (Join-Path $dir 'order.txt') ($order -join [char]10)
    if ($Group -eq 'all') {
        $child = T1-Fixture "$Name/coalescing" 'false' 'false'
        Replace-Evidence $child 'manifest.txt' 'baselineSha256=absent' "baselineSha256=$(Hash (Join-Path $dir 'baseline.jar'))"
    }
    return $dir
}
function Check($Dir, [string]$Group) {
    & $Checker -Path $Dir -CandidateJar (Join-Path $Dir 'candidate.jar') -ExpectedBaselineSha256 (Hash (Join-Path $Dir 'baseline.jar')) -ProcessorCount 1 -Group $Group
}
function Positive([string]$Group, [switch]$ConsolePrefixes) {
    $name = if ($ConsolePrefixes) { "valid-$Group-console-prefixes" } else { "valid-$Group" }
    $dir = Fixture $name $Group
    if ($ConsolePrefixes) {
        foreach ($case in (Cases $Group)) {
            $file = "$($case.label).log"
            Replace-Evidence $dir $file 'T9_TRIAL ' '# Warmup Iteration   1: T9_TRIAL '
            Replace-Evidence $dir $file 'T9_ITER pid=101' '# Warmup Iteration   2: T9_ITER pid=101'
            Replace-Evidence $dir $file 'T9_ITER pid=102' 'Iteration   1: T9_ITER pid=102'
        }
    }
    $output = (Check $dir $Group) -join [char]10
    if ($output -notmatch "T9_$($Group.ToUpperInvariant())_RAW_COVERAGE_AND_NUMERIC_GATES_PASS_NOT_RELEASE_ACCEPTANCE") {
        throw "Missing successful synthetic schema result: $Group"
    }
    if ($Group -eq 'all' -and $output -notmatch 'FAIL_DEFAULT_ENABLED') { throw 'All-group default=false opt-in classification disappeared.' }
    $script:passed++
}
function Negative([string]$Name, [string]$Group, [scriptblock]$Mutation, [string]$Expected) {
    $dir = Fixture $Name $Group
    & $Mutation $dir | Out-Null
    $caught = $false
    try { Check $dir $Group | Out-Null } catch {
        $caught = $true
        if ($_.Exception.Message -notmatch $Expected) { throw "Wrong rejection for $Name : $($_.Exception.Message)" }
    }
    if (!$caught) { throw "Invalid synthetic evidence accepted: $Name" }
    $script:passed++
}
try {
    foreach ($group in @('observation','async','tracking','features','all')) { Positive $group }
    Positive 'features' -ConsolePrefixes
    Negative 'failed-marker' 'features' {param($d) Write-Utf8 (Join-Path $d 'FAILED.txt') 'SYNTHETIC_FAILURE'} 'Incomplete/failed'
    Negative 'jar-digest' 'features' {param($d) Write-Utf8 (Join-Path $d 'candidate.jar') 'CHANGED'} 'identity mismatch'
    Negative 'baseline-digest' 'features' {param($d) Replace-Evidence $d 'manifest.txt' (Hash (Join-Path $d 'baseline.jar')) ('a' * 64)} 'identity mismatch'
    Negative 'source-digest' 'features' {
        param($d)
        $mutationFile = Get-Item -LiteralPath (Join-Path $d 'sources/tools/benchmarks/redis/pom.xml') -Force
        if ($mutationFile.PSIsContainer -or ($mutationFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Mutation target must be a regular fixture file.'
        }
        # File.Copy preserves read-only source permissions; mutate only this owned fixture leaf.
        $mutationFile.IsReadOnly = $false
        Write-Utf8 $mutationFile.FullName 'CHANGED'
    } 'Source snapshot digest'
    Negative 'default-forgery' 'features' {param($d) Replace-Evidence $d 'preflight-observation.log' 'coalescing=false configBuilder=false runtimeOptions=false' 'coalescing=true configBuilder=true runtimeOptions=true'} 'Proof mismatch:'
    foreach ($pathName in @('hit','miss')) {
        Negative "noop-$pathName-regression" 'observation' {
            param($d)
            Change-Row $d "observation-$pathName-sample-noop" {
                param($r)
                foreach ($p in $r.primaryMetric.scorePercentiles.PSObject.Properties) { $p.Value=10.31 }
                foreach ($fork in $r.primaryMetric.rawDataHistogram) {
                    foreach ($iteration in $fork) { $iteration[0][0]=10.31 }
                }
                $r.primaryMetric.score=10.31
            }
        } 'NOOP p50 exceeds 3%'
    }
    Negative 'forged-noop-summary' 'observation' {
        param($d)
        Change-Row $d 'observation-hit-sample-noop' {param($r) $r.primaryMetric.scorePercentiles.'50.0'=10.0}
    } 'JMH histogram percentile mismatch'
    Negative 'noop-fake-enabled' 'observation' {param($d) Replace-Evidence $d 'observation-hit-thrpt-noop.log' 'observationEnabled=false' 'observationEnabled=true'} 'Proof mismatch:'
    Negative 'missing-native-hook' 'observation' {param($d) Replace-Evidence $d 'preflight-observation.log' 'T9_OBSERVATION_CONTROL path=hit' 'REMOVED_CONTROL path=hit'} 'native observation tier controls'
    Negative 'wrong-native-tier' 'observation' {param($d) Replace-Evidence $d 'preflight-observation.log' 'tier=L1' 'tier=REDIS'} 'Proof mismatch:'
    Negative 'missing-cell' 'features' {param($d) Remove-Item -LiteralPath (Join-Path $d 'async-miss-thrpt-true.json')} 'paired matrix'
    Negative 'non-alternating' 'features' {param($d) Replace-Evidence $d 'order.txt' 'observation-hit-thrpt-disabled' 'observation-hit-thrpt-noop'} 'paired matrix'
    Negative 'two-forks' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.primaryMetric.rawData=@($r.primaryMetric.rawData[0],$r.primaryMetric.rawData[1])}} 'three complete raw forks'
    Negative 'four-measurements' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.primaryMetric.rawData[0]=@($r.primaryMetric.rawData[0][0..3])}} 'five measured iterations'
    Negative 'warmup-weakened' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.warmupIterations=4}} 'fixed JMH controls'
    Negative 'wrong-fork-heap' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.jvmArgs[1]='-Xmx1024m'}} 'fork heap/CPU'
    Negative 'wrong-controller-heap' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'xmxMiB=1024' 'xmxMiB=512'} 'Proof mismatch:'
    Negative 'guard-override' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.jvmArgs+= '-Dcachedb.guard.disabled=true'}} 'JVM override'
    Negative 'endpoint-drift' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.jvmArgs[3]='-Dcachedb.bench.redis=other:1'}} 'endpoint changed'
    Negative 'missing-gc' 'async' {param($d) Change-Row $d 'async-miss-thrpt-true' {param($r) $r.secondaryMetrics.PSObject.Properties.Remove('gc.alloc.rate.norm')}} 'GC allocation profiler'
    Negative 'missing-worker-proof' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'T9_ITER pid=101' 'REMOVED_ITER pid=101'} 'warmup and measurement controls'
    Negative 'unknown-console-prefix' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'T9_TRIAL pid=101' 'diagnostic: T9_TRIAL pid=101'} 'warmup and measurement controls'
    Negative 'invalid-console-iteration' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'T9_ITER pid=101' 'Iteration   0: T9_ITER pid=101'} 'warmup and measurement controls'
    Negative 'duplicate-ordinal' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'ordinal=9 ' 'ordinal=8 '} 'iteration ordinal'
    Negative 'offload-used' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'offload=0' 'offload=1'} 'Proof mismatch:'
    Negative 'native-credit-retained' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'reservedBytes=0' 'reservedBytes=1'} 'Proof mismatch:'
    Negative 'singleton-fifo-bypassed' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-false.log' 'transportBypassed=1000' 'transportBypassed=0'} 'physical completion proof'
    Negative 'no-coalescing' 'async' {param($d) Replace-Evidence $d 'async-miss-thrpt-true.log' 'batches=10 coalesced=990' 'batches=1000 coalesced=0'} 'physical completion proof'
    Negative 'strict-publish' 'tracking' {param($d) Replace-Evidence $d 'tracking-write-thrpt-SERVER_TRACKING.log' 'publishDelta=0' 'publishDelta=1'} 'INFO PUBLISH contract'
    Negative 'pubsub-no-positive-control' 'tracking' {param($d) Replace-Evidence $d 'preflight-tracking.log' 'publishDelta=1000' 'publishDelta=0'} 'INFO PUBLISH contract'
    Negative 'fallback-masquerade' 'tracking' {param($d) Replace-Evidence $d 'tracking-hit-thrpt-SERVER_TRACKING.log' 'actualMode=SERVER_TRACKING' 'actualMode=PUBSUB'} 'Proof mismatch:'
    Negative 'readiness-lost' 'tracking' {param($d) Replace-Evidence $d 'tracking-hit-thrpt-SERVER_TRACKING.log' 'tracking=true' 'tracking=false'} 'Proof mismatch:'
    Negative 'generation-changed' 'tracking' {param($d) Replace-Evidence $d 'tracking-hit-thrpt-SERVER_TRACKING.log' 'generationStable=true' 'generationStable=false'} 'Proof mismatch:'
    Negative 'missing-thousand-versions' 'tracking' {param($d) Replace-Evidence $d 'preflight-tracking.log' 'versions=1000' 'versions=999'} 'Proof mismatch:'
    Negative 'raw-write-not-invalidated' 'tracking' {param($d) Replace-Evidence $d 'preflight-tracking.log' 'rawInvalidation=1' 'rawInvalidation=0'} 'Proof mismatch:'
    Negative 'unsafe-mixed-fleet' 'tracking' {param($d) Replace-Evidence $d 'preflight-tracking.log' 'rejectedAutoLegacyFalse=true' 'rejectedAutoLegacyFalse=false'} 'Proof mismatch:'
    Negative 'oom-output' 'async' {param($d) Add-Content -LiteralPath (Join-Path $d 'async-miss-thrpt-true.log') -Value 'java.lang.OutOfMemoryError'} 'Failure text'
    Negative 'oversized-json' 'async' {param($d) $f=[IO.File]::OpenWrite((Join-Path $d 'async-miss-thrpt-false.json'));try{$f.SetLength(64MB+1)}finally{$f.Dispose()}} 'oversized evidence'
    Negative 'oversized-log' 'async' {param($d) $f=[IO.File]::OpenWrite((Join-Path $d 'async-miss-thrpt-false.log'));try{$f.SetLength(16MB+1)}finally{$f.Dispose()}} 'oversized evidence'
    Negative 'all-default-mismatch' 'all' {param($d) Replace-Evidence (Join-Path $d 'coalescing') 'manifest.txt' 'defaultCoalescing=false' 'defaultCoalescing=true'} 'Proof mismatch:'
    Negative 'all-t1-point64-fails' 'all' {param($d) Change-Row (Join-Path $d 'coalescing') 'point64-thrpt-true' {param($r) $r.primaryMetric.score=3.0}} 'point64 throughput gain is below 2x'
    Negative 'all-t1-virtual-fails' 'all' {param($d) Change-Row (Join-Path $d 'coalescing') 'virtual1000-thrpt-true' {param($r) $r.primaryMetric.score=3.0}} 'virtual1000 throughput gain is below 2x'
    Negative 'all-t1-old-schema' 'all' {param($d) Replace-Evidence (Join-Path $d 'coalescing') 'manifest.txt' 'T1_COALESCING_V4' 'T1_COALESCING_V3'} 'Proof mismatch:'
    Negative 'all-t1-missing-compactor' 'all' {
        param($d)
        Replace-Evidence (Join-Path $d 'coalescing') 'virtual1000-sample-true.controller.args' "-prof`ncom.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfiler`n" ''
    } 'Unexpected profiler list/order'
    Negative 'all-t1-compactor-tamper' 'all' {
        param($d)
        Write-Utf8 (Join-Path $d 'coalescing/compactor/com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class') 'CHANGED'
    } 'Helper-only compactor'
    Negative 'all-default-true-regression' 'all' {
        param($d)
        Replace-Evidence $d 'manifest.txt' 'defaultCoalescing=false' 'defaultCoalescing=true'
        foreach ($f in @('observation','async','tracking')) {
            Replace-Evidence $d "preflight-$f.log" 'coalescing=false configBuilder=false runtimeOptions=false' 'coalescing=true configBuilder=true runtimeOptions=true'
        }
        $child = Join-Path $d 'coalescing'
        Replace-Evidence $child 'manifest.txt' 'defaultCoalescing=false' 'defaultCoalescing=true'
        foreach ($v in @('false','true')) {
            Replace-Evidence $child "preflight-$v.log" 'T1_DEFAULT coalescing=false configBuilder=false runtimeOptions=false' 'T1_DEFAULT coalescing=true configBuilder=true runtimeOptions=true'
        }
    } 'artifact default is not false'
    Write-Output ("T9_CHECKER_SELFTEST_PASS cases={0}; synthetic contracts only, NOT Java/provider/performance evidence." -f $script:passed)
} finally {
    if ($temporary) {
        $resolved = [IO.Path]::GetFullPath($base)
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (!$resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or
                [IO.Path]::GetFileName($resolved) -notmatch '^cachedb-t9-checker-[0-9a-f]{32}$') { throw 'Unsafe self-test cleanup target.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    } else { Write-Output "Synthetic fixtures retained at $base" }
}
