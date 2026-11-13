# Synthetic format fixtures only. No benchmark or product code is executed.
function New-JmhTestRow([string]$Mode = 'thrpt', [double]$Score = 100,
        [string]$Benchmark = 'fixture', [int]$Threads = 1) {
    $raw = [Collections.Generic.List[object]]::new()
    $gcRaw = [Collections.Generic.List[object]]::new()
    for ($fork = 0; $fork -lt 3; $fork++) {
        $iterations = [Collections.Generic.List[object]]::new()
        $gcIterations = [Collections.Generic.List[object]]::new()
        for ($iteration = 0; $iteration -lt 5; $iteration++) {
            if ($Mode -ceq 'sample') {
                $buckets = [Collections.Generic.List[object]]::new()
                $buckets.Add([object[]]@($Score, 100))
                $iterations.Add($buckets)
            } else { $iterations.Add($Score) }
            $gcIterations.Add(10.0)
        }
        $raw.Add($iterations)
        $gcRaw.Add($gcIterations)
    }
    $metric = [ordered]@{ score = $Score; scoreError = 0.0; scoreConfidence = @($Score, $Score);
        scoreUnit = $(if ($Mode -ceq 'thrpt') { 'ops/us' } else { 'us/op' }) }
    if ($Mode -ceq 'sample') {
        $percentiles = [ordered]@{}
        foreach ($key in @('0.0','50.0','90.0','95.0','99.0','99.9','99.99','99.999','99.9999','100.0')) {
            $percentiles[$key] = $Score
        }
        $metric.scorePercentiles = $percentiles
        $metric.rawDataHistogram = $raw
    } else { $metric.rawData = $raw }
    $arguments = @('-Xms512m', '-Xmx512m', '-XX:ActiveProcessorCount=2', '-Dcachedb.bench.redis=redis:6379')
    if ($Threads -eq 1000) {
        $arguments += @('-Djmh.executor=CUSTOM',
            '-Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor')
    }
    $row = [ordered]@{ benchmark = $Benchmark; mode = $Mode; threads = $Threads;
        jmhVersion = '1.37'; jdkVersion = '21'; vmName = 'fixture'; vmVersion = 'fixture';
        forks = 3; warmupIterations = 5; measurementIterations = 5; warmupTime = '3 s'; measurementTime = '3 s';
        warmupBatchSize = 1; measurementBatchSize = 1; jvmArgs = $arguments; primaryMetric = $metric;
        secondaryMetrics = @{ 'gc.alloc.rate.norm' = @{ score = 10.0; scoreError = 0.0;
            scoreConfidence = @(10.0, 10.0); scoreUnit = 'B/op'; rawData = $gcRaw } } }
    return (ConvertTo-Json -InputObject $row -Depth 20 | ConvertFrom-Json)
}

function New-JmhTestInstrumentationContract {
    $profiler = 'com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler'
    return [pscustomobject]@{
        controllerProfilers = @($profiler, 'gc')
        helpers = @(@('resource-collector', 'resource-launcher', 'sidecar-writer', 'sidecar-launcher', "profiler:$profiler") |
            ForEach-Object { [pscustomobject]@{ identity = $_; sha256 = ('a' * 64) } })
        sampler = [pscustomobject]@{ intervalMs = 250; maxRowsPerFile = 24000; maxBytesPerFile = 16777216; maxForks = 128;
            redisTarget = 'UNAVAILABLE_EXTERNAL_TARGET_NOT_SUPPLIED' }
    }
}

function New-JmhTestInstrumentation([object[]]$Rows) {
    $contracts = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($row in $Rows) { $contracts.Add((Get-JmhCaseKey $row), (New-JmhTestInstrumentationContract)) }
    return ,$contracts
}

function Write-JmhTestInstrumentation([string]$Path, [object[]]$Rows) {
    # Explicit synthetic rebinding is confined to disposable fixtures, never retained evidence.
    if (Test-Path -LiteralPath "$Path.instrumentation") { [IO.File]::Delete("$Path.instrumentation") }
    Write-JmhInstrumentation $Path $Rows (New-JmhTestInstrumentation $Rows)
}
