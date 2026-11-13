#requires -Version 7.3
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (![OperatingSystem]::IsLinux()) { throw 'Linux tooling workspace required.' }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Fresh synthetic evidence directory required.' }
$null = [IO.Directory]::CreateDirectory($EvidenceDirectory)
$controls = [Collections.Generic.List[string]]::new()
function Check([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "Contract failed: $Name" }; $controls.Add($Name)
}
function Reject([string]$Name, [scriptblock]$Action) {
    try { $null = & $Action } catch { $controls.Add($Name); return }
    throw "Invalid synthetic authority accepted: $Name"
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() }
$wrapper = Join-Path $RepositoryRoot 'tools/ci/import-v019-private-owned-runtime.ps1'
$template = Join-Path $RepositoryRoot 'tools/ci/private-v019-runtime.workflow.yml.template'
$public = Join-Path $RepositoryRoot '.github/workflows/framework-readiness.yml'
$source = [IO.File]::ReadAllText($wrapper); $yaml = [IO.File]::ReadAllText($template)
$tokens = $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
Check 'wrapper-parses' (!$errors.Count)
# Load definitions only, never the network/import entry point or artifact code.
foreach ($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
    . ([scriptblock]::Create($function.Extent.Text))
}
$definitions = @(Read-PrivateOwnedImportFunctions $public)
Check 'exact-eight-canonical-functions' ($definitions.Count -eq 8)
foreach ($definition in $definitions) { . ([scriptblock]::Create($definition)) }
$publicText = [IO.File]::ReadAllText($public)
foreach ($definition in $definitions) { Check ('original-definition-' + $controls.Count) ($publicText.Contains(($definition -split "`n" | ForEach-Object { $_.TrimEnd("`r") })[0])) }
$uploader = Join-Path $RepositoryRoot 'tools/ci/stage-v019-private-evidence.ps1'
$uploadSource = [IO.File]::ReadAllText($uploader)
$uploadAst = [Management.Automation.Language.Parser]::ParseInput($uploadSource, [ref]$tokens, [ref]$errors)
Check 'uploader-parses' (!$errors.Count)
foreach ($function in $uploadAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
    . ([scriptblock]::Create($function.Extent.Text))
}

