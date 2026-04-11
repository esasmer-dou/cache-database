# Public Beta Readiness

Bu doküman, public beta için yeterince olgun olan kisimlari ve GA duyurusunu
halen bloke eden konulari özetler.

## Public Beta İçin Hazır Olanlar

- çekirdek production hikayesi için ölçulmus runtime kanıti var
- multi-instance coordination için resmi smoke ve CI lane mevcut
- generated ergonomi, minimal repository overhead'ine yakın kaliyor
- relation-heavy read recipe'leri dokümante ve benchmark'li
- Türkçe ve Ingilizce urun odaklı dokümanlar var

## Repo'yu Public Acmadan Önce Halen Gerekenler

- [../../pom.xml](../../pom.xml) içindeki repo URL ve maintainer metadata alanlarini gerçek değerlerle doldur
- GitHub seçurity reporting ve branch protection'i ac
- mevcut beta lisans seçiminin korunup korunmayacagina karar ver
- seçilen publish/release kanali ve credential'lari doğrula

## GA İçin Halen Eksik Olanlar

- daha uzun soak ve Redis HA failover kanıtlari
- signing ve publication tarafinda daha sert release engineering
- stabil API uyumluluk politikasi ve upgrade notlari
- projection replay/backfill ve incident drill tarafinda daha derin operasyonel hikaye

## Özet Hukum

`cache-database` guclu bir public beta seviyesinde. Ancak henuz "koşulsuz GA"
duyurusu için konumlanmis değil.
