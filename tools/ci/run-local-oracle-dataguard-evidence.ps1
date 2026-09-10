param(
    [string]$MavenExecutable = "",
    [int]$ListenerPort = 15223,
    [int]$PrimaryPort = 15221,
    [int]$StandbyPort = 15222,
    [int]$DatabaseStartupTimeoutSeconds = 1500,
    [int]$TransitionTimeoutSeconds = 240,
    [string]$RunId = "",
    [string]$OracleImage = "oracle/database:19.3.0-ee",
    [string]$ProxyImage = "haproxy:2.9@sha256:81506628494800519f82caf4128cb693df21ee5f38fca467224fcff508d537de",
    [switch]$UseExistingTopology,
    [string]$PrimaryContainerName = "",
    [string]$StandbyContainerName = "",
    [string]$NetworkName = "",
    [switch]$SkipProviderEvidence,
    [switch]$SkipUnplannedFailover,
    [switch]$KeepContainers
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-local-oracle-dataguard-reports"
$workDir = Join-Path $reportsDir "work"
$oracleSysPassword = "CacheDbDg123!"
$applicationUser = "cachedb"
$applicationPassword = "CacheDbOracle123"
$serviceName = "CACHEDBPDB"
$startedAt = [DateTimeOffset]::UtcNow
$ownerLabel = "com.reactor.cachedb.owner=oracle-dg-evidence"
$createdContainers = New-Object System.Collections.Generic.List[string]
$networkCreated = $false
$proxyCreated = $false
$plannedEndpointReadyMs = 0L
$plannedApplicationRecoveryMs = 0L
$unplannedEndpointReadyMs = 0L
$unplannedApplicationRecoveryMs = 0L
$initialPrimaryIdentity = ""
$plannedPrimaryIdentity = ""
$failoverPrimaryIdentity = ""
$oracleImageId = ""
$proxyImageId = ""
$dockerServerVersion = ""
$dockerMemoryBytes = 0L
$dockerCpuCount = 0
$gitCommit = ""
$gitWorktreeDirty = $true
$physicalDataGuardReady = $false
$brokerPreTransitionReady = $false
$plannedServiceRecoveryPassed = $false
$plannedProviderEvidencePassed = $false
$switchbackPassed = $false
$unplannedServiceRecoveryPassed = $false
$unplannedProviderEvidencePassed = $false
$redundancyRestoredPassed = $false
$redundancyRestoreMs = 0L
$finalPrimaryIdentity = ""
$finalStandbyIdentity = ""

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = Get-Date -Format "yyyyMMddHHmmss"
}
if ($RunId -notmatch '^[A-Za-z0-9_.-]+$') {
    throw "RunId may only contain letters, numbers, dot, underscore, and dash."
}

$namePrefix = "cachedb-local-oracle-dg-$RunId"
if ([string]::IsNullOrWhiteSpace($PrimaryContainerName)) {
    $PrimaryContainerName = "$namePrefix-primary"
}
if ([string]::IsNullOrWhiteSpace($StandbyContainerName)) {
    $StandbyContainerName = "$namePrefix-standby"
}
if ([string]::IsNullOrWhiteSpace($NetworkName)) {
    $NetworkName = "$namePrefix-net"
}
$proxyContainerName = "$namePrefix-proxy"
$listenerUrl = "jdbc:oracle:thin:@//127.0.0.1:$ListenerPort/$serviceName"
$serviceFailoverUrl = "jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=5)(TRANSPORT_CONNECT_TIMEOUT=3)(RETRY_COUNT=1)(RETRY_DELAY=1)(ADDRESS_LIST=(LOAD_BALANCE=OFF)(FAILOVER=ON)(ADDRESS=(PROTOCOL=TCP)(HOST=127.0.0.1)(PORT=$PrimaryPort))(ADDRESS=(PROTOCOL=TCP)(HOST=127.0.0.1)(PORT=$StandbyPort)))(CONNECT_DATA=(SERVICE_NAME=$serviceName)))"

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
    param([int]$Port)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(750)) {
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
        [int]$TimeoutSeconds = 60
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-TcpPort -Port $Port) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not open TCP port $Port within $TimeoutSeconds seconds."
}

function Assert-LocalImage {
    param([string]$Image)
    docker image inspect $Image *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Required image '$Image' is not present locally. This runner does not pull licensed Oracle images automatically."
    }
}

function Assert-DockerResources {
    $memoryText = Invoke-Docker @("info", "--format", "{{.MemTotal}}")
    $memoryBytes = [long](([string]$memoryText).Trim())
    $minimumBytes = 14L * 1024L * 1024L * 1024L
    if ($memoryBytes -lt $minimumBytes) {
        throw "Oracle Data Guard evidence requires at least 14 GiB assigned to Docker. Available: $memoryBytes bytes."
    }
    $cpuText = Invoke-Docker @("info", "--format", "{{.NCPU}}")
    $cpuCount = [int](([string]$cpuText).Trim())
    if ($cpuCount -lt 6) {
        throw "Oracle Data Guard evidence requires at least 6 CPUs assigned to Docker. Available: $cpuCount."
    }
}

function Get-ContainerLabel {
    param([string]$Name)
    $value = docker inspect --format "{{index .Config.Labels `"com.reactor.cachedb.owner`"}}" $Name 2>$null
    if ($LASTEXITCODE -ne 0) {
        return ""
    }
    return ([string]$value).Trim()
}

function Remove-OwnedContainer {
    param([string]$Name)
    $existing = docker ps -a --filter "name=^/$Name$" --format "{{.Names}}" 2>$null
    if ($existing -notcontains $Name) {
        return
    }
    if ((Get-ContainerLabel -Name $Name) -ne "oracle-dg-evidence") {
        throw "Refusing to remove container without CacheDB Oracle DG ownership label: $Name"
    }
    docker rm -f $Name | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to remove owned container: $Name"
    }
}

function Remove-OwnedNetwork {
    param([string]$Name)
    $existing = docker network ls --filter "name=^$Name$" --format "{{.Name}}" 2>$null
    if ($existing -notcontains $Name) {
        return
    }
    $label = docker network inspect --format "{{index .Labels `"com.reactor.cachedb.owner`"}}" $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or ([string]$label).Trim() -ne "oracle-dg-evidence") {
        throw "Refusing to remove network without CacheDB Oracle DG ownership label: $Name"
    }
    docker network rm $Name | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to remove owned network: $Name"
    }
}

