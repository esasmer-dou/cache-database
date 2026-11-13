#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$BaseUrl,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{32}$')][string]$RunId,
    [Parameter(Mandatory)][switch]$ConfirmDisposableDemo,
    [ValidateNotNullOrEmpty()][ValidatePattern('(?-i)\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\z')][string]$RedisServiceName = 'redis'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (!$IsLinux -or $env:CACHEDB_TEST_NAMESPACE -cne 'cachedb-v019-validation') {
    throw 'Linux execution in the dedicated CacheDB validation namespace required.'
}
if ($env:CACHEDB_TEST_DATASOURCE_URL -cnotmatch '^jdbc:sqlserver://mssql\.cachedb-v019-validation\.svc\.cluster\.local:1433;' -or
        $env:CACHEDB_TEST_REDIS_URI -cnotmatch ('^redis://' + [regex]::Escape($RedisServiceName) +
            '\.cachedb-v019-validation\.svc\.cluster\.local:6379/(?:0|[1-9][0-9]*)\z')) {
    throw 'Required SQL and Redis fixtures must use their dedicated validation namespace Services.'
}
if (!$ConfirmDisposableDemo -or !$BaseUrl.IsAbsoluteUri -or $BaseUrl.Scheme -cne 'http' -or
        $BaseUrl.UserInfo -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.AbsolutePath -ne '/') {
    throw 'Explicit disposable companion HTTP origin and confirmation required.'
}
$token = $env:SAMPLE_PURGE_ADMIN_TOKEN
if (!$token -or $token -cnotmatch '^[A-Za-z0-9_-]{32,128}$') { throw 'Bounded SAMPLE_PURGE_ADMIN_TOKEN required.' }
$root = Split-Path -Parent $PSScriptRoot
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
$provider = ([string]$pom.project.artifactId).Replace('sample-cache-database-', '')
if ($provider -cnotin @('postgresql','mssql','oracle')) { throw 'Unknown sample provider.' }
$collection = Join-Path $root "postman/cache-database-$provider-tenant-purge.postman_collection.json"
$operation = [Guid]::NewGuid().ToString('D')
$output = Join-Path $root "target/tenant-purge-postman/$RunId/$operation"
if (Test-Path -LiteralPath $output) { throw 'Fresh evidence directory required.' }
$null = [IO.Directory]::CreateDirectory($output,
    ([IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute))
$private = Join-Path $output '.private-environment.json'
$junit = Join-Path $output 'newman.xml'
$utf8 = [Text.UTF8Encoding]::new($false)
$values = @{
    baseUrl = $BaseUrl.GetLeftPart([UriPartial]::Authority)
    purgeRunId = $RunId
    purgeAdminToken = $token
    purgeOperationId = $operation
}
$environment = @{name='Private native purge run'; values=@(
    foreach ($key in $values.Keys) { @{key=$key; value=$values[$key]; enabled=$true; type='secret'} }
)} | ConvertTo-Json -Depth 6
$options = [IO.FileStreamOptions]::new()
$options.Mode = [IO.FileMode]::CreateNew
$options.Access = [IO.FileAccess]::Write
$options.UnixCreateMode = [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite
$process = $null
try {
    $stream = [IO.FileStream]::new($private, $options)
    try { $bytes = $utf8.GetBytes($environment); $stream.Write($bytes, 0, $bytes.Length) }
    finally { $stream.Dispose() }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = 'npx'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.WorkingDirectory = $root
    foreach ($argument in @('--yes','--offline','--package','newman@6.2.1','newman','run',$collection,
            '--environment',$private,'--ignore-redirects','--timeout','890000',
            '--timeout-request','90000','--timeout-script','25000','--reporters','cli,junit',
            '--reporter-junit-export',$junit,'--reporter-cli-no-console','--bail')) {
        $process.StartInfo.ArgumentList.Add($argument)
    }
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $finished = $process.WaitForExit(900000)
    if (!$finished) { $process.Kill($true); $null = $process.WaitForExit(5000) }
    if (!$process.HasExited -or !$stdout.Wait(5000) -or !$stderr.Wait(5000)) {
        throw 'Newman termination/output drain exceeded the finite bound; cohort requires inspection.'
    }
    [IO.File]::WriteAllText((Join-Path $output 'newman.stdout.log'),
        $stdout.GetAwaiter().GetResult().Replace($token, '[redacted]'), $utf8)
    [IO.File]::WriteAllText((Join-Path $output 'newman.stderr.log'),
        $stderr.GetAwaiter().GetResult().Replace($token, '[redacted]'), $utf8)
    if (!(Test-Path -LiteralPath $junit)) { throw 'Newman did not produce JUnit evidence; logs retained.' }
    $report = [IO.File]::ReadAllText($junit)
    if ($report.Contains($token)) {
        [IO.File]::WriteAllText($junit, $report.Replace($token, '[redacted]'), $utf8)
        throw 'Credential output rejected; report redacted and evidence failed.'
    }
    if (!$finished -or $process.ExitCode -ne 0) { throw 'Newman failed or exceeded 900 seconds; evidence retained.' }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create([IO.StringReader]::new($report), $settings)
    $xml = [Xml.XmlDocument]::new()
    $xml.XmlResolver = $null
    try { $xml.Load($reader) } finally { $reader.Dispose() }
    if ($xml.DocumentElement.LocalName -cne 'testsuites') { throw 'Expected Newman testsuites root.' }
    $expected = @(
        '01 Anonymous Status','02 Invalid Bearer Status','03 Native INITIAL Status','04 Owned Hot Row',
        '05 Owned Cold Row Fails Closed','06 Purge Owned Tenant Including Source','07 Retained CLOSED Status',
        '08 Closed Delivery Rejected'
    )
    $requiredAssertions = @{
        '01 Anonymous Status' = @('No redirects','No credential output','Exact HTTP 401')
        '02 Invalid Bearer Status' = @('No redirects','No credential output','Exact HTTP 403')
        '03 Native INITIAL Status' = @('No redirects','No credential output','Exact HTTP 200','Actual two-root INITIAL state','Bounded owned SQL and hot state','Foreign SQL and encrypted Redis sentinel preserved')
        '04 Owned Hot Row' = @('No redirects','No credential output','Exact HTTP 200','Owned hot row delivered')
        '05 Owned Cold Row Fails Closed' = @('No redirects','No credential output','Exact HTTP 409')
        '06 Purge Owned Tenant Including Source' = @('No redirects','No credential output','Exact HTTP 200','Actual two-root CLOSED state','Bounded owned SQL and hot state','Foreign SQL and encrypted Redis sentinel preserved','Include-source purge retains security metadata')
        '07 Retained CLOSED Status' = @('No redirects','No credential output','Exact HTTP 200','Actual two-root CLOSED state','Bounded owned SQL and hot state','Foreign SQL and encrypted Redis sentinel preserved','Include-source purge retains security metadata')
        '08 Closed Delivery Rejected' = @('No redirects','No credential output','Exact HTTP 409')
    }
    $counts = @(3,3,6,4,3,7,7,3)
    $suites = @($xml.SelectNodes('/testsuites/testsuite'))
    if ($suites.Count -ne 8 -or @($xml.SelectNodes('//failure|//error|//skipped')).Count -ne 0) {
        throw 'All eight original requests must execute without failure/error/skip.'
    }
    for ($index = 0; $index -lt 8; $index++) {
        $suite = $suites[$index]
        $cases = @($suite.SelectNodes('./testcase'))
        if ($suite.GetAttribute('name') -cne $expected[$index] -or $cases.Count -ne $counts[$index]) {
            throw 'Exact request order and assertion counts required.'
        }
        if ($suite.GetAttribute('tests') -cne [string]$counts[$index]) {
            throw 'JUnit declared assertion count differs from actual nodes.'
        }
        foreach ($attribute in @('failures','errors','skipped')) {
            if ($suite.HasAttribute($attribute) -and $suite.GetAttribute($attribute) -cne '0') {
                throw 'Declared failure/error/skip rejected.'
            }
        }
        $names = @($cases | ForEach-Object { $_.GetAttribute('name') })
        if (@($names | Select-Object -Unique).Count -ne $counts[$index]) {
            throw 'Original request assertions required.'
        }
        foreach ($assertion in $requiredAssertions[$expected[$index]]) {
            if ($names -cnotcontains $assertion) { throw 'Exact original assertion inventory required.' }
        }
        foreach ($case in $cases) {
            if ($case.HasAttribute('status') -and $case.GetAttribute('status') -cne 'passed') {
                throw 'Non-passing assertion rejected.'
            }
        }
    }
    [IO.File]::WriteAllText((Join-Path $output 'summary.json'),
        (@{status='POSTMAN_ONLY_PASS'; provider=$provider; requests=8; assertions=36;
            runId=$RunId; operationId=$operation; redirectsIgnored=$true} | ConvertTo-Json), $utf8)
    Write-Output "Purge Postman: eight requests, 36 assertions; evidence: $output"
} finally {
    if (Test-Path -LiteralPath $private) { Remove-Item -LiteralPath $private -Force }
    $token = $null; $environment = $null; $values.Clear()
    if ($null -ne $process) { $process.Dispose() }
}
