param(
    [string] $PostgresqlSample = "./sample-cache-database-postgresql",
    [string] $MssqlSample = "./sample-cache-database-mssql"
)

$ErrorActionPreference = "Stop"

function Get-ComparableFiles {
    param([string] $Root)

    $resolved = (Resolve-Path -LiteralPath $Root).Path
    Get-ChildItem -LiteralPath (Join-Path $resolved "src") -Recurse -File |
        Where-Object {
            $_.FullName -match "\\src\\(main|test)\\java\\" -and
            $_.Name -notmatch "^(Postgresql|Mssql)Sample(Application|IT)\.java$"
        } |
        ForEach-Object {
            [pscustomobject]@{
                RelativePath = $_.FullName.Substring($resolved.Length + 1)
                FullName = $_.FullName
            }
        }
}

$postgresFiles = @(Get-ComparableFiles -Root $PostgresqlSample)
$mssqlFiles = @(Get-ComparableFiles -Root $MssqlSample)
$postgresByPath = @{}
$mssqlByPath = @{}
$postgresFiles | ForEach-Object { $postgresByPath[$_.RelativePath] = $_.FullName }
$mssqlFiles | ForEach-Object { $mssqlByPath[$_.RelativePath] = $_.FullName }

$allPaths = @($postgresByPath.Keys + $mssqlByPath.Keys | Sort-Object -Unique)
$differences = foreach ($relativePath in $allPaths) {
    if (-not $postgresByPath.ContainsKey($relativePath)) {
        "Only MSSQL sample contains: $relativePath"
        continue
    }
    if (-not $mssqlByPath.ContainsKey($relativePath)) {
        "Only PostgreSQL sample contains: $relativePath"
        continue
    }
    $postgresHash = (Get-FileHash -LiteralPath $postgresByPath[$relativePath] -Algorithm SHA256).Hash
    $mssqlHash = (Get-FileHash -LiteralPath $mssqlByPath[$relativePath] -Algorithm SHA256).Hash
    if ($postgresHash -ne $mssqlHash) {
        "Provider-neutral source differs: $relativePath"
    }
}

if ($differences) {
    $differences | ForEach-Object { Write-Error $_ }
    throw "Sample provider parity check failed. Keep common Java contracts identical and isolate provider differences."
}

Write-Host "Sample provider parity verified for $($allPaths.Count) common Java files."
