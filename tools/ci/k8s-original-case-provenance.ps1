#requires -Version 7.3

function Assert-K8sOriginalCaseFields {
    param([System.Text.Json.JsonElement]$Element, [string[]]$Fields, [string]$Label)
    if ($Element.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { throw "Expected object: $Label" }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($property in $Element.EnumerateObject()) {
        if (!$seen.Add($property.Name) -or $property.Name -cnotin $Fields) {
            throw "Duplicate or unexpected property: $Label/$($property.Name)"
        }
    }
    if ($seen.Count -ne $Fields.Count) { throw "Missing property: $Label" }
}

function Get-K8sOriginalCaseString {
    param([System.Text.Json.JsonElement]$Element, [string]$Property, [int]$Maximum = 4096)
    $value = $Element.GetProperty($Property)
    if ($value.ValueKind -ne [System.Text.Json.JsonValueKind]::String) { throw "Expected string: $Property" }
    $text = $value.GetString()
    if ([string]::IsNullOrWhiteSpace($text) -or $text.Length -gt $Maximum -or $text -match '[\x00-\x1f\x7f]') {
        throw "Invalid string: $Property"
    }
    return $text
}

function Assert-K8sOriginalCaseNoLink {
    param([string]$Path)
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Linked evidence path is forbidden: $Path"
        }
        $parent = [IO.Directory]::GetParent($item.FullName)
        $item = if ($null -ne $parent) { Get-Item -LiteralPath $parent.FullName -Force -ErrorAction Stop } else { $null }
    }
}

