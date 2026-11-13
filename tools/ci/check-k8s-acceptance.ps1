#requires -Version 7.3
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SummaryPath = 'target/k8s-acceptance-verification.md'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-K8sPin([string]$Value) {
    if ($Value -cnotmatch '^[a-f0-9]{64}$') { throw 'A lowercase SHA256 authority is required.' }
}

function Resolve-K8sMember([string]$Root, [string]$Member) {
    if (!$Member -or $Member.Length -gt 1024 -or $Member.StartsWith('/') -or $Member -match '[\\:\x00-\x1f\x7f]' -or
        @($Member.Split('/') | Where-Object { !$_ -or $_ -in @('.', '..') -or $_ -cne $_.Trim() -or $_.EndsWith('.') }).Count) {
        throw 'Unsafe evidence member.'
    }
    $base = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolved = [IO.Path]::GetFullPath((Join-Path $Root $Member))
    if (!$resolved.StartsWith($base, [StringComparison]::Ordinal)) { throw 'Evidence member escaped its root.' }
    return $resolved
}

function Read-K8sJson([string]$Path, [long]$MaximumBytes = 1MB) {
    $file = Get-Item -LiteralPath $Path -Force
    if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt $MaximumBytes -or
        ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Invalid bounded evidence file.' }
    return ([IO.File]::ReadAllText($file.FullName, [Text.UTF8Encoding]::new($false, $true)) | ConvertFrom-Json -AsHashtable -Depth 32)
}

function Assert-K8sInventory($Receipt, [string]$Root) {
    $entries = @($Receipt.files)
    if (!$entries.Count -or $entries.Count -gt 19998) { throw 'Evidence inventory is missing or oversized.' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $bytes = 0L
    foreach ($entry in $entries) {
        Assert-K8sPin $entry.sha256
        if (!$names.Add($entry.path) -or $entry.path -ceq 'receipt.json' -or
            $entry.bytes -isnot [long] -and $entry.bytes -isnot [int]) { throw 'Invalid evidence inventory entry.' }
        $file = Get-Item -LiteralPath (Resolve-K8sMember $Root $entry.path) -Force
        $bytes += $file.Length
        if ($file.PSIsContainer -or $file.Length -ne $entry.bytes -or $file.Length -gt 128MB -or $bytes -gt 2GB -or
            ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) {
            throw 'Sealed evidence bytes differ.'
        }
    }
    $actual = @(Get-ChildItem -LiteralPath $Root -Force -Recurse -File | Where-Object {
        [IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\', '/') -cne 'receipt.json'
    })
    if ($actual.Count -ne $names.Count) { throw 'Unsealed evidence files are forbidden.' }
    foreach ($file in $actual) {
        if (!$names.Contains([IO.Path]::GetRelativePath($Root, $file.FullName).Replace('\', '/'))) { throw 'Unsealed evidence member.' }
    }
    return $names
}

function Read-K8sManifest([string]$Path, [int]$MaximumFiles = 20000) {
    $file = Get-Item -LiteralPath $Path -Force
    if ($file.Length -le 0 -or $file.Length -gt 4MB) { throw 'Invalid byte manifest.' }
    $entries = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in [IO.File]::ReadAllLines($file.FullName)) {
        $match = [regex]::Match($line, '^([a-f0-9]{64})  (.+)$')
        if (!$match.Success -or !$entries.TryAdd($match.Groups[2].Value, $match.Groups[1].Value) -or
            $entries.Count -gt $MaximumFiles) { throw 'Invalid or ambiguous byte manifest.' }
        $null = Resolve-K8sMember ([IO.Path]::GetTempPath()) $match.Groups[2].Value
    }
    return ,$entries
}

function ConvertTo-K8sTimestamp([object]$Value) {
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { throw 'Evidence timestamp has no timezone.' }
        return [DateTimeOffset]::new($Value)
    }
    if ($Value -isnot [string] -or
        $Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$') {
        throw 'Evidence timestamp must have an explicit ISO8601 timezone.'
    }
    return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None)
}

function Assert-K8sSnapshotIdentity($Identity) {
    if ($Identity -isnot [Collections.IDictionary] -or $Identity.Count -ne 2 -or
        !$Identity.Contains('kind') -or !$Identity.Contains('manifestSha256') -or
        $Identity.kind -cne 'SOURCE_SNAPSHOT') { throw 'Explicit manifest-only source identity required.' }
    Assert-K8sPin $Identity.manifestSha256
}

function Get-K8sSourceIdentity($Receipt) {
    if ($Receipt.schema -cin @('cachedb.k8s-acceptance/v2', 'cachedb.k8s-acceptance/v3')) {
        if ($Receipt.source.ContainsKey('sha')) { throw 'Snapshot source must not be relabelled with a Git SHA.' }
        Assert-K8sSnapshotIdentity $Receipt.source.identity
        return $Receipt.source.identity
    }
    if ($Receipt.schema -cne 'cachedb.k8s-acceptance/v1' -or $Receipt.source.ContainsKey('identity') -or
        $Receipt.source.sha -cnotmatch '^[a-f0-9]{40}$') { throw 'Unambiguous original source identity required.' }
    return @{kind='GIT_COMMIT'; sha=$Receipt.source.sha}
}

function Assert-K8sExecutionSource($Execution, $Receipt) {
    $identity = Get-K8sSourceIdentity $Receipt
    if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
        if ($Execution.ContainsKey('sourceSha')) { throw 'Snapshot execution cannot claim a Git source SHA.' }
        Assert-K8sSnapshotIdentity $Execution.sourceIdentity
        if ($Execution.sourceIdentity.manifestSha256 -cne $identity.manifestSha256) {
            $qualified = $Receipt.schema -ceq 'cachedb.k8s-acceptance/v3' -and
                @($Receipt.executionSources | Where-Object {
                    $_.manifest.sha256 -ceq $Execution.sourceIdentity.manifestSha256
                }).Count -eq 1
            if (!$qualified) { throw 'Foreign execution snapshot.' }
        }
    } elseif ($Execution.ContainsKey('sourceIdentity') -or $Execution.sourceSha -cne $identity.sha) {
        throw 'Foreign execution source commit.'
    }
}

function Assert-K8sHeader($Receipt, [hashtable]$Authority, $Policy) {
    $identity = Get-K8sSourceIdentity $Receipt
    if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
        if ($Receipt.schema -cnotin @($Policy.SnapshotSchema, 'cachedb.k8s-acceptance/v3') -or $Authority.ContainsKey('sha')) { throw 'Snapshot authority must remain distinct from final Git authority.' }
        Assert-K8sSnapshotIdentity $Authority.sourceIdentity
        if ($identity.manifestSha256 -cne $Authority.sourceIdentity.manifestSha256) { throw 'Snapshot authority differs.' }
    } elseif ($Receipt.source.sha -cne $Authority.sha) { throw 'Acceptance source authority differs.' }
    if ($Receipt.kind -cne $Policy.Kind -or
        $Receipt.source.repository -cne $Authority.publicRepository -or
        [string]$Receipt.source.repositoryId -cne $Authority.publicRepositoryId -or
        $Receipt.sdk.manifestSha256 -cne $Authority.sdkPin -or
        [string]$Receipt.producer.runId -cne $Authority.runId -or
        [string]$Receipt.producer.runAttempt -cne $Authority.runAttempt -or
        $Receipt.producer.workflowPath -cne $Authority.workflowPath -or
        [string]$Receipt.producer.repositoryId -cne $Authority.privateRepositoryId) { throw 'Acceptance header/authority mismatch.' }
    $runs = @($Receipt.runs)
    if (!$runs.Count -or $runs.Count -gt 64) { throw 'Bounded Kubernetes authority is required.' }
    $uids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($run in $runs) {
        $created = ConvertTo-K8sTimestamp $run.createdAt
        $cutoff = ConvertTo-K8sTimestamp $run.originalCutoff
        $finished = ConvertTo-K8sTimestamp $run.finishedAt
        if ($run.cluster -cne 'EmaTech' -or $run.namespace -cnotmatch '^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$' -or
            $run.podUid -cnotmatch '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$' -or !$uids.Add($run.podUid) -or
            $cutoff -le $created -or ($cutoff - $created).TotalSeconds -gt 7200 -or $finished -lt $created -or $finished -gt $cutoff) {
            throw 'Invalid Kubernetes run identity or original deadline.'
        }
    }
    $executions = @($Receipt.executions)
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if (!$executions.Count -or $executions.Count -gt 256) { throw 'Bounded execution evidence is required.' }
    foreach ($execution in $executions) {
        if (!$execution.id -or !$ids.Add($execution.id)) { throw 'Duplicate execution obligation.' }
        $actual = Get-K8sExecution $Receipt $execution.id
        if (Test-K8sOriginalCaseObligation $actual $Receipt) { continue }
        Assert-K8sExecutionSource $actual $Receipt
        if ($actual.sdkManifestSha256 -cne $Authority.sdkPin) { throw 'Foreign execution scope.' }
    }
    if ($Receipt.schema -ceq 'cachedb.k8s-acceptance/v3') {
        Assert-K8sOriginalCaseHeader $Receipt $Policy
    }
}

