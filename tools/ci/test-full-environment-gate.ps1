$ErrorActionPreference = 'Stop'
$gate = Join-Path $PSScriptRoot 'check-full-environment-tests.ps1'
$tempRoot = [IO.Path]::GetTempPath()
$root = Join-Path $tempRoot ("cachedb-gate-" + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $root
try {
    $contracts = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'required-environment-suites.psd1')
    $repeatedSuite = 'com.reactor.cachedb.spring.boot.cache.CacheDbCacheSubscriptionRedisTest'
    $repeatedMethod = 'reconnectAndCloseNeverReturnPubsubFramesToTheSingleCommandConnection'
    $repeatedDirectory = Join-Path $root 'cachedb-spring-boot-starter/target/surefire-reports'
    $repeatedReport = Join-Path $repeatedDirectory "TEST-$repeatedSuite.xml"
    foreach ($name in @($contracts.Standalone | Where-Object { $_ -ne 'RedisNearCacheTest' })) {
        $directory = Join-Path $root 'cachedb-storage-redis/target/surefire-reports'
        $suiteName = "example.$name"
        $reportName = "TEST-$name.xml"
        $methods = if ($contracts.StandaloneMethods.ContainsKey($name)) { @($contracts.StandaloneMethods[$name]) } else { @('passes') }
        if ($name -eq 'CacheDbCacheSubscriptionRedisTest') {
            $directory = $repeatedDirectory
            $suiteName = $repeatedSuite
            $reportName = "TEST-$suiteName.xml"
            $methods = @($repeatedMethod) * 8
        }
        $null = New-Item -ItemType Directory -Force -Path $directory
        $suiteXml = "<testsuite name='$suiteName' tests='$($methods.Count)' failures='0' errors='0' skipped='0'>"
        $suiteXml += (@($methods | ForEach-Object { "<testcase classname='$suiteName' name='$_'/>" }) -join '')
        $suiteXml += '</testsuite>'
        $suiteXml | Set-Content (Join-Path $directory $reportName) -Encoding utf8
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
        "<testsuite name='example.ExecutedTest' tests='1' failures='0' errors='0' skipped='0'><testcase name='passes'/></testsuite>" |
            Set-Content (Join-Path $directory 'TEST-ExecutedTest.xml') -Encoding utf8
    }
    $summary = Join-Path $root 'target/summary.md'
    & $gate -ReportsRoot $root -SummaryPath $summary
    $retryNodes = @('flakyFailure', 'flakyError', 'rerunFailure', 'rerunError')
    $retryReports = @($repeatedReport,
        (Join-Path $root 'cachedb-storage-redis/target/surefire-reports/TEST-GeneratedInitialNativeFixtureDdlTest.xml'))
    foreach ($retryReport in $retryReports) {
        $validRetryXml = [IO.File]::ReadAllText($retryReport)
        foreach ($nodeName in $retryNodes) {
            [xml]$retryXml = $validRetryXml
            $retryNode = $retryXml.CreateElement($nodeName)
            $retryNode.SetAttribute('message', 'prior attempt failed')
            $retryNode.SetAttribute('type', 'java.lang.AssertionError')
            $null = $retryXml.DocumentElement.SelectSingleNode('testcase').AppendChild($retryNode)
            $retryXml.Save($retryReport)
            $rejected = $false
            try { & $gate -ReportsRoot $root -SummaryPath $summary }
            catch {
                if ($_.Exception.Message -notlike 'Rerun/flaky test evidence is forbidden:*') { throw }
                $rejected = $true
            }
            if (!$rejected) { throw "Full environment guard accepted retry history: $retryReport/$nodeName" }
            $validRetryXml | Set-Content $retryReport -Encoding utf8
        }
    }
    $validRepeatedXml = [IO.File]::ReadAllText($repeatedReport)
    $repeatedCase = "<testcase classname='$repeatedSuite' name='$repeatedMethod'/>"
    foreach ($count in @(1, 7, 9)) {
        $changed = "<testsuite name='$repeatedSuite' tests='$count' failures='0' errors='0' skipped='0'>" +
            ($repeatedCase * $count) + '</testsuite>'
        $changed | Set-Content $repeatedReport -Encoding utf8
        $rejected = $false
        try { & $gate -ReportsRoot $root -SummaryPath $summary }
        catch { $rejected = $true }
        if (!$rejected) { throw "Repeated container guard accepted $count invocations instead of eight" }
        $validRepeatedXml | Set-Content $repeatedReport -Encoding utf8
    }
    $repeatedMutations = [ordered]@{
        'wrong-method' = $validRepeatedXml.Replace("name='$repeatedMethod'", "name='unrelated'")
        'wrong-classname' = $validRepeatedXml.Replace("classname='$repeatedSuite'", "classname='example.UnrelatedTest'")
        'wrong-suite' = $validRepeatedXml.Replace($repeatedSuite, 'example.CacheDbCacheSubscriptionRedisTest')
        'indexed-repeat' = $validRepeatedXml.Replace("name='$repeatedMethod'", "name='$($repeatedMethod)[1]'")
        'counter-mismatch' = $validRepeatedXml.Replace("tests='8'", "tests='9'")
        'skipped-repeat' = $validRepeatedXml.Replace("skipped='0'", "skipped='1'").Replace(
            ($repeatedCase * 8), ($repeatedCase.Replace('/>', '><skipped/></testcase>') + ($repeatedCase * 7)))
        'failed-repeat' = $validRepeatedXml.Replace("failures='0'", "failures='1'").Replace(
            ($repeatedCase * 8), ($repeatedCase.Replace('/>', '><failure/></testcase>') + ($repeatedCase * 7)))
        'errored-repeat' = $validRepeatedXml.Replace("errors='0'", "errors='1'").Replace(
            ($repeatedCase * 8), ($repeatedCase.Replace('/>', '><error/></testcase>') + ($repeatedCase * 7)))
    }
    foreach ($mutation in $repeatedMutations.GetEnumerator()) {
        $mutation.Value | Set-Content $repeatedReport -Encoding utf8
        $rejected = $false
        try { & $gate -ReportsRoot $root -SummaryPath $summary }
        catch { $rejected = $true }
        if (!$rejected) { throw "Repeated container guard accepted invalid evidence: $($mutation.Key)" }
        $validRepeatedXml | Set-Content $repeatedReport -Encoding utf8
    }
    foreach ($mutation in @('missing-suite', 'duplicate-suite', 'split-suite', 'wrong-module', 'noncanonical-report')) {
        $extraReport = Join-Path $repeatedDirectory 'TEST-repeated-copy.xml'
        $changed = $validRepeatedXml
        switch ($mutation) {
            'missing-suite' { Remove-Item -LiteralPath $repeatedReport }
            'duplicate-suite' { $validRepeatedXml | Set-Content $extraReport -Encoding utf8 }
            'split-suite' {
                $changed = $validRepeatedXml.Replace("tests='8'", "tests='4'").Replace(
                    ($repeatedCase * 8), ($repeatedCase * 4))
                $changed | Set-Content $repeatedReport -Encoding utf8
                $changed | Set-Content $extraReport -Encoding utf8
            }
            'wrong-module' {
                $extraReport = Join-Path $root "cachedb-storage-redis/target/surefire-reports/TEST-$repeatedSuite.xml"
                Move-Item -LiteralPath $repeatedReport -Destination $extraReport
            }
            'noncanonical-report' { Move-Item -LiteralPath $repeatedReport -Destination $extraReport }
        }
        $rejected = $false
        try { & $gate -ReportsRoot $root -SummaryPath $summary }
        catch { $rejected = $true }
        if (!$rejected) { throw "Repeated container guard accepted invalid suite evidence: $mutation" }
        if (Test-Path -LiteralPath $extraReport) { Remove-Item -LiteralPath $extraReport }
        $validRepeatedXml | Set-Content $repeatedReport -Encoding utf8
    }
    & $gate -ReportsRoot $root -SummaryPath $summary
    foreach ($name in @($contracts.Standalone | Where-Object { $contracts.StandaloneMethods.ContainsKey($_) })) {
        $methodReport = Join-Path $root "cachedb-storage-redis/target/surefire-reports/TEST-$name.xml"
        $validXml = [IO.File]::ReadAllText($methodReport)
        foreach ($mutation in @('missing-suite','all-skipped-suite')) {
            if ($mutation -eq 'missing-suite') {
                Remove-Item -LiteralPath $methodReport
            } else {
                $changed = $validXml.Replace("skipped='0'", "skipped='$(@($contracts.StandaloneMethods[$name]).Count)'")
                foreach ($method in $contracts.StandaloneMethods[$name]) {
                    $changed = $changed.Replace("<testcase classname='example.$name' name='$method'/>",
                        "<testcase classname='example.$name' name='$method'><skipped/></testcase>")
                }
                $changed | Set-Content $methodReport -Encoding utf8
            }
            $rejected = $false
            try { & $gate -ReportsRoot $root -SummaryPath $summary }
            catch { $rejected = $true }
            if (!$rejected) { throw "Standalone guard accepted invalid suite evidence: $name/$mutation" }
            $validXml | Set-Content $methodReport -Encoding utf8
        }
        foreach ($method in $contracts.StandaloneMethods[$name]) {
            foreach ($mutation in @('missing-method','wrong-classname','skipped-method')) {
                $changed = switch ($mutation) {
                    'missing-method' { $validXml.Replace("name='$method'", "name='unrelated'") }
                    'wrong-classname' { $validXml.Replace("classname='example.$name' name='$method'", "classname='example.UnrelatedTest' name='$method'") }
                    'skipped-method' { $validXml.Replace("skipped='0'", "skipped='1'").Replace(
                        "<testcase classname='example.$name' name='$method'/>",
                        "<testcase classname='example.$name' name='$method'><skipped/></testcase>") }
                }
                $changed | Set-Content $methodReport -Encoding utf8
                $rejected = $false
                try { & $gate -ReportsRoot $root -SummaryPath $summary }
                catch { $rejected = $true }
                if (!$rejected) { throw "Standalone guard accepted invalid method evidence: $name/$method/$mutation" }
                $validXml | Set-Content $methodReport -Encoding utf8
            }
        }
    }
    foreach ($mutation in @('all-skipped','failed','missing','live-method-skipped','counter-mismatch')) {
        $changed = switch ($mutation) {
            'all-skipped' { $xml.Replace("skipped='0'","skipped='3'").Replace("'/>","'><skipped/></testcase>") }
            'failed' { $xml.Replace("failures='0'","failures='1'").Replace(
                "name='refreshOverloadNeverBlocksCallersAndReleasesReservations'/>",
                "name='refreshOverloadNeverBlocksCallersAndReleasesReservations'><failure/></testcase>") }
            'missing' { $xml.Replace('example.RedisNearCacheTest','example.RenamedTest') }
            'live-method-skipped' { $xml.Replace("skipped='0'","skipped='1'").Replace("name='refreshOverloadNeverBlocksCallersAndReleasesReservations'/>","name='refreshOverloadNeverBlocksCallersAndReleasesReservations'><skipped/></testcase>") }
            'counter-mismatch' { $xml.Replace("tests='3'","tests='4'") }
        }
        $changed | Set-Content $near -Encoding utf8
        $rejected = $false
        try { & $gate -ReportsRoot $root -SummaryPath $summary }
        catch { $rejected = $true }
        if (!$rejected) { throw "Full environment guard accepted invalid evidence: $mutation" }
    }
    $xml | Set-Content $near -Encoding utf8
    $required = Join-Path $root 'cachedb-storage-redis/target/surefire-reports/TEST-WriteFenceProvidersIntegrationTest.xml'
    "<testsuite name='example.WriteFenceProvidersIntegrationTest' tests='2' failures='0' errors='0' skipped='1'><testcase name='passes'/><testcase name='oracle'><skipped/></testcase></testsuite>" |
        Set-Content $required -Encoding utf8
    $rejected = $false
    try { & $gate -ReportsRoot $root -SummaryPath $summary }
    catch { $rejected = $true }
    if (!$rejected) { throw 'Full environment guard accepted a partly skipped provider suite' }
    $clusterRoot = Join-Path $root 'cluster'
    $clusterReports = Join-Path $clusterRoot 'cachedb-storage-redis/target/surefire-reports'
    $null = New-Item -ItemType Directory -Force -Path $clusterReports
    foreach ($name in $contracts.Cluster) {
        $methods = if ($contracts.ClusterMethods.ContainsKey($name)) { @($contracts.ClusterMethods[$name]) } else { @('passes') }
        $clusterXml = "<testsuite name='example.$name' tests='$(@($methods).Count)' failures='0' errors='0' skipped='0'>"
        $clusterXml += (@($methods) | ForEach-Object { "<testcase classname='example.$name' name='$_'/>" }) -join ''
        $clusterXml += '</testsuite>'
        $clusterXml | Set-Content (Join-Path $clusterReports "TEST-$name.xml") -Encoding utf8
    }
    & $gate -ReportsRoot $clusterRoot -SummaryPath $summary -ClusterOnly
    $clusterRetryReport = Join-Path $clusterReports 'TEST-RedisCausalReadsTest.xml'
    $validClusterRetryXml = [IO.File]::ReadAllText($clusterRetryReport)
    foreach ($nodeName in $retryNodes) {
        [xml]$retryXml = $validClusterRetryXml
        $retryNode = $retryXml.CreateElement($nodeName)
        $retryNode.SetAttribute('message', 'prior attempt failed')
        $retryNode.SetAttribute('type', 'java.lang.AssertionError')
        $null = $retryXml.DocumentElement.SelectSingleNode('testcase').AppendChild($retryNode)
        $retryXml.Save($clusterRetryReport)
        $rejected = $false
        try { & $gate -ReportsRoot $clusterRoot -SummaryPath $summary -ClusterOnly }
        catch {
            if ($_.Exception.Message -notlike 'Rerun/flaky test evidence is forbidden:*') { throw }
            $rejected = $true
        }
        if (!$rejected) { throw "Cluster guard accepted retry history: $nodeName" }
        $validClusterRetryXml | Set-Content $clusterRetryReport -Encoding utf8
    }
    foreach ($name in @($contracts.Cluster | Where-Object { $contracts.ClusterMethods.ContainsKey($_) })) {
        $methodReport = Join-Path $clusterReports "TEST-$name.xml"
        $validClusterXml = [IO.File]::ReadAllText($methodReport)
        foreach ($method in $contracts.ClusterMethods[$name]) {
            foreach ($mutation in @('missing-method','wrong-classname')) {
                $changed = if ($mutation -eq 'missing-method') {
                    $validClusterXml.Replace("name='$method'", "name='unrelated'")
                } else {
                    $validClusterXml.Replace("classname='example.$name'", "classname='example.UnrelatedTest'")
                }
                $changed | Set-Content $methodReport -Encoding utf8
                $rejected = $false
                try { & $gate -ReportsRoot $clusterRoot -SummaryPath $summary -ClusterOnly }
                catch { $rejected = $true }
                if (!$rejected) { throw "Cluster guard accepted invalid method evidence: $name/$method/$mutation" }
                $validClusterXml | Set-Content $methodReport -Encoding utf8
            }
        }
    }
    $clusterRequired = Join-Path $clusterReports 'TEST-RedisServerTrackingClusterTest.xml'
    "<testsuite name='example.RedisServerTrackingClusterTest' tests='2' failures='0' errors='0' skipped='1'><testcase name='passes'/><testcase name='missing'><skipped/></testcase></testsuite>" |
        Set-Content $clusterRequired -Encoding utf8
    $rejected = $false
    try { & $gate -ReportsRoot $clusterRoot -SummaryPath $summary -ClusterOnly }
    catch { $rejected = $true }
    if (!$rejected) { throw 'Cluster guard accepted a partly skipped tracking suite' }
    Write-Host 'Full-environment gate self-test passed: eight bare repeated invocations, invalid repeated coverage, standalone/Cluster flaky/rerun rejection, valid, absent, failed, skipped, counter mismatch, partly skipped provider suites and missing/misclassified/skipped required methods.'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath($tempRoot),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
