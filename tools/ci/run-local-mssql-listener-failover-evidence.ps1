param(
    [string]$MavenExecutable = "",
    [int]$ListenerPort = 14333,
    [int]$PrimaryPort = 14334,
    [int]$SecondaryPort = 14335,
    [int]$ListenerFailoverTimeoutSeconds = 120,
    [switch]$SkipProviderEvidence,
    [switch]$KeepContainers,
    [string]$RunId = "",
    [string]$SqlServerImage = "mcr.microsoft.com/mssql/server:2022-latest",
    [string]$ProxyImage = "haproxy:2.9"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-local-mssql-listener-failover-reports"
$workDir = Join-Path $reportsDir "work"
$mssqlPassword = "YourStrong!Passw0rd"
$startedAt = [DateTimeOffset]::UtcNow

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = Get-Date -Format "yyyyMMddHHmmss"
}

if ($RunId -notmatch '^[A-Za-z0-9_.-]+$') {
    throw "RunId may only contain letters, numbers, dot, underscore, and dash."
}

$namePrefix = "cachedb-local-mssql-listener-$RunId"
$networkName = "$namePrefix-net"
$primaryName = "$namePrefix-primary"
$secondaryName = "$namePrefix-secondary"
$proxyName = "$namePrefix-proxy"
$readyFile = Join-Path $workDir "listener-ready.txt"
$switchFile = Join-Path $workDir "listener-switched.txt"
$listenerUrl = "jdbc:sqlserver://127.0.0.1:$ListenerPort;databaseName=tempdb;encrypt=false;trustServerCertificate=true;multiSubnetFailover=true;loginTimeout=5"
$startedContainers = New-Object System.Collections.Generic.List[string]
$networkCreated = $false

function Effective-MavenExecutable {
    if (-not [string]::IsNullOrWhiteSpace($MavenExecutable)) {
        return $MavenExecutable
    }
    if ($IsWindows) {
        return "mvn.cmd"
    }
    return "mvn"
}

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

function Wait-TcpPort {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds = 180
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-TcpPort -HostName "127.0.0.1" -Port $Port) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not open TCP port $Port within $TimeoutSeconds seconds."
}

function Wait-MssqlReady {
    param(
        [string]$Name,
        [int]$Port
    )
    Wait-TcpPort -Name $Name -Port $Port -TimeoutSeconds 210
    Write-Host "$Name TCP port is reachable. Waiting for SQL Server startup grace period..."
    Start-Sleep -Seconds 25
}

function Remove-ContainerIfOwned {
    param([string]$Name)
    if ($Name -notmatch '^cachedb-local-mssql-listener-[A-Za-z0-9_.-]+-(primary|secondary|proxy)$') {
        throw "Refusing to remove unexpected container name: $Name"
    }
    $existing = docker ps -a --filter "name=^/$Name$" --format "{{.Names}}" 2>$null
    if ($existing -contains $Name) {
        docker rm -f $Name | Out-Null
    }
}

function Remove-NetworkIfOwned {
    param([string]$Name)
    if ($Name -notmatch '^cachedb-local-mssql-listener-[A-Za-z0-9_.-]+-net$') {
        throw "Refusing to remove unexpected network name: $Name"
    }
    $existing = docker network ls --filter "name=^$Name$" --format "{{.Name}}" 2>$null
    if ($existing -contains $Name) {
        docker network rm $Name | Out-Null
    }
}

function Start-SqlServerContainer {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port
    )
    Remove-ContainerIfOwned -Name $Name
    Invoke-Docker @(
        "run", "-d",
        "--name", $Name,
        "--hostname", $HostName,
        "--network", $networkName,
        "-e", "ACCEPT_EULA=Y",
        "-e", "MSSQL_PID=Developer",
        "-e", "MSSQL_SA_PASSWORD=$mssqlPassword",
        "-p", "${Port}:1433",
        $SqlServerImage
    ) | Out-Null
    $startedContainers.Add($Name)
}

function New-HaproxyConfig {
    param([string]$BackendName)
    $configPath = Join-Path $workDir "haproxy-$BackendName.cfg"
    $targetContainer = if ($BackendName -eq "primary") { $primaryName } else { $secondaryName }
    $content = @"
global
    log stdout format raw local0
    maxconn 256

defaults
    log global
    mode tcp
    timeout connect 5s
    timeout client 30s
    timeout server 30s

frontend mssql_listener
    bind *:1433
    default_backend mssql_backend

backend mssql_backend
    server $BackendName ${targetContainer}:1433 check inter 1000 rise 1 fall 1
"@
    Set-Content -Path $configPath -Value $content -Encoding ASCII
    return $configPath
}

