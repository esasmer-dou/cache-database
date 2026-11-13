#requires -Version 7.2
param(
    [string]$Checker = (Join-Path $PSScriptRoot 'check-ga-release-readiness.ps1'),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-ga-workflow-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Resolve-Path -LiteralPath $Checker).Path, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Release checker has parse errors.' }
# Load only the real pure functions, never the checker entrypoint or remote commands.
foreach ($name in @('Get-RequiredPerformanceWorkflows', 'Test-CanonicalK8sRelease', 'Assert-CanonicalK8sArtifact',
    'Select-SuccessfulWorkflowRun', 'Assert-SuccessfulWorkflowJob', 'Get-LatestSuccessfulRun')) {
    $definitions = @($ast.FindAll({param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true))
    if ($definitions.Count -ne 1) { throw "Missing/duplicate checker function: $name" }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}
$passed = [Collections.Generic.List[string]]::new()
function Pass([string]$Name) { $passed.Add($Name); Write-Host "PASS $Name" }
function Reject([string]$Name, [scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw "Invalid synthetic workflow evidence accepted: $Name" }
    Pass $Name
}
# This adapter returns recorded JSON only; it cannot execute gh, git or another native command.
$Repository = 'synthetic/no-remote-query'
$script:runJson = ''; $script:jobJson = ''
$script:jobJsonByRun = @{}
$script:runQueries = 0
$script:jobQueries = [Collections.Generic.List[string]]::new()
function Invoke-Native([string]$Command, [string[]]$Arguments) {
    if ($Command -cne 'gh') { throw 'Synthetic adapter forbids native execution.' }
    if ($Arguments[0] -ceq 'run' -and $Arguments[1] -ceq 'list') {
        $script:runQueries++
        return $script:runJson
    }
    if ($Arguments[0] -ceq 'run' -and $Arguments[1] -ceq 'view') {
        $id = [string]$Arguments[2]
        $script:jobQueries.Add($id)
        if ($script:jobJsonByRun.Count) {
            if (!$script:jobJsonByRun.ContainsKey($id)) { throw 'Unexpected synthetic run ID.' }
            return $script:jobJsonByRun[$id]
        }
        return $script:jobJson
    }
    throw 'Unexpected synthetic gh request.'
}
function Assert-SyntheticQueries([string[]]$ExpectedRunIds, [int]$ExpectedLists = 1) {
    if ($script:runQueries -ne $ExpectedLists -or
            ($script:jobQueries.ToArray() -join ',') -cne ($ExpectedRunIds -join ',')) {
        throw 'Workflow job evidence was not fetched exactly once from the selected run only.'
    }
}
$sha = '1' * 40
$wrong = '2' * 40
$required = @(Get-RequiredPerformanceWorkflows 'v0.19.0')
if ($required.Count -ne 2 -or $required[0] -cne 'Redis Performance Gate' -or
        $required[1] -cne 'EmaTech T1 and v019 Feature Performance Gates') { throw 'Mandatory 0.19 performance workflow names differ.' }
Pass 'v019-requires-both-exact-workflow-names'
if (@(Get-RequiredPerformanceWorkflows 'v0.18.1').Count) { throw 'Old immutable tags acquired new workflow requirements.' }
Pass 'v018-tag-compatibility'
if (@(Get-RequiredPerformanceWorkflows 'v1.0.0').Count -ne 2) { throw 'Later stable tags lost the performance requirements.' }
Pass 'later-stable-tags-require-performance'
Reject 'prerelease-tag' { Get-RequiredPerformanceWorkflows 'v0.19.0-rc1' }
foreach ($workflow in $required) {
    $row = [ordered]@{databaseId=1; workflowName=$workflow; headBranch='main'; headSha=$sha;
        conclusion='success'; status='completed'; event='workflow_dispatch';
        url='https://example.invalid/synthetic-only'; createdAt='2026-01-01T00:00:00Z'}
    $json = ConvertTo-Json -InputObject @($row) -Depth 4
    $safeName = $workflow -replace '[^A-Za-z0-9]', '-'
    $json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "$safeName-gh-synthetic.json") -Encoding utf8
    $runs = @($json | ConvertFrom-Json)
    $selected = Select-SuccessfulWorkflowRun -Runs $runs -WorkflowName $workflow -HeadSha $sha
    if ($selected.headSha -cne $sha) { throw 'Wrong SHA selected.' }
    Pass "$safeName-exact-sha-manual-success"
    $runs[0].headBranch = 'v0.19.0'; $runs[0].event = 'push'
    $null = Select-SuccessfulWorkflowRun -Runs $runs -WorkflowName $workflow -HeadSha $sha
    Pass "$safeName-exact-sha-tag-success"
    Reject "$safeName-missing" { Select-SuccessfulWorkflowRun -Runs @() -WorkflowName $workflow -HeadSha $sha }
    foreach ($mutation in @('wrong-sha','failed','skipped','cancelled','queued','in-progress','wrong-workflow')) {
        $runs = @($json | ConvertFrom-Json)
        switch ($mutation) {
            'wrong-sha' { $runs[0].headSha = $wrong }
            'failed' { $runs[0].conclusion = 'failure' }
            'skipped' { $runs[0].conclusion = 'skipped' }
            'cancelled' { $runs[0].conclusion = 'cancelled' }
            'queued' { $runs[0].status = 'queued' }
            'in-progress' { $runs[0].status = 'in_progress' }
            'wrong-workflow' { $runs[0].workflowName = 'Other workflow' }
        }
        Reject "$safeName-$mutation" { Select-SuccessfulWorkflowRun -Runs $runs -WorkflowName $workflow -HeadSha $sha }
    }
    $jobName = if ($workflow -ceq 'Redis Performance Gate') { 'redis-performance' } else { 'feature-performance' }
    $positiveJobJson = ConvertTo-Json -InputObject @{jobs=@(@{name=$jobName; status='completed'; conclusion='success'})} -Depth 4
    $positiveJobJson | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "$safeName-jobs-synthetic.json") -Encoding utf8
    $script:runJson = $json; $script:jobJson = $positiveJobJson
    $null = Get-LatestSuccessfulRun -WorkflowName $workflow -HeadSha $sha -RequiredJobName $jobName
    Pass "$safeName-required-job-completed-success"
    $script:jobJson = '{"jobs":[]}'
    Reject "$safeName-required-job-missing" { Get-LatestSuccessfulRun -WorkflowName $workflow -HeadSha $sha -RequiredJobName $jobName }
    foreach ($conclusion in @('skipped','failure','cancelled')) {
        $jobs = @(($positiveJobJson | ConvertFrom-Json).jobs); $jobs[0].conclusion=$conclusion
        $script:jobJson = ConvertTo-Json -InputObject @{jobs=$jobs} -Depth 4
        Reject "$safeName-overall-success-but-job-$conclusion" { Get-LatestSuccessfulRun -WorkflowName $workflow -HeadSha $sha -RequiredJobName $jobName }
    }
}
# Exercise the actual Framework Readiness gate body, not a second copy of its required job list.
$frameworkChecks = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
    $node.GetCommandName() -ceq 'Invoke-ReadinessCheck' -and
    @($node.CommandElements | Where-Object {
        $_ -is [Management.Automation.Language.StringConstantExpressionAst] -and
        $_.Value -ceq 'Framework Readiness workflow'
    }).Count -eq 1
}, $true))
if ($frameworkChecks.Count -ne 1) { throw 'Framework Readiness gate is missing or ambiguous.' }
$frameworkBodies = @($frameworkChecks[0].CommandElements | Where-Object {
    $_ -is [Management.Automation.Language.ScriptBlockExpressionAst]
})
if ($frameworkBodies.Count -ne 1) { throw 'Framework Readiness gate body is missing or ambiguous.' }
$frameworkGate = [scriptblock]::Create(($frameworkBodies[0].ScriptBlock.EndBlock.Statements.Extent.Text -join "`n"))
$targetRefValue = 'main'; $targetSha = $sha; $releaseTagValue = 'v0.18.1'
$frameworkRow = [ordered]@{databaseId=101; workflowName='Framework Readiness'; headBranch='main'; headSha=$sha;
    conclusion='success'; status='completed'; event='push';
    url='https://example.invalid/synthetic-only'; createdAt='2026-01-01T00:00:00Z'}