function Assert-K8sExecution($Execution, $Receipt, [string]$Root, [int]$ExpectedExit = 0) {
    if (Test-K8sOriginalCaseObligation $Execution $Receipt) {
        throw 'An original-case obligation is not a successful physical process.'
    }
    Assert-K8sExecutionSource $Execution $Receipt
    $runs = @($Receipt.runs | Where-Object { $_.podUid -ceq $Execution.podUid })
    if ($runs.Count -ne 1 -or
        $Execution.sdkManifestSha256 -cne $Receipt.sdk.manifestSha256) { throw 'Execution/source/SDK authority differs.' }
    $start = ConvertTo-K8sTimestamp $Execution.startedAt
    $end = ConvertTo-K8sTimestamp $Execution.finishedAt
    $created = ConvertTo-K8sTimestamp $runs[0].createdAt
    $finished = ConvertTo-K8sTimestamp $runs[0].finishedAt
    $cutoff = ConvertTo-K8sTimestamp $runs[0].originalCutoff
    if ($start -lt $created -or $end -lt $start -or $end -gt $finished -or $end -gt $cutoff) {
        throw 'Execution exceeded its Kubernetes authority.'
    }
    $exitPath = Resolve-K8sMember $Root $Execution.exitFile
    $logPath = Resolve-K8sMember $Root $Execution.log
    if ((Get-Item -LiteralPath $exitPath -Force).Length -gt 16 -or
        [IO.File]::ReadAllText($exitPath).Trim() -cne [string]$ExpectedExit -or
        (Get-Item -LiteralPath $logPath -Force).Length -eq 0) { throw 'Actual process exit/log evidence is absent or failed.' }
}

function Get-K8sExecution($Receipt, [string]$Id) {
    $matches = @($Receipt.executions | Where-Object { $_.id -ceq $Id })
    if ($matches.Count -ne 1) { throw 'Required execution is absent or duplicated.' }
    $execution = $matches[0]
    if (!$execution.ContainsKey('executionRef')) { return $execution }
    # Only this obligation may reuse a physical execution; references cannot carry overrides.
    if ($Id -cne 'control/17' -or $execution.executionRef -cne 'sdk-build' -or $execution.Count -ne 3 -or
        @($execution.Keys | Where-Object { $_ -cnotin @('id', 'executionRef', 'canonicalCommand') }).Count -or
        $execution.canonicalCommand -isnot [string] -or !$execution.canonicalCommand) {
        throw 'Only a reference-only control/17 -> sdk-build binding is permitted.'
    }
    $target = @($Receipt.executions | Where-Object { $_.id -ceq 'sdk-build' })
    if ($target.Count -ne 1 -or $target[0].ContainsKey('executionRef')) { throw 'Execution reference chains and cycles are forbidden.' }
    return $target[0]
}

function Assert-K8sExactStrings($Actual, $Expected) {
    if ($Actual -isnot [array] -or $Actual.Count -ne $Expected.Count) { throw 'Exact ordered invocation values required.' }
    for ($i = 0; $i -lt $Expected.Count; $i++) {
        if ($Actual[$i] -isnot [string] -or $Actual[$i] -cne $Expected[$i]) { throw 'Unapproved invocation value.' }
    }
}

function Test-K8sOriginalCaseObligation($Execution, $Receipt) {
    if (!$Execution.Contains('kind') -or $Execution.kind -cne 'ORIGINAL_CASES') { return $false }
    if ($Receipt.schema -cne 'cachedb.k8s-acceptance/v3' -or
        $Execution.id -cnotin @('standalone', 'cluster', 'provider/postgresql', 'provider/mssql', 'provider/oracle') -or
        $Execution.Count -ne 4 -or @($Execution.Keys | Where-Object {
            $_ -cnotin @('id', 'kind', 'physicalExecutionIds', 'originalCases')
        }).Count -or $Execution.physicalExecutionIds -isnot [array] -or
        !$Execution.physicalExecutionIds.Count -or $Execution.physicalExecutionIds.Count -gt 128 -or
        @($Execution.physicalExecutionIds | Sort-Object -Unique).Count -ne $Execution.physicalExecutionIds.Count) {
        throw 'An exact, bounded original-case binding is required; synthetic process fields are forbidden.'
    }
    foreach ($id in $Execution.physicalExecutionIds) {
        if ($id -isnot [string] -or $id -cnotmatch '^[A-Za-z0-9][A-Za-z0-9/_-]{0,159}$') {
            throw 'Invalid original physical execution identity.'
        }
    }
    return $true
}

function Assert-K8sOriginalCaseHeader($Receipt, $Policy) {
    if ($Receipt.physicalExecutions -isnot [array] -or !$Receipt.physicalExecutions.Count -or
        $Receipt.physicalExecutions.Count -gt 128 -or $Receipt.executionSources -isnot [array] -or
        $Receipt.executionSources.Count -gt 8) { throw 'Bounded explicit original execution/source inventories are required.' }
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $owners = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $originalIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($execution in $Receipt.physicalExecutions) {
        if ($execution.id -cnotmatch '^[A-Za-z0-9][A-Za-z0-9/_-]{0,159}$' -or !$ids.Add($execution.id) -or
            @($Receipt.executions | Where-Object { $_.id -ieq $execution.id }).Count -or
            $execution.Contains('executionRef') -or $execution.Contains('kind') -or
            ($execution.observedExit -isnot [int] -and $execution.observedExit -isnot [long]) -or
            $execution.observedExit -lt 0 -or $execution.observedExit -gt 255 -or
            $execution.originalPhysicalExecutionId -cnotmatch '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$' -or
            !$originalIds.Add($execution.originalPhysicalExecutionId)) {
            throw 'Original execution identities or observed native exits are invalid.'
        }
        Assert-K8sExecutionSource $execution $Receipt
        if ($execution.sdkManifestSha256 -cne $Receipt.sdk.manifestSha256) { throw 'Original execution SDK differs.' }
    }
    foreach ($execution in $Receipt.executions) {
        if (!(Test-K8sOriginalCaseObligation $execution $Receipt)) { continue }
        foreach ($id in $execution.physicalExecutionIds) {
            if (!$ids.Contains($id)) { throw 'A physical original is missing.' }
            if (!$used.Add($id)) {
                # Cluster-only cases can close standalone skips without inventing another process.
                if ($owners[$id] -cnotin @('standalone', 'cluster') -or
                    $execution.id -cnotin @('standalone', 'cluster') -or $owners[$id] -ceq $execution.id) {
                    throw 'Only standalone and cluster may reference the same original process.'
                }
                $original = @($Receipt.physicalExecutions | Where-Object { $_.id -ceq $id })[0]
                if ($original.workingDirectory -cne '/workspace/source' -or
                    $original.command -cnotmatch '(?:^| )-Dcachedb\.redis\.cluster=true(?: |$)') {
                    throw 'Shared originals must be actual cluster-profile processes.'
                }
            } else { $owners.Add($id, $execution.id) }
        }
    }
    if (!$ids.SetEquals($used)) { throw 'Unbound physical execution originals are forbidden.' }
    $sourceIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($source in $Receipt.executionSources) {
        Assert-K8sPin $source.manifest.sha256
        if (!$sourceIds.Add($source.manifest.sha256) -or
            $source.manifest.sha256 -ceq $Receipt.source.identity.manifestSha256 -or
            !$Policy.Contains('ReviewedTestSources') -or !$Policy.ReviewedTestSources.Contains($source.manifest.sha256)) {
            throw 'Execution test-source overlay lacks independent policy admission.'
        }
        if (!@($Receipt.physicalExecutions | Where-Object {
            $_.sourceIdentity.manifestSha256 -ceq $source.manifest.sha256
        }).Count) { throw 'Unused test-source qualification is forbidden.' }
    }
}

function Assert-K8sOriginalExecutionSources($Receipt, [string]$Root, $Policy) {
    $baseline = Read-K8sManifest (Resolve-K8sMember $Root $Receipt.source.manifest)
    foreach ($source in $Receipt.executionSources) {
        $manifest = Assert-K8sSealedReference $source.manifest $Receipt $Root 4MB
        $actual = Read-K8sManifest $manifest
        $approved = $Policy.ReviewedTestSources[$source.manifest.sha256]
        if ($actual.Count -ne $baseline.Count) { throw 'Original execution test-source inventory differs.' }
        $changes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($path in $baseline.Keys) {
            if (!$actual.ContainsKey($path)) { throw 'Original execution source removal is forbidden.' }
            if ($actual[$path] -ceq $baseline[$path]) { continue }
            if ($path -cnotmatch '/src/test/java/.+\.java$' -or !$approved.Contains($path) -or
                $actual[$path] -cne $approved[$path]) { throw 'Unreviewed original execution source delta.' }
            $null = $changes.Add($path)
        }
        if (!$changes.SetEquals(@($approved.Keys))) { throw 'Exact reviewed fixture deltas are required.' }
    }
}

