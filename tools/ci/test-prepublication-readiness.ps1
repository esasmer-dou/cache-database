#requires -Version 7.2
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-prepublication-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
# Scenario location changes must not rebase the native adapter's control file.
$EvidenceDirectory = (Resolve-Path -LiteralPath $EvidenceDirectory).ProviderPath
$checkerPath = Join-Path $RepositoryRoot 'tools/ci/check-ga-release-readiness.ps1'
$checker = Get-Content -LiteralPath $checkerPath -Raw
$tokens=$null; $errors=$null
$ast = [Management.Automation.Language.Parser]::ParseInput($checker, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Checker parse errors.' }
$native = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Native'
}, $true))
if ($native.Count -ne 1) { throw 'Expected exactly one native adapter.' }
# Replace only the native boundary in a private copy. The actual checker entrypoint runs unchanged.
$adapter = @'
function Invoke-Native([string]$Command, [string[]]$Arguments) {
    $f = Get-Content -LiteralPath $env:CACHEDB_PUBLICATION_CONTROL -Raw | ConvertFrom-Json
    @{command=$Command; arguments=$Arguments} | ConvertTo-Json -Compress |
        Add-Content -LiteralPath ($env:CACHEDB_PUBLICATION_CONTROL + '.calls.jsonl')
    if ($Command -ceq 'git') {
        if ($Arguments[0] -ceq 'ls-remote') { return @() }
        if ($Arguments[0] -ceq 'status') { return $f.dirty }
        if ($Arguments[0] -ceq 'rev-parse') {
            if ($Arguments[-1] -ceq 'HEAD^{commit}') { return $f.head }
            if (!$f.tagExists) { throw 'Synthetic missing release tag.' }
            return $f.tag
        }
    }
    $workflows=@('Framework Readiness','Production Evidence','Snapshot Provider Evidence',
        'Redis Performance Gate','EmaTech T1 and v019 Feature Performance Gates','Oracle Data Guard Evidence')
    if ($Command -ceq 'gh' -and $Arguments[0] -ceq 'run' -and $Arguments[1] -ceq 'list') {
        $name=$Arguments[[array]::IndexOf($Arguments,'--workflow')+1]
        $id=[array]::IndexOf($workflows,$name)
        if ($id -lt 0) { throw 'Unexpected workflow query.' }
        if ($f.badWorkflow -ceq $name -and $f.mutation -ceq 'missing') { return '[]' }
        $row=@{databaseId=100+$id; workflowName=$name; headBranch='main'; headSha=$f.tag;
            conclusion='success'; status='completed'; event='workflow_dispatch'; url='https://example.invalid/synthetic'}
        if ($id -eq 0) { $row.event='push' }
        if ($f.badWorkflow -ceq $name) {
            switch ($f.mutation) {
                'wrong-sha' { $row.headSha='2'*40 }
                'failed' { $row.conclusion='failure' }
                'skipped' { $row.conclusion='skipped' }
            }
        }
        return ConvertTo-Json -InputObject @($row) -Depth 4
    }
    if ($Command -ceq 'gh' -and $Arguments[0] -ceq 'run' -and $Arguments[1] -ceq 'view') {
        $id=[int]$Arguments[2]-100
        $jobNames=@(switch ($id) {
            0 { 'K8s Acceptance Verification' }
            3 { 'redis-performance' }
            4 { 'feature-performance' }
            default { throw 'Unexpected job query.' }
        })
        $jobs=@(foreach ($jobName in $jobNames) {
            $conclusion=if ($f.badWorkflow -ceq $workflows[$id] -and
                    ($f.mutation -ceq 'job-skipped' -or $f.mutation -ceq "job-skipped-$jobName")) {'skipped'} else {'success'}
            @{name=$jobName; status='completed'; conclusion=$conclusion}
        })
        return ConvertTo-Json -InputObject @{jobs=$jobs} -Depth 4
    }
    if ($Command -ceq 'gh' -and ($Arguments -join ' ') -ceq
        'api --method GET repos/synthetic/no-remote-query/actions/runs/100/artifacts?per_page=100') {
        $artifact=@{id=200;name='k8s-acceptance-verification';expired=$false;size_in_bytes=512;digest=('sha256:'+('a'*64))
            url='https://api.github.com/repos/synthetic/no-remote-query/actions/artifacts/200'
            archive_download_url='https://api.github.com/repos/synthetic/no-remote-query/actions/artifacts/200/zip'
            workflow_run=@{id=100;head_sha=$f.tag}}
        $artifacts=@($artifact)
        switch ($f.mutation) {
            'artifact-missing' { $artifacts=@() }
            'artifact-duplicate' { $artifacts=@($artifact,$artifact) }
            'artifact-expired' { $artifact.expired=$true }
            'artifact-no-digest' { $artifact.digest='' }
            'artifact-wrong-source' { $artifact.workflow_run.head_sha='2'*40 }
            'artifact-wrong-run' { $artifact.workflow_run.id=101 }
        }
        return ConvertTo-Json -InputObject @{artifacts=$artifacts} -Depth 5
    }
    if ($Command -ceq 'pwsh' -and $f.allowAssets) { return 'SYNTHETIC_POST_PUBLICATION_CHECK' }
    throw "Synthetic boundary forbids native execution: $Command"
}
'@
$mockChecker = $checker.Substring(0, $native[0].Extent.StartOffset) + $adapter + $checker.Substring($native[0].Extent.EndOffset)
$workflowPath=Join-Path $RepositoryRoot '.github/workflows/maven-central-publish.yml'
$workflow=Get-Content -LiteralPath $workflowPath -Raw
$gate=[regex]::Match($workflow,'(?ms)^      - name: Require stable publication source and workflow evidence\r?\n(.*?)(?=^      - name:|\z)')
if (!$gate.Success -or $gate.Groups[1].Value -match '(?m)^        (if|continue-on-error):') {
    throw 'Stable publication guard is missing or conditional.'
}
$body=[regex]::Match($gate.Groups[1].Value,'(?ms)^        run: \|\r?\n(.*)$')
if (!$body.Success) { throw 'Publication guard body missing.' }
$gateLines=@($body.Groups[1].Value -split '\r?\n' | ForEach-Object {
    if ($_ -eq '') { '' } elseif ($_.StartsWith('          ')) { $_.Substring(10) } else { throw 'Unexpected guard indentation.' }
})
$gateCode=[scriptblock]::Create(($gateLines -join "`n"))
if ($gate.Index -ge $workflow.IndexOf('      - name: Import GPG signing key') -or
        $gate.Index -ge $workflow.IndexOf('      - name: Publish signed artifacts to Maven Central')) {
    throw 'Source/workflow gate must precede signing and publication.'
}
$passed=[Collections.Generic.List[string]]::new()
function Pass([string]$Name) { $passed.Add($Name); Write-Host "PASS $Name" }
Pass 'unconditional-guard-precedes-signing-and-publication'
$sha='1'*40
$envNames=@('PUBLICATION_TAG','PUBLICATION_REF','PUBLICATION_REPOSITORY','PUBLICATION_TARGET',
    'PUBLICATION_COVERAGE','PUBLICATION_FULL_GA','CACHEDB_PUBLICATION_CONTROL',
    'CENTRAL_USERNAME','CENTRAL_PASSWORD','GPG_PRIVATE_KEY','GPG_PASSPHRASE')
