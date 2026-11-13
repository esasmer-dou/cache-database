#requires -Version 7.3
[CmdletBinding()]
param([string]$EvidenceDirectory, [switch]$OracleConfigurationControlsOnly)
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
function Test-OracleConfigurationControls {
    $policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
    $configurationPath = 'sample-cache-database-oracle/src/purge/resources/application-purge.yml'
    $loggingPaths = @('tools/k8s/SampleRuntimeEvidence.java','tools/k8s/run-sample-runtime-evidence.sh')
    $matrixPaths = $loggingPaths + @('tools/ci/test-v019-sample-matrix.mjs','tools/ci/check-v019-sample-coverage.mjs')
    $otherToolingPath = 'tools/ci/test-k8s-export-staging.ps1'
    $actualPin = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot ('../../' + $configurationPath))).Hash.ToLowerInvariant()
    if ((Get-K8sOverlayClassification $configurationPath $policy $actualPin) -cne 'scoped-runtime-configuration') {
        throw 'Actual reviewed Oracle configuration is not classified by its exact policy pin.'
    }
    $fixtureChecks.Add('actual-oracle-configuration-policy-pin')
    foreach ($pin in @('', ('0' * 64))) {
        Reject-Overlay ('oracle-classification-wrong-after-pin-' + $pin.Length) {
            Get-K8sOverlayClassification $configurationPath $policy $pin
        } 'Only explicitly pinned'
    }
    foreach ($path in @($configurationPath.ToUpperInvariant(),
            'sample-cache-database-oracle/src/main/resources/application.yml',
            'sample-cache-database-postgresql/src/purge/resources/application-purge.yml')) {
        Reject-Overlay ('oracle-classification-wrong-path-' + $path) {
            Get-K8sOverlayClassification $path $policy $actualPin
        } 'Only explicitly pinned'
    }
    $script:paths = @($configurationPath) + $matrixPaths + @($otherToolingPath)
    function Invoke-PrivateOwnedGit([string]$Root, [string[]]$Arguments) {
        if ($Root -cne $source -or ($Arguments -join ',') -cne 'ls-files') { throw 'Unexpected scoped synthetic source inspection.' }
        return $script:paths -join "`n"
    }
    $targetSha = '2' * 40; $sdkPin = '3' * 64
    function Assert-PrivateOwnedCheckout([string]$Root, [string]$Sha) {
        if ($Root -cne $source -or $Sha -cne $targetSha) { throw 'Unexpected scoped synthetic checkout authority.' }
    }
    $before = @{}
    foreach ($path in $script:paths) {
        Put $path ('SYNTHETIC ORIGINAL ' + $path)
        $before[$path] = (Get-FileHash -LiteralPath (Resolve-K8sMember $source $path)).Hash.ToLowerInvariant()
    }
    $manifest = Join-Path $EvidenceDirectory 'oracle-source.sha256'
    [IO.File]::WriteAllLines($manifest, [string[]]@($script:paths | ForEach-Object { $before[$_] + '  ' + $_ }))
    $sourcePin = (Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant()
    $identity = @{kind='SOURCE_SNAPSHOT'; manifestSha256=$sourcePin}
    foreach ($path in $script:paths) { Put $path ('SYNTHETIC QUALIFIED ' + $path) }
    $lines = @($script:paths | ForEach-Object {
        (Get-FileHash -LiteralPath (Resolve-K8sMember $source $_)).Hash.ToLowerInvariant() + '  ' + $_
    })
    $root = Join-Path $EvidenceDirectory 'oracle-qualification'; $null = [IO.Directory]::CreateDirectory((Join-Path $root 'controls'))
    $pod = '11111111-2222-3333-4444-555555555555'
    $records = @{
        scopedReceipt=@{kind='cachedb-oracle-tenant-purge-scoped-runtime'; execution=@{runnerPodUid=$pod}
            sdk=@{manifest=@{sha256=$sdkPin}; sourceManifestSha256=$sourcePin}; cells=@(@{provider='oracle'; lane='tenant-purge'})}
        nativeProcess=@{podUid=$pod; exitCode=0; teeExit=0; timedOut=$false; stdout=@{bytes=100}}
        testsConfiguration=@{phase='tests'; readTimeoutMs=15000; connectTimeoutMsUnchanged=5000; guardStatementTimeoutSecondsUnchanged=2}
        httpConfiguration=@{phase='http'; readTimeoutMs=15000; connectTimeoutMsUnchanged=5000; guardStatementTimeoutSecondsUnchanged=2}
    }
    $review = @{before=$before[$configurationPath]; after=($lines[0] -split '  ')[0]; source=$sourcePin; sdk=$sdkPin; pod=$pod}
    $refs = @{}
    foreach ($name in $records.Keys) {
        $path = $name + '.json'; [IO.File]::WriteAllText((Join-Path $root $path), ($records[$name] | ConvertTo-Json -Depth 12))
        $pin = (Get-FileHash -LiteralPath (Join-Path $root $path)).Hash.ToLowerInvariant()
        $review[$name] = $pin; $refs[$name] = @{path=$path; sha256=$pin}
    }
    # Synthetic policy authority tests the contract; these are not replacements for original runtime records.
    $fixturePolicy = Clone $policy; $fixturePolicy.ReviewedOraclePurgeConfiguration = $review
    $proof = @{schema='cachedb.k8s-source-equivalence/v3'; kind='SEPARATELY_QUALIFIED_ORACLE_PURGE_CONFIGURATION'
        sourceIdentity=$identity; originalReceiptSha256=('4' * 64); targetSha=$targetSha; sdkManifestSha256=$sdkPin; sourceManifestSha256=$sourcePin
        targetManifest=@{path='target.sha256'; sha256=$sourcePin}; oraclePurgeConfiguration=$refs
        changes=@($script:paths | ForEach-Object {
            @{path=$_; classification=(Get-K8sOverlayClassification $_ $fixturePolicy (($lines[$script:paths.IndexOf($_)] -split '  ')[0]))
                beforeSha256=$before[$_]; afterSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $source $_)).Hash.ToLowerInvariant()}
        }); toolingEvidence=@(
            @{obligation='control/09'; paths=$matrixPaths; receipt=@{path='controls/matrix.json'; sha256=$sourcePin}},
            @{obligation='control/29'; paths=@($otherToolingPath); receipt=@{path='controls/other.json'; sha256=$sourcePin}}
        ); files=@()}
    function Check-Oracle($Value = $proof, $PolicyValue = $fixturePolicy, [string[]]$ControlLines = $lines, [int]$MatrixControlIndex = 9) {
        $candidate = Clone $Value
        [IO.File]::WriteAllLines((Join-Path $root 'target.sha256'), [string[]]$lines)
        [IO.File]::WriteAllLines((Join-Path $root 'control-source.sha256'), $ControlLines)
        $controlPin = (Get-FileHash -LiteralPath (Join-Path $root 'control-source.sha256')).Hash.ToLowerInvariant()
        if ($candidate.toolingEvidence.Count) { $candidate.toolingEvidence[0].obligation = 'control/{0:D2}' -f $MatrixControlIndex }
        $controlFiles = @('target.sha256','control-source.sha256')
        foreach ($control in @(@{name='matrix'; index=$MatrixControlIndex},@{name='other'; index=29})) {
            $stem = 'controls/' + $control.name
            $record = @{schema='cachedb.k8s-tooling-control/v1'; kind='TOOLING_ONLY_NOT_PRODUCT_RUNTIME_COVERAGE'
                execution=@{id=('oracle-source-' + $control.name); command=$policy.Controls[$control.index]; workingDirectory='.'; sourceIdentity=@{kind='SOURCE_SNAPSHOT'; manifestSha256=$controlPin}
                    podUid=$pod; startedAt='2026-11-13T10:00:01Z'; finishedAt='2026-11-13T10:00:20Z'; log=($stem + '.log'); exitFile=($stem + '.exit')}
                run=@{cluster='EmaTech'; namespace='synthetic-controls'; podUid=$pod; createdAt='2026-11-13T10:00:00Z'; originalCutoff='2026-11-13T10:02:00Z'; finishedAt='2026-11-13T10:00:21Z'}
                sourceManifest=@{path='control-source.sha256'; sha256=$controlPin}}
            [IO.File]::WriteAllText((Join-Path $root ($stem + '.json')), ($record | ConvertTo-Json -Depth 12))
            [IO.File]::WriteAllText((Join-Path $root ($stem + '.log')), 'SYNTHETIC TOOLING ONLY')
            [IO.File]::WriteAllText((Join-Path $root ($stem + '.exit')), '0')
            $controlFiles += @(($stem + '.json'), ($stem + '.log'), ($stem + '.exit'))
        }
        $candidate.files = @($controlFiles +
            @($records.Keys | ForEach-Object { $_ + '.json' }) | ForEach-Object {
                $file = Get-Item -LiteralPath (Join-Path $root $_)
                @{path=$_; bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()}
            })
        $candidate.targetManifest.sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'target.sha256')).Hash.ToLowerInvariant()
        foreach ($binding in $candidate.toolingEvidence) { $binding.receipt.sha256 = (Get-FileHash -LiteralPath (Join-Path $root $binding.receipt.path)).Hash.ToLowerInvariant() }
        $file = Join-Path $root 'source-equivalence.json'; [IO.File]::WriteAllText($file, ($candidate | ConvertTo-Json -Depth 20))
        $pin = (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant()
        $null = Assert-K8sSourceEquivalence $file $pin $manifest $source '' $targetSha $sdkPin $identity $proof.originalReceiptSha256 $PolicyValue
    }
    Check-Oracle; $fixtureChecks.Add('v3-exact-oracle-runtime-qualification-and-mapped-logging')
    foreach ($path in $matrixPaths) {
        $bad = Clone $proof; $bad.toolingEvidence[0].paths = @($path)
        Reject-Overlay ('matrix-surface-cannot-use-unrelated-command-' + $path) { Check-Oracle $bad -MatrixControlIndex 29 } 'must be qualified by their exact matrix control'
    }
    $bad = Clone $proof; $bad.toolingEvidence[0].paths += @($otherToolingPath)
    Reject-Overlay 'matrix-command-cannot-qualify-unrelated-tooling' { Check-Oracle $bad } 'must be qualified by their exact matrix control'
    $legacy = Clone $proof; $legacy.schema = 'cachedb.k8s-source-equivalence/v2'; $legacy.kind = 'DOCUMENTATION_TOOLING_ONLY_NOT_RUNTIME_ACCEPTANCE'; $null = $legacy.Remove('oraclePurgeConfiguration')
    Reject-Overlay 'v2-cannot-carry-scoped-runtime-delta' { Check-Oracle $legacy } 'Oracle configuration requires separate runtime qualification'
    foreach ($name in @('before','after','source','sdk')) {
        $badPolicy = Clone $fixturePolicy; $badPolicy.ReviewedOraclePurgeConfiguration[$name] = '0' * 64
        Reject-Overlay ('oracle-wrong-policy-' + $name) { Check-Oracle -PolicyValue $badPolicy } 'exact independently qualified source/SDK change'
    }
    foreach ($name in $records.Keys) {
        $bad = Clone $proof; $bad.oraclePurgeConfiguration[$name].sha256 = '0' * 64
        Reject-Overlay ('oracle-unreviewed-original-' + $name) { Check-Oracle $bad } 'Unreviewed Oracle qualification reference'
    }
    $bad = Clone $proof; $null = $bad.oraclePurgeConfiguration.Remove('httpConfiguration')
    Reject-Overlay 'oracle-missing-original-phase' { Check-Oracle $bad } 'Four original Oracle qualification references'
    $bad = Clone $proof; $bad.toolingEvidence = @()
    Reject-Overlay 'logging-files-require-tooling-execution' { Check-Oracle $bad } 'Changed tooling lacks separately pinned execution coverage'
    foreach ($path in $loggingPaths) {
        if ((Get-K8sOverlayClassification $path) -cne 'execution-tooling') { throw 'Logging file is not execution tooling.' }
        $bad = Clone $proof; $bad.toolingEvidence[0].paths = @($loggingPaths | Where-Object { $_ -cne $path })
        Reject-Overlay ('logging-file-without-mapped-control-' + $path) { Check-Oracle $bad } 'Changed tooling lacks separately pinned execution coverage'
        $partial = @($lines | Where-Object { !$_.EndsWith('  ' + $path, [StringComparison]::Ordinal) })
        Reject-Overlay ('logging-file-missing-from-control-source-' + $path) { Check-Oracle -ControlLines $partial } 'different non-documentation source bytes'
    }
    $fixtureChecks.Add('two-logging-files-are-execution-tooling-not-runtime-configuration')
}
if ($OracleConfigurationControlsOnly) {
    Test-OracleConfigurationControls
    @{syntheticOnly=$true; actualKubernetesAcceptance=$false; result='PASS'; checks=$fixtureChecks} |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
    Write-Host "ORACLE_CONFIGURATION_SYNTHETIC_CONTROLS_PASS=$($fixtureChecks.Count); NOT_RUNTIME_ACCEPTANCE"
    return
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
Test-OracleConfigurationControls
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