function Wait-ContainerHealthy {
    param(
        [string]$Name,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $state = docker inspect --format "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.OOMKilled}}" $Name 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "Oracle container disappeared while waiting for readiness: $Name"
        }
        $parts = ([string]$state).Trim().Split('|')
        if ($parts[0] -ne "running") {
            docker logs --tail 300 $Name | Out-Host
            throw "Oracle container stopped before readiness: $Name ($state)"
        }
        if ($parts[2] -eq "true") {
            docker logs --tail 300 $Name | Out-Host
            throw "Oracle container was OOM-killed: $Name"
        }
        if ($parts[1] -eq "healthy") {
            return
        }
        Start-Sleep -Seconds 5
    }
    docker logs --tail 400 $Name | Out-Host
    throw "Oracle container did not become healthy within $TimeoutSeconds seconds: $Name"
}

function Wait-OracleDatabaseCreation {
    param(
        [string]$Name,
        [string]$Sid,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $state = docker inspect --format "{{.State.Status}}|{{.State.OOMKilled}}" $Name 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "Oracle container disappeared while waiting for DBCA: $Name"
        }
        $parts = ([string]$state).Trim().Split('|')
        if ($parts[0] -ne "running" -or $parts[1] -eq "true") {
            docker logs --tail 400 $Name | Out-Host
            throw "Oracle DBCA container became unavailable: $Name ($state)"
        }

        $probe = @"
log=/opt/oracle/cfgtoollogs/dbca/$Sid/$Sid.log
if [ -f "`$log" ] && grep -q '\[FATAL\]' "`$log"; then
  exit 42
fi
if [ -f "`$log" ] && grep -q 'DBCA_PROGRESS : 100%' "`$log" && \
   ! pgrep -f '[o]racle.assistants.dbca.driver.DBConfigurator' >/dev/null; then
  exit 0
fi
exit 1
"@
        $probe | & docker exec -i $Name bash 2>$null
        $probeExitCode = $global:LASTEXITCODE
        if ($probeExitCode -eq 0) {
            # Let runOracle.sh finish its post-DBCA listener and file moves.
            Start-Sleep -Seconds 5
            return
        }
        if ($probeExitCode -eq 42) {
            docker logs --tail 400 $Name | Out-Host
            throw "Oracle DBCA reported a fatal error while creating $Sid in $Name."
        }
        if ($probeExitCode -eq 255) {
            # Oracle's standby duplicate can temporarily reject docker exec for the whole RMAN phase.
            $setupOutput = (docker logs --tail 120 $Name 2>&1 | Out-String)
            if ($setupOutput -match 'DATABASE SETUP WAS NOT SUCCESSFUL|\[FATAL\]') {
                $setupOutput | Write-Host
                throw "Oracle standby setup failed before DBCA probing became available in $Name."
            }
            Start-Sleep -Seconds 5
            continue
        }
        if ($probeExitCode -ne 1) {
            throw "Oracle DBCA completion probe failed in $Name with exit code $probeExitCode."
        }
        Start-Sleep -Seconds 5
    }
    docker logs --tail 400 $Name | Out-Host
    throw "Oracle DBCA did not complete within $TimeoutSeconds seconds: $Name"
}

function Invoke-ContainerSql {
    param(
        [string]$ContainerName,
        [string]$Sql
    )
    $script = @"
whenever sqlerror exit sql.sqlcode
set echo off feedback on pagesize 200 linesize 240 trimspool on
$Sql
exit;
"@
    $output = $script | & docker exec -i $ContainerName sqlplus -s / as sysdba 2>&1
    $exitCode = $global:LASTEXITCODE
    $text = ($output | Out-String).Trim()
    if ($exitCode -ne 0) {
        throw "SQL failed in $ContainerName with exit code $exitCode. Output: $text"
    }
    return $text
}

function Invoke-Dgmgrl {
    param(
        [string]$ContainerName,
        [string]$ConnectAlias,
        [string[]]$Commands,
        [switch]$AllowStatusWarnings
    )
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("connect sys/`"$oracleSysPassword`"@$ConnectAlias;")
    foreach ($command in $Commands) {
        $lines.Add($command.TrimEnd(';') + ";")
    }
    $lines.Add("exit;")
    $output = ($lines -join [Environment]::NewLine) |
        & docker exec -i $ContainerName dgmgrl -silent 2>&1
    $exitCode = $global:LASTEXITCODE
    $text = ($output | Out-String).Trim()
    if ($exitCode -ne 0) {
        throw "DGMGRL failed with exit code $exitCode. Output: $text"
    }
    if (-not $AllowStatusWarnings -and
            $text -match '(?im)^\s*(Error:|Failed\.|(?:Warning:\s*)?(?:ORA|DGM)-\d+)') {
        throw "DGMGRL reported an Oracle error. Output: $text"
    }
    return $text
}

function Wait-BrokerSuccess {
    param(
        [string]$ContainerName,
        [string]$ConnectAlias,
        [int]$TimeoutSeconds = 120
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $last = ""
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $last = Invoke-Dgmgrl -ContainerName $ContainerName -ConnectAlias $ConnectAlias `
                -Commands @("show configuration verbose") -AllowStatusWarnings
            if ($last -match '(?is)Configuration Status:\s*SUCCESS' -and
                    $last -notmatch '(?im)^\s*(?:Warning:\s*)?(?:ORA|DGM)-\d+') {
                return $last
            }
        } catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Seconds 3
    }
    throw "Data Guard broker did not reach SUCCESS within $TimeoutSeconds seconds. Last output: $last"
}

