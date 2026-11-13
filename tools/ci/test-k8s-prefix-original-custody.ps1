#requires -Version 7.3
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($env:KUBERNETES_SERVICE_HOST)) {
    throw 'These synthetic fixtures must execute in Kubernetes, not on the workstation.'
}
. (Join-Path $PSScriptRoot 'k8s-prefix-original-custody.ps1')
$script:fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-prefix-custody-fixtures-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $script:fixtureRoot
$script:fixtureCount = 0

# Isolated doubles for the parent checker, never product evidence. Only the historical
# verification signature is substituted: synthetic JSON cannot have that historical SHA.
# All other references, inventories and staged bytes use real SHA-256 and length checks.
function Resolve-K8sMember {
    param([string]$Root, [string]$Member)
    Assert-K8sPrefixPath $Member
    return [IO.Path]::Combine($Root,$Member)
}

function Read-K8sJson {
    param([string]$Path, [long]$MaximumBytes = 1MB)
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Length -le 0 -or $item.Length -gt $MaximumBytes) { throw 'Synthetic JSON byte limit.' }
    return [Text.UTF8Encoding]::new($false,$true).GetString([IO.File]::ReadAllBytes($Path)) | ConvertFrom-Json -AsHashtable -Depth 32
}

function Assert-K8sSealedReference {
    param($Reference, $Receipt, [string]$Root, [long]$MaximumBytes = 1MB)
    $entries = @($Receipt.files | Where-Object { $_.path -ceq $Reference.path })
    if ($entries.Count -ne 1) { throw 'Synthetic sealed reference missing or duplicated.' }
    $path = Resolve-K8sMember $Root $Reference.path
    $file = Get-Item -LiteralPath $path -Force
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($file.Length -gt $MaximumBytes -or $file.Length -ne $entries[0].bytes -or $hash -cne $entries[0].sha256) {
        throw 'Synthetic sealed bytes differ.'
    }
    $authorityDouble = $Reference.path -ceq 'authority/verification.json' -and
        $Reference.sha256 -ceq '765307e4742afcc42d887e63927306889760135a3fc43fa41b6d32fa63740019'
    if (!$authorityDouble -and $Reference.sha256 -cne $hash) { throw 'Synthetic sealed reference SHA differs.' }
    return $path
}

function Write-PrefixFixtureMember {
    param($Fixture, [string]$Path, [string]$Content)
    $full = Join-Path $Fixture.root $Path
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $full)
    [IO.File]::WriteAllText($full,$Content,[Text.UTF8Encoding]::new($false))
    $pin = @{path=$Path; bytes=(Get-Item -LiteralPath $full).Length; sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()}
    for ($i=$Fixture.receipt.files.Count - 1; $i -ge 0; $i--) {
        if ($Fixture.receipt.files[$i].path -ceq $Path) { $Fixture.receipt.files.RemoveAt($i) }
    }
    $Fixture.receipt.files.Add($pin)
    return $pin
}

function Write-PrefixFixtureJson {
    param($Fixture, [string]$Path, $Value)
    return Write-PrefixFixtureMember $Fixture $Path (ConvertTo-Json -InputObject $Value -Depth 24 -Compress)
}

function Seal-PrefixFixtureReaders {
    param($Fixture)
    $Fixture.policy.ReviewedPrefixOriginals = @{}
    foreach ($kind in @('process','report')) {
        $summary = Write-PrefixFixtureJson $Fixture "readers/$kind/summary.json" $Fixture[$kind]
        $inventory = $Fixture[$kind + 'Inventory'].ToArray() + @(@{path='custody/result.json'; bytes=$summary.bytes; sha256=$summary.sha256})
        $inventoryPin = Write-PrefixFixtureJson $Fixture "readers/$kind/inventory.json" $inventory
        $binding = @{summary=@{path=$summary.path; sha256=$summary.sha256}; inventory=@{path=$inventoryPin.path; sha256=$inventoryPin.sha256}}
        $Fixture.execution.originalCustody.prefixCustody[$kind + 'Reader'] = $binding
        $profile = if ($kind -ceq 'process') { 'BB_PROCESS' } elseif ($Fixture.execution.originalRecordId -ceq 'f3') { 'BB_F3_XML' } else { 'BB_BASE_XML' }
        $Fixture.policy.ReviewedPrefixOriginals[$summary.sha256] = @{profile=$profile; inventorySha256=$inventoryPin.sha256}
    }
}

