#requires -Version 7.2
<#
.SYNOPSIS
Compatibility entry point for the standalone Java resource-guard controls.
.DESCRIPTION
Runs Maven verify for tools/ci/resource-guard only. No Kubernetes requests,
benchmark execution, SDK install or replacement PowerShell resource fixtures.
#>
[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=(Resolve-Path -LiteralPath $RepositoryRoot).Path
$module=Join-Path $root 'tools/ci/resource-guard'
$pom=Join-Path $module 'pom.xml'
$action=Join-Path $root '.github/actions/cachedb-v019-performance/action.yml'
if (!$EvidenceDirectory) {
    $EvidenceDirectory=Join-Path ([IO.Path]::GetTempPath()) ('cachedb-resource-contract-'+[guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null=New-Item -ItemType Directory -Path $EvidenceDirectory
$evidence=(Resolve-Path -LiteralPath $EvidenceDirectory).Path
$wiringTest=Join-Path $root 'tools/ci/test-performance-execution-wiring.ps1'
$wiringInputs=@('.github/workflows/performance-gate.yml',
    '.github/actions/cachedb-redis-performance/action.yml',
    'tools/ci/prepare-ematech-resource-guard.sh','tools/ci/run-ematech-redis-performance.sh') |
    ForEach-Object { Join-Path $root $_ }
$inputs=@((Join-Path $root 'pom.xml'),$pom,$action,$PSCommandPath,$wiringTest)+@($wiringInputs)+@(
    Get-ChildItem -LiteralPath (Join-Path $module 'src') -Recurse -File | ForEach-Object FullName
)
$before=@{}
foreach ($path in $inputs) { $before[$path]=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
$before.Keys | Sort-Object | ForEach-Object { $before[$_].ToLowerInvariant()+'  '+$_ } |
    Set-Content -LiteralPath (Join-Path $evidence 'inputs.sha256') -Encoding utf8
$p=[Diagnostics.Process]::new()
$p.StartInfo.FileName=(Get-Command mvn -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
foreach ($arg in @('-B','-ntp','-f',$pom,"-Dcachedb.resource.evidence=$evidence",
        '-DskipTests=false','-Dmaven.test.skip=false','verify')) { $p.StartInfo.ArgumentList.Add($arg) }
$p.StartInfo.WorkingDirectory=$root
$p.StartInfo.UseShellExecute=$false
$p.StartInfo.RedirectStandardInput=$true
$p.StartInfo.RedirectStandardOutput=$true
$p.StartInfo.RedirectStandardError=$true
$p.StartInfo.Environment['MAVEN_OPTS']=([string]$env:MAVEN_OPTS+' -Xmx256m').Trim()
$outPath=Join-Path $evidence 'maven.stdout.log'; $errPath=Join-Path $evidence 'maven.stderr.log'
$outFile=[IO.File]::Open($outPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite)
$errFile=[IO.File]::Open($errPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite)
$cancel=[Threading.CancellationTokenSource]::new()
$clock=[Diagnostics.Stopwatch]::StartNew()
$started=$false; $failure=''; $reportedControls=0
try {
    $started=$p.Start()
    if (!$started) { throw 'Cannot launch standalone resource-guard verification.' }
    $p.StandardInput.Close()
    $o=$p.StandardOutput.BaseStream.CopyToAsync($outFile,8192,$cancel.Token)
    $e=$p.StandardError.BaseStream.CopyToAsync($errFile,8192,$cancel.Token)
    while (!$p.WaitForExit(50)) {
        if ($clock.ElapsedMilliseconds -ge 300000) { throw 'Standalone resource controls exceeded five minutes.' }
        if ((Get-Item -LiteralPath $outPath).Length -gt 2MB -or
                (Get-Item -LiteralPath $errPath).Length -gt 2MB) { throw 'Maven output exceeded 2 MiB per stream.' }
    }
    $drains=[Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($o,$e))
    if (!$drains.Wait(2000)) { throw 'Maven output drain exceeded two seconds.' }
    $outFile.Flush(); $errFile.Flush()
    if ((Get-Item -LiteralPath $outPath).Length -gt 2MB -or
            (Get-Item -LiteralPath $errPath).Length -gt 2MB) { throw 'Maven output exceeded 2 MiB per stream.' }
    if ($p.ExitCode -ne 0) { throw "Standalone resource-guard Maven verify failed: exit $($p.ExitCode)." }
    $summaryPath=Join-Path $evidence 'summary.json'
    if (!(Test-Path -LiteralPath $summaryPath) -or (Get-Item -LiteralPath $summaryPath).Length -gt 1MB) {
        throw 'Maven verification did not produce a bounded fresh Java controls summary.'
    }
    $summary=Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    if ($summary.result -cne 'PASS' -or $summary.syntheticOnly -ne $true -or
            $summary.benchmarkExecuted -ne $false -or $summary.actualBenchmarkQualified -ne $false) {
        throw 'Standalone controls summary is incomplete or claims benchmark qualification.'
    }
    $reportedControls=@($summary.controls).Count
    if ($reportedControls -lt 23) { throw 'Standalone summary omits the original resource-control coverage.' }
    $reports=@(Get-ChildItem -LiteralPath (Join-Path $module 'target/surefire-reports') -Filter 'TEST-*.xml' -File)
    if (!$reports.Count -or $reports.Count -gt 32) { throw 'Missing or unbounded Java test reports.' }
    $tests=0L
    foreach ($report in $reports) {
        $settings=[Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver=$null; $settings.MaxCharactersInDocument=2MB
        $reader=[Xml.XmlReader]::Create($report.FullName,$settings)
        try { $xml=[Xml.XmlDocument]::new(); $xml.XmlResolver=$null; $xml.Load($reader) }
        finally { $reader.Dispose() }
        $suite=$xml.DocumentElement
        # PowerShell's XML adapter maps .Name to Surefire's name attribute, not the element tag.
        if ($suite.LocalName -cne 'testsuite' -or [long]$suite.GetAttribute('tests') -le 0 -or
                $suite.SelectNodes('testcase').Count -ne [long]$suite.GetAttribute('tests') -or
                $suite.GetAttribute('failures') -cne '0' -or $suite.GetAttribute('errors') -cne '0' -or
                $suite.GetAttribute('skipped') -cne '0' -or $suite.SelectNodes('testcase/failure|testcase/error|testcase/skipped').Count) {
            throw 'Java resource controls were missing, failed or skipped.'
        }
        $tests+=[long]$suite.GetAttribute('tests')
    }
    $body=Get-Content -LiteralPath $action -Raw
    foreach ($token in @('qualify --container','same --container','snapshot --container','compare --expected',
            '--fresh --github-output','resource-guard-final.stderr.log','test "$resource_match" = true')) {
        if (!$body.Contains($token)) { throw "Action is missing the shared resource contract: $token" }
    }
    if ($body -match '(?m)^\s+(?:jq\s|.*\|\s*jq\s)' -or $body.Contains('pod-resources";')) {
        throw 'Action still depends on the replaced resource parser.'
    }
    # Existing canonical control 18 also covers the new execution wiring, not a new obligation.
    & $wiringTest -RepositoryRoot $root -EvidenceDirectory (Join-Path $evidence 'execution-wiring')
    foreach ($path in $before.Keys) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $before[$path]) {
            throw "Resource-control input changed during verification: $path"
        }
    }
    Write-Host "RESOURCE_JAVA_CONTROLS_PASS=$reportedControls; JUNIT_TESTS=$tests; SYNTHETIC_ONLY; NO_BENCHMARK_ACCEPTANCE"
} catch { $failure=$_.Exception.Message; throw }
finally {
    if ($started -and !$p.HasExited) { $p.Kill($true); $null=$p.WaitForExit(2000) }
    $cancel.Cancel()
    $outFile.Dispose(); $errFile.Dispose(); $cancel.Dispose(); $p.Dispose()
    $reportsPath=Join-Path $module 'target/surefire-reports'
    if (Test-Path -LiteralPath $reportsPath) {
        $reportCopy=Join-Path $evidence 'surefire-reports'
        $null=New-Item -ItemType Directory -Force -Path $reportCopy
        Get-ChildItem -LiteralPath $reportsPath -File | ForEach-Object {
            if ($_.Length -le 2MB) { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $reportCopy $_.Name) }
        }
    }
    [ordered]@{result=$(if($failure){'FAIL'}else{'PASS'});error=$failure;reportedControls=$reportedControls;
        javaExecuted=$started;syntheticOnly=$true;benchmarkExecuted=$false;actualBenchmarkQualified=$false;
        sdkInstalled=$false;remoteCiQualified=$false} | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $evidence 'wrapper-summary.json') -Encoding utf8
}