function Assert-K8sOriginalCaseExecutions($Obligation, $Receipt, [string]$Root, $Policy, $ProcessFiles, $ValidatedOriginalIds = $null) {
    if ($null -eq $ValidatedOriginalIds) {
        $ValidatedOriginalIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    }
    Assert-K8sOriginalCaseHeader $Receipt $Policy
    Assert-K8sOriginalExecutionSources $Receipt $Root $Policy
    $index = Read-K8sJson (Assert-K8sSealedReference $Obligation.originalCases $Receipt $Root 4MB) 4MB
    if ($index.schemaVersion -cne 'cachedb.k8s-original-case-provenance/v1' -or $index.obligationId -cne $Obligation.id) {
        throw 'Original-case index identity differs.'
    }
    $reported = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($report in $index.reports) {
        if ($report.executionId -cnotin $Obligation.physicalExecutionIds) { throw 'Report references an unrelated physical original.' }
        $null = $reported.Add($report.executionId)
        $null = Assert-K8sSealedReference @{path=$report.path; sha256=$report.sha256} $Receipt $Root 32MB
    }
    if (!$reported.SetEquals([string[]]$Obligation.physicalExecutionIds)) { throw 'Every bound physical original must contribute its original report.' }
    foreach ($other in $Receipt.executions) {
        if ($other.id -ceq $Obligation.id -or !(Test-K8sOriginalCaseObligation $other $Receipt)) { continue }
        $shared = @($Obligation.physicalExecutionIds | Where-Object { $_ -cin $other.physicalExecutionIds })
        if (!$shared.Count) { continue }
        $otherIndex = Read-K8sJson (Assert-K8sSealedReference $other.originalCases $Receipt $Root 4MB) 4MB
        if ($otherIndex.schemaVersion -cne 'cachedb.k8s-original-case-provenance/v1' -or
            $otherIndex.obligationId -cne $other.id -or $otherIndex.reports -isnot [array]) {
            throw 'Shared original-case index identity differs.'
        }
        foreach ($id in $shared) {
            $originalReportPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            $sharedReportPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($report in @($index.reports | Where-Object { $_.executionId -ceq $id })) {
                if (!$originalReportPaths.Add($report.path + "`n" + $report.sha256)) { throw 'Duplicate shared original report.' }
            }
            foreach ($report in @($otherIndex.reports | Where-Object { $_.executionId -ceq $id })) {
                $null = Resolve-K8sMember $Root $report.path
                Assert-K8sPin $report.sha256
                if (!$sharedReportPaths.Add($report.path + "`n" + $report.sha256)) { throw 'Duplicate shared original report.' }
            }
            if (!$originalReportPaths.Count -or !$originalReportPaths.SetEquals($sharedReportPaths)) {
                throw 'Shared physical originals require identical complete report path/hash sets.'
            }
        }
    }
    foreach ($id in $Obligation.physicalExecutionIds) {
        $execution = @($Receipt.physicalExecutions | Where-Object { $_.id -ceq $id })[0]
        Assert-K8sOriginalCustody $execution $index $Receipt $Root $Policy
        $firstUse = $ValidatedOriginalIds.Add($execution.id)
        foreach ($member in @($execution.log, $execution.exitFile)) {
            if (@($Receipt.files | Where-Object { $_.path -ceq $member }).Count -ne 1 -or
                ($firstUse -and !$ProcessFiles.Add($member))) {
                throw 'Each physical original needs its own sealed process evidence.'
            }
        }
        # A failed or interrupted original stays failed; only its completed cases may contribute coverage.
        Assert-K8sExecution $execution $Receipt $Root $execution.observedExit
        $original = Read-K8sJson (Assert-K8sSealedReference $execution.originalRecord $Receipt $Root 4MB) 4MB
        $records = @($original.executions | Where-Object { $_.id -ceq $execution.originalRecordId })
        if ($records.Count -ne 1) { throw 'Captured original process record is missing or ambiguous.' }
        $record = $records[0]
        if ($record.podUid -cne $execution.podUid -or $record.sdkManifestSha256 -cne $execution.sdkManifestSha256 -or
            $record.physicalExecutionId -cne $execution.originalPhysicalExecutionId -or
            $execution.originalPhysicalExecutionId -cnotmatch '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$' -or
            $record.sourceManifestSha256 -cne $execution.sourceIdentity.manifestSha256 -or
            $record.nativeExit -ne $execution.observedExit -or
            !(ConvertTo-K8sTimestamp $record.startedUtc).Equals((ConvertTo-K8sTimestamp $execution.startedAt)) -or
            !(ConvertTo-K8sTimestamp $record.finishedUtc).Equals((ConvertTo-K8sTimestamp $execution.finishedAt)) -or
            $record.cwd -cne $execution.workingDirectory -or
            $record.executable -isnot [string] -or !$record.executable -or $record.arguments -isnot [array] -or
            $execution.command -cne ($record.executable + ' ' + ($record.arguments -join ' '))) {
            throw 'Original-case process metadata was relabelled.'
        }
        if (!$record.Contains('processLog') -or !$record.Contains('processExit')) {
            throw 'Original captured process log and native exit pins are required.'
        }
        foreach ($pair in @(@('processLog', 'log'), @('processExit', 'exitFile'))) {
            if ($record[$pair[0]].sha256 -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $execution[$pair[1]])).Hash.ToLowerInvariant()) {
                throw 'Original captured process log/exit pin differs.'
            }
        }
        if ($Obligation.id.StartsWith('provider/', [StringComparison]::Ordinal)) {
            $provider = $Obligation.id.Substring('provider/'.Length)
            if ($record.executable -cne 'mvn' -or $record.cwd -cne "/workspace/source/sample-cache-database-$provider" -or
                '-B' -cnotin $record.arguments -or
                !@($record.arguments | Where-Object { $_ -cin @('test', 'verify') }).Count -or
                @($record.arguments | Where-Object {
                    $_ -match '^-D(?:skipTests|maven\.test\.skip)(?:=(.*))?$' -and $Matches[1] -ine 'false'
                }).Count) {
                throw 'Original provider invocation is not a reviewed test execution.'
            }
        } elseif ($Obligation.id -ceq 'cluster') {
            if ($record.executable -cne 'mvn' -or $record.cwd -cne '/workspace/source' -or
                '-B' -cnotin $record.arguments -or '-Dcachedb.redis.cluster=true' -cnotin $record.arguments -or
                !@($record.arguments | Where-Object { $_ -cin @('test', 'verify') }).Count -or
                @($record.arguments | Where-Object {
                    $_ -match '^-D(?:skipTests|maven\.test\.skip)(?:=(.*))?$' -and $Matches[1] -ine 'false'
                }).Count) { throw 'Original cluster invocation is not a reviewed cluster test execution.' }
        }
    }
    foreach ($closure in $index.closures) {
        $from = @($index.reports | Where-Object { $_.path -ceq $closure.from.reportPath })
        $to = @($index.reports | Where-Object { $_.path -ceq $closure.to.reportPath })
        if ($from.Count -ne 1 -or $to.Count -ne 1 -or $from[0].executionId -ceq $to[0].executionId) {
            throw 'Each closure must bind two distinct original executions.'
        }
        $before = @($Receipt.physicalExecutions | Where-Object { $_.id -ceq $from[0].executionId })[0]
        $after = @($Receipt.physicalExecutions | Where-Object { $_.id -ceq $to[0].executionId })[0]
        if ((ConvertTo-K8sTimestamp $after.startedAt) -lt (ConvertTo-K8sTimestamp $before.finishedAt)) {
            throw 'A closure must have executed after its non-passing original completed.'
        }
    }
}

function Assert-K8sOriginalCustody($Execution, $Index, $Receipt, [string]$Root, $Policy) {
    $binding = $Execution.originalCustody
    if ($binding -is [Collections.IDictionary] -and $binding.Contains('prefixCustody')) {
        . (Join-Path $PSScriptRoot 'k8s-prefix-original-custody.ps1')
        Assert-K8sPrefixOriginalCustody $Execution $Index $Receipt $Root $Policy
        return
    }
    if ($binding -isnot [Collections.IDictionary] -or $binding.Count -ne 2 -or
        @($binding.Keys | Where-Object { $_ -cnotin @('receipt', 'prefix') }).Count -or
        $binding.prefix -isnot [string] -or !$binding.prefix -or $binding.prefix.EndsWith('/') -or
        $binding.receipt -isnot [Collections.IDictionary] -or $binding.receipt.Count -ne 2 -or
        @($binding.receipt.Keys | Where-Object { $_ -cnotin @('path', 'sha256') }).Count -or
        !$Policy.Contains('ReviewedOriginalReceipts') -or
        !$Policy.ReviewedOriginalReceipts.Contains($binding.receipt.sha256)) {
        throw 'Original custody requires an independently reviewed captured receipt.'
    }
    $null = Resolve-K8sMember $Root ($binding.prefix + '/custody-member')
    $captured = Read-K8sJson (Assert-K8sSealedReference $binding.receipt $Receipt $Root 4MB) 4MB
    $completeRecovery = $captured.Contains('schema') -and
        $captured.schema -ceq 'cachedb.selected-original-custody/v1' -and
        $captured.allSelectedOriginalsRetained -is [bool] -and $captured.allSelectedOriginalsRetained -and
        $captured.testsReexecuted -is [bool] -and !$captured.testsReexecuted -and
        $captured.fileCount -eq @($captured.files).Count
    $selectedCapture = $captured.Contains('status') -and $captured.status -ceq 'SELECTED_ORIGINALS_HASH_VERIFIED'
    if ((!$selectedCapture -and !$completeRecovery) -or
        $captured.podUid -cne $Execution.podUid -or
        $captured.podUid -cne $Policy.ReviewedOriginalReceipts[$binding.receipt.sha256] -or
        $captured.files -isnot [array] -or !$captured.files.Count -or $captured.files.Count -gt 5000) {
        throw 'Captured custody identity or bounded file inventory differs.'
    }
    $members = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $portableNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $captured.files) {
        if ($entry.name -isnot [string] -or !$portableNames.Add($entry.name) -or
            ($entry.bytes -isnot [int] -and $entry.bytes -isnot [long]) -or $entry.bytes -lt 0) {
            throw 'Captured custody inventory contains invalid or duplicate members.'
        }
        $null = Resolve-K8sMember $Root ($binding.prefix + '/' + $entry.name)
        Assert-K8sPin $entry.sha256
        $members.Add($entry.name, $entry)
    }
    # The captured inventory is the complete admitted roster, not just the indexed subset.
    # A receipt may cover several processes; only the same physical original may repeat across lanes.
    $originalReportPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $members.Keys) {
        if ($name -cmatch '(?:^|/)TEST-[^/]+\.xml$') { $null = $originalReportPaths.Add($binding.prefix + '/' + $name) }
    }
    $indexedOriginalReportPaths = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($obligation in $Receipt.executions) {
        if (!(Test-K8sOriginalCaseObligation $obligation $Receipt)) { continue }
        $ownedIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($physical in $Receipt.physicalExecutions) {
            if ($physical.id -cnotin $obligation.physicalExecutionIds -or
                $physical.originalCustody -isnot [Collections.IDictionary] -or
                !$physical.originalCustody.Contains('receipt') -or
                $physical.originalCustody.receipt.sha256 -cne $binding.receipt.sha256) { continue }
            if ($physical.originalCustody.prefix -cne $binding.prefix -or $physical.originalCustody.Contains('prefixCustody')) {
                throw 'The same ordinary original custody cannot be aliased to another prefix or custody type.'
            }
            $null = $ownedIds.Add($physical.id)
        }
        if (!$ownedIds.Count) { continue }
        $otherIndex = if ($obligation.id -ceq $Index.obligationId) { $Index } else {
            Read-K8sJson (Assert-K8sSealedReference $obligation.originalCases $Receipt $Root 4MB) 4MB
        }
        if ($otherIndex.schemaVersion -cne 'cachedb.k8s-original-case-provenance/v1' -or
            $otherIndex.obligationId -cne $obligation.id -or $otherIndex.reports -isnot [array] -or
            $otherIndex.reports.Count -gt 10000) { throw 'Invalid original custody report index.' }
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($report in $otherIndex.reports) {
            if (!$ownedIds.Contains($report.executionId)) { continue }
            if (!$originalReportPaths.Contains($report.path) -or !$seen.Add($report.path)) {
                throw 'Unmapped or duplicate ordinary original XML.'
            }
            $name = $report.path.Substring($binding.prefix.Length + 1)
            if ($report.sha256 -cne $members[$name].sha256) { throw 'Indexed original XML differs from admitted custody.' }
            if ($indexedOriginalReportPaths.ContainsKey($report.path)) {
                if ($indexedOriginalReportPaths[$report.path] -cne $report.executionId) {
                    throw 'An original XML cannot be assigned to distinct physical executions.'
                }
            } else { $indexedOriginalReportPaths.Add($report.path, $report.executionId) }
        }
    }
    if (!$originalReportPaths.Count -or !$originalReportPaths.SetEquals([string[]]@($indexedOriginalReportPaths.Keys))) {
        throw 'Every admitted original XML must be retained in the original-case indexes.'
    }
    $paths = @($Execution.originalRecord.path, $Execution.log, $Execution.exitFile) +
        @($Index.reports | Where-Object { $_.executionId -ceq $Execution.id } | ForEach-Object { $_.path })
    foreach ($member in $paths) {
        if ($member -isnot [string] -or !$member.StartsWith($binding.prefix + '/', [StringComparison]::Ordinal)) {
            throw 'Original evidence lies outside its captured custody prefix.'
        }
        $name = $member.Substring($binding.prefix.Length + 1)
        if (!$members.ContainsKey($name)) { throw 'Original evidence was not captured in the reviewed custody inventory.' }
        $original = $members[$name]
        $actual = Assert-K8sSealedReference @{path=$member;sha256=$original.sha256} $Receipt $Root 32MB
        if ((Get-Item -LiteralPath $actual).Length -ne $original.bytes) {
            throw 'Original evidence byte count differs from captured custody.'
        }
    }
}

