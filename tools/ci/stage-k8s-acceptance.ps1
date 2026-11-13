#requires -Version 7.3
[CmdletBinding()]
param([string]$SourceRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'k8s-source-equivalence.ps1')

function Assert-K8sDistributionEnvelope($Receipt, [hashtable]$Authority) {
    if ($Receipt.schema -cne 'cachedb.k8s-distribution/v1' -or
        $Receipt.kind -cne 'K8S_EVIDENCE_DISTRIBUTION_NOT_RUNTIME_ACCEPTANCE' -or
        $Receipt.releaseSource.sha -cne $Authority.sha -or
        $Receipt.releaseSource.repository -cne $Authority.publicRepository -or
        [string]$Receipt.releaseSource.repositoryId -cne $Authority.publicRepositoryId -or
        [string]$Receipt.producer.repositoryId -cne $Authority.privateRepositoryId -or
        [string]$Receipt.producer.runId -cne $Authority.runId -or
        [string]$Receipt.producer.runAttempt -cne $Authority.runAttempt -or
        $Receipt.producer.workflowPath -cne $Authority.workflowPath -or
        $Receipt.producer.headSha -cne $Authority.sha -or
        $Receipt.runtime.receipt -cne 'runtime/receipt.json' -or
        $Receipt.runtime.sdkManifestSha256 -cne $Authority.sdkPin) {
        throw 'Distribution must match the independently authenticated producer, final source and SDK.'
    }
    foreach ($pin in @($Receipt.runtime.receiptSha256, $Receipt.runtime.inputArchiveSha256)) { Assert-K8sPin $pin }
}

