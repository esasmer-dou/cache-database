param([switch]$Check)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$poll = 'Latest Background Job Status (repeat until COMPLETED)'
function Get-Requests($items) {
    foreach ($item in $items) {
        if ($item.PSObject.Properties.Name -contains 'request') { $item }
        elseif ($item.PSObject.Properties.Name -contains 'item') { Get-Requests $item.item }
    }
}
foreach ($provider in @('postgresql','mssql','oracle')) {
    $path = Join-Path $root "sample-cache-database-$provider/postman/cache-database-$provider-sample.postman_collection.json"
    $collection = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $requests = @(Get-Requests $collection.item)
    $names = @($requests | ForEach-Object { $_.name })
    if ($names.Count -ne 61 -or @($names | Sort-Object -Unique).Count -ne $names.Count -or $poll -notin $names) {
        throw 'Business flow requires 61 uniquely named requests and one saved status request.'
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
    $expected = ($collection | ConvertTo-Json -Depth 100).Replace("`r`n","`n") + "`n"
    if ($Check) {
        $actual = [IO.File]::ReadAllText($path).Replace("`r`n","`n")
        if ($actual -ne $expected) { throw "Collection job flow is stale: $provider" }
    } else {
        [IO.File]::WriteAllText($path,$expected,[Text.UTF8Encoding]::new($false))
    }
}
Write-Output 'Business collection polling and HTTP assertions match all three providers.'
