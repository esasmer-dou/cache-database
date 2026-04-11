param(
    [string]$MavenExecutable = "",
    [string]$RedisUri = "redis://default:welcome1@127.0.0.1:56379",
    [string]$PostgresUrl = "jdbc:postgresql://127.0.0.1:55432/postgres",
    [string]$PostgresUser = "postgres",
    [string]$PostgresPassword = "postgresql"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reportsDir = Join-Path $repoRoot "target\cachedb-prodtest-reports"

function Invoke-Maven {
    param(
        [string[]]$Arguments
    )

    $effectiveMavenExecutable = $MavenExecutable
    if ([string]::IsNullOrWhiteSpace($effectiveMavenExecutable)) {
        $effectiveMavenExecutable = if ($IsWindows) { "mvn.cmd" } else { "mvn" }
    }

    Write-Host ""
    Write-Host "==> $effectiveMavenExecutable $($Arguments -join ' ')"

    $global:LASTEXITCODE = 0
    if ($IsWindows -and [System.IO.Path]::GetExtension($effectiveMavenExecutable).Equals(".cmd", [System.StringComparison]::OrdinalIgnoreCase)) {
        $quotedArguments = $Arguments | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_.Replace('"', '\"')) + '"'
            } else {
                $_
            }
        }

        $command = '"' + $effectiveMavenExecutable + '" ' + ($quotedArguments -join ' ')
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
        & $effectiveMavenExecutable @Arguments
        $exitCode = $global:LASTEXITCODE
    }

    if ($exitCode -ne 0) {
        throw "Maven failed with exit code $exitCode"
    }
}

if (Test-Path $reportsDir) {
    Remove-Item -Recurse -Force $reportsDir
}
New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null

Invoke-Maven @(
    "-pl", "cachedb-production-tests",
    "-am",
    "test",
    "-DforkCount=0",
    "-Dtest=MultiInstanceCoordinationSmokeTest",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-Dcachedb.prod.reportDir=$reportsDir",
    "-Dcachedb.prod.redis.uri=$RedisUri",
    "-Dcachedb.prod.postgres.url=$PostgresUrl",
    "-Dcachedb.prod.postgres.user=$PostgresUser",
    "-Dcachedb.prod.postgres.password=$PostgresPassword"
)

Write-Host ""
Write-Host "Multi-instance coordination evidence run completed."
Write-Host "Reports:"
Write-Host " - $reportsDir\multi-instance-coordination-smoke.json"
Write-Host " - $reportsDir\multi-instance-coordination-smoke.md"
