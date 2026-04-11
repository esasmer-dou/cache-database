param(
    [string]$CurrentVersion = "0.1.0-beta.1",
    [string]$NextVersion = "0.1.0-beta.2-SNAPSHOT",
    [switch]$CreateReleaseNotesTemplate
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pomFiles = Get-ChildItem -Path $repoRoot -Recurse -Filter pom.xml |
        Where-Object { $_.FullName -notmatch "\\target\\" }
$changelogPath = Join-Path $repoRoot "CHANGELOG.md"
$releaseNotesDir = Join-Path $repoRoot "docs\releases"

function Replace-FirstLiteral {
    param(
        [string]$Text,
        [string]$OldValue,
        [string]$NewValue
    )

    $escaped = [regex]::Escape($OldValue)
    return [regex]::Replace($Text, $escaped, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $NewValue }, 1)
}

foreach ($pom in $pomFiles) {
    $content = Get-Content -Path $pom.FullName -Raw
    if ($content.Contains("<version>$CurrentVersion</version>")) {
        $updated = Replace-FirstLiteral -Text $content -OldValue "<version>$CurrentVersion</version>" -NewValue "<version>$NextVersion</version>"
        Set-Content -Path $pom.FullName -Value $updated -Encoding UTF8
    }
}

if (Test-Path $changelogPath) {
    $changelog = Get-Content -Path $changelogPath -Raw
    if ($changelog -notmatch '(?m)^## Unreleased\s*$') {
        $header = "# Changelog`r`n`r`nAll notable changes to `cache-database` will be tracked here.`r`n`r`nThe format is intentionally simple during public beta.`r`n`r`n"
        if ($changelog.StartsWith($header)) {
            $rest = $changelog.Substring($header.Length)
            $newChangelog = $header +
                    "## Unreleased`r`n`r`n### Added`r`n`r`n- _TBD_`r`n`r`n### Changed`r`n`r`n- _TBD_`r`n`r`n### Fixed`r`n`r`n- _TBD_`r`n`r`n" +
                    $rest
            Set-Content -Path $changelogPath -Value $newChangelog -Encoding UTF8
        }
    }
}

if ($CreateReleaseNotesTemplate) {
    if (-not (Test-Path $releaseNotesDir)) {
        New-Item -ItemType Directory -Path $releaseNotesDir | Out-Null
    }
    $nextTag = "v" + ($NextVersion -replace '-SNAPSHOT$', '')
    $templatePath = Join-Path $releaseNotesDir "$nextTag.md"
    if (-not (Test-Path $templatePath)) {
        $template = @"
# $nextTag

## Highlights

- _TBD_

## What Changed

### Added

- _TBD_

### Changed

- _TBD_

### Fixed

- _TBD_

## Notes

- _TBD_
"@
        Set-Content -Path $templatePath -Value $template -Encoding UTF8
    }
}

Write-Host "Next beta cycle prepared."
Write-Host "Current version: $CurrentVersion"
Write-Host "Next version: $NextVersion"
Write-Host "Updated pom files: $($pomFiles.Count)"
Write-Host "Changelog: $changelogPath"
