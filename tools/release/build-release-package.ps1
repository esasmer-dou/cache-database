param(
    [string]$Version = "",
    [string]$PackageLabel = "github-release",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Version)) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    [xml]$rootPom = Get-Content -LiteralPath (Join-Path $repoRoot "pom.xml") -Raw
    $Version = [string]$rootPom.project.version
}

$scriptPath = Join-Path $PSScriptRoot "build-release-bundle.ps1"
$arguments = @{
    Version = $Version
    PackageLabel = $PackageLabel
}
if ($SkipBuild) {
    $arguments.SkipBuild = $true
}

& $scriptPath @arguments
