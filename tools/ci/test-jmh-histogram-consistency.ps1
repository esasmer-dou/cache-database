#requires -Version 7.2
param(
    [string[]]$FixturePaths = @(),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if (!$EvidenceDirectory) {
    $EvidenceDirectory=Join-Path ([IO.Path]::GetTempPath()) ('cachedb-histogram-controls-'+[guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null=New-Item -ItemType Directory -Path $EvidenceDirectory
$helper=Join-Path $PSScriptRoot 'jmh-histogram-consistency.ps1'
. $helper
$passed=[Collections.Generic.List[string]]::new()
function Pass([string]$Name) { $passed.Add($Name); Write-Output "PASS $Name" }
function New-Row([string]$Distribution='constant') {
    $ranks=[ordered]@{}
    foreach ($key in @('0.0','50.0','90.0','95.0','99.0','99.9','99.99','99.999','99.9999','100.0')) {
        $ranks[$key]=if ($Distribution -eq 'constant') {10.0} elseif ($Distribution -eq 'zero') {0.0}
            elseif ($key -eq '0.0') {10.125} elseif ($key -eq '50.0' -and $Distribution -eq 'interpolation') {15.1875} else {20.25}
    }
    $raw=[Collections.Generic.List[object]]::new()
    for ($f=0;$f -lt 3;$f++) {
        $iterations=[Collections.Generic.List[object]]::new()
        for ($i=0;$i -lt 5;$i++) {
            $buckets=[Collections.Generic.List[object]]::new()
            switch ($Distribution) {
                constant { $buckets.Add([object[]]@(10.0,100)) }
                zero { $buckets.Add([object[]]@(0.0,100)) }
                interpolation { $buckets.Add([object[]]@(10.125,1)); $buckets.Add([object[]]@(20.25,1)) }
                weighted {
                    if ($f -eq 2 -and $i -eq 4) { $buckets.Add([object[]]@(20.25,100)) }
                    else { $buckets.Add([object[]]@(10.125,1)) }
                }
                default { throw 'Unknown synthetic distribution.' }
            }
            $iterations.Add($buckets)
        }
        $raw.Add($iterations)
    }
    # JSON round-trip uses the same numeric reader as the evidence checkers, not a Java invocation.
    return (@{jmhVersion='1.37'; mode='sample'; forks=3; measurementIterations=5;
        primaryMetric=@{scoreUnit='us/op'; scorePercentiles=$ranks; rawDataHistogram=$raw}} |
        ConvertTo-Json -Depth 20 | ConvertFrom-Json)
}
function Reject([string]$Name, [scriptblock]$Mutation, [string]$Expected, [string]$Distribution='constant') {
    $row=New-Row $Distribution
    & $Mutation $row
    $caught=$false
    try { Assert-Jmh137HistogramConsistency $row | Out-Null } catch {
        $caught=$true
        if ($_.Exception.Message -notmatch $Expected) { throw "Wrong rejection ${Name}: $($_.Exception.Message)" }
    }
    if (!$caught) { throw "Invalid histogram accepted: $Name" }
    Pass $Name
}
foreach ($distribution in @('constant','zero','interpolation','weighted')) {
    $proof=Assert-Jmh137HistogramConsistency (New-Row $distribution)
    if ($proof.percentilesChecked -ne 10) { throw 'Not every percentile was checked.' }
    Pass "synthetic-$distribution-exact-parity"
}
foreach ($key in @('0.0','50.0','90.0','95.0','99.0','99.9','99.99','99.999','99.9999','100.0')) {
    Reject "reject-one-ulp-p$key" {
        param($r)
        $p=$r.primaryMetric.scorePercentiles.PSObject.Properties[$key]
        $p.Value=[Math]::BitIncrement([double]$p.Value)
    } 'percentile mismatch'
}
Reject 'reject-forged-lower-summary' {param($r) $r.primaryMetric.scorePercentiles.'50.0'=9.0} 'percentile mismatch'
Reject 'reject-histogram-only-tail-change' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][0]=11.0} 'percentile mismatch'
Reject 'reject-rank-changing-count' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1]=2} 'percentile mismatch' 'interpolation'
Reject 'reject-wrong-jmh' {param($r) $r.jmhVersion='1.36'} 'Unsupported JMH histogram contract'
Reject 'reject-numeric-jmh' {param($r) $r.jmhVersion=1.37} 'Unsupported JMH histogram contract'
Reject 'reject-wrong-unit' {param($r) $r.primaryMetric.scoreUnit='ns/op'} 'Unsupported JMH histogram contract'
Reject 'reject-wrong-mode' {param($r) $r.mode='thrpt'} 'Unsupported JMH histogram contract'
Reject 'reject-wrong-forks' {param($r) $r.forks=2} 'Unsupported JMH histogram contract'
Reject 'reject-wrong-iterations' {param($r) $r.measurementIterations=4} 'Unsupported JMH histogram contract'
Reject 'reject-fractional-count' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1]=1.5} 'positive int32'
Reject 'reject-string-count' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1]='100'} 'positive int32'
Reject 'reject-zero-count' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1]=0} 'positive int32'
Reject 'reject-negative-count' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1]=-1} 'positive int32'
Reject 'reject-int32-overflow' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][1]=[int]::MaxValue} 'aggregate int32 overflow'
Reject 'reject-nonfinite-latency' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][0]=[double]::PositiveInfinity} 'finite nonnegative'
Reject 'reject-string-latency' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][0]='10'} 'JSON numeric'
Reject 'reject-negative-zero' {param($r) $r.primaryMetric.rawDataHistogram[0][0][0][0]=[BitConverter]::Int64BitsToDouble([long]::MinValue)} 'negative zero'
Reject 'reject-duplicate-bucket' {param($r) $r.primaryMetric.rawDataHistogram[0][0]=@(@(10.0,1),@(10.0,1))} 'unique and sorted'
Reject 'reject-unsorted-bucket' {param($r) $r.primaryMetric.rawDataHistogram[0][0]=@(@(20.0,1),@(10.0,1))} 'unique and sorted'
Reject 'reject-oversized-iteration' {param($r) $r.primaryMetric.rawDataHistogram[0][0]=[object[]]::new(55297)} 'cell bound'
Reject 'reject-missing-percentile' {param($r) $r.primaryMetric.scorePercentiles.PSObject.Properties.Remove('99.9')} 'every official'
Reject 'reject-extra-percentile' {param($r) $r.primaryMetric.scorePercentiles | Add-Member -NotePropertyName '1.0' -NotePropertyValue 10.0} 'every official'
$realProofs=[Collections.Generic.List[object]]::new()
$syntheticCount=$passed.Count
[long]$remaining=256MB
if ($FixturePaths.Count -gt 64) { throw 'Real fixture file count exceeds 64.' }
foreach ($path in $FixturePaths) {
    $file=Get-Item -LiteralPath $path
    if ($file.PSIsContainer -or $file.LinkType -or $file.Length -lt 1 -or $file.Length -gt 64MB) { throw 'Invalid real fixture.' }
    $remaining-=$file.Length; if ($remaining -lt 0) { throw 'Real fixture budget exceeds 256 MiB.' }
    $before=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    $rows=@([IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json)
    if ($rows.Count -gt 32) { throw 'Too many real fixture rows.' }
    $samples=@($rows | Where-Object mode -ceq 'sample')
    if (!$samples.Count) { throw 'No sample rows in real fixture.' }
    foreach ($row in $samples) {
        $proof=Assert-Jmh137HistogramConsistency $row
        $realProofs.Add([pscustomobject]@{path=$file.FullName; sha256=$before; benchmark=$row.benchmark; proof=$proof})
        # Exercise forged summary rejection against an in-memory copy; never rewrite the real JSON.
        $original=$row.primaryMetric.scorePercentiles.'50.0'
        $row.primaryMetric.scorePercentiles.'50.0'=[Math]::BitDecrement([double]$original)
        $rejected=$false
        try { Assert-Jmh137HistogramConsistency $row | Out-Null } catch {
            if ($_.Exception.Message -notmatch 'percentile mismatch') { throw }
            $rejected=$true
        } finally { $row.primaryMetric.scorePercentiles.'50.0'=$original }
        if (!$rejected) { throw 'Forged real-fixture summary accepted.' }
    }
    if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -cne $before) { throw 'Real fixture changed.' }
    Pass "real-format-parity-and-forgery-$($file.Name)"
}
if (@($passed | Select-Object -Unique).Count -ne $passed.Count) { throw 'Duplicate control names.' }
[ordered]@{result='PASS'; controls=$passed; syntheticControls=$syntheticCount; realFiles=$FixturePaths.Count;
    realSampleRows=$realProofs.Count; realPercentiles=($realProofs.Count*10); exactBinary64=$true;
    fixturesUnchanged=$true; benchmarkExecuted=$false; diagnosticPerformanceQualified=$false;
    helperSha256=(Get-FileHash -LiteralPath $helper).Hash; realProofs=$realProofs} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Output "JMH137_HISTOGRAM_CONTROLS_PASS=$($passed.Count); REAL_ROWS=$($realProofs.Count); FORMAT_ONLY_NOT_PERFORMANCE_ACCEPTANCE"
