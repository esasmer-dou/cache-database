$ErrorActionPreference = 'Stop'
$gate = Join-Path $PSScriptRoot 'check-full-environment-tests.ps1'
$tempRoot = [IO.Path]::GetTempPath()
$root = Join-Path $tempRoot ("cachedb-gate-" + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $root
try {
    foreach ($name in @('HotPathEvolutionRedisTest','RedisQueryIndexDiffRedisTest','RedisIndexAggregateBatchTest',
            'RedisCompositeSignatureTest','RedisCausalReadsTest','CacheCausalContextTest','JdbcReorderingMetricsTest','WriteFenceProvidersIntegrationTest')) {
        $directory = Join-Path $root 'cachedb-storage-redis/target/surefire-reports'
        $null = New-Item -ItemType Directory -Force -Path $directory
        "<testsuite name='example.$name' tests='1' failures='0' errors='0' skipped='0'><testcase name='passes'/></testsuite>" |
                Set-Content (Join-Path $directory "TEST-$name.xml") -Encoding utf8
    }
    $near = Join-Path $directory 'TEST-RedisNearCacheTest.xml'
    $xml = "<testsuite name='example.RedisNearCacheTest' tests='3' failures='0' errors='0' skipped='0'>" +
        (@('refreshOverloadNeverBlocksCallersAndReleasesReservations','separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose',
        'refreshBudgetsAreIndependentAndShutdownCancelsQueuedWork') | ForEach-Object { "<testcase name='$_'/>" }) -join ''
    $xml += '</testsuite>'
    $xml | Set-Content $near -Encoding utf8
    foreach ($module in @('cachedb-integration-tests','cachedb-production-tests')) {
        $directory = Join-Path $root "$module/target/surefire-reports"
        $null = New-Item -ItemType Directory -Force -Path $directory
        "<testsuite name='example.ExecutedTest' tests='1' failures='0' errors='0' skipped='0'/>" |
            Set-Content (Join-Path $directory 'TEST-ExecutedTest.xml') -Encoding utf8
    }
    $summary = Join-Path $root 'target/summary.md'
    & $gate -ReportsRoot $root -SummaryPath $summary
    foreach ($mutation in @('all-skipped','failed','missing','live-method-skipped')) {
        $changed = switch ($mutation) {
            'all-skipped' { $xml.Replace("skipped='0'","skipped='3'") }
            'failed' { $xml.Replace("failures='0'","failures='1'") }
            'missing' { $xml.Replace('example.RedisNearCacheTest','example.RenamedTest') }
            'live-method-skipped' { $xml.Replace("name='refreshOverloadNeverBlocksCallersAndReleasesReservations'/>","name='refreshOverloadNeverBlocksCallersAndReleasesReservations'><skipped/></testcase>") }
        }
        $changed | Set-Content $near -Encoding utf8
        $rejected = $false
        try { & $gate -ReportsRoot $root -SummaryPath $summary }
        catch { $rejected = $true }
        if (!$rejected) { throw "Full environment guard accepted invalid evidence: $mutation" }
    }
    Write-Host 'Full-environment gate self-test passed: valid, absent, failed, all-skipped and partially skipped live suites.'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath($tempRoot),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
