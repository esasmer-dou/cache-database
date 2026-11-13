#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SourceRoot,
    [string]$ControlRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-PrivateOwnedContext([hashtable]$Context) {
    foreach ($name in @('repository', 'approvedRepository', 'publicRepository')) {
        if ($Context[$name] -cnotmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}$' -or
            $Context[$name].Split('/')[1] -in @('.', '..')) { throw 'Invalid trusted repository authority.' }
    }
    foreach ($name in @('repositoryId', 'approvedRepositoryId', 'publicRepositoryId', 'runId')) {
        if ($Context[$name] -cnotmatch '^[1-9][0-9]{0,18}$') { throw 'Invalid trusted repository/run identity.' }
    }
    if ($Context.actions -cne 'true' -or $Context.event -cne 'workflow_dispatch' -or
        $Context.api -cne 'https://api.github.com' -or $Context.server -cne 'https://github.com' -or
        [string]::IsNullOrWhiteSpace($Context.token) -or
        $Context.repository -cne $Context.approvedRepository -or $Context.repositoryId -cne $Context.approvedRepositoryId -or
        $Context.publicRepository -ceq $Context.repository -or $Context.publicRepositoryId -ceq $Context.repositoryId -or
        $Context.sourceSha -cnotmatch '^[a-f0-9]{40}$' -or $Context.dispatchSha -cnotmatch '^[a-f0-9]{40}$' -or
        $Context.workflowSha -cne $Context.dispatchSha) { throw 'Explicit private-owned dispatch and approved source authority required.' }
    $prefix = [regex]::Escape($Context.repository) + '/'
    $workflow = [regex]::Match($Context.workflowRef, '^' + $prefix + '(\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml)@refs/heads/[A-Za-z0-9_./-]+$')
    if (!$workflow.Success) {
        throw 'Exact manual workflow identity required.'
    }
    return $workflow.Groups[1].Value
}

function Read-PrivateOwnedImportFunctions([string]$Workflow) {
    $text = [IO.File]::ReadAllText($Workflow)
    $blocks = @([regex]::Matches($text, '(?ms)^      - name: Import pinned actual sample runtime evidence\r?\n(?<body>.*?)(?=^      - name: |\z)'))
    if ($blocks.Count -ne 1) { throw 'Exact canonical import step required.' }
    $run = [regex]::Match($blocks[0].Groups['body'].Value, '(?ms)^        run: \|\r?\n(?<script>.*)\z')
    if (!$run.Success) { throw 'Literal canonical import script required.' }
    $script = (($run.Groups['script'].Value -split '\r?\n') | ForEach-Object {
        if ($_ -and !$_.StartsWith('          ')) { throw 'Canonical import indentation differs.' }
        if ($_) { $_.Substring(10) } else { '' }
    }) -join "`n"
    $tokens = $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput($script, [ref]$tokens, [ref]$errors)
    $functions = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true))
    if ($errors.Count -or (($functions.Name | Sort-Object) -join ',') -cne
        'Assert-PrivateSampleRuntimeRepository,Assert-SampleRuntimeArtifact,Assert-SampleRuntimeInputs,Assert-SampleRuntimeProducer,Assert-SampleRuntimeQualificationInputs,Assert-SampleRuntimeSource,Expand-SampleRuntimeArchive,Receive-SampleRuntimeArchive') {
        throw 'Canonical import function roster differs.'
    }
    # Return original AST text, not rewritten implementations or the YAML entry point.
    foreach ($function in $functions) { $function.Extent.Text }
}

function Assert-PrivateOwnedRepository($Metadata, [string]$Repository, [string]$Id) {
    Assert-PrivateSampleRuntimeRepository $Metadata $Repository $Id
    if ($Metadata.fork -isnot [bool] -or $Metadata.fork) { throw 'Private evidence repository must not be a fork.' }
}

function Assert-PrivateOwnedPublicSource($Metadata, [string]$Repository, [string]$Id) {
    if ([string]$Metadata.id -cne $Id -or $Metadata.full_name -cne $Repository -or
        $Metadata.private -isnot [bool] -or $Metadata.private -or $Metadata.visibility -cne 'public' -or
        $Metadata.fork -isnot [bool] -or $Metadata.fork) { throw 'Exact approved public nonfork source required.' }
}

