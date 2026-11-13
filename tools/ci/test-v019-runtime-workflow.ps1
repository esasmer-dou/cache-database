#requires -Version 7.3
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = '',
    [switch]$QualificationOnly,
    [switch]$ReleaseArchiveOnly,
    [switch]$PrivateProducerOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (![OperatingSystem]::IsLinux()) { throw 'Linux tooling workspace required.' }
if (@(@($QualificationOnly, $ReleaseArchiveOnly, $PrivateProducerOnly) | Where-Object { $_ }).Count -gt 1) { throw 'Select only one isolated control group.' }
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-runtime-workflow-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Fresh contract directory required.' }
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
$null = [IO.Directory]::CreateDirectory($EvidenceDirectory)
$controls = [Collections.Generic.List[string]]::new()
function Check([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "Contract failed: $Name" }
    $controls.Add($Name)
}
function Reject([string]$Name, [scriptblock]$Action, [string]$Reason) {
    try { $null = & $Action } catch {
        if ($_.Exception.Message -notmatch $Reason) { throw "Wrong rejection for ${Name}: $($_.Exception.GetType().Name)" }
        $controls.Add($Name); return
    }
    throw "Invalid import accepted: $Name"
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Test-PrivateProducer {
    . (Join-Path $RepositoryRoot 'tools/ci/k8s-source-equivalence.ps1')
    $producerPath = Join-Path $RepositoryRoot 'tools/ci/stage-k8s-private-producer.ps1'
    $producerSource = [IO.File]::ReadAllText($producerPath)
    $producerYaml = [IO.File]::ReadAllText((Join-Path $RepositoryRoot '.github/workflows/k8s-evidence-producer.yml')).Replace("`r`n", "`n")
    foreach ($name in @('import-v019-private-owned-runtime.ps1', 'stage-v019-private-evidence.ps1',
        'check-k8s-acceptance.ps1', 'stage-k8s-acceptance.ps1', 'stage-k8s-private-producer.ps1')) {
        foreach ($definition in Import-K8sGuardFunctions (Join-Path $RepositoryRoot "tools/ci/$name")) {
            . ([scriptblock]::Create($definition))
        }
    }
    foreach ($definition in Read-PrivateOwnedImportFunctions (Join-Path $RepositoryRoot '.github/workflows/framework-readiness.yml')) {
        . ([scriptblock]::Create($definition))
    }
    Check 'private-producer-manual-private-read-only-workflow' ($producerYaml.Contains("    if: github.event_name == 'workflow_dispatch' && github.event.repository.private == true") -and
        $producerYaml.Contains("permissions:`n  contents: read") -and $producerYaml -notmatch '(?m)^  (push|pull_request|schedule):|contents: write|packages: write|continue-on-error|secrets\.')
    Check 'private-producer-fixed-workflow-and-stage-deadlines' ($producerYaml.Contains('    timeout-minutes: 26') -and
        $producerYaml.Contains('        timeout-minutes: 24'))
    Check 'private-producer-checkout-actual-source-no-retained-credentials' ($producerYaml.Contains('          ref: ${{ github.sha }}') -and
        $producerYaml.Contains('          persist-credentials: false') -and $producerYaml.Contains('$env:PRIVATE_WORKFLOW_SHA -cne $env:GITHUB_SHA'))
    Check 'private-producer-upload-only-validated-evidence' ($producerYaml.Contains('        run: ./tools/ci/stage-k8s-private-producer.ps1') -and
        $producerYaml.Contains('          path: ${{ runner.temp }}/v019-private-owned-runtime/evidence/') -and
        $producerYaml.Contains('          if-no-files-found: error') -and $producerYaml.Contains('          include-hidden-files: true') -and
        $producerYaml -notmatch 'if: always\(|setup-java|\bmvn\b|\bjava\b|\bnode\b')
    function Assert-ProducerWiring([string]$Text) {
        $previous = -1
        foreach ($line in @('Assert-PrivateProducerInputs $context $inputs',
            'Wait-PrivateProducerHandoff $releaseUri $headers $handoffName $archiveName',
            'Assert-PrivateProducerHandoff $handoff $context $inputs $archiveName',
            'Expand-SampleRuntimeArchive $archive $package $handoff.archive.sha256 $handoff.receiptSha256',
            'Assert-K8sDistributionEnvelope $envelope $authority',
            'Resolve-K8sAcceptance $envelope $package $SourceRoot $authority $policy',
            'Assert-K8sHeader $accepted.receipt $runtimeAuthority $policy',
            'Assert-K8sReports $accepted.receipt $accepted.root $SourceRoot $privateResults $policy $accepted.sourceQualification',
            'Assert-PrivateEvidenceMembersUnchanged $package $original',
            'Assert-K8sInventory $envelope $package',
            'Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha $workflow',
            "[IO.File]::AppendAllText(`$env:GITHUB_OUTPUT,")) {
            $index = $Text.IndexOf($line, ($previous + 1), [StringComparison]::Ordinal)
            if ($index -lt 0) { throw 'Producer validation/publication ordering differs.' }
            $previous = $index
        }
        if (!$Text.Contains("`$env:GH_TOKEN = ''; `$env:GITHUB_TOKEN = ''") -or
            !$Text.Contains("Resolve-K8sMember `$package 'handoff/frozen-plan'") -or
            !$Text.Contains('Original execution source manifest differs dispatch pin.') -or
            !$Text.Contains('$Inputs.runAttempt') -or $Text -match 'Invoke-Expression|Start-Process|&\s*\$receipt') {
            throw 'Producer validation/publication ordering differs.'
        }
    }
    Assert-ProducerWiring $producerSource
    Check 'private-producer-inner-outer-authority-before-output' $true
    foreach ($line in @('Assert-K8sHeader $accepted.receipt $runtimeAuthority $policy',
        'Assert-K8sReports $accepted.receipt $accepted.root $SourceRoot $privateResults $policy $accepted.sourceQualification',
        'Assert-PrivateEvidenceMembersUnchanged $package $original', "`$env:GH_TOKEN = ''; `$env:GITHUB_TOKEN = ''")) {
        Reject ('producer-rejects-removed-validation-' + $controls.Count) { Assert-ProducerWiring ($producerSource.Replace($line, '')) } 'ordering differs'
    }
    $context = @{actions='true'; event='workflow_dispatch'; api='https://api.github.com'; server='https://github.com'
        repository='synthetic/private'; repositoryId='42'; approvedRepository='synthetic/private'; approvedRepositoryId='42'
        publicRepository='synthetic/public'; publicRepositoryId='24'; runId='111'; sourceSha=('a' * 40)
        dispatchSha=('a' * 40); workflowSha=('a' * 40); token='SYNTHETIC-NOT-A-CREDENTIAL'
        workflowRef='synthetic/private/.github/workflows/k8s-evidence-producer.yml@refs/heads/main'}
    $inputs = @{repositoryPrivate='true'; releaseId='333'; runAttempt='2'; sourceManifestPin=('b' * 64)
        sdkPin=('c' * 64); frozenPlanPin=('d' * 64)}
    Check 'producer-admits-exact-real-run-context' ((Assert-PrivateProducerInputs $context $inputs) -ceq '.github/workflows/k8s-evidence-producer.yml')
    foreach ($row in @(@('event','push'), @('runId','0'), @('workflowSha',('f' * 40)), @('dispatchSha',('f' * 40)),
        @('workflowRef','synthetic/private/.github/workflows/framework-readiness.yml@refs/heads/main'),
        @('workflowRef','synthetic/private/.github/workflows/k8s-evidence-producer.yml@refs/tags/v0.19.0'), @('token',''))) {
        $bad=$context.Clone(); $bad[$row[0]]=$row[1]
        Reject ('producer-context-' + $controls.Count) { Assert-PrivateProducerInputs $bad $inputs } 'required|differs|identity'
    }
    foreach ($row in @(@('repositoryPrivate','false'), @('releaseId','0'), @('releaseId','../333'), @('runAttempt','0'),
        @('sourceManifestPin',('B' * 64)), @('sdkPin',''), @('frozenPlanPin',('d' * 63)))) {
        $bad=$inputs.Clone(); $bad[$row[0]]=$row[1]
        Reject ('producer-input-' + $controls.Count) { Assert-PrivateProducerInputs $context $bad } 'required|pin|SHA256'
    }
    $release = @{id=333;url='https://api.github.com/repos/synthetic/private/releases/333';draft=$true;target_commitish=$context.sourceSha}
    Assert-PrivateProducerRelease $release '333' $context.sourceSha $context.repository
    Check 'producer-requires-draft-release-at-actual-source' $true
    foreach ($row in @(@('id',334), @('url','https://api.github.com/repos/synthetic/other/releases/333'),
        @('draft',$false), @('draft','true'), @('target_commitish','main'))) {
        $bad=$release.Clone(); $bad[$row[0]]=$row[1]
        Reject ('producer-release-' + $controls.Count) { Assert-PrivateProducerRelease $bad '333' $context.sourceSha $context.repository } 'exact final source'
    }
    $asset = @{id=444;name='canonical52-111-2.zip';url='https://api.github.com/repos/synthetic/private/releases/assets/444'
        digest=('sha256:' + ('e' * 64));state='uploaded';size=512}
    $null=Get-PrivateProducerAsset @($asset) $asset.name $context.repository
    Check 'producer-resolves-own-asset-with-authenticated-pin' $true
    Reject 'producer-duplicate-asset' { Get-PrivateProducerAsset @($asset,$asset) $asset.name $context.repository } 'Exactly one'
    foreach ($row in @(@('id',0), @('name','canonical52-112-2.zip'), @('digest',''), @('size',(512MB+1)),
        @('size',0), @('size','512'), @('state','new'), @('url','https://api.github.com/repos/synthetic/other/releases/assets/444'))) {
        $bad=$asset.Clone(); $bad[$row[0]]=$row[1]
        Reject ('producer-asset-' + $controls.Count) { Get-PrivateProducerAsset @($bad) $asset.name $context.repository } 'required|metadata|differs'
    }
    Reject 'producer-handoff-exceeds-64KiB' { Get-PrivateProducerAsset @($asset) $asset.name $context.repository 511 } 'bounded'
    $handoff = @{schema='cachedb.k8s-producer-handoff/v1';sourceSha=$context.sourceSha
        sourceManifestSha256=$inputs.sourceManifestPin;sdkManifestSha256=$inputs.sdkPin;frozenPlanSha256=$inputs.frozenPlanPin
        privateReleaseId='333';producer=@{repositoryId='42';runId='111';runAttempt='2';workflowPath='.github/workflows/k8s-evidence-producer.yml'}
        archive=@{id='444';name=$asset.name;sha256=('e' * 64)};receiptSha256=('f' * 64)}
    Assert-PrivateProducerHandoff $handoff $context $inputs $asset.name
    Check 'producer-handoff-binds-all-frozen-inputs-and-real-run' $true
    foreach ($field in @('schema','sourceSha','sourceManifestSha256','sdkManifestSha256','frozenPlanSha256','privateReleaseId','receiptSha256',
        'producer.repositoryId','producer.runId','producer.runAttempt','producer.workflowPath','archive.id','archive.name','archive.sha256','extra')) {
        $bad = $handoff | ConvertTo-Json -Depth 8 | ConvertFrom-Json -AsHashtable
        $parts=$field.Split('.'); if ($parts.Count -eq 2) { $bad[$parts[0]][$parts[1]]='invalid' } else { $bad[$field]='invalid' }
        Reject ('producer-handoff-' + $field) { Assert-PrivateProducerHandoff $bad $context $inputs $asset.name } 'differs|pin|SHA256'
    }
    # Replace only transport in this local function scope. Never call GitHub or the script entrypoint.
    $fixture=Join-Path $EvidenceDirectory 'private-producer'; $null=[IO.Directory]::CreateDirectory($fixture)
    $fixtureBytes=[Text.Encoding]::UTF8.GetBytes('SYNTHETIC ASSET TRANSPORT ONLY')
    function Receive-SampleRuntimeArchive([string]$Uri,[string]$Token,[string]$Destination,[string]$Accept) {
        if ($Uri -cne $asset.url -or $Token -cne $context.token -or $Accept -cne 'application/octet-stream') { throw 'Unexpected fixture transfer.' }
        [IO.File]::WriteAllBytes($Destination,$fixtureBytes)
    }
    $transport=$asset.Clone(); $transport.size=$fixtureBytes.Length
    $transport.digest='sha256:'+[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($fixtureBytes)).ToLowerInvariant()
    Save-PrivateProducerAsset $transport $context.repository $context.token (Join-Path $fixture 'valid.bin')
    Check 'producer-transfer-verifies-authenticated-bytes' $true
    $corrupt=$transport.Clone(); $corrupt.digest='sha256:'+('e'*64)
    Reject 'producer-transfer-digest-mismatch' { Save-PrivateProducerAsset $corrupt $context.repository $context.token (Join-Path $fixture 'corrupt.bin') } 'Downloaded asset bytes'
    $corrupt=$transport.Clone(); $corrupt.size++
    Reject 'producer-transfer-length-mismatch' { Save-PrivateProducerAsset $corrupt $context.repository $context.token (Join-Path $fixture 'length.bin') } 'Downloaded asset bytes'
    $ready=$asset.Clone(); $ready.id=445; $ready.name='canonical52-111-2.handoff.json'
    $waitAssets=@($asset,$ready); $metadataCalls=0; $metadataFailure=$false
    function Invoke-PrivateProducerMetadata([string]$Uri,[hashtable]$Headers,[int]$TimeoutSeconds=20) {
        $metadataCalls++
        if ($Uri -cne 'https://api.github.com/repos/synthetic/private/releases/333/assets?per_page=100' -or
            $TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 20) { throw 'Unbounded fixture request.' }
        if ($metadataFailure) { throw 'SYNTHETIC API FAILURE' }
        return $waitAssets
    }
    $observed=Wait-PrivateProducerHandoff $release.url @{} $ready.name $asset.name
    Check 'producer-wait-accepts-completed-own-zip-before-handoff' ($observed.Count -eq 2)
    $waitAssets=@($asset,$ready,$ready)
    Reject 'producer-wait-ambiguous-handoff' { Wait-PrivateProducerHandoff $release.url @{} $ready.name $asset.name } 'Ambiguous'
    $waitAssets=@($ready)
    Reject 'producer-wait-rejects-handoff-before-zip' { Wait-PrivateProducerHandoff $release.url @{} $ready.name $asset.name } 'completed archive'
    $pending=$asset.Clone(); $pending.state='new'; $waitAssets=@($pending,$ready)
    Reject 'producer-wait-rejects-incomplete-zip' { Wait-PrivateProducerHandoff $release.url @{} $ready.name $asset.name } 'completed archive'
    $waitAssets=@(1..100 | ForEach-Object { $asset })
    Reject 'producer-wait-rejects-second-page' { Wait-PrivateProducerHandoff $release.url @{} $ready.name $asset.name } 'one-page'
    $metadataFailure=$true
    Reject 'producer-wait-does-not-retry-api-failures' { Wait-PrivateProducerHandoff $release.url @{} $ready.name $asset.name } 'SYNTHETIC API FAILURE'
    $waitText=(Get-Command Wait-PrivateProducerHandoff).ScriptBlock.ToString()
    Check 'producer-wait-monotonic-fixed-budget-not-renewed' ($waitText.Contains('[Diagnostics.Stopwatch]::StartNew()') -and
        $waitText.Contains('while ($clock.Elapsed.TotalSeconds -lt 840)') -and
        $waitText.Contains('[Math]::Min(20, [Math]::Floor($remaining))') -and
        $waitText.Contains('if ($clock.Elapsed.TotalSeconds -ge 840)') -and
        $waitText.Contains('[Math]::Min(10000,') -and $waitText -notmatch '\.Restart\(|\.Reset\(|catch\s*\{')
}
function Test-CanonicalReleaseGate {
    $checker = Join-Path $RepositoryRoot 'tools/ci/check-ga-release-readiness.ps1'
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($checker,[ref]$tokens,[ref]$errors)
    Check 'canonical-release-checker-parses' (!$errors.Count)
    foreach ($name in @('Test-CanonicalK8sRelease','Assert-CanonicalK8sArtifact','Get-RequiredPerformanceWorkflows')) {
        $definitions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
        Check ('canonical-release-function-'+$name) ($definitions.Count -eq 1)
        . ([scriptblock]::Create($definitions[0].Extent.Text))
    }
    foreach ($tag in @('v0.19.0','v0.19.1','v0.20.0','v1.0.0')) {
        Check ('canonical-release-successor-'+$tag) (Test-CanonicalK8sRelease $tag)
    }
    foreach ($tag in @('v0.18.1','v0.17.0','v0.0.1')) {
        Check ('canonical-release-retains-legacy-'+$tag) (!(Test-CanonicalK8sRelease $tag))
    }
    foreach ($tag in @('main','v0.19.0-rc1','0.19.0','v0.19')) {
        Reject ('canonical-release-invalid-tag-'+$tag) { Test-CanonicalK8sRelease $tag } 'stable release tag'
    }
    $sha='a'*40; $Repository='synthetic/public'
    $run=@{databaseId=333;workflowName='Framework Readiness';headSha=$sha;status='completed';conclusion='success';event='push';url='https://example.invalid/synthetic'}
    $artifact=@{id=444;name='k8s-acceptance-verification';expired=$false;size_in_bytes=512;digest=('sha256:'+('b'*64))
        workflow_run=@{id=333;head_sha=$sha};url='https://api.github.com/repos/synthetic/public/actions/artifacts/444'
        archive_download_url='https://api.github.com/repos/synthetic/public/actions/artifacts/444/zip'}
    $null=Assert-CanonicalK8sArtifact @($artifact) $run $sha $Repository
    Check 'canonical-release-exact-verifier-artifact-is-required-proof' $true
    Reject 'canonical-release-artifact-missing' { Assert-CanonicalK8sArtifact @() $run $sha $Repository } 'artifact'
    Reject 'canonical-release-artifact-duplicate' { Assert-CanonicalK8sArtifact @($artifact,$artifact) $run $sha $Repository } 'artifact'
    Reject 'canonical-release-artifact-pagination-bound' { Assert-CanonicalK8sArtifact @(1..100|ForEach-Object{$artifact}) $run $sha $Repository } 'one-page'
    foreach ($row in @(@('id',0),@('name','public-evidence-import'),@('expired',$true),@('expired','false'),
        @('size_in_bytes',0),@('size_in_bytes',(1MB+1)),@('size_in_bytes','512'),@('digest',''),@('digest',('sha256:'+('B'*64))),
        @('workflow_run.id',334),@('workflow_run.head_sha',('c'*40)),@('url','https://example.invalid/444'),
        @('archive_download_url','https://example.invalid/444/zip'))) {
        $bad=$artifact|ConvertTo-Json -Depth 5|ConvertFrom-Json -AsHashtable
        $parts=$row[0].Split('.'); if($parts.Count -eq 2){$bad[$parts[0]][$parts[1]]=$row[1]}else{$bad[$row[0]]=$row[1]}
        Reject ('canonical-release-artifact-'+$row[0]+'-'+$controls.Count) { Assert-CanonicalK8sArtifact @($bad) $run $sha $Repository } 'artifact'
    }
    foreach ($row in @(@('databaseId',0),@('headSha',('c'*40)),@('workflowName','Other'),@('status','in_progress'),
        @('conclusion','skipped'),@('event','pull_request'))) {
        $bad=$run.Clone();$bad[$row[0]]=$row[1]
        Reject ('canonical-release-run-'+$row[0]) { Assert-CanonicalK8sArtifact @($artifact) $bad $sha $Repository } 'exact-S'
    }
    $checks=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and
        $node.GetCommandName() -ceq 'Invoke-ReadinessCheck' -and @($node.CommandElements|Where-Object{
            $_ -is [Management.Automation.Language.StringConstantExpressionAst] -and $_.Value -ceq 'Framework Readiness workflow'
        }).Count -eq 1},$true))
    Check 'canonical-release-one-real-framework-gate' ($checks.Count -eq 1)
    $body=@($checks[0].CommandElements|Where-Object{$_ -is [Management.Automation.Language.ScriptBlockExpressionAst]})
    Check 'canonical-release-one-real-framework-gate-body' ($body.Count -eq 1)
    $gate=[scriptblock]::Create(($body[0].ScriptBlock.EndBlock.Statements.Extent.Text -join "`n"))
    $queries=[Collections.Generic.List[string]]::new()
    $metadataJson=ConvertTo-Json -InputObject @{artifacts=@($artifact)} -Depth 6
    function Get-LatestSuccessfulRun([string]$WorkflowName,[string]$HeadBranch,[string]$HeadSha,[string[]]$RequiredJobNames) {
        if ($WorkflowName -cne 'Framework Readiness' -or $HeadBranch -cne 'main' -or $HeadSha -cne $sha -or
            ($RequiredJobNames -join ',') -cne 'K8s Acceptance Verification') { throw 'Exact verifier job selection lost.' }
        return $run
    }
    function Invoke-Native([string]$Command,[string[]]$Arguments) {
        if ($Command -cne 'gh' -or ($Arguments -join ' ') -cne
            'api --method GET repos/synthetic/public/actions/runs/333/artifacts?per_page=100') { throw 'Unbounded or unrelated proof query.' }
        $queries.Add('selected-run-proof');return $metadataJson
    }
    $targetRefValue='main';$targetSha=$sha;$releaseTagValue='v0.19.0'
    $null=& $gate
    Check 'canonical-release-real-gate-validates-selected-run-proof-once' ($queries.Count -eq 1)
    $metadataJson='{"artifacts":[]}'
    Reject 'canonical-release-real-gate-cannot-pass-without-proof' { & $gate } 'artifact'
    $queries.Clear();$releaseTagValue='v0.18.1';$null=& $gate
    Check 'canonical-release-legacy-gate-adds-no-new-artifact-query' (!$queries.Count)
    $branches=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and
        ($node.Clauses[0].Item1.Extent.Text -replace '\s','') -ceq '!(Test-CanonicalK8sRelease$releaseTagValue)'},$true))
    Check 'canonical-release-one-real-legacy-selection-branch' ($branches.Count -eq 1)
    $branch=[scriptblock]::Create($branches[0].Extent.Text)
    $names=[Collections.Generic.List[string]]::new()
    function Invoke-ReadinessCheck([string]$Name,[scriptblock]$Check) { $names.Add($Name) }
    foreach ($tag in @('v0.19.0','v1.0.0')) {
        $names.Clear();$releaseTagValue=$tag;& $branch
        Check ('canonical-release-never-requires-legacy-replay-'+$tag) (!$names.Count)
    }
    $names.Clear();$releaseTagValue='v0.18.1';& $branch
    Check 'canonical-release-actual-older-requirements-retained' (($names -join ',') -ceq 'Production Evidence workflow,Snapshot Provider Evidence workflow')
}
if ($PrivateProducerOnly) {
    Test-PrivateProducer
    Test-CanonicalReleaseGate
    [ordered]@{schema=1;syntheticOnly=$true;actualRuntimeQualified=$false;releaseAccepted=$false;controls=$controls
        producerWorkflowSha256=(Hash (Join-Path $RepositoryRoot '.github/workflows/k8s-evidence-producer.yml'))
        producerHelperSha256=(Hash (Join-Path $RepositoryRoot 'tools/ci/stage-k8s-private-producer.ps1'))} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
    Write-Host "$($controls.Count) private producer/release gate controls completed; no product or release acceptance."
    return
}
function Test-ReleaseArchiveHiddenFiles {
    $releaseTool = Join-Path $RepositoryRoot 'tools/release/build-release-bundle.ps1'
    $releaseText = Get-Content -LiteralPath $releaseTool -Raw
    $parseTokens = $null; $parseErrors = $null
    $releaseAst = [Management.Automation.Language.Parser]::ParseInput($releaseText, [ref]$parseTokens, [ref]$parseErrors)
    Check 'release-archive-tool-parses' (!$parseErrors.Count)
    $statements = @($releaseAst.EndBlock.Statements)
    $calls = @($statements | Where-Object { $_.Extent.Text -cmatch '^\[System\.IO\.Compression\.ZipFile\]::CreateFromDirectory\(' })
    Check 'one-real-release-archive-call' ($calls.Count -eq 1)
    $index = [Array]::IndexOf($statements, $calls[0])
    Check 'release-archive-call-has-guarded-cleanup' ($index -gt 0 -and
        ($statements[$index - 1].Extent.Text -replace '\s', '') -ceq 'if(Test-Path-LiteralPath$zipPath){Remove-Item-LiteralPath$zipPath-Force}')
    Check 'release-archive-exact-directory-compression-contract' (($calls[0].Extent.Text -replace '\s', '') -ceq
        '[System.IO.Compression.ZipFile]::CreateFromDirectory($stagingRoot,$zipPath,[System.IO.Compression.CompressionLevel]::Optimal,$false)')
    # Execute only the actual archive/cleanup statements against synthetic staging, never the release build.
    $archiveBlock = [scriptblock]::Create($statements[$index - 1].Extent.Text + "`n" + $calls[0].Extent.Text)
    $fixture = Join-Path $EvidenceDirectory 'release-archive'
    $stagingRoot = Join-Path $fixture 'stage'
    $zipPath = Join-Path $fixture 'fixture.zip'
    $members = [ordered]@{ 'README.md' = [Text.Encoding]::UTF8.GetBytes("SYNTHETIC ARCHIVE CONTROL`n"); 'payload.bin' = [byte[]]@(0, 1, 127, 255) }
    foreach ($provider in @('postgresql', 'mssql', 'oracle')) {
        $members["samples/sample-cache-database-$provider/.gitignore"] = [Text.Encoding]::UTF8.GetBytes("target/`n$provider`n")
        $members["samples/sample-cache-database-$provider/.github/workflows/build.yml"] = [Text.Encoding]::UTF8.GetBytes("# SYNTHETIC $provider CONTROL`n")
    }
    foreach ($name in $members.Keys) {
        $full = Join-Path $stagingRoot $name
        $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $full))
        [IO.File]::WriteAllBytes($full, $members[$name])
    }
    function Assert-ReleaseArchiveFixtureParity([string]$ArchivePath) {
        $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
        try {
            $entries = @($archive.Entries)
            $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($entry in $entries) {
                if (!$names.Add($entry.FullName) -or !$members.Contains($entry.FullName)) { throw 'Archive member roster mismatch.' }
                $stream = $entry.Open(); $algorithm = [Security.Cryptography.SHA256]::Create()
                try { $pin = [Convert]::ToHexString($algorithm.ComputeHash($stream)).ToLowerInvariant() }
                finally { $stream.Dispose(); $algorithm.Dispose() }
                if ($pin -cne (Hash (Join-Path $stagingRoot $entry.FullName))) { throw 'Archive member byte hash mismatch.' }
            }
            if ($entries.Count -ne $members.Count) { throw 'Archive member roster mismatch.' }
        } finally { $archive.Dispose() }
    }
    [IO.File]::WriteAllText($zipPath, 'SYNTHETIC STALE ARCHIVE')
    & $archiveBlock
    Assert-ReleaseArchiveFixtureParity $zipPath
    Check 'release-archive-preserves-complete-roster-and-bytes-without-base-directory' $true
    $hidden = @($members.Keys | Where-Object { $_ -match '(?:^|/)\.' })
    Check 'release-archive-fixture-has-six-hidden-sample-entries' ($hidden.Count -eq 6)
    $archive = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Update)
    try { foreach ($name in $hidden) { $archive.GetEntry($name).Delete() } } finally { $archive.Dispose() }
    Reject 'release-archive-parity-rejects-six-omitted-hidden-entries' { Assert-ReleaseArchiveFixtureParity $zipPath } 'member roster mismatch'
    & $archiveBlock
    $archive = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $archive.GetEntry('README.md').Delete()
        $stream = $archive.CreateEntry('README.md').Open()
        try { $stream.WriteByte(42) } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
    Reject 'release-archive-parity-rejects-changed-member-bytes' { Assert-ReleaseArchiveFixtureParity $zipPath } 'member byte hash mismatch'
}
Test-ReleaseArchiveHiddenFiles
if ($ReleaseArchiveOnly) {
    [ordered]@{schema=1;syntheticOnly=$true;actualRuntimeQualified=$false;releaseAccepted=$false;controls=$controls
        releaseToolSha256=(Hash (Join-Path $RepositoryRoot 'tools/release/build-release-bundle.ps1'))} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
    Write-Host "$($controls.Count) isolated release archive controls completed; no production package or release acceptance."
    return
}
$workflow = Join-Path $RepositoryRoot '.github/workflows/framework-readiness.yml'
$source = Get-Content -LiteralPath $workflow -Raw
$blocks = @([regex]::Matches($source, '(?ms)^      - name: Import pinned actual sample runtime evidence\r?\n(?<body>.*?)(?=^      - name: |\z)'))
Check 'one-real-import-step' ($blocks.Count -eq 1)
$run = [regex]::Match($blocks[0].Groups['body'].Value, '(?ms)^        run: \|\r?\n(?<script>.*)\z')
Check 'explicit-literal-script' $run.Success
$script = (($run.Groups['script'].Value -split '\r?\n') | ForEach-Object {
    if ($_ -and !($_.StartsWith('          '))) { throw 'Import script indentation changed.' }
    if ($_) { $_.Substring(10) } else { '' }
}) -join "`n"
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($script, [ref]$tokens, [ref]$errors)
Check 'actual-workflow-script-parses' (!$errors.Count)
$functions = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true))
Check 'bounded-import-functions-only' (($functions.Name | Sort-Object) -join ',' -ceq
    'Assert-PrivateSampleRuntimeRepository,Assert-SampleRuntimeArtifact,Assert-SampleRuntimeInputs,Assert-SampleRuntimeProducer,Assert-SampleRuntimeQualificationInputs,Assert-SampleRuntimeSource,Expand-SampleRuntimeArchive,Receive-SampleRuntimeArchive')
