#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CollectionPath,
    [Parameter(Mandatory)][string]$ReportPath,
    [Parameter(Mandatory)][ValidateSet('Business', 'Catalog', 'Controls', 'CacheApi', 'RangeData', 'Encryption', 'Audit')][string]$Kind
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false, $true)
function Read-Json([string]$Path, [long]$MaximumBytes) {
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer -or $file.Length -gt $MaximumBytes) { throw 'Postman evidence exceeds its size budget.' }
    return [IO.File]::ReadAllText($file.FullName, $utf8) | ConvertFrom-Json -AsHashtable -Depth 32
}
function Get-Requests($Items, [int]$Depth = 0) {
    if ($Depth -gt 8) { throw 'Postman nesting exceeds eight levels.' }
    foreach ($item in $Items) {
        if ($item.Contains('request')) { $item }
        elseif ($item.Contains('item')) { Get-Requests $item.item ($Depth + 1) }
        else { throw 'Unexpected Postman collection node.' }
    }
}
function Script-Lines($Item, [string]$Listen) {
    if (!$Item.Contains('event')) { return '' }
    $lines = foreach ($event in @($Item.event)) {
        if ($event.listen -eq $Listen) { @($event.script.exec) }
    }
    return $lines -join [char]10
}
function Request-Url($Request) {
    $url = $Request.url
    if ($url -is [string]) { return $url }
    if ($url -isnot [Collections.IDictionary]) { throw 'Invalid Postman URL representation.' }
    # Newman serializes a whole-origin variable as host components without raw.
    foreach ($field in $url.Keys) {
        if ($field -notin @('raw', 'host', 'path', 'query', 'variable')) {
            throw "Unsupported Postman URL component: $field"
        }
    }
    if (!$url.Contains('host') -or !$url.Contains('path') -or
            @($url.host).Count -ne 1 -or $url.host[0] -isnot [string] -or
            $url.host[0] -cnotmatch '^\{\{(?:baseUrl|peerBaseUrl)\}\}$' -or
            ($url.Contains('variable') -and @($url.variable).Count -ne 0)) {
        throw 'Expected a whole-origin variable and literal path components.'
    }
    $parts = @($url.path)
    foreach ($part in $parts) {
        if ($part -isnot [string] -or $part -match '[/#?]') { throw 'Invalid Postman path component.' }
    }
    $result = $url.host[0] + '/' + ($parts -join '/')
    $query = @()
    if ($url.Contains('query')) {
        foreach ($entry in @($url.query)) {
            if ($entry -isnot [Collections.IDictionary] -or !$entry.Contains('key') -or
                    !$entry.Contains('value') -or $entry.key -isnot [string] -or
                    $entry.value -isnot [string] -or $entry.key -match '[&=#?]' -or
                    $entry.value -match '[&#]' -or
                    ($entry.Contains('disabled') -and $entry.disabled -ne $false)) {
                throw 'Invalid or disabled Postman query parameter.'
            }
            foreach ($field in $entry.Keys) {
                if ($field -notin @('key', 'value', 'disabled')) { throw 'Unsupported Postman query component.' }
            }
            $query += $entry.key + '=' + $entry.value
        }
    }
    if ($query.Count -gt 0) { $result += '?' + ($query -join '&') }
    if ($url.Contains('raw') -and ($url.raw -isnot [string] -or $url.raw -cne $result)) {
        throw 'Postman raw URL disagrees with its components.'
    }
    return $result
}
$collection = Read-Json $CollectionPath 1048576
$report = Read-Json $ReportPath 33554432
$all = @(Get-Requests $collection.item)
if ($all.Count -gt 256 -or @($all.name | Sort-Object -Unique).Count -ne $all.Count) {
    throw 'Saved request names must be bounded and unique.'
}
$folderName = '07 Method Cache and Async'
$expectedCount = @{Business=84; Catalog=23; Controls=40; CacheApi=23; RangeData=37; Encryption=10; Audit=42}[$Kind]
$expected = $all
if ($Kind -in @('Business', 'CacheApi')) {
    $folders = @($collection.item | Where-Object { $_.name -ceq $folderName })
    if ($all.Count -ne 84 -or $folders.Count -ne 1) { throw 'Business collection requires 61 legacy plus 23 new requests.' }
    $new = @(Get-Requests $folders[0].item)
    if ($new.Count -ne 23) { throw 'All 23 method-cache/async requests are required.' }
    if ($Kind -eq 'CacheApi') { $expected = $new }
}
if ($expected.Count -ne $expectedCount) { throw "Expected $expectedCount saved requests for $Kind." }
if (!$report.Contains('run') -or !$report.Contains('collection') -or
        @($report.run.failures).Count -ne 0 -or
        ($report.run.Contains('error') -and $null -ne $report.run.error)) {
    throw 'Newman reports a request/script/assertion failure or missing evidence.'
}
foreach ($name in @('requests', 'prerequestScripts', 'testScripts', 'assertions')) {
    if (!$report.run.stats.Contains($name) -or [long]$report.run.stats[$name].failed -ne 0) {
        throw "Newman $name failed or its counters are absent."
    }
}
if ([long]$report.run.stats.assertions.total -lt $expectedCount) { throw 'Insufficient executed assertion count.' }
$reported = @(Get-Requests $report.collection.item)
if (($reported.name -join [char]10) -cne ($all.name -join [char]10)) { throw 'The executed collection request set differs.' }
$definitions = @{}
foreach ($item in $all) { $definitions[$item.name] = $item }
foreach ($item in $reported) {
    $saved = $definitions[$item.name]
    if ($item.request.method -cne $saved.request.method -or
            (Request-Url $item.request) -cne (Request-Url $saved.request) -or
            (Script-Lines $item 'test') -cne (Script-Lines $saved 'test') -or
            (Script-Lines $item 'prerequest') -cne (Script-Lines $saved 'prerequest')) {
        throw "Executed request or script differs from the saved contract: $($item.name)"
    }
}
if ($Kind -in @('RangeData', 'Encryption', 'Audit')) {
    if ((Script-Lines $report.collection 'prerequest') -cne (Script-Lines $collection 'prerequest')) {
        throw 'The executed range/data feature gate differs.'
    }
    $gate = Script-Lines $collection 'prerequest'
    $feature = if ($Kind -eq 'Encryption') { 'encryptionApi' } elseif ($Kind -eq 'Audit') { 'auditApi' } else { 'rangeData' }
    if (!$gate.Contains($feature + 'Enabled') -or !$gate.Contains($feature + 'Certification')) {
        throw 'Missing strict range/data feature gate.'
    }
    foreach ($item in $reported) {
        $saved = $definitions[$item.name]
        $body = if ($item.request.Contains('body')) { [string]$item.request.body.raw } else { '' }
        $savedBody = if ($saved.request.Contains('body')) { [string]$saved.request.body.raw } else { '' }
        if ($body -cne $savedBody) { throw "Executed range/data body differs: $($item.name)" }
    }
}
if ($Kind -in @('Business', 'CacheApi')) {
    $reportedFolder = @($report.collection.item | Where-Object { $_.name -ceq $folderName })[0]
    if ((Script-Lines $reportedFolder 'prerequest') -cne (Script-Lines $folders[0] 'prerequest')) {
        throw 'The executed optional feature gate differs.'
    }
    $gate = Script-Lines $folders[0] 'prerequest'
    if (!$gate.Contains('cacheApiCertification') -or !$gate.Contains('cacheApiEnabled')) { throw 'Missing strict feature gate.' }
}
$needed = @{}
$passed = @{}
foreach ($item in $expected) {
    $needed[$item.name] = @([regex]::Matches((Script-Lines $item 'test'), "pm\.test\('([^']+)'") |
        ForEach-Object { $_.Groups[1].Value })
    if ($item.name -eq 'Latest Background Job Status (repeat until COMPLETED)') {
        $needed[$item.name] = @('Job status is available', 'Job completed before a hot read')
    }
    $passed[$item.name] = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
}
$executions = @($report.run.executions)
if ($executions.Count -lt $expectedCount -or $executions.Count -gt 4096) {
    throw 'Required execution count is missing or exceeds bounded polling capacity.'
}
if ($Kind -in @('RangeData', 'Encryption', 'Audit') -and ($executions.Count -ne $expectedCount -or
        @($executions | ForEach-Object { $_.item.name } | Sort-Object -Unique).Count -ne $expectedCount)) {
    throw 'All required scenario requests must execute exactly once.'
}
foreach ($execution in $executions) {
    $name = [string]$execution.item.name
    if (!$passed.ContainsKey($name)) { throw "Unexpected executed request: $name" }
    if (!$execution.Contains('response') -or $null -eq $execution.response) { throw "Required request was skipped or has no response: $name" }
    $code = [int]$execution.response.code
    if ($code -lt 100 -or $code -gt 599) { throw "Invalid response code: $name" }
    if ($Kind -in @('RangeData', 'Encryption', 'Audit')) {
        $http = [regex]::Match((Script-Lines $definitions[$name] 'test'), "pm\.test\('HTTP (\d{3})'")
        if (!$http.Success -or $code -ne [int]$http.Groups[1].Value) {
            throw "Range/data HTTP status differs from the saved contract: $name"
        }
    } elseif ($name.StartsWith('Cache API - ', [StringComparison]::Ordinal)) {
        $method = $definitions[$name].request.method
        $status = if ($method -eq 'DELETE') { 204 } elseif ($method -eq 'PUT') { 202 } else { 200 }
        if ($code -ne $status) { throw "Unexpected cache API response code: $name" }
    } elseif ($Kind -eq 'Business') {
        if ($name -eq 'Reject Incomplete Absolute Stock Values') {
            if ($code -ne 400) { throw 'The incomplete command must be rejected.' }
        } elseif ($code -lt 200 -or $code -gt 299) { throw "Legacy business HTTP request failed: $name" }
    }
    $assertions = if ($execution.Contains('assertions')) { @($execution.assertions) } else { @() }
    foreach ($assertion in $assertions) {
        if (($assertion.Contains('skipped') -and $assertion.skipped) -or
                ($assertion.Contains('error') -and $null -ne $assertion.error)) {
            throw "Required assertion was skipped or failed: $name"
        }
        [void]$passed[$name].Add([string]$assertion.assertion)
    }
}
foreach ($item in $expected) {
    $name = [string]$item.name
    if ($passed[$name].Count -eq 0) { throw "Required request has no executed passing assertions: $name" }
    foreach ($title in $needed[$name]) {
        if (!$passed[$name].Contains($title)) { throw "Required saved assertion did not execute: $name / $title" }
    }
}
Write-Host "$Kind : $expectedCount distinct requests executed with required assertions; no skipped or failed evidence."
