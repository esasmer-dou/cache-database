param(
    [switch]$CleanBuild
)

$ErrorActionPreference = "Stop"

$javaHome = "C:\java64\Semeru\jdk-21.0.2.13-openj9"
$mavenHome = "C:\apache-maven-3.9.6"
$dockerExe = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$imageName = "cachedb-spring-load-demo:local"
$containerName = "cachedb-spring-load-demo"
$dockerfilePath = Join-Path $repoRoot "tools\tmp\spring-boot-load-demo.Dockerfile"
$logDir = Join-Path $repoRoot "tools\tmp"
$mavenBuildLog = Join-Path $logDir "mvn-demo-container-build.log"
$mavenRepackageLog = Join-Path $logDir "mvn-demo-container-repackage.log"
$dockerBuildLog = Join-Path $logDir "docker-demo-container-build.log"
$dockerRunLog = Join-Path $logDir "docker-demo-container-run.log"

function Invoke-ExternalProcess {
    param(
        [string]$FilePath,
        [string]$Arguments,
        [string]$WorkingDirectory,
        [int]$TimeoutMs = 1200000,
        [string]$StdOutPath,
        [string]$StdErrPath
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
    $psi.Environment["SystemRoot"] = "C:\WINDOWS"
    $psi.Environment["ComSpec"] = "C:\WINDOWS\System32\cmd.exe"
    $psi.Environment["Path"] = "$javaHome\bin;$mavenHome\bin;" +
            [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path", "User")
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutMs)) {
        try { $process.Kill($true) } catch {}
        throw "Timeout running: $FilePath $Arguments"
    }
    if ($StdOutPath) {
        [System.IO.File]::WriteAllText($StdOutPath, $stdoutTask.Result)
    }
    if ($StdErrPath) {
        [System.IO.File]::WriteAllText($StdErrPath, $stderrTask.Result)
    }
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdoutTask.Result
        StdErr = $stderrTask.Result
    }
}

$buildGoal = if ($CleanBuild) { "clean install" } else { "install" }

$install = Invoke-ExternalProcess `
        -FilePath "C:\Windows\System32\cmd.exe" `
        -Arguments "/c ""C:\apache-maven-3.9.6\bin\mvn.cmd"" -pl cachedb-examples -am -DskipTests $buildGoal" `
        -WorkingDirectory $repoRoot `
        -StdOutPath $mavenBuildLog `
        -StdErrPath ($mavenBuildLog + ".err")

if ($install.ExitCode -ne 0) {
    throw "Maven build failed. See $mavenBuildLog and $($mavenBuildLog + '.err').`n$($install.StdOut)`n$($install.StdErr)"
}

$repackage = Invoke-ExternalProcess `
        -FilePath "C:\Windows\System32\cmd.exe" `
        -Arguments '/c "C:\apache-maven-3.9.6\bin\mvn.cmd" -f cachedb-examples/pom.xml -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.5:repackage -Dspring-boot.repackage.mainClass=com.reactor.cachedb.examples.demo.boot.DemoSpringBootLoadApplication' `
        -WorkingDirectory $repoRoot `
        -StdOutPath $mavenRepackageLog `
        -StdErrPath ($mavenRepackageLog + ".err")

if ($repackage.ExitCode -ne 0) {
    throw "Spring Boot repackage failed. See $mavenRepackageLog and $($mavenRepackageLog + '.err').`n$($repackage.StdOut)`n$($repackage.StdErr)"
}

$null = Invoke-ExternalProcess -FilePath $dockerExe -Arguments "rm -f $containerName" -WorkingDirectory $repoRoot -TimeoutMs 120000

$build = Invoke-ExternalProcess `
        -FilePath $dockerExe `
        -Arguments "build -f `"$dockerfilePath`" -t $imageName ." `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 1200000 `
        -StdOutPath $dockerBuildLog `
        -StdErrPath ($dockerBuildLog + ".err")

if ($build.ExitCode -ne 0) {
    throw "Docker build failed. See $dockerBuildLog and $($dockerBuildLog + '.err').`n$($build.StdOut)`n$($build.StdErr)"
}

$run = Invoke-ExternalProcess `
        -FilePath $dockerExe `
        -Arguments "run -d --name $containerName -p 8090:8090 -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:55432/postgres -e SPRING_DATASOURCE_USERNAME=postgres -e SPRING_DATASOURCE_PASSWORD=postgresql -e CACHEDB_REDIS_URI=redis://default:welcome1@host.docker.internal:56379/15 -e CACHEDB_DEMO_REDIS_URI=redis://default:welcome1@host.docker.internal:56379/15 -e CACHEDB_DEMO_REDISURI=redis://default:welcome1@host.docker.internal:56379/15 $imageName" `
        -WorkingDirectory $repoRoot `
        -TimeoutMs 120000 `
        -StdOutPath $dockerRunLog `
        -StdErrPath ($dockerRunLog + ".err")

if ($run.ExitCode -ne 0) {
    throw "Docker run failed. See $dockerRunLog and $($dockerRunLog + '.err').`n$($run.StdOut)`n$($run.StdErr)"
}

Write-Host "Spring Boot load demo container started."
Write-Host "Demo UI: http://127.0.0.1:8090/demo-load"
Write-Host "Admin UI: http://127.0.0.1:8090/cachedb-admin/dashboard-v3?lang=tr"
