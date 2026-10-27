param([switch]$SkipInfrastructure, [switch]$SplitRoles)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
[xml]$pom = Get-Content (Join-Path $root 'pom.xml') -Raw
$provider = ([string]$pom.project.artifactId).Replace('sample-cache-database-', '')
if ($provider -notin @('postgresql', 'mssql', 'oracle')) { throw 'Unknown sample provider.' }
if ($SkipInfrastructure -and $SplitRoles) { throw 'Split-role testing requires owned disposable infrastructure.' }
$project = "cachedb-$provider-catalog-check"
$jar = Join-Path $root "target/sample-cache-database-$provider-$($pom.project.version).jar"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) {'bin/java.exe'} else {'bin/java'}) } else { (Get-Command java).Source }
$npx = Get-Command $(if ($IsWindows) {'npx.cmd'} else {'npx'}) -ErrorAction Stop
if (!(Test-Path $jar)) { throw 'Build the sample JAR with mvn verify first.' }
$output = Join-Path $root 'target/catalog-postman'
$null = New-Item -ItemType Directory -Force -Path $output
$lease = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$lease.Start()
$port = $lease.LocalEndpoint.Port
$lease.Stop()
$process = $null
$previousPort = $env:SAMPLE_HTTP_PORT
Push-Location $root
try {
    if (!$SkipInfrastructure) {
        $database = @{postgresql='postgres'; mssql='sqlserver'; oracle='oracle'}[$provider]
        & docker compose -p $project up -d --wait --wait-timeout 180 redis $database
        if ($LASTEXITCODE -ne 0) { throw 'Disposable provider infrastructure did not become ready.' }
        if ($provider -eq 'mssql') {
            # Some Compose versions treat a successful one-shot exit as a failed --wait.
            & docker compose -p $project run --rm --no-deps sql-init
            if ($LASTEXITCODE -ne 0) { throw 'Disposable SQL Server schema preparation failed.' }
        }
    }
    $start = [Diagnostics.ProcessStartInfo]::new($java)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.WorkingDirectory = $root
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Never inherit a workstation's production datasource into a disposable test.
    foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'REDIS_URI')) {
        $null = $start.Environment.Remove($name)
    }
    foreach ($argument in @('-jar', $jar, '--spring.profiles.active=demo', "--server.port=$port",
            '--server.address=127.0.0.1', '--sample.scheduled-warm.enabled=false')) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (!$process.Start()) { throw 'Could not start sample.' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $base = "http://127.0.0.1:$port"
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -ne $process -and $process.HasExited) { throw 'Sample exited before readiness. See target/catalog-postman.' }
        try {
            $health = Invoke-RestMethod "$base/actuator/health/readiness" -TimeoutSec 2 -SkipHttpErrorCheck
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch [System.Net.Http.HttpRequestException] { }
          catch [System.Threading.Tasks.TaskCanceledException] { }
        Start-Sleep -Milliseconds 250
    }
    if (!$ready) { throw 'Sample readiness exceeded 120 seconds.' }
    if ($SplitRoles) {
        $process.Kill($true)
        $process.WaitForExit()
        [IO.File]::WriteAllText((Join-Path $output 'bootstrap.log'), $stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $output 'bootstrap-error.log'), $stderr.GetAwaiter().GetResult())
        $process.Dispose()
        $process = $null
        $env:SAMPLE_HTTP_PORT = [string]$port
        & docker compose -p $project -f docker-compose.yml -f docker-compose.roles.yml up -d --scale worker=2 api worker
        if ($LASTEXITCODE -ne 0) { throw 'Split API/worker containers failed to start.' }
        $ready = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        while ([DateTime]::UtcNow -lt $deadline) {
            try {
                $health = Invoke-RestMethod "$base/actuator/health/readiness" -TimeoutSec 2 -SkipHttpErrorCheck
                if ($health.status -eq 'UP') { $ready = $true; break }
            } catch [System.Net.Http.HttpRequestException] { }
              catch [System.Threading.Tasks.TaskCanceledException] { }
            Start-Sleep -Milliseconds 250
        }
        if (!$ready) { throw 'Split-role API readiness exceeded 120 seconds.' }
        $workers = @(& docker compose -p $project -f docker-compose.yml -f docker-compose.roles.yml ps --status running -q worker)
        if ($LASTEXITCODE -ne 0 -or $workers.Count -ne 2) {
            throw 'Expected two running worker containers before executing the API contract.'
        }
    }
    & $npx.Source --yes newman@6.2.1 run "postman/cache-database-$provider-catalog.postman_collection.json" `
        --env-var "baseUrl=$base" --timeout 120000 --timeout-request 10000 --timeout-script 5000 `
        --reporters cli,json,junit --reporter-json-export "$output/results.json" `
        --reporter-junit-export "$output/results.xml"
    if ($LASTEXITCODE -ne 0) { throw 'Catalog Postman assertions failed.' }
    & $npx.Source --yes newman@6.2.1 run "postman/cache-database-$p-v018.postman_collection.json" `
        --env-var "baseUrl=$base" --env-var "peerBaseUrl=$base" --timeout 180000 --timeout-request 10000 --timeout-script 5000 `
        --reporters cli,json,junit --reporter-json-export "$output/v018-results.json" `
        --reporter-junit-export "$output/v018-results.xml"
    if ($LASTEXITCODE -ne 0) { throw '0.18 controls Postman assertions failed.' }
} finally {
    if ($null -ne $process) {
        if (!$process.HasExited) { $process.Kill($true) }
        $process.WaitForExit()
        [IO.File]::WriteAllText((Join-Path $output 'application.log'), $stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $output 'application-error.log'), $stderr.GetAwaiter().GetResult())
        $process.Dispose()
    }
    if (!$SkipInfrastructure) {
        if ($SplitRoles) {
            & docker compose -p $project -f docker-compose.yml -f docker-compose.roles.yml logs --no-color api worker *> (Join-Path $output 'split-role-containers.log')
            & docker compose -p $project -f docker-compose.yml -f docker-compose.roles.yml down --volumes
        } else {
            & docker compose -p $project down --volumes
        }
        if ($LASTEXITCODE -ne 0) { Write-Warning "Clean up disposable project $project manually." }
    }
    $env:SAMPLE_HTTP_PORT = $previousPort
    Pop-Location
}
