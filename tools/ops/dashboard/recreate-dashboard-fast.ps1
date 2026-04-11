$ErrorActionPreference = 'Stop'

function Start-DockerProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Arguments
    )

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process `
            -FilePath 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' `
            -ArgumentList $Arguments `
            -WorkingDirectory 'C:\Program Files\Docker\Docker\resources\bin' `
            -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile `
            -NoNewWindow `
            -PassThru `
            -Wait

        [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut = [System.IO.File]::ReadAllText($stdoutFile)
            StdErr = [System.IO.File]::ReadAllText($stderrFile)
        }
    } finally {
        Remove-Item $stdoutFile,$stderrFile -ErrorAction SilentlyContinue
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

$compileArgs = @'
run --rm -v //e/ReactorRepository/cache-database:/workspace -v //e/maven/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 bash -lc "mvn -q -s /workspace/tools/build/maven-settings.xml -pl cachedb-examples -am -DskipTests compile"
'@
Invoke-DockerStep -Arguments $compileArgs -StepName 'dockerized compile'

$null = Start-DockerProcess 'rm -f cachedb-demo-ui'

$runArgs = @'
run -d --name cachedb-demo-ui -p 8080:8080 -p 8090:8090 -v //e/ReactorRepository/cache-database:/workspace -v //e/maven/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-21 bash /workspace/tools/build/run-demo-container.sh
'@
Invoke-DockerStep -Arguments $runArgs -StepName 'docker run'

Start-Sleep -Seconds 45

$fetch = Invoke-DockerStep -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"' -StepName 'dashboard fetch'
$html = $fetch.StdOut

@(
    'has_top_read=' + $html.Contains('performanceTopReadGrid')
    'has_top_write=' + $html.Contains('performanceTopWriteGrid')
    'has_scenario_grid=' + $html.Contains('performanceScenarioGrid')
)