function New-PrefixFixture {
    param([switch]$Base, [switch]$Processor)
    $root = Join-Path $script:fixtureRoot ([guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $root
    $uid = 'df21bb8a-a92f-4473-8f7a-f6742ceb7884'
    $source = '456ef8ad0a32a571ec52a345dbeb0a3e5f2dcaf742a7eb3d840f49c831a69a11'
    $sdk = '474527305300767b9d6ba35f24e5c5085505680653aea9137cf9524044d116a3'
    $prefixSha = 'ae97bc8c7d6845f0df295c0a06fa82b3504086afeb489d41ce085513711f14b1'
    $verificationSha = '765307e4742afcc42d887e63927306889760135a3fc43fa41b6d32fa63740019'
    $prefix = 'originals/bb'
    $f = @{root=$root; receipt=@{files=[Collections.Generic.List[object]]::new()}; processInventory=[Collections.Generic.List[object]]::new();
        reportInventory=[Collections.Generic.List[object]]::new(); index=@{reports=[Collections.Generic.List[object]]::new()};
        policy=@{ReviewedOriginalReceipts=@{$verificationSha=$uid}; ReviewedPrefixOriginals=@{}}}
    $records = [Collections.Generic.List[object]]::new()
    $processMembers = [Collections.Generic.List[object]]::new()
    $identities = @(
        @{id='f3'; uuid='e46f53fa-478c-4ae8-ab68-a6481068aab2'; native=0},
        @{id='standalone/cachedb-core'; uuid='961e4a72-1155-4bc8-bb7a-f4ee93e986f0'; native=0},
        @{id='standalone/cachedb-processor'; uuid='5602849a-bf09-484b-899e-ee601e3afc49'; native=1}
    )
    $recordId = if ($Processor) { 'standalone/cachedb-processor' } elseif ($Base) { 'standalone/cachedb-core' } else { 'f3' }
    foreach ($identity in $identities) {
        $record = @{id=$identity.id; physicalExecutionId=$identity.uuid; podUid=$uid; nativeExit=$identity.native; effectiveExit=$identity.native;
            sourceManifestSha256=$source; sdkManifestSha256=$sdk; processEvidenceSealed=$true}
        foreach ($kind in @('log','exit')) {
            $name = 'evidence/commands/' + $identity.id.Replace('/','-') + '/process.' + $kind
            $content = if ($kind -ceq 'log') { 'synthetic-log:' + $identity.id } else { [string]$identity.native + "`n" }
            $pin = Write-PrefixFixtureMember $f "$prefix/$name" $content
            $field = if ($kind -ceq 'log') { 'processLog' } else { 'processExit' }
            $record[$field] = @{path="/workspace/$name"; bytes=$pin.bytes; sha256=$pin.sha256}
            $custody = 'process-' + $processMembers.Count + '.stdout'
            $processMembers.Add(@{name=$name; basis='ORIGINAL_SELECTED_SCOPED_JSON'; status='ORIGINAL_INDIVIDUAL_BYTES_MATCH';
                file=$custody; bytes=$pin.bytes; sha256=$pin.sha256; expectedBytes=$pin.bytes; expectedSha256=$pin.sha256})
            $f.processInventory.Add(@{path="custody/$custody"; bytes=$pin.bytes; sha256=$pin.sha256})
        }
        $records.Add($record)
    }
    $f.raw = @{executions=$records.ToArray()}
    $raw = Write-PrefixFixtureJson $f "$prefix/evidence/scoped-result.json" $f.raw
    $f.verification = @{schema='cachedb.remote-archive-verification/v1'; podUid=$uid; archiveBytes=90091062;
        archiveSha256='912704d180155a83ea388644d14a883e3872c0a70eb2d23910511b79b39eec92';
        selected=@(@{name='evidence/scoped-result.json'; bytes=$raw.bytes; sha256=$raw.sha256})}
    $null = Write-PrefixFixtureJson $f 'authority/verification.json' $f.verification
    $selectedRecord = @($records | Where-Object { $_.id -ceq $recordId })[0]
    $f.execution = @{id='bb-original'; originalRecordId=$recordId; originalPhysicalExecutionId=$selectedRecord.physicalExecutionId;
        podUid=$uid; observedExit=$selectedRecord.nativeExit; originalRecord=@{path=$raw.path; sha256=$raw.sha256};
        log=($prefix + '/' + $selectedRecord.processLog.path.Substring(11)); exitFile=($prefix + '/' + $selectedRecord.processExit.path.Substring(11));
        originalCustody=@{receipt=@{path='authority/verification.json'; sha256=$verificationSha}; prefix=$prefix;
            prefixCustody=@{schemaVersion='cachedb.bb-prefix-original-custody/v1'; processReader=@{}; reportReader=@{}}}}
    $f.process = @{schema='cachedb.partial-custody-read/v1'; status='READ_COMPLETED_INDIVIDUAL_RESULTS_ONLY';
        testsRun=$false; archiveAccepted=$false; releaseAccepted=$false; originalProducerRecreated=$false; members=$processMembers.ToArray();
        originalExecutions=@($records | ForEach-Object { @{id=$_.id; physicalExecutionId=$_.physicalExecutionId; podUid=$_.podUid; nativeExit=$_.nativeExit; effectiveExit=$_.effectiveExit} });
        inputs=@{authenticatedPrefixBytes=20971520; authenticatedPrefixSha256=$prefixSha; verificationSha256=$verificationSha;
            originalArchiveBytes=$f.verification.archiveBytes; originalArchiveSha256=$f.verification.archiveSha256}}
    $f.report = @{schema='cachedb.bb-junit-semantic-qualification/v1'; status='SEMANTIC_REVIEW_COMPLETE_NOT_RELEASE_ACCEPTANCE';
        qualificationCompleted=$true; error=$null; sourceManifestSha256=$source; sdkManifestSha256=$sdk; originalPodUid=$uid;
        testsRun=$false; archiveAccepted=$false; releaseAccepted=$false; allSelectedMethodsPassed=$false}
    $xmlReports = [Collections.Generic.List[object]]::new()
    $regularMembers = [Collections.Generic.List[object]]::new()
    $modules = if ($recordId -ceq 'f3') { @('cachedb-spring-boot-starter','cachedb-starter','cachedb-storage-jdbc') } else { @('cachedb-core','cachedb-processor') }
    foreach ($module in $modules) {
        $owner = if ($recordId -ceq 'f3') { $identities[0] } elseif ($module -ceq 'cachedb-core') { $identities[1] } else { $identities[2] }
        $name = "evidence/standalone/$module/target/surefire-reports/TEST-fixture.$module.xml"
        $pin = Write-PrefixFixtureMember $f "$prefix/$name" "<testsuite name=`"fixture.$module`" tests=`"1`" failures=`"0`" errors=`"0`" skipped=`"1`"><testcase classname=`"fixture.$module`" name=`"synthetic`"><skipped /></testcase></testsuite>"
        $custody = 'xml-regular-' + $xmlReports.Count + '.stdout'
        $export = 'custody/xml-' + $xmlReports.Count + '.xml'
        $report = @{originalName=$name; module=$module; exportMember=$export; custodyMember="custody/$custody"; bytes=$pin.bytes; sha256=$pin.sha256;
            byteAuthentication='INHERITED_AUTHENTICATED_COMPRESSED_PREFIX_COMPLETE_REGULAR_MEMBER'; originalPhysicalExecutionId=$owner.uuid; originalNativeExit=$owner.native}
        $f.reportInventory.Add(@{path=$export; bytes=$pin.bytes; sha256=$pin.sha256})
        $regularPin = @{path="custody/$custody"; bytes=$pin.bytes; sha256=$pin.sha256}
        if ($recordId -ceq 'f3') {
            $report.recoveryNativeExit = 0
            $f.reportInventory.Add($regularPin)
            $regularMembers.Add(@{name=$name; file=$custody; basis='AUTHENTICATED_20M_PREFIX_GNU_REGULAR_HEADER_NO_INDIVIDUAL_ORIGINAL_HASH';
                status='PREFIX_REGULAR_CANDIDATE_UNQUALIFIED'; bytes=$pin.bytes; sha256=$pin.sha256})
        } else { $f.processInventory.Add($regularPin) }
        $xmlReports.Add($report)
        $f.index.reports.Add(@{executionId=$(if ($owner.id -ceq $recordId) { 'bb-original' } else { 'other-bb-original' }); path=$pin.path; sha256=$pin.sha256})
    }
    $f.index.reports = $f.index.reports.ToArray()
    $f.report.reports = $xmlReports.ToArray()
    $f.report.selectedSuiteCount = $xmlReports.Count
    if ($recordId -ceq 'f3') {
        $f.report.authenticatedPrefixBytes = 20971520
        $f.report.authenticatedPrefixSha256 = $prefixSha
        $f.report.f3 = @{physicalExecutionId=$identities[0].uuid; originalNativeExit=0}
        $f.report.members = $regularMembers.ToArray()
    } else { $f.report.opaqueCustodySha256 = '6aa969bf1ff70b4339271e326ff238ac5ef12d641fdcd06005a8709b1e69bae0' }
    Seal-PrefixFixtureReaders $f
    return $f
}

function Invoke-PrefixFixture {
    param($Fixture)
    Assert-K8sPrefixOriginalCustody $Fixture.execution $Fixture.index $Fixture.receipt $Fixture.root $Fixture.policy
}

function Test-PrefixFixture {
    param([string]$Name, [scriptblock]$Body)
    & $Body
    $script:fixtureCount++
    Write-Host "PASS synthetic custody fixture: $Name"
}

function Assert-PrefixRejected {
    param($Fixture, [string]$Pattern)
    try { Invoke-PrefixFixture $Fixture }
    catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw "Unexpected custody rejection (expected $Pattern): $($_.Exception.Message)" }
        return
    }
    throw "Unsafe synthetic custody fixture was accepted (expected $Pattern)."
}

try {
    Test-PrefixFixture 'F3 admitted custody is not semantic PASS, including original skips' {
        $f = New-PrefixFixture
        $output = @(Invoke-PrefixFixture $f)
        if ($output.Count -or $f.report.allSelectedMethodsPassed) { throw 'Custody synthesized test PASS.' }
    }
    Test-PrefixFixture 'base XML uses base inventory and retains processor native one' {
        Invoke-PrefixFixture (New-PrefixFixture -Base)
        $f = New-PrefixFixture -Processor
        Invoke-PrefixFixture $f
        if ($f.execution.observedExit -ne 1) { throw 'Failed producer exit was replaced.' }
    }
    $mutations = @(
        @{name='unreviewed original verification'; pattern='original authority'; body={param($f) $f.policy.ReviewedOriginalReceipts.Clear()}},
        @{name='unreviewed reader'; pattern='independent policy admission'; body={param($f) $f.policy.ReviewedPrefixOriginals.Clear()}},
        @{name='wrong reader role'; pattern='reader profile'; body={param($f) $f.policy.ReviewedPrefixOriginals[$f.execution.originalCustody.prefixCustody.reportReader.summary.sha256].profile='BB_BASE_XML'}},
        @{name='wrong admitted inventory'; pattern='admitted inventory'; body={param($f) $f.policy.ReviewedPrefixOriginals[$f.execution.originalCustody.prefixCustody.reportReader.summary.sha256].inventorySha256=('0'*64)}},
        @{name='unknown binding property'; pattern='Unexpected prefix custody field'; body={param($f) $f.execution.originalCustody.prefixCustody.invented=$true}},
        @{name='missing reader binding'; pattern='Missing prefix custody field'; body={param($f) $f.execution.originalCustody.prefixCustody.Remove('processReader')}},
        @{name='missing optional typed policy'; pattern='Missing prefix custody field'; body={param($f) $f.policy.Remove('ReviewedPrefixOriginals')}},
        @{name='renamed raw producer'; pattern='original selected member'; body={param($f) $f.execution.originalRecord.path='originals/bb/evidence/other.json'}},
        @{name='reader identity used as producer'; pattern='producer identity'; body={param($f) $f.execution.originalPhysicalExecutionId='28687044-a560-4806-accc-727f3a142267'}},
        @{name='fake successful native exit'; pattern='producer identity'; body={param($f) $f.execution.observedExit=1}},
        @{name='unmapped original command'; pattern='Unmapped BB producer'; body={param($f) $f.execution.originalRecordId='reader'}},
        @{name='unsafe original prefix'; pattern='Unsafe prefix'; body={param($f) $f.execution.originalCustody.prefix='../bb'}},
        @{name='renamed log'; pattern='process evidence was renamed'; body={param($f) $f.execution.log='originals/bb/evidence/commands/other/process.log'}},
        @{name='invented XML'; pattern='Unmapped or duplicate indexed'; body={param($f) $f.index.reports[0].path='originals/bb/evidence/standalone/cachedb-starter/target/surefire-reports/TEST-invented.xml'}},
        @{name='dropped original XML'; pattern='must be retained'; body={param($f) $f.index.reports=@($f.index.reports | Select-Object -Skip 1)}},
        @{name='wrong indexed hash'; pattern='ownership or SHA'; body={param($f) $f.index.reports[0].sha256=('0'*64)}},
        @{name='wrong XML process'; pattern='must be retained'; body={param($f) $f.index.reports[0].executionId='foreign'}},
        @{name='duplicate indexed XML'; pattern='duplicate indexed'; body={param($f) $f.index.reports += $f.index.reports[0]}}
    )
    foreach ($mutation in $mutations) {
        Test-PrefixFixture $mutation.name {
            $f = New-PrefixFixture
            & $mutation.body $f
            Assert-PrefixRejected $f $mutation.pattern
        }
    }
    $readerMutations = @(
        @{name='wrong frozen SDK'; pattern='qualifier identity'; body={param($f) $f.report.sdkManifestSha256=('0'*64)}},
        @{name='wrong source overlay'; pattern='qualifier identity'; body={param($f) $f.report.sourceManifestSha256=('0'*64)}},
        @{name='wrong authenticated prefix'; pattern='prefix authority'; body={param($f) $f.process.inputs.authenticatedPrefixBytes=20971519}},
        @{name='unhashed process authority'; pattern='original hash authority'; body={param($f) $f.process.members[0].basis='INVENTED'}},
        @{name='process hash changed'; pattern='byte pin differs'; body={param($f) $f.process.members[0].expectedSha256=('0'*64)}},
        @{name='duplicate process record'; pattern='missing or ambiguous'; body={param($f) $f.process.originalExecutions += $f.process.originalExecutions[0]}},
        @{name='duplicate reader inventory path'; pattern='Duplicate prefix reader inventory'; body={param($f) $f.reportInventory.Add($f.reportInventory[0])}},
        @{name='case-alias inventory path'; pattern='Duplicate prefix reader inventory'; body={param($f) $p=$f.reportInventory[0]; $f.reportInventory.Add(@{path=$p.path.ToUpperInvariant(); bytes=$p.bytes; sha256=$p.sha256})}},
        @{name='traversing inventory path'; pattern='Unsafe prefix'; body={param($f) $f.reportInventory[0].path='../xml.xml'}},
        @{name='missing export mapping'; pattern='export is missing'; body={param($f) $f.report.reports[0].exportMember='custody/absent.xml'}},
        @{name='nonzero extraction'; pattern='extraction must have native exit zero'; body={param($f) $f.report.reports[0].recoveryNativeExit=1}},
        @{name='missing extraction result'; pattern='Missing prefix custody field'; body={param($f) $f.report.reports[0].Remove('recoveryNativeExit')}},
        @{name='reader as XML owner'; pattern='Invented BB XML producer'; body={param($f) $f.report.reports[0].originalPhysicalExecutionId='28687044-a560-4806-accc-727f3a142267'}},
        @{name='short original XML'; pattern='byte pin differs'; body={param($f) $f.report.reports[0].bytes--}},
        # Preserve F3 member cardinality so each collision reaches the mapping guard.
        @{name='duplicate original XML mapping'; pattern='Duplicate BB XML mapping'; body={param($f) $f.report.reports[1].originalName=$f.report.reports[0].originalName}},
        @{name='duplicate XML export mapping'; pattern='Duplicate BB XML mapping'; body={param($f) $f.report.reports[1].exportMember=$f.report.reports[0].exportMember}},
        @{name='duplicate XML custody mapping'; pattern='Duplicate BB XML mapping'; body={param($f) $f.report.reports[1].custodyMember=$f.report.reports[0].custodyMember}},
        @{name='invented regular member'; pattern='regular-header member lineage'; body={param($f) $f.report.members[0].basis='INVENTED'}},
        @{name='unauthenticated XML'; pattern='unauthenticated regular header'; body={param($f) $f.report.reports[0].byteAuthentication='SYNTHETIC'}},
        @{name='original failure erased'; pattern='original native exit was replaced'; body={param($f) $f.report.reports[0].originalNativeExit=137}}
    )
    foreach ($mutation in $readerMutations) {
        Test-PrefixFixture $mutation.name {
            $f = New-PrefixFixture
            & $mutation.body $f
            Seal-PrefixFixtureReaders $f
            Assert-PrefixRejected $f $mutation.pattern
        }
    }
    Test-PrefixFixture 'unindexed sealed XML cannot disappear from the obligation' {
        $f = New-PrefixFixture
        $null = Write-PrefixFixtureMember $f 'originals/bb/evidence/standalone/cachedb-starter/target/surefire-reports/TEST-unindexed.xml' '<testsuite />'
        Assert-PrefixRejected $f 'Unindexed BB original XML'
    }
    Test-PrefixFixture 'tampered staged bytes are not repaired by reader admission' {
        $f = New-PrefixFixture
        [IO.File]::AppendAllText((Join-Path $f.root $f.index.reports[0].path),'tampered')
        Assert-PrefixRejected $f 'Synthetic sealed bytes differ'
    }
    Write-Host "PASS: $script:fixtureCount synthetic prefix-custody checks; not historical or product qualification."
} finally {
    if (Test-Path -LiteralPath $script:fixtureRoot) {
        $resolved = (Get-Item -LiteralPath $script:fixtureRoot -Force).FullName
        if ($resolved -cne [IO.Path]::GetFullPath($script:fixtureRoot) -or
                (Split-Path -Leaf $resolved) -cnotmatch '^cachedb-prefix-custody-fixtures-[0-9a-f]{32}$') { throw 'Refusing unsafe synthetic fixture cleanup.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
