param(
    [string] $PostgresqlSample = "./sample-cache-database-postgresql",
    [string] $MssqlSample = "./sample-cache-database-mssql",
    [string] $OracleSample = "./sample-cache-database-oracle"
)

$ErrorActionPreference = "Stop"
$parityScript = Join-Path (Split-Path -Parent $PSScriptRoot) "samples\sync-sample-provider.ps1"
& $parityScript -CanonicalSample $PostgresqlSample -TargetSample $MssqlSample -TargetProvider mssql -Check
if ($LASTEXITCODE -ne 0) {
    throw "MSSQL sample provider parity check failed."
}
& $parityScript -CanonicalSample $PostgresqlSample -TargetSample $OracleSample -TargetProvider oracle -Check
if ($LASTEXITCODE -ne 0) {
    throw "Oracle sample provider parity check failed."
}
