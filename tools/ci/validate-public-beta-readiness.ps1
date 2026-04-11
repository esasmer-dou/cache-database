param(
    [string]$SummaryPath = ".\target\public-beta-readiness-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$summaryFullPath = Join-Path $repoRoot $SummaryPath
$summaryDir = Split-Path -Parent $summaryFullPath

$requiredFiles = @(
    "LICENSE",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
    "SUPPORT.md",
    "CHANGELOG.md",
    ".github\ISSUE_TEMPLATE\bug_report.md",
    ".github\ISSUE_TEMPLATE\feature_request.md",
    ".github\ISSUE_TEMPLATE\config.yml",
    ".github\pull_request_template.md",
    "docs\release-checklist.md",
    "tr\docs\release-checklist.md",
    "docs\public-beta-readiness.md",
    "tr\docs\public-beta-readiness.md"
)

$missing = @()
foreach ($relativePath in $requiredFiles) {
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path $fullPath)) {
        $missing += $relativePath
    }
}

$pomPath = Join-Path $repoRoot "pom.xml"
$pomContent = Get-Content $pomPath -Raw
$requiredPomTokens = @(
    "<licenses>",
    "<developers>",
    "<scm>",
    "<issueManagement>",
    "<ciManagement>",
    "<profile>",
    "<id>oss-release</id>"
)

$missingPomTokens = @()
foreach ($token in $requiredPomTokens) {
    if (-not $pomContent.Contains($token)) {
        $missingPomTokens += $token
    }
}

if (-not (Test-Path $summaryDir)) {
    New-Item -ItemType Directory -Path $summaryDir -Force | Out-Null
}

$summaryLines = @(
    "# Public Beta Readiness Summary",
    "",
    "## Files",
    ""
)

if ($missing.Count -eq 0) {
    $summaryLines += "- all required community files are present"
} else {
    $summaryLines += "- missing files detected"
}

$summaryLines += ""
$summaryLines += "## POM Metadata"
$summaryLines += ""

if ($missingPomTokens.Count -eq 0) {
    $summaryLines += "- root pom contains the expected public-release metadata blocks"
} else {
    $summaryLines += "- root pom is missing one or more metadata blocks"
}

if ($missing.Count -gt 0) {
    $summaryLines += ""
    $summaryLines += "### Missing Files"
    $summaryLines += ""
    $summaryLines += ($missing | ForEach-Object { "- $_" })
}

if ($missingPomTokens.Count -gt 0) {
    $summaryLines += ""
    $summaryLines += "### Missing POM Tokens"
    $summaryLines += ""
    $summaryLines += ($missingPomTokens | ForEach-Object { "- $_" })
}

Set-Content -Path $summaryFullPath -Value ($summaryLines -join [Environment]::NewLine)

if ($missing.Count -gt 0 -or $missingPomTokens.Count -gt 0) {
    throw "Public beta readiness validation failed."
}

Write-Host "Public beta readiness validation passed."
Write-Host "Summary written to $summaryFullPath"
