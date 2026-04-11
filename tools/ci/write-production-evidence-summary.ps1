param(
    [string]$ReportsDirectory = "",
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($ReportsDirectory)) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $candidateDirectories = @(
        (Join-Path $repoRoot "target\cachedb-prodtest-reports"),
        (Join-Path $repoRoot "cachedb-production-tests\target\cachedb-prodtest-reports")
    )
    $ReportsDirectory = $candidateDirectories | Where-Object {
        (Test-Path (Join-Path $_ "repository-recipe-comparison.json")) -and
        (Test-Path (Join-Path $_ "relation-read-shape-comparison.json")) -and
        (Test-Path (Join-Path $_ "ranked-projection-comparison.json"))
    } | Select-Object -First 1
}

if ([string]::IsNullOrWhiteSpace($ReportsDirectory)) {
    throw "Unable to locate production evidence reports."
}

$recipeReportPath = Join-Path $ReportsDirectory "repository-recipe-comparison.json"
$shapeReportPath = Join-Path $ReportsDirectory "relation-read-shape-comparison.json"
$rankedReportPath = Join-Path $ReportsDirectory "ranked-projection-comparison.json"

$recipe = Get-Content $recipeReportPath -Raw | ConvertFrom-Json
$shape = Get-Content $shapeReportPath -Raw | ConvertFrom-Json
$ranked = Get-Content $rankedReportPath -Raw | ConvertFrom-Json

$rankedWide = $ranked.shapeReports | Where-Object { $_.shapeName -eq "WIDE_CANDIDATE_SCAN" } | Select-Object -First 1
$rankedTopWindow = $ranked.shapeReports | Where-Object { $_.shapeName -eq "RANKED_TOP_WINDOW" } | Select-Object -First 1

$summary = @"
## Production Evidence

### Recipe Benchmark

- Fastest average surface: ``$($recipe.fastestAverageMode)``
- Fastest p95 surface: ``$($recipe.fastestP95Mode)``
- Average spread vs minimal repository: $([math]::Round($recipe.maxAverageSpreadPercent, 2))%
- Compared operations: $($recipe.comparedOperations -join ', ')

### Relation-Heavy Read Shape Benchmark

- Fastest shape: ``$($shape.fastestAverageShape)``
- Max average spread vs fastest shape: $([math]::Round($shape.maxAverageSpreadPercent, 2))%
- Orders per read: $($shape.ordersPerRead)
- Preview lines per order: $($shape.previewLinesPerOrder)
- Full lines per order: $($shape.fullLinesPerOrder)

### Ranked Projection Benchmark

- Fastest shape: ``$($ranked.fastestAverageShape)``
- Ranked top-window avg ns: $($rankedTopWindow.averageLatencyNanos)
- Wide candidate scan avg ns: $($rankedWide.averageLatencyNanos)
- Ranked top-window objects/op: $($rankedTopWindow.materializedObjectsPerOperation)
- Wide candidate scan objects/op: $($rankedWide.materializedObjectsPerOperation)

### Key Guidance

- Generated ergonomics stay in the same low-overhead band as direct repository usage.
- Summary/detail remains the preferred first-paint model for relation-heavy screens.
- Global sorted or range-driven list screens should use projection-specific ranked fields instead of wide multi-sort entity queries.
- Ranked projection top-window paths should stay cheaper than wide candidate scans and materialize fewer objects.
"@

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    Write-Output $summary
} else {
    $outputDirectory = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    $summary | Set-Content -Path $OutputPath -NoNewline
}
