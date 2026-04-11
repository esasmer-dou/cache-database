param(
    [string]$MavenExecutable = "mvn.cmd"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$rootReportsDir = Join-Path $repoRoot "target\cachedb-prodtest-reports"
$moduleReportsDir = Join-Path $repoRoot "cachedb-production-tests\target\cachedb-prodtest-reports"

function Invoke-Maven {
    param(
        [string[]]$Arguments
    )

    Write-Host ""
    Write-Host "==> $MavenExecutable $($Arguments -join ' ')"
    $global:LASTEXITCODE = 0
    if ($IsWindows -and [System.IO.Path]::GetExtension($MavenExecutable).Equals(".cmd", [System.StringComparison]::OrdinalIgnoreCase)) {
        $quotedArguments = $Arguments | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_.Replace('"', '\"')) + '"'
            } else {
                $_
            }
        }

        $command = '"' + $MavenExecutable + '" ' + ($quotedArguments -join ' ')
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $cmdExecutable = $env:ComSpec
        if ([string]::IsNullOrWhiteSpace($cmdExecutable)) {
            $cmdExecutable = [Environment]::GetEnvironmentVariable("ComSpec", "Machine")
        }
        if ([string]::IsNullOrWhiteSpace($cmdExecutable)) {
            $systemRoot = $env:SystemRoot
            if ([string]::IsNullOrWhiteSpace($systemRoot)) {
                $systemRoot = [Environment]::GetEnvironmentVariable("SystemRoot", "Machine")
            }
            if ([string]::IsNullOrWhiteSpace($systemRoot)) {
                $systemRoot = "C:\Windows"
            }
            $cmdExecutable = Join-Path $systemRoot "System32\cmd.exe"
        }
        if ($cmdExecutable -match "%SystemRoot%") {
            $systemRoot = $env:SystemRoot
            if ([string]::IsNullOrWhiteSpace($systemRoot)) {
                $systemRoot = [Environment]::GetEnvironmentVariable("SystemRoot", "Machine")
            }
            if ([string]::IsNullOrWhiteSpace($systemRoot)) {
                $systemRoot = "C:\Windows"
            }
            $cmdExecutable = $cmdExecutable -ireplace "%SystemRoot%", [Regex]::Escape($systemRoot).Replace("\\", "\")
        }
        $cmdExecutable = [Environment]::ExpandEnvironmentVariables($cmdExecutable)
        $psi.FileName = $cmdExecutable
        $psi.Arguments = "/c $command"
        $psi.WorkingDirectory = $repoRoot
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true

        $process = [System.Diagnostics.Process]::Start($psi)
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        if (-not [string]::IsNullOrWhiteSpace($stdout)) {
            Write-Host $stdout.TrimEnd()
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            Write-Host $stderr.TrimEnd()
        }

        $exitCode = $process.ExitCode
    } else {
        & $MavenExecutable @Arguments
        $exitCode = $global:LASTEXITCODE
    }
    if ($exitCode -ne 0) {
        throw "Maven failed with exit code $exitCode"
    }
}

foreach ($directory in @($rootReportsDir, $moduleReportsDir)) {
    if (Test-Path $directory) {
        Remove-Item -Recurse -Force $directory
    }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

Invoke-Maven @(
    "-pl", "cachedb-production-tests",
    "-am",
    "test",
    "-DforkCount=0",
    "-Dtest=RepositoryRecipeBenchmarkSmokeTest,ReadShapeBenchmarkSmokeTest,RankedProjectionBenchmarkSmokeTest,BenchmarkReportGenerationTest",
    "-Dcachedb.prod.reportDir=$rootReportsDir",
    "-Dsurefire.failIfNoSpecifiedTests=false"
)

Write-Host ""
Write-Host "Production evidence run completed."
Write-Host "Expected report locations:"
Write-Host " - $rootReportsDir"
Write-Host " - $moduleReportsDir"