$context = @{
    actions='true'; event='workflow_dispatch'; api='https://api.github.com'; server='https://github.com'
    repository='synthetic/private'; repositoryId='42'; approvedRepository='synthetic/private'; approvedRepositoryId='42'; runId='111'
    publicRepository='synthetic/public'; publicRepositoryId='24'; sourceSha=('a' * 40); dispatchSha=('b' * 40); workflowSha=('b' * 40)
    workflowRef='synthetic/private/.github/workflows/runtime.yml@refs/heads/main'; token='SYNTHETIC-NOT-A-CREDENTIAL'
}
Check 'distinct-approved-S-and-workflow-D' ((Assert-PrivateOwnedContext $context) -ceq '.github/workflows/runtime.yml')
$same = $context.Clone(); $same.dispatchSha=$same.sourceSha; $same.workflowSha=$same.sourceSha
Check 'same-S-D-authority-allowed' ((Assert-PrivateOwnedContext $same) -ceq '.github/workflows/runtime.yml')
foreach ($row in @(
    @('actions','false'), @('event','push'), @('api','https://elsewhere.invalid'), @('server','http://github.com'),
    @('repository','synthetic/other'), @('repositoryId','43'), @('approvedRepository','synthetic/other'), @('approvedRepositoryId','43'),
    @('publicRepository','synthetic/private'), @('publicRepositoryId','42'), @('publicRepository','../public'),
    @('publicRepository','synthetic/.'), @('publicRepository','synthetic/..'), @('repository','../private'),
    @('runId','0'), @('sourceSha',('A' * 40)), @('sourceSha',('a' * 39)), @('dispatchSha',('B' * 40)),
    @('workflowSha',('c' * 40)), @('workflowRef','synthetic/other/.github/workflows/runtime.yml@refs/heads/main'),
    @('workflowRef','synthetic/private/../runtime.yml@refs/heads/main'), @('token',''))) {
    $bad=$context.Clone(); $bad[$row[0]]=$row[1]
    Reject ('context-' + $row[0] + '-' + $controls.Count) { Assert-PrivateOwnedContext $bad }
}
$privateJson='{"id":42,"full_name":"synthetic/private","private":true,"visibility":"private","fork":false}'
Assert-PrivateOwnedRepository ($privateJson | ConvertFrom-Json) 'synthetic/private' '42'
Check 'own-private-nonfork-api-identity' $true
foreach ($mutation in @('fork', 'fork-string', 'fork-missing', 'public', 'id', 'name')) {
    $metadata=$privateJson | ConvertFrom-Json
    switch ($mutation) {
        'fork' {$metadata.fork=$true}; 'fork-string' {$metadata.fork='false'}; 'fork-missing' {$metadata.PSObject.Properties.Remove('fork')}
        'public' {$metadata.private=$false; $metadata.visibility='public'}; 'id' {$metadata.id=43}; 'name' {$metadata.full_name='synthetic/other'}
    }
    Reject ('own-repository-' + $mutation) { Assert-PrivateOwnedRepository $metadata 'synthetic/private' '42' }
}
$publicJson='{"id":24,"full_name":"synthetic/public","private":false,"visibility":"public","fork":false}'
Assert-PrivateOwnedPublicSource ($publicJson | ConvertFrom-Json) 'synthetic/public' '24'
Check 'independent-public-source-api-identity' $true
foreach ($mutation in @('id','name','private','fork','fork-string','fork-missing','visibility')) {
    $metadata=$publicJson | ConvertFrom-Json
    switch ($mutation) {
        'id' {$metadata.id=25}; 'name' {$metadata.full_name='synthetic/other'}; 'private' {$metadata.private=$true}
        'fork' {$metadata.fork=$true}; 'fork-string' {$metadata.fork='false'}; 'fork-missing' {$metadata.PSObject.Properties.Remove('fork')}
        'visibility' {$metadata.visibility='private'}
    }
    Reject ('public-source-' + $mutation) { Assert-PrivateOwnedPublicSource $metadata 'synthetic/public' '24' }
}
$pin='c' * 64
$qualification = @{ docPath='qualification/docs.json'; docPin=$pin; toolPath='qualification/tooling.json'; toolPin=('d' * 64) }
Assert-SampleRuntimeQualificationInputs '' '' '' ''
Check 'optional-qualification-all-absent' $true
Assert-SampleRuntimeQualificationInputs $qualification.docPath $qualification.docPin $qualification.toolPath $qualification.toolPin
Check 'qualification-both-explicit-pairs' $true
for ($mask=1; $mask -lt 15; $mask++) {
    $values=@('', '', '', '')
    $all=@($qualification.docPath, $qualification.docPin, $qualification.toolPath, $qualification.toolPin)
    for ($i=0; $i -lt 4; $i++) { if ($mask -band (1 -shl $i)) { $values[$i]=$all[$i] } }
    Reject ('partial-qualification-' + $mask) { Assert-SampleRuntimeQualificationInputs @values }
}
foreach ($field in @('docPath','toolPath')) {
    foreach ($path in @('../escape.json', '/absolute.json', 'a\b.json', 'a//b.json', './a.json', 'a/../b.json', 'a/ b.json', 'a/b.', 'C:/a.json')) {
        $bad=$qualification.Clone(); $bad[$field]=$path
        Reject ('unsafe-' + $field + '-' + $controls.Count) { Assert-SampleRuntimeQualificationInputs $bad.docPath $bad.docPin $bad.toolPath $bad.toolPin }
    }
}
foreach ($field in @('docPin','toolPin')) {
    foreach ($value in @(('A' * 64), ('a' * 63), 'sha256:abcd', ' ')) {
        $bad=$qualification.Clone(); $bad[$field]=$value
        Reject ('invalid-' + $field + '-' + $controls.Count) { Assert-SampleRuntimeQualificationInputs $bad.docPath $bad.docPin $bad.toolPath $bad.toolPin }
    }
}
$uploadContext=$same.Clone(); $uploadContext.workflowRef='synthetic/private/.github/workflows/framework-readiness.yml@refs/heads/final'
$uploadInputs=@{
    mode='private-upload'; repositoryPrivate='true'; artifactId=''; artifactPin=''
    assetId='123'; assetPin=$pin; receiptPin=('e' * 64)
    docPath=$qualification.docPath; docPin=$qualification.docPin; toolPath=$qualification.toolPath; toolPin=$qualification.toolPin
}
Assert-PrivateEvidenceUploadInputs $uploadContext $uploadInputs
Check 'uploader-exact-private-workflow-source-S' $true
Reject 'uploader-does-not-relabel-D-as-S' { Assert-PrivateEvidenceUploadInputs $context $uploadInputs }
foreach ($row in @(@('mode','public-import'), @('repositoryPrivate','false'), @('artifactId','456'), @('artifactPin',$pin),
    @('assetId','0'), @('assetId','../123'), @('assetPin',('A' * 64)), @('receiptPin',''),
    @('docPath',''), @('docPin',''), @('toolPath',''), @('toolPin',''))) {
    $bad=$uploadInputs.Clone(); $bad[$row[0]]=$row[1]
    Reject ('uploader-input-' + $row[0] + '-' + $controls.Count) { Assert-PrivateEvidenceUploadInputs $uploadContext $bad }
}
$emptyQualification=$uploadInputs.Clone()
foreach ($name in @('docPath','docPin','toolPath','toolPin')) { $emptyQualification[$name]='' }
Reject 'uploader-no-unqualified-retained-evidence' { Assert-PrivateEvidenceUploadInputs $uploadContext $emptyQualification }
foreach ($row in @(@('event','push'), @('repository','synthetic/public'), @('repositoryId','24'), @('workflowSha',('f' * 40)),
    @('workflowRef','synthetic/private/.github/workflows/other.yml@refs/heads/final'))) {
    $bad=$uploadContext.Clone(); $bad[$row[0]]=$row[1]
    Reject ('uploader-context-' + $row[0]) { Assert-PrivateEvidenceUploadInputs $bad $uploadInputs }
}
$assetJson='{"id":123,"url":"https://api.github.com/repos/synthetic/private/releases/assets/123","state":"uploaded","size":512,"digest":"sha256:' + $pin + '"}'
Assert-PrivateEvidenceReleaseAsset ($assetJson | ConvertFrom-Json) 'synthetic/private' '123' $pin
Check 'asset-exact-same-repository-api-pin' $true
foreach ($row in @(@('id',124), @('url','https://api.github.com/repos/synthetic/other/releases/assets/123'),
    @('url','https://elsewhere.invalid/123'), @('state','open'), @('size',0), @('size',(512MB + 1)),
    @('size','512'), @('digest',('sha256:' + ('f' * 64))))) {
    $bad=$assetJson | ConvertFrom-Json; $bad.($row[0])=$row[1]
    Reject ('asset-' + $row[0] + '-' + $controls.Count) { Assert-PrivateEvidenceReleaseAsset $bad 'synthetic/private' '123' $pin }
}
$checkerStart=New-PrivateOwnedCheckerStart $RepositoryRoot '/private/receipt.json' $pin '/private/results' $qualification
Check 'checker-forwards-documentation-pair' ($checkerStart.ArgumentList.Contains('--source-equivalence') -and $checkerStart.ArgumentList.Contains($qualification.docPath) -and $checkerStart.ArgumentList.Contains($qualification.docPin))
Check 'checker-forwards-tooling-pair' ($checkerStart.ArgumentList.Contains('--execution-tooling-overlay') -and $checkerStart.ArgumentList.Contains($qualification.toolPath) -and $checkerStart.ArgumentList.Contains($qualification.toolPin))
Check 'checker-keeps-private-qualification-chain' ($checkerStart.ArgumentList.Contains('--private-results') -and $checkerStart.ArgumentList.Contains('/private/results/qualification.json'))
Check 'checker-summary-must-be-under-target' ($checkerStart.ArgumentList.Contains((Join-Path $RepositoryRoot 'target/v019-private-runtime-coverage.md')))
Check 'checker-never-inherits-tokens' (!$checkerStart.Environment.ContainsKey('GITHUB_TOKEN') -and !$checkerStart.Environment.ContainsKey('GH_TOKEN'))
$partial=$qualification.Clone(); $partial.docPin=''
Reject 'checker-rejects-partial-qualification-before-launch' { New-PrivateOwnedCheckerStart $RepositoryRoot '/private/receipt.json' $pin '/private/results' $partial }
$checkerEmpty=New-PrivateOwnedCheckerStart $RepositoryRoot '/private/receipt.json' $pin '/private/results'
Check 'checker-optional-qualification-remains-explicit' (!$checkerEmpty.ArgumentList.Contains('--source-equivalence'))
$resultJson='{"complete":true,"runtimeQualified":true,"releaseAccepted":false,"evidence":"STATIC_AND_PINNED_FINAL_SAMPLE_RUNTIME","runtime":{"receiptSha256":"' + $pin + '","sourceQualificationChain":{"kind":"cachedb-v019-source-qualification-chain","stages":[{"manifest":{"path":"qualification/docs.json","sha256":"' + $pin + '"}},{"manifest":{"path":"qualification/tooling.json","sha256":"' + ('d' * 64) + '"}}]}}}'
Assert-PrivateOwnedCheckerResult ($resultJson | ConvertFrom-Json) $pin $qualification
Check 'private-result-retains-both-approved-references' $true
foreach ($field in @('complete','runtimeQualified','releaseAccepted','evidence','receipt','docPath','docPin','toolPath','toolPin','missing-chain','partial-chain')) {
    $bad=$resultJson | ConvertFrom-Json
    switch ($field) {
        'complete' {$bad.complete=$false}; 'runtimeQualified' {$bad.runtimeQualified=$false}; 'releaseAccepted' {$bad.releaseAccepted=$true}
        'evidence' {$bad.evidence='STATIC_ONLY_NOT_RUNTIME_ACCEPTANCE'}; 'receipt' {$bad.runtime.receiptSha256='f' * 64}
        'docPath' {$bad.runtime.sourceQualificationChain.stages[0].manifest.path='other.json'}
        'docPin' {$bad.runtime.sourceQualificationChain.stages[0].manifest.sha256='f' * 64}
        'toolPath' {$bad.runtime.sourceQualificationChain.stages[1].manifest.path='other.json'}
        'toolPin' {$bad.runtime.sourceQualificationChain.stages[1].manifest.sha256='f' * 64}
        'missing-chain' {$bad.runtime.PSObject.Properties.Remove('sourceQualificationChain')}
        'partial-chain' {$bad.runtime.sourceQualificationChain.stages=@($bad.runtime.sourceQualificationChain.stages[0])}
    }
    Reject ('private-result-' + $field) { Assert-PrivateOwnedCheckerResult $bad $pin $qualification }
}
$artifactJson='{"id":123,"expired":false,"size_in_bytes":512,"digest":"sha256:' + $pin +
    '","workflow_run":{"id":222,"repository_id":42,"head_repository_id":42,"head_sha":"' + $context.sourceSha + '"}}'
