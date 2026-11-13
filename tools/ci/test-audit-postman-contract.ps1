#requires -Version 7.3
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$target = [IO.Path]::GetFullPath((Join-Path $root 'target')) + [IO.Path]::DirectorySeparatorChar
$fixture = Join-Path $target ('audit-contract-tests-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixture
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Json($Value, [string]$Path) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 40), $utf8)
}
function Synthetic-Report([string]$CollectionPath) {
    $collection = Get-Content -LiteralPath $CollectionPath -Raw | ConvertFrom-Json -AsHashtable -Depth 40
    $executions = @(); $total = 0
    foreach ($item in $collection.item) {
        $script = @($item.event | Where-Object listen -eq 'test').script.exec -join [char]10
        $titles = @([regex]::Matches($script, "pm\.test\('([^']+)'") | ForEach-Object { $_.Groups[1].Value })
        $status = [regex]::Match($script, "pm\.test\('HTTP (\d{3})'")
        if (!$status.Success -or !$titles.Count) { throw 'Saved audit request has no explicit status/assertion.' }
        $assertions = @($titles | ForEach-Object { @{assertion=$_;skipped=$false} })
        $total += $assertions.Count
        $executions += @{item=@{name=$item.name};response=@{code=[int]$status.Groups[1].Value};assertions=$assertions}
    }
    return @{collection=$collection;run=@{failures=@();executions=$executions;stats=@{
        requests=@{failed=0;total=42};prerequestScripts=@{failed=0};testScripts=@{failed=0}
        assertions=@{failed=0;total=$total}
    }}}
}
try {
    foreach ($provider in @('postgresql','mssql','oracle')) {
        $sample = Join-Path $root "sample-cache-database-$provider"
        $collection = Join-Path $sample "postman/cache-database-$provider-audit.postman_collection.json"
        $validator = Join-Path $sample 'scripts/assert-postman-execution.ps1'
        $reportPath = Join-Path $fixture "$provider-report.json"
        Write-Json (Synthetic-Report $collection) $reportPath
        & $validator -CollectionPath $collection -ReportPath $reportPath -Kind Audit
        foreach ($mutation in @('all-skipped','missing-one','duplicate-name','extra-duplicate','missing-response','wrong-status',
                'skipped-assertion','missing-title','failed-script','bad-stats','changed-gate','changed-body')) {
            $report = Synthetic-Report $collection
            switch ($mutation) {
                'all-skipped' { $report.run.executions = @() }
                'missing-one' { $report.run.executions = @($report.run.executions | Select-Object -First 41) }
                'duplicate-name' { $report.run.executions[-1].item.name = $report.run.executions[0].item.name }
                'extra-duplicate' {
                    $report.run.executions += $report.run.executions[0]
                    $report.run.stats.requests.total = 43
                }
                'missing-response' { [void]$report.run.executions[0].Remove('response') }
                'wrong-status' { $report.run.executions[0].response.code = 500 }
                'skipped-assertion' { $report.run.executions[0].assertions[0].skipped = $true }
                'missing-title' { $report.run.executions[0].assertions = @($report.run.executions[0].assertions | Select-Object -First 1) }
                'failed-script' { $report.run.failures = @(@{error=@{message='synthetic script error'}}) }
                'bad-stats' { $report.run.stats.assertions.failed = 1 }
                'changed-gate' { $report.collection.event[0].script.exec = @('pm.execution.skipRequest();') }
                'changed-body' { $report.collection.item[0].request.body.raw = '{}' }
            }
            Write-Json $report $reportPath
            $rejected = $false
            try { & $validator -CollectionPath $collection -ReportPath $reportPath -Kind Audit }
            catch { $rejected = $true }
            if (!$rejected) { throw "Malformed audit evidence was accepted: $provider / $mutation" }
        }
    }
    Write-Host 'Audit evidence validator synthetic checks passed; this does not certify live API execution.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (!$resolved.StartsWith($target, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the target directory.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
