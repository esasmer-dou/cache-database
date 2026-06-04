param(
    [string]$MavenExecutable = "",
    [string]$RedisUri = "redis://127.0.0.1:56379",
    [string]$PostgresUrl = "jdbc:postgresql://127.0.0.1:55432/postgres",
    [string]$PostgresUser = "postgres",
    [string]$PostgresPassword = "postgresql",
    [string]$ReportsDir = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($ReportsDir)) {
    $reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-prodtest-reports"
} elseif ([System.IO.Path]::IsPathRooted($ReportsDir)) {
    $reportsDir = $ReportsDir
} else {
    $reportsDir = Join-Path $repoRoot $ReportsDir
}

function Invoke-Maven {
    param([string[]]$Arguments)

    $effectiveMavenExecutable = $MavenExecutable
    if ([string]::IsNullOrWhiteSpace($effectiveMavenExecutable)) {
        $effectiveMavenExecutable = if ($IsWindows) { "mvn.cmd" } else { "mvn" }
    }

    Write-Host ""
    Write-Host "==> $effectiveMavenExecutable $($Arguments -join ' ')"
    $global:LASTEXITCODE = 0
    & $effectiveMavenExecutable @Arguments
    if ($global:LASTEXITCODE -ne 0) {
        throw "Maven failed with exit code $global:LASTEXITCODE"
    }
}

New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null
Get-ChildItem -Path $reportsDir -Filter "production-scenario-certification.*" -ErrorAction SilentlyContinue |
    Remove-Item -Force

Invoke-Maven @(
    "-pl", "cachedb-production-tests",
    "-am",
    "test",
    "-DforkCount=0",
    "-Dtest=ProductionScenarioCertificationSmokeTest",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-Dcachedb.prod.reportDir=$reportsDir",
    "-Dcachedb.prod.redis.uri=$RedisUri",
    "-Dcachedb.prod.postgres.url=$PostgresUrl",
    "-Dcachedb.prod.postgres.user=$PostgresUser",
    "-Dcachedb.prod.postgres.password=$PostgresPassword"
)

Write-Host ""
Write-Host "Production scenario certification completed."
Write-Host "Reports:"
Write-Host " - $(Join-Path $reportsDir "production-scenario-certification.json")"
Write-Host " - $(Join-Path $reportsDir "production-scenario-certification.md")"
