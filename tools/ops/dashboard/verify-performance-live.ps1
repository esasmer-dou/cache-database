$ErrorActionPreference = 'Stop'

function Invoke-DockerFetch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,

        [ValidateSet('GET', 'POST')]
        [string]$Method = 'GET'
    )

    $wgetArgs = if ($Method -eq 'POST') {
        "wget -qO- --post-data='' $Url"
    } else {
        "wget -qO- $Url"
    }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = "exec cachedb-demo-ui bash -lc `"$wgetArgs`""
    $psi.WorkingDirectory = 'C:\Program Files\Docker\Docker\resources\bin'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "docker fetch failed for $Method $Url.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

function Wait-ForDemoIdle {
    param(
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = Invoke-DockerFetch 'http://127.0.0.1:8090/api/status' | ConvertFrom-Json
        $actionState = [string]($status.action.state)
        $running = [bool]$status.snapshot.running
        if ((-not $running) -and ($actionState -notin @('QUEUED', 'RUNNING'))) {
            return $status
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)

    throw 'demo scenario did not become idle in time'
}

$dashboardHtml = Invoke-DockerFetch 'http://127.0.0.1:8080/dashboard?lang=tr'
$performanceBefore = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance' | ConvertFrom-Json
$performanceHistoryBefore = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/history?limit=20' | ConvertFrom-Json

Invoke-DockerFetch 'http://127.0.0.1:8090/api/fresh-reset' 'POST' | Out-Null
Wait-ForDemoIdle | Out-Null

Invoke-DockerFetch 'http://127.0.0.1:8090/api/seed' 'POST' | Out-Null
Wait-ForDemoIdle | Out-Null

Invoke-DockerFetch 'http://127.0.0.1:8090/api/start?level=HIGH' 'POST' | Out-Null
Start-Sleep -Seconds 8
$performanceDuring = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance' | ConvertFrom-Json
$performanceHistoryDuring = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/history?limit=20' | ConvertFrom-Json
Invoke-DockerFetch 'http://127.0.0.1:8090/api/stop' 'POST' | Out-Null
Wait-ForDemoIdle | Out-Null

$resetResult = Invoke-DockerFetch 'http://127.0.0.1:8080/api/telemetry/reset' 'POST' | ConvertFrom-Json
$performanceResetResult = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/reset' 'POST' | ConvertFrom-Json
$performanceAfterReset = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance' | ConvertFrom-Json
$performanceHistoryAfterReset = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/history?limit=20' | ConvertFrom-Json

$redisReadCount = [int64]$performanceDuring.redisRead.operationCount
$redisWriteCount = [int64]$performanceDuring.redisWrite.operationCount
$postgresReadCount = [int64]$performanceDuring.postgresRead.operationCount
$postgresWriteCount = [int64]$performanceDuring.postgresWrite.operationCount
$redisReadScenarioCount = @($performanceDuring.redisReadBreakdown.items).Count
$redisWriteScenarioCount = @($performanceDuring.redisWriteBreakdown.items).Count
$postgresReadScenarioCount = @($performanceDuring.postgresReadBreakdown.items).Count
$postgresWriteScenarioCount = @($performanceDuring.postgresWriteBreakdown.items).Count
$effectivePostgresWriteScenarioCount = if ($postgresWriteScenarioCount -gt 0) { $postgresWriteScenarioCount } else { $redisWriteScenarioCount }
$resetOpsCleared = [int64]$resetResult.storagePerformanceOperationsCleared
$performanceOnlyResetOpsCleared = [int64]$performanceResetResult.storagePerformanceOperationsCleared
$performanceOnlyResetHistoryCleared = [int]$performanceResetResult.performanceHistorySamplesCleared
$afterResetRedisRead = [int64]$performanceAfterReset.redisRead.operationCount
$afterResetRedisWrite = [int64]$performanceAfterReset.redisWrite.operationCount
$afterResetPostgresRead = [int64]$performanceAfterReset.postgresRead.operationCount
$afterResetPostgresWrite = [int64]$performanceAfterReset.postgresWrite.operationCount
$performanceHistoryBeforeCount = ($performanceHistoryBefore.items | Measure-Object).Count
$performanceHistoryDuringCount = ($performanceHistoryDuring.items | Measure-Object).Count
$performanceHistoryAfterResetCount = ($performanceHistoryAfterReset.items | Measure-Object).Count

@(
    'has_performance_section=' + $dashboardHtml.Contains('Gecikme ve Performans')
    'has_performance_nav=' + $dashboardHtml.Contains('Redis ve PostgreSQL gecikmesini buradan izlersin')
    'has_performance_scope_note=' + $dashboardHtml.Contains('Son resetten beri biriken süreç içi gözlemler')
    'has_performance_reset_button=' + $dashboardHtml.Contains('Performans Ölçümlerini Temizle')
    'has_performance_trend_dashboard=' + $dashboardHtml.Contains('performanceTrendDashboard')
    'has_performance_scenario_dashboard=' + $dashboardHtml.Contains('performanceScenarioGrid')
    'has_performance_top_read_dashboard=' + $dashboardHtml.Contains('performanceTopReadGrid')
    'has_performance_top_write_dashboard=' + $dashboardHtml.Contains('performanceTopWriteGrid')
    'has_performance_top_postgres_read_dashboard=' + $dashboardHtml.Contains('performanceTopPostgresReadGrid')
    'has_performance_top_postgres_write_dashboard=' + $dashboardHtml.Contains('performanceTopPostgresWriteGrid')
    'has_performance_render=' + ($dashboardHtml -match 'renderPerformanceSection\(perf,perfHistory\)')
    'has_performance_api_call=' + ($dashboardHtml -match '/api/performance')
    'has_performance_history_api=' + ($dashboardHtml -match '/api/performance/history\?limit=60')
    'has_performance_reset_api=' + ($dashboardHtml -match '/api/performance/reset')
    'has_redis_read_card=' + ($dashboardHtml -match 'performanceRedisRead')
    'has_postgres_write_card=' + ($dashboardHtml -match 'performancePostgresWrite')
    'before_has_payload=' + ($null -ne $performanceBefore.redisRead -and $null -ne $performanceBefore.postgresWrite)
    'history_before_count=' + $performanceHistoryBeforeCount
    'during_has_payload=' + ($null -ne $performanceDuring.redisRead -and $null -ne $performanceDuring.postgresWrite)
    'redis_read_count=' + $redisReadCount
    'redis_write_count=' + $redisWriteCount
    'postgres_read_count=' + $postgresReadCount
    'postgres_write_count=' + $postgresWriteCount
    'redis_read_scenario_count=' + $redisReadScenarioCount
    'redis_write_scenario_count=' + $redisWriteScenarioCount
    'postgres_read_scenario_count=' + $postgresReadScenarioCount
    'postgres_write_scenario_count=' + $postgresWriteScenarioCount
    'postgres_write_scenario_count_effective=' + $effectivePostgresWriteScenarioCount
    'history_during_count=' + $performanceHistoryDuringCount
    'redis_read_dynamic=' + ($redisReadCount -gt 0)
    'redis_write_dynamic=' + ($redisWriteCount -gt 0)
    'postgres_read_dynamic=' + ($postgresReadCount -gt 0)
    'postgres_write_dynamic=' + ($postgresWriteCount -gt 0)
    'redis_read_breakdown_dynamic=' + ($redisReadScenarioCount -gt 0)
    'redis_write_breakdown_dynamic=' + ($redisWriteScenarioCount -gt 0)
    'postgres_read_breakdown_dynamic=' + ($postgresReadScenarioCount -gt 0)
    'postgres_write_breakdown_dynamic=' + ($postgresWriteScenarioCount -gt 0)
    'postgres_write_breakdown_effective_dynamic=' + ($effectivePostgresWriteScenarioCount -gt 0)
    'reset_performance_ops_cleared=' + $resetOpsCleared
    'reset_reported_performance_clear=' + ($resetOpsCleared -gt 0)
    'performance_only_reset_ops_cleared=' + $performanceOnlyResetOpsCleared
    'performance_only_reset_history_cleared=' + $performanceOnlyResetHistoryCleared
    'after_reset_redis_read=' + $afterResetRedisRead
    'after_reset_redis_write=' + $afterResetRedisWrite
    'after_reset_postgres_read=' + $afterResetPostgresRead
    'after_reset_postgres_write=' + $afterResetPostgresWrite
    'history_after_reset_count=' + $performanceHistoryAfterResetCount
    'after_reset_zero=' + (($afterResetRedisRead -eq 0) -and ($afterResetRedisWrite -eq 0) -and ($afterResetPostgresRead -eq 0) -and ($afterResetPostgresWrite -eq 0))
)
