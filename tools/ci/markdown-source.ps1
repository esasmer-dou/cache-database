# Source-oriented Markdown scanning shared by the prose and local-link guards.
# Keep labels/titles as prose; only code and destination spans are masked.
function Test-MarkdownEscaped([string]$Text, [int]$Index) {
    $slashes = 0
    for ($p = $Index - 1; $p -ge 0 -and $Text[$p] -eq '\'; $p--) { $slashes++ }
    return ($slashes % 2) -eq 1
}

function Read-MarkdownDestination([string]$Text, [int]$Start, [bool]$Inline) {
    $p = $Start
    while ($p -lt $Text.Length -and [char]::IsWhiteSpace($Text[$p])) { $p++ }
    $begin = $p
    if ($p -ge $Text.Length) { return $null }
    $angle = $Text[$p] -eq '<'
    if ($angle) {
        $p++
        while ($p -lt $Text.Length -and ($Text[$p] -ne '>' -or (Test-MarkdownEscaped $Text $p))) { $p++ }
        if ($p -ge $Text.Length) { return $null }
        $target = $Text.Substring($begin + 1, $p - $begin - 1)
        $p++
    } else {
        $depth = 0
        while ($p -lt $Text.Length) {
            $c = $Text[$p]
            if ($c -eq '\' -and $p + 1 -lt $Text.Length) { $p += 2; continue }
            if ([char]::IsWhiteSpace($c) -or ($c -eq ')' -and $depth -eq 0)) { break }
            if ($c -eq '(') { $depth++; if ($depth -gt 32) { throw 'Markdown destination nesting exceeds 32.' } }
            if ($c -eq ')') { $depth-- }
            $p++
        }
        if ($depth -ne 0) { return $null }
        $target = $Text.Substring($begin, $p - $begin)
    }
    if (!$target) { return $null }
    $end = $p
    if ($Inline) {
        while ($p -lt $Text.Length -and [char]::IsWhiteSpace($Text[$p])) { $p++ }
        if ($p -lt $Text.Length -and $Text[$p] -in @('"', "'", '(')) {
            $close = if ($Text[$p] -eq '(') { ')' } else { [string]$Text[$p] }
            $p++
            while ($p -lt $Text.Length -and ($Text[$p] -ne $close -or (Test-MarkdownEscaped $Text $p))) { $p++ }
            if ($p -ge $Text.Length) { return $null }
            $p++
            while ($p -lt $Text.Length -and [char]::IsWhiteSpace($Text[$p])) { $p++ }
        }
        if ($p -ge $Text.Length -or $Text[$p] -ne ')') { return $null }
    }
    $target = [regex]::Replace($target, '\\([\\()\[\]<>])', '$1')
    return [pscustomobject]@{ Start = $begin; End = $end; Target = $target }
}

function Get-MarkdownSourceLines([string[]]$Lines) {
    $fence = ''
    $inlineLinks = [regex]::new('!?\[(?:\\.|[^\]\\])*\]\(', [Text.RegularExpressions.RegexOptions]::None, [TimeSpan]::FromSeconds(1))
    for ($lineIndex = 0; $lineIndex -lt $Lines.Count; $lineIndex++) {
        $line = $Lines[$lineIndex]
        if ($line.Length -gt 1MB) { throw 'Markdown line exceeds 1 MiB.' }
        if ($line -match '^ {0,3}(?<run>`{3,}|~{3,})(?<info>.*)$') {
            $run = $Matches.run; $info = $Matches.info
            if ($fence) {
                if ($run[0] -eq $fence[0] -and $run.Length -ge $fence.Length -and !$info.Trim()) { $fence = '' }
                continue
            }
            if ($run[0] -ne '`' -or !$info.Contains('`')) { $fence = $run; continue }
        }
        if ($fence) { continue }
        $chars = $line.ToCharArray()
        $ticks = [regex]::Matches($line, '`+')
        for ($i = 0; $i -lt $ticks.Count; $i++) {
            if (Test-MarkdownEscaped $line $ticks[$i].Index) { continue }
            $closing = -1
            for ($j = $i + 1; $j -lt $ticks.Count; $j++) {
                if ($ticks[$j].Length -eq $ticks[$i].Length) { $closing = $j; break }
            }
            if ($closing -lt 0) { continue }
            for ($p = $ticks[$i].Index; $p -lt $ticks[$closing].Index + $ticks[$closing].Length; $p++) { $chars[$p] = ' ' }
            $i = $closing
        }
        $codeMasked = -join $chars
        $targets = [Collections.Generic.List[string]]::new()
        $destinations = [Collections.Generic.List[object]]::new()
        foreach ($match in $inlineLinks.Matches($codeMasked)) {
            $bracket = $match.Index
            if ($codeMasked[$bracket] -eq '!') { $bracket++ }
            if (Test-MarkdownEscaped $codeMasked $bracket) { continue }
            $destination = Read-MarkdownDestination $codeMasked ($match.Index + $match.Length) $true
            if ($null -ne $destination) { $destinations.Add($destination) }
        }
        $reference = [regex]::Match($codeMasked, '^[ \t]{0,3}\[(?:\\.|[^\]\\])+\]:[ \t]*')
        if ($reference.Success) {
            $destination = Read-MarkdownDestination $codeMasked $reference.Length $false
            if ($null -ne $destination) { $destinations.Add($destination) }
        }
        foreach ($destination in $destinations) {
            $targets.Add($destination.Target)
            for ($p = $destination.Start; $p -lt $destination.End; $p++) { $chars[$p] = ' ' }
        }
        [pscustomobject]@{ Number = $lineIndex + 1; Prose = (-join $chars); Targets = @($targets) }
    }
}
