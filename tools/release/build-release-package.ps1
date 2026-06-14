param(
    [string]$Version = "0.1.0",
    [string]$PackageLabel = "github-release",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$scriptPath = Join-Path $PSScriptRoot "build-public-beta-package.ps1"
$arguments = @{
    Version = $Version
    PackageLabel = $PackageLabel
}
if ($SkipBuild) {
    $arguments.SkipBuild = $true
}

& $scriptPath @arguments
