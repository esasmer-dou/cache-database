param(
    [string] $PostgresqlCollection = "sample-cache-database-postgresql/postman/cache-database-postgresql-sample.postman_collection.json",
    [string] $MssqlCollection = "sample-cache-database-mssql/postman/cache-database-mssql-sample.postman_collection.json",
    [string] $OracleCollection = "sample-cache-database-oracle/postman/cache-database-oracle-sample.postman_collection.json",
    [string] $SummaryPath = "target/postman-collection-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Read-Collection {
    param([string] $RelativePath)

    $path = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing Postman collection: $path"
    }
    if ((Get-Item -LiteralPath $path).Length -gt 1048576) { throw "Oversized Postman collection: $path" }
    try {
        return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    } catch {
        throw "Invalid Postman JSON in $path`: $($_.Exception.Message)"
    }
}

function Get-Requests {
    param(
        [object[]] $Items,
        [string] $Folder = "",
        [int] $Depth = 0
    )

    if ($Depth -gt 8) { throw 'Postman nesting exceeds eight levels.' }

    $requests = [System.Collections.Generic.List[object]]::new()
    foreach ($item in @($Items)) {
        $path = if ([string]::IsNullOrWhiteSpace($Folder)) { $item.name } else { "$Folder / $($item.name)" }
        $requestProperty = $item.PSObject.Properties["request"]
        $childrenProperty = $item.PSObject.Properties["item"]
        $eventProperty = $item.PSObject.Properties["event"]
        if ($null -ne $requestProperty) {
            $request = $requestProperty.Value
            $requests.Add([pscustomobject]@{
                Name = [string] $item.name
                Path = $path
                Folder = $Folder
                Method = [string] $request.method
                Url = [string] $request.url
                Event = if ($null -eq $eventProperty) { @() } else { @($eventProperty.Value) }
            })
        }
        if ($null -ne $childrenProperty) {
            foreach ($nested in @(Get-Requests -Items @($childrenProperty.Value) -Folder $path -Depth ($Depth + 1))) {
                $requests.Add($nested)
            }
        }
    }
    if ($requests.Count -gt 256) { throw 'Postman collection exceeds 256 requests.' }
    return $requests.ToArray()
}

