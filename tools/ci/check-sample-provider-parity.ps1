param(
    [string] $PostgresqlSample = "./sample-cache-database-postgresql",
    [string] $MssqlSample = "./sample-cache-database-mssql"
)

$ErrorActionPreference = "Stop"
$parityScript = Join-Path (Split-Path -Parent $PSScriptRoot) "samples\sync-sample-provider.ps1"
& $parityScript -CanonicalSample $PostgresqlSample -TargetSample $MssqlSample -Check
if ($LASTEXITCODE -ne 0) {
    throw "Sample provider parity check failed."
}
