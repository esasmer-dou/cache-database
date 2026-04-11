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

$psiEn = [System.Diagnostics.ProcessStartInfo]::new()
$psiEn.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$psiEn.Arguments = 'exec cachedb-demo-ui bash -lc "wget -qO- http://127.0.0.1:8080/dashboard?lang=en"'
$psiEn.WorkingDirectory = 'C:\Program Files\Docker\Docker\resources\bin'
$psiEn.RedirectStandardOutput = $true
$psiEn.RedirectStandardError = $true
$psiEn.UseShellExecute = $false
$psiEn.CreateNoWindow = $true

$processEn = [System.Diagnostics.Process]::Start($psiEn)
$stdoutEn = $processEn.StandardOutput.ReadToEnd()
$stderrEn = $processEn.StandardError.ReadToEnd()
$processEn.WaitForExit()

if ($processEn.ExitCode -ne 0) {
    throw "dashboard en html fetch failed: $stderrEn`n$stdoutEn"
}

@(
    'has_runtime_profile_tr=' + ($stdout -match 'Çalışma Profili Kontrolü')
    'has_tuning_tr=' + ($stdout -match 'Etkin Ayarlar')
    'has_reset_text_tr=' + ($stdout -match 'Henüz telemetri sıfırlaması çalıştırılmadı')
    'has_service_status_tr=' + ($stdout -match 'Servis Durumu')
    'no_tuning_slice=' + (-not ($stdout -match '\.slice\(0,60\)'))
    'has_relation_states_tr=' + ($stdout -match 'İlişki Durumları')
    'has_copy_markdown_tr=' + ($stdout -match 'Markdown Olarak Kopyala')
    'has_download_json_tr=' + ($stdout -match 'JSON Olarak İndir')
    'has_save_note_tr=' + ($stdout -match 'Olay Notu Olarak Kaydet')
    'has_retry_policy_tr=' + ($stdout -match 'Yeniden Deneme Politikası')
    'has_last_delivery_state_tr=' + ($stdout -match 'Son Teslim Durumu')
    'has_why_slow_tr=' + ($stdout -match 'Neden Yavaş\?')
    'has_why_degraded_tr=' + ($stdout -match 'Neden Bozuldu\?')
    'has_suspicious_step_tr=' + ($stdout -match 'En Şüpheli Adım')
    'has_triage_prefix_tr=' + ($stdout -match 'Ana darboğaz:')
    'has_signal_write_summary_tr=' + ($stdout -match 'Arka plan yazma kuyruğu, başarısız kayıtlar ve bekleyen sıkıştırma işlerini birlikte yorumlar')
    'has_signal_runtime_summary_tr=' + ($stdout -match 'Aktif profil, baskı düzeyi ve önceliklendirme sonucu birlikte okunur')
    'has_estimated_cost_tr=' + ($stdout -match 'Tahmini Maliyet')
    'has_trend_samples_tr=' + ($stdout -match 'örnek ')
    'has_trend_last_tr=' + ($stdout -match 'son ')
    'has_failing_signals_prefix_tr=' + ($stdout -match 'Açık ')
    'has_failing_signals_recent_tr=' + ($stdout -match 'Son dönemde ')
    'has_failing_signals_last_seen_tr=' + ($stdout -match 'Son görülme ')
    'has_route_last_state_taxonomy_tr=' + ($stdout -match 'beklemede, teslim edildi ya da görülen hata türü')
    'has_service_retry_label_tr=' + ($stdout -match 'Yeniden deneme sayısı')
    'has_service_backoff_label_tr=' + ($stdout -match 'Bekleme aralığı')
    'has_service_claim_timeout_label_tr=' + ($stdout -match 'Talep zaman aşımı')
    'has_service_poll_timeout_label_tr=' + ($stdout -match 'Yoklama zaman aşımı')
    'has_backlog_action_tr=' + ($stdout -match 'Arka plan yazma, başarısız kayıt kuyruğu ve işleyici hatalarını birlikte kontrol et')
    'has_backlog_drain_action_tr=' + ($stdout -match 'Birikim boşalıyor olabilir')
    'has_severity_legend_critical_tr=' + ($stdout -match 'kritik \(')
    'has_severity_legend_warning_tr=' + ($stdout -match 'uyarı \(')
    'has_severity_legend_info_tr=' + ($stdout -match 'bilgi \(')
    'has_trend_samples_en=' + ($stdoutEn -match 'samples ')
    'has_trend_last_en=' + ($stdoutEn -match 'last ')
    'has_failing_signals_prefix_en=' + ($stdoutEn -match 'Active ')
    'has_failing_signals_recent_en=' + ($stdoutEn -match ' / Recent ')
    'has_failing_signals_last_seen_en=' + ($stdoutEn -match 'Last seen ')
    'has_service_retry_label_en=' + ($stdoutEn -match 'Retry count')
    'has_service_backoff_label_en=' + ($stdoutEn -match 'Backoff delay')
    'has_service_claim_timeout_label_en=' + ($stdoutEn -match 'Claim timeout')
    'has_service_poll_timeout_label_en=' + ($stdoutEn -match 'Poll timeout')
    'has_severity_legend_critical_en=' + ($stdoutEn -match 'critical \(')
    'has_severity_legend_warning_en=' + ($stdoutEn -match 'warning \(')
    'has_severity_legend_info_en=' + ($stdoutEn -match 'info \(')
)
