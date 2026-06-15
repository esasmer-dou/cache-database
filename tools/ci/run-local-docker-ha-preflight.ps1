param(
    [string]$MavenExecutable = "",
    [int]$RedisPort = 56379,
    [int]$PostgresPort = 55432,
    [int]$MssqlPort = 14333,
    [int]$RedisOutageSeconds = 5,
    [int]$MssqlRestartWaitSeconds = 5,
    [switch]$SkipMssql,
    [switch]$KeepContainers,
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-local-docker-ha-reports"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = Get-Date -Format "yyyyMMddHHmmss"
}

if ($RunId -notmatch '^[A-Za-z0-9_.-]+$') {
    throw "RunId may only contain letters, numbers, dot, underscore, and dash."
}

$redisName = "cachedb-local-ha-$RunId-redis"
$postgresName = "cachedb-local-ha-$RunId-postgres"
$mssqlName = "cachedb-local-ha-$RunId-mssql"
$startedContainers = New-Object System.Collections.Generic.List[string]
$startedAt = [DateTimeOffset]::UtcNow

function Invoke-Docker {
    param([string[]]$Arguments)
    $output = & docker @Arguments 2>&1
    if ($global:LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed. Output: $output"
    }
    return $output
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

function Assert-DockerAvailable {
    Invoke-Docker @("version", "--format", "{{.Server.Version}}") | Out-Null
}

function Remove-ContainerIfOwned {
    param([string]$Name)
    if ($Name -notmatch '^cachedb-local-ha-[A-Za-z0-9_.-]+-(redis|postgres|mssql)$') {
        throw "Refusing to remove unexpected container name: $Name"
    }
    $existing = docker ps -a --filter "name=^/$Name$" --format "{{.Names}}" 2>$null
    if ($existing -contains $Name) {
        docker rm -f $Name | Out-Null
    }
}

function Start-Redis {
    Remove-ContainerIfOwned -Name $redisName
    Invoke-Docker @("run", "-d", "--name", $redisName, "-p", "${RedisPort}:6379", "redis:8-alpine") | Out-Null
    $startedContainers.Add($redisName)
}

function Start-Postgres {
    Remove-ContainerIfOwned -Name $postgresName
    Invoke-Docker @(
        "run", "-d",
        "--name", $postgresName,
        "-e", "POSTGRES_DB=postgres",
        "-e", "POSTGRES_USER=postgres",
        "-e", "POSTGRES_PASSWORD=postgresql",
        "-p", "${PostgresPort}:5432",
        "postgres:16"
    ) | Out-Null
    $startedContainers.Add($postgresName)
}

function Start-Mssql {
    if ($SkipMssql) {
        return
    }
    Remove-ContainerIfOwned -Name $mssqlName
    Invoke-Docker @(
        "run", "-d",
        "--name", $mssqlName,
        "-e", "ACCEPT_EULA=Y",
        "-e", "MSSQL_PID=Developer",
        "-e", "MSSQL_SA_PASSWORD=YourStrong!Passw0rd",
        "-p", "${MssqlPort}:1433",
        "mcr.microsoft.com/mssql/server:2022-latest"
    ) | Out-Null
    $startedContainers.Add($mssqlName)
}

function Wait-RedisReady {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $ping = docker exec $redisName redis-cli ping 2>$null
        if ($global:LASTEXITCODE -eq 0 -and (($ping -join "").Trim()) -eq "PONG") {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Redis container did not become ready on port $RedisPort."
}

function Wait-PostgresReady {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        docker exec $postgresName pg_isready -U postgres -d postgres | Out-Null
        if ($global:LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "PostgreSQL container did not become ready on port $PostgresPort."
}

function Wait-MssqlReady {
    if ($SkipMssql) {
        return
    }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(180)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-TcpPort -HostName "127.0.0.1" -Port $MssqlPort) {
            Start-Sleep -Seconds 20
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "SQL Server container did not open TCP port $MssqlPort."
}

function Invoke-RedisPreflight {
    & (Join-Path $PSScriptRoot "run-redis-failover-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -RedisUri "redis://127.0.0.1:$RedisPort" `
        -RedisContainerId $redisName `
        -OutageSeconds $RedisOutageSeconds `
        -PostgresUrl "jdbc:postgresql://127.0.0.1:$PostgresPort/postgres" `
        -PostgresUser "postgres" `
        -PostgresPassword "postgresql"
}

function Invoke-MssqlPreflight {
    if ($SkipMssql) {
        return
    }
    & (Join-Path $PSScriptRoot "run-mssql-provider-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -MssqlUrl "jdbc:sqlserver://127.0.0.1:$MssqlPort;databaseName=tempdb;encrypt=false;trustServerCertificate=true" `
        -MssqlUser "sa" `
        -MssqlPassword "YourStrong!Passw0rd" `
        -ReportsDir (Join-Path $reportsDir "mssql-provider") `
        -RestartSqlServerContainer `
        -MssqlContainerId $mssqlName `
        -RestartWaitSeconds $MssqlRestartWaitSeconds
}

function Write-Summary {
    param([string]$Status)
    if (-not (Test-Path $reportsDir)) {
        New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null
    }
    $summaryPath = Join-Path $reportsDir "local-docker-ha-preflight.md"
    $jsonPath = Join-Path $reportsDir "local-docker-ha-preflight.json"
    $finishedAt = [DateTimeOffset]::UtcNow
    $summary = @(
        "# Local Docker HA Preflight",
        "",
        "- Status: ``$Status``",
        "- Run id: ``$RunId``",
        "- Redis outage/recovery evidence: ``passed``",
        "- PostgreSQL coordination backing store: ``passed``",
        "- MSSQL restart/reconnect evidence: ``$(if ($SkipMssql) { 'skipped' } else { 'passed' })``",
        "- Started at: ``$($startedAt.ToString("O"))``",
        "- Finished at: ``$($finishedAt.ToString("O"))``",
        "",
        "This Docker Desktop preflight validates local outage/restart behavior. It is not a substitute for a managed Redis failover or SQL Server Always On failover in the target production topology."
    )
    $summary | Set-Content -Path $summaryPath -Encoding UTF8

    $json = [ordered]@{
        status = $Status
        runId = $RunId
        redisPort = $RedisPort
        postgresPort = $PostgresPort
        mssqlPort = $MssqlPort
        redisOutageSeconds = $RedisOutageSeconds
        skipMssql = [bool]$SkipMssql
        startedAt = $startedAt.ToString("O")
        finishedAt = $finishedAt.ToString("O")
        scope = "local-docker-outage-and-restart-preflight"
    }
    $json | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8
    Write-Host "Local Docker HA preflight summary: $summaryPath"
}

try {
    Assert-DockerAvailable
    if (Test-Path $reportsDir) {
        Remove-Item -Recurse -Force $reportsDir
    }
    New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null

    Write-Host "Starting local Docker preflight containers..."
    Start-Redis
    Start-Postgres
    Start-Mssql

    Wait-RedisReady
    Wait-PostgresReady
    Wait-MssqlReady

    Write-Host "Running Redis outage/recovery preflight..."
    Invoke-RedisPreflight

    Write-Host "Running MSSQL restart/reconnect preflight..."
    Invoke-MssqlPreflight

    Write-Summary -Status "passed"
    Write-Host "Local Docker HA preflight passed."
} finally {
    if (-not $KeepContainers) {
        foreach ($container in @($startedContainers)) {
            Remove-ContainerIfOwned -Name $container
        }
    } else {
        Write-Host "Keeping containers: $($startedContainers -join ', ')"
    }
}
