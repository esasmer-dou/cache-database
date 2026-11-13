param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$manifest = Get-Item -LiteralPath $Path
if ($manifest.PSIsContainer -or $manifest.Length -le 0 -or $manifest.Length -gt 64KB) { throw 'Invalid legacy liveness manifest size.' }
$evidence = Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json
if ($evidence.schemaVersion -ne 1 -or $evidence.result -cne 'LEGACY_VIRTUAL_BASELINE_UNAVAILABLE' -or
        $evidence.baselineTag -cne 'v0.18.1' -or
        $evidence.baselineCommit -cne '3a212461fd835c783cf84d3c6b656b375df40a19' -or
        $evidence.runtimeSourcesUnmodified -ne $true -or
        $evidence.operatorAttestedStallSeconds -lt 30 -or $evidence.workerCount -ne 1000 -or
        $evidence.baselineJarSha256 -cnotmatch '^[a-f0-9]{64}$') {
    throw 'Unsupported legacy liveness exception; only the recorded 0.18.1 virtual-worker failure is eligible.'
}
$directory = $manifest.Directory.FullName
function Read-Evidence($Descriptor, [long]$MaxBytes) {
    if ($Descriptor.file -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
            $Descriptor.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid legacy evidence descriptor.' }
    $file = Get-Item -LiteralPath (Join-Path $directory $Descriptor.file)
    if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt $MaxBytes -or
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Descriptor.sha256) {
        throw 'Legacy liveness evidence is missing, oversized or changed.'
    }
    return Get-Content -LiteralPath $file.FullName -Raw
}
$dump = Read-Evidence $evidence.threadDump 16MB | ConvertFrom-Json -Depth 32
$log = Read-Evidence $evidence.runLog 8MB
$threads = @($dump.threadDump.threadContainers | ForEach-Object { $_.threads } | Select-Object -First 5001)
if ($threads.Count -gt 5000) { throw 'Legacy thread evidence exceeds its inspection bound.' }
$workers = @($threads | Where-Object { $_.name -like 'jmh-virtual-*' })
$pinned = @($workers | Where-Object {
    $stack = $_.stack -join "`n"
    $stack -match 'VirtualThread\.parkOnCarrierThread' -and
        $stack -match 'RedisProducerGuard\.sampleSafely' -and
        $stack -match 'GenericObjectPool\.borrowObject'
})
if ($workers.Count -ne 1000 -or !$pinned.Count -or
        $log -notmatch 'l1MissFindByIdVirtual1000' -or
        $log -notmatch 'Benchmark had encountered error' -or
        $log -notmatch 'forked VM failed with exit code') {
    throw 'Legacy failure does not establish the known pinned guardrail path.'
}
Write-Host 'Legacy 0.18.1 virtual baseline unavailable: pinned guardrail path verified; elapsed stall is operator-attested, not a numeric performance result.'
return $evidence
