#requires -Version 7.3
<#
.SYNOPSIS
Reconstruct the pinned AE script declaration from supplied original evidence.
.DESCRIPTION
InventoryPath is a pinned cachedb.k8s-pinned-script-input/v1 JSON object with
exactly schema and files. files maps the three InputSha256 roles and twelve
OriginalEvidenceRoles in k8s-acceptance-contract.psd1 to {path,bytes,sha256},
relative to EvidenceRoot. Supply ORIGINAL bytes, including the admitted Pod JSON,
exported input/authority.json, build receipt, logs, exits, timestamps and manifests.
Output is fresh and separate; originals and the input inventory are copied unchanged.
PINNED_EXECUTED_SCRIPT proves only the literal invocation and exports/unsets of the
reviewed script under an independently authenticated producer. It is NOT execve
capture, a full inherited-environment capture, or proof of Maven's effective options.
reconstructedAt is new; startedAt/finishedAt/originalCutoff remain original.
No source Git SHA or producer identity is inferred. All 92 original SDK hashes
remain in the copied manifest. No authority pin is approved or populated here.
The returned files and invocationQualification reference can be supplied explicitly
to the acceptance assembler. Independent original authority admission, producer
authentication, exact public source verification and all 52 obligations still apply.
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

function New-K8sPackageInvocationQualification {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$InventoryPath,
        [Parameter(Mandatory)][string]$InventorySha256,
        [Parameter(Mandatory)][string]$EvidenceRoot,
        [Parameter(Mandatory)][string]$OutputDirectory,
        [hashtable]$Policy = $null
    )
    foreach ($script in @('check-k8s-acceptance.ps1', 'new-k8s-acceptance-receipt.ps1')) {
        $tokens = $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $script), [ref]$tokens, [ref]$errors)
        if ($errors.Count) { throw 'Evidence tooling must parse.' }
        foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
            if ($definition.Name -cne 'New-K8sAcceptanceReceipt') { . ([scriptblock]::Create($definition.Extent.Text)) }
        }
    }
    # Internal fixture injection only; the script CLI never accepts or forwards a policy override.
    if ($null -eq $Policy) { $Policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1') }
    Assert-K8sPin $InventorySha256
    $InventoryPath = [IO.Path]::GetFullPath($InventoryPath)
    $EvidenceRoot = [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    foreach ($path in @($InventoryPath, $EvidenceRoot, $OutputDirectory)) { Assert-K8sAssemblyPath $path }
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    $separator = [IO.Path]::DirectorySeparatorChar
    if (!(Test-Path -LiteralPath $EvidenceRoot -PathType Container) -or (Test-Path -LiteralPath $OutputDirectory) -or
        $OutputDirectory.Equals($EvidenceRoot, $comparison) -or $OutputDirectory.StartsWith($EvidenceRoot + $separator, $comparison) -or
        $EvidenceRoot.StartsWith($OutputDirectory + $separator, $comparison) -or $InventoryPath.StartsWith($OutputDirectory + $separator, $comparison)) {
        throw 'Fresh qualification output separate from original evidence required.'
    }
    $file = Get-Item -LiteralPath $InventoryPath -Force
    if ($file.PSIsContainer -or $file.Length -le 0 -or $file.Length -gt 1MB) { throw 'Bounded original inventory required.' }
    $pending = $OutputDirectory + '.partial-' + [guid]::NewGuid().ToString('N')
    $null = [IO.Directory]::CreateDirectory($pending)
    if (!$IsWindows) { [IO.File]::SetUnixFileMode($pending, [IO.UnixFileMode]448) }
    $origin = @{path='invocation-origin/pinned-script-inventory.json'; sha256=$InventorySha256}
    Copy-K8sAssemblyFile $InventoryPath (Resolve-K8sMember $pending $origin.path) $file.Length $InventorySha256
    $inventory = Read-K8sJson (Resolve-K8sMember $pending $origin.path)
    $roles = @($policy.OfflinePackageInvocation.InputSha256.Keys) + @($policy.OfflinePackageInvocation.PinnedExecutedScript.OriginalEvidenceRoles)
    if ($inventory.schema -cne 'cachedb.k8s-pinned-script-input/v1' -or $inventory.Count -ne 2 -or
        $inventory.files -isnot [System.Collections.IDictionary] -or $inventory.files.Count -ne $roles.Count) { throw 'Exact original evidence roster required.' }
    $receipt = @{files=@(@{path=$origin.path; bytes=$file.Length; sha256=$InventorySha256})}
    $members = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($name in $roles) {
        $entry = $inventory.files[$name]
        if ($entry -isnot [System.Collections.IDictionary] -or $entry.Count -ne 3 -or
            !$members.Add($entry.path) -or $entry.path -imatch '^(?:invocation-origin(?:/|$)|qualification\.json$|receipt\.json$)' -or
            ($entry.bytes -isnot [long] -and $entry.bytes -isnot [int])) { throw 'Ambiguous original evidence entry.' }
        Copy-K8sAssemblyFile (Resolve-K8sMember $EvidenceRoot $entry.path) (Resolve-K8sMember $pending $entry.path) $entry.bytes $entry.sha256
        $receipt.files += $entry
    }
    $null = Assert-K8sInventory $receipt $pending
    $record = New-K8sPinnedScriptRecord $inventory $origin $receipt $pending $policy
    $recordPath = Resolve-K8sMember $pending 'qualification.json'
    [IO.File]::WriteAllText($recordPath, (($record | ConvertTo-Json -Depth 32) + "`n"), [Text.UTF8Encoding]::new($false))
    $reference = @{path='qualification.json'; sha256=(Get-FileHash -LiteralPath $recordPath).Hash.ToLowerInvariant()}
    $receipt.files += @{path=$reference.path; sha256=$reference.sha256; bytes=(Get-Item -LiteralPath $recordPath).Length}
    $null = Assert-K8sInventory $receipt $pending
    Assert-K8sAssemblyPath $OutputDirectory
    [IO.Directory]::Move($pending, $OutputDirectory)
    return [ordered]@{status='RECONSTRUCTED_NOT_AUTHENTICATED'; evidenceMode=$record.evidenceMode
        outputDirectory=$OutputDirectory; invocationQualification=$reference; files=$receipt.files
        runtimeAcceptance=$false; publicSourceShaQualified=$false}
}

New-K8sPackageInvocationQualification @PSBoundParameters
