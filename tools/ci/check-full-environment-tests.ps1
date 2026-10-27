param([string]$ReportsRoot = '', [string]$SummaryPath = 'target/full-environment-tests.md')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$ReportsRoot) { $ReportsRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
$required = @('HotPathEvolutionRedisTest','RedisQueryIndexDiffRedisTest','RedisIndexAggregateBatchTest',
    'RedisNearCacheTest','WriteFenceProvidersIntegrationTest','RedisCompositeSignatureTest','RedisCausalReadsTest',
    'CacheCausalContextTest','JdbcReorderingMetricsTest')
$records = @()
$reports = @(Get-ChildItem -LiteralPath $ReportsRoot -Recurse -Filter 'TEST-*.xml' -File |
        Where-Object { $_.FullName -match '[/\\]target[/\\](surefire|failsafe)-reports[/\\]' } | Select-Object -First 10001)
if ($reports.Count -gt 10000) { throw 'Test evidence exceeds 10000 report files.' }
$totalBytes = 0L
foreach ($file in $reports) {
    $totalBytes += $file.Length
    if ($file.Length -gt 32MB -or $totalBytes -gt 128MB) { throw 'Test evidence exceeds the 32 MiB file / 128 MiB total budget.' }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.MaxCharactersInDocument = 32MB
    $reader = [Xml.XmlReader]::Create($file.FullName,$settings)
    try { $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) }
    finally { $reader.Dispose() }
    $suite = $xml.DocumentElement
    if ($suite.LocalName -ne 'testsuite') { throw "Unsupported test report: $($file.Name)" }
    $relative = [IO.Path]::GetRelativePath($ReportsRoot,$file.FullName).Replace('\','/')
    $records += [pscustomobject]@{ module=$relative.Split('/')[0]; name=$suite.GetAttribute('name');
        tests=[long]$suite.GetAttribute('tests'); failures=[long]$suite.GetAttribute('failures');
        errors=[long]$suite.GetAttribute('errors'); skipped=[long]$suite.GetAttribute('skipped'); xml=$xml }
}
if (!$records.Count) { throw 'No full-environment test evidence found.' }
foreach ($record in $records) {
    if ($record.failures -or $record.errors) { throw "Full-environment test failure: $($record.name)" }
}
foreach ($name in $required) {
    $matches = @($records | Where-Object { $_.name.EndsWith(".$name") })
    if (!$matches.Count -or @($matches | Where-Object { $_.tests -gt $_.skipped }).Count -ne $matches.Count) {
        throw "Required suite missing or entirely skipped: $name"
    }
}
foreach ($module in @('cachedb-integration-tests','cachedb-production-tests')) {
    $matches = @($records | Where-Object { $_.module -eq $module -and $_.tests -gt $_.skipped })
    if (!$matches.Count) { throw "Required module did not execute: $module" }
}
$near = $records | Where-Object { $_.name.EndsWith('.RedisNearCacheTest') } | Select-Object -First 1
foreach ($method in @('refreshOverloadNeverBlocksCallersAndReleasesReservations','separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose',
        'refreshBudgetsAreIndependentAndShutdownCancelsQueuedWork')) {
    $cases = @($near.xml.SelectNodes("//testcase[@name='$method']"))
    if (!$cases.Count -or @($cases | Where-Object { $_.SelectSingleNode('skipped') }).Count) {
        throw "Required live Redis test skipped or absent: $method"
    }
}
$lines = @('# Full Environment Test Gate','','| Module | Tests | Failures | Errors | Skipped |','| --- | ---: | ---: | ---: | ---: |')
$modules = @($records.module | Sort-Object -Unique)
$pomPath = Join-Path $ReportsRoot 'pom.xml'
if (Test-Path -LiteralPath $pomPath) {
    [xml]$pom = Get-Content -LiteralPath $pomPath -Raw
    $modules = @([string]$pom.project.artifactId) + @($pom.project.modules.module | ForEach-Object { [string]$_ })
}
foreach ($module in $modules) {
    $moduleRecords = @($records | Where-Object { $_.module -eq $module })
    $totals = if ($moduleRecords.Count) {
        @('tests','failures','errors','skipped') | ForEach-Object { ($moduleRecords | Measure-Object -Property $_ -Sum).Sum }
    } else { @(0,0,0,0) }
    $lines += "| $module | $($totals -join ' | ') |"
}
$null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $SummaryPath)
$lines | Set-Content -LiteralPath $SummaryPath -Encoding utf8
Write-Host "Full-environment gate passed: $($records.Count) suites, required Redis/provider suites and both test modules executed."