# Load only pure admission/extraction functions from the actual YAML. No network or gate is invoked.
foreach ($function in $functions | Where-Object Name -cne 'Receive-SampleRuntimeArchive') {
    . ([scriptblock]::Create($function.Extent.Text))
}
function Assert-PublicQualificationUploadDeclaration([string]$Text) {
    $blocks = @([regex]::Matches($Text, '(?ms)^      - name: Upload readiness artifacts\r?\n(?<body>.*?)(?=^      - name: |^  [a-zA-Z0-9_-]+:|\z)'))
    if ($blocks.Count -ne 1 -or $blocks[0].Value -notmatch '(?m)^        uses: actions/upload-artifact@') {
        throw 'Exact public artifact upload declaration required.'
    }
    $paths = [regex]::Match($blocks[0].Groups['body'].Value, '(?m)^          path: \|\r?\n(?<paths>(?:            [^\r\n]+\r?\n)+)')
    $allowed = @('target/k8s-acceptance-verification.md')
    $actual = @($paths.Groups['paths'].Value -split '\r?\n' | Where-Object { $_ } | ForEach-Object { $_.Trim() })
    if (!$paths.Success -or $actual.Count -ne $allowed.Count -or
        (($actual | Sort-Object) -join "`n") -cne (($allowed | Sort-Object) -join "`n")) {
        throw 'Public artifact paths must remain the exact sanitized summary/control allowlist.'
    }
    $imports = @([regex]::Matches($Text, '(?ms)^      - name: Upload R07 import summary\r?\n(?<body>.*?)(?=^      - name: |^  [a-zA-Z0-9_-]+:|\z)'))
    if ($imports.Count -ne 1 -or $imports[0].Value -notmatch '(?m)^        uses: actions/upload-artifact@') {
        throw 'Exact separate R07 artifact upload declaration required.'
    }
    $importPaths = [regex]::Match($imports[0].Groups['body'].Value, '(?m)^          path: \|\r?\n(?<paths>(?:            [^\r\n]+\r?\n)+)')
    $actualImport = @($importPaths.Groups['paths'].Value -split '\r?\n' | Where-Object { $_ } | ForEach-Object { $_.Trim() })
    if (!$importPaths.Success -or $actualImport.Count -ne 1 -or $actualImport[0] -cne 'target/v019-sample-coverage.md') {
        throw 'R07 artifact paths must remain the exact sanitized summary/control allowlist.'
    }
}