function Assert-K8sSealedReference($Reference, $Receipt, [string]$Root, [long]$MaximumBytes = 1MB) {
    if ($Reference -isnot [System.Collections.IDictionary] -or $Reference.Count -ne 2 -or
        !$Reference.Contains('path') -or !$Reference.Contains('sha256')) { throw 'An exact sealed evidence reference is required.' }
    Assert-K8sPin $Reference.sha256
    $entry = @($Receipt.files | Where-Object { $_.path -ceq $Reference.path })
    if ($entry.Count -ne 1 -or $entry[0].sha256 -cne $Reference.sha256) { throw 'Referenced evidence is not independently sealed.' }
    $path = Resolve-K8sMember $Root $Reference.path
    $file = Get-Item -LiteralPath $path -Force
    if ($file.PSIsContainer -or $file.Length -gt $MaximumBytes -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $Reference.sha256) { throw 'Referenced evidence bytes differ.' }
    return $path
}

function Assert-K8sPinnedScriptEvidence($Record, $Receipt, [string]$Root, $Policy) {
    $contract = $Policy.OfflinePackageInvocation
    $keys = @('schema', 'evidenceMode', 'reconstructedAt', 'syscallCapture', 'environmentCapture', 'argv', 'wrapperArgv',
        'workingDirectory', 'environment', 'unsetEnvironment', 'inputPins', 'originalPins', 'origin',
        'sourceManifestSha256', 'sdkManifestSha256', 'podUid', 'startedAt', 'finishedAt', 'originalCutoff', 'logSha256', 'exitSha256')
    if ($Record.schema -cne 'cachedb.k8s-offline-package-invocation/v2' -or $Record.Count -ne $keys.Count -or
        @($Record.Keys | Where-Object { $_ -cnotin $keys }).Count -or
        $Record.evidenceMode -cne 'PINNED_EXECUTED_SCRIPT' -or $Record.syscallCapture -isnot [bool] -or $Record.syscallCapture -or
        $Record.environmentCapture -cne 'SCRIPT_DECLARED_ONLY' -or $Record.workingDirectory -cne $contract.WorkingDirectory) {
        throw 'Exact pinned-script evidence with explicit capture limitations required.'
    }
    Assert-K8sExactStrings $Record.argv $contract.Argv
    Assert-K8sExactStrings $Record.wrapperArgv $contract.WrapperArgv
    Assert-K8sExactStrings $Record.unsetEnvironment $contract.UnsetEnvironment
    if ($Record.environment -isnot [System.Collections.IDictionary] -or $Record.environment.Count -ne $contract.Environment.Count -or
        $Record.inputPins -isnot [System.Collections.IDictionary] -or $Record.inputPins.Count -ne $contract.InputSha256.Count -or
        $Record.originalPins -isnot [System.Collections.IDictionary] -or
        $Record.originalPins.Count -ne $contract.PinnedExecutedScript.OriginalEvidenceRoles.Count) {
        throw 'Exact script declarations and original evidence roster required.'
    }
    foreach ($name in $contract.Environment.Keys) {
        if (!$Record.environment.Contains($name) -or $Record.environment[$name] -isnot [string] -or
            $Record.environment[$name] -cne $contract.Environment[$name]) { throw 'Script environment declaration differs.' }
    }
    $origin = Read-K8sJson (Assert-K8sSealedReference $Record.origin $Receipt $Root)
    $roles = @($contract.InputSha256.Keys) + @($contract.PinnedExecutedScript.OriginalEvidenceRoles)
    if ($origin.schema -cne 'cachedb.k8s-pinned-script-input/v1' -or $origin.Count -ne 2 -or
        $origin.files -isnot [System.Collections.IDictionary] -or $origin.files.Count -ne $roles.Count) {
        throw 'Original evidence inventory differs.'
    }
    $paths = @{}
    $members = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($name in $roles) {
        $reference = if ($contract.InputSha256.Contains($name)) { $Record.inputPins[$name] } else { $Record.originalPins[$name] }
        $maximum = if ($name -ceq 'buildLog') { 128MB } else { 1MB }
        $paths[$name] = Assert-K8sSealedReference $reference $Receipt $Root $maximum
        $entry = $origin.files[$name]
        if (!$members.Add($reference.path) -or $entry -isnot [System.Collections.IDictionary] -or $entry.Count -ne 3 -or
            $entry.path -cne $reference.path -or $entry.sha256 -cne $reference.sha256 -or
            ($entry.bytes -isnot [long] -and $entry.bytes -isnot [int]) -or $entry.bytes -ne (Get-Item -LiteralPath $paths[$name]).Length -or
            ($contract.InputSha256.Contains($name) -and $reference.sha256 -cne $contract.InputSha256[$name])) {
            throw 'Original evidence pin, role, or byte count differs.'
        }
    }
    $pod = Read-K8sJson $paths.pod
    $authority = Read-K8sJson $paths.authority
    $build = Read-K8sJson $paths.buildReceipt
    $runners = @($pod.spec.containers | Where-Object { $_.name -ceq 'runner' })
    if ($runners.Count -ne 1) { throw 'Original runner container required.' }
    $runner = $runners[0]
    Assert-K8sExactStrings $runner.command @('bash', '-ec')
    if ($runner.args -isnot [array] -or $runner.args.Count -ne 1 -or $runner.args[0] -isnot [string] -or
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($runner.args[0]))).ToLowerInvariant() -cne
            $contract.PinnedExecutedScript.LauncherArgumentSha256 -or $runner.workingDir -cne '/workspace' -or
        ($runner.ContainsKey('envFrom') -and @($runner.envFrom).Count)) { throw 'Unapproved original script launcher.' }
    $uidEnvironment = @($runner.env | Where-Object { $_.name -ceq 'POD_UID' })
    if ($uidEnvironment.Count -ne 1 -or $uidEnvironment[0].valueFrom.fieldRef.fieldPath -cne 'metadata.uid' -or
        $pod.metadata.uid -cne $Record.podUid -or $authority.runnerPodUid -cne $Record.podUid -or
        $pod.metadata.namespace -cne $authority.namespace) { throw 'Original pod and authority identity differs.' }
    foreach ($name in @('buildExit', 'wrapperExit', 'sourcePreservationExit', 'sdkPreservationExit')) {
        if ((Get-Item -LiteralPath $paths[$name]).Length -gt 16 -or [IO.File]::ReadAllText($paths[$name]).Trim() -cne '0') {
            throw 'Original build, wrapper, or preservation evidence failed.'
        }
    }
    $start = ConvertTo-K8sTimestamp ([IO.File]::ReadAllText($paths.buildStarted).Trim())
    $finish = ConvertTo-K8sTimestamp ([IO.File]::ReadAllText($paths.buildFinished).Trim())
    $created = ConvertTo-K8sTimestamp $pod.metadata.creationTimestamp
    $cutoff = ConvertTo-K8sTimestamp $authority.originalCutoff
    if (!$start.Equals((ConvertTo-K8sTimestamp $Record.startedAt)) -or !$finish.Equals((ConvertTo-K8sTimestamp $Record.finishedAt)) -or
        !$cutoff.Equals((ConvertTo-K8sTimestamp $Record.originalCutoff)) -or $start -lt $created -or $finish -lt $start -or
        $finish -gt $cutoff -or !$created.Equals((ConvertTo-K8sTimestamp $authority.runnerCreatedAt)) -or
        ($cutoff - $created).TotalSeconds -ne [int]$authority.activeDeadlineSeconds -or
        (ConvertTo-K8sTimestamp $Record.reconstructedAt) -lt $finish) { throw 'Original interval or reconstruction timestamp differs.' }
    if ($build.status -cne 'CURRENT_SOURCE_PACKAGE_NOT_RUNTIME_QUALIFICATION' -or $build.buildCommand -cne $Policy.Controls[17] -or
        $build.runnerPodUid -cne $Record.podUid -or $build.sourceManifestSha256 -cne $Record.sourceManifestSha256 -or
        $build.sdkManifestSha256 -cne $Record.sdkManifestSha256 -or $build.files -ne $Policy.SdkFiles -or
        $build.installed -cne $false -or $build.wholeRelease -cne $false -or
        !$start.Equals((ConvertTo-K8sTimestamp $build.buildStartedAt)) -or !$finish.Equals((ConvertTo-K8sTimestamp $build.buildFinishedAt)) -or
        $Record.originalPins.sourceManifest.sha256 -cne $Record.sourceManifestSha256 -or
        $Record.originalPins.sdkManifest.sha256 -cne $Record.sdkManifestSha256 -or
        $Record.originalPins.buildLog.sha256 -cne $Record.logSha256 -or $Record.originalPins.buildExit.sha256 -cne $Record.exitSha256 -or
        (Get-Item -LiteralPath $paths.buildLog).Length -eq 0) { throw 'Original SDK build binding differs.' }
    $null = Read-K8sManifest $paths.sourceManifest
    $sdk = Read-K8sManifest $paths.sdkManifest
    if ($sdk.Count -ne $Policy.SdkFiles) { throw 'Original SDK hash roster is incomplete.' }
}

