$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$checker = Join-Path $PSScriptRoot 'check-framework-principles.ps1'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-principles-' + [guid]::NewGuid().ToString('N'))
$output = Join-Path $root 'target/framework-principles-selftest'
$null = New-Item -ItemType Directory -Force -Path $fixture, $output
$files = @(& git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate architecture fixture sources.' }
$configuration = 'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheTransactionAutoConfiguration.java'
$repository = 'cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabase.java'
$original = [IO.File]::ReadAllText((Join-Path $root $configuration))
$repositoryOriginal = [IO.File]::ReadAllText((Join-Path $root $repository))
$keyGenerator = 'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/cache/CacheDbKeyGenerator.java'
$keyOriginal = [IO.File]::ReadAllText((Join-Path $root $keyGenerator))
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($checker, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Architecture checker parse errors.' }
$policy = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-TypeOnlyKeyGeneratorSpi'
}, $true))
if ($policy.Count -ne 1) { throw 'Missing/duplicate type-only SPI policy.' }
. ([scriptblock]::Create($policy[0].Extent.Text))
$spiCases = @(
    @('type-only-spi', $keyOriginal, $true),
    @('spi-invoke', $keyOriginal.Replace('return key.toString();', 'method.invoke(target); return key.toString();'), $false),
    @('spi-metadata-read', $keyOriginal.Replace('return key.toString();', 'method.getName(); return key.toString();'), $false),
    @('spi-method-reference', $keyOriginal.Replace('return key.toString();', 'var ref = method::invoke; return key.toString();'), $false),
    @('spi-alias', $keyOriginal.Replace('return key.toString();', 'var alias = method; alias.invoke(target); return key.toString();'), $false),
    @('spi-extra-method-field', $keyOriginal.Replace('private static final String RESERVED', 'private Method extra; private static final String RESERVED'), $false),
    @('spi-class-for-name', $keyOriginal.Replace('return key.toString();', 'Class.forName("example.Type"); return key.toString();'), $false),
    @('spi-indirect-invocation', $keyOriginal.Replace('return key.toString();', 'target.getClass().getMethod("x").invoke(target); return key.toString();'), $false),
    @('spi-wrong-signature', $keyOriginal.Replace('Object... params', 'Object[] params'), $false),
    @('spi-comment-signature', ($keyOriginal.Replace('Object generate(', 'Object changed(') + "`n/* @Override public Object generate(Object target, Method method, Object... params) { */"), $false),
    @('spi-lookalike-class', $keyOriginal.Replace('class CacheDbKeyGenerator', 'class OtherKeyGenerator'), $false),
    @('spi-wrong-package', $keyOriginal.Replace('package com.reactor.cachedb.spring.boot.cache;', 'package example;'), $false),
    @('spi-wrong-interface-import', $keyOriginal.Replace('import org.springframework.cache.interceptor.KeyGenerator;', 'import example.KeyGenerator;'), $false),
    @('spi-unicode-escape', $keyOriginal.Replace('return key.toString();', '\u006dethod.invoke(target); return key.toString();'), $false),
    @('spi-comment-is-not-execution', ($keyOriginal + "`n// method.invoke(target) is forbidden"), $true)
)
foreach ($case in $spiCases) {
    if ($case.Count -ne 3) { throw 'SPI fixture must contain name, source and expected result.' }
    if ($case[1] -ceq $keyOriginal -and $case[0] -ne 'type-only-spi') { throw "SPI mutation anchor missing: $($case[0])" }
    if ((Test-TypeOnlyKeyGeneratorSpi $case[1]) -ne $case[2]) { throw "Wrong SPI policy result: $($case[0])" }
    Write-Output "SPI guard assertion passed: $($case[0])"
}
try {
    foreach ($file in $files) {
        $source = Join-Path $root $file
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $destination = Join-Path $fixture $file
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)
        Copy-Item -LiteralPath $source -Destination $destination
    }
    # Copy-Item preserves read-only attributes; only these owned fixture files are mutated.
    foreach ($relative in @($configuration, $repository, $keyGenerator)) {
        $mutationFile = Get-Item -LiteralPath (Join-Path $fixture $relative) -Force
        if ($mutationFile.PSIsContainer -or ($mutationFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Mutation target must be a regular fixture file.'
        }
        $mutationFile.IsReadOnly = $false
    }
    $cases = @(
        @{ Name='baseline'; Source=$original; Label=''; Repository=$repositoryOriginal },
        @{ Name='startup-field-reflection'; Source=$original.Replace('import java.lang.reflect.Method;', 'import java.lang.reflect.Field;'); Label='runtime-reflection-import'; Repository=$repositoryOriginal },
        @{ Name='hot-annotation-scan'; Source=$original.Replace('var definition = definitions.get(invocation.getMethod());', 'AnnotatedElementUtils.findMergedAnnotation(type, CacheTransactional.class); var definition = definitions.get(invocation.getMethod());'); Label='transaction-hot-path-reflection'; Repository=$repositoryOriginal },
        @{ Name='hot-reflective-invocation'; Source=$original.Replace('var definition = definitions.get(invocation.getMethod());', 'invocation.getMethod().invoke(bean); var definition = definitions.get(invocation.getMethod());'); Label='transaction-hot-path-reflection'; Repository=$repositoryOriginal },
        @{ Name='repository-reflection-import'; Source=$original; Label='runtime-reflection-import'; Repository=$repositoryOriginal.Replace('import com.reactor.cachedb.core.api.CacheSession;', 'import java.lang.reflect.Method;' + "`n" + 'import com.reactor.cachedb.core.api.CacheSession;') },
        @{ Name='key-generator-executable-reflection'; Source=$original; Label='runtime-reflection-import'; Repository=$repositoryOriginal; Spi=$spiCases[1][1] },
        @{ Name='test-source-layout'; Source=$original; Label='source-layout'; Repository=$repositoryOriginal; BadLayout=$true },
        @{ Name='case-sensitive-spi-path'; Source=$original; Label='runtime-reflection-import'; Repository=$repositoryOriginal; SpiLookalike=$true }
    )
    if ($cases[4].Repository -eq $repositoryOriginal) { throw 'Repository mutation anchor is missing.' }
    Push-Location $fixture
    try {
        foreach ($case in $cases) {
            [IO.File]::WriteAllText((Join-Path $fixture $configuration), $case.Source)
            [IO.File]::WriteAllText((Join-Path $fixture $repository), $case.Repository)
            [IO.File]::WriteAllText((Join-Path $fixture $keyGenerator), $(if ($case.ContainsKey('Spi')) { $case.Spi } else { $keyOriginal }))
            $badLayout = Join-Path $fixture 'tools/source-layout-control/src/test/java/com/reactor/cachedb/Forbidden.java'
            if ($case.ContainsKey('BadLayout')) {
                $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $badLayout)
                [IO.File]::WriteAllText($badLayout, 'package com.reactor.cachedb; class Forbidden {}')
            } elseif (Test-Path -LiteralPath $badLayout) { Remove-Item -LiteralPath $badLayout }
            $lookalike = Join-Path $fixture 'cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/cache/CachedbKeyGenerator.java'
            if ($case.ContainsKey('SpiLookalike')) {
                $fake = $keyOriginal.Replace('class CacheDbKeyGenerator', 'class CachedbKeyGenerator').Replace(
                    'return key.toString();', 'try { Class.forName("example.Type"); } catch (ClassNotFoundException ignored) {} return key.toString();')
                [IO.File]::WriteAllText($lookalike, $fake)
            } elseif (Test-Path -LiteralPath $lookalike) { Remove-Item -LiteralPath $lookalike }
            $summary = Join-Path $output ($case.Name + '.md')
            & (Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })) -NoProfile -File $checker -SummaryPath $summary *> (Join-Path $output ($case.Name + '.log'))
            if ($case.Name -eq 'baseline') {
                if ($LASTEXITCODE -ne 0) { throw 'Baseline architecture contract failed.' }
            } elseif ($LASTEXITCODE -eq 0 -or
                    [IO.File]::ReadAllText($summary) -notmatch [regex]::Escape($case.Label)) {
                throw "Architecture guard failed to reject $($case.Name)."
            }
            Write-Output "Architecture guard assertion passed: $($case.Name)"
        }
    } finally { Pop-Location }
} finally {
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (![IO.Path]::GetFullPath($fixture).StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($fixture) -notmatch '^cachedb-principles-[a-f0-9]{32}$') {
        throw 'Architecture fixture cleanup escaped its temporary directory.'
    }
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
Write-Output "ARCHITECTURE_GUARD_CONTROLS_PASS=$($spiCases.Count + $cases.Count); NO_JAVA_EXECUTION"
# Expected failing child probes must not become the surrounding CI shell's exit code.
exit 0
