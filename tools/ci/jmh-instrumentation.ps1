# Shared case identity and fail-closed, raw-SHA-bound instrumentation contracts.
function Get-JmhCaseKey($Result) {
    if ([string]$Result.benchmark -cnotmatch '^[A-Za-z0-9_.$]{1,1024}$' -or
            [string]$Result.mode -cnotin @('thrpt', 'sample') -or $Result.threads -le 0) {
        throw 'Invalid JMH instrumentation case identity.'
    }
    $parameters = @()
    if ($Result.PSObject.Properties.Name -contains 'params') {
        $names = [string[]]@($Result.params.PSObject.Properties.Name)
        [Array]::Sort($names, [StringComparer]::Ordinal)
        foreach ($name in $names) {
            $value = $Result.params.$name
            if ($name -cnotmatch '^[A-Za-z0-9_.-]{1,128}$' -or $value -isnot [string] -or
                    $value -cnotmatch '^[A-Za-z0-9_.:/+ -]{0,1024}$') {
                throw 'Ambiguous JMH instrumentation case parameter.'
            }
            $parameters += "$name=$value"
        }
    }
    return "$($Result.benchmark)|$($Result.mode)|threads=$($Result.threads)|$($parameters -join ',')"
}

function Assert-JmhInstrumentationFields($Value, [string[]]$Names, [string]$Label) {
    if ($null -eq $Value -or $Value -isnot [pscustomobject]) { throw "Invalid instrumentation object: $Label" }
    $actual = [string[]]@($Value.PSObject.Properties.Name)
    $expected = [string[]]@($Names)
    [Array]::Sort($actual, [StringComparer]::Ordinal)
    [Array]::Sort($expected, [StringComparer]::Ordinal)
    if (($actual -join "`n") -cne ($expected -join "`n")) { throw "Invalid instrumentation fields: $Label" }
}

function ConvertTo-JmhInstrumentationContract($Contract) {
    Assert-JmhInstrumentationFields $Contract @('controllerProfilers', 'helpers', 'sampler') 'contract'
    if ($Contract.controllerProfilers -isnot [array] -or $Contract.controllerProfilers.Count -lt 1 -or
            $Contract.controllerProfilers.Count -gt 16 -or $Contract.helpers -isnot [array] -or
            $Contract.helpers.Count -gt 32) { throw 'Invalid instrumentation profiler/helper array.' }
    $profilers = [string[]]@()
    foreach ($profiler in $Contract.controllerProfilers) {
        if ($profiler -isnot [string] -or $profiler.Length -gt 4096 -or
                $profiler -cnotmatch '^[A-Za-z0-9_.$]+(?::[^\x00-\x1f\x7f]+)?$') {
            throw 'Invalid instrumentation profiler.'
        }
        $profilers += $profiler
    }
    $helpers = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($helper in $Contract.helpers) {
        Assert-JmhInstrumentationFields $helper @('identity', 'sha256') 'helper'
        if ($helper.identity -isnot [string] -or $helper.identity -cnotmatch '^[A-Za-z0-9_:.$-]{1,1024}$' -or
                $helper.sha256 -isnot [string] -or $helper.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
                $helpers.ContainsKey($helper.identity)) { throw 'Invalid or duplicate instrumentation helper identity/hash.' }
        $helpers.Add($helper.identity, [pscustomobject][ordered]@{ identity = $helper.identity; sha256 = $helper.sha256 })
    }
    $required = @('resource-collector', 'resource-launcher', 'sidecar-writer', 'sidecar-launcher')
    foreach ($profiler in $profilers) {
        $name = ($profiler -split ':', 2)[0]
        if ($name -cne 'gc') { $required += "profiler:$name" }
    }
    $required = [string[]]@($required | Select-Object -Unique)
    if ($helpers.Count -ne $required.Count) { throw 'Instrumentation helper roster differs from active profilers.' }
    foreach ($identity in $required) {
        if (!$helpers.ContainsKey($identity)) { throw "Missing instrumentation helper: $identity" }
    }
    if (@($profilers | Where-Object { ($_ -split ':', 2)[0] -ceq
                    'com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler' }).Count -ne 1) {
        throw 'Instrumentation requires the actual resource phase profiler exactly once.'
    }
    Assert-JmhInstrumentationFields $Contract.sampler @('intervalMs', 'maxRowsPerFile', 'maxBytesPerFile', 'maxForks', 'redisTarget') 'sampler'
    $limits = [ordered]@{ intervalMs = @(100, 5000); maxRowsPerFile = @(1, 100000);
        maxBytesPerFile = @(4096, 67108864); maxForks = @(1, 128) }
    $sampler = [ordered]@{}
    foreach ($name in $limits.Keys) {
        $value = $Contract.sampler.$name
        if (($value -isnot [int] -and $value -isnot [long]) -or $value -lt $limits[$name][0] -or
                $value -gt $limits[$name][1]) { throw "Invalid instrumentation sampler setting: $name" }
        $sampler[$name] = [long]$value
    }
    if ($Contract.sampler.redisTarget -cnotin @('UNAVAILABLE_EXTERNAL_TARGET_NOT_SUPPLIED',
            'EXPLICIT_LOCAL_PID_TARGET_OPERATOR_ENDPOINT_BINDING_NOT_VERIFIED')) { throw 'Invalid instrumentation Redis target mode.' }
    $sampler.redisTarget = $Contract.sampler.redisTarget
    $identities = [string[]]@($helpers.Keys)
    [Array]::Sort($identities, [StringComparer]::Ordinal)
    return [pscustomobject][ordered]@{ controllerProfilers = $profilers;
        helpers = @($identities | ForEach-Object { $helpers[$_] }); sampler = [pscustomobject]$sampler }
}