$artifact=$artifactJson | ConvertFrom-Json
Assert-SampleRuntimeArtifact $artifact '123' $pin '42' $context.sourceSha
Check 'artifact-producer-S-not-workflow-D' $true
$artifact.workflow_run.head_sha=$context.dispatchSha
Reject 'artifact-at-D-cannot-be-relabeled-S' { Assert-SampleRuntimeArtifact $artifact '123' $pin '42' $context.sourceSha }
foreach ($field in @('repository_id','head_repository_id')) {
    $artifact=$artifactJson | ConvertFrom-Json; $artifact.workflow_run.$field=43
    Reject ('artifact-own-' + $field) { Assert-SampleRuntimeArtifact $artifact '123' $pin '42' $context.sourceSha }
}
$runJson='{"id":222,"repository":{"id":42},"head_repository":{"id":42},"head_sha":"' + $context.sourceSha +
    '","event":"workflow_dispatch","status":"completed","conclusion":"success"}'
Assert-PrivateOwnedProducer ($runJson | ConvertFrom-Json) '222' '42' $context.sourceSha
Check 'completed-own-producer-at-S' $true
foreach ($mutation in @('id','repository','head_repository','head_sha','event','status','conclusion')) {
    $run=$runJson | ConvertFrom-Json
    switch ($mutation) {
        'id' {$run.id=223}; 'repository' {$run.repository.id=43}; 'head_repository' {$run.head_repository.id=43}
        'head_sha' {$run.head_sha=$context.dispatchSha}; 'event' {$run.event='pull_request'}
        'status' {$run.status='in_progress'}; 'conclusion' {$run.conclusion='failure'}
    }
    Reject ('producer-' + $mutation) { Assert-PrivateOwnedProducer $run '222' '42' $context.sourceSha }
}
function New-SyntheticProcess([string]$Command) {
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $PSHOME 'pwsh'))
    foreach ($argument in @('-NoLogo', '-NoProfile', '-NonInteractive', '-Command', $Command)) { $start.ArgumentList.Add($argument) }
    return $start
}
Check 'synthetic-process-uses-current-interpreter' ((New-SyntheticProcess '').FileName -ceq (Join-Path $PSHOME 'pwsh'))
Check 'bounded-git-drains-both-streams' ((Invoke-PrivateOwnedBoundedProcess (
    New-SyntheticProcess "[Console]::Write('ok'); [Console]::Error.Write('diagnostic')"
)) -ceq 'ok')
foreach ($row in @(
    @('bounded-git-stdout-limit', '[Console]::OpenStandardOutput().Write([byte[]]::new(2048), 0, 2048)', 15000, 'Checkout inspection output exceeded byte limit.'),
    @('bounded-git-stderr-limit', '[Console]::OpenStandardError().Write([byte[]]::new(2048), 0, 2048)', 15000, 'Checkout inspection output exceeded byte limit.'),
    @('bounded-git-timeout', '[Threading.Thread]::Sleep(5000)', 500, 'Checkout inspection timed out.')
)) {
    $failure = $null
    try {
        $null = Invoke-PrivateOwnedBoundedProcess (New-SyntheticProcess $row[1]) -MaximumOutputBytes 1024 -TimeoutMilliseconds $row[2]
    } catch { $failure = $_.Exception.Message }
    Check $row[0] ($failure -ceq $row[3])
}
# Real local Git repositories in this Linux tooling pod, not product builds.
$gitRoot=Join-Path $EvidenceDirectory 'git'; $null=[IO.Directory]::CreateDirectory($gitRoot)
$null=Invoke-PrivateOwnedGit $gitRoot @('init','--quiet')
$null=Invoke-PrivateOwnedGit $gitRoot @('config','user.name','Synthetic tooling')
$null=Invoke-PrivateOwnedGit $gitRoot @('config','user.email','synthetic@example.invalid')
$null=[IO.Directory]::CreateDirectory((Join-Path $gitRoot '.github/workflows'))
[IO.File]::WriteAllText((Join-Path $gitRoot '.github/workflows/runtime.yml'), 'synthetic workflow only')
$null=Invoke-PrivateOwnedGit $gitRoot @('add','.')
$null=Invoke-PrivateOwnedGit $gitRoot @('commit','--quiet','-m','synthetic tooling fixture')
$head=Invoke-PrivateOwnedGit $gitRoot @('rev-parse','HEAD')
Assert-PrivateOwnedCheckout $gitRoot $head '.github/workflows/runtime.yml'
Check 'actual-head-and-candidate-workflow-presence' $true
$realGit = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$firstBin = Join-Path $EvidenceDirectory 'path one'; $secondBin = Join-Path $EvidenceDirectory 'path two'
$null = [IO.Directory]::CreateDirectory($firstBin); $null = [IO.Directory]::CreateDirectory($secondBin)
$firstGit = Join-Path $firstBin 'git'; $secondGit = Join-Path $secondBin 'git'
$null = [IO.File]::CreateSymbolicLink($firstGit, $realGit)
$null = [IO.File]::CreateSymbolicLink($secondGit, '/bin/false')
$originalPath = $env:PATH; $separator = [IO.Path]::PathSeparator
try {
    $env:PATH = "$firstBin$separator$secondBin$separator$originalPath"
    $applications = @(Get-Command git -CommandType Application -All -ErrorAction Stop)
    Check 'duplicate-path-executable-roster' ($applications.Count -ge 2 -and
        @($applications.Source | Sort-Object -Unique).Count -ge 2)
    $selected = Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1
    Check 'duplicate-path-first-git-application' ($selected.Source -ceq $firstGit)
    Check 'duplicate-path-actual-git-head' ((Invoke-PrivateOwnedGit $gitRoot @('rev-parse','HEAD')) -ceq $head)
    Assert-PrivateOwnedCheckout $gitRoot $head '.github/workflows/runtime.yml'
    Check 'duplicate-path-actual-checkout-proof' $true
    $env:PATH = "$secondBin$separator$firstBin$separator$originalPath"
    $failure = $null
    try { $null = Invoke-PrivateOwnedGit $gitRoot @('rev-parse','HEAD') } catch { $failure = $_.Exception.Message }
    Check 'duplicate-path-first-failure-no-fallback' ($failure -ceq 'Checkout inspection failed.')
} finally { $env:PATH = $originalPath }
Check 'duplicate-path-path-restored' ($env:PATH -ceq $originalPath)
Reject 'actual-head-not-context-head' { Assert-PrivateOwnedCheckout $gitRoot ('f' * 40) }
Reject 'same-S-D-without-installed-workflow' { Assert-PrivateOwnedCheckout $gitRoot $head '.github/workflows/absent.yml' }
[IO.File]::AppendAllText((Join-Path $gitRoot '.github/workflows/runtime.yml'), 'modified')
Reject 'tracked-source-modification' { Assert-PrivateOwnedCheckout $gitRoot $head }
$temp=Join-Path $EvidenceDirectory 'temp'; $null=[IO.Directory]::CreateDirectory($temp)
Check 'private-output-outside-checkouts' ((Assert-PrivateOwnedOutput $temp '/unrelated/source' '/unrelated/control') -ceq (Join-Path $temp 'v019-private-owned-runtime'))
Reject 'private-output-inside-checkout' { Assert-PrivateOwnedOutput $temp $EvidenceDirectory '/unrelated/control' }
$null=[IO.Directory]::CreateDirectory((Join-Path $temp 'v019-private-owned-runtime'))
Reject 'private-output-never-adopted' { Assert-PrivateOwnedOutput $temp '/unrelated/source' '/unrelated/control' }