function Assert-PrivateOwnedProducer($Run, [string]$RunId, [string]$RepositoryId, [string]$SourceSha) {
    Assert-SampleRuntimeProducer $Run $RunId $RepositoryId $SourceSha
}

function Invoke-PrivateOwnedBoundedProcess(
    [Diagnostics.ProcessStartInfo]$Start,
    [ValidateRange(1, 1048576)][int]$MaximumOutputBytes = 1MB,
    [ValidateRange(1, 15000)][int]$TimeoutMilliseconds = 15000
) {
    $Start.UseShellExecute = $false; $Start.RedirectStandardOutput = $true; $Start.RedirectStandardError = $true
    $null = $Start.Environment.Remove('GITHUB_TOKEN'); $null = $Start.Environment.Remove('GH_TOKEN')
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $Start
    $output = [IO.MemoryStream]::new(); $started = $false
    $clock = [Diagnostics.Stopwatch]::StartNew()
    try {
        $null = $process.Start(); $started = $true
        $streams = @($process.StandardOutput.BaseStream, $process.StandardError.BaseStream)
        $buffers = [byte[][]]::new(2); $pending = [object[]]::new(2)
        $finished = [bool[]]::new(2); $received = [long[]]::new(2)
        for ($i = 0; $i -lt 2; $i++) {
            $buffers[$i] = [byte[]]::new(8192)
            $pending[$i] = $streams[$i].ReadAsync($buffers[$i], 0, $buffers[$i].Length)
        }
        # Drain both pipes concurrently; cap bytes before retaining stdout and discard stderr.
        while (!$finished[0] -or !$finished[1]) {
            if ($clock.ElapsedMilliseconds -ge $TimeoutMilliseconds) { throw 'Checkout inspection timed out.' }
            $progress = $false
            for ($i = 0; $i -lt 2; $i++) {
                if ($finished[$i] -or !$pending[$i].IsCompleted) { continue }
                $read = $pending[$i].GetAwaiter().GetResult(); $progress = $true
                if ($read -eq 0) { $finished[$i] = $true; continue }
                $received[$i] += $read
                if ($received[$i] -gt $MaximumOutputBytes) { throw 'Checkout inspection output exceeded byte limit.' }
                if ($i -eq 0) { $output.Write($buffers[$i], 0, $read) }
                $pending[$i] = $streams[$i].ReadAsync($buffers[$i], 0, $buffers[$i].Length)
            }
            if (!$progress) { [Threading.Thread]::Sleep(5) }
        }
        $remaining = $TimeoutMilliseconds - $clock.ElapsedMilliseconds
        if ($remaining -le 0 -or !$process.WaitForExit([int]$remaining)) { throw 'Checkout inspection timed out.' }
        if ($process.ExitCode -ne 0) { throw 'Checkout inspection failed.' }
        return [Text.Encoding]::UTF8.GetString($output.GetBuffer(), 0, [int]$output.Length).TrimEnd("`r", "`n")
    } finally {
        if ($started) { try { if (!$process.HasExited) { $process.Kill($true) } } catch {} }
        $output.Dispose(); $process.Dispose()
    }
}

function Invoke-PrivateOwnedGit([string]$Root, [string[]]$Arguments) {
    $application = Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $start = [Diagnostics.ProcessStartInfo]::new($application.Source)
    $start.ArgumentList.Add('-C'); $start.ArgumentList.Add($Root)
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    return Invoke-PrivateOwnedBoundedProcess $start
}

function Assert-PrivateOwnedCheckout([string]$Root, [string]$Sha, [string]$WorkflowPath = '') {
    if ((Invoke-PrivateOwnedGit $Root @('rev-parse', 'HEAD')) -cne $Sha -or
        (Invoke-PrivateOwnedGit $Root @('status', '--porcelain', '--untracked-files=all'))) {
        throw 'Actual checkout HEAD or clean tracked source differs.'
    }
    if ($WorkflowPath) { $null = Invoke-PrivateOwnedGit $Root @('cat-file', '-e', ($Sha + ':' + $WorkflowPath)) }
}

