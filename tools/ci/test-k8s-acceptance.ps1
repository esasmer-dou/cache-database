#requires -Version 7.3
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$controlBudgetWatch = [Diagnostics.Stopwatch]::StartNew()
if (!$EvidenceDirectory) { $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-k8s-controls-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Fresh control output directory required.' }
$null = [IO.Directory]::CreateDirectory($EvidenceDirectory)
$checker = Join-Path $PSScriptRoot 'check-k8s-acceptance.ps1'
$tokens = $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($checker, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Acceptance verifier has parse errors.' }
# Load pure guards only; never invoke network, the workflow entry point, or product workloads.
foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
$controls = [Collections.Generic.List[string]]::new()
function Pass([string]$Name) { $controls.Add($Name) }
function Reject([string]$Name, [scriptblock]$Action) {
    $failed = $false
    try { $null = & $Action } catch { $failed = $true }
    if (!$failed) { throw "Unsafe synthetic acceptance admitted: $Name" }
    Pass $Name
}
function Clone($Value) { $Value | ConvertTo-Json -Depth 32 -Compress | ConvertFrom-Json -AsHashtable -Depth 32 }
function Assert-K8sExportChildResult([int]$ExitCode, [bool]$TimedOut, [string]$SummaryPath) {
    if ($TimedOut -or $ExitCode -ne 0) { throw 'Export child failed or exceeded the original control budget.' }
    $summary = [IO.File]::ReadAllText($SummaryPath) | ConvertFrom-Json -AsHashtable -Depth 16
    if ($summary.result -cne 'PASS' -or $summary.syntheticOnly -isnot [bool] -or !$summary.syntheticOnly -or
        $summary.actualKubernetesAcceptance -isnot [bool] -or $summary.actualKubernetesAcceptance -or
        $summary.controls -isnot [array] -or $summary.controls.Count -ne 59 -or
        @($summary.controls | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) }).Count -or
        @($summary.controls | Sort-Object -Unique).Count -ne 59) { throw 'Export child summary is incomplete or unsuccessful.' }
    return $summary
}
$childFixtureRoot = Join-Path $EvidenceDirectory 'export-child-result-controls'
$null = [IO.Directory]::CreateDirectory($childFixtureRoot)
$childFixturePath = Join-Path $childFixtureRoot 'summary.json'
$childFixture = @{result='PASS'; syntheticOnly=$true; actualKubernetesAcceptance=$false
    controls=@(0..58 | ForEach-Object { 'synthetic-export-{0:d2}' -f $_ })}
[IO.File]::WriteAllText($childFixturePath, ($childFixture | ConvertTo-Json))
$null = Assert-K8sExportChildResult 0 $false $childFixturePath
Pass 'export-child-success-requires-zero-exit-and-complete-synthetic-summary'
Reject 'export-child-nonzero-exit-with-pass-summary' { Assert-K8sExportChildResult 1 $false $childFixturePath }
Reject 'export-child-timeout-with-pass-summary' { Assert-K8sExportChildResult 0 $true $childFixturePath }
Reject 'export-child-missing-summary' { Assert-K8sExportChildResult 0 $false (Join-Path $childFixtureRoot 'missing.json') }
foreach ($case in @('failed-status','runtime-claim','string-synthetic','missing-controls','short-roster','duplicate-control','empty-control')) {
    $badChild = Clone $childFixture
    switch ($case) {
        failed-status { $badChild.result = 'FAIL' }
        runtime-claim { $badChild.actualKubernetesAcceptance = $true }
        string-synthetic { $badChild.syntheticOnly = 'true' }
        missing-controls { $badChild.Remove('controls') }
        short-roster { $badChild.controls = @($badChild.controls[0..57]) }
        duplicate-control { $badChild.controls[58] = $badChild.controls[0] }
        empty-control { $badChild.controls[58] = '' }
    }
    [IO.File]::WriteAllText($childFixturePath, ($badChild | ConvertTo-Json))
    Reject ('export-child-' + $case) { Assert-K8sExportChildResult 0 $false $childFixturePath }
}
[IO.File]::WriteAllText($childFixturePath, '{invalid-json')
Reject 'export-child-malformed-summary' { Assert-K8sExportChildResult 0 $false $childFixturePath }
$instant = [DateTimeOffset]::new(2026, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
$originalCulture = [Globalization.CultureInfo]::CurrentCulture
try {
    [Globalization.CultureInfo]::CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('tr-TR')
    foreach ($value in @($instant, $instant.ToOffset([TimeSpan]::FromHours(3)), $instant.UtcDateTime,
        $instant.LocalDateTime, '2026-01-01T00:00:00Z', '2026-01-01T03:00:00+03:00')) {
        if ((ConvertTo-K8sTimestamp $value).UtcDateTime.Ticks -ne $instant.UtcDateTime.Ticks) {
            throw 'Evidence timestamp changed instant under the current culture/timezone.'
        }
    }
    foreach ($value in @($instant.ToOffset([TimeSpan]::FromHours(3)), '2026-01-01T03:00:00+03:00')) {
        if ((ConvertTo-K8sTimestamp $value).Offset -ne [TimeSpan]::FromHours(3)) { throw 'Explicit offset was lost.' }
    }
    Pass 'typed-and-string-timestamps-preserve-instant-and-offset-under-tr-TR'
} finally { [Globalization.CultureInfo]::CurrentCulture = $originalCulture }
foreach ($value in @([DateTime]::SpecifyKind($instant.UtcDateTime, [DateTimeKind]::Unspecified),
    '2026-01-01T00:00:00', '01/01/2026 00:00:00', $null, 0)) {
    Reject ('ambiguous-timestamp-' + $controls.Count) { ConvertTo-K8sTimestamp $value }
}
foreach ($member in @('../outside', '/outside', 'a/../b', 'a\\b', 'C:/escape', 'a//b', 'a/./b', 'a/b.', 'a/ b')) {
    Reject ('unsafe-path-' + $controls.Count) { Resolve-K8sMember $EvidenceDirectory $member }
}
$policy = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'k8s-acceptance-contract.psd1')
$sha = '1' * 40; $sdkPin = '2' * 64
$authority = @{publicRepository='synthetic/public'; publicRepositoryId='10'; sha=$sha; sdkPin=$sdkPin
    privateRepositoryId='20'; runId='30'; runAttempt='1'; workflowPath='.github/workflows/acceptance.yml'}
$uid = '00000000-1111-2222-3333-444444444444'
$receipt = @{schema=$policy.Schema; kind=$policy.Kind
    source=@{repository='synthetic/public'; repositoryId='10'; sha=$sha}
    sdk=@{manifestSha256=$sdkPin}; producer=@{runId='30'; runAttempt='1'; repositoryId='20'; workflowPath=$authority.workflowPath}
    runs=@(@{cluster='EmaTech'; namespace='synthetic-owned'; podUid=$uid; createdAt='2026-01-01T00:00:00Z'
        originalCutoff='2026-01-01T01:00:00Z'; finishedAt='2026-01-01T00:59:00Z'})
    executions=@(@{id='standalone'; podUid=$uid; sourceSha=$sha; sdkManifestSha256=$sdkPin
        startedAt='2026-01-01T00:01:00Z'; finishedAt='2026-01-01T00:58:00Z'; exitFile='exit'; log='log'})}
Assert-K8sHeader $receipt $authority $policy; Pass 'exact-source-sdk-and-kubernetes-header'
foreach ($field in @('schema', 'kind', 'source-sha', 'source-repository', 'sdk-pin', 'producer-run', 'producer-attempt', 'producer-workflow', 'producer-repository',
    'pod-uid', 'deadline', 'finished-after-cutoff', 'duplicate-execution', 'foreign-execution-sha', 'foreign-execution-sdk')) {
    $bad = Clone $receipt
    switch ($field) {
        schema { $bad.schema = 'other' }
        kind { $bad.kind = 'METADATA_IMPORT' }
        source-sha { $bad.source.sha = '3' * 40 }
        source-repository { $bad.source.repository = 'other/public' }
        sdk-pin { $bad.sdk.manifestSha256 = '4' * 64 }
        producer-run { $bad.producer.runId = '31' }
        producer-attempt { $bad.producer.runAttempt = '2' }
        producer-workflow { $bad.producer.workflowPath = '.github/workflows/metadata.yml' }
        producer-repository { $bad.producer.repositoryId = '21' }
        pod-uid { $bad.runs[0].podUid = 'unknown' }
        deadline { $bad.runs[0].originalCutoff = '2026-01-02T01:00:00Z' }
        finished-after-cutoff { $bad.runs[0].finishedAt = '2026-01-01T01:00:01Z' }
        duplicate-execution { $bad.executions += (Clone $bad.executions[0]) }
        foreign-execution-sha { $bad.executions[0].sourceSha = '3' * 40 }
        foreign-execution-sdk { $bad.executions[0].sdkManifestSha256 = '4' * 64 }
    }
    Reject $field { Assert-K8sHeader $bad $authority $policy }
}
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'exit'), "0`n")
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'log'), 'SYNTHETIC PROCESS LOG ONLY')
Assert-K8sExecution $receipt.executions[0] $receipt $EvidenceDirectory; Pass 'actual-exit-and-log-required'
$offsetReceipt = Clone $receipt
$offsetReceipt.runs[0].createdAt = '2026-01-01T03:00:00+03:00'
$offsetReceipt.runs[0].finishedAt = '2026-01-01T02:59:00+02:00'
$offsetReceipt.executions[0].startedAt = '2025-12-31T19:01:00-05:00'
$offsetReceipt.executions[0].finishedAt = '2026-01-01T03:58:00+03:00'
$offsetReceipt = Clone $offsetReceipt
Assert-K8sHeader $offsetReceipt $authority $policy
Assert-K8sExecution $offsetReceipt.executions[0] $offsetReceipt $EvidenceDirectory
Pass 'json-roundtrip-mixed-offset-header-and-execution-preserve-authority'
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'exit'), "134`n")
Reject 'native-abort-is-not-test-success' { Assert-K8sExecution $receipt.executions[0] $receipt $EvidenceDirectory }
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'exit'), "0`n")
$bad = Clone $receipt; $bad.executions[0].finishedAt = '2026-01-01T01:00:01Z'
Reject 'execution-outside-original-budget' { Assert-K8sExecution $bad.executions[0] $bad $EvidenceDirectory }
Reject 'missing-scope-not-substituted-by-another' { Get-K8sExecution $receipt 'cluster' }
$xmlRoot = Join-Path $EvidenceDirectory 'xml'; $null = [IO.Directory]::CreateDirectory($xmlRoot)
$xmlPath = Join-Path $xmlRoot 'TEST-synthetic.xml'
$xml = '<testsuite name="synthetic.ProviderTest" tests="1" failures="0" errors="0" skipped="0"><testcase classname="synthetic.ProviderTest" name="runs" /></testsuite>'
[IO.File]::WriteAllText($xmlPath, $xml)
Assert-K8sProviderReports $xmlRoot; Pass 'real-testcase-counters-checked'
$invalidReports = [ordered]@{
    'counter-mismatch' = $xml.Replace('tests="1"', 'tests="2"')
    'skipped-case' = $xml.Replace(' />', '><skipped /></testcase>')
    'failed-case' = $xml.Replace(' />', '><failure /></testcase>')
    'flaky-case' = $xml.Replace(' />', '><flakyFailure /></testcase>')
    'external-entity-doctype' = ('<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///not-readable">]>' + $xml)
}
foreach ($name in $invalidReports.Keys) {
    $badXml = $invalidReports[$name]
    if ($badXml -ceq $xml) { throw "Negative JUnit fixture is unchanged: $name" }
    [IO.File]::WriteAllText($xmlPath, $badXml)
    Reject ('invalid-junit-' + $name) { Assert-K8sProviderReports $xmlRoot }
}
$sealed = Join-Path $EvidenceDirectory 'sealed'; $null = [IO.Directory]::CreateDirectory($sealed)
[IO.File]::WriteAllText((Join-Path $sealed 'log'), 'SYNTHETIC SEALED LOG')
$inventory = @{files=@(@{path='log'; bytes=(Get-Item -LiteralPath (Join-Path $sealed 'log')).Length
    sha256=(Get-FileHash -LiteralPath (Join-Path $sealed 'log')).Hash.ToLowerInvariant()})}
$null = Assert-K8sInventory $inventory $sealed; Pass 'pinned-complete-byte-inventory'
[IO.File]::WriteAllText((Join-Path $sealed 'log'), 'TAMPERED')
Reject 'changed-raw-report-rejected' { Assert-K8sInventory $inventory $sealed }
[IO.File]::WriteAllText((Join-Path $sealed 'log'), 'SYNTHETIC SEALED LOG')
[IO.File]::WriteAllText((Join-Path $sealed 'extra'), 'UNSEALED')
Reject 'unsealed-report-rejected' { Assert-K8sInventory $inventory $sealed }
$workflow = [IO.File]::ReadAllText((Join-Path $RepositoryRoot '.github/workflows/framework-readiness.yml'))
$job = [regex]::Match($workflow, '(?ms)^  k8s-acceptance-verification:\r?\n(?<body>.*)\z').Groups['body'].Value
if (!$job -or $job -notmatch '(?m)^    environment: k8s-acceptance\r?$' -or
    $job -notmatch '(?m)^        run: \./tools/ci/check-k8s-acceptance\.ps1\r?$' -or
    $job -match '(?m)^\s+(?:run:.*\bmvn\b|uses: actions/setup-java@)' -or
    $job -notmatch '(?m)^            target/k8s-acceptance-verification.md\r?$') { throw 'Kubernetes-only verification workflow contract differs.' }
