param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Lane,
    [string]$OutputRoot = '/workspace/evidence',
    [datetime]$SinceUtc = [datetime]::MinValue
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath '/workspace/builds')) { throw 'Run inside the dedicated Kubernetes workspace.' }
$destination = Join-Path $OutputRoot $Lane
$null = New-Item -ItemType Directory -Force -Path $destination
$passwordPath = '/workspace/fixture-password'
$password = if (Test-Path $passwordPath) { [IO.File]::ReadAllText($passwordPath).Trim() } else { '' }
$root = (Get-Location).Path
$marker = Join-Path $root 'target/kubernetes-validation-start.txt'
if ($SinceUtc -eq [datetime]::MinValue -and (Test-Path -LiteralPath $marker)) {
    $SinceUtc = [datetime]::Parse([IO.File]::ReadAllText($marker).Trim(),
            [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
}
$records = @()
foreach ($file in Get-ChildItem -Path . -Recurse -Filter 'TEST-*.xml' -File |
        Where-Object { $_.FullName -match '/target/(surefire|failsafe)-reports/' -and $_.LastWriteTimeUtc -ge $SinceUtc }) {
    if ($file.Length -gt 32MB) { throw "Oversized test report: $($file.Name)" }
    $content = [IO.File]::ReadAllText($file.FullName)
    if ($password) { $content = $content.Replace($password,'[redacted-fixture-password]') }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $reader = [Xml.XmlReader]::Create([IO.StringReader]::new($content),$settings)
    try { $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) }
    finally { $reader.Dispose() }
    foreach ($node in @($xml.SelectNodes('//testsuite/properties'))) { [void]$node.ParentNode.RemoveChild($node) }
    $suite = $xml.DocumentElement
    $records += [pscustomobject][ordered]@{
        name = [string]$suite.name; tests = [int]$suite.tests
        failures = [int]$suite.failures; errors = [int]$suite.errors
        skipped = [int]$suite.skipped; seconds = [double]::Parse([string]$suite.time,[Globalization.CultureInfo]::InvariantCulture)
    }
    $relative = [IO.Path]::GetRelativePath($root,$file.FullName)
    $target = Join-Path $destination $relative
    $null = New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)
    $xml.Save($target)
}
if (!$records.Count) { throw 'No test reports were found.' }
$summary = [ordered]@{
    lane = $Lane; generatedUtc = [DateTime]::UtcNow.ToString('o'); sinceUtc = $SinceUtc.ToString('o')
    suites = $records.Count
    tests = ($records | Measure-Object -Property tests -Sum).Sum
    failures = ($records | Measure-Object -Property failures -Sum).Sum
    errors = ($records | Measure-Object -Property errors -Sum).Sum
    skipped = ($records | Measure-Object -Property skipped -Sum).Sum
    results = $records
}
if ($summary.tests -lt 1 -or $null -eq $summary.failures -or $null -eq $summary.errors) {
    throw 'Incomplete test totals; evidence cannot be accepted.'
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'summary.json') -Encoding utf8
Write-Output ("{0}: tests={1}, failures={2}, errors={3}, skipped={4}" -f $Lane,$summary.tests,$summary.failures,$summary.errors,$summary.skipped)
if ($summary.failures -or $summary.errors) { throw 'Test evidence contains failures.' }