Check 'manual-only-template' ($yaml -match '(?m)^  workflow_dispatch:' -and $yaml -notmatch '(?m)^  (push|pull_request|pull_request_target|workflow_run|schedule):')
Check 'own-ephemeral-token-only' ($yaml.Contains('GITHUB_TOKEN: ${{ github.token }}') -and $yaml -notmatch 'secrets\.|GH_TOKEN:|write-all|id-token:')
Check 'minimal-token-permissions' ($yaml -match '(?m)^  contents: read$' -and $yaml -match '(?m)^  actions: read$' -and $yaml -notmatch '(?m)^\s+\w+: write$')
Check 'no-raw-report-publication' ($yaml -notmatch '(?m)^\s+uses: actions/upload-artifact' -and $yaml -notmatch 'GITHUB_STEP_SUMMARY|GITHUB_OUTPUT')
Check 'source-from-trusted-vars-not-artifact' ($yaml.Contains('ref: ${{ vars.CACHEDB_APPROVED_SOURCE_SHA }}') -and $source.Contains('publicRepositoryId = $env:PUBLIC_SOURCE_REPOSITORY_ID'))
Check 'never-overwrites-github-sha' ($source -notmatch '(?im)\$env:GITHUB_SHA\s*=' -and $yaml -notmatch '(?m)^\s+GITHUB_SHA:')
Check 'no-token-argument-or-fallback' ($source -notmatch '\$env:GH_TOKEN|secrets\.|CACHEDB_EVIDENCE_READ_TOKEN' -and $source.Contains('token = $env:GITHUB_TOKEN'))
Check 'checker-is-from-approved-checkout' ($source.Contains("Join-Path `$Root 'tools/ci/check-v019-sample-coverage.mjs'") -and $source.Contains('Invoke-PrivateOwnedChecker $SourceRoot'))
Check 'checker-token-scrubbed' ($source.Contains("`$start.Environment.Remove('GITHUB_TOKEN')") -and $source.Contains("`$start.Environment.Remove('GH_TOKEN')"))
Check 'separate-S-D-provenance' ($source.Contains('sourceCommitS = $context.sourceSha; workflowCommitD = $context.dispatchSha'))
Check 'no-artifact-execution' ($source -notmatch 'Invoke-Expression|Import-Module|&\s*\$receipt|WorkingDirectory\s*=\s*\$Output')
Check 'bounded-original-transfer-and-checker' ($source.Contains('120000') -and
    @($definitions | Where-Object { $_.StartsWith('function Receive-SampleRuntimeArchive') -and $_.Contains('FromSeconds(90)') -and $_.Contains('512MB') }).Count -eq 1)
