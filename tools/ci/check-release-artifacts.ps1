param(
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($Version)) {
    [xml]$rootPom = Get-Content -LiteralPath (Join-Path $repoRoot "pom.xml")
    $Version = [string]$rootPom.project.version
}

$publicJarModules = @(
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

$missing = New-Object System.Collections.Generic.List[string]
$invalid = New-Object System.Collections.Generic.List[string]

function Get-JarEntries {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Artifact
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead($Artifact)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
}

foreach ($module in $publicJarModules) {
    $target = Join-Path (Join-Path $repoRoot $module) "target"
    foreach ($suffix in @(".jar", "-sources.jar", "-javadoc.jar")) {
        $artifact = Join-Path $target ($module + "-" + $Version + $suffix)
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            $missing.Add([System.IO.Path]::GetRelativePath($repoRoot, $artifact))
            continue
        }

        try {
            $entries = @(Get-JarEntries -Artifact $artifact)
        } catch {
            $invalid.Add("$([System.IO.Path]::GetRelativePath($repoRoot, $artifact)): $($_.Exception.Message)")
            continue
        }

        $hasExpectedContent = switch ($suffix) {
            ".jar" { @($entries | Where-Object { $_ -match '\.class$' }).Count -gt 0 }
            "-sources.jar" { @($entries | Where-Object { $_ -match '\.java$' }).Count -gt 0 }
            "-javadoc.jar" { @($entries | Where-Object { $_ -eq "index.html" }).Count -eq 1 }
            default { $false }
        }
        if (-not $hasExpectedContent) {
            $invalid.Add("$([System.IO.Path]::GetRelativePath($repoRoot, $artifact)): expected packaged content is missing")
        }
    }
}

$bomPom = Join-Path $repoRoot "cachedb-bom\pom.xml"
if (-not (Test-Path -LiteralPath $bomPom -PathType Leaf)) {
    $missing.Add("cachedb-bom/pom.xml")
}

if ($missing.Count -gt 0) {
    $missing | ForEach-Object { Write-Host "Missing release artifact: $_" }
}

if ($invalid.Count -gt 0) {
    $invalid | ForEach-Object { Write-Host "Invalid release artifact: $_" }
}

if ($missing.Count -gt 0 -or $invalid.Count -gt 0) {
    throw "Release artifact validation failed with $($missing.Count) missing and $($invalid.Count) invalid file(s)."
}

Write-Host "Release artifact validation passed for $($publicJarModules.Count) public jar modules and cachedb-bom $Version; binary, source, and javadoc contents were inspected."
