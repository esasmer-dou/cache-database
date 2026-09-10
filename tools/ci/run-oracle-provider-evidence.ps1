param(
    [string]$MavenExecutable = "",
    [string]$OracleUrl = "jdbc:oracle:thin:@//127.0.0.1:15211/FREEPDB1",
    [string]$OracleUser = "cachedb",
    [string]$OraclePassword = "CacheDbOracle123",
    [string]$ReportsDir = "",
    [string]$ContainerName = "cachedb-oracle-provider-evidence",
    [string]$OracleImage = "gvenzl/oracle-free:23.9-slim@sha256:202889b03adeb66f0b87713f5cf01a06af2cfeb0c25e9ba31a74f5cd24172e31",
    [switch]$UseExistingContainer,
    [switch]$ExternalDatabase,
    [switch]$RestartOracleContainer
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ownerLabelName = "com.reactor.cachedb.owner"
$ownerLabelValue = "oracle-provider-evidence"
$ownerLabel = "$ownerLabelName=$ownerLabelValue"
if ([string]::IsNullOrWhiteSpace($ReportsDir)) {
    $reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-oracle-provider-reports"
} elseif ([System.IO.Path]::IsPathRooted($ReportsDir)) {
    $reportsDir = $ReportsDir
} else {
    $reportsDir = Join-Path $repoRoot $ReportsDir
}

function Invoke-Maven {
    param([string[]]$Arguments)
    $effectiveMaven = $MavenExecutable
    if ([string]::IsNullOrWhiteSpace($effectiveMaven)) {
        $effectiveMaven = if ($IsWindows) { "mvn.cmd" } else { "mvn" }
    }
    $displayArguments = $Arguments | ForEach-Object {
        if ($_ -like "-Dcachedb.it.oracle.password=*") {
            "-Dcachedb.it.oracle.password=<redacted>"
        } else {
            $_
        }
    }
    Write-Host "==> $effectiveMaven $($displayArguments -join ' ')"
    $global:LASTEXITCODE = 0
    & $effectiveMaven @Arguments
    if ($global:LASTEXITCODE -ne 0) {
        throw "Maven failed with exit code $global:LASTEXITCODE"
    }
}

function Wait-ForOracleReady {
    param([string]$Name)
    $deadline = [DateTimeOffset]::Now.AddMinutes(7)
    while ([DateTimeOffset]::Now -lt $deadline) {
        $state = docker inspect --format "{{.State.Status}}" $Name 2>$null
        if ($LASTEXITCODE -ne 0 -or $state -ne "running") {
            throw "Oracle evidence container '$Name' is not running"
        }
        docker exec $Name healthcheck.sh 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Start-Sleep -Seconds 5
            return
        }
        Start-Sleep -Seconds 5
    }
    docker logs --tail 200 $Name | Out-Host
    throw "Oracle evidence container did not become ready within seven minutes"
}

