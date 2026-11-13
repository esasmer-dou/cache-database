#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$root = Join-Path $EvidenceDirectory 'source'
$stage = Join-Path $EvidenceDirectory 'staging'
$sample = 'sample-cache-database-postgresql'
$files = @{
    'README.md' = "[Sample]($sample/README.md)`n[Turkish](tr/README.md)`n[Production](cachedb-production-tests/README.md#acceptance)`n[Examples](cachedb-examples/README.md)`n"
    'tr/README.md' = "[Sample](../$sample/tr/README.md)`n"
    'docs/routes.md' = '# Routes'
    'tools/ci/check-example.ps1' = '# Source-only tool'
    'cachedb-production-tests/README.md' = '# Production tests'
    'cachedb-examples/README.md' = '# Examples'
    'sample-cache-database-mssql/README.md' = '[Routes](../docs/routes.md#timeline)'
    'sample-cache-database-oracle/README.md' = '[Routes](../docs/routes.md#timeline)'
    "$sample/README.md" = "[Routes](../docs/routes.md#timeline)`n[Tool](../tools/ci/check-example.ps1)`n[External](https://example.org/a)`n[Anchor](#local)`n[Angle](<../docs/routes.md>)`n[reference]: ../docs/routes.md#ref`n``````markdown`n[Keep](../docs/routes.md)`n```````n"
    "$sample/tr/README.md" = "[Routes](../../docs/routes.md?lang=tr#read)`n"
}
foreach ($path in $files.Keys) {
    $source = Join-Path $root $path
    $destination = if ($path.StartsWith('sample-cache-database-')) { Join-Path $stage "samples/$path" } else { Join-Path $stage $path }
    $null = New-Item -ItemType Directory -Path (Split-Path -Parent $source) -Force
    [IO.File]::WriteAllText($source, $files[$path])
    if (!$path.StartsWith('tools/') -and $path -notmatch '^cachedb-[A-Za-z0-9-]+/README\.md$') {
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
$before = (Get-FileHash -LiteralPath (Join-Path $root "$sample/README.md")).Hash
(Get-Item -LiteralPath (Join-Path $root "$sample/README.md")).IsReadOnly = $true
(Get-Item -LiteralPath (Join-Path $stage "samples/$sample/README.md")).IsReadOnly = $true
& (Join-Path $RepositoryRoot 'tools/release/repair-bundle-documentation.ps1') -RepositoryRoot $root -StagingRoot $stage -Version '0.19.0'
$checks = @{
    'root-sample' = @('README.md', "[Sample](samples/$sample/README.md)")
    'root-tr' = @('README.md', '[Turkish](tr/README.md)')
    'excluded-production' = @('README.md', '[Production](https://github.com/esasmer-dou/cache-database/blob/v0.19.0/cachedb-production-tests/README.md#acceptance)')
    'excluded-examples' = @('README.md', '[Examples](https://github.com/esasmer-dou/cache-database/blob/v0.19.0/cachedb-examples/README.md)')
    'tr-sample' = @('tr/README.md', "[Sample](../samples/$sample/tr/README.md)")
    'sample-doc' = @("samples/$sample/README.md", '[Routes](../../docs/routes.md#timeline)')
    'sample-tr-doc' = @("samples/$sample/tr/README.md", '[Routes](../../../docs/routes.md?lang=tr#read)')
    'mssql-doc' = @('samples/sample-cache-database-mssql/README.md', '[Routes](../../docs/routes.md#timeline)')
    'oracle-doc' = @('samples/sample-cache-database-oracle/README.md', '[Routes](../../docs/routes.md#timeline)')
    'excluded-tool' = @("samples/$sample/README.md", '[Tool](https://github.com/esasmer-dou/cache-database/blob/v0.19.0/tools/ci/check-example.ps1)')
    'external' = @("samples/$sample/README.md", '[External](https://example.org/a)')
    'anchor' = @("samples/$sample/README.md", '[Anchor](#local)')
    'angle' = @("samples/$sample/README.md", '[Angle](<../../docs/routes.md>)')
    'reference' = @("samples/$sample/README.md", '[reference]: ../../docs/routes.md#ref')
    'fenced-code' = @("samples/$sample/README.md", '[Keep](../docs/routes.md)')
}
foreach ($name in $checks.Keys) {
    $check = $checks[$name]
    if (![IO.File]::ReadAllText((Join-Path $stage $check[0])).Contains($check[1])) { throw "Documentation check failed: $name" }
}
if ((Get-FileHash -LiteralPath (Join-Path $root "$sample/README.md")).Hash -cne $before) { throw 'Source documentation was modified.' }
if (!(Get-Item -LiteralPath (Join-Path $root "$sample/README.md")).IsReadOnly) { throw 'Source documentation permissions were modified.' }
$secondBefore = (Get-FileHash -LiteralPath (Join-Path $stage "samples/$sample/README.md")).Hash
& (Join-Path $RepositoryRoot 'tools/release/repair-bundle-documentation.ps1') -RepositoryRoot $root -StagingRoot $stage -Version '0.19.0'
if ((Get-FileHash -LiteralPath (Join-Path $stage "samples/$sample/README.md")).Hash -cne $secondBefore) { throw 'Documentation rewrite is not idempotent.' }
Write-Output "BUNDLE_DOCUMENTATION_CONTROLS_PASS checks=$($checks.Count + 3)"