$frameworkJson = ConvertTo-Json -InputObject @($frameworkRow) -Depth 4
$frameworkJobs = @('K8s Acceptance Verification')
$frameworkJobJson = ConvertTo-Json -InputObject @{jobs=@($frameworkJobs | ForEach-Object {
    @{name=$_; status='completed'; conclusion='success'}
})} -Depth 4
$frameworkJson | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'Framework-Readiness-gh-synthetic.json') -Encoding utf8
$frameworkJobJson | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'Framework-Readiness-jobs-synthetic.json') -Encoding utf8
function Check-FrameworkGate([string]$Name, [switch]$RejectRun, [string[]]$ExpectedRunIds = @('101')) {
    $script:runQueries = 0; $script:jobQueries.Clear()
    if ($RejectRun) { Reject $Name { & $frameworkGate } }
    else { $null = & $frameworkGate; Pass $Name }
    Assert-SyntheticQueries -ExpectedRunIds $ExpectedRunIds
}
$script:runJson = $frameworkJson; $script:jobJson = $frameworkJobJson
Check-FrameworkGate 'framework-k8s-verification-completed-success-selected-run'
foreach ($mutation in @('wrong-sha', 'wrong-branch', 'missing', 'incomplete')) {
    $runs = @($frameworkJson | ConvertFrom-Json)
    switch ($mutation) {
        'wrong-sha' { $runs[0].headSha = $wrong }
        'wrong-branch' { $runs[0].headBranch = 'other' }
        'missing' { $runs = @() }
        'incomplete' { $runs[0].status = 'in_progress' }
    }
    $script:runJson = ConvertTo-Json -InputObject $runs -Depth 4
    Check-FrameworkGate "framework-run-$mutation" -RejectRun -ExpectedRunIds @()
}
$script:runJson = $frameworkJson
foreach ($jobName in $frameworkJobs) {
    foreach ($mutation in @('missing', 'duplicate', 'wrong-name', 'queued', 'in-progress', 'skipped', 'failure', 'cancelled', 'no-conclusion')) {
        $jobs = @(($frameworkJobJson | ConvertFrom-Json).jobs)
        $job = @($jobs | Where-Object name -CEQ $jobName)[0]
        switch ($mutation) {
            'missing' { $jobs = @($jobs | Where-Object name -CNE $jobName) }
            'duplicate' { $jobs += $job }
            'wrong-name' { $job.name = 'other-job' }
            'queued' { $job.status = 'queued' }
            'in-progress' { $job.status = 'in_progress' }
            'skipped' { $job.conclusion = 'skipped' }
            'failure' { $job.conclusion = 'failure' }
            'cancelled' { $job.conclusion = 'cancelled' }
            'no-conclusion' { $job.conclusion = $null }
        }
        $script:jobJson = ConvertTo-Json -InputObject @{jobs=$jobs} -Depth 4
        Check-FrameworkGate "framework-$jobName-$mutation" -RejectRun
    }
}
$runs = @($frameworkJson | ConvertFrom-Json); $runs[0].event = 'workflow_dispatch'
$script:runJson = ConvertTo-Json -InputObject $runs -Depth 4
foreach ($mode in @('public-import', 'private-upload')) {
    $metadataJob = if ($mode -ceq 'public-import') { 'public-evidence-import' } else { 'private-evidence-upload' }
    $jobs = @(($frameworkJobJson | ConvertFrom-Json).jobs)
    foreach ($job in $jobs) { $job.conclusion = 'skipped' }
    $jobs += [pscustomobject]@{name=$metadataJob; status='completed'; conclusion='success'}
    $script:jobJson = ConvertTo-Json -InputObject @{jobs=$jobs} -Depth 4
    Check-FrameworkGate "framework-metadata-only-$mode-success-rejected" -RejectRun
}
$script:jobJson = ConvertTo-Json -InputObject @{jobs=@(
    @{name='public-evidence-import'; status='completed'; conclusion='success'}
)} -Depth 4
Check-FrameworkGate 'framework-r07-success-cannot-replace-ordinary-ci' -RejectRun
$runs = @($frameworkJson | ConvertFrom-Json)
$older = ($frameworkJson | ConvertFrom-Json)[0]; $older.databaseId = 102
$script:runJson = ConvertTo-Json -InputObject @($runs[0], $older) -Depth 4
$jobs = @(($frameworkJobJson | ConvertFrom-Json).jobs)
$script:jobJsonByRun = @{
    '101' = (ConvertTo-Json -InputObject @{jobs=@(@{name='public-evidence-import'; status='completed'; conclusion='success'})} -Depth 4)
    '102' = (ConvertTo-Json -InputObject @{jobs=@($jobs[0])} -Depth 4)
}
Check-FrameworkGate 'framework-metadata-cannot-borrow-verification-from-another-run' -RejectRun
$script:jobJsonByRun['102'] = $frameworkJobJson
Check-FrameworkGate 'framework-selected-run-cannot-fallback-to-older-complete-run' -RejectRun
$script:jobJsonByRun = @{}
$script:runJson = $frameworkJson; $script:jobJson = $frameworkJobJson
$jobEvidence = @{}
foreach ($i in 1..51) { $jobEvidence["job-$i"] = @{name="job-$i"; status='completed'; conclusion='success'} }
$script:jobJson = ConvertTo-Json -InputObject @{jobs=@($jobEvidence.Values)} -Depth 4
$script:runQueries = 0; $script:jobQueries.Clear()
Reject 'required-job-evidence-over-50-rejected' {
    Get-LatestSuccessfulRun -WorkflowName 'Framework Readiness' -HeadSha $sha -RequiredJobName 'job-1'
}
Assert-SyntheticQueries -ExpectedRunIds @('101')
$jobEvidence.Remove('job-51')
$script:jobJson = ConvertTo-Json -InputObject @{jobs=@($jobEvidence.Values)} -Depth 4
$script:runQueries = 0; $script:jobQueries.Clear()
$null = Get-LatestSuccessfulRun -WorkflowName 'Framework Readiness' -HeadSha $sha -RequiredJobNames @($jobEvidence.Keys)
Assert-SyntheticQueries -ExpectedRunIds @('101')
Pass 'required-job-names-50-boundary-single-selected-run-query'
$script:jobJson = $frameworkJobJson
foreach ($mutation in @('empty', 'null', 'blank', 'duplicate', 'over-50', 'mixed-single-and-list')) {
    $names = @($frameworkJobs)
    $parameters = @{WorkflowName='Framework Readiness'; HeadSha=$sha}
    switch ($mutation) {
        'empty' { $names = @() }
        'null' { $names = $null }
        'blank' { $names = @('framework-readiness', ' ') }
        'duplicate' { $names = @('framework-readiness', 'framework-readiness') }
        'over-50' { $names = @(1..51 | ForEach-Object { "job-$_" }) }
        'mixed-single-and-list' { $parameters.RequiredJobName = 'framework-readiness' }
    }
    $parameters.RequiredJobNames = $names
    $script:runQueries = 0; $script:jobQueries.Clear()
    Reject "required-job-names-$mutation-rejected-before-query" { Get-LatestSuccessfulRun @parameters }
    Assert-SyntheticQueries -ExpectedRunIds @() -ExpectedLists 0
}
# Execute only the actual skip branch against a recording Add-Check stub.
$skip = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.IfStatementAst] -and
    $node.Clauses[0].Item1.Extent.Text.Trim() -eq '$SkipRemoteWorkflowChecks'
}, $true))
if ($skip.Count -ne 1) { throw 'Remote skip branch is missing or ambiguous.' }
$script:skipRows = [Collections.Generic.List[object]]::new()
function Add-Check([string]$Name, [string]$Status, [string]$Details) {
    $script:skipRows.Add([pscustomobject]@{name=$Name; status=$Status})
}
& ([scriptblock]::Create(($skip[0].Clauses[0].Item2.Statements.Extent.Text -join "`n")))
if ($skipRows.Count -ne 1 -or $skipRows[0].status -cne 'FAIL') { throw 'SkipRemoteWorkflowChecks did not remain release-blocking.' }
Pass 'skip-remote-checks-remains-fail'
$source = Get-Content -LiteralPath $Checker -Raw
if ($source -notmatch 'foreach\s*\(\$workflowName\s+in\s+@\(Get-RequiredPerformanceWorkflows' -or
        $source -notmatch 'Get-LatestSuccessfulRun -WorkflowName \$workflowName -HeadSha \$targetSha -RequiredJobName \$jobName') {
    throw 'Version-aware performance selection is not wired into the real release checker.'
}
Pass 'real-checker-wires-exact-release-sha-without-branch-filter'
[ordered]@{result='PASS'; controls=$passed; syntheticOnly=$true; remoteWorkflowQueried=$false;
    actualRemoteCiQualified=$false; checkerSha256=(Get-FileHash -LiteralPath $Checker -Algorithm SHA256).Hash} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Host "GA_WORKFLOW_SYNTHETIC_CONTROLS_PASS=$($passed.Count); NO_REMOTE_CI_ACCEPTANCE"