function Test-PublicQualificationPlumbing {
    $importJob = [regex]::Match($source, '(?ms)^  public-evidence-import:\r?\n(?<job>.*?)(?=^  [a-zA-Z0-9_-]+:\r?$|\z)').Groups['job'].Value
    $ordinaryJob = [regex]::Match($source, '(?ms)^  k8s-acceptance-verification:\r?\n(?<job>.*?)(?=^  [a-zA-Z0-9_-]+:\r?$|\z)').Groups['job'].Value
    Check 'ordinary-contract-isolated-from-metadata-import' ($importJob.Contains('      - name: Import pinned actual sample runtime evidence') -and
        !$importJob.Contains('./tools/ci/test-v019-runtime-workflow.ps1') -and
        $ordinaryJob -match '(?m)^        run: \./tools/ci/check-k8s-acceptance\.ps1\r?$' -and
        $ordinaryJob -match '(?m)^    name: K8s Acceptance Verification\r?$' -and
        $ordinaryJob -notmatch '(?m)^\s+(?:run:.*\bmvn\b|uses: actions/setup-java@)')
    $pin = 'a' * 64
    Assert-SampleRuntimeQualificationInputs '' '' '' ''
    Check 'qualification-is-explicitly-optional' $true
    Assert-SampleRuntimeQualificationInputs 'proof/docs.json' $pin 'proof/tooling.json' $pin
    Check 'qualification-requires-owner-supplied-complete-pairs' $true
    for ($mask = 1; $mask -lt 15; $mask++) {
        $values = @('proof/docs.json', $pin, 'proof/tooling.json', $pin)
        for ($i = 0; $i -lt 4; $i++) { if (!(($mask -shr $i) -band 1)) { $values[$i] = '' } }
        Reject "qualification-rejects-partial-chain-$mask" { Assert-SampleRuntimeQualificationInputs @values } 'both explicit'
    }
    foreach ($index in @(0, 2)) {
        foreach ($bad in @('../escape', '/absolute', 'C:/outside', 'a\b', 'a//b', 'a/./b', 'a/ b', 'a/b.')) {
            $values = @('proof/docs.json', $pin, 'proof/tooling.json', $pin); $values[$index] = $bad
            Reject "qualification-unsafe-path-$index-$($controls.Count)" { Assert-SampleRuntimeQualificationInputs @values } 'Unsafe qualification'
        }
    }
    foreach ($index in @(1, 3)) {
        foreach ($bad in @($pin.ToUpperInvariant(), ('g' * 64), 'abc')) {
            $values = @('proof/docs.json', $pin, 'proof/tooling.json', $pin); $values[$index] = $bad
            Reject "qualification-invalid-pin-$index-$($controls.Count)" { Assert-SampleRuntimeQualificationInputs @values } 'both explicit'
        }
    }
    foreach ($name in @('sampleRuntimeSourceEquivalencePath', 'sampleRuntimeSourceEquivalenceSha256', 'sampleRuntimeToolingOverlayPath', 'sampleRuntimeToolingOverlaySha256')) {
        Check "qualification-dispatch-input-$name" ($source -match ('(?m)^      ' + $name + ':\s*$'))
        Check "qualification-input-env-only-$name" ([regex]::Matches($source, [regex]::Escape('${{ inputs.' + $name + ' }}')).Count -eq 3)
    }
    $coverage = [regex]::Match($source, '(?ms)^      - name: Validate 0.19 sample capability coverage\r?\n(?<body>.*?)(?=^      - name: |\z)').Groups['body'].Value
    Check 'qualification-all-four-forwarded-by-workflow' ($coverage.Contains('$arguments.SourceEquivalencePath = $env:SAMPLE_DOC_PATH') -and
        $coverage.Contains('$arguments.SourceEquivalenceSha256 = $env:SAMPLE_DOC_PIN') -and
        $coverage.Contains('$arguments.ExecutionToolingOverlayPath = $env:SAMPLE_TOOL_PATH') -and
        $coverage.Contains('$arguments.ExecutionToolingOverlaySha256 = $env:SAMPLE_TOOL_PIN') -and
        $coverage.Contains('./tools/ci/check-v019-sample-coverage.ps1 @arguments'))
    Assert-PublicQualificationUploadDeclaration $source
    Check 'retains-only-sanitized-public-summaries' $true
    Check 'qualification-results-private-not-uploaded' ($coverage.Contains("PrivateResultsPath = (Join-Path `$env:RUNNER_TEMP 'v019-sample-qualification.json')"))
    foreach ($leak in @('target/v019-sample-coverage.json', '${{ runner.temp }}/v019-sample-qualification.json', 'target/sample-runtime-workflow-controls/summary.json', 'target/**', '${{ runner.temp }}/')) {
        $badUpload = $source.Replace('            target/k8s-acceptance-verification.md', ('            ' + $leak))
        Reject ('qualification-rejects-public-upload-leak-' + $controls.Count) { Assert-PublicQualificationUploadDeclaration $badUpload } 'sanitized summary/control allowlist'
        $badImportUpload = $source.Replace('            target/v019-sample-coverage.md', ('            ' + $leak))
        Reject ('qualification-rejects-r07-upload-leak-' + $controls.Count) { Assert-PublicQualificationUploadDeclaration $badImportUpload } 'sanitized summary/control allowlist'
    }
    Check 'qualification-admission-before-network' ($script.IndexOf('Assert-SampleRuntimeQualificationInputs $env:') -gt 0 -and
        $script.IndexOf('Assert-SampleRuntimeQualificationInputs $env:') -lt $script.IndexOf('$repository = Invoke-RestMethod'))
    $coverageRun = [regex]::Match($coverage, '(?ms)^        run: \|\r?\n(?<script>.*)\z').Groups['script'].Value
    $coverageScript = (($coverageRun -split '\r?\n') | ForEach-Object { if ($_) { $_.Substring(10) } else { '' } }) -join "`n"
    $parseTokens = $null; $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseInput($coverageScript, [ref]$parseTokens, [ref]$parseErrors)
    Check 'qualification-workflow-coverage-script-parses' (!$parseErrors.Count)
    Check 'qualification-inputs-not-interpolated-in-shell' (!$coverageScript.Contains('${{'))
    # A capture-only Node fixture tests the real PS wrapper, not receipts or runtime acceptance.
    $fixture = Join-Path $EvidenceDirectory 'wrapper-capture'
    $null = [IO.Directory]::CreateDirectory((Join-Path $fixture 'tools/ci'))
    'process.stdout.write(JSON.stringify(process.argv.slice(2)));' |
        Set-Content -LiteralPath (Join-Path $fixture 'tools/ci/check-v019-sample-coverage.mjs') -Encoding utf8
    'SYNTHETIC TRANSPORT CONTROL ONLY' | Set-Content -LiteralPath (Join-Path $fixture 'receipt.json') -Encoding utf8
    $wrapper = Join-Path $RepositoryRoot 'tools/ci/check-v019-sample-coverage.ps1'
    $valid = @{ RepositoryRoot = $fixture; RuntimeEvidencePath = (Join-Path $fixture 'receipt.json'); RuntimeEvidenceSha256 = $pin
        SourceEquivalencePath = 'proof/docs.json'; SourceEquivalenceSha256 = $pin
        ExecutionToolingOverlayPath = 'proof/tooling.json'; ExecutionToolingOverlaySha256 = $pin
        PrivateResultsPath = (Join-Path $EvidenceDirectory 'private-result.json') }
    foreach ($name in @('SourceEquivalencePath', 'SourceEquivalenceSha256', 'ExecutionToolingOverlayPath', 'ExecutionToolingOverlaySha256',
            'RuntimeEvidencePath', 'RuntimeEvidenceSha256', 'PrivateResultsPath')) {
        $invalid = $valid.Clone(); $invalid.Remove($name)
        Reject "wrapper-rejects-missing-$name" { & $wrapper @invalid } 'Qualification requires|together'
    }
    foreach ($name in @('SourceEquivalencePath', 'ExecutionToolingOverlayPath')) {
        $invalid = $valid.Clone(); $invalid[$name] = '../escape'
        Reject "wrapper-rejects-unsafe-$name" { & $wrapper @invalid } 'Unsafe qualification'
    }
    foreach ($name in @('SourceEquivalenceSha256', 'ExecutionToolingOverlaySha256')) {
        $invalid = $valid.Clone(); $invalid[$name] = $pin.ToUpperInvariant()
        Reject "wrapper-rejects-uppercase-$name" { & $wrapper @invalid } 'Qualification requires'
    }
    $forwarded = @((& $wrapper @valid) | ConvertFrom-Json)
    foreach ($pair in @(@('--source-equivalence', 'proof/docs.json'), @('--source-equivalence-sha256', $pin),
            @('--execution-tooling-overlay', 'proof/tooling.json'), @('--execution-tooling-overlay-sha256', $pin),
            @('--private-results', $valid.PrivateResultsPath))) {
        $index = [Array]::IndexOf($forwarded, $pair[0])
        Check "wrapper-forwards-$($pair[0])" ($index -ge 0 -and $forwarded[$index + 1] -ceq $pair[1])
    }
}
Test-PublicQualificationPlumbing
if ($QualificationOnly) {
    [ordered]@{schema=1;syntheticOnly=$true;actualRuntimeQualified=$false;controls=$controls;workflowSha256=(Hash $workflow)} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
    Write-Host "$($controls.Count) qualification plumbing controls completed; no actual runtime qualification."
    return
}
$archivePin = 'a' * 64; $receiptPin = 'b' * 64
Assert-SampleRuntimeInputs 'workflow_dispatch' '123' $archivePin $receiptPin
Check 'complete-explicit-pins' $true
foreach ($row in @(
    @('push', '123', $archivePin, $receiptPin),
    @('pull_request', '123', $archivePin, $receiptPin),
    @('workflow_dispatch', '', '', ''),
    @('workflow_dispatch', '', $archivePin, $receiptPin),
    @('workflow_dispatch', '123', '', $receiptPin),
    @('workflow_dispatch', '123', $archivePin, ''),
    @('workflow_dispatch', '0', $archivePin, $receiptPin),
    @('workflow_dispatch', '../123', $archivePin, $receiptPin),
    @('workflow_dispatch', '123', $archivePin.ToUpperInvariant(), $receiptPin),
    @('workflow_dispatch', '123', $archivePin, ('g' * 64)))) {
    Reject "reject-input-$($controls.Count)" { Assert-SampleRuntimeInputs @row } 'requires explicit'
}
$head = 'c' * 40
Assert-SampleRuntimeSource 'approved/private-mirror' '42' 'contract-only-not-a-real-token' $head
Check 'explicit-private-source-authority' $true
foreach ($row in @(
    @('', '42', 'contract-only-not-a-real-token', $head),
    @('approved/private-mirror/../../public', '42', 'contract-only-not-a-real-token', $head),
    @('approved/private-mirror', '', 'contract-only-not-a-real-token', $head),
    @('approved/private-mirror', '0', 'contract-only-not-a-real-token', $head),
    @('approved/private-mirror', '42', '', $head),
    @('approved/private-mirror', '42', '   ', $head),
    @('approved/private-mirror', '42', 'contract-only-not-a-real-token', $head.ToUpperInvariant()))) {
    Reject "reject-private-authority-$($controls.Count)" { Assert-SampleRuntimeSource @row } 'Explicit private evidence'
}
$privateJson = '{"id":42,"full_name":"approved/private-mirror","private":true,"visibility":"private","fork":false}'
Assert-PrivateSampleRuntimeRepository ($privateJson | ConvertFrom-Json) 'approved/private-mirror' '42'
Check 'private-repository-metadata-authority' $true
foreach ($mutation in @('public', 'private-string', 'wrong-name', 'wrong-id', 'internal', 'unknown-visibility', 'fork', 'fork-string', 'fork-missing')) {
    $repository = $privateJson | ConvertFrom-Json
    switch ($mutation) {
        'public' { $repository.private = $false; $repository.visibility = 'public' }
        'private-string' { $repository.private = 'true' }
        'wrong-name' { $repository.full_name = 'other/private-mirror' }
        'wrong-id' { $repository.id = 43 }
        'internal' { $repository.visibility = 'internal' }
        'unknown-visibility' { $repository.visibility = '' }
        'fork' { $repository.fork = $true }
        'fork-string' { $repository.fork = 'false' }
        'fork-missing' { $repository.PSObject.Properties.Remove('fork') }
    }
    Reject "reject-private-repository-$mutation" { Assert-PrivateSampleRuntimeRepository $repository 'approved/private-mirror' '42' } 'exact approved private'
}
function Test-K8sRepositoryMetadataBoundary([string]$PrivateJson) {
    $checker = Join-Path $RepositoryRoot 'tools/ci/check-k8s-acceptance.ps1'
    $readerTokens = $readerErrors = $null
    $checkerAst = [Management.Automation.Language.Parser]::ParseFile($checker, [ref]$readerTokens, [ref]$readerErrors)
    $readers = @($checkerAst.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Read-K8sJson'
    }, $true))
    $guardCalls = @($checkerAst.FindAll({ param($node)
        $node -is [Management.Automation.Language.CommandAst] -and
            $node.GetCommandName() -ceq 'Assert-PrivateSampleRuntimeRepository'
    }, $true))
    Check 'k8s-repository-reader-and-boundary-unique' (!$readerErrors.Count -and $readers.Count -eq 1 -and
        $guardCalls.Count -eq 1 -and $guardCalls[0].CommandElements.Count -eq 4)
    # Execute only the actual bounded reader and repository argument, never the network entry point.
    . ([scriptblock]::Create($readers[0].Extent.Text))
    $boundary = [scriptblock]::Create($guardCalls[0].CommandElements[1].Extent.Text)
    $repositoryFile = Join-Path $EvidenceDirectory 'k8s-repository-metadata.json'
    [IO.File]::WriteAllText($repositoryFile, $PrivateJson, [Text.UTF8Encoding]::new($false))
    Check 'k8s-repository-reader-preserves-dictionary' ((Read-K8sJson $repositoryFile) -is [Collections.IDictionary])
    Assert-PrivateSampleRuntimeRepository (& $boundary) 'approved/private-mirror' '42'
    Check 'k8s-repository-reader-boundary-accepts-private' $true
    foreach ($mutation in @('public', 'private-string', 'wrong-name', 'wrong-id', 'internal', 'unknown-visibility', 'fork', 'fork-string', 'fork-missing')) {
        $metadata = $PrivateJson | ConvertFrom-Json -AsHashtable
        switch ($mutation) {
            'public' { $metadata.private = $false; $metadata.visibility = 'public' }
            'private-string' { $metadata.private = 'true' }
            'wrong-name' { $metadata.full_name = 'other/private-mirror' }
            'wrong-id' { $metadata.id = 43 }
            'internal' { $metadata.visibility = 'internal' }
            'unknown-visibility' { $metadata.visibility = '' }
            'fork' { $metadata.fork = $true }
            'fork-string' { $metadata.fork = 'false' }
            'fork-missing' { $null = $metadata.Remove('fork') }
        }
        [IO.File]::WriteAllText($repositoryFile, ($metadata | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
        Reject "reject-k8s-repository-reader-boundary-$mutation" {
            Assert-PrivateSampleRuntimeRepository (& $boundary) 'approved/private-mirror' '42'
        } 'exact approved private'
    }
}
Test-K8sRepositoryMetadataBoundary $privateJson
$metadataJson = '{"id":123,"expired":false,"size_in_bytes":512,"digest":"sha256:' + $archivePin +
    '","workflow_run":{"repository_id":42,"head_repository_id":42,"head_sha":"' + $head + '"}}'
Assert-SampleRuntimeArtifact ($metadataJson | ConvertFrom-Json) '123' $archivePin '42' $head
Check 'exact-artifact-authority' $true
foreach ($mutation in @('id', 'expired', 'expired-string', 'empty', 'oversize', 'fractional-size', 'digest', 'repository', 'fork', 'source')) {
    $metadata = $metadataJson | ConvertFrom-Json
    switch ($mutation) {
        'id' { $metadata.id = 124 }
        'expired' { $metadata.expired = $true }
        'expired-string' { $metadata.expired = 'false' }
        'empty' { $metadata.size_in_bytes = 0 }
        'oversize' { $metadata.size_in_bytes = 512MB + 1 }
        'fractional-size' { $metadata.size_in_bytes = 1.5 }
        'digest' { $metadata.digest = 'sha256:' + ('d' * 64) }
        'repository' { $metadata.workflow_run.repository_id = 43 }
        'fork' { $metadata.workflow_run.head_repository_id = 43 }
        'source' { $metadata.workflow_run.head_sha = 'd' * 40 }
    }
    Reject "reject-metadata-$mutation" { Assert-SampleRuntimeArtifact $metadata '123' $archivePin '42' $head } 'binding differs'
}
$producerJson = '{"id":222,"repository":{"id":42},"head_repository":{"id":42},"head_sha":"' + $head +
    '","event":"workflow_dispatch","status":"completed","conclusion":"success"}'
Assert-SampleRuntimeProducer ($producerJson | ConvertFrom-Json) '222' '42' $head
Check 'completed-successful-own-producer-at-source' $true
foreach ($mutation in @('id', 'repository', 'head_repository', 'head_sha', 'event', 'status', 'conclusion')) {
    $producer = $producerJson | ConvertFrom-Json
    switch ($mutation) {
        'id' { $producer.id = 223 }
        'repository' { $producer.repository.id = 43 }
        'head_repository' { $producer.head_repository.id = 43 }
        'head_sha' { $producer.head_sha = 'd' * 40 }
        'event' { $producer.event = 'pull_request' }
        'status' { $producer.status = 'in_progress' }
        'conclusion' { $producer.conclusion = 'failure' }
    }
    Reject "reject-producer-$mutation" { Assert-SampleRuntimeProducer $producer '222' '42' $head } 'Completed successful own-repository'
}
foreach ($producerId in @('', '0', '../222', '222/zip')) {
    Reject "reject-producer-id-$($controls.Count)" {
        Assert-SampleRuntimeProducer ($producerJson | ConvertFrom-Json) $producerId '42' $head
    } 'Completed successful own-repository'
}
function New-Archive([string]$Name, [object[]]$Members) {
    $path = Join-Path $EvidenceDirectory "$Name.zip"
    $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($member in $Members) {
            $entry = $zip.CreateEntry($member.Name)
            if ($member.ContainsKey('Mode')) { $entry.ExternalAttributes = $member.Mode -shl 16 }
            if ($member.ContainsKey('Attributes')) { $entry.ExternalAttributes = $member.Attributes }
            $stream = $entry.Open()
            try {
                $bytes = [Text.Encoding]::UTF8.GetBytes([string]$member.Text)
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $path
}
# These are transport fixtures, deliberately not valid runtime receipts/SDKs/reports.
$receipt = @{ Name = 'receipt.json'; Text = '{"syntheticImportOnly":true}' }
$good = New-Archive 'bounded' @($receipt, @{Name='source/';Text='';Mode=0x4000},
    @{Name='source/pom.xml';Text='transport fixture only';Mode=0x8000})
$expected = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($receipt.Text))).ToLowerInvariant()
$destination = Join-Path $EvidenceDirectory 'bounded-output'
$imported = Expand-SampleRuntimeArchive $good $destination (Hash $good) $expected
Check 'bounded-extraction-with-pinned-root-receipt' ($imported -ceq (Join-Path $destination 'receipt.json') -and (Hash $imported) -ceq $expected)
Reject 'never-adopt-existing-destination' { Expand-SampleRuntimeArchive $good $destination (Hash $good) $expected } 'fresh destination differs'
Reject 'archive-pin-mismatch' { Expand-SampleRuntimeArchive $good (Join-Path $EvidenceDirectory 'wrong-zip-pin') ('e' * 64) $expected } 'Archive pin'
Reject 'receipt-pin-mismatch' { Expand-SampleRuntimeArchive $good (Join-Path $EvidenceDirectory 'wrong-receipt-pin') (Hash $good) ('e' * 64) } 'Receipt pin'
$unsafe = @('../escape', '/absolute', 'source/../../escape', 'source/./file', 'source//file', 'C:/outside', 'source\file', 'source/file:stream',
    ('nested/' * 24 + 'file'), ('x' * 1025))
foreach ($name in $unsafe) {
    $case = 'unsafe-' + $controls.Count
    $path = New-Archive $case @($receipt, @{Name=$name;Text='must not escape'})
    $target = Join-Path $EvidenceDirectory "$case-output"
    Reject $case { Expand-SampleRuntimeArchive $path $target (Hash $path) $expected } 'Unsafe archive member'
    Check "$case-no-partial-extraction" (!(Test-Path -LiteralPath $target))
}
foreach ($case in @('duplicate', 'case-alias', 'directory-case-alias', 'file-ancestor', 'symlink', 'reparse', 'mode-mismatch', 'missing-root-receipt', 'wrong-case-root-receipt')) {
    $members = @($receipt)
    switch ($case) {
        'duplicate' { $members += @{Name='receipt.json';Text='duplicate'} }
        'case-alias' { $members += @{Name='RECEIPT.JSON';Text='alias'} }
        'directory-case-alias' { $members += @{Name='source/One/a';Text='a'}, @{Name='source/one/b';Text='b'} }
        'file-ancestor' { $members += @{Name='source';Text='file'}, @{Name='source/pom.xml';Text='child'} }
        'symlink' { $members += @{Name='link';Text='../outside';Mode=0xA000} }
        'reparse' { $members += @{Name='link';Text='outside';Attributes=0x400} }
        'mode-mismatch' { $members += @{Name='directory/';Text='';Mode=0x8000} }
        'missing-root-receipt' { $members = @(@{Name='wrapped/receipt.json';Text=$receipt.Text}) }
        'wrong-case-root-receipt' { $members = @(@{Name='RECEIPT.JSON';Text=$receipt.Text}) }
    }
    $path = New-Archive $case $members
    $target = Join-Path $EvidenceDirectory "$case-output"
    Reject $case { Expand-SampleRuntimeArchive $path $target (Hash $path) $expected } 'Unsafe archive member|case alias|collision|root receipt'
    Check "$case-no-partial-extraction" (!(Test-Path -LiteralPath $target))
}
$members = @($receipt)
for ($i=0; $i -lt 834; $i++) { $members += @{Name=("d-$i/" + ('x/' * 22) + 'file');Text='x'} }
$path = New-Archive 'implicit-directory-budget' $members
$target = Join-Path $EvidenceDirectory 'implicit-directory-output'
Reject 'implicit-directories-count-toward-entry-limit' { Expand-SampleRuntimeArchive $path $target (Hash $path) $expected } 'Expanded entry budget'
Check 'implicit-directory-budget-no-partial-extraction' (!(Test-Path -LiteralPath $target))
# Corrupt only declared ZIP lengths: exercise limits without allocating large evidence.
foreach ($case in @('member-limit', 'expanded-limit')) {
    $members = @($receipt)
    if ($case -ceq 'expanded-limit') { for ($i=0; $i -lt 33; $i++) { $members += @{Name="file-$i";Text='x'} } }
    $path = New-Archive $case $members
    $bytes = [IO.File]::ReadAllBytes($path)
    [uint32]$length = if ($case -ceq 'member-limit') { 128MB + 1 } else { 128MB }
    $patched = 0
    for ($i=0; $i -le $bytes.Length - 28; $i++) {
        if ([BitConverter]::ToUInt32($bytes, $i) -eq 0x02014b50) {
            [BitConverter]::GetBytes($length).CopyTo($bytes, $i + 24); $patched++
        }
    }
    Check "$case-fixture-has-central-entries" ($patched -eq $members.Count)
    [IO.File]::WriteAllBytes($path, $bytes)
    $target = Join-Path $EvidenceDirectory "$case-output"
    Reject $case { Expand-SampleRuntimeArchive $path $target (Hash $path) $expected } 'Unsafe archive member|Expanded evidence budget'
    Check "$case-no-partial-extraction" (!(Test-Path -LiteralPath $target))
}
Check 'no-inputs-in-shell-source' (!$script.Contains('${{'))
Check 'no-skipped-or-ignored-import' ($blocks[0].Value -notmatch '(?m)^        (if|continue-on-error):')
Check 'bounded-transfer-with-no-auth-forwarding-switch' ($script -match 'FromSeconds\(90\)' -and $script -match 'MaxAutomaticRedirections = 3' -and
    $script -notmatch 'PreserveAuthorization|location-trusted')
Check 'actual-checkout-gate-receives-both-pins' ($source -match
    '(?m)^          \./tools/ci/check-v019-sample-coverage\.ps1 @arguments\s*$' -and
    $source.Contains('RuntimeEvidencePath = $env:SAMPLE_RECEIPT_PATH; RuntimeEvidenceSha256 = $env:SAMPLE_RECEIPT_SHA256'))
Check 'private-evidence-outside-checkout' ($script.Contains("Join-Path `$env:RUNNER_TEMP 'v019-sample-runtime'") -and
    $source -notmatch '(?m)^            .*v019-sample-runtime/\*')
Check 'private-read-token-with-no-public-token-fallback' ($blocks[0].Value.Contains('GH_TOKEN: ${{ secrets.CACHEDB_EVIDENCE_READ_TOKEN }}') -and
    !$blocks[0].Value.Contains('${{ github.token }}'))
Check 'allowlisted-private-source-config' ($blocks[0].Value.Contains('EVIDENCE_REPOSITORY: ${{ vars.CACHEDB_EVIDENCE_REPOSITORY }}') -and
    $blocks[0].Value.Contains('EVIDENCE_REPOSITORY_ID: ${{ vars.CACHEDB_EVIDENCE_REPOSITORY_ID }}'))
Check 'private-repository-verified-before-artifact-request' ($script.IndexOf('Assert-PrivateSampleRuntimeRepository $repository ') -gt 0 -and
    $script.IndexOf('Assert-PrivateSampleRuntimeRepository $repository ') -lt $script.IndexOf('$metadata = Invoke-RestMethod'))
Check 'artifact-bound-to-private-mirror-and-exact-checkout' ($script.Contains(
    'Assert-SampleRuntimeArtifact $metadata $env:SAMPLE_ARTIFACT_ID $env:SAMPLE_ARTIFACT_SHA256 $env:EVIDENCE_REPOSITORY_ID $env:GITHUB_SHA'))
$producerWiring = [ordered]@{
    id = '$producerId = [string]$metadata.workflow_run.id'
    guard = 'if ($producerId -cnotmatch ''^[1-9][0-9]{0,18}$'') { throw ''Exact numeric artifact producer required.'' }'
    lookup = '$producer = Invoke-RestMethod -Uri "$repositoryUri/actions/runs/$producerId" -Headers $headers -TimeoutSec 30 -MaximumRedirection 0'
    validate = 'Assert-SampleRuntimeProducer $producer $producerId $env:EVIDENCE_REPOSITORY_ID $env:GITHUB_SHA'
    receive = 'Receive-SampleRuntimeArchive "$uri/zip" $env:GH_TOKEN $archive'
}
function Assert-SampleRuntimeProducerWiring([string]$Text) {
    $previous = -1
    foreach ($line in $producerWiring.Values) {
        $matches = [regex]::Matches($Text, ('(?m)^ *' + [regex]::Escape($line) + ' *\r?$'))
        if ($matches.Count -ne 1 -or $matches[0].Index -le $previous) {
            throw 'Exact bounded producer lookup and validation must precede archive download.'
        }
        $previous = $matches[0].Index
    }
}
Assert-SampleRuntimeProducerWiring $script
Check 'producer-authenticated-before-archive-download' $true
foreach ($mutation in @('missing-id', 'missing-guard', 'guard-after-lookup', 'foreign-uri', 'unbounded-timeout',
    'redirects', 'validation-before-lookup', 'validation-after-download', 'missing-validation', 'duplicate-lookup')) {
    $badWiring = $script
    switch ($mutation) {
        'missing-id' { $badWiring = $badWiring.Replace($producerWiring.id, '') }
        'missing-guard' { $badWiring = $badWiring.Replace($producerWiring.guard, '') }
        'guard-after-lookup' {
            $badWiring = $badWiring.Replace($producerWiring.guard, '').Replace($producerWiring.lookup,
                ($producerWiring.lookup + "`n" + $producerWiring.guard))
        }
        'foreign-uri' { $badWiring = $badWiring.Replace('"$repositoryUri/actions/runs/$producerId"', '"https://elsewhere.invalid/$producerId"') }
        'unbounded-timeout' { $badWiring = $badWiring.Replace($producerWiring.lookup, $producerWiring.lookup.Replace(' -TimeoutSec 30', '')) }
        'redirects' { $badWiring = $badWiring.Replace($producerWiring.lookup, $producerWiring.lookup.Replace('-MaximumRedirection 0', '-MaximumRedirection 1')) }
        'validation-before-lookup' {
            $badWiring = $badWiring.Replace($producerWiring.validate, '').Replace($producerWiring.lookup,
                ($producerWiring.validate + "`n" + $producerWiring.lookup))
        }
        'validation-after-download' {
            $badWiring = $badWiring.Replace($producerWiring.validate, '').Replace($producerWiring.receive,
                ($producerWiring.receive + "`n" + $producerWiring.validate))
        }
        'missing-validation' { $badWiring = $badWiring.Replace($producerWiring.validate, '') }
        'duplicate-lookup' { $badWiring = $badWiring.Replace($producerWiring.lookup, ($producerWiring.lookup + "`n" + $producerWiring.lookup)) }
    }
    Reject "reject-producer-wiring-$mutation" { Assert-SampleRuntimeProducerWiring $badWiring } 'Exact bounded producer'
}
Check 'never-download-current-public-repository-artifact' (!$script.Contains('$env:GITHUB_REPOSITORY'))
Check 'does-not-execute-artifact-code' ($script -notmatch 'Invoke-Expression|Start-Process|&\s*\$receipt|node\s|mvn\s|java\s')
& (Join-Path $RepositoryRoot 'tools/ci/test-v019-private-owned-runtime.ps1') -RepositoryRoot $RepositoryRoot `
    -EvidenceDirectory (Join-Path $EvidenceDirectory 'private-owned-import')
Check 'private-owned-import-controls' $true
Test-PrivateProducer
Test-CanonicalReleaseGate
[ordered]@{schema=1;syntheticOnly=$true;actualRuntimeQualified=$false;controls=$controls;
    workflowSha256=(Hash $workflow)} | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Host "$($controls.Count) tooling-only import checks completed; no actual sample acceptance or release qualification."
