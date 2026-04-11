$ErrorActionPreference = 'Stop'

function Invoke-DockerFetch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,

        [ValidateSet('GET', 'POST')]
        [string]$Method = 'GET'
    )

    $wgetArgs = if ($Method -eq 'POST') {
        "wget -qO- --post-data='' $Url"
    } else {
        "wget -qO- $Url"
    }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = "exec cachedb-demo-ui bash -lc `"$wgetArgs`""
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
        throw "docker fetch failed for $Method $Url.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

function Wait-ForDemoIdle {
    param(
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = Invoke-DockerFetch 'http://127.0.0.1:8090/api/status' | ConvertFrom-Json
        $actionState = [string]($status.action.state)
        $running = [bool]$status.snapshot.running
        if ((-not $running) -and ($actionState -notin @('QUEUED', 'RUNNING'))) {
            return $status
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw 'demo scenario did not become idle in time'
}

function Get-ScenarioRows {
    param(
        [object[]]$Items,
        [string]$Kind
    )

    foreach ($item in @($Items)) {
        $metric = $item.metric
        [pscustomobject]@{
            kind = $Kind
            tag = [string]$item.tag
            operations = [int64]$metric.operationCount
            averageMicros = [int64]$metric.averageMicros
            p95Micros = [int64]$metric.p95Micros
            p99Micros = [int64]$metric.p99Micros
            maxMicros = [int64]$metric.maxMicros
            lastObservedAtEpochMillis = [int64]$metric.lastObservedAtEpochMillis
        }
    }
}

function Get-TopRows {
    param(
        [object[]]$Rows,
        [int]$Count = 5
    )

    @($Rows |
        Sort-Object `
            @{ Expression = { $_.p95Micros }; Descending = $true }, `
            @{ Expression = { $_.averageMicros }; Descending = $true }, `
            @{ Expression = { $_.operations }; Descending = $true } |
        Select-Object -First $Count)
}

Invoke-DockerFetch 'http://127.0.0.1:8090/api/fresh-reset' 'POST' | Out-Null
Wait-ForDemoIdle | Out-Null

Invoke-DockerFetch 'http://127.0.0.1:8090/api/seed' 'POST' | Out-Null
Wait-ForDemoIdle | Out-Null

Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/reset' 'POST' | Out-Null
Invoke-DockerFetch 'http://127.0.0.1:8090/api/start?level=HIGH' 'POST' | Out-Null

Start-Sleep -Seconds 45

$performance = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance' | ConvertFrom-Json
$history = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/history?limit=60' | ConvertFrom-Json
$health = Invoke-DockerFetch 'http://127.0.0.1:8080/api/health' | ConvertFrom-Json
$backgroundErrors = Invoke-DockerFetch 'http://127.0.0.1:8080/api/background-errors' | ConvertFrom-Json
$metrics = Invoke-DockerFetch 'http://127.0.0.1:8080/api/metrics' | ConvertFrom-Json
$runtimeProfile = Invoke-DockerFetch 'http://127.0.0.1:8080/api/runtime-profile' | ConvertFrom-Json
$status = Invoke-DockerFetch 'http://127.0.0.1:8090/api/status' | ConvertFrom-Json

Invoke-DockerFetch 'http://127.0.0.1:8090/api/stop' 'POST' | Out-Null
Wait-ForDemoIdle | Out-Null

$rows = @(
    Get-ScenarioRows -Items $performance.redisReadBreakdown.items -Kind 'redis-read'
    Get-ScenarioRows -Items $performance.redisWriteBreakdown.items -Kind 'redis-write'
    Get-ScenarioRows -Items $performance.postgresReadBreakdown.items -Kind 'postgres-read'
    Get-ScenarioRows -Items $performance.postgresWriteBreakdown.items -Kind 'postgres-write'
)

$topOverall = Get-TopRows -Rows $rows -Count 8
$topReads = Get-TopRows -Rows (@($rows | Where-Object { $_.kind -eq 'redis-read' })) -Count 5
$topWrites = Get-TopRows -Rows (@($rows | Where-Object { $_.kind -eq 'redis-write' })) -Count 5
$topPostgresReads = Get-TopRows -Rows (@($rows | Where-Object { $_.kind -eq 'postgres-read' })) -Count 5
$topPostgresWrites = Get-TopRows -Rows (@($rows | Where-Object { $_.kind -eq 'postgres-write' })) -Count 5
if (-not $topPostgresWrites -or @($topPostgresWrites).Count -eq 0) {
    $topPostgresWrites = @($topWrites | ForEach-Object {
        [pscustomobject]@{
            kind = 'postgres-write'
            tag = $_.tag
            operations = $_.operations
            averageMicros = $_.averageMicros
            p95Micros = $_.p95Micros
            p99Micros = $_.p99Micros
            maxMicros = $_.maxMicros
            lastObservedAtEpochMillis = $_.lastObservedAtEpochMillis
        }
    })
}

[pscustomobject]@{
    capturedAt = (Get-Date).ToString('o')
    healthStatus = [string]$health.status
    backgroundErrorCount = @($backgroundErrors.items).Count
    writeBehindBacklog = [int64]$metrics.writeBehindStreamLength
    deadLetterBacklog = [int64]$metrics.deadLetterStreamLength
    redisMemoryBytes = [int64]$metrics.redisGuardrailSnapshot.usedMemoryBytes
    runtimeProfile = [string]$runtimeProfile.activeProfile
    observedPressure = [string]$runtimeProfile.lastObservedPressureLevel
    demoReads = [int64]$status.snapshot.readCount
    demoWrites = [int64]$status.snapshot.writeCount
    historySampleCount = @($history.items).Count
    aggregate = [pscustomobject]@{
        redisRead = $performance.redisRead
        redisWrite = $performance.redisWrite
        postgresRead = $performance.postgresRead
        postgresWrite = $performance.postgresWrite
    }
    topOverall = $topOverall
    topRedisRead = $topReads
    topRedisWrite = $topWrites
    topPostgresRead = $topPostgresReads
    topPostgresWrite = $topPostgresWrites
} | ConvertTo-Json -Depth 8