function New-K8sPinnedScriptRecord($Inventory, $Origin, $Receipt, [string]$Root, $Policy) {
    $contract = $Policy.OfflinePackageInvocation
    $original = @{}
    $inputs = @{}
    foreach ($name in @($contract.InputSha256.Keys) + @($contract.PinnedExecutedScript.OriginalEvidenceRoles)) {
        $entry = $Inventory.files[$name]
        $reference = @{path=$entry.path; sha256=$entry.sha256}
        if ($contract.InputSha256.Contains($name)) { $inputs[$name] = $reference } else { $original[$name] = $reference }
    }
    $build = Read-K8sJson (Resolve-K8sMember $Root $original.buildReceipt.path)
    $authority = Read-K8sJson (Resolve-K8sMember $Root $original.authority.path)
    # This is a reconstruction of declarations, not a historical argv or environment capture.
    $record = @{schema='cachedb.k8s-offline-package-invocation/v2'; evidenceMode='PINNED_EXECUTED_SCRIPT'
        reconstructedAt=[DateTimeOffset]::UtcNow.ToString('o'); syscallCapture=$false; environmentCapture='SCRIPT_DECLARED_ONLY'
        argv=$contract.Argv; wrapperArgv=$contract.WrapperArgv; workingDirectory=$contract.WorkingDirectory
        environment=$contract.Environment; unsetEnvironment=$contract.UnsetEnvironment; inputPins=$inputs; originalPins=$original; origin=$Origin
        sourceManifestSha256=$build.sourceManifestSha256; sdkManifestSha256=$build.sdkManifestSha256; podUid=$build.runnerPodUid
        startedAt=[IO.File]::ReadAllText((Resolve-K8sMember $Root $original.buildStarted.path)).Trim()
        finishedAt=[IO.File]::ReadAllText((Resolve-K8sMember $Root $original.buildFinished.path)).Trim()
        originalCutoff=$authority.originalCutoff; logSha256=$original.buildLog.sha256; exitSha256=$original.buildExit.sha256}
    Assert-K8sPinnedScriptEvidence $record $Receipt $Root $Policy
    return $record
}

function Assert-K8sPackageInvocation($Execution, $Receipt, [string]$Root, $Policy) {
    $contract = $Policy.OfflinePackageInvocation
    if ($Execution.id -cne 'sdk-build' -or $Execution.canonicalCommand -cne $Policy.Controls[17] -or
        $Execution.command -cne ($contract.Argv -join ' ') -or $Execution.workingDirectory -cne '.') {
        throw 'Unapproved offline package invocation.'
    }
    $reference = $Execution.invocationQualification
    $path = Assert-K8sSealedReference $reference $Receipt $Root
    $record = Read-K8sJson $path
    if ($record.schema -ceq 'cachedb.k8s-offline-package-invocation/v2') {
        Assert-K8sPinnedScriptEvidence $record $Receipt $Root $Policy
        # Approval pins ORIGINAL documents, never a reconstructed record that could not have existed historically.
        foreach ($name in @('pod', 'authority')) {
            $approved = $contract.PinnedExecutedScript.ApprovedOriginalSha256[$name]
            if (!$approved) { throw 'Pinned-script qualification pending independent original pod/authority admission.' }
            Assert-K8sPin $approved
            if ($record.originalPins[$name].sha256 -cne $approved) { throw 'Unapproved original pinned-script authority.' }
        }
        $run = @($Receipt.runs | Where-Object { $_.podUid -ceq $Execution.podUid })
        if ($run.Count -ne 1 -or $record.podUid -cne $Execution.podUid -or
            $record.sdkManifestSha256 -cne $Execution.sdkManifestSha256 -or
            $record.sourceManifestSha256 -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Receipt.source.manifest)).Hash.ToLowerInvariant() -or
            !(ConvertTo-K8sTimestamp $record.startedAt).Equals((ConvertTo-K8sTimestamp $Execution.startedAt)) -or
            !(ConvertTo-K8sTimestamp $record.finishedAt).Equals((ConvertTo-K8sTimestamp $Execution.finishedAt)) -or
            !(ConvertTo-K8sTimestamp $record.originalCutoff).Equals((ConvertTo-K8sTimestamp $run[0].originalCutoff)) -or
            $record.logSha256 -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Execution.log)).Hash.ToLowerInvariant() -or
            $record.exitSha256 -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Execution.exitFile)).Hash.ToLowerInvariant()) {
            throw 'Reconstructed declaration differs from the original execution.'
        }
        return
    }
    # An adapter cannot approve its own reconstructed capture. Original authority must be admitted separately.
    if ((Get-K8sSourceIdentity $Receipt).kind -ceq 'SOURCE_SNAPSHOT') { throw 'Git capture-v1 cannot qualify a manifest-only execution.' }
    if (!$contract.ApprovedRecordSha256) { throw 'Offline package qualification pending original invocation/environment evidence authority.' }
    Assert-K8sPin $contract.ApprovedRecordSha256
    if ($reference.sha256 -cne $contract.ApprovedRecordSha256) { throw 'Offline invocation record lacks approved original authority.' }
    $keys = @('schema', 'argv', 'wrapperArgv', 'workingDirectory', 'environment', 'unsetEnvironment', 'inputPins',
        'sourceSha', 'sourceManifestSha256', 'sdkManifestSha256', 'podUid', 'startedAt', 'finishedAt', 'originalCutoff',
        'logSha256', 'exitSha256')
    if ($record.schema -cne 'cachedb.k8s-offline-package-invocation/v1' -or $record.Count -ne $keys.Count -or
        @($record.Keys | Where-Object { $_ -cnotin $keys }).Count -or $record.workingDirectory -cne $contract.WorkingDirectory -or
        $record.sourceSha -cne $Execution.sourceSha -or $record.sdkManifestSha256 -cne $Execution.sdkManifestSha256 -or
        $record.podUid -cne $Execution.podUid -or $record.sourceManifestSha256 -cne
            (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Receipt.source.manifest)).Hash.ToLowerInvariant()) {
        throw 'Offline invocation identity differs from the original execution.'
    }
    Assert-K8sExactStrings $record.argv $contract.Argv
    Assert-K8sExactStrings $record.wrapperArgv $contract.WrapperArgv
    Assert-K8sExactStrings $record.unsetEnvironment $contract.UnsetEnvironment
    if ($record.environment -isnot [System.Collections.IDictionary] -or $record.environment.Count -ne $contract.Environment.Count -or
        $record.inputPins -isnot [System.Collections.IDictionary] -or $record.inputPins.Count -ne $contract.InputSha256.Count) {
        throw 'Complete exact offline environment and frozen input pins required.'
    }
    foreach ($name in $contract.Environment.Keys) {
        if (!$record.environment.Contains($name) -or $record.environment[$name] -isnot [string] -or
            $record.environment[$name] -cne $contract.Environment[$name]) { throw 'Unapproved offline environment override.' }
    }
    foreach ($name in $contract.InputSha256.Keys) {
        if (!$record.inputPins.Contains($name) -or $record.inputPins[$name].sha256 -cne $contract.InputSha256[$name]) {
            throw 'Original frozen offline input pin differs.'
        }
        $null = Assert-K8sSealedReference $record.inputPins[$name] $Receipt $Root
    }
    $run = @($Receipt.runs | Where-Object { $_.podUid -ceq $Execution.podUid })
    if ($run.Count -ne 1 -or !(ConvertTo-K8sTimestamp $record.startedAt).Equals((ConvertTo-K8sTimestamp $Execution.startedAt)) -or
        !(ConvertTo-K8sTimestamp $record.finishedAt).Equals((ConvertTo-K8sTimestamp $Execution.finishedAt)) -or
        !(ConvertTo-K8sTimestamp $record.originalCutoff).Equals((ConvertTo-K8sTimestamp $run[0].originalCutoff))) {
        throw 'Offline invocation original interval or cutoff differs.'
    }
    foreach ($pair in @(@('logSha256', 'log'), @('exitSha256', 'exitFile'))) {
        if ($record[$pair[0]] -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Execution[$pair[1]])).Hash.ToLowerInvariant()) {
            throw 'Offline invocation process evidence differs.'
        }
    }
}

