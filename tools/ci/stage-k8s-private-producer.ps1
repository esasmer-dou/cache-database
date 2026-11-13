#requires -Version 7.3
<#
.SYNOPSIS
Wait for this real Actions run's sealed K8s distribution; verify without workloads.
.DESCRIPTION
The coordinator uploads canonical52-RUN-ATTEMPT.zip and then
canonical52-RUN-ATTEMPT.handoff.json to an existing same-source private draft
release. The ZIP is the distribution envelope already assembled in Kubernetes,
including runtime/receipt.json, its unchanged files, separate qualification, and
handoff/frozen-plan. GitHub asset metadata supplies the archive digest; the
handoff supplies the independently sealed envelope receipt pin. This script
never manufactures producer identity, edits receipts, builds, or runs tests.
#>
[CmdletBinding()]
param([string]$SourceRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-PrivateProducerInputs([hashtable]$Context, [hashtable]$Inputs) {
    $workflow = Assert-PrivateOwnedContext $Context
    if ($workflow -cne '.github/workflows/k8s-evidence-producer.yml' -or
        $Context.sourceSha -cne $Context.dispatchSha -or $Inputs.repositoryPrivate -cne 'true' -or
        $Inputs.releaseId -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $Inputs.runAttempt -cnotmatch '^[1-9][0-9]{0,18}$') {
        throw 'Exact-source private producer dispatch and release authority required.'
    }
    foreach ($pin in @($Inputs.sourceManifestPin, $Inputs.sdkPin, $Inputs.frozenPlanPin)) { Assert-K8sPin $pin }
    return $workflow
}

function Assert-PrivateProducerRelease($Release, [string]$ReleaseId, [string]$SourceSha, [string]$Repository) {
    if ([string]$Release.id -cne $ReleaseId -or
        $Release.url -cne ('https://api.github.com/repos/' + $Repository + '/releases/' + $ReleaseId) -or
        $Release.draft -isnot [bool] -or !$Release.draft -or $Release.target_commitish -cne $SourceSha) {
        throw 'Pre-created private draft release must target the exact final source commit.'
    }
}

function Get-PrivateProducerAsset($Assets, [string]$Name, [string]$Repository, [long]$MaximumBytes = 512MB) {
    $matches = @($Assets | Where-Object { $_.name -ceq $Name })
    if ($matches.Count -ne 1) { throw 'Exactly one current-run release asset is required.' }
    $asset = $matches[0]
    if ([string]$asset.id -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $asset.digest -cnotmatch '^sha256:[a-f0-9]{64}$' -or $asset.size -gt $MaximumBytes) {
        throw 'Release asset lacks its bounded GitHub SHA256 metadata.'
    }
    Assert-PrivateEvidenceReleaseAsset $asset $Repository ([string]$asset.id) $asset.digest.Substring(7)
    return $asset
}

function Assert-PrivateProducerHandoff($Handoff, [hashtable]$Context, [hashtable]$Inputs, [string]$ArchiveName) {
    if ($Handoff -isnot [Collections.IDictionary] -or $Handoff.Count -ne 9 -or
        $Handoff.schema -cne 'cachedb.k8s-producer-handoff/v1' -or
        $Handoff.sourceSha -cne $Context.sourceSha -or
        $Handoff.sourceManifestSha256 -cne $Inputs.sourceManifestPin -or
        $Handoff.sdkManifestSha256 -cne $Inputs.sdkPin -or
        $Handoff.frozenPlanSha256 -cne $Inputs.frozenPlanPin -or
        [string]$Handoff.privateReleaseId -cne $Inputs.releaseId -or
        $Handoff.producer -isnot [Collections.IDictionary] -or $Handoff.producer.Count -ne 4 -or
        [string]$Handoff.producer.repositoryId -cne $Context.repositoryId -or
        [string]$Handoff.producer.runId -cne $Context.runId -or
        [string]$Handoff.producer.runAttempt -cne $Inputs.runAttempt -or
        $Handoff.producer.workflowPath -cne '.github/workflows/k8s-evidence-producer.yml' -or
        $Handoff.archive -isnot [Collections.IDictionary] -or $Handoff.archive.Count -ne 3 -or
        [string]$Handoff.archive.id -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $Handoff.archive.name -cne $ArchiveName) {
        throw 'Handoff differs from the actual run, frozen inputs or own release asset name.'
    }
    Assert-K8sPin $Handoff.archive.sha256
    Assert-K8sPin $Handoff.receiptSha256
}

function Save-PrivateProducerAsset($Asset, [string]$Repository, [string]$Token, [string]$Destination) {
    Assert-PrivateEvidenceReleaseAsset $Asset $Repository ([string]$Asset.id) $Asset.digest.Substring(7)
    Receive-SampleRuntimeArchive $Asset.url $Token $Destination 'application/octet-stream'
    if ((Get-Item -LiteralPath $Destination).Length -ne $Asset.size -or
        (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Asset.digest.Substring(7)) {
        throw 'Downloaded asset bytes differ from authenticated GitHub metadata.'
    }
}

function Invoke-PrivateProducerMetadata([string]$Uri, [hashtable]$Headers, [int]$TimeoutSeconds = 20) {
    return Invoke-RestMethod -Uri $Uri -Headers $Headers -TimeoutSec $TimeoutSeconds -MaximumRedirection 0
}

function Wait-PrivateProducerHandoff([string]$ReleaseUri, [hashtable]$Headers, [string]$HandoffName, [string]$ArchiveName) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    # Missing assets may be awaited. Any API/validation failure stops immediately; no retry loop.
    while ($clock.Elapsed.TotalSeconds -lt 840) {
        $remaining = 840 - $clock.Elapsed.TotalSeconds
        if ($remaining -lt 1) { break }
        $timeout = [int][Math]::Min(20, [Math]::Floor($remaining))
        $listed = Invoke-PrivateProducerMetadata ($ReleaseUri + '/assets?per_page=100') $Headers $timeout
        $assets = @($listed)
        if ($clock.Elapsed.TotalSeconds -ge 840) { break }
        if ($assets.Count -ge 100) { throw 'Dedicated release asset listing exceeded its one-page budget.' }
        $handoffs = @($assets | Where-Object { $_.name -ceq $HandoffName })
        if ($handoffs.Count -gt 1) { throw 'Ambiguous own-run handoff assets.' }
        if ($handoffs.Count -eq 1 -and $handoffs[0].state -ceq 'uploaded') {
            # The coordinator publishes the handoff last, after the ZIP upload has completed.
            $archives = @($assets | Where-Object { $_.name -ceq $ArchiveName })
            if ($archives.Count -ne 1 -or $archives[0].state -cne 'uploaded') { throw 'Ready handoff lacks its completed archive.' }
            return ,$assets
        }
        $sleep = [int][Math]::Min(10000, [Math]::Max(0, (840 - $clock.Elapsed.TotalSeconds) * 1000))
        if ($sleep -gt 0) { [Threading.Thread]::Sleep($sleep) }
    }
    throw 'Own-run Kubernetes handoff did not arrive within the fixed fourteen-minute wait.'
}

$phase = 'trusted-source'
try {
    if (![OperatingSystem]::IsLinux()) { throw 'Linux private metadata runner required.' }
    $SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).ProviderPath
    if ([IO.Path]::GetFullPath((Join-Path $SourceRoot 'tools/ci')) -cne $PSScriptRoot) { throw 'Trusted checkout helper required.' }
    . (Join-Path $PSScriptRoot 'k8s-source-equivalence.ps1')
    # Import existing guard definitions, not their workflow-specific entrypoints.
    # stage-k8s-acceptance's framework-readiness-only admission remains unchanged.
    foreach ($name in @('import-v019-private-owned-runtime.ps1', 'stage-v019-private-evidence.ps1',
        'check-k8s-acceptance.ps1', 'stage-k8s-acceptance.ps1')) {
        foreach ($definition in Import-K8sGuardFunctions (Join-Path $PSScriptRoot $name)) {
            . ([scriptblock]::Create($definition))
        }
    }
    foreach ($definition in Read-PrivateOwnedImportFunctions (Join-Path $SourceRoot '.github/workflows/framework-readiness.yml')) {
        . ([scriptblock]::Create($definition))
    }
    $context = @{
        actions=$env:GITHUB_ACTIONS; event=$env:GITHUB_EVENT_NAME; api=$env:GITHUB_API_URL; server=$env:GITHUB_SERVER_URL
        repository=$env:GITHUB_REPOSITORY; repositoryId=$env:GITHUB_REPOSITORY_ID; runId=$env:GITHUB_RUN_ID
        approvedRepository=$env:APPROVED_PRIVATE_REPOSITORY; approvedRepositoryId=$env:APPROVED_PRIVATE_REPOSITORY_ID
        publicRepository=$env:PUBLIC_SOURCE_REPOSITORY; publicRepositoryId=$env:PUBLIC_SOURCE_REPOSITORY_ID
        sourceSha=$env:APPROVED_SOURCE_SHA; dispatchSha=$env:GITHUB_SHA; workflowSha=$env:PRIVATE_WORKFLOW_SHA
        workflowRef=$env:GITHUB_WORKFLOW_REF; token=$env:GITHUB_TOKEN
    }
    $inputs = @{repositoryPrivate=$env:EVENT_REPOSITORY_PRIVATE; releaseId=$env:PRIVATE_RELEASE_ID
        runAttempt=$env:GITHUB_RUN_ATTEMPT; sourceManifestPin=$env:K8S_SOURCE_MANIFEST_PIN
        sdkPin=$env:K8S_SDK_PIN; frozenPlanPin=$env:K8S_FROZEN_PLAN_PIN}
    $phase = 'dispatch-context'
    $workflow = Assert-PrivateProducerInputs $context $inputs
    $phase = 'checkout'
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha $workflow
    $phase = 'runner-output'
    $output = Assert-PrivateOwnedOutput $env:RUNNER_TEMP $SourceRoot $SourceRoot
    $api = 'https://api.github.com/repos/' + $context.repository
    $headers = @{Authorization=('Bearer ' + $context.token); Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'}
    $phase = 'private-repository'
    Assert-PrivateOwnedRepository (Invoke-PrivateProducerMetadata $api $headers) $context.repository $context.repositoryId
    $publicApi = 'https://api.github.com/repos/' + $context.publicRepository
    $publicHeaders = @{Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'}
    $phase = 'public-repository'
    Assert-PrivateOwnedPublicSource (Invoke-PrivateProducerMetadata $publicApi $publicHeaders) $context.publicRepository $context.publicRepositoryId
    $phase = 'public-commit'
    $commit = Invoke-PrivateProducerMetadata ($publicApi + '/commits/' + $context.sourceSha) $publicHeaders
    if ($commit.sha -cne $context.sourceSha) { throw 'Final public commit S must exist before dispatch.' }
    $releaseUri = $api + '/releases/' + $inputs.releaseId
    $phase = 'private-release'
    Assert-PrivateProducerRelease (Invoke-PrivateProducerMetadata $releaseUri $headers) $inputs.releaseId $context.sourceSha $context.repository
    $stem = 'canonical52-' + $context.runId + '-' + $inputs.runAttempt
    $archiveName = $stem + '.zip'; $handoffName = $stem + '.handoff.json'
    Write-Host ('PRIVATE_PRODUCER_WAITING; RUN=' + $context.runId + '; ATTEMPT=' + $inputs.runAttempt + '; WAIT_LIMIT_SECONDS=840')
    $phase = 'sealed-handoff'
    $assets = Wait-PrivateProducerHandoff $releaseUri $headers $handoffName $archiveName
    $handoffAsset = Get-PrivateProducerAsset $assets $handoffName $context.repository 64KB
    $archiveAsset = Get-PrivateProducerAsset $assets $archiveName $context.repository
    $null = [IO.Directory]::CreateDirectory($output)
    [IO.File]::SetUnixFileMode($output, [IO.UnixFileMode]448)
    $handoffPath = Join-Path $output 'handoff.json'
    Save-PrivateProducerAsset $handoffAsset $context.repository $context.token $handoffPath
    $handoff = Read-K8sJson $handoffPath 64KB
    Assert-PrivateProducerHandoff $handoff $context $inputs $archiveName
    if ([string]$archiveAsset.id -cne [string]$handoff.archive.id -or
        $archiveAsset.digest -cne ('sha256:' + $handoff.archive.sha256)) { throw 'Handoff archive pin differs from GitHub asset metadata.' }
    $phase = 'sealed-archive'
    $archive = Join-Path $output 'existing-evidence.zip'
    Save-PrivateProducerAsset $archiveAsset $context.repository $context.token $archive
    $package = Join-Path $output 'evidence'
    $receiptPath = Expand-SampleRuntimeArchive $archive $package $handoff.archive.sha256 $handoff.receiptSha256
    $envelope = Read-K8sJson $receiptPath
    $policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
    $authority = @{publicRepository=$context.publicRepository; publicRepositoryId=$context.publicRepositoryId
        sha=$context.sourceSha; sdkPin=$inputs.sdkPin; privateRepositoryId=$context.repositoryId
        runId=$context.runId; runAttempt=$inputs.runAttempt; workflowPath=$workflow}
    $phase = 'acceptance-metadata'
    Assert-K8sDistributionEnvelope $envelope $authority
    $accepted = Resolve-K8sAcceptance $envelope $package $SourceRoot $authority $policy
    # Unlike retained-evidence redistribution, this coordinator must own the inner producer too.
    $runtimeAuthority = @{} + $authority
    $identity = Get-K8sSourceIdentity $accepted.receipt
    if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
        $null = $runtimeAuthority.Remove('sha'); $runtimeAuthority.sourceIdentity = $identity
    }
    Assert-K8sHeader $accepted.receipt $runtimeAuthority $policy
    $sourceManifest = Resolve-K8sMember $accepted.root $accepted.receipt.source.manifest
    if ((Get-FileHash -LiteralPath $sourceManifest).Hash.ToLowerInvariant() -cne $inputs.sourceManifestPin) {
        throw 'Original execution source manifest differs from the dispatch pin.'
    }
    $planPath = Resolve-K8sMember $package 'handoff/frozen-plan'
    if ((Get-Item -LiteralPath $planPath).Length -le 0 -or (Get-Item -LiteralPath $planPath).Length -gt 1MB -or
        (Get-FileHash -LiteralPath $planPath).Hash.ToLowerInvariant() -cne $inputs.frozenPlanPin) {
        throw 'Sealed frozen plan differs from the coordinator-approved dispatch pin.'
    }
    $original = Get-PrivateEvidenceMemberPins $package
    $privateResults = Join-Path $output 'verification'; $null = [IO.Directory]::CreateDirectory($privateResults)
    # Trusted final-checkout report parsers only. No credentials reach their child processes.
    $env:GH_TOKEN = ''; $env:GITHUB_TOKEN = ''
    $phase = 'retained-reports'
    & { Assert-K8sReports $accepted.receipt $accepted.root $SourceRoot $privateResults $policy $accepted.sourceQualification } *> (Join-Path $output 'report-validation.private.log')
    Assert-PrivateEvidenceMembersUnchanged $package $original
    $null = Assert-K8sInventory $envelope $package
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha $workflow
    # Re-check the exact release and asset IDs after verification, before artifact publication.
    $phase = 'final-asset-custody'
    Assert-PrivateProducerRelease (Invoke-PrivateProducerMetadata $releaseUri $headers) $inputs.releaseId $context.sourceSha $context.repository
    foreach ($asset in @($handoffAsset, $archiveAsset)) {
        $current = Invoke-PrivateProducerMetadata $asset.url $headers
        $checked = Get-PrivateProducerAsset @($current) $asset.name $context.repository
        if ([string]$checked.id -cne [string]$asset.id -or $checked.size -ne $asset.size -or
            $checked.digest -cne $asset.digest -or $checked.updated_at -cne $asset.updated_at) {
            throw 'Private release asset changed during validation.'
        }
    }
    [IO.File]::AppendAllText($env:GITHUB_OUTPUT, ('receipt-sha256=' + $handoff.receiptSha256 + "`n"), [Text.UTF8Encoding]::new($false))
    Write-Host 'PRIVATE_K8S_PRODUCER_VALIDATED; NO_WORKLOADS_EXECUTED; PUBLIC_ACCEPTANCE_AND_RELEASE_STILL_REQUIRED'
} catch {
    # Log only fixed phase/type/status fields, never headers, response bodies or command arguments.
    Write-Host ('PRIVATE_PRODUCER_FAILED_PHASE=' + $phase)
    Write-Host ('EXCEPTION_TYPE=' + $_.Exception.GetType().FullName)
    if ($_.Exception.PSObject.Properties['Response'] -and $null -ne $_.Exception.Response) {
        Write-Host ('HTTP_STATUS=' + [int]$_.Exception.Response.StatusCode)
    }
    Write-Error 'Private Kubernetes producer failed closed; no evidence may be uploaded or accepted for release.'
    exit 1
}
