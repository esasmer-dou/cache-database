param([Parameter(Mandatory)][string]$Path, [ValidateSet(1, 2)][int]$ProcessorCount = 2,
    [string]$LegacyVirtualLivenessEvidence = '', [switch]$RetainedBaselineFormatOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/jmh-evidence-validation.ps1"
$file = Get-Item -LiteralPath $Path
if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt 64MB) {
    throw 'JMH evidence must be a nonempty JSON file, at most 64 MiB.'
}
$legacyUnavailable = ![string]::IsNullOrWhiteSpace($LegacyVirtualLivenessEvidence)
if ($legacyUnavailable) { & "$PSScriptRoot/check-legacy-virtual-liveness.ps1" -Path $LegacyVirtualLivenessEvidence | Out-Null }
$results = @(Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json)
$minimum = if ($legacyUnavailable) { 20 } else { 22 }
if ($results.Count -lt $minimum -or $results.Count -gt 256) { throw 'Incomplete or oversized JMH matrix.' }
$cases = @{}
foreach ($item in $results) {
    Assert-JmhEvidenceRow $item
    if (!$RetainedBaselineFormatOnly) {
        foreach ($duration in @('warmupTime', 'measurementTime')) {
            if ($item.$duration -isnot [string] -or $item.$duration -cnotmatch '\A3 ?s\z') {
                throw "Strict JMH acceptance requires 3 s for ${duration}: $($item.benchmark)"
            }
        }
    }
    if ($item.forks -lt 3 -or $item.warmupIterations -lt 5 -or $item.measurementIterations -lt 5) {
        throw "Insufficient forks/iterations: $($item.benchmark)"
    }
    if ($item.mode -notin @('thrpt', 'sample')) { throw 'Both throughput and sampled latency are required.' }
    if ('-Xms512m' -notin $item.jvmArgs -or '-Xmx512m' -notin $item.jvmArgs -or
            "-XX:ActiveProcessorCount=$ProcessorCount" -notin $item.jvmArgs -or
            @($item.jvmArgs | Where-Object { $_ -like '-XX:ActiveProcessorCount=*' } | Select-Object -Unique).Count -ne 1) {
        throw 'JVM resource settings differ from the reference fixture.'
    }
    if ($item.PSObject.Properties.Name -notcontains 'secondaryMetrics' -or
            $item.secondaryMetrics.PSObject.Properties.Name -notcontains 'gc.alloc.rate.norm') {
        throw "GC allocation evidence missing: $($item.benchmark)"
    }
    $tenant = if ($item.PSObject.Properties.Name -contains 'params' -and
            $item.params.PSObject.Properties.Name -contains 'tenant') { $item.params.tenant } else { '-' }
    $name = $item.benchmark.Substring($item.benchmark.LastIndexOf('.') + 1)
    if ($name -eq 'l1MissFindByIdVirtual1000' -and
            ('-Djmh.executor=CUSTOM' -notin $item.jvmArgs -or
             '-Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor' -notin $item.jvmArgs)) {
        throw 'Virtual-worker evidence must use the bounded virtual-thread executor.'
    }
    $key = "$name/$($item.mode)/$($item.threads)/$tenant"
    if ($cases.ContainsKey($key)) { throw "Duplicate benchmark case: $key" }
    $cases[$key] = $true
}
foreach ($mode in @('thrpt', 'sample')) {
    $expected = @("l1HitFindById/$mode/1/-", "findAllMixed100/$mode/1/-", "saveSingle/$mode/1/-")
    if (!$legacyUnavailable) { $expected += "l1MissFindByIdVirtual1000/$mode/1000/-" }
    foreach ($threads in @(1, 16, 64)) { $expected += "l1MissFindById/$mode/$threads/-" }
    foreach ($tenant in @('false', 'true')) {
        $expected += "aggregateCount10k/$mode/1/$tenant"
        $expected += "facet10k20Groups/$mode/1/$tenant"
    }
    foreach ($key in $expected) { if (!$cases.ContainsKey($key)) { throw "Missing required benchmark: $key" } }
}
if ($RetainedBaselineFormatOnly) {
    Write-Host "JMH_FORMAT_ONLY_NOT_ACCEPTANCE: $($results.Count) retained cases; iteration durations are not acceptance-qualified."
} else {
    Write-Host "JMH acceptance matrix verified: $($results.Count) cases, 3 s warmup/measurement iterations, complete raw forks/iterations, finite metrics/CI/GC, explicit units, histogram consistency."
}
if ($legacyUnavailable) { Write-Host 'Scope: 20 comparable legacy cases only. The candidate still requires the complete 22-case matrix.' }
