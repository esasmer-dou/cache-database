$ErrorActionPreference = 'Stop'

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'
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
    throw "dashboard fetch failed.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
}

@(
    'has_performance_scenario_dashboard=' + $stdout.Contains('performanceScenarioGrid')
    'has_performance_top_read_dashboard=' + $stdout.Contains('performanceTopReadGrid')
    'has_performance_top_write_dashboard=' + $stdout.Contains('performanceTopWriteGrid')
    'has_performance_top_postgres_read_dashboard=' + $stdout.Contains('performanceTopPostgresReadGrid')
    'has_performance_top_postgres_write_dashboard=' + $stdout.Contains('performanceTopPostgresWriteGrid')
    'has_performance_bottleneck_summary=' + $stdout.Contains('performanceBottleneckSummary')
    'has_performance_pressure_badge=' + $stdout.Contains('performancePressureBadge')
    'has_performance_pressure_bar=' + $stdout.Contains('performancePressureReadBar')
    'has_performance_pressure_scope=' + $stdout.Contains('performancePressureScopeLabel')
    'has_performance_rank_badge=' + $stdout.Contains('performance-rank-badge')
    'has_performance_demo_action=' + $stdout.Contains('Demo yükünü aç')
    'has_performance_scenario_fn=' + $stdout.Contains('renderPerformanceScenarioDashboard')
    'has_performance_scenario_intro=' + $stdout.Contains('hangi okuma ve yazma senaryosunda Redis veya PostgreSQL tarafını daha çok zorladığını ayırır')
    'has_performance_scenario_label=' + $stdout.Contains('Toplu müşteri okuması')
)
