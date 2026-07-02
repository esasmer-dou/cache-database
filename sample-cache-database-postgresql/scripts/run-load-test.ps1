param(
    [string] $BaseUrl = "http://127.0.0.1:8091",
    [int] $SeedCustomers = 10,
    [int] $OrdersPerCustomer = 20,
    [int] $LinesPerOrder = 4,
    [int] $WarmCustomers = 10,
    [int] $WarmLimit = 100,
    [int] $Concurrency = 8,
    [int] $DurationSeconds = 20,
    [double] $MaxP95Millis = 250.0,
    [ValidateSet("hot-timeline", "mixed")]
    [string] $RouteProfile = "hot-timeline",
    [switch] $SkipSeed,
    [switch] $SkipWarm
)

$ErrorActionPreference = "Stop"

function Invoke-SamplePost {
    param([string] $Path)
    Invoke-RestMethod -Method Post -Uri ($BaseUrl.TrimEnd("/") + $Path) | Out-Null
}

function Wait-SampleReady {
    param(
        [string] $Phase,
        [int] $TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = $null
    do {
        try {
            $ready = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd("/") + "/api/health/ready") -TimeoutSec 5
            $lastStatus = $ready
            $deadLetters = if ($null -ne $ready.deadLetterCount) { [int64] $ready.deadLetterCount } else { 0 }
            $pendingRecovery = if ($null -ne $ready.pendingRecoveryCount) { [int64] $ready.pendingRecoveryCount } else { 0 }
            if ($ready.status -eq "UP" -and $ready.writeBehindHealthy -and $deadLetters -eq 0 -and $pendingRecovery -eq 0) {
                return
            }
        } catch {
            $lastStatus = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Sample was not ready after $Phase. Last health: $($lastStatus | ConvertTo-Json -Compress)"
}

if (-not $SkipSeed) {
    Write-Host "Seeding sample data..."
    Invoke-SamplePost "/api/demo/seed?customers=$SeedCustomers&ordersPerCustomer=$OrdersPerCustomer&linesPerOrder=$LinesPerOrder"
    Write-Host "Waiting for write-behind health after seed..."
    Wait-SampleReady "seed"
}

if (-not $SkipWarm) {
    Write-Host "Warming customer order windows through CacheWarmPlan..."
    1..([Math]::Min($WarmCustomers, $SeedCustomers)) | ForEach-Object {
        Invoke-SamplePost "/api/warm/orders/customer/${_}?limit=$WarmLimit&dryRun=false"
    }
    Wait-SampleReady "warm"
}

$routes = if ($RouteProfile -eq "mixed") {
    @(
        "/api/dashboard/commerce?limit=25",
        "/api/dashboard/operations?limit=25",
        "/api/customers/{0}/orders?limit=20",
        "/api/customers/{0}?orderPreview=5",
        "/api/orders/{1}?linePreview=4"
    )
} else {
    @(
        "/api/customers/{0}/orders?limit=20",
        "/api/customers/{0}/orders?limit=50"
    )
}

$worker = {
    param(
        [string] $WorkerBaseUrl,
        [int] $WorkerDurationSeconds,
        [int] $WorkerCustomerCount,
        [string[]] $WorkerRoutes
    )

    Add-Type -AssemblyName System.Net.Http
    $workerDeadlineTicks = [DateTime]::UtcNow.AddSeconds($WorkerDurationSeconds).Ticks
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    $random = [Random]::new()
    $latencies = [System.Collections.Generic.List[double]]::new()
    $ok = 0
    $fail = 0

    while ([DateTime]::UtcNow.Ticks -lt $workerDeadlineTicks) {
        $customerId = $random.Next(1, $WorkerCustomerCount + 1)
        $orderIndex = $random.Next(1, 20)
        $orderId = ($customerId * 10000) + $orderIndex
        $route = $WorkerRoutes[$random.Next(0, $WorkerRoutes.Count)]
        $path = [string]::Format($route, $customerId, $orderId)
        $url = $WorkerBaseUrl.TrimEnd("/") + $path
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = $client.GetAsync($url).GetAwaiter().GetResult()
            $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | Out-Null
            if ($response.IsSuccessStatusCode) {
                $ok++
            } else {
                $fail++
            }
        } catch {
            $fail++
        } finally {
            $sw.Stop()
            $latencies.Add($sw.Elapsed.TotalMilliseconds)
        }
    }

    $client.Dispose()
    [pscustomobject]@{
        Ok = $ok
        Fail = $fail
        Latencies = $latencies.ToArray()
    }
}

Write-Host "Running load test: profile=$RouteProfile concurrency=$Concurrency durationSeconds=$DurationSeconds"
$started = Get-Date
$jobs = 1..$Concurrency | ForEach-Object {
    Start-Job -ScriptBlock $worker -ArgumentList $BaseUrl, $DurationSeconds, $SeedCustomers, $routes
}
$results = $jobs | Receive-Job -Wait -AutoRemoveJob
$elapsedSeconds = [Math]::Max(1.0, ((Get-Date) - $started).TotalSeconds)
$ok = ($results | Measure-Object -Property Ok -Sum).Sum
$fail = ($results | Measure-Object -Property Fail -Sum).Sum
$latencies = @($results | ForEach-Object { $_.Latencies } | Sort-Object)
$count = [Math]::Max(1, $latencies.Count)
$avg = ($latencies | Measure-Object -Average).Average
$p95Index = [Math]::Min($count - 1, [Math]::Floor($count * 0.95))
$p95 = [double] $latencies[$p95Index]
$rps = [Math]::Round($ok / $elapsedSeconds, 2)

$summary = [pscustomobject]@{
    baseUrl = $BaseUrl
    ok = $ok
    fail = $fail
    routeProfile = $RouteProfile
    rps = $rps
    avgMillis = [Math]::Round($avg, 2)
    p95Millis = [Math]::Round($p95, 2)
    maxAllowedP95Millis = $MaxP95Millis
}

$summary | ConvertTo-Json

if ($fail -gt 0) {
    throw "Load test failed: $fail requests failed."
}
if ($p95 -gt $MaxP95Millis) {
    throw "Load test failed: p95=$([Math]::Round($p95, 2)) ms exceeded MaxP95Millis=$MaxP95Millis."
}