Pass 'protected-environment-without-ubuntu-test-workload'
$digestBindings = [ordered]@{
    K8S_ARCHIVE_PIN = 'CACHEDB_K8S_ACCEPTANCE_ARCHIVE_SHA256'
    K8S_RECEIPT_PIN = 'CACHEDB_K8S_ACCEPTANCE_RECEIPT_SHA256'
    K8S_SDK_PIN = 'CACHEDB_K8S_ACCEPTANCE_SDK_SHA256'
}
foreach ($binding in $digestBindings.Keys) {
    if ($job -notmatch ('(?m)^          ' + $binding + ': \$\{\{ vars\.' + [regex]::Escape($digestBindings[$binding]) + ' \}\}\r?$')) {
        throw "Independent protected digest binding is missing or differs: $binding"
    }
}
Pass 'independent-artifact-receipt-sdk-pins-retained'
if ($job -match 'K8S_PUBLIC_KEY|K8S_KEY_PIN' -or
    @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq 'Assert-K8sSignature'}, $true)).Count) {
    throw 'Acceptance must use the approved GitHub producer without a new signing authority.'
}
Pass 'no-additional-signer-provisioning'
if ($policy.Controls.Count -ne 30 -or @($policy.Controls | Sort-Object -Unique).Count -ne $policy.Controls.Count) { throw 'Required control roster differs.' }
Pass 'original-control-obligations-retained'
$assembler = Join-Path $PSScriptRoot 'new-k8s-acceptance-receipt.ps1'
$assemblerAst = [Management.Automation.Language.Parser]::ParseFile($assembler, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Receipt assembler has parse errors.' }
foreach ($definition in $assemblerAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
    if ($null -ne $definition.Parameters) {
        . ([scriptblock]::Create($definition.Extent.Text))
    } else {
        # Preserve the file context without invoking the assembler's entry point.
        Set-Item -Path "Function:$($definition.Name)" -Value ($definition.Body.GetScriptBlock())
    }
}
$assemblyRoot = Join-Path $EvidenceDirectory 'assembly-controls'
$assemblyData = Join-Path $assemblyRoot 'data'
$null = [IO.Directory]::CreateDirectory($assemblyData)
function Write-AssemblyFixture([string]$Member, [string]$Value) {
    $path = Resolve-K8sMember $assemblyData $Member
    $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $path))
    [IO.File]::WriteAllText($path, $Value, [Text.UTF8Encoding]::new($false))
}
$sourceContent = 'SYNTHETIC SOURCE CONTENT'
$sourceContentPin = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($sourceContent))).ToLowerInvariant()
$sourceManifest = $sourceContentPin + "  synthetic-source.txt`n"
$sdkManifest = ((1..$policy.SdkFiles | ForEach-Object { ('b' * 64) + ('  lib/{0:d3}.jar' -f $_) }) -join "`n") + "`n"
foreach ($name in @('manifest', 'before', 'after')) {
    Write-AssemblyFixture "source/$name.sha256" $sourceManifest
    Write-AssemblyFixture "sdk/$name.sha256" $sdkManifest
}
$assemblyPin = (Get-FileHash -LiteralPath (Join-Path $assemblyData 'sdk/manifest.sha256')).Hash.ToLowerInvariant()
$assemblySourcePin = (Get-FileHash -LiteralPath (Join-Path $assemblyData 'source/manifest.sha256')).Hash.ToLowerInvariant()
$assemblyInput = @{schema='cachedb.k8s-acceptance-input/v1'
    source=@{repository='synthetic/public'; repositoryId='10'; sha=$sha
        manifest='source/manifest.sha256'; before='source/before.sha256'; after='source/after.sha256'}
    sdk=@{manifestSha256=$assemblyPin; manifest='sdk/manifest.sha256'; before='sdk/before.sha256'; after='sdk/after.sha256'}
    producer=(Clone $receipt.producer)
    runs=@(@{cluster='EmaTech'; namespace='synthetic-owned'; podUid=$uid; createdAt='2026-01-01T03:00:00+03:00'
        originalCutoff='2026-01-01T04:00:00+03:00'; finishedAt='2026-01-01T02:59:00+02:00'})
    executions=@(); files=@()}
