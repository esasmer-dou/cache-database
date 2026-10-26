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
try {
    foreach ($file in $files) {
        $source = Join-Path $root $file
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $destination = Join-Path $fixture $file
        $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)
        Copy-Item -LiteralPath $source -Destination $destination
    }
    $cases = @(
        @{ Name='baseline'; Source=$original; Label=''; Repository=$repositoryOriginal },
        @{ Name='startup-field-reflection'; Source=$original.Replace('import java.lang.reflect.Method;', 'import java.lang.reflect.Field;'); Label='runtime-reflection-import'; Repository=$repositoryOriginal },
        @{ Name='hot-annotation-scan'; Source=$original.Replace('var definition = definitions.get(invocation.getMethod());', 'AnnotatedElementUtils.findMergedAnnotation(type, CacheTransactional.class); var definition = definitions.get(invocation.getMethod());'); Label='transaction-hot-path-reflection'; Repository=$repositoryOriginal },
        @{ Name='hot-reflective-invocation'; Source=$original.Replace('var definition = definitions.get(invocation.getMethod());', 'invocation.getMethod().invoke(bean); var definition = definitions.get(invocation.getMethod());'); Label='transaction-hot-path-reflection'; Repository=$repositoryOriginal },
        @{ Name='repository-reflection-import'; Source=$original; Label='runtime-reflection-import'; Repository=$repositoryOriginal.Replace('import com.reactor.cachedb.core.api.CacheSession;', 'import java.lang.reflect.Method;' + "`n" + 'import com.reactor.cachedb.core.api.CacheSession;') }
    )
    if ($cases[-1].Repository -eq $repositoryOriginal) { throw 'Repository mutation anchor is missing.' }
    Push-Location $fixture
    try {
        foreach ($case in $cases) {
            [IO.File]::WriteAllText((Join-Path $fixture $configuration), $case.Source)
            [IO.File]::WriteAllText((Join-Path $fixture $repository), $case.Repository)
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
# Expected failing child probes must not become the surrounding CI shell's exit code.
exit 0
