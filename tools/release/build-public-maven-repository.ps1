param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$OutputDirectory = "target/public-maven-repository",
    [switch]$SkipBuild,
    [switch]$PreserveExisting
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Public Maven repository accepts stable semantic versions only: $Version"
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$outputRoot = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    [System.IO.Path]::GetFullPath($OutputDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
}

if (-not $SkipBuild) {
    & (Join-Path $repoRoot "tools/build/invoke-maven-semeru.ps1") `
        -WorkingDirectory $repoRoot `
        -MavenArgs @("-B", "-P", "oss-release", "-DskipTests", "-Dgpg.skip=true", "package")
    if ($LASTEXITCODE -ne 0) {
        throw "Release artifact build failed with exit code $LASTEXITCODE"
    }
}

if ((Test-Path $outputRoot) -and -not $PreserveExisting) {
    Remove-Item -LiteralPath $outputRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
New-Item -ItemType File -Path (Join-Path $outputRoot ".nojekyll") -Force | Out-Null

$artifactModules = @(
    "cachedb-annotations",
    "cachedb-processor",
    "cachedb-core",
    "cachedb-storage-redis",
    "cachedb-storage-jdbc",
    "cachedb-storage-postgres",
    "cachedb-storage-mssql",
    "cachedb-starter",
    "cachedb-spring-boot-starter",
    "cachedb-spring-boot-starter-postgres",
    "cachedb-spring-boot-starter-mssql",
    "cachedb-spring-boot-starter-admin",
    "cachedb-spring-boot-test",
    "cachedb-maven-plugin",
    "cachedb-migration-recipes",
    "cachedb-examples"
)

function Copy-ImmutableArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Required public Maven artifact is missing: $Source"
    }
    $destinationParent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        $sourceHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
        $destinationHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
        if ($sourceHash -ne $destinationHash) {
            throw "Refusing to replace immutable public Maven artifact: $Destination"
        }
    } else {
        Copy-Item -LiteralPath $Source -Destination $Destination
    }
    $sha1 = (Get-FileHash -LiteralPath $Destination -Algorithm SHA1).Hash.ToLowerInvariant()
    $sha256 = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$Destination.sha1" -Value $sha1 -NoNewline
    Set-Content -LiteralPath "$Destination.sha256" -Value $sha256 -NoNewline
}

function Publish-PomArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactId,
        [Parameter(Mandatory = $true)][string]$PomPath
    )
    $versionDirectory = Join-Path $outputRoot "maven2/com/reactor/cachedb/$ArtifactId/$Version"
    Copy-ImmutableArtifact -Source $PomPath -Destination (Join-Path $versionDirectory "$ArtifactId-$Version.pom")
}

Publish-PomArtifact -ArtifactId "cache-database" -PomPath (Join-Path $repoRoot "pom.xml")
Publish-PomArtifact -ArtifactId "cachedb-bom" -PomPath (Join-Path $repoRoot "cachedb-bom/pom.xml")

foreach ($module in $artifactModules) {
    $versionDirectory = Join-Path $outputRoot "maven2/com/reactor/cachedb/$module/$Version"
    Publish-PomArtifact -ArtifactId $module -PomPath (Join-Path $repoRoot "$module/pom.xml")
    foreach ($suffix in @(".jar", "-sources.jar", "-javadoc.jar")) {
        Copy-ImmutableArtifact `
            -Source (Join-Path $repoRoot "$module/target/$module-$Version$suffix") `
            -Destination (Join-Path $versionDirectory "$module-$Version$suffix")
    }
}

$groupRoot = Join-Path $outputRoot "maven2/com/reactor/cachedb"
$artifactDirectories = @(Get-ChildItem -LiteralPath $groupRoot -Directory | Sort-Object Name)
foreach ($artifactDirectory in $artifactDirectories) {
    $versions = @(Get-ChildItem -LiteralPath $artifactDirectory.FullName -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name })
    if ($versions.Count -eq 0) {
        continue
    }
    $latest = $versions[-1].Name
    $metadata = @(
        '<?xml version="1.0" encoding="UTF-8"?>',
        "<metadata>",
        "  <groupId>com.reactor.cachedb</groupId>",
        "  <artifactId>$($artifactDirectory.Name)</artifactId>",
        "  <versioning>",
        "    <latest>$latest</latest>",
        "    <release>$latest</release>",
        "    <versions>"
    )
    $metadata += $versions | ForEach-Object { "      <version>$($_.Name)</version>" }
    $metadata += @(
        "    </versions>",
        "    <lastUpdated>$([DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmss'))</lastUpdated>",
        "  </versioning>",
        "</metadata>"
    )
    $metadataPath = Join-Path $artifactDirectory.FullName "maven-metadata.xml"
    Set-Content -LiteralPath $metadataPath -Value ($metadata -join "`n") -NoNewline
    Set-Content -LiteralPath "$metadataPath.sha1" -Value ((Get-FileHash -LiteralPath $metadataPath -Algorithm SHA1).Hash.ToLowerInvariant()) -NoNewline
    Set-Content -LiteralPath "$metadataPath.sha256" -Value ((Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant()) -NoNewline
}

$index = @"
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CacheDB Maven Repository</title></head>
<body style="max-width:760px;margin:4rem auto;padding:0 1.5rem;font:16px/1.6 system-ui,sans-serif;color:#17202a">
<h1>CacheDB Public Maven Repository</h1>
<p>This repository serves immutable CacheDB release artifacts without authentication.</p>
<p>Maven base URL: <code>https://esasmer-dou.github.io/cache-database/maven2</code></p>
<p>Latest published version in this deployment: <strong>$Version</strong></p>
<p><a href="https://github.com/esasmer-dou/cache-database">Documentation and source</a></p>
</body>
</html>
"@
Set-Content -LiteralPath (Join-Path $outputRoot "index.html") -Value $index -NoNewline

Write-Host "Public Maven repository prepared: $outputRoot"
Write-Host "Anonymous repository URL: https://esasmer-dou.github.io/cache-database/maven2"
