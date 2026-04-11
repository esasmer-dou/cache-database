param(
    [string]$RedisUri = "redis://default:welcome1@127.0.0.1:56379",
    [string]$PostgresUrl = "jdbc:postgresql://127.0.0.1:55432/postgres",
    [string]$PostgresUser = "postgres",
    [string]$PostgresPassword = "postgresql"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path $PSScriptRoot))
$reportDir = Join-Path $repoRoot "target\cachedb-prodtest-reports"
$invokeMaven = Join-Path $repoRoot "tools\build\invoke-maven-semeru.ps1"

& $invokeMaven `
    -WorkingDirectory $repoRoot `
    -MavenArgs @(
        "-pl", "cachedb-production-tests",
        "-am",
        "-DforkCount=0",
        "-Dtest=MultiInstanceCoordinationSmokeTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dcachedb.prod.reportDir=$reportDir",
        "-Dcachedb.prod.redis.uri=$RedisUri",
        "-Dcachedb.prod.postgres.url=$PostgresUrl",
        "-Dcachedb.prod.postgres.user=$PostgresUser",
        "-Dcachedb.prod.postgres.password=$PostgresPassword",
        "test"
    )

Write-Host ""
Write-Host "Multi-instance coordination smoke completed."
Write-Host "Reports:"
Write-Host " - $reportDir\\multi-instance-coordination-smoke.json"
Write-Host " - $reportDir\\multi-instance-coordination-smoke.md"
