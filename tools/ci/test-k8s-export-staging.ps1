#requires -Version 7.3
[CmdletBinding()]
param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'k8s-source-equivalence.ps1')
foreach ($name in @('check-k8s-acceptance.ps1','stage-k8s-acceptance.ps1')) {
    foreach ($definition in Import-K8sGuardFunctions (Join-Path $PSScriptRoot $name)) {
        . ([scriptblock]::Create($definition))
    }
}
if (!$EvidenceDirectory) { $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('k8s-export-controls-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Fresh synthetic control output required.' }
$null = [IO.Directory]::CreateDirectory($EvidenceDirectory)
$source = Join-Path $EvidenceDirectory 'source'; $null = [IO.Directory]::CreateDirectory($source)
$controls = [Collections.Generic.List[string]]::new()
$fixtureChecks = [Collections.Generic.List[string]]::new()
function Reject([string]$Name, [scriptblock]$Action) {
    $rejected = $false
    try { $null = & $Action } catch { $rejected = $true }
    if (!$rejected) { throw "Unsafe synthetic export accepted: $Name" }
    $controls.Add($Name)
}
function Clone($Value) { $Value | ConvertTo-Json -Depth 32 | ConvertFrom-Json -AsHashtable -Depth 32 }
function Reject-Overlay([string]$Name, [scriptblock]$Action, [string]$ExpectedError) {
    $failure = $null
    try { $null = & $Action } catch { $failure = $_.Exception.Message }
    if (!$failure -or $failure -cnotmatch $ExpectedError) { throw "Overlay control failed: $Name; observed: $failure" }
    $fixtureChecks.Add($Name)
}
function Put([string]$Path, [string]$Value) {
    $file = Resolve-K8sMember $source $Path
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($file))
    [IO.File]::WriteAllText($file, $Value, [Text.UTF8Encoding]::new($false))
}
$reviewedPaths = @('cachedb-starter/src/test/java/com/cachedb/starter/RedisReadWiringRedisTest.java',
    'cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisReadRedirectServer.java')
$sampleWorkflowPaths = @('postgresql','mssql','oracle' | ForEach-Object {
    'sample-cache-database-' + $_ + '/.github/workflows/consumer-build.yml'
})
$script:paths = @('pom.xml', 'cachedb-core/src/main/java/Example.java', 'cachedb-processor/src/main/java/Processor.java',
    'sample-cache-database-postgresql/src/main/java/Example.java', 'README.md', 'tools/ci/control.ps1') +
    $reviewedPaths + $sampleWorkflowPaths
# Synthetic inventory only: the production helper uses the existing bounded Git inspector.
function Invoke-PrivateOwnedGit([string]$Root, [string[]]$Arguments) {
    if ($Root -cne $source -or ($Arguments -join ',') -cne 'ls-files') { throw 'Unexpected synthetic source inspection.' }
    return $script:paths -join "`n"
}
$old = [ordered]@{}
foreach ($path in $script:paths) {
    Put $path 'ORIGINAL'
    $old[$path] = (Get-FileHash -LiteralPath (Resolve-K8sMember $source $path)).Hash.ToLowerInvariant()
}
$manifest = Join-Path $EvidenceDirectory 'source.sha256'
[IO.File]::WriteAllLines($manifest, [string[]]@($old.Keys | ForEach-Object { $old[$_] + '  ' + $_ }))
$from = '1' * 40; $to = '2' * 40; $sdk = '3' * 64
function Assert-PrivateOwnedCheckout([string]$Root, [string]$Sha) {
    if ($Root -cne $source -or $Sha -cne $to) { throw 'Unexpected synthetic checkout authority.' }
}
Put 'README.md' 'UPDATED DOCUMENTATION'; Put 'tools/ci/control.ps1' 'UPDATED METADATA CONTROL'
$proof = @{schema='cachedb.k8s-source-equivalence/v1'; kind='DOCUMENTATION_TOOLING_ONLY_NOT_RUNTIME_ACCEPTANCE'
    sourceSha=$from; targetSha=$to; sdkManifestSha256=$sdk; sourceManifestSha256=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant()
    changes=@('README.md','tools/ci/control.ps1' | ForEach-Object {
        @{path=$_; classification=(Get-K8sOverlayClassification $_); beforeSha256=$old[$_]
            afterSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $source $_)).Hash.ToLowerInvariant()}
    })}
