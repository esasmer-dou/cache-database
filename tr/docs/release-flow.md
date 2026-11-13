# Kararlı Sürüm Akışı

Her kararlı sürüm, değişmez bir SDK paket kümesi kullanır. Sürüm etiketi bu
paketlerin kaynak kodunu tanımlar. Derlemeden sonra belgelerde veya doğrulama
araçlarında değişiklik yapılmışsa, bu farklar incelenmiş bir kaynak eşdeğerliği
kaydıyla açıklanmalıdır.

## Sürüm Sırası

1. Kök POM ile bütün modüllerde kararlı semantic version değerini ayarla.
2. `CHANGELOG.md`, Türkçe ve İngilizce sürüm notları, README dosyaları ve üç
   bağımsız sample projeyi güncelle.
3. Gerekli modül, veritabanı sağlayıcısı, HA, belge, API, performans, örnek uygulama
   ve paket kontrollerini onaylı Kubernetes ortamında çalıştır. Özgün sonuçları
   ve paket sağlama toplamlarını koru. Tek bir toplu başarı kaydı almak için
   sabitlenmiş SDK'yı yeniden derleme veya geçmiş testleri tekrar çalıştırma.
4. Kanonik kanıt doğrulamasını tamamla. Önceki başarısız sonuçları koru; izin
   verilen tamamlayıcı sonuçları özgün kayıtlarına açıkça bağla. Eksik kanıt,
   başarılı kontrol değil, yayın engelidir.
5. `main` dalını gönder; aynı commit üzerinde açıklamalı kararlı etiketi oluştur.
   Gerekli uzak kanıt kontrollerini ve yayın öncesi hazırlık kontrolünü tamamla.
   Etiketin oluşturulmuş olması tek başına yayın onayı değildir.
6. Doğrulanmış ZIP, BOM, derlenmiş JAR, kaynak ve Javadoc JAR dosyaları ile sağlama
   toplamlarını içeren taslak GitHub Release kaydını hazırla. Yalnız bütün zorunlu
   yayın öncesi kontroller geçtikten sonra kararlı sürüm olarak yayımla.
7. `Public Maven Repository Publish` akışını çalıştır. Bu akış yayımlanan ZIP'i
   kullanır; SDK'yı yeniden derlemez. Ardından Maven bağımlılıklarının kimlik
   doğrulaması olmadan indirilebildiğini kontrol et.
8. Aynı paketleri ek dağıtım kanalı olarak GitHub Packages'a yayımla.
9. Yayın sonrası hazırlık kontrollerini çalıştır. Framework paketinin yayımlanması,
   uygulamanın kendi HA topolojisini veya geçiş kapsamını sertifikalandırmaz.
10. PostgreSQL, SQL Server ve Oracle örnek uygulamalarını onaylı Kubernetes
    ortamında uzak, anonim Maven deposundan derle. Ardından güncel kaynaklarını,
    etiketlerini ve sürüm dosyalarını gönder.

Var olan bir sürümü yeniden derleme veya değiştirme. Düzeltme gerekiyorsa yeni
bir semantic version yayımla.

## Sonraki Geliştirme Sürümü

Sürüm doğrulandıktan sonra yeni geliştirme başladığında `main` branch'ini bir
sonraki `-SNAPSHOT` sürümüne taşı. Kararlı etiketler ve Maven yolları değişmez
kalır.

## Branch Kuralı

`main`, herkese açık entegrasyon branch'idir. Gerektiğinde kısa ömürlü feature
branch kullan, merge sonrasında sil ve iç çalışma için kullanılan `codex/*`
branch'lerini remote'a gönderme.
