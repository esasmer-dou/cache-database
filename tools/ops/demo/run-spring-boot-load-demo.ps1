$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$javaHome = "C:\java64\Semeru\jdk-21.0.2.13-openj9"
$mavenHome = "C:\apache-maven-3.9.6"

if (-not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Configured JDK not found: $javaHome"
}

if (-not (Test-Path (Join-Path $mavenHome "bin\mvn.cmd"))) {
    throw "Configured Maven not found: $mavenHome"
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$mavenHome\bin;$env:Path"

Push-Location $repoRoot
try {
    & "$mavenHome\bin\mvn.cmd" "-pl" "cachedb-examples" "-am" "-DskipTests" "install"
    if ($LASTEXITCODE -ne 0) {
        throw "Install failed with exit code $LASTEXITCODE"
    }
    Write-Host "Starting Spring Boot load demo with JDK: $javaHome"
    Write-Host "Demo UI: http://127.0.0.1:8090/demo-load"
Write-Host "Admin UI: http://127.0.0.1:8090/cachedb-admin?lang=tr"
    & "$mavenHome\bin\mvn.cmd" `
        "-f" "cachedb-examples/pom.xml" `
        "org.springframework.boot:spring-boot-maven-plugin:3.3.5:run" `
        "-Dspring-boot.run.mainClass=com.reactor.cachedb.examples.demo.boot.DemoSpringBootLoadApplication"
} finally {
    Pop-Location
}