function Get-DatabaseIdentity {
    param([string]$ContainerName)
    $result = Invoke-ContainerSql -ContainerName $ContainerName -Sql @"
set heading off feedback off pagesize 0
select db_unique_name || '|' || database_role || '|' || open_mode from v`$database;
"@
    $identity = @($result -split "`r?`n" | Where-Object { $_ -match '^[A-Za-z0-9]+\|' } | Select-Object -Last 1)
    if ($identity.Count -ne 1) {
        throw "Could not parse Oracle database identity from $ContainerName. Output: $result"
    }
    return $identity[0].Trim()
}

function Wait-DatabaseRole {
    param(
        [string]$ContainerName,
        [string]$ExpectedRole,
        [string]$ExpectedOpenMode,
        [int]$TimeoutSeconds = 180
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $last = ""
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $last = Get-DatabaseIdentity -ContainerName $ContainerName
            $parts = $last.Split('|')
            if ($parts[1] -eq $ExpectedRole -and $parts[2] -eq $ExpectedOpenMode) {
                return $last
            }
        } catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "$ContainerName did not reach role '$ExpectedRole' and open mode '$ExpectedOpenMode'. Last identity: $last"
}

function Start-OracleContainer {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port,
        [string]$Sid,
        [switch]$Standby
    )
    Remove-OwnedContainer -Name $Name
    $arguments = @(
        "run", "-d",
        "--name", $Name,
        "--hostname", $HostName,
        "--network", $NetworkName,
        "--network-alias", $HostName,
        "--label", $ownerLabel,
        "--memory", "5g",
        "--cpus", "3",
        "--shm-size", "1g",
        "-p", "127.0.0.1:${Port}:1521",
        "-e", "ORACLE_SID=$Sid",
        "-e", "ORACLE_PDB=$serviceName",
        "-e", "ORACLE_PWD=$oracleSysPassword",
        "-e", "ORACLE_EDITION=enterprise",
        "-e", "INIT_SGA_SIZE=1536",
        "-e", "INIT_PGA_SIZE=512",
        "-e", "INIT_CPU_COUNT=2",
        "-e", "INIT_PROCESSES=300"
    )
    if ($Standby) {
        $arguments += @("-e", "STANDBY_DB=true", "-e", "PRIMARY_DB_CONN_STR=dgprim:1521/DGPRIM")
    } else {
        $arguments += @("-e", "ENABLE_ARCHIVELOG=true")
    }
    $arguments += $OracleImage
    Invoke-Docker $arguments | Out-Null
    $createdContainers.Add($Name)
}

function Write-NetworkConfiguration {
    $tns = @"
DGPRIM =
  (DESCRIPTION =
    (ADDRESS = (PROTOCOL = TCP)(HOST = dgprim)(PORT = 1521))
    (CONNECT_DATA = (SERVER = DEDICATED)(SERVICE_NAME = DGPRIM))
  )
DGSTBY =
  (DESCRIPTION =
    (ADDRESS = (PROTOCOL = TCP)(HOST = dgstby)(PORT = 1521))
    (CONNECT_DATA = (SERVER = DEDICATED)(SERVICE_NAME = DGSTBY))
  )
"@
    $primaryListener = @"
LISTENER =
  (DESCRIPTION_LIST =
    (DESCRIPTION =
      (ADDRESS = (PROTOCOL = IPC)(KEY = EXTPROC1))
      (ADDRESS = (PROTOCOL = TCP)(HOST = 0.0.0.0)(PORT = 1521))
    )
  )
SID_LIST_LISTENER =
  (SID_LIST =
    (SID_DESC =
      (GLOBAL_DBNAME = DGPRIM_DGMGRL)
      (ORACLE_HOME = /opt/oracle/product/19c/dbhome_1)
      (SID_NAME = DGPRIM)
    )
  )
DEDICATED_THROUGH_BROKER_LISTENER=ON
DIAG_ADR_ENABLED=OFF
"@
    $standbyListener = $primaryListener.Replace("DGPRIM_DGMGRL", "DGSTBY_DGMGRL").Replace("DGPRIM)", "DGSTBY)")

    $tns | & docker exec -i $PrimaryContainerName bash -c `
        'cat > /opt/oracle/product/19c/dbhome_1/network/admin/tnsnames.ora'
    if ($LASTEXITCODE -ne 0) { throw "Failed to write primary tnsnames.ora" }
    $tns | & docker exec -i $StandbyContainerName bash -c `
        'cat > /opt/oracle/product/19c/dbhome_1/network/admin/tnsnames.ora'
    if ($LASTEXITCODE -ne 0) { throw "Failed to write standby tnsnames.ora" }
    $primaryListener | & docker exec -i $PrimaryContainerName bash -c `
        'cat > /opt/oracle/product/19c/dbhome_1/network/admin/listener.ora'
    if ($LASTEXITCODE -ne 0) { throw "Failed to write primary listener.ora" }
    $standbyListener | & docker exec -i $StandbyContainerName bash -c `
        'cat > /opt/oracle/product/19c/dbhome_1/network/admin/listener.ora'
    if ($LASTEXITCODE -ne 0) { throw "Failed to write standby listener.ora" }

    Invoke-Docker @("exec", $PrimaryContainerName, "lsnrctl", "reload") | Out-Null
    Invoke-Docker @("exec", $StandbyContainerName, "lsnrctl", "reload") | Out-Null
    Invoke-Docker @("exec", $PrimaryContainerName, "tnsping", "DGSTBY") | Out-Null
    Invoke-Docker @("exec", $StandbyContainerName, "tnsping", "DGPRIM") | Out-Null
}

function Configure-NewTopology {
    Write-Host "Starting Oracle Data Guard primary..."
    Start-OracleContainer -Name $PrimaryContainerName -HostName "dgprim" -Port $PrimaryPort -Sid "DGPRIM"
    Wait-ContainerHealthy -Name $PrimaryContainerName -TimeoutSeconds $DatabaseStartupTimeoutSeconds

    Invoke-Docker @("exec", $PrimaryContainerName, "bash", "-lc", "mkdir -p /opt/oracle/oradata/DGPRIM/fast_recovery_area") | Out-Null
    Invoke-ContainerSql -ContainerName $PrimaryContainerName -Sql @"
alter database force logging;
alter system set standby_file_management='AUTO' scope=both;
alter system set db_recovery_file_dest_size=8G scope=both;
alter system set db_recovery_file_dest='/opt/oracle/oradata/DGPRIM/fast_recovery_area' scope=both;
alter system set dg_broker_start=true scope=both;
alter database add standby logfile thread 1 group 11 ('/opt/oracle/oradata/DGPRIM/standby_redo11.log') size 200M;
alter database add standby logfile thread 1 group 12 ('/opt/oracle/oradata/DGPRIM/standby_redo12.log') size 200M;
alter database add standby logfile thread 1 group 13 ('/opt/oracle/oradata/DGPRIM/standby_redo13.log') size 200M;
alter database add standby logfile thread 1 group 14 ('/opt/oracle/oradata/DGPRIM/standby_redo14.log') size 200M;
alter database flashback on;
alter session set container=$serviceName;
create user $applicationUser identified by "$applicationPassword";
grant create session, create table, create sequence, create view, create procedure to $applicationUser;
alter user $applicationUser quota unlimited on users;
create table $applicationUser.cachedb_dg_evidence (
    id number(19,0) primary key,
    marker varchar2(100 char) not null,
    created_at timestamp(6) not null
);
insert into $applicationUser.cachedb_dg_evidence(id, marker, created_at)
values (1, 'before-standby-duplicate', systimestamp);
commit;
alter session set container=CDB`$ROOT;
"@ | Out-Null

    Write-Host "Starting Oracle physical standby duplicate..."
    Start-OracleContainer -Name $StandbyContainerName -HostName "dgstby" -Port $StandbyPort `
        -Sid "DGSTBY" -Standby
    Wait-OracleDatabaseCreation -Name $StandbyContainerName -Sid "DGSTBY" `
        -TimeoutSeconds $DatabaseStartupTimeoutSeconds
    # DBCA may leave the standby open read-only. Broker owns apply only after DBCA has fully exited.
    Invoke-ContainerSql -ContainerName $StandbyContainerName -Sql @"
whenever sqlerror continue
shutdown immediate;
whenever sqlerror exit sql.sqlcode
startup mount;
"@ | Out-Null
    Wait-DatabaseRole -ContainerName $StandbyContainerName -ExpectedRole "PHYSICAL STANDBY" `
        -ExpectedOpenMode "MOUNTED" -TimeoutSeconds $DatabaseStartupTimeoutSeconds | Out-Null
    Write-NetworkConfiguration

    Invoke-Docker @("exec", $StandbyContainerName, "bash", "-lc", "mkdir -p /opt/oracle/oradata/DGSTBY/archive_logs /opt/oracle/oradata/DGSTBY/fast_recovery_area") | Out-Null
    Invoke-ContainerSql -ContainerName $PrimaryContainerName -Sql @"
alter system set log_archive_config='DG_CONFIG=(DGPRIM,DGSTBY)' scope=both;
alter system set log_archive_dest_1='LOCATION=/opt/oracle/oradata/DGPRIM/archive_logs VALID_FOR=(ALL_LOGFILES,ALL_ROLES) DB_UNIQUE_NAME=DGPRIM' scope=both;
alter system set log_archive_dest_2='SERVICE=DGSTBY ASYNC NOAFFIRM VALID_FOR=(ONLINE_LOGFILES,PRIMARY_ROLE) DB_UNIQUE_NAME=DGSTBY' scope=both;
alter system set log_archive_dest_state_2=enable scope=both;
alter system set fal_server='DGSTBY' scope=both;
alter system set fal_client='DGPRIM' scope=both;
alter system archive log current;
"@ | Out-Null
    Invoke-ContainerSql -ContainerName $StandbyContainerName -Sql @"
alter system set log_archive_config='DG_CONFIG=(DGPRIM,DGSTBY)' scope=both;
alter system set log_archive_dest_1='LOCATION=/opt/oracle/oradata/DGSTBY/archive_logs VALID_FOR=(ALL_LOGFILES,ALL_ROLES) DB_UNIQUE_NAME=DGSTBY' scope=both;
alter system set log_archive_dest_2='SERVICE=DGPRIM ASYNC NOAFFIRM VALID_FOR=(ONLINE_LOGFILES,PRIMARY_ROLE) DB_UNIQUE_NAME=DGPRIM' scope=both;
alter system set log_archive_dest_state_2=enable scope=both;
alter system set fal_server='DGPRIM' scope=both;
alter system set fal_client='DGSTBY' scope=both;
alter system set standby_file_management='AUTO' scope=both;
alter system set db_recovery_file_dest_size=8G scope=both;
alter system set db_recovery_file_dest='/opt/oracle/oradata/DGSTBY/fast_recovery_area' scope=both;
"@ | Out-Null
    Invoke-ContainerSql -ContainerName $PrimaryContainerName -Sql @"
alter system set log_archive_dest_state_2=defer scope=both;
alter system set log_archive_dest_state_2=enable scope=both;
alter system archive log current;
alter system archive log current;
"@ | Out-Null

    Invoke-Dgmgrl -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" -Commands @(
        "create configuration CACHEDBDG as primary database is DGPRIM connect identifier is DGPRIM",
        "edit database DGPRIM set property StaticConnectIdentifier='(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=dgprim)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=DGPRIM_DGMGRL)(INSTANCE_NAME=DGPRIM)(SERVER=DEDICATED)))'"
    ) | Out-Null
    Invoke-ContainerSql -ContainerName $StandbyContainerName -Sql @"
alter system set log_archive_dest_state_2=defer scope=both;
alter system set log_archive_dest_2='' scope=both;
"@ | Out-Null
    Invoke-Dgmgrl -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" -Commands @(
        "add database DGSTBY as connect identifier is DGSTBY maintained as physical",
        "edit database DGSTBY set property StaticConnectIdentifier='(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=dgstby)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=DGSTBY_DGMGRL)(INSTANCE_NAME=DGSTBY)(SERVER=DEDICATED)))'",
        "enable configuration"
    ) -AllowStatusWarnings | Out-Null
    Wait-BrokerSuccess -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" | Out-Null

    Invoke-Dgmgrl -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" `
        -Commands @("edit database DGSTBY set state='APPLY-OFF'") | Out-Null
    Invoke-ContainerSql -ContainerName $StandbyContainerName -Sql "alter database flashback on;" | Out-Null
    Invoke-Dgmgrl -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" `
        -Commands @("edit database DGSTBY set state='APPLY-ON'") | Out-Null
    Wait-BrokerSuccess -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" | Out-Null
}

