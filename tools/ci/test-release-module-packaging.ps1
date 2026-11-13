#requires -Version 7.2
<#
.SYNOPSIS
Regression controls for public release-module allowlists and artifact checks.
.DESCRIPTION
Creates synthetic ZIP/JAR fixtures in a new evidence directory. No Java, Maven,
installation or publication is run. Passing these controls does not qualify real
artifacts. Git is initialized only inside the isolated synthetic source fixture;
the repository supplied as input is read-only. With no EvidenceDirectory, a new
temporary evidence directory is retained and its path is printed.
.EXAMPLE
./tools/ci/test-release-module-packaging.ps1 -EvidenceDirectory ./target/release-packaging-controls
#>
[CmdletBinding()]
param(
    [Alias('SourceRoot')]
    [ValidateNotNullOrEmpty()]
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSStyle.OutputRendering = 'PlainText'
$controlBudgetWatch = [Diagnostics.Stopwatch]::StartNew()
$version = '0.19.0'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-release-packaging-' + [guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
Write-Host "SYNTHETIC_PACKAGING_EVIDENCE=$evidence"
$root = Join-Path $evidence 'synthetic-source'
$scripts = @(
    'tools/release/import-release-artifacts.ps1',
    'tools/release/build-public-maven-repository.ps1',
    'tools/release/build-release-bundle.ps1',
    'tools/release/repair-bundle-documentation.ps1',
    'tools/ci/check-public-api-compatibility.ps1',
    'tools/ci/check-release-artifacts.ps1'
)
$modules = @(
    'cachedb-annotations', 'cachedb-processor', 'cachedb-core', 'cachedb-json',
    'cachedb-storage-redis', 'cachedb-storage-jdbc', 'cachedb-storage-postgres',
    'cachedb-storage-mssql', 'cachedb-storage-oracle', 'cachedb-starter',
    'cachedb-spring-boot-starter', 'cachedb-reactor', 'cachedb-spring-data',
    'cachedb-spring-boot-starter-postgres', 'cachedb-spring-boot-starter-mssql',
    'cachedb-spring-boot-starter-oracle', 'cachedb-spring-boot-starter-admin',
    'cachedb-spring-boot-test', 'cachedb-maven-plugin', 'cachedb-migration-recipes', 'cachedb-examples'
)
$requiredTypes = @{
    'cachedb-annotations' = @('annotations/CacheSeek', 'annotations/CacheJobHandler',
        'annotations/CachePatch', 'annotations/CacheNearPolicy', 'annotations/CacheTransactional',
        'annotations/CacheDelta', 'annotations/CacheSet', 'annotations/CacheAggregate',
        'annotations/CacheTenantId', 'annotations/CacheWatcher', 'annotations/CacheSchemaVersion',
        'annotations/CacheFacet', 'annotations/CacheCompositeIndex', 'annotations/CacheCompositeIndexes')
    'cachedb-core' = @('core/codec/CacheValueCodec', 'core/codec/GeneratedJsonCodecProvider',
        'core/codec/GeneratedJsonCodecs', 'core/codec/JsonNoopCheckpoint', 'core/api/CacheUnitOfWork',
        'core/query/TypedColumn', 'core/cache/NearCachePolicy', 'core/cache/SingleFlightLoader',
        'core/config/MutationConfig', 'core/trace/TraceContextPropagator', 'core/codec/PayloadCompressionCodec',
        'core/codec/PayloadEntityCodec', 'core/codec/CachePayloadUpcaster', 'core/model/PatchDeltaSpec',
        'core/page/EntityOwnershipLoader', 'core/query/AggregateSpec', 'core/query/AggregateResult',
        'core/tenant/CacheTenantContext', 'core/watch/CacheChangeEvent', 'core/causal/CacheCausalContext',
        'core/query/FacetAccumulator')
    'cachedb-json' = @('json/StreamingJsonCodec', 'json/JsonTokenWriter', 'json/JsonUtf8Writer')
    'cachedb-storage-jdbc' = @('jdbc/AbstractJdbcWriteBehindFlusher')
    'cachedb-storage-redis' = @('redis/RedisNearCacheCoordinator', 'redis/LocalDurabilitySignals',
        'redis/RedisTenantWriteGuard', 'redis/RedisWatchRegistry', 'redis/RedisCompositeIndexes')
    'cachedb-starter' = @('starter/CacheTransactionTemplate')
    'cachedb-processor' = @('processor/CacheJobHandlerProcessor')
    'cachedb-spring-boot-starter' = @('spring/boot/snapshot/SnapshotPageRequest',
        'spring/boot/snapshot/SnapshotTypedPageReadResult', 'spring/boot/snapshot/SnapshotManifest',
        'spring/boot/CacheTransactionAutoConfiguration', 'spring/boot/CacheTraceAutoConfiguration',
        'spring/boot/CacheDatabaseMetricsMeterBinder', 'spring/boot/snapshot/SnapshotDirtyBinding')
    'cachedb-reactor' = @('reactor/ReactiveEntityRepository', 'reactor/ReactiveEntityRepositoryFactory',
        'reactor/CacheReactorContext', 'reactor/CacheReactorAutoConfiguration')
    'cachedb-spring-data' = @('spring/data/CacheDbSpringDataRepository',
        'spring/data/AbstractCacheDbSpringDataRepository', 'spring/data/CacheDbSpringDataOptions',
        'spring/data/SpringDataQueries')
}
$controls = [Collections.Generic.List[object]]::new()
function Pass([string]$Name) {
    $controls.Add([pscustomobject]@{ control = $Name; result = 'PASS' })
    Write-Host "PASS $Name"
}
function Write-Text([string]$Path, [string]$Text) {
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path)
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
function Write-Zip([string]$Path, [string[]]$Entries) {
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path)
    $stream = [IO.File]::Create($Path)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Entries) {
            $entry = $zip.CreateEntry($name)
            $writer = [IO.StreamWriter]::new($entry.Open())
            try { $writer.Write('synthetic packaging-control content; not a compiled SDK artifact') }
            finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose(); $stream.Dispose() }
}
function Jar-Path([string]$Module, [string]$Suffix) {
    Join-Path $root "$Module/target/$Module-$version$Suffix"
}
function Initialize-Source([string]$Destination) {
    foreach ($path in $scripts) {
        $target = Join-Path $Destination $path
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot $path) -Destination $target
    }
    Write-Text (Join-Path $Destination 'pom.xml') "<project><artifactId>cache-database</artifactId><version>$version</version></project>"
    foreach ($module in @($modules) + @('cachedb-bom')) {
        Write-Text (Join-Path $Destination "$module/pom.xml") "<project><artifactId>$module</artifactId><version>$version</version></project>"
    }
}
function Reject([string]$Name, [scriptblock]$Action, [string]$Expected) {
    $diagnostic = [Collections.Generic.List[string]]::new()
    $rejected = $false
    try { & $Action 6>&1 | ForEach-Object { $diagnostic.Add([string]$_) } }
    catch { $rejected = $true; $diagnostic.Add($_.Exception.Message) }
    $diagnostic | Set-Content -LiteralPath (Join-Path $evidence "$Name.log") -Encoding utf8
    if (!$rejected) { throw "Negative control was accepted: $Name" }
    if (($diagnostic -join "`n") -notmatch $Expected) { throw "Wrong rejection reason: $Name" }
    Pass $Name
}
function Get-Array([string]$Path, [string]$Name, [switch]$Parameter) {
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "Script parse error: $Path" }
    if ($Parameter) {
        $value = $ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq $Name }
        $literal = $value.DefaultValue
    } else {
        $assignment = $ast.Find({param($node)
            $node -is [Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and
            $node.Left.VariablePath.UserPath -eq $Name
        }, $true)
        if (!$assignment) { throw "Array assignment missing: $Name" }
        $literal = $assignment.Right
    }
    $dynamic = @($literal.FindAll({param($node)
        $node -is [Management.Automation.Language.ExpressionAst] -and
        $node -isnot [Management.Automation.Language.ArrayExpressionAst] -and
        $node -isnot [Management.Automation.Language.ArrayLiteralAst] -and
        $node -isnot [Management.Automation.Language.StringConstantExpressionAst]
    }, $true))
    if ($dynamic.Count) { throw "Public allowlist contains a nonliteral expression: $Name" }
    return @($literal.FindAll({param($node)
        $node -is [Management.Automation.Language.StringConstantExpressionAst]
    }, $true) | ForEach-Object { $_.Value })
}
Initialize-Source $root
$arraySpecs = @(
    @('tools/release/import-release-artifacts.ps1', 'modules', $false),
    @('tools/release/build-public-maven-repository.ps1', 'artifactModules', $false),
    @('tools/release/build-release-bundle.ps1', 'artifactModules', $false),
    @('tools/ci/check-release-artifacts.ps1', 'publicJarModules', $false),
    @('tools/ci/check-public-api-compatibility.ps1', 'Modules', $true)
)
foreach ($spec in $arraySpecs) {
    $actual = @(Get-Array (Join-Path $root $spec[0]) $spec[1] -Parameter:$spec[2])
    $expected = if ($spec[2]) { @($modules | Where-Object { $_ -ne 'cachedb-examples' }) } else { $modules }
    if ($actual.Count -ne $expected.Count -or @(Compare-Object $actual $expected).Count -or
            @($actual | Sort-Object -Unique).Count -ne $actual.Count -or
            @($actual | Where-Object { $_ -match 'benchmark|acceptance|tools/|integration-tests|production-tests' }).Count) {
        throw "Public allowlist mismatch: $($spec[0])"
    }
    Pass ("allowlist-" + [IO.Path]::GetFileNameWithoutExtension($spec[0]))
}
# Inspect only the numeric selector, never evaluate deployment commands from YAML.
$workflow = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/workflows/github-packages-publish.yml') -Raw
$selector = @($workflow -split '\r?\n' | Where-Object { $_ -match '^\s*\$expectedPomCount\s*=' })
$selectorPattern = '^\s*\$expectedPomCount\s*=\s*if\s*\(\s*\[version\]\s*\$version\s+-ge\s*\[version\]\s*''(?<cutoff>\d+\.\d+\.\d+)''\s*\)\s*\{\s*(?<modern>\d+)\s*\}\s*else\s*\{\s*(?<legacy>\d+)\s*\}\s*$'
if ($selector.Count -ne 1 -or $selector[0] -notmatch $selectorPattern) {
    throw 'Publish workflow must have one explicit version-aware POM-count selector.'
}
$cutoff = [version]$Matches.cutoff
$modernPomCount = [int]$Matches.modern
$legacyPomCount = [int]$Matches.legacy
if ($workflow -notmatch '\$poms\.Count\s+-ne\s+\$expectedPomCount') {
    throw 'Publish workflow does not enforce the selected POM count.'
}
foreach ($case in @(@('0.18.1', 21), @('0.19.0', 23))) {
    $actualCount = if ([version]$case[0] -ge $cutoff) { $modernPomCount } else { $legacyPomCount }
    if ($actualCount -ne $case[1]) { throw "Wrong public POM count for $($case[0]): $actualCount" }
    Pass "publish-workflow-pom-count-$($case[0])"
}
foreach ($module in $modules) {
    foreach ($suffix in @('.jar', '-sources.jar', '-javadoc.jar')) {
        if ($suffix -eq '-javadoc.jar') { $entries = @('index.html') }
        else {
            $prefix = if ($suffix -eq '.jar') { 'com/reactor/cachedb' } else { 'com/cachedb' }
            $extension = if ($suffix -eq '.jar') { 'class' } else { 'java' }
            $entries = @("$prefix/fixture/Packaged.$extension")
            if ($requiredTypes.ContainsKey($module)) {
                $entries += @($requiredTypes[$module] | ForEach-Object { "$prefix/$_.$extension" })
            }
            if ($module -eq 'cachedb-storage-jdbc') {
                $entries += @('postgres', 'mssql', 'oracle' | ForEach-Object { "com/reactor/cachedb/jdbc/migrations/$_/V014__durable_write_fence.sql" })
            }
            if ($module -eq 'cachedb-examples' -and $suffix -eq '.jar') {
                $entries += 'META-INF/services/com.reactor.cachedb.core.codec.GeneratedJsonCodecProvider'
            }
        }
        Write-Zip (Jar-Path $module $suffix) $entries
    }
}
[xml]$releasePom = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'pom.xml') -Raw
$ns = [Xml.XmlNamespaceManager]::new($releasePom.NameTable)
$ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
$releasePath = "/m:project/m:profiles/m:profile[m:id='oss-release']/m:build/m:plugins/m:plugin[m:artifactId='maven-javadoc-plugin']"
$configuration = $releasePom.SelectSingleNode("$releasePath/m:executions/m:execution[m:id='attach-javadocs']/m:configuration", $ns)
$versionNode = $releasePom.SelectSingleNode('/m:project/m:properties/m:maven.javadoc.plugin.version', $ns)
if ($null -eq $configuration -or $null -eq $versionNode -or $versionNode.InnerText -cne '3.7.0' -or
        $configuration.sourcepath -cne '${project.build.sourceDirectory}${path.separator}${project.build.directory}/generated-sources/annotations' -or
        $configuration.failOnError -cne 'false') { throw 'Release javadoc 3.7.0 generated-source path contract changed.' }
