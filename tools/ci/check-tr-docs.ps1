param(
    [string]$DocsRoot = "tr",
    [string]$SummaryPath = ".\target\tr-docs-quality-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$docsFullPath = Join-Path $repoRoot $DocsRoot
$summaryFullPath = Join-Path $repoRoot $SummaryPath
$summaryDir = Split-Path -Parent $summaryFullPath

if (-not (Test-Path $docsFullPath)) {
    throw "Turkish docs root not found: $docsFullPath"
}

$rules = @(
    @{ Pattern = '(?<!\p{L})Ile(?!\p{L})'; Suggestion = 'Use "ile" or "İle" with the correct Turkish dotted I.' },
    @{ Pattern = '(?<!\p{L})Ilk(?!\p{L})'; Suggestion = 'Use "İlk".' },
    @{ Pattern = '(?<!\p{L})Baslamali(?!\p{L})'; Suggestion = 'Use "Başlamalı".' },
    @{ Pattern = '(?<!\p{L})Baslanmali(?!\p{L})'; Suggestion = 'Use "Başlanmalı".' },
    @{ Pattern = '(?<!\p{L})Hizli(?!\p{L})'; Suggestion = 'Use "Hızlı".' },
    @{ Pattern = '(?<!\p{L})Karsi(?=\p{L}|$)'; Suggestion = 'Use "Karşı..." forms with ş and ı.' },
    @{ Pattern = '(?<!\p{L})Olcul(?=\p{L}|$)'; Suggestion = 'Use "Ölçül..." forms with ö and ü.' },
    @{ Pattern = '(?<!\p{L})Olcum(?=\p{L}|$)'; Suggestion = 'Use "Ölçüm..." forms with ö and ü.' },
    @{ Pattern = '(?<!\p{L})Ornegi(?!\p{L})'; Suggestion = 'Use "Örneği".' },
    @{ Pattern = '(?<!\p{L})Misin(?!\p{L})'; Suggestion = 'Use "Mısın".' },
    @{ Pattern = '(?<!\p{L})Halen(?!\p{L})'; Suggestion = 'Use "Hâlen".' },
    @{ Pattern = '(?<!\p{L})Asagi(?=\p{L}|$)'; Suggestion = 'Use "Aşağı..." forms with ş and ğ.' },
    @{ Pattern = '(?<!\p{L})asagi(?=\p{L}|$)'; Suggestion = 'Use "aşağı..." forms with ş and ğ.' },
    @{ Pattern = '(?<!\p{L})dogal(?!\p{L})'; Suggestion = 'Use "doğal".' },
    @{ Pattern = '(?<!\p{L})dogru(?=\p{L}|$)'; Suggestion = 'Use "doğru..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})Dogru(?=\p{L}|$)'; Suggestion = 'Use "Doğru..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})dogrul(?=\p{L}|$)'; Suggestion = 'Use "doğrul..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})Dogrul(?=\p{L}|$)'; Suggestion = 'Use "Doğrul..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})bagiml(?=\p{L}|$)'; Suggestion = 'Use "bağıml..." forms with ğ and ı.' },
    @{ Pattern = '(?<!\p{L})Bagiml(?=\p{L}|$)'; Suggestion = 'Use "Bağıml..." forms with ğ and ı.' },
    @{ Pattern = '(?<!\p{L})bagli(?=\p{L}|$)'; Suggestion = 'Use "bağlı..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})Bagli(?=\p{L}|$)'; Suggestion = 'Use "Bağlı..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})sagli(?=\p{L}|$)'; Suggestion = 'Use "sağlı..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})Sagli(?=\p{L}|$)'; Suggestion = 'Use "Sağlı..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})sagla(?=\p{L}|$)'; Suggestion = 'Use "sağla..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})Sagla(?=\p{L}|$)'; Suggestion = 'Use "Sağla..." forms with ğ.' },
    @{ Pattern = '(?<!\p{L})cagri(?=\p{L}|$)'; Suggestion = 'Use "çağrı..." forms with ç and ğ.' },
    @{ Pattern = '(?<!\p{L})Cagri(?=\p{L}|$)'; Suggestion = 'Use "Çağrı..." forms with ç and ğ.' },
    @{ Pattern = '(?<!\p{L})gecmis(?=\p{L}|$)'; Suggestion = 'Use "geçmiş..." forms with ç.' },
    @{ Pattern = '(?<!\p{L})trafig(?=\p{L}|$)'; Suggestion = 'Use "trafik/trafiği" forms with ı and ğ when needed.' },
    @{ Pattern = '(?<!\p{L})tarafinda(?!\p{L})'; Suggestion = 'Use "tarafında".' },
    @{ Pattern = '(?<!\p{L})yazılir(?!\p{L})'; Suggestion = 'Use "yazılır".' },
    @{ Pattern = '(?<!\p{L})darbohaz(?!\p{L})'; Suggestion = 'Use "darboğaz".' },
    @{ Pattern = '(?<!\p{L})açıklik(?!\p{L})'; Suggestion = 'Use "açıklık".' },
    @{ Pattern = '(?<!\p{L})sekl(?=\p{L}|$)'; Suggestion = 'Use "şekil/şekli/şeklinde" forms with ş.' }
)

$findings = New-Object System.Collections.Generic.List[object]
$markdownFiles = Get-ChildItem -Path $docsFullPath -Recurse -Filter *.md | Sort-Object FullName

foreach ($file in $markdownFiles) {
    $lines = Get-Content -LiteralPath $file.FullName
    $insideFence = $false

    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        $trimmed = $line.Trim()

        if ($trimmed.StartsWith('```')) {
            $insideFence = -not $insideFence
            continue
        }

        if ($insideFence) {
            continue
        }

        $scanLine = [regex]::Replace($line, '`[^`]+`', '')
        if ([string]::IsNullOrWhiteSpace($scanLine)) {
            continue
        }

        foreach ($rule in $rules) {
            $match = [regex]::Match($scanLine, $rule.Pattern)
            if ($match.Success) {
                $relativePath = [System.IO.Path]::GetRelativePath($repoRoot, $file.FullName)
                $findings.Add([pscustomobject]@{
                    File = $relativePath
                    Line = $index + 1
                    Token = $match.Value
                    Suggestion = $rule.Suggestion
                    Text = $line.Trim()
                })
            }
        }
    }
}

if (-not (Test-Path $summaryDir)) {
    New-Item -ItemType Directory -Path $summaryDir -Force | Out-Null
}

$summaryLines = @(
    "# Turkish Docs Quality Summary",
    "",
    ('Scanned root: `' + $DocsRoot + '`'),
    "",
    "Findings: $($findings.Count)",
    ""
)

if ($findings.Count -eq 0) {
    $summaryLines += "- no suspicious Turkish diacritic drift patterns were detected"
} else {
    $summaryLines += "## Findings"
    $summaryLines += ""
    foreach ($finding in $findings) {
        $summaryLines += ('- [' + $finding.File + ':' + $finding.Line + '] suspicious token `' + $finding.Token + '`')
        $summaryLines += "  - suggestion: $($finding.Suggestion)"
        $summaryLines += "  - line: $($finding.Text)"
    }
}

Set-Content -Path $summaryFullPath -Value ($summaryLines -join [Environment]::NewLine)

if ($findings.Count -gt 0) {
    foreach ($finding in $findings) {
        Write-Host "$($finding.File):$($finding.Line) [$($finding.Token)] $($finding.Suggestion)"
        Write-Host "  $($finding.Text)"
    }
    throw "Turkish docs quality validation failed."
}

Write-Host "Turkish docs quality validation passed."
Write-Host "Summary written to $summaryFullPath"
