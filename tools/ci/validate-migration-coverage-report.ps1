param(
    [Parameter(Mandatory = $true)]
    [string]$CoverageCsvPath,
    [string]$EvidenceDirectory = "",
    [string]$ExpectedCommit = "",
    [string]$ExpectedEnvironment = "",
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
$coverageFullPath = [System.IO.Path]::GetFullPath($coverageFullPath)
$evidenceRoot = if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    Split-Path -Parent $coverageFullPath
} elseif ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    [System.IO.Path]::GetFullPath($EvidenceDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
}
$summaryFullPath = if ([System.IO.Path]::IsPathRooted($SummaryPath)) {
    $SummaryPath
} else {
    Join-Path $repoRoot $SummaryPath
}

if (-not (Test-Path -LiteralPath $coverageFullPath -PathType Leaf)) {
    throw "Migration coverage CSV not found: $coverageFullPath"
}

$rows = @(Import-Csv -LiteralPath $coverageFullPath)
if ($rows.Count -eq 0) {
    throw "Migration coverage CSV is empty: $coverageFullPath"
}

$requiredColumns = @(
    "RouteName",
    "RouteKind",
    "Owner",
    "QueryShape",
    "CacheDbShape",
    "WarmStatus",
    "WarmEvidence",
    "CompareStatus",
    "CompareEvidence",
    "MemoryStatus",
    "MemoryEvidence",
    "CutoverStatus",
    "RollbackPlan",
    "RollbackEvidence",
    "Blocker"
)
$actualColumns = @($rows[0].PSObject.Properties.Name)
$missingColumns = @($requiredColumns | Where-Object { $_ -notin $actualColumns })
if ($missingColumns.Count -gt 0) {
    throw "Migration coverage CSV is missing required columns: $($missingColumns -join ', ')"
}

$acceptedRouteKinds = @("screen", "api", "batch", "worker", "report")
$acceptedShapes = @("generated", "projection", "ranked projection", "repository", "cold path")
$acceptedWarmStatuses = @("passed", "not required")
$acceptedCompareStatuses = @("matched", "cold-path-approved")
$acceptedMemoryStatuses = @("within budget", "not applicable")
$acceptedCutoverStatuses = @("ready", "canary", "live")
$failures = New-Object System.Collections.Generic.List[string]
$routeNames = @{}

function Read-EvidenceHeader {
    param([string]$Path)
    $header = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^:]+):\s*(.*)\s*$') {
            $key = $Matches[1].Trim().ToLowerInvariant()
            if (-not $header.ContainsKey($key)) {
                $header[$key] = $Matches[2].Trim()
            }
        }
    }
    return $header
}

function Test-EvidencePath {
    param(
        [string]$RouteName,
        [string]$Column,
        [string]$RelativePath
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath)) {
        $script:failures.Add("${RouteName}: $Column is blank")
        return
    }
    $resolved = [System.IO.Path]::GetFullPath((Join-Path $script:evidenceRoot $RelativePath))
    $rootWithSeparator = $script:evidenceRoot.TrimEnd([char[]]@('\', '/')) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($rootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
        $script:failures.Add("${RouteName}: $Column must stay inside the evidence directory")
        return
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) -or (Get-Item -LiteralPath $resolved).Length -eq 0) {
        $script:failures.Add("${RouteName}: $Column does not reference a non-empty evidence file: $RelativePath")
        return
    }

    $header = Read-EvidenceHeader -Path $resolved
    foreach ($required in @("status", "commit", "environment", "owner", "generated-at", "summary")) {
        if (-not $header.ContainsKey($required) -or [string]::IsNullOrWhiteSpace([string]$header[$required])) {
            $script:failures.Add("${RouteName}: $Column evidence header is missing $required")
        }
    }
    if (([string]$header["status"]).Trim().ToLowerInvariant() -ne "passed") {
        $script:failures.Add("${RouteName}: $Column evidence status must be passed")
    }
    $commit = ([string]$header["commit"]).Trim()
    if ($commit -notmatch '^[0-9a-fA-F]{7,40}$') {
        $script:failures.Add("${RouteName}: $Column evidence commit must be a 7-40 character Git SHA")
    } elseif (-not [string]::IsNullOrWhiteSpace($script:ExpectedCommit) -and
              $commit -ne $script:ExpectedCommit) {
        $script:failures.Add("${RouteName}: $Column evidence commit does not match ExpectedCommit")
    }
    $environment = ([string]$header["environment"]).Trim()
    if (-not [string]::IsNullOrWhiteSpace($script:ExpectedEnvironment) -and
        $environment -ne $script:ExpectedEnvironment) {
        $script:failures.Add("${RouteName}: $Column evidence environment does not match ExpectedEnvironment")
    }
    $generatedAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse(([string]$header["generated-at"]), [ref]$generatedAt)) {
        $script:failures.Add("${RouteName}: $Column generated-at must be an ISO-8601 timestamp with an offset")
    }
}

foreach ($row in $rows) {
    $routeName = ([string]$row.RouteName).Trim()
    $context = if ([string]::IsNullOrWhiteSpace($routeName)) { "row" } else { $routeName }
    foreach ($column in @("RouteName", "Owner", "QueryShape", "RollbackPlan")) {
        if ([string]::IsNullOrWhiteSpace([string]$row.$column)) {
            $failures.Add("${context}: $column is blank")
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($routeName)) {
        $normalizedRoute = $routeName.ToLowerInvariant()
        if ($routeNames.ContainsKey($normalizedRoute)) {
            $failures.Add("${context}: RouteName must be unique")
        } else {
            $routeNames[$normalizedRoute] = $true
        }
    }

    $checks = @(
        @{ Column = "RouteKind"; Accepted = $acceptedRouteKinds },
        @{ Column = "CacheDbShape"; Accepted = $acceptedShapes },
        @{ Column = "WarmStatus"; Accepted = $acceptedWarmStatuses },
        @{ Column = "CompareStatus"; Accepted = $acceptedCompareStatuses },
        @{ Column = "MemoryStatus"; Accepted = $acceptedMemoryStatuses },
        @{ Column = "CutoverStatus"; Accepted = $acceptedCutoverStatuses }
    )
    foreach ($check in $checks) {
        $column = $check.Column
        $value = ([string]$row.$column).Trim().ToLowerInvariant()
        if ($value -notin $check.Accepted) {
            $failures.Add("${context}: $column must be one of $($check.Accepted -join ', ')")
        }
    }

    foreach ($evidenceColumn in @("WarmEvidence", "CompareEvidence", "MemoryEvidence", "RollbackEvidence")) {
        Test-EvidencePath -RouteName $context -Column $evidenceColumn -RelativePath ([string]$row.$evidenceColumn)
    }
    $blocker = ([string]$row.Blocker).Trim().ToLowerInvariant()
    if (-not [string]::IsNullOrWhiteSpace($blocker) -and $blocker -ne "none") {
        $failures.Add("${context}: unresolved blocker '$($row.Blocker)'")
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
    "- Evidence directory: ``$evidenceRoot``",
    "- Result: ``$(if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' })``",
    "",
    "Every production screen, API, batch, worker, and report route must have an owner, an explicit CacheDB shape, warm/parity/memory evidence, a cutover state, and a tested rollback artifact."
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
