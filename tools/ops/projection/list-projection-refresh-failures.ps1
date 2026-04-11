param(
    [string]$BaseUrl = "http://127.0.0.1:8090/cachedb-admin",
    [int]$Limit = 20
)

$uri = "$BaseUrl/api/projection-refresh/failed?limit=$Limit"
Invoke-RestMethod -Uri $uri -Method Get -Headers @{
    "Cache-Control" = "no-cache"
    "Pragma" = "no-cache"
}
