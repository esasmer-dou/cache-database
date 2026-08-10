param(
    [string] $PostgresqlCollection = "sample-cache-database-postgresql/postman/cache-database-postgresql-sample.postman_collection.json",
    [string] $MssqlCollection = "sample-cache-database-mssql/postman/cache-database-mssql-sample.postman_collection.json",
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
    try {
        return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    } catch {
        throw "Invalid Postman JSON in $path`: $($_.Exception.Message)"
    }
}

function Get-Requests {
    param(
        [object[]] $Items,
        [string] $Folder = ""
    )

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
            foreach ($nested in @(Get-Requests -Items @($childrenProperty.Value) -Folder $path)) {
                $requests.Add($nested)
            }
        }
    }
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
$postgresqlRequests = @(Assert-CollectionContract -Collection $postgresql -ExpectedBaseUrl "http://127.0.0.1:8091" -Provider "PostgreSQL")
$mssqlRequests = @(Assert-CollectionContract -Collection $mssql -ExpectedBaseUrl "http://127.0.0.1:8092" -Provider "MSSQL")

$postgresqlContract = @($postgresqlRequests | ForEach-Object { "$($_.Path)|$($_.Method)|$($_.Url)" })
$mssqlContract = @($mssqlRequests | ForEach-Object { "$($_.Path)|$($_.Method)|$($_.Url)" })
if (($postgresqlContract -join "`n") -cne ($mssqlContract -join "`n")) {
    throw "PostgreSQL and MSSQL Postman request contracts differ."
}

$summary = @"
# Postman Collection Validation

- PostgreSQL requests: $($postgresqlRequests.Count)
- MSSQL requests: $($mssqlRequests.Count)
- Required bounded warm routes: 15
- Provider request contract parity: passed
- Warm-before-hot ordering: passed
- Job completion assertion: passed
"@
$summaryFullPath = Join-Path $repoRoot $SummaryPath
$summaryDirectory = Split-Path -Parent $summaryFullPath
if (-not (Test-Path -LiteralPath $summaryDirectory)) {
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
}
[System.IO.File]::WriteAllText($summaryFullPath, $summary.Trim() + "`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "Validated $($postgresqlRequests.Count) requests in each Postman collection."
Write-Host "Summary: $summaryFullPath"