$proofPath = Join-Path $EvidenceDirectory 'proof.json'
function Check($Value, [string]$Pin = '') {
    [IO.File]::WriteAllText($proofPath, ($Value | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
    if (!$Pin) { $Pin = (Get-FileHash -LiteralPath $proofPath).Hash.ToLowerInvariant() }
    $null = Assert-K8sSourceEquivalence $proofPath $Pin $manifest $source $from $to $sdk
}
Check $proof; $controls.Add('explicit-documentation-tooling-only-overlay-with-unchanged-product-bytes')
Reject 'independent-proof-pin' { Check $proof ('0' * 64) }
foreach ($field in @('schema','kind','sourceSha','targetSha','sdkManifestSha256','sourceManifestSha256')) {
    $bad = Clone $proof; $bad[$field] = 'foreign'
    Reject ('foreign-' + $field) { Check $bad }
}
foreach ($path in @('pom.xml', 'cachedb-core/src/main/java/Example.java', 'cachedb-processor/src/main/java/Processor.java',
    'sample-cache-database-postgresql/src/main/java/Example.java')) {
    Put $path 'CHANGED RUNTIME OR BUILD'
    Reject ('undeclared-product-change-' + $path) { Check $proof }
    $bad = Clone $proof
    $bad.changes += @{path=$path; classification='execution-tooling'; beforeSha256=$old[$path]
        afterSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $source $path)).Hash.ToLowerInvariant()}
    Reject ('declared-product-change-' + $path) { Check $bad }
    Put $path 'ORIGINAL'
}
$bad = Clone $proof; $bad.changes += (Clone $bad.changes[0])
Reject 'duplicate-delta' { Check $bad }
$bad = Clone $proof; $bad.changes[0].beforeSha256 = '0' * 64
Reject 'wrong-before-byte-pin' { Check $bad }
$bad = Clone $proof; $bad.changes[0].afterSha256 = '0' * 64
Reject 'wrong-after-byte-pin' { Check $bad }
$bad = Clone $proof; $bad.changes[0].classification = 'execution-tooling'
Reject 'wrong-classification' { Check $bad }
$bad = Clone $proof; $bad.changes = @()
Reject 'empty-source-equivalence' { Check $bad }
foreach ($path in @('../escape', 'tools/ci/../../pom.xml', 'sample-cache-database-oracle/scripts/run.ps1', 'cachedb-processor/README.md')) {
    Reject ('ineligible-overlay-' + $path) { Get-K8sOverlayClassification $path }
}
$script:paths += 'readme.md'
Put 'readme.md' 'UNAPPROVED CASE ALIAS'
Reject 'case-alias-cannot-hide-extra-source' { Check $proof }
$script:paths = @($script:paths | Where-Object { $_ -cne 'readme.md' })
$authority = @{sha=$to; publicRepository='synthetic/public'; publicRepositoryId='10'; privateRepositoryId='20'
    runId='30'; runAttempt='1'; workflowPath='.github/workflows/framework-readiness.yml'; sdkPin=$sdk}
$envelope = @{schema='cachedb.k8s-distribution/v1'; kind='K8S_EVIDENCE_DISTRIBUTION_NOT_RUNTIME_ACCEPTANCE'
    releaseSource=@{sha=$to; repository='synthetic/public'; repositoryId='10'}
    producer=@{repositoryId='20'; runId='30'; runAttempt='1'; workflowPath=$authority.workflowPath; headSha=$to}
    runtime=@{receipt='runtime/receipt.json'; receiptSha256=('4'*64); inputArchiveSha256=('5'*64); sdkManifestSha256=$sdk}}