$saved=@{}
foreach ($name in $envNames) { $saved[$name]=[Environment]::GetEnvironmentVariable($name) }
function git {
    $global:LASTEXITCODE=0
    if ($args[0] -ceq 'status') { return $script:scenario.dirty }
    if ($args[0] -ceq 'rev-parse') {
        if ($args[-1] -ceq 'HEAD') { return $script:scenario.head }
        if (!$script:scenario.tagExists) { $global:LASTEXITCODE=1; return }
        return $script:scenario.tag
    }
    throw 'Synthetic git boundary forbids this command.'
}
function Run-Scenario([string]$Name, [hashtable]$Changes, [bool]$ExpectedPass=$true, [switch]$DirectPost) {
    $script:scenario=@{version='0.19.0'; head=$sha; tag=$sha; dirty=''; tagExists=$true;
        badWorkflow=''; mutation=''; allowAssets=[bool]$DirectPost; fullGa='false'; ref='v0.19.0'; tagInput=''}
    foreach ($key in $Changes.Keys) { $script:scenario[$key]=$Changes[$key] }
    $dir=Join-Path $EvidenceDirectory $Name
    $null=New-Item -ItemType Directory -Path (Join-Path $dir 'tools/ci') -Force
    $null=New-Item -ItemType Directory -Path (Join-Path $dir 'docs')
    '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>'+$scenario.version+'</version></project>' |
        Set-Content -LiteralPath (Join-Path $dir 'pom.xml') -Encoding utf8
    $mockChecker | Set-Content -LiteralPath (Join-Path $dir 'tools/ci/check-ga-release-readiness.ps1') -Encoding utf8
    foreach ($path in @('docs/ga-migration-coverage-template.csv','tools/ci/validate-migration-coverage-report.ps1',
            'tools/ci/run-local-docker-ha-preflight.ps1')) {
        'SYNTHETIC_PRESENCE_ONLY' | Set-Content -LiteralPath (Join-Path $dir $path) -Encoding utf8
    }
    $control=Join-Path $dir 'control.json'
    $scenario | ConvertTo-Json | Set-Content -LiteralPath $control -Encoding utf8
    $env:CACHEDB_PUBLICATION_CONTROL=$control
    $env:PUBLICATION_TAG=$scenario.tagInput; $env:PUBLICATION_REF=$scenario.ref
    $env:PUBLICATION_REPOSITORY='synthetic/no-remote-query'; $env:PUBLICATION_TARGET='main'
    $env:PUBLICATION_COVERAGE='docs/not-required.csv'; $env:PUBLICATION_FULL_GA=$scenario.fullGa
    foreach ($secretName in @('CENTRAL_USERNAME','CENTRAL_PASSWORD','GPG_PRIVATE_KEY','GPG_PASSPHRASE')) {
        [Environment]::SetEnvironmentVariable($secretName,'SYNTHETIC_NOT_A_SECRET')
    }
    $failure=$null
    Push-Location $dir
    try {
        if ($DirectPost) {
            & './tools/ci/check-ga-release-readiness.ps1' -Repository 'synthetic/no-remote-query' `
                -ReleaseTag 'v0.19.0' -EvidenceScope FrameworkPackage -SkipSecretChecks
        } else { & $gateCode }
    } catch { $failure=$_.Exception.Message }
    finally { Pop-Location }
    [string]$failure | Set-Content -LiteralPath (Join-Path $dir 'error.txt') -Encoding utf8
    if (($null -eq $failure) -ne $ExpectedPass) { throw "Unexpected synthetic result ${Name}: $failure" }
    if (!$ExpectedPass -and $scenario.badWorkflow) {
        $report=Get-Content -LiteralPath (Join-Path $dir 'target/ga-release-readiness-summary.md') -Raw
        if (!$report.Contains(('| '+$scenario.badWorkflow+' workflow | `FAIL` |'))) {
            throw "Negative control did not fail its intended workflow: $Name"
        }
    }
    $callsPath=$control+'.calls.jsonl'
    $calls=@(if (Test-Path -LiteralPath $callsPath) { Get-Content -LiteralPath $callsPath | ConvertFrom-Json })
    if (!$DirectPost -and @($calls | Where-Object command -ceq 'pwsh').Count) { throw 'Pre-publication queried post-publication distribution.' }
    if ($DirectPost -and !@($calls | Where-Object command -ceq 'pwsh').Count) { throw 'Post-publication distribution check disappeared.' }
    if ($ExpectedPass -and !$DirectPost -and $scenario.version -eq '0.19.0') {
        $queries=@($calls | Where-Object { $_.command -ceq 'gh' -and $_.arguments[1] -ceq 'list' })
        $expected=if ($scenario.fullGa -eq 'true') {2} else {1}
        if ($queries.Count -ne $expected) { throw 'Canonical publication requires its verifier, plus Data Guard only for full GA.' }
        if (@($queries | Where-Object { $_.arguments -contains 'Production Evidence' -or
            $_.arguments -contains 'Snapshot Provider Evidence' -or $_.arguments -contains 'Redis Performance Gate' -or
            $_.arguments -contains 'EmaTech T1 and v019 Feature Performance Gates' }).Count) {
            throw 'Canonical publication must never replay or require superseded product workflows.'
        }
        $jobQueries=@($calls | Where-Object { $_.command -ceq 'gh' -and $_.arguments[1] -ceq 'view' })
        $jobRunIds=@($jobQueries | ForEach-Object { [string]$_.arguments[2] } | Sort-Object)
        if ($jobQueries.Count -ne 1 -or ($jobRunIds -join ',') -cne '100') {
            throw 'Canonical publication must validate its selected K8s verifier job.'
        }
        if (@($calls | Where-Object { $_.command -ceq 'gh' -and $_.arguments[0] -ceq 'api' }).Count -ne 1) {
            throw 'Canonical publication must require the selected verifier artifact proof exactly once.'
        }
    }
    if ($scenario.version -eq '0.18.1' -and $scenario.fullGa -eq 'false' -and $calls.Count) {
        throw 'Legacy immutable rerun acquired new checker dependencies.'
    }
    Pass $Name
}
try {
    Run-Scenario 'stable-framework-requires-canonical-verifier-and-proof' @{}
    Run-Scenario 'explicit-full-ga-adds-dataguard-without-assets' @{fullGa='true'}
    Run-Scenario 'legacy-immutable-tag-keeps-old-requirements' @{version='0.18.1'; ref='v0.18.1'}
    Run-Scenario 'legacy-tag-cannot-hide-new-source' @{ref='v0.18.1'} $false
    Run-Scenario 'legacy-head-mismatch' @{version='0.18.1'; ref='v0.18.1'; head='2'*40} $false
    Run-Scenario 'missing-tag' @{tagExists=$false} $false
    Run-Scenario 'branch-is-not-release-tag' @{ref='main'} $false
    Run-Scenario 'wrong-head' @{head='2'*40} $false
    Run-Scenario 'dirty-source' @{dirty=' M pom.xml'} $false
    foreach ($workflowName in @('Framework Readiness')) {
        foreach ($mutation in @('missing','wrong-sha','failed','skipped')) {
            Run-Scenario (($workflowName -replace '[^A-Za-z0-9]','-')+'-'+$mutation) `
                @{badWorkflow=$workflowName; mutation=$mutation} $false
        }
    }
    foreach ($workflowName in @('Framework Readiness')) {
        Run-Scenario (($workflowName -replace '[^A-Za-z0-9]','-')+'-job-skipped') `
            @{badWorkflow=$workflowName; mutation='job-skipped'} $false
    }
    foreach ($jobName in @('K8s Acceptance Verification')) {
        Run-Scenario ("Framework-Readiness-$jobName-job-skipped") `
            @{badWorkflow='Framework Readiness'; mutation="job-skipped-$jobName"} $false
    }
    foreach ($mutation in @('artifact-missing','artifact-duplicate','artifact-expired','artifact-no-digest',
        'artifact-wrong-source','artifact-wrong-run')) {
        Run-Scenario $mutation @{badWorkflow='Framework Readiness';mutation=$mutation} $false
    }
    Run-Scenario 'full-ga-still-requires-physical-dataguard' @{fullGa='true';badWorkflow='Oracle Data Guard Evidence';mutation='missing'} $false
    Run-Scenario 'post-publication-still-checks-distribution' @{} -DirectPost
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name,$saved[$name]) }
}
if (@($passed | Select-Object -Unique).Count -ne $passed.Count) { throw 'Duplicate publication control names.' }
[ordered]@{result='PASS'; controls=$passed; syntheticOnly=$true; nativeAdapterOnly=$true;
    remoteWorkflowQueried=$false; actualRemoteCiQualified=$false; publicationExecuted=$false;
    checkerSha256=(Get-FileHash -LiteralPath $checkerPath).Hash; workflowSha256=(Get-FileHash -LiteralPath $workflowPath).Hash} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Host "PREPUBLICATION_SYNTHETIC_CONTROLS_PASS=$($passed.Count); NO_REMOTE_CI_OR_PUBLICATION_ACCEPTANCE"
