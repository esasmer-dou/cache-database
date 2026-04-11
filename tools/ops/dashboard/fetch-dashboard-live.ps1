param(
    [string]$Lang = 'tr',
    [string]$OutputPath = 'E:\ReactorRepository\cache-database\tools\tmp\dashboard-live.html'
)

$ErrorActionPreference = 'Stop'

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = "exec cachedb-demo-ui bash -lc `"wget -qO- http://127.0.0.1:8080/dashboard?lang=$Lang`""
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

Set-Content -Path $OutputPath -Value $stdout
Write-Output ("saved=" + $OutputPath)
