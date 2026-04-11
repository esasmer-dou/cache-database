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

$curl = 'C:\Windows\System32\curl.exe'

Write-Host '[1/4] Seed action'
$seedAck = Invoke-Native -File $curl -Arguments '-s -X POST http://127.0.0.1:8090/api/seed'
Write-Host $seedAck
Start-Sleep -Seconds 2

Write-Host '[2/4] Status after seed'
$statusAfterSeed = Invoke-Native -File $curl -Arguments '-s http://127.0.0.1:8090/api/status'
$seedJson = $statusAfterSeed | ConvertFrom-Json
Write-Host ("seeded={0} customers={1} products={2} carts={3} orders={4}" -f `
    $seedJson.snapshot.seeded,
    $seedJson.snapshot.customerCount,
    $seedJson.snapshot.productCount,
    $seedJson.snapshot.cartCount,
    $seedJson.snapshot.orderCount)

Write-Host '[3/4] Fresh reset action'
$resetAck = Invoke-Native -File $curl -Arguments '-s -X POST http://127.0.0.1:8090/api/fresh-reset'
Write-Host $resetAck
Start-Sleep -Seconds 3

Write-Host '[4/4] Status after fresh reset'
$statusAfterReset = Invoke-Native -File $curl -Arguments '-s http://127.0.0.1:8090/api/status'
$resetJson = $statusAfterReset | ConvertFrom-Json
Write-Host ("seeded={0} customers={1} products={2} carts={3} orders={4} errors={5}" -f `
    $resetJson.snapshot.seeded,
    $resetJson.snapshot.customerCount,
    $resetJson.snapshot.productCount,
    $resetJson.snapshot.cartCount,
    $resetJson.snapshot.orderCount,
    $resetJson.snapshot.errorCount)

if ($resetJson.snapshot.seeded -or
    $resetJson.snapshot.customerCount -ne 0 -or
    $resetJson.snapshot.productCount -ne 0 -or
    $resetJson.snapshot.cartCount -ne 0 -or
    $resetJson.snapshot.orderCount -ne 0) {
    throw 'Fresh Start sonucu demo verisi sifira donmedi.'
}

Write-Host 'Fresh Start verification passed.'
