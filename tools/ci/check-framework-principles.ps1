param(
    [string] $SummaryPath = "./target/framework-principles-summary.md"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath ".").Path
$violations = [System.Collections.Generic.List[string]]::new()

function Add-Violation {
    param([string] $Message)
    $violations.Add($Message)
}

$mainSources = Get-ChildItem -LiteralPath $root -Recurse -Filter "*.java" -File |
    Where-Object {
        $_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' -and
        $_.FullName -notmatch '[\\/]target[\\/]'
    }

$forbiddenRuntimePatterns = [ordered]@{
    'java\.lang\.reflect' = 'runtime-reflection-import'
    'Method\.invoke\s*\(' = 'runtime-method-invocation'
    'getDeclaredMethods\s*\(' = 'runtime-method-scan'
    'Proxy\.newProxyInstance\s*\(' = 'runtime-proxy-generation'
}

foreach ($entry in $forbiddenRuntimePatterns.GetEnumerator()) {
    foreach ($match in Select-String -LiteralPath $mainSources.FullName -Pattern $entry.Key) {
        $relative = [System.IO.Path]::GetRelativePath($root, $match.Path)
        Add-Violation "${relative}:$($match.LineNumber) [$($entry.Value)] $($match.Line.Trim())"
    }
}

$scheduledWarmAnnotation = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheScheduledWarm.java'
) -Raw
if ($scheduledWarmAnnotation -notmatch '@Retention\(RetentionPolicy\.SOURCE\)') {
    Add-Violation '[scheduled-warm-retention] @CacheScheduledWarm must remain compile-time only'
}

$processorServices = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor'
) -Raw
if ($processorServices -notmatch 'com\.reactor\.cachedb\.processor\.CacheScheduledWarmProcessor') {
    Add-Violation '[scheduled-warm-processor] reflection-free warm processor is not registered'
}

$repositoryProcessor = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-processor/src/main/java/com/cachedb/processor/CacheRepositoryProcessor.java'
) -Raw
if ($repositoryProcessor -notmatch 'ROUTE_SORTS_' -or $repositoryProcessor -notmatch 'ROUTE_CONTRACT_') {
    Add-Violation '[route-allocation] generated route sort and contract constants are missing'
}
if ($repositoryProcessor -match 'entities\.stream\(\)\.map\(this::ensureGeneratedId\)') {
    Add-Violation '[bulk-allocation] generated bulk commands must not create a stream pipeline'
}
if ($repositoryProcessor -match 'sourceRepository\.query\([^\)]*\)\.stream\(\)' -or
    $repositoryProcessor -match 'sourceRows\.stream\(\)') {
    Add-Violation '[source-projection-allocation] generated source projection routes must use one pre-sized mapping loop'
}
if ($repositoryProcessor -notmatch 'RepositoryRouteCatalog' -or
    $repositoryProcessor -notmatch 'RepositoryRouteDefinition') {
    Add-Violation '[route-inventory] compile-time repository route catalog generation is missing'
}
if ($repositoryProcessor -notmatch 'awaitDurableOrThrow\(cachedb\$receipts') {
    Add-Violation '[batch-durability] generated SQL-durable batch commands must use one bounded batch wait'
}

$routeQueryAnnotation = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-annotations/src/main/java/com/cachedb/annotations/CacheRouteQuery.java'
) -Raw
if ($routeQueryAnnotation -notmatch 'boolean\s+explicitDisjunction\s*\(\)') {
    Add-Violation '[query-disjunction] repository OR predicates require an explicit opt-in contract'
}
if ($repositoryProcessor -notmatch 'groups\.size\(\)\s*>\s*1\s*&&\s*!annotation\.explicitDisjunction\(\)') {
    Add-Violation '[query-disjunction] processor does not reject implicit OR predicate groups'
}

