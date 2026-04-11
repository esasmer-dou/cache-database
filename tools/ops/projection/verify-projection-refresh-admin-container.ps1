$ErrorActionPreference = "Stop"

$dockerExe = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$containerName = "cachedb-spring-load-demo"

function Invoke-Docker {
    param(
        [string]$Arguments,
        [int]$TimeoutMs = 300000
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $dockerExe
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.Environment["SystemRoot"] = $env:SystemRoot
    $psi.Environment["ComSpec"] = $env:ComSpec
    $psi.Environment["Path"] = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path", "User")

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    if (-not $process.WaitForExit($TimeoutMs)) {
        try { $process.Kill($true) } catch {}
        throw "Timeout running docker $Arguments"
    }

    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    if ($process.ExitCode -ne 0) {
        throw "docker failed: $Arguments`n$stdout`n$stderr"
    }
    return $stdout
}

function Get-ContainerJson {
    param([string]$Path)
    return (Invoke-Docker "exec $containerName /usr/bin/curl -sS http://127.0.0.1:8090$Path") | ConvertFrom-Json
}

function Get-ContainerText {
    param([string]$Path)
    return Invoke-Docker "exec $containerName /usr/bin/curl -sS http://127.0.0.1:8090$Path"
}

$hot = Get-ContainerJson "/cachedb-admin/api/dashboard/hot"
$projectionRefresh = Get-ContainerJson "/cachedb-admin/api/projection-refresh"
$projectionFailures = Get-ContainerJson "/cachedb-admin/api/projection-refresh/failed?limit=5"
$dashboardHtml = Get-ContainerText "/cachedb-admin?lang=en"
$logs = Invoke-Docker "logs --tail 120 $containerName"

[pscustomobject]@{
    health = $hot.h.status
    hotHasProjectionRefreshSnapshot = ($null -ne $hot.m.projectionRefreshSnapshot)
    projectionRefreshPending = $projectionRefresh.pendingCount
    projectionRefreshDeadLetter = $projectionRefresh.deadLetterStreamLength
    projectionRefreshProcessed = $projectionRefresh.processedCount
    projectionRefreshFailures = @($projectionFailures.items).Count
    dashboardHasProjectionRefreshSection = $dashboardHtml -match "Projection Refresh"
    dashboardHasReplayStatus = $dashboardHtml -match "Replay status|Projection refresh replay tools are ready"
    dashboardHasSignalDashboard = $dashboardHtml -match "Signal Dashboard"
    recentLogsContainProjectionRefreshServletError = $logs -match "projection-refresh"
    recentLogsContainDiagnosticsServletError = $logs -match "CacheDatabaseAdmin\\.diagnostics"
    logTail = $logs
}