Check 'private-output-mode-700' ($source.Contains('[IO.UnixFileMode]448'))
foreach ($inputName in @('sourceEquivalencePath','sourceEquivalenceSha256','toolingOverlayPath','toolingOverlaySha256')) {
    Check ('template-forwards-' + $inputName) ($yaml.Contains(('${{ inputs.' + $inputName + ' }}')))
}

$uploadBlock=[regex]::Match($publicText, '(?ms)^  private-evidence-upload:\r?\n(?<job>.*?)(?=^  [a-zA-Z0-9_-]+:\r?$)').Groups['job'].Value
Check 'private-upload-job-present' (![string]::IsNullOrWhiteSpace($uploadBlock))
Check 'private-upload-only-explicit-dispatch' ($uploadBlock.Contains("if: github.event_name == 'workflow_dispatch' && inputs.evidenceMode == 'private-upload'"))
Check 'private-upload-read-only-own-token' ($uploadBlock.Contains('contents: read') -and $uploadBlock.Contains('GITHUB_TOKEN: ${{ github.token }}') -and $uploadBlock -notmatch 'secrets\.|actions: write|contents: write|id-token:|write-all')
Check 'private-upload-same-git-object-S' ($uploadBlock.Contains('$env:PRIVATE_WORKFLOW_SHA -cne $env:GITHUB_SHA') -and $uploadBlock.Contains('$env:GITHUB_SHA -cne $env:APPROVED_SOURCE_SHA') -and $uploadBlock.Contains('ref: ${{ github.sha }}'))
Check 'private-upload-admission-before-checkout' ($uploadBlock.Contains('Admit exact private manual source before checkout') -and $uploadBlock.IndexOf('Admit exact private manual source before checkout') -lt $uploadBlock.IndexOf('uses: actions/checkout@'))
Check 'private-upload-no-workloads' ($uploadBlock -notmatch 'mvn|kubectl|run-v019|test-v019|setup-java|build-release|docker')
Check 'private-upload-no-failed-evidence-publication' ($uploadBlock -notmatch 'always\(\)|continue-on-error|GITHUB_STEP_SUMMARY' -and
    $uploadBlock.IndexOf('./tools/ci/stage-v019-private-evidence.ps1') -ge 0 -and
    $uploadBlock.IndexOf('./tools/ci/stage-k8s-acceptance.ps1') -ge 0 -and
    $uploadBlock.IndexOf('./tools/ci/stage-v019-private-evidence.ps1') -lt $uploadBlock.IndexOf('uses: actions/upload-artifact@') -and
    $uploadBlock.IndexOf('./tools/ci/stage-k8s-acceptance.ps1') -lt $uploadBlock.IndexOf('uses: actions/upload-artifact@'))
