# Shared validation for the Redis JMH evidence gates. Never rewrites evidence.
. "$PSScriptRoot/jmh-histogram-consistency.ps1"

function Assert-JmhProperties($Value, [string[]]$Names, [string]$Label) {
    if ($Value -isnot [pscustomobject]) { throw "JMH evidence requires a JSON object: $Label" }
    foreach ($name in $Names) {
        if ($null -eq $Value.PSObject.Properties[$name] -or $null -eq $Value.$name) {
            throw "JMH evidence missing property: $Label/$name"
        }
    }
}

function ConvertTo-JmhFiniteNumber($Value, [string]$Label, [switch]$Positive, [switch]$NonNegative) {
    if ($Value -isnot [double] -and $Value -isnot [long] -and $Value -isnot [int]) {
        throw "JMH evidence requires a JSON number: $Label"
    }
    [double]$number = $Value
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or
            ($Positive -and $number -le 0) -or ($NonNegative -and $number -lt 0)) {
        throw "JMH evidence requires a finite value in range: $Label"
    }
    return $number
}

function Assert-JmhInteger($Value, [int]$Minimum, [int]$Maximum, [string]$Label) {
    if (($Value -isnot [int] -and $Value -isnot [long]) -or $Value -lt $Minimum -or $Value -gt $Maximum) {
        throw "JMH evidence integer outside [$Minimum,$Maximum]: $Label"
    }
}

function Assert-JmhClose([double]$Actual, [double]$Expected, [string]$Label) {
    # Summary arithmetic can round differently; histogram percentiles remain bit-exact in the existing helper.
    $scale = [Math]::Max([Math]::Abs($Actual), [Math]::Abs($Expected))
    if ([double]::IsNaN($Expected) -or [double]::IsInfinity($Expected) -or
            [Math]::Abs($Actual - $Expected) -gt [Math]::Max([double]::Epsilon * 32, $scale * 1e-10)) {
        throw "JMH evidence summary mismatch: $Label"
    }
}

function Assert-JmhJvmArguments($Arguments) {
    if ($Arguments -isnot [array] -or $Arguments.Count -lt 1 -or $Arguments.Count -gt 128) {
        throw 'JMH JVM arguments must be a nonempty bounded JSON array.'
    }
    $settings = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($argument in $Arguments) {
        if ($argument -isnot [string] -or $argument.Length -lt 2 -or $argument.Length -gt 4096 -or
                !$argument.StartsWith('-', [StringComparison]::Ordinal) -or $argument -match '[\x00\r\n]') {
            throw 'JMH JVM arguments must be individual nonempty option strings without line breaks.'
        }
        $key = $null; $value = $null
        if ($argument -cmatch '^-D(?<name>[^=]+)(?:=(?<value>.*))?$') {
            $key = 'D:' + $Matches['name']
            $value = if ($Matches.ContainsKey('value')) { $Matches['value'] } else { '' }
        } elseif ($argument -cmatch '^-X(ms|mx|ss)(.+)$') {
            $key = 'X:' + $Matches[1]; $value = $Matches[2]
        } elseif ($argument -cmatch '^-XX:([+-])([^=]+)$') {
            $key = 'XX:' + $Matches[2]; $value = if ($Matches[1] -ceq '+') { 'true' } else { 'false' }
        } elseif ($argument -cmatch '^-XX:([^=]+)=(.*)$') {
            $key = 'XX:' + $Matches[1]; $value = $Matches[2]
        } elseif ($argument -cin @('-Xint', '-Xcomp', '-Xmixed')) {
            $key = 'execution-mode'; $value = $argument
        }
        if ($null -ne $key) {
            if ($settings.ContainsKey($key) -and $settings[$key] -cne $value) {
                throw "Conflicting duplicate JVM setting: $key"
            }
            $settings[$key] = $value
        }
    }
}

function Get-JmhComparableArguments($Arguments,
        [string[]]$IgnoredProperties = @('cachedb.bench.output', 'cachedb.bench.case')) {
    Assert-JmhJvmArguments $Arguments
    foreach ($argument in $Arguments) {
        $ignored = $false
        foreach ($property in $IgnoredProperties) {
            if ($argument.StartsWith("-D$property=", [StringComparison]::Ordinal)) {
                $ignored = $true
                break
            }
        }
        if (!$ignored) { $argument }
    }
}