function Assert-JmhInstrumentationCoverage([object[]]$Rows, $Contracts) {
    if ($null -eq $Contracts -or $Contracts.Count -ne $Rows.Count) { throw 'Missing or extra instrumentation cases.' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($row in $Rows) {
        $key = Get-JmhCaseKey $row
        if (!$seen.Add($key) -or !$Contracts.ContainsKey($key)) { throw "Missing or duplicate instrumentation case: $key" }
        $null = ConvertTo-JmhInstrumentationContract $Contracts[$key]
    }
}

function Read-JmhInstrumentation([string]$RawPath, [object[]]$Rows) {
    $path = "$RawPath.instrumentation"
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing instrumentation sidecar: $path" }
    $file = Get-Item -LiteralPath $path
    if ($file.Length -le 0 -or $file.Length -gt 4MB -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Invalid instrumentation sidecar file.'
    }
    $document = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-JmhInstrumentationFields $document @('schema', 'rawSha256', 'cases') 'document'
    if ($document.schema -cne 'JMH_INSTRUMENTATION_V1' -or $document.rawSha256 -cnotmatch '^[a-f0-9]{64}$' -or
            $document.rawSha256 -cne (Get-FileHash -LiteralPath $RawPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Instrumentation raw SHA256 mismatch or invalid schema.'
    }
    if ($document.cases -isnot [array] -or $document.cases.Count -lt 1 -or $document.cases.Count -gt 256) {
        throw 'Invalid instrumentation case array.'
    }
    $contracts = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($record in $document.cases) {
        Assert-JmhInstrumentationFields $record @('key', 'contract') 'case'
        if ($record.key -isnot [string] -or $record.key.Length -gt 8192 -or $contracts.ContainsKey($record.key)) {
            throw 'Invalid or duplicate instrumentation case key.'
        }
        $contracts.Add($record.key, (ConvertTo-JmhInstrumentationContract $record.contract))
    }
    Assert-JmhInstrumentationCoverage $Rows $contracts
    return ,$contracts
}

function Write-JmhInstrumentation([string]$RawPath, [object[]]$Rows, $Contracts) {
    Assert-JmhInstrumentationCoverage $Rows $Contracts
    $records = @($Rows | ForEach-Object {
        $key = Get-JmhCaseKey $_
        [pscustomobject][ordered]@{ key = $key; contract = (ConvertTo-JmhInstrumentationContract $Contracts[$key]) }
    })
    $document = [ordered]@{ schema = 'JMH_INSTRUMENTATION_V1';
        rawSha256 = (Get-FileHash -LiteralPath $RawPath -Algorithm SHA256).Hash.ToLowerInvariant(); cases = $records }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $document -Depth 20))
    $stream = [IO.File]::Open("$RawPath.instrumentation", [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
}

function Assert-MatchingJmhInstrumentation($Before, $After, [string]$Key) {
    $old = ConvertTo-Json -InputObject (ConvertTo-JmhInstrumentationContract $Before) -Depth 20 -Compress
    $new = ConvertTo-Json -InputObject (ConvertTo-JmhInstrumentationContract $After) -Depth 20 -Compress
    if ($old -cne $new) { throw "Instrumentation settings mismatch: $Key" }
}
