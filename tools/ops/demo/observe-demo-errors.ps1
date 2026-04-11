param(
    [ValidateSet("LOW", "MEDIUM", "HIGH")]
    [string]$Level = "MEDIUM",
    [int]$DurationSeconds = 20
)

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

function Get-DemoStatus {
    return (Get-Url "/demo-load/api/status" 1800000 | ConvertFrom-Json)
}

function Get-ActionStatus {
    return (Get-Url "/demo-load/api/action-status" 1800000 | ConvertFrom-Json)
}

function Wait-ForIdle([int]$TimeoutSeconds=1800) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while((Get-Date) -lt $deadline) {
        $action = Get-ActionStatus
        if ($action.state -eq "IDLE" -or $action.state -eq "ERROR") {
            return $action
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for action idle"
}

$initial = Get-DemoStatus
if ($initial.snapshot.running) {
    Post-Url "/demo-load/api/stop" | Out-Null
    $null = Wait-ForIdle 300
}
if (-not $initial.snapshot.seeded) {
    Post-Url "/demo-load/api/seed" 600000 | Out-Null
    $null = Wait-ForIdle 1800
}

$before = Get-DemoStatus
Post-Url "/demo-load/api/start?level=$Level" 600000 | Out-Null
$startAction = Wait-ForIdle 1800
$started = Get-DemoStatus

Start-Sleep -Seconds $DurationSeconds

$after = Get-DemoStatus
$health = Get-Url "/cachedb-admin/api/health"

Write-Output ("BEFORE=" + ($before | ConvertTo-Json -Depth 8 -Compress))
Write-Output ("START_ACTION=" + ($startAction | ConvertTo-Json -Depth 6 -Compress))
Write-Output ("STARTED=" + ($started | ConvertTo-Json -Depth 8 -Compress))
Write-Output ("AFTER=" + ($after | ConvertTo-Json -Depth 8 -Compress))
Write-Output ("HEALTH=" + $health)
