$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach ($provider in @('postgresql','mssql','oracle')) {
    $sample = Join-Path $root "sample-cache-database-$provider"
    $requirements = @{
        'domain/SupplierOffer.java' = @('CacheCompositeIndex', 'softTtlSeconds = 2', 'independentWriteReordering = true')
        'domain/TenantStock.java' = @('CacheTenantId', 'CachePartitionedIndex', 'CacheCompositeIndex')
        'repository/SupplierOfferRepository.java' = @('CacheFacet', 'CacheAggregate', 'countsBySupplier')
        'controls/ControlsStore.java' = @('findAll(ids)', 'queryPartitions', 'inTransaction', 'projected(', 'decimalAverage()')
        'controls/ControlsController.java' = @('X-CacheDB-Causal', 'withTenant', 'withToken', 'max = 64', 'max = 32')
        'controls/OfferWarmHandler.java' = @('CacheJobHandler', 'database.warm', 'withQueryTimeoutSeconds(15)')
    }
    foreach ($path in $requirements.Keys) {
        $source = Get-Content (Join-Path $sample "src/main/java/com/example/cachedb/sample/$path") -Raw
        foreach ($needle in $requirements[$path]) {
            if (!$source.Contains($needle)) { throw "Missing $provider/$path contract: $needle" }
        }
    }
    foreach ($name in @('README.md','README.tr.md')) {
        $text = Get-Content (Join-Path $sample $name) -Raw
        foreach ($needle in @('SupplierOffer.java','ControlsController.java','OfferWarmHandler.java','peerBaseUrl','v018.postman_collection.json')) {
            if (!$text.Contains($needle)) { throw "Incomplete $provider/${name}: $needle" }
        }
        if ($text -match 'After 0\.18\.0 is published|0\.15\.1 processor|0\.15\.1 BOM|Çalışan 0\.14') {
            throw "Stale onboarding version: $provider/$name"
        }
    }
    $collection = Get-Content (Join-Path $sample "postman/cache-database-$provider-v018.postman_collection.json") -Raw | ConvertFrom-Json
    if ($collection.item.Count -ne 40) { throw "Expected 40 0.18 requests for $provider" }
    if (@($collection.item | Group-Object name | Where-Object Count -gt 1).Count) { throw 'Duplicate request name' }
    $scripts = ($collection.item | ForEach-Object { $_.event.script.exec }) -join "`n"
    foreach ($status in @(400,401,403,404,409)) {
        if (!$scripts.Contains("status($status)")) { throw "Missing negative contract $status for $provider" }
    }
    if (!$scripts.Contains('sqlDeadline') -or !$scripts.Contains('warmDeadline') -or !$scripts.Contains('setNextRequest')) {
        throw 'Unbounded or missing explicit SQL/warm polling contract'
    }
    $test = Get-Content (Join-Path $sample 'src/test/java/com/example/cachedb/sample/controls/ControlsScenarios.java') -Raw
    foreach ($needle in @('warm','decimalAverage','cache.get','X-CacheDB-Causal')) {
        if (!$test.Contains($needle)) { throw "Missing $provider integration coverage: $needle" }
    }
}
Write-Host '0.18 sample implementations, compiled EN/TR examples and 40-request API contracts are present for all providers.'
