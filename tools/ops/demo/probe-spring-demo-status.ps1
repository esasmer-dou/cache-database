$ErrorActionPreference = "Stop"

$dockerExe = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$containerName = "cachedb-spring-load-demo"

function Invoke-Docker {
    param(
        [string]$Arguments,
        [int]$TimeoutMs = 300000
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $dockerExe
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    if (-not $process.WaitForExit($TimeoutMs)) {
        try { $process.Kill($true) } catch {}
        throw "Timeout running docker $Arguments"
    }

    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    if ($process.ExitCode -ne 0) {
        throw "docker failed: $Arguments`n$stdout`n$stderr"
    }
    return $stdout.Trim()
}

function Get-Url {
    param(
        [string]$Path,
        [int]$TimeoutMs = 300000
    )

    return Invoke-Docker "exec $containerName /usr/bin/curl -sS http://127.0.0.1:8090$Path" $TimeoutMs
}

$status = Get-Url "/demo-load/api/status" | ConvertFrom-Json
$action = Get-Url "/demo-load/api/action-status" | ConvertFrom-Json
$health = Get-Url "/cachedb-admin/api/health" | ConvertFrom-Json

Write-Output ("STATUS=" + ($status | ConvertTo-Json -Depth 8 -Compress))
Write-Output ("ACTION=" + ($action | ConvertTo-Json -Depth 6 -Compress))
Write-Output ("HEALTH=" + ($health | ConvertTo-Json -Depth 6 -Compress))
