#requires -Version 7.2
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-workflow-triggers-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
$controls = [Collections.Generic.List[string]]::new()
$hashes = [ordered]@{}
function Pass([string]$Name) { $controls.Add($Name); Write-Host "PASS $Name" }
function Reject([string]$Name, [scriptblock]$Action) {
    $rejected = $false
    try { $null = & $Action } catch { $rejected = $true }
    if (!$rejected) { throw "Invalid trigger contract accepted: $Name" }
    Pass $Name
}
function Get-EventPaths([string]$Source, [string]$Event) {
    # Read only the repository's explicit block-style trigger lists; fail on syntax drift.
    $events = @([regex]::Matches($Source, '(?m)^  [a-z_]+:[^\r\n]*\r?$'))
    $eventMatches = @($events | Where-Object { $_.Value -match ('^  ' + [regex]::Escape($Event) + ':') })
    if ($eventMatches.Count -ne 1) { throw "Missing or ambiguous trigger: $Event" }
    $start = $eventMatches[0].Index + $eventMatches[0].Length
    $following = @($events | Where-Object Index -gt $eventMatches[0].Index)
    $end = if ($following.Count) { $following[0].Index } else { $Source.Length }
    $body = $Source.Substring($start, $end - $start)
    $pathBlocks = @([regex]::Matches($body, '(?m)^    paths:\s*\r?$'))
    if ($pathBlocks.Count -ne 1) { throw "Missing or ambiguous paths block: $Event" }
    $tail = $body.Substring($pathBlocks[0].Index + $pathBlocks[0].Length)
    $paths = [Collections.Generic.List[string]]::new()
    foreach ($line in ($tail -split '\r?\n')) {
        if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }
        if ($line -notmatch '^      - "([^"\r\n]+)"\s*$') { break }
        $paths.Add($Matches[1])
    }
    if (!$paths.Count) { throw "Empty or unsupported path-list syntax: $Event" }
    return $paths.ToArray()
}
function Assert-Paths([string[]]$Paths, [string[]]$Required) {
    if (@($Paths | Select-Object -Unique).Count -ne $Paths.Count) { throw 'Duplicate trigger paths.' }
    foreach ($path in $Required) {
        if (@($Paths | Where-Object { $_ -ceq $path }).Count -ne 1) { throw "Missing exact path: $path" }
    }
}
function Get-TriggerBlock([string]$Source) {
    $headers = @([regex]::Matches($Source, '(?m)^on:[^\r\n]*\r?$'))
    if ($headers.Count -ne 1 -or $headers[0].Value.Trim() -cne 'on:') {
        throw 'One explicit block-style workflow trigger is required.'
    }
    $start = $headers[0].Index + $headers[0].Length
    $following = [regex]::Match($Source.Substring($start), '(?m)^[A-Za-z_][A-Za-z0-9_-]*:')
    $length = if ($following.Success) { $following.Index } else { $Source.Length - $start }
    return $Source.Substring($start, $length)
}
function Assert-ManualOnly([string]$Source) {
    $lines = @((Get-TriggerBlock $Source) -split '\r?\n' |
        Where-Object { $_ -notmatch '^\s*(#.*)?$' })
    if ($lines.Count -ne 1 -or $lines[0] -cnotmatch '^  workflow_dispatch:\s*$') {
        throw 'Legacy evidence workflows must have only an explicit manual dispatch trigger.'
    }
}
$contracts = [ordered]@{
    'framework-readiness.yml' = @('cachedb-reactor/**', 'cachedb-spring-data/**', 'tools/ci/**',
        '.github/actions/cachedb-v019-performance/**', '.github/workflows/framework-readiness.yml',
        '.github/workflows/maven-central-publish.yml')
}
foreach ($entry in $contracts.GetEnumerator()) {
    $path = Join-Path $RepositoryRoot ".github/workflows/$($entry.Key)"
    $source = Get-Content -LiteralPath $path -Raw
    $hashes[$entry.Key] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    $triggerBlock = Get-TriggerBlock $source
    $events = @([regex]::Matches($triggerBlock, '(?m)^  ([a-z_]+):[^\r\n]*\r?$') |
        ForEach-Object { $_.Groups[1].Value })
    if ($events.Count -ne 3 -or (@($events | Sort-Object) -join ',') -cne 'pull_request,push,workflow_dispatch') {
        throw 'Framework trigger trust must remain push, pull_request and workflow_dispatch only.'
    }
    Pass 'framework-trusted-trigger-events-preserved'
    foreach ($event in @('pull_request', 'push')) {
        $paths = @(Get-EventPaths $triggerBlock $event)
        Assert-Paths $paths $entry.Value
        Pass "$($entry.Key)-$event-required-paths"
        foreach ($required in $entry.Value) {
            $missing = @($paths | Where-Object { $_ -cne $required })
            Reject "$($entry.Key)-$event-reject-missing-$required" { Assert-Paths $missing $entry.Value }
        }
        $duplicate = @($paths) + @($paths[0])
        Reject "$($entry.Key)-$event-reject-duplicate" { Assert-Paths $duplicate $entry.Value }
        $otherEvent = if ($event -eq 'push') { 'pull_request' } else { 'push' }
        $truncated = "on:`n  ${otherEvent}:`n    paths:`n      - `"unrelated/**`"`n"
        Reject "$($entry.Key)-$event-reject-missing-event" { Get-EventPaths $truncated $event }
    }
}
foreach ($name in @('production-evidence.yml', 'snapshot-provider-evidence.yml')) {
    $path = Join-Path $RepositoryRoot ".github/workflows/$name"
    $source = Get-Content -LiteralPath $path -Raw
    $hashes[$name] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    Assert-ManualOnly $source
    Pass "$name-remains-manual-only"
    foreach ($event in @('push', 'pull_request', 'pull_request_target', 'workflow_run', 'schedule', 'repository_dispatch')) {
        $automatic = "on:`n  workflow_dispatch:`n  ${event}:`njobs:`n  fixture:`n"
        Reject "$name-reject-$event" { Assert-ManualOnly $automatic }
    }
    Reject "$name-reject-duplicate-dispatch" { Assert-ManualOnly "on:`n  workflow_dispatch:`n  workflow_dispatch:`n" }
    Reject "$name-reject-inline-trigger" { Assert-ManualOnly 'on: [workflow_dispatch, push]' }
    Reject "$name-reject-duplicate-trigger-block" { Assert-ManualOnly "on:`n  workflow_dispatch:`non:`n  workflow_dispatch:`n" }
    Reject "$name-reject-missing-dispatch" { Assert-ManualOnly "on:`n  # no event`njobs:`n  fixture:`n" }
    Reject "$name-reject-unsupported-trigger-body" { Assert-ManualOnly "on:`n  workflow_dispatch:`n  <<: *events`n" }
}
$framework = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/workflows/framework-readiness.yml') -Raw
$acceptance = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
if ($framework -notmatch '(?m)^        run: \./tools/ci/check-k8s-acceptance\.ps1\r?$' -or
        './tools/ci/test-workflow-trigger-contract.ps1 -EvidenceDirectory ./target/workflow-trigger-controls' -cnotin $acceptance.Controls) {
    throw 'Framework Readiness must verify sealed Kubernetes execution of the trigger regression controls.'
}
Pass 'framework-verifies-k8s-trigger-control-evidence'
$performance = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/workflows/performance-features-ematech.yml') -Raw
if ($performance -notmatch '(?m)^  workflow_dispatch:\s*$' -or
        $performance -match '(?m)^  (push|pull_request|schedule):') {
    throw 'Feature performance workflow must remain manual-only.'
}
Pass 'feature-performance-remains-manual-only'
[ordered]@{result='PASS'; controls=$controls; sourceOnly=$true; syntheticMutations=$true;
    remoteWorkflowQueried=$false; actualRemoteCiQualified=$false; workflowSha256=$hashes} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Host "WORKFLOW_TRIGGER_CONTROLS_PASS=$($controls.Count); NO_REMOTE_CI_ACCEPTANCE"
