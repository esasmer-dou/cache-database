$ErrorActionPreference = 'Stop'

function Invoke-DockerExec {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Arguments
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = $Arguments
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
        throw "docker exec failed.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

Write-Host '[1/5] Fresh Start tetikleniyor'
Invoke-DockerExec 'exec cachedb-demo-ui bash -lc "wget -qO- --post-data=\"\" http://127.0.0.1:8090/api/fresh-reset"' | Out-Null
Start-Sleep -Seconds 4

Write-Host '[2/5] Arka plan hata snapshot'
$backgroundErrorsJson = Invoke-DockerExec 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/api/background-errors"'
Write-Host $backgroundErrorsJson

Write-Host '[3/5] Health snapshot'
$healthJson = Invoke-DockerExec 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/api/health"'
Write-Host $healthJson

Write-Host '[4/5] Worker metrics snapshot'
$metricsJson = Invoke-DockerExec 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/api/metrics"'
Write-Host ($metricsJson.Substring(0, [Math]::Min($metricsJson.Length, 600)))

Write-Host '[5/5] Son container loglari'
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = 'logs --tail 200 cachedb-demo-ui'
$psi.WorkingDirectory = 'C:\Program Files\Docker\Docker\resources\bin'
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$process = [System.Diagnostics.Process]::Start($psi)
$logs = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -ne 0) {
    throw "docker logs failed.`nSTDERR:`n$stderr"
}
Write-Host ($logs | Select-Object -Last 1)

if ($backgroundErrorsJson -match 'NOGROUP' -or $logs -match 'NOGROUP') {
    throw 'Reset sonrasi workerlar hala NOGROUP hatasi uretiyor.'
}

$health = $healthJson | ConvertFrom-Json
if ($health.status -ne 'UP') {
    throw "Reset sonrasi health beklenen durumda degil: $($health.status)"
}

Write-Host 'Reset sonrasi worker recovery verification passed.'
