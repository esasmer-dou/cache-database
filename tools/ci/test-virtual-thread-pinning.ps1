#requires -Version 7.2
<#
.SYNOPSIS
Instrumentation control: real monitor pinning must fail; lock parking must pass.
.DESCRIPTION
Creates a bounded Java source-mode fixture and two completed
recordings in a NEW evidence directory. No Maven, provisioning or application code.
Requires the same capability-qualified JDK 21 and settings used by the evidence job.
#>
[CmdletBinding()]
param(
    [ValidateLength(1, 128)]
    [string]$KubernetesContext = 'unspecified',
    [Parameter(Mandatory)]
    [string]$EvidenceDirectory,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$total = [Diagnostics.Stopwatch]::StartNew()

function Invoke-ControlProcess([string]$Executable, [string[]]$Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $start.Environment['JAVA_TOOL_OPTIONS'] = '-Xms16m -Xmx256m'
    $start.Environment.Remove('_JAVA_OPTIONS') | Out-Null
    $start.Environment.Remove('JDK_JAVA_OPTIONS') | Out-Null
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $stdout = [IO.MemoryStream]::new()
    $stderr = [IO.MemoryStream]::new()
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Unable to start control process.' }
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $outBuffer = [byte[]]::new(8192)
        $errBuffer = [byte[]]::new(8192)
        $outTask = $process.StandardOutput.BaseStream.ReadAsync($outBuffer, 0, $outBuffer.Length)
        $errTask = $process.StandardError.BaseStream.ReadAsync($errBuffer, 0, $errBuffer.Length)
        $outDone = $false
        $errDone = $false
        while (-not ($outDone -and $errDone -and $process.HasExited)) {
            if ($timer.Elapsed.TotalSeconds -ge 40 -or $total.Elapsed.TotalSeconds -ge 120) {
                throw 'Control process/total deadline exceeded (40/120 seconds).'
            }
            if (-not $outDone -and $outTask.IsCompleted) {
                $count = $outTask.GetAwaiter().GetResult()
                if ($count -eq 0) { $outDone = $true }
                else {
                    if ($stdout.Length + $count -gt 256KB) { throw 'Control stdout exceeds 256 KiB.' }
                    $stdout.Write($outBuffer, 0, $count)
                    $outTask = $process.StandardOutput.BaseStream.ReadAsync($outBuffer, 0, $outBuffer.Length)
                }
            }
            if (-not $errDone -and $errTask.IsCompleted) {
                $count = $errTask.GetAwaiter().GetResult()
                if ($count -eq 0) { $errDone = $true }
                else {
                    if ($stderr.Length + $count -gt 64KB) { throw 'Control stderr exceeds 64 KiB.' }
                    $stderr.Write($errBuffer, 0, $count)
                    $errTask = $process.StandardError.BaseStream.ReadAsync($errBuffer, 0, $errBuffer.Length)
                }
            }
            Start-Sleep -Milliseconds 10
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = [Text.Encoding]::UTF8.GetString($stdout.GetBuffer(), 0, [int]$stdout.Length)
            StdoutBytes = $stdout.ToArray()
            StderrBytes = $stderr.ToArray()
        }
    } finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true)
            if (-not $process.WaitForExit(2000)) { Write-Warning 'Control child exceeded the cleanup deadline.' }
        }
        $process.Dispose()
        $stdout.Dispose()
        $stderr.Dispose()
    }
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'JavaHome is required.' }
$release = Get-Item -LiteralPath (Join-Path $JavaHome 'release')
if ($release.Length -gt 64KB -or
    (Get-Content -LiteralPath $release.FullName -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:\.|[+"]|-)') {
    throw 'Controls require the evidence JDK 21.'
}
$destination = [IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $destination) { throw 'Use a NEW evidence directory; existing evidence is never overwritten.' }
$null = [IO.Directory]::CreateDirectory($destination)
$fixture = Join-Path $destination 'VirtualThreadPinningControl.java'
$source = @'
package com.reactor.cachedb.ci;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

public final class VirtualThreadPinningControl {
    private static final Object MONITOR = new Object();
    private static final ReentrantLock LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("directory and settings required");
        if (FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .noneMatch(type -> type.getName().equals("jdk.VirtualThreadPinned"))) {
            throw new IllegalStateException("VM has no jdk.VirtualThreadPinned event");
        }
        Configuration config = Configuration.create(Path.of(args[1]));
        if (!"true".equals(config.getSettings().get("jdk.VirtualThreadPinned#enabled"))
                || !"true".equals(config.getSettings().get("jdk.VirtualThreadPinned#stackTrace"))
                || !"20 ms".equals(config.getSettings().get("jdk.VirtualThreadPinned#threshold"))) {
            throw new IllegalStateException("Controls require enabled stacks and the 20 ms threshold");
        }
        run(config, Path.of(args[0], "monitor.jfr"), true);
        run(config, Path.of(args[0], "reentrant.jfr"), false);
        System.out.println("Two completed virtual-thread control recordings written");
    }

    private static void run(Configuration config, Path output, boolean monitor) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (Recording recording = new Recording(config)) {
            recording.setName(monitor ? "cachedb-monitor-control" : "cachedb-lock-control");
            recording.setMaxSize(64L * 1024 * 1024);
            recording.setDuration(Duration.ofSeconds(15));
            recording.start();
            Thread worker = Thread.ofVirtual().name("cachedb-pinning-control").start(() -> {
                try {
                    if (!Thread.currentThread().isVirtual()) throw new AssertionError("Not a virtual thread");
                    if (monitor) {
                        synchronized (MONITOR) { boundedPark(); }
                    } else {
                        LOCK.lock();
                        try { boundedPark(); } finally { LOCK.unlock(); }
                    }
                } catch (Throwable error) { failure.set(error); }
            });
            if (!worker.join(Duration.ofSeconds(10))) {
                worker.interrupt();
                throw new IllegalStateException("Control worker exceeded 10 seconds");
            }
            if (failure.get() != null) throw new AssertionError("Control worker failed", failure.get());
            recording.stop();
            recording.dump(output);
        }
    }

    private static void boundedPark() {
        // A real carrier-pinning park, not a synthetic JFR event or mocked JSON.
        long until = System.nanoTime() + Duration.ofMillis(250).toNanos();
        for (int parks = 0; parks < 1024; parks++) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return;
            LockSupport.parkNanos(remaining);
        }
        throw new AssertionError("Excessive spurious wakeups");
    }
}
'@
[IO.File]::WriteAllText($fixture, $source, [Text.UTF8Encoding]::new($false))
$java = Join-Path $JavaHome 'bin/java'
if ($IsWindows) { $java += '.exe' }
$settings = Join-Path $PSScriptRoot 'virtual-threads.jfc'
$control = Invoke-ControlProcess $java @('-XX:FlightRecorderOptions=stackdepth=256', '--source', '21',
        $fixture, $destination, $settings)
