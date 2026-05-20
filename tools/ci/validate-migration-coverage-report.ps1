param(
    [Parameter(Mandatory = $true)]
    [string]$CoverageCsvPath,
    [string]$SummaryPath = "target/migration-coverage-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$coverageFullPath = if ([System.IO.Path]::IsPathRooted($CoverageCsvPath)) {
    $CoverageCsvPath
} else {
    Join-Path $repoRoot $CoverageCsvPath
}
$summaryFullPath = if ([System.IO.Path]::IsPathRooted($SummaryPath)) {
    $SummaryPath
} else {
    Join-Path $repoRoot $SummaryPath
}

if (-not (Test-Path $coverageFullPath)) {
    throw "Migration coverage CSV not found: $coverageFullPath"
}

$rows = @(Import-Csv -Path $coverageFullPath)
if ($rows.Count -eq 0) {
    throw "Migration coverage CSV is empty: $coverageFullPath"
}

$requiredColumns = @(
    "RouteName",
    "Owner",
    "RootTable",
    "QueryShape",
    "CacheDbShape",
    "WarmStatus",
    "CompareStatus",
    "CutoverStatus",
    "RollbackPlan"
)

$actualColumns = @($rows[0].PSObject.Properties.Name)
$missingColumns = @($requiredColumns | Where-Object { $_ -notin $actualColumns })
if ($missingColumns.Count -gt 0) {
    throw "Migration coverage CSV is missing required columns: $($missingColumns -join ', ')"
}

$acceptedWarmStatuses = @("warm ok", "dry-run ok", "not required", "cold path")
$acceptedCompareStatuses = @("matched", "cold-path-approved")
$acceptedCutoverStatuses = @("ready", "canary", "live")
$acceptedShapes = @("generated", "projection", "ranked projection", "repository", "cold path")

$failures = New-Object System.Collections.Generic.List[string]
foreach ($row in $rows) {
    foreach ($column in $requiredColumns) {
        if ([string]::IsNullOrWhiteSpace([string]$row.$column)) {
            $failures.Add("$($row.RouteName): $column is blank")
        }
    }

    $shape = ([string]$row.CacheDbShape).Trim().ToLowerInvariant()
    if ($shape -and $shape -notin $acceptedShapes) {
        $failures.Add("$($row.RouteName): unsupported CacheDbShape '$($row.CacheDbShape)'")
    }

    $warm = ([string]$row.WarmStatus).Trim().ToLowerInvariant()
    if ($warm -and $warm -notin $acceptedWarmStatuses) {
        $failures.Add("$($row.RouteName): WarmStatus must be one of $($acceptedWarmStatuses -join ', ')")
    }

    $compare = ([string]$row.CompareStatus).Trim().ToLowerInvariant()
    if ($compare -and $compare -notin $acceptedCompareStatuses) {
        $failures.Add("$($row.RouteName): CompareStatus must be matched or cold-path-approved")
    }

    $cutover = ([string]$row.CutoverStatus).Trim().ToLowerInvariant()
    if ($cutover -and $cutover -notin $acceptedCutoverStatuses) {
        $failures.Add("$($row.RouteName): CutoverStatus must be ready, canary, or live")
    }
}

$summaryDir = Split-Path -Parent $summaryFullPath
if (-not (Test-Path $summaryDir)) {
    New-Item -ItemType Directory -Path $summaryDir -Force | Out-Null
}

$summaryLines = @(
    "# Migration Coverage Summary",
    "",
    "- Routes checked: ``$($rows.Count)``",
    "- Result: ``$(if ($failures.Count -eq 0) { "PASS" } else { "FAIL" })``",
    "",
    "## Required GA Rule",
    "",
    "Every production screen, API, batch, worker, and report route must have a CacheDB shape decision, warm decision, comparison result, cutover state, owner, and rollback plan."
)

if ($failures.Count -gt 0) {
    $summaryLines += ""
    $summaryLines += "## Failures"
    $summaryLines += ""
    $summaryLines += ($failures | ForEach-Object { "- $_" })
}

Set-Content -Path $summaryFullPath -Value ($summaryLines -join [Environment]::NewLine)

if ($failures.Count -gt 0) {
    throw "Migration coverage validation failed. See $summaryFullPath"
}

Write-Host "Migration coverage validation passed."
Write-Host "Summary: $summaryFullPath"
