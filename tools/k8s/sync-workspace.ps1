param(
    [Parameter(Mandatory=$true)][string]$Kubectl,
    [Parameter(Mandatory=$true)][string]$Context,
    [string]$Namespace = 'cachedb-v017-validation',
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9-]{1,60}$')][string]$SourceId
)
$ErrorActionPreference = 'Stop'
if (!$Namespace.StartsWith('cachedb-')) { throw 'A dedicated CacheDB validation namespace is required.' }
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    $output = Join-Path $root 'target/k8s-transfer'
    $null = New-Item -ItemType Directory -Force -Path $output
    $files = @(git -c core.quotepath=false ls-files --cached --others --exclude-standard |
            Where-Object { [IO.File]::Exists($_) })
    [IO.File]::WriteAllLines((Join-Path $output 'files.txt'), $files, [Text.UTF8Encoding]::new($false))
    & tar -czf target/k8s-transfer/source.tgz -T target/k8s-transfer/files.txt
    if ($LASTEXITCODE -ne 0) { throw 'Source archive failed.' }
    & $Kubectl --context $Context -n $Namespace cp target/k8s-transfer/source.tgz workspace:/workspace/source.tgz -c maven
    if ($LASTEXITCODE -ne 0) { throw 'Source transfer failed.' }
    & $Kubectl --context $Context -n $Namespace exec workspace -c maven -- sh -c "test ! -e /workspace/builds/$SourceId && mkdir -p /workspace/builds/$SourceId && tar -xzf /workspace/source.tgz -C /workspace/builds/$SourceId && sha256sum /workspace/source.tgz"
    if ($LASTEXITCODE -ne 0) { throw 'Source extraction failed.' }
    Write-Host "Cluster source directory: /workspace/builds/$SourceId"
} finally { Pop-Location }
