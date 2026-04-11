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
    param([string]$Path,[int]$TimeoutMs = 120000)
    return Invoke-Docker "exec $containerName /usr/bin/curl -sS -o /tmp/cachedb.out -w %{http_code} http://127.0.0.1:8090$Path" $TimeoutMs
}

function Get-Json {
    param([string]$Path,[int]$TimeoutMs = 120000)
    [void](Get-Url $Path $TimeoutMs)
    return Invoke-Docker "exec $containerName /bin/cat /tmp/cachedb.out" $TimeoutMs
}

function Post-Url {
    param([string]$Path,[int]$TimeoutMs = 300000)
    return Invoke-Docker "exec $containerName /usr/bin/curl -sS -o /tmp/cachedb-post.out -w %{http_code} -X POST http://127.0.0.1:8090$Path" $TimeoutMs
}

function Wait-ForIdle {
    param([int]$TimeoutSeconds = 900)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $action = Get-Json "/demo-load/api/action-status" 120000 | ConvertFrom-Json
        if ($action.state -eq "IDLE" -or $action.state -eq "ERROR") {
            return $action
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for action idle"
}

$status = Get-Json "/demo-load/api/status" 120000 | ConvertFrom-Json
if (-not $status.snapshot.seeded) {
    [void](Post-Url "/demo-load/api/seed" 900000)
    $null = Wait-ForIdle 1800
}

[void](Post-Url "/demo-load/api/start?level=LOW" 300000)
$null = Wait-ForIdle 900
Start-Sleep -Seconds 5

$paths = @(
    "/cachedb-admin/api/triage",
    "/cachedb-admin/api/services",
    "/cachedb-admin/api/background-errors",
    "/cachedb-admin/api/alert-routing",
    "/cachedb-admin/api/incidents",
    "/cachedb-admin/api/schema/status"
)

1..5 | ForEach-Object {
    foreach ($path in $paths) {
        $code = Get-Url $path 45000
        [pscustomobject]@{
            Tick = $_
            Path = $path
            Code = $code
        } | ConvertTo-Json -Compress
    }
    Start-Sleep -Seconds 2
}
