param(
    [switch]$UpdateBaseline,
    [string]$BaselinePath = "tools/ci/public-api-baseline.txt",
    [string]$OutputPath = "target/public-api-signature.txt",
    [string[]]$Modules = @(
        "cachedb-annotations",
        "cachedb-core",
        "cachedb-processor",
        "cachedb-storage-redis",
        "cachedb-storage-jdbc",
        "cachedb-storage-postgres",
        "cachedb-storage-mssql",
        "cachedb-starter",
        "cachedb-spring-boot-starter"
    )
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$baselineFullPath = Join-Path $repoRoot $BaselinePath
$outputFullPath = Join-Path $repoRoot $OutputPath
$outputDirectory = Split-Path -Parent $outputFullPath

if (-not (Test-Path $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}

$classPathEntries = @()
foreach ($module in $Modules) {
    $classesDirectory = Join-Path (Join-Path (Join-Path $repoRoot $module) "target") "classes"
    if (-not (Test-Path $classesDirectory)) {
        throw "Missing compiled classes for $module. Run mvn -DskipTests package before public API compatibility check."
    }
    $classPathEntries += $classesDirectory
}
$classPath = $classPathEntries -join [System.IO.Path]::PathSeparator

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# CacheDB public API baseline")
$lines.Add("# Regenerate intentionally with: pwsh ./tools/ci/check-public-api-compatibility.ps1 -UpdateBaseline")
$lines.Add("")

foreach ($module in $Modules) {
    $classesDirectory = Join-Path (Join-Path (Join-Path $repoRoot $module) "target") "classes"
    $classFiles = Get-ChildItem -Path $classesDirectory -Recurse -Filter "*.class" |
            Where-Object {
                $_.Name -ne "module-info.class" -and
                $_.Name -notmatch '\$' -and
                $_.FullName -notmatch ([Regex]::Escape([System.IO.Path]::DirectorySeparatorChar + "target" + [System.IO.Path]::DirectorySeparatorChar + "classes" + [System.IO.Path]::DirectorySeparatorChar + "META-INF" + [System.IO.Path]::DirectorySeparatorChar))
            } |
            Sort-Object FullName

    $classNames = @()
    foreach ($classFile in $classFiles) {
        $relative = [System.IO.Path]::GetRelativePath($classesDirectory, $classFile.FullName)
        $classNames += $relative.Substring(0, $relative.Length - ".class".Length).Replace([System.IO.Path]::DirectorySeparatorChar, '.').Replace([System.IO.Path]::AltDirectorySeparatorChar, '.')
    }

    for ($index = 0; $index -lt $classNames.Count; $index += 40) {
        $batch = $classNames[$index..([Math]::Min($index + 39, $classNames.Count - 1))]
        $javapOutput = & javap -classpath $classPath -public @batch 2>&1
        if ($global:LASTEXITCODE -ne 0) {
            throw "javap failed for $module. Output: $javapOutput"
        }

        $blocks = New-Object System.Collections.Generic.List[object]
        $current = New-Object System.Collections.Generic.List[string]
        foreach ($line in $javapOutput) {
            if ($line -match '^Compiled from ' -and $current.Count -gt 0) {
                $blocks.Add($current.ToArray())
                $current = New-Object System.Collections.Generic.List[string]
            }
            $current.Add([string]$line)
        }
        if ($current.Count -gt 0) {
            $blocks.Add($current.ToArray())
        }

        foreach ($block in $blocks) {
            $signatureLines = @($block | Where-Object {
                $_ -notmatch '^Compiled from ' -and
                $_.Trim().Length -gt 0
            })
            if ($signatureLines.Count -eq 0) {
                continue
            }
            $declaration = ($signatureLines | Where-Object { $_ -match '^(public\s+).*\{' } | Select-Object -First 1)
            if ([string]::IsNullOrWhiteSpace($declaration)) {
                continue
            }
            $className = $declaration.Trim() -replace '\s+\{.*$', ''
            $className = $className -replace '^public\s+', ''
            $className = $className -replace '^(final|abstract)\s+', ''
            $className = $className -replace '^(class|interface|enum|@interface)\s+', ''
            $className = $className -replace '<[^>]+>', ''
            $className = $className -replace '\s+(extends|implements)\s+.*$', ''
            $lines.Add("## $module :: $className")
            foreach ($line in $signatureLines) {
                $lines.Add($line.TrimEnd())
            }
            $lines.Add("")
        }
    }
}

while ($lines.Count -gt 0 -and [string]::IsNullOrEmpty($lines[$lines.Count - 1])) {
    $lines.RemoveAt($lines.Count - 1)
}
$actual = ($lines -join "`n") + "`n"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($outputFullPath, $actual, $utf8NoBom)

if ($UpdateBaseline) {
    $baselineDirectory = Split-Path -Parent $baselineFullPath
    if (-not (Test-Path $baselineDirectory)) {
        New-Item -ItemType Directory -Path $baselineDirectory -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($baselineFullPath, $actual, $utf8NoBom)
    Write-Host "Public API baseline updated: $baselineFullPath"
    return
}

if (-not (Test-Path $baselineFullPath)) {
    throw "Missing public API baseline: $baselineFullPath. Generate it intentionally with -UpdateBaseline."
}

$expected = Get-Content $baselineFullPath -Raw
if ($expected -ne $actual) {
    Write-Host "Public API signature changed."
    Write-Host "Actual signature written to: $outputFullPath"
    Write-Host "If this is an intentional public API change, regenerate the baseline and document the compatibility decision."
    throw "Public API compatibility check failed."
}

Write-Host "Public API compatibility check passed."
Write-Host "Signature: $outputFullPath"
