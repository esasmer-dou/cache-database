$ErrorActionPreference = "Stop"

$dockerExe = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

function Invoke-ExternalProcess {
    param(
        [string]$FilePath,
        [string]$Arguments,
        [string]$WorkingDirectory,
        [int]$TimeoutMs = 120000
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
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

$demo = Invoke-ExternalProcess `
        -FilePath "C:\Windows\System32\curl.exe" `
        -Arguments "-sS http://127.0.0.1:8090/demo-load" `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 30000

$admin = Invoke-ExternalProcess `
        -FilePath "C:\Windows\System32\curl.exe" `
        -Arguments "-sS http://127.0.0.1:8090/cachedb-admin/dashboard-v3?lang=tr" `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 30000

$logs = Invoke-ExternalProcess `
        -FilePath $dockerExe `
        -Arguments "logs --tail 120 cachedb-spring-load-demo" `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 120000

$containerDemo = Invoke-ExternalProcess `
        -FilePath $dockerExe `
        -Arguments 'exec cachedb-spring-load-demo bash -lc "exec 3<>/dev/tcp/127.0.0.1/8090; printf \"GET /demo-load HTTP/1.0\r\nHost: localhost\r\n\r\n\" >&3; cat <&3 | head -c 1200"' `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 120000

$containerAdmin = Invoke-ExternalProcess `
        -FilePath $dockerExe `
        -Arguments 'exec cachedb-spring-load-demo bash -lc "exec 3<>/dev/tcp/127.0.0.1/8090; printf \"GET /cachedb-admin/dashboard-v3?lang=tr HTTP/1.0\r\nHost: localhost\r\n\r\n\" >&3; cat <&3 | head -c 1200"' `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 120000

[pscustomobject]@{
    demoCurlExit = $demo.ExitCode
    adminCurlExit = $admin.ExitCode
    demoContainsWorkspace = $demo.StdOut -match "Load Scenario Workspace"
    demoContainsSeedButton = $demo.StdOut -match "Seed Demo Data"
    adminContainsDashboard = $admin.StdOut -match "CacheDB"
    adminContainsSignals = $admin.StdOut -match "Ana Gosterge|Ana Gösterge|Primary Signals"
    containerDemoExit = $containerDemo.ExitCode
    containerAdminExit = $containerAdmin.ExitCode
    containerDemoContainsWorkspace = $containerDemo.StdOut -match "Load Scenario Workspace|CacheDB Demo Load UI"
    containerDemoContainsSeedButton = $containerDemo.StdOut -match "Seed Demo Data|Start High|Start Medium|Start Low"
    containerAdminContainsDashboard = $containerAdmin.StdOut -match "CacheDB Operasyon Konsolu|CacheDB Operations Console"
    containerAdminContainsSignals = $containerAdmin.StdOut -match "Ana Gosterge|Ana Gösterge|Primary Signals|Sinyal Panosu"
    logTail = $logs.StdOut
    logErr = $logs.StdErr
    containerDemoHead = $containerDemo.StdOut
    containerAdminHead = $containerAdmin.StdOut
}
