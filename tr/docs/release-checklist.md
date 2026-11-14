# Sürüm Yayım Kontrol Listesi

[English](../../docs/release-checklist.md)

Bu liste, numaralandırılmış bir framework paketini yayımlamak içindir.
Production GA onayı ve bir uygulamanın canlıya geçişi ayrı kararlardır.
Ayrıntılar için [olgunluk belgesine](production-olgunlugu.md) bakın.

## 1. Sürüm ve Kaynak Kod

- Ana POM, BOM, işlemci, çalışma zamanı ve sağlayıcı modüllerinde aynı kararlı sürümü kullanın.
- Değişiklik günlüğünü, sürüm notlarını ve Türkçe/İngilizce geçiş açıklamalarını güncelleyin.
- Paketlenen çalışma zamanının hangi commit'ten derlendiğini belirtin. Sonraki belge veya yayın aracı değişikliklerini ayrı gösterin.
- Açıklamalı bir etiket oluşturup gönderin; mevcut etiketi değiştirmeyin.

## 2. Mevcut Test Sonuçları

- Paketlenen çalışma zamanına ait doğruluk, uyumluluk, sağlayıcı ve performans sonuçlarını inceleyin. Sağlanamayan hedefleri ve kabul edilmiş istisnaları açıklayın.
- Dosya yayımlamak veya yeni rapor oluşturmak için değişmemiş SDK'yı yeniden derlemeyin; geçmiş başarılı testleri tekrarlamayın.
- Çalışma zamanı davranışı değiştiyse ilgili testler yine gereklidir. Eksik sonuç başarı sayılmaz. Özgün başarısızlıkları koruyun.
- Özel depoya kayıt aktarımı ve kanonik kayıt birleştirme, isteğe bağlı tanılama işlemleridir; paket yayımının ön koşulu değildir.

0.19.0'da ham COUNT/FACET için iki kat işlem hacmi hedefi sağlanmamıştır.
Açık kabul istisnası [sürüm notlarında](releases/v0.19.0.md) korunur.
Mevcut Kubernetes sonuçları kullanılır; yeni bir tam kabul koşusu yapıldığı iddia edilmez.

## 3. Paket Dosyaları

- Beklenen binary, kaynak ve Javadoc JAR'larını, POM'ları, BOM'u ve SHA-256 sağlama toplamlarını doğrulayın.
- Dağıtım ZIP'inin aynı modül ve sürüm kümesini içerdiğini kontrol edin.
- 0.19.0 dağıtımı; sağlama toplamları, `SHA256SUMS` ve dağıtım ZIP'i dahil toplam 175 dosyadır. Bu kümede 63 JAR ve 23 POM bulunur.
- Yayımlanmış dosyaları değiştirmeyin. Binary değişikliği için yeni sürüm çıkarın.

## 4. Yayımlama

- GitHub sürümünü taslak oluşturun. Tüm dosyaları ekleyip adlarını, boyutlarını ve SHA-256 özetlerini doğruladıktan sonra yayımlayın.
- Mevcut dağıtım iş akışları, aynı dosyaları yeniden derlemeden açık Maven deposuna ve GitHub Packages'a aktarır.
- İş akışlarının gerçek sonuçlarını ve kimlik doğrulamasız Maven erişimini kontrol edin. İşlemin başlaması, yayımın tamamlandığı anlamına gelmez.
- Üç örnek projenin yayımlanan sürümü kullandığını ve Türkçe/İngilizce bağımlılık açıklamalarının eşleştiğini doğrulayın.

## 5. Ayrı Canlıya Geçiş Onayı

Paket yayımı; uygulama rotası sertifikası, yeni performans sonucu,
Oracle Data Guard/SQL Server Always On sertifikası veya her ortam için
Production GA onayı değildir. Topoloji ve uygulama kontrollerini ilgili
iddia ya da dağıtım gerektiğinde çalıştırın; dosya yayımını bunlara bağlamayın.
