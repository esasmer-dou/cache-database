$ErrorActionPreference = 'Stop'

function Start-DockerProcess {
    param([string]$Arguments)

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

    [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdout
        StdErr = $stderr
    }
}

function Invoke-Docker {
    param([string]$Arguments)

    $result = Start-DockerProcess $Arguments
    if ($result.ExitCode -ne 0) {
        throw "docker step failed: $Arguments`nSTDOUT:`n$($result.StdOut)`nSTDERR:`n$($result.StdErr)"
    }
    $result.StdOut
}

Write-Host '[1/7] Fresh Start'
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/fresh-reset' | Out-Null
Start-Sleep -Seconds 2

Write-Host '[2/7] Seed'
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/seed' | Out-Null
Start-Sleep -Seconds 3

Write-Host '[3/7] Start MEDIUM'
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/start?level=MEDIUM' | Out-Null
Start-Sleep -Seconds 10

Write-Host '[4/7] Health during load'
$healthDuring = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/health'
Write-Host $healthDuring

Write-Host '[5/7] Background errors during load'
$errorsDuring = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/background-errors'
Write-Host $errorsDuring

Write-Host '[6/7] Stop load'
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/stop' | Out-Null
Start-Sleep -Seconds 3

Write-Host '[7/7] Background errors after load'
$errorsAfter = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/background-errors'
Write-Host $errorsAfter

$logs = Invoke-Docker 'logs --tail 200 cachedb-demo-ui'

if ($logs -match 'ON CONFLICT DO UPDATE command cannot affect row a second time') {
    throw 'Postgres duplicate upsert error still present in container logs.'
}
if ($errorsDuring -match 'flushUpsertMultiRow' -or $errorsDuring -match 'ON CONFLICT DO UPDATE command cannot affect row a second time') {
    throw 'Background errors still report duplicate upsert during MEDIUM.'
}
if ($errorsAfter -match 'flushUpsertMultiRow' -or $errorsAfter -match 'ON CONFLICT DO UPDATE command cannot affect row a second time') {
    throw 'Background errors still report duplicate upsert after MEDIUM.'
}

Write-Host 'Medium duplicate-upsert verification passed.'
