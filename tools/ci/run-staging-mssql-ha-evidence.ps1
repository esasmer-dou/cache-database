param(
    [string]$MavenExecutable = "",
    [Parameter(Mandatory = $true)]
    [string]$MssqlUrl,
    [Parameter(Mandatory = $true)]
    [string]$MssqlUser,
    [Parameter(Mandatory = $true)]
    [string]$MssqlPassword,
    [int]$ExternalFailoverWaitSeconds = 120
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path (Join-Path $repoRoot "target") "cachedb-staging-mssql-ha-reports"
$beforeDir = Join-Path $reportsDir "before-external-failover"
$afterDir = Join-Path $reportsDir "after-external-failover"

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
    & (Join-Path $PSScriptRoot "run-mssql-provider-evidence.ps1") `
        -MavenExecutable $MavenExecutable `
        -MssqlUrl $MssqlUrl `
        -MssqlUser $MssqlUser `
        -MssqlPassword $MssqlPassword `
        -ReportsDir $OutputDirectory
}

Assert-NonBlank -Name "MssqlUrl" -Value $MssqlUrl
Assert-NonBlank -Name "MssqlUser" -Value $MssqlUser
Assert-NonBlank -Name "MssqlPassword" -Value $MssqlPassword

if (Test-Path $reportsDir) {
    Remove-Item -Recurse -Force $reportsDir
}
New-Item -ItemType Directory -Path $beforeDir -Force | Out-Null
New-Item -ItemType Directory -Path $afterDir -Force | Out-Null

Write-Host "Running pre-failover staging MSSQL provider evidence..."
Run-MssqlEvidence -OutputDirectory $beforeDir

if ($ExternalFailoverWaitSeconds -gt 0) {
    Write-Host "Waiting $ExternalFailoverWaitSeconds second(s) for an externally triggered SQL Server HA failover."
    Write-Host "Trigger failover in the managed SQL Server or Always On topology now. This script does not mutate the production topology."
    Start-Sleep -Seconds $ExternalFailoverWaitSeconds
} else {
    Write-Host "External failover wait skipped. Post-check will validate reconnect/reuse only, not a live HA failover."
}

Write-Host "Running post-failover staging MSSQL provider evidence..."
Run-MssqlEvidence -OutputDirectory $afterDir

$summaryPath = Join-Path $reportsDir "staging-mssql-ha-evidence.md"
$summary = @"
# Staging MSSQL HA Evidence

- MSSQL URL: supplied via workflow secret or local parameter
- External failover wait: ``$ExternalFailoverWaitSeconds`` second(s)
- Pre-failover provider evidence: ``passed``
- Post-failover provider evidence: ``passed``

This lane validates CacheDB MSSQL write-behind, outbox checkpointing, high-volume replay, migration SQL, and multi-pod apply behavior against a real staging SQL Server HA topology. For GA, run it while a real managed SQL Server failover or Always On failover is triggered during the wait window.
"@
Set-Content -Path $summaryPath -Value $summary -NoNewline

Write-Host "Staging MSSQL HA evidence passed."
Write-Host "Summary: $summaryPath"
