#requires -Version 7.3
[CmdletBinding()]
param([string]$SourceRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-PrivateEvidenceUploadInputs([hashtable]$Context, [hashtable]$Inputs) {
    $workflow = Assert-PrivateOwnedContext $Context
    if ($Context.sourceSha -cne $Context.dispatchSha -or
        $workflow -cne '.github/workflows/framework-readiness.yml' -or $Inputs.mode -cne 'private-upload' -or
        $Inputs.repositoryPrivate -cne 'true' -or $Inputs.artifactId -or $Inputs.artifactPin -or
        $Inputs.assetId -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $Inputs.assetPin -cnotmatch '^[a-f0-9]{64}$' -or $Inputs.receiptPin -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Exact private upload at final source/workflow commit S and explicit release asset pins required.'
    }
    Assert-SampleRuntimeQualificationInputs $Inputs.docPath $Inputs.docPin $Inputs.toolPath $Inputs.toolPin
    if (!$Inputs.docPath -or !$Inputs.docPin -or !$Inputs.toolPath -or !$Inputs.toolPin) {
        throw 'Both independently approved qualification references are mandatory for retained evidence upload.'
    }
}

function Assert-PrivateEvidenceReleaseAsset($Metadata, [string]$Repository, [string]$Id, [string]$Pin) {
    $uri = 'https://api.github.com/repos/' + $Repository + '/releases/assets/' + $Id
    if ([string]$Metadata.id -cne $Id -or $Metadata.url -cne $uri -or $Metadata.state -cne 'uploaded' -or
        ($Metadata.size -isnot [int] -and $Metadata.size -isnot [long]) -or
        $Metadata.size -le 0 -or $Metadata.size -gt 512MB -or $Metadata.digest -cne ('sha256:' + $Pin)) {
        throw 'Same-private-repository release asset identity, digest, state or bounded size differs.'
    }
}

function Get-PrivateEvidenceMemberPins([string]$Root) {
    $pins = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($file in [IO.Directory]::EnumerateFiles($Root, '*', [IO.SearchOption]::AllDirectories)) {
        $relative = [IO.Path]::GetRelativePath($Root, $file).Replace('\', '/')
        if ($pins.Count -ge 20000 -or ([IO.File]::GetAttributes($file) -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Evidence member inventory differs from bounded regular archive files.'
        }
        $pins.Add($relative, (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())
    }
    return ,$pins
}

function Assert-PrivateEvidenceMembersUnchanged([string]$Root, $Original) {
    foreach ($path in $Original.Keys) {
        $file = Join-Path $Root $path
        if (![IO.File]::Exists($file) -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Original[$path]) {
            throw 'An original evidence member changed; upload is prohibited.'
        }
    }
    $current = Get-PrivateEvidenceMemberPins $Root
    foreach ($path in $current.Keys) {
        if (!$Original.ContainsKey($path) -and !$path.StartsWith('private-upload-qualification/', [StringComparison]::Ordinal)) {
            throw 'Unexpected added evidence member; upload is prohibited.'
        }
    }
}

try {
    if (![OperatingSystem]::IsLinux()) { throw 'Linux private runner required.' }
    $SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).ProviderPath
    # Load only trusted checkout function definitions, never any extracted source or report.
    $tokens = $errors = $null
    $helper = Join-Path $SourceRoot 'tools/ci/import-v019-private-owned-runtime.ps1'
    $ast = [Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'Trusted private import helper does not parse.' }
    foreach ($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
        . ([scriptblock]::Create($function.Extent.Text))
    }
    foreach ($definition in Read-PrivateOwnedImportFunctions (Join-Path $SourceRoot '.github/workflows/framework-readiness.yml')) {
        . ([scriptblock]::Create($definition))
    }
    $context = @{
        actions = $env:GITHUB_ACTIONS; event = $env:GITHUB_EVENT_NAME; api = $env:GITHUB_API_URL; server = $env:GITHUB_SERVER_URL
        repository = $env:GITHUB_REPOSITORY; repositoryId = $env:GITHUB_REPOSITORY_ID; runId = $env:GITHUB_RUN_ID
        approvedRepository = $env:APPROVED_PRIVATE_REPOSITORY; approvedRepositoryId = $env:APPROVED_PRIVATE_REPOSITORY_ID
        publicRepository = $env:PUBLIC_SOURCE_REPOSITORY; publicRepositoryId = $env:PUBLIC_SOURCE_REPOSITORY_ID
        sourceSha = $env:APPROVED_SOURCE_SHA; dispatchSha = $env:GITHUB_SHA; workflowSha = $env:PRIVATE_WORKFLOW_SHA
        workflowRef = $env:GITHUB_WORKFLOW_REF; token = $env:GITHUB_TOKEN
    }
    $inputs = @{
        mode = $env:EVIDENCE_MODE; repositoryPrivate = $env:EVENT_REPOSITORY_PRIVATE
        artifactId = $env:SAMPLE_ARTIFACT_ID; artifactPin = $env:SAMPLE_ARTIFACT_SHA256
        assetId = $env:SAMPLE_ASSET_ID; assetPin = $env:SAMPLE_ASSET_SHA256; receiptPin = $env:SAMPLE_RECEIPT_SHA256
        docPath = $env:SAMPLE_DOC_PATH; docPin = $env:SAMPLE_DOC_PIN; toolPath = $env:SAMPLE_TOOL_PATH; toolPin = $env:SAMPLE_TOOL_PIN
    }
    Assert-PrivateEvidenceUploadInputs $context $inputs
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha '.github/workflows/framework-readiness.yml'
    $output = Assert-PrivateOwnedOutput $env:RUNNER_TEMP $SourceRoot $SourceRoot
    $repositoryUri = 'https://api.github.com/repos/' + $context.repository
    $publicUri = 'https://api.github.com/repos/' + $context.publicRepository
    $publicHeaders = @{ Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
    $headers = @{ Authorization = 'Bearer ' + $context.token; Accept = $publicHeaders.Accept; 'X-GitHub-Api-Version' = '2022-11-28' }
    $metadata = Invoke-RestMethod -Uri $repositoryUri -Headers $headers -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateOwnedRepository $metadata $context.repository $context.repositoryId
    $public = Invoke-RestMethod -Uri $publicUri -Headers $publicHeaders -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateOwnedPublicSource $public $context.publicRepository $context.publicRepositoryId
    $commit = Invoke-RestMethod -Uri ($publicUri + '/commits/' + $context.sourceSha) -Headers $publicHeaders -TimeoutSec 30 -MaximumRedirection 0
    if ($commit.sha -cne $context.sourceSha) { throw 'Final source S is not present in the approved public repository.' }
    $assetUri = $repositoryUri + '/releases/assets/' + $inputs.assetId
    $asset = Invoke-RestMethod -Uri $assetUri -Headers $headers -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateEvidenceReleaseAsset $asset $context.repository $inputs.assetId $inputs.assetPin
    $null = [IO.Directory]::CreateDirectory($output)
    [IO.File]::SetUnixFileMode($output, [IO.UnixFileMode]448)
    $archive = Join-Path $output 'existing-evidence.zip'
    Receive-SampleRuntimeArchive $assetUri $context.token $archive 'application/octet-stream'
    if ((Get-Item -LiteralPath $archive).Length -ne $asset.size) { throw 'Release asset length differs from metadata.' }
    $evidence = Join-Path $output 'evidence'
    $receipt = Expand-SampleRuntimeArchive $archive $evidence $inputs.assetPin $inputs.receiptPin
    $original = Get-PrivateEvidenceMemberPins $evidence
    $qualificationOutput = Join-Path $evidence 'private-upload-qualification'
    if (Test-Path -LiteralPath $qualificationOutput) { throw 'Existing evidence occupies reserved fresh qualification output.' }
    $null = [IO.Directory]::CreateDirectory($qualificationOutput)
    $qualification = @{ docPath = $inputs.docPath; docPin = $inputs.docPin; toolPath = $inputs.toolPath; toolPin = $inputs.toolPin }
    Invoke-PrivateOwnedChecker $SourceRoot $receipt $inputs.receiptPin $qualificationOutput $qualification
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha '.github/workflows/framework-readiness.yml'
    Assert-PrivateEvidenceMembersUnchanged $evidence $original
    $proof = [ordered]@{
        schema = 1; kind = 'RETAINED_EVIDENCE_VALIDATED_UPLOAD_NOT_NEW_RUNTIME'
        sourceCommitS = $context.sourceSha; workflowCommitS = $context.workflowSha; dispatchRunId = $context.runId
        releaseAssetId = $inputs.assetId; inputArchiveSha256 = $inputs.assetPin; receiptSha256 = $inputs.receiptPin
        qualificationReferences = $qualification; originalMemberCount = $original.Count; originalMembersUnchanged = $true
        runtimeResultsRegenerated = $false; checkerExit = 0
    }
    $proofPath = Join-Path $qualificationOutput 'transport.json'
    $stream = [IO.File]::Open($proofPath, [IO.FileMode]::CreateNew)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($proof | ConvertTo-Json -Depth 5) + "`n")
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
    Write-Output 'Existing immutable evidence validated for private-only upload; no runtime results were regenerated.'
} catch {
    # Never expose API responses, credentials, signed URLs, raw evidence or private identities.
    throw 'Private retained-evidence staging failed; upload must not run.'
}
