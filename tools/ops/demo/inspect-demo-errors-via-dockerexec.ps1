$ErrorActionPreference = 'Stop'

function Invoke-DockerNative {
    param(
        [Parameter(Mandatory = $true)][string]$Arguments
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
        throw "Command failed: docker.exe $Arguments`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

function Get-Json {
    param(
        [Parameter(Mandatory = $true)][string]$Url
    )
    $raw = Invoke-DockerNative ('exec cachedb-demo-ui bash -lc "wget -qO- ' + $Url + '"')
    return ($raw | ConvertFrom-Json)
}

$status = Get-Json 'http://127.0.0.1:8090/api/status'
$backgroundErrors = Get-Json 'http://127.0.0.1:8080/api/background-errors'
$health = Get-Json 'http://127.0.0.1:8080/api/health'

Write-Host '[status]'
$status | ConvertTo-Json -Depth 10

Write-Host '[background-errors]'
$backgroundErrors | ConvertTo-Json -Depth 10

Write-Host '[health]'
$health | ConvertTo-Json -Depth 10
