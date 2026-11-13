#requires -Version 7.2
<#
.SYNOPSIS
Bounded loopback HTTP regression controls for the anonymous Maven probe.
.DESCRIPTION
Runs the real checker against synthetic responses, not a public repository.
Does not build, install, publish, or qualify SDK artifacts or remote availability.
#>
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-public-maven-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
$checker = Join-Path $RepositoryRoot 'tools/ci/check-public-maven-repository.ps1'
$builder = Join-Path $RepositoryRoot 'tools/release/build-public-maven-repository.ps1'
$inputs = @($checker, $builder, $PSCommandPath)
$before = @{}
foreach ($path in $inputs) { $before[$path] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
$controls = [Collections.Generic.List[string]]::new()
$clock = [Diagnostics.Stopwatch]::StartNew()
function Pass([string]$Name) { $controls.Add($Name); Write-Host "PASS $Name" }
function Expected-Paths([string]$Release) {
    $relative = @(
        "cachedb-bom/$Release/cachedb-bom-$Release.pom",
        "cachedb-spring-boot-starter-postgres/$Release/cachedb-spring-boot-starter-postgres-$Release.pom",
        "cachedb-spring-boot-starter-mssql/$Release/cachedb-spring-boot-starter-mssql-$Release.pom",
        "cachedb-spring-boot-starter-oracle/$Release/cachedb-spring-boot-starter-oracle-$Release.pom",
        "cachedb-maven-plugin/$Release/cachedb-maven-plugin-$Release.pom",
        "cachedb-core/$Release/cachedb-core-$Release.jar"
    )
    if ([version]$Release -ge [version]'0.14.0') {
        $relative += @("cachedb-json/$Release/cachedb-json-$Release.pom", "cachedb-json/$Release/cachedb-json-$Release.jar")
    }
    if ([version]$Release -ge [version]'0.19.0') {
        foreach ($module in @('cachedb-reactor', 'cachedb-spring-data')) {
            foreach ($suffix in @('pom', 'jar')) { $relative += "$module/$Release/$module-$Release.$suffix" }
        }
    }
    @($relative | ForEach-Object { "/maven2/com/reactor/cachedb/$_" })
}

# The listener is loopback-only. The worker has a request cap and a wall deadline.
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = ([Net.IPEndPoint]$probe.LocalEndpoint).Port
$probe.Stop()
$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$port/")
$state = [Collections.Concurrent.ConcurrentDictionary[string, object]]::new()
$state['stop'] = $false
$state['faultPath'] = ''
$state['faultStatus'] = 200
$state['empty'] = $false
$requests = [Collections.Concurrent.ConcurrentQueue[object]]::new()
$worker = [powershell]::Create()
$server = {
    param($Listener, $State, $Requests)
    $ErrorActionPreference = 'Stop'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $count = 0
    try {
        $pending = $Listener.GetContextAsync()
        while (!$State['stop'] -and $timer.Elapsed.TotalSeconds -lt 180 -and $count -lt 256) {
            if (!$pending.Wait(250)) { continue }
            $context = $pending.GetAwaiter().GetResult()
            $count++
            $path = $context.Request.Url.AbsolutePath
            $Requests.Enqueue([pscustomobject]@{
                path = $path; method = $context.Request.HttpMethod
                hasAuthorization = ![string]::IsNullOrEmpty($context.Request.Headers['Authorization'])
                hasCookie = ![string]::IsNullOrEmpty($context.Request.Headers['Cookie'])
            })
            $fault = $path -ceq [string]$State['faultPath']
            $status = if ($fault) { [int]$State['faultStatus'] } else { 200 }
            $body = [Text.Encoding]::UTF8.GetBytes('Synthetic HTTP response; not an SDK artifact.')
            if ($fault -and $State['empty']) { $body = [byte[]]::new(0) }
            try {
                $context.Response.StatusCode = $status
                if ($status -eq 401) { $context.Response.Headers.Add('WWW-Authenticate', 'Basic realm="synthetic"') }
                $context.Response.ContentType = 'application/octet-stream'
                $context.Response.ContentLength64 = $body.Length
                if ($body.Length) { $context.Response.OutputStream.Write($body, 0, $body.Length) }
            } finally { $context.Response.Close() }
            $pending = $Listener.GetContextAsync()
        }
        if (!$State['stop']) { throw 'Synthetic server exceeded its request or wall-clock bound.' }
    } catch {
        if (!$State['stop']) { $State['error'] = $_.Exception.Message }
    }
}
$null = $worker.AddScript($server.ToString()).AddArgument($listener).AddArgument($state).AddArgument($requests)
$handle = $null
function Run-Case([string]$Name, [string]$Release, [string]$FaultPath = '', [int]$Status = 200, [bool]$Empty = $false) {
    $state['faultPath'] = $FaultPath
    $state['faultStatus'] = $Status
    $state['empty'] = $Empty
    $summaryPath = Join-Path $evidence "$Name.md"
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })
    foreach ($argument in @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $checker,
            '-Version', $Release, '-BaseUrl', "http://127.0.0.1:$port/maven2/", '-SummaryPath', $summaryPath)) {
        $process.StartInfo.ArgumentList.Add($argument)
    }
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $budget = [int][Math]::Min(20000, 180000 - $clock.ElapsedMilliseconds)
        if ($budget -le 0) { throw 'Synthetic controls exceeded their 180-second bound.' }
        if (!$process.Start()) { throw 'Cannot start checker child.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit($budget)) { $process.Kill($true); $process.WaitForExit(); throw "Checker exceeded bound: $Name" }
        $log = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($log.Length -gt 65536) { throw 'Checker output exceeded 64 KiB.' }
        [IO.File]::WriteAllText((Join-Path $evidence "$Name.log"), $log)
        $negative = $FaultPath -ne ''
        if (($process.ExitCode -eq 0) -eq $negative) { throw "Unexpected checker exit for $Name`: $($process.ExitCode)" }
    } finally { $process.Dispose() }
    if ($state.ContainsKey('error')) { throw "Synthetic server failed: $($state['error'])" }
    $observed = [Collections.Generic.List[object]]::new()
    $request = $null
    while ($requests.TryDequeue([ref]$request)) { $observed.Add($request) }
    $observed | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $evidence "$Name.requests.json") -Encoding utf8
    $expected = @(Expected-Paths $Release)
    if ($observed.Count -ne $expected.Count -or
            @(Compare-Object @($observed.path) $expected -CaseSensitive).Count -or
            @($observed | Where-Object { $_.method -cne 'GET' -or $_.hasAuthorization -or $_.hasCookie }).Count) {
        throw "Unexpected artifact set, non-GET or authenticated request: $Name"
    }
    $summary = Get-Content -LiteralPath $summaryPath -Raw
    $result = if ($negative) { 'FAIL' } else { 'PASS' }
    if (!$summary.Contains('- Result: `' + $result + '`') -or !$summary.Contains('- Authentication: `not required`')) {
        throw "Wrong summary outcome: $Name"
    }
    if ($negative) {
        $artifact = $FaultPath.Substring('/maven2/com/reactor/cachedb/'.Length)
        if (!$summary.Contains('| `' + $artifact + '` | `FAIL` |')) { throw "Failing artifact is not identified: $Name" }
    }
    Pass $Name
}
try {
    $listener.Start()
    $handle = $worker.BeginInvoke()
    foreach ($release in @('0.13.9', '0.14.0', '0.18.1', '0.19.0', '0.20.0')) {
        Run-Case "positive-$release-exact-inventory" $release
    }
    foreach ($module in @('cachedb-reactor', 'cachedb-spring-data')) {
        foreach ($suffix in @('pom', 'jar')) {
            $path = "/maven2/com/reactor/cachedb/$module/0.19.0/$module-0.19.0.$suffix"
            Run-Case "missing-$module-$suffix-rejected" '0.19.0' $path 404
            Run-Case "empty-$module-$suffix-rejected" '0.19.0' $path 200 $true
        }
    }
    $protected = '/maven2/com/reactor/cachedb/cachedb-reactor/0.19.0/cachedb-reactor-0.19.0.jar'
    foreach ($status in @(401, 403)) { Run-Case "http-$status-rejected-anonymously" '0.19.0' $protected $status }
    Run-Case 'positive-after-negative-controls' '0.19.0'

    # Inspect the staging label only; never run a build or publish in these controls.
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($builder, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'Builder parse error.' }
    $labels = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Write-Host' -and
        $node.Extent.Text -match 'repository URL:|Intended publication URL'
    }, $true))
    if ($labels.Count -ne 1 -or $labels[0].Extent.Text -cne
            'Write-Host "Intended publication URL (not deployed by this script): https://esasmer-dou.github.io/cache-database/maven2"') {
        throw 'Staging output must distinguish intended publication URL from deployment.'
    }
    Pass 'staging-label-does-not-claim-publication'
    foreach ($path in $inputs) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $before[$path]) { throw 'Source changed during controls.' }
    }
    Pass 'checker-builder-and-controls-source-unchanged'
} finally {
    $state['stop'] = $true
    $listener.Close()
    if ($null -ne $handle) {
        if (!$handle.AsyncWaitHandle.WaitOne(2000)) { $worker.Stop() }
        $null = $worker.EndInvoke($handle)
    }
    $worker.Dispose()
}
[ordered]@{
    result = 'PASS'; controls = $controls; sourceSha256 = $before
    syntheticLoopbackHttpOnly = $true; remotePublicationVerified = $false
    sdkArtifactsQualified = $false; javaExecuted = $false; mavenExecuted = $false
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
Write-Host "SYNTHETIC_PUBLIC_MAVEN_CONTROLS_PASS=$($controls.Count); NO_REMOTE_OR_ARTIFACT_ACCEPTANCE"
