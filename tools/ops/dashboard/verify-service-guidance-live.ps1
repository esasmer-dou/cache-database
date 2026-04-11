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
    'has_tr_service_guide_block=' + ($trHtml -match 'id="serviceGuideFirstFix"')
    'has_tr_service_guide_title=' + ($trHtml -match 'Seçili servis rehberi')
    'has_tr_service_click_binding=' + ($trHtml -match 'data-service-entry=')
    'has_tr_service_default_copy=' + ($trHtml -match 'Bir servis satırına basarak ayrıntılı operasyon rehberini aç\.')
    'has_en_service_guide_block=' + ($enHtml -match 'id="serviceGuideFirstFix"')
    'has_en_service_guide_title=' + ($enHtml -match 'Selected service guide')
    'has_en_service_click_binding=' + ($enHtml -match 'data-service-entry=')
    'has_en_service_default_copy=' + ($enHtml -match 'Click a service row to open the detailed operational guide\.')
)
