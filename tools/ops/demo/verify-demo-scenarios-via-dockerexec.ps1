$ErrorActionPreference = 'Stop'

function Invoke-DockerNative {
    param(
        [Parameter(Mandatory = $true)][string]$Arguments
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

    if ($process.ExitCode -ne 0) {
        throw "Command failed: docker.exe $Arguments`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

function Get-DemoStatus {
    $raw = Invoke-DockerNative 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8090/api/status"'
    return ($raw | ConvertFrom-Json)
}

function Invoke-DemoAction {
    param([Parameter(Mandatory = $true)][string]$Path)
    $arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- --post-data=\"\" http://127.0.0.1:8090' + $Path + '"'
    Invoke-DockerNative $arguments | Out-Null
}

function Wait-ForLabel {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Condition,
        [int]$Attempts = 30,
        [int]$DelaySeconds = 1
    )

    for ($i = 0; $i -lt $Attempts; $i++) {
        $status = Get-DemoStatus
        if (& $Condition $status) {
            return $status
        }
        Start-Sleep -Seconds $DelaySeconds
    }

    throw 'Timed out waiting for demo status condition.'
}

Write-Host '[1/6] Fresh Start'
Invoke-DemoAction '/api/fresh-reset'
$afterReset = Wait-ForLabel -Condition { param($s) -not $s.snapshot.seeded -and $s.snapshot.customerCount -eq 0 -and $s.snapshot.productCount -eq 0 -and $s.snapshot.cartCount -eq 0 -and $s.snapshot.orderCount -eq 0 -and $s.snapshot.orderLineCount -eq 0 }
Write-Host ("reset seeded={0} customers={1} products={2} carts={3} orders={4} orderLines={5}" -f $afterReset.snapshot.seeded, $afterReset.snapshot.customerCount, $afterReset.snapshot.productCount, $afterReset.snapshot.cartCount, $afterReset.snapshot.orderCount, $afterReset.snapshot.orderLineCount)

Write-Host '[2/6] Seed Demo Data'
Invoke-DemoAction '/api/seed'
$afterSeed = Wait-ForLabel -Condition {
    param($s)
    $s.snapshot.seeded -and
    $s.snapshot.customerCount -gt 50 -and
    $s.snapshot.productCount -gt 50 -and
    $s.snapshot.cartCount -gt 50 -and
    $s.snapshot.orderCount -gt 50 -and
    $s.snapshot.orderLineCount -gt 1000 -and
    (($s.snapshot.customerCount + $s.snapshot.productCount + $s.snapshot.cartCount + $s.snapshot.orderCount + $s.snapshot.orderLineCount) -gt 10000)
}
Write-Host ("seed customers={0} products={1} carts={2} orders={3} orderLines={4} total={5}" -f
    $afterSeed.snapshot.customerCount,
    $afterSeed.snapshot.productCount,
    $afterSeed.snapshot.cartCount,
    $afterSeed.snapshot.orderCount,
    $afterSeed.snapshot.orderLineCount,
    ($afterSeed.snapshot.customerCount + $afterSeed.snapshot.productCount + $afterSeed.snapshot.cartCount + $afterSeed.snapshot.orderCount + $afterSeed.snapshot.orderLineCount))
Write-Host ("seed summary={0}" -f $afterSeed.snapshot.seedSummary)

foreach ($level in @('LOW', 'MEDIUM', 'HIGH')) {
    Write-Host ("[Scenario] {0}" -f $level)
    Invoke-DemoAction ("/api/start?level={0}" -f $level)
    $running = Wait-ForLabel -Condition { param($s) $s.snapshot.running -and $s.snapshot.activeScenario -eq $level }
    Start-Sleep -Seconds 2
    $sample = Get-DemoStatus
    Write-Host ("running level={0} readers={1} writers={2} reads={3} writes={4}" -f
        $sample.snapshot.activeScenario,
        $sample.snapshot.activeReaderThreads,
        $sample.snapshot.activeWriterThreads,
        $sample.snapshot.readCount,
        $sample.snapshot.writeCount)
    Write-Host ("summary={0}" -f $sample.snapshot.activeScenarioSummary)

    Invoke-DemoAction '/api/stop'
    $stopped = Wait-ForLabel -Condition { param($s) -not $s.snapshot.running -and $s.snapshot.activeScenario -eq 'IDLE' }
    Write-Host ("stopped currentAction={0}" -f $stopped.snapshot.currentAction)
}

Write-Host '[6/6] Final Status'
$final = Get-DemoStatus
Write-Host ("final seeded={0} running={1} reads={2} writes={3} errors={4}" -f
    $final.snapshot.seeded,
    $final.snapshot.running,
    $final.snapshot.readCount,
    $final.snapshot.writeCount,
    $final.snapshot.errorCount)
