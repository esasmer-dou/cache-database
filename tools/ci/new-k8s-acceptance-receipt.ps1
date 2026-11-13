#requires -Version 7.3
<#
.SYNOPSIS
Assemble supplied Kubernetes evidence; do not execute workloads or grant acceptance.
.DESCRIPTION
InventoryPath is a pinned JSON object with schema cachedb.k8s-acceptance-input/v1,
source, sdk, producer, runs, executions and files. Metadata uses the existing
acceptance contract verbatim; files contains explicit path/bytes/sha256 entries
relative to EvidenceRoot. All 52 obligations remain required. Only control/17 may
contain exactly id, executionRef='sdk-build', canonicalCommand instead of a second
execution. Each physical execution needs its own log/exit, exact invocation,
source/SDK identity and original timestamps. Offline package qualification uses
canonicalCommand plus invocationQualification={path,sha256}. Capture-v1 requires
original record authority; pinned-script-v2 instead requires independent admission
of original pod/authority pins. Neither mode approves itself during assembly.
The original inventory is retained unchanged under origin/execution-inventory.json.
The resulting receipt still requires check-k8s-acceptance.ps1 report verification
and independently authenticated producer authority. No identity is inferred.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InventoryPath,
    [Parameter(Mandatory)][string]$InventorySha256,
    [Parameter(Mandatory)][string]$EvidenceRoot,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-K8sAssemblyPath([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -ne $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Evidence paths and their ancestors must not be links.'
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Copy-K8sAssemblyFile([string]$From, [string]$To, [long]$Bytes, [string]$Pin) {
    Assert-K8sPin $Pin
    Assert-K8sAssemblyPath $From
    if ($Bytes -lt 0 -or $Bytes -gt 128MB) { throw 'Evidence file exceeds its byte budget.' }
    $inputStream = [IO.File]::Open($From, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ($inputStream.Length -ne $Bytes) { throw 'Supplied evidence length differs.' }
        $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $To))
        $outputStream = [IO.File]::Open($To, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $buffer = [byte[]]::new(81920)
            $remaining = $Bytes
            while ($remaining -gt 0) {
                $read = $inputStream.Read($buffer, 0, [int][Math]::Min($remaining, $buffer.Length))
                if (!$read) { throw 'Supplied evidence was truncated during assembly.' }
                $outputStream.Write($buffer, 0, $read)
                $remaining -= $read
            }
            if ($inputStream.ReadByte() -ne -1) { throw 'Supplied evidence grew during assembly.' }
        } finally { $outputStream.Dispose() }
    } finally { $inputStream.Dispose() }
    Assert-K8sAssemblyPath $From
    if ((Get-Item -LiteralPath $To).Length -ne $Bytes -or
        (Get-FileHash -LiteralPath $To -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Pin) {
        throw 'Supplied evidence bytes differ from their pin.'
    }
}

function New-K8sAcceptanceReceipt {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$InventoryPath,
        [Parameter(Mandatory)][string]$InventorySha256,
        [Parameter(Mandatory)][string]$EvidenceRoot,
        [Parameter(Mandatory)][string]$OutputDirectory,
        [hashtable]$Policy = $null
    )
    $tokens = $parseErrors = $null
    $checker = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'check-k8s-acceptance.ps1'), [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw 'Acceptance guards must parse.' }
    foreach ($definition in $checker.FindAll({param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true)) { . ([scriptblock]::Create($definition.Extent.Text)) }
    # Internal fixture injection only; the CLI has no policy override.
    if ($null -eq $Policy) { $Policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1') }
    Assert-K8sPin $InventorySha256
    $InventoryPath = [IO.Path]::GetFullPath($InventoryPath)
    $EvidenceRoot = [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    foreach ($path in @($InventoryPath, $EvidenceRoot, $OutputDirectory)) { Assert-K8sAssemblyPath $path }
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    $separator = [IO.Path]::DirectorySeparatorChar
    if (!(Test-Path -LiteralPath $EvidenceRoot -PathType Container) -or
        (Test-Path -LiteralPath $OutputDirectory) -or
        $OutputDirectory.Equals($EvidenceRoot, $comparison) -or
        $OutputDirectory.StartsWith($EvidenceRoot + $separator, $comparison) -or
        $EvidenceRoot.StartsWith($OutputDirectory + $separator, $comparison) -or
        $InventoryPath.StartsWith($OutputDirectory + $separator, $comparison)) {
        throw 'Fresh output separate from original evidence is required.'
    }
    $inventoryFile = Get-Item -LiteralPath $InventoryPath -Force
    if ($inventoryFile.PSIsContainer -or $inventoryFile.Length -le 0 -or $inventoryFile.Length -gt 1MB) {
        throw 'A bounded explicit execution inventory is required.'
    }
    # Publish only after every supplied byte and required record has been checked.
    $pending = $OutputDirectory + '.partial-' + [guid]::NewGuid().ToString('N')
    if (Test-Path -LiteralPath $pending) { throw 'Assembly scratch directory already exists.' }
    $null = [IO.Directory]::CreateDirectory($pending)
    if (!$IsWindows) { [IO.File]::SetUnixFileMode($pending, [IO.UnixFileMode]448) }
    $origin = 'origin/execution-inventory.json'
    $originPath = Resolve-K8sMember $pending $origin
    Copy-K8sAssemblyFile $InventoryPath $originPath $inventoryFile.Length $InventorySha256
    $inventory = Read-K8sJson $originPath
    $keys = @('schema', 'source', 'sdk', 'producer', 'runs', 'executions', 'files')
    if ($inventory.schema -ceq 'cachedb.k8s-acceptance-input/v3') { $keys += @('physicalExecutions', 'executionSources') }
    if ($inventory.schema -cnotin @('cachedb.k8s-acceptance-input/v1', 'cachedb.k8s-acceptance-input/v2', 'cachedb.k8s-acceptance-input/v3') -or $inventory.Count -ne $keys.Count -or
        @($inventory.Keys | Where-Object { $_ -cnotin $keys }).Count) {
        throw 'Explicit runtime inventory required; envelopes and source equivalence are not assembly inputs.'
    }
    $arrayGroups = @('runs', 'executions', 'files')
    if ($inventory.schema -ceq 'cachedb.k8s-acceptance-input/v3') { $arrayGroups += @('physicalExecutions', 'executionSources') }
    foreach ($group in $arrayGroups) {
        if ($inventory[$group] -isnot [array]) { throw 'Run, execution and file inventories must be explicit arrays.' }
    }
    # Keep recorded timezone offsets verbatim on PowerShell versions that parse JSON dates.
    $document = [Text.Json.JsonDocument]::Parse([IO.File]::ReadAllText($originPath))
    try {
        $timestampGroups = @('runs', 'executions')
        if ($inventory.schema -ceq 'cachedb.k8s-acceptance-input/v3') { $timestampGroups += 'physicalExecutions' }
        foreach ($group in $timestampGroups) {
            $fields = if ($group -ceq 'runs') { @('createdAt', 'originalCutoff', 'finishedAt') } else { @('startedAt', 'finishedAt') }
            $records = $document.RootElement.GetProperty($group)
            for ($i=0; $i -lt $inventory[$group].Count; $i++) {
                if ($group -ceq 'executions' -and $inventory[$group][$i].ContainsKey('executionRef')) { continue }
                if ($group -ceq 'executions' -and $inventory[$group][$i].ContainsKey('kind') -and
                    $inventory[$group][$i].kind -ceq 'ORIGINAL_CASES') { continue }
                foreach ($field in $fields) { $inventory[$group][$i][$field] = $records[$i].GetProperty($field).GetString() }
            }
        }
    } finally { $document.Dispose() }
    if ($inventory.source.repository -cnotmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or
        $inventory.producer.workflowPath -cnotmatch '^\.github/workflows/[A-Za-z0-9_-]+\.ya?ml$') {
        throw 'Original source and producer identities are required.'
    }
    foreach ($id in @($inventory.source.repositoryId, $inventory.producer.repositoryId,
        $inventory.producer.runId, $inventory.producer.runAttempt)) {
        if ([string]$id -cnotmatch '^[1-9][0-9]{0,18}$') { throw 'Original repository and producer IDs are required.' }
    }
    Assert-K8sPin $inventory.sdk.manifestSha256
    $schema = switch -CaseSensitive ($inventory.schema) {
        'cachedb.k8s-acceptance-input/v3' { 'cachedb.k8s-acceptance/v3' }
        'cachedb.k8s-acceptance-input/v2' { $Policy.SnapshotSchema }
        default { $Policy.Schema }
    }
    $receipt = [ordered]@{schema=$schema; kind=$policy.Kind
        source=$inventory.source; sdk=$inventory.sdk; producer=$inventory.producer
        runs=$inventory.runs; executions=$inventory.executions; files=@($inventory.files)
        origin=@{inventory=$origin; sha256=$InventorySha256}}
    if ($schema -ceq 'cachedb.k8s-acceptance/v3') {
        $receipt.physicalExecutions = $inventory.physicalExecutions
        $receipt.executionSources = $inventory.executionSources
    }
    $identity = Get-K8sSourceIdentity $receipt
    $authority = @{publicRepository=$receipt.source.repository; publicRepositoryId=[string]$receipt.source.repositoryId
        sdkPin=$receipt.sdk.manifestSha256; privateRepositoryId=[string]$receipt.producer.repositoryId
        runId=[string]$receipt.producer.runId; runAttempt=[string]$receipt.producer.runAttempt; workflowPath=$receipt.producer.workflowPath}
    if ($identity.kind -ceq 'SOURCE_SNAPSHOT') { $authority.sourceIdentity = $identity } else { $authority.sha = $identity.sha }
    Assert-K8sHeader $receipt $authority $policy
    $members = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $bytes = $inventoryFile.Length
    if (!$receipt.files.Count -or $receipt.files.Count -gt 19997) { throw 'Bounded supplied file inventory required.' }
    foreach ($entry in $receipt.files) {
        if (!$members.Add($entry.path) -or $entry.path -ieq 'receipt.json' -or
            $entry.path -imatch '^origin(?:/|$)' -or
            ($entry.bytes -isnot [long] -and $entry.bytes -isnot [int])) { throw 'Ambiguous supplied file inventory.' }
        $bytes += $entry.bytes
        if ($bytes -gt 2GB) { throw 'Evidence exceeds its total byte budget.' }
        Copy-K8sAssemblyFile (Resolve-K8sMember $EvidenceRoot $entry.path) (Resolve-K8sMember $pending $entry.path) $entry.bytes $entry.sha256
    }
    $receipt.files += @{path=$origin; bytes=$inventoryFile.Length; sha256=$InventorySha256}
    $null = Assert-K8sInventory $receipt $pending
    Assert-K8sExecutionRoster $receipt $pending $policy
    foreach ($execution in $receipt.executions) {
        if ($execution.ContainsKey('executionRef')) { continue }
        if (Test-K8sOriginalCaseObligation $execution $receipt) { continue }
        $id = $execution.id
        $expectedExit = if ($id.StartsWith('certification/', [StringComparison]::Ordinal)) { 1 } else { 0 }
        foreach ($field in @('report', 'receipt')) {
            if ($execution.ContainsKey($field) -and (!$members.Contains($execution[$field]) -or
                (Get-Item -LiteralPath (Resolve-K8sMember $pending $execution[$field])).Length -eq 0)) {
                throw 'Referenced report/receipt must contain supplied sealed bytes.'
            }
        }
        if ($id -in @('standalone', 'cluster') -or $id.StartsWith('provider/', [StringComparison]::Ordinal)) {
            $reports = Resolve-K8sMember $pending $execution.reportsRoot
            if (!(Test-Path -LiteralPath $reports -PathType Container) -or
                !@(Get-ChildItem -LiteralPath $reports -Recurse -File -Filter 'TEST-*.xml').Count) { throw 'Actual test report inventory is required.' }
        }
        if ($id.StartsWith('baseline/', [StringComparison]::Ordinal) -or $expectedExit -eq 1) {
            if (!$execution.ContainsKey('report')) { throw 'Actual baseline/certification report is required.' }
        }
        if ($expectedExit -eq 1 -and
            (!([IO.File]::ReadAllText((Resolve-K8sMember $pending $execution.log))).Contains('CacheDB production certification failed') -or
             !([IO.File]::ReadAllText((Resolve-K8sMember $pending $execution.report))).Contains('Missing certification manifest') -or
             !([IO.File]::ReadAllText((Resolve-K8sMember $pending $execution.report))).Contains('Missing route coverage CSV'))) {
            throw 'Certification evidence must retain both expected negative guards.'
        }
    }
    foreach ($provider in $policy.Providers) {
        $execution = Get-K8sExecution $receipt "provider/$provider"
        if (!(Test-K8sOriginalCaseObligation $execution $receipt)) {
            Assert-K8sProviderInvocation $execution $receipt $pending $policy
        }
    }
    $preservation = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($member in @($receipt.source.manifest, $receipt.source.before, $receipt.source.after,
        $receipt.sdk.manifest, $receipt.sdk.before, $receipt.sdk.after)) {
        if (!$members.Contains($member) -or !$preservation.Add($member)) { throw 'Separate sealed source/SDK preservation files required.' }
    }
    Assert-K8sPreservation $receipt $pending $receipt.sdk.manifestSha256 $policy
    $null = Read-K8sManifest (Resolve-K8sMember $pending $receipt.source.manifest)
    $feature = Get-K8sExecution $receipt 'sample-features'
    if (!$feature.ContainsKey('receipt')) { throw 'Actual feature runtime receipt is required.' }
    Assert-K8sFeatureBinding (Read-K8sJson (Resolve-K8sMember $pending $feature.receipt)) $receipt $pending
    $json = ($receipt | ConvertTo-Json -Depth 32) + "`n"
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 1MB) { throw 'Receipt exceeds the verifier byte budget.' }
    [IO.File]::WriteAllText((Join-Path $pending 'receipt.json'), $json, [Text.UTF8Encoding]::new($false))
    $null = Assert-K8sInventory (Read-K8sJson (Join-Path $pending 'receipt.json')) $pending
    Assert-K8sAssemblyPath $OutputDirectory
    [IO.Directory]::Move($pending, $OutputDirectory)
    $receiptPath = Join-Path $OutputDirectory 'receipt.json'
    return [ordered]@{status='ASSEMBLED_NOT_RUNTIME_ACCEPTANCE'; receiptPath=$receiptPath
        receiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash.ToLowerInvariant(); sdkManifestSha256=$receipt.sdk.manifestSha256}
}

New-K8sAcceptanceReceipt @PSBoundParameters
