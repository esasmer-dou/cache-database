param(
    [Parameter(Mandatory = $true)]
    [string]$KeyPath,
    [string]$RemoteHost = "git@github.com",
    [switch]$VerboseLog
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$sshExe = "C:\Windows\System32\OpenSSH\ssh.exe"
if (-not (Test-Path $sshExe)) {
    throw "ssh.exe not found: $sshExe"
}
if (-not (Test-Path $KeyPath)) {
    throw "SSH key not found: $KeyPath"
}

$logPath = $null
$arguments = @(
    "-T",
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", "IdentitiesOnly=yes",
    "-i", "`"$KeyPath`"",
    $RemoteHost
)

if ($VerboseLog) {
    $logPath = Join-Path ([System.IO.Path]::GetTempPath()) ("cachedb-ssh-probe-" + [System.Guid]::NewGuid().ToString("N") + ".log")
    $arguments = @("-vvv", "-E", "`"$logPath`"") + $arguments
}

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $sshExe
$psi.Arguments = $arguments -join ' '
$psi.WorkingDirectory = (Get-Location).Path
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.Environment["SystemRoot"] = "C:\WINDOWS"
$psi.Environment["windir"] = "C:\WINDOWS"
$psi.Environment["ComSpec"] = "C:\WINDOWS\System32\cmd.exe"
$psi.Environment["PROCESSOR_ARCHITECTURE"] = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE } else { "AMD64" }
$psi.Environment["Path"] = "C:\WINDOWS\system32;C:\WINDOWS;C:\WINDOWS\System32\OpenSSH;C:\Program Files\Git\usr\bin;C:\Program Files\Git\cmd;C:\Program Files\Git\mingw64\bin"
$psi.Environment["HOME"] = if ($env:HOME) { $env:HOME } else { $env:USERPROFILE }
$psi.Environment["USERPROFILE"] = if ($env:USERPROFILE) { $env:USERPROFILE } else { "C:\Users\generic" }

$process = [System.Diagnostics.Process]::Start($psi)
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()

$result = [pscustomobject]@{
    keyPath = $KeyPath
    exitCode = $process.ExitCode
    stdout = $stdout.Trim()
    stderr = $stderr.Trim()
    logPath = $logPath
    log = if ($logPath -and (Test-Path $logPath)) { (Get-Content $logPath -Raw) } else { "" }
}

$result | ConvertTo-Json -Depth 4

if ($logPath -and (Test-Path $logPath)) {
    Remove-Item -Force $logPath
}
