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
    'has_tr_fetch_depth_status=' + ($trHtml -match 'Fetch derinliği aşıldı')
    'has_tr_fetch_depth_detail=' + ($trHtml -match 'İstenen fetch yolu izin verilen ilişki derinliğini aşıyor\. İzin verilen: \{allowed\}, istenen: \{requested\}\.')
    'has_tr_fetch_loader_missing_detail=' + ($trHtml -match 'Bu fetch yolu istendi, ancak kaynak varlık için somut bir ilişki yükleyicisi kayıtlı değil\.')
    'has_tr_fetch_path_unverified_detail=' + ($trHtml -match 'Fetch yolu tanımlı görünüyor, ancak binding doğrulaması tamamlanamadı\.')
    'has_tr_usage_fetch=' + ($trHtml -match 'case "explain\.usage\.fetch" -> "Getirme"')
    'has_en_fetch_depth_status=' + ($enHtml -match 'Fetch depth exceeded')
    'has_en_fetch_depth_detail=' + ($enHtml -match 'Requested fetch path exceeds the allowed relation depth\. Allowed: \{allowed\}, requested: \{requested\}\.')
    'has_en_fetch_loader_missing_detail=' + ($enHtml -match 'This fetch path was requested, but no concrete relation loader is registered for the source entity\.')
    'has_en_fetch_path_unverified_detail=' + ($enHtml -match 'The fetch path is declared, but its binding could not be fully verified\.')
    'has_en_usage_fetch=' + ($enHtml -match 'case "explain\.usage\.fetch" -> "Fetch"')
)
