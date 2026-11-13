#requires -Version 7.3
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixture = Join-Path $root ("target/v019-postman-contract-tests-" + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixture
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Json($Value, [string]$Path) { [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 32), $utf8) }
function Read-Json([string]$Path) { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable -Depth 32 }
function Requests($Items) {
    foreach ($item in $Items) {
        if ($item.Contains('request')) { $item } elseif ($item.Contains('item')) { Requests $item.item }
    }
}
function Must-Reject([scriptblock]$Action) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (!$rejected) { throw 'A malformed or skipped execution contract was accepted.' }
}
$paths = @{}
$originals = @{}
try {
    foreach ($provider in @('postgresql','mssql','oracle')) {
        $relative = "sample-cache-database-$provider/postman/cache-database-$provider-sample.postman_collection.json"
        $path = Join-Path $fixture $relative
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path)
        $originals[$provider] = [IO.File]::ReadAllText((Join-Path $root $relative))
        [IO.File]::WriteAllText($path, $originals[$provider], $utf8)
        $paths[$provider] = $path
    }
    $sync = Join-Path $root 'tools/samples/sync-business-collection-flow.ps1'
    & $sync -RepositoryRoot $fixture
    & $sync -RepositoryRoot $fixture -Check
    foreach ($provider in $paths.Keys) {
        $before = $originals[$provider] | ConvertFrom-Json -AsHashtable -Depth 32
        $after = Read-Json $paths[$provider]
        if (($before.item | ConvertTo-Json -Depth 32 -Compress) -cne ($after.item | ConvertTo-Json -Depth 32 -Compress)) {
            throw 'Normalization changed a legacy request or folder 07.'
        }
        $sequence = @($after.variable | Where-Object { $_.key -eq 'businessSequence' })[0].value | ConvertFrom-Json
        if (@($sequence).Count -ne 61 -or @(Requests $after.item).Count -ne 84) { throw 'Normalization mixed the legacy and optional sequences.' }
    }
    foreach ($mutation in @('drop07','missing-new','duplicate-new','changed-legacy-sequence')) {
        $bad = $originals.postgresql | ConvertFrom-Json -AsHashtable -Depth 32
        $folder = @($bad.item | Where-Object { $_.name -eq '07 Method Cache and Async' })[0]
        switch ($mutation) {
            'drop07' { $bad.item = @($bad.item | Where-Object { $_.name -ne '07 Method Cache and Async' }) }
            'missing-new' { $folder.item = @($folder.item | Select-Object -First 22) }
            'duplicate-new' { $folder.item[1].name = $folder.item[0].name }
            'changed-legacy-sequence' { (@($bad.variable | Where-Object { $_.key -eq 'businessSequence' })[0]).value = '[]' }
        }
        Write-Json $bad $paths.postgresql
        Must-Reject { & $sync -RepositoryRoot $fixture -Check }
        [IO.File]::WriteAllText($paths.postgresql, $originals.postgresql, $utf8)
    }
    $validator = Join-Path $root 'sample-cache-database-postgresql/scripts/assert-postman-execution.ps1'
    $reportPath = Join-Path $fixture 'synthetic-report.json'
    function Synthetic-Report {
        $collection = Read-Json $paths.postgresql
        $executions = @()
        foreach ($item in @(Requests $collection.item)) {
            $titles = @()
            if ($item.Contains('event')) {
                $script = (@($item.event | Where-Object { $_.listen -eq 'test' }).script.exec) -join [char]10
                $titles = @([regex]::Matches($script, "pm\.test\('([^']+)'") | ForEach-Object { $_.Groups[1].Value })
            }
            if ($item.name -eq 'Latest Background Job Status (repeat until COMPLETED)') {
                $titles = @('Job status is available','Job completed before a hot read')
            }
            $assertions = @(@{assertion='Expected successful HTTP response: '+$item.name;skipped=$false})
            foreach ($title in $titles) { $assertions += @{assertion=$title;skipped=$false} }
            $code = 200
            if ($item.name.StartsWith('Cache API - ')) {
                if ($item.request.method -eq 'DELETE') { $code=204 } elseif ($item.request.method -eq 'PUT') { $code=202 }
            } elseif ($item.name -eq 'Reject Incomplete Absolute Stock Values') { $code=400 }
            $executions += @{item=@{name=$item.name};response=@{code=$code};assertions=$assertions}
        }
        return @{collection=$collection;run=@{
            failures=@();executions=$executions
            stats=@{requests=@{failed=0;total=84};prerequestScripts=@{failed=0};testScripts=@{failed=0};assertions=@{failed=0;total=500}}
        }}
    }
    Write-Json (Synthetic-Report) $reportPath
    & $validator -CollectionPath $paths.postgresql -ReportPath $reportPath -Kind Business
    function Component-Report {
        $report = Synthetic-Report
        foreach ($item in @(Requests $report.collection.item)) {
            $match = [regex]::Match($item.request.url, '^(\{\{(?:baseUrl|peerBaseUrl)\}\})/([^?]*)(?:\?(.*))?$')
            if (!$match.Success) { throw 'Unexpected saved URL in the component fixture.' }
            $query = @()
            if ($match.Groups[3].Success) {
                foreach ($pair in $match.Groups[3].Value.Split('&')) {
                    $parts = $pair.Split('=', 2)
                    $query += @{key=$parts[0];value=$parts[1]}
                }
            }
            $item.request.url = @{host=@($match.Groups[1].Value);path=@($match.Groups[2].Value.Split('/'));query=$query;variable=@()}
        }
        return $report
    }
    Write-Json (Component-Report) $reportPath
    foreach ($provider in @('postgresql','mssql','oracle')) {
        & (Join-Path $root "sample-cache-database-$provider/scripts/assert-postman-execution.ps1") -CollectionPath $paths.postgresql -ReportPath $reportPath -Kind Business
    }
    foreach ($mutation in @('host','path','query','disabled','raw-conflict','unknown-component')) {
        $report = Component-Report
        $requests = @(Requests $report.collection.item)
        $url = $requests[0].request.url
        switch ($mutation) {
            'host' { $url.host = @('{{peerBaseUrl}}') }
            'path' { $url.path[0] = 'changed' }
            'query' { $url.query = @(@{key='unexpected';value='true'}) }
            'disabled' { $url.query = @(@{key='unexpected';value='true';disabled=$true}) }
            'raw-conflict' { $url.raw = '{{baseUrl}}/other' }
            'unknown-component' { $url.protocol = 'https' }
        }
        Write-Json $report $reportPath
        Must-Reject { & $validator -CollectionPath $paths.postgresql -ReportPath $reportPath -Kind Business }
    }
    foreach ($mutation in @('missing-new-execution','skipped','missing-response','failed-assertion','script-failure','missing-terminal','bad-stats','wrong-script')) {
        $report = Synthetic-Report
        switch ($mutation) {
            'missing-new-execution' { $report.run.executions = @($report.run.executions | Where-Object { !$_.item.name.StartsWith('Cache API - ') }) }
            'skipped' { $report.run.executions[0].assertions[0].skipped = $true }
            'missing-response' { [void]$report.run.executions[0].Remove('response') }
            'failed-assertion' { $report.run.executions[0].assertions[0].error = @{message='synthetic failure'} }
            'script-failure' { $report.run.failures = @(@{error=@{message='synthetic script failure'}}) }
            'missing-terminal' {
                $entry = @($report.run.executions | Where-Object { $_.item.name -eq 'Cache API - Read Refreshed Quote' })[0]
                $entry.assertions = @($entry.assertions | Where-Object { $_.assertion -ne 'Notification-visible method result' })
            }
            'bad-stats' { $report.run.stats.testScripts.failed=1 }
            'wrong-script' { $report.collection.item[-1].event[0].script.exec = @('pm.execution.skipRequest();') }
        }
        Write-Json $report $reportPath
        Must-Reject { & $validator -CollectionPath $paths.postgresql -ReportPath $reportPath -Kind Business }
    }
    $savedSql = $env:CACHEDB_TEST_DATASOURCE_URL
    $savedRedis = $env:CACHEDB_TEST_REDIS_URI
    $redisGateChecks = 0
    try {
        foreach ($provider in @('postgresql','mssql','oracle')) {
            $wrapper = Join-Path $root "sample-cache-database-$provider/scripts/test-tenant-purge-postman.ps1"
            $tokens = $null; $errors = $null
            $ast = [Management.Automation.Language.Parser]::ParseFile($wrapper, [ref]$tokens, [ref]$errors)
            if (@($errors).Count -ne 0) { throw 'Purge wrapper must parse.' }
            $checks = @($ast.EndBlock.Statements | Where-Object {
                $_ -is [Management.Automation.Language.IfStatementAst] -and
                    $_.Extent.Text.Contains('$env:CACHEDB_TEST_REDIS_URI')
            })
            if ($checks.Count -ne 1) { throw 'Exactly one native fixture URI guard required.' }
            # Execute only the real parameter binding and URI guard; no wrapper body or network/process work.
            $guard = [scriptblock]::Create($ast.ParamBlock.Extent.Text + [Environment]::NewLine + $checks[0].Extent.Text)
            $env:CACHEDB_TEST_DATASOURCE_URL = switch ($provider) {
                'postgresql' { 'jdbc:postgresql://postgres.cachedb-v019-validation.svc.cluster.local:5432/fresh' }
                'mssql' { 'jdbc:sqlserver://mssql.cachedb-v019-validation.svc.cluster.local:1433;databaseName=fresh' }
                'oracle' { 'jdbc:oracle:thin:@//oracle.cachedb-v019-validation.svc.cluster.local:1521/FREEPDB1' }
            }
            $arguments = @{BaseUrl=[uri]'http://companion.cachedb-v019-validation.svc.cluster.local:8080/';
                RunId=('a' * 32);ConfirmDisposableDemo=$true}
            $env:CACHEDB_TEST_REDIS_URI = 'redis://redis.cachedb-v019-validation.svc.cluster.local:6379/0'
            & $guard @arguments; $redisGateChecks++
            foreach ($service in @('s529-private-redis','a',('r' * 63))) {
                $env:CACHEDB_TEST_REDIS_URI = "redis://${service}.cachedb-v019-validation.svc.cluster.local:6379/12"
                & $guard @arguments -RedisServiceName $service; $redisGateChecks++
            }
            foreach ($badName in @('','Redis','-redis','redis-','redis.private','redis/other','redis:6379',
                    'redis_user',('r' * 64),"redis`n",'redis@host','redis*')) {
                $env:CACHEDB_TEST_REDIS_URI = "redis://${badName}.cachedb-v019-validation.svc.cluster.local:6379/0"
                Must-Reject { & $guard @arguments -RedisServiceName $badName }; $redisGateChecks++
            }
            $hostName = 's529-private-redis.cachedb-v019-validation.svc.cluster.local'
            foreach ($badUri in @('redis://redis.cachedb-v019-validation.svc.cluster.local:6379/0',
                    'redis://s529-private-redis.other.svc.cluster.local:6379/0',
                    "redis://${hostName}.evil:6379/0","redis://${hostName}:6380/0",
                    "redis://user:password@${hostName}:6379/0","rediss://${hostName}:6379/0",
                    "redis://${hostName}:6379/01","redis://${hostName}:6379/0?x=1",
                    "redis://${hostName}:6379/0#extra","redis://${hostName}:6379/0`n")) {
                $env:CACHEDB_TEST_REDIS_URI = $badUri
                Must-Reject { & $guard @arguments -RedisServiceName 's529-private-redis' }; $redisGateChecks++
            }
            $env:CACHEDB_TEST_REDIS_URI = "redis://${hostName}:6379/0"
            $env:CACHEDB_TEST_DATASOURCE_URL = 'jdbc:postgresql://foreign.other.svc.cluster.local:5432/fresh'
            Must-Reject { & $guard @arguments -RedisServiceName 's529-private-redis' }; $redisGateChecks++
        }
    } finally {
        $env:CACHEDB_TEST_DATASOURCE_URL = $savedSql
        $env:CACHEDB_TEST_REDIS_URI = $savedRedis
    }
    if ($redisGateChecks -ne 81) { throw 'Complete three-provider Redis Service gate matrix required.' }
    Write-Host 'Postman contract self-checks covered normalization, missing/skipped/failed evidence, and 81 Redis Service gate cases. Synthetic fixtures are not API execution evidence.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $allowed = [IO.Path]::GetFullPath((Join-Path $root 'target')) + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notmatch '^v019-postman-contract-tests-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside the contract fixture directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
