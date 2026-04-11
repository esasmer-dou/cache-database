$ErrorActionPreference = 'Stop'

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [string]$WorkingDirectory = 'E:\ReactorRepository\cache-database'
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $File
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "Command failed: $File $Arguments`n$stderr"
    }

    return $stdout
}

$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'

Write-Host '[1/4] Seed action inside container'
$seed = Invoke-Native -File $docker -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- --post-data=\"\" http://127.0.0.1:8090/api/seed"'
Write-Host $seed
Start-Sleep -Seconds 2

Write-Host '[2/4] Status after seed'
$statusSeedRaw = Invoke-Native -File $docker -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8090/api/status"'
$statusSeed = $statusSeedRaw | ConvertFrom-Json
Write-Host ("seeded={0} customers={1} products={2} carts={3} orders={4}" -f `
    $statusSeed.snapshot.seeded,
    $statusSeed.snapshot.customerCount,
    $statusSeed.snapshot.productCount,
    $statusSeed.snapshot.cartCount,
    $statusSeed.snapshot.orderCount)

Write-Host '[3/4] Fresh Start action inside container'
$reset = Invoke-Native -File $docker -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- --post-data=\"\" http://127.0.0.1:8090/api/fresh-reset"'
Write-Host $reset
Start-Sleep -Seconds 3

Write-Host '[4/4] Status after Fresh Start'
$statusResetRaw = Invoke-Native -File $docker -Arguments 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8090/api/status"'
$statusReset = $statusResetRaw | ConvertFrom-Json
Write-Host ("seeded={0} customers={1} products={2} carts={3} orders={4} errors={5}" -f `
    $statusReset.snapshot.seeded,
    $statusReset.snapshot.customerCount,
    $statusReset.snapshot.productCount,
    $statusReset.snapshot.cartCount,
    $statusReset.snapshot.orderCount,
    $statusReset.snapshot.errorCount)

if ($statusReset.snapshot.seeded -or
    $statusReset.snapshot.customerCount -ne 0 -or
    $statusReset.snapshot.productCount -ne 0 -or
    $statusReset.snapshot.cartCount -ne 0 -or
    $statusReset.snapshot.orderCount -ne 0) {
    throw 'Fresh Start sonucu demo verisi sifira donmedi.'
}

Write-Host 'Fresh Start verification passed.'
