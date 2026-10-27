param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$file = Get-Item -LiteralPath $Path
if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt 64MB) {
    throw 'JMH evidence must be a nonempty JSON file, at most 64 MiB.'
}
$results = @(Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json)
if ($results.Count -lt 22 -or $results.Count -gt 256) { throw 'Incomplete or oversized JMH matrix.' }
$cases = @{}
foreach ($item in $results) {
    if ($item.forks -lt 3 -or $item.warmupIterations -lt 5 -or $item.measurementIterations -lt 5) {
        throw "Insufficient forks/iterations: $($item.benchmark)"
    }
    if ($item.mode -notin @('thrpt', 'sample')) { throw 'Both throughput and sampled latency are required.' }
    if ('-Xms512m' -notin $item.jvmArgs -or '-Xmx512m' -notin $item.jvmArgs -or
            '-XX:ActiveProcessorCount=2' -notin $item.jvmArgs) { throw 'JVM resource settings differ from the reference fixture.' }
    if ($item.PSObject.Properties.Name -notcontains 'secondaryMetrics' -or
            $item.secondaryMetrics.PSObject.Properties.Name -notcontains 'gc.alloc.rate.norm') {
        throw "GC allocation evidence missing: $($item.benchmark)"
    }
    $tenant = if ($item.PSObject.Properties.Name -contains 'params' -and
            $item.params.PSObject.Properties.Name -contains 'tenant') { $item.params.tenant } else { '-' }
    $name = $item.benchmark.Substring($item.benchmark.LastIndexOf('.') + 1)
    $key = "$name/$($item.mode)/$($item.threads)/$tenant"
    if ($cases.ContainsKey($key)) { throw "Duplicate benchmark case: $key" }
    $cases[$key] = $true
}
foreach ($mode in @('thrpt', 'sample')) {
    $expected = @("l1HitFindById/$mode/1/-", "findAllMixed100/$mode/1/-", "saveSingle/$mode/1/-",
            "l1MissFindByIdVirtual1000/$mode/1000/-")
    foreach ($threads in @(1, 16, 64)) { $expected += "l1MissFindById/$mode/$threads/-" }
    foreach ($tenant in @('false', 'true')) {
        $expected += "aggregateCount10k/$mode/1/$tenant"
        $expected += "facet10k20Groups/$mode/1/$tenant"
    }
    foreach ($key in $expected) { if (!$cases.ContainsKey($key)) { throw "Missing required benchmark: $key" } }
}
Write-Host "JMH acceptance matrix verified: $($results.Count) cases, >=3 forks, >=5 warmup/measurement iterations, GC evidence."