function Resolve-K8sOriginalCaseReport {
    param([string]$Root, [string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or $RelativePath.Length -gt 4096 -or
            $RelativePath -match '[\\\x00-\x1f\x7f<>:"|?*]' -or [IO.Path]::IsPathRooted($RelativePath)) {
        throw "Unsafe relative report path: $RelativePath"
    }
    foreach ($part in $RelativePath.Split('/')) {
        if (!$part -or $part -in @('.','..') -or $part.EndsWith('.') -or $part.EndsWith(' ')) {
            throw "Unsafe relative report path: $RelativePath"
        }
    }
    if ($RelativePath.Split('/')[-1] -cnotmatch '^TEST-.+\.xml$') { throw "Not an original JUnit report path: $RelativePath" }
    $full = [IO.Path]::GetFullPath([IO.Path]::Combine($Root,$RelativePath))
    $prefix = $Root.TrimEnd([char[]]@([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)) + [IO.Path]::DirectorySeparatorChar
    if (!$full.StartsWith($prefix,[StringComparison]::Ordinal)) { throw "Report escaped evidence root: $RelativePath" }
    Assert-K8sOriginalCaseNoLink $full
    if ((Get-Item -LiteralPath $full -Force).PSIsContainer) { throw "Report is not a file: $RelativePath" }
    return $full
}

function Read-K8sOriginalCaseBytes {
    param([string]$Path, [long]$Maximum)
    Assert-K8sOriginalCaseNoLink $Path
    $stream = [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        if ($stream.Length -le 0 -or $stream.Length -gt $Maximum) { throw "Evidence file size limit: $Path" }
        $bytes = [byte[]]::new([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $count = $stream.Read($bytes,$offset,$bytes.Length - $offset)
            if (!$count) { throw "Short evidence file: $Path" }
            $offset += $count
        }
        if ($stream.ReadByte() -ne -1) { throw "Evidence changed length while reading: $Path" }
        return ,$bytes
    } finally { $stream.Dispose() }
}

function Get-K8sOriginalCaseKey {
    param([string[]]$Parts)
    return (($Parts | ForEach-Object { "$($_.Length):$_" }) -join '')
}

function Get-K8sOriginalCaseEndpoint {
    param([System.Text.Json.JsonElement]$Element)
    Assert-K8sOriginalCaseFields $Element @('reportPath','className','name','occurrence') 'closure endpoint'
    $occurrence = $Element.GetProperty('occurrence')
    $number = 0
    if ($occurrence.ValueKind -ne [System.Text.Json.JsonValueKind]::Number -or
            $occurrence.GetRawText() -cnotmatch '^[1-9][0-9]*$' -or !$occurrence.TryGetInt32([ref]$number) -or $number -gt 100000) {
        throw 'Invalid closure occurrence; expected a one-based integer.'
    }
    return [pscustomobject]@{
        reportPath = Get-K8sOriginalCaseString $Element 'reportPath'
        className = Get-K8sOriginalCaseString $Element 'className' 1024
        name = Get-K8sOriginalCaseString $Element 'name'
        occurrence = $number
    }
}

function Assert-K8sOriginalCaseProvenance {
    <#
    Pure, read-only semantic validation, not execution/custody or SDK attestation.
    The caller must authenticate index completeness and each declared executionId against original
    receipts/inventories, including real exits, frozen SDK and separately reviewed fixture overlays.
    Schema: {schemaVersion:'cachedb.k8s-original-case-provenance/v1', obligationId,
      reports:[{executionId,path,sha256}], closures:[{from:{reportPath,className,name,occurrence},
      to:{reportPath,className,name,occurrence}}]}. No additional or duplicate fields are allowed.
    Occurrence is one-based document order per exact (classname,name) within each report.
    All indexed non-passing cases need explicit closures, including incidental suites and skips.
    Returns records (original counters/module), cases (unchanged outcomes), closedFailures
    (failure/error closure edges), closedSkips, and coveredCases (unique passing references).
    No required-contract claim is made by a four-argument call: requiredContractsChecked is false.
    Never emits merged JUnit, rewrites originals, or supplies product process exits.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory,Position=0)][string]$IndexPath,
        [Parameter(Mandatory,Position=1)][string]$IndexSha256,
        [Parameter(Mandatory,Position=2)][string]$EvidenceRoot,
        [Parameter(Mandatory,Position=3)][Alias('PhysicalExecutionIds')][string[]]$ExecutionIds,
        [ValidateSet('standalone','cluster','provider/postgresql','provider/mssql','provider/oracle')][string]$ExpectedObligationId,
        [string[]]$RequiredSuites = @(),
        [Collections.IDictionary]$RequiredMethods = @{}
    )
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'
    if ($IndexSha256 -cnotmatch '^[0-9a-fA-F]{64}$') { throw 'Invalid index SHA-256 pin.' }
    $allowedExecutions = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $ExecutionIds) {
        if ([string]::IsNullOrWhiteSpace($id) -or $id.Length -gt 256 -or $id -match '[\x00-\x1f\x7f]' -or !$allowedExecutions.Add($id)) {
            throw 'Invalid or duplicate declared physical execution ID.'
        }
    }
    if (!$allowedExecutions.Count -or $allowedExecutions.Count -gt 10000) { throw 'Declared physical execution IDs are empty or exceed the count limit.' }
    $rootItem = Get-Item -LiteralPath $EvidenceRoot -Force
    if (!$rootItem.PSIsContainer -or $rootItem.PSProvider.Name -ne 'FileSystem') { throw 'Evidence root must be a filesystem directory.' }
    $root = $rootItem.FullName
    Assert-K8sOriginalCaseNoLink $root
    $indexBytes = Read-K8sOriginalCaseBytes ([IO.Path]::GetFullPath($IndexPath)) 4MB
    $indexHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($indexBytes)).ToLowerInvariant()
    if ($indexHash -cne $IndexSha256.ToLowerInvariant()) { throw 'Index SHA-256 mismatch.' }
    $jsonText = [Text.UTF8Encoding]::new($false,$true).GetString($indexBytes).TrimStart([char]0xfeff)
    $options = [Text.Json.JsonDocumentOptions]::new()
    $options.MaxDepth = 8
    $document = [Text.Json.JsonDocument]::Parse($jsonText,$options)
    try {
        $index = $document.RootElement
        Assert-K8sOriginalCaseFields $index @('schemaVersion','obligationId','reports','closures') 'index'
        $obligation = Get-K8sOriginalCaseString $index 'obligationId'
        if ((Get-K8sOriginalCaseString $index 'schemaVersion') -cne 'cachedb.k8s-original-case-provenance/v1' -or
                $obligation -cnotin @('standalone','cluster','provider/postgresql','provider/mssql','provider/oracle') -or
                ($ExpectedObligationId -and $obligation -cne $ExpectedObligationId)) {
            throw 'Provenance schema or obligation mismatch.'
        }
        $reportEntries = $index.GetProperty('reports')
        $closureEntries = $index.GetProperty('closures')
        if ($reportEntries.ValueKind -ne [Text.Json.JsonValueKind]::Array -or $reportEntries.GetArrayLength() -lt 1 -or
                $reportEntries.GetArrayLength() -gt 10000 -or $closureEntries.ValueKind -ne [Text.Json.JsonValueKind]::Array -or
                $closureEntries.GetArrayLength() -gt 100000) { throw 'Invalid reports/closures array or count limit.' }
        $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $caseByReference = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        $coveredByIdentity = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        $coveredSuites = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $coveredMethods = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $reports = [Collections.Generic.List[object]]::new()
        $cases = [Collections.Generic.List[object]]::new()
        $totalBytes = 0L
        foreach ($entry in $reportEntries.EnumerateArray()) {
            Assert-K8sOriginalCaseFields $entry @('executionId','path','sha256') 'report'
            $executionId = Get-K8sOriginalCaseString $entry 'executionId' 256
            if (!$allowedExecutions.Contains($executionId)) { throw "Report execution ID is not declared by the caller: $executionId" }
            $path = Get-K8sOriginalCaseString $entry 'path'
            $hash = Get-K8sOriginalCaseString $entry 'sha256' 64
            if ($hash -cnotmatch '^[0-9a-fA-F]{64}$' -or !$paths.Add($path)) { throw "Invalid hash or duplicate report path: $path" }
            $full = Resolve-K8sOriginalCaseReport $root $path
            $length = (Get-Item -LiteralPath $full -Force).Length
            if ($length -gt 32MB -or $totalBytes + $length -gt 128MB) { throw 'Report evidence exceeds 32 MiB file / 128 MiB total budget.' }
            $bytes = Read-K8sOriginalCaseBytes $full 32MB
            $totalBytes += $bytes.Length
            if ($totalBytes -gt 128MB) { throw 'Report evidence exceeds 128 MiB total budget.' }
            if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $hash.ToLowerInvariant()) {
                throw "Report SHA-256 mismatch: $path"
            }
            $settings = [Xml.XmlReaderSettings]::new()
            $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
            $settings.XmlResolver = $null
            $settings.MaxCharactersInDocument = 32MB
            $stream = [IO.MemoryStream]::new($bytes,$false)
            $reader = [Xml.XmlReader]::Create($stream,$settings)
            try { $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) }
            finally { $reader.Dispose(); $stream.Dispose() }
            $suite = $xml.get_DocumentElement()
            if ($null -eq $suite -or $suite.get_Name() -cne 'testsuite' -or $suite.get_NamespaceURI()) { throw "Unsupported JUnit root: $path" }
            foreach ($element in $xml.GetElementsByTagName('*')) {
                # CLR getters avoid PowerShell's XML adapter resolving a 'name' attribute as Node.Name.
                $tag = $element.get_LocalName()
                $parent = $element.get_ParentNode()
                if ($element.get_NamespaceURI() -or $tag -match '(?i:flaky|rerun)') { throw "Namespace or flaky/rerun evidence: $path" }
                foreach ($attribute in $element.get_Attributes()) {
                    if ($attribute.get_LocalName() -match '(?i:flaky|rerun)') { throw "Flaky/rerun evidence attribute: $path" }
                }
                if (($tag -ceq 'testsuite' -and ![object]::ReferenceEquals($element,$suite)) -or
                        ($tag -ceq 'testcase' -and ![object]::ReferenceEquals($parent,$suite)) -or
                        ($tag -cin @('failure','error','skipped') -and
                            ($parent.get_LocalName() -cne 'testcase' -or ![object]::ReferenceEquals($parent.get_ParentNode(),$suite)))) {
                    throw "Nested or misplaced JUnit result: $path"
                }
                if (([object]::ReferenceEquals($parent,$suite) -and $tag -cnotin @('properties','testcase','system-out','system-err')) -or
                        ($parent.get_LocalName() -ceq 'testcase' -and $tag -cnotin @('failure','error','skipped','properties','system-out','system-err'))) {
                    throw "Unexpected JUnit child element: $path/$tag"
                }
            }
            $suiteName = $suite.GetAttribute('name')
            if ([string]::IsNullOrWhiteSpace($suiteName) -or $suiteName.Length -gt 1024 -or $suiteName -match '[\x00-\x1f\x7f]') { throw "Invalid suite name: $path" }
            $counters = @{}
            foreach ($counter in @('tests','failures','errors','skipped')) {
                $number = 0L
                $value = $suite.GetAttribute($counter)
                if ($value -cnotmatch '^(0|[1-9][0-9]*)$' -or ![long]::TryParse($value,[ref]$number) -or $number -gt 100000) {
                    throw "Invalid JUnit counter: $path/$counter"
                }
                $counters[$counter] = $number
            }
            $nodes = @($suite.SelectNodes('testcase'))
            if (!$nodes.Count -or $cases.Count + $nodes.Count -gt 100000 -or $nodes.Count -ne $counters.tests -or
                    @($suite.SelectNodes('testcase/failure')).Count -ne $counters.failures -or
                    @($suite.SelectNodes('testcase/error')).Count -ne $counters.errors -or
                    @($suite.SelectNodes('testcase/skipped')).Count -ne $counters.skipped) { throw "JUnit counters disagree or testcase limit exceeded: $path" }
            $occurrences = [Collections.Generic.Dictionary[string,int]]::new([StringComparer]::Ordinal)
            $reportCases = [Collections.Generic.List[object]]::new()
            foreach ($node in $nodes) {
                $className = $node.GetAttribute('classname')
                $name = $node.GetAttribute('name')
                if ($className -cne $suiteName -or [string]::IsNullOrWhiteSpace($name) -or $name.Length -gt 4096 -or $name -match '[\x00-\x1f\x7f]') {
                    throw "Testcase class/name mismatch: $path"
                }
                $outcomes = @($node.SelectNodes('failure|error|skipped'))
                if ($outcomes.Count -gt 1) { throw "Multiple testcase outcomes: $path/$name" }
                $pair = Get-K8sOriginalCaseKey @($className,$name)
                $ordinal = if ($occurrences.ContainsKey($pair)) { $occurrences[$pair] + 1 } else { 1 }
                $occurrences[$pair] = $ordinal
                $outcome = if ($outcomes.Count) { $outcomes[0].get_LocalName() } else { 'passed' }
                $case = [pscustomobject]@{ executionId=$executionId; reportPath=$path; className=$className; name=$name; occurrence=$ordinal; outcome=$outcome }
                $identity = Get-K8sOriginalCaseKey @($className,$name,[string]$ordinal)
                $reference = Get-K8sOriginalCaseKey @($path,$className,$name,[string]$ordinal)
                $caseByReference.Add($reference,$case)
                if ($outcome -ceq 'passed') {
                    if (!$coveredByIdentity.ContainsKey($identity)) { $coveredByIdentity.Add($identity,$case) }
                    $null = $coveredSuites.Add($className)
                    $null = $coveredMethods.Add($pair)
                }
                $reportCases.Add($case)
                $cases.Add($case)
            }
            $module = if ($path -cmatch '(^|/)([^/]+)/target/(surefire|failsafe)-reports/[^/]+$') { $Matches[2] } else { '' }
            $reports.Add([pscustomobject]@{ executionId=$executionId; path=$path; report=$path; sha256=$hash.ToLowerInvariant(); suiteName=$suiteName; name=$suiteName; module=$module;
                tests=$counters.tests; failures=$counters.failures; errors=$counters.errors; skipped=$counters.skipped; cases=$reportCases.ToArray() })
        }
        $closed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $closures = [Collections.Generic.List[object]]::new()
        foreach ($entry in $closureEntries.EnumerateArray()) {
            Assert-K8sOriginalCaseFields $entry @('from','to') 'closure'
            $from = Get-K8sOriginalCaseEndpoint $entry.GetProperty('from')
            $to = Get-K8sOriginalCaseEndpoint $entry.GetProperty('to')
            $fromKey = Get-K8sOriginalCaseKey @($from.reportPath,$from.className,$from.name,[string]$from.occurrence)
            $toKey = Get-K8sOriginalCaseKey @($to.reportPath,$to.className,$to.name,[string]$to.occurrence)
            if (!$caseByReference.ContainsKey($fromKey) -or !$caseByReference.ContainsKey($toKey)) { throw 'Closure endpoint is not an indexed original testcase.' }
            $original = $caseByReference[$fromKey]
            $replacement = $caseByReference[$toKey]
            if ($original.outcome -ceq 'passed' -or $replacement.outcome -cne 'passed' -or
                    $from.className -cne $to.className -or $from.name -cne $to.name -or $from.occurrence -ne $to.occurrence -or
                    $original.executionId -ceq $replacement.executionId -or !$closed.Add($fromKey)) {
                throw 'Closure must uniquely replace a non-passing original with the identical passing occurrence in another execution.'
            }
            $closures.Add([pscustomobject]@{ from=$original; to=$replacement })
        }
        foreach ($case in $cases) {
            if ($case.outcome -cne 'passed' -and !$closed.Contains((Get-K8sOriginalCaseKey @($case.reportPath,$case.className,$case.name,[string]$case.occurrence)))) {
                throw "Unclosed original testcase: $($case.reportPath)/$($case.className)/$($case.name)#$($case.occurrence) [$($case.outcome)]"
            }
        }
        $suiteNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $shortNames = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($report in $reports) {
            $null = $suiteNames.Add($report.suiteName)
            $short = $report.suiteName.Split('.')[-1]
            if (!$shortNames.ContainsKey($short)) { $shortNames.Add($short,[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)) }
            $null = $shortNames[$short].Add($report.suiteName)
        }
        $bindings = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
        $boundSuites = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($required in $RequiredSuites) {
            if ([string]::IsNullOrWhiteSpace($required) -or $bindings.ContainsKey($required)) { throw 'Empty or duplicate required suite contract.' }
            $matches = @(if ($required.Contains('.')) { $required | Where-Object { $suiteNames.Contains($_) } }
                elseif ($shortNames.ContainsKey($required)) { $shortNames[$required] })
            if ($matches.Count -ne 1 -or !$coveredSuites.Contains($matches[0])) {
                throw "Required suite missing, ambiguous or not passed: $required"
            }
            if (!$boundSuites.Add($matches[0])) { throw "Duplicate required suite binding: $required" }
            $bindings.Add($required,$matches[0])
        }
        foreach ($key in $RequiredMethods.Keys) {
            if (!$bindings.ContainsKey([string]$key)) { throw "Method contract has no required suite: $key" }
            $methods = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($method in $RequiredMethods[$key]) {
                if ($method -isnot [string] -or [string]::IsNullOrWhiteSpace($method) -or !$methods.Add($method)) { throw "Invalid or duplicate method contract: $key" }
                if (!$coveredMethods.Contains((Get-K8sOriginalCaseKey @($bindings[$key],$method)))) {
                    throw "Required original method absent or not passed: $key/$method"
                }
            }
        }
        return [pscustomobject]@{
            schemaVersion='cachedb.k8s-original-case-provenance/v1'; obligationId=$obligation; indexSha256=$indexHash
            qualificationCompleted=$true; allSelectedMethodsPassed=$true; semanticCoverageQualified=$true
            requiredContractsChecked=($bindings.Count -gt 0)
            records=$reports.ToArray(); reports=$reports.ToArray(); cases=$cases.ToArray(); closures=$closures.ToArray()
            closedFailures=@($closures.ToArray() | Where-Object { $_.from.outcome -cin @('failure','error') })
            closedSkips=@($closures.ToArray() | Where-Object { $_.from.outcome -ceq 'skipped' })
            coveredCases=[object[]]$coveredByIdentity.Values; requiredSuiteBindings=$bindings; evidenceBytes=$totalBytes
        }
    } finally { $document.Dispose() }
}
