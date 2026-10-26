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
        if ([version]$Version -ge [version]'0.15.0' -and $suffix -ne '-javadoc.jar') {
            $requiredTypes = switch ($module) {
                'cachedb-annotations' { @('annotations/CacheSeek', 'annotations/CacheJobHandler') }
                'cachedb-core' { @('core/codec/CacheValueCodec', 'core/codec/GeneratedJsonCodecProvider', 'core/codec/GeneratedJsonCodecs') }
                'cachedb-storage-jdbc' { @('jdbc/AbstractJdbcWriteBehindFlusher') }
                'cachedb-spring-boot-starter' { @('spring/boot/snapshot/SnapshotPageRequest', 'spring/boot/snapshot/SnapshotTypedPageReadResult', 'spring/boot/snapshot/SnapshotManifest') }
                'cachedb-processor' { @('processor/CacheJobHandlerProcessor') }
                default { @() }
            }
            $extension = if ($suffix -eq '.jar') { 'class' } else { 'java' }
            $prefix = if ($suffix -eq '.jar' -or $module -eq 'cachedb-annotations') { 'com/reactor/cachedb' } else { 'com/cachedb' }
            foreach ($type in $requiredTypes) {
                if ($entries -notcontains "$prefix/$type.$extension") {
                    $invalid.Add("${module}: required 0.15 type is missing: $type.$extension")
                }
            }
            if ($module -eq 'cachedb-examples' -and $suffix -eq '.jar' -and
                $entries -notcontains 'META-INF/services/com.reactor.cachedb.core.codec.GeneratedJsonCodecProvider') {
                $invalid.Add('cachedb-examples: generated JSON provider service descriptor is missing')
            }
        }
        if ($module -eq 'cachedb-storage-jdbc' -and $suffix -ne '-javadoc.jar' -and
            [version]$Version -ge [version]'0.14.0') {
            foreach ($provider in @('postgres', 'mssql', 'oracle')) {
                $migration = "com/reactor/cachedb/jdbc/migrations/$provider/V014__durable_write_fence.sql"
                if ($entries -notcontains $migration) {
                    $invalid.Add("$([System.IO.Path]::GetRelativePath($repoRoot, $artifact)): required migration is missing: $migration")
                }
            }
        }
        if ([version]$Version -ge [version]'0.16.0' -and $suffix -ne '-javadoc.jar') {
            $types = switch ($module) {
                'cachedb-annotations' { @('annotations/CachePatch', 'annotations/CacheNearPolicy', 'annotations/CacheTransactional') }
                'cachedb-core' { @('core/api/CacheUnitOfWork', 'core/query/TypedColumn', 'core/cache/NearCachePolicy', 'core/cache/SingleFlightLoader', 'core/config/MutationConfig', 'core/trace/TraceContextPropagator') }
                'cachedb-storage-redis' { @('redis/RedisNearCacheCoordinator', 'redis/LocalDurabilitySignals') }
                'cachedb-starter' { @('starter/CacheTransactionTemplate') }
                'cachedb-spring-boot-starter' { @('spring/boot/CacheTransactionAutoConfiguration', 'spring/boot/CacheTraceAutoConfiguration', 'spring/boot/CacheDatabaseMetricsMeterBinder', 'spring/boot/snapshot/SnapshotDirtyBinding') }
                default { @() }
            }
            $extension = if ($suffix -eq '.jar') { 'class' } else { 'java' }
            $prefix = if ($suffix -eq '.jar') { 'com/reactor/cachedb' } else { 'com/cachedb' }
            foreach ($type in $types) {
                if ($entries -notcontains "$prefix/$type.$extension") {
                    $invalid.Add("${module}: required 0.16 type is missing: $type.$extension")
                }
            }
        }
        if ($module -eq 'cachedb-json' -and $suffix -ne '-javadoc.jar' -and
            [version]$Version -ge [version]'0.14.0') {
            $extension = if ($suffix -eq '.jar') { 'class' } else { 'java' }
            foreach ($type in @('StreamingJsonCodec', 'JsonTokenWriter', 'JsonUtf8Writer')) {
                $entry = "com/reactor/cachedb/json/$type.$extension"
                if ($entries -notcontains $entry) {
                    $invalid.Add("$([System.IO.Path]::GetRelativePath($repoRoot, $artifact)): required JSON implementation is missing: $entry")
                }
            }
        }
        if ($module -eq 'cachedb-core' -and $suffix -ne '-javadoc.jar' -and
            [version]$Version -ge [version]'0.14.0') {
            $extension = if ($suffix -eq '.jar') { 'class' } else { 'java' }
            $prefix = if ($suffix -eq '.jar') { 'com/reactor/cachedb' } else { 'com/cachedb' }
            if ($entries -notcontains "$prefix/core/codec/JsonNoopCheckpoint.$extension") {
                $invalid.Add("$([System.IO.Path]::GetRelativePath($repoRoot, $artifact)): reusable JSON checkpoint is missing")
            }
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
