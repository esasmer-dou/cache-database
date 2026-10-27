param([switch]$Check)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$utf8 = [Text.UTF8Encoding]::new($false)
$begin = '<!-- BEGIN COMPILED FEATURE EXAMPLES -->'
$end = '<!-- END COMPILED FEATURE EXAMPLES -->'
foreach ($provider in @('postgresql', 'mssql', 'oracle')) {
    $sample = Join-Path $root "sample-cache-database-$provider"
    $port = @{postgresql=8091; mssql=8092; oracle=8093}[$provider]
    foreach ($language in @('en', 'tr')) {
        $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot "templates/features.$language.md"), $utf8)
        $notePath = Join-Path $PSScriptRoot "templates/features.$provider.$language.md"
        $note = if (Test-Path $notePath) { [IO.File]::ReadAllText($notePath, $utf8).TrimEnd() } else { '' }
        $template = $template.Replace('{{PROVIDER_NOTE}}', $note)
        $controls = [IO.File]::ReadAllText((Join-Path $PSScriptRoot "templates/controls.$language.md"), $utf8)
        $template = $template.Replace('{{CONTROLS_GUIDE}}', $controls)
        $body = [regex]::Replace($template, '\{\{CODE:([^}]+)\}\}', {
            param($match)
            $source = [IO.Path]::GetFullPath((Join-Path $sample $match.Groups[1].Value))
            $prefix = [IO.Path]::GetFullPath($sample) + [IO.Path]::DirectorySeparatorChar
            if (!$source.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Code excerpt escaped sample' }
            return [IO.File]::ReadAllText($source, $utf8).Replace("`r`n", "`n").TrimEnd()
        }).Replace('{{PORT}}', [string]$port).Replace('{{PROVIDER}}', $provider)
        $path = Join-Path $sample $(if ($language -eq 'en') {'README.md'} else {'README.tr.md'})
        $content = [IO.File]::ReadAllText($path, $utf8).Replace("`r`n", "`n")
        $section = "$begin`n$($body.TrimEnd())`n$end"
        $pattern = '(?s)' + [regex]::Escape($begin) + '.*?' + [regex]::Escape($end)
        $expected = if ($content.Contains($begin)) {
            [regex]::Replace($content, $pattern, [Text.RegularExpressions.MatchEvaluator]{param($m) $section})
        } else {
            $content.TrimEnd() + "`n`n" + $section + "`n"
        }
        if ($Check) {
            if ($expected -cne $content) { throw "Compiled README examples are stale: $path" }
        } else {
            [IO.File]::WriteAllText($path, $expected, $utf8)
        }
    }
}
Write-Host 'Compiled EN/TR feature examples match all three samples.'
