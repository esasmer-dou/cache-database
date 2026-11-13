param([string]$Checker = (Join-Path $PSScriptRoot 'check-t1-coalescing-evidence.ps1'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Synthetic parser/gate fixtures, never Redis/JMH measurements and never performance evidence.
$base = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('cachedb-t1-schema-selftest-' + [guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($base)
function Write-Utf8($Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false)) }
function Hash($Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Fixture([string]$Name, [string]$Default, [string]$Proof, [string]$Slow = '') {
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
                    jvmArgs=$jvmArgs; primaryMetric=$metric; secondaryMetrics=@{'gc.alloc.rate.norm'=@{score=1.0}}}
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
function Check($Dir) { & $Checker -Path $Dir -CandidateJar (Join-Path $Dir 'candidate.jar') -ProcessorCount 1 }
function Reject($Dir, [string]$Expected) {
    $caught = $false
    try { Check $Dir | Out-Null } catch {
        $caught = $true
        if ($_.Exception.Message -notmatch $Expected) { throw "Wrong rejection for $Dir : $($_.Exception.Message)" }
    }
    if (!$caught) { throw "Checker accepted invalid fixture: $Dir" }
}
try {
    $valid = Fixture 'false-optin-valid' 'false' 'false'
    $output = (Check $valid) -join [char]10
    if ($output -notmatch 'FAIL_DEFAULT_ENABLED' -or
            $output -notmatch 'NUMERIC_T1_OPT_IN_GATE_PASS_DEFAULT_DISABLED_NOT_RELEASE_ACCEPTANCE') {
        throw 'Default=false must retain the point1 failure classification and permit only the opt-in gate.'
    }
    Reject (Fixture 'true-artifact' 'true' 'true') 'artifact default is not false'
    Reject (Fixture 'fake-false-manifest' 'false' 'true') 'Qualification mismatch:'
    Reject (Fixture 'missing-default-proof' 'false' 'missing') 'default proof per preflight'
    Reject (Fixture 'slow-point64' 'false' 'false' 'point64') 'point64 throughput gain is below 2x'
    Reject (Fixture 'slow-virtual1000' 'false' 'false' 'virtual1000') 'virtual1000 throughput gain is below 2x'
    $forged = Fixture 'forged-summary' 'false' 'false'
    $path = Join-Path $forged 'point1-sample-true.json'
    $rows = @([IO.File]::ReadAllText($path) | ConvertFrom-Json)
    $rows[0].primaryMetric.scorePercentiles.'50.0'=10.0
    Write-Utf8 $path (ConvertTo-Json -InputObject $rows -Depth 20)
    Reject $forged 'JMH histogram percentile mismatch'
    foreach ($case in @('old-schema', 'old-controller', 'missing', 'duplicate', 'reversed', 'wrong-cell', 'unexpected',
            'missing-helper', 'tampered-helper', 'extra-helper-class', 'classpath-shadow', 'missing-resource',
            'missing-platform-resource', 'missing-virtual-throughput-resource', 'duplicate-resource', 'resource-after-gc',
            'missing-resource-classpath', 'resource-classpath-shadow', 'reversed-overlays', 'short-warmup', 'short-measurement')) {
        $bad = Fixture $case 'false' 'false'
        $argsPath = Join-Path $bad 'virtual1000-sample-true.controller.args'
        $argsText = [IO.File]::ReadAllText($argsPath)
        $helperName = 'com.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfiler'
        $resourceName = 'com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler'
        $normalPair = "-prof`n$resourceName`n-prof`ngc"
        $pair = "-prof`n$helperName`n$normalPair"
        $expected = 'Unexpected profiler list/order'
        switch ($case) {
            'old-schema' {
                $p=Join-Path $bad 'manifest.txt'; Write-Utf8 $p ([IO.File]::ReadAllText($p).Replace('T1_COALESCING_V4','T1_COALESCING_V3'))
                $expected='Qualification mismatch: schema'
            }
            'old-controller' {
                $p=Join-Path $bad 'manifest.txt'; Write-Utf8 $p ([IO.File]::ReadAllText($p).Replace('controllerHeapMiB=512','controllerHeapMiB=1024'))
                $expected='Qualification mismatch: controllerHeapMiB'
            }
            'missing' { Write-Utf8 $argsPath ($argsText.Replace($pair,$normalPair)) }
            'duplicate' { Write-Utf8 $argsPath ($argsText.Replace($pair,"$pair`n-prof`n$helperName")) }
            'reversed' { Write-Utf8 $argsPath ($argsText.Replace($pair,"$normalPair`n-prof`n$helperName")) }
            'wrong-cell' {
                $p=Join-Path $bad 'virtual1000-thrpt-true.controller.args'
                Write-Utf8 $p ([IO.File]::ReadAllText($p).Replace($normalPair,$pair))
            }
            'unexpected' { Write-Utf8 $argsPath ($argsText.Replace($pair,"$pair`n-prof`nstack")) }
            'missing-helper' { [IO.File]::Delete((Join-Path $bad 'compactor/com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class')); $expected='Helper-only compactor' }
            'tampered-helper' { Write-Utf8 (Join-Path $bad 'compactor/com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class') 'CHANGED'; $expected='Helper-only compactor' }
            'extra-helper-class' { Write-Utf8 (Join-Path $bad 'compactor/Other.class') 'CHANGED'; $expected='Helper-only compactor' }
            'classpath-shadow' { Write-Utf8 $argsPath ($argsText.Replace("/synthetic/evidence/$case/compactor:",'/untrusted:')); $expected='Unexpected controller arguments' }
            'missing-resource' { Write-Utf8 $argsPath ($argsText.Replace($normalPair,"-prof`ngc")) }
            'missing-platform-resource' {
                $p=Join-Path $bad 'point1-thrpt-true.controller.args'
                Write-Utf8 $p ([IO.File]::ReadAllText($p).Replace($normalPair,"-prof`ngc"))
            }
            'missing-virtual-throughput-resource' {
                $p=Join-Path $bad 'virtual1000-thrpt-true.controller.args'
                Write-Utf8 $p ([IO.File]::ReadAllText($p).Replace($normalPair,"-prof`ngc"))
            }
            'duplicate-resource' { Write-Utf8 $argsPath ($argsText.Replace($normalPair,"-prof`n$resourceName`n$normalPair")) }
            'resource-after-gc' { Write-Utf8 $argsPath ($argsText.Replace($normalPair,"-prof`ngc`n-prof`n$resourceName")) }
            'missing-resource-classpath' {
                $p=Join-Path $bad 'point1-thrpt-true.controller.args'
                Write-Utf8 $p ([IO.File]::ReadAllText($p).Replace("/synthetic/evidence/$case/resource-tools/classes:",''))
                $expected='Unexpected controller arguments'
            }
            'resource-classpath-shadow' {
                Write-Utf8 $argsPath ($argsText.Replace("/synthetic/evidence/$case/resource-tools/classes:",'/untrusted:'))
                $expected='Unexpected controller arguments'
            }
            'reversed-overlays' {
                $overlays="/synthetic/evidence/$case/compactor:/synthetic/evidence/$case/resource-tools/classes:"
                $reversed="/synthetic/evidence/$case/resource-tools/classes:/synthetic/evidence/$case/compactor:"
                Write-Utf8 $argsPath ($argsText.Replace($overlays,$reversed))
                $expected='Unexpected controller arguments'
            }
            { $_ -in @('short-warmup', 'short-measurement') } {
                $p=Join-Path $bad 'point1-thrpt-true.json'
                $rows=@([IO.File]::ReadAllText($p) | ConvertFrom-Json)
                $field=if ($case -eq 'short-warmup') { 'warmupTime' } else { 'measurementTime' }
                $rows[0].$field='1 s'
                Write-Utf8 $p (ConvertTo-Json -InputObject $rows -Depth 20)
                $expected='Wrong fixed JMH settings'
            }
        }
        Reject $bad $expected
    }
    Write-Output 'T1_SCHEMA_SELFTEST_PASS: relocated synthetic positive and exact profiler/classpath/duration negative cases; NOT benchmark evidence.'
} finally {
    $resolved = [IO.Path]::GetFullPath($base)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notmatch '^cachedb-t1-schema-selftest-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside the verified private self-test directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