Check 'private-upload-preserves-hidden-members' ($uploadBlock.Contains('include-hidden-files: true'))
$jobs = [ordered]@{}
$jobSection = [regex]::Match($publicText, '(?ms)^jobs:\r?\n(?<jobs>.*)\z').Groups['jobs'].Value
$jobNames = @([regex]::Matches($jobSection, '(?m)^  ([a-zA-Z0-9_-]+):\r?$') | ForEach-Object { $_.Groups[1].Value })
Check 'routing-exact-job-roster' (($jobNames -join ',') -ceq 'private-evidence-upload,public-evidence-import,k8s-acceptance-verification')
foreach ($name in @('private-evidence-upload', 'public-evidence-import', 'k8s-acceptance-verification')) {
    $blocks = @([regex]::Matches($publicText, ('(?ms)^  ' + [regex]::Escape($name) + ':\r?\n(?<job>.*?)(?=^  [a-zA-Z0-9_-]+:\r?$|\z)')))
    Check ('routing-one-job-' + $name) ($blocks.Count -eq 1)
    $jobs[$name] = $blocks[0].Groups['job'].Value
}
$metadata = $jobs['public-evidence-import']
$acceptance = $jobs['k8s-acceptance-verification']
Check 'acceptance-public-nonmetadata-routing' ($acceptance.Contains("if: github.event.repository.private == false && (github.event_name != 'workflow_dispatch' || inputs.evidenceMode == 'k8s-verify')"))
Check 'acceptance-no-hosted-runtime-workloads' ($acceptance -notmatch '\b(mvn|mvnw|java|javac|kubectl|docker)\b|setup-java|run-v019|build-release')
Check 'acceptance-verifies-actual-evidence' ($acceptance.Contains('run: ./tools/ci/check-k8s-acceptance.ps1') -and
    $acceptance.Contains('name: K8s Acceptance Verification') -and $acceptance.Contains('environment: k8s-acceptance') -and
    $acceptance -notmatch 'Import pinned actual sample runtime evidence|steps\.sample-runtime\.outputs|continue-on-error|always\(\)')
Check 'acceptance-rejects-pr-before-evidence-access' ($acceptance.Contains("`$env:GITHUB_EVENT_NAME -cnotin @('push', 'workflow_dispatch')") -and
    $acceptance.Contains('$env:PUBLIC_WORKFLOW_SHA -cne $env:GITHUB_SHA') -and
    $acceptance.IndexOf('Require trusted source before private evidence access') -lt $acceptance.IndexOf('GH_TOKEN:'))
Check 'acceptance-only-publishes-verification-marker' ($acceptance.Contains('target/k8s-acceptance-verification.md') -and
    $acceptance -notmatch 'sample-runtime-evidence|target/\*\*|target/\*|\.xml|\.zip')