function Start-Proxy {
    param([string]$BackendName)
    Remove-ContainerIfOwned -Name $proxyName
    $configPath = New-HaproxyConfig -BackendName $BackendName
    Invoke-Docker @(
        "run", "-d",
        "--name", $proxyName,
        "--network", $networkName,
        "-p", "${ListenerPort}:1433",
        "-v", "${configPath}:/usr/local/etc/haproxy/haproxy.cfg:ro",
        $ProxyImage
    ) | Out-Null
    if (-not $startedContainers.Contains($proxyName)) {
        $startedContainers.Add($proxyName)
    }
    Wait-TcpPort -Name "MSSQL listener proxy ($BackendName)" -Port $ListenerPort -TimeoutSeconds 60
}

function Wait-File {
    param(
        [string]$Path,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-Path $Path) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for file: $Path"
}

function Start-ListenerFailoverTest {
    $maven = Effective-MavenExecutable
    $logPath = Join-Path $reportsDir "listener-failover-test.log"
    $arguments = @(
        "-q",
        "-pl", "cachedb-storage-mssql",
        "-am",
        "test",
        "-Dtest=MssqlListenerFailoverSqlServerTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dcachedb.it.mssql.listenerFailover.required=true",
        "-Dcachedb.it.mssql.listenerFailover.readyFile=$readyFile",
        "-Dcachedb.it.mssql.listenerFailover.switchFile=$switchFile",
        "-Dcachedb.it.mssql.listenerFailover.timeoutSeconds=$ListenerFailoverTimeoutSeconds",
        "-Dcachedb.it.mssql.required=true",
        "-Dcachedb.it.mssql.url=$listenerUrl",
        "-Dcachedb.it.mssql.user=sa",
        "-Dcachedb.it.mssql.password=$mssqlPassword"
    )
    Write-Host "Starting listener failover JDBC smoke test..."
    return Start-Job -Name "cachedb-mssql-listener-failover-$RunId" -ScriptBlock {
        param(
            [string]$RepoRoot,
            [string]$Maven,
            [string[]]$Arguments,
            [string]$LogPath
        )
        Set-Location $RepoRoot
        & $Maven @Arguments *> $LogPath
        $exitCode = $global:LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "Maven listener failover test failed with exit code $exitCode. See $LogPath"
        }
    } -ArgumentList $repoRoot, $maven, $arguments, $logPath
}

function Complete-ListenerFailoverTest {
    param([System.Management.Automation.Job]$Job)
    $completed = Wait-Job -Job $Job -Timeout ($ListenerFailoverTimeoutSeconds + 90)
    if ($null -eq $completed) {
        Stop-Job -Job $Job | Out-Null
        throw "Listener failover JDBC smoke test timed out."
    }
    Receive-Job -Job $Job -ErrorAction Stop | Out-Host
    Remove-Job -Job $Job -Force | Out-Null
}