function New-HaproxyConfig {
    param([ValidateSet("primary", "standby")][string]$Backend)
    $target = if ($Backend -eq "primary") { $PrimaryContainerName } else { $StandbyContainerName }
    $configPath = Join-Path $workDir "haproxy-$Backend.cfg"
    @"
global
    log stdout format raw local0
    maxconn 128

defaults
    log global
    mode tcp
    timeout connect 5s
    timeout client 30s
    timeout server 30s

frontend oracle_service
    bind *:1521
    default_backend oracle_primary

backend oracle_primary
    server $Backend ${target}:1521 check inter 1000 rise 1 fall 1
"@ | Set-Content -LiteralPath $configPath -Encoding ASCII
    return $configPath
}

function Start-Proxy {
    param([ValidateSet("primary", "standby")][string]$Backend)
    Remove-OwnedContainer -Name $proxyContainerName
    $configPath = New-HaproxyConfig -Backend $Backend
    Invoke-Docker @(
        "run", "-d",
        "--name", $proxyContainerName,
        "--network", $NetworkName,
        "--label", $ownerLabel,
        "-p", "127.0.0.1:${ListenerPort}:1521",
        "-v", "${configPath}:/usr/local/etc/haproxy/haproxy.cfg:ro",
        $ProxyImage
    ) | Out-Null
    $script:proxyCreated = $true
    Wait-TcpPort -Name "Oracle stable service proxy ($Backend)" -Port $ListenerPort -TimeoutSeconds 60
}

