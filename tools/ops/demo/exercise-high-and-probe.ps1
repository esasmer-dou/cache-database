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
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    [pscustomobject]@{ ExitCode = $p.ExitCode; StdOut = $out; StdErr = $err }
}

function Invoke-Docker($args) {
    $r = Start-DockerProcess $args
    if ($r.ExitCode -ne 0) {
        throw "docker step failed: $args`nSTDOUT:`n$($r.StdOut)`nSTDERR:`n$($r.StdErr)"
    }
    $r.StdOut
}

Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/fresh-reset' | Out-Null
Start-Sleep -Seconds 2
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/seed' | Out-Null
Start-Sleep -Seconds 3
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/start?level=HIGH' | Out-Null
Start-Sleep -Seconds 8
$metricsDuring = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/metrics'
$triageDuring = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/triage'
$healthDuring = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/health'
Invoke-Docker 'exec cachedb-demo-ui wget -qO- --post-data="" http://127.0.0.1:8090/api/stop' | Out-Null
Start-Sleep -Seconds 2
$metricsAfter = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/metrics'
$triageAfter = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/triage'
$healthAfter = Invoke-Docker 'exec cachedb-demo-ui wget -qO- http://127.0.0.1:8080/api/health'

"=== HEALTH DURING ==="
$healthDuring
"=== TRIAGE DURING ==="
$triageDuring
"=== METRICS DURING ==="
$metricsDuring
"=== HEALTH AFTER ==="
$healthAfter
"=== TRIAGE AFTER ==="
$triageAfter
"=== METRICS AFTER ==="
$metricsAfter