function Assert-CollectionContract {
    param(
        [object] $Collection,
        [string] $ExpectedBaseUrl,
        [string] $Provider
    )

    if ($Collection.info.schema -ne "https://schema.getpostman.com/json/collection/v2.1.0/collection.json") {
        throw "$Provider collection must use Postman schema v2.1.0."
    }

    $baseUrl = @($Collection.variable | Where-Object { $_.key -eq "baseUrl" })
    if ($baseUrl.Count -ne 1 -or $baseUrl[0].value -ne $ExpectedBaseUrl) {
        throw "$Provider collection must define baseUrl=$ExpectedBaseUrl exactly once."
    }

    $collectionTests = (@($Collection.event) | ForEach-Object { @($_.script.exec) }) -join "`n"
    if ($collectionTests -notmatch "warmJobId" -or $collectionTests -notmatch "jobId") {
        throw "$Provider collection must capture the distributed job ID from 202 responses."
    }

    $requests = @(Get-Requests -Items @($Collection.item))
    $optional = @($Collection.item | Where-Object { $_.name -ceq '07 Method Cache and Async' })
    if ($optional.Count -ne 1 -or $requests.Count -ne 84) {
        throw "$Provider requires 84 business requests: 61 legacy plus 23 method-cache/async."
    }
    $new = @(Get-Requests -Items @($optional[0].item))
    $legacy = @(Get-Requests -Items @($Collection.item | Where-Object { $_.name -cne '07 Method Cache and Async' }))
    if ($new.Count -ne 23 -or $legacy.Count -ne 61) { throw "$Provider request groups are incomplete." }
    foreach ($request in $new) {
        if (!(($request.Event.script.exec -join [char]10).Contains('pm.test'))) {
            throw "$Provider request lacks assertions: $($request.Name)"
        }
    }
    $gate = $optional[0].event.script.exec -join [char]10
    foreach ($needle in @('cacheApiEnabled', 'cacheApiCertification', 'throw new Error')) {
        if (!$gate.Contains($needle)) { throw "$Provider feature gate lacks $needle" }
    }
    $duplicateNames = @($requests | Group-Object Name | Where-Object Count -gt 1)
    if ($duplicateNames.Count -gt 0) {
        throw "$Provider collection contains duplicate request names: $($duplicateNames.Name -join ', ')"
    }

    $requiredWarmUrls = @(
        "{{baseUrl}}/api/warm/customers/active?limit=100",
        "{{baseUrl}}/api/warm/orders/customer/{{customerId}}?limit=100&projectionOnly=true",
        "{{baseUrl}}/api/warm/orders/{{orderId}}/lines?limit=100",
        "{{baseUrl}}/api/warm/orders/high-value?minimumAmount=500&limit=100",
        "{{baseUrl}}/api/warm/orders/highlighted?minimumPriorityScore=60&limit=100",
        "{{baseUrl}}/api/warm/products/active?category=electronics&limit=100&projectionOnly=true",
        "{{baseUrl}}/api/warm/products/low-stock?limit=100",
        "{{baseUrl}}/api/warm/tickets/open?limit=50",
        "{{baseUrl}}/api/warm/shipments/active?limit=100&projectionOnly=true",
        "{{baseUrl}}/api/warm/shipments/customer/{{customerId}}?limit=100",
        "{{baseUrl}}/api/warm/shipments/exceptions?limit=100",
        "{{baseUrl}}/api/warm/shipments/{{shipmentId}}/events?limit=100",
        "{{baseUrl}}/api/warm/reports/live?limit=50",
        "{{baseUrl}}/api/warm/reports/type/ORDER_SUMMARY?limit=50",
        "{{baseUrl}}/api/warm/audit/security?limit=50"
    )
    foreach ($url in $requiredWarmUrls) {
        if (-not ($requests | Where-Object { $_.Method -eq "POST" -and $_.Url -eq $url })) {
            throw "$Provider collection is missing warm request: $url"
        }
    }

    $status = @($requests | Where-Object { $_.Url -eq "{{baseUrl}}/api/warm/jobs/{{warmJobId}}" })
    if ($status.Count -ne 1) {
        throw "$Provider collection must contain exactly one latest-job status request."
    }
    $statusTests = ($status[0].Event | ForEach-Object { @($_.script.exec) }) -join "`n"
    if ($statusTests -notmatch "COMPLETED") {
        throw "$Provider latest-job request must fail until the job reaches COMPLETED."
    }

    $orderedPairs = @(
        @("Warm Active Customers", "Active Customers"),
        @("Warm Customer Orders Projection", "Customer Order Timeline Projection"),
        @("Warm Order Lines", "Order Detail With Line Preview"),
        @("Warm High Value Orders", "High Value Orders Projection"),
        @("Warm Active Products Projection", "Active Products Projection"),
        @("Warm Low Stock Products", "Low Stock Products Projection"),
        @("Warm Open Tickets", "Open Tickets"),
        @("Warm Active Shipments Projection", "Active Shipments Projection"),
        @("Warm Shipment Exceptions", "Shipment Exceptions Projection"),
        @("Warm Customer Shipments", "Customer Shipments Projection"),
        @("Warm Shipment Events", "Shipment Events"),
        @("Warm Live Report Jobs", "Live Report Jobs"),
        @("Warm Report Jobs By Type", "Report Jobs By Type"),
        @("Warm Security Audit Events", "Security Audit Events From Active Set")
    )
    $requestNames = @($requests | ForEach-Object Name)
    foreach ($pair in $orderedPairs) {
        $warmIndex = [Array]::IndexOf($requestNames, $pair[0])
        $readIndex = [Array]::IndexOf($requestNames, $pair[1])
        if ($warmIndex -lt 0 -or $readIndex -lt 0 -or $warmIndex -ge $readIndex) {
            throw "$Provider collection must place '$($pair[0])' before '$($pair[1])'."
        }
    }

    return $requests
}

$postgresql = Read-Collection -RelativePath $PostgresqlCollection
$mssql = Read-Collection -RelativePath $MssqlCollection
$oracle = Read-Collection -RelativePath $OracleCollection
$postgresqlRequests = @(Assert-CollectionContract -Collection $postgresql -ExpectedBaseUrl "http://127.0.0.1:8091" -Provider "PostgreSQL")
$mssqlRequests = @(Assert-CollectionContract -Collection $mssql -ExpectedBaseUrl "http://127.0.0.1:8092" -Provider "MSSQL")
$oracleRequests = @(Assert-CollectionContract -Collection $oracle -ExpectedBaseUrl "http://127.0.0.1:8093" -Provider "Oracle")

