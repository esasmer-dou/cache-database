#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$BaseUrl,
    [string]$PeerBaseUrl = '',
    [Parameter(Mandatory)][switch]$ConfirmDisposableDemo,
    [string]$SupplierToken = '',
    [string]$TenantAToken = '',
    [string]$TenantBToken = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (!$ConfirmDisposableDemo -or !$BaseUrl.IsAbsoluteUri -or $BaseUrl.Scheme -notin @('http','https') -or
        $BaseUrl.UserInfo -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.AbsolutePath -ne '/') {
    throw 'Provide an explicit disposable demo HTTP(S) origin; database/Redis services and the app must already be running.'
}
$root = Split-Path -Parent $PSScriptRoot
[xml]$pom = Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw
$provider = ([string]$pom.project.artifactId).Replace('sample-cache-database-', '')
if ($provider -notin @('postgresql','mssql','oracle')) { throw 'Unknown sample provider.' }
$collection = (Resolve-Path -LiteralPath (Join-Path $root "postman/cache-database-$provider-sample.postman_collection.json")).Path
$validator = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'assert-postman-execution.ps1')).Path
$tokens = @{
    cacheSupplierToken = if ($SupplierToken) { $SupplierToken } elseif ($env:SAMPLE_CONTROLS_SUPPLIER_TOKEN) { $env:SAMPLE_CONTROLS_SUPPLIER_TOKEN } else { 'demo-supplier' }
    cacheTenantAToken = if ($TenantAToken) { $TenantAToken } elseif ($env:SAMPLE_CONTROLS_TENANT_A_TOKEN) { $env:SAMPLE_CONTROLS_TENANT_A_TOKEN } else { 'demo-shop-a' }
    cacheTenantBToken = if ($TenantBToken) { $TenantBToken } elseif ($env:SAMPLE_CONTROLS_TENANT_B_TOKEN) { $env:SAMPLE_CONTROLS_TENANT_B_TOKEN } else { 'demo-shop-b' }
}
foreach ($token in $tokens.Values) {
    if ($token -cnotmatch '^[A-Za-z0-9._~+/=-]{8,1024}$') { throw 'Demo credentials must be bounded bearer-token text.' }
}
$runId = [Guid]::NewGuid().ToString('N')
$output = Join-Path $root "target/v019-postman/$runId"
if ($IsWindows) {
    $null = New-Item -ItemType Directory -Path $output
} else {
    $mode = [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute
    $null = [IO.Directory]::CreateDirectory($output, $mode)
}
$environmentPath = Join-Path $output 'credentials.json'
$environment = @{values=@()}
foreach ($key in $tokens.Keys) { $environment.values += @{key=$key;value=$tokens[$key];enabled=$true} }
$arguments = @('--yes','newman@6.2.1','run',$collection,'--env-var',"baseUrl=$($BaseUrl.AbsoluteUri.TrimEnd('/'))",
    '--env-var','cacheApiEnabled=true','--env-var','cacheApiCertification=true',
    '--timeout','900000','--timeout-request','10000','--timeout-script','25000',
    '--reporters','cli,json,junit','--reporter-json-export',(Join-Path $output 'business-results.json'),
    '--reporter-junit-export',(Join-Path $output 'business-results.xml'),'-e',$environmentPath)
if ($PeerBaseUrl) { $arguments += @('--env-var',"peerBaseUrl=$PeerBaseUrl") }
$npx = Get-Command $(if ($IsWindows) { 'npx.cmd' } else { 'npx' }) -ErrorAction Stop
try {
    $options = [IO.FileStreamOptions]::new()
    $options.Mode = [IO.FileMode]::CreateNew
    $options.Access = [IO.FileAccess]::Write
    $options.Share = [IO.FileShare]::None
    if (!$IsWindows) { $options.UnixCreateMode = [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite }
    $file = [IO.FileStream]::new($environmentPath, $options)
    try {
        $writer = [IO.StreamWriter]::new($file, [Text.UTF8Encoding]::new($false))
        try { $writer.Write(($environment | ConvertTo-Json -Depth 4)) } finally { $writer.Dispose() }
    } finally { $file.Dispose() }
    & $npx.Source @arguments
    $exitCode = $LASTEXITCODE
    foreach ($name in @('business-results.json', 'business-results.xml')) {
        $path = Join-Path $output $name
        if (!(Test-Path -LiteralPath $path)) { continue }
        if ((Get-Item -LiteralPath $path).Length -gt 33554432) {
            Remove-Item -LiteralPath $path -Force
            throw 'Oversized report rejected; no acceptance result is recorded.'
        }
        $text = [IO.File]::ReadAllText($path)
        foreach ($token in $tokens.Values) { $text = $text.Replace($token, 'REDACTED') }
        [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    }
    if ($exitCode -ne 0) { throw 'Business Postman execution failed; no acceptance result is recorded.' }
    & $validator -CollectionPath $collection -ReportPath (Join-Path $output 'business-results.json') -Kind Business
    Write-Host "Validated 61 legacy + 23 method-cache/async requests. Evidence: $output"
    & (Join-Path $PSScriptRoot 'test-range-data-postman.ps1') -BaseUrl $BaseUrl -ConfirmDisposableDemo `
        -SupplierToken $tokens.cacheSupplierToken -TenantAToken $tokens.cacheTenantAToken -TenantBToken $tokens.cacheTenantBToken
} finally {
    if (Test-Path -LiteralPath $environmentPath) { Remove-Item -LiteralPath $environmentPath -Force }
}
