# Release Checklist

Bu kontrol listesi ilk public beta oncesinde ve sonraki release'lerde kullanilmalidir.

## Repo Hijyeni

- [LICENSE](../../LICENSE) dosyasinin hedeflenen public lisans secimiyle uyumlu oldugunu dogrula
- [SECURITY.md](../../SECURITY.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../../CODE_OF_CONDUCT.md) ve [SUPPORT.md](../../SUPPORT.md) dosyalarini gozden gecir
- issue template ve pull request template'lerinin guncel surece uydugunu dogrula

## Metadata

- [../../pom.xml](../../pom.xml) icindeki placeholder repo metadata alanlarini gercek degerlerle degistir:
  - `cachedb.release.projectUrl`
  - `cachedb.release.scmConnection`
  - `cachedb.release.scmDeveloperConnection`
  - `cachedb.release.issueUrl`
  - `cachedb.release.ciUrl`
  - maintainer identity alanlari
- artifact coordinate ve public package isimlerini dogrula

## Surumleme

- `-SNAPSHOT` surumunu hedef release surumune cek
- [../../CHANGELOG.md](../../CHANGELOG.md) dosyasini guncelle
- dokumanlardaki public beta vs GA metinlerini kontrol et

## Kanit

- production evidence workflow yesil
- coordination evidence workflow yesil
- runtime hotspot degisiklikleri icin guncel benchmark veya smoke kaniti var

## Publishing

- `oss-release` Maven profilinin sources ve javadocs uretebildigini dogrula
- secilen release kanali icin signing config ve secret'lari dogrula
- hedef release workflow ve credential'lari dogrula

## GitHub / Repo Ayarlari

- private vulnerability reporting'i ac
- default branch icin branch protection tanimla
- evidence workflow'larini required check yap
- dis katilimcilar icin label ve milestone'lari hazirla

## Dokumantasyon

- README ve Turkce README guncel
- production recipe rehberi guncel
- yeni tuning parametreleri dokumante edildi
- public beta sinirlari acikca yazili