function Remove-OwnedOracleContainer {
    param([string]$Name)
    $existing = docker ps -aq --filter "name=^/$Name$"
    if ([string]::IsNullOrWhiteSpace(($existing | Out-String))) {
        return
    }
    $label = docker inspect --format "{{index .Config.Labels `"$ownerLabelName`"}}" $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or ([string]$label).Trim() -ne $ownerLabelValue) {
        throw "Refusing to remove Oracle evidence container without the CacheDB ownership label: $Name"
    }
    docker rm -f $Name | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to remove owned Oracle evidence container: $Name"
    }
}

function Invoke-OracleEvidenceTests {
    param([string]$Phase)
    Invoke-Maven @(
        "-B", "-ntp",
        "-pl", "cachedb-storage-oracle,cachedb-starter,cachedb-spring-boot-starter,cachedb-spring-boot-starter-oracle,cachedb-maven-plugin",
        "-am", "test",
        "-Dtest=JdbcQueryDialectsTest,JdbcSchemaDialectsTest,OracleDatabaseDialectTest,OracleFailureClassifierTest,OracleOutboxDialectTest,OracleQueryDialectTest,OracleWriteBehindOptionsTest,OracleProviderIntegrationTest,OracleHighLatencyIntegrationTest,OracleMigrationPlannerTest,CacheDbSpringPropertiesTest,OracleStarterClasspathTest,CacheDbDoctorMojoTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dcachedb.it.oracle.required=true",
        "-Dcachedb.it.oracle.url=$OracleUrl",
        "-Dcachedb.it.oracle.user=$OracleUser",
        "-Dcachedb.it.oracle.password=$OraclePassword"
    )

    $benchmarkSource = Join-Path $repoRoot "cachedb-storage-oracle/target/oracle-write-behind-benchmark.json"
    if (-not (Test-Path -LiteralPath $benchmarkSource)) {
        throw "Oracle write-behind benchmark report was not generated: $benchmarkSource"
    }
    $benchmark = Get-Content -LiteralPath $benchmarkSource -Raw | ConvertFrom-Json
    if ($benchmark.status -ne "PASS") {
        throw "Oracle write-behind benchmark reported status '$($benchmark.status)'"
    }
    if ([double]$benchmark.operationsPerSecond -lt [double]$benchmark.minimumOperationsPerSecond) {
        throw "Oracle benchmark is below threshold: $($benchmark.operationsPerSecond) < $($benchmark.minimumOperationsPerSecond)"
    }
    Copy-Item -LiteralPath $benchmarkSource -Destination (Join-Path $reportsDir "oracle-write-behind-benchmark-$Phase.json") -Force
}

$resolvedReports = [System.IO.Path]::GetFullPath($reportsDir)
$resolvedTarget = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
if (-not $resolvedReports.StartsWith($resolvedTarget, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Oracle evidence reports must stay under the repository target directory: $resolvedReports"
}
if ($ExternalDatabase -and $RestartOracleContainer) {
    throw "ExternalDatabase cannot be combined with RestartOracleContainer. The owning topology controls restart and failover."
}
if (Test-Path -LiteralPath $resolvedReports) {
    Remove-Item -LiteralPath $resolvedReports -Recurse -Force
}
New-Item -ItemType Directory -Path $resolvedReports -Force | Out-Null

$startedContainer = $false
try {
    if (-not $ExternalDatabase -and -not $UseExistingContainer) {
        Remove-OwnedOracleContainer -Name $ContainerName
        docker run -d --name $ContainerName --label $ownerLabel -p 127.0.0.1:15211:1521 `
            -e "ORACLE_PASSWORD=$OraclePassword" `
            -e "APP_USER=$OracleUser" `
            -e "APP_USER_PASSWORD=$OraclePassword" `
            $OracleImage | Out-Null
        $startedContainer = $true
    }

    if ($ExternalDatabase) {
        Invoke-OracleEvidenceTests -Phase "external-database"
    } else {
        Wait-ForOracleReady -Name $ContainerName
        Invoke-OracleEvidenceTests -Phase "pre-restart"
    }

    if ($RestartOracleContainer) {
        docker restart $ContainerName | Out-Null
        Wait-ForOracleReady -Name $ContainerName
        Invoke-OracleEvidenceTests -Phase "post-restart"
    }

    $summaryPath = Join-Path $resolvedReports "oracle-provider-evidence.md"
    @(
        "# Oracle Provider Evidence",
        "",
        "- Oracle JDBC Thin provider discovery and Spring Boot wiring: passed",
        "- Version-guarded MERGE, stale write, delete, and empty-string contract: passed",
        "- Concurrent same-id write race: passed",
        "- Bounded 1,201-value IN query with Oracle 900-expression chunks: passed",
        "- Oracle outbox checkpoint and replay: passed",
        "- Delayed-network bounded read and write path: passed",
        "- Migration schema discovery, warm, compare, and memory estimate: passed",
        "- Write-behind throughput regression threshold: passed",
        "- Container restart and reconnect: $(if ($RestartOracleContainer) { 'passed' } elseif ($ExternalDatabase) { 'owned by external topology lane' } else { 'not requested' })",
        "",
        $(if ($ExternalDatabase) {
            "This lane proves provider behavior against the supplied Oracle endpoint. Topology, role transition, and service failover claims must come from the owning external evidence lane."
        } else {
            "This lane proves single-instance Oracle restart/reconnect and provider behavior. RAC, Data Guard, or application-specific service-name failover must still be tested in the owning application's staging topology."
        })
    ) | Set-Content -LiteralPath $summaryPath -Encoding UTF8

    [ordered]@{
        provider = "oracle"
        status = "passed"
        image = if ($ExternalDatabase) { "external" } else { $OracleImage }
        externalDatabase = [bool]$ExternalDatabase
        restartOracleContainer = [bool]$RestartOracleContainer
        generatedAt = [DateTimeOffset]::UtcNow.ToString("O")
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resolvedReports "oracle-provider-evidence.json") -Encoding UTF8
} finally {
    if ($startedContainer) {
        Remove-OwnedOracleContainer -Name $ContainerName
    }
}
