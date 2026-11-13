param(
    [string]$MavenExecutable = "",
    [Parameter(Mandatory = $true)]
    [string]$MssqlUrl,
    [Parameter(Mandatory = $true)]
    [string]$MssqlUser,
    [Parameter(Mandatory = $true)]
    [string]$MssqlPassword,
    [ValidateRange(0, 2147483647)]
    [int]$ExternalFailoverWaitSeconds = 120,
    [switch]$ReconnectSmokeOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsRoot = Join-Path (Join-Path $repoRoot "target") "cachedb-staging-mssql-ha-reports"
$runId = "run-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))-$([guid]::NewGuid().ToString('N'))"
$reportsDir = Join-Path $reportsRoot $runId
$beforeDir = Join-Path $reportsDir "before-wait"
$afterDir = Join-Path $reportsDir "after-wait"
$summaryPath = Join-Path $reportsDir "staging-mssql-ha-evidence.md"
$jsonPath = Join-Path $reportsDir "staging-mssql-ha-evidence.json"

function Assert-NonBlank {
    param(
        [string]$Name,
        [string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is required for staging MSSQL HA evidence."
    }
}

function Run-MssqlEvidence {
    param([string]$OutputDirectory)
    $global:LASTEXITCODE = 0
    & (Join-Path $PSScriptRoot "run-mssql-provider-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -MssqlUrl $MssqlUrl `
        -MssqlUser $MssqlUser `
        -MssqlPassword $MssqlPassword `
        -ReportsDir $OutputDirectory
    $smokeSucceeded = $?
    if (-not $smokeSucceeded -or $global:LASTEXITCODE -ne 0) {
        throw "MSSQL provider smoke failed with exit code $global:LASTEXITCODE."
    }
}

function Write-StagingEvidence {
    $evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
    @(
        "# Staging MSSQL Smoke Evidence",
        "",
        "- Run: $runId",
        "- Mode: $($evidence.executionMode)",
        "- Status: $($evidence.status)",
        "- External failover wait: $ExternalFailoverWaitSeconds second(s)",
        "- Before-wait provider smoke: $($evidence.beforeSmoke)",
        "- After-wait provider smoke: $($evidence.afterSmoke)",
        "- liveFailoverVerified=false",
        "- haQualified=false",
        "",
        "Only the recorded smoke outcomes are established. An elapsed wait does not prove failover or connection recovery.",
        "No independent live-failover witness is collected or validated by this runner. HA remains NOT QUALIFIED."
    ) | Set-Content -LiteralPath $summaryPath -Encoding UTF8
}

Assert-NonBlank -Name "MssqlUrl" -Value $MssqlUrl
Assert-NonBlank -Name "MssqlUser" -Value $MssqlUser
Assert-NonBlank -Name "MssqlPassword" -Value $MssqlPassword

New-Item -ItemType Directory -Path $reportsRoot -Force | Out-Null
New-Item -ItemType Directory -Path $reportsDir | Out-Null
$evidence = [ordered]@{
    schemaVersion = 1
    provider = "mssql"
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
    Write-Host "Running before-wait staging MSSQL provider smoke..."
    $evidence.beforeSmoke = "running"
    Run-MssqlEvidence -OutputDirectory $beforeDir
    $evidence.beforeSmoke = "passed"

    $phase = "wait"
    if ($ExternalFailoverWaitSeconds -gt 0) {
        Write-Host "Waiting $ExternalFailoverWaitSeconds second(s) for operator activity; no failover witness is collected."
        Start-Sleep -Seconds $ExternalFailoverWaitSeconds
    } else {
        Write-Host "External failover wait skipped; this does not establish reconnect or live failover."
    }

    $phase = "afterSmoke"
    Write-Host "Running after-wait staging MSSQL provider smoke..."
    $evidence.afterSmoke = "running"
    Run-MssqlEvidence -OutputDirectory $afterDir
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
    throw "Staging MSSQL HA is NOT QUALIFIED: no independent live-failover witness was validated. Reports: $reportsDir"
}
Write-Host "Staging MSSQL smoke checks passed; live failover and HA remain unverified."