function Wait-File {
    param(
        [string]$Path,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $Path) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for coordination file: $Path"
}

function Start-ServiceFailoverTest {
    param(
        [string]$Phase,
        [long]$MarkerId,
        [string]$ReadyFile,
        [string]$SwitchFile
    )
    $maven = Effective-MavenExecutable
    $logPath = Join-Path $reportsDir "service-failover-$Phase.log"
    $arguments = @(
        "-q",
        "-pl", "cachedb-storage-oracle",
        "-am", "test",
        "-Dtest=OracleServiceNameFailoverIntegrationTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dcachedb.it.oracle.serviceFailover.required=true",
        "-Dcachedb.it.oracle.serviceFailover.readyFile=$ReadyFile",
        "-Dcachedb.it.oracle.serviceFailover.switchFile=$SwitchFile",
        "-Dcachedb.it.oracle.serviceFailover.timeoutSeconds=$TransitionTimeoutSeconds",
        "-Dcachedb.it.oracle.serviceFailover.markerId=$MarkerId",
        "-Dcachedb.it.oracle.url=$serviceFailoverUrl",
        "-Dcachedb.it.oracle.user=$applicationUser",
        "-Dcachedb.it.oracle.password=$applicationPassword"
    )
    return Start-Job -Name "cachedb-oracle-service-failover-$Phase-$RunId" -ScriptBlock {
        param($Root, $Maven, $MavenArguments, $LogPath)
        Set-Location $Root
        & $Maven @MavenArguments *> $LogPath
        if ($global:LASTEXITCODE -ne 0) {
            throw "Maven service failover test failed with exit code $global:LASTEXITCODE. See $LogPath"
        }
    } -ArgumentList $repoRoot, $maven, $arguments, $logPath
}

