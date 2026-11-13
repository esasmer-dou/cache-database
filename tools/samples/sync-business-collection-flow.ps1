param([switch]$Check, [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
Set-StrictMode -Version Latest
$optionalFolder = '07 Method Cache and Async'
$optionalNames = @(
    'Cache API - Start and Clear Tenant A',
    'Cache API - Clear Tenant B',
    'Cache API - Read Tenant A Quote',
    'Cache API - Read Tenant A Cache Hit',
    'Cache API - Read Tenant B Isolated Quote',
    'Cache API - Refresh Tenant A Quote',
    'Cache API - Read Refreshed Quote',
    'Cache API - Clear Only Tenant B',
    'Cache API - Tenant A Survives Tenant B Clear',
    'Cache API - Evict One Tenant A Quote',
    'Cache API - Read After Key Eviction',
    'Cache API - Clear Tenant A Generation',
    'Cache API - Read After Generation Clear',
    'Cache API - Read Nullable Method',
    'Cache API - Read Cached Null',
    'Cache API - Async Save With Actual Receipt',
    'Cache API - Async Hot Read With Causal Token',
    'Cache API - Explicit Async Source Read',
    'Cache API - Seed Tenant A Stock With Receipt',
    'Cache API - Reactive Tenant A Causal Read',
    'Cache API - Seed Tenant B Stock With Receipt',
    'Cache API - Reactive Tenant B Causal Read',
    'Cache API - Reactive Bounded Stock List'
)
$poll = 'Latest Background Job Status (repeat until COMPLETED)'
function Get-Requests($items, [int]$Depth = 0) {
    if ($Depth -gt 8) { throw 'Collection nesting exceeds eight levels.' }
    foreach ($item in $items) {
        if ($item.PSObject.Properties.Name -contains 'request') { $item }
        elseif ($item.PSObject.Properties.Name -contains 'item') { Get-Requests $item.item ($Depth + 1) }
    }
}
foreach ($provider in @('postgresql','mssql','oracle')) {
    $path = Join-Path $root "sample-cache-database-$provider/postman/cache-database-$provider-sample.postman_collection.json"
    if ((Get-Item -LiteralPath $path).Length -gt 1048576) { throw 'Collection exceeds 1 MiB.' }
    $collection = [IO.File]::ReadAllText($path, [Text.UTF8Encoding]::new($false,$true)) | ConvertFrom-Json
    $allRequests = @(Get-Requests $collection.item)
    $extensions = @($collection.item | Where-Object { $_.name -ceq $optionalFolder })
    if ($allRequests.Count -ne 84 -or $extensions.Count -ne 1 -or $collection.item[-1].name -cne $optionalFolder -or
            @($allRequests.name | Sort-Object -Unique).Count -ne 84) {
        throw 'Business collection requires 61 legacy plus 23 method-cache/async requests.'
    }
    $extension = $extensions[0]
    $extensionSnapshot = $extension | ConvertTo-Json -Depth 100 -Compress
    $newRequests = @(Get-Requests $extension.item)
    if (($newRequests.name -join [char]10) -cne ($optionalNames -join [char]10)) {
        throw 'Method-cache/async requests are missing, renamed or reordered.'
    }
    $gate = ($extension.event.script.exec) -join [char]10
    if (!$gate.Contains('cacheApiEnabled') -or !$gate.Contains('cacheApiCertification') -or
            !$gate.Contains('throw new Error') -or !$gate.Contains('skipRequest')) {
        throw 'The optional folder must reject certification opt-out and retain normal opt-out.'
    }
    $legacyItems = @($collection.item | Where-Object { $_.name -cne $optionalFolder })
    $requests = @(Get-Requests $legacyItems)
    $names = @($requests.name)
    if ($names.Count -ne 61 -or $poll -cnotin $names) {
        throw 'The legacy business flow must retain 61 requests and its saved polling request.'
    }
    $savedSequence = @($collection.variable | Where-Object { $_.key -ceq 'businessSequence' })
    if ($savedSequence.Count -ne 1 -or
            (($savedSequence[0].value | ConvertFrom-Json) -join [char]10) -cne ($names -join [char]10)) {
        throw 'Legacy businessSequence changed; do not derive it from all 84 requests.'
    }
    $sequence = $names | ConvertTo-Json -Compress
    $collection.variable = @($collection.variable | Where-Object { $_.key -ne 'businessSequence' }) +
        [pscustomobject]@{key='businessSequence';value=$sequence}
    $collection.event = @([pscustomobject]@{
        listen='test'; script=[pscustomobject]@{type='text/javascript';exec=@(
            "const pollName = '$poll';",
            "if (pm.info.requestName !== pollName) {",
            "  if (pm.info.requestName === 'Reject Incomplete Absolute Stock Values') {",
            "    pm.test('Incomplete absolute stock command is rejected', () => pm.response.to.have.status(400));",
            "  } else {",
            "    pm.test('Expected successful HTTP response: ' + pm.info.requestName, () => pm.expect(pm.response.code).to.be.within(200, 299));",
            "  }",
            "  if (pm.response.code === 202 && pm.response.json().jobId) {",
            "    const sequence = JSON.parse(pm.collectionVariables.get('businessSequence'));",
            "    const index = sequence.indexOf(pm.info.requestName);",
            "    const next = sequence[index + 1] === pollName ? sequence[index + 2] : sequence[index + 1];",
            "    pm.collectionVariables.set('businessResume', next || '');",
            "    pm.collectionVariables.set('warmJobId', pm.response.json().jobId);",
            "    pm.collectionVariables.set('businessPolls', 0);",
            "    pm.collectionVariables.set('businessDeadline', Date.now() + 30000);",
            "    pm.execution.setNextRequest(pollName);",
            "  }",
            "}"
        )}
    })
    $status = $requests | Where-Object name -EQ $poll
    $status.event = @([pscustomobject]@{
        listen='test';script=[pscustomobject]@{type='text/javascript';exec=@(
            "pm.test('Job status is available', () => pm.response.to.have.status(200));",
            "const body = pm.response.json();",
            "const count = Number(pm.collectionVariables.get('businessPolls') || 0) + 1;",
            "pm.collectionVariables.set('businessPolls', count);",
            "if (!['COMPLETED','FAILED','CANCELLED'].includes(body.status) && count < 150 && Date.now() < Number(pm.collectionVariables.get('businessDeadline'))) {",
            "  setTimeout(() => pm.execution.setNextRequest(pm.info.requestName), 200);",
            "} else {",
            "  pm.test('Job completed before a hot read', () => pm.expect(body.status).to.eql('COMPLETED'));",
            "  const next = pm.collectionVariables.get('businessResume');",
            "  pm.collectionVariables.unset('businessResume');",
            "  pm.execution.setNextRequest(body.status === 'COMPLETED' && next ? next : null);",
            "}"
        )}
    })
    $status.request.description = 'The runner polls this saved request after each accepted background job, for at most 30 seconds and 150 attempts. It then resumes the original sequence. Failed jobs stop the flow.'
    if (($extension | ConvertTo-Json -Depth 100 -Compress) -cne $extensionSnapshot) {
        throw 'Optional request contents must not be changed by legacy flow normalization.'
    }
    $expected = ($collection | ConvertTo-Json -Depth 100).Replace("`r`n","`n") + "`n"
    if ($Check) {
        $actual = [IO.File]::ReadAllText($path).Replace("`r`n","`n")
        if ($actual -ne $expected) { throw "Collection job flow is stale: $provider" }
    } else {
        [IO.File]::WriteAllText($path,$expected,[Text.UTF8Encoding]::new($false))
    }
}
Write-Output 'Business collection polling and HTTP assertions match all three providers.'
