$ErrorActionPreference = 'Stop'

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psi.Arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=tr"'
$psi.WorkingDirectory = 'C:\Program Files\Docker\Docker\resources\bin'
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$process = [System.Diagnostics.Process]::Start($psi)
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()

if ($process.ExitCode -ne 0) {
    throw "dashboard html fetch failed: $stderr`n$stdout"
}

$content = $stdout
$scriptIndex = $stdout.IndexOf('<script>')
if ($scriptIndex -ge 0) {
    $content = $stdout.Substring(0, $scriptIndex)
}

$liveRefreshIndex = $content.IndexOf('Canlı Yenileme')
$resetToolsIndex = $content.IndexOf('Yönetim Sıfırlama Araçları')
$howIndex = $content.IndexOf('card-header">Bu Ekran Nasıl Okunur?')
$sectionGuideIndex = $content.IndexOf('card-header">Bölüm Rehberi')
$overviewIndex = $content.IndexOf('workspace-group-title">Genel Bakış')
$taxonomyIndex = $content.IndexOf('card-header">Terim Sözlüğü')

[pscustomobject]@{
    liveRefreshIndex = $liveRefreshIndex
    resetToolsIndex = $resetToolsIndex
    howToReadIndex = $howIndex
    sectionGuideIndex = $sectionGuideIndex
    overviewIndex = $overviewIndex
    taxonomyIndex = $taxonomyIndex
    liveBeforeReset = ($liveRefreshIndex -ge 0 -and $resetToolsIndex -ge 0 -and $liveRefreshIndex -lt $resetToolsIndex)
    resetBeforeHow = ($resetToolsIndex -ge 0 -and $howIndex -ge 0 -and $resetToolsIndex -lt $howIndex)
    howBeforeTaxonomy = ($howIndex -ge 0 -and $taxonomyIndex -ge 0 -and $howIndex -lt $taxonomyIndex)
    sectionGuideBeforeTaxonomy = ($sectionGuideIndex -ge 0 -and $taxonomyIndex -ge 0 -and $sectionGuideIndex -lt $taxonomyIndex)
    howBeforeSectionGuide = ($howIndex -ge 0 -and $sectionGuideIndex -ge 0 -and $howIndex -lt $sectionGuideIndex)
    sectionGuideBeforeOverview = ($sectionGuideIndex -ge 0 -and $overviewIndex -ge 0 -and $sectionGuideIndex -lt $overviewIndex)
} | ConvertTo-Json -Depth 3
