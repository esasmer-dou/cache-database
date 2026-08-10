param(
    [string]$SummaryPath = "target/markdown-link-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$summaryFullPath = Join-Path $repoRoot $SummaryPath
$summaryDirectory = Split-Path -Parent $summaryFullPath

$relativeFiles = @(& git -C $repoRoot ls-files --cached --others --exclude-standard -- "*.md")
if ($LASTEXITCODE -ne 0) {
    throw "Could not enumerate Markdown files with git."
}

$findings = New-Object System.Collections.Generic.List[object]
$inlineLinkPattern = [regex]'!?\[[^\]]*\]\((?<target><[^>]+>|[^\s\)]+)'
$referenceLinkPattern = [regex]'^[ \t]*\[[^\]]+\]:[ \t]*(?<target><[^>]+>|\S+)'

foreach ($relativeFile in ($relativeFiles | Sort-Object -Unique)) {
    $filePath = Join-Path $repoRoot $relativeFile
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        continue
    }

    $insideFence = $false
    $lines = Get-Content -LiteralPath $filePath
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = [string]$lines[$index]
        if ($line.TrimStart().StartsWith('```')) {
            $insideFence = -not $insideFence
            continue
        }
        if ($insideFence) {
            continue
        }

        $scanLine = [regex]::Replace($line, '`[^`]*`', '')
        $matches = @($inlineLinkPattern.Matches($scanLine)) + @($referenceLinkPattern.Matches($scanLine))
        foreach ($match in $matches) {
            $rawTarget = $match.Groups['target'].Value.Trim().Trim('<', '>')
            if ([string]::IsNullOrWhiteSpace($rawTarget) -or
                    $rawTarget.StartsWith('#') -or
                    $rawTarget.StartsWith('//') -or
                    $rawTarget -match '^[A-Za-z][A-Za-z0-9+.-]*:') {
                continue
            }

            $pathPart = ($rawTarget -split '[?#]', 2)[0]
            if ([string]::IsNullOrWhiteSpace($pathPart)) {
                continue
            }
            try {
                $pathPart = [Uri]::UnescapeDataString($pathPart)
            } catch {
                $findings.Add([pscustomobject]@{
                    File = $relativeFile
                    Line = $index + 1
                    Target = $rawTarget
                    Reason = 'invalid URL encoding'
                })
                continue
            }

            $resolved = if ($pathPart.StartsWith('/')) {
                Join-Path $repoRoot $pathPart.TrimStart('/')
            } else {
                Join-Path (Split-Path -Parent $filePath) $pathPart
            }
            if (-not (Test-Path -LiteralPath $resolved)) {
                $findings.Add([pscustomobject]@{
                    File = $relativeFile
                    Line = $index + 1
                    Target = $rawTarget
                    Reason = 'target does not exist'
                })
            }
        }
    }
}

if (-not (Test-Path -LiteralPath $summaryDirectory)) {
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
}

$summary = @(
    '# Markdown Link Summary',
    '',
    "Files scanned: $($relativeFiles.Count)",
    "Broken local links: $($findings.Count)",
    ''
)
if ($findings.Count -eq 0) {
    $summary += '- all local Markdown link targets exist'
} else {
    $summary += '## Findings'
    $summary += ''
    foreach ($finding in $findings) {
        $summary += "- $($finding.File):$($finding.Line) -> $($finding.Target) ($($finding.Reason))"
    }
}

Set-Content -LiteralPath $summaryFullPath -Value ($summary -join [Environment]::NewLine)

if ($findings.Count -gt 0) {
    $findings | ForEach-Object {
        Write-Host "$($_.File):$($_.Line) [$($_.Target)] $($_.Reason)"
    }
    throw "Markdown link validation failed."
}

Write-Host "Markdown link validation passed."
Write-Host "Summary written to $summaryFullPath"
