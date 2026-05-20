# Açık Beta Hazırlık Durumu

Bu doküman, açık beta için yeterince olgun olan alanları ve GA duyurusunu
hâlâ engelleyen konuları özetler.

## Açık Beta İçin Hazır Olanlar

- çekirdek production hikâyesi için ölçülmüş çalışma zamanı kanıtı var
- çok instance'lı koordinasyon için resmi smoke testi ve CI hattı mevcut
- üretilmiş API kullanımı, doğrudan repository kullanımına yakın düşük ek yükte kalıyor
- çok ilişkili okuma ekranları için reçeteler ve benchmark kanıtları var
- Türkçe ve İngilizce ürün odaklı dokümanlar hazırlanmış durumda

## Repoyu Açık Hale Getirmeden Önce Gerekenler

- [../../pom.xml](../../pom.xml) içindeki repo URL ve maintainer metadata alanlarını gerçek değerlerle doldur
- GitHub security reporting ve branch protection ayarlarını aç
- mevcut beta lisans seçiminin korunup korunmayacağına karar ver
- seçilen publish/release kanalını ve credential'ları doğrula

## GA İçin Hâlen Eksik Olanlar

- daha uzun soak ve Redis HA failover kanıtları
- signing ve publication tarafında daha sert release engineering
- stabil API uyumluluk politikası ve upgrade notları
- projection replay/backfill ve incident drill tarafında daha derin operasyonel hikâye

## Özet Hüküm

`cache-database` güçlü bir açık beta seviyesinde. Ancak henüz "koşulsuz GA"
duyurusu için konumlanmış değil.