function Assert-JmhMetric($Metric, [string]$Label, [string]$Unit, [switch]$AllowZero) {
    # Validate CI arithmetic, not independent Student-t calibration or p99 confidence.
    Assert-JmhProperties $Metric @('score', 'scoreError', 'scoreConfidence', 'scoreUnit') $Label
    if ($Metric.scoreUnit -isnot [string] -or $Metric.scoreUnit -cne $Unit) {
        throw "Unexpected JMH metric unit: $Label (expected $Unit)"
    }
    $score = ConvertTo-JmhFiniteNumber $Metric.score "$Label/score" -Positive:(!$AllowZero) -NonNegative
    $error = ConvertTo-JmhFiniteNumber $Metric.scoreError "$Label/scoreError" -NonNegative
    if ($Metric.scoreConfidence -isnot [array] -or $Metric.scoreConfidence.Count -ne 2) {
        throw "JMH confidence interval requires two bounds: $Label"
    }
    $lower = ConvertTo-JmhFiniteNumber $Metric.scoreConfidence[0] "$Label/confidence lower"
    $upper = ConvertTo-JmhFiniteNumber $Metric.scoreConfidence[1] "$Label/confidence upper"
    if ($lower -gt $upper -or $lower -gt $score -or $upper -lt $score) {
        throw "JMH confidence interval must be ordered and contain the score: $Label"
    }
    Assert-JmhClose $lower ($score - $error) "$Label/confidence lower versus scoreError"
    Assert-JmhClose $upper ($score + $error) "$Label/confidence upper versus scoreError"
}

function Assert-JmhRawData($Metric, [int]$Forks, [int]$Iterations, [string]$Label, [switch]$AllowZero) {
    Assert-JmhProperties $Metric @('rawData') $Label
    $raw = $Metric.rawData
    if ($raw -isnot [array] -or $raw.Count -ne $Forks) { throw "JMH raw fork shape mismatch: $Label" }
    [double]$mean = 0
    foreach ($fork in $raw) {
        if ($fork -isnot [array] -or $fork.Count -ne $Iterations) {
            throw "JMH raw iteration shape mismatch: $Label"
        }
        foreach ($iteration in $fork) {
            $number = ConvertTo-JmhFiniteNumber $iteration "$Label/raw iteration" -Positive:(!$AllowZero) -NonNegative
            $mean += $number / ($Forks * $Iterations)
        }
    }
    Assert-JmhClose $Metric.score $mean "$Label/score versus raw mean"
}

function Assert-JmhEvidenceRow($Result) {
    Assert-JmhProperties $Result @('benchmark', 'mode', 'threads', 'jmhVersion', 'jdkVersion', 'vmName', 'vmVersion',
        'forks', 'warmupIterations', 'measurementIterations', 'warmupTime', 'measurementTime',
        'warmupBatchSize', 'measurementBatchSize', 'jvmArgs', 'primaryMetric', 'secondaryMetrics') 'result'
    foreach ($name in @('benchmark', 'jdkVersion', 'vmName', 'vmVersion', 'warmupTime', 'measurementTime')) {
        if ($Result.$name -isnot [string] -or [string]::IsNullOrWhiteSpace($Result.$name)) {
            throw "JMH evidence requires a nonempty string: $name"
        }
    }
    if ($Result.jmhVersion -cne '1.37' -or $Result.jmhVersion -isnot [string]) { throw 'Unsupported JMH evidence version.' }
    if ($Result.mode -cnotin @('thrpt', 'sample')) { throw 'Unsupported JMH evidence mode.' }
    Assert-JmhInteger $Result.threads 1 1000 'threads'
    Assert-JmhInteger $Result.forks 3 64 'forks'
    Assert-JmhInteger $Result.warmupIterations 5 10000 'warmupIterations'
    Assert-JmhInteger $Result.measurementIterations 5 10000 'measurementIterations'
    Assert-JmhInteger $Result.warmupBatchSize 1 1 'warmupBatchSize'
    Assert-JmhInteger $Result.measurementBatchSize 1 1 'measurementBatchSize'
    if ($Result.forks * $Result.measurementIterations -gt 16384) { throw 'JMH raw iteration budget exceeded.' }
    Assert-JmhJvmArguments $Result.jvmArgs
    $label = "$($Result.benchmark)/$($Result.mode)/$($Result.threads)"
    $unit = if ($Result.mode -ceq 'thrpt') { 'ops/us' } else { 'us/op' }
    Assert-JmhMetric $Result.primaryMetric "$label/primary" $unit
    if ($Result.mode -ceq 'thrpt') {
        Assert-JmhRawData $Result.primaryMetric $Result.forks $Result.measurementIterations "$label/primary"
    } else {
        # The existing histogram contract fails closed outside JMH 1.37 / three forks / five iterations.
        $proof = Assert-Jmh137HistogramConsistency $Result
        [double]$mean = 0
        foreach ($fork in $Result.primaryMetric.rawDataHistogram) {
            foreach ($iteration in $fork) {
                foreach ($bucket in $iteration) { $mean += [double]$bucket[0] * ([double]$bucket[1] / $proof.samples) }
            }
        }
        Assert-JmhClose $Result.primaryMetric.score $mean "$label/primary score versus histogram mean"
    }
    Assert-JmhProperties $Result.secondaryMetrics @('gc.alloc.rate.norm') "$label/secondaryMetrics"
    $gc = $Result.secondaryMetrics.'gc.alloc.rate.norm'
    Assert-JmhMetric $gc "$label/gc.alloc.rate.norm" 'B/op' -AllowZero
    Assert-JmhRawData $gc $Result.forks $Result.measurementIterations "$label/gc.alloc.rate.norm" -AllowZero
}
