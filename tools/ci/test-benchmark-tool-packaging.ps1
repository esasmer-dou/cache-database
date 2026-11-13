#requires -Version 7.2
param(
    [string]$RepositoryRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if (!$EvidenceDirectory) {
    $EvidenceDirectory=Join-Path ([IO.Path]::GetTempPath()) ('cachedb-tool-packaging-'+[guid]::NewGuid().ToString('N'))
}
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence directory must be new.' }
$null=New-Item -ItemType Directory -Path $EvidenceDirectory
$RepositoryRoot=(Get-Item -LiteralPath $RepositoryRoot).FullName
$passed=[Collections.Generic.List[string]]::new()
$clock=[Diagnostics.Stopwatch]::StartNew()
function Pass([string]$Name) { $passed.Add($Name); Write-Host "PASS $Name" }
function Invoke-Bounded([string]$Command, [string[]]$Arguments, [string]$Name, [string]$InputText='', [int]$Seconds=120) {
    # Reserve the termination/drain budget inside, not after, the overall deadline.
    $Seconds=[int][Math]::Min($Seconds,[Math]::Floor(180-$clock.Elapsed.TotalSeconds-2))
    if ($Seconds -lt 1) { throw 'Overall checker-packaging control deadline exceeded.' }
    $start=[Diagnostics.ProcessStartInfo]::new()
    $application=Get-Command $Command -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $start.FileName=$application.Source
    $start.UseShellExecute=$false; $start.RedirectStandardInput=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach ($arg in $Arguments) { $start.ArgumentList.Add($arg) }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$start
    $streams=@()
    $state=@{failure=''; timedOut=$false; outputLimitExceeded=$false}
    $started=$false; $killAttempted=$false; $killError=''; $inputClosed=$false
    $inputTask=$null; $startedUtc=[DateTimeOffset]::UtcNow
    $elapsed=[Diagnostics.Stopwatch]::StartNew()
    $receive={
        foreach ($stream in $streams) {
            if ($stream.ended -or !$stream.task.IsCompleted) { continue }
            try {
                $count=$stream.task.GetAwaiter().GetResult()
                if (!$count) { $stream.ended=$true; continue }
                $stream.observed+=$count
                $remaining=64KB-[int]$stream.capture.Length
                if ($remaining -gt 0) {
                    $stream.capture.Write($stream.buffer,0,[Math]::Min($count,$remaining))
                }
                if ($stream.observed -gt 64KB) {
                    $state.outputLimitExceeded=$true
                    if (!$state.failure) { $state.failure='Packaging control output exceeded 64 KiB.' }
                }
                $stream.task=$stream.pipe.ReadAsync($stream.buffer,0,$stream.buffer.Length)
            } catch {
                $stream.ended=$true; $stream.readError=$_.Exception.Message
                if (!$state.failure) { $state.failure="Packaging control capture failed: $Name" }
            }
        }
    }
    try {
        $null=$process.Start()
        $started=$true
        foreach ($pipe in @($process.StandardOutput.BaseStream,$process.StandardError.BaseStream)) {
            $buffer=[byte[]]::new(4096)
            $streams+=@{pipe=$pipe; buffer=$buffer; capture=[IO.MemoryStream]::new();
                task=$pipe.ReadAsync($buffer,0,$buffer.Length); ended=$false; observed=0L; readError=''}
        }
        $inputBytes=[Text.Encoding]::UTF8.GetBytes($InputText)
        $inputTask=$process.StandardInput.BaseStream.WriteAsync($inputBytes,0,$inputBytes.Length)
        while ($true) {
            & $receive
            if (!$inputClosed -and $inputTask.IsCompleted) {
                $null=$inputTask.GetAwaiter().GetResult(); $process.StandardInput.Close(); $inputClosed=$true
            }
            if ($state.failure) { break }
            if ($process.HasExited -and $streams[0].ended -and $streams[1].ended) { break }
            if ($elapsed.Elapsed.TotalSeconds -ge $Seconds) {
                $state.timedOut=$true; $state.failure="Packaging control deadline exceeded: $Name"
                break
            }
            [Threading.Thread]::Sleep(10)
        }
    } catch {
        if (!$state.failure) { $state.failure="Packaging control execution failed: $Name / $($_.Exception.Message)" }
    } finally {
        $cleanup=[Diagnostics.Stopwatch]::StartNew()
        $cleanupMillis=[Math]::Max(0,[Math]::Min(2000,180000-$clock.Elapsed.TotalMilliseconds))
        try {
            if ($started -and !$process.HasExited) {
                $killAttempted=$true
                try { $process.Kill($true) } catch { $killError=$_.Exception.Message }
            }
            while ($started -and $cleanup.Elapsed.TotalMilliseconds -lt $cleanupMillis) {
                & $receive
                if ($process.HasExited -and $streams.Count -eq 2 -and $streams[0].ended -and $streams[1].ended) { break }
                [Threading.Thread]::Sleep(1)
            }
            $exited=$started -and $process.HasExited
            $exitCode=if ($exited) { $process.ExitCode } else { $null }
            $drained=$streams.Count -eq 2 -and $streams[0].ended -and $streams[1].ended -and
                !$streams[0].readError -and !$streams[1].readError
            if ((!$exited -or !$drained) -and !$state.failure) {
                $state.failure="Packaging control physical completion UNKNOWN: $Name"
            }
            $captured=[object[]]::new(2)
            $captured[0]=[byte[]]::new(0); $captured[1]=[byte[]]::new(0)
            for ($i=0; $i -lt $streams.Count; $i++) { $captured[$i]=$streams[$i].capture.ToArray() }
            [IO.File]::WriteAllBytes((Join-Path $EvidenceDirectory "$Name.stdout.txt"),$captured[0])
            [IO.File]::WriteAllBytes((Join-Path $EvidenceDirectory "$Name.stderr.txt"),$captured[1])
            $outcome=if (!$exited -or !$drained) { 'UNKNOWN' } elseif ($state.timedOut) { 'TIMEOUT' }
                elseif ($state.outputLimitExceeded) { 'OUTPUT_LIMIT' } elseif ($state.failure) { 'ERROR' }
                elseif ($exitCode) { 'NONZERO' } else { 'EXITED' }
            [ordered]@{name=$Name; startedUtc=$startedUtc.ToString('O'); endedUtc=[DateTimeOffset]::UtcNow.ToString('O');
                effectiveDeadlineSeconds=$Seconds; overallDeadlineSeconds=180; elapsedSeconds=$elapsed.Elapsed.TotalSeconds;
                timedOut=$state.timedOut; outputLimitExceeded=$state.outputLimitExceeded; outcome=$outcome;
                processExited=$exited; exitCode=$exitCode; drainComplete=$drained; killAttempted=$killAttempted;
                killError=$killError; cleanupMillis=$cleanup.Elapsed.TotalMilliseconds; cleanupBudgetMillis=$cleanupMillis;
                streamLimitBytes=64KB; stdoutCapturedBytes=$captured[0].Length; stderrCapturedBytes=$captured[1].Length;
                failure=$state.failure} | ConvertTo-Json -Depth 3 |
                Set-Content -LiteralPath (Join-Path $EvidenceDirectory "$Name.process.json") -Encoding utf8
        } finally {
            foreach ($stream in $streams) { $stream.pipe.Dispose(); $stream.capture.Dispose() }
            $process.Dispose()
        }
    }
    if ($state.failure) { throw $state.failure }
    return @{code=$exitCode; output=[Text.Encoding]::UTF8.GetString($captured[0]); error=[Text.Encoding]::UTF8.GetString($captured[1])}
}
function Snapshot([string]$Runner, [string]$Marker, [string]$Name) {
    $source=Get-Content -LiteralPath (Join-Path $RepositoryRoot $Runner) -Raw
    $pattern='(?ms)^# BEGIN '+[regex]::Escape($Marker)+'\r?\n(.*?)^# END '+[regex]::Escape($Marker)+'\r?$'
    $blocks=@([regex]::Matches($source,$pattern))
    if ($blocks.Count -ne 1) { throw "Missing/ambiguous actual copy block: $Name" }
    $block=$blocks[0].Groups[1].Value.Replace("`r",'')
    $dest=Join-Path $EvidenceDirectory "$Name-stage"
    $null=New-Item -ItemType Directory -Path $dest
    # Execute only the runners' literal source/tool copy blocks, never their JVM/build paths.
    $script='set -eu' + "`n" + 'source_root=$1; repo=$1; output=$2' + "`n" + $block
    $result=Invoke-Bounded 'sh' @('-s','--',$RepositoryRoot,$dest) "$Name-copy-block" $script 20
    if ($result.code) { throw "Actual runner copy failed: $Name / $($result.error)" }
    Pass "$Name-actual-copy-block"
    return $dest
}
function Verify-Copy([string]$Kit, [string]$Manifest, [int]$Expected, [string]$Name) {
    $lines=@(Get-Content -LiteralPath $Manifest)
    if ($lines.Count -ne $Expected) { throw "Wrong fixed copy count: $Name" }
    $t1Paths=@('tools/ci/check-t1-coalescing-evidence.ps1','tools/ci/jmh-histogram-consistency.ps1',
        'tools/ci/test-t1-default-acceptance.ps1','tools/ci/test-jmh-histogram-consistency.ps1',
        'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java',
        'tools/benchmarks/redis/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java')
    $t9Paths=@('cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java',
        'tools/benchmarks/redis/pom.xml','tools/benchmarks/redis/run-coalescing-paired.sh','tools/benchmarks/redis/run-features-paired.sh',
        'tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingBenchmark.java',
        'tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingFixtures.java',
        'tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingPreflight.java',
        'tools/benchmarks/redis/src/features/README.md',
        'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisFeatureFixtures.java',
        'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisFeaturePreflight.java',
        'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisNativeAsyncBenchmark.java',
        'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisObservationBenchmark.java',
        'tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisTrackingBenchmark.java',
        'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkFixture.java',
        'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkRunner.java',
        'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisRepositoryBenchmark.java',
        'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/VirtualBenchmarkExecutor.java',
        'tools/ci/check-t1-coalescing-evidence.ps1','tools/ci/check-t9-feature-evidence.ps1',
        'tools/ci/jmh-histogram-consistency.ps1','tools/ci/test-t1-default-acceptance.ps1',
        'tools/ci/test-t9-feature-evidence.ps1','tools/ci/test-jmh-histogram-consistency.ps1',
        'tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java',
        'tools/benchmarks/redis/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java')
    $expectedPaths=if ($Expected -eq 6) {$t1Paths} elseif ($Expected -eq 25) {$t9Paths} else {throw 'Unknown fixed kit roster.'}
    $actualPaths=@($lines | ForEach-Object { if ($_ -notmatch '^[a-fA-F0-9]{64}  (.+)$') {throw 'Malformed packaged-source digest.'}; $Matches[1] })
    if (($actualPaths -join "`0") -cne ($expectedPaths -join "`0") -or
            @(Get-ChildItem -LiteralPath $Kit -Recurse -Force -File).Count -ne $Expected) { throw "Wrong exact source roster: $Name" }
    foreach ($line in $lines) {
        if ($line -notmatch '^([a-fA-F0-9]{64})  ([A-Za-z0-9_./-]+)$') { throw 'Malformed packaged-source digest.' }
        $digest=$Matches[1]; $path=$Matches[2]
        if ($path -match '(^|/)\.\.(/|$)|(^|/)(target|\.m2)(/|$)|\.jar$') { throw 'Checker kit contains runtime artifacts or unsafe paths.' }
        if ((Get-FileHash -LiteralPath (Join-Path $Kit $path)).Hash -ine $digest -or
                (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot $path)).Hash -ine $digest) {
            throw "Copied checker/source digest mismatch: $path"
        }
    }
    foreach ($leaf in @('check-t1-coalescing-evidence.ps1','jmh-histogram-consistency.ps1',
            'test-t1-default-acceptance.ps1','test-jmh-histogram-consistency.ps1')) {
        if (!(Test-Path -LiteralPath (Join-Path $Kit "tools/ci/$leaf") -PathType Leaf)) { throw "Incomplete checker dependency closure: $leaf" }
    }
}
function Archive-RoundTrip([string]$Kit, [string]$Name) {
    $archive=Join-Path $EvidenceDirectory "$Name-tools.tar"
    $copy=Join-Path $EvidenceDirectory "$Name-extracted"
    $null=New-Item -ItemType Directory -Path $copy
    $pack=Invoke-Bounded 'tar' @('-C',$Kit,'-cf',$archive,'.') "$Name-tar" '' 20
    if ($pack.code) { throw 'Checker kit tar failed.' }
    $unpack=Invoke-Bounded 'tar' @('-C',$copy,'-xf',$archive) "$Name-untar" '' 20
    if ($unpack.code) { throw 'Checker kit untar failed.' }
    Pass "$Name-tar-round-trip"
    return $copy
}
function Run-Copied([string]$Kit, [string]$File, [string]$Name, [string]$Marker) {
    $args=@('-NoLogo','-NoProfile','-NonInteractive','-File',(Join-Path $Kit "tools/ci/$File"))
    if ($File -ne 'test-t1-default-acceptance.ps1') {
        $args+=@('-EvidenceDirectory',(Join-Path $EvidenceDirectory "$Name-fixtures"))
    }
    $result=Invoke-Bounded (Join-Path $PSHOME 'pwsh') $args $Name
    if ($result.code -or $result.output -notmatch [regex]::Escape($Marker)) {
        throw "Copied selftest failed: $Name / $($result.error)"
    }
    Pass $Name
}
$t1=Snapshot 'tools/benchmarks/redis/run-coalescing-paired.sh' 'T1 CHECKER TOOL SNAPSHOT' 't1'
$t1Kit=Join-Path $t1 'checker-tools'
Verify-Copy $t1Kit (Join-Path $t1 'checker-tools.sha256') 6 't1'
Pass 't1-six-file-closure-hashes'
$t1Copy=Archive-RoundTrip $t1Kit 't1'
Verify-Copy $t1Copy (Join-Path $t1 'checker-tools.sha256') 6 't1-archive'
Pass 't1-archive-closure-hashes'
Run-Copied $t1Copy 'test-t1-default-acceptance.ps1' 't1-copied-selftest' 'T1_SCHEMA_SELFTEST_PASS'
Run-Copied $t1Copy 'test-jmh-histogram-consistency.ps1' 't1-copied-histogram-selftest' 'JMH137_HISTOGRAM_CONTROLS_PASS'
$t9=Snapshot 'tools/benchmarks/redis/run-features-paired.sh' 'FEATURE SOURCE SNAPSHOT' 't9'
$t9Kit=Join-Path $t9 'sources'
Verify-Copy $t9Kit (Join-Path $t9 'sources.sha256') 25 't9'
Pass 't9-complete-source-and-selftest-closure-hashes'
$t9Copy=Archive-RoundTrip $t9Kit 't9'
Verify-Copy $t9Copy (Join-Path $t9 'sources.sha256') 25 't9-archive'
Pass 't9-archive-closure-hashes'
Run-Copied $t9Copy 'test-t9-feature-evidence.ps1' 't9-copied-selftest' 'T9_CHECKER_SELFTEST_PASS'
Run-Copied $t9Copy 'test-t1-default-acceptance.ps1' 't9-copied-t1-selftest' 'T1_SCHEMA_SELFTEST_PASS'
Run-Copied $t9Copy 'test-jmh-histogram-consistency.ps1' 't9-copied-histogram-selftest' 'JMH137_HISTOGRAM_CONTROLS_PASS'
foreach ($case in @(@{kit=$t1Copy; manifest=(Join-Path $t1 'checker-tools.sha256'); count=6; name='t1'},
        @{kit=$t9Copy; manifest=(Join-Path $t9 'sources.sha256'); count=25; name='t9'})) {
    foreach ($path in @('tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java',
            'tools/benchmarks/redis/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java')) {
        $file=Join-Path $case.kit $path
        $bytes=[IO.File]::ReadAllBytes($file)
        try {
            [IO.File]::Delete($file)
            $caught=$false
            try { Verify-Copy $case.kit $case.manifest $case.count $case.name } catch {$caught=$true}
            if (!$caught) {throw 'Packaging accepted a missing compactor source.'}
            Pass "$($case.name)-missing-$([IO.Path]::GetFileName($path))-rejected"
            [IO.File]::WriteAllText($file,'TAMPERED',[Text.UTF8Encoding]::new($false))
            $caught=$false
            try { Verify-Copy $case.kit $case.manifest $case.count $case.name } catch {$caught=$true}
            if (!$caught) {throw 'Packaging accepted a tampered compactor source.'}
            Pass "$($case.name)-tampered-$([IO.Path]::GetFileName($path))-rejected"
        } finally { [IO.File]::WriteAllBytes($file,$bytes) }
    }
}
foreach ($kit in @($t1Copy,$t9Copy)) {
    $helper=Join-Path $kit 'tools/ci/jmh-histogram-consistency.ps1'
    $hidden=$helper+'.disabled'
    Move-Item -LiteralPath $helper -Destination $hidden
    try {
        $name=if ($kit -eq $t1Copy) {'t1-missing-helper'} else {'t9-missing-helper'}
        $result=Invoke-Bounded (Join-Path $PSHOME 'pwsh') @('-NoLogo','-NoProfile','-NonInteractive','-File',
            (Join-Path $kit 'tools/ci/test-jmh-histogram-consistency.ps1'),'-EvidenceDirectory',
            (Join-Path $EvidenceDirectory "$name-fixtures")) $name
        if (!$result.code -or ($result.output+$result.error) -notmatch 'jmh-histogram-consistency') {
            throw 'A copied checker silently ignored its missing helper.'
        }
        Pass "$name-rejected"
    } finally { Move-Item -LiteralPath $hidden -Destination $helper }
}
[ordered]@{result='PASS'; controls=$passed; t1Files=6; t9Files=25; actualCopyBlocksExecuted=$true;
    tarRoundTrips=2; copiedSelftestsExecuted=5; javaExecuted=$false; mavenExecuted=$false;
    benchmarkExecuted=$false; sdkArtifactsIncluded=$false; performanceQualified=$false} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
Write-Output "BENCHMARK_TOOL_PACKAGING_CONTROLS_PASS=$($passed.Count); NO_JAVA_BUILD_JMH_OR_PERFORMANCE_ACCEPTANCE"
