[CmdletBinding()]
param(
    [string]$JavaHome
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$candidates = if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    @(
        $env:JAVA_HOME,
        [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "User"),
        [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
    )
} else {
    @($JavaHome)
}

foreach ($candidate in $candidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        continue
    }

    $normalized = [System.Environment]::ExpandEnvironmentVariables($candidate.Trim().Trim('"'))
    if (Test-Path -LiteralPath (Join-Path $normalized "bin\java.exe")) {
        Write-Output (Resolve-Path -LiteralPath $normalized).Path
        return
    }
}

throw "No valid JDK was found. Set JAVA_HOME to a directory containing bin\java.exe."