$hotRouteAnnotation = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-annotations/src/main/java/com/cachedb/annotations/HotRoute.java'
) -Raw
if ($hotRouteAnnotation -notmatch 'enum\s+Population' -or
    $hotRouteAnnotation -notmatch 'Population\s+population\s*\(\)') {
    Add-Violation '[hot-route-population] HOT routes need an explicit population contract'
}
if ($repositoryProcessor -notmatch 'route\.population\(\)\s*==\s*HotRoute\.Population\.DECLARED_WARM' -or
    $repositoryProcessor -notmatch 'HotRoutePopulation\."\s*\+\s*population') {
    Add-Violation '[hot-route-population] processor does not validate and publish population strategy'
}

$warmRouteAnnotation = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-annotations/src/main/java/com/cachedb/annotations/WarmRoute.java'
) -Raw
if ($warmRouteAnnotation -notmatch 'String\s+targetParameter\s*\(\)') {
    Add-Violation '[warm-target] generated warm routes need a typed runtime target parameter'
}
if ($repositoryProcessor -notmatch 'CacheWarmTarget' -or
    $repositoryProcessor -notmatch 'targetParameter') {
    Add-Violation '[warm-target] processor does not validate typed warm targets'
}

$repositoryAnnotation = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-annotations/src/main/java/com/cachedb/annotations/CacheRepository.java'
) -Raw
if ($repositoryAnnotation -notmatch 'String\s+springBeanName\s*\(\)') {
    Add-Violation '[spring-bean-isolation] repositories with duplicate simple names need an explicit bean-name contract'
}
if ($repositoryProcessor -notmatch 'springBeanOwners\.putIfAbsent') {
    Add-Violation '[spring-bean-isolation] processor does not reject duplicate generated repository bean names'
}
if ($repositoryProcessor -notmatch '\.routeCatalog') {
    Add-Violation '[spring-bean-isolation] generated route catalogs need package-qualified bean names'
}

$autoConfiguration = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheDatabaseSpringBootAutoConfiguration.java'
) -Raw
if ($autoConfiguration -match '@(?:org\.springframework\.context\.annotation\.)?Primary') {
    Add-Violation '[spring-bean-isolation] CacheDB infrastructure beans must not override application primary beans'
}

$sampleRoots = @(
    (Join-Path $root 'sample-cache-database-postgresql'),
    (Join-Path $root 'sample-cache-database-mssql')
)
foreach ($sampleRoot in $sampleRoots) {
    $repositorySources = Get-ChildItem -LiteralPath (Join-Path $sampleRoot 'src/main/java') -Recurse -Filter '*Repository.java' -File
    $repositoryText = ($repositorySources | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
    foreach ($repositorySource in $repositorySources) {
        $source = Get-Content -LiteralPath $repositorySource.FullName -Raw
        if ($source -notmatch '@HotRoute\s*\(') {
            continue
        }
        $repositoryDefault = $source -match '(?s)@CacheRepositoryDefaults\s*\([^)]*hotPopulation\s*=\s*HotRoute\.Population\.DECLARED_WARM'
        $hotRouteCount = ([regex]::Matches($source, '@HotRoute\s*\(')).Count
        $methodOverrideCount = ([regex]::Matches(
            $source,
            'population\s*=\s*HotRoute\.Population\.DECLARED_WARM'
        )).Count
        if (-not $repositoryDefault -and $hotRouteCount -ne $methodOverrideCount) {
            Add-Violation "[sample-hot-route-population] $($repositorySource.Name) must declare how every HOT route is populated"
        }
    }

    $warmApplication = Get-Content -LiteralPath (
        Join-Path $sampleRoot 'src/main/java/com/example/cachedb/sample/application/warm/WarmBackfillApplicationService.java'
    ) -Raw
    $warmHandler = Get-Content -LiteralPath (
        Join-Path $sampleRoot 'src/main/java/com/example/cachedb/sample/service/SampleWarmJobHandler.java'
    ) -Raw
    if ($warmApplication -notmatch 'jobs\.submit\(SampleWarmJobHandler\.DEFINITION' -or
        $warmHandler -notmatch 'CacheDistributedJobHandler\.Typed<SampleWarmCommand>') {
        Add-Violation "[sample-typed-job] $([System.IO.Path]::GetFileName($sampleRoot)) warm jobs must keep one typed route contract"
    }

    $sampleJava = Get-ChildItem -LiteralPath (Join-Path $sampleRoot 'src/main/java') -Recurse -Filter '*.java' -File
    if (Select-String -LiteralPath $sampleJava.FullName -Pattern 'ApiLimits|memoryBudgetBytes\s*=\s*[0-9_]+L' -Quiet) {
        Add-Violation "[sample-declarative-surface] $([System.IO.Path]::GetFileName($sampleRoot)) must use Bean Validation and named memory budgets"
    }
}

$windowCursor = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-core/src/main/java/com/cachedb/core/repository/WindowCursor.java'
) -Raw
if ($windowCursor -notmatch 'cachedb_cursor_contract' -or $repositoryProcessor -notmatch 'KeysetPagination\.(hotWindow|sourceWindow)') {
    Add-Violation '[cursor-contract] continuation tokens must be bound to generated route and scope contracts'
}

