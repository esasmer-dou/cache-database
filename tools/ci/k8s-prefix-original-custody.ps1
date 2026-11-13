#requires -Version 7.3

function Assert-K8sPrefixFields {
    param($Value, [string[]]$Fields, [string]$Label, [switch]$Exact)
    if ($Value -isnot [Collections.IDictionary]) { throw "Prefix custody object required: $Label" }
    foreach ($field in $Fields) {
        if (!$Value.Contains($field)) { throw "Missing prefix custody field: $Label/$field" }
    }
    if ($Exact -and ($Value.Count -ne $Fields.Count -or @($Value.Keys | Where-Object { $_ -cnotin $Fields }).Count)) {
        throw "Unexpected prefix custody field: $Label"
    }
}

function Assert-K8sPrefixPin {
    param($Value, [string]$Label)
    Assert-K8sPrefixFields $Value @('bytes','sha256') $Label
    if (($Value.bytes -isnot [int] -and $Value.bytes -isnot [long]) -or
            $Value.bytes -lt 0 -or $Value.bytes -gt 32MB -or
            $Value.sha256 -isnot [string] -or $Value.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw "Invalid prefix custody byte pin: $Label"
    }
}

function Assert-K8sPrefixSamePin {
    param($Left, $Right, [string]$Label)
    Assert-K8sPrefixPin $Left $Label
    Assert-K8sPrefixPin $Right $Label
    if ($Left.bytes -ne $Right.bytes -or $Left.sha256 -cne $Right.sha256) { throw "Prefix custody byte pin differs: $Label" }
}

function Assert-K8sPrefixPath {
    param([string]$Path)
    if (!$Path -or $Path.Length -gt 1024 -or $Path -cmatch '[\\\x00-\x1f\x7f<>:"|?*]' -or $Path.StartsWith('/')) {
        throw 'Unsafe prefix custody member path.'
    }
    foreach ($part in $Path.Split('/')) {
        if (!$part -or $part -cin @('.','..') -or $part.Trim() -cne $part -or $part.EndsWith('.')) {
            throw 'Unsafe prefix custody member path.'
        }
    }
}

function Get-K8sPrefixSealedPath {
    param($Reference, $Receipt, [string]$Root, [long]$Maximum = 4MB)
    Assert-K8sPrefixFields $Reference @('path','sha256') 'sealed reference' -Exact
    Assert-K8sPrefixPath $Reference.path
    if ($Reference.sha256 -isnot [string] -or $Reference.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Invalid prefix reference SHA-256.' }
    $path = Assert-K8sSealedReference $Reference $Receipt $Root $Maximum
    $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
    if ($item.PSIsContainer -or $item.Length -gt $Maximum) { throw 'Prefix custody member exceeds its file limit.' }
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked prefix custody path is forbidden.' }
        $parent = [IO.Directory]::GetParent($item.FullName)
        $item = if ($null -ne $parent) { Get-Item -LiteralPath $parent.FullName -Force -ErrorAction Stop } else { $null }
    }
    return $path
}

function Assert-K8sPrefixStagedMember {
    param([string]$Member, $Pin, $Receipt, [string]$Root)
    Assert-K8sPrefixPin $Pin $Member
    $path = Get-K8sPrefixSealedPath @{path=$Member; sha256=$Pin.sha256} $Receipt $Root 32MB
    $entries = @($Receipt.files | Where-Object { $_.path -ceq $Member })
    if ($entries.Count -ne 1 -or (Get-Item -LiteralPath $path -Force).Length -ne $Pin.bytes) { throw "Prefix staged member length differs: $Member" }
    Assert-K8sPrefixSamePin $entries[0] $Pin $Member
    return $path
}

