# Shared bounded JMH 1.37 SampleTime validation. No samples or summaries are rewritten.
# Official sources: openjdk/jmh tag 1.37, JSONResultFormat and MultisetStatistics.
# https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java
# https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/util/MultisetStatistics.java
function Get-Jmh137WeightedValue {
    param([double[]]$Values, [long[]]$Cumulative, [long]$Index)
    if ($Index -le 1) { return $Values[0] }
    if ($Index -ge $Cumulative[-1]) { return $Values[-1] }
    $lo=0; $hi=$Values.Length-1
    while ($lo -lt $hi) {
        $mid=$lo + (($hi-$lo) -shr 1)
        if ($Cumulative[$mid] -ge $Index) { $hi=$mid } else { $lo=$mid+1 }
    }
    return $Values[$lo]
}

function Convert-Jmh137Double {
    param($Value)
    if ($Value -isnot [double] -and $Value -isnot [long] -and $Value -isnot [int]) {
        throw 'JMH histogram requires JSON numeric binary64 values.'
    }
    [double]$number=$Value
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt 0 -or
            [BitConverter]::DoubleToInt64Bits($number) -lt 0) {
        throw 'JMH histogram requires finite nonnegative values without negative zero.'
    }
    return $number
}

function Assert-Jmh137HistogramConsistency {
    param([Parameter(Mandatory)]$Result)
    if ($Result.jmhVersion -isnot [string] -or $Result.jmhVersion -cne '1.37' -or $Result.mode -cne 'sample' -or
            $Result.primaryMetric.scoreUnit -cne 'us/op' -or $Result.forks -ne 3 -or $Result.measurementIterations -ne 5) {
        throw 'Unsupported JMH histogram contract: requires 1.37/sample/us-op/3 forks/5 iterations.'
    }
    $raw=$Result.primaryMetric.rawDataHistogram
    if ($raw -isnot [array] -or $raw.Count -ne 3) { throw 'JMH histogram requires three raw forks.' }
    $counts=[Collections.Generic.Dictionary[double,long]]::new()
    [long]$total=0; [long]$bucketReads=0
    foreach ($fork in $raw) {
        if ($fork -isnot [array] -or $fork.Count -ne 5) { throw 'JMH histogram requires five iterations per fork.' }
        foreach ($iteration in $fork) {
            if ($iteration -isnot [array] -or $iteration.Count -lt 1 -or $iteration.Count -gt 55296) {
                throw 'JMH histogram exceeds the SampleBuffer cell bound or is empty.'
            }
            [double]$previous=-1
            foreach ($bucket in $iteration) {
                if ($bucket -isnot [array] -or $bucket.Count -ne 2) { throw 'Malformed JMH histogram bucket.' }
                [double]$value=Convert-Jmh137Double $bucket[0]
                if ($value -le $previous) { throw 'JMH histogram iteration keys must be unique and sorted.' }
                $previous=$value
                if (($bucket[1] -isnot [long] -and $bucket[1] -isnot [int]) -or
                        $bucket[1] -lt 1 -or $bucket[1] -gt [int]::MaxValue) {
                    throw 'JMH histogram count must be a positive int32 JSON integer.'
                }
                [long]$count=$bucket[1]
                [long]$old=0
                $exists=$counts.TryGetValue($value,[ref]$old)
                if ($old + $count -gt [int]::MaxValue) { throw 'Unsupported JMH SampleBuffer aggregate int32 overflow.' }
                if (!$exists -and $counts.Count -ge 55296) { throw 'JMH histogram unique-cell bound exceeded.' }
                $counts[$value]=$old+$count
                $total+=$count; $bucketReads++
                if ($bucketReads -gt 829440) { throw 'JMH histogram input-cell bound exceeded.' }
            }
        }
    }
    [double[]]$values=[double[]]::new($counts.Count)
    $counts.Keys.CopyTo($values,0)
    [Array]::Sort($values)
    [long[]]$cumulative=[long[]]::new($values.Length)
    [long]$sum=0
    for ($i=0;$i -lt $values.Length;$i++) { $sum+=$counts[$values[$i]]; $cumulative[$i]=$sum }
    $ranks=@('0.0','50.0','90.0','95.0','99.0','99.9','99.99','99.999','99.9999','100.0')
    $properties=@($Result.primaryMetric.scorePercentiles.PSObject.Properties)
    if ($properties.Count -ne $ranks.Count) { throw 'JMH histogram requires every official primary percentile.' }
    $computed=[ordered]@{}
    foreach ($key in $ranks) {
        $property=$Result.primaryMetric.scorePercentiles.PSObject.Properties[$key]
        if ($null -eq $property) { throw "Missing JMH percentile: $key" }
        [double]$reported=Convert-Jmh137Double $property.Value
        [double]$rank=[double]::Parse($key,[Globalization.CultureInfo]::InvariantCulture)
        if ($rank -eq 0) { [double]$expected=$values[0] }
        else {
            # JMH 1.37 MultisetStatistics: preserve operation order, not nearest-rank.
            [double]$position=($rank * [double]($total+1)) / [double]100
            [double]$floor=[Math]::Floor($position)
            [double]$lower=Get-Jmh137WeightedValue $values $cumulative ([long]$floor)
            [double]$upper=Get-Jmh137WeightedValue $values $cumulative ([long]$floor+1)
            [double]$difference=$upper-$lower
            [double]$fraction=$position-$floor
            [double]$product=$difference*$fraction
            [double]$expected=$lower+$product
        }
        # JSONResultFormat emits String.valueOf(double): compare round-tripped bits, no epsilon.
        if ([BitConverter]::DoubleToInt64Bits($reported) -ne [BitConverter]::DoubleToInt64Bits($expected)) {
            throw "JMH histogram percentile mismatch: p$key reported=$reported expected=$expected"
        }
        $computed[$key]=$expected
    }
    return [pscustomobject]@{samples=$total; uniqueValues=$values.Length; inputBuckets=$bucketReads;
        percentilesChecked=$ranks.Count; percentiles=$computed}
}

