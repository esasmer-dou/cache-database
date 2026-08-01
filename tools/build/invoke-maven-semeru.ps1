param(
    [string]$JavaHome,
    [string]$MavenHome = "C:\apache-maven-3.9.6",
    [string]$WorkingDirectory = (Get-Location).Path,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @("-version")
)

$JavaHome = & (Join-Path $PSScriptRoot "resolve-java-home.ps1") -JavaHome $JavaHome
$mavenCmd = Join-Path $MavenHome "bin\mvn.cmd"
if (-not (Test-Path -LiteralPath $mavenCmd)) {
    throw "Maven executable not found: $mavenCmd"
}

$env:SystemRoot = "C:\WINDOWS"
$env:ComSpec = "C:\WINDOWS\System32\cmd.exe"
$env:JAVA_HOME = $JavaHome

$requiredPathEntries = @(
    "C:\WINDOWS\system32",
    "C:\WINDOWS",
    (Join-Path $JavaHome "bin"),
    (Join-Path $MavenHome "bin")
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
$env:Path = ($normalizedEntries -join ';')

$quotedArgs = $MavenArgs | ForEach-Object {
    if ($_ -match '[\s"]') {
        '"' + ($_.Replace('"', '\"')) + '"'
    } else {
        $_
    }
}

$command = "cd /d `"$WorkingDirectory`" && `"$mavenCmd`" " + ($quotedArgs -join ' ')

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Running Maven from $WorkingDirectory"
Write-Host $command

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $env:ComSpec
$psi.Arguments = "/c $command"
$psi.WorkingDirectory = $WorkingDirectory
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.Environment["SystemRoot"] = $env:SystemRoot
$psi.Environment["ComSpec"] = $env:ComSpec
$psi.Environment["JAVA_HOME"] = $env:JAVA_HOME
$psi.Environment["Path"] = $env:Path

$process = [System.Diagnostics.Process]::Start($psi)
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()

if (-not [string]::IsNullOrEmpty($stdout)) {
    Write-Host $stdout.TrimEnd()
}
if (-not [string]::IsNullOrEmpty($stderr)) {
    if ($process.ExitCode -eq 0) {
        Write-Warning $stderr.TrimEnd()
    } else {
        Write-Error $stderr.TrimEnd()
    }
}

exit $process.ExitCode
