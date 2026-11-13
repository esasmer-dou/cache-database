#requires -Version 7.3
# Function-only helper. Callers load the existing acceptance guards from their trusted checkout.
function Import-K8sGuardFunctions([string]$Path) {
    $tokens = $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'Trusted guard source does not parse.' }
    foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
        $definition.Extent.Text
    }
}

function Get-K8sOverlayClassification([string]$Path, $Policy = $null, [string]$AfterSha256 = '') {
    $null = Resolve-K8sMember ([IO.Path]::GetTempPath()) $Path
    if ($Path -cmatch '^(README|CHANGELOG|DOCUMENTATION_MAP|PRODUCTION_GA_CRITERIA)\.md$' -or
        $Path -cmatch '^tr/(README|DOKUMAN_HARITASI)\.md$' -or
        $Path -cmatch '^(tr/)?docs/[A-Za-z0-9_./-]+\.md$' -or
        $Path -cin @('.github/actions/cachedb-v019-performance/README.md','tools/ci/v019-sample-runtime-evidence.md') -or
        $Path -cmatch '^sample-cache-database-(postgresql|mssql|oracle)/(README(\.tr)?\.md|tr/README\.md)$') {
        return 'documentation'
    }
    if ($Path -cmatch '^tools/(ci|release)/[A-Za-z0-9_/-]+\.(ps1|psd1|mjs|json)$' -or
        $Path -cmatch '^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$' -or
        $Path -cmatch '^sample-cache-database-(postgresql|mssql|oracle)/\.github/workflows/consumer-build\.yml$' -or
        $Path -cin @('.github/actions/cachedb-v019-performance/action.yml',
            '.github/actions/cachedb-redis-performance/action.yml',
            'tools/ci/prepare-ematech-resource-guard.sh',
            'tools/ci/run-ematech-redis-performance.sh','tools/k8s/SampleRuntimeEvidence.java',
            'tools/k8s/run-sample-runtime-evidence.sh')) { return 'execution-tooling' }
    if ($Path -ceq 'sample-cache-database-oracle/src/purge/resources/application-purge.yml' -and
        $Policy -is [Collections.IDictionary] -and $Policy.Contains('ReviewedOraclePurgeConfiguration') -and
        $AfterSha256 -cmatch '^[a-f0-9]{64}$' -and $Policy.ReviewedOraclePurgeConfiguration.after -ceq $AfterSha256) {
        return 'scoped-runtime-configuration'
    }
    if ($Path -cin @(
            'cachedb-starter/src/test/java/com/cachedb/starter/RedisReadWiringRedisTest.java',
            'cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisReadRedirectServer.java') -and
        $Policy -is [Collections.IDictionary] -and $Policy.Contains('ReviewedTestSources') -and
        $Policy.ReviewedTestSources -is [Collections.IDictionary]) {
        Assert-K8sPin $AfterSha256
        foreach ($manifestPin in $Policy.ReviewedTestSources.Keys) {
            $reviewed = $Policy.ReviewedTestSources[$manifestPin]
            if ($reviewed -is [Collections.IDictionary] -and $reviewed.Contains($Path) -and
                $reviewed[$Path] -ceq $AfterSha256) {
                Assert-K8sPin $manifestPin
                return 'reviewed-test-fixture'
            }
        }
    }
    throw 'Only explicitly pinned documentation, CI/release tooling and reviewed test fixtures are eligible.'
}