function Invoke-ProviderEvidenceAfterSwitch {
    if ($SkipProviderEvidence) {
        return
    }
    & (Join-Path $PSScriptRoot "run-mssql-provider-evidence.ps1") `
        -MavenExecutable (Effective-MavenExecutable) `
        -MssqlUrl $listenerUrl `
        -MssqlUser "sa" `
        -MssqlPassword $mssqlPassword `
        -ReportsDir (Join-Path $reportsDir "provider-after-listener-switch")
}

function Write-Summary {
    param([string]$Status)
    $finishedAt = [DateTimeOffset]::UtcNow
    $primaryServerName = if (Test-Path $readyFile) { (Get-Content $readyFile -Raw).Trim() } else { "" }
    $summaryPath = Join-Path $reportsDir "local-mssql-listener-failover-evidence.md"
    $jsonPath = Join-Path $reportsDir "local-mssql-listener-failover-evidence.json"
    $summary = @(
        "# Local MSSQL Listener Failover Evidence",
        "",
        "- Status: ``$Status``",
        "- Run id: ``$RunId``",
        "- Stable listener URL: ``jdbc:sqlserver://127.0.0.1:$ListenerPort``",
        "- Initial backend identity: ``$primaryServerName``",
        "- Listener backend switch: ``primary -> secondary``",
        "- Stale JDBC connection after switch: ``failed as expected``",
        "- New JDBC connection after switch: ``connected to a different SQL Server backend``",
        "- Provider evidence after listener switch: ``$(if ($SkipProviderEvidence) { 'skipped' } else { 'passed' })``",
        "- Started at: ``$($startedAt.ToString("O"))``",
        "- Finished at: ``$($finishedAt.ToString("O"))``",
        "",
        "Scope: this Docker Desktop lane validates CacheDB and Microsoft JDBC reconnect behavior through a stable listener-like endpoint after the TCP backend is switched. It is intentionally not a SQL Server Always On replication, quorum, read-only routing, or managed HA certification."
    )
    $summary | Set-Content -Path $summaryPath -Encoding UTF8

    $json = [ordered]@{
        status = $Status
        runId = $RunId
        listenerPort = $ListenerPort
        primaryPort = $PrimaryPort
        secondaryPort = $SecondaryPort
        listenerUrl = $listenerUrl
        initialBackendIdentity = $primaryServerName
        providerEvidenceAfterSwitch = if ($SkipProviderEvidence) { "skipped" } else { "passed" }
        startedAt = $startedAt.ToString("O")
        finishedAt = $finishedAt.ToString("O")
        scope = "local-docker-mssql-listener-failover-preflight"
    }
    $json | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8
    Write-Host "Local MSSQL listener failover evidence summary: $summaryPath"
}

try {
    Invoke-Docker @("version", "--format", "{{.Server.Version}}") | Out-Null
    if (Test-Path $reportsDir) {
        Remove-Item -Recurse -Force $reportsDir
    }
    New-Item -ItemType Directory -Path $workDir -Force | Out-Null

    Remove-ContainerIfOwned -Name $proxyName
    Remove-ContainerIfOwned -Name $primaryName
    Remove-ContainerIfOwned -Name $secondaryName
    Remove-NetworkIfOwned -Name $networkName

    Invoke-Docker @("network", "create", $networkName) | Out-Null
    $networkCreated = $true

    Write-Host "Starting local SQL Server primary and secondary containers..."
    Start-SqlServerContainer -Name $primaryName -HostName "cachedb-mssql-primary" -Port $PrimaryPort
    Start-SqlServerContainer -Name $secondaryName -HostName "cachedb-mssql-secondary" -Port $SecondaryPort
    Wait-MssqlReady -Name "Primary SQL Server" -Port $PrimaryPort
    Wait-MssqlReady -Name "Secondary SQL Server" -Port $SecondaryPort

    Write-Host "Starting listener proxy on primary backend..."
    Start-Proxy -BackendName "primary"

    $listenerJob = Start-ListenerFailoverTest
    try {
        Wait-File -Path $readyFile -TimeoutSeconds $ListenerFailoverTimeoutSeconds
        Write-Host "Listener smoke test reached primary. Switching proxy backend to secondary..."
        Start-Proxy -BackendName "secondary"
        Set-Content -Path $switchFile -Value ([DateTimeOffset]::UtcNow.ToString("O")) -Encoding ASCII
        Complete-ListenerFailoverTest -Job $listenerJob
    } catch {
        if ($null -ne $listenerJob) {
            Stop-Job -Job $listenerJob -ErrorAction SilentlyContinue | Out-Null
            Receive-Job -Job $listenerJob -ErrorAction SilentlyContinue | Out-Host
            Remove-Job -Job $listenerJob -Force -ErrorAction SilentlyContinue | Out-Null
        }
        throw
    }

    if ($SkipProviderEvidence) {
        Write-Host "Skipping full MSSQL provider evidence after listener switch."
    } else {
        Write-Host "Running MSSQL provider evidence through the listener after backend switch..."
    }
    Invoke-ProviderEvidenceAfterSwitch

    Write-Summary -Status "passed"
    Write-Host "Local MSSQL listener failover evidence passed."
} finally {
    if (-not $KeepContainers) {
        foreach ($container in @($startedContainers)) {
            Remove-ContainerIfOwned -Name $container
        }
        if ($networkCreated) {
            Remove-NetworkIfOwned -Name $networkName
        }
    } else {
        Write-Host "Keeping containers: $($startedContainers -join ', ')"
        Write-Host "Keeping network: $networkName"
    }
}
