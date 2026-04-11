$ErrorActionPreference = 'Stop'

function Run-Proc {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $File
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "PROC_FAIL[$File]: $stderr`n$stdout"
    }

    return $stdout
}

$root = 'E:\ReactorRepository\cache-database'
$cmd = 'C:\Windows\System32\cmd.exe'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$dockerWd = 'C:\Program Files\Docker\Docker\resources\bin'

Run-Proc $cmd '/c cd /d E:\ReactorRepository\cache-database && set "JAVA_HOME=C:\java64\Semeru\jdk-21.0.2.13-openj9" && set "PATH=C:\java64\Semeru\jdk-21.0.2.13-openj9\bin;C:\apache-maven-3.9.6\bin;%PATH%" && call C:\apache-maven-3.9.6\bin\mvn.cmd -q -s tools\build\maven-offline-settings.xml -pl cachedb-examples -am -DskipTests install' $root | Out-Null

try {
    Run-Proc $docker 'rm -f cachedb-demo-ui' $dockerWd | Out-Null
} catch {
}

Run-Proc $docker 'run -d --name cachedb-demo-ui -p 8080:8080 -p 8090:8090 -v //e/ReactorRepository/cache-database:/workspace -v //e/maven/.m2:/root/.m2 -w /workspace/cachedb-examples maven:3.9.9-eclipse-temurin-21 bash -lc "mvn -q -s /workspace/tools/build/maven-settings.xml exec:java -Dexec.mainClass=com.reactor.cachedb.examples.demo.DemoLoadMain -Dcachedb.demo.redisUri=redis://default:welcome1@host.docker.internal:56379 -Dcachedb.demo.jdbcUrl=jdbc:postgresql://host.docker.internal:55432/postgres -Dcachedb.demo.jdbcUser=postgres -Dcachedb.demo.jdbcPassword=postgresql"' $dockerWd | Out-Null

Start-Sleep -Seconds 8

$logs = Run-Proc $docker 'logs --tail 40 cachedb-demo-ui' $dockerWd
$html = Run-Proc $docker 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"' $dockerWd

$checks = [ordered]@{
    logs_demo = ($logs -match 'Demo load UI listening')
    logs_admin = ($logs -match 'Admin dashboard listening')
    has_runtime_profile_tr = ($html -match 'Çalışma Profili Kontrolü')
    has_tuning_tr = ($html -match 'Etkin Ayarlar')
    has_reset_text_tr = ($html -match 'Henüz telemetri sıfırlaması çalıştırılmadı')
    has_service_status_tr = ($html -match 'Servis Durumu')
    no_tuning_slice = -not ($html -match '\.slice\(0,60\)')
}

$checks.GetEnumerator() | ForEach-Object {
    "{0}={1}" -f $_.Key, $_.Value
}
