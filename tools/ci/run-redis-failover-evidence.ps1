param(
    [string]$MavenExecutable = "",
    [string]$RedisUri = "redis://default:welcome1@127.0.0.1:56379",
    [string]$RedisContainerId = "",
    [int]$OutageSeconds = 5,
    [string]$PostgresUrl = "jdbc:postgresql://127.0.0.1:55432/postgres",
    [string]$PostgresUser = "postgres",
    [string]$PostgresPassword = "postgresql"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path $repoRoot "target\cachedb-redis-failover-reports"
$beforeDir = Join-Path $reportsDir "before-outage"
$afterDir = Join-Path $reportsDir "after-outage"

function Resolve-RedisContainer {
    if (-not [string]::IsNullOrWhiteSpace($RedisContainerId)) {
        return $RedisContainerId
    }
    $byPort = docker ps --filter "publish=56379" --format "{{.ID}}" | Select-Object -First 1
    if (-not [string]::IsNullOrWhiteSpace($byPort)) {
        return $byPort
    }
    $byImage = docker ps --filter "ancestor=bitnami/redis:7.2" --format "{{.ID}}" | Select-Object -First 1
    if (-not [string]::IsNullOrWhiteSpace($byImage)) {
        return $byImage
    }
    throw "Could not resolve Redis service container. Pass -RedisContainerId explicitly."
}

function Wait-RedisReady {
    param(
        [string]$ContainerId,
        [int]$TimeoutSeconds = 30
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $global:LASTEXITCODE = 0
        $ping = docker exec $ContainerId redis-cli -a welcome1 ping 2>$null
        if ($global:LASTEXITCODE -eq 0 -and ($ping -join "").Trim() -eq "PONG") {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Redis did not become healthy after outage."
}

function Run-CoordinationSmoke {
    param([string]$OutputDirectory)
    & (Join-Path $PSScriptRoot "run-multi-instance-coordination-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -RedisUri $RedisUri `
        -PostgresUrl $PostgresUrl `
        -PostgresUser $PostgresUser `
        -PostgresPassword $PostgresPassword `
        -ReportsDir $OutputDirectory
}

if (Test-Path $reportsDir) {
    Remove-Item -Recurse -Force $reportsDir
}
New-Item -ItemType Directory -Path $beforeDir -Force | Out-Null
New-Item -ItemType Directory -Path $afterDir -Force | Out-Null

$containerId = Resolve-RedisContainer
Write-Host "Redis failover evidence container: $containerId"

Write-Host "Running pre-outage coordination smoke..."
Run-CoordinationSmoke -OutputDirectory $beforeDir

Write-Host "Pausing Redis container for $OutageSeconds second(s)..."
docker pause $containerId | Out-Host
Start-Sleep -Seconds $OutageSeconds
Write-Host "Unpausing Redis container..."
docker unpause $containerId | Out-Host
Wait-RedisReady -ContainerId $containerId

Write-Host "Running post-outage coordination smoke..."
Run-CoordinationSmoke -OutputDirectory $afterDir

$summaryPath = Join-Path $reportsDir "redis-failover-evidence.md"
$summary = @"
# Redis Failover Evidence

- Redis container: ``$containerId``
- Injected outage: ``$OutageSeconds`` second(s) via ``docker pause``
- Pre-outage coordination smoke: ``passed``
- Post-outage coordination smoke: ``passed``

This lane validates that CacheDB can restart its multi-instance coordination, consumer identity, leader lease, and pending-entry claim evidence after a Redis outage. It is a CI regression gate, not a replacement for a full managed Redis HA topology test in staging.
"@
Set-Content -Path $summaryPath -Value $summary -NoNewline

Write-Host "Redis failover evidence passed."
Write-Host "Summary: $summaryPath"
