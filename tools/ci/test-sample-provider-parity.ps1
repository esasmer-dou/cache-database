$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script = Join-Path $root 'tools/samples/sync-sample-provider.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($script, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Provider synchronizer parse errors.' }
$functions = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Convert-ProviderBoundJava'
}, $true))
if ($functions.Count -ne 1) { throw 'Missing/duplicate provider binding conversion.' }
. ([scriptblock]::Create($functions[0].Extent.Text))

function Assert-OrdinalEqual([string]$Expected, [string]$Actual, [string]$Name) {
    if (-not [string]::Equals($Expected, $Actual, [StringComparison]::Ordinal)) {
        throw "Provider binding regression: $Name"
    }
}
function Assert-Rejected([scriptblock]$Operation, [string]$Name) {
    try { $null = & $Operation } catch { return }
    throw "Provider binding accepted invalid resources: $Name"
}

$relative = 'src/purge-test/java/com/example/cachedb/purge/TenantPurgeContractTest.java'
$windowsRelative = $relative.Replace([char]47, [char]92)
$canonical = [IO.File]::ReadAllText((Join-Path $root ('sample-cache-database-postgresql/' + $relative)))
$collection = '"cache-database-postgresql-tenant-purge.postman_collection.json"'
$migration = '"com/reactor/cachedb/jdbc/migrations/postgres/"'
$securityAssertion = 'assertEquals(8, collection.path("item").size());'
if (-not $canonical.Contains($securityAssertion)) { throw 'Purge security assertion mutation anchor missing.' }
$dialects = @{ postgresql = 'postgres'; mssql = 'mssql'; oracle = 'oracle' }
$checked = 0
foreach ($provider in @('postgresql', 'mssql', 'oracle')) {
    $actual = [IO.File]::ReadAllText((Join-Path $root ("sample-cache-database-$provider/" + $relative)))
    $normalized = Convert-ProviderBoundJava $actual $windowsRelative $provider postgresql
    Assert-OrdinalEqual $canonical $normalized "$provider Windows-path normalized contract"
    $converted = Convert-ProviderBoundJava $canonical $relative postgresql $provider
    Assert-OrdinalEqual $actual $converted "$provider synchronization preserves provider resources"
    $checked += 2

    $targetCollection = '"cache-database-' + $provider + '-tenant-purge.postman_collection.json"'
    $targetMigration = '"com/reactor/cachedb/jdbc/migrations/' + $dialects[$provider] + '/"'
    $other = if ($provider -eq 'mssql') { 'oracle' } else { 'mssql' }
    $wrongCollection = $actual.Replace($targetCollection, ('"cache-database-' + $other + '-tenant-purge.postman_collection.json"'))
    $wrongMigration = $actual.Replace($targetMigration, ('"com/reactor/cachedb/jdbc/migrations/' + $dialects[$other] + '/"'))
    Assert-Rejected { Convert-ProviderBoundJava $wrongCollection $relative $provider postgresql } "$provider wrong collection"
    Assert-Rejected { Convert-ProviderBoundJava $wrongMigration $relative $provider postgresql } "$provider wrong migration"
    $checked += 2

    $changed = $actual.Replace($securityAssertion, 'assertEquals(7, collection.path("item").size());')
    $changedNormalized = Convert-ProviderBoundJava $changed $relative $provider postgresql
    if ([string]::Equals($canonical, $changedNormalized, [StringComparison]::Ordinal)) {
        throw "Provider normalization concealed a changed assertion: $provider"
    }
    $disabled = Convert-ProviderBoundJava ("@Disabled`n" + $actual) $relative $provider postgresql
    if ([string]::Equals($canonical, $disabled, [StringComparison]::Ordinal)) {
        throw "Provider normalization concealed a disabled test: $provider"
    }
    $checked += 2
}
foreach ($literal in @($collection, $migration)) {
    $missing = $canonical.Replace($literal, '"missing-resource"')
    $duplicate = $canonical + "`n// " + $literal
    Assert-Rejected { Convert-ProviderBoundJava $missing $relative postgresql mssql } 'missing binding'
    Assert-Rejected { Convert-ProviderBoundJava $duplicate $relative postgresql mssql } 'duplicate binding'
    $checked += 2
}
foreach ($ordinaryPath in @('src/test/java/OtherContractTest.java', ($relative + '.backup'))) {
    $unchanged = Convert-ProviderBoundJava $canonical $ordinaryPath postgresql oracle
    Assert-OrdinalEqual $canonical $unchanged 'No normalization outside the exact allowlisted file'
    $checked++
}
$runtimeRelative = 'src/purge/java/com/example/cachedb/purge/TenantPurgeRuntime.java'
$runtimeWindows = $runtimeRelative.Replace([char]47, [char]92)
$runtimeCanonical = [IO.File]::ReadAllText((Join-Path $root ('sample-cache-database-postgresql/' + $runtimeRelative)))
$runtimeBinding = 'private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect.POSTGRES;'
$constants = @{ postgresql = 'POSTGRES'; mssql = 'MSSQL'; oracle = 'ORACLE' }
$optIn = 'String run = TenantPurgeOptIn.require(env);'
if (-not $runtimeCanonical.Contains($optIn)) { throw 'Purge runtime opt-in mutation anchor missing.' }
foreach ($provider in @('postgresql', 'mssql', 'oracle')) {
    $actual = [IO.File]::ReadAllText((Join-Path $root ("sample-cache-database-$provider/" + $runtimeRelative)))
    $normalized = Convert-ProviderBoundJava $actual $runtimeWindows $provider postgresql
    Assert-OrdinalEqual $runtimeCanonical $normalized "$provider Windows-path normalized runtime"
    $converted = Convert-ProviderBoundJava $runtimeCanonical $runtimeRelative postgresql $provider
    Assert-OrdinalEqual $actual $converted "$provider synchronization preserves runtime dialect"
    $targetBinding = 'private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect.' + $constants[$provider] + ';'
    $other = if ($provider -eq 'mssql') { 'ORACLE' } else { 'MSSQL' }
    $wrong = $actual.Replace($targetBinding, ('private static final JdbcTenantPurgeDialect DIALECT = JdbcTenantPurgeDialect.' + $other + ';'))
    Assert-Rejected { Convert-ProviderBoundJava $wrong $runtimeRelative $provider postgresql } "$provider wrong runtime dialect"
    $changed = Convert-ProviderBoundJava ($actual.Replace($optIn, 'String run = env.getProperty("SAMPLE_PURGE_RUN_ID");')) $runtimeRelative $provider postgresql
    if ([string]::Equals($runtimeCanonical, $changed, [StringComparison]::Ordinal)) {
        throw "Provider normalization concealed a changed opt-in guard: $provider"
    }
    $extraNewline = Convert-ProviderBoundJava ($actual + "`n") $runtimeRelative $provider postgresql
    if ([string]::Equals($runtimeCanonical, $extraNewline, [StringComparison]::Ordinal)) {
        throw "Provider normalization concealed whitespace drift: $provider"
    }
    $checked += 5
}
$missingRuntime = $runtimeCanonical.Replace($runtimeBinding, '')
$duplicateRuntime = $runtimeCanonical + "`n// " + $runtimeBinding
Assert-Rejected { Convert-ProviderBoundJava $missingRuntime $runtimeRelative postgresql mssql } 'missing runtime dialect'
Assert-Rejected { Convert-ProviderBoundJava $duplicateRuntime $runtimeRelative postgresql mssql } 'duplicate runtime dialect'
$unchangedRuntime = Convert-ProviderBoundJava $runtimeCanonical ($runtimeRelative + '.backup') postgresql oracle
Assert-OrdinalEqual $runtimeCanonical $unchangedRuntime 'No runtime normalization outside exact allowlisted path'
$checked += 3
if ($checked -ne 42) { throw 'Provider binding control inventory differs.' }
Write-Output "PROVIDER_BINDING_CONTROLS_PASS=$checked; NO_APPLICATION_OR_DATABASE_EXECUTION"
