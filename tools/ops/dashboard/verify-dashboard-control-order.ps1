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
    throw "dashboard html fetch failed: $stderr`n$stdout"
}

$liveRefreshIndex = $stdout.IndexOf('nav-panel-header">Canlı Yenileme')
$resetToolsIndex = $stdout.IndexOf('nav-panel-header text-danger">Yönetim Sıfırlama Araçları')
$overviewIndex = $stdout.IndexOf('workspace-group-title">Genel Bakış')

[pscustomobject]@{
    liveRefreshIndex = $liveRefreshIndex
    resetToolsIndex = $resetToolsIndex
    overviewIndex = $overviewIndex
    liveRefreshBeforeOverview = ($liveRefreshIndex -ge 0 -and $overviewIndex -ge 0 -and $liveRefreshIndex -lt $overviewIndex)
    resetToolsBeforeOverview = ($resetToolsIndex -ge 0 -and $overviewIndex -ge 0 -and $resetToolsIndex -lt $overviewIndex)
    resetAfterLiveRefresh = ($liveRefreshIndex -ge 0 -and $resetToolsIndex -ge 0 -and $liveRefreshIndex -lt $resetToolsIndex)
} | ConvertTo-Json -Depth 3
