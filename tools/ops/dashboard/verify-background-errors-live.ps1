$ErrorActionPreference = 'Stop'

$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'

function Invoke-DockerExec {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Arguments
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $docker
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

$apiJson = Invoke-DockerExec 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/api/background-errors"'
$html = Invoke-DockerExec 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'

$result = [pscustomobject]@{
    hasApiItems = $apiJson -match '"items"'
    hasHtmlSection = $html -match 'background-errors-section'
    hasHtmlRenderer = $html -match 'renderBackgroundErrors'
    hasHealthReason = $html -match 'healthReasonTitle'
    hasSectionTitle = $html -match 'Arka Plan Hataları'
    hasSignalLamp = $html -match 'Arka Plan Hata Lambası'
}

$result | ConvertTo-Json -Depth 3
