param(
    [string]$Repository = "esasmer-dou/cache-database",
    [string]$TargetRef = "main",
    [Parameter(Mandatory = $true)]
    [string]$ReleaseTag,
    [string]$CoverageCsvPath = "docs/ga-migration-coverage.csv",
    [string]$SummaryPath = "target/ga-release-readiness-summary.md",
    [switch]$CheckGitHubSecrets,
    [switch]$RequireManagedStagingHa,
    [switch]$RequireApplicationMigrationCoverage,
    [switch]$RequireMavenCentralPublish,
    [switch]$SkipRemoteWorkflowChecks,
    [switch]$SkipSecretChecks,
    [switch]$SkipMavenCentralPublishCheck
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$summaryFullPath = if ([System.IO.Path]::IsPathRooted($SummaryPath)) {
    $SummaryPath
} else {
    Join-Path $repoRoot $SummaryPath
}

$checks = New-Object System.Collections.Generic.List[object]

function Normalize-Detail {
    param([string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    $ansiPattern = "$([char]27)\[[0-?]*[ -/]*[@-~]"
    $clean = $Value -replace $ansiPattern, ""
    $clean = $clean -replace "\s+", " "
    $clean = $clean.Trim()
    if ($clean.Length -gt 900) {
        return $clean.Substring(0, 900) + "..."
    }
    return $clean
}

function Add-Check {
    param(
        [string]$Name,
        [ValidateSet("PASS", "FAIL", "SKIP")]
        [string]$Status,
        [string]$Details
    )
    $script:checks.Add([pscustomobject]@{
        Name = $Name
        Status = $Status
        Details = Normalize-Detail $Details
    })
}

function Invoke-ReadinessCheck {
    param(
        [string]$Name,
        [scriptblock]$Check
    )
    try {
        $details = & $Check
        if ([string]::IsNullOrWhiteSpace([string]$details)) {
            $details = "OK"
        }
        Add-Check -Name $Name -Status "PASS" -Details ([string]$details)
    } catch {
        Add-Check -Name $Name -Status "FAIL" -Details $_.Exception.Message
    }
}

function Escape-MarkdownCell {
    param([string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return ($Value -replace '\|', '\|' -replace "`r?`n", " ")
}

function Invoke-Native {
    param(
        [string]$Command,
        [string[]]$Arguments
    )
    $output = & $Command @Arguments 2>&1
    if ($global:LASTEXITCODE -ne 0) {
        $details = Normalize-Detail (($output | ForEach-Object { [string]$_ }) -join " ")
        throw "$Command $($Arguments -join ' ') failed. Output: $details"
    }
    return $output
}

function Get-RootPomVersion {
    $pomPath = Join-Path $repoRoot "pom.xml"
    if (-not (Test-Path $pomPath)) {
        throw "Root pom.xml not found."
    }
    [xml]$pom = Get-Content -Path $pomPath -Raw
    $namespaceManager = [System.Xml.XmlNamespaceManager]::new($pom.NameTable)
    $namespaceManager.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")
    $versionNode = $pom.SelectSingleNode("/m:project/m:version", $namespaceManager)
    if ($null -eq $versionNode -or [string]::IsNullOrWhiteSpace($versionNode.InnerText)) {
        throw "Root pom.xml has no explicit project version."
    }
    return $versionNode.InnerText.Trim()
}

function Resolve-CommitSha {
    param([string]$Ref)
    $sha = Invoke-Native -Command "git" -Arguments @("rev-parse", "$Ref^{commit}")
    return ([string]$sha).Trim()
}

function Get-LatestSuccessfulRun {
    param(
        [string]$WorkflowName,
        [string]$HeadBranch,
        [string]$HeadSha,
        [string]$RequiredEvent = ""
    )
    $json = (Invoke-Native -Command "gh" -Arguments @(
        "run", "list",
        "--repo", $Repository,
        "--workflow", $WorkflowName,
        "--limit", "50",
        "--json", "databaseId,workflowName,headBranch,headSha,conclusion,status,event,url,createdAt"
    )) -join [Environment]::NewLine
    $runs = @($json | ConvertFrom-Json)
    if ($runs.Count -eq 0) {
        throw "No GitHub Actions runs found for workflow '$WorkflowName'."
    }

    $matches = @($runs | Where-Object {
        $_.status -eq "completed" -and
        $_.conclusion -eq "success" -and
        ([string]::IsNullOrWhiteSpace($HeadBranch) -or $_.headBranch -eq $HeadBranch) -and
        ([string]::IsNullOrWhiteSpace($HeadSha) -or $_.headSha -eq $HeadSha) -and
        ([string]::IsNullOrWhiteSpace($RequiredEvent) -or $_.event -eq $RequiredEvent)
    })

    if ($matches.Count -eq 0) {
        $latest = $runs | Select-Object -First 1
        throw "No successful '$WorkflowName' run matched headBranch='$HeadBranch' headSha='$HeadSha' event='$RequiredEvent'. Latest run: status=$($latest.status), conclusion=$($latest.conclusion), branch=$($latest.headBranch), sha=$($latest.headSha), url=$($latest.url)"
    }

    return $matches[0]
}

$releaseTagValue = $ReleaseTag.Trim()
$targetRefValue = if ([string]::IsNullOrWhiteSpace($TargetRef)) { "main" } else { $TargetRef.Trim() }
$releaseCommitSha = ""

if (-not [string]::IsNullOrWhiteSpace($releaseTagValue)) {
    try {
        $releaseCommitSha = Resolve-CommitSha -Ref $releaseTagValue
    } catch {
        $releaseCommitSha = ""
    }
}

Invoke-ReadinessCheck -Name "Stable GA version and tag" -Check {
    if ([string]::IsNullOrWhiteSpace($releaseTagValue)) {
        throw "ReleaseTag is required for GA readiness."
    }
    if ([string]::IsNullOrWhiteSpace($releaseCommitSha)) {
        throw "Release tag '$releaseTagValue' does not resolve to a commit."
    }
    if ($releaseTagValue -notmatch '^v\d+\.\d+\.\d+$') {
        throw "GA release tag must be stable semver such as v1.0.0. Pre-release tags are not GA: $releaseTagValue"
    }
    $version = Get-RootPomVersion
    $tagVersion = $releaseTagValue.Substring(1)
    if ($version -ne $tagVersion) {
        throw "Root pom.xml version '$version' does not match release tag '$releaseTagValue'."
    }
    if ($version -match '(?i)(snapshot|alpha|beta|rc|milestone|preview)') {
        throw "Root pom.xml version '$version' is not a GA version."
    }
    "Version $version, tag $releaseTagValue, commit $releaseCommitSha"
}

Invoke-ReadinessCheck -Name "No public codex branches" -Check {
    $remoteHeads = Invoke-Native -Command "git" -Arguments @("ls-remote", "--heads", "origin")
    $codexBranches = @($remoteHeads | Where-Object { $_ -match 'refs/heads/codex($|/)' })
    if ($codexBranches.Count -gt 0) {
        throw "Remote still contains public codex branch refs: $($codexBranches -join '; ')"
    }
    "No refs/heads/codex or refs/heads/codex/* branches found on origin."
}

if ($SkipSecretChecks) {
    Add-Check -Name "External GA secrets" -Status "SKIP" -Details "Skipped by caller."
} else {
    Invoke-ReadinessCheck -Name "External GA secrets" -Check {
        $requiredSecrets = New-Object System.Collections.Generic.List[string]
        if ($RequireMavenCentralPublish) {
            $requiredSecrets.Add("CENTRAL_USERNAME")
            $requiredSecrets.Add("CENTRAL_PASSWORD")
            $requiredSecrets.Add("GPG_PRIVATE_KEY")
            $requiredSecrets.Add("GPG_PASSPHRASE")
        }
        if ($RequireManagedStagingHa) {
            $requiredSecrets.Add("STAGING_REDIS_URI")
            $requiredSecrets.Add("STAGING_POSTGRES_URL")
            $requiredSecrets.Add("STAGING_POSTGRES_USER")
            $requiredSecrets.Add("STAGING_POSTGRES_PASSWORD")
            $requiredSecrets.Add("STAGING_MSSQL_URL")
            $requiredSecrets.Add("STAGING_MSSQL_USER")
            $requiredSecrets.Add("STAGING_MSSQL_PASSWORD")
        }
        if ($requiredSecrets.Count -eq 0) {
            return "No external secret gate requested. Maven Central and managed staging HA are optional library-release gates unless explicitly required."
        }

        if ($CheckGitHubSecrets) {
            $json = (Invoke-Native -Command "gh" -Arguments @("secret", "list", "--repo", $Repository, "--json", "name")) -join [Environment]::NewLine
            $secretRows = @($json | ConvertFrom-Json)
            $secretNames = @($secretRows | ForEach-Object { $_.name })
            $missing = @($requiredSecrets | Where-Object { $_ -notin $secretNames })
            if ($missing.Count -gt 0) {
                throw "Missing required GitHub repository secrets: $($missing -join ', ')"
            }
            return "All required repository secret names are present."
        }

        $missingEnv = @($requiredSecrets | Where-Object {
            [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
        })
        if ($missingEnv.Count -gt 0) {
            throw "Missing required secret environment values: $($missingEnv -join ', ')"
        }
        "All required secret environment values are present."
    }
}

if ($RequireApplicationMigrationCoverage) {
    Invoke-ReadinessCheck -Name "Application migration coverage CSV" -Check {
        $coverageFullPath = if ([System.IO.Path]::IsPathRooted($CoverageCsvPath)) {
            $CoverageCsvPath
        } else {
            Join-Path $repoRoot $CoverageCsvPath
        }
        if (-not (Test-Path $coverageFullPath)) {
            throw "Migration coverage CSV not found: $coverageFullPath"
        }
        $validatorPath = Join-Path $repoRoot "tools/ci/validate-migration-coverage-report.ps1"
        Invoke-Native -Command "pwsh" -Arguments @(
            $validatorPath,
            "-CoverageCsvPath", $CoverageCsvPath,
            "-SummaryPath", "target/migration-coverage-summary.md"
        ) | Out-Null
        "Coverage file '$CoverageCsvPath' passed schema and cutover checks."
    }
} else {
    Invoke-ReadinessCheck -Name "Migration coverage tooling" -Check {
        $templatePath = Join-Path $repoRoot "docs/ga-migration-coverage-template.csv"
        $validatorPath = Join-Path $repoRoot "tools/ci/validate-migration-coverage-report.ps1"
        if (-not (Test-Path $templatePath)) {
            throw "Migration coverage template is missing: $templatePath"
        }
        if (-not (Test-Path $validatorPath)) {
            throw "Migration coverage validator is missing: $validatorPath"
        }
        "Template and validator are present. Full route coverage is a consuming-application cutover gate, not a generic library GA gate."
    }
}

Invoke-ReadinessCheck -Name "Local Docker HA preflight tooling" -Check {
    $preflightPath = Join-Path $repoRoot "tools/ci/run-local-docker-ha-preflight.ps1"
    if (-not (Test-Path $preflightPath)) {
        throw "Local Docker HA preflight script is missing: $preflightPath"
    }
    "Docker Desktop preflight tooling is present for Redis outage/recovery and MSSQL restart/reconnect evidence."
}

if ($SkipRemoteWorkflowChecks) {
    Add-Check -Name "Remote workflow evidence" -Status "SKIP" -Details "Skipped by caller."
} else {
    $targetSha = $releaseCommitSha

    Invoke-ReadinessCheck -Name "Public Beta Readiness workflow" -Check {
        $run = Get-LatestSuccessfulRun -WorkflowName "Public Beta Readiness" -HeadBranch $targetRefValue -HeadSha $targetSha
        "Run $($run.databaseId): $($run.url)"
    }

    Invoke-ReadinessCheck -Name "Production Evidence workflow" -Check {
        $run = Get-LatestSuccessfulRun -WorkflowName "Production Evidence" -HeadBranch $targetRefValue -HeadSha $targetSha
        "Run $($run.databaseId): $($run.url)"
    }

    if ($RequireManagedStagingHa) {
        Invoke-ReadinessCheck -Name "Production GA Staging Evidence workflow" -Check {
            $run = Get-LatestSuccessfulRun -WorkflowName "Production GA Staging Evidence" -HeadBranch $targetRefValue -HeadSha $targetSha
            "Run $($run.databaseId): $($run.url)"
        }
    } else {
        Add-Check -Name "Production GA Staging Evidence workflow" -Status "SKIP" -Details "Managed Redis/SQL HA evidence was not requested. It remains required for a consuming application's own production cutover or managed HA support claim."
    }

    if (-not $RequireMavenCentralPublish) {
        Add-Check -Name "Maven Central Publish workflow" -Status "SKIP" -Details "Maven Central publish was not requested. Use this when the official distribution channel is GitHub Release or GitHub Packages."
    } elseif ($SkipMavenCentralPublishCheck) {
        Add-Check -Name "Maven Central Publish workflow" -Status "SKIP" -Details "Skipped by caller before the publish job."
    } else {
        Invoke-ReadinessCheck -Name "Maven Central Publish workflow" -Check {
            $run = Get-LatestSuccessfulRun -WorkflowName "Maven Central Publish" -HeadBranch $releaseTagValue -HeadSha $targetSha
            "Run $($run.databaseId): $($run.url)"
        }
    }
}

$summaryDirectory = Split-Path -Parent $summaryFullPath
if (-not (Test-Path $summaryDirectory)) {
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
}

$failedChecks = @($checks | Where-Object { $_.Status -eq "FAIL" })
$summaryLines = New-Object System.Collections.Generic.List[string]
$summaryLines.Add("# Production GA Release Readiness")
$summaryLines.Add("")
$summaryLines.Add("- Repository: ``$Repository``")
$summaryLines.Add("- Target ref: ``$targetRefValue``")
$summaryLines.Add("- Release tag: ``$releaseTagValue``")
$summaryLines.Add("- Result: ``$(if ($failedChecks.Count -eq 0) { "PASS" } else { "FAIL" })``")
$summaryLines.Add("")
$summaryLines.Add("| Gate | Status | Details |")
$summaryLines.Add("| --- | --- | --- |")
foreach ($check in $checks) {
    $summaryLines.Add("| $(Escape-MarkdownCell $check.Name) | ``$($check.Status)`` | $(Escape-MarkdownCell $check.Details) |")
}

if ($failedChecks.Count -gt 0) {
    $summaryLines.Add("")
    $summaryLines.Add("## Blocking Decision")
    $summaryLines.Add("")
    $summaryLines.Add("Do not publish or announce a production GA release until every gate above is PASS.")
}

Set-Content -Path $summaryFullPath -Value ($summaryLines -join [Environment]::NewLine)

Write-Host "GA release readiness summary: $summaryFullPath"
if ($failedChecks.Count -gt 0) {
    throw "Production GA release readiness failed. See $summaryFullPath"
}

Write-Host "Production GA release readiness passed."
