param(
    [string]$ReportsRoot = '',
    [string]$SummaryPath = 'target/full-environment-tests.md',
    [switch]$ClusterOnly,
    [string]$OriginalCaseIndexPath = '',
    [string]$OriginalCaseIndexSha256 = '',
    [string]$EvidenceRoot = '',
    [string[]]$PhysicalExecutionIds = @()
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$ReportsRoot) { $ReportsRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
$contracts = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'required-environment-suites.psd1')
$required = if ($ClusterOnly) { @($contracts.Cluster) } else { @($contracts.Standalone) }
$requiredMethods = if ($ClusterOnly) { $contracts.ClusterMethods } else { $contracts.StandaloneMethods }
if (!$required.Count -or @($required | Sort-Object -Unique).Count -ne $required.Count) {
    throw 'Required-suite manifest is empty or contains duplicates.'
}
if ($OriginalCaseIndexPath -or $OriginalCaseIndexSha256 -or $EvidenceRoot -or $PhysicalExecutionIds.Count) {
    if (!$OriginalCaseIndexPath -or !$OriginalCaseIndexSha256 -or !$EvidenceRoot -or !$PhysicalExecutionIds.Count) {
        throw 'Original-case mode requires index path, SHA-256, evidence root and physical execution IDs.'
    }
    . (Join-Path $PSScriptRoot 'k8s-original-case-provenance.ps1')
    $unionMethods = @{}
    foreach ($key in $requiredMethods.Keys) { $unionMethods[$key] = @($requiredMethods[$key]) }
    if (!$ClusterOnly) {
        $nearMethods = @('refreshOverloadNeverBlocksCallersAndReleasesReservations','separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose',
            'refreshBudgetsAreIndependentAndShutdownCancelsQueuedWork')
        $unionMethods['RedisNearCacheTest'] = @(@($unionMethods['RedisNearCacheTest']) + $nearMethods | Where-Object { $_ } | Select-Object -Unique)
    }
    $expectedObligation = if ($ClusterOnly) { 'cluster' } else { 'standalone' }
    $coverage = Assert-K8sOriginalCaseProvenance -IndexPath $OriginalCaseIndexPath -IndexSha256 $OriginalCaseIndexSha256 `
        -EvidenceRoot $EvidenceRoot -ExpectedObligationId $expectedObligation -ExecutionIds $PhysicalExecutionIds `
        -RequiredSuites $required -RequiredMethods $unionMethods
    if (!$ClusterOnly) {
    foreach ($module in @('cachedb-integration-tests','cachedb-production-tests')) {
        $pattern = '(^|/)' + [regex]::Escape($module) + '/target/(surefire|failsafe)-reports/TEST-[^/]+\.xml$'
        if (!@($coverage.reports | Where-Object { $_.path -cmatch $pattern -and $_.tests -gt 0 }).Count) {
            throw "Required module did not execute: $module"
        }
    }
    # Keep the source-declared eight bare-name occurrences; partial 4+4 reports are not eight invocations.
    $repeatedSuite = 'com.reactor.cachedb.spring.boot.cache.CacheDbCacheSubscriptionRedisTest'
    $repeatedMethod = 'reconnectAndCloseNeverReturnPubsubFramesToTheSingleCommandConnection'
    $repeatedPattern = '(^|/)cachedb-spring-boot-starter/target/surefire-reports/TEST-' + [regex]::Escape($repeatedSuite) + '\.xml$'
    $repeatedReports = @($coverage.reports | Where-Object { $_.suiteName -ceq $repeatedSuite })
    $repeatedCases = @($coverage.coveredCases | Where-Object { $_.className -ceq $repeatedSuite })
    if ($repeatedCases.Count -ne 8 -or @($repeatedCases | Where-Object { $_.name -cne $repeatedMethod }).Count -or
            !@($repeatedReports | Where-Object { $_.path -cmatch $repeatedPattern -and $_.tests -eq 8 }).Count -or
            @($repeatedReports | Where-Object { $_.path -cnotmatch $repeatedPattern -or @($_.cases | Where-Object { $_.name -cne $repeatedMethod }).Count }).Count) {
        throw "Required repeated container must prove eight original bare-name occurrences: $repeatedSuite/$repeatedMethod"
    }
    }
    $lines = @('# Original Case Coverage Qualification','',
        "Index SHA-256: $($coverage.indexSha256)",
        "Required suites covered: $($coverage.requiredSuiteBindings.Count)",
        "Original reports retained: $($coverage.reports.Count)",
        "Original testcase records retained: $($coverage.cases.Count)",
        "Explicit non-passing original closures: $($coverage.closures.Count)",
        '', 'Semantic coverage only. Original failures/skips remain unchanged. No aggregate product process or merged JUnit is asserted.')
    $parent = Split-Path -Parent $SummaryPath
    if ($parent) { $null = New-Item -ItemType Directory -Force -Path $parent }
    $lines | Set-Content -LiteralPath $SummaryPath -Encoding utf8
    Write-Host "Original-case coverage qualified: $($coverage.reports.Count) original reports; $($required.Count) required suites. Not canonical aggregate execution."
    return
}
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
    foreach ($element in $xml.GetElementsByTagName('*')) {
        if ($element.LocalName.IndexOf('flaky',[StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $element.LocalName.IndexOf('rerun',[StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "Rerun/flaky test evidence is forbidden: $($file.Name)/$($element.LocalName)"
        }
    }
    $cases = @($suite.SelectNodes('testcase'))
    if ($cases.Count -ne [long]$suite.GetAttribute('tests') -or
            @($suite.SelectNodes('testcase/skipped')).Count -ne [long]$suite.GetAttribute('skipped') -or
            @($suite.SelectNodes('testcase/failure')).Count -ne [long]$suite.GetAttribute('failures') -or
            @($suite.SelectNodes('testcase/error')).Count -ne [long]$suite.GetAttribute('errors')) {
        throw "Test report counters disagree with testcase results: $($file.Name)"
    }
    $relative = [IO.Path]::GetRelativePath($ReportsRoot,$file.FullName).Replace('\','/')
    $records += [pscustomobject]@{ module=$relative.Split('/')[0]; name=$suite.GetAttribute('name'); report=$relative;
        tests=[long]$suite.GetAttribute('tests'); failures=[long]$suite.GetAttribute('failures');
        errors=[long]$suite.GetAttribute('errors'); skipped=[long]$suite.GetAttribute('skipped'); xml=$xml }
}
if (!$records.Count) { throw 'No full-environment test evidence found.' }
foreach ($record in $records) {
    if ($record.failures -or $record.errors) { throw "Full-environment test failure: $($record.name)" }
}
if (!$ClusterOnly) {
    # This source-declared @RepeatedTest(8) emits bare names, not parameterized invocation indices.
    $repeatedSuite = 'com.reactor.cachedb.spring.boot.cache.CacheDbCacheSubscriptionRedisTest'
    $repeatedMethod = 'reconnectAndCloseNeverReturnPubsubFramesToTheSingleCommandConnection'
    $repeatedReport = "cachedb-spring-boot-starter/target/surefire-reports/TEST-$repeatedSuite.xml"
    $repeatedRecords = @($records | Where-Object { $_.name.EndsWith('.CacheDbCacheSubscriptionRedisTest') })
    if ($repeatedRecords.Count -ne 1) {
        throw "Required repeated container missing or ambiguous: $repeatedSuite"
    }
    $repeated = $repeatedRecords[0]
    $repeatedCases = @($repeated.xml.SelectNodes('/testsuite/testcase'))
    if ($repeated.name -cne $repeatedSuite -or $repeated.report -cne $repeatedReport -or
            $repeated.tests -ne 8 -or $repeatedCases.Count -ne 8 -or
            @($repeatedCases | Where-Object {
                $_.GetAttribute('classname') -cne $repeatedSuite -or
                $_.GetAttribute('name') -cne $repeatedMethod -or
                $_.SelectSingleNode('skipped|failure|error')
            }).Count) {
        throw "Required repeated container must contain eight passed bare-name testcases: $repeatedSuite/$repeatedMethod"
    }
}
foreach ($name in $required) {
    $matches = @($records | Where-Object { $_.name.EndsWith(".$name") })
    if (!$matches.Count -or @($matches | Where-Object { $_.tests -gt $_.skipped }).Count -ne $matches.Count) {
        throw "Required suite missing or entirely skipped: $name"
    }
    # The live Cluster method has its own mandatory lane. All other required cases must run here.
    foreach ($record in $matches) {
        foreach ($case in @($record.xml.SelectNodes('//testcase[skipped]'))) {
            if (!$ClusterOnly -and $contracts.ClusterMethods.ContainsKey($name) -and
                    $case.GetAttribute('name') -in $contracts.ClusterMethods[$name]) { continue }
            throw "Required test was skipped: $name/$($case.GetAttribute('name'))"
        }
        if ($requiredMethods.ContainsKey($name)) {
            foreach ($method in $requiredMethods[$name]) {
                $methodCases = @($record.xml.SelectNodes('/testsuite/testcase') | Where-Object {
                    $_.GetAttribute('classname') -ceq $record.name -and $_.GetAttribute('name') -ceq $method
                })
                if (!$methodCases.Count -or @($methodCases | Where-Object {
                    $_.SelectSingleNode('skipped|failure|error')
                }).Count) {
                    throw "Required live contract test absent or not passed: $name/$method"
                }
            }
        }
    }
}
foreach ($module in $(if ($ClusterOnly) { @() } else { @('cachedb-integration-tests','cachedb-production-tests') })) {
    $matches = @($records | Where-Object { $_.module -eq $module -and $_.tests -gt $_.skipped })
    if (!$matches.Count) { throw "Required module did not execute: $module" }
}
if (!$ClusterOnly) {
$near = $records | Where-Object { $_.name.EndsWith('.RedisNearCacheTest') } | Select-Object -First 1
foreach ($method in @('refreshOverloadNeverBlocksCallersAndReleasesReservations','separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose',
        'refreshBudgetsAreIndependentAndShutdownCancelsQueuedWork')) {
    $cases = @($near.xml.SelectNodes("//testcase[@name='$method']"))
    if (!$cases.Count -or @($cases | Where-Object { $_.SelectSingleNode('skipped') }).Count) {
        throw "Required live Redis test skipped or absent: $method"
    }
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
Write-Host "Environment gate passed: $($records.Count) suites; $($required.Count) required contracts executed (ClusterOnly=$ClusterOnly)."
