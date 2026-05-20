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
    [int]$ExternalFailoverWaitSeconds = 120
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-staging-ha-reports"
$beforeDir = Join-Path $reportsDir "before-external-failover"
$afterDir = Join-Path $reportsDir "after-external-failover"

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
    & (Join-Path $PSScriptRoot "run-multi-instance-coordination-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -RedisUri $RedisUri `
        -PostgresUrl $PostgresUrl `
        -PostgresUser $PostgresUser `
        -PostgresPassword $PostgresPassword `
        -ReportsDir $OutputDirectory
}

Assert-NonBlank -Name "RedisUri" -Value $RedisUri
Assert-NonBlank -Name "PostgresUrl" -Value $PostgresUrl
Assert-NonBlank -Name "PostgresUser" -Value $PostgresUser
Assert-NonBlank -Name "PostgresPassword" -Value $PostgresPassword

if (Test-Path $reportsDir) {
    Remove-Item -Recurse -Force $reportsDir
}
New-Item -ItemType Directory -Path $beforeDir -Force | Out-Null
New-Item -ItemType Directory -Path $afterDir -Force | Out-Null

Write-Host "Running pre-failover staging coordination smoke..."
Run-CoordinationSmoke -OutputDirectory $beforeDir

if ($ExternalFailoverWaitSeconds -gt 0) {
    Write-Host "Waiting $ExternalFailoverWaitSeconds second(s) for an externally triggered Redis HA failover."
    Write-Host "Trigger failover in the managed Redis tier now. This script does not mutate the managed Redis topology."
    Start-Sleep -Seconds $ExternalFailoverWaitSeconds
} else {
    Write-Host "External failover wait skipped. Post-check will validate reconnect/reuse only, not a live failover."
}

Write-Host "Running post-failover staging coordination smoke..."
Run-CoordinationSmoke -OutputDirectory $afterDir

$summaryPath = Join-Path $reportsDir "staging-redis-ha-evidence.md"
$summary = @"
# Staging Redis HA Evidence

- Redis URI: supplied via workflow secret or local parameter
- PostgreSQL URL: supplied via workflow secret or local parameter
- External failover wait: ``$ExternalFailoverWaitSeconds`` second(s)
- Pre-failover coordination smoke: ``passed``
- Post-failover coordination smoke: ``passed``

This lane validates CacheDB coordination, consumer identity, leader lease, and pending-entry claim behavior against the staging Redis/PostgreSQL topology. For GA, run it while a real managed Redis failover is triggered during the wait window.
"@
Set-Content -Path $summaryPath -Value $summary -NoNewline

Write-Host "Staging Redis HA evidence passed."
Write-Host "Summary: $summaryPath"
