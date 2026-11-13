#requires -Version 7.3
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$SummaryPath = 'target/v019-sample-coverage.md',
    [string]$RuntimeEvidencePath = '',
    [string]$RuntimeEvidenceSha256 = '',
    [string]$SourceEquivalencePath = '',
    [string]$SourceEquivalenceSha256 = '',
    [string]$ExecutionToolingOverlayPath = '',
    [string]$ExecutionToolingOverlaySha256 = '',
    [string]$PrivateResultsPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$hasEvidencePath = $PSBoundParameters.ContainsKey('RuntimeEvidencePath')
$hasEvidenceHash = $PSBoundParameters.ContainsKey('RuntimeEvidenceSha256')
if ($hasEvidencePath -ne $hasEvidenceHash) {
    throw 'Provide RuntimeEvidencePath and RuntimeEvidenceSha256 together.'
}
if ($hasEvidencePath -and ([string]::IsNullOrWhiteSpace($RuntimeEvidencePath) -or
        $RuntimeEvidenceSha256 -cnotmatch '^[a-f0-9]{64}$')) {
    throw 'Explicit receipt path and coordinator-pinned lowercase SHA256 required.'
}
$qualificationNames = @('SourceEquivalencePath', 'SourceEquivalenceSha256', 'ExecutionToolingOverlayPath', 'ExecutionToolingOverlaySha256')
$qualificationCount = 0
foreach ($name in $qualificationNames) { if ($PSBoundParameters.ContainsKey($name)) { $qualificationCount++ } }
if ($qualificationCount) {
    if ($qualificationCount -ne 4 -or !$hasEvidencePath -or !$PrivateResultsPath -or
        $SourceEquivalenceSha256 -cnotmatch '^[a-f0-9]{64}$' -or $ExecutionToolingOverlaySha256 -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Qualification requires both explicit path/SHA256 pairs, receipt pins and private results.'
    }
    foreach ($member in @($SourceEquivalencePath, $ExecutionToolingOverlayPath)) {
        if (!$member -or $member.Length -gt 1024 -or $member.StartsWith('/') -or $member -match '[\\:\x00-\x1f\x7f]' -or
            @($member.Split('/') | Where-Object { !$_ -or $_ -in @('.', '..') -or $_ -cne $_.Trim() -or $_.EndsWith('.') }).Count) {
            throw 'Unsafe qualification manifest path.'
        }
    }
}
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$node = Get-Command node -ErrorAction Stop
$checker = Join-Path $root 'tools/ci/check-v019-sample-coverage.mjs'
$arguments = @($checker, '--root', $root, '--summary', $SummaryPath)
if ($hasEvidencePath) {
    $receipt = (Resolve-Path -LiteralPath $RuntimeEvidencePath).Path
    $arguments += @('--runtime-evidence', $receipt, '--runtime-evidence-sha256', $RuntimeEvidenceSha256)
}
if ($qualificationCount) {
    $arguments += @('--source-equivalence', $SourceEquivalencePath, '--source-equivalence-sha256', $SourceEquivalenceSha256,
        '--execution-tooling-overlay', $ExecutionToolingOverlayPath, '--execution-tooling-overlay-sha256', $ExecutionToolingOverlaySha256)
}
if ($PrivateResultsPath) { $arguments += @('--private-results', $PrivateResultsPath) }
& $node.Source @arguments
if ($LASTEXITCODE -ne 0) {
    throw 'Required sample scenario matrix is incomplete; disabled, deferred or mismatched evidence cannot certify a release.'
}