function Complete-ServiceFailoverTest {
    param(
        [System.Management.Automation.Job]$Job,
        [string]$Phase
    )
    $completed = Wait-Job -Job $Job -Timeout ($TransitionTimeoutSeconds + 180)
    if ($null -eq $completed) {
        Stop-Job -Job $Job -ErrorAction SilentlyContinue | Out-Null
        throw "Oracle service failover test timed out."
    }
    Receive-Job -Job $Job -ErrorAction Stop | Out-Host
    Remove-Job -Job $Job -Force | Out-Null

    $surefireDirectory = Join-Path $repoRoot "cachedb-storage-oracle/target/surefire-reports"
    foreach ($reportName in @(
        "TEST-com.reactor.cachedb.oracle.OracleServiceNameFailoverIntegrationTest.xml",
        "com.reactor.cachedb.oracle.OracleServiceNameFailoverIntegrationTest.txt"
    )) {
        $source = Join-Path $surefireDirectory $reportName
        if (-not (Test-Path -LiteralPath $source)) {
            throw "Oracle service failover Surefire report was not generated: $source"
        }
        $extension = [System.IO.Path]::GetExtension($reportName)
        Copy-Item -LiteralPath $source `
            -Destination (Join-Path $reportsDir "service-failover-$Phase-surefire$extension") -Force
    }
}

function Invoke-ProviderEvidence {
    param([string]$Phase)
    if ($SkipProviderEvidence) {
        return
    }
    & (Join-Path $PSScriptRoot "run-oracle-provider-evidence.ps1") `
        -MavenExecutable (Effective-MavenExecutable) `
        -OracleUrl $listenerUrl `
        -OracleUser $applicationUser `
        -OraclePassword $applicationPassword `
        -ReportsDir (Join-Path $reportsDir "provider-$Phase") `
        -ExternalDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Oracle provider evidence failed after $Phase."
    }
}

function Invoke-PlannedSwitchover {
    $readyFile = Join-Path $workDir "planned-ready.txt"
    $switchFile = Join-Path $workDir "planned-switched.txt"
    $job = Start-ServiceFailoverTest -Phase "planned" -MarkerId 10001 `
        -ReadyFile $readyFile -SwitchFile $switchFile
    try {
        Wait-File -Path $readyFile -TimeoutSeconds $TransitionTimeoutSeconds
        $script:initialPrimaryIdentity = (Get-Content -LiteralPath $readyFile -Raw).Trim()
        $transitionStarted = [DateTimeOffset]::UtcNow
        Invoke-Dgmgrl -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" `
            -Commands @("switchover to DGSTBY") |
            Set-Content -LiteralPath (Join-Path $reportsDir "broker-planned-switchover.txt") -Encoding UTF8
        $script:plannedPrimaryIdentity = Wait-DatabaseRole -ContainerName $StandbyContainerName `
            -ExpectedRole "PRIMARY" -ExpectedOpenMode "READ WRITE" -TimeoutSeconds $TransitionTimeoutSeconds
        Set-Content -LiteralPath $switchFile -Value ([DateTimeOffset]::UtcNow.ToString("O")) -Encoding ASCII
        Complete-ServiceFailoverTest -Job $job -Phase "planned"
        $script:plannedApplicationRecoveryMs = [long]([DateTimeOffset]::UtcNow - $transitionStarted).TotalMilliseconds
        $script:plannedServiceRecoveryPassed = $true
        Start-Proxy -Backend "standby"
        $script:plannedEndpointReadyMs = [long]([DateTimeOffset]::UtcNow - $transitionStarted).TotalMilliseconds
    } catch {
        Stop-Job -Job $job -ErrorAction SilentlyContinue | Out-Null
        Receive-Job -Job $job -ErrorAction SilentlyContinue | Out-Host
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue | Out-Null
        throw
    }
    Wait-BrokerSuccess -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" |
        Set-Content -LiteralPath (Join-Path $reportsDir "broker-after-planned-switchover.txt") -Encoding UTF8
    Invoke-ProviderEvidence -Phase "planned-switchover"
    if (-not $SkipProviderEvidence) {
        $script:plannedProviderEvidencePassed = $true
    }
}

function Invoke-Switchback {
    Invoke-Dgmgrl -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" `
        -Commands @("switchover to DGPRIM") |
        Set-Content -LiteralPath (Join-Path $reportsDir "broker-switchback.txt") -Encoding UTF8
    Wait-DatabaseRole -ContainerName $PrimaryContainerName -ExpectedRole "PRIMARY" `
        -ExpectedOpenMode "READ WRITE" -TimeoutSeconds $TransitionTimeoutSeconds | Out-Null
    Start-Proxy -Backend "primary"
    Wait-BrokerSuccess -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" |
        Set-Content -LiteralPath (Join-Path $reportsDir "broker-after-switchback.txt") -Encoding UTF8
    $script:switchbackPassed = $true
}

function Invoke-UnplannedFailover {
    $readyFile = Join-Path $workDir "unplanned-ready.txt"
    $switchFile = Join-Path $workDir "unplanned-switched.txt"
    $job = Start-ServiceFailoverTest -Phase "unplanned" -MarkerId 20001 `
        -ReadyFile $readyFile -SwitchFile $switchFile
    try {
        Wait-File -Path $readyFile -TimeoutSeconds $TransitionTimeoutSeconds
        $transitionStarted = [DateTimeOffset]::UtcNow
        Invoke-Docker @("kill", $PrimaryContainerName) | Out-Null
        Invoke-Dgmgrl -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" `
            -Commands @("failover to DGSTBY immediate") |
            Set-Content -LiteralPath (Join-Path $reportsDir "broker-unplanned-failover.txt") -Encoding UTF8
        $script:failoverPrimaryIdentity = Wait-DatabaseRole -ContainerName $StandbyContainerName `
            -ExpectedRole "PRIMARY" -ExpectedOpenMode "READ WRITE" -TimeoutSeconds $TransitionTimeoutSeconds
        Set-Content -LiteralPath $switchFile -Value ([DateTimeOffset]::UtcNow.ToString("O")) -Encoding ASCII
        Complete-ServiceFailoverTest -Job $job -Phase "unplanned"
        $script:unplannedApplicationRecoveryMs = [long]([DateTimeOffset]::UtcNow - $transitionStarted).TotalMilliseconds
        $script:unplannedServiceRecoveryPassed = $true
        Start-Proxy -Backend "standby"
        $script:unplannedEndpointReadyMs = [long]([DateTimeOffset]::UtcNow - $transitionStarted).TotalMilliseconds
    } catch {
        Stop-Job -Job $job -ErrorAction SilentlyContinue | Out-Null
        Receive-Job -Job $job -ErrorAction SilentlyContinue | Out-Host
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue | Out-Null
        throw
    }
    Invoke-Dgmgrl -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" -Commands @(
        "show configuration verbose",
        "show database verbose DGSTBY"
    ) -AllowStatusWarnings |
        Set-Content -LiteralPath (Join-Path $reportsDir "broker-after-unplanned-failover.txt") -Encoding UTF8
    Invoke-ProviderEvidence -Phase "unplanned-failover"
    if (-not $SkipProviderEvidence) {
        $script:unplannedProviderEvidencePassed = $true
    }
}

function Wait-ReinstatedStandbyReady {
    param([int]$TimeoutSeconds = 300)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $last = ""
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $last = Invoke-Dgmgrl -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" `
                -Commands @("validate database verbose DGPRIM") -AllowStatusWarnings
            $readyForSwitchover = $last -match 'Ready for Switchover:\s+Yes'
            $noGap = $last -match 'Gap Status:\s+No Gap'
            $applyCaughtUp = $last -match 'Apply Lag:\s+0 seconds'
            $transportCaughtUp = $last -match 'Transport Lag:\s+0 seconds'
            $transportHealthy = $last -match 'Transport Status:\s+Success'
            if ($readyForSwitchover -and $noGap -and $applyCaughtUp -and
                    $transportCaughtUp -and $transportHealthy) {
                return $last
            }
        } catch {
            $last = $_.Exception.Message
        }
        Start-Sleep -Seconds 5
    }
    throw "Reinstated standby did not reach zero-lag, no-gap readiness. Last output: $last"
}

function Invoke-PostFailoverRedundancyRestore {
    $restoreStarted = [DateTimeOffset]::UtcNow
    if ((Get-ContainerLabel -Name $PrimaryContainerName) -ne "oracle-dg-evidence") {
        throw "Former primary lacks the required ownership label: $PrimaryContainerName"
    }
    Invoke-Docker @("start", $PrimaryContainerName) | Out-Null

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TransitionTimeoutSeconds)
    $beforeReinstate = ""
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $beforeReinstate = Get-DatabaseIdentity -ContainerName $PrimaryContainerName
            if ($beforeReinstate -match '^DGPRIM\|PRIMARY\|') {
                break
            }
        } catch {
            $beforeReinstate = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    }
    if ($beforeReinstate -notmatch '^DGPRIM\|PRIMARY\|') {
        throw "Former primary did not become mountable for reinstatement. Last identity: $beforeReinstate"
    }

    Invoke-ContainerSql -ContainerName $PrimaryContainerName -Sql @"
whenever sqlerror continue
shutdown immediate;
whenever sqlerror exit sql.sqlcode
startup mount;
"@ | Out-Null
    Wait-DatabaseRole -ContainerName $PrimaryContainerName -ExpectedRole "PRIMARY" `
        -ExpectedOpenMode "MOUNTED" -TimeoutSeconds $TransitionTimeoutSeconds | Out-Null

    Invoke-Dgmgrl -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" `
        -Commands @("reinstate database DGPRIM") |
        Set-Content -LiteralPath (Join-Path $reportsDir "broker-reinstate-former-primary.txt") -Encoding UTF8
    Wait-BrokerSuccess -ContainerName $StandbyContainerName -ConnectAlias "DGSTBY" | Out-Null
    Invoke-ContainerSql -ContainerName $StandbyContainerName -Sql @"
