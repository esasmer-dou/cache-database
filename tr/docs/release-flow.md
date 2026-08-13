# Kararlı Sürüm Akışı

Her kararlı sürüm, aynı değişmez etiket üzerinden derlenir, test edilir,
yayımlanır ve doğrulanır.

## Sürüm Sırası

1. Kök POM ile bütün modüllerde kararlı semantic version değerini ayarla.
2. `CHANGELOG.md`, Türkçe ve İngilizce sürüm notları, README dosyaları ve iki
   bağımsız sample projeyi güncelle.
3. Tüm reactor testlerini, provider entegrasyonlarını, Docker HA kontrollerini,
   dokümantasyon kontrollerini, API uyumluluğunu, benchmark eşiklerini ve release
   artifact doğrulamasını yerelde çalıştır.
4. `main` branch'ini gönder; aynı commit için `Framework Readiness` ve
   `Production Evidence` workflow'larının geçmesini bekle.
5. Açıklamalı kararlı etiketi oluştur ve gönder.
6. `Public Maven Repository Publish` tamamlanınca etiketteki artifact'lerin
   kimlik doğrulaması olmadan Maven ile indirilebildiğini kontrol et.
7. Geriye dönük dağıtım kanalı olarak GitHub Packages yayımını çalıştır.
8. ZIP, BOM, binary JAR ve checksum dosyalarıyla prerelease olmayan GitHub
   Release kaydını oluştur.
9. Etiket için `Production GA Release Readiness` kontrolünü çalıştır.
10. PostgreSQL ve SQL Server sample projelerini uzak ve anonim Maven deposundan
    derle; ardından iki sample için de etiket ve release oluştur.

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