Assert-K8sDistributionEnvelope $envelope $authority; $controls.Add('actual-distribution-producer-binding-not-runtime-pass')
foreach ($field in @('runId','runAttempt','repositoryId','workflowPath','headSha')) {
    $bad = Clone $envelope; $bad.producer[$field] = 'foreign'
    Reject ('foreign-producer-' + $field) { Assert-K8sDistributionEnvelope $bad $authority }
}
$bad = Clone $envelope; $bad.kind = 'KUBERNETES_RUNTIME_ACCEPTANCE'
Reject 'distribution-cannot-label-itself-runtime-acceptance' { Assert-K8sDistributionEnvelope $bad $authority }
$parent = @{source=@{manifest='source.sha256'}; sdk=@{manifestSha256=$sdk}}
$sourcePin = (Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant()
$feature = @{source=@{manifest=@{sha256=$sourcePin}}; sdk=@{manifest=@{sha256=$sdk}; sourceManifestSha256=$sourcePin}}
Assert-K8sFeatureBinding $feature $parent $EvidenceDirectory
$controls.Add('feature-must-bind-original-execution-source-and-sdk')
$bad = Clone $feature; $bad.sdk.manifest.sha256 = '7' * 64
Reject 'old-sdk-feature-cannot-qualify-new-runtime' { Assert-K8sFeatureBinding $bad $parent $EvidenceDirectory }
$bad = Clone $feature; $bad.source.manifest.sha256 = '8' * 64; $bad.sdk.sourceManifestSha256 = '8' * 64
Reject 'old-source-feature-cannot-qualify-new-runtime' { Assert-K8sFeatureBinding $bad $parent $EvidenceDirectory }
$bad = Clone $feature; $bad.sdk.sourceManifestSha256 = '9' * 64
Reject 'feature-sdk-build-source-must-match-feature-source' { Assert-K8sFeatureBinding $bad $parent $EvidenceDirectory }
# Snapshot qualification is separate from the immutable original receipt and has its own tooling evidence.
$policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
$qualificationRoot = Join-Path $EvidenceDirectory 'snapshot-qualification'
$null = [IO.Directory]::CreateDirectory((Join-Path $qualificationRoot 'controls'))
$finalManifestLines = @($script:paths | ForEach-Object {
    (Get-FileHash -LiteralPath (Resolve-K8sMember $source $_)).Hash.ToLowerInvariant() + '  ' + $_
})
$originalReceipt = Join-Path $EvidenceDirectory 'original-snapshot-receipt.json'
[IO.File]::WriteAllText($originalReceipt, '{"syntheticOriginalIdentity":"not-a-final-git-commit"}')
$originalReceiptPin = (Get-FileHash -LiteralPath $originalReceipt).Hash.ToLowerInvariant()
$snapshotIdentity = @{kind='SOURCE_SNAPSHOT'; manifestSha256=$sourcePin}
$toolingControl = @{schema='cachedb.k8s-tooling-control/v1'; kind='TOOLING_ONLY_NOT_PRODUCT_RUNTIME_COVERAGE'
    execution=@{id='receipt-controls'; command=$policy.Controls[29]; workingDirectory='.'
        sourceIdentity=(Clone $snapshotIdentity); podUid='11111111-2222-3333-4444-555555555555'
        startedAt='2026-11-11T10:00:01Z'; finishedAt='2026-11-11T10:00:20Z'
        log='controls/process.log'; exitFile='controls/process.exit'}
    run=@{cluster='EmaTech'; namespace='synthetic-controls'; podUid='11111111-2222-3333-4444-555555555555'
        createdAt='2026-11-11T10:00:00Z'; originalCutoff='2026-11-11T10:03:00Z'; finishedAt='2026-11-11T10:00:21Z'}
    sourceManifest=@{path='control-source.sha256'; sha256=$sourcePin}}
$snapshotProof = @{schema='cachedb.k8s-source-equivalence/v2'; kind=$proof.kind
    sourceIdentity=(Clone $snapshotIdentity); originalReceiptSha256=$originalReceiptPin; targetSha=$to
    sdkManifestSha256=$sdk; sourceManifestSha256=$sourcePin
    targetManifest=@{path='target.sha256'; sha256=$sourcePin}; changes=(Clone $proof.changes)
    toolingEvidence=@(@{obligation='control/29'; paths=@('tools/ci/control.ps1')
        receipt=@{path='controls/control.json'; sha256=$sourcePin}}); files=@()}
function Check-Snapshot($Value = $snapshotProof, $Record = $toolingControl,
    [string[]]$TargetLines = $finalManifestLines, [string[]]$ControlLines = $finalManifestLines,
    [string]$ProcessExit = '0', [string]$Pin = '', $PolicyValue = $policy,
    [string]$OriginalManifestPath = $manifest, $OriginalIdentity = $snapshotIdentity) {
    $valueCopy = Clone $Value; $recordCopy = Clone $Record
    foreach ($entry in @(@{path='target.sha256'; lines=$TargetLines}, @{path='control-source.sha256'; lines=$ControlLines})) {
        [IO.File]::WriteAllLines((Join-Path $qualificationRoot $entry.path), [string[]]$entry.lines, [Text.UTF8Encoding]::new($false))
    }
    $recordCopy.sourceManifest.sha256 = (Get-FileHash -LiteralPath (Join-Path $qualificationRoot 'control-source.sha256')).Hash.ToLowerInvariant()
    $recordCopy.execution.sourceIdentity.manifestSha256 = $recordCopy.sourceManifest.sha256
    [IO.File]::WriteAllText((Join-Path $qualificationRoot 'controls/process.log'), 'SYNTHETIC TOOLING CONTROL ONLY')
    [IO.File]::WriteAllText((Join-Path $qualificationRoot 'controls/process.exit'), $ProcessExit)
    [IO.File]::WriteAllText((Join-Path $qualificationRoot 'controls/control.json'), ($recordCopy | ConvertTo-Json -Depth 32))
    $valueCopy.files = @('target.sha256','control-source.sha256','controls/process.log','controls/process.exit','controls/control.json' | ForEach-Object {
        $file = Get-Item -LiteralPath (Join-Path $qualificationRoot $_)
        @{path=$_; bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()}
    })
    $valueCopy.targetManifest.sha256 = $valueCopy.files[0].sha256
    foreach ($binding in $valueCopy.toolingEvidence) { $binding.receipt.sha256 = $valueCopy.files[4].sha256 }
    $path = Join-Path $qualificationRoot 'source-equivalence.json'
    [IO.File]::WriteAllText($path, ($valueCopy | ConvertTo-Json -Depth 32))
    if (!$Pin) { $Pin = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() }
    $null = Assert-K8sSourceEquivalence $path $Pin $OriginalManifestPath $source '' $to $sdk $OriginalIdentity $originalReceiptPin $PolicyValue
}
Check-Snapshot; $controls.Add('manifest-snapshot-to-final-commit-separately-sealed-tooling-evidence')
Reject 'snapshot-independent-qualification-pin' { Check-Snapshot -Pin ('0' * 64) }
foreach ($field in @('schema','kind','targetSha','originalReceiptSha256','sdkManifestSha256','sourceManifestSha256')) {
    $bad = Clone $snapshotProof; $bad[$field] = 'foreign'
    Reject ('snapshot-foreign-' + $field) { Check-Snapshot $bad }
}
$bad = Clone $snapshotProof; $bad.sourceSha = $from
Reject 'snapshot-cannot-invent-original-git-sha' { Check-Snapshot $bad }
$bad = Clone $snapshotProof; $bad.sourceIdentity.manifestSha256 = '0' * 64
Reject 'snapshot-foreign-original-manifest-identity' { Check-Snapshot $bad }
$bad = Clone $snapshotProof; $bad.toolingEvidence = @()
Reject 'snapshot-tooling-without-its-own-controls' { Check-Snapshot $bad }
$bad = Clone $snapshotProof; $bad.toolingEvidence[0].obligation = 'control/17'
Reject 'snapshot-product-build-not-tooling-control-coverage' { Check-Snapshot $bad }
$bad = Clone $snapshotProof; $bad.toolingEvidence[0].paths = @('README.md')
Reject 'snapshot-unrelated-control-to-delta-mapping' { Check-Snapshot $bad }
$bad = Clone $toolingControl; $bad.execution.command = 'unapproved control'
Reject 'snapshot-unapproved-tooling-command' { Check-Snapshot -Record $bad }
$bad = Clone $toolingControl; $bad.execution.podUid = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
Reject 'snapshot-tooling-original-pod-uid' { Check-Snapshot -Record $bad }
$bad = Clone $toolingControl; $bad.run.originalCutoff = '2026-11-11T10:00:10Z'
Reject 'snapshot-tooling-original-cutoff' { Check-Snapshot -Record $bad }
Reject 'snapshot-failed-tooling-control' { Check-Snapshot -ProcessExit '1' }
Reject 'snapshot-partial-final-source-roster' { Check-Snapshot -TargetLines $finalManifestLines[1..($finalManifestLines.Count-1)] }
$oldControlLines = @($old.Keys | ForEach-Object { $old[$_] + '  ' + $_ })
Reject 'snapshot-controls-on-old-executable-source' { Check-Snapshot -ControlLines $oldControlLines }
$documentationOnlyDifference = @($finalManifestLines | ForEach-Object {
    if ($_.EndsWith('  README.md')) { $old['README.md'] + '  README.md' } else { $_ }
})
Check-Snapshot -ControlLines $documentationOnlyDifference
$controls.Add('snapshot-controls-may-precede-documentation-only-final-delta')
foreach ($path in @('pom.xml','cachedb-core/src/main/java/Example.java')) {
    Put $path 'CHANGED RUNTIME OR BUILD'
    $bad = Clone $snapshotProof
    $bad.changes += @{path=$path; classification='execution-tooling'; beforeSha256=$old[$path]
        afterSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $source $path)).Hash.ToLowerInvariant()}
    Reject ('snapshot-declared-runtime-build-change-' + $path) { Check-Snapshot $bad }
    Put $path 'ORIGINAL'
}
Check-Snapshot
# Additional cases belong to the existing snapshot qualification groups, not new top-level controls.
$fixturePolicy = Clone $policy
$fixturePolicy.ReviewedTestSources = @{}
$fixturePins = @{}
$overlayProof = Clone $snapshotProof
try {
    foreach ($path in @($reviewedPaths + $sampleWorkflowPaths)) {
        Put $path ('SYNTHETIC REVIEWED OVERLAY ' + $path)
        $pin = (Get-FileHash -LiteralPath (Resolve-K8sMember $source $path)).Hash.ToLowerInvariant()
        $classification = 'execution-tooling'
        if ($path -cin $reviewedPaths) {
            $classification = 'reviewed-test-fixture'
            $fixturePins[$path] = $pin
        } else { $overlayProof.toolingEvidence[0].paths += $path }
        $overlayProof.changes += @{path=$path; classification=$classification; beforeSha256=$old[$path]; afterSha256=$pin}
    }
    # Synthetic policy pins exercise admission without claiming these fixture bytes are original runtime evidence.
    $fixturePolicy.ReviewedTestSources[$sourcePin] = $fixturePins
    $overlayLines = @($script:paths | ForEach-Object {
        (Get-FileHash -LiteralPath (Resolve-K8sMember $source $_)).Hash.ToLowerInvariant() + '  ' + $_
    })
    Check-Snapshot $overlayProof -TargetLines $overlayLines -ControlLines $overlayLines -PolicyValue $fixturePolicy
    $fixtureChecks.Add('v2-exact-policy-pinned-test-fixtures-and-three-sample-workflows')
    $legacy = Clone $proof; $legacy.changes = Clone $overlayProof.changes
    Reject-Overlay 'v1-cannot-admit-reviewed-java-fixtures' { Check $legacy } 'Only explicitly pinned'
    $noAdmission = Clone $fixturePolicy; $noAdmission.ReviewedTestSources = @{}
    Reject-Overlay 'v2-reviewed-fixtures-require-policy-admission' {
        Check-Snapshot $overlayProof -TargetLines $overlayLines -ControlLines $overlayLines -PolicyValue $noAdmission
    } 'Only explicitly pinned'
    foreach ($path in $reviewedPaths) {
        $wrongPin = Clone $fixturePolicy; $wrongPin.ReviewedTestSources[$sourcePin][$path] = '0' * 64
        Reject-Overlay ('wrong-reviewed-after-pin-' + $path) {
            Check-Snapshot $overlayProof -TargetLines $overlayLines -ControlLines $overlayLines -PolicyValue $wrongPin
        } 'Only explicitly pinned'
        Reject-Overlay ('reviewed-path-case-alias-' + $path) {
            Get-K8sOverlayClassification $path.ToUpperInvariant() $fixturePolicy $fixturePins[$path]
        } 'Only explicitly pinned'
        $additionManifest = Join-Path $EvidenceDirectory 'fixture-addition-source.sha256'
        [IO.File]::WriteAllLines($additionManifest, [string[]]@($old.Keys | Where-Object { $_ -cne $path } | ForEach-Object {
            $old[$_] + '  ' + $_
        }))
        $additionPin = (Get-FileHash -LiteralPath $additionManifest).Hash.ToLowerInvariant()
        $additionIdentity = @{kind='SOURCE_SNAPSHOT'; manifestSha256=$additionPin}
        $additionProof = Clone $overlayProof
        $additionProof.sourceIdentity = $additionIdentity; $additionProof.sourceManifestSha256 = $additionPin
        @($additionProof.changes | Where-Object { $_.path -ceq $path })[0].beforeSha256 = $null
        Reject-Overlay ('reviewed-fixture-addition-' + $path) {
            Check-Snapshot $additionProof -TargetLines $overlayLines -ControlLines $overlayLines -PolicyValue $fixturePolicy `
                -OriginalManifestPath $additionManifest -OriginalIdentity $additionIdentity
        } 'Reviewed test fixtures must already exist'
    }
    foreach ($path in @('cachedb-core/src/test/java/UnreviewedTest.java', 'cachedb-core/src/main/java/Example.java',
            'cachedb-processor/src/main/java/Processor.java', 'sample-cache-database-postgresql/src/main/java/Example.java', 'pom.xml')) {
        $overbroad = Clone $fixturePolicy; $overbroad.ReviewedTestSources[$sourcePin][$path] = 'a' * 64
        Reject-Overlay ('policy-cannot-admit-generic-java-or-build-' + $path) {
            Get-K8sOverlayClassification $path $overbroad ('a' * 64)
        } 'Only explicitly pinned'
    }
    foreach ($path in @('sample-cache-database-oracle/.github/workflows/other.yml',
            'sample-cache-database-foreign/.github/workflows/consumer-build.yml',
            'sample-cache-database-oracle/scripts/run.ps1')) {
        Reject-Overlay ('unrelated-nested-sample-tooling-' + $path) { Get-K8sOverlayClassification $path } 'Only explicitly pinned'
    }
    foreach ($path in @('tools/ci/control.ps1') + $reviewedPaths + $sampleWorkflowPaths) {
        $partial = @($overlayLines | Where-Object { !$_.EndsWith('  ' + $path, [StringComparison]::Ordinal) })
        Reject-Overlay ('full-control-source-required-' + $path) {
            Check-Snapshot $overlayProof -TargetLines $overlayLines -ControlLines $partial -PolicyValue $fixturePolicy
        } 'Tooling controls executed different non-documentation source bytes'
    }
    $extraControlLines = @($overlayLines) + (('a' * 64) + '  tools/ci/foreign.ps1')
    Reject-Overlay 'control-source-cannot-have-extra-non-documentation-member' {
        Check-Snapshot $overlayProof -TargetLines $overlayLines -ControlLines $extraControlLines -PolicyValue $fixturePolicy
    } 'Tooling controls executed different non-documentation source bytes'
    $uncovered = Clone $overlayProof
    $uncovered.toolingEvidence[0].paths = @($uncovered.toolingEvidence[0].paths | Where-Object { $_ -cne $sampleWorkflowPaths[0] })
    Reject-Overlay 'every-changed-workflow-needs-explicit-control-coverage' {
        Check-Snapshot $uncovered -TargetLines $overlayLines -ControlLines $overlayLines -PolicyValue $fixturePolicy
    } 'Changed tooling lacks separately pinned execution coverage'
    $misclassified = Clone $overlayProof
    $misclassified.toolingEvidence[0].paths += $reviewedPaths[0]
    Reject-Overlay 'tooling-control-cannot-claim-reviewed-java-runtime-coverage' {
        Check-Snapshot $misclassified -TargetLines $overlayLines -ControlLines $overlayLines -PolicyValue $fixturePolicy
    } 'Tooling evidence mapping is duplicate or unrelated'
} finally {
    foreach ($path in @($reviewedPaths + $sampleWorkflowPaths)) { Put $path 'ORIGINAL' }
}
Check-Snapshot
if ($controls.Count -ne 59) { throw 'Existing 59 top-level export control groups must remain unchanged.' }
if ((Get-FileHash -LiteralPath $originalReceipt).Hash.ToLowerInvariant() -cne $originalReceiptPin) { throw 'Synthetic original receipt changed.' }
[ordered]@{syntheticOnly=$true; actualKubernetesAcceptance=$false; controls=$controls; result='PASS'
    fixtureCoverage=@{obligation='control/29'; command=$policy.Controls[29]; syntheticOnly=$true
        paths=@('tools/ci/k8s-source-equivalence.ps1','tools/ci/test-k8s-export-staging.ps1')
        groups=@('manifest-snapshot-to-final-commit-separately-sealed-tooling-evidence',
            'snapshot-controls-on-old-executable-source','snapshot-unrelated-control-to-delta-mapping')
        checks=$fixtureChecks}} |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Host "K8S_EXPORT_SYNTHETIC_CONTROLS_PASS=$($controls.Count); NOT_RUNTIME_ACCEPTANCE"