alter system archive log current;
alter system archive log current;
"@ | Out-Null
    Wait-ReinstatedStandbyReady -TimeoutSeconds $TransitionTimeoutSeconds |
        Set-Content -LiteralPath (Join-Path $reportsDir "broker-after-reinstate.txt") -Encoding UTF8

    $script:finalPrimaryIdentity = Wait-DatabaseRole -ContainerName $StandbyContainerName `
        -ExpectedRole "PRIMARY" -ExpectedOpenMode "READ WRITE" -TimeoutSeconds $TransitionTimeoutSeconds
    $script:finalStandbyIdentity = Wait-DatabaseRole -ContainerName $PrimaryContainerName `
        -ExpectedRole "PHYSICAL STANDBY" -ExpectedOpenMode "MOUNTED" -TimeoutSeconds $TransitionTimeoutSeconds
    $script:redundancyRestoreMs = [long]([DateTimeOffset]::UtcNow - $restoreStarted).TotalMilliseconds
    $script:redundancyRestoredPassed = $true
}

function Write-EvidenceSummary {
    param(
        [ValidateSet("passed", "failed")][string]$Status,
        [string]$Failure = ""
    )
    $finishedAt = [DateTimeOffset]::UtcNow
    $summaryPath = Join-Path $reportsDir "local-oracle-dataguard-evidence.md"
    $jsonPath = Join-Path $reportsDir "local-oracle-dataguard-evidence.json"
    @(
        "# Local Oracle Data Guard and Service Failover Evidence",
        "",
        "- Status: ``$Status``",
        "- Oracle image: ``$OracleImage``",
        "- Oracle image ID: ``$oracleImageId``",
        "- Proxy image ID: ``$proxyImageId``",
        "- Docker server: ``$dockerServerVersion``; CPUs: ``$dockerCpuCount``; memory: ``$dockerMemoryBytes bytes``",
        "- Git commit: ``$gitCommit``; dirty worktree: ``$gitWorktreeDirty``",
        "- Provider evidence JDBC URL: ``$listenerUrl``",
        "- Service failover JDBC descriptor: ``$serviceFailoverUrl``",
        "- Physical standby and real-time apply: ``$(if ($physicalDataGuardReady) { 'passed' } else { 'not completed' })``",
        "- Data Guard Broker pre-transition status: ``$(if ($brokerPreTransitionReady) { 'SUCCESS' } else { 'not completed' })``",
        "- Planned switchover: ``$(if ($plannedPrimaryIdentity) { 'passed' } else { 'not completed' })``",
        "- Planned switchover direct-descriptor application recovery: ``$plannedApplicationRecoveryMs ms``",
        "- Planned switchover provider proxy ready: ``$plannedEndpointReadyMs ms``",
        "- Unplanned primary-loss failover: ``$(if ($SkipUnplannedFailover) { 'skipped' } elseif ($failoverPrimaryIdentity) { 'passed' } else { 'not completed' })``",
        "- Unplanned failover direct-descriptor application recovery: ``$unplannedApplicationRecoveryMs ms``",
        "- Unplanned failover provider proxy ready: ``$unplannedEndpointReadyMs ms``",
        "- Post-failover standby reinstatement and no-gap readiness: ``$(if ($SkipUnplannedFailover) { 'skipped' } elseif ($redundancyRestoredPassed) { 'passed' } else { 'not completed' })``",
        "- Post-failover redundancy restore: ``$redundancyRestoreMs ms``",
        "- Broker switchback: ``$(if ($switchbackPassed) { 'passed' } else { 'not completed' })``",
        "- Hikari stale-connection rejection and pool recovery: ``$(if ($plannedServiceRecoveryPassed -and ($SkipUnplannedFailover -or $unplannedServiceRecoveryPassed)) { 'passed' } else { 'not completed' })``",
        "- Pre-transition marker visible on new primary: ``$(if ($plannedServiceRecoveryPassed -and ($SkipUnplannedFailover -or $unplannedServiceRecoveryPassed)) { 'passed' } else { 'not completed' })``",
        "- CacheDB provider evidence after role transition: ``$(if ($SkipProviderEvidence) { 'skipped' } elseif ($plannedProviderEvidencePassed -and ($SkipUnplannedFailover -or $unplannedProviderEvidencePassed)) { 'passed' } else { 'not completed' })``",
        "- Initial service identity: ``$initialPrimaryIdentity``",
        "- Planned new primary: ``$plannedPrimaryIdentity``",
        "- Failover new primary: ``$failoverPrimaryIdentity``",
        "- Final primary: ``$finalPrimaryIdentity``",
        "- Final standby: ``$finalStandbyIdentity``",
        "- Started at: ``$($startedAt.ToString('O'))``",
        "- Finished at: ``$($finishedAt.ToString('O'))``",
        $(if ($Failure) { "- Failure: ``$Failure``" } else { "" }),
        "",
        "Scope: this lane uses two Oracle 19c Enterprise single-instance containers and real physical Data Guard role transitions. The Hikari test connects through an Oracle JDBC multi-address descriptor with one stable service name; HAProxy is used separately for the full provider suite after Broker confirms the role. It validates CacheDB, Oracle JDBC Thin, HikariCP, redo apply, broker switchover/failover, and connect-time address failover. It is not Oracle RAC, Clusterware, SCAN, FAN/ONS, Fast Connection Failover, Application Continuity, quorum, zero-RPO, or production network certification."
    ) | Where-Object { $_ -ne "" -or $Failure -eq "" } |
        Set-Content -LiteralPath $summaryPath -Encoding UTF8

    [ordered]@{
        status = $Status
        scope = "local-docker-oracle-physical-dataguard"
        oracleImage = $OracleImage
        oracleImageId = $oracleImageId
        proxyImageId = $proxyImageId
        dockerServerVersion = $dockerServerVersion
        dockerMemoryBytes = $dockerMemoryBytes
        dockerCpuCount = $dockerCpuCount
        gitCommit = $gitCommit
        gitWorktreeDirty = $gitWorktreeDirty
        listenerUrl = $listenerUrl
        serviceFailoverUrl = $serviceFailoverUrl
        initialPrimaryIdentity = $initialPrimaryIdentity
        plannedPrimaryIdentity = $plannedPrimaryIdentity
        failoverPrimaryIdentity = $failoverPrimaryIdentity
        plannedEndpointReadyMs = $plannedEndpointReadyMs
        plannedApplicationRecoveryMs = $plannedApplicationRecoveryMs
        unplannedEndpointReadyMs = $unplannedEndpointReadyMs
        unplannedApplicationRecoveryMs = $unplannedApplicationRecoveryMs
        redundancyRestoreMs = $redundancyRestoreMs
        providerEvidence = if ($SkipProviderEvidence) { "skipped" } else { $Status }
        physicalDataGuardReady = $physicalDataGuardReady
        brokerPreTransitionReady = $brokerPreTransitionReady
        plannedServiceRecoveryPassed = $plannedServiceRecoveryPassed
        plannedProviderEvidencePassed = $plannedProviderEvidencePassed
        switchbackPassed = $switchbackPassed
        unplannedServiceRecoveryPassed = $unplannedServiceRecoveryPassed
        unplannedProviderEvidencePassed = $unplannedProviderEvidencePassed
        redundancyRestoredPassed = $redundancyRestoredPassed
        finalPrimaryIdentity = $finalPrimaryIdentity
        finalStandbyIdentity = $finalStandbyIdentity
        serviceRelocationMode = "oracle-jdbc-multi-address-service-descriptor; provider-suite-via-haproxy-after-broker-role-confirmation"
        racCertified = $false
        zeroRpoCertified = $false
        failure = $Failure
        startedAt = $startedAt.ToString("O")
        finishedAt = $finishedAt.ToString("O")
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
}

$failureMessage = ""
try {
    Assert-DockerResources
    Assert-LocalImage -Image $OracleImage
    Assert-LocalImage -Image $ProxyImage
    $dockerServerVersion = ([string](Invoke-Docker @("version", "--format", "{{.Server.Version}}"))).Trim()
    $dockerMemoryBytes = [long](([string](Invoke-Docker @("info", "--format", "{{.MemTotal}}"))).Trim())
    $dockerCpuCount = [int](([string](Invoke-Docker @("info", "--format", "{{.NCPU}}"))).Trim())
    $oracleImageId = ([string](Invoke-Docker @("image", "inspect", "--format", "{{.Id}}", $OracleImage))).Trim()
    $proxyImageId = ([string](Invoke-Docker @("image", "inspect", "--format", "{{.Id}}", $ProxyImage))).Trim()
    $gitCommit = ([string](& git rev-parse HEAD 2>$null)).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($gitCommit)) {
        throw "Oracle Data Guard evidence must run from a Git checkout."
    }
    $gitWorktreeDirty = -not [string]::IsNullOrWhiteSpace(((& git status --porcelain) | Out-String))
    if (Test-Path -LiteralPath $reportsDir) {
        Remove-Item -LiteralPath $reportsDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $workDir -Force | Out-Null

    if ($UseExistingTopology) {
        foreach ($name in @($PrimaryContainerName, $StandbyContainerName)) {
            $state = docker inspect --format "{{.State.Status}}" $name 2>$null
            if ($LASTEXITCODE -ne 0 -or ([string]$state).Trim() -ne "running") {
                throw "Existing Oracle Data Guard container is not running: $name"
            }
            if ((Get-ContainerLabel -Name $name) -ne "oracle-dg-evidence") {
                throw "Existing Oracle Data Guard container lacks the required ownership label: $name"
            }
        }
        $network = docker network ls --filter "name=^$NetworkName$" --format "{{.Name}}"
        if ($network -notcontains $NetworkName) {
            throw "Existing Oracle Data Guard network was not found: $NetworkName"
        }
        $networkOwner = docker network inspect --format "{{index .Labels `"com.reactor.cachedb.owner`"}}" $NetworkName 2>$null
        if ($LASTEXITCODE -ne 0 -or ([string]$networkOwner).Trim() -ne "oracle-dg-evidence") {
            throw "Existing Oracle Data Guard network lacks the required ownership label: $NetworkName"
        }
        Wait-BrokerSuccess -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" | Out-Null
    } else {
        foreach ($port in @($ListenerPort, $PrimaryPort, $StandbyPort)) {
            if (Test-TcpPort -Port $port) {
                throw "Required host port is already in use: $port"
            }
        }
        Remove-OwnedContainer -Name $proxyContainerName
        Remove-OwnedContainer -Name $PrimaryContainerName
        Remove-OwnedContainer -Name $StandbyContainerName
        Remove-OwnedNetwork -Name $NetworkName
        Invoke-Docker @("network", "create", "--label", $ownerLabel, $NetworkName) | Out-Null
        $networkCreated = $true
        Configure-NewTopology
    }

    $initialPrimary = Wait-DatabaseRole -ContainerName $PrimaryContainerName -ExpectedRole "PRIMARY" `
        -ExpectedOpenMode "READ WRITE" -TimeoutSeconds $TransitionTimeoutSeconds
    $initialStandby = Wait-DatabaseRole -ContainerName $StandbyContainerName `
        -ExpectedRole "PHYSICAL STANDBY" -ExpectedOpenMode "MOUNTED" -TimeoutSeconds $TransitionTimeoutSeconds
    $initialPrimary | Set-Content -LiteralPath (Join-Path $reportsDir "initial-primary-identity.txt") -Encoding ASCII
    $initialStandby | Set-Content -LiteralPath (Join-Path $reportsDir "initial-standby-identity.txt") -Encoding ASCII
    Invoke-Dgmgrl -ContainerName $PrimaryContainerName -ConnectAlias "DGPRIM" -Commands @(
        "validate database verbose DGPRIM",
        "validate database verbose DGSTBY"
    ) | Set-Content -LiteralPath (Join-Path $reportsDir "broker-validation-before-transition.txt") -Encoding UTF8
    $physicalDataGuardReady = $true
    $brokerPreTransitionReady = $true

    Start-Proxy -Backend "primary"
    Invoke-PlannedSwitchover
    Invoke-Switchback
    if (-not $SkipUnplannedFailover) {
        Invoke-UnplannedFailover
        Invoke-PostFailoverRedundancyRestore
    }

    Write-EvidenceSummary -Status "passed"
    Write-Host "Local Oracle Data Guard evidence passed: $reportsDir"
} catch {
    $failureMessage = $_.Exception.Message -replace "`r?`n", " "
    if (Test-Path -LiteralPath $reportsDir) {
        Write-EvidenceSummary -Status "failed" -Failure $failureMessage
    }
    throw
} finally {
    if (-not $KeepContainers) {
        if ($proxyCreated) {
            Remove-OwnedContainer -Name $proxyContainerName
        }
        if (-not $UseExistingTopology) {
            foreach ($container in @($createdContainers)) {
                Remove-OwnedContainer -Name $container
            }
            if ($networkCreated) {
                Remove-OwnedNetwork -Name $NetworkName
            }
        }
    } else {
        Write-Host "Keeping Oracle Data Guard containers: $PrimaryContainerName, $StandbyContainerName"
        Write-Host "Keeping Oracle Data Guard network: $NetworkName"
    }
}
