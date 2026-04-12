param(
    [string]$WorkingDirectory = (Get-Location).Path,
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GitArgs = @("status", "--short"),
    [string]$GitExe = "C:\Program Files\Git\cmd\git.exe",
    [string]$SshExe = "C:\Program Files\Git\usr\bin\ssh.exe",
    [string]$SshConfig = "C:\Users\generic\.ssh\config",
    [switch]$PrintCommand
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path $GitExe)) {
    throw "git.exe not found: $GitExe"
}
if (-not (Test-Path $SshExe)) {
    throw "ssh.exe not found: $SshExe"
}

function Convert-ToForwardSlashPath {
    param([string]$PathValue)

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $PathValue
    }
    return $PathValue.Replace('\', '/')
}

$systemRoot = "C:\WINDOWS"
$requiredPathEntries = @(
    "C:\WINDOWS\system32",
    "C:\WINDOWS",
    "C:\Program Files\Git\cmd",
    "C:\Program Files\Git\mingw64\bin",
    "C:\Program Files\Git\usr\bin"
)

$currentEntries = @()
if ($env:Path) {
    $currentEntries = $env:Path.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
}
$normalizedEntries = [System.Collections.Generic.List[string]]::new()
foreach ($entry in ($requiredPathEntries + $currentEntries)) {
    if ([string]::IsNullOrWhiteSpace($entry)) {
        continue
    }
    if (-not $normalizedEntries.Contains($entry)) {
        $normalizedEntries.Add($entry)
    }
}

$quotedArgs = $GitArgs | ForEach-Object {
    if ($_ -match '[\s"]') {
        '"' + ($_.Replace('"', '\"')) + '"'
    } else {
        $_
    }
}

if ($PrintCommand) {
    Write-Host ("Running git from {0}`n{1} {2}" -f $WorkingDirectory, $GitExe, ($quotedArgs -join ' '))
}

$sshExeForGit = Convert-ToForwardSlashPath $SshExe
$sshConfigForGit = Convert-ToForwardSlashPath $SshConfig

$sshCommand = if (Test-Path $SshConfig) {
    '"' + $sshExeForGit + '" -F "' + $sshConfigForGit + '"'
} else {
    '"' + $sshExeForGit + '"'
}

$gitConfigPath = Join-Path $WorkingDirectory ".git\config"
$hasRepoSshCommand = $false
if (Test-Path $gitConfigPath) {
    $gitConfigContent = Get-Content $gitConfigPath -Raw
    $hasRepoSshCommand = $gitConfigContent -match '(?im)^\s*sshCommand\s*='
}

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $GitExe
$psi.Arguments = $quotedArgs -join ' '
$psi.WorkingDirectory = $WorkingDirectory
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.Environment["SystemRoot"] = $systemRoot
$psi.Environment["windir"] = $systemRoot
$psi.Environment["ComSpec"] = Join-Path $systemRoot "System32\cmd.exe"
$psi.Environment["PROCESSOR_ARCHITECTURE"] = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE } else { "AMD64" }
$psi.Environment["Path"] = $normalizedEntries -join ';'
$homeValue = if ($env:HOME) { $env:HOME } else { $env:USERPROFILE }
$userProfileValue = if ($env:USERPROFILE) { $env:USERPROFILE } else { "C:\Users\generic" }
$psi.Environment["HOME"] = Convert-ToForwardSlashPath $homeValue
$psi.Environment["USERPROFILE"] = Convert-ToForwardSlashPath $userProfileValue
if (-not $hasRepoSshCommand) {
    $psi.Environment["GIT_SSH_COMMAND"] = $sshCommand
    $psi.Environment["GIT_SSH_VARIANT"] = "ssh"
}

$process = [System.Diagnostics.Process]::Start($psi)
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()

if (-not [string]::IsNullOrWhiteSpace($stdout)) {
    Write-Output $stdout.TrimEnd()
}
if (-not [string]::IsNullOrWhiteSpace($stderr)) {
    if ($process.ExitCode -eq 0) {
        Write-Warning $stderr.TrimEnd()
    } else {
        Write-Error $stderr.TrimEnd()
    }
}

exit $process.ExitCode
