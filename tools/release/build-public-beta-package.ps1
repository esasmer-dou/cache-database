param(
    [string]$Version = "0.1.0-beta.4",
    [string]$PackageLabel = "public-beta",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$releaseRoot = Join-Path $repoRoot "target\releases"
$stagingRoot = Join-Path $releaseRoot "cache-database-$Version"
$isBetaPackage = $PackageLabel -match '(?i)beta'

if ($PackageLabel -notmatch '^[A-Za-z0-9_.-]+$') {
    throw "PackageLabel may only contain letters, numbers, dot, underscore, and dash."
}

$zipPath = Join-Path $releaseRoot "cache-database-$Version-$PackageLabel.zip"

if (-not $SkipBuild) {
    & (Join-Path $repoRoot "tools\build\invoke-maven-semeru.ps1") `
        -WorkingDirectory $repoRoot `
        -MavenArgs @("-B", "-P", "oss-release", "-DskipTests", "-Dgpg.skip=true", "package")

    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }
}

if (Test-Path $stagingRoot) {
    Remove-Item -Recurse -Force $stagingRoot
}
if (Test-Path $zipPath) {
    Remove-Item -Force $zipPath
}
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null

$topLevelFiles = @(
    "README.md",
    "LICENSE",
    "CHANGELOG.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
    "SUPPORT.md"
)

foreach ($file in $topLevelFiles) {
    Copy-Item -Path (Join-Path $repoRoot $file) -Destination (Join-Path $stagingRoot $file)
}

$docsDest = Join-Path $stagingRoot "docs"
New-Item -ItemType Directory -Path $docsDest -Force | Out-Null

$docFiles = @(
    "docs\getting-started.md",
    "docs\database-provider-spi.md",
    "docs\use-case-examples.md",
    "docs\migration-planner.md",
    "docs\production-recipes.md",
    "docs\orm-alternative.md",
    "docs\production-ga-release-runbook.md",
    "docs\release-checklist.md",
    "docs\maven-central-publish-checklist.md",
    "docs\releases\v$Version.md",
    "PRODUCTION_GA_CRITERIA.md",
    "tr\README.md",
    "tr\DOKUMAN_HARITASI.md",
    "tr\docs\getting-started.md",
    "tr\docs\veritabani-provider-spi.md",
    "tr\docs\use-case-examples.md",
    "tr\docs\migration-planner.md",
    "tr\docs\production-recipes.md",
    "tr\docs\production-ga-release-runbook.md",
    "tr\docs\release-checklist.md"
)

if ($isBetaPackage) {
    $docFiles += @(
        "docs\public-beta-readiness.md",
        "tr\docs\public-beta-readiness.md",
        "docs\public-beta-launch-kit.md",
        "tr\docs\public-beta-launch-kit.md"
    )
} else {
    $docFiles += @(
        "docs\stable-release-launch-kit.md",
        "tr\docs\stable-release-launch-kit.md"
    )
}

foreach ($file in $docFiles) {
    $source = Join-Path $repoRoot $file
    $destination = Join-Path $stagingRoot $file
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -Path $source -Destination $destination
}

$artifactModules = @(
    "cachedb-annotations",
    "cachedb-processor",
    "cachedb-core",
    "cachedb-storage-redis",
    "cachedb-storage-jdbc",
    "cachedb-storage-postgres",
    "cachedb-storage-mssql",
    "cachedb-starter",
    "cachedb-spring-boot-starter"
)

$artifactsDest = Join-Path $stagingRoot "artifacts"
New-Item -ItemType Directory -Path $artifactsDest -Force | Out-Null

foreach ($module in $artifactModules) {
    $moduleTarget = Join-Path $repoRoot "$module\target"
    $moduleDest = Join-Path $artifactsDest $module
    New-Item -ItemType Directory -Path $moduleDest -Force | Out-Null
    $versionPattern = "^$([regex]::Escape($module))-$([regex]::Escape($Version))(-sources|-javadoc)?\.jar$"
    Get-ChildItem -Path $moduleTarget -File -Filter "*.jar" |
        Where-Object { $_.Name -match $versionPattern } |
        Copy-Item -Destination $moduleDest
}

Compress-Archive -Path (Join-Path $stagingRoot "*") -DestinationPath $zipPath -Force

Write-Host "Release bundle created:"
Write-Host " - $zipPath"
