$ErrorActionPreference = 'Stop'

function Start-DockerProcess {
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

    [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdout
        StdErr = $stderr
    }
}

$null = Start-DockerProcess 'rm -f cachedb-demo-ui'

$runArgs = @'
run -d --name cachedb-demo-ui -p 8080:8080 -p 8090:8090 -v //e/ReactorRepository/cache-database:/workspace -v //e/maven/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 bash /workspace/tools/build/run-demo-container.sh
'@
$run = Start-DockerProcess $runArgs
if ($run.ExitCode -ne 0) {
    throw "docker run failed:`nSTDOUT:`n$($run.StdOut)`nSTDERR:`n$($run.StdErr)"
}

Start-Sleep -Seconds 20

$logs = Start-DockerProcess 'logs --tail 120 cachedb-demo-ui'
if ($logs.ExitCode -ne 0) {
    throw "docker logs failed:`nSTDOUT:`n$($logs.StdOut)`nSTDERR:`n$($logs.StdErr)"
}

$fetch = Start-DockerProcess 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'
if ($fetch.ExitCode -ne 0) {
    $retry = Start-DockerProcess 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'
    if ($retry.ExitCode -eq 0) {
        $fetch = $retry
    } else {
        throw "dashboard fetch failed:`nSTDOUT:`n$($retry.StdOut)`nSTDERR:`n$($retry.StdErr)`nLOGS:`n$($logs.StdOut)"
    }
}

$html = $fetch.StdOut

$checks = @(
    'has_triage_prefix_tr=' + ($html -match 'Ana darboğaz:'),
    'has_signal_write_summary_tr=' + ($html -match 'Arka plan yazma kuyruğu, başarısız kayıtlar ve bekleyen sıkıştırma işlerini birlikte yorumlar\.'),
    'has_signal_runtime_summary_tr=' + ($html -match 'Aktif profil, baskı düzeyi ve önceliklendirme sonucu birlikte okunur\.'),
    'has_estimated_cost_tr=' + ($html -match 'Tahmini Maliyet')
)

$logs.StdOut
$checks
