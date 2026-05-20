param(
    [string]$ReportsDirectory = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if ([string]::IsNullOrWhiteSpace($ReportsDirectory)) {
    $candidateDirectories = @(
        (Join-Path (Join-Path $repoRoot "target") "cachedb-prodtest-reports"),
        (Join-Path (Join-Path (Join-Path $repoRoot "cachedb-production-tests") "target") "cachedb-prodtest-reports")
    )
    $ReportsDirectory = $candidateDirectories | Where-Object {
        (Test-Path (Join-Path $_ "repository-recipe-comparison.json")) -and
        (Test-Path (Join-Path $_ "relation-read-shape-comparison.json")) -and
        (Test-Path (Join-Path $_ "ranked-projection-comparison.json"))
    } | Select-Object -First 1
}

if ([string]::IsNullOrWhiteSpace($ReportsDirectory)) {
    throw "Unable to locate benchmark reports. Run tools/ci/run-production-evidence.ps1 first."
}

function Read-JsonReport {
    param([string]$Name)
    $path = Join-Path $ReportsDirectory $Name
    if (-not (Test-Path $path)) {
        throw "Missing benchmark report: $path"
    }
    return Get-Content $path -Raw | ConvertFrom-Json
}

function Find-Item {
    param(
        [object[]]$Items,
        [string]$Property,
        [string]$Value
    )
    $item = $Items | Where-Object { $_.$Property -eq $Value } | Select-Object -First 1
    if ($null -eq $item) {
        throw "Benchmark report is missing $Property=$Value"
    }
    return $item
}

function Assert-LessThan {
    param(
        [double]$Actual,
        [double]$Threshold,
        [string]$Message
    )
    if ($Actual -ge $Threshold) {
        throw "$Message Actual=$Actual ThresholdExclusive=$Threshold"
    }
}

function Assert-LessThanOrEqual {
    param(
        [double]$Actual,
        [double]$Threshold,
        [string]$Message
    )
    if ($Actual -gt $Threshold) {
        throw "$Message Actual=$Actual Threshold=$Threshold"
    }
}

$recipe = Read-JsonReport "repository-recipe-comparison.json"
$readShape = Read-JsonReport "relation-read-shape-comparison.json"
$ranked = Read-JsonReport "ranked-projection-comparison.json"

$minimal = Find-Item $recipe.modeReports "mode" "MINIMAL_REPOSITORY"
$generated = Find-Item $recipe.modeReports "mode" "GENERATED_ENTITY_BINDING"
$grouped = Find-Item $recipe.modeReports "mode" "JPA_STYLE_DOMAIN_MODULE"

Assert-LessThan $minimal.averageLatencyNanos 100000 "Minimal repository average latency regressed."
Assert-LessThan $generated.averageLatencyNanos 120000 "Generated binding average latency regressed."
Assert-LessThan $grouped.averageLatencyNanos 150000 "Grouped domain module average latency regressed."
Assert-LessThan $generated.p95LatencyNanos 150000 "Generated binding p95 latency regressed."
Assert-LessThan $grouped.p95LatencyNanos 200000 "Grouped domain module p95 latency regressed."
Assert-LessThan $recipe.maxAverageSpreadPercent 5000 "Repository recipe average drift regressed."

$summary = Find-Item $readShape.shapeReports "shapeName" "SUMMARY_LIST"
$summaryPreview = Find-Item $readShape.shapeReports "shapeName" "SUMMARY_PLUS_SINGLE_PREVIEW"
$previewList = Find-Item $readShape.shapeReports "shapeName" "PREVIEW_LIST"
$fullAggregate = Find-Item $readShape.shapeReports "shapeName" "FULL_AGGREGATE_LIST"

if ($readShape.fastestAverageShape -eq "FULL_AGGREGATE_LIST") {
    throw "Full aggregate became the fastest relation-heavy first-paint shape."
}
Assert-LessThan $summary.estimatedObjectCountPerOperation $summaryPreview.estimatedObjectCountPerOperation "Summary-only object count must stay below summary-plus-preview."
Assert-LessThan $summaryPreview.estimatedObjectCountPerOperation $previewList.estimatedObjectCountPerOperation "Single preview object count must stay below preview-list."
Assert-LessThan $previewList.estimatedObjectCountPerOperation $fullAggregate.estimatedObjectCountPerOperation "Preview-list object count must stay below full aggregate."
Assert-LessThan $previewList.materializedLineViewsPerOperation $fullAggregate.materializedLineViewsPerOperation "Preview-list child materialization must stay below full aggregate."

$wideCandidateScan = Find-Item $ranked.shapeReports "shapeName" "WIDE_CANDIDATE_SCAN"
$rankedTopWindow = Find-Item $ranked.shapeReports "shapeName" "RANKED_TOP_WINDOW"

Assert-LessThan $rankedTopWindow.averageLatencyNanos $wideCandidateScan.averageLatencyNanos "Ranked top-window average latency must stay below wide candidate scan."
Assert-LessThanOrEqual $rankedTopWindow.p95LatencyNanos $wideCandidateScan.p95LatencyNanos "Ranked top-window p95 must not exceed wide candidate scan."
Assert-LessThan $rankedTopWindow.materializedObjectsPerOperation $wideCandidateScan.materializedObjectsPerOperation "Ranked top-window object materialization must stay below wide candidate scan."

Write-Host "Benchmark threshold gate passed."
Write-Host "Reports directory: $ReportsDirectory"
