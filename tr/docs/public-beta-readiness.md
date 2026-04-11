# Public Beta Readiness

Bu dokuman, public beta icin yeterince olgun olan kisimlari ve GA duyurusunu
halen bloke eden konulari ozetler.

## Public Beta Icin Hazir Olanlar

- cekirdek production hikayesi icin olculmus runtime kaniti var
- multi-instance coordination icin resmi smoke ve CI lane mevcut
- generated ergonomi, minimal repository overhead'ine yakin kaliyor
- relation-heavy read recipe'leri dokumante ve benchmark'li
- Turkce ve Ingilizce urun odakli dokumanlar var

## Repo'yu Public Acmadan Once Halen Gerekenler

- [../../pom.xml](../../pom.xml) icindeki repo URL ve maintainer metadata alanlarini gercek degerlerle doldur
- GitHub security reporting ve branch protection'i ac
- mevcut beta lisans seciminin korunup korunmayacagina karar ver
- secilen publish/release kanali ve credential'lari dogrula

## GA Icin Halen Eksik Olanlar

- daha uzun soak ve Redis HA failover kanitlari
- signing ve publication tarafinda daha sert release engineering
- stabil API uyumluluk politikasi ve upgrade notlari
- projection replay/backfill ve incident drill tarafinda daha derin operasyonel hikaye

## Ozet Hukum

`cache-database` guclu bir public beta seviyesinde. Ancak henuz "kosulsuz GA"
duyurusu icin konumlanmis degil.