function Read-K8sPrefixReader {
    param($Binding, [string]$Profile, $Receipt, [string]$Root, $Policy)
    Assert-K8sPrefixFields $Binding @('summary','inventory') 'reader binding' -Exact
    foreach ($ref in @($Binding.summary,$Binding.inventory)) {
        Assert-K8sPrefixFields $ref @('path','sha256') 'reader reference' -Exact
    }
    Assert-K8sPrefixFields $Policy @('ReviewedPrefixOriginals') 'policy'
    $admissions = $Policy.ReviewedPrefixOriginals
    if ($admissions -isnot [Collections.IDictionary] -or !$admissions.Contains($Binding.summary.sha256)) {
        throw 'Prefix reader summary lacks independent policy admission.'
    }
    $admission = $admissions[$Binding.summary.sha256]
    Assert-K8sPrefixFields $admission @('profile','inventorySha256') 'reader policy' -Exact
    if ($admission.profile -cne $Profile -or $admission.inventorySha256 -cne $Binding.inventory.sha256) {
        throw 'Prefix reader profile or independently admitted inventory differs.'
    }
    $summaryPath = Get-K8sPrefixSealedPath $Binding.summary $Receipt $Root
    $inventoryPath = Get-K8sPrefixSealedPath $Binding.inventory $Receipt $Root
    $summary = Read-K8sJson $summaryPath 4MB
    $entries = @(Read-K8sJson $inventoryPath 4MB)
    if (!$entries.Count -or $entries.Count -gt 512) { throw 'Prefix reader inventory count exceeds its limit.' }
    $inventory = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $portable = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $entries) {
        Assert-K8sPrefixFields $entry @('path','bytes','sha256') 'reader inventory member' -Exact
        Assert-K8sPrefixPath $entry.path
        Assert-K8sPrefixPin $entry $entry.path
        $null = Resolve-K8sMember $Root $entry.path
        if (!$portable.Add($entry.path)) { throw 'Duplicate prefix reader inventory member.' }
        $inventory.Add($entry.path,$entry)
    }
    if (!$inventory.ContainsKey('custody/result.json')) { throw 'Prefix reader inventory does not bind its summary.' }
    Assert-K8sPrefixSamePin $inventory['custody/result.json'] @{
        bytes=(Get-Item -LiteralPath $summaryPath -Force).Length; sha256=$Binding.summary.sha256
    } 'reader summary inventory linkage'
    return @{ summary=$summary; inventory=$inventory }
}

