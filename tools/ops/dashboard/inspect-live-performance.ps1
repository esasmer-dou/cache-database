$ErrorActionPreference = 'Stop'

function Invoke-DockerFetch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = "exec cachedb-demo-ui bash -lc `"wget -qO- $Url`""
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
        throw "docker fetch failed for $Url.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

function Get-Direction {
    param(
        [double[]]$Values
    )

    if ($null -eq $Values -or $Values.Count -lt 2) {
        return 'flat'
    }

    $current = [double]$Values[-1]
    $previous = [double]$Values[-2]
    $delta = $current - $previous

    if ($delta -gt 0.0001) { return 'rising' }
    if ($delta -lt -0.0001) { return 'falling' }
    return 'flat'
}

$performance = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance' | ConvertFrom-Json
$history = Invoke-DockerFetch 'http://127.0.0.1:8080/api/performance/history?limit=20' | ConvertFrom-Json
$health = Invoke-DockerFetch 'http://127.0.0.1:8080/api/health' | ConvertFrom-Json
$backgroundErrors = Invoke-DockerFetch 'http://127.0.0.1:8080/api/background-errors' | ConvertFrom-Json
$metrics = Invoke-DockerFetch 'http://127.0.0.1:8080/api/metrics' | ConvertFrom-Json
$runtimeProfile = Invoke-DockerFetch 'http://127.0.0.1:8080/api/runtime-profile' | ConvertFrom-Json

$redisReadAvgSeries = @($history.items | ForEach-Object { [double]$_.redisReadAverageMicros })
$redisWriteAvgSeries = @($history.items | ForEach-Object { [double]$_.redisWriteAverageMicros })
$postgresReadAvgSeries = @($history.items | ForEach-Object { [double]$_.postgresReadAverageMicros })
$postgresWriteAvgSeries = @($history.items | ForEach-Object { [double]$_.postgresWriteAverageMicros })

$redisReadOpsSeries = @($history.items | ForEach-Object { [double]$_.redisReadOperationCount })
$redisWriteOpsSeries = @($history.items | ForEach-Object { [double]$_.redisWriteOperationCount })
$postgresReadOpsSeries = @($history.items | ForEach-Object { [double]$_.postgresReadOperationCount })
$postgresWriteOpsSeries = @($history.items | ForEach-Object { [double]$_.postgresWriteOperationCount })

[pscustomobject]@{
    capturedAt = (Get-Date).ToString('o')
    healthStatus = [string]$health.status
    healthIssues = @($health.issues)
    backgroundErrorCount = @($backgroundErrors.items).Count
    writeBehindBacklog = [int64]$metrics.writeBehindStreamLength
    deadLetterBacklog = [int64]$metrics.deadLetterStreamLength
    redisMemoryBytes = [int64]$metrics.redisGuardrailSnapshot.usedMemoryBytes
    runtimeProfile = [string]$runtimeProfile.activeProfile
    observedPressure = [string]$runtimeProfile.lastObservedPressureLevel
    historySamples = @($history.items).Count
    redisRead = [pscustomobject]@{
        operations = [int64]$performance.redisRead.operationCount
        averageMicros = [int64]$performance.redisRead.averageMicros
        p95Micros = [int64]$performance.redisRead.p95Micros
        p99Micros = [int64]$performance.redisRead.p99Micros
        maxMicros = [int64]$performance.redisRead.maxMicros
        latencyDirection = Get-Direction $redisReadAvgSeries
        volumeDirection = Get-Direction $redisReadOpsSeries
    }
    redisWrite = [pscustomobject]@{
        operations = [int64]$performance.redisWrite.operationCount
        averageMicros = [int64]$performance.redisWrite.averageMicros
        p95Micros = [int64]$performance.redisWrite.p95Micros
        p99Micros = [int64]$performance.redisWrite.p99Micros
        maxMicros = [int64]$performance.redisWrite.maxMicros
        latencyDirection = Get-Direction $redisWriteAvgSeries
        volumeDirection = Get-Direction $redisWriteOpsSeries
    }
    postgresRead = [pscustomobject]@{
        operations = [int64]$performance.postgresRead.operationCount
        averageMicros = [int64]$performance.postgresRead.averageMicros
        p95Micros = [int64]$performance.postgresRead.p95Micros
        p99Micros = [int64]$performance.postgresRead.p99Micros
        maxMicros = [int64]$performance.postgresRead.maxMicros
        latencyDirection = Get-Direction $postgresReadAvgSeries
        volumeDirection = Get-Direction $postgresReadOpsSeries
    }
    postgresWrite = [pscustomobject]@{
        operations = [int64]$performance.postgresWrite.operationCount
        averageMicros = [int64]$performance.postgresWrite.averageMicros
        p95Micros = [int64]$performance.postgresWrite.p95Micros
        p99Micros = [int64]$performance.postgresWrite.p99Micros
        maxMicros = [int64]$performance.postgresWrite.maxMicros
        latencyDirection = Get-Direction $postgresWriteAvgSeries
        volumeDirection = Get-Direction $postgresWriteOpsSeries
    }
} | ConvertTo-Json -Depth 6
