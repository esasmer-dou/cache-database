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

function Get-Url([string]$Path,[int]$TimeoutMs=300000) {
    return Invoke-Docker "exec $containerName /usr/bin/curl -sS http://127.0.0.1:8090$Path" $TimeoutMs
}

function Post-Url([string]$Path,[int]$TimeoutMs=300000) {
    return Invoke-Docker "exec $containerName /usr/bin/curl -sS -o /tmp/cachedb-post.out -w %{http_code} -X POST http://127.0.0.1:8090$Path" $TimeoutMs
}

function Wait-ForIdle([int]$TimeoutSeconds=600) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while((Get-Date) -lt $deadline) {
        $action = (Get-Url "/demo-load/api/action-status" 120000 | ConvertFrom-Json)
        if ($action.state -eq "IDLE" -or $action.state -eq "ERROR") {
            return $action
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for action idle"
}

function Wait-ForDemoReady([int]$TimeoutSeconds=180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while((Get-Date) -lt $deadline) {
        try {
            $page = Get-Url "/demo-load" 30000
            if ($page -and $page.Contains("Load Scenario Workspace")) {
                return
            }
        } catch {
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for demo UI readiness"
}

Wait-ForDemoReady
Write-Output ("STOP=" + (Post-Url "/demo-load/api/stop"))
Write-Output ("SEED=" + (Post-Url "/demo-load/api/seed" 600000))
$seedAction = Wait-ForIdle 1800
Write-Output ("SEED_ACTION=" + ($seedAction | ConvertTo-Json -Compress))
Write-Output ("START=" + (Post-Url "/demo-load/api/start?level=HIGH" 600000))
$startAction = Wait-ForIdle 1800
Write-Output ("START_ACTION=" + ($startAction | ConvertTo-Json -Compress))
Write-Output ("HEALTH=" + (Get-Url "/cachedb-admin/api/health"))
Write-Output ("PERFORMANCE=" + (Get-Url "/cachedb-admin/api/performance"))
Write-Output ("SHAPE=" + (Get-Url "/demo-load/api/scenario-shapes"))
