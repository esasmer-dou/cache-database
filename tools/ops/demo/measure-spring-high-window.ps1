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

function Invoke-ContainerCurl {
    param(
        [string]$Path,
        [string]$Method = "GET",
        [int]$TimeoutMs = 300000
    )

    $args = if ($Method -eq "POST") {
        "exec $containerName /usr/bin/curl -sS -o /tmp/cachedb-post.out -w %{http_code} -X POST http://127.0.0.1:8090$Path"
    } else {
        "exec $containerName /usr/bin/curl -sS http://127.0.0.1:8090$Path"
    }
    return Invoke-Docker $args $TimeoutMs
}

function Post-Url {
    param(
        [string]$Path,
        [int]$TimeoutMs = 300000
    )

    Invoke-ContainerCurl -Path $Path -Method "POST" -TimeoutMs $TimeoutMs | Out-Host
}

function Get-Url {
    param(
        [string]$Path,
        [int]$TimeoutMs = 300000
    )

    return Invoke-ContainerCurl -Path $Path -Method "GET" -TimeoutMs $TimeoutMs
}

function Get-DemoStatus {
    return (Get-Url "/demo-load/api/status" 1800000 | ConvertFrom-Json)
}

function Get-ActionStatus {
    return (Get-Url "/demo-load/api/action-status" 1800000 | ConvertFrom-Json)
}

function Wait-ForActionState {
    param(
        [scriptblock]$Condition,
        [string]$Description,
        [int]$TimeoutSeconds = 900
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = Get-ActionStatus
        if ($Condition.InvokeReturnAsIs($status)) {
            return $status
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for $Description"
}

function Wait-ForDemoReady {
    param([int]$TimeoutSeconds = 180)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
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
$statusBefore = Get-DemoStatus
$snapshotBefore = $statusBefore.snapshot

if (-not ($snapshotBefore.running -and $snapshotBefore.activeScenario -eq "HIGH")) {
    if ($snapshotBefore.running) {
        Post-Url "/demo-load/api/stop" 300000
        $null = Wait-ForActionState -Description "stop completion" -Condition {
            param($status)
            $status.state -eq "IDLE"
        }
    }

    if (-not $snapshotBefore.seeded) {
        Post-Url "/demo-load/api/seed" 600000
        $null = Wait-ForActionState -Description "seed completion" -Condition {
            param($status)
            $status.state -eq "IDLE"
        }
    }
}

Post-Url "/cachedb-admin/api/performance/reset" 300000
Post-Url "/demo-load/api/scenario-shapes/reset" 300000

if (-not ($snapshotBefore.running -and $snapshotBefore.activeScenario -eq "HIGH")) {
    Post-Url "/demo-load/api/start?level=HIGH" 300000
    $null = Wait-ForActionState -Description "HIGH start completion" -Condition {
        param($status)
        $status.state -eq "IDLE"
    }
}

Start-Sleep -Seconds 45

$actionStatus = Get-ActionStatus | ConvertTo-Json -Depth 4 -Compress
$demoStatus = Get-DemoStatus | ConvertTo-Json -Depth 6 -Compress
$health = Get-Url "/cachedb-admin/api/health"
$performance = Get-Url "/cachedb-admin/api/performance"
$shape = Get-Url "/demo-load/api/scenario-shapes"

Write-Output "STATUS=$demoStatus"
Write-Output "ACTION=$actionStatus"
Write-Output "HEALTH=$health"
Write-Output "PERFORMANCE=$performance"
Write-Output "SHAPE=$shape"