function Assert-K8sOraclePurgeConfiguration($Proof, [string]$Root, $Policy) {
    $review = $Policy.ReviewedOraclePurgeConfiguration
    $changes = @($Proof.changes | Where-Object classification -CEQ 'scoped-runtime-configuration')
    if ($changes.Count -ne 1 -or $changes[0].path -cne 'sample-cache-database-oracle/src/purge/resources/application-purge.yml' -or
        $changes[0].beforeSha256 -cne $review.before -or $changes[0].afterSha256 -cne $review.after -or
        $Proof.sourceManifestSha256 -cne $review.source -or $Proof.sdkManifestSha256 -cne $review.sdk) {
        throw 'Oracle purge configuration is not the exact independently qualified source/SDK change.'
    }
    $refs = $Proof.oraclePurgeConfiguration
    if ($refs -isnot [Collections.IDictionary] -or $refs.Count -ne 4) { throw 'Four original Oracle qualification references required.' }
    foreach ($name in @('scopedReceipt','nativeProcess','testsConfiguration','httpConfiguration')) {
        $ref = $refs[$name]
        if ($ref -isnot [Collections.IDictionary] -or $ref.Count -ne 2 -or $ref.sha256 -cne $review[$name]) {
            throw 'Unreviewed Oracle qualification reference.'
        }
        $record = Read-K8sJson (Assert-K8sSealedReference $ref @{files=$Proof.files} $Root 2MB)
        switch ($name) {
            scopedReceipt {
                if ($record.kind -cne 'cachedb-oracle-tenant-purge-scoped-runtime' -or $record.execution.runnerPodUid -cne $review.pod -or
                    $record.sdk.manifest.sha256 -cne $review.sdk -or $record.sdk.sourceManifestSha256 -cne $review.source -or
                    $record.cells.Count -ne 1 -or $record.cells[0].provider -cne 'oracle' -or $record.cells[0].lane -cne 'tenant-purge') {
                    throw 'Oracle qualification does not identify the original scoped runtime.'
                }
            }
            nativeProcess {
                if ($record.podUid -cne $review.pod -or $record.exitCode -ne 0 -or $record.teeExit -ne 0 -or
                    $record.timedOut -ne $false -or $record.stdout.bytes -le 0) { throw 'Unsuccessful Oracle native process.' }
            }
            default {
                $phase = if ($name -ceq 'testsConfiguration') { 'tests' } else { 'http' }
                if ($record.phase -cne $phase -or $record.readTimeoutMs -ne 15000 -or $record.connectTimeoutMsUnchanged -ne 5000 -or
                    $record.guardStatementTimeoutSecondsUnchanged -ne 2) { throw 'Different Oracle configuration used in the original phase.' }
            }
        }
    }
}

function Read-K8sExactManifest([string]$Path) {
    $result = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $manifest = Read-K8sManifest $Path
    foreach ($path in $manifest.Keys) { $result.Add($path, $manifest[$path]) }
    return ,$result
}

function Assert-K8sToolingQualification($Proof, [string]$Root, $Target, $Policy) {
    $sealed = @{files=$Proof.files}
    $required = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($change in $Proof.changes) {
        if ($change.classification -ceq 'execution-tooling') { $null = $required.Add($change.path) }
    }
    $loggerDelta = $required.Contains('tools/k8s/SampleRuntimeEvidence.java') -or $required.Contains('tools/k8s/run-sample-runtime-evidence.sh')
    $covered = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $identities = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if ($Proof.toolingEvidence -isnot [array] -or $Proof.toolingEvidence.Count -gt 128) { throw 'Bounded explicit tooling evidence required.' }
    foreach ($binding in $Proof.toolingEvidence) {
        if ($binding -isnot [Collections.IDictionary] -or $binding.Count -ne 3 -or
            $binding.obligation -cnotmatch '^control/[0-9]{2}$' -or
            $binding.paths -isnot [array] -or !$binding.paths.Count -or $binding.paths.Count -gt 128) {
            throw 'Explicit control-to-delta mapping required; unsupported mappings remain pending.'
        }
        $index = [int]$binding.obligation.Split('/')[1]
        $matrixCommand = 'node ./tools/ci/test-v019-sample-matrix.mjs'
        if ($index -ge $Policy.Controls.Count -or
            ($Policy.Controls[$index] -cnotlike './tools/ci/*' -and $Policy.Controls[$index] -cne $matrixCommand)) {
            throw 'Only declared tooling controls can qualify tooling deltas.'
        }
        $record = Read-K8sJson (Assert-K8sSealedReference $binding.receipt $sealed $Root)
        if ($record.schema -cne 'cachedb.k8s-tooling-control/v1' -or $record.Count -ne 5 -or
            $record.kind -cne 'TOOLING_ONLY_NOT_PRODUCT_RUNTIME_COVERAGE') { throw 'Separate tooling execution evidence required.' }
        $execution = $record.execution; $run = $record.run
        if ($execution.Count -ne 9 -or !$execution.id -or $execution.command -cne $Policy.Controls[$index] -or
            $execution.workingDirectory -cne '.' -or $run.Count -ne 6 -or $run.cluster -cne 'EmaTech' -or
            $run.namespace -cnotmatch '^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$' -or
            $run.podUid -cnotmatch '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$' -or
            $execution.podUid -cne $run.podUid -or !$identities.Add($run.podUid + '/' + $execution.id)) { throw 'Tooling execution identity/command differs.' }
        $created = ConvertTo-K8sTimestamp $run.createdAt; $cutoff = ConvertTo-K8sTimestamp $run.originalCutoff
        $finished = ConvertTo-K8sTimestamp $run.finishedAt
        $start = ConvertTo-K8sTimestamp $execution.startedAt; $end = ConvertTo-K8sTimestamp $execution.finishedAt
        if ($cutoff -le $created -or ($cutoff-$created).TotalSeconds -gt 7200 -or $finished -lt $created -or
            $finished -gt $cutoff -or $start -lt $created -or $end -le $start -or $end -gt $finished) { throw 'Tooling original interval/cutoff differs.' }
        foreach ($name in @('log','exitFile')) {
            $entry = @($Proof.files | Where-Object { $_.path -ceq $execution[$name] })
            if ($entry.Count -ne 1 -or $execution.log -ceq $execution.exitFile) { throw 'Separate sealed tooling log/exit required.' }
            $limit = if ($name -ceq 'log') { 128MB } else { 16 }
            $file = Assert-K8sSealedReference @{path=$entry[0].path; sha256=$entry[0].sha256} $sealed $Root $limit
            if ((Get-Item -LiteralPath $file).Length -le 0 -or
                ($name -ceq 'exitFile' -and [IO.File]::ReadAllText($file).Trim() -cne '0')) { throw 'Tooling control did not succeed.' }
        }
        $controlManifest = Assert-K8sSealedReference $record.sourceManifest $sealed $Root 4MB
        Assert-K8sSnapshotIdentity $execution.sourceIdentity
        if ($execution.sourceIdentity.manifestSha256 -cne $record.sourceManifest.sha256) { throw 'Tooling source identity differs from its own manifest.' }
        $controlSource = Read-K8sExactManifest $controlManifest
        # Tooling controls may precede final documentation, never final executable/build bytes.
        foreach ($path in @($Target.Keys) + @($controlSource.Keys)) {
            $documentation = $false
            try { $documentation = (Get-K8sOverlayClassification $path) -ceq 'documentation' } catch { }
            if (!$documentation -and (!$Target.ContainsKey($path) -or !$controlSource.ContainsKey($path) -or
                $Target[$path] -cne $controlSource[$path])) { throw 'Tooling controls executed different non-documentation source bytes.' }
        }
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($path in $binding.paths) {
            if (!$names.Add($path) -or !$required.Contains($path)) { throw 'Tooling evidence mapping is duplicate or unrelated.' }
            $matrixSurface = $path -cin @('tools/k8s/SampleRuntimeEvidence.java','tools/k8s/run-sample-runtime-evidence.sh') -or
                ($path -ceq 'tools/ci/test-v019-sample-matrix.mjs' -and ($loggerDelta -or $Policy.Controls[$index] -ceq $matrixCommand))
            if ($matrixSurface -ne ($Policy.Controls[$index] -ceq $matrixCommand)) {
                throw 'Sample logger and its matrix guard must be qualified by their exact matrix control.'
            }
            $null = $covered.Add($path)
        }
    }
    if (!$required.SetEquals($covered)) { throw 'Changed tooling lacks separately pinned execution coverage.' }
}