Check 'public-import-only-explicit-public-dispatch' ($metadata.Contains("if: github.event_name == 'workflow_dispatch' && github.event.repository.private == false && inputs.evidenceMode == 'public-import'"))
Check 'public-mode-rejects-mixed-asset-inputs' ($metadata.Contains("`$env:EVIDENCE_MODE -cne 'public-import'") -and $metadata.Contains('$env:PRIVATE_ASSET_ID -or $env:PRIVATE_ASSET_PIN'))
Check 'public-import-exact-workflow-source-S' ($metadata.Contains('$env:PUBLIC_WORKFLOW_SHA -cne $env:GITHUB_SHA') -and $metadata.Contains('ref: ${{ github.sha }}') -and $metadata.Contains('persist-credentials: false'))
Check 'public-import-admission-before-checkout' ($metadata.IndexOf('Admit exact public metadata-only import before checkout') -ge 0 -and
    $metadata.IndexOf('Admit exact public metadata-only import before checkout') -lt $metadata.IndexOf('uses: actions/checkout@'))
Check 'public-import-read-only-dedicated-token' ($metadata.Contains('contents: read') -and $metadata.Contains('GH_TOKEN: ${{ secrets.CACHEDB_EVIDENCE_READ_TOKEN }}') -and
    $metadata -notmatch 'actions: write|contents: write|id-token:|write-all|GITHUB_TOKEN:')
# Exempt only the existing bare path probe, never a path-qualified or suffixed script.
$metadataPathProbe = '(?<=\()Test-Path(?=[ \t]+-LiteralPath\b)'
$metadataWorkloads = '\b(mvn|mvnw|java|javac|kubectl|docker)\b|setup-java|test-[A-Za-z0-9_-]+|run-v019|build-release'
Check 'public-import-no-workloads' (($metadata -creplace $metadataPathProbe, 'MetadataPathProbe') -notmatch $metadataWorkloads)
Check 'public-import-allows-bare-metadata-path-probe' (('if ((Test-Path -LiteralPath $Destination)) {}' -creplace $metadataPathProbe, 'MetadataPathProbe') -notmatch $metadataWorkloads)
$workloadFixtures = [ordered]@{
    'maven' = 'run: mvn verify'
    'maven-wrapper' = 'run: ./mvnw verify'
    'java' = 'run: java -version'
    'javac' = 'run: javac Main.java'
    'kubectl' = 'run: kubectl get pods'
    'docker' = 'run: docker build .'
    'setup-java' = 'uses: actions/setup-java@v4'
    'test-script' = 'run: ./tools/ci/test-v019-runtime-workflow.ps1'
    'mixed-case-test-script' = 'run: ./tools/ci/TEST-V019-runtime-workflow.ps1'
    'path-probe-script' = 'run: ./tools/ci/Test-Path.ps1 -LiteralPath /workspace'
    'path-qualified-probe' = 'if ((./tools/ci/Test-Path -LiteralPath /workspace)) {}'
    'suffixed-probe' = 'if ((Test-Path.ps1 -LiteralPath /workspace)) {}'
    'runtime-wrapper' = 'run: ./tools/ci/run-v019-sample-runtime.ps1'
    'release-build' = 'run: ./tools/release/build-release-bundle.ps1'
}
foreach ($fixtureName in $workloadFixtures.Keys) {
    Check ('public-import-rejects-workload-' + $fixtureName) (($workloadFixtures[$fixtureName] -creplace $metadataPathProbe, 'MetadataPathProbe') -match $metadataWorkloads)
}
Check 'public-import-independent-success-only-gate' ($metadata -notmatch 'always\(\)|continue-on-error|(?m)^    needs:' -and
    $metadata.Contains('R07 retained-evidence import only; ordinary CI and release acceptance remain separate gates.'))
$metadataSteps = @([regex]::Matches($metadata, '(?m)^      - name: ([^\r\n]+)') | ForEach-Object { $_.Groups[1].Value })
Check 'public-import-exact-metadata-step-allowlist' (($metadataSteps -join '|') -ceq (@(
    'Admit exact public metadata-only import before checkout', 'Checkout exact source S with no retained credentials',
    'Import pinned actual sample runtime evidence', 'Validate 0.19 sample capability coverage',
    'Publish R07 import summary', 'Upload R07 import summary') -join '|'))
Check 'canonical-import-moved-not-duplicated' ([regex]::Matches($publicText, '(?m)^      - name: Import pinned actual sample runtime evidence\r?$').Count -eq 1 -and
    $metadata.Contains('Import pinned actual sample runtime evidence'))

