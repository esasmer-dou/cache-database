param(
    [ValidateRange(3, 20)]
    [int]$Runs = 5,
    [ValidateRange(1, 1000)]
    [double]$MaximumMilliseconds = 50,
    [switch]$ReportOnly,
    [string]$ResultsDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($ResultsDirectory)) {
    $ResultsDirectory = Join-Path $repoRoot 'target/inmemory-unit-latency'
}
$ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
$null = New-Item -ItemType Directory -Force -Path $ResultsDirectory
# Invalidate a previous result before compilation or the first fork can fail.
[ordered]@{
    generatedUtc = [DateTime]::UtcNow.ToString('o')
    completed = $false
    fullTargetPasses = $false
    functionalTestsPass = $false
    latencyGateEnforced = !$ReportOnly
    exclusiveMaximumMillis = $MaximumMilliseconds
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDirectory 'summary.json') -Encoding utf8
$maven = (Get-Command mvn -ErrorAction Stop).Source
$selectors = 'InMemoryCacheRepositoryTest,DurabilityWaitTest,InMemorySnapshotRepositoryTest,TypedSnapshotRepositoryTest#fourStatesAndTypedValuesNeverNeedInfrastructure'
$classes = @{
    'cachedb-core' = @('com.reactor.cachedb.core.testing.InMemoryCacheRepositoryTest',
        'com.reactor.cachedb.core.api.DurabilityWaitTest')
    'cachedb-spring-boot-starter' = @('com.reactor.cachedb.spring.boot.snapshot.InMemorySnapshotRepositoryTest',
        'com.reactor.cachedb.spring.boot.snapshot.TypedSnapshotRepositoryTest')
}
$measurements = @()
Push-Location $repoRoot
try {
    for ($run = 1; $run -le $Runs; $run++) {
        $started = [DateTime]::UtcNow
        & $maven -B -ntp -pl cachedb-spring-boot-starter -am test "-Dtest=$selectors" `
            '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=1' '-DreuseForks=true' `
            2>&1 | Tee-Object -FilePath (Join-Path $ResultsDirectory "run-$run.log")
        if ($LASTEXITCODE -ne 0) { throw "Functional tests failed in run $run." }
        foreach ($module in $classes.Keys) {
            foreach ($class in $classes[$module]) {
                $path = Join-Path $repoRoot "$module/target/surefire-reports/TEST-$class.xml"
                $file = Get-Item -LiteralPath $path
                if ($file.LastWriteTimeUtc -lt $started) { throw "Stale test report: $path" }
                [xml]$xml = Get-Content -LiteralPath $path -Raw
                if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or
                    [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.tests -lt 1) {
                    throw "Missing, skipped or failed contracts in $class."
                }
                foreach ($test in $xml.testsuite.testcase) {
                    $millis = [double]::Parse([string]$test.time, [Globalization.CultureInfo]::InvariantCulture) * 1000
                    $measurements += [pscustomobject]@{
                        run = $run
                        class = $class
                        name = [string]$test.name
                        millis = $millis
                    }
                }
            }
        }
    }
    $summary = @($measurements | Group-Object class, name | ForEach-Object {
        $times = @($_.Group.millis | Sort-Object)
        if ($times.Count -ne $Runs) { throw 'Incomplete repeated test evidence.' }
        [pscustomobject]@{
            name = $_.Group[0].name
            class = $_.Group[0].class
            runs = $times.Count
            medianMillis = ($times[[int][Math]::Floor(($times.Count - 1) / 2)] +
                $times[[int][Math]::Floor($times.Count / 2)]) / 2
            maxMillis = $times[-1]
            passes = ($times[-1] -lt $MaximumMilliseconds)
        }
    })
    $passed = @($summary | Where-Object { -not $_.passes }).Count -eq 0
    [ordered]@{
        generatedUtc = [DateTime]::UtcNow.ToString('o')
        completed = $true
        javaHome = $env:JAVA_HOME
        runs = $Runs
        exclusiveMaximumMillis = $MaximumMilliseconds
        fullTargetPasses = $passed
        functionalTestsPass = $true
        latencyGateEnforced = !$ReportOnly
        measurementCount = $measurements.Count
        exceededSamples = @($measurements | Where-Object { $_.millis -ge $MaximumMilliseconds }).Count
        scope = 'Surefire per-test times in fresh forked JVMs, including first-use initialization. No deliberate prewarming. Maven and JVM process startup excluded.'
        summary = $summary
        measurements = $measurements
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ResultsDirectory 'summary.json') -Encoding utf8
    $summary | Sort-Object maxMillis -Descending | Format-Table name, runs, medianMillis, maxMillis, passes -AutoSize | Out-Host
    if (-not $passed -and -not $ReportOnly) {
        throw "The strict <$MaximumMilliseconds ms unit-test gate failed. See $ResultsDirectory."
    }
    Write-Host "Strict per-test latency target passes: $passed. Reports: $ResultsDirectory"
} finally {
    Pop-Location
}
