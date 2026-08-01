param(
    [string] $CanonicalSample = "./sample-cache-database-postgresql",
    [string] $TargetSample = "./sample-cache-database-mssql",
    [switch] $Check
)

$ErrorActionPreference = "Stop"

$canonicalRoot = (Resolve-Path -LiteralPath $CanonicalSample).Path
$targetRoot = (Resolve-Path -LiteralPath $TargetSample).Path
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

if (-not $canonicalRoot.StartsWith($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    -not $targetRoot.StartsWith($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Sample roots must remain inside the CacheDB workspace."
}

function Get-ProviderNeutralJavaFiles {
    param([string] $Root)

    Get-ChildItem -LiteralPath (Join-Path $Root "src") -Recurse -File -Filter "*.java" |
        Where-Object { $_.Name -notmatch "^(Postgresql|Mssql)Sample(Application|IT)\.java$" } |
        ForEach-Object {
            [pscustomobject]@{
                RelativePath = $_.FullName.Substring($Root.Length + 1)
                FullName = $_.FullName
            }
        }
}

function Get-IntegrationContractBody {
    param([string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    $content = Get-Content -LiteralPath $Path -Raw
    $contractStart = $content.IndexOf("        jdbcTemplate.update(", [System.StringComparison]::Ordinal)
    if ($contractStart -lt 0) {
        throw "Integration contract marker was not found in $Path"
    }
    return $content.Substring($contractStart).Replace("`r`n", "`n")
}

$canonicalFiles = @(Get-ProviderNeutralJavaFiles -Root $canonicalRoot)
$canonicalByPath = @{}
$differences = [System.Collections.Generic.List[string]]::new()
foreach ($file in $canonicalFiles) {
    $canonicalByPath[$file.RelativePath] = $file.FullName
    $targetPath = Join-Path $targetRoot $file.RelativePath
    if ($Check) {
        if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
            $differences.Add("Missing in MSSQL sample: $($file.RelativePath)")
            continue
        }
        $canonicalHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        $targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
        if ($canonicalHash -ne $targetHash) {
            $differences.Add("Different provider-neutral file: $($file.RelativePath)")
        }
        continue
    }
    $targetDirectory = Split-Path -Parent $targetPath
    if (-not (Test-Path -LiteralPath $targetDirectory)) {
        New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
    }
    Copy-Item -LiteralPath $file.FullName -Destination $targetPath -Force
}

foreach ($targetFile in @(Get-ProviderNeutralJavaFiles -Root $targetRoot)) {
    if (-not $canonicalByPath.ContainsKey($targetFile.RelativePath)) {
        if ($Check) {
            $differences.Add("Extra in MSSQL sample: $($targetFile.RelativePath)")
            continue
        }
        $resolvedTarget = [System.IO.Path]::GetFullPath($targetFile.FullName)
        if (-not $resolvedTarget.StartsWith($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove a file outside the target sample: $resolvedTarget"
        }
        Remove-Item -LiteralPath $resolvedTarget -Force
    }
}

if ($Check) {
    $canonicalIntegrationTest = Join-Path $canonicalRoot "src\test\java\com\example\cachedb\sample\PostgresqlSampleIT.java"
    $targetIntegrationTest = Join-Path $targetRoot "src\test\java\com\example\cachedb\sample\MssqlSampleIT.java"
    $canonicalContract = Get-IntegrationContractBody -Path $canonicalIntegrationTest
    $targetContract = Get-IntegrationContractBody -Path $targetIntegrationTest
    if ($null -eq $canonicalContract -or $null -eq $targetContract) {
        $differences.Add("Provider integration contract test is missing from one of the samples.")
    } elseif ($canonicalContract -cne $targetContract) {
        $differences.Add("Provider integration contract bodies differ after provider-specific setup.")
    }
    if ($differences.Count -gt 0) {
        $differences | ForEach-Object { Write-Error $_ }
        throw "Provider-neutral sample parity failed with $($differences.Count) difference(s)."
    }
    Write-Host "Verified $($canonicalFiles.Count) provider-neutral Java files and the shared integration contract across PostgreSQL and MSSQL samples."
    exit 0
}

Write-Host "Synchronized $($canonicalFiles.Count) provider-neutral Java files from PostgreSQL to MSSQL sample."
