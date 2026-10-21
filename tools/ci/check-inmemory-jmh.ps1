param(
    [ValidateSet('Warmed', 'ColdAndWarmed')]
    [string]$Acceptance = 'ColdAndWarmed',
    [ValidateRange(1, 1000)]
    [double]$MaximumMilliseconds = 50,
    [switch]$SkipBuild,
    [switch]$AnalyzeOnly,
    [string]$BenchmarkJavaHome = $env:JAVA_HOME,
    [string]$ResultsDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($ResultsDirectory)) {
    $ResultsDirectory = Join-Path $repoRoot 'target/inmemory-latency'
}
$ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
$null = New-Item -ItemType Directory -Force -Path $ResultsDirectory
if ([string]::IsNullOrWhiteSpace($BenchmarkJavaHome)) { throw 'Set JAVA_HOME or BenchmarkJavaHome.' }
$java = Join-Path $BenchmarkJavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $BenchmarkJavaHome 'bin/java' }
if (-not (Test-Path -LiteralPath $java)) { throw "Java executable not found under BenchmarkJavaHome." }
$jar = Join-Path $repoRoot 'tools/benchmarks/inmemory/target/benchmarks.jar'

Push-Location $repoRoot
try {
    if (-not $SkipBuild -and -not $AnalyzeOnly) {
        $maven = (Get-Command mvn -ErrorAction Stop).Source
        & $maven -B -ntp -P inmemory-benchmarks -pl tools/benchmarks/inmemory -am package '-DskipTests' `
            2>&1 | Tee-Object -FilePath (Join-Path $ResultsDirectory 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'In-memory benchmark build failed.' }
    }
    $json = Join-Path $ResultsDirectory 'jmh.json'
    if (-not $AnalyzeOnly) {
        if (-not (Test-Path -LiteralPath $jar)) { throw "Missing benchmark JAR: $jar" }
        & $java -jar $jar 'InMemoryFixtureBenchmark.*' -foe true -prof gc -rf json -rff $json `
            2>&1 | Tee-Object -FilePath (Join-Path $ResultsDirectory 'jmh.log')
        if ($LASTEXITCODE -ne 0) { throw 'JMH failed; incomplete results are not acceptance evidence.' }
    }
    $jmhLog = Get-Content -LiteralPath (Join-Path $ResultsDirectory 'jmh.log') -Raw
    $supportedVm = $jmhLog -notmatch 'This VM is not supported by JMH'

    $results = @(Get-Content -LiteralPath $json -Raw | ConvertFrom-Json)
    $summary = @()
    foreach ($scenario in @('entity', 'batch', 'paging', 'snapshot')) {
        foreach ($mode in @('coldFixture', 'warmedFixture')) {
            $matches = @($results | Where-Object {
                $_.benchmark.EndsWith(".$mode") -and $_.params.scenario -eq $scenario
            })
            if ($matches.Count -ne 1) { throw "Expected exactly one $mode/$scenario result." }
            $result = $matches[0]
            $metric = $result.primaryMetric
            if ($result.params.rows -ne '100' -or $metric.scoreUnit -ne 'ms/op') {
                throw 'Unexpected fixture size or unit; refusing a misleading comparison.'
            }
            $isCold = $mode -eq 'coldFixture'
            if (($isCold -and ($result.forks -ne 5 -or $result.warmupIterations -ne 0)) -or
                (-not $isCold -and ($result.forks -ne 3 -or $result.warmupIterations -lt 5))) {
                throw 'Unexpected fork/warmup configuration.'
            }
            $p95 = [double]$metric.scorePercentiles.'95.0'
            $maximum = [double]$metric.scorePercentiles.'100.0'
            if ([double]::IsNaN($p95) -or [double]::IsInfinity($p95) -or $p95 -lt 0) {
                throw 'Invalid latency measurement.'
            }
            $summary += [pscustomobject]@{
                scenario = $scenario
                mode = $mode
                forks = $result.forks
                p95Millis = $p95
                maxMillis = $maximum
                passes = ($maximum -lt $MaximumMilliseconds)
            }
        }
    }
    $coldPasses = @($summary | Where-Object { $_.mode -eq 'coldFixture' -and -not $_.passes }).Count -eq 0
    $warmedPasses = @($summary | Where-Object { $_.mode -eq 'warmedFixture' -and -not $_.passes }).Count -eq 0
    $report = [ordered]@{
        generatedUtc = [DateTime]::UtcNow.ToString('o')
        acceptance = $Acceptance
        exclusiveMaximumMillis = $MaximumMilliseconds
        coldPasses = $coldPasses
        warmedPasses = $warmedPasses
        fullTargetPasses = ($coldPasses -and $warmedPasses -and $supportedVm)
        supportedJmhVm = $supportedVm
        scope = 'Single-thread complete in-memory fixtures; not Maven/JVM startup, Redis or SQL latency.'
        measurements = $summary
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ResultsDirectory 'summary.json') -Encoding utf8
    $summary | Format-Table -AutoSize | Out-Host
    Write-Host "Warmed=$warmedPasses Cold=$coldPasses FullTarget=$($report.fullTargetPasses)"
    if (-not $supportedVm) {
        throw 'JMH reports an unsupported VM. Results are diagnostic only, not acceptance evidence. Run the JUnit contracts on the target VM.'
    }
    if (-not $warmedPasses -or ($Acceptance -eq 'ColdAndWarmed' -and -not $coldPasses)) {
        throw "The requested $Acceptance latency gate failed. See $ResultsDirectory."
    }
} finally {
    Pop-Location
}
