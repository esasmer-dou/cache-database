#requires -Version 7.2
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (!$EvidenceDirectory) { $EvidenceDirectory = Join-Path ([IO.Path]::GetTempPath()) ('cachedb-doc-guards-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Evidence must be new.' }
$null = New-Item -ItemType Directory -Path $EvidenceDirectory
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
$fixture = Join-Path $evidence 'source'
$null = New-Item -ItemType Directory -Path (Join-Path $fixture 'tools/ci'), (Join-Path $fixture 'tr/docs'), (Join-Path $fixture 'target')
foreach ($file in @('check-tr-docs.ps1', 'check-markdown-links.ps1', 'markdown-source.ps1')) {
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot "tools/ci/$file") -Destination (Join-Path $fixture "tools/ci/$file")
}
foreach ($file in @('surum-gecmisi.md', 'known file.md', 'known(item).md')) {
    [IO.File]::WriteAllText((Join-Path $fixture "tr/docs/$file"), 'Geçmişi doğru okuyun.')
}
[IO.File]::WriteAllText((Join-Path $fixture '.gitignore'), "/target/`n")
& git -C $fixture init --quiet
if ($LASTEXITCODE) { throw 'Isolated fixture git init failed.' }
$clock = [Diagnostics.Stopwatch]::StartNew()
$passed = [Collections.Generic.List[string]]::new()
$invocations = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
function Run-Guard([string]$Name, [string]$Guard) {
    if ($invocations.Contains("$Name/$Guard")) { throw 'Duplicate guard invocation.' }
    $guardClock = [Diagnostics.Stopwatch]::StartNew()
    $guardRunspace = $null
    $guardPowerShell = $null
    $guardInput = $null
    $guardOutput = $null
    $guardWaitHandle = $null
    try {
        $budget = [int][Math]::Min(15000, 180000 - $clock.ElapsedMilliseconds)
        if ($budget -le 0) { throw 'Documentation controls exceeded 180 seconds.' }
        $guardRunspace = [Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace()
        $guardRunspace.Open()
        $guardPowerShell = [Management.Automation.PowerShell]::Create()
        $guardPowerShell.Runspace = $guardRunspace
        $null = $guardPowerShell.AddCommand((Join-Path $fixture "tools/ci/$Guard")).
            AddParameter('SummaryPath', "target/$Name-$Guard.md")
        $guardInput = [Management.Automation.PSDataCollection[psobject]]::new()
        $guardInput.Complete()
        $guardOutput = [Management.Automation.PSDataCollection[psobject]]::new()
        # Typed buffers retain output even when EndInvoke throws on a guard's terminating error.
        $guardAsync = $guardPowerShell.BeginInvoke($guardInput, $guardOutput)
        $null = $invocations.Add("$Name/$Guard")
        $guardWaitHandle = $guardAsync.AsyncWaitHandle
        $guardRemaining = [int][Math]::Max(0, $budget - $guardClock.ElapsedMilliseconds)
        if (!$guardWaitHandle.WaitOne($guardRemaining)) {
            $guardPowerShell.Stop()
            throw 'Guard timed out.'
        }
        $guardFailure = $null
        try { $null = $guardPowerShell.EndInvoke($guardAsync) }
        catch { $guardFailure = $_ }
        $guardLog = [Text.StringBuilder]::new()
        foreach ($guardRecord in $guardOutput) { $null = $guardLog.AppendLine([string]$guardRecord) }
        foreach ($guardStream in @('Error', 'Warning', 'Verbose', 'Debug', 'Information', 'Progress')) {
            foreach ($guardRecord in $guardPowerShell.Streams.$guardStream) {
                $null = $guardLog.AppendLine([string]$guardRecord)
            }
        }
        if ($null -ne $guardFailure) { $null = $guardLog.AppendLine([string]$guardFailure) }
        $log = $guardLog.ToString()
        if ($log.Length -gt 65536) { throw 'Guard output exceeds 64 KiB.' }
        [IO.File]::WriteAllText((Join-Path $evidence "$Name-$Guard.log"), $log)
        if ($null -ne $guardFailure -or $guardPowerShell.HadErrors -or
                $guardPowerShell.InvocationStateInfo.State -ne [Management.Automation.PSInvocationState]::Completed) {
            return 1
        }
        return 0
    } finally {
        try {
            if ($null -ne $guardPowerShell) { $guardPowerShell.Dispose() }
        } finally {
            try {
                if ($null -ne $guardRunspace) { $guardRunspace.Dispose() }
            } finally {
                if ($null -ne $guardWaitHandle) { $guardWaitHandle.Dispose() }
                if ($null -ne $guardInput) { $guardInput.Dispose() }
                if ($null -ne $guardOutput) { $guardOutput.Dispose() }
            }
        }
    }
}
$cases = @(
    @('native-label-ascii-destination', '[Sürüm geçmişi](docs/surum-gecmisi.md)', $true, $true),
    @('reference-destination', "[Sürüm geçmişi][ref]`n`n[ref]: docs/surum-gecmisi.md", $true, $true),
    @('inline-code', '`Ile [broken](absent.md)`', $true, $true),
    @('double-inline-code', '``Ile ` [broken](absent.md)``', $true, $true),
    @('tilde-fence', "~~~powershell`n[Convert]::ToBase64String(`$bytes)`nIle [broken](absent.md)`n~~~", $true, $true),
    @('backtick-fence', ('```powershell' + "`nIle [broken](absent.md)`n" + '```'), $true, $true),
    @('long-backtick-fence-short-inner', ('````text' + "`n" + '```' + "`nIle [broken](absent.md)`n" + '````'), $true, $true),
    @('mixed-fence-does-not-close', ("~~~~text`n" + '```' + "`nIle [broken](absent.md)`n~~~~"), $true, $true),
    @('angle-space-destination', '[Geçmişi](<docs/known file.md>)', $true, $true),
    @('escaped-parentheses', '[Geçmişi](docs/known\(item\).md)', $true, $true),
    @('balanced-parentheses', '[Geçmişi](docs/known(item).md)', $true, $true),
    @('query-and-fragment', '[Geçmişi](docs/surum-gecmisi.md?gecmis=1#Ile)', $true, $true),
    @('external-url-no-network', '[Geçmişi](https://example.invalid/gecmis)', $true, $true),
    @('native-title', '[Geçmişi](docs/surum-gecmisi.md "İlk geçmişi okuyun")', $true, $true),
    @('broken-inline', '[Geçmişi](absent.md)', $true, $false),
    @('broken-reference', "[Geçmişi][ref]`n`n[ref]: absent.md", $true, $false),
    @('broken-angle', '[Geçmişi](<absent file.md>)', $true, $false),
    @('broken-image', '![Geçmişi](absent.png)', $true, $false),
    @('escaped-image-marker-still-has-link', '\![Geçmişi](absent.md)', $true, $false),
    @('escaped-bracket-is-not-link', '!\[Geçmişi](absent.md)', $true, $true),
    @('bad-visible-label', '[gecmis](docs/surum-gecmisi.md)', $false, $true),
    @('bad-prose-before', 'Ile [Geçmişi](docs/surum-gecmisi.md)', $false, $true),
    @('bad-prose-after', '[Geçmişi](docs/surum-gecmisi.md) Hizli', $false, $true),
    @('bad-inline-title', '[Geçmişi](docs/surum-gecmisi.md "Ile")', $false, $true),
    @('bad-reference-title', "[Geçmişi][ref]`n`n[ref]: docs/surum-gecmisi.md 'Ile'", $false, $true),
    @('broken-after-long-fence', "~~~~`nIle [code](absent.md)`n~~~`n~~~~`n[Geçmişi](absent.md)", $true, $false),
    @('broken-after-mixed-fence', ('```' + "`n~~~`nIle [code](absent.md)`n" + '```' + "`n[Geçmişi](absent.md)"), $true, $false),
    @('escaped-code-is-prose', '\`Ile`', $false, $true),
    @('unclosed-inline-is-prose', '`Ile', $false, $true),
    @('broken-percent-path', '[Geçmişi](docs/%ZZ.md)', $true, $false)
)
if ($cases.Count -ne 30) { throw 'Expected all 30 documentation cases.' }
foreach ($case in $cases) {
    [IO.File]::WriteAllText((Join-Path $fixture 'tr/guide.md'), $case[1], [Text.UTF8Encoding]::new($false))
    $tr = Run-Guard $case[0] 'check-tr-docs.ps1'
    $link = Run-Guard $case[0] 'check-markdown-links.ps1'
    if (($tr -eq 0) -ne $case[2] -or ($link -eq 0) -ne $case[3]) {
        throw "Wrong guard result: $($case[0]), TR=$tr links=$link"
    }
    foreach ($spec in @(@('check-tr-docs.ps1', $case[2], 'Turkish docs quality validation failed'),
            @('check-markdown-links.ps1', $case[3], 'Markdown link validation failed'))) {
        if (!$spec[1] -and [IO.File]::ReadAllText((Join-Path $evidence "$($case[0])-$($spec[0]).log")) -notmatch $spec[2]) {
            throw 'Negative failed for an unrelated reason.'
        }
    }
    $passed.Add($case[0]); Write-Host "PASS $($case[0])"
}
$helper = Join-Path $fixture 'tools/ci/markdown-source.ps1'
[IO.File]::WriteAllText((Join-Path $fixture 'tr/guide.md'), '[Geçmişi](docs/surum-gecmisi.md)')
foreach ($guard in @('check-tr-docs.ps1', 'check-markdown-links.ps1')) {
    if ((Run-Guard 'helper-present' $guard) -ne 0) { throw 'Helper-present control failed.' }
}
Move-Item -LiteralPath $helper -Destination "$helper.hidden"
try {
    foreach ($guard in @('check-tr-docs.ps1', 'check-markdown-links.ps1')) {
        if ((Run-Guard 'missing-helper' $guard) -eq 0) { throw 'Missing parser helper was accepted.' }
        if ([IO.File]::ReadAllText((Join-Path $evidence "missing-helper-$guard.log")) -notmatch 'markdown-source\.ps1') {
            throw 'Missing-helper control failed for an unrelated reason.'
        }
        $passed.Add("missing-helper-$guard"); Write-Host "PASS missing-helper-$guard"
    }
} finally { Move-Item -LiteralPath "$helper.hidden" -Destination $helper }
$expectedInvocations = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($control in @($cases | ForEach-Object { $_[0] }) + @('helper-present', 'missing-helper')) {
    foreach ($guard in @('check-tr-docs.ps1', 'check-markdown-links.ps1')) {
        if (!$expectedInvocations.Add("$control/$guard")) { throw 'Duplicate documentation control.' }
    }
}
if ($invocations.Count -ne 64 -or $expectedInvocations.Count -ne 64 -or
        !$invocations.SetEquals($expectedInvocations)) { throw 'Expected exactly the same 64 guard invocations.' }
[ordered]@{result='PASS'; controls=$passed; guardInvocations=$invocations.Count;
    syntheticOnly=$true; javaExecuted=$false; remoteLinksFetched=$false} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
Write-Host "DOCUMENTATION_GUARD_CONTROLS_PASS=$($passed.Count); NO_REMOTE_OR_RUNTIME_ACCEPTANCE"
