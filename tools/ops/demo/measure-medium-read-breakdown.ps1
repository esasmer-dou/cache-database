param(
    [ValidateSet("LOW", "MEDIUM", "HIGH")]
    [string]$Level = "MEDIUM",
    [int]$ReadersOverride = -1,
    [int]$WritersOverride = -1,
    [int]$ObservationSeconds = 45
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

    $statusCode = Invoke-ContainerCurl -Path $Path -Method "POST" -TimeoutMs $TimeoutMs
    if ($statusCode -ne "200") {
        throw "Unexpected HTTP status for POST $Path : $statusCode"
    }
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

if ($snapshotBefore.running) {
    Post-Url "/demo-load/api/stop" 300000
    $null = Wait-ForActionState -Description "stop completion" -Condition {
        param($status)
        $status.state -eq "IDLE"
    }
}

if (-not $snapshotBefore.seeded) {
    Post-Url "/demo-load/api/seed" 1800000
    $null = Wait-ForActionState -Description "seed completion" -Condition {
        param($status)
        $status.state -eq "IDLE"
    }
}

Post-Url "/cachedb-admin/api/performance/reset" 300000
Post-Url "/demo-load/api/scenario-shapes/reset" 300000

$startPath = "/demo-load/api/start?level=$Level"
if ($ReadersOverride -ge 0) {
    $startPath += "&readers=$ReadersOverride"
}
if ($WritersOverride -ge 0) {
    $startPath += "&writers=$WritersOverride"
}

Post-Url $startPath 300000
$null = Wait-ForActionState -Description "$Level start completion" -Condition {
    param($status)
    $status.state -eq "IDLE"
}

Start-Sleep -Seconds $ObservationSeconds

$demoStatus = Get-DemoStatus
$health = Get-Url "/cachedb-admin/api/health" | ConvertFrom-Json
$performance = Get-Url "/cachedb-admin/api/performance" | ConvertFrom-Json
$shape = Get-Url "/demo-load/api/scenario-shapes" | ConvertFrom-Json

$topRedisReads = $performance.redisReadBreakdown.items |
        Sort-Object p95Micros -Descending |
        Select-Object -First 6

Write-Output ("SCENARIO=" + $demoStatus.snapshot.activeScenario)
Write-Output ("RUNNING=" + $demoStatus.snapshot.running)
Write-Output ("READS=" + $demoStatus.snapshot.readCount)
Write-Output ("WRITES=" + $demoStatus.snapshot.writeCount)
Write-Output ("ERRORS=" + $demoStatus.snapshot.errorCount)
Write-Output ("HEALTH=" + ($health | ConvertTo-Json -Depth 6 -Compress))
Write-Output ("REDIS_READ=" + ($performance.redisRead | ConvertTo-Json -Depth 4 -Compress))
Write-Output ("TOP_REDIS_READS=" + ($topRedisReads | ConvertTo-Json -Depth 5 -Compress))
Write-Output ("SHAPES=" + ($shape.items | ConvertTo-Json -Depth 5 -Compress))