$jobProgress = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheDistributedJobProgress.java'
) -Raw
if ($jobProgress -notmatch 'MAX_ATTRIBUTES\s*=\s*16' -or $jobProgress -notmatch 'percent must be between 0 and 100') {
    Add-Violation '[job-checkpoint] distributed checkpoints must use a bounded structured progress contract'
}

$durableBatch = Get-Content -LiteralPath (
    Join-Path $root 'cachedb-starter/src/main/java/com/cachedb/starter/CacheDurableBatchWriter.java'
) -Raw
if ($durableBatch -notmatch 'maxPendingReceipts' -or $durableBatch -notmatch 'awaitPending\(\)') {
    Add-Violation '[durable-batch] framework batch writes must apply bounded receipt backpressure'
}

$documentationSources = Get-ChildItem -LiteralPath @(
    (Join-Path $root 'docs'),
    (Join-Path $root 'tr'),
    (Join-Path $root 'sample-cache-database-postgresql'),
    (Join-Path $root 'sample-cache-database-mssql')
) -Recurse -Filter '*.md' -File
foreach ($match in Select-String -LiteralPath $documentationSources.FullName -Pattern (
    'warmCustomerTimelineProjection|warmCustomerTimelineEntities|warmActiveProjection|warmActiveEntities'
)) {
    $relative = [System.IO.Path]::GetRelativePath($root, $match.Path)
    Add-Violation "${relative}:$($match.LineNumber) [obsolete-warm-api] $($match.Line.Trim())"
}

$summary = @(
    '# Framework Principles',
    '',
    "- Runtime Java files checked: $($mainSources.Count)",
    '- Runtime reflection: forbidden',
    '- Scheduled warm dispatch: compile-time generated',
    '- Generated route metadata: static and reusable',
    '- Repository OR predicates: explicit opt-in required',
    '- HOT route population: explicit and compile-time validated',
    '- Warm payload target: typed and generated',
    '- Generated source projection mapping: pre-sized loop',
    '- SQL-durable batch waiting: one bounded batch wait',
    '- Spring infrastructure beans: no implicit primary override',
    '- Generated Spring repository beans: explicit collision detection',
    '- Sample warm jobs: one typed command and route definition',
    '- Cursor continuation: route, scope, and sort contract bound',
    '- Distributed checkpoints: structured and bounded',
    '- Sample input limits: declarative Bean Validation',
    '- Sample memory budgets: named compile-time constants',
    '- Durable seed batching: bounded framework helper',
    "- Violations: $($violations.Count)",
    "- Result: $(if ($violations.Count -eq 0) { 'PASS' } else { 'FAIL' })"
)
if ($violations.Count -gt 0) {
    $summary += ''
    $summary += '## Violations'
    $summary += $violations | ForEach-Object { "- $_" }
}

$summaryFile = [System.IO.Path]::GetFullPath($SummaryPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($summaryFile)) | Out-Null
Set-Content -LiteralPath $summaryFile -Value $summary -Encoding utf8

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Verified reflection-free runtime and generated-code allocation principles across $($mainSources.Count) Java files."
