param(
    [string]$MavenExecutable = "",
    [string]$MssqlUrl = "jdbc:sqlserver://127.0.0.1:14333;databaseName=tempdb;encrypt=false;trustServerCertificate=true",
    [string]$MssqlUser = "sa",
    [string]$MssqlPassword = "YourStrong!Passw0rd",
    [string]$ReportsDir = "",
    [switch]$RestartSqlServerContainer,
    [string]$MssqlContainerId = "",
    [int]$RestartWaitSeconds = 5
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($ReportsDir)) {
    $reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-mssql-provider-reports"
} elseif ([System.IO.Path]::IsPathRooted($ReportsDir)) {
    $reportsDir = $ReportsDir
} else {
    $reportsDir = Join-Path $repoRoot $ReportsDir
}

function Invoke-Maven {
    param(
        [string[]]$Arguments
    )

    $effectiveMavenExecutable = $MavenExecutable
    if ([string]::IsNullOrWhiteSpace($effectiveMavenExecutable)) {
        $effectiveMavenExecutable = if ($IsWindows) { "mvn.cmd" } else { "mvn" }
    }

    Write-Host ""
    Write-Host "==> $effectiveMavenExecutable $($Arguments -join ' ')"
    $global:LASTEXITCODE = 0
    & $effectiveMavenExecutable @Arguments
    $exitCode = $global:LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Maven failed with exit code $exitCode"
    }
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1000)) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-ForMssqlPort {
    if ($MssqlUrl -notmatch '^jdbc:sqlserver://([^;:/]+)(?::([0-9]+))?') {
        throw "Could not parse SQL Server host and port from JDBC URL: $MssqlUrl"
    }
    $hostName = $Matches[1]
    $port = if (-not [string]::IsNullOrWhiteSpace($Matches[2])) { [int]$Matches[2] } else { 1433 }
    $deadline = [DateTimeOffset]::Now.AddSeconds(150)
    while ([DateTimeOffset]::Now -lt $deadline) {
        if (Test-TcpPort -HostName $hostName -Port $port) {
            Write-Host "SQL Server TCP port is reachable at ${hostName}:${port}. Waiting for database startup grace period..."
            Start-Sleep -Seconds 20
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "SQL Server TCP port did not become reachable at ${hostName}:${port}"
}

function Resolve-MssqlContainer {
    if (-not [string]::IsNullOrWhiteSpace($MssqlContainerId)) {
        return $MssqlContainerId
    }
    if ($MssqlUrl -notmatch '^jdbc:sqlserver://([^;:/]+)(?::([0-9]+))?') {
        throw "Could not parse SQL Server host and port from JDBC URL: $MssqlUrl"
    }
    $port = if (-not [string]::IsNullOrWhiteSpace($Matches[2])) { [int]$Matches[2] } else { 1433 }
    $byPort = docker ps --filter "publish=$port" --format "{{.ID}}" | Select-Object -First 1
    if (-not [string]::IsNullOrWhiteSpace($byPort)) {
        return $byPort
    }
    $byImage = docker ps --filter "ancestor=mcr.microsoft.com/mssql/server:2022-latest" --format "{{.ID}}" | Select-Object -First 1
    if (-not [string]::IsNullOrWhiteSpace($byImage)) {
        return $byImage
    }
    throw "Could not resolve SQL Server container. Pass -MssqlContainerId explicitly."
}

function Invoke-MssqlEvidenceTests {
    param(
        [string]$Phase
    )

    Write-Host ""
    Write-Host "Running MSSQL provider evidence phase: $Phase"
    Invoke-Maven @(
        "-pl", "cachedb-storage-mssql,cachedb-starter,cachedb-spring-boot-starter,cachedb-integration-tests",
        "-am",
        "test",
        "-Dtest=MssqlFailureClassifierTest,MssqlWriteBehindFlusherTest,MssqlWriteBehindFlusherSqlServerTest,MssqlWriteBehindFlusherLoadSqlServerTest,MssqlOutboxExternalChangeFeedAdapterSqlServerTest,MssqlMigrationPlannerSqlServerTest,MssqlOutboxMultiPodApplyRunnerTest,CacheDbSpringPropertiesTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dcachedb.it.mssql.required=true",
        "-Dcachedb.it.mssql.url=$MssqlUrl",
        "-Dcachedb.it.mssql.user=$MssqlUser",
        "-Dcachedb.it.mssql.password=$MssqlPassword"
    )
}

if (Test-Path $reportsDir) {
    Remove-Item -Recurse -Force $reportsDir
}
New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null

Wait-ForMssqlPort

Invoke-MssqlEvidenceTests -Phase "pre-restart"

$restartContainer = ""
if ($RestartSqlServerContainer) {
    $restartContainer = Resolve-MssqlContainer
    Write-Host ""
    Write-Host "Restarting SQL Server container $restartContainer for reconnect evidence..."
    docker restart $restartContainer | Out-Host
    if ($RestartWaitSeconds -gt 0) {
        Start-Sleep -Seconds $RestartWaitSeconds
    }
    Wait-ForMssqlPort
    Invoke-MssqlEvidenceTests -Phase "post-restart"
}

$summaryPath = Join-Path $reportsDir "mssql-provider-evidence.md"
$jsonPath = Join-Path $reportsDir "mssql-provider-evidence.json"
$summary = @(
    "# MSSQL Provider Evidence",
    "",
    "- SQL Server write-behind idempotency smoke: passed",
    "- SQL Server concurrent duplicate-id/stale-version write race: passed",
    "- SQL Server live lock-timeout classification: passed",
    "- SQL Server provider-level lock/query timeout options: passed",
    "- SQL Server retryable timeout/deadlock/lock-conflict classifier coverage: passed",
    "- Spring Boot explicit MSSQL provider wiring: passed",
    "- SQL Server provider-tagged write performance breakdown: passed",
    "- SQL Server write-behind high-volume load: passed",
    "- SQL Server outbox checkpoint smoke: passed",
    "- SQL Server high-volume multi-pod outbox replay: passed",
    "- SQL Server representative-volume migration discovery/warm/compare: passed",
    "- SQL Server multi-pod outbox apply checkpoint smoke: passed",
    "- SQL Server container restart/reconnect check: $(if ($RestartSqlServerContainer) { 'passed' } else { 'not requested' })",
    "",
    "This lane is a CI regression gate for single-node SQL Server restart/reconnect, concurrency, lock classification, load, replay, checkpoint, provider-tagged metrics, and planner SQL behavior. It is not a replacement for a real SQL Server HA or Always On staging topology test."
)
$summary | Set-Content -Path $summaryPath -Encoding UTF8

$json = [ordered]@{
    provider = "mssql"
    status = "passed"
    tests = @(
        "MssqlFailureClassifierTest",
        "MssqlWriteBehindFlusherTest",
        "MssqlWriteBehindFlusherSqlServerTest",
        "MssqlWriteBehindFlusherLoadSqlServerTest",
        "MssqlOutboxExternalChangeFeedAdapterSqlServerTest",
        "MssqlMigrationPlannerSqlServerTest",
        "MssqlOutboxMultiPodApplyRunnerTest",
        "CacheDbSpringPropertiesTest"
    )
    restartSqlServerContainer = [bool]$RestartSqlServerContainer
    restartedContainer = $restartContainer
    generatedAt = [DateTimeOffset]::UtcNow.ToString("O")
}
$json | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8

Write-Host ""
Write-Host "MSSQL provider evidence run completed."
Write-Host "Reports:"
Write-Host " - $summaryPath"
Write-Host " - $jsonPath"