$postgresqlContract = @($postgresqlRequests | ForEach-Object { "$($_.Path)|$($_.Method)|$($_.Url)" })
$mssqlContract = @($mssqlRequests | ForEach-Object { "$($_.Path)|$($_.Method)|$($_.Url)" })
if (($postgresqlContract -join "`n") -cne ($mssqlContract -join "`n")) {
    throw "PostgreSQL and SQL Server Postman request contracts differ."
}
$oracleContract = @($oracleRequests | ForEach-Object { "$($_.Path)|$($_.Method)|$($_.Url)" })
if (($postgresqlContract -join "`n") -cne ($oracleContract -join "`n")) {
    throw "PostgreSQL and Oracle Postman request contracts differ."
}

$catalogContracts = @{}
foreach ($provider in @('postgresql', 'mssql', 'oracle')) {
    $path = "sample-cache-database-$provider/postman/cache-database-$provider-catalog.postman_collection.json"
    $catalog = Read-Collection -RelativePath $path
    $requests = @(Get-Requests -Items @($catalog.item))
    if ($requests.Count -ne 23) { throw "$provider catalog collection must maintain the documented 23-request flow." }
    foreach ($request in $requests) {
        $scripts = ($request.Event | ForEach-Object { @($_.script.exec) }) -join "`n"
        if ($scripts -notmatch 'pm\.test') { throw "Missing catalog assertion: $($request.Name)" }
    }
    $catalogContracts[$provider] = ($requests | ForEach-Object { "$($_.Path)|$($_.Method)|$($_.Url)" }) -join "`n"
}
if ($catalogContracts.postgresql -cne $catalogContracts.mssql -or
    $catalogContracts.postgresql -cne $catalogContracts.oracle) {
    throw 'Provider catalog Postman contracts differ.'
}
$rangeContracts = @{}
foreach ($provider in @('postgresql', 'mssql', 'oracle')) {
    $path = "sample-cache-database-$provider/postman/cache-database-$provider-range-data.postman_collection.json"
    $range = Read-Collection -RelativePath $path
    $requests = @(Get-Requests -Items @($range.item))
    if ($requests.Count -ne 37 -or @($requests.Name | Sort-Object -Unique).Count -ne 37) {
        throw "$provider requires all 37 distinct range/keyset and Spring Data requests."
    }
    if ($range.info.schema -ne 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json') {
        throw 'Range/data collections require schema v2.1.0.'
    }
    $port = @{postgresql=8091;mssql=8092;oracle=8093}[$provider]
    $base = @($range.variable | Where-Object key -eq 'baseUrl')
    if ($base.Count -ne 1 -or $base[0].value -cne "http://127.0.0.1:$port") {
        throw 'Range/data base URL differs from the provider demo port.'
    }
    $gate = @($range.event.script.exec) -join [char]10
    foreach ($needle in @('rangeDataEnabled','rangeDataCertification','throw new Error')) {
        if (!$gate.Contains($needle)) { throw "Missing range/data certification gate: $needle" }
    }
    foreach ($request in $requests) {
        $scripts = @($request.Event.script.exec) -join [char]10
        if ($scripts -notmatch "pm\.test\('HTTP [1-5][0-9]{2}'") {
            throw "Range/data request lacks its exact HTTP assertion: $($request.Name)"
        }
    }
    $rangeContracts[$provider] = ($range.item | ConvertTo-Json -Depth 32 -Compress) + $gate
}
if ($rangeContracts.postgresql -cne $rangeContracts.mssql -or $rangeContracts.postgresql -cne $rangeContracts.oracle) {
    throw 'Provider range/data request, body, authentication or assertion contracts differ.'
}

& (Join-Path $repoRoot 'tools/samples/sync-business-collection-flow.ps1') -Check

$summary = @"
# Postman Collection Validation

- PostgreSQL requests: $($postgresqlRequests.Count)
- MSSQL requests: $($mssqlRequests.Count)
- Oracle requests: $($oracleRequests.Count)
- Business groups per provider: 61 legacy + 23 method-cache/async = 84
- Optional certification gate: present (structural only, execution is separate)
- Required bounded warm routes: 15
- Provider request contract parity: passed
- Warm-before-hot ordering: passed
- Job completion assertion: passed
- Range/data requests per provider: 37 (structural; execution is separate)
- Catalog requests per provider: 23
- Catalog assertion presence and provider parity: passed (structural; live runs are separate)
"@
$summaryFullPath = Join-Path $repoRoot $SummaryPath
$summaryDirectory = Split-Path -Parent $summaryFullPath
if (-not (Test-Path -LiteralPath $summaryDirectory)) {
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
}
[System.IO.File]::WriteAllText($summaryFullPath, $summary.Trim() + "`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "Validated $($postgresqlRequests.Count) business requests (61 legacy + 23 method-cache/async), 23 catalog and 37 range/data requests for each provider."
Write-Host "Summary: $summaryFullPath"
