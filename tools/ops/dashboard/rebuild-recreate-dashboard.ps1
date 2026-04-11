$ErrorActionPreference = 'Stop'

function Start-DockerProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Arguments
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = 'C:\Program Files\Docker\Docker\resources\bin'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdout
        StdErr = $stderr
    }
}

function Invoke-DockerStep {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$StepName
    )

    $result = Start-DockerProcess $Arguments
    if ($result.ExitCode -ne 0) {
        throw "$StepName failed.`nSTDOUT:`n$($result.StdOut)`nSTDERR:`n$($result.StdErr)"
    }
    return $result
}

$buildArgs = @'
run --rm -v //e/ReactorRepository/cache-database:/workspace -v //e/maven/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 bash -lc "mvn -q -s /workspace/tools/build/maven-settings.xml -pl cachedb-examples -am -DskipTests install"
'@
Invoke-DockerStep -Arguments $buildArgs -StepName 'dockerized build'

$null = Start-DockerProcess 'rm -f cachedb-demo-ui'

$runArgs = @'
run -d --name cachedb-demo-ui -p 8080:8080 -p 8090:8090 -v //e/ReactorRepository/cache-database:/workspace -v //e/maven/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 bash /workspace/tools/build/run-demo-container.sh
'@
Invoke-DockerStep -Arguments $runArgs -StepName 'docker run'

Start-Sleep -Seconds 45

$logs = Invoke-DockerStep -Arguments 'logs --tail 120 cachedb-demo-ui' -StepName 'docker logs'
$fetch = Start-DockerProcess 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'
if ($fetch.ExitCode -ne 0) {
    Start-Sleep -Seconds 20
    $fetch = Invoke-DockerStep -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"' -StepName 'dashboard fetch'
}

$html = $fetch.StdOut

@(
    'has_health_reason_title=' + ($html -match 'healthReasonTitle')
    'has_health_reason_copy=' + ($html -match 'Bu durumun nedeni')
    'has_primary_signals=' + ($html -match 'Ana Göstergeler')
    'has_triage_section=' + ($html -match 'Önceliklendirme')
)