Pass 'javadoc-fresh-invocation-sourcepath'
$checker = Join-Path $root 'tools/ci/check-release-artifacts.ps1'
& $checker -Version $version
Pass 'checker-positive-21-modules'
$javadoc = Jar-Path 'cachedb-examples' '-javadoc.jar'
$backup = "$javadoc.control-backup"
Copy-Item -LiteralPath $javadoc -Destination $backup
try {
    Write-Zip $javadoc @()
    Reject 'examples-empty-javadoc' { & $checker -Version $version } 'cachedb-examples-0\.19\.0-javadoc\.jar'
    Write-Zip $javadoc @('overview-summary.html')
    Reject 'examples-javadoc-without-index' { & $checker -Version $version } 'cachedb-examples-0\.19\.0-javadoc\.jar'
} finally { Move-Item -LiteralPath $backup -Destination $javadoc -Force }
foreach ($module in @('cachedb-reactor', 'cachedb-spring-data')) {
    foreach ($suffix in @('.jar', '-sources.jar', '-javadoc.jar')) {
        $path = Jar-Path $module $suffix
        $backup = "$path.control-backup"
        Move-Item -LiteralPath $path -Destination $backup
        try { Reject "missing-$module$suffix" { & $checker -Version $version } ([regex]::Escape("$module-$version$suffix")) }
        finally { Move-Item -LiteralPath $backup -Destination $path }
    }
    foreach ($suffix in @('.jar', '-sources.jar')) {
        $path = Jar-Path $module $suffix
        $backup = "$path.control-backup"
        Copy-Item -LiteralPath $path -Destination $backup
        try {
            foreach ($type in $requiredTypes[$module]) {
                Copy-Item -LiteralPath $backup -Destination $path -Force
                $prefix = if ($suffix -eq '.jar') { 'com/reactor/cachedb' } else { 'com/cachedb' }
                $extension = if ($suffix -eq '.jar') { 'class' } else { 'java' }
                $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Update)
                try { $zip.GetEntry("$prefix/$type.$extension").Delete() } finally { $zip.Dispose() }
                Reject ("missing-type-" + $module + '-' + ($type -replace '/', '-') + '-' + $extension) `
                    { & $checker -Version $version } ([regex]::Escape("required 0.19 type is missing: $type.$extension"))
            }
        } finally { Move-Item -LiteralPath $backup -Destination $path -Force }
    }
}
foreach ($file in @('README.md', 'LICENSE', 'CHANGELOG.md', 'CONTRIBUTING.md', 'SECURITY.md', 'CODE_OF_CONDUCT.md', 'SUPPORT.md', 'docs/contract.md', 'tr/docs/contract.md')) {
    Write-Text (Join-Path $root $file) 'Synthetic packaging-control fixture, not release evidence.'
}
$hiddenSampleFiles = [Collections.Generic.List[string]]::new()
foreach ($sample in @('sample-cache-database-postgresql', 'sample-cache-database-mssql', 'sample-cache-database-oracle')) {
    Write-Text (Join-Path $root "$sample/pom.xml") '<project>synthetic sample source</project>'
    foreach ($relative in @('.gitignore', '.github/workflows/consumer-build.yml')) {
        $path = "$sample/$relative"
        Write-Text (Join-Path $root $path) "Synthetic hidden-file packaging control: $path`n"
        $hiddenSampleFiles.Add($path)
    }
}
foreach ($tool in @('redis', 'observation-otel')) {
    Write-Text (Join-Path $root "tools/benchmarks/$tool/pom.xml") '<project>excluded synthetic tool</project>'
    Write-Zip (Join-Path $root "tools/benchmarks/$tool/target/excluded-tool-0.19.0.jar") @('excluded.class')
}
# Source archives have no .git. Initialize only this new synthetic source, without add/commit/push.
& git -C $root init --quiet
if ($LASTEXITCODE) { throw 'Isolated fixture git init failed.' }
& (Join-Path $root 'tools/release/build-release-bundle.ps1') -Version $version -PackageLabel 'synthetic-control' -SkipBuild
$bundle = Join-Path $root "target/releases/cache-database-$version-synthetic-control.zip"
$zip = [IO.Compression.ZipFile]::OpenRead($bundle)
try {
    $names = @($zip.Entries.FullName)
    if (@($names | Where-Object { $_ -match '^artifacts/.*\.jar$' }).Count -ne 63 -or
            @($names | Where-Object { $_ -match '^artifacts/.*\.pom$' }).Count -ne 23 -or
            @($names | Where-Object { $_ -match 'benchmarks|observation-otel|excluded-tool' }).Count) {
        throw 'Bundle has wrong public artifact inventory or leaked excluded tools.'
    }
    foreach ($path in $hiddenSampleFiles) {
        $name = "samples/$path"
        $entries = @($zip.Entries | Where-Object { $_.FullName -ceq $name })
        if ($entries.Count -ne 1) { throw "Hidden sample source must appear exactly once: $name" }
        $stream = $entries[0].Open()
        try { $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
        finally { $stream.Dispose() }
        $expected = (Get-FileHash -LiteralPath (Join-Path $root $path) -Algorithm SHA256).Hash
        if ($actual -cne $expected) { throw "Hidden sample source bytes changed: $name" }
    }
} finally { $zip.Dispose() }
Pass 'bundle-positive-63-jars-23-poms-tools-excluded'
Pass 'bundle-preserves-six-hidden-sample-files-byte-for-byte'
$repository = Join-Path $evidence 'synthetic-maven-repository'
& (Join-Path $root 'tools/release/build-public-maven-repository.ps1') -Version $version -OutputDirectory $repository -SkipBuild
if (@(Get-ChildItem -LiteralPath $repository -Recurse -Filter '*.jar' -File).Count -ne 63 -or
        @(Get-ChildItem -LiteralPath $repository -Recurse -Filter '*.pom' -File).Count -ne 23 -or
        @(Get-ChildItem -LiteralPath $repository -Recurse -File | Where-Object { $_.Name -match 'excluded-tool|benchmark|observation-otel' }).Count) {
    throw 'Public repository has wrong artifact inventory or leaked excluded tools.'
}
Pass 'repository-positive-63-jars-23-poms-tools-excluded'
$importRoot = Join-Path $evidence 'import-positive'
Initialize-Source $importRoot
& (Join-Path $importRoot 'tools/release/import-release-artifacts.ps1') -Version $version -ArchivePath $bundle
if (@(Get-ChildItem -LiteralPath $importRoot -Recurse -Filter '*.jar' -File).Count -ne 63) { throw 'Importer did not restore every public artifact.' }
Pass 'import-positive-21-modules'
foreach ($module in @('cachedb-reactor', 'cachedb-spring-data')) {
    $bad = Join-Path $evidence "missing-$module.zip"
    Copy-Item -LiteralPath $bundle -Destination $bad
    $zip = [IO.Compression.ZipFile]::Open($bad, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $remove = @($zip.Entries | Where-Object { $_.FullName.StartsWith("artifacts/$module/") })
        foreach ($entry in $remove) { $entry.Delete() }
    } finally { $zip.Dispose() }
    $destination = Join-Path $evidence "import-negative-$module"
    Initialize-Source $destination
    Reject "import-missing-$module" { & (Join-Path $destination 'tools/release/import-release-artifacts.ps1') -Version $version -ArchivePath $bad } ([regex]::Escape("artifacts/$module/"))
    if (@(Get-ChildItem -LiteralPath $destination -Recurse -Filter '*.jar' -File).Count) { throw 'Rejected import wrote a partial artifact set.' }
    Pass "import-missing-$module-no-partial-writes"
}
& $checker -Version $version
Pass 'checker-positive-after-negative-controls'
$documentationScript = Join-Path $RepositoryRoot 'tools/ci/test-bundle-documentation.ps1'
$documentationRoot = Join-Path $evidence 'bundle-documentation-child'
$null = [IO.Directory]::CreateDirectory($documentationRoot)
$documentationEvidence = Join-Path $documentationRoot 'evidence'
$childMilliseconds = [int][Math]::Min(120000, 120000 - $controlBudgetWatch.ElapsedMilliseconds - 6000)
if ($childMilliseconds -le 0) { throw 'Original packaging control budget exhausted before documentation fixtures.' }
$childInfo = [Diagnostics.ProcessStartInfo]::new()
$childInfo.FileName = [Environment]::ProcessPath
$childInfo.UseShellExecute = $false
$childInfo.RedirectStandardOutput = $true
$childInfo.RedirectStandardError = $true
$childInfo.CreateNoWindow = $true
$childInfo.WorkingDirectory = $RepositoryRoot
$childArguments = @('-NoLogo','-NoProfile','-NonInteractive','-File',$documentationScript,
    '-RepositoryRoot',$RepositoryRoot,'-EvidenceDirectory',$documentationEvidence)
foreach ($argument in $childArguments) { $childInfo.ArgumentList.Add($argument) }
$child = [Diagnostics.Process]::new()
$child.StartInfo = $childInfo
$childStdoutPath = Join-Path $documentationRoot 'stdout.log'
$childStdout = [IO.File]::Open($childStdoutPath, [IO.FileMode]::CreateNew)
$childStderr = [IO.File]::Open((Join-Path $documentationRoot 'stderr.log'), [IO.FileMode]::CreateNew)
$childStartedAt = [DateTimeOffset]::UtcNow.ToString('o')
$childTimedOut = $false
try {
    if (!$child.Start()) { throw 'Documentation fixture child did not start.' }
    $stdoutCopy = $child.StandardOutput.BaseStream.CopyToAsync($childStdout)
    $stderrCopy = $child.StandardError.BaseStream.CopyToAsync($childStderr)
    if (!$child.WaitForExit($childMilliseconds)) {
        $childTimedOut = $true
        $child.Kill($true)
        if (!$child.WaitForExit(5000)) { throw 'Documentation child did not stop within the original control budget.' }
    }
    $stdoutCopy.GetAwaiter().GetResult()
    $stderrCopy.GetAwaiter().GetResult()
    $childExit = $child.ExitCode
} finally {
    $childStdout.Dispose()
    $childStderr.Dispose()
    $child.Dispose()
}
[IO.File]::WriteAllText((Join-Path $documentationRoot 'process.exit'), ([string]$childExit + "`n"))
[ordered]@{schema='cachedb.bundle-documentation-child/v1'; command=$childInfo.FileName; argv=$childArguments
    startedAt=$childStartedAt; finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    exitCode=$childExit; timedOut=$childTimedOut; budgetMilliseconds=$childMilliseconds
    scriptSha256=(Get-FileHash -LiteralPath $documentationScript).Hash.ToLowerInvariant()
    repairSha256=(Get-FileHash -LiteralPath (Join-Path $RepositoryRoot 'tools/release/repair-bundle-documentation.ps1')).Hash.ToLowerInvariant()
    stdout='stdout.log'; stderr='stderr.log'; exitFile='process.exit'} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $documentationRoot 'result.json') -Encoding utf8
if ($childTimedOut -or $childExit -ne 0 -or
        [IO.File]::ReadAllText($childStdoutPath) -notmatch '(?m)^BUNDLE_DOCUMENTATION_CONTROLS_PASS checks=18\r?$') {
    throw 'Dedicated bundle-documentation fixtures did not complete successfully.'
}
if ($controlBudgetWatch.ElapsedMilliseconds -ge 120000) { throw 'Original packaging control budget exceeded.' }
Pass 'dedicated-bundle-documentation-fixtures-executed-once-as-successful-child'
[ordered]@{ result = 'PASS'; controls = $controls; publicModules = 21; jars = 63; poms = 23;
    syntheticOnly = $true; realReleaseQualified = $false; runtimeTestsExecuted = $false;
    documentationChild = @{result='PASS'; checks=18; resultFile='bundle-documentation-child/result.json'} } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
Write-Host "SYNTHETIC_PACKAGING_CONTROLS_PASS=$($controls.Count); NO_RELEASE_OR_RUNTIME_ACCEPTANCE"
