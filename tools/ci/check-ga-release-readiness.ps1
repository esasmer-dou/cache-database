param(
    [string]$Repository = "esasmer-dou/cache-database",
    [string]$TargetRef = "main",
    [Parameter(Mandatory = $true)]
    [string]$ReleaseTag,
    [string]$CoverageCsvPath = "docs/ga-migration-coverage.csv",
    [string]$SummaryPath = "target/ga-release-readiness-summary.md",
    [string]$PublicMavenBaseUrl = "https://esasmer-dou.github.io/cache-database/maven2",
    [ValidateSet("FrameworkPackage", "ProductionGA")]
    [string]$EvidenceScope = "ProductionGA",
    [switch]$CheckGitHubSecrets,
    [switch]$RequireManagedStagingHa,
    [switch]$RequireApplicationMigrationCoverage,
    [switch]$RequireMavenCentralPublish,
    [switch]$PrePublication,
    [switch]$SkipRemoteWorkflowChecks,
    [switch]$SkipSecretChecks,
    [switch]$SkipMavenCentralPublishCheck
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$readinessLabel = if ($EvidenceScope -eq "ProductionGA") { "Production GA" } else { "Framework Package" }

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

function Assert-PublicationSource {
    param([string]$Version, [string]$ReleaseTag, [string]$HeadSha, [string]$TagSha, [string]$WorkingTreeStatus)
    if ($ReleaseTag -notmatch '^v\d+\.\d+\.\d+$' -or $Version -cne $ReleaseTag.Substring(1)) {
        throw 'Publication requires a stable tag matching the source POM version.'
    }
    if ($HeadSha -notmatch '^[0-9a-fA-F]{40}$' -or $TagSha -notmatch '^[0-9a-fA-F]{40}$' -or $HeadSha -ine $TagSha) {
        throw 'Publication HEAD must equal the exact immutable release tag commit.'
    }
    if (![string]::IsNullOrWhiteSpace($WorkingTreeStatus)) { throw 'Publication source must have a clean working tree.' }
}

function Get-RequiredPerformanceWorkflows {
    param([string]$StableReleaseTag)
    if ($StableReleaseTag -notmatch '^v\d+\.\d+\.\d+$') { throw 'Performance workflow requirements need a stable release tag.' }
    if ([version]$StableReleaseTag.Substring(1) -ge [version]'0.19.0') {
        'Redis Performance Gate'
        'EmaTech T1 and v019 Feature Performance Gates'
    }
}

function Test-CanonicalK8sRelease {
    param([string]$StableReleaseTag)
    if ($StableReleaseTag -cnotmatch '^v\d+\.\d+\.\d+$') { throw 'Canonical evidence selection requires a stable release tag.' }
    return [version]$StableReleaseTag.Substring(1) -ge [version]'0.19.0'
}

function Assert-CanonicalK8sArtifact {
    param([object[]]$Artifacts, $Run, [string]$SourceSha, [string]$Repository)
    if ($SourceSha -cnotmatch '^[a-f0-9]{40}$' -or $Run.headSha -cne $SourceSha -or
        [string]$Run.databaseId -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $Run.workflowName -cne 'Framework Readiness' -or $Run.status -cne 'completed' -or
        $Run.conclusion -cne 'success' -or $Run.event -cnotin @('push', 'workflow_dispatch')) {
        throw 'Canonical K8s proof requires its successful trusted exact-S public verifier run.'
    }
    if ($Artifacts.Count -ge 100) { throw 'Canonical K8s proof exceeds the one-page artifact bound.' }
    $matches = @($Artifacts | Where-Object { $_.name -ceq 'k8s-acceptance-verification' })
    if ($matches.Count -ne 1) { throw 'Exactly one canonical K8s verification artifact is required.' }
    $artifact = $matches[0]
    $api = 'https://api.github.com/repos/' + $Repository + '/actions/artifacts/' + [string]$artifact.id
    if ([string]$artifact.id -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $artifact.expired -isnot [bool] -or $artifact.expired -or
        ($artifact.size_in_bytes -isnot [int] -and $artifact.size_in_bytes -isnot [long]) -or
        $artifact.size_in_bytes -le 0 -or $artifact.size_in_bytes -gt 1MB -or
        $artifact.digest -cnotmatch '^sha256:[a-f0-9]{64}$' -or
        [string]$artifact.workflow_run.id -cne [string]$Run.databaseId -or
        $artifact.workflow_run.head_sha -cne $SourceSha -or
        $artifact.url -cne $api -or $artifact.archive_download_url -cne ($api + '/zip')) {
        throw 'Canonical K8s verification artifact is expired, unpinned, oversized or from a different run/source.'
    }
    return $artifact
}

function Select-SuccessfulWorkflowRun {
    param(
        [object[]]$Runs,
        [string]$WorkflowName,
        [string]$HeadBranch = '',
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$HeadSha,
        [string]$RequiredEvent = ''
    )
    if (!$Runs.Count) { throw "No GitHub Actions runs found for workflow '$WorkflowName'." }
    if ($Runs.Count -gt 50) { throw 'Workflow evidence exceeds the 50-run query bound.' }
    $matches = @($Runs | Where-Object {
        $_.workflowName -ceq $WorkflowName -and
        $_.status -ceq 'completed' -and $_.conclusion -ceq 'success' -and
        $_.headSha -eq $HeadSha -and
        ([string]::IsNullOrWhiteSpace($HeadBranch) -or $_.headBranch -eq $HeadBranch) -and
        ([string]::IsNullOrWhiteSpace($RequiredEvent) -or $_.event -eq $RequiredEvent)
    })
    if (!$matches.Count) {
        throw "No completed successful '$WorkflowName' run matched exact release SHA '$HeadSha', branch='$HeadBranch', event='$RequiredEvent'."
    }
    return $matches[0]
}

function Assert-SuccessfulWorkflowJob {
    param([object[]]$Jobs, [string]$RequiredJobName)
    if ($Jobs.Count -gt 50) { throw 'Workflow job evidence exceeds the 50-job bound.' }
    $matches = @($Jobs | Where-Object { $_.name -ceq $RequiredJobName })
    if ($matches.Count -ne 1 -or $matches[0].status -cne 'completed' -or $matches[0].conclusion -cne 'success') {
        throw "Required workflow job '$RequiredJobName' is missing, duplicated, skipped, failed, or incomplete."
    }
}

function Get-LatestSuccessfulRun {
    param(
        [string]$WorkflowName,
        [string]$HeadBranch,
        [string]$HeadSha,
        [string]$RequiredEvent = "",
        [string]$RequiredJobName = "",
        [string[]]$RequiredJobNames = @()
    )
    if ($PSBoundParameters.ContainsKey('RequiredJobNames')) {
        if ($RequiredJobName) { throw 'Use either RequiredJobName or RequiredJobNames, not both.' }
        if (!$RequiredJobNames -or $RequiredJobNames.Count -gt 50) {
            throw 'RequiredJobNames must contain between 1 and 50 job names.'
        }
    }
    $jobNames = @($RequiredJobNames)
    if ($RequiredJobName) { $jobNames = @($RequiredJobName) }
    $uniqueNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($jobName in $jobNames) {
        if ([string]::IsNullOrWhiteSpace($jobName) -or !$uniqueNames.Add($jobName)) {
            throw 'Required workflow job names must be nonblank and unique.'
        }
    }
    $json = (Invoke-Native -Command "gh" -Arguments @(
        "run", "list",
        "--repo", $Repository,
        "--workflow", $WorkflowName,
        "--limit", "50",
        "--json", "databaseId,workflowName,headBranch,headSha,conclusion,status,event,url,createdAt"
    )) -join [Environment]::NewLine
    $runs = @($json | ConvertFrom-Json)
    $selected = Select-SuccessfulWorkflowRun -Runs $runs -WorkflowName $WorkflowName -HeadBranch $HeadBranch -HeadSha $HeadSha -RequiredEvent $RequiredEvent
    if ($jobNames.Count) {
        $jobJson = (Invoke-Native -Command 'gh' -Arguments @('run', 'view', [string]$selected.databaseId,
            '--repo', $Repository, '--json', 'jobs')) -join [Environment]::NewLine
        $jobEvidence = $jobJson | ConvertFrom-Json
        foreach ($jobName in $jobNames) {
            Assert-SuccessfulWorkflowJob -Jobs @($jobEvidence.jobs) -RequiredJobName $jobName
        }
    }
    return $selected
}

$releaseTagValue = $ReleaseTag.Trim()
$targetRefValue = if ([string]::IsNullOrWhiteSpace($TargetRef)) { "main" } else { $TargetRef.Trim() }
$releaseCommitSha = ""

if (-not [string]::IsNullOrWhiteSpace($releaseTagValue)) {
    try {
        $releaseCommitSha = Resolve-CommitSha -Ref $(if ($PrePublication) { "refs/tags/$releaseTagValue" } else { $releaseTagValue })
    } catch {
        $releaseCommitSha = ""
    }
}

Invoke-ReadinessCheck -Name "Stable release version and tag" -Check {
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

if ($PrePublication) {
    Invoke-ReadinessCheck -Name 'Publication source identity' -Check {
        $head = Resolve-CommitSha -Ref 'HEAD'
        $status = (Invoke-Native -Command 'git' -Arguments @('status', '--porcelain', '--untracked-files=all')) -join "`n"
        Assert-PublicationSource -Version (Get-RootPomVersion) -ReleaseTag $releaseTagValue -HeadSha $head `
            -TagSha $releaseCommitSha -WorkingTreeStatus $status
        "Clean HEAD $head matches refs/tags/$releaseTagValue."
    }
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
    Add-Check -Name "Remote workflow evidence" -Status "FAIL" -Details "Required workflow and distribution checks cannot be skipped for release approval."
} else {
    $targetSha = $releaseCommitSha

    if ($EvidenceScope -eq 'ProductionGA') {
    Invoke-ReadinessCheck -Name "Framework Readiness workflow" -Check {
        $run = Get-LatestSuccessfulRun -WorkflowName "Framework Readiness" -HeadBranch $targetRefValue -HeadSha $targetSha `
            -RequiredJobNames @('K8s Acceptance Verification')
        if (Test-CanonicalK8sRelease $releaseTagValue) {
            # This exact-S job runs the unchanged full receipt/producer/report guard, not workloads.
            $json = (Invoke-Native -Command 'gh' -Arguments @('api', '--method', 'GET',
                "repos/$Repository/actions/runs/$($run.databaseId)/artifacts?per_page=100")) -join [Environment]::NewLine
            $metadata = $json | ConvertFrom-Json
            $artifact = Assert-CanonicalK8sArtifact @($metadata.artifacts) $run $targetSha $Repository
            return "Run $($run.databaseId): $($run.url); canonical K8s proof artifact $($artifact.id), $($artifact.digest)."
        }
        "Run $($run.databaseId): $($run.url)"
    }

    if (!(Test-CanonicalK8sRelease $releaseTagValue)) {
        # Immutable older releases retain their original workflow requirements.
        Invoke-ReadinessCheck -Name "Production Evidence workflow" -Check {
            $run = Get-LatestSuccessfulRun -WorkflowName "Production Evidence" -HeadBranch $targetRefValue -HeadSha $targetSha
            "Run $($run.databaseId): $($run.url)"
        }

        Invoke-ReadinessCheck -Name "Snapshot Provider Evidence workflow" -Check {
            $run = Get-LatestSuccessfulRun -WorkflowName "Snapshot Provider Evidence" -HeadBranch $targetRefValue -HeadSha $targetSha
            "Run $($run.databaseId): $($run.url)"
        }

        if ($releaseTagValue -match '^v\d+\.\d+\.\d+$') {
            foreach ($workflowName in @(Get-RequiredPerformanceWorkflows -StableReleaseTag $releaseTagValue)) {
                Invoke-ReadinessCheck -Name "$workflowName workflow" -Check {
                    $jobName = if ($workflowName -ceq 'Redis Performance Gate') { 'redis-performance' } else { 'feature-performance' }
                    $run = Get-LatestSuccessfulRun -WorkflowName $workflowName -HeadSha $targetSha -RequiredJobName $jobName
                    "Run $($run.databaseId): $($run.url)"
                }
            }
        }
    }

    } else {
        Add-Check -Name 'Framework runtime evidence' -Status 'SKIP' -Details 'Numbered package distribution reuses reviewed artifact-bound results. Private evidence transport and a new canonical verifier run are not publication prerequisites. This check does not approve runtime or Production GA.'
    }

    if ($EvidenceScope -eq "ProductionGA") {
        Invoke-ReadinessCheck -Name "Oracle Data Guard Evidence workflow" -Check {
            $run = Get-LatestSuccessfulRun -WorkflowName "Oracle Data Guard Evidence" -HeadSha $targetSha
            "Run $($run.databaseId): $($run.url)"
        }
    } else {
        Add-Check -Name "Oracle Data Guard Evidence workflow" -Status "SKIP" -Details "Framework package distribution only. Physical Data Guard evidence is not certified and full Production GA is not approved by this check."
    }

    if ($PrePublication) {
        Add-Check -Name 'Anonymous public Maven repository' -Status 'SKIP' -Details 'Post-publication distribution check; not a pre-publication dependency.'
    } else {
        Invoke-ReadinessCheck -Name "Anonymous public Maven repository" -Check {
            $version = $releaseTagValue.Substring(1)
            $validatorPath = Join-Path $repoRoot "tools/ci/check-public-maven-repository.ps1"
            Invoke-Native -Command "pwsh" -Arguments @(
                $validatorPath,
                "-Version", $version,
                "-BaseUrl", $PublicMavenBaseUrl,
                "-SummaryPath", "target/public-maven-repository-summary.md"
            ) | Out-Null
            "Version $version resolved without authentication from $PublicMavenBaseUrl"
        }
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
        Add-Check -Name "Maven Central Publish workflow" -Status "SKIP" -Details "Maven Central publish was not requested. The anonymous public Maven repository and GitHub Release are the official distribution channels."
    } elseif ($PrePublication) {
        Add-Check -Name 'Maven Central Publish workflow' -Status 'SKIP' -Details 'The publication being gated cannot already be complete; verify distribution after publication.'
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
$summaryLines.Add("# $readinessLabel Release Readiness")
$summaryLines.Add("")
$summaryLines.Add("- Repository: ``$Repository``")
$summaryLines.Add("- Target ref: ``$targetRefValue``")
$summaryLines.Add("- Release tag: ``$releaseTagValue``")
$summaryLines.Add("- Evidence scope: ``$EvidenceScope``")
$summaryLines.Add("- Phase: ``$(if ($PrePublication) { 'PRE_PUBLICATION_SOURCE_AND_WORKFLOWS_ONLY' } else { 'POST_PUBLICATION_WITH_DISTRIBUTION' })``")
if ($EvidenceScope -eq "FrameworkPackage") {
    $summaryLines.Add("- Full Production GA: ``NOT_APPROVED_BY_THIS_CHECK``; requires all GA gates, including exact-commit physical Data Guard evidence.")
}
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
    $summaryLines.Add("Do not publish or announce a $readinessLabel release until every mandatory gate above is PASS.")
}

Set-Content -Path $summaryFullPath -Value ($summaryLines -join [Environment]::NewLine)

Write-Host "$readinessLabel release readiness summary: $summaryFullPath"
if ($failedChecks.Count -gt 0) {
    throw "$readinessLabel release readiness failed. See $summaryFullPath"
}

if ($PrePublication) {
    Write-Host 'Pre-publication source/workflow checks passed; distribution and release approval remain separate.'
} else {
    Write-Host "$readinessLabel release readiness passed."
}