$assemblyIds = @('sdk-build', 'standalone', 'cluster', 'sample-features')
foreach ($provider in $policy.Providers) {
    $assemblyIds += @("provider/$provider", "certification/$provider")
    foreach ($cell in $policy.Baseline) { $assemblyIds += "baseline/$provider/$($cell.Name)" }
}
for ($i=0; $i -lt $policy.Controls.Count; $i++) { $assemblyIds += ('control/{0:d2}' -f $i) }
foreach ($id in $assemblyIds) {
    $execution = @{id=$id; podUid=$uid; sourceSha=$sha; sdkManifestSha256=$assemblyPin
        command="SYNTHETIC invocation $id"; workingDirectory='.'
        startedAt='2025-12-31T19:01:00-05:00'; finishedAt='2026-01-01T03:58:00+03:00'
        log="process/$id.log"; exitFile="process/$id.exit"}
    Write-AssemblyFixture $execution.log 'SYNTHETIC PROCESS LOG ONLY'
    Write-AssemblyFixture $execution.exitFile "0`n"
    if ($id -in @('standalone', 'cluster') -or $id.StartsWith('provider/')) {
        $execution.reportsRoot = "reports/$id"
        Write-AssemblyFixture ($execution.reportsRoot + '/TEST-synthetic.xml') $xml
    }
    if ($id.StartsWith('provider/')) {
        $execution.command = 'mvn -B clean verify -Pprovider-integration'
        $execution.workingDirectory = 'sample-cache-database-' + $id.Split('/')[1]
    }
    if ($id.StartsWith('control/')) { $execution.command = $policy.Controls[[int]$id.Split('/')[1]] }
    if ($id.StartsWith('baseline/')) {
        $execution.report = "reports/$id.json"
        Write-AssemblyFixture $execution.report '{"syntheticOnly":true}'
    }
    if ($id.StartsWith('certification/')) {
        $execution.report = "reports/$id.txt"
        Write-AssemblyFixture $execution.log 'SYNTHETIC: CacheDB production certification failed'
        Write-AssemblyFixture $execution.exitFile "1`n"
        Write-AssemblyFixture $execution.report 'SYNTHETIC: Missing certification manifest; Missing route coverage CSV'
    }
    if ($id -ceq 'sample-features') {
        $execution.receipt = 'features/receipt.json'
        $header = @{source=@{manifest=@{sha256=$assemblySourcePin}}
            sdk=@{manifest=@{sha256=$assemblyPin}; sourceManifestSha256=$assemblySourcePin}}
        Write-AssemblyFixture $execution.receipt ($header | ConvertTo-Json -Depth 8)
    }
    $assemblyInput.executions += $execution
}
Write-AssemblyFixture 'process/unexpected-failure.exit' "134`n"
Write-AssemblyFixture 'process/unexpected-success.exit' "0`n"
$assemblyInput.files = @(Get-ChildItem -LiteralPath $assemblyData -Recurse -File | Sort-Object FullName | ForEach-Object {
    @{path=[IO.Path]::GetRelativePath($assemblyData, $_.FullName).Replace('\', '/'); bytes=$_.Length
        sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
function Invoke-AssemblyFixture($Value, [string]$Name, [string]$Pin = '') {
    $inputPath = Join-Path $assemblyRoot ($Name + '.json')
    [IO.File]::WriteAllText($inputPath, ($Value | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
    if (!$Pin) { $Pin = (Get-FileHash -LiteralPath $inputPath).Hash.ToLowerInvariant() }
    New-K8sAcceptanceReceipt -InventoryPath $inputPath -InventorySha256 $Pin -EvidenceRoot $assemblyData `
        -OutputDirectory (Join-Path $assemblyRoot $Name)
}
$assembled = Invoke-AssemblyFixture $assemblyInput 'valid'
$assembledReceipt = Read-K8sJson $assembled.receiptPath
if ($assembled.status -cne 'ASSEMBLED_NOT_RUNTIME_ACCEPTANCE' -or
    $assembledReceipt.executions.Count -ne $assemblyIds.Count -or
    $assembledReceipt.source.sha -cne $sha -or $assembledReceipt.sdk.manifestSha256 -cne $assemblyPin -or
    [string]$assembledReceipt.producer.runId -cne '30' -or
    (Get-FileHash -LiteralPath (Join-Path $assemblyRoot 'valid.json')).Hash -cne
        (Get-FileHash -LiteralPath (Join-Path $assemblyRoot 'valid/origin/execution-inventory.json')).Hash) {
    throw 'Assembler changed the original inventory or claimed runtime acceptance.'
}
$assembledDocument = [Text.Json.JsonDocument]::Parse([IO.File]::ReadAllText($assembled.receiptPath))
try {
    if ($assembledDocument.RootElement.GetProperty('runs')[0].GetProperty('createdAt').GetString() -cne '2026-01-01T03:00:00+03:00' -or
        $assembledDocument.RootElement.GetProperty('executions')[0].GetProperty('startedAt').GetString() -cne '2025-12-31T19:01:00-05:00') {
        throw 'Assembler normalized original timestamp offsets.'
    }
} finally { $assembledDocument.Dispose() }
$null = Assert-K8sInventory $assembledReceipt (Join-Path $assemblyRoot 'valid')
foreach ($entry in $assemblyInput.files) {
    if ((Get-FileHash -LiteralPath (Resolve-K8sMember $assemblyData $entry.path)).Hash.ToLowerInvariant() -cne $entry.sha256) {
        throw 'Assembler modified original supplied evidence.'
    }
}
Pass 'assembler-preserves-original-identity-offsets-and-bytes-without-runtime-acceptance'
foreach ($case in @('missing-producer', 'missing-command', 'missing-run', 'foreign-source', 'foreign-sdk', 'late-execution',
    'missing-execution', 'missing-log', 'missing-report', 'missing-feature-receipt', 'changed-control', 'changed-file',
    'unsafe-member', 'duplicate-member', 'reserved-origin', 'reused-process-file', 'aliased-preservation', 'source-equivalence',
    'failed-process-exit', 'certification-unexpected-success')) {
    $bad = Clone $assemblyInput
    switch ($case) {
        missing-producer { $bad.producer.Remove('runId') }
        missing-command { $bad.executions[0].Remove('command') }
        missing-run { $bad.runs = @() }
        foreign-source { $bad.executions[0].sourceSha = '3' * 40 }
        foreign-sdk { $bad.executions[0].sdkManifestSha256 = '3' * 64 }
        late-execution { $bad.executions[0].finishedAt = '2026-01-01T01:00:01Z' }
        missing-execution { $bad.executions = @($bad.executions | Where-Object { $_.id -cne 'cluster' }) }
        missing-log { $bad.executions[0].log = 'missing.log' }
        missing-report { ($bad.executions | Where-Object { $_.id -ceq 'provider/oracle' }).reportsRoot = 'missing-reports' }
        missing-feature-receipt { ($bad.executions | Where-Object { $_.id -ceq 'sample-features' }).Remove('receipt') }
        changed-control { ($bad.executions | Where-Object { $_.id -ceq 'control/00' }).command = 'SYNTHETIC wrong command' }
        changed-file { $bad.files[0].sha256 = '0' * 64 }
        unsafe-member { $bad.files[0].path = '../outside' }
        duplicate-member { $bad.files += (Clone $bad.files[0]) }
        reserved-origin { $bad.files[0].path = 'origin/relabel.json' }
        reused-process-file { $bad.executions[1].log = $bad.executions[0].log }
        aliased-preservation { $bad.source.after = $bad.source.manifest }
        source-equivalence { $bad.sourceEquivalence = @{sourceSha='3' * 40} }
        failed-process-exit { $bad.executions[0].exitFile = 'process/unexpected-failure.exit' }
        certification-unexpected-success { ($bad.executions | Where-Object { $_.id -ceq 'certification/postgresql' }).exitFile = 'process/unexpected-success.exit' }
    }
    Reject ('assembler-' + $case) { Invoke-AssemblyFixture $bad $case }
    if (Test-Path -LiteralPath (Join-Path $assemblyRoot $case)) { throw 'Failed assembly published output.' }
}
Reject 'assembler-wrong-inventory-pin' { Invoke-AssemblyFixture $assemblyInput 'wrong-pin' ('0' * 64) }
Reject 'assembler-does-not-overwrite-output' { Invoke-AssemblyFixture $assemblyInput 'valid' }
Reject 'assembler-output-cannot-overlap-original-evidence' {
    New-K8sAcceptanceReceipt -InventoryPath (Join-Path $assemblyRoot 'valid.json') `
        -InventorySha256 $assembledReceipt.origin.sha256 -EvidenceRoot $assemblyData -OutputDirectory (Join-Path $assemblyData 'output')
}
if (!$IsWindows) {
    $link = Join-Path $assemblyRoot 'linked-evidence'
    $null = [IO.Directory]::CreateSymbolicLink($link, $assemblyData)
    Reject 'assembler-rejects-linked-evidence-ancestor' {
        New-K8sAcceptanceReceipt -InventoryPath (Join-Path $assemblyRoot 'valid.json') `
            -InventorySha256 $assembledReceipt.origin.sha256 -EvidenceRoot $link -OutputDirectory (Join-Path $assemblyRoot 'linked-output')
    }
}
Assert-K8sExecutionRoster $assembledReceipt (Join-Path $assemblyRoot 'valid') $policy
Pass 'legacy-unaliased-52-obligation-receipt-remains-valid'
$aliased = Clone $assemblyInput
for ($i = 0; $i -lt $aliased.executions.Count; $i++) {
    if ($aliased.executions[$i].id -ceq 'sdk-build') { $aliased.executions[$i].command = $policy.Controls[17] }
    if ($aliased.executions[$i].id -ceq 'control/17') {
        $aliased.executions[$i] = @{id='control/17'; executionRef='sdk-build'; canonicalCommand=$policy.Controls[17]}
    }
}
$aliased.files = @($aliased.files | Where-Object { $_.path -cnotin @('process/control/17.log', 'process/control/17.exit') })
$aliasResult = Invoke-AssemblyFixture $aliased 'single-build'
$aliasReceipt = Read-K8sJson $aliasResult.receiptPath
$aliasRoot = Split-Path -Parent $aliasResult.receiptPath
Assert-K8sExecutionRoster $aliasReceipt $aliasRoot $policy
$build = Get-K8sExecution $aliasReceipt 'sdk-build'
if ($aliasReceipt.executions.Count -ne 52 -or
    @($aliasReceipt.executions | Where-Object { !$_.ContainsKey('executionRef') }).Count -ne 51 -or
    ![object]::ReferenceEquals($build, (Get-K8sExecution $aliasReceipt 'control/17')) -or
    @($aliasReceipt.files | Where-Object { $_.path -cin @('process/control/17.log', 'process/control/17.exit') }).Count -or
    $build.podUid -cne $uid -or $build.sourceSha -cne $sha -or $build.sdkManifestSha256 -cne $assemblyPin) {
    throw 'Shared package execution was copied, relabeled, or lost its original identity.'
}
Pass 'assembler-and-checker-share-one-build-with-52-obligations-and-51-executions'
foreach ($case in @('other-alias', 'wrong-target', 'self-cycle', 'target-cycle', 'target-chain', 'alias-conflict',
    'canonical-conflict', 'physical-canonical-conflict', 'implicit-shared-log', 'missing-target', 'unapproved-argv')) {
    $bad = Clone $aliasReceipt
    $binding = @($bad.executions | Where-Object { $_.id -ceq 'control/17' })[0]
    $target = Get-K8sExecution $bad 'sdk-build'
    switch ($case) {
        other-alias {
            for ($i = 0; $i -lt $bad.executions.Count; $i++) {
                if ($bad.executions[$i].id -ceq 'control/18') {
                    $bad.executions[$i] = @{id='control/18'; executionRef='sdk-build'; canonicalCommand=$policy.Controls[18]}
                }
            }
        }
        wrong-target { $binding.executionRef = 'standalone' }
        self-cycle { $binding.executionRef = 'control/17' }
        target-cycle { $target.executionRef = 'control/17' }
        target-chain { $target.executionRef = 'standalone' }
        alias-conflict { $binding.startedAt = '2026-01-01T00:00:00Z' }
        canonical-conflict { $binding.canonicalCommand = 'mvn install' }
        physical-canonical-conflict { $target.canonicalCommand = 'mvn install' }
        implicit-shared-log { (Get-K8sExecution $bad 'standalone').log = $target.log }
        missing-target { $bad.executions = @($bad.executions | Where-Object { $_.id -cne 'sdk-build' }) }
        unapproved-argv { $target.command += ' -Dmaven.test.skip=true' }
    }
    Reject ('shared-execution-' + $case) { Assert-K8sExecutionRoster $bad $aliasRoot $policy }
}
$bad = Clone $aliased
(@($bad.executions | Where-Object { $_.id -ceq 'control/17' })[0]).exitFile = 'process/sdk-build.exit'
Reject 'assembler-reference-cannot-override-process-evidence' { Invoke-AssemblyFixture $bad 'alias-process-override' }

# All invocation captures below are synthetic. They do not approve or reconstruct AE evidence.
$offline = Clone $aliasReceipt
$offlinePolicy = Clone $policy
$offlineContract = $offlinePolicy.OfflinePackageInvocation
$offlineBuild = Get-K8sExecution $offline 'sdk-build'
$offlineBuild.command = $offlineContract.Argv -join ' '
$offlineBuild.canonicalCommand = $policy.Controls[17]
$capture = @{schema='cachedb.k8s-offline-package-invocation/v1'; argv=$offlineContract.Argv
    wrapperArgv=$offlineContract.WrapperArgv; workingDirectory=$offlineContract.WorkingDirectory
    environment=(Clone $offlineContract.Environment); unsetEnvironment=$offlineContract.UnsetEnvironment
    inputPins=@{}; sourceSha=$sha; sourceManifestSha256=$assemblySourcePin; sdkManifestSha256=$assemblyPin; podUid=$uid
    startedAt=$offlineBuild.startedAt; finishedAt=$offlineBuild.finishedAt; originalCutoff=$offline.runs[0].originalCutoff
    logSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $aliasRoot $offlineBuild.log)).Hash.ToLowerInvariant()
    exitSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $aliasRoot $offlineBuild.exitFile)).Hash.ToLowerInvariant()}
foreach ($name in @('runner', 'settings', 'plan')) {
    $member = "synthetic-invocation/$name.txt"
    Write-AssemblyFixture $member ('SYNTHETIC ORIGINAL INPUT ' + $name)
    $pin = (Get-FileHash -LiteralPath (Resolve-K8sMember $assemblyData $member)).Hash.ToLowerInvariant()
    $offlineContract.InputSha256[$name] = $pin
    $capture.inputPins[$name] = @{path=$member; sha256=$pin}
    $offline.files += @{path=$member; sha256=$pin; bytes=(Get-Item -LiteralPath (Resolve-K8sMember $assemblyData $member)).Length}
}
# Read existing logs/manifests from the original fixture, without copying or creating execution evidence.
function Write-InvocationFixture($Value) {
    $member = 'synthetic-invocation/capture.json'
    Write-AssemblyFixture $member ($Value | ConvertTo-Json -Depth 32)
    $file = Get-Item -LiteralPath (Resolve-K8sMember $assemblyData $member)
    $pin = (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    $offline.files = @($offline.files | Where-Object { $_.path -cne $member }) + @(@{path=$member; sha256=$pin; bytes=$file.Length})
    $offlineBuild.invocationQualification = @{path=$member; sha256=$pin}
    $offlineContract.ApprovedRecordSha256 = $pin
}
Write-InvocationFixture $capture
Assert-K8sControlInvocation $offline 'control/17' $assemblyData $offlinePolicy
Pass 'synthetic-approved-exact-offline-capture-binds-one-canonical-obligation'
Reject 'actual-offline-qualification-remains-pending-without-original-authority' {
    Assert-K8sControlInvocation $offline 'control/17' $assemblyData $policy
}
foreach ($case in @('argv-extra', 'argv-order', 'wrapper', 'environment-override', 'environment-extra', 'unset-environment',
    'missing-pin', 'changed-pin', 'foreign-source', 'foreign-sdk', 'foreign-pod', 'changed-start', 'changed-cutoff',
    'changed-log', 'changed-exit', 'source-manifest', 'extra-field')) {
    $badCapture = Clone $capture
    switch ($case) {
        argv-extra { $badCapture.argv += '-Dmaven.test.skip=true' }
        argv-order { $badCapture.argv = @($badCapture.argv[1], $badCapture.argv[0]) + @($badCapture.argv[2..($badCapture.argv.Count - 1)]) }
        wrapper { $badCapture.wrapperArgv[3] = '471' }
        environment-override { $badCapture.environment.MAVEN_OPTS += ' -Dskip=true' }
        environment-extra { $badCapture.environment.MAVEN_ARGS = 'install' }
        unset-environment { $badCapture.unsetEnvironment = @('CLASSPATH') }
        missing-pin { $badCapture.inputPins.Remove('settings') }
        changed-pin { $badCapture.inputPins.settings.sha256 = '0' * 64 }
        foreign-source { $badCapture.sourceSha = '3' * 40 }
        foreign-sdk { $badCapture.sdkManifestSha256 = '3' * 64 }
        foreign-pod { $badCapture.podUid = '00000000-1111-2222-3333-555555555555' }
        changed-start { $badCapture.startedAt = '2026-01-01T00:01:01Z' }
        changed-cutoff { $badCapture.originalCutoff = '2026-01-01T01:00:01Z' }
        changed-log { $badCapture.logSha256 = '0' * 64 }
        changed-exit { $badCapture.exitSha256 = '0' * 64 }
        source-manifest { $badCapture.sourceManifestSha256 = '0' * 64 }
        extra-field { $badCapture.reconstructed = $true }
    }
    Write-InvocationFixture $badCapture
    Reject ('offline-invocation-' + $case) { Assert-K8sControlInvocation $offline 'control/17' $assemblyData $offlinePolicy }
}
Write-InvocationFixture $capture
$offlineBuild.invocationQualification.sha256 = '0' * 64
Reject 'offline-invocation-unsealed-record-pin' { Assert-K8sControlInvocation $offline 'control/17' $assemblyData $offlinePolicy }

# Entirely synthetic originals. No AE input, authority, or runtime result is imported by these controls.
$qualifier = Join-Path $PSScriptRoot 'new-k8s-package-invocation-qualification.ps1'
$qualifierAst = [Management.Automation.Language.Parser]::ParseFile($qualifier, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Invocation qualification assembler has parse errors.' }
foreach ($definition in $qualifierAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $true)) {
    Set-Item -Path "Function:$($definition.Name)" -Value ($definition.Body.GetScriptBlock())
}
$pinnedPolicy = Clone $offlinePolicy
$pinnedPolicy.OfflinePackageInvocation.ApprovedRecordSha256 = ''
$pinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.ApprovedOriginalSha256 = @{pod=''; authority=''}
$pinnedBuild = Get-K8sExecution $offline 'sdk-build'
$launcher = 'SYNTHETIC EXECUTED SCRIPT LAUNCHER ONLY'
$pinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.LauncherArgumentSha256 =
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($launcher))).ToLowerInvariant()
$originalValues = @{
    pod=(@{metadata=@{uid=$uid; namespace='synthetic-owned'; creationTimestamp=$offline.runs[0].createdAt}
        spec=@{containers=@(@{name='runner'; command=@('bash','-ec'); args=@($launcher); workingDir='/workspace'
            env=@(@{name='POD_UID'; valueFrom=@{fieldRef=@{fieldPath='metadata.uid'}}})})}} | ConvertTo-Json -Depth 12)
    authority=(@{namespace='synthetic-owned'; runnerPodUid=$uid; runnerCreatedAt=$offline.runs[0].createdAt
        originalCutoff=$offline.runs[0].originalCutoff; activeDeadlineSeconds=3600} | ConvertTo-Json)
    buildReceipt=(@{status='CURRENT_SOURCE_PACKAGE_NOT_RUNTIME_QUALIFICATION'; buildCommand=$policy.Controls[17]
        runnerPodUid=$uid; sourceManifestSha256=$assemblySourcePin; sdkManifestSha256=$assemblyPin; files=$policy.SdkFiles
        buildStartedAt=$pinnedBuild.startedAt; buildFinishedAt=$pinnedBuild.finishedAt; installed=$false; wholeRelease=$false} | ConvertTo-Json)
    buildStarted='2025-12-31T19:01:00-05:00'; buildFinished='2026-01-01T03:58:00+03:00'
    wrapperExit="0`n"; sourcePreservationExit="0`n"; sdkPreservationExit="0`n"
}
$pinnedInput = @{schema='cachedb.k8s-pinned-script-input/v1'; files=@{}}
foreach ($name in @($pinnedPolicy.OfflinePackageInvocation.InputSha256.Keys) +
    @($pinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.OriginalEvidenceRoles)) {
    $member = switch ($name) {
        sourceManifest { 'source/manifest.sha256' }
        sdkManifest { 'sdk/manifest.sha256' }
        buildLog { $pinnedBuild.log }
        buildExit { $pinnedBuild.exitFile }
        { $_ -in @('runner','settings','plan') } { $capture.inputPins[$name].path }
        default { "synthetic-pinned/$name.txt" }
    }
    if ($originalValues.ContainsKey($name)) { Write-AssemblyFixture $member $originalValues[$name] }
    $file = Get-Item -LiteralPath (Resolve-K8sMember $assemblyData $member)
    $pinnedInput.files[$name] = @{path=$member; bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()}
}
$pinnedInputPath = Join-Path $assemblyRoot 'pinned-originals.json'
[IO.File]::WriteAllText($pinnedInputPath, ($pinnedInput | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
$pinnedInputHash = (Get-FileHash -LiteralPath $pinnedInputPath).Hash.ToLowerInvariant()
$pinnedOutput = Join-Path $assemblyRoot 'pinned-qualification'
$pinnedResult = New-K8sPackageInvocationQualification -InventoryPath $pinnedInputPath -InventorySha256 $pinnedInputHash `
    -EvidenceRoot $assemblyData -OutputDirectory $pinnedOutput -Policy $pinnedPolicy
$pinnedRecord = Read-K8sJson (Resolve-K8sMember $pinnedOutput $pinnedResult.invocationQualification.path)
if ($pinnedResult.status -cne 'RECONSTRUCTED_NOT_AUTHENTICATED' -or $pinnedResult.runtimeAcceptance -or $pinnedResult.publicSourceShaQualified -or
    $pinnedRecord.ContainsKey('sourceSha') -or $pinnedRecord.syscallCapture -or $pinnedRecord.environmentCapture -cne 'SCRIPT_DECLARED_ONLY' -or
    $pinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.ApprovedOriginalSha256.pod -or
    $pinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.ApprovedOriginalSha256.authority) { throw 'Reconstruction invented or approved authority.' }
foreach ($entry in $pinnedInput.files.Values) {
    if ((Get-FileHash -LiteralPath (Resolve-K8sMember $assemblyData $entry.path)).Hash.ToLowerInvariant() -cne $entry.sha256 -or
        (Get-FileHash -LiteralPath (Resolve-K8sMember $pinnedOutput $entry.path)).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Original evidence changed.' }
}
Pass 'pinned-script-assembler-preserves-original-bytes-without-inventing-capture-sha-or-authority'
$pinnedReceipt = Clone $offline
$pinnedReceipt.files = $pinnedResult.files
(Get-K8sExecution $pinnedReceipt 'sdk-build').invocationQualification = $pinnedResult.invocationQualification
Reject 'pinned-script-original-authority-remains-pending' { Assert-K8sControlInvocation $pinnedReceipt 'control/17' $pinnedOutput $pinnedPolicy }
foreach ($name in @('pod','authority')) {
    $pinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.ApprovedOriginalSha256[$name] = $pinnedRecord.originalPins[$name].sha256
}
Assert-K8sControlInvocation $pinnedReceipt 'control/17' $pinnedOutput $pinnedPolicy
Pass 'synthetic-admitted-original-pins-qualify-script-declarations-without-historical-capture'
foreach ($case in @('mode', 'syscall-claim', 'environment-capture-claim', 'reconstruction-time', 'extra-option',
    'environment-override', 'missing-original', 'changed-original', 'foreign-pod', 'changed-cutoff', 'invented-source-sha')) {
    $badPinned = Clone $pinnedRecord
    switch ($case) {
        mode { $badPinned.evidenceMode = 'EXECVE' }
        syscall-claim { $badPinned.syscallCapture = $true }
        environment-capture-claim { $badPinned.environmentCapture = 'COMPLETE' }
        reconstruction-time { $badPinned.reconstructedAt = $offline.runs[0].createdAt }
        extra-option { $badPinned.argv += '-Dmaven.test.skip=true' }
        environment-override { $badPinned.environment.MAVEN_ARGS = 'install' }
        missing-original { $badPinned.originalPins.Remove('buildStarted') }
        changed-original { $badPinned.originalPins.buildExit.sha256 = '0' * 64 }
        foreign-pod { $badPinned.podUid = '00000000-1111-2222-3333-555555555555' }
        changed-cutoff { $badPinned.originalCutoff = '2026-01-01T01:00:01Z' }
        invented-source-sha { $badPinned.sourceSha = $sha }
    }
    Reject ('pinned-script-' + $case) { Assert-K8sPinnedScriptEvidence $badPinned $pinnedReceipt $pinnedOutput $pinnedPolicy }
}
$badPinnedPolicy = Clone $pinnedPolicy
$badPinnedPolicy.OfflinePackageInvocation.PinnedExecutedScript.ApprovedOriginalSha256.pod = '0' * 64
Reject 'pinned-script-unapproved-original-pod' { Assert-K8sControlInvocation $pinnedReceipt 'control/17' $pinnedOutput $badPinnedPolicy }
Reject 'pinned-script-original-inventory-pin-mismatch' {
    New-K8sPackageInvocationQualification -InventoryPath $pinnedInputPath -InventorySha256 ('0' * 64) `
        -EvidenceRoot $assemblyData -OutputDirectory (Join-Path $assemblyRoot 'bad-pinned-pin') -Policy $pinnedPolicy
}
Reject 'pinned-script-original-evidence-cannot-be-output' {
    New-K8sPackageInvocationQualification -InventoryPath $pinnedInputPath -InventorySha256 $pinnedInputHash `
        -EvidenceRoot $assemblyData -OutputDirectory (Join-Path $assemblyData 'unsafe-output') -Policy $pinnedPolicy
}
# Exercise the actual qualifier output through the actual acceptance assembler, not only its guards.
$combinedRoot = Join-Path $assemblyRoot 'combined-input'
$null = [IO.Directory]::CreateDirectory($combinedRoot)
$combined = Clone $aliased
$combinedBuild = Get-K8sExecution $combined 'sdk-build'
$combinedBuild.command = $pinnedBuild.command
$combinedBuild.canonicalCommand = $policy.Controls[17]
$combinedBuild.invocationQualification = $pinnedResult.invocationQualification
$combinedFiles = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
foreach ($entry in $combined.files) { $combinedFiles[$entry.path] = $entry }
foreach ($entry in $pinnedResult.files) { $combinedFiles[$entry.path] = $entry }
$combined.files = @($combinedFiles.Values)
foreach ($entry in $combined.files) {
    $from = if (@($pinnedResult.files | Where-Object { $_.path -ceq $entry.path }).Count) { $pinnedOutput } else { $assemblyData }
    Copy-K8sAssemblyFile (Resolve-K8sMember $from $entry.path) (Resolve-K8sMember $combinedRoot $entry.path) $entry.bytes $entry.sha256
}
function Assemble-Combined($Value, [string]$Name, $FixturePolicy) {
    $inputPath = Join-Path $assemblyRoot ($Name + '.json')
    [IO.File]::WriteAllText($inputPath, ($Value | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
    New-K8sAcceptanceReceipt -InventoryPath $inputPath -InventorySha256 (Get-FileHash -LiteralPath $inputPath).Hash.ToLowerInvariant() `
        -EvidenceRoot $combinedRoot -OutputDirectory (Join-Path $assemblyRoot $Name) -Policy $FixturePolicy
}
$pendingPolicy = Clone $pinnedPolicy
$pendingPolicy.OfflinePackageInvocation.PinnedExecutedScript.ApprovedOriginalSha256 = @{pod=''; authority=''}
Reject 'qualifier-to-assembler-does-not-self-admit-original-authority' { Assemble-Combined $combined 'combined-unapproved' $pendingPolicy }
$combinedResult = Assemble-Combined $combined 'combined-admitted' $pinnedPolicy
$combinedReceipt = Read-K8sJson $combinedResult.receiptPath
$combinedOutput = Split-Path -Parent $combinedResult.receiptPath
Assert-K8sExecutionRoster $combinedReceipt $combinedOutput $pinnedPolicy
if ($combinedReceipt.executions.Count -ne 52 -or
    (Get-K8sExecution $combinedReceipt 'sdk-build').invocationQualification.sha256 -cne $pinnedResult.invocationQualification.sha256 -or
    ![object]::ReferenceEquals((Get-K8sExecution $combinedReceipt 'sdk-build'), (Get-K8sExecution $combinedReceipt 'control/17'))) {
    throw 'Qualifier-to-assembler integration lost original execution binding.'
}
Pass 'real-qualifier-to-assembler-synthetic-integration-retains-single-build-and-all-obligations'
$snapshotInput = Clone $combined
$snapshotInput.schema = 'cachedb.k8s-acceptance-input/v2'
$snapshotInput.source.Remove('sha')
$snapshotInput.source.identity = @{kind='SOURCE_SNAPSHOT'; manifestSha256=$assemblySourcePin}
foreach ($execution in $snapshotInput.executions) {
    if ($execution.ContainsKey('executionRef')) { continue }
    $execution.Remove('sourceSha')
    $execution.sourceIdentity = Clone $snapshotInput.source.identity
}
$snapshotResult = Assemble-Combined $snapshotInput 'snapshot-assembled' $pinnedPolicy
$snapshotReceipt = Read-K8sJson $snapshotResult.receiptPath
$snapshotRoot = Split-Path -Parent $snapshotResult.receiptPath
$snapshotAuthority = Clone $authority
$snapshotAuthority.Remove('sha'); $snapshotAuthority.sourceIdentity = Clone $snapshotInput.source.identity
$snapshotAuthority.sdkPin = $assemblyPin
Assert-K8sHeader $snapshotReceipt $snapshotAuthority $pinnedPolicy
Assert-K8sExecutionRoster $snapshotReceipt $snapshotRoot $pinnedPolicy
Pass 'manifest-only-snapshot-assembler-and-guards-preserve-physical-source-identity'

# Synthetic v3 originals only. These controls grant neither custody nor runtime acceptance.
. (Join-Path $PSScriptRoot 'k8s-original-case-provenance.ps1')
$v3Root = Join-Path $EvidenceDirectory 'original-case-controls'
$null = [IO.Directory]::CreateDirectory($v3Root)
$v3Input = Clone $snapshotInput
$v3Input.schema = 'cachedb.k8s-acceptance-input/v3'
$v3Input.physicalExecutions = @()
$v3Input.executionSources = @()
$v3Policy = Clone $pinnedPolicy
$v3Policy.ReviewedOriginalReceipts = @{}
foreach ($entry in $v3Input.files) {
    Copy-K8sAssemblyFile (Resolve-K8sMember $combinedRoot $entry.path) (Resolve-K8sMember $v3Root $entry.path) $entry.bytes $entry.sha256
}
function Write-OriginalCaseFixture([string]$Member, $Value) {
    $path = Resolve-K8sMember $v3Root $Member
    $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $path))
    $text = if ($Value -is [string]) { $Value } else { $Value | ConvertTo-Json -Depth 32 }
    [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    return @{path=$Member; sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
}
$v3Reports = @()
$v3AdditionalReports = @()
foreach ($attempt in @('failed', 'closed')) {
    $failed = $attempt -ceq 'failed'
    $exit = if ($failed) { 1 } else { 0 }
    $start = if ($failed) { '2026-01-01T03:02:00+03:00' } else { '2026-01-01T03:11:00+03:00' }
    $finish = if ($failed) { '2026-01-01T03:10:00+03:00' } else { '2026-01-01T03:20:00+03:00' }
    $physicalId = if ($failed) { '10000000-1111-2222-3333-444444444444' } else { '20000000-1111-2222-3333-444444444444' }
    $log = Write-OriginalCaseFixture "original/$attempt/process.log" 'SYNTHETIC ORIGINAL PROCESS ONLY'
    $exitRef = Write-OriginalCaseFixture "original/$attempt/process.exit" ("$exit`n")
    $failure = if ($failed) { '<failure message="synthetic failure" />' } else { '' }
    $report = Write-OriginalCaseFixture "original/$attempt/TEST-original.xml" (
        '<testsuite name="synthetic.OriginalTest" tests="1" failures="' + $exit + '" errors="0" skipped="0"><testcase classname="synthetic.OriginalTest" name="originalCase">' + $failure + '</testcase></testsuite>')
    $v3Reports += @{executionId="original/$attempt"; path=$report.path; sha256=$report.sha256}
    $additional = Write-OriginalCaseFixture "original/$attempt/TEST-additional.xml" (
        '<testsuite name="synthetic.OriginalTest" tests="1" failures="0" errors="0" skipped="0"><testcase classname="synthetic.OriginalTest" name="additionalOriginalCase" /></testsuite>')
    $v3AdditionalReports += @{executionId="original/$attempt"; path=$additional.path; sha256=$additional.sha256}
    $record = @{id="captured/$attempt"; podUid=$uid; sdkManifestSha256=$assemblyPin
        physicalExecutionId=$physicalId; sourceManifestSha256=$assemblySourcePin; nativeExit=$exit
        startedUtc=$start; finishedUtc=$finish; cwd='/workspace/source'; executable='mvn'; arguments=@('-B','test')
        processLog=$log; processExit=$exitRef}
    $original = Write-OriginalCaseFixture "original/$attempt/record.json" @{executions=@($record)}
    $v3Input.physicalExecutions += @{id="original/$attempt"; podUid=$uid
        sourceIdentity=(Clone $v3Input.source.identity); sdkManifestSha256=$assemblyPin
        command='mvn -B test'; workingDirectory='/workspace/source'; startedAt=$start; finishedAt=$finish
        log=$log.path; exitFile=$exitRef.path; observedExit=$exit; originalRecord=$original
        originalRecordId=$record.id; originalPhysicalExecutionId=$physicalId}
}
$v3Index = @{schemaVersion='cachedb.k8s-original-case-provenance/v1'; obligationId='standalone'
    reports=(@($v3Reports) + @($v3AdditionalReports)); closures=@(@{
        from=@{reportPath=$v3Reports[0].path; className='synthetic.OriginalTest'; name='originalCase'; occurrence=1}
        to=@{reportPath=$v3Reports[1].path; className='synthetic.OriginalTest'; name='originalCase'; occurrence=1}})}
$v3IndexRef = Write-OriginalCaseFixture 'original/index.json' $v3Index
$v3Input.executions = @($v3Input.executions | ForEach-Object {
    if ($_.id -ceq 'standalone') {
        @{id='standalone'; kind='ORIGINAL_CASES'; physicalExecutionIds=@('original/failed','original/closed'); originalCases=$v3IndexRef}
    } else { $_ }
})
function Refresh-OriginalCaseFixture($Value, [switch]$PreserveCustody) {
    if (!$PreserveCustody) {
        foreach ($physical in $Value.physicalExecutions) {
            $prefix = $physical.id
            $names = @('record.json', 'process.log', 'process.exit', 'TEST-original.xml') + @(
                Get-ChildItem -LiteralPath (Resolve-K8sMember $v3Root $prefix) -File -Filter 'TEST-*.xml' |
                    Where-Object { $_.Name -cne 'TEST-original.xml' } | Sort-Object Name | ForEach-Object { $_.Name })
            $capturedFiles = @(foreach ($name in $names) {
                $path = Resolve-K8sMember $v3Root ($prefix + '/' + $name)
                [ordered]@{name=$name; sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
                    bytes=(Get-Item -LiteralPath $path).Length}
            })
            $custody = Write-OriginalCaseFixture ("original/custody/" + $prefix.Split('/')[-1] + '.json') `
                ([ordered]@{status='SELECTED_ORIGINALS_HASH_VERIFIED'; podUid=$physical.podUid; files=$capturedFiles})
            $physical.originalCustody = @{receipt=$custody; prefix=$prefix}
            # Synthetic admissions are private to these fixtures, never product custody authority.
            $v3Policy.ReviewedOriginalReceipts[$custody.sha256] = $physical.podUid
        }
    }
    $Value.files = @($Value.files | Where-Object { !$_.path.StartsWith('original/', [StringComparison]::Ordinal) })
    $Value.files += @(Get-ChildItem -LiteralPath (Join-Path $v3Root 'original') -Recurse -File | ForEach-Object {
        @{path=[IO.Path]::GetRelativePath($v3Root,$_.FullName).Replace('\','/'); bytes=$_.Length
            sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })
}
Refresh-OriginalCaseFixture $v3Input
$v3Receipt = Clone $v3Input
$v3Receipt.schema = 'cachedb.k8s-acceptance/v3'; $v3Receipt.kind = $policy.Kind
function Assert-OriginalCaseFixture($Value, [string]$ObligationId = 'standalone', $FixturePolicy = $v3Policy) {
    Assert-K8sHeader $Value $snapshotAuthority $FixturePolicy
    $obligation = Get-K8sExecution $Value $ObligationId
    $processFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    Assert-K8sOriginalCaseExecutions $obligation $Value $v3Root $FixturePolicy $processFiles
    $result = Assert-K8sOriginalCaseProvenance -IndexPath (Resolve-K8sMember $v3Root $obligation.originalCases.path) `
        -IndexSha256 $obligation.originalCases.sha256 -EvidenceRoot $v3Root -ExecutionIds $obligation.physicalExecutionIds `
        -ExpectedObligationId $ObligationId -RequiredSuites @('synthetic.OriginalTest') -RequiredMethods @{'synthetic.OriginalTest'=@('originalCase')}
    if (!$result.requiredContractsChecked -or !$result.allSelectedMethodsPassed -or $result.closedFailures.Count -ne 1) {
        throw 'Original-case fixture lost its explicit failure closure or required method coverage.'
    }
}
Assert-OriginalCaseFixture $v3Receipt
Pass 'v3-original-failure-remains-native-one-with-explicit-later-method-closure'
foreach ($case in @('omitted-passing', 'omitted-failed', 'omitted-unstaged', 'reassigned', 'duplicate')) {
    $bad = Clone $v3Receipt
    $index = Clone $v3Index
    $physical = $bad.physicalExecutions[0]
    $omitted = $v3AdditionalReports[0].path
    if ($case -ceq 'omitted-failed') { $omitted = $v3Reports[0].path }
    switch ($case) {
        reassigned { $index.reports[2].executionId = 'original/closed' }
        duplicate { $index.reports += (Clone $index.reports[2]) }
        default { $index.reports = @($index.reports | Where-Object { $_.path -cne $omitted }) }
    }
    if ($case -ceq 'omitted-unstaged') { $bad.files = @($bad.files | Where-Object { $_.path -cne $omitted }) }
    Reject ('v3-complete-ordinary-original-roster-' + $case) {
        Assert-K8sOriginalCustody $physical $index $bad $v3Root $v3Policy
    }
}
# One admitted receipt can contain several processes; completeness is its original XML union.
$v3CombinedCustody = Clone $v3Receipt
$combinedCustodyFiles = @(foreach ($physical in $v3CombinedCustody.physicalExecutions) {
    $captured = Read-K8sJson (Resolve-K8sMember $v3Root $physical.originalCustody.receipt.path)
    foreach ($entry in $captured.files) {
        @{name=$physical.id.Substring('original/'.Length) + '/' + $entry.name; bytes=$entry.bytes; sha256=$entry.sha256}
    }
})
$combinedCustodyRef = Write-OriginalCaseFixture 'original/custody/combined.json' @{
    status='SELECTED_ORIGINALS_HASH_VERIFIED'; podUid=$uid; files=$combinedCustodyFiles}
$v3Policy.ReviewedOriginalReceipts[$combinedCustodyRef.sha256] = $uid
foreach ($physical in $v3CombinedCustody.physicalExecutions) {
    $physical.originalCustody = @{receipt=$combinedCustodyRef; prefix='original'}
}
Refresh-OriginalCaseFixture $v3CombinedCustody -PreserveCustody
Assert-OriginalCaseFixture $v3CombinedCustody
Pass 'v3-complete-ordinary-original-roster-spans-all-captured-processes'
$incompleteCombinedIndex = Clone $v3Index
$incompleteCombinedIndex.reports = @($incompleteCombinedIndex.reports | Where-Object { $_.path -cne $v3AdditionalReports[1].path })
Reject 'v3-complete-ordinary-roster-cannot-omit-another-process-report' {
    Assert-K8sOriginalCustody $v3CombinedCustody.physicalExecutions[0] $incompleteCombinedIndex $v3CombinedCustody $v3Root $v3Policy
}
Refresh-OriginalCaseFixture $v3Receipt
foreach ($case in @('missing-physical-array','missing-source-array','duplicate-physical','unbound-physical',
    'missing-physical','shared-physical','synthetic-process-fields','native-zero-substitute','foreign-source',
    'foreign-sdk','foreign-pod','outside-cutoff','reused-process-log','missing-original-record')) {
    $bad = Clone $v3Receipt
    $logical = Get-K8sExecution $bad 'standalone'
    switch ($case) {
        missing-physical-array { $bad.Remove('physicalExecutions') }
        missing-source-array { $bad.Remove('executionSources') }
        duplicate-physical { $bad.physicalExecutions += (Clone $bad.physicalExecutions[0]) }
        unbound-physical { $extra = Clone $bad.physicalExecutions[0]; $extra.id='original/unbound'; $bad.physicalExecutions += $extra }
        missing-physical { $logical.physicalExecutionIds += 'original/missing' }
        shared-physical { $logical.physicalExecutionIds += $logical.physicalExecutionIds[0] }
        synthetic-process-fields { $logical.observedExit=0; $logical.log='log'; $logical.exitFile='exit' }
        native-zero-substitute { $bad.physicalExecutions[0].observedExit=0 }
        foreign-source { $bad.physicalExecutions[0].sourceIdentity.manifestSha256='0'*64 }
        foreign-sdk { $bad.physicalExecutions[0].sdkManifestSha256='0'*64 }
        foreign-pod { $bad.physicalExecutions[0].podUid='30000000-1111-2222-3333-444444444444' }
        outside-cutoff { $bad.physicalExecutions[1].finishedAt='2026-01-01T01:00:01Z' }
        reused-process-log { $bad.physicalExecutions[1].log=$bad.physicalExecutions[0].log }
        missing-original-record { $bad.physicalExecutions[0].originalRecord.path='original/missing.json' }
    }
    Reject ('v3-original-' + $case) { Assert-OriginalCaseFixture $bad }
}
foreach ($schema in @('cachedb.k8s-acceptance/v1','cachedb.k8s-acceptance/v2')) {
    $bad = Clone $v3Receipt; $bad.schema=$schema
    Reject ('v3-original-not-legacy-' + $schema.Split('/')[-1]) {
        Test-K8sOriginalCaseObligation (Get-K8sExecution $bad 'standalone') $bad
    }
}
foreach ($case in @('pod','physical-id','source','native-exit','command','start','finish','log-pin','missing-process-pins')) {
    $bad = Clone $v3Receipt
    $physical = $bad.physicalExecutions[0]
    $captured = Read-K8sJson (Resolve-K8sMember $v3Root $physical.originalRecord.path)
    $record = $captured.executions[0]
    switch ($case) {
        pod { $record.podUid='30000000-1111-2222-3333-444444444444' }
        physical-id { $record.physicalExecutionId='30000000-1111-2222-3333-444444444444' }
        source { $record.sourceManifestSha256='0'*64 }
        native-exit { $record.nativeExit=0 }
        command { $record.arguments=@('-B','validate') }
        start { $record.startedUtc='2026-01-01T00:03:00Z' }
        finish { $record.finishedUtc='2026-01-01T00:09:00Z' }
        log-pin { $record.processLog.sha256='0'*64 }
        missing-process-pins { $record.Remove('processLog'); $record.Remove('processExit') }
    }
    $saved = [IO.File]::ReadAllText((Resolve-K8sMember $v3Root $physical.originalRecord.path))
    try {
        $physical.originalRecord = Write-OriginalCaseFixture $physical.originalRecord.path $captured
        Refresh-OriginalCaseFixture $bad
        Reject ('v3-captured-record-' + $case) { Assert-OriginalCaseFixture $bad }
    } finally {
        $null = Write-OriginalCaseFixture $physical.originalRecord.path $saved
        Refresh-OriginalCaseFixture $v3Receipt
    }
}
foreach ($case in @('overlapping-time','reversed-time','same-execution','unclosed-failure','wrong-method','duplicate-edge')) {
    $bad = Clone $v3Receipt
    $index = Clone $v3Index
    switch ($case) {
        overlapping-time { $bad.physicalExecutions[1].startedAt='2026-01-01T00:09:00Z' }
        reversed-time { $bad.physicalExecutions[1].startedAt='2026-01-01T00:01:00Z' }
        same-execution { $index.reports[1].executionId=$index.reports[0].executionId }
        unclosed-failure { $index.closures=@() }
        wrong-method { $index.closures[0].to.name='unrelatedCase' }
        duplicate-edge { $index.closures += (Clone $index.closures[0]) }
    }
    $recordPath = $bad.physicalExecutions[1].originalRecord.path
    $saved = [IO.File]::ReadAllText((Resolve-K8sMember $v3Root $recordPath))
    try {
        if ($case -in @('overlapping-time','reversed-time')) {
            $captured = Read-K8sJson (Resolve-K8sMember $v3Root $recordPath)
            $captured.executions[0].startedUtc=$bad.physicalExecutions[1].startedAt
            $bad.physicalExecutions[1].originalRecord=Write-OriginalCaseFixture $recordPath $captured
        }
        (Get-K8sExecution $bad 'standalone').originalCases=Write-OriginalCaseFixture 'original/index.json' $index
        Refresh-OriginalCaseFixture $bad
        Reject ('v3-closure-' + $case) { Assert-OriginalCaseFixture $bad }
    } finally {
        $null=Write-OriginalCaseFixture $recordPath $saved
        $null=Write-OriginalCaseFixture 'original/index.json' $v3Index
        Refresh-OriginalCaseFixture $v3Receipt
    }
}
foreach ($case in @('unapproved-receipt', 'foreign-pod', 'record-hash', 'log-hash', 'exit-hash', 'report-hash', 'prefix-mismatch')) {
    $bad = Clone $v3Receipt
    $casePolicy = Clone $v3Policy
    $physical = $bad.physicalExecutions[0]
    $receiptPath = $physical.originalCustody.receipt.path
    $saved = [IO.File]::ReadAllText((Resolve-K8sMember $v3Root $receiptPath))
    $captured = Read-K8sJson (Resolve-K8sMember $v3Root $receiptPath)
    $expectedError = 'Referenced evidence is not independently sealed.'
    try {
        switch ($case) {
            unapproved-receipt {
                $casePolicy.ReviewedOriginalReceipts.Remove($physical.originalCustody.receipt.sha256)
                $expectedError = 'Original custody requires an independently reviewed captured receipt.'
            }
            foreign-pod {
                $captured.podUid = '30000000-1111-2222-3333-444444444444'
                $expectedError = 'Captured custody identity or bounded file inventory differs.'
            }
            record-hash { $captured.files[0].sha256 = '0' * 64 }
            log-hash { $captured.files[1].sha256 = '0' * 64 }
            exit-hash { $captured.files[2].sha256 = '0' * 64 }
            report-hash {
                $captured.files[3].sha256 = '0' * 64
                $expectedError = 'Indexed original XML differs from admitted custody.'
            }
            prefix-mismatch {
                $physical.originalCustody.prefix = 'original/closed'
                $expectedError = 'Unmapped or duplicate ordinary original XML.'
            }
        }
        if ($case -notin @('unapproved-receipt', 'prefix-mismatch')) {
            $physical.originalCustody.receipt = Write-OriginalCaseFixture $receiptPath $captured
            $casePolicy.ReviewedOriginalReceipts[$physical.originalCustody.receipt.sha256] = $physical.podUid
        }
        Refresh-OriginalCaseFixture $bad -PreserveCustody
        $rejected = $false
        try { Assert-OriginalCaseFixture $bad -FixturePolicy $casePolicy } catch {
            if ($_.Exception.Message -cne $expectedError) { throw }
            $rejected = $true
        }
        if (!$rejected) { throw "Unsafe synthetic original custody admitted: $case" }
        Pass ('v3-original-custody-' + $case)
    } finally {
        $null = Write-OriginalCaseFixture $receiptPath $saved
        Refresh-OriginalCaseFixture $v3Receipt
    }
}
Refresh-OriginalCaseFixture $v3Input
$v3InventoryPath = Join-Path $assemblyRoot 'original-v3-input.json'
[IO.File]::WriteAllText($v3InventoryPath,($v3Input|ConvertTo-Json -Depth 32),[Text.UTF8Encoding]::new($false))
$v3Assembled = New-K8sAcceptanceReceipt -InventoryPath $v3InventoryPath `
    -InventorySha256 (Get-FileHash -LiteralPath $v3InventoryPath).Hash.ToLowerInvariant() `
    -EvidenceRoot $v3Root -OutputDirectory (Join-Path $assemblyRoot 'original-v3-assembled') -Policy $v3Policy
$v3AssembledReceipt = Read-K8sJson $v3Assembled.receiptPath
if ($v3Assembled.status -cne 'ASSEMBLED_NOT_RUNTIME_ACCEPTANCE' -or $v3AssembledReceipt.schema -cne 'cachedb.k8s-acceptance/v3' -or
    $v3AssembledReceipt.physicalExecutions[0].observedExit -ne 1 -or $v3AssembledReceipt.executions.Count -ne 52) {
    throw 'V3 assembly relabelled original failures or changed the legacy obligation roster.'
}
Pass 'v3-assembler-retains-failed-originals-all-obligations-and-no-runtime-acceptance'

# Exercise provider guards with matching captured records, process pins and index identity.
$v3ProviderId = 'provider/postgresql'
$v3ProviderReceipt = Clone $v3Receipt
$v3ProviderLogical = Clone (Get-K8sExecution $v3ProviderReceipt 'standalone')
$v3ProviderLogical.id = $v3ProviderId
$v3ProviderReceipt.executions = @($v3ProviderReceipt.executions | ForEach-Object {
    if ($_.id -ceq 'standalone') { Clone (Get-K8sExecution $snapshotReceipt 'standalone') }
    elseif ($_.id -ceq $v3ProviderId) { $v3ProviderLogical }
    else { $_ }
})
$v3ProviderIndex = Clone $v3Index
$v3ProviderIndex.obligationId = $v3ProviderId
$v3SavedOriginals = @{}
foreach ($member in @('original/index.json') + @($v3ProviderReceipt.physicalExecutions | ForEach-Object { $_.originalRecord.path })) {
    $v3SavedOriginals[$member] = [IO.File]::ReadAllText((Resolve-K8sMember $v3Root $member))
}
try {
    foreach ($property in @('skipTests', 'maven.test.skip')) {
        foreach ($value in @('false', 'FALSE', 'true', 'TRUE', '')) {
            $candidate = Clone $v3ProviderReceipt
            $flag = if ($value) { "-D$property=$value" } else { "-D$property" }
            $label = switch -CaseSensitive ($value) {
                'false' { 'lower-false' }
                'FALSE' { 'upper-false' }
                'true' { 'lower-true' }
                'TRUE' { 'upper-true' }
                '' { 'bare' }
            }
            foreach ($physical in $candidate.physicalExecutions) {
                $captured = Read-K8sJson (Resolve-K8sMember $v3Root $physical.originalRecord.path)
                $record = $captured.executions[0]
                $record.cwd = '/workspace/source/sample-cache-database-postgresql'
                $record.arguments = @('-B', 'test', $flag)
                $physical.workingDirectory = $record.cwd
                $physical.command = $record.executable + ' ' + ($record.arguments -join ' ')
                $physical.originalRecord = Write-OriginalCaseFixture $physical.originalRecord.path $captured
            }
            (Get-K8sExecution $candidate $v3ProviderId).originalCases = Write-OriginalCaseFixture 'original/index.json' $v3ProviderIndex
            Refresh-OriginalCaseFixture $candidate
            $name = "v3-provider-original-$property-$label"
            if ($value -ieq 'false') {
                Assert-OriginalCaseFixture $candidate $v3ProviderId
            } else {
                $rejected = $false
                try { Assert-OriginalCaseFixture $candidate $v3ProviderId } catch {
                    if ($_.Exception.Message -cne 'Original provider invocation is not a reviewed test execution.') { throw }
                    $rejected = $true
                }
                if (!$rejected) { throw "Unsafe synthetic provider invocation admitted: $flag" }
            }
            Pass $name
        }
    }
} finally {
    foreach ($member in $v3SavedOriginals.Keys) {
        $null = Write-OriginalCaseFixture $member $v3SavedOriginals[$member]
    }
    Refresh-OriginalCaseFixture $v3Receipt
}

# A real cluster process may also close the same cluster-only standalone skips.
$v3ClusterReceipt = Clone $v3Receipt
$clusterLogical = Clone (Get-K8sExecution $v3ClusterReceipt 'standalone')
$clusterLogical.id = 'cluster'
$clusterIndex = Clone $v3Index
$clusterIndex.obligationId = 'cluster'
$clusterLogical.originalCases = Write-OriginalCaseFixture 'original/cluster-index.json' $clusterIndex
$v3ClusterReceipt.executions = @($v3ClusterReceipt.executions | ForEach-Object {
    if ($_.id -ceq 'cluster') { $clusterLogical } else { $_ }
})
try {
    foreach ($physical in $v3ClusterReceipt.physicalExecutions) {
        $captured = Read-K8sJson (Resolve-K8sMember $v3Root $physical.originalRecord.path)
        $captured.executions[0].arguments = @('-B', '-Dcachedb.redis.cluster=true', 'test')
        $physical.command = 'mvn -B -Dcachedb.redis.cluster=true test'
        $physical.originalRecord = Write-OriginalCaseFixture $physical.originalRecord.path $captured
    }
    Refresh-OriginalCaseFixture $v3ClusterReceipt
    Assert-K8sHeader $v3ClusterReceipt $snapshotAuthority $v3Policy
    $sharedFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $sharedOriginals = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($lane in @('standalone', 'cluster')) {
        Assert-K8sOriginalCaseExecutions (Get-K8sExecution $v3ClusterReceipt $lane) $v3ClusterReceipt $v3Root $v3Policy $sharedFiles $sharedOriginals
        Assert-OriginalCaseFixture $v3ClusterReceipt $lane
    }
    if ($sharedFiles.Count -ne 4 -or $sharedOriginals.Count -ne 2) { throw 'Shared originals were counted as new processes.' }
    Pass 'v3-cluster-sharing-preserves-original-processes-and-both-coverage-contracts'
    foreach ($case in @('omitted-report', 'reassigned-report', 'changed-report-hash', 'duplicate-report', 'both-omit-report')) {
        $bad = Clone $v3ClusterReceipt
        $badClusterIndex = Clone $clusterIndex
        switch ($case) {
            omitted-report { $badClusterIndex.reports = @($badClusterIndex.reports | Where-Object { $_.path -cne $v3AdditionalReports[0].path }) }
            reassigned-report { $badClusterIndex.reports[2].executionId = 'original/closed' }
            changed-report-hash { $badClusterIndex.reports[2].sha256 = '0' * 64 }
            duplicate-report { $badClusterIndex.reports += (Clone $badClusterIndex.reports[2]) }
            both-omit-report { $badClusterIndex.reports = @($badClusterIndex.reports | Where-Object { $_.path -cne $v3AdditionalReports[0].path }) }
        }
        try {
            (Get-K8sExecution $bad 'cluster').originalCases = Write-OriginalCaseFixture 'original/cluster-index.json' $badClusterIndex
            if ($case -ceq 'both-omit-report') {
                $badStandaloneIndex = Clone $badClusterIndex
                $badStandaloneIndex.obligationId = 'standalone'
                (Get-K8sExecution $bad 'standalone').originalCases = Write-OriginalCaseFixture 'original/index.json' $badStandaloneIndex
            }
            Refresh-OriginalCaseFixture $bad -PreserveCustody
            Reject ('v3-shared-original-report-set-' + $case) { Assert-OriginalCaseFixture $bad }
        } finally {
            $null = Write-OriginalCaseFixture 'original/index.json' $v3Index
            $null = Write-OriginalCaseFixture 'original/cluster-index.json' $clusterIndex
        }
    }
    # Report ordering is not provenance: both indexes must bind the same immutable set.
    $reorderedClusterIndex = Clone $clusterIndex
    [array]::Reverse($reorderedClusterIndex.reports)
    try {
        (Get-K8sExecution $v3ClusterReceipt 'cluster').originalCases = Write-OriginalCaseFixture 'original/cluster-index.json' $reorderedClusterIndex
        Refresh-OriginalCaseFixture $v3ClusterReceipt -PreserveCustody
        Assert-OriginalCaseFixture $v3ClusterReceipt
        Pass 'v3-shared-original-report-set-order-is-irrelevant'
    } finally {
        (Get-K8sExecution $v3ClusterReceipt 'cluster').originalCases = Write-OriginalCaseFixture 'original/cluster-index.json' $clusterIndex
        Refresh-OriginalCaseFixture $v3ClusterReceipt -PreserveCustody
    }
    foreach ($case in @('provider-sharing', 'non-cluster-sharing', 'foreign-cwd-sharing')) {
        $bad = Clone $v3ClusterReceipt
        switch ($case) {
            provider-sharing { (Get-K8sExecution $bad 'cluster').id = 'provider/postgresql' }
            non-cluster-sharing { $bad.physicalExecutions[0].command = 'mvn -B test' }
            foreign-cwd-sharing { $bad.physicalExecutions[0].workingDirectory = '/workspace/source/other' }
        }
        Reject ('v3-original-' + $case) { Assert-K8sOriginalCaseHeader $bad $v3Policy }
    }
} finally {
    foreach ($member in $v3SavedOriginals.Keys) {
        $null = Write-OriginalCaseFixture $member $v3SavedOriginals[$member]
    }
    Refresh-OriginalCaseFixture $v3Receipt
}

# Future provider captures retain physical argv/cwd; no historical record is reconstructed.
$providerInput = Clone $snapshotInput
$providerInput.schema = $policy.SnapshotSchema
$providerPolicy = Clone $pinnedPolicy
$providerRoot = Join-Path $EvidenceDirectory 'provider-invocation-controls'
$null = [IO.Directory]::CreateDirectory($providerRoot)
foreach ($entry in $providerInput.files) {
    Copy-K8sAssemblyFile (Resolve-K8sMember $snapshotRoot $entry.path) (Resolve-K8sMember $providerRoot $entry.path) $entry.bytes $entry.sha256
}
function Write-ProviderInvocationFixture($Value, [string]$Provider) {
    $member = "provider-invocations/$Provider.json"
    $path = Resolve-K8sMember $providerRoot $member
    $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $path))
    [IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
    $pin = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
    $providerInput.files = @($providerInput.files | Where-Object { $_.path -cne $member }) +
        @(@{path=$member; sha256=$pin; bytes=(Get-Item -LiteralPath $path).Length})
    (Get-K8sExecution $providerInput "provider/$Provider").invocationQualification = @{path=$member; sha256=$pin}
}
$settingsMember = 'provider-invocations/settings.xml'
$null = [IO.Directory]::CreateDirectory((Join-Path $providerRoot 'provider-invocations'))
$settingsPath = Resolve-K8sMember $providerRoot $settingsMember
$buildSettingsPath = Resolve-K8sMember $assemblyData $capture.inputPins.settings.path
Copy-K8sAssemblyFile $buildSettingsPath $settingsPath (Get-Item -LiteralPath $buildSettingsPath).Length `
    $pinnedPolicy.OfflinePackageInvocation.InputSha256.settings
$settingsPin = (Get-FileHash -LiteralPath $settingsPath).Hash.ToLowerInvariant()
if ($settingsPin -cne $providerPolicy.OfflinePackageInvocation.InputSha256.settings) {
    throw 'Provider fixture must preserve the original build-approved settings pin.'
}
Pass 'offline-provider-reuses-build-approved-settings-without-changing-policy'
$providerInput.files += @{path=$settingsMember; sha256=$settingsPin; bytes=(Get-Item -LiteralPath $settingsPath).Length}
$providerCaptures = @{}
$providerArgv = @('mvn', '-o', '-s', '/workspace/input/settings-offline.xml', '-gs',
    '/workspace/input/settings-offline.xml', '-Dmaven.repo.local=/workspace/repository',
    '-B', 'clean', 'verify', '-Pprovider-integration')
foreach ($provider in $policy.Providers) {
    $execution = Get-K8sExecution $providerInput "provider/$provider"
    $relative = "sample-cache-database-$provider"
    $execution.command = $providerArgv -join ' '
    $execution.workingDirectory = '/workspace/source/' + $relative
    $execution.canonicalCommand = 'mvn -B clean verify -Pprovider-integration'
    $providerCaptures[$provider] = @{schema='cachedb.k8s-offline-provider-invocation/v1'
        argv=$providerArgv; workingDirectory=$execution.workingDirectory; relativeWorkingDirectory=$relative
        sourceIdentity=(Clone $execution.sourceIdentity); sdkManifestSha256=$assemblyPin; podUid=$execution.podUid
        startedAt=$execution.startedAt; finishedAt=$execution.finishedAt; originalCutoff=$providerInput.runs[0].originalCutoff
        settings=@{path=$settingsMember; sha256=$settingsPin}
        sdkManifest=@{path=$providerInput.sdk.manifest; sha256=$assemblyPin}
        environment=@{MAVEN_SKIP_RC='1'; MAVEN_OPTS='-Xms64m -Xmx768m -XX:ActiveProcessorCount=2'}
        unsetEnvironment=@('MAVEN_ARGS', 'MAVEN_CONFIG', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')
        logSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $providerRoot $execution.log)).Hash.ToLowerInvariant()
        exitSha256=(Get-FileHash -LiteralPath (Resolve-K8sMember $providerRoot $execution.exitFile)).Hash.ToLowerInvariant()}
    Write-ProviderInvocationFixture $providerCaptures[$provider] $provider
    Assert-K8sProviderInvocation $execution $providerInput $providerRoot $providerPolicy
    Pass ("offline-provider-exact-physical-invocation-$provider")
}
Assert-K8sExecutionRoster $providerInput $providerRoot $providerPolicy
$providerInventoryPath = Join-Path $assemblyRoot 'offline-providers.json'
$providerAssemblyInput = Clone $providerInput
$providerAssemblyInput.schema = 'cachedb.k8s-acceptance-input/v2'
[IO.File]::WriteAllText($providerInventoryPath, ($providerAssemblyInput | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
$providerResult = New-K8sAcceptanceReceipt -InventoryPath $providerInventoryPath `
    -InventorySha256 (Get-FileHash -LiteralPath $providerInventoryPath).Hash.ToLowerInvariant() `
    -EvidenceRoot $providerRoot -OutputDirectory (Join-Path $assemblyRoot 'offline-providers') -Policy $providerPolicy
$providerReceipt = Read-K8sJson $providerResult.receiptPath
$providerOutput = Split-Path -Parent $providerResult.receiptPath
Assert-K8sExecutionRoster $providerReceipt $providerOutput $providerPolicy
foreach ($provider in $policy.Providers) {
    $actual = Get-K8sExecution $providerReceipt "provider/$provider"
    $original = Get-K8sExecution $providerInput "provider/$provider"
    foreach ($field in @('id', 'command', 'workingDirectory', 'canonicalCommand', 'podUid', 'sdkManifestSha256', 'log', 'exitFile')) {
        if ($actual[$field] -cne $original[$field]) { throw 'Provider assembly relabelled an original execution field.' }
    }
    foreach ($field in @('startedAt', 'finishedAt')) {
        if (!(ConvertTo-K8sTimestamp $actual[$field]).Equals((ConvertTo-K8sTimestamp $original[$field]))) {
            throw 'Provider assembly changed an original process interval.'
        }
    }
    if ($actual.sourceIdentity.kind -cne $original.sourceIdentity.kind -or
        $actual.sourceIdentity.manifestSha256 -cne $original.sourceIdentity.manifestSha256 -or
        $actual.invocationQualification.path -cne $original.invocationQualification.path -or
        $actual.invocationQualification.sha256 -cne $original.invocationQualification.sha256 -or
        (Get-FileHash -LiteralPath (Resolve-K8sMember $providerOutput $actual.invocationQualification.path)).Hash.ToLowerInvariant() -cne
        $original.invocationQualification.sha256) { throw 'Provider assembly relabelled original source or capture.' }
}
Pass 'offline-provider-assembler-preserves-physical-command-cwd-source-and-capture'
foreach ($case in @('extra-property', 'extra-goal', 'extra-profile', 'duplicate-option', 'argv-order', 'settings-argv', 'global-settings-argv', 'cache-argv',
    'capture-cwd', 'relative-cwd', 'source', 'source-git-alias', 'sdk', 'uid', 'started', 'finished', 'cutoff',
    'log-pin', 'exit-pin', 'settings-pin', 'settings-member', 'sdk-member', 'sdk-manifest-pin',
    'environment-extra', 'environment-skip', 'environment-array', 'schema-array', 'cwd-array', 'unset-environment', 'extra-field')) {
    $badCapture = Clone $providerCaptures.postgresql
    switch ($case) {
        extra-property { $badCapture.argv += '-DskipTests' }
        extra-goal { $badCapture.argv += 'install' }
        extra-profile { $badCapture.argv += '-Pother' }
        duplicate-option { $badCapture.argv += '-B' }
        argv-order { $badCapture.argv[8] = 'verify'; $badCapture.argv[9] = 'clean' }
        settings-argv { $badCapture.argv[3] = '/workspace/input/other.xml' }
        global-settings-argv { $badCapture.argv[5] = '/workspace/input/other.xml' }
        cache-argv { $badCapture.argv[6] = '-Dmaven.repo.local=/tmp/other' }
        capture-cwd { $badCapture.workingDirectory = '/workspace/source/sample-cache-database-mssql' }
        relative-cwd { $badCapture.relativeWorkingDirectory = 'sample-cache-database-mssql' }
        source { $badCapture.sourceIdentity.manifestSha256 = '0' * 64 }
        source-git-alias { $badCapture.sourceSha = $sha }
        sdk { $badCapture.sdkManifestSha256 = '0' * 64 }
        uid { $badCapture.podUid = '11111111-1111-2222-3333-444444444444' }
        started { $badCapture.startedAt = '2026-01-01T00:02:00Z' }
        finished { $badCapture.finishedAt = '2026-01-01T00:57:00Z' }
        cutoff { $badCapture.originalCutoff = '2026-01-01T02:00:00Z' }
        log-pin { $badCapture.logSha256 = '0' * 64 }
        exit-pin { $badCapture.exitSha256 = '0' * 64 }
        settings-pin { $badCapture.settings.sha256 = '0' * 64 }
        settings-member { $badCapture.settings.path = 'missing-settings.xml' }
        sdk-member { $badCapture.sdkManifest.path = $providerInput.sdk.before }
        sdk-manifest-pin { $badCapture.sdkManifest.sha256 = '0' * 64 }
        environment-extra { $badCapture.environment.MAVEN_ARGS = '-DskipTests' }
        environment-skip { $badCapture.environment.MAVEN_OPTS += ' -Dmaven.test.skip=true' }
        environment-array { $badCapture.environment.MAVEN_OPTS = @($badCapture.environment.MAVEN_OPTS) }
        schema-array { $badCapture.schema = @($badCapture.schema) }
        cwd-array { $badCapture.workingDirectory = @($badCapture.workingDirectory) }
        unset-environment { $badCapture.unsetEnvironment = @($badCapture.unsetEnvironment | Where-Object { $_ -cne 'MAVEN_ARGS' }) }
        extra-field { $badCapture.reconstructedAt = '2026-01-01T01:00:00Z' }
    }
    Write-ProviderInvocationFixture $badCapture 'postgresql'
    Reject ('offline-provider-' + $case) {
        Assert-K8sProviderInvocation (Get-K8sExecution $providerInput 'provider/postgresql') $providerInput $providerRoot $providerPolicy
    }
}
Write-ProviderInvocationFixture $providerCaptures.postgresql 'postgresql'
foreach ($case in @('missing-qualification', 'unsealed-qualification', 'extra-command', 'relabelled-command', 'relabelled-cwd',
    'canonical-conflict', 'failed-exit', 'foreign-source', 'foreign-sdk', 'nonprovider-absolute-cwd')) {
    $bad = Clone $providerInput
    $execution = Get-K8sExecution $bad 'provider/postgresql'
    switch ($case) {
        missing-qualification { $execution.Remove('invocationQualification') }
        unsealed-qualification { $execution.invocationQualification.sha256 = '0' * 64 }
        extra-command { $execution.command += ' -DskipTests' }
        relabelled-command { $execution.command = $execution.canonicalCommand }
        relabelled-cwd { $execution.workingDirectory = 'sample-cache-database-postgresql' }
        canonical-conflict { $execution.canonicalCommand = 'mvn install' }
        failed-exit { $execution.exitFile = 'process/unexpected-failure.exit' }
        foreign-source { $execution.sourceIdentity.manifestSha256 = '0' * 64 }
        foreign-sdk { $execution.sdkManifestSha256 = '0' * 64 }
        nonprovider-absolute-cwd { (Get-K8sExecution $bad 'standalone').workingDirectory = '/workspace/source' }
    }
    Reject ('offline-provider-roster-' + $case) { Assert-K8sExecutionRoster $bad $providerRoot $providerPolicy }
    if ($case -in @('extra-command', 'relabelled-cwd', 'unsealed-qualification')) {
        $bad.schema = 'cachedb.k8s-acceptance-input/v2'
        $badInventoryPath = Join-Path $assemblyRoot ("offline-provider-$case.json")
        [IO.File]::WriteAllText($badInventoryPath, ($bad | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
        Reject ('offline-provider-assembler-' + $case) {
            New-K8sAcceptanceReceipt -InventoryPath $badInventoryPath `
                -InventorySha256 (Get-FileHash -LiteralPath $badInventoryPath).Hash.ToLowerInvariant() `
                -EvidenceRoot $providerRoot -OutputDirectory (Join-Path $assemblyRoot "offline-provider-$case") -Policy $providerPolicy
        }
    }
}
$badProviderPolicy = Clone $providerPolicy
$badProviderPolicy.OfflinePackageInvocation.InputSha256.settings = '0' * 64
Reject 'offline-provider-cannot-self-approve-settings-pin' {
    Assert-K8sProviderInvocation (Get-K8sExecution $providerInput 'provider/postgresql') $providerInput $providerRoot $badProviderPolicy
}
foreach ($case in @('source-git-alias','execution-git-alias','foreign-execution-snapshot','missing-execution-snapshot','mixed-schema','manifest-binding')) {
    $bad = Clone $snapshotInput
    $actual = Get-K8sExecution $bad 'sdk-build'
    switch ($case) {
        source-git-alias { $bad.source.sha = $sha }
        execution-git-alias { $actual.sourceSha = $sha }
        foreign-execution-snapshot { $actual.sourceIdentity.manifestSha256 = '0' * 64 }
        missing-execution-snapshot { $actual.Remove('sourceIdentity') }
        mixed-schema { $bad.schema = 'cachedb.k8s-acceptance-input/v1' }
        manifest-binding {
            $bad.source.identity.manifestSha256 = '0' * 64
            foreach ($item in $bad.executions) { if (!$item.ContainsKey('executionRef')) { $item.sourceIdentity = Clone $bad.source.identity } }
        }
    }
    Reject ('snapshot-assembler-' + $case) { Assemble-Combined $bad ('snapshot-bad-' + $case) $pinnedPolicy }
}

# Pure synthetic final-checkout adapters; no Git, process, network, or product execution.
. (Join-Path $PSScriptRoot 'k8s-source-equivalence.ps1')
if ((Get-K8sOverlayClassification '.github/actions/cachedb-v019-performance/README.md') -cne 'documentation') {
    throw 'Explicit performance action documentation was not classified.'
}
Pass 'explicit-performance-action-documentation'
Reject 'unrelated-action-documentation' { Get-K8sOverlayClassification '.github/actions/unrelated/README.md' }
foreach ($member in @('.github/actions/cachedb-v019-performance/action.yml',
        '.github/actions/cachedb-redis-performance/action.yml',
        'tools/ci/prepare-ematech-resource-guard.sh','tools/ci/run-ematech-redis-performance.sh')) {
    if ((Get-K8sOverlayClassification $member) -cne 'execution-tooling') {
        throw "Explicit performance execution tooling was not classified: $member"
    }
    Pass ('explicit-performance-tooling-' + $member)
}
foreach ($member in @('.github/actions/unrelated/action.yml',
        '.github/actions/cachedb-redis-performance/other.yml',
        '.github/actions/cachedb-redis-performance/action.yaml',
        '.github/actions/cachedb-redis-performance/nested/action.yml',
        '.github/actions/CacheDB-redis-performance/action.yml',
        'tools/ci/unrelated.sh','tools/release/run-ematech-redis-performance.sh')) {
    Reject ('non-allowlisted-performance-tooling-' + $member) { Get-K8sOverlayClassification $member }
}
foreach ($definition in Import-K8sGuardFunctions (Join-Path $PSScriptRoot 'stage-k8s-acceptance.ps1')) { . ([scriptblock]::Create($definition)) }
$finalRoot = Join-Path $assemblyRoot 'final-source'
$null = [IO.Directory]::CreateDirectory($finalRoot)
[IO.File]::WriteAllText((Join-Path $finalRoot 'synthetic-source.txt'), $sourceContent, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $finalRoot 'README.md'), 'SYNTHETIC FINAL DOCUMENTATION', [Text.UTF8Encoding]::new($false))
$finalSha = '9' * 40
function Assert-PrivateOwnedCheckout([string]$Root, [string]$Sha) {
    if ($Root -cne $finalRoot -or $Sha -cne $finalSha) { throw 'Unexpected synthetic final checkout.' }
}
function Invoke-PrivateOwnedGit([string]$Root, [string[]]$Arguments) {
    if ($Root -cne $finalRoot -or ($Arguments -join ',') -cne 'ls-files') { throw 'Unexpected synthetic Git inspection.' }
    return "synthetic-source.txt`nREADME.md"
}
$distributionRoot = Join-Path $assemblyRoot 'snapshot-distribution'
$qualificationRoot = Join-Path $distributionRoot 'qualification'
$null = [IO.Directory]::CreateDirectory($qualificationRoot)
foreach ($file in Get-ChildItem -LiteralPath $snapshotRoot -Recurse -File) {
    $member = [IO.Path]::GetRelativePath($snapshotRoot, $file.FullName).Replace('\','/')
    Copy-K8sAssemblyFile $file.FullName (Resolve-K8sMember $distributionRoot ('runtime/' + $member)) $file.Length `
        (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()
}
$targetLines = @('README.md','synthetic-source.txt' | ForEach-Object {
    (Get-FileHash -LiteralPath (Join-Path $finalRoot $_)).Hash.ToLowerInvariant() + '  ' + $_
})
$targetPath = Join-Path $qualificationRoot 'target.sha256'
[IO.File]::WriteAllLines($targetPath, [string[]]$targetLines, [Text.UTF8Encoding]::new($false))
$targetPin = (Get-FileHash -LiteralPath $targetPath).Hash.ToLowerInvariant()
$bridge = @{schema='cachedb.k8s-source-equivalence/v2'; kind='DOCUMENTATION_TOOLING_ONLY_NOT_RUNTIME_ACCEPTANCE'
    sourceIdentity=(Clone $snapshotInput.source.identity); originalReceiptSha256=$snapshotResult.receiptSha256
    targetSha=$finalSha; sdkManifestSha256=$assemblyPin; sourceManifestSha256=$assemblySourcePin
    targetManifest=@{path='target.sha256'; sha256=$targetPin}; toolingEvidence=@()
    changes=@(@{path='README.md'; classification='documentation'; beforeSha256=$null
        afterSha256=(Get-FileHash -LiteralPath (Join-Path $finalRoot 'README.md')).Hash.ToLowerInvariant()})
    files=@(@{path='target.sha256'; sha256=$targetPin; bytes=(Get-Item -LiteralPath $targetPath).Length})}
$bridgePath = Join-Path $qualificationRoot 'source-equivalence.json'
[IO.File]::WriteAllText($bridgePath, ($bridge | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
$finalAuthority = Clone $authority; $finalAuthority.sha = $finalSha; $finalAuthority.sdkPin = $assemblyPin
$distribution = @{schema='cachedb.k8s-distribution/v1'; kind='K8S_EVIDENCE_DISTRIBUTION_NOT_RUNTIME_ACCEPTANCE'
    releaseSource=@{sha=$finalSha; repository='synthetic/public'; repositoryId='10'}
    producer=@{repositoryId='20'; runId='30'; runAttempt='1'; workflowPath=$authority.workflowPath; headSha=$finalSha}
    runtime=@{receipt='runtime/receipt.json'; receiptSha256=$snapshotResult.receiptSha256; inputArchiveSha256=('7'*64); sdkManifestSha256=$assemblyPin}
    sourceEquivalence=@{path='qualification/source-equivalence.json'; sha256=(Get-FileHash -LiteralPath $bridgePath).Hash.ToLowerInvariant()}
    files=@(Get-ChildItem -LiteralPath $distributionRoot -Recurse -File | ForEach-Object {
        @{path=[IO.Path]::GetRelativePath($distributionRoot,$_.FullName).Replace('\','/'); bytes=$_.Length
            sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })}
$acceptedSnapshot = Resolve-K8sAcceptance $distribution $distributionRoot $finalRoot $finalAuthority $pinnedPolicy
if ($acceptedSnapshot.receipt.source.ContainsKey('sha') -or
    (Get-K8sExecution $acceptedSnapshot.receipt 'sdk-build').ContainsKey('sourceSha') -or
    (Get-FileHash -LiteralPath (Join-Path $distributionRoot 'runtime/receipt.json')).Hash.ToLowerInvariant() -cne $snapshotResult.receiptSha256 -or
    $acceptedSnapshot.sourceQualification.originalReceiptSha256 -cne $snapshotResult.receiptSha256) { throw 'Snapshot bridge relabelled original evidence.' }
Pass 'snapshot-to-final-commit-resolver-integration-with-unchanged-original-receipt-and-sdk'
Reject 'snapshot-cannot-pass-as-direct-final-git-receipt' { Resolve-K8sAcceptance $snapshotReceipt $snapshotRoot $finalRoot $finalAuthority $pinnedPolicy }
$bad = Clone $distribution; $bad.sourceEquivalence = $null
Reject 'snapshot-distribution-requires-explicit-qualification' { Resolve-K8sAcceptance $bad $distributionRoot $finalRoot $finalAuthority $pinnedPolicy }
$originalCaseScript = Join-Path $PSScriptRoot 'test-k8s-original-case-provenance.ps1'
$originalCaseRoot = Join-Path $EvidenceDirectory 'original-case-child'
if (Test-Path -LiteralPath $originalCaseRoot) { throw 'Fresh original-case child output required.' }
$null = [IO.Directory]::CreateDirectory($originalCaseRoot)
$caseMilliseconds = [int][Math]::Min(120000, 120000 - $controlBudgetWatch.ElapsedMilliseconds - 6000)
if ($caseMilliseconds -le 0) { throw 'Original control budget exhausted before original-case fixtures.' }
$caseInfo = [Diagnostics.ProcessStartInfo]::new()
$caseInfo.FileName = [Environment]::ProcessPath
$caseInfo.UseShellExecute = $false
$caseInfo.RedirectStandardOutput = $true
$caseInfo.RedirectStandardError = $true
$caseInfo.CreateNoWindow = $true
$caseInfo.WorkingDirectory = $RepositoryRoot
$caseArguments = @('-NoLogo','-NoProfile','-NonInteractive','-File',$originalCaseScript)
foreach ($argument in $caseArguments) { $caseInfo.ArgumentList.Add($argument) }
$caseChild = [Diagnostics.Process]::new()
$caseChild.StartInfo = $caseInfo
$caseStdoutPath = Join-Path $originalCaseRoot 'stdout.log'
$caseStdout = [IO.File]::Open($caseStdoutPath, [IO.FileMode]::CreateNew)
$caseStderr = [IO.File]::Open((Join-Path $originalCaseRoot 'stderr.log'), [IO.FileMode]::CreateNew)
$caseStartedAt = [DateTimeOffset]::UtcNow.ToString('o')
$caseTimedOut = $false
try {
    if (!$caseChild.Start()) { throw 'Original-case fixture child did not start.' }
    $caseStdoutCopy = $caseChild.StandardOutput.BaseStream.CopyToAsync($caseStdout)
    $caseStderrCopy = $caseChild.StandardError.BaseStream.CopyToAsync($caseStderr)
    if (!$caseChild.WaitForExit($caseMilliseconds)) {
        $caseTimedOut = $true
        $caseChild.Kill($true)
        if (!$caseChild.WaitForExit(5000)) { throw 'Original-case child did not stop within the original control budget.' }
    }
    $caseStdoutCopy.GetAwaiter().GetResult()
    $caseStderrCopy.GetAwaiter().GetResult()
    $caseExit = $caseChild.ExitCode
} finally {
    $caseStdout.Dispose()
    $caseStderr.Dispose()
    $caseChild.Dispose()
}
[IO.File]::WriteAllText((Join-Path $originalCaseRoot 'process.exit'), ([string]$caseExit + "`n"))
[ordered]@{schema='cachedb.k8s-original-case-child/v1'; command=$caseInfo.FileName; argv=$caseArguments
    startedAt=$caseStartedAt; finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    exitCode=$caseExit; timedOut=$caseTimedOut; budgetMilliseconds=$caseMilliseconds
    scriptSha256=(Get-FileHash -LiteralPath $originalCaseScript).Hash.ToLowerInvariant()
    stdout='stdout.log'; stderr='stderr.log'; exitFile='process.exit'} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $originalCaseRoot 'result.json') -Encoding utf8
if ($caseTimedOut -or $caseExit -ne 0 -or [IO.File]::ReadAllText($caseStdoutPath) -notmatch
        '(?m)^PASS: [1-9][0-9]* original-case provenance fixtures \(Kubernetes only\)\.\r?$') {
    throw 'Dedicated original-case provenance fixtures did not complete successfully.'
}
if ($controlBudgetWatch.ElapsedMilliseconds -ge 120000) { throw 'Original control budget exceeded.' }
Pass 'dedicated-original-case-provenance-fixtures-executed-once-as-successful-child'
$exportChildScript = Join-Path $PSScriptRoot 'test-k8s-export-staging.ps1'
$exportChildRoot = Join-Path $EvidenceDirectory 'export-staging-child'
if (Test-Path -LiteralPath $exportChildRoot) { throw 'Fresh export child output required.' }
$null = [IO.Directory]::CreateDirectory($exportChildRoot)
$exportChildEvidence = Join-Path $exportChildRoot 'evidence'
$exportChildSummary = Join-Path $exportChildEvidence 'summary.json'
& (Join-Path $PSScriptRoot 'test-k8s-prefix-original-custody.ps1')
Pass 'prefix-custody-admission-fixtures-executed-without-product-qualification'
# Preserve the existing 120-second outer control limit, including child cleanup.
$childMilliseconds = [int][Math]::Min(120000, 120000 - $controlBudgetWatch.ElapsedMilliseconds - 6000)
if ($childMilliseconds -le 0) { throw 'Original control budget exhausted before export child.' }
$childInfo = [Diagnostics.ProcessStartInfo]::new()
$childInfo.FileName = [Environment]::ProcessPath
$childInfo.UseShellExecute = $false
$childInfo.RedirectStandardOutput = $true
$childInfo.RedirectStandardError = $true
$childInfo.CreateNoWindow = $true
$childInfo.WorkingDirectory = $RepositoryRoot
$childArguments = @('-NoLogo','-NoProfile','-NonInteractive','-File',$exportChildScript,'-EvidenceDirectory',$exportChildEvidence)
foreach ($argument in $childArguments) { $childInfo.ArgumentList.Add($argument) }
$child = [Diagnostics.Process]::new()
$child.StartInfo = $childInfo
$childStdout = [IO.File]::Open((Join-Path $exportChildRoot 'stdout.log'), [IO.FileMode]::CreateNew)
$childStderr = [IO.File]::Open((Join-Path $exportChildRoot 'stderr.log'), [IO.FileMode]::CreateNew)
$childStartedAt = [DateTimeOffset]::UtcNow.ToString('o')
$childTimedOut = $false
try {
    if (!$child.Start()) { throw 'Export child did not start.' }
    $stdoutCopy = $child.StandardOutput.BaseStream.CopyToAsync($childStdout)
    $stderrCopy = $child.StandardError.BaseStream.CopyToAsync($childStderr)
    if (!$child.WaitForExit($childMilliseconds)) {
        $childTimedOut = $true
        $child.Kill($true)
        if (!$child.WaitForExit(5000)) { throw 'Export child did not stop within the original control budget.' }
    }
    $stdoutCopy.GetAwaiter().GetResult()
    $stderrCopy.GetAwaiter().GetResult()
    $childExit = $child.ExitCode
} finally {
    $childStdout.Dispose()
    $childStderr.Dispose()
    $child.Dispose()
}
[IO.File]::WriteAllText((Join-Path $exportChildRoot 'process.exit'), ([string]$childExit + "`n"))
[ordered]@{schema='cachedb.k8s-export-child/v1'; command=$childInfo.FileName; argv=$childArguments
    workingDirectory=$RepositoryRoot; startedAt=$childStartedAt; finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    exitCode=$childExit; timedOut=$childTimedOut; budgetMilliseconds=$childMilliseconds
    scriptSha256=(Get-FileHash -LiteralPath $exportChildScript).Hash.ToLowerInvariant()
    stdout='stdout.log'; stderr='stderr.log'; exitFile='process.exit'; summary='evidence/summary.json'} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $exportChildRoot 'result.json') -Encoding utf8
$exportChildResult = Assert-K8sExportChildResult $childExit $childTimedOut $exportChildSummary
if ($controlBudgetWatch.ElapsedMilliseconds -ge 120000) { throw 'Original control budget exceeded.' }
Pass 'existing-export-staging-suite-executed-once-as-successful-child'
[ordered]@{result='PASS'; syntheticOnly=$true; actualKubernetesAcceptance=$false; controls=$controls
    verifierSha256=(Get-FileHash -LiteralPath $checker).Hash.ToLowerInvariant()
    assemblerSha256=(Get-FileHash -LiteralPath $assembler).Hash.ToLowerInvariant()
    invocationQualifierSha256=(Get-FileHash -LiteralPath $qualifier).Hash.ToLowerInvariant()
    sourceEquivalenceSha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'k8s-source-equivalence.ps1')).Hash.ToLowerInvariant()
    originalCaseChild=@{result='PASS'; resultFile='original-case-child/result.json'}
    exportChild=@{result='PASS'; controls=$exportChildResult.controls.Count; summary='export-staging-child/evidence/summary.json'
        summarySha256=(Get-FileHash -LiteralPath $exportChildSummary).Hash.ToLowerInvariant(); resultFile='export-staging-child/result.json'}} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Host "K8S_ACCEPTANCE_SYNTHETIC_CONTROLS_PASS=$($controls.Count); NO_RUNTIME_ACCEPTANCE"
