#requires -Version 7.2
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($env:KUBERNETES_SERVICE_HOST)) {
    throw 'Run this synthetic contract test only in the approved Kubernetes test lane.'
}
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-staging-ha-contract-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
$passed = [Collections.Generic.List[string]]::new()
$sourcePins = [ordered]@{}

# Exercise unchanged entrypoints in private trees. Only their child smoke scripts and sleep are mocked.
$smokeStub = @'
param(
    [string]$MavenExecutable,
    [string]$RedisUri,
    [string]$PostgresUrl,
    [string]$PostgresUser,
    [string]$PostgresPassword,
    [string]$MssqlUrl,
    [string]$MssqlUser,
    [string]$MssqlPassword,
    [string]$ReportsDir
)
$ErrorActionPreference = 'Stop'
$control = Get-Content -LiteralPath $env:CACHEDB_STAGING_HA_MOCK_CONTROL -Raw | ConvertFrom-Json
$phase = Split-Path -Leaf $ReportsDir
if ($MavenExecutable -cne 'synthetic-maven-must-not-run') { throw 'Maven argument forwarding changed.' }
if ($control.provider -ceq 'redis') {
    if ($RedisUri -cne 'redis://synthetic.invalid:6379' -or $PostgresUrl -cne 'jdbc:postgresql://synthetic.invalid/test' -or
            $PostgresUser -cne 'synthetic-user' -or $PostgresPassword -cne 'synthetic-secret-do-not-report') {
        throw 'Redis/PostgreSQL argument forwarding changed.'
    }
} elseif ($MssqlUrl -cne 'jdbc:sqlserver://synthetic.invalid;databaseName=test' -or
        $MssqlUser -cne 'synthetic-user' -or $MssqlPassword -cne 'synthetic-secret-do-not-report') {
    throw 'MSSQL argument forwarding changed.'
}
@{event='smoke'; phase=$phase; reportsDir=$ReportsDir} | ConvertTo-Json -Compress |
    Add-Content -LiteralPath ($env:CACHEDB_STAGING_HA_MOCK_CONTROL + '.calls.jsonl')
$null = New-Item -ItemType Directory -Path $ReportsDir
'SYNTHETIC_SMOKE_NOT_LIVE_HA' | Set-Content -LiteralPath (Join-Path $ReportsDir 'mock-smoke.txt')
if ($control.failure -ceq ('throw-' + $phase)) { throw ('synthetic-smoke-failure-' + $phase) }
if ($control.failure -ceq ('exit-' + $phase)) { exit 7 }
'SYNTHETIC_SMOKE_RETURNED'
'@

function Start-Sleep {
    param([int]$Seconds)
    $control = Get-Content -LiteralPath $env:CACHEDB_STAGING_HA_MOCK_CONTROL -Raw | ConvertFrom-Json
    @{event='wait'; seconds=$Seconds} | ConvertTo-Json -Compress |
        Add-Content -LiteralPath ($env:CACHEDB_STAGING_HA_MOCK_CONTROL + '.calls.jsonl')
    if ($control.failure -ceq 'throw-wait') { throw 'synthetic-wait-failure' }
}

$cases = @(
    @{name='default-zero-wait'; wait=0; smokeOnly=$false; failure=''; error='*HA is NOT QUALIFIED*';
        status='ha-not-qualified'; before='passed'; after='passed'; phases='before-wait,after-wait'; failurePhase=$null},
    @{name='default-positive-wait'; wait=120; smokeOnly=$false; failure=''; error='*HA is NOT QUALIFIED*';
        status='ha-not-qualified'; before='passed'; after='passed'; phases='before-wait,after-wait'; failurePhase=$null},
    @{name='smoke-only-zero-wait'; wait=0; smokeOnly=$true; failure=''; error='';
        status='smoke-only-passed'; before='passed'; after='passed'; phases='before-wait,after-wait'; failurePhase=$null},
    @{name='smoke-only-positive-wait'; wait=120; smokeOnly=$true; failure=''; error='';
        status='smoke-only-passed'; before='passed'; after='passed'; phases='before-wait,after-wait'; failurePhase=$null},
    @{name='before-throws-default'; wait=0; smokeOnly=$false; failure='throw-before-wait'; error='synthetic-smoke-failure-before-wait';
        status='smoke-failed'; before='failed'; after='not-run'; phases='before-wait'; failurePhase='beforeSmoke'},
    @{name='before-throws-smoke-only'; wait=0; smokeOnly=$true; failure='throw-before-wait'; error='synthetic-smoke-failure-before-wait';
        status='smoke-failed'; before='failed'; after='not-run'; phases='before-wait'; failurePhase='beforeSmoke'},
    @{name='after-throws-default'; wait=0; smokeOnly=$false; failure='throw-after-wait'; error='synthetic-smoke-failure-after-wait';
        status='smoke-failed'; before='passed'; after='failed'; phases='before-wait,after-wait'; failurePhase='afterSmoke'},
    @{name='after-throws-smoke-only'; wait=0; smokeOnly=$true; failure='throw-after-wait'; error='synthetic-smoke-failure-after-wait';
        status='smoke-failed'; before='passed'; after='failed'; phases='before-wait,after-wait'; failurePhase='afterSmoke'},
    @{name='before-nonzero-exit'; wait=0; smokeOnly=$true; failure='exit-before-wait'; error='*smoke failed with exit code 7.*';
        status='smoke-failed'; before='failed'; after='not-run'; phases='before-wait'; failurePhase='beforeSmoke'},
    @{name='after-nonzero-exit'; wait=0; smokeOnly=$true; failure='exit-after-wait'; error='*smoke failed with exit code 7.*';
        status='smoke-failed'; before='passed'; after='failed'; phases='before-wait,after-wait'; failurePhase='afterSmoke'},
    @{name='wait-throws'; wait=120; smokeOnly=$true; failure='throw-wait'; error='synthetic-wait-failure';
        status='smoke-failed'; before='passed'; after='not-run'; phases='before-wait'; failurePhase='wait'},
    @{name='negative-wait-rejected'; wait=-1; smokeOnly=$true; failure=''; error='*ExternalFailoverWaitSeconds*';
        status=''; before=''; after=''; phases=''; failurePhase=$null}
)