function Assert-PrivateOwnedOutput([string]$TemporaryRoot, [string]$Source, [string]$Control) {
    $temp = [IO.Path]::GetFullPath($TemporaryRoot).TrimEnd('/')
    if (!$temp -or $temp -ceq '/' -or ![IO.Directory]::Exists($temp)) { throw 'Private runner temporary root required.' }
    $destination = Join-Path $temp 'v019-private-owned-runtime'
    foreach ($root in @($Source, $Control)) {
        $full = [IO.Path]::GetFullPath($root).TrimEnd('/')
        if ($destination -ceq $full -or $destination.StartsWith($full + '/', [StringComparison]::Ordinal) -or
            $full.StartsWith($destination + '/', [StringComparison]::Ordinal)) { throw 'Private evidence must be outside both checkouts.' }
    }
    $ancestor = $temp
    while ($ancestor) {
        if (([IO.File]::GetAttributes($ancestor) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Canonical private temporary root required.' }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    if (Test-Path -LiteralPath $destination) { throw 'Fresh private import directory required.' }
    return $destination
}

function New-PrivateOwnedCheckerStart([string]$Root, [string]$Receipt, [string]$ReceiptPin, [string]$Output, [hashtable]$Qualification = @{}) {
    Assert-SampleRuntimeQualificationInputs $Qualification['docPath'] $Qualification['docPin'] $Qualification['toolPath'] $Qualification['toolPin']
    $application = Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $start = [Diagnostics.ProcessStartInfo]::new($application.Source)
    $start.UseShellExecute = $false; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.WorkingDirectory = $Root
    foreach ($argument in @('--max-old-space-size=256', (Join-Path $Root 'tools/ci/check-v019-sample-coverage.mjs'),
        '--root', $Root, '--summary', (Join-Path $Root 'target/v019-private-runtime-coverage.md'),
        '--runtime-evidence', $Receipt, '--runtime-evidence-sha256', $ReceiptPin,
        '--private-results', (Join-Path $Output 'qualification.json'))) {
        $start.ArgumentList.Add($argument)
    }
    if ($Qualification['docPath']) {
        foreach ($argument in @('--source-equivalence', $Qualification['docPath'], '--source-equivalence-sha256', $Qualification['docPin'],
            '--execution-tooling-overlay', $Qualification['toolPath'], '--execution-tooling-overlay-sha256', $Qualification['toolPin'])) {
            $start.ArgumentList.Add($argument)
        }
    }
    $null = $start.Environment.Remove('GITHUB_TOKEN'); $null = $start.Environment.Remove('GH_TOKEN')
    return $start
}

function Invoke-PrivateOwnedChecker([string]$Root, [string]$Receipt, [string]$ReceiptPin, [string]$Output, [hashtable]$Qualification = @{}) {
    $start = New-PrivateOwnedCheckerStart $Root $Receipt $ReceiptPin $Output $Qualification
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    $stdout = $stderr = $null
    try {
        $stdout = [IO.File]::Open((Join-Path $Output 'checker.stdout'), [IO.FileMode]::CreateNew)
        $stderr = [IO.File]::Open((Join-Path $Output 'checker.stderr'), [IO.FileMode]::CreateNew)
        $null = $process.Start()
        $outTask = $process.StandardOutput.BaseStream.CopyToAsync($stdout); $errTask = $process.StandardError.BaseStream.CopyToAsync($stderr)
        if (!$process.WaitForExit(120000)) { $process.Kill($true); throw 'Unchanged runtime checker timed out.' }
        [void]$outTask.GetAwaiter().GetResult(); [void]$errTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'Unchanged runtime checker rejected evidence.' }
        $resultPath = Join-Path $Output 'qualification.json'
        if (!(Test-Path -LiteralPath $resultPath -PathType Leaf) -or (Get-Item -LiteralPath $resultPath).Length -gt 1MB) {
            throw 'Bounded private checker qualification result required.'
        }
        Assert-PrivateOwnedCheckerResult ([IO.File]::ReadAllText($resultPath) | ConvertFrom-Json -Depth 40) $ReceiptPin $Qualification
    } finally {
        if ($null -ne $stdout) { $stdout.Dispose() }; if ($null -ne $stderr) { $stderr.Dispose() }; $process.Dispose()
    }
}

function Assert-PrivateOwnedCheckerResult($Result, [string]$ReceiptPin, [hashtable]$Qualification) {
    if ($Result.complete -isnot [bool] -or !$Result.complete -or $Result.runtimeQualified -isnot [bool] -or !$Result.runtimeQualified -or
        $Result.releaseAccepted -isnot [bool] -or $Result.releaseAccepted -or
        $Result.evidence -cne 'STATIC_AND_PINNED_FINAL_SAMPLE_RUNTIME' -or
        $Result.runtime.receiptSha256 -cne $ReceiptPin) {
        throw 'Private checker did not return bounded runtime qualification of the pinned original receipt.'
    }
    if ($Qualification['docPath'] -and ($Result.runtime.sourceQualificationChain.kind -cne 'cachedb-v019-source-qualification-chain' -or
        @($Result.runtime.sourceQualificationChain.stages).Count -ne 2)) {
        throw 'Both source qualification stages must be retained in the private result.'
    }
    if ($Qualification['docPath']) {
        $stages = @($Result.runtime.sourceQualificationChain.stages)
        if ($stages[0].manifest.path -cne $Qualification['docPath'] -or $stages[0].manifest.sha256 -cne $Qualification['docPin'] -or
            $stages[1].manifest.path -cne $Qualification['toolPath'] -or $stages[1].manifest.sha256 -cne $Qualification['toolPin']) {
            throw 'Private qualification chain does not retain the exact approved reference pairs.'
        }
    }
}

try {
    if (![OperatingSystem]::IsLinux()) { throw 'Linux private runner required.' }
    $SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).ProviderPath
    $ControlRoot = (Resolve-Path -LiteralPath $ControlRoot).ProviderPath
    $context = @{
        actions = $env:GITHUB_ACTIONS; event = $env:GITHUB_EVENT_NAME; api = $env:GITHUB_API_URL; server = $env:GITHUB_SERVER_URL
        repository = $env:GITHUB_REPOSITORY; repositoryId = $env:GITHUB_REPOSITORY_ID; runId = $env:GITHUB_RUN_ID
        approvedRepository = $env:APPROVED_PRIVATE_REPOSITORY; approvedRepositoryId = $env:APPROVED_PRIVATE_REPOSITORY_ID
        publicRepository = $env:PUBLIC_SOURCE_REPOSITORY; publicRepositoryId = $env:PUBLIC_SOURCE_REPOSITORY_ID
        sourceSha = $env:APPROVED_SOURCE_SHA; dispatchSha = $env:GITHUB_SHA; workflowSha = $env:PRIVATE_WORKFLOW_SHA
        workflowRef = $env:GITHUB_WORKFLOW_REF; token = $env:GITHUB_TOKEN
    }
    $workflowPath = Assert-PrivateOwnedContext $context
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha
    Assert-PrivateOwnedCheckout $ControlRoot $context.dispatchSha $workflowPath
    if ($context.sourceSha -ceq $context.dispatchSha) { Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha $workflowPath }
    foreach ($definition in Read-PrivateOwnedImportFunctions (Join-Path $SourceRoot '.github/workflows/framework-readiness.yml')) {
        . ([scriptblock]::Create($definition))
    }
    Assert-SampleRuntimeInputs $context.event $env:SAMPLE_ARTIFACT_ID $env:SAMPLE_ARTIFACT_SHA256 $env:SAMPLE_RECEIPT_SHA256
    $qualification = @{ docPath = $env:SAMPLE_DOC_PATH; docPin = $env:SAMPLE_DOC_PIN; toolPath = $env:SAMPLE_TOOL_PATH; toolPin = $env:SAMPLE_TOOL_PIN }
    Assert-SampleRuntimeQualificationInputs $qualification.docPath $qualification.docPin $qualification.toolPath $qualification.toolPin
    Assert-SampleRuntimeSource $context.repository $context.repositoryId $context.token $context.sourceSha
    $output = Assert-PrivateOwnedOutput $env:RUNNER_TEMP $SourceRoot $ControlRoot
    $repositoryUri = 'https://api.github.com/repos/' + $context.repository
    $publicUri = 'https://api.github.com/repos/' + $context.publicRepository
    $publicHeaders = @{ Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
    $headers = @{ Authorization = 'Bearer ' + $context.token; Accept = $publicHeaders.Accept; 'X-GitHub-Api-Version' = '2022-11-28' }
    $repository = Invoke-RestMethod -Uri $repositoryUri -Headers $headers -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateOwnedRepository $repository $context.repository $context.repositoryId
    $public = Invoke-RestMethod -Uri $publicUri -Headers $publicHeaders -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateOwnedPublicSource $public $context.publicRepository $context.publicRepositoryId
    $commit = Invoke-RestMethod -Uri ($publicUri + '/commits/' + $context.sourceSha) -Headers $publicHeaders -TimeoutSec 30 -MaximumRedirection 0
    if ($commit.sha -cne $context.sourceSha) { throw 'Approved source commit is not present in the public source repository.' }
    $uri = $repositoryUri + '/actions/artifacts/' + $env:SAMPLE_ARTIFACT_ID
    $metadata = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 30 -MaximumRedirection 0
    Assert-SampleRuntimeArtifact $metadata $env:SAMPLE_ARTIFACT_ID $env:SAMPLE_ARTIFACT_SHA256 $context.repositoryId $context.sourceSha
    $producerId = [string]$metadata.workflow_run.id
    if ($producerId -cnotmatch '^[1-9][0-9]{0,18}$') { throw 'Exact producer run identity required.' }
    $producer = Invoke-RestMethod -Uri ($repositoryUri + '/actions/runs/' + $producerId) -Headers $headers -TimeoutSec 30 -MaximumRedirection 0
    Assert-PrivateOwnedProducer $producer $producerId $context.repositoryId $context.sourceSha
    $null = [IO.Directory]::CreateDirectory($output)
    [IO.File]::SetUnixFileMode($output, [IO.UnixFileMode]448)
    $archive = Join-Path $output 'evidence.zip'
    Receive-SampleRuntimeArchive ($uri + '/zip') $context.token $archive
    $receipt = Expand-SampleRuntimeArchive $archive (Join-Path $output 'evidence') $env:SAMPLE_ARTIFACT_SHA256 $env:SAMPLE_RECEIPT_SHA256
    Invoke-PrivateOwnedChecker $SourceRoot $receipt $env:SAMPLE_RECEIPT_SHA256 $output $qualification
    Assert-PrivateOwnedCheckout $SourceRoot $context.sourceSha
    Assert-PrivateOwnedCheckout $ControlRoot $context.dispatchSha $workflowPath
    $proof = [ordered]@{
        schema = 1; kind = 'PRIVATE_OWNED_RUNTIME_IMPORT_NOT_PUBLIC_CI_OR_RELEASE'
        privateRepository = $context.repository; privateRepositoryId = $context.repositoryId
        publicSourceRepository = $context.publicRepository; publicSourceRepositoryId = $context.publicRepositoryId
        sourceCommitS = $context.sourceSha; workflowCommitD = $context.dispatchSha; dispatchRunId = $context.runId
        producerRunId = $producerId; producerHeadSha = $context.sourceSha; artifactId = $env:SAMPLE_ARTIFACT_ID
        archiveSha256 = $env:SAMPLE_ARTIFACT_SHA256; receiptSha256 = $env:SAMPLE_RECEIPT_SHA256
        qualificationReferences = $qualification
        canonicalWorkflowSha256 = (Get-FileHash (Join-Path $SourceRoot '.github/workflows/framework-readiness.yml')).Hash.ToLowerInvariant()
        runtimeVerifierSha256 = (Get-FileHash (Join-Path $SourceRoot 'tools/ci/verify-v019-sample-runtime.mjs')).Hash.ToLowerInvariant()
        checkerExit = 0; canonicalFunctionsReused = $true
    }
    [IO.File]::WriteAllText((Join-Path $output 'import-proof.json'), ($proof | ConvertTo-Json) + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output 'Private-owned evidence passed the unchanged sample runtime checker; no public CI or release acceptance is implied.'
} catch {
    # No API response, signed URL, credential, repository name or raw report enters a public log.
    throw 'Private-owned evidence import failed; retained private files are not qualification.'
}
