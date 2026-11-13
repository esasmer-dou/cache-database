#requires -Version 7.3
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($env:KUBERNETES_SERVICE_HOST)) {
    throw 'These fixtures must be executed in Kubernetes, not on the local workstation.'
}
. (Join-Path $PSScriptRoot 'k8s-original-case-provenance.ps1')
$gate = Join-Path $PSScriptRoot 'check-full-environment-tests.ps1'
$script:fixtureRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('cachedb-original-case-fixtures-' + [guid]::NewGuid().ToString('N'))))
$null = New-Item -ItemType Directory -Path $script:fixtureRoot
$script:testCount = 0

function Assert-Fixture {
    param([bool]$Condition, [string]$Message)
    if (!$Condition) { throw $Message }
}

function Test-Fixture {
    param([string]$Name, [scriptblock]$Body)
    & $Body
    $script:testCount++
    Write-Host "PASS fixture: $Name"
}

function Assert-RejectedFixture {
    param([scriptblock]$Body, [string]$Pattern)
    try { $null = & $Body }
    catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw "Unexpected rejection (expected $Pattern): $($_.Exception.Message)" }
        return
    }
    throw "Unsafe fixture was accepted (expected $Pattern)."
}

function New-Fixture {
    $root = Join-Path $script:fixtureRoot ([guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $root
    return [pscustomobject]@{ root=$root; reports=[Collections.Generic.List[object]]::new(); closures=[Collections.Generic.List[object]]::new();
        ids=@('physical-a','physical-b'); required=@('ExampleSuite'); methods=@{ ExampleSuite=@('alpha','beta') }; obligation='standalone' }
}

function Add-FixtureReport {
    param($Fixture, [string]$Path, [string]$ExecutionId, [object[]]$Cases, [string]$Suite = 'demo.ExampleSuite')
    $builder = [Text.StringBuilder]::new()
    $failures = @($Cases | Where-Object { $_.outcome -ceq 'failure' }).Count
    $errors = @($Cases | Where-Object { $_.outcome -ceq 'error' }).Count
    $skipped = @($Cases | Where-Object { $_.outcome -ceq 'skipped' }).Count
    $null = $builder.Append('<testsuite name="' + [Security.SecurityElement]::Escape($Suite) + '" tests="' + $Cases.Count +
        '" failures="' + $failures + '" errors="' + $errors + '" skipped="' + $skipped + '">')
    foreach ($case in $Cases) {
        $null = $builder.Append('<testcase classname="' + [Security.SecurityElement]::Escape($Suite) + '" name="' + [Security.SecurityElement]::Escape($case.name) + '">')
        if ($case.outcome -cne 'passed') { $null = $builder.Append('<' + $case.outcome + ' />') }
        $null = $builder.Append('</testcase>')
    }
    $null = $builder.Append('</testsuite>')
    $full = Join-Path $Fixture.root $Path
    $null = New-Item -ItemType Directory -Path (Split-Path -Parent $full) -Force
    [IO.File]::WriteAllText($full,$builder.ToString(),[Text.UTF8Encoding]::new($false))
    $Fixture.reports.Add([pscustomobject]@{ executionId=$ExecutionId; path=$Path; sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant() })
}

function Set-FixtureXml {
    param($Fixture, [int]$Report, [string]$Xml)
    $full = Join-Path $Fixture.root $Fixture.reports[$Report].path
    [IO.File]::WriteAllText($full,$Xml,[Text.UTF8Encoding]::new($false))
    $Fixture.reports[$Report].sha256 = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-FixtureXml {
    param($Fixture, [int]$Report = 0)
    return [IO.File]::ReadAllText((Join-Path $Fixture.root $Fixture.reports[$Report].path))
}

function New-ClosureEndpoint {
    param([string]$Path, [string]$Name = 'beta', [int]$Occurrence = 1, [string]$Class = 'demo.ExampleSuite')
    return [pscustomobject]@{ reportPath=$Path; className=$Class; name=$Name; occurrence=$Occurrence }
}

function New-BaseFixture {
    $fixture = New-Fixture
    Add-FixtureReport $fixture 'a/module/target/surefire-reports/TEST-demo.ExampleSuite.xml' physical-a @(
        @{ name='alpha'; outcome='passed' }, @{ name='beta'; outcome='error' })
    Add-FixtureReport $fixture 'b/module/target/surefire-reports/TEST-demo.ExampleSuite.xml' physical-b @(@{ name='beta'; outcome='passed' })
    $fixture.closures.Add([pscustomobject]@{
        from=(New-ClosureEndpoint $fixture.reports[0].path); to=(New-ClosureEndpoint $fixture.reports[1].path)
    })
    return $fixture
}

function Write-FixtureIndex {
    param($Fixture, [string]$RawJson = '')
    $path = Join-Path $Fixture.root 'index.json'
    if (!$RawJson) {
        $RawJson = [ordered]@{ schemaVersion='cachedb.k8s-original-case-provenance/v1'; obligationId=$Fixture.obligation;
            reports=$Fixture.reports.ToArray(); closures=$Fixture.closures.ToArray() } | ConvertTo-Json -Depth 12 -Compress
    }
    [IO.File]::WriteAllText($path,$RawJson,[Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{ path=$path; hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
}

function Invoke-Fixture {
    param($Fixture, [string]$RawJson = '')
    $index = Write-FixtureIndex $Fixture $RawJson
    return Assert-K8sOriginalCaseProvenance -IndexPath $index.path -IndexSha256 $index.hash -EvidenceRoot $Fixture.root `
        -ExpectedObligationId $Fixture.obligation -ExecutionIds $Fixture.ids -RequiredSuites $Fixture.required -RequiredMethods $Fixture.methods
}

try {
    Test-Fixture 'split originals retain error and exact replacement, without changing bytes' {
        $fixture = New-BaseFixture
        $before = Get-FixtureXml $fixture
        $result = Invoke-Fixture $fixture
        Assert-Fixture ($result.qualificationCompleted -and $result.allSelectedMethodsPassed -and $result.semanticCoverageQualified) 'Coverage did not qualify.'
        Assert-Fixture ($result.cases.Count -eq 3 -and $result.coveredCases.Count -eq 2 -and $result.closures.Count -eq 1) 'Original records were collapsed or coverage was inflated.'
        Assert-Fixture ($result.cases[1].outcome -ceq 'error' -and (Get-FixtureXml $fixture) -ceq $before) 'Original error was rewritten.'
        Assert-Fixture ($result.records[0].module -ceq 'module' -and $result.closedFailures.Count -eq 1 -and $result.closedSkips.Count -eq 0) 'Public record/closure interface is incorrect.'
        Assert-Fixture (!$result.PSObject.Properties['nativeExit']) 'A product process exit was synthesized.'
    }
    Test-Fixture 'provider obligation and fully qualified contracts' {
        $fixture = New-BaseFixture
        $fixture.obligation = 'provider/postgresql'
        $fixture.required = @('demo.ExampleSuite')
        $fixture.methods = @{ 'demo.ExampleSuite'=@('alpha','beta') }
        $null = Invoke-Fixture $fixture
    }
    Test-Fixture 'four-argument public interface does not assert required contracts' {
        $fixture = New-BaseFixture
        $index = Write-FixtureIndex $fixture
        $result = Assert-K8sOriginalCaseProvenance $index.path $index.hash $fixture.root $fixture.ids
        Assert-Fixture (!$result.requiredContractsChecked -and $result.records.Count -eq 2 -and $result.closedFailures.Count -eq 1) 'Four-argument interface asserted unchecked contracts.'
    }
    foreach ($outcome in @('failure','error','skipped')) {
        Test-Fixture "explicit $outcome closure" {
            $fixture = New-BaseFixture
            $counter = switch ($outcome) { failure { 'failures' }; error { 'errors' }; skipped { 'skipped' } }
            $xml = (Get-FixtureXml $fixture).Replace('errors="1"','errors="0"').Replace('<error />',"<$outcome />")
            Set-FixtureXml $fixture 0 ($xml.Replace($counter + '="0"',$counter + '="1"'))
            $null = Invoke-Fixture $fixture
        }
    }
    Test-Fixture 'repeated bare names preserve each occurrence' {
        $fixture = New-Fixture
        $fixture.methods = @{ ExampleSuite=@('repeat') }
        Add-FixtureReport $fixture 'a/TEST-demo.ExampleSuite.xml' physical-a @(@{ name='repeat'; outcome='passed' },@{ name='repeat'; outcome='failure' })
        Add-FixtureReport $fixture 'b/TEST-demo.ExampleSuite.xml' physical-b @(@{ name='repeat'; outcome='passed' },@{ name='repeat'; outcome='passed' })
        $fixture.closures.Add([pscustomobject]@{ from=(New-ClosureEndpoint $fixture.reports[0].path repeat 2); to=(New-ClosureEndpoint $fixture.reports[1].path repeat 2) })
        $result = Invoke-Fixture $fixture
        Assert-Fixture ($result.cases.Count -eq 4 -and $result.coveredCases.Count -eq 2 -and $result.closures[0].to.occurrence -eq 2) 'Repeated cases lost their ordinal identity.'
        $fixture.closures[0].to.occurrence = 1
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'identical passing occurrence'
    }
    Test-Fixture 'parameterized names are exact, not bare-method aliases' {
        $fixture = New-Fixture
        $fixture.methods = @{ ExampleSuite=@('parameterized[1]','parameterized[2]') }
        Add-FixtureReport $fixture 'a/TEST-demo.ExampleSuite.xml' physical-a @(@{ name='parameterized[1]'; outcome='passed' },@{ name='parameterized[2]'; outcome='passed' })
        $result = Invoke-Fixture $fixture
        Assert-Fixture ($result.coveredCases.Count -eq 2) 'Parameterized invocations were collapsed.'
        $fixture.methods = @{ ExampleSuite=@('parameterized') }
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'Required original method absent'
    }
    $mutations = @(
        @{ name='unclosed error'; pattern='Unclosed original'; body={ param($f) $f.closures.Clear() } },
        @{ name='undeclared physical ID'; pattern='not declared'; body={ param($f) $f.reports[1].executionId='foreign' } },
        @{ name='case-sensitive physical ID'; pattern='not declared'; body={ param($f) $f.reports[1].executionId='PHYSICAL-B' } },
        @{ name='duplicate physical ID'; pattern='duplicate declared'; body={ param($f) $f.ids=@('physical-a','physical-a','physical-b') } },
        @{ name='duplicate closure'; pattern='uniquely replace'; body={ param($f) $f.closures.Add($f.closures[0]) } },
        @{ name='passing original cannot be superseded'; pattern='uniquely replace'; body={ param($f) $f.closures[0].from.name='alpha' } },
        @{ name='missing endpoint'; pattern='not an indexed original'; body={ param($f) $f.closures[0].to.reportPath='absent/TEST-none.xml' } },
        @{ name='case-sensitive endpoint'; pattern='not an indexed original'; body={ param($f) $f.closures[0].to.name='Beta' } },
        @{ name='zero occurrence'; pattern='one-based integer'; body={ param($f) $f.closures[0].to.occurrence=0 } },
        @{ name='same physical execution'; pattern='another execution'; body={ param($f) $f.reports[1].executionId='physical-a' } },
        @{ name='duplicate report'; pattern='duplicate report path'; body={ param($f) $f.reports.Add($f.reports[0]) } },
        @{ name='path traversal'; pattern='Unsafe relative'; body={ param($f) $f.reports[0].path='../TEST-demo.ExampleSuite.xml' } },
        @{ name='absolute path'; pattern='Unsafe relative'; body={ param($f) $f.reports[0].path='/tmp/TEST-demo.ExampleSuite.xml' } },
        @{ name='backslash path'; pattern='Unsafe relative'; body={ param($f) $f.reports[0].path='a\TEST-demo.ExampleSuite.xml' } },
        @{ name='alternate stream'; pattern='Unsafe relative'; body={ param($f) $f.reports[0].path='a/TEST-demo.ExampleSuite.xml:stream' } },
        @{ name='hash mismatch'; pattern='Report SHA-256 mismatch'; body={ param($f) $f.reports[0].sha256=('0' * 64) } },
        @{ name='unknown report field'; pattern='unexpected property'; body={ param($f) $f.reports[0] | Add-Member -NotePropertyName ignored -NotePropertyValue $true } },
        @{ name='missing contract'; pattern='Required original method absent'; body={ param($f) $f.methods.ExampleSuite += 'missing' } },
        @{ name='duplicate contract'; pattern='duplicate method contract'; body={ param($f) $f.methods.ExampleSuite += 'alpha' } },
        @{ name='duplicate suite alias'; pattern='Duplicate required suite binding'; body={ param($f) $f.required=@('ExampleSuite','demo.ExampleSuite') } }
    )
    foreach ($mutation in $mutations) {
        Test-Fixture $mutation.name {
            $fixture = New-BaseFixture
            & $mutation.body $fixture
            Assert-RejectedFixture { Invoke-Fixture $fixture } $mutation.pattern
        }
    }
    Test-Fixture 'unexpected failures are not filtered out by required suites' {
        $fixture = New-BaseFixture
        Add-FixtureReport $fixture 'a/TEST-demo.Unexpected.xml' physical-a @(@{ name='unrelated'; outcome='failure' }) demo.Unexpected
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'Unclosed original'
    }
    Test-Fixture 'unexpected skips also require explicit closure' {
        $fixture = New-BaseFixture
        Add-FixtureReport $fixture 'a/TEST-demo.Unexpected.xml' physical-a @(@{ name='unrelated'; outcome='skipped' }) demo.Unexpected
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'Unclosed original'
    }
    Test-Fixture 'ambiguous short suite is rejected' {
        $fixture = New-BaseFixture
        Add-FixtureReport $fixture 'a/TEST-other.ExampleSuite.xml' physical-a @(@{ name='alpha'; outcome='passed' }) other.ExampleSuite
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'Required suite missing, ambiguous'
    }
    Test-Fixture 'non-passing replacement cannot close a failure' {
        $fixture = New-BaseFixture
        Set-FixtureXml $fixture 1 ((Get-FixtureXml $fixture 1).Replace('skipped="0"','skipped="1"').Replace('</testcase>','<skipped /></testcase>'))
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'identical passing occurrence'
    }
    Test-Fixture 'index SHA and expected obligation are enforced' {
        $fixture = New-BaseFixture
        $index = Write-FixtureIndex $fixture
        $arguments = @{ IndexPath=$index.path; IndexSha256=('0' * 64); EvidenceRoot=$fixture.root; ExpectedObligationId='standalone';
            PhysicalExecutionIds=$fixture.ids; RequiredSuites=$fixture.required; RequiredMethods=$fixture.methods }
        Assert-RejectedFixture { Assert-K8sOriginalCaseProvenance @arguments } 'Index SHA-256 mismatch'
        $arguments.IndexSha256=$index.hash
        $arguments.ExpectedObligationId='provider/oracle'
        Assert-RejectedFixture { Assert-K8sOriginalCaseProvenance @arguments } 'obligation mismatch'
    }
    Test-Fixture 'duplicate JSON and unknown root field are rejected' {
        $fixture = New-BaseFixture
        $index = Write-FixtureIndex $fixture
        $json = [IO.File]::ReadAllText($index.path)
        Assert-RejectedFixture { Invoke-Fixture $fixture ($json.Replace('"schemaVersion":','"schemaVersion":"ignored","schemaVersion":')) } 'Duplicate or unexpected property'
        Assert-RejectedFixture { Invoke-Fixture $fixture ($json.Replace('"schemaVersion":','"ignored":true,"schemaVersion":')) } 'Duplicate or unexpected property'
    }
    $xmlMutations = @(
        @{ name='missing counter'; pattern='Invalid JUnit counter'; body={ param($x) $x.Replace(' errors="1"','') } },
        @{ name='noncanonical counter'; pattern='Invalid JUnit counter'; body={ param($x) $x.Replace('tests="2"','tests="02"') } },
        @{ name='counter disagreement'; pattern='counters disagree'; body={ param($x) $x.Replace('tests="2"','tests="3"') } },
        @{ name='flaky node'; pattern='flaky/rerun'; body={ param($x) $x.Replace('</testcase>','<flakyFailure /></testcase>') } },
        @{ name='rerun attribute'; pattern='Flaky/rerun'; body={ param($x) $x.Replace('<testsuite ','<testsuite rerun="1" ') } },
        @{ name='nested result'; pattern='misplaced JUnit'; body={ param($x) $x.Replace('<error />','<system-out><error /></system-out>') } },
        @{ name='multiple outcomes'; pattern='Multiple testcase outcomes'; body={ param($x) $x.Replace('failures="0"','failures="1"').Replace('<error />','<failure /><error />') } },
        @{ name='classname mismatch'; pattern='class/name mismatch'; body={ param($x) $x.Replace('classname="demo.ExampleSuite"','classname="other.ExampleSuite"') } },
        @{ name='namespace'; pattern='Unsupported JUnit root'; body={ param($x) $x.Replace('<testsuite ','<testsuite xmlns="urn:unexpected" ') } },
        @{ name='unknown outcome spelling'; pattern='Unexpected JUnit child'; body={ param($x) $x.Replace('<error />','<Error />') } },
        @{ name='DTD'; pattern='DTD'; body={ param($x) '<!DOCTYPE testsuite [<!ENTITY external SYSTEM "file:///not-readable">]>' + $x } },
        @{ name='truncated XML'; pattern='Unexpected end|unclosed|not complete'; body={ param($x) $x.Substring(0,$x.Length - 8) } }
    )
    foreach ($mutation in $xmlMutations) {
        Test-Fixture $mutation.name {
            $fixture = New-BaseFixture
            Set-FixtureXml $fixture 0 (& $mutation.body (Get-FixtureXml $fixture))
            Assert-RejectedFixture { Invoke-Fixture $fixture } $mutation.pattern
        }
    }
    Test-Fixture 'linked reports and linked evidence roots are forbidden' {
        $fixture = New-BaseFixture
        $original = Join-Path $fixture.root $fixture.reports[0].path
        $target = Join-Path $fixture.root 'target-original.xml'
        [IO.File]::Move($original,$target)
        $null = [IO.File]::CreateSymbolicLink($original,$target)
        Assert-RejectedFixture { Invoke-Fixture $fixture } 'Linked evidence path'
        [IO.File]::Delete($original)
        [IO.File]::Move($target,$original)
        $index = Write-FixtureIndex $fixture
        $link = Join-Path $script:fixtureRoot ('link-' + [guid]::NewGuid().ToString('N'))
        $null = [IO.Directory]::CreateSymbolicLink($link,$fixture.root)
        try {
            Assert-RejectedFixture { Assert-K8sOriginalCaseProvenance -IndexPath $index.path -IndexSha256 $index.hash -EvidenceRoot $link `
                -ExpectedObligationId standalone -ExecutionIds $fixture.ids -RequiredSuites $fixture.required -RequiredMethods $fixture.methods } 'Linked evidence path'
        } finally { [IO.Directory]::Delete($link) }
    }
    Test-Fixture 'file budget rejects oversized original before parsing' {
        $fixture = New-BaseFixture
        $stream = [IO.File]::OpenWrite((Join-Path $fixture.root $fixture.reports[0].path))
        try { $stream.SetLength(33MB) } finally { $stream.Dispose() }
        Assert-RejectedFixture { Invoke-Fixture $fixture } '32 MiB file'
    }

    # These are isolated validator fixtures, never campaign evidence or substitute original reports.
    Test-Fixture 'opt-in union passes while unchanged legacy rejects partial contracts' {
        $fixture = New-Fixture
        $contracts = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'required-environment-suites.psd1')
        $repeatClass = 'com.reactor.cachedb.spring.boot.cache.CacheDbCacheSubscriptionRedisTest'
        $repeatName = 'reconnectAndCloseNeverReturnPubsubFramesToTheSingleCommandConnection'
        $splitSuite = @($contracts.StandaloneMethods.Keys | Where-Object { @($contracts.StandaloneMethods[$_]).Count -gt 1 } | Sort-Object)[0]
        foreach ($suite in $contracts.Standalone) {
            $class = if ($suite -ceq 'CacheDbCacheSubscriptionRedisTest') { $repeatClass } else { "fixture.$suite" }
            $module = if ($suite -ceq 'CacheDbCacheSubscriptionRedisTest') { 'cachedb-spring-boot-starter' } else { 'cachedb-fixture-tests' }
            # Preserve array concatenation when the suite starts with a single synthetic case.
            [string[]]$methods = if ($contracts.StandaloneMethods.ContainsKey($suite)) { @($contracts.StandaloneMethods[$suite]) } else { @('fixtureCase') }
            if ($suite -ceq 'RedisNearCacheTest') {
                $methods = @($methods + @('refreshOverloadNeverBlocksCallersAndReleasesReservations','separateRuntimeSubscriptionsEvictPeerCopiesAndClearOnClose',
                    'refreshBudgetsAreIndependentAndShutdownCancelsQueuedWork') | Select-Object -Unique)
            }
            if ($suite -ceq 'CacheDbCacheSubscriptionRedisTest') { $methods = @(1..8 | ForEach-Object { $repeatName }) }
            $caseNodes = @($methods | ForEach-Object { @{ name=$_; outcome='passed' } })
            Add-FixtureReport $fixture "$module/target/surefire-reports/TEST-$class.xml" physical-a $caseNodes $class
        }
        foreach ($module in @('cachedb-integration-tests','cachedb-production-tests')) {
            Add-FixtureReport $fixture "$module/target/surefire-reports/TEST-fixture.Module.xml" physical-a @(@{ name='fixtureCase'; outcome='passed' }) fixture.Module
        }
        $summary = Join-Path $fixture.root 'summary.md'
        & $gate -ReportsRoot $fixture.root -SummaryPath $summary
        $splitRecord = @($fixture.reports | Where-Object { $_.path.EndsWith("TEST-fixture.$splitSuite.xml",[StringComparison]::Ordinal) })[0]
        $splitIndex = $fixture.reports.IndexOf($splitRecord)
        $allMethods = @($contracts.StandaloneMethods[$splitSuite])
        $first = New-Fixture
        Add-FixtureReport $first 'TEST-partial.xml' physical-a @(@{ name=$allMethods[0]; outcome='passed' }) "fixture.$splitSuite"
        Set-FixtureXml $fixture $splitIndex (Get-FixtureXml $first)
        Add-FixtureReport $fixture "b/cachedb-fixture-tests/target/surefire-reports/TEST-fixture.$splitSuite.xml" physical-b `
            @($allMethods[1..($allMethods.Count - 1)] | ForEach-Object { @{ name=$_; outcome='passed' } }) "fixture.$splitSuite"
        Assert-RejectedFixture { & $gate -ReportsRoot $fixture.root -SummaryPath $summary } 'Required live contract test absent'
        $index = Write-FixtureIndex $fixture
        & $gate -ReportsRoot $fixture.root -SummaryPath $summary -OriginalCaseIndexPath $index.path `
            -OriginalCaseIndexSha256 $index.hash -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids
        Assert-Fixture ([IO.File]::ReadAllText($summary).Contains('No aggregate product process or merged JUnit is asserted.')) 'Summary implied aggregate execution.'
        Assert-RejectedFixture { & $gate -ClusterOnly -SummaryPath $summary -OriginalCaseIndexPath $index.path `
            -OriginalCaseIndexSha256 $index.hash -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids } 'obligation mismatch'
        $repeatedRecord = @($fixture.reports | Where-Object { $_.path.EndsWith("TEST-$repeatClass.xml",[StringComparison]::Ordinal) })[0]
        $repeatedIndex = $fixture.reports.IndexOf($repeatedRecord)
        $half = New-Fixture
        Add-FixtureReport $half 'TEST-partial.xml' physical-a @(1..4 | ForEach-Object { @{ name=$repeatName; outcome='passed' } }) $repeatClass
        Set-FixtureXml $fixture $repeatedIndex (Get-FixtureXml $half)
        Add-FixtureReport $fixture "b/cachedb-spring-boot-starter/target/surefire-reports/TEST-$repeatClass.xml" physical-b `
            @(1..4 | ForEach-Object { @{ name=$repeatName; outcome='passed' } }) $repeatClass
        $index = Write-FixtureIndex $fixture
        Assert-RejectedFixture { & $gate -SummaryPath $summary -OriginalCaseIndexPath $index.path -OriginalCaseIndexSha256 $index.hash `
            -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids } 'eight original bare-name occurrences'
    }
    Test-Fixture 'cluster original cases enforce only the complete cluster suite and method contract' {
        $fixture = New-Fixture
        $fixture.obligation = 'cluster'
        $contracts = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'required-environment-suites.psd1')
        foreach ($suite in $contracts.Cluster) {
            [string[]]$methods = if ($contracts.ClusterMethods.ContainsKey($suite)) { @($contracts.ClusterMethods[$suite]) } else { @('fixtureCase') }
            Add-FixtureReport $fixture "cluster/cachedb-fixture-tests/target/surefire-reports/TEST-fixture.$suite.xml" physical-a `
                @($methods | ForEach-Object { @{name=$_; outcome='passed'} }) "fixture.$suite"
        }
        $summary = Join-Path $fixture.root 'cluster-summary.md'
        $index = Write-FixtureIndex $fixture
        & $gate -ClusterOnly -SummaryPath $summary -OriginalCaseIndexPath $index.path -OriginalCaseIndexSha256 $index.hash `
            -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids
        Assert-Fixture ([IO.File]::ReadAllText($summary).Contains("Required suites covered: $($contracts.Cluster.Count)")) 'Cluster suite union was not checked.'
        Assert-RejectedFixture { & $gate -SummaryPath $summary -OriginalCaseIndexPath $index.path -OriginalCaseIndexSha256 $index.hash `
            -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids } 'obligation mismatch'
        $omitted = $fixture.reports[0]
        $fixture.reports.RemoveAt(0)
        $index = Write-FixtureIndex $fixture
        Assert-RejectedFixture { & $gate -ClusterOnly -SummaryPath $summary -OriginalCaseIndexPath $index.path -OriginalCaseIndexSha256 $index.hash `
            -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids } 'Required suite missing'
        $fixture.reports.Insert(0,$omitted)
        foreach ($suite in $contracts.ClusterMethods.Keys) {
            $record = @($fixture.reports | Where-Object { $_.path.EndsWith("TEST-fixture.$suite.xml",[StringComparison]::Ordinal) })[0]
            $position = $fixture.reports.IndexOf($record)
            $originalXml = Get-FixtureXml $fixture $position
            $replacement = New-Fixture
            Add-FixtureReport $replacement 'TEST-fixture.xml' physical-a @(@{name='notTheRequiredLiveMethod'; outcome='passed'}) "fixture.$suite"
            Set-FixtureXml $fixture $position (Get-FixtureXml $replacement)
            $index = Write-FixtureIndex $fixture
            Assert-RejectedFixture { & $gate -ClusterOnly -SummaryPath $summary -OriginalCaseIndexPath $index.path -OriginalCaseIndexSha256 $index.hash `
                -EvidenceRoot $fixture.root -PhysicalExecutionIds $fixture.ids } 'Required original method absent'
            Set-FixtureXml $fixture $position $originalXml
        }
    }
    Write-Host "PASS: $script:testCount original-case provenance fixtures (Kubernetes only)."
} finally {
    if (Test-Path -LiteralPath $script:fixtureRoot) {
        $resolved = (Get-Item -LiteralPath $script:fixtureRoot -Force).FullName
        if ($resolved -cne $script:fixtureRoot -or (Split-Path -Leaf $resolved) -cnotmatch '^cachedb-original-case-fixtures-[0-9a-f]{32}$') {
            throw 'Refusing fixture cleanup outside the created temporary root.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
