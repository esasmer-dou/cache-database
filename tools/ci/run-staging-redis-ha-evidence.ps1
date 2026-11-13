param(
    [string]$MavenExecutable = "",
    [Parameter(Mandatory = $true)]
    [string]$RedisUri,
    [Parameter(Mandatory = $true)]
    [string]$PostgresUrl,
    [Parameter(Mandatory = $true)]
    [string]$PostgresUser,
    [Parameter(Mandatory = $true)]
    [string]$PostgresPassword,
    [ValidateRange(0, 2147483647)]
    [int]$ExternalFailoverWaitSeconds = 120,
    [switch]$ReconnectSmokeOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsRoot = Join-Path (Join-Path $repoRoot "target") "cachedb-staging-ha-reports"
$runId = "run-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))-$([guid]::NewGuid().ToString('N'))"
$reportsDir = Join-Path $reportsRoot $runId
$beforeDir = Join-Path $reportsDir "before-wait"
$afterDir = Join-Path $reportsDir "after-wait"
$summaryPath = Join-Path $reportsDir "staging-redis-ha-evidence.md"
$jsonPath = Join-Path $reportsDir "staging-redis-ha-evidence.json"

function Assert-NonBlank {
    param(
        [string]$Name,
        [string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is required for staging Redis HA evidence."
    }
}

function Run-CoordinationSmoke {
    param([string]$OutputDirectory)
    $global:LASTEXITCODE = 0
    & (Join-Path $PSScriptRoot "run-multi-instance-coordination-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -RedisUri $RedisUri `
        -PostgresUrl $PostgresUrl `
        -PostgresUser $PostgresUser `
        -PostgresPassword $PostgresPassword `
        -ReportsDir $OutputDirectory
    $smokeSucceeded = $?
    if (-not $smokeSucceeded -or $global:LASTEXITCODE -ne 0) {
        throw "Redis coordination smoke failed with exit code $global:LASTEXITCODE."
    }
}

function Write-StagingEvidence {
    $evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
    @(
        "# Staging Redis Smoke Evidence",
        "",
        "- Run: $runId",
        "- Mode: $($evidence.executionMode)",
        "- Status: $($evidence.status)",
        "- External failover wait: $ExternalFailoverWaitSeconds second(s)",
        "- Before-wait coordination smoke: $($evidence.beforeSmoke)",
        "- After-wait coordination smoke: $($evidence.afterSmoke)",
        "- liveFailoverVerified=false",
        "- haQualified=false",
        "",
        "Only the recorded smoke outcomes are established. An elapsed wait does not prove failover or connection recovery.",
        "No independent live-failover witness is collected or validated by this runner. HA remains NOT QUALIFIED."
    ) | Set-Content -LiteralPath $summaryPath -Encoding UTF8
}

Assert-NonBlank -Name "RedisUri" -Value $RedisUri
Assert-NonBlank -Name "PostgresUrl" -Value $PostgresUrl
Assert-NonBlank -Name "PostgresUser" -Value $PostgresUser
Assert-NonBlank -Name "PostgresPassword" -Value $PostgresPassword

New-Item -ItemType Directory -Path $reportsRoot -Force | Out-Null
New-Item -ItemType Directory -Path $reportsDir | Out-Null
$evidence = [ordered]@{
    schemaVersion = 1
    provider = "redis"
    runId = $runId
    executionMode = $(if ($ReconnectSmokeOnly) { "reconnect-smoke-only" } else { "ha-qualification" })
    status = "running"
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    completedAtUtc = $null
    externalFailoverWaitSeconds = $ExternalFailoverWaitSeconds
    beforeSmoke = "not-run"
    afterSmoke = "not-run"
    smokeChecksPassed = $false
    liveFailoverVerified = $false
    haQualified = $false
    qualificationReason = "No independent live-failover witness is collected or validated by this runner."
    failurePhase = $null
}
Write-StagingEvidence
Write-Host "Run reports: $reportsDir"

$phase = "beforeSmoke"
try {
    Write-Host "Running before-wait staging coordination smoke..."
    $evidence.beforeSmoke = "running"
    Run-CoordinationSmoke -OutputDirectory $beforeDir
    $evidence.beforeSmoke = "passed"

    $phase = "wait"
    if ($ExternalFailoverWaitSeconds -gt 0) {
        Write-Host "Waiting $ExternalFailoverWaitSeconds second(s) for operator activity; no failover witness is collected."
        Start-Sleep -Seconds $ExternalFailoverWaitSeconds
    } else {
        Write-Host "External failover wait skipped; this does not establish reconnect or live failover."
    }

    $phase = "afterSmoke"
    Write-Host "Running after-wait staging coordination smoke..."
    $evidence.afterSmoke = "running"
    Run-CoordinationSmoke -OutputDirectory $afterDir
    $evidence.afterSmoke = "passed"
    $evidence.smokeChecksPassed = $true
    $evidence.status = $(if ($ReconnectSmokeOnly) { "smoke-only-passed" } else { "ha-not-qualified" })
} catch {
    if ($phase -ne "wait") { $evidence[$phase] = "failed" }
    $evidence.status = "smoke-failed"
    $evidence.failurePhase = $phase
    throw
} finally {
    $evidence.completedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    Write-StagingEvidence
    Write-Host "liveFailoverVerified=false"
    Write-Host "haQualified=false"
    Write-Host "Summary: $summaryPath"
    Write-Host "JSON: $jsonPath"
}

if (-not $ReconnectSmokeOnly) {
    throw "Staging Redis HA is NOT QUALIFIED: no independent live-failover witness was validated. Reports: $reportsDir"
}
Write-Host "Staging Redis smoke checks passed; live failover and HA remain unverified."
