$ErrorActionPreference = "Stop"

$javaHome = "C:\java64\Semeru\jdk-21.0.2.13-openj9"
$mavenHome = "C:\apache-maven-3.9.6"
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

function New-ProcessStartInfo {
    param(
        [string]$FilePath,
        [string]$Arguments,
        [string]$WorkingDirectory
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.Environment["JAVA_HOME"] = $javaHome
    $psi.Environment["Path"] = "$javaHome\bin;$mavenHome\bin;" +
            [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path", "User")
    return $psi
}

function Invoke-ShortProcess {
    param(
        [string]$FilePath,
        [string]$Arguments,
        [int]$TimeoutMs = 120000
    )

    $psi = New-ProcessStartInfo -FilePath $FilePath -Arguments $Arguments -WorkingDirectory $repoRoot
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

$compile = Invoke-ShortProcess `
        -FilePath "C:\Windows\System32\cmd.exe" `
        -Arguments '/c "C:\apache-maven-3.9.6\bin\mvn.cmd" -pl cachedb-examples -am -DskipTests install' `
        -TimeoutMs 1200000

if ($compile.ExitCode -ne 0) {
    throw "Compile failed.`nSTDOUT:`n$($compile.StdOut)`nSTDERR:`n$($compile.StdErr)"
}

$runPsi = New-ProcessStartInfo `
        -FilePath "C:\Windows\System32\cmd.exe" `
        -Arguments '/c "C:\apache-maven-3.9.6\bin\mvn.cmd" -f cachedb-examples/pom.xml org.springframework.boot:spring-boot-maven-plugin:3.3.5:run -Dspring-boot.run.mainClass=com.reactor.cachedb.examples.demo.boot.DemoSpringBootLoadApplication' `
        -WorkingDirectory $repoRoot

$runProcess = [System.Diagnostics.Process]::new()
$runProcess.StartInfo = $runPsi
[void]$runProcess.Start()
$runStdOutTask = $runProcess.StandardOutput.ReadToEndAsync()
$runStdErrTask = $runProcess.StandardError.ReadToEndAsync()

$wasRunningBeforeProbe = $false
Start-Sleep -Seconds 40
$wasRunningBeforeProbe = -not $runProcess.HasExited

$demoResponse = Invoke-ShortProcess `
        -FilePath "C:\Windows\System32\curl.exe" `
        -Arguments '-sS http://127.0.0.1:8090/demo-load' `
        -TimeoutMs 30000

$adminResponse = Invoke-ShortProcess `
        -FilePath "C:\Windows\System32\curl.exe" `
        -Arguments '-sS http://127.0.0.1:8090/cachedb-admin?lang=tr' `
        -TimeoutMs 30000

try {
    if (-not $runProcess.HasExited) {
        $runProcess.Kill($true)
    }
} catch {
}

$null = $runProcess.WaitForExit(15000)

[pscustomobject]@{
    compileExit = $compile.ExitCode
    springBootExit = $runProcess.ExitCode
    springBootWasRunningBeforeProbe = $wasRunningBeforeProbe
    demoCurlExit = $demoResponse.ExitCode
    adminCurlExit = $adminResponse.ExitCode
    demoContainsWorkspace = $demoResponse.StdOut -match "Load Scenario Workspace"
    demoContainsSeedButton = $demoResponse.StdOut -match "Seed Demo Data"
    adminContainsDashboard = $adminResponse.StdOut -match "CacheDB"
    adminContainsSignals = $adminResponse.StdOut -match "Ana Gosterge|Ana Gösterge|Primary Signals"
    springBootStdOutTail = (($runStdOutTask.Result -split "`r?`n") | Select-Object -Last 60) -join "`n"
    springBootStdErrTail = (($runStdErrTask.Result -split "`r?`n") | Select-Object -Last 60) -join "`n"
}
