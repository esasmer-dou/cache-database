param(
    [string]$Version = "",
    [string]$PackageLabel = "github-release",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($Version)) {
    [xml]$rootPom = Get-Content -LiteralPath (Join-Path $repoRoot "pom.xml") -Raw
    $Version = [string]$rootPom.project.version
}
$releaseRoot = Join-Path $repoRoot "target\releases"
$stagingRoot = Join-Path $releaseRoot "cache-database-$Version"

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

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must be a stable semantic version."
}
$resolvedReleaseRoot = [System.IO.Path]::GetFullPath($releaseRoot) + [System.IO.Path]::DirectorySeparatorChar
foreach ($targetPath in @($stagingRoot, $zipPath)) {
    if (-not [System.IO.Path]::GetFullPath($targetPath).StartsWith($resolvedReleaseRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Release target escaped the release directory."
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

# Export the complete public documentation tree, not a hand-maintained partial list.
$docFiles = @(& git -c core.quotepath=false -C $repoRoot ls-files --cached --others --exclude-standard -- docs tr DOCUMENTATION_MAP.md PRODUCTION_GA_CRITERIA.md)
if ($LASTEXITCODE -ne 0) { throw "Cannot enumerate public documentation." }
$repositoryPrefix = [IO.Path]::GetFullPath($repoRoot) + [IO.Path]::DirectorySeparatorChar
foreach ($file in ($docFiles | Sort-Object -Unique)) {
    if ($file -match '(^|/)(target|\.git|\.codex|agentmemory|secrets|\.ssh)(/|$)' -or
        $file -match '(^|/)(PROJECT_MEMORY|MEMORY)\.md$|(^|/)\.env($|\.)|\.(log|secret|secrets|pem|pfx|p12|jks)$') {
        continue
    }
    $source = [IO.Path]::GetFullPath((Join-Path $repoRoot $file))
    if (-not $source.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Documentation source escaped the repository: $file"
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Release documentation must not be symbolic links: $file"
    }
    $destination = Join-Path $stagingRoot $file
    $parent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
}

$sampleProjects = @(
    "sample-cache-database-postgresql",
    "sample-cache-database-mssql",
    "sample-cache-database-oracle"
)
$samplesDest = Join-Path $stagingRoot "samples"
New-Item -ItemType Directory -Path $samplesDest -Force | Out-Null

foreach ($sampleProject in $sampleProjects) {
    $sampleSource = Join-Path $repoRoot $sampleProject
    $sampleDest = Join-Path $samplesDest $sampleProject
    New-Item -ItemType Directory -Path $sampleDest -Force | Out-Null
    # Respect each sample's ignore rules: release archives must not export local state.
    $files = @(& git -c core.quotepath=false -C $sampleSource ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot enumerate release sources for $sampleProject; use a Git checkout."
    }
    $sourcePrefix = [IO.Path]::GetFullPath($sampleSource) + [IO.Path]::DirectorySeparatorChar
    foreach ($relative in ($files | Sort-Object -Unique)) {
        $portable = $relative.Replace('\', '/')
        if ($portable -match '(^|/)(target|\.git|\.idea|\.vscode|\.codex|agentmemory|secrets|\.ssh)(/|$)' -or
            $portable -match '(^|/)(PROJECT_MEMORY|MEMORY)\.md$|(^|/)\.env($|\.)|\.(log|secret|secrets|pem|pfx|p12|jks)$') {
            continue
        }
        $source = [IO.Path]::GetFullPath((Join-Path $sampleSource $relative))
        if (-not $source.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Sample source escaped its root: $relative"
        }
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        if ((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Release sources must not be symbolic links: $relative"
        }
        $destination = Join-Path $sampleDest $relative
        $parent = Split-Path -Parent $destination
        if (-not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $destination
    }
}

$artifactModules = @(
    "cachedb-annotations",
    "cachedb-processor",
    "cachedb-core",
    "cachedb-json",
    "cachedb-storage-redis",
    "cachedb-storage-jdbc",
    "cachedb-storage-postgres",
    "cachedb-storage-mssql",
    "cachedb-storage-oracle",
    "cachedb-starter",
    "cachedb-spring-boot-starter",
    "cachedb-spring-boot-starter-postgres",
    "cachedb-spring-boot-starter-mssql",
    "cachedb-spring-boot-starter-oracle",
    "cachedb-spring-boot-starter-admin",
    "cachedb-spring-boot-test",
    "cachedb-maven-plugin",
    "cachedb-migration-recipes",
    "cachedb-examples"
)

$artifactsDest = Join-Path $stagingRoot "artifacts"
New-Item -ItemType Directory -Path $artifactsDest -Force | Out-Null

Copy-Item `
    -Path (Join-Path $repoRoot "pom.xml") `
    -Destination (Join-Path $artifactsDest "cache-database-$Version.pom")

foreach ($module in $artifactModules) {
    $moduleTarget = Join-Path $repoRoot "$module\target"
    $moduleDest = Join-Path $artifactsDest $module
    New-Item -ItemType Directory -Path $moduleDest -Force | Out-Null
    foreach ($suffix in @(".jar", "-sources.jar", "-javadoc.jar")) {
        $artifact = Join-Path $moduleTarget "$module-$Version$suffix"
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            throw "Required release artifact is missing: $artifact"
        }
        Copy-Item -LiteralPath $artifact -Destination $moduleDest
    }

    Copy-Item `
        -Path (Join-Path $repoRoot "$module\pom.xml") `
        -Destination (Join-Path $moduleDest "$module-$Version.pom")
}

$bomDest = Join-Path $artifactsDest "cachedb-bom"
New-Item -ItemType Directory -Path $bomDest -Force | Out-Null
Copy-Item `
    -Path (Join-Path $repoRoot "cachedb-bom\pom.xml") `
    -Destination (Join-Path $bomDest "cachedb-bom-$Version.pom")

Compress-Archive -Path (Join-Path $stagingRoot "*") -DestinationPath $zipPath -Force
$checksumPath = "$zipPath.sha256"
$checksum = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumPath -Value "$checksum  $([System.IO.Path]::GetFileName($zipPath))" -NoNewline

Write-Host "Release bundle created:"
Write-Host " - $zipPath"
Write-Host " - $checksumPath"
