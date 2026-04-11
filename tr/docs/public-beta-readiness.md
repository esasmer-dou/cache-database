# Public Beta Readiness

Bu doküman, public beta için yeterince olgun olan kısımları ve GA duyurusunu
hâlâ bloke eden konuları özetler.

## Public Beta İçin Hazır Olanlar

- çekirdek production hikayesi için ölçülmüş runtime kanıtı var
- multi-instance coordination için resmi smoke ve CI lane mevcut
- generated ergonomi, minimal repository overhead'ine yakın kalıyor
- relation-heavy read recipe'leri dokümante ve benchmark'lı
- Türkçe ve İngilizce ürün odaklı dokümanlar var

## Repo'yu Public Açmadan Önce Hâlen Gerekenler

- [../../pom.xml](../../pom.xml) içindeki repo URL ve maintainer metadata alanlarını gerçek değerlerle doldur
- GitHub security reporting ve branch protection'ı aç
- mevcut beta lisans seçiminin korunup korunmayacagina karar ver
- seçilen publish/release kanalı ve credential'ları doğrula

## GA İçin Hâlen Eksik Olanlar

- daha uzun soak ve Redis HA failover kanıtları
- signing ve publication tarafında daha sert release engineering
- stabil API uyumluluk politikası ve upgrade notları
- projection replay/backfill ve incident drill tarafında daha derin operasyonel hikâye

## Özet Hüküm

`cache-database` güçlü bir public beta seviyesinde. Ancak henüz "koşulsuz GA"
duyurusu için konumlanmış değil.
