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

$summary = @(
    '# Framework Principles',
    '',
    "- Runtime Java files checked: $($mainSources.Count)",
    '- Runtime reflection: forbidden',
    '- Scheduled warm dispatch: compile-time generated',
    '- Generated route metadata: static and reusable',
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
