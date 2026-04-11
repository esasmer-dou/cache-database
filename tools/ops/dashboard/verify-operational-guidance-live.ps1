$ErrorActionPreference = 'Stop'

function Invoke-DockerFetch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = "exec cachedb-demo-ui bash -lc `"wget -qO- $Url`""
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
        throw "docker fetch failed for $Url.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

$trHtml = Invoke-DockerFetch 'http://127.0.0.1:8080/dashboard?lang=tr'
$enHtml = Invoke-DockerFetch 'http://127.0.0.1:8080/dashboard?lang=en'

@(
    'has_tr_triage_guide_block=' + ($trHtml -match 'id="triageGuideFirstFix"')
    'has_tr_background_guide_block=' + ($trHtml -match 'id="backgroundGuideFirstFix"')
    'has_tr_health_guide_block=' + ($trHtml -match 'id="healthGuideFirstFix"')
    'has_tr_first_fix_label=' + ($trHtml -match 'İlk düzeltme önerisi')
    'has_tr_check_area_label=' + ($trHtml -match 'Kontrol edilecek yer')
    'has_tr_likely_cause_label=' + ($trHtml -match 'Muhtemel kök neden')
    'has_tr_background_no_error_copy=' + ($trHtml -match 'Şu anda aktif worker hatası görünmüyor\. Yeni bir hata oluşursa ilk inceleme buradan başlar\.')
    'has_en_triage_guide_block=' + ($enHtml -match 'id="triageGuideFirstFix"')
    'has_en_background_guide_block=' + ($enHtml -match 'id="backgroundGuideFirstFix"')
    'has_en_health_guide_block=' + ($enHtml -match 'id="healthGuideFirstFix"')
    'has_en_first_fix_label=' + ($enHtml -match 'First fix')
    'has_en_check_area_label=' + ($enHtml -match 'Check here')
    'has_en_likely_cause_label=' + ($enHtml -match 'Likely root cause')
    'has_en_background_no_error_copy=' + ($enHtml -match 'No active worker error is visible right now\. If a new failure appears, start here first\.')
)