if ($control.ExitCode -ne 0) { throw "Real JFR control generation failed: exit=$($control.ExitCode)." }

$checker = Join-Path $PSScriptRoot 'check-virtual-thread-pinning.ps1'
$pwsh = (Get-Command pwsh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$results = [Collections.Generic.List[object]]::new()
foreach ($case in @('monitor', 'reentrant')) {
    $recording = Join-Path $destination "$case.jfr"
    $arguments = @('-NoProfile', '-File', $checker, '-RecordingPath', $recording,
        '-KubernetesContext', $KubernetesContext, '-JavaHome', $JavaHome,
        '-MaxEvents', '1000', '-ProcessTimeoutSeconds', '20', '-TotalTimeoutSeconds', '30')
    $checked = Invoke-ControlProcess $pwsh $arguments
    $retained = Join-Path $destination "$case.checker"
    [IO.File]::WriteAllText("$retained.exit", "$($checked.ExitCode)`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes("$retained.stdout.json", $checked.StdoutBytes)
    [IO.File]::WriteAllBytes("$retained.stderr.log", $checked.StderrBytes)
    try {
        $report = ConvertFrom-Json -InputObject $checked.Stdout -Depth 16
    } catch {
        throw "Checker $case control report is invalid JSON; inspect retained private checker evidence."
    }
    $failedPredicates = [Collections.Generic.List[string]]::new()
    if ($case -eq 'monitor') {
        if ($checked.ExitCode -ne 1) { $failedPredicates.Add('ExitCode == 1') }
        if ($report.result -ne 'FAIL_PINNING') { $failedPredicates.Add('report.result == FAIL_PINNING') }
        if ($report.cachedbPinnedEvents -lt 1) { $failedPredicates.Add('report.cachedbPinnedEvents >= 1') }
    } else {
        if ($checked.ExitCode -ne 0) { $failedPredicates.Add('ExitCode == 0') }
        if ($report.result -ne 'NO_CACHEDB_PINNING_OBSERVED') { $failedPredicates.Add('report.result == NO_CACHEDB_PINNING_OBSERVED') }
        if ($report.pinnedEvents -ne 0) { $failedPredicates.Add('report.pinnedEvents == 0') }
        if ($report.incompleteStacks -ne 0) { $failedPredicates.Add('report.incompleteStacks == 0') }
    }
    if ($failedPredicates.Count -gt 0) {
        throw "Checker $case control failed predicate(s): $($failedPredicates -join '; '). Inspect retained private checker evidence."
    }
    $results.Add([ordered]@{ control = $case; expectedExit = $checked.ExitCode; evidence = $report })
}
if ($total.Elapsed.TotalSeconds -ge 120) { throw 'Control total deadline exceeded.' }
[ordered]@{ result = 'CONTROLS_PASSED'; controls = $results } | ConvertTo-Json -Depth 10
