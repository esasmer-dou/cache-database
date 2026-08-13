param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$BaseUrl = "https://esasmer-dou.github.io/cache-database/maven2",
    [string]$SummaryPath = "target/public-maven-repository-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must be stable semantic version: $Version"
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$summaryFullPath = if ([System.IO.Path]::IsPathRooted($SummaryPath)) {
    $SummaryPath
} else {
    Join-Path $repoRoot $SummaryPath
}
$normalizedBaseUrl = $BaseUrl.TrimEnd('/')
$requiredArtifacts = @(
    "cachedb-bom/$Version/cachedb-bom-$Version.pom",
    "cachedb-spring-boot-starter-postgres/$Version/cachedb-spring-boot-starter-postgres-$Version.pom",
    "cachedb-spring-boot-starter-mssql/$Version/cachedb-spring-boot-starter-mssql-$Version.pom",
    "cachedb-maven-plugin/$Version/cachedb-maven-plugin-$Version.pom",
    "cachedb-core/$Version/cachedb-core-$Version.jar"
)

$results = New-Object System.Collections.Generic.List[object]
foreach ($artifact in $requiredArtifacts) {
    $url = "$normalizedBaseUrl/com/reactor/cachedb/$artifact"
    try {
        $response = Invoke-WebRequest -Uri $url -Method Get -MaximumRedirection 5 -TimeoutSec 30
        if ($response.StatusCode -ne 200 -or $response.RawContentLength -le 0) {
            throw "Unexpected response status=$($response.StatusCode), bytes=$($response.RawContentLength)"
        }
        $results.Add([pscustomobject]@{ Artifact = $artifact; Status = "PASS"; Details = "$($response.RawContentLength) bytes" })
    } catch {
        $results.Add([pscustomobject]@{ Artifact = $artifact; Status = "FAIL"; Details = $_.Exception.Message })
    }
}

$failed = @($results | Where-Object { $_.Status -eq "FAIL" })
$summaryDirectory = Split-Path -Parent $summaryFullPath
New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
$summary = @(
    "# Public Maven Repository",
    "",
    "- Base URL: ``$normalizedBaseUrl``",
    "- Version: ``$Version``",
    "- Authentication: ``not required``",
    "- Result: ``$(if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' })``",
    "",
    "| Artifact | Status | Details |",
    "| --- | --- | --- |"
)
$summary += $results | ForEach-Object { "| ``$($_.Artifact)`` | ``$($_.Status)`` | $($_.Details -replace '\|', '\|') |" }
Set-Content -LiteralPath $summaryFullPath -Value ($summary -join [Environment]::NewLine)

if ($failed.Count -gt 0) {
    throw "Anonymous public Maven repository verification failed. See $summaryFullPath"
}
Write-Host "Anonymous public Maven repository verification passed: $normalizedBaseUrl"