function Assert-K8sProviderInvocation($Execution, $Receipt, [string]$Root, $Policy) {
    if ($Execution.id -cnotmatch '^provider/(postgresql|mssql|oracle)$' -or
        $Execution.id.Split('/')[1] -cnotin $Policy.Providers) { throw 'Provider invocation scope differs.' }
    $relative = 'sample-cache-database-' + $Execution.id.Split('/')[1]
    $canonical = 'mvn -B clean verify -Pprovider-integration'
    if ($Execution.ContainsKey('canonicalCommand') -and
        ($Execution.canonicalCommand -isnot [string] -or $Execution.canonicalCommand -cne $canonical)) {
        throw 'Provider canonical obligation conflicts.'
    }
    if (!$Execution.ContainsKey('invocationQualification')) {
        if ($Execution.command -cne $canonical -or $Execution.workingDirectory -cne $relative) {
            throw 'Provider integration invocation differs.'
        }
        return
    }
    # Preserve the physical command/cwd; only this exact offline prefix is qualified.
    $argv = @('mvn', '-o', '-s', '/workspace/input/settings-offline.xml', '-gs',
        '/workspace/input/settings-offline.xml', '-Dmaven.repo.local=/workspace/repository',
        '-B', 'clean', 'verify', '-Pprovider-integration')
    $cwd = '/workspace/source/' + $relative
    if ($Execution.command -cne ($argv -join ' ') -or $Execution.workingDirectory -cne $cwd -or
        (Get-K8sSourceIdentity $Receipt).kind -cne 'SOURCE_SNAPSHOT') {
        throw 'Unapproved offline provider invocation.'
    }
    $record = Read-K8sJson (Assert-K8sSealedReference $Execution.invocationQualification $Receipt $Root)
    $keys = @('schema', 'argv', 'workingDirectory', 'relativeWorkingDirectory', 'sourceIdentity',
        'sdkManifestSha256', 'podUid', 'startedAt', 'finishedAt', 'originalCutoff', 'settings',
        'sdkManifest', 'environment', 'unsetEnvironment', 'logSha256', 'exitSha256')
    foreach ($name in @('schema', 'workingDirectory', 'relativeWorkingDirectory', 'sdkManifestSha256',
        'podUid', 'logSha256', 'exitSha256')) {
        if (!$record.ContainsKey($name) -or $record[$name] -isnot [string]) {
            throw 'Offline provider capture requires scalar identity and evidence fields.'
        }
    }
    if ($record.schema -cne 'cachedb.k8s-offline-provider-invocation/v1' -or $record.Count -ne $keys.Count -or
        @($record.Keys | Where-Object { $_ -cnotin $keys }).Count -or
        $record.workingDirectory -cne $cwd -or $record.relativeWorkingDirectory -cne $relative -or
        $record.sdkManifestSha256 -cne $Execution.sdkManifestSha256 -or $record.podUid -cne $Execution.podUid) {
        throw 'Offline provider capture or cwd mapping differs.'
    }
    Assert-K8sExactStrings $record.argv $argv
    Assert-K8sExecutionSource $record $Receipt
    Assert-K8sExecution $Execution $Receipt $Root
    $run = @($Receipt.runs | Where-Object { $_.podUid -ceq $Execution.podUid })[0]
    if (!(ConvertTo-K8sTimestamp $record.startedAt).Equals((ConvertTo-K8sTimestamp $Execution.startedAt)) -or
        !(ConvertTo-K8sTimestamp $record.finishedAt).Equals((ConvertTo-K8sTimestamp $Execution.finishedAt)) -or
        !(ConvertTo-K8sTimestamp $record.originalCutoff).Equals((ConvertTo-K8sTimestamp $run.originalCutoff))) {
        throw 'Offline provider original interval or cutoff differs.'
    }
    if ($record.settings.sha256 -cne $Policy.OfflinePackageInvocation.InputSha256.settings -or
        $record.sdkManifest.path -cne $Receipt.sdk.manifest -or
        $record.sdkManifest.sha256 -cne $Receipt.sdk.manifestSha256) {
        throw 'Offline provider settings or cached SDK pin differs.'
    }
    $null = Assert-K8sSealedReference $record.settings $Receipt $Root
    $null = Assert-K8sSealedReference $record.sdkManifest $Receipt $Root 4MB
    # Capture these non-secret process environment channels, not fixture credentials.
    $environment = @{MAVEN_SKIP_RC='1'; MAVEN_OPTS='-Xms64m -Xmx768m -XX:ActiveProcessorCount=2'}
    if ($record.environment -isnot [Collections.IDictionary] -or $record.environment.Count -ne $environment.Count) {
        throw 'Exact offline provider Maven environment required.'
    }
    foreach ($name in $environment.Keys) {
        if (!$record.environment.Contains($name) -or $record.environment[$name] -isnot [string] -or
            $record.environment[$name] -cne $environment[$name]) {
            throw 'Unapproved provider Maven environment override.'
        }
    }
    Assert-K8sExactStrings $record.unsetEnvironment @('MAVEN_ARGS', 'MAVEN_CONFIG', 'JAVA_TOOL_OPTIONS',
        'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')
    foreach ($pair in @(@('logSha256', 'log'), @('exitSha256', 'exitFile'))) {
        if ($record[$pair[0]] -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Execution[$pair[1]])).Hash.ToLowerInvariant()) {
            throw 'Offline provider process evidence differs.'
        }
    }
}

function Assert-K8sControlInvocation($Receipt, [string]$Id, [string]$Root, $Policy) {
    $index = [int]$Id.Split('/')[1]
    $execution = Get-K8sExecution $Receipt $Id
    $obligation = @($Receipt.executions | Where-Object { $_.id -ceq $Id })[0]
    if ($obligation.ContainsKey('executionRef') -and $obligation.canonicalCommand -cne $Policy.Controls[$index]) {
        throw 'Referenced canonical control obligation differs.'
    }
    if ($execution.ContainsKey('canonicalCommand') -and $execution.canonicalCommand -cne $Policy.Controls[$index]) {
        throw 'Physical execution canonical obligation conflicts.'
    }
    if ($execution.ContainsKey('invocationQualification') -or $execution.command -cne $Policy.Controls[$index]) {
        if ($Id -cne 'control/17') { throw 'Required Kubernetes control command differs.' }
        Assert-K8sPackageInvocation $execution $Receipt $Root $Policy
    }
}

function Assert-K8sExecutionRoster($Receipt, [string]$Root, $Policy) {
    $required = @('sdk-build', 'standalone', 'cluster', 'sample-features')
    foreach ($provider in $Policy.Providers) {
        $required += @("provider/$provider", "certification/$provider")
        foreach ($cell in $Policy.Baseline) { $required += "baseline/$provider/$($cell.Name)" }
    }
    for ($i = 0; $i -lt $Policy.Controls.Count; $i++) { $required += ('control/{0:d2}' -f $i) }
    if (@($Receipt.executions).Count -ne $required.Count) { throw 'Complete exact execution obligation roster required.' }
    $physical = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $processFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $validatedOriginals = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $required) {
        $execution = Get-K8sExecution $Receipt $id
        if (Test-K8sOriginalCaseObligation $execution $Receipt) {
            Assert-K8sOriginalCaseExecutions $execution $Receipt $Root $Policy $processFiles $validatedOriginals
            continue
        }
        if ($id.StartsWith('control/', [StringComparison]::Ordinal)) { Assert-K8sControlInvocation $Receipt $id $Root $Policy }
        if (!$physical.Add($execution.id)) { continue }
        if ($execution.command -isnot [string] -or [string]::IsNullOrWhiteSpace($execution.command) -or
            $execution.workingDirectory -isnot [string] -or !$execution.workingDirectory) { throw 'Exact invocation is required.' }
        if ($id.StartsWith('provider/', [StringComparison]::Ordinal)) {
            Assert-K8sProviderInvocation $execution $Receipt $Root $Policy
        } elseif ($execution.workingDirectory -cne '.') { $null = Resolve-K8sMember $Root $execution.workingDirectory }
        foreach ($member in @($execution.log, $execution.exitFile)) {
            if (@($Receipt.files | Where-Object { $_.path -ceq $member }).Count -ne 1 -or !$processFiles.Add($member)) {
                throw 'Each physical execution requires its own sealed process evidence.'
            }
        }
        $expectedExit = if ($id.StartsWith('certification/', [StringComparison]::Ordinal)) { 1 } else { 0 }
        Assert-K8sExecution $execution $Receipt $Root $expectedExit
    }
}

