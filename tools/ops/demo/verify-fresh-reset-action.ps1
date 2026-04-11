$ErrorActionPreference = "Stop"

$dockerExe = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

function Invoke-ExternalProcess {
    param(
        [string]$FilePath,
        [string]$Arguments,
        [int]$TimeoutMs = 120000
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
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
        throw "Timeout running: $FilePath $Arguments"
    }
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdoutTask.Result
        StdErr = $stderrTask.Result
    }
}

function Invoke-DockerJson {
    param(
        [string]$Command,
        [int]$TimeoutMs = 120000
    )

    $result = Invoke-ExternalProcess `
            -FilePath $dockerExe `
            -Arguments "exec cachedb-spring-load-demo bash -lc ""$Command""" `
            -TimeoutMs $TimeoutMs
    if ($result.ExitCode -ne 0) {
        throw "Docker exec failed: $($result.StdOut)`n$($result.StdErr)"
    }
    return $result.StdOut
}

$statusBefore = Invoke-DockerJson "curl -sS http://127.0.0.1:8090/demo-load/api/status"
$ack = Invoke-DockerJson "curl -sS -X POST http://127.0.0.1:8090/demo-load/api/fresh-reset"
Start-Sleep -Seconds 2
$statusAfter2s = Invoke-DockerJson "curl -sS http://127.0.0.1:8090/demo-load/api/status"
Start-Sleep -Seconds 5
$statusAfter7s = Invoke-DockerJson "curl -sS http://127.0.0.1:8090/demo-load/api/status"
Start-Sleep -Seconds 10
$statusAfter17s = Invoke-DockerJson "curl -sS http://127.0.0.1:8090/demo-load/api/status"

[pscustomobject]@{
    statusBefore = $statusBefore
    ack = $ack
    statusAfter2s = $statusAfter2s
    statusAfter7s = $statusAfter7s
    statusAfter17s = $statusAfter17s
}
