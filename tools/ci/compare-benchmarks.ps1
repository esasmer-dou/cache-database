param(
    [string]$Baseline,
    [string]$Current,
    [string]$Summary,
    [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-CaseKey($Result) {
    $parameters = if ($Result.PSObject.Properties.Name -contains 'params') {
        @($Result.params.PSObject.Properties | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','
    } else { '' }
    return "$($Result.benchmark)|$($Result.mode)|threads=$($Result.threads)|$parameters"
}

function Get-PositiveNumber($Value, [string]$Label) {
    $number = 0.0
    if (![double]::TryParse([Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture), [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or
            [double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -le 0) {
        throw "Invalid benchmark value: $Label"
    }
    return $number
}

function Compare-Results([object[]]$Before, [object[]]$After) {
    if ($Before.Count -eq 0 -or $After.Count -eq 0) { throw 'Empty benchmark report.' }
    $index = @{}
    foreach ($item in $After) {
        $key = Get-CaseKey $item
        if ($index.ContainsKey($key)) { throw "Duplicate current case: $key" }
        $index[$key] = $item
    }
    $seen = @{}
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('| Case | Baseline | Current | Change | Gate |')
    $lines.Add('| --- | ---: | ---: | ---: | --- |')
    $failures = [Collections.Generic.List[string]]::new()
    foreach ($old in $Before) {
        $key = Get-CaseKey $old
        if ($seen.ContainsKey($key)) { throw "Duplicate baseline case: $key" }
        $seen[$key] = $true
        if (!$index.ContainsKey($key)) { throw "Missing current benchmark case: $key" }
        $new = $index[$key]
        if ($old.primaryMetric.scoreUnit -ne $new.primaryMetric.scoreUnit) { throw "Unit mismatch: $key" }
        if ($old.mode -notin @('thrpt', 'sample')) { throw "Unsupported comparison mode: $($old.mode)" }
        if ($old.mode -eq 'thrpt') {
            $previous = Get-PositiveNumber $old.primaryMetric.score $key
            $next = Get-PositiveNumber $new.primaryMetric.score $key
            $failed = $next / $previous -lt 0.90
            $metric = 'throughput'
        } else {
            $previous = Get-PositiveNumber $old.primaryMetric.scorePercentiles.'99.0' $key
            $next = Get-PositiveNumber $new.primaryMetric.scorePercentiles.'99.0' $key
            $failed = $next / $previous -gt 1.15
            $metric = 'p99'
        }
        $delta = ($next / $previous - 1) * 100
        $gate = if ($failed) { 'FAIL' } else { 'PASS' }
        $description = $key.Replace('|', ' / ')
        $lines.Add(('| {0} ({1}) | {2:G6} | {3:G6} | {4:+0.00;-0.00;0.00}% | {5} |' -f
                $description, $metric, $previous, $next, $delta, $gate))
        if ($failed) { $failures.Add("${key}: $metric regression $delta percent") }
    }
    return [pscustomobject]@{ Markdown = $lines -join "`n"; Failures = $failures.ToArray() }
}

if ($SelfTest) {
    function Fixture([string]$Mode, [double]$Score, [double]$P99 = 100) {
        return [pscustomobject]@{ benchmark = 'fixture'; mode = $Mode; threads = 1
            primaryMetric = [pscustomobject]@{ score = $Score; scoreUnit = $Mode
                scorePercentiles = [pscustomobject]@{ '99.0' = $P99 } } }
    }
    if (@((Compare-Results @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 80))).Failures).Count -ne 1) {
        throw 'Self-test failed: 20 percent throughput regression was not detected.'
    }
    if (@((Compare-Results @((Fixture 'sample' 1 100)) @((Fixture 'sample' 1 120))).Failures).Count -ne 1) {
        throw 'Self-test failed: 20 percent p99 regression was not detected.'
    }
    if (@((Compare-Results @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 95))).Failures).Count -ne 0) {
        throw 'Self-test failed: acceptable drift rejected.'
    }
    if (@((Compare-Results @((Fixture 'thrpt' 100)) @((Fixture 'thrpt' 90))).Failures).Count -ne 0 -or
            @((Compare-Results @((Fixture 'sample' 1 100)) @((Fixture 'sample' 1 115))).Failures).Count -ne 0) {
        throw 'Self-test failed: exact threshold must not be rejected.'
    }
    $rejectedMissing = $false
    try { Compare-Results @((Fixture 'thrpt' 100)) @((Fixture 'sample' 1)) | Out-Null }
    catch { $rejectedMissing = $true }
    if (!$rejectedMissing) { throw 'Self-test failed: missing case accepted.' }
    Write-Host 'Benchmark gate self-test passed (throughput, p99, acceptable drift, missing case).'
    return
}
if (!$Baseline -or !$Current) { throw 'Specify -Baseline and -Current, or -SelfTest.' }
function Read-Report([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt 64MB) {
        throw 'Benchmark report must be a nonempty file, at most 64 MiB.'
    }
    $items = @(Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json)
    if ($items.Count -gt 256) { throw 'Benchmark report exceeds 256 cases.' }
    return $items
}
$result = Compare-Results @(Read-Report $Baseline) @(Read-Report $Current)
if ($Summary) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($Summary), $result.Markdown, [Text.UTF8Encoding]::new($false)) }
Write-Output $result.Markdown
if ($result.Failures.Count -gt 0) { throw ($result.Failures -join "`n") }
