$ErrorActionPreference = 'Stop'

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [string]$WorkingDirectory = 'E:\ReactorRepository\cache-database'
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $File
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "Command failed: $File $Arguments`n$stderr"
    }

    return $stdout
}

$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$html = Invoke-Native -File $docker -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'

$checks = @(
    'table-layout:fixed',
    'white-space:normal',
    'font-size:.69rem',
    'padding:.52rem .58rem'
)

foreach ($check in $checks) {
    Write-Host ($check + '=' + $html.Contains($check))
    if (-not $html.Contains($check)) {
        throw "Dashboard HTML içinde beklenen CSS bulunamadi: $check"
    }
}

Write-Host 'Dashboard table CSS verification passed.'
