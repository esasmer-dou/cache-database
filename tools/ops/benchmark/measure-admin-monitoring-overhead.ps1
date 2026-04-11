$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$mvn = 'C:\apache-maven-3.9.6\bin\mvn.cmd'
$javaHome = 'C:\java64\Semeru\jdk-21.0.2.13-openj9'
if (-not (Test-Path $mvn)) {
    throw "Maven not found at $mvn"
}
if (-not (Test-Path $javaHome)) {
    throw "JAVA_HOME not found at $javaHome"
}

$cmd = 'set "JAVA_HOME={0}" && set "PATH={0}\bin;C:\apache-maven-3.9.6\bin;%PATH%" && call {1} -pl cachedb-starter -DforkCount=0 -Dtest=com.reactor.cachedb.starter.AdminMonitoringOverheadTest test' -f $javaHome, $mvn
$process = Start-Process -FilePath 'C:\Windows\System32\cmd.exe' `
    -ArgumentList '/c', $cmd `
    -WorkingDirectory $repoRoot `
    -Wait `
    -PassThru `
    -NoNewWindow

exit $process.ExitCode
