param(
    [Parameter(Mandatory = $true)]
    [string]$EntryId,
    [string]$BaseUrl = "http://127.0.0.1:8090/cachedb-admin"
)

$uri = "$BaseUrl/api/projection-refresh/replay?entryId=$([uri]::EscapeDataString($EntryId))"
Invoke-RestMethod -Uri $uri -Method Post -Headers @{
    "Cache-Control" = "no-cache"
    "Pragma" = "no-cache"
}
