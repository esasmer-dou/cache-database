param(
    [string]$MavenExecutable = "",
    [string]$MssqlUrl = "jdbc:sqlserver://127.0.0.1:14333;databaseName=tempdb;encrypt=false;trustServerCertificate=true",
    [string]$MssqlUser = "sa",
    [string]$MssqlPassword = "YourStrong!Passw0rd",
    [string]$ReportsDir = ""
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

if (Test-Path $reportsDir) {
    Remove-Item -Recurse -Force $reportsDir
}
New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null

Wait-ForMssqlPort

Invoke-Maven @(
    "-pl", "cachedb-storage-mssql,cachedb-starter,cachedb-integration-tests",
    "-am",
    "test",
    "-Dtest=MssqlWriteBehindFlusherSqlServerTest,MssqlOutboxExternalChangeFeedAdapterSqlServerTest,MssqlMigrationPlannerSqlServerTest,MssqlOutboxMultiPodApplyRunnerTest",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-Dcachedb.it.mssql.required=true",
    "-Dcachedb.it.mssql.url=$MssqlUrl",
    "-Dcachedb.it.mssql.user=$MssqlUser",
    "-Dcachedb.it.mssql.password=$MssqlPassword"
)

$summaryPath = Join-Path $reportsDir "mssql-provider-evidence.md"
$jsonPath = Join-Path $reportsDir "mssql-provider-evidence.json"
$summary = @(
    "# MSSQL Provider Evidence",
    "",
    "- SQL Server write-behind idempotency smoke: passed",
    "- SQL Server outbox checkpoint smoke: passed",
    "- SQL Server migration discovery/warm/compare SQL smoke: passed",
    "- SQL Server multi-pod outbox apply checkpoint smoke: passed",
    "",
    "MSSQL remains explicit beta until larger soak, failover, and production replay lanes are completed."
)
$summary | Set-Content -Path $summaryPath -Encoding UTF8

$json = [ordered]@{
    provider = "mssql"
    status = "passed"
    tests = @(
        "MssqlWriteBehindFlusherSqlServerTest",
        "MssqlOutboxExternalChangeFeedAdapterSqlServerTest",
        "MssqlMigrationPlannerSqlServerTest",
        "MssqlOutboxMultiPodApplyRunnerTest"
    )
    generatedAt = [DateTimeOffset]::UtcNow.ToString("O")
}
$json | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8

Write-Host ""
Write-Host "MSSQL provider evidence run completed."
Write-Host "Reports:"
Write-Host " - $summaryPath"
Write-Host " - $jsonPath"
