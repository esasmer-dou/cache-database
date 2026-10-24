param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$ArchivePath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'A stable semantic version is required.' }
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$modules = @(
    'cachedb-annotations', 'cachedb-processor', 'cachedb-core', 'cachedb-json',
    'cachedb-storage-redis', 'cachedb-storage-jdbc', 'cachedb-storage-postgres',
    'cachedb-storage-mssql', 'cachedb-storage-oracle', 'cachedb-starter',
    'cachedb-spring-boot-starter', 'cachedb-spring-boot-starter-postgres',
    'cachedb-spring-boot-starter-mssql', 'cachedb-spring-boot-starter-oracle',
    'cachedb-spring-boot-starter-admin', 'cachedb-spring-boot-test',
    'cachedb-maven-plugin', 'cachedb-migration-recipes', 'cachedb-examples'
)
$expected = [ordered]@{ "artifacts/cache-database-$Version.pom" = 'pom.xml' }
$expected["artifacts/cachedb-bom/cachedb-bom-$Version.pom"] = 'cachedb-bom/pom.xml'
foreach ($module in $modules) {
    $expected["artifacts/$module/$module-$Version.pom"] = "$module/pom.xml"
    foreach ($suffix in @('.jar', '-sources.jar', '-javadoc.jar')) {
        $expected["artifacts/$module/$module-$Version$suffix"] = "$module/target/$module-$Version$suffix"
    }
}

$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ArchivePath).Path)
try {
    if ($archive.Entries.Count -gt 20000) { throw 'Release archive entry budget exceeded.' }
    $entries = @{}
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ($name.StartsWith('artifacts/')) {
            if ($entries.ContainsKey($name)) { throw "Duplicate artifact entry: $name" }
            $entries[$name] = $entry
        }
    }
    # Preflight every POM and artifact before importing any file.
    foreach ($name in $expected.Keys) {
        if (!$entries.ContainsKey($name)) { throw "Missing release artifact: $name" }
        $entry = $entries[$name]
        if ($entry.Length -lt 1 -or $entry.Length -gt 32MB) { throw "Artifact size budget exceeded: $name" }
        if ($name.EndsWith('.pom')) {
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $published = $reader.ReadToEnd().Replace("`r`n", "`n") }
            finally { $reader.Dispose() }
            $source = [IO.File]::ReadAllText((Join-Path $root $expected[$name])).Replace("`r`n", "`n")
            if ($source -cne $published) { throw "Release POM differs from checked-out source: $name" }
        }
    }
    foreach ($name in $expected.Keys) {
        $destination = Join-Path $root $expected[$name]
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)
        # Preserve canonical POM bytes too; checkout may normalize line endings.
        $input = $entries[$name].Open()
        try {
            $output = [IO.File]::Create($destination)
            try { $input.CopyTo($output) } finally { $output.Dispose() }
        } finally { $input.Dispose() }
    }
    Write-Host "Imported 57 JARs and 21 matching POMs for $Version without rebuilding."
} finally { $archive.Dispose() }