function Assert-K8sPreservation($Receipt, [string]$Root, [string]$SdkPin, $Policy) {
    $manifest = Resolve-K8sMember $Root $Receipt.source.manifest
    $identity = Get-K8sSourceIdentity $Receipt
    if ($identity.kind -ceq 'SOURCE_SNAPSHOT' -and
        (Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant() -cne $identity.manifestSha256) {
        throw 'Original source manifest differs from the execution snapshot identity.'
    }
    foreach ($member in @($Receipt.source.before, $Receipt.source.after)) {
        if ((Get-FileHash -LiteralPath (Resolve-K8sMember $Root $member)).Hash -cne (Get-FileHash -LiteralPath $manifest).Hash) {
            throw 'Kubernetes source preservation failed.'
        }
    }
    foreach ($member in @($Receipt.sdk.manifest, $Receipt.sdk.before, $Receipt.sdk.after)) {
        if ((Get-FileHash -LiteralPath (Resolve-K8sMember $Root $member)).Hash.ToLowerInvariant() -cne $SdkPin) {
            throw 'SDK manifest/build/preservation authority differs.'
        }
    }
    $sdk = Read-K8sManifest (Resolve-K8sMember $Root $Receipt.sdk.manifest)
    if ($sdk.Count -ne $Policy.SdkFiles) { throw 'Complete SDK inventory is required.' }
    $build = Get-K8sExecution $Receipt 'sdk-build'
    Assert-K8sExecution $build $Receipt $Root
    if ($build.ContainsKey('invocationQualification')) { Assert-K8sPackageInvocation $build $Receipt $Root $Policy }
}

function Assert-K8sSourceAndSdk($Receipt, [string]$Root, [string]$SourceRoot, [string]$SdkPin, $Policy) {
    $source = Read-K8sManifest (Resolve-K8sMember $Root $Receipt.source.manifest)
    $tracked = @(& git -C $SourceRoot ls-files)
    if ($LASTEXITCODE -ne 0 -or !$tracked.Count -or $tracked.Count -ne $source.Count -or
        @($tracked | Sort-Object -Unique).Count -ne $tracked.Count) { throw 'Full source inventory differs from exact S.' }
    foreach ($path in $tracked) {
        $file = Get-Item -LiteralPath (Resolve-K8sMember $SourceRoot $path) -Force
        if (!$source.ContainsKey($path) -or $file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $source[$path]) {
            throw 'Kubernetes source bytes differ from exact S.'
        }
    }
    Assert-K8sPreservation $Receipt $Root $SdkPin $Policy
}

function Resolve-K8sAcceptance($Envelope, [string]$EvidenceRoot, [string]$SourceRoot, [hashtable]$Authority, $Policy) {
    $receipt = $Envelope; $runtime = $EvidenceRoot; $qualification = $null
    $originalAuthority = $Authority
    if ($Envelope.schema -ceq 'cachedb.k8s-distribution/v1') {
        Assert-K8sDistributionEnvelope $Envelope $Authority
        $null = Assert-K8sInventory $Envelope $EvidenceRoot
        $receiptPath = Resolve-K8sMember $EvidenceRoot $Envelope.runtime.receipt
        if ((Get-FileHash -LiteralPath $receiptPath).Hash.ToLowerInvariant() -cne $Envelope.runtime.receiptSha256) {
            throw 'Original runtime receipt pin differs.'
        }
        $receipt = Read-K8sJson $receiptPath
        $runtime = Split-Path -Parent $receiptPath
        $identity = Get-K8sSourceIdentity $receipt
        $originalAuthority = @{} + $Authority
        # The protected outer receipt pins the original identity; never rewrite it to the distribution run.
        if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
            $originalAuthority.Remove('sha')
            $originalAuthority.sourceIdentity = $identity
        } else { $originalAuthority.sha = $identity.sha }
        $originalAuthority.runId = [string]$receipt.producer.runId
        $originalAuthority.runAttempt = [string]$receipt.producer.runAttempt
        $originalAuthority.workflowPath = $receipt.producer.workflowPath
        if ($originalAuthority.runId -cnotmatch '^[1-9][0-9]{0,18}$' -or
            $originalAuthority.runAttempt -cnotmatch '^[1-9][0-9]{0,18}$' -or
            $originalAuthority.workflowPath -cnotmatch '^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$') {
            throw 'Original runtime producer identity is invalid.'
        }
        if ($null -ne $Envelope.sourceEquivalence) {
            $arguments = @{ProofPath=(Resolve-K8sMember $EvidenceRoot $Envelope.sourceEquivalence.path)
                ProofPin=$Envelope.sourceEquivalence.sha256; OriginalManifest=(Resolve-K8sMember $runtime $receipt.source.manifest)
                SourceRoot=$SourceRoot; TargetSha=$Authority.sha; SdkPin=$Authority.sdkPin}
            if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
                $qualification = Assert-K8sSourceEquivalence @arguments -SourceSha '' -SnapshotIdentity $identity `
                    -OriginalReceiptSha256 $Envelope.runtime.receiptSha256 -Policy $Policy
            } else { $qualification = Assert-K8sSourceEquivalence @arguments -SourceSha $identity.sha }
        } elseif ($identity.kind -ceq 'SOURCE_SNAPSHOT' -or $receipt.source.sha -cne $Authority.sha) {
            throw 'Original execution source differs without independently pinned source equivalence.'
        }
    }
    Assert-K8sHeader $receipt $originalAuthority $Policy
    $null = Assert-K8sInventory $receipt $runtime
    Assert-K8sExecutionRoster $receipt $runtime $Policy
    if ($null -ne $qualification) {
        Assert-K8sPreservation $receipt $runtime $Authority.sdkPin $Policy
    } else {
        Assert-K8sSourceAndSdk $receipt $runtime $SourceRoot $Authority.sdkPin $Policy
    }
    return @{receipt=$receipt; root=$runtime; sourceQualification=$qualification}
}

function Assert-K8sFeatureBinding($FeatureHeader, $Receipt, [string]$Root) {
    if ($FeatureHeader.source.manifest.sha256 -cne (Get-FileHash -LiteralPath (Resolve-K8sMember $Root $Receipt.source.manifest)).Hash.ToLowerInvariant() -or
        $FeatureHeader.sdk.manifest.sha256 -cne $Receipt.sdk.manifestSha256 -or
        $FeatureHeader.sdk.sourceManifestSha256 -cne $FeatureHeader.source.manifest.sha256) {
        throw 'Retained feature evidence is not from the exact accepted source and SDK; delta acceptance is still open.'
    }
}

function Assert-K8sReports($Receipt, [string]$Root, [string]$SourceRoot, [string]$PrivateResults, $Policy, $SourceQualification = $null) {
    foreach ($lane in @('standalone', 'cluster')) {
        $execution = Get-K8sExecution $Receipt $lane
        if (Test-K8sOriginalCaseObligation $execution $Receipt) {
            $arguments = @{ OriginalCaseIndexPath = (Resolve-K8sMember $Root $execution.originalCases.path)
                OriginalCaseIndexSha256 = $execution.originalCases.sha256; EvidenceRoot = $Root
                PhysicalExecutionIds = [string[]]$execution.physicalExecutionIds
                SummaryPath = (Join-Path $PrivateResults "$lane.md"); ClusterOnly = ($lane -ceq 'cluster') }
        } else {
            Assert-K8sExecution $execution $Receipt $Root
            $arguments = @{ ReportsRoot = (Resolve-K8sMember $Root $execution.reportsRoot)
                SummaryPath = (Join-Path $PrivateResults "$lane.md"); ClusterOnly = ($lane -ceq 'cluster') }
        }
        & (Join-Path $SourceRoot 'tools/ci/check-full-environment-tests.ps1') @arguments | Out-Null
    }
    $feature = Get-K8sExecution $Receipt 'sample-features'
    Assert-K8sExecution $feature $Receipt $Root
    $featureReceipt = Resolve-K8sMember $Root $feature.receipt
    $featureHeader = Read-K8sJson $featureReceipt
    Assert-K8sFeatureBinding $featureHeader $Receipt $Root
    $featureSource = $SourceRoot
    if ($null -ne $SourceQualification) {
        $identity = Get-K8sSourceIdentity $Receipt
        if ($identity.kind -ceq 'SOURCE_SNAPSHOT') {
            Assert-K8sSnapshotIdentity $SourceQualification.sourceIdentity
            if ($SourceQualification.sourceIdentity.manifestSha256 -cne $identity.manifestSha256) { throw 'Feature source snapshot qualification differs.' }
        } elseif ($SourceQualification.sourceSha -cne $Receipt.source.sha) { throw 'Feature source qualification differs.' }
        if ($SourceQualification.sdkManifestSha256 -cne $Receipt.sdk.manifestSha256) { throw 'Feature SDK qualification differs.' }
        $featureSource = Resolve-K8sMember (Split-Path -Parent $featureReceipt) $featureHeader.source.root
    }
    # Execute only the trusted final parser. Original source bytes are data, not executable artifact code.
    $node = Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1
    & $node.Source (Join-Path $SourceRoot 'tools/ci/check-v019-sample-coverage.mjs') --root $featureSource `
        --runtime-evidence $featureReceipt --runtime-evidence-sha256 (Get-FileHash -LiteralPath $featureReceipt).Hash.ToLowerInvariant() `
        --summary (Join-Path $PrivateResults 'features.md') --private-results (Join-Path $PrivateResults 'features.json') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Same-source sample feature runtime acceptance is incomplete.' }
    $requests = 0
    foreach ($provider in $Policy.Providers) {
        $sample = Join-Path $SourceRoot "sample-cache-database-$provider"
        foreach ($cell in $Policy.Baseline) {
            $execution = Get-K8sExecution $Receipt "baseline/$provider/$($cell.Name)"
            Assert-K8sExecution $execution $Receipt $Root
            $collection = Join-Path $sample "postman/cache-database-$provider-$($cell.Name).postman_collection.json"
            & (Join-Path $sample 'scripts/assert-postman-execution.ps1') -CollectionPath $collection `
                -ReportPath (Resolve-K8sMember $Root $execution.report) -Kind $cell.Kind | Out-Null
            $requests += $cell.Requests
        }
        $execution = Get-K8sExecution $Receipt "provider/$provider"
        $requiredSuites = @(Get-ChildItem -LiteralPath (Join-Path $sample 'src/test/java') -Recurse -File | Where-Object {
            $_.Name -cmatch '(?:Test|IT)\.java$'
        } | ForEach-Object {
            if ($_.Length -gt 1MB) { throw 'Oversized test source.' }
            $package = [regex]::Match([IO.File]::ReadAllText($_.FullName), '(?m)^package\s+([A-Za-z0-9_.]+);')
            if (!$package.Success) { throw 'Provider test package is absent.' }
            $package.Groups[1].Value + '.' + $_.BaseName
        })
        if (!$requiredSuites.Count) { throw 'Provider integration source roster is empty.' }
        if (Test-K8sOriginalCaseObligation $execution $Receipt) {
            . (Join-Path $SourceRoot 'tools/ci/k8s-original-case-provenance.ps1')
            $null = Assert-K8sOriginalCaseProvenance -IndexPath (Resolve-K8sMember $Root $execution.originalCases.path) `
                -IndexSha256 $execution.originalCases.sha256 -EvidenceRoot $Root `
                -ExecutionIds ([string[]]$execution.physicalExecutionIds) -ExpectedObligationId "provider/$provider" `
                -RequiredSuites $requiredSuites
        } else {
            Assert-K8sProviderInvocation $execution $Receipt $Root $Policy
            Assert-K8sExecution $execution $Receipt $Root
            Assert-K8sProviderReports (Resolve-K8sMember $Root $execution.reportsRoot) $requiredSuites
        }
        $execution = Get-K8sExecution $Receipt "certification/$provider"
        Assert-K8sExecution $execution $Receipt $Root 1
        # Preserve both original negative checks; a random Maven failure proves neither.
        $log = [IO.File]::ReadAllText((Resolve-K8sMember $Root $execution.log))
        $report = [IO.File]::ReadAllText((Resolve-K8sMember $Root $execution.report))
        if (!$log.Contains('CacheDB production certification failed') -or
            !$report.Contains('Missing certification manifest') -or !$report.Contains('Missing route coverage CSV')) {
            throw 'Certification failure was not the required fail-closed guard.'
        }
    }
    if ($requests -ne 552) { throw 'Full twelve-cell baseline contract differs.' }
    for ($i = 0; $i -lt $Policy.Controls.Count; $i++) {
        $execution = Get-K8sExecution $Receipt ('control/{0:d2}' -f $i)
        Assert-K8sControlInvocation $Receipt ('control/{0:d2}' -f $i) $Root $Policy
        Assert-K8sExecution $execution $Receipt $Root
    }
}

function Assert-K8sProviderReports([string]$Root, [string[]]$RequiredSuites = @()) {
    $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter 'TEST-*.xml')
    if (!$files.Count -or $files.Count -gt 1000) { throw 'Provider integration reports are required.' }
    $tests = 0
    $suites = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($file in $files) {
        if ($file.Length -gt 32MB) { throw 'Oversized provider report.' }
        $settings = [Xml.XmlReaderSettings]::new(); $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.MaxCharactersInDocument = 32MB
        $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
        try { $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) } finally { $reader.Dispose() }
        $suite = $xml.DocumentElement; $cases = @($suite.SelectNodes('testcase'))
        if ($suite.LocalName -cne 'testsuite' -or !$cases.Count -or
            $cases.Count -ne [long]$suite.GetAttribute('tests') -or
            @($suite.SelectNodes('testcase/skipped|testcase/failure|testcase/error')).Count -or
            [long]$suite.GetAttribute('failures') -ne 0 -or [long]$suite.GetAttribute('errors') -ne 0 -or
            [long]$suite.GetAttribute('skipped') -ne 0 -or $xml.SelectNodes('//*[contains(local-name(),"flaky") or contains(local-name(),"rerun")]').Count) {
            throw 'Provider integration report is failed, skipped, flaky, or has invalid counters.'
        }
        if (!$suites.Add($suite.GetAttribute('name'))) { throw 'Duplicate provider suite report.' }
        foreach ($case in $cases) {
            if ($case.GetAttribute('classname') -cne $suite.GetAttribute('name') -or !$case.GetAttribute('name')) {
                throw 'Provider testcase identity differs.'
            }
        }
        $tests += $cases.Count
    }
    if (!$tests) { throw 'Provider integration did not execute.' }
    foreach ($required in $RequiredSuites) {
        if (!$suites.Contains($required)) { throw 'Required provider integration suite did not execute.' }
    }
}

# Only a protected, exact-S public invocation may consume the private read-only credential.
try {
    if ($env:GITHUB_ACTIONS -cne 'true' -or $env:GITHUB_EVENT_NAME -cnotin @('push', 'workflow_dispatch') -or
        $env:GITHUB_API_URL -cne 'https://api.github.com' -or $env:GITHUB_SERVER_URL -cne 'https://github.com' -or
        $env:GITHUB_SHA -cnotmatch '^[a-f0-9]{40}$' -or $env:PUBLIC_WORKFLOW_SHA -cne $env:GITHUB_SHA -or
        $env:GITHUB_REPOSITORY_ID -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $env:K8S_ARTIFACT_ID -cnotmatch '^[1-9][0-9]{0,18}$' -or
        $env:K8S_PRODUCER_WORKFLOW -cnotmatch '^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$') { throw 'Trusted exact-S invocation is required.' }
    foreach ($pin in @($env:K8S_ARCHIVE_PIN, $env:K8S_RECEIPT_PIN, $env:K8S_SDK_PIN)) { Assert-K8sPin $pin }
    $root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
    . (Join-Path $PSScriptRoot 'k8s-source-equivalence.ps1')
    $loader = Join-Path $root 'tools/ci/import-v019-private-owned-runtime.ps1'
    foreach ($definition in Import-K8sGuardFunctions $loader) { . ([scriptblock]::Create($definition)) }
    foreach ($definition in Import-K8sGuardFunctions (Join-Path $PSScriptRoot 'stage-k8s-acceptance.ps1')) {
        . ([scriptblock]::Create($definition))
    }
    foreach ($definition in (Read-PrivateOwnedImportFunctions (Join-Path $root '.github/workflows/framework-readiness.yml'))) {
        . ([scriptblock]::Create($definition))
    }
    Assert-SampleRuntimeSource $env:EVIDENCE_REPOSITORY $env:EVIDENCE_REPOSITORY_ID $env:GH_TOKEN $env:GITHUB_SHA
    $work = Join-Path $env:RUNNER_TEMP ('cachedb-k8s-' + [guid]::NewGuid().ToString('N'))
    $null = [IO.Directory]::CreateDirectory($work)
    if (!$IsWindows) { [IO.File]::SetUnixFileMode($work, [IO.UnixFileMode]448) }
    $api = 'https://api.github.com/repos/' + $env:EVIDENCE_REPOSITORY
    $repositoryFile = Join-Path $work 'repository.json'
    Receive-SampleRuntimeArchive $api $env:GH_TOKEN $repositoryFile 'application/vnd.github+json'
    Assert-PrivateSampleRuntimeRepository ([pscustomobject](Read-K8sJson $repositoryFile)) $env:EVIDENCE_REPOSITORY $env:EVIDENCE_REPOSITORY_ID
    $metadataFile = Join-Path $work 'artifact.json'
    Receive-SampleRuntimeArchive "$api/actions/artifacts/$($env:K8S_ARTIFACT_ID)" $env:GH_TOKEN $metadataFile 'application/vnd.github+json'
    $metadata = Read-K8sJson $metadataFile
    Assert-SampleRuntimeArtifact $metadata $env:K8S_ARTIFACT_ID $env:K8S_ARCHIVE_PIN $env:EVIDENCE_REPOSITORY_ID $env:GITHUB_SHA
    $runFile = Join-Path $work 'producer.json'
    Receive-SampleRuntimeArchive "$api/actions/runs/$($metadata.workflow_run.id)" $env:GH_TOKEN $runFile 'application/vnd.github+json'
    $producer = Read-K8sJson $runFile
    if ($producer.status -cne 'completed' -or $producer.conclusion -cne 'success' -or $producer.event -cne 'workflow_dispatch' -or
        $producer.path -cne $env:K8S_PRODUCER_WORKFLOW -or $producer.head_sha -cne $env:GITHUB_SHA -or
        [string]$producer.id -cne [string]$metadata.workflow_run.id -or
        [string]$producer.repository.id -cne $env:EVIDENCE_REPOSITORY_ID -or
        [string]$producer.head_repository.id -cne $env:EVIDENCE_REPOSITORY_ID) { throw 'Unapproved artifact producer.' }
    $archive = Join-Path $work 'acceptance.zip'; $evidence = Join-Path $work 'evidence'
    Receive-SampleRuntimeArchive "$api/actions/artifacts/$($env:K8S_ARTIFACT_ID)/zip" $env:GH_TOKEN $archive
    $receiptPath = Expand-SampleRuntimeArchive $archive $evidence $env:K8S_ARCHIVE_PIN $env:K8S_RECEIPT_PIN
    $receipt = Read-K8sJson $receiptPath
    $policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
    $authority = @{ publicRepository = $env:GITHUB_REPOSITORY; publicRepositoryId = $env:GITHUB_REPOSITORY_ID
        sha = $env:GITHUB_SHA; sdkPin = $env:K8S_SDK_PIN; privateRepositoryId = $env:EVIDENCE_REPOSITORY_ID
        runId = [string]$producer.id; runAttempt = [string]$producer.run_attempt; workflowPath = $env:K8S_PRODUCER_WORKFLOW }
    $accepted = Resolve-K8sAcceptance $receipt $evidence $root $authority $policy
    $privateResults = Join-Path $work 'verification'; $null = [IO.Directory]::CreateDirectory($privateResults)
    # Verification invokes report parsers only, never artifact code, Maven, or test workloads.
    $env:GH_TOKEN = ''; $env:GITHUB_TOKEN = ''
    & { Assert-K8sReports $accepted.receipt $accepted.root $root $privateResults $policy $accepted.sourceQualification } *> (Join-Path $work 'report-validation.private.log')
    $summary = Join-Path $root $SummaryPath
    $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $summary))
    [IO.File]::WriteAllText($summary, "K8S_ACCEPTANCE_VERIFIED`nSource: $($env:GITHUB_SHA)`nSDK: $($env:K8S_SDK_PIN)`nPinned Kubernetes runtime reports verified against the approved GitHub producer; no workloads executed in GitHub Actions.`n")
    Write-Host 'K8S_ACCEPTANCE_VERIFIED'
} catch {
    # Raw issuer reports, exception messages, paths and credentials are never public artifacts.
    Write-Error 'Kubernetes acceptance verification failed; missing, mismatched or untrusted evidence is release-blocking.'
    exit 1
}