function Assert-K8sSourceEquivalence(
    [string]$ProofPath, [string]$ProofPin, [string]$OriginalManifest,
    [string]$SourceRoot, [string]$SourceSha, [string]$TargetSha, [string]$SdkPin,
    $SnapshotIdentity = $null, [string]$OriginalReceiptSha256 = '', $Policy = $null
) {
    foreach ($pin in @($ProofPin, $SdkPin)) { Assert-K8sPin $pin }
    $snapshot = $null -ne $SnapshotIdentity
    if ($TargetSha -cnotmatch '^[a-f0-9]{40}$' -or
        (!$snapshot -and $SourceSha -cnotmatch '^[a-f0-9]{40}$') -or ($snapshot -and $SourceSha)) {
        throw 'Exact execution and final release source identities are required.'
    }
    Assert-PrivateOwnedCheckout $SourceRoot $TargetSha
    if ((Get-FileHash -LiteralPath $ProofPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ProofPin) {
        throw 'Source equivalence is not the independently approved proof.'
    }
    $proof = Read-K8sJson $ProofPath
    $scoped = $snapshot -and $proof.schema -ceq 'cachedb.k8s-source-equivalence/v3'
    if ($snapshot) {
        Assert-K8sSnapshotIdentity $SnapshotIdentity
        Assert-K8sSnapshotIdentity $proof.sourceIdentity
        Assert-K8sPin $OriginalReceiptSha256
        if ($null -eq $Policy -or (!$scoped -and $proof.schema -cne 'cachedb.k8s-source-equivalence/v2') -or
            $proof.Count -ne (11 + [int]$scoped) -or
            $proof.ContainsKey('sourceSha') -or $proof.sourceIdentity.manifestSha256 -cne $SnapshotIdentity.manifestSha256 -or
            $proof.originalReceiptSha256 -cne $OriginalReceiptSha256 -or
            $proof.sourceManifestSha256 -cne $SnapshotIdentity.manifestSha256 -or
            [IO.Path]::GetFileName($ProofPath) -cne 'source-equivalence.json') { throw 'Snapshot qualification identity/receipt differs.' }
        $proofRoot = Split-Path -Parent $ProofPath
        $proofEntry = @{path='source-equivalence.json'; sha256=$ProofPin; bytes=(Get-Item -LiteralPath $ProofPath).Length}
        if ($proof.files -isnot [array] -or @($proof.files | Where-Object { $_.path -ieq $proofEntry.path }).Count) { throw 'Separate sealed qualification file roster required.' }
        $null = Assert-K8sInventory @{files=@($proof.files) + @($proofEntry)} $proofRoot
    }
    if ((!$snapshot -and ($proof.schema -cne 'cachedb.k8s-source-equivalence/v1' -or $proof.sourceSha -cne $SourceSha)) -or
        $proof.kind -cne $(if ($scoped) { 'SEPARATELY_QUALIFIED_ORACLE_PURGE_CONFIGURATION' } else { 'DOCUMENTATION_TOOLING_ONLY_NOT_RUNTIME_ACCEPTANCE' }) -or
        $proof.targetSha -cne $TargetSha -or
        $proof.sdkManifestSha256 -cne $SdkPin -or
        $proof.sourceManifestSha256 -cne (Get-FileHash -LiteralPath $OriginalManifest).Hash.ToLowerInvariant()) {
        throw 'Source equivalence authority differs.'
    }
    $original = Read-K8sManifest $OriginalManifest
    $before = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($path in $original.Keys) { $before.Add($path, $original[$path]) }
    $after = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $caseNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $tracked = @((Invoke-PrivateOwnedGit $SourceRoot @('ls-files')) -split '\r?\n')
    if (!$tracked.Count -or $tracked.Count -gt 20000) { throw 'Bounded final source inventory required.' }
    foreach ($path in $tracked) {
        $file = Get-Item -LiteralPath (Resolve-K8sMember $SourceRoot $path) -Force
        if (!$caseNames.Add($path) -or $file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            !$after.TryAdd($path, (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant())) {
            throw 'Final source is not an unambiguous regular file inventory.'
        }
    }
    $changes = @($proof.changes)
    if ($scoped) { Assert-K8sOraclePurgeConfiguration $proof $proofRoot $Policy }
    elseif (@($changes | Where-Object classification -CEQ 'scoped-runtime-configuration').Count) {
        throw 'Oracle configuration requires separate runtime qualification, not a tooling-only proof.'
    }
    if (!$changes.Count -or $changes.Count -gt 128) { throw 'Explicit bounded source deltas are required.' }
    $approved = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $classificationPolicy = if ($snapshot) { $Policy } else { $null }
    foreach ($change in $changes) {
        if (!$approved.Add($change.path) -or
            (Get-K8sOverlayClassification $change.path $classificationPolicy $change.afterSha256) -cne $change.classification -or
            !$after.ContainsKey($change.path)) { throw 'Source delta is duplicate, unclassified, or deleted.' }
        $old = if ($before.ContainsKey($change.path)) { $before[$change.path] } else { $null }
        if ($change.classification -ceq 'reviewed-test-fixture' -and $null -eq $old) {
            throw 'Reviewed test fixtures must already exist in the original source inventory.'
        }
        Assert-K8sPin $change.afterSha256
        if ($null -ne $old) { Assert-K8sPin $change.beforeSha256 }
        if ($change.beforeSha256 -cne $old -or $change.afterSha256 -cne $after[$change.path] -or
            $old -ceq $change.afterSha256) { throw 'Declared source delta is not the exact byte change.' }
    }
    foreach ($path in $before.Keys) {
        if (!$after.ContainsKey($path)) { throw 'Source removals are not eligible.' }
        if ($before[$path] -cne $after[$path] -and !$approved.Contains($path)) {
            throw 'An unapproved source change is present.'
        }
    }
    foreach ($path in $after.Keys) {
        if (!$before.ContainsKey($path) -and !$approved.Contains($path)) { throw 'An unapproved source addition is present.' }
    }
    if ($snapshot) {
        $targetManifest = Assert-K8sSealedReference $proof.targetManifest @{files=$proof.files} $proofRoot 4MB
        $target = Read-K8sExactManifest $targetManifest
        if ($target.Count -ne $after.Count) { throw 'Full final source manifest roster differs.' }
        foreach ($path in $after.Keys) {
            if (!$target.ContainsKey($path) -or $target[$path] -cne $after[$path]) { throw 'Full final source manifest bytes differ.' }
        }
        Assert-K8sToolingQualification $proof $proofRoot $after $Policy
    }
    # SDK sources/build inputs remain identical; any scoped configuration delta has independent runtime proof.
    return $proof
}
