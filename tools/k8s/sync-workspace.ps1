param(
    [Parameter(Mandatory=$true)][string]$Kubectl,
    [Parameter(Mandatory=$true)][string]$Context,
    [string]$Namespace = 'cachedb-v017-validation',
    [ValidateLength(1,63)][ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')][string]$WorkspacePod = 'workspace',
    [ValidateRange(1,65536)][int]$WorkspaceUsageLimitMiB = 6144,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9-]{1,60}$')][string]$SourceId
)
$ErrorActionPreference = 'Stop'
if (!$Namespace.StartsWith('cachedb-')) { throw 'A dedicated CacheDB validation namespace is required.' }
. (Join-Path $PSScriptRoot 'source-archive-guard.ps1')
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    $files = @(Get-K8sSourceArchiveFiles -Root $root)
    $usage = @(& $Kubectl --context $Context --request-timeout=60s -n $Namespace exec $WorkspacePod -c maven -- du -sm /workspace)
    if ($LASTEXITCODE -ne 0 -or $usage.Count -ne 1) { throw 'Cannot establish workspace disk usage before source transfer.' }
    $usedMiB = 0L
    if (![long]::TryParse(($usage[0] -split '\s+')[0], [ref]$usedMiB) -or $usedMiB -lt 0) {
        throw 'Invalid workspace disk usage response.'
    }
    if ($usedMiB -ge $WorkspaceUsageLimitMiB) {
        throw "Workspace uses $usedMiB MiB; soft limit is $WorkspaceUsageLimitMiB MiB. Export evidence and retire unused private caches before copying more sources."
    }
    $output = Join-Path $root 'target/k8s-transfer'
    $null = New-Item -ItemType Directory -Force -Path $output
    [IO.File]::WriteAllLines((Join-Path $output 'files.txt'), $files, [Text.UTF8Encoding]::new($false))
    & tar -czf target/k8s-transfer/source.tgz -T target/k8s-transfer/files.txt
    if ($LASTEXITCODE -ne 0) { throw 'Source archive failed.' }
    & $Kubectl --context $Context -n $Namespace cp target/k8s-transfer/source.tgz "${WorkspacePod}:/workspace/source.tgz" -c maven
    if ($LASTEXITCODE -ne 0) { throw 'Source transfer failed.' }
    & $Kubectl --context $Context -n $Namespace exec $WorkspacePod -c maven -- sh -c "test ! -e /workspace/builds/$SourceId && mkdir -p /workspace/builds/$SourceId && tar -xzf /workspace/source.tgz -C /workspace/builds/$SourceId && sha256sum /workspace/source.tgz"
    if ($LASTEXITCODE -ne 0) { throw 'Source extraction failed.' }
    Write-Host "Cluster source directory: /workspace/builds/$SourceId"
} finally { Pop-Location }