function Test-WorkflowJobRoute([string]$Condition, [string]$Event, [bool]$Private, [string]$Mode) {
    # Translate only this routing grammar, never arbitrary workflow code or functions.
    $tokens = [regex]::Matches($Condition, "github\.event_name|github\.event\.repository\.private|inputs\.evidenceMode|'[A-Za-z0-9_-]*'|==|!=|&&|\|\||[()]|true|false")
    if (($tokens.Value -join '') -cne ($Condition -replace '\s', '')) { throw 'Unsupported job routing expression.' }
    $expression = ($tokens | ForEach-Object {
        switch -CaseSensitive ($_.Value) {
            'github.event_name' { '$Event' }
            'github.event.repository.private' { '$Private' }
            'inputs.evidenceMode' { '$Mode' }
            '==' { '-eq' }; '!=' { '-ne' }; '&&' { '-and' }; '||' { '-or' }
            'true' { '$true' }; 'false' { '$false' }
            default { $_ }
        }
    }) -join ' '
    return [bool](& ([scriptblock]::Create('param($Event, $Private, $Mode) ' + $expression)) $Event $Private $Mode)
}
$conditions = @{}
foreach ($name in $jobs.Keys) {
    $matches = @([regex]::Matches($jobs[$name], '(?m)^    if: ([^\r\n]+)'))
    Check ('routing-one-condition-' + $name) ($matches.Count -eq 1)
    $conditions[$name] = $matches[0].Groups[1].Value
}
foreach ($event in @('workflow_dispatch', 'push', 'pull_request', 'pull_request_target', 'workflow_run', 'schedule', 'repository_dispatch')) {
    foreach ($private in @($false, $true)) {
        foreach ($mode in @('', 'k8s-verify', 'public-import', 'private-upload', 'unknown')) {
            $expected = [ordered]@{
                'private-evidence-upload' = ($event -ceq 'workflow_dispatch' -and $mode -ceq 'private-upload')
                'public-evidence-import' = (!$private -and $event -ceq 'workflow_dispatch' -and $mode -ceq 'public-import')
                'k8s-acceptance-verification' = (!$private -and ($event -cne 'workflow_dispatch' -or $mode -ceq 'k8s-verify'))
            }
            foreach ($name in $expected.Keys) {
                $actual = Test-WorkflowJobRoute $conditions[$name] $event $private $mode
                Check ("routing-$name-$event-$private-" + $(if ($mode) { $mode } else { 'empty' })) ($actual -eq $expected[$name])
            }
        }
    }
}
Reject 'routing-rejects-arbitrary-workflow-code' { Test-WorkflowJobRoute 'always()' 'workflow_dispatch' $false 'public-import' }
Check 'uploader-downloads-fixed-same-repository-endpoint' ($uploadSource.Contains("`$assetUri = `$repositoryUri + '/releases/assets/' + `$inputs.assetId") -and $uploadSource.Contains("Receive-SampleRuntimeArchive `$assetUri `$context.token `$archive 'application/octet-stream'"))
Check 'uploader-reuses-original-bounded-extractor' ($uploadSource.Contains('Expand-SampleRuntimeArchive $archive $evidence $inputs.assetPin $inputs.receiptPin'))
Check 'uploader-never-regenerates-receipt-or-results' ($uploadSource -notmatch 'WriteAllText|SampleRuntimeEvidence\.java|receipt\s*=.*ConvertTo-Json|mvn|kubectl' -and $uploadSource.Contains('runtimeResultsRegenerated = $false'))
Check 'uploader-no-artifact-code-execution' ($uploadSource -notmatch 'Invoke-Expression|Import-Module|&\s*\$receipt|WorkingDirectory\s*=\s*\$evidence')

# These tiny member fixtures exercise mutation rejection, not runtime evidence or receipts.
$memberRoot=Join-Path $EvidenceDirectory 'member-controls'; $null=[IO.Directory]::CreateDirectory($memberRoot)
$member=Join-Path $memberRoot 'original.dat'; [IO.File]::WriteAllText($member, 'sealed control bytes')
$original=Get-PrivateEvidenceMemberPins $memberRoot
Assert-PrivateEvidenceMembersUnchanged $memberRoot $original
Check 'original-members-byte-identical' $true
[IO.File]::WriteAllText($member, 'changed control bytes')
Reject 'original-member-mutation' { Assert-PrivateEvidenceMembersUnchanged $memberRoot $original }
[IO.File]::Delete($member)
Reject 'original-member-deletion' { Assert-PrivateEvidenceMembersUnchanged $memberRoot $original }
[IO.File]::WriteAllText($member, 'sealed control bytes')
$extra=Join-Path $memberRoot 'extra.dat'; [IO.File]::WriteAllText($extra, 'unexpected')
Reject 'unapproved-added-member' { Assert-PrivateEvidenceMembersUnchanged $memberRoot $original }
[IO.File]::Delete($extra)
$qualified=Join-Path $memberRoot 'private-upload-qualification'; $null=[IO.Directory]::CreateDirectory($qualified)
[IO.File]::WriteAllText((Join-Path $qualified 'transport.json'), 'control metadata only')
Assert-PrivateEvidenceMembersUnchanged $memberRoot $original
Check 'only-fresh-transport-metadata-added' $true

$summary=[ordered]@{
    schema=1; kind='SYNTHETIC_PRIVATE_OWNED_IMPORT_GUARDS_ONLY'; actualRuntimeQualified=$false; livePrivateTransport=$false
    controls=$controls; wrapperSha256=(Hash $wrapper); templateSha256=(Hash $template); publicWorkflowSha256=(Hash $public); uploaderSha256=(Hash $uploader)
}
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'summary.json'), ($summary | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
Write-Host ($controls.Count.ToString() + ' synthetic private-owned import checks passed; no live transport, product or public CI qualification.')
