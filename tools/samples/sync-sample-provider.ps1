param(
    [string] $CanonicalSample = "./sample-cache-database-postgresql",
    [string] $TargetSample = "./sample-cache-database-mssql",
    [ValidateSet("mssql", "oracle")]
    [string] $TargetProvider = "mssql",
    [switch] $Check
)

$ErrorActionPreference = "Stop"

$canonicalRoot = (Resolve-Path -LiteralPath $CanonicalSample).Path
$targetRoot = (Resolve-Path -LiteralPath $TargetSample).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

if (-not $canonicalRoot.StartsWith($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    -not $targetRoot.StartsWith($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Sample roots must remain inside the CacheDB workspace."
}

function Get-ProviderNeutralJavaFiles {
    param([string] $Root)

    Get-ChildItem -LiteralPath (Join-Path $Root "src") -Recurse -File -Filter "*.java" |
        Where-Object { $_.Name -notmatch "^(Postgresql|Mssql|Oracle)Sample(Application|IT)\.java$" } |
        ForEach-Object {
            [pscustomobject]@{
                RelativePath = $_.FullName.Substring($Root.Length + 1)
                FullName = $_.FullName
            }
        }
}

function Get-IntegrationContractBody {
    param([string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    $content = Get-Content -LiteralPath $Path -Raw
    $contractStart = $content.IndexOf("        jdbcTemplate.update(", [System.StringComparison]::Ordinal)
    if ($contractStart -lt 0) {
        throw "Integration contract marker was not found in $Path"
    }
    return $content.Substring($contractStart).Replace("`r`n", "`n")
}

function Convert-ProviderBoundJava {
    param(
        [string] $Content,
        [string] $RelativePath,
        [ValidateSet("postgresql", "mssql", "oracle")] [string] $FromProvider,
        [ValidateSet("postgresql", "mssql", "oracle")] [string] $ToProvider
    )

    $purgeContract = "src/purge-test/java/com/example/cachedb/purge/TenantPurgeContractTest.java"
    $purgeRuntime = "src/purge/java/com/example/cachedb/purge/TenantPurgeRuntime.java"
    $path = $RelativePath.Replace([char]92, [char]47)
    if ($path -cnotin @($purgeContract, $purgeRuntime)) { return $Content }
    $dialects = @{ postgresql = "postgres"; mssql = "mssql"; oracle = "oracle" }
    # Only explicit provider bindings may differ; all other bytes remain exact.
    $bindings = if ($path -ceq $purgeRuntime) {
        $constants = @{ postgresql = "POSTGRES"; mssql = "MSSQL"; oracle = "ORACLE" }
        @(@{
            Pattern = 'private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect\.(?:POSTGRES|MSSQL|ORACLE);'
            From = 'private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect.' + $constants[$FromProvider] + ';'
            To = 'private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect.' + $constants[$ToProvider] + ';'
        })
    } else { @(
        @{
            Pattern = '"cache-database-(?:postgresql|mssql|oracle)-tenant-purge\.postman_collection\.json"'
            From = '"cache-database-' + $FromProvider + '-tenant-purge.postman_collection.json"'
            To = '"cache-database-' + $ToProvider + '-tenant-purge.postman_collection.json"'
        },
        @{
            Pattern = '"com/reactor/cachedb/jdbc/migrations/(?:postgres|mssql|oracle)/"'
            From = '"com/reactor/cachedb/jdbc/migrations/' + $dialects[$FromProvider] + '/"'
            To = '"com/reactor/cachedb/jdbc/migrations/' + $dialects[$ToProvider] + '/"'
        }
    ) }
    foreach ($binding in $bindings) {
        $occurrences = [regex]::Matches($Content, $binding.Pattern)
        if ($occurrences.Count -ne 1 -or
            -not [string]::Equals($occurrences[0].Value, $binding.From, [StringComparison]::Ordinal)) {
            throw "Expected one exact $FromProvider purge binding in $RelativePath."
        }
        $Content = $Content.Replace($binding.From, $binding.To)
    }
    return $Content
}

$canonicalFiles = @(Get-ProviderNeutralJavaFiles -Root $canonicalRoot)
$providerDisplayName = switch ($TargetProvider) {
    "mssql" { "MSSQL" }
    "oracle" { "Oracle" }
}
$canonicalByPath = @{}
$differences = [System.Collections.Generic.List[string]]::new()
foreach ($file in $canonicalFiles) {
    $canonicalByPath[$file.RelativePath] = $file.FullName
    $targetPath = Join-Path $targetRoot $file.RelativePath
    if ($Check) {
        if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
            $differences.Add("Missing in $providerDisplayName sample: $($file.RelativePath)")
            continue
        }
        if ($file.RelativePath.Replace([char]92, [char]47) -cin @(
            "src/purge-test/java/com/example/cachedb/purge/TenantPurgeContractTest.java",
            "src/purge/java/com/example/cachedb/purge/TenantPurgeRuntime.java")) {
            $canonicalContent = Convert-ProviderBoundJava -Content (Get-Content -LiteralPath $file.FullName -Raw) `
                -RelativePath $file.RelativePath -FromProvider postgresql -ToProvider postgresql
            $targetContent = Convert-ProviderBoundJava -Content (Get-Content -LiteralPath $targetPath -Raw) `
                -RelativePath $file.RelativePath -FromProvider $TargetProvider -ToProvider postgresql
            $same = [string]::Equals($canonicalContent, $targetContent, [StringComparison]::Ordinal)
        } else {
            $canonicalHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            $targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
            $same = $canonicalHash -eq $targetHash
        }
        if (-not $same) {
            $differences.Add("Different provider-neutral file: $($file.RelativePath)")
        }
        continue
    }
    $targetDirectory = Split-Path -Parent $targetPath
    if (-not (Test-Path -LiteralPath $targetDirectory)) {
        New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
    }
    if ($file.RelativePath.Replace([char]92, [char]47) -cin @(
        "src/purge-test/java/com/example/cachedb/purge/TenantPurgeContractTest.java",
        "src/purge/java/com/example/cachedb/purge/TenantPurgeRuntime.java")) {
        $targetContent = Convert-ProviderBoundJava -Content (Get-Content -LiteralPath $file.FullName -Raw) `
            -RelativePath $file.RelativePath -FromProvider postgresql -ToProvider $TargetProvider
        [IO.File]::WriteAllText($targetPath, $targetContent, [Text.UTF8Encoding]::new($false))
    } else {
        Copy-Item -LiteralPath $file.FullName -Destination $targetPath -Force
    }
}

foreach ($targetFile in @(Get-ProviderNeutralJavaFiles -Root $targetRoot)) {
    if (-not $canonicalByPath.ContainsKey($targetFile.RelativePath)) {
        if ($Check) {
            $differences.Add("Extra in $providerDisplayName sample: $($targetFile.RelativePath)")
            continue
        }
        $resolvedTarget = [System.IO.Path]::GetFullPath($targetFile.FullName)
        if (-not $resolvedTarget.StartsWith($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove a file outside the target sample: $resolvedTarget"
        }
        Remove-Item -LiteralPath $resolvedTarget -Force
    }
}

if ($Check) {
    $canonicalIntegrationTest = Join-Path $canonicalRoot "src\test\java\com\example\cachedb\sample\PostgresqlSampleIT.java"
    $targetIntegrationTestName = switch ($TargetProvider) {
        "mssql" { "MssqlSampleIT.java" }
        "oracle" { "OracleSampleIT.java" }
    }
    $targetIntegrationTest = Join-Path $targetRoot "src\test\java\com\example\cachedb\sample\$targetIntegrationTestName"
    $canonicalContract = Get-IntegrationContractBody -Path $canonicalIntegrationTest
    $targetContract = Get-IntegrationContractBody -Path $targetIntegrationTest
    if ($null -eq $canonicalContract -or $null -eq $targetContract) {
        $differences.Add("Provider integration contract test is missing from one of the samples.")
    } elseif ($canonicalContract -cne $targetContract) {
        $differences.Add("Provider integration contract bodies differ after provider-specific setup.")
    }
    if ($differences.Count -gt 0) {
        $differences | ForEach-Object { Write-Error $_ }
        throw "Provider-neutral sample parity failed with $($differences.Count) difference(s)."
    }
    Write-Host "Verified $($canonicalFiles.Count) provider-neutral Java files and the shared integration contract across PostgreSQL and $providerDisplayName samples."
    exit 0
}

Write-Host "Synchronized $($canonicalFiles.Count) provider-neutral Java files from PostgreSQL to $providerDisplayName sample."
