param(
    [string]$MavenExecutable = "",
    [string]$RedisUri = "redis://127.0.0.1:56379",
    [string]$RedisContainerId = "",
    [int]$OutageSeconds = 5,
    [string]$PostgresUrl = "jdbc:postgresql://127.0.0.1:55432/postgres",
    [string]$PostgresUser = "postgres",
    [string]$PostgresPassword = "postgresql"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-redis-failover-reports"
$beforeDir = Join-Path $reportsDir "before-outage"
$afterDir = Join-Path $reportsDir "after-outage"

function Resolve-RedisHostPort {
    try {
        $uri = [Uri]$RedisUri
        if ($uri.Port -gt 0) {
            return $uri.Port
        }
    } catch {
    }
    return 56379
}

function Resolve-RedisCliPingArguments {
    try {
        $uri = [Uri]$RedisUri
        if (-not [string]::IsNullOrWhiteSpace($uri.UserInfo) -and $uri.UserInfo.Contains(":")) {
            $password = $uri.UserInfo.Split(":", 2)[1]
            if (-not [string]::IsNullOrWhiteSpace($password)) {
                return @("-a", $password, "ping")
            }
        }
    } catch {
    }
    return @("ping")
}

function Resolve-RedisContainer {
    if (-not [string]::IsNullOrWhiteSpace($RedisContainerId)) {
        return $RedisContainerId
    }
    $redisHostPort = Resolve-RedisHostPort
    $byPort = docker ps --filter "publish=$redisHostPort" --format "{{.ID}}" | Select-Object -First 1
    if (-not [string]::IsNullOrWhiteSpace($byPort)) {
        return $byPort
    }
    $byImage = docker ps --filter "ancestor=redis:8-alpine" --format "{{.ID}}" | Select-Object -First 1
    if (-not [string]::IsNullOrWhiteSpace($byImage)) {
        return $byImage
    }
    throw "Could not resolve Redis service container. Pass -RedisContainerId explicitly."
}

function Wait-RedisReady {
    param(
        [string]$ContainerId,
        [int]$TimeoutSeconds = 120
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    [string[]]$pingArguments = @(Resolve-RedisCliPingArguments)
    $lastState = ""
    $lastPing = ""
    $lastExitCode = 0
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $global:LASTEXITCODE = 0
        $lastState = (docker inspect -f "{{.State.Status}} paused={{.State.Paused}}" $ContainerId 2>$null) -join ""
        $global:LASTEXITCODE = 0
        $ping = docker exec $ContainerId redis-cli @pingArguments 2>$null
        $lastExitCode = $global:LASTEXITCODE
        $lastPing = ($ping -join "").Trim()
        if ($lastExitCode -eq 0 -and $lastPing -eq "PONG") {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Redis did not become healthy after outage. Last state: $lastState. Last redis-cli exit: $lastExitCode. Last redis-cli output: $lastPing"
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
