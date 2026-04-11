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
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "Command failed: $File $Arguments`nSTDERR:`n$stderr`nSTDOUT:`n$stdout"
    }

    return $stdout
}

$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$containerName = 'cachedb-spring-load-demo'
$httpProbe = @'
exec 3<>/dev/tcp/127.0.0.1/8090
printf "POST /cachedb-admin/api/telemetry/reset HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" >&3
cat <&3
'@
$escapedProbe = $httpProbe.Replace('"', '\"').Replace("`r", '').Replace("`n", '; ')

Write-Host '[1/3] Telemetry reset raw HTTP response'
$raw = Invoke-Native -File $docker -Arguments "exec $containerName bash -lc ""$escapedProbe"""
Write-Host $raw

$parts = $raw -split "`r?`n`r?`n", 2
if ($parts.Count -lt 2) {
    throw "Unexpected HTTP response:`n$raw"
}

$headers = $parts[0]
$body = $parts[1].Trim()
$statusLine = ($headers -split "`r?`n")[0]

if ($statusLine -notmatch 'HTTP/\d+\.\d+\s+(\d{3})') {
    throw "Could not parse status line: $statusLine"
}

$statusCode = [int]$Matches[1]

Write-Host '[2/3] Parsed status'
Write-Host ("status={0}" -f $statusCode)

if ($statusCode -ne 200) {
    throw "Telemetry reset returned HTTP $statusCode`n$body"
}

Write-Host '[3/3] JSON parse'
$parsed = $body | ConvertFrom-Json
Write-Host ("diagnostics={0} incidents={1} history={2} routeHistory={3} performanceOps={4} resetAt={5}" -f
    $parsed.diagnosticsEntriesCleared,
    $parsed.incidentEntriesCleared,
    $parsed.monitoringHistorySamplesCleared,
    $parsed.alertRouteHistorySamplesCleared,
    $parsed.storagePerformanceOperationsCleared,
    $parsed.resetAt)

Write-Host 'Telemetry reset verification passed.'