try {
    if (![OperatingSystem]::IsLinux()) { throw 'Linux private metadata runner required.' }
    $SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).ProviderPath
    if ([IO.Path]::GetFullPath((Join-Path $SourceRoot 'tools/ci')) -cne $PSScriptRoot) { throw 'Stager must belong to the trusted checkout.' }
    foreach ($name in @('import-v019-private-owned-runtime.ps1', 'stage-v019-private-evidence.ps1', 'check-k8s-acceptance.ps1', 'new-k8s-acceptance-receipt.ps1')) {
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
    $workflow = Assert-PrivateOwnedContext $context
    if ($context.sourceSha -cne $context.dispatchSha -or $workflow -cne '.github/workflows/framework-readiness.yml' -or
        $env:EVIDENCE_MODE -cne 'private-upload' -or $env:EVENT_REPOSITORY_PRIVATE -cne 'true' -or
        $env:SAMPLE_ASSET_ID -cnotmatch '^[1-9][0-9]{0,18}$' -or $env:GITHUB_RUN_ATTEMPT -cnotmatch '^[1-9][0-9]*$' -or
        $env:SAMPLE_ARTIFACT_ID -or $env:SAMPLE_ARTIFACT_SHA256) {
        throw 'Exact-S manual private metadata distribution required.'
    }
    foreach ($pin in @($env:SAMPLE_ASSET_SHA256, $env:SAMPLE_RECEIPT_SHA256, $env:K8S_SDK_PIN)) { Assert-K8sPin $pin }
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha $workflow
    $output = Assert-PrivateOwnedOutput $env:RUNNER_TEMP $SourceRoot $SourceRoot
    $api = 'https://api.github.com/repos/' + $context.repository
    $headers = @{Authorization=('Bearer ' + $context.token); Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'}
    Assert-PrivateOwnedRepository (Invoke-RestMethod -Uri $api -Headers $headers -TimeoutSec 30 -MaximumRedirection 0) $context.repository $context.repositoryId
    $publicApi = 'https://api.github.com/repos/' + $context.publicRepository
    $publicHeaders = @{Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'}
    Assert-PrivateOwnedPublicSource (Invoke-RestMethod -Uri $publicApi -Headers $publicHeaders -TimeoutSec 30 -MaximumRedirection 0) $context.publicRepository $context.publicRepositoryId
    $commit = Invoke-RestMethod -Uri ($publicApi + '/commits/' + $context.sourceSha) -Headers $publicHeaders -TimeoutSec 30 -MaximumRedirection 0
    if ($commit.sha -cne $context.sourceSha) { throw 'Final public source is unavailable.' }
    $uri = $api + '/releases/assets/' + $env:SAMPLE_ASSET_ID
    $asset = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateEvidenceReleaseAsset $asset $context.repository $env:SAMPLE_ASSET_ID $env:SAMPLE_ASSET_SHA256
    $null = [IO.Directory]::CreateDirectory($output)
    [IO.File]::SetUnixFileMode($output, [IO.UnixFileMode]448)
    $archive = Join-Path $output 'existing-evidence.zip'
    Receive-SampleRuntimeArchive $uri $context.token $archive 'application/octet-stream'
    if ((Get-Item -LiteralPath $archive -Force).Length -ne $asset.size) { throw 'Pinned asset length differs.' }
    $package = Join-Path $output 'evidence'; $runtime = Join-Path $package 'runtime'
    $receiptPath = Expand-SampleRuntimeArchive $archive $runtime $env:SAMPLE_ASSET_SHA256 $env:SAMPLE_RECEIPT_SHA256
    $original = Get-PrivateEvidenceMemberPins $runtime
    $receipt = Read-K8sJson $receiptPath
    $null = Assert-K8sInventory $receipt $runtime
    $identity = Get-K8sSourceIdentity $receipt
    $policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
    $authority = @{publicRepository=$context.publicRepository; publicRepositoryId=$context.publicRepositoryId
        sdkPin=$env:K8S_SDK_PIN; privateRepositoryId=$context.repositoryId
        runId=[string]$receipt.producer.runId; runAttempt=[string]$receipt.producer.runAttempt; workflowPath=$receipt.producer.workflowPath}
    if ($identity.kind -ceq 'SOURCE_SNAPSHOT') { $authority.sourceIdentity = $identity } else { $authority.sha = $identity.sha }
    # The original receipt is independently pinned, not relabelled as this Actions run.
    Assert-K8sHeader $receipt $authority $policy
    $manifest = Resolve-K8sMember $runtime $receipt.source.manifest
    Assert-K8sPreservation $receipt $runtime $env:K8S_SDK_PIN $policy
    $qualification = $null
    if ($env:K8S_EQUIVALENCE_PATH -or $env:K8S_EQUIVALENCE_PIN) {
        if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
            # Separate input bundle: putting a receipt-bound proof in that receipt would create a hash cycle.
            $proofPath = $env:K8S_EQUIVALENCE_PATH
            if (![IO.Path]::IsPathFullyQualified($proofPath)) { throw 'Explicit separate snapshot qualification bundle required.' }
            $proofPath = [IO.Path]::GetFullPath($proofPath)
            if ($proofPath.StartsWith($runtime + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) { throw 'Snapshot qualification cannot modify original runtime evidence.' }
            $proof = Assert-K8sSourceEquivalence $proofPath $env:K8S_EQUIVALENCE_PIN $manifest $SourceRoot '' `
                $context.sourceSha $env:K8S_SDK_PIN $identity $env:SAMPLE_RECEIPT_SHA256 $policy
            $proofRoot = Split-Path -Parent $proofPath
            $proofEntry = @{path='source-equivalence.json'; bytes=(Get-Item -LiteralPath $proofPath).Length; sha256=$env:K8S_EQUIVALENCE_PIN}
            foreach ($entry in @($proof.files) + @($proofEntry)) {
                Copy-K8sAssemblyFile (Resolve-K8sMember $proofRoot $entry.path) `
                    (Resolve-K8sMember $package ('qualification/' + $entry.path)) $entry.bytes $entry.sha256
            }
            $qualification = @{path='qualification/source-equivalence.json'; sha256=$env:K8S_EQUIVALENCE_PIN}
        } else {
            $proofPath = Resolve-K8sMember $runtime $env:K8S_EQUIVALENCE_PATH
            $null = Assert-K8sSourceEquivalence $proofPath $env:K8S_EQUIVALENCE_PIN $manifest $SourceRoot $identity.sha $context.sourceSha $env:K8S_SDK_PIN
            [IO.File]::Copy($proofPath, (Join-Path $package 'source-equivalence.json'), $false)
            $qualification = @{path='source-equivalence.json'; sha256=$env:K8S_EQUIVALENCE_PIN}
        }
    } else {
        if ($identity.kind -ceq 'SOURCE_SNAPSHOT' -or $identity.sha -cne $context.sourceSha) { throw 'Execution source differs; an independently pinned equivalence proof is required.' }
        Assert-K8sSourceAndSdk $receipt $runtime $SourceRoot $env:K8S_SDK_PIN $policy
    }
    Assert-PrivateEvidenceMembersUnchanged $runtime $original
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha $workflow
    $files = @(Get-ChildItem -LiteralPath $package -Recurse -Force -File | Sort-Object FullName | ForEach-Object {
        @{path=[IO.Path]::GetRelativePath($package,$_.FullName).Replace('\','/'); bytes=$_.Length
            sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })
    $envelope = [ordered]@{schema='cachedb.k8s-distribution/v1'; kind='K8S_EVIDENCE_DISTRIBUTION_NOT_RUNTIME_ACCEPTANCE'
        releaseSource=@{sha=$context.sourceSha; repository=$context.publicRepository; repositoryId=$context.publicRepositoryId}
        producer=@{repositoryId=$context.repositoryId; runId=$context.runId; runAttempt=$env:GITHUB_RUN_ATTEMPT; workflowPath=$workflow; headSha=$context.sourceSha}
        runtime=@{receipt='runtime/receipt.json'; receiptSha256=$env:SAMPLE_RECEIPT_SHA256; inputArchiveSha256=$env:SAMPLE_ASSET_SHA256; sdkManifestSha256=$env:K8S_SDK_PIN}
        sourceEquivalence=$qualification; files=$files}
    [IO.File]::WriteAllText((Join-Path $package 'receipt.json'), ($envelope | ConvertTo-Json -Depth 32) + "`n", [Text.UTF8Encoding]::new($false))
    $null = Assert-K8sInventory $envelope $package
    Write-Host 'K8S_EVIDENCE_DISTRIBUTION_STAGED; NOT_RUNTIME_ACCEPTANCE; NO_WORKLOADS_EXECUTED'
} catch {
    throw 'Pinned Kubernetes evidence staging failed; upload must not run.'
}