$workflow = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/workflows/production-ga-staging-evidence.yml') -Raw
$savedControl = [Environment]::GetEnvironmentVariable('CACHEDB_STAGING_HA_MOCK_CONTROL')
try {
    foreach ($provider in @('redis', 'mssql')) {
        $fileName = "run-staging-$provider-ha-evidence.ps1"
        $source = Join-Path $RepositoryRoot "tools/ci/$fileName"
        $sourcePins[$fileName] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        $label = if ($provider -ceq 'redis') { 'Redis' } else { 'MSSQL' }
        $step = [regex]::Match($workflow, ('(?ms)^      - name: Run staging ' + $label + ' HA evidence\r?\n(.*?)(?=^      - name:|\z)'))
        if (-not $step.Success -or -not $step.Groups[1].Value.Contains($fileName) -or
                $step.Groups[1].Value -match 'ReconnectSmokeOnly|continue-on-error:') {
            throw "The $provider HA workflow must use the fail-closed default, not smoke-only execution."
        }

        $root = Join-Path $EvidenceDirectory $provider
        $ci = Join-Path $root 'tools/ci'
        $null = New-Item -ItemType Directory -Path $ci -Force
        $subject = Join-Path $ci $fileName
        Copy-Item -LiteralPath $source -Destination $subject
        if ((Get-FileHash -LiteralPath $subject -Algorithm SHA256).Hash -cne
                (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) { throw 'Entrypoint copy changed.' }
        $child = if ($provider -ceq 'redis') { 'run-multi-instance-coordination-evidence.ps1' } else { 'run-mssql-provider-evidence.ps1' }
        $smokeStub | Set-Content -LiteralPath (Join-Path $ci $child) -Encoding utf8
        $reportsName = if ($provider -ceq 'redis') { 'cachedb-staging-ha-reports' } else { 'cachedb-staging-mssql-ha-reports' }
        $reportsRoot = Join-Path $root "target/$reportsName"
        $null = New-Item -ItemType Directory -Path (Join-Path $reportsRoot 'historical-run') -Force
        'PRESERVE_PRIOR_REPORT' | Set-Content -LiteralPath (Join-Path $reportsRoot "staging-$provider-ha-evidence.md")
        'PRESERVE_PRIOR_RUN' | Set-Content -LiteralPath (Join-Path $reportsRoot 'historical-run/report.json')

        foreach ($case in $cases) {
            $caseName = "$provider-$($case.name)"
            $priorHashes = @{}
            Get-ChildItem -LiteralPath $reportsRoot -Recurse -File | ForEach-Object {
                $priorHashes[$_.FullName] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
            $priorRuns = @(Get-ChildItem -LiteralPath $reportsRoot -Directory | ForEach-Object { $_.Name })
            $controlPath = Join-Path $root ($case.name + '.json')
            @{provider=$provider; failure=$case.failure} | ConvertTo-Json | Set-Content -LiteralPath $controlPath
            $env:CACHEDB_STAGING_HA_MOCK_CONTROL = $controlPath
            $arguments = @{MavenExecutable='synthetic-maven-must-not-run'; ExternalFailoverWaitSeconds=$case.wait}
            if ($provider -ceq 'redis') {
                $arguments.RedisUri='redis://synthetic.invalid:6379'
                $arguments.PostgresUrl='jdbc:postgresql://synthetic.invalid/test'
                $arguments.PostgresUser='synthetic-user'
                $arguments.PostgresPassword='synthetic-secret-do-not-report'
            } else {
                $arguments.MssqlUrl='jdbc:sqlserver://synthetic.invalid;databaseName=test'
                $arguments.MssqlUser='synthetic-user'
                $arguments.MssqlPassword='synthetic-secret-do-not-report'
            }
            if ($case.smokeOnly) { $arguments.ReconnectSmokeOnly=$true }
            $failure = $null
            $transcriptPath = Join-Path $root ($case.name + '.output.txt')
            try { & $subject @arguments *>&1 | Set-Content -LiteralPath $transcriptPath }
            catch { $failure = $_ }
            if ($case.error) {
                if ($null -eq $failure -or $failure.Exception.Message -notlike $case.error) {
                    throw "Unexpected rejection for ${caseName}: $failure"
                }
            } elseif ($null -ne $failure) { throw "Unexpected smoke-only failure for ${caseName}: $failure" }
            if ($null -ne $failure) {
                $failure.Exception.Message | Set-Content -LiteralPath (Join-Path $root ($case.name + '.error.txt'))
            }
            foreach ($path in $priorHashes.Keys) {
                if (-not (Test-Path -LiteralPath $path) -or
                        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $priorHashes[$path]) {
                    throw "Earlier evidence changed during ${caseName}: $path"
                }
            }
            $runs = @(Get-ChildItem -LiteralPath $reportsRoot -Directory | Where-Object { $_.Name -notin $priorRuns })
            $callsPath = $controlPath + '.calls.jsonl'
            $calls = @(if (Test-Path -LiteralPath $callsPath) { Get-Content -LiteralPath $callsPath | ConvertFrom-Json })
            $smokes = @($calls | Where-Object { $_.event -ceq 'smoke' })
            if (($smokes | ForEach-Object { $_.phase }) -join ',' -cne $case.phases) {
                throw "Incorrect smoke sequence: $caseName"
            }
            $waits = @($calls | Where-Object { $_.event -ceq 'wait' })
            $expectedWaits = if ($case.wait -gt 0) { 1 } else { 0 }
            if ($waits.Count -ne $expectedWaits -or ($waits.Count -and $waits[0].seconds -ne $case.wait)) {
                throw "Incorrect wait behavior: $caseName"
            }
            if ($case.wait -lt 0) {
                if ($runs.Count -or $calls.Count) { throw 'Invalid wait produced execution or evidence.' }
                $passed.Add($caseName)
                continue
            }
            if ($runs.Count -ne 1) { throw "Expected one new run directory: $caseName" }
            $run = $runs[0]
            foreach ($smoke in $smokes) {
                if ($smoke.reportsDir -cne (Join-Path $run.FullName $smoke.phase)) {
                    throw "Smoke report escaped its unique run: $caseName"
                }
            }
            $reportPath = Join-Path $run.FullName "staging-$provider-ha-evidence.json"
            $reportText = Get-Content -LiteralPath $reportPath -Raw
            $report = $reportText | ConvertFrom-Json
            $mode = if ($case.smokeOnly) { 'reconnect-smoke-only' } else { 'ha-qualification' }
            if ($report.provider -cne $provider -or $report.runId -cne $run.Name -or
                    $report.executionMode -cne $mode -or $report.status -cne $case.status -or
                    $report.externalFailoverWaitSeconds -ne $case.wait -or
                    $report.beforeSmoke -cne $case.before -or $report.afterSmoke -cne $case.after -or
                    $report.failurePhase -cne $case.failurePhase -or -not $report.completedAtUtc) {
                throw "Incorrect phase evidence: $caseName"
            }
            foreach ($flag in @('liveFailoverVerified', 'haQualified')) {
                if ($report.$flag -isnot [bool] -or $report.$flag) { throw "False HA qualification: $caseName/$flag" }
            }
            $expectedSmokesPassed = $case.before -ceq 'passed' -and $case.after -ceq 'passed'
            if ($report.smokeChecksPassed -isnot [bool] -or $report.smokeChecksPassed -ne $expectedSmokesPassed) {
                throw "Smoke success was misreported: $caseName"
            }
            $summary = Get-Content -LiteralPath (Join-Path $run.FullName "staging-$provider-ha-evidence.md") -Raw
            $output = Get-Content -LiteralPath $transcriptPath -Raw
            if ($summary -notmatch 'HA remains NOT QUALIFIED' -or $output -match 'HA evidence passed' -or
                    $output -notmatch 'liveFailoverVerified=false' -or $output -notmatch 'haQualified=false') {
                throw "Misleading or missing qualification summary: $caseName"
            }
            if (($summary + $reportText).Contains('synthetic-secret-do-not-report') -or
                    ($summary + $reportText).Contains('synthetic.invalid')) { throw 'Report exposed connection details.' }
            $passed.Add($caseName)
        }
    }
} finally {
    [Environment]::SetEnvironmentVariable('CACHEDB_STAGING_HA_MOCK_CONTROL', $savedControl)
}

@{kind='synthetic-staging-ha-contract'; liveHaEvidence=$false; passedCases=$passed.Count;
    cases=$passed.ToArray(); sourceSha256=$sourcePins} | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'synthetic-contract-summary.json') -Encoding utf8
$global:LASTEXITCODE = 0
Write-Host "$($passed.Count) synthetic staging smoke/qualification cases passed. This is not live HA evidence."
