$ErrorActionPreference = 'Stop'

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'
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
    throw "dashboard html fetch failed: $stderr`n$stdout"
}

$content = $stdout
$scriptIndex = $stdout.IndexOf('<script>')
if ($scriptIndex -ge 0) {
    $content = $stdout.Substring(0, $scriptIndex)
}

[pscustomobject]@{
    hasNavLink = $content.Contains('href="#runtime-profile-churn-section"')
    hasTargetId = $content.Contains('id="runtime-profile-churn-section"')
    hasTitle = $content.Contains('Profil Geçiş Geçmişi')
} | ConvertTo-Json -Depth 3
