$ErrorActionPreference = 'Stop'

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/api/diagnostics?limit=5"'
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
    throw "diagnostics fetch failed: $stderr`n$stdout"
}

$stdout | Set-Content -Path 'E:\ReactorRepository\cache-database\tools\data\_diagnostics_live.json'
$json = $stdout | ConvertFrom-Json
$items = @($json.items)

[pscustomobject]@{
    count = $items.Count
    firstSource = if ($items.Count -gt 0) { $items[0].source } else { $null }
    firstNote = if ($items.Count -gt 0) { $items[0].note } else { $null }
    hasFields = if ($items.Count -gt 0) { $null -ne $items[0].fields } else { $false }
} | ConvertTo-Json -Depth 4
