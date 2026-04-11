$ErrorActionPreference = 'Stop'

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- ''http://127.0.0.1:8090/?focus=high-line-orders-read&level=HIGH''"'
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
    throw "demo focus fetch failed.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
}

@(
    'has_focus_panel=' + $stdout.Contains('scenarioFocusPanel')
    'has_focus_logic=' + $stdout.Contains('applyScenarioFocus')
    'has_focus_copy=' + $stdout.Contains('Admin konsolundan gelen senaryo odağı')
    'has_focus_tag=' + $stdout.Contains('high-line-orders-read')
)