function Assert-K8sPrefixOriginalCustody {
    <#
    Read-only typed admission for BB's incomplete original archive, not semantic PASS.
    Dependencies are the parent checker's sealed-reference, path and JSON readers.
    originalCustody has exactly receipt, prefix, prefixCustody. prefixCustody has exactly
    schemaVersion='cachedb.bb-prefix-original-custody/v1', processReader, reportReader;
    each reader has summary and inventory {path,sha256} references sealed in Receipt.files.
    No archive is extracted, recreated or accepted. Raw producer metadata owns identities.

    Suggested independently reviewed policy entries (not auto-installed by this helper):
    ReviewedOriginalReceipts = @{
      '765307e4742afcc42d887e63927306889760135a3fc43fa41b6d32fa63740019' = 'df21bb8a-a92f-4473-8f7a-f6742ceb7884'
    }
    ReviewedPrefixOriginals = @{
      'f1fce3ee1ec153e2826bdad4faef0ad95a2a3924e1d1edfa85c687b29e217dc2' = @{
        profile='BB_PROCESS'; inventorySha256='a281a4f118dc06a6a9b138eaeb4ffb025cc32b478c58e4fdcdc7a04c0e7b304e'
      }
      'bc13c2e1d48c6559de6e80a1dcc68b246186abe2189d86223741cc5032aa319a' = @{
        profile='BB_BASE_XML'; inventorySha256='c1af95385475fea0d8f02264463bc087b22467533405ab8196f03da4d8ae866e'
      }
      '6f020592270ea937539e320cb8a9e44f66017de1646d3817980acf24bc973687' = @{
        profile='BB_F3_XML'; inventorySha256='db3e7d4aca169447ddb3a8d72cc2d68a63de3adcfde854c39b3cfb5286d92ebf'
      }
    }
    #>
    [CmdletBinding()]
    param($Execution, $Index, $Receipt, [string]$Root, $Policy)
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'
    $verificationSha = '765307e4742afcc42d887e63927306889760135a3fc43fa41b6d32fa63740019'
    $producerUid = 'df21bb8a-a92f-4473-8f7a-f6742ceb7884'
    $sourceSha = '456ef8ad0a32a571ec52a345dbeb0a3e5f2dcaf742a7eb3d840f49c831a69a11'
    $sdkSha = '474527305300767b9d6ba35f24e5c5085505680653aea9137cf9524044d116a3'
    $prefixSha = 'ae97bc8c7d6845f0df295c0a06fa82b3504086afeb489d41ce085513711f14b1'
    $archiveSha = '912704d180155a83ea388644d14a883e3872c0a70eb2d23910511b79b39eec92'
    $owners = @{
        f3=@{uuid='e46f53fa-478c-4ae8-ab68-a6481068aab2'; modules=@('cachedb-spring-boot-starter','cachedb-starter','cachedb-storage-jdbc')}
        'standalone/cachedb-core'=@{uuid='961e4a72-1155-4bc8-bb7a-f4ee93e986f0'; modules=@('cachedb-core')}
        'standalone/cachedb-processor'=@{uuid='5602849a-bf09-484b-899e-ee601e3afc49'; modules=@('cachedb-processor')}
    }
    Assert-K8sPrefixFields $Execution @('id','originalCustody','originalRecord','originalRecordId','originalPhysicalExecutionId','podUid','observedExit','log','exitFile') 'execution'
    Assert-K8sPrefixFields $Index @('reports') 'case index'
    Assert-K8sPrefixFields $Receipt @('files') 'receipt'
    Assert-K8sPrefixFields $Policy @('ReviewedOriginalReceipts') 'policy'
    $binding = $Execution.originalCustody
    Assert-K8sPrefixFields $binding @('receipt','prefix','prefixCustody') 'original custody binding' -Exact
    Assert-K8sPrefixPath $binding.prefix
    $typed = $binding.prefixCustody
    Assert-K8sPrefixFields $typed @('schemaVersion','processReader','reportReader') 'typed prefix binding' -Exact
    if ($typed.schemaVersion -cne 'cachedb.bb-prefix-original-custody/v1' -or
            $Policy.ReviewedOriginalReceipts -isnot [Collections.IDictionary] -or
            !$Policy.ReviewedOriginalReceipts.Contains($verificationSha) -or
            $Policy.ReviewedOriginalReceipts[$verificationSha] -cne $producerUid) { throw 'BB original authority is not independently admitted.' }
    Assert-K8sPrefixFields $binding.receipt @('path','sha256') 'original verification reference' -Exact
    if ($binding.receipt.sha256 -cne $verificationSha) { throw 'Only the reviewed original BB verification is admitted.' }
    $verification = Read-K8sJson (Get-K8sPrefixSealedPath $binding.receipt $Receipt $Root) 4MB
    Assert-K8sPrefixFields $verification @('schema','podUid','selected','archiveSha256','archiveBytes') 'original verification'
    if ($verification.schema -cne 'cachedb.remote-archive-verification/v1' -or $verification.podUid -cne $producerUid -or
            $verification.archiveSha256 -cne $archiveSha -or $verification.archiveBytes -ne 90091062) { throw 'Original BB verification identity differs.' }
    if ($verification.selected -isnot [array] -or $verification.selected.Count -gt 64) { throw 'Invalid original selected member list.' }
    $selected = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $selectedPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $verification.selected) {
        Assert-K8sPrefixFields $entry @('name','bytes','sha256') 'original selected member'
        Assert-K8sPrefixPath $entry.name
        Assert-K8sPrefixPin $entry $entry.name
        if (!$selectedPaths.Add($entry.name)) { throw 'Duplicate original selected member.' }
        $selected.Add($entry.name,$entry)
    }
    if (!$selected.ContainsKey('evidence/scoped-result.json')) { throw 'Original verification does not bind the raw producer record.' }
    Assert-K8sPrefixFields $Execution.originalRecord @('path','sha256') 'raw record reference' -Exact
    if ($Execution.originalRecord.path -cne ($binding.prefix + '/evidence/scoped-result.json') -or
            $Execution.originalRecord.sha256 -cne $selected['evidence/scoped-result.json'].sha256) { throw 'Raw producer record is not the original selected member.' }
    $rawPath = Assert-K8sPrefixStagedMember $Execution.originalRecord.path $selected['evidence/scoped-result.json'] $Receipt $Root
    $raw = Read-K8sJson $rawPath 4MB
    Assert-K8sPrefixFields $raw @('executions') 'raw producer record'
    if ($raw.executions -isnot [array] -or $raw.executions.Count -gt 128 -or !$owners.Contains($Execution.originalRecordId)) { throw 'Unmapped BB producer execution.' }
    $records = @($raw.executions | Where-Object {
        Assert-K8sPrefixFields $_ @('id') 'raw execution'
        $_.id -ceq $Execution.originalRecordId
    })
    if ($records.Count -ne 1) { throw 'Original BB process record is missing or duplicated.' }
    $record = $records[0]
    Assert-K8sPrefixFields $record @('physicalExecutionId','podUid','nativeExit','effectiveExit','sourceManifestSha256','sdkManifestSha256','processEvidenceSealed','processLog','processExit') 'raw process'
    if ($record.physicalExecutionId -cne $owners[$Execution.originalRecordId].uuid -or
            $Execution.originalPhysicalExecutionId -cne $record.physicalExecutionId -or
            $Execution.podUid -cne $producerUid -or $record.podUid -cne $producerUid -or
            $record.sourceManifestSha256 -cne $sourceSha -or $record.sdkManifestSha256 -cne $sdkSha -or
            $record.processEvidenceSealed -isnot [bool] -or !$record.processEvidenceSealed -or
            $Execution.observedExit -ne $record.nativeExit) { throw 'BB producer identity, source, SDK or native exit was relabelled.' }
    $processReader = Read-K8sPrefixReader $typed.processReader BB_PROCESS $Receipt $Root $Policy
    $process = $processReader.summary
    Assert-K8sPrefixFields $process @('schema','status','inputs','members','originalExecutions','testsRun','archiveAccepted','releaseAccepted','originalProducerRecreated') 'process reader summary'
    if ($process.schema -cne 'cachedb.partial-custody-read/v1' -or $process.status -cne 'READ_COMPLETED_INDIVIDUAL_RESULTS_ONLY' -or
            $process.testsRun -cne $false -or $process.archiveAccepted -cne $false -or $process.releaseAccepted -cne $false -or $process.originalProducerRecreated -cne $false) { throw 'Invalid BB process custody scope.' }
    Assert-K8sPrefixFields $process.inputs @('authenticatedPrefixBytes','authenticatedPrefixSha256','originalArchiveBytes','originalArchiveSha256','verificationSha256') 'prefix inputs'
    if ($process.inputs.authenticatedPrefixBytes -ne 20971520 -or $process.inputs.authenticatedPrefixSha256 -cne $prefixSha -or
            $process.inputs.originalArchiveBytes -ne $verification.archiveBytes -or $process.inputs.originalArchiveSha256 -cne $archiveSha -or
            $process.inputs.verificationSha256 -cne $verificationSha) { throw 'BB authenticated prefix authority differs.' }
    if ($process.members -isnot [array] -or $process.members.Count -gt 128 -or $process.originalExecutions -isnot [array]) { throw 'Invalid BB process reader members.' }
    $projection = @($process.originalExecutions | Where-Object {
        Assert-K8sPrefixFields $_ @('id') 'reader process projection'
        $_.id -ceq $Execution.originalRecordId
    })
    if ($projection.Count -ne 1) { throw 'BB reader process projection is missing or ambiguous.' }
    Assert-K8sPrefixFields $projection[0] @('physicalExecutionId','podUid','nativeExit','effectiveExit') 'reader process projection'
    foreach ($key in @('physicalExecutionId','podUid','nativeExit','effectiveExit')) {
        if ($projection[0][$key] -cne $record[$key]) { throw 'Reader identity does not match the original producer record.' }
    }
    foreach ($kind in @('processLog','processExit')) {
        $pin = $record[$kind]
        Assert-K8sPrefixFields $pin @('path','bytes','sha256') $kind
        if ($pin.path -cnotmatch '^/workspace/(evidence/commands/[^/]+/process\.(log|exit))$') { throw 'Unmapped BB process path.' }
        $originalName = $Matches[1]
        $expectedName = 'evidence/commands/' + $Execution.originalRecordId.Replace('/','-') + '/process.' + $(if ($kind -ceq 'processLog') { 'log' } else { 'exit' })
        if ($originalName -cne $expectedName) { throw 'BB process evidence belongs to another command.' }
        $member = $binding.prefix + '/' + $originalName
        $declared = if ($kind -ceq 'processLog') { $Execution.log } else { $Execution.exitFile }
        if ($declared -cne $member) { throw 'BB process evidence was renamed.' }
        $matches = @($process.members | Where-Object {
            Assert-K8sPrefixFields $_ @('name') 'process member'
            $_.name -ceq $originalName
        })
        if ($matches.Count -ne 1) { throw 'BB original process member is missing or duplicated.' }
        $found = $matches[0]
        Assert-K8sPrefixFields $found @('basis','status','file','expectedBytes','expectedSha256','bytes','sha256') 'process member'
        if ($found.basis -cne 'ORIGINAL_SELECTED_SCOPED_JSON' -or $found.status -cne 'ORIGINAL_INDIVIDUAL_BYTES_MATCH') { throw 'BB process member lacks original hash authority.' }
        Assert-K8sPrefixSamePin $found $pin $originalName
        Assert-K8sPrefixSamePin @{bytes=$found.expectedBytes; sha256=$found.expectedSha256} $pin $originalName
        Assert-K8sPrefixPath $found.file
        $custodyName = 'custody/' + $found.file
        if (!$processReader.inventory.ContainsKey($custodyName)) { throw 'BB process member is not in the admitted inventory.' }
        Assert-K8sPrefixSamePin $processReader.inventory[$custodyName] $pin $originalName
        $null = Assert-K8sPrefixStagedMember $member $pin $Receipt $Root
    }
    $profile = if ($Execution.originalRecordId -ceq 'f3') { 'BB_F3_XML' } else { 'BB_BASE_XML' }
    $reportReader = Read-K8sPrefixReader $typed.reportReader $profile $Receipt $Root $Policy
    $summary = $reportReader.summary
    Assert-K8sPrefixFields $summary @('schema','status','qualificationCompleted','error','reports','selectedSuiteCount','sourceManifestSha256','sdkManifestSha256','originalPodUid','testsRun','archiveAccepted','releaseAccepted') 'XML qualifier summary'
    if ($summary.schema -cne 'cachedb.bb-junit-semantic-qualification/v1' -or $summary.status -cne 'SEMANTIC_REVIEW_COMPLETE_NOT_RELEASE_ACCEPTANCE' -or
            $summary.qualificationCompleted -cne $true -or $null -ne $summary.error -or
            $summary.sourceManifestSha256 -cne $sourceSha -or $summary.sdkManifestSha256 -cne $sdkSha -or $summary.originalPodUid -cne $producerUid -or
            $summary.testsRun -cne $false -or $summary.archiveAccepted -cne $false -or $summary.releaseAccepted -cne $false -or
            $summary.reports -isnot [array] -or !$summary.reports.Count -or $summary.reports.Count -gt 67 -or
            $summary.selectedSuiteCount -ne $summary.reports.Count) { throw 'BB XML qualifier identity or scope differs.' }
    if ($profile -ceq 'BB_F3_XML') {
        Assert-K8sPrefixFields $summary @('authenticatedPrefixBytes','authenticatedPrefixSha256','f3','members') 'F3 prefix summary'
        Assert-K8sPrefixFields $summary.f3 @('physicalExecutionId','originalNativeExit') 'F3 producer'
        if ($summary.authenticatedPrefixBytes -ne 20971520 -or $summary.authenticatedPrefixSha256 -cne $prefixSha -or
                $summary.f3.physicalExecutionId -cne $record.physicalExecutionId -or $summary.f3.originalNativeExit -ne $record.nativeExit -or
                $summary.members -isnot [array] -or $summary.members.Count -ne $summary.reports.Count) { throw 'F3 original prefix lineage differs.' }
    } else {
        Assert-K8sPrefixFields $summary @('opaqueCustodySha256') 'base XML summary'
        if ($summary.opaqueCustodySha256 -cne '6aa969bf1ff70b4339271e326ff238ac5ef12d641fdcd06005a8709b1e69bae0') { throw 'Base XML reader opaque custody lineage differs.' }
    }
    $mapped = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $exports = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $custodyMembers = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($report in $summary.reports) {
        Assert-K8sPrefixFields $report @('originalName','exportMember','custodyMember','bytes','sha256','byteAuthentication','originalPhysicalExecutionId','originalNativeExit','module') 'qualified XML mapping'
        foreach ($path in @($report.originalName,$report.exportMember,$report.custodyMember)) { Assert-K8sPrefixPath $path }
        if ($mapped.ContainsKey($report.originalName) -or !$exports.Add($report.exportMember) -or !$custodyMembers.Add($report.custodyMember)) { throw 'Duplicate BB XML mapping.' }
        if ($report.originalName -cnotmatch '^evidence/standalone/([^/]+)/target/surefire-reports/TEST-[^/]+\.xml$' -or
                $report.module -cne $Matches[1] -or $report.byteAuthentication -cne 'INHERITED_AUTHENTICATED_COMPRESSED_PREFIX_COMPLETE_REGULAR_MEMBER') { throw 'Unmapped BB XML member or unauthenticated regular header.' }
        $reportOwner = @($owners.Values | Where-Object { $_.uuid -ceq $report.originalPhysicalExecutionId -and $report.module -cin $_.modules })
        if ($reportOwner.Count -ne 1 -or ($profile -ceq 'BB_F3_XML') -ne ($report.originalPhysicalExecutionId -ceq $owners.f3.uuid)) { throw 'Invented BB XML producer lineage.' }
        $ownerRecords = @($raw.executions | Where-Object {
            Assert-K8sPrefixFields $_ @('physicalExecutionId') 'raw XML owner'
            $_.physicalExecutionId -ceq $report.originalPhysicalExecutionId
        })
        if ($ownerRecords.Count -ne 1) { throw 'BB XML has no unique raw producer record.' }
        Assert-K8sPrefixFields $ownerRecords[0] @('nativeExit') 'raw XML owner'
        if ($report.originalNativeExit -ne $ownerRecords[0].nativeExit) { throw 'BB XML original native exit was replaced.' }
        if (!$reportReader.inventory.ContainsKey($report.exportMember)) { throw 'BB XML export is missing from admitted inventory.' }
        Assert-K8sPrefixSamePin $reportReader.inventory[$report.exportMember] $report $report.originalName
        $sourceInventory = if ($profile -ceq 'BB_F3_XML') { $reportReader.inventory } else { $processReader.inventory }
        if (!$sourceInventory.ContainsKey($report.custodyMember)) { throw 'BB XML regular member is missing from custody inventory.' }
        Assert-K8sPrefixSamePin $sourceInventory[$report.custodyMember] $report $report.originalName
        if ($profile -ceq 'BB_F3_XML') {
            Assert-K8sPrefixFields $report @('recoveryNativeExit') 'F3 selected extraction'
            if (($report.recoveryNativeExit -isnot [int] -and $report.recoveryNativeExit -isnot [long]) -or $report.recoveryNativeExit -ne 0) { throw 'F3 selected-member extraction must have native exit zero.' }
            $members = @($summary.members | Where-Object {
                Assert-K8sPrefixFields $_ @('name') 'F3 regular member'
                $_.name -ceq $report.originalName
            })
            if ($members.Count -ne 1) { throw 'F3 original regular member is missing or duplicated.' }
            Assert-K8sPrefixFields $members[0] @('file','basis','status','bytes','sha256') 'F3 regular member'
            if (('custody/' + $members[0].file) -cne $report.custodyMember -or
                    $members[0].basis -cne 'AUTHENTICATED_20M_PREFIX_GNU_REGULAR_HEADER_NO_INDIVIDUAL_ORIGINAL_HASH' -or
                    $members[0].status -cne 'PREFIX_REGULAR_CANDIDATE_UNQUALIFIED') { throw 'F3 regular-header member lineage differs.' }
            Assert-K8sPrefixSamePin $members[0] $report $report.originalName
        }
        $mapped.Add($report.originalName,$report)
    }
    if ($Index.reports -isnot [array] -or $Index.reports.Count -gt 10000) { throw 'Invalid BB original-case report list.' }
    $included = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($report in $Index.reports) {
        Assert-K8sPrefixFields $report @('executionId','path','sha256') 'indexed report' -Exact
        if ($report.executionId -cne $Execution.id) { continue }
        $prefix = $binding.prefix + '/'
        if (!$report.path.StartsWith($prefix,[StringComparison]::Ordinal)) { throw 'Indexed BB XML is outside its original prefix.' }
        $name = $report.path.Substring($prefix.Length)
        if (!$mapped.ContainsKey($name) -or !$included.Add($name)) { throw 'Unmapped or duplicate indexed BB XML.' }
        $original = $mapped[$name]
        if ($original.originalPhysicalExecutionId -cne $record.physicalExecutionId -or $report.sha256 -cne $original.sha256) { throw 'Indexed BB XML ownership or SHA-256 differs.' }
        $null = Assert-K8sPrefixStagedMember $report.path $original $Receipt $Root
    }
    $expected = @($summary.reports | Where-Object { $_.originalPhysicalExecutionId -ceq $record.physicalExecutionId } | ForEach-Object { $_.originalName })
    if (!$expected.Count -or !$included.SetEquals([string[]]$expected)) { throw 'Every qualified original XML for the BB process must be retained.' }
    foreach ($file in $Receipt.files) {
        Assert-K8sPrefixFields $file @('path') 'sealed member'
        if ($file.path.StartsWith(($binding.prefix + '/evidence/standalone/'),[StringComparison]::Ordinal) -and $file.path.EndsWith('.xml',[StringComparison]::Ordinal)) {
            if (!@($Index.reports | Where-Object { $_.path -ceq $file.path }).Count) { throw 'Unindexed BB original XML cannot be ignored.' }
        }
    }
}
