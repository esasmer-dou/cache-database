#requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RepositoryRoot,
    [Parameter(Mandatory)][string]$StagingRoot,
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath($RepositoryRoot)
$staging = [IO.Path]::GetFullPath($StagingRoot)
$sourcePrefix = $repository.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$stagingPrefix = $staging.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$samples = @('sample-cache-database-postgresql', 'sample-cache-database-mssql', 'sample-cache-database-oracle')
# These public source targets are intentionally absent from the binary/sample bundle.
$externalSourceTargets = [Collections.Generic.HashSet[string]]::new(
    [string[]]@(
        'cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/postgres/V014__durable_write_fence.sql'
        'cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/mssql/V014__durable_write_fence.sql'
        'cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/oracle/V014__durable_write_fence.sql'
        'pom.xml'
        '.github/workflows/production-evidence.yml'
        'cachedb-examples/src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java'
        'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisNearCacheCoordinator.java'
        'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisWatchRegistry.java'
        'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisEntityRepository.java'
        'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java'
        'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProjectionRepository.java'
        'cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisQueryIndexManager.java'
        'cachedb-storage-jdbc/src/main/java/com/cachedb/jdbc/AbstractJdbcWriteBehindFlusher.java'
        'cachedb-core/src/main/java/com/cachedb/core/registry/DefaultEntityRegistry.java'
        'cachedb-core/src/main/java/com/cachedb/core/queue/StoragePerformanceCollector.java'
        'cachedb-starter/src/main/java/com/cachedb/starter/AdminConsistencyInspector.java'
        'cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabase.java'
        'cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabaseAdminHttpServer.java'
        'cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabaseSchemaAdmin.java'
        'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheDistributedJobExecutor.java'
        'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/snapshot/JacksonJsonArrayCodec.java'
        'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/snapshot/SnapshotJobs.java'
    ),
    [StringComparer]::Ordinal
)
if (!(Test-Path -LiteralPath $repository -PathType Container) -or
    !(Test-Path -LiteralPath $staging -PathType Container) -or $repository -eq $staging) {
    throw 'Repository and staging must be distinct existing directories.'
}

$inline = '(?<prefix>!?\[[^\]\r\n]*\]\()(?<url><[^>\r\n]+>|[^\s)\r\n]+)(?<suffix>(?:[ \t]+(?:"[^"\r\n]*"|''[^''\r\n]*''))?\))'
$reference = '^(?<prefix>[ \t]{0,3}\[[^\]\r\n]+\]:[ \t]*)(?<url><[^>\r\n]+>|[^\s\r\n]+)(?<suffix>.*)$'
$changed = 0
$links = 0
foreach ($file in Get-ChildItem -LiteralPath $staging -Filter '*.md' -File -Recurse) {
    if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked documentation is not allowed: $($file.FullName)" }
    $relative = [IO.Path]::GetRelativePath($staging, $file.FullName).Replace('\', '/')
    $sourceRelative = $relative
    foreach ($sample in $samples) {
        if ($relative.StartsWith("samples/$sample/", [StringComparison]::Ordinal)) {
            $sourceRelative = $relative.Substring('samples/'.Length)
            break
        }
    }
    $sourceFile = Join-Path $repository $sourceRelative
    if (!(Test-Path -LiteralPath $sourceFile -PathType Leaf)) { continue }
    $sourceDirectory = [IO.Path]::GetDirectoryName($sourceFile)
    $destinationDirectory = $file.DirectoryName
    $rewrite = [Text.RegularExpressions.MatchEvaluator]{ param($match)
        $raw = $match.Groups['url'].Value
        $bracketed = $raw.StartsWith('<') -and $raw.EndsWith('>')
        $url = if ($bracketed) { $raw.Substring(1, $raw.Length - 2) } else { $raw }
        if ($url -match '^(?:[A-Za-z][A-Za-z0-9+.-]*:|/|#)' -or $url.Contains('\\')) { return $match.Value }
        $parts = [regex]::Match($url, '^(?<path>[^?#]+)(?<tail>[?#].*)?$')
        if (!$parts.Success) { return $match.Value }
        $target = [IO.Path]::GetFullPath((Join-Path $sourceDirectory ([Uri]::UnescapeDataString($parts.Groups['path'].Value))))
        if (!$target.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) { return $match.Value }
        $targetRelative = [IO.Path]::GetRelativePath($repository, $target).Replace('\', '/')
        if ($targetRelative -match '(^|/)(\.git|\.codex|agentmemory|secrets|\.ssh)(/|$)|(^|/)(PROJECT_MEMORY|MEMORY)\.md$|(^|/)\.env($|\.)') {
            throw "Private documentation target is not allowed: $targetRelative"
        }
        $stagedRelative = $targetRelative
        foreach ($sample in $samples) {
            if ($targetRelative -eq $sample -or $targetRelative.StartsWith("$sample/", [StringComparison]::Ordinal)) {
                $stagedRelative = "samples/$targetRelative"
                break
            }
        }
        $stagedTarget = [IO.Path]::GetFullPath((Join-Path $staging $stagedRelative))
        if (!$stagedTarget.StartsWith($stagingPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Documentation target escaped staging.' }
        if (Test-Path -LiteralPath $stagedTarget) {
            $replacement = [IO.Path]::GetRelativePath($destinationDirectory, $stagedTarget).Replace('\', '/')
            $replacement = ($replacement.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
        } elseif (($targetRelative.StartsWith('tools/', [StringComparison]::Ordinal) -or
                   $targetRelative -match '^cachedb-[A-Za-z0-9-]+/README\.md$' -or
                   $externalSourceTargets.Contains($targetRelative)) -and
                  (Test-Path -LiteralPath $target -PathType Leaf)) {
            $encoded = ($targetRelative.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
            $replacement = "https://github.com/esasmer-dou/cache-database/blob/v$Version/$encoded"
        } else {
            return $match.Value
        }
        $replacement += $parts.Groups['tail'].Value
        if ($bracketed) { $replacement = "<$replacement>" }
        if ($replacement -ceq $raw) { return $match.Value }
        $script:links++
        return $match.Groups['prefix'].Value + $replacement + $match.Groups['suffix'].Value
    }
    $text = [IO.File]::ReadAllText($file.FullName)
    $fence = ''
    $updated = foreach ($line in [regex]::Split($text, '(?<=\n)')) {
        $marker = [regex]::Match($line, '^ {0,3}(?<fence>`{3,}|~{3,})')
        if ($marker.Success) {
            $value = $marker.Groups['fence'].Value
            if (!$fence) { $fence = $value }
            elseif ($value[0] -eq $fence[0] -and $value.Length -ge $fence.Length) { $fence = '' }
            $line
            continue
        }
        if ($fence) { $line; continue }
        $line = [regex]::Replace($line, $inline, $rewrite)
        [regex]::Replace($line, $reference, $rewrite)
    }
    $updated = $updated -join ''
    if ($updated -cne $text) {
        if ($file.IsReadOnly) { $file.IsReadOnly = $false }
        [IO.File]::WriteAllText($file.FullName, $updated, [Text.UTF8Encoding]::new($false))
        $changed++
    }
}
Write-Output "BUNDLE_DOCUMENTATION_REPAIRED files=$changed links=$links"
