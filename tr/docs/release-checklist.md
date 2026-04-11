# Release Checklist

Bu kontrol listesi ilk public beta öncesinde ve sonraki release'lerde kullanılmalıdır.

## Repo Hijyeni

- [LICENSE](../../LICENSE) dosyasının hedeflenen public lisans seçimiyle uyumlu olduğunu doğrula
- [SECURITY.md](../../SECURITY.md), [CONTRIBUTING.md](../../CONTRIBUTING.md), [CODE_OF_CONDUCT.md](../../CODE_OF_CONDUCT.md) ve [SUPPORT.md](../../SUPPORT.md) dosyalarını gözden geçir
- issue template ve pull request template'lerinin güncel sürece uyduğunu doğrula

## Metadata

- [../../pom.xml](../../pom.xml) içindeki placeholder repo metadata alanlarını gerçek değerlerle değiştir:
  - `cachedb.release.projectUrl`
  - `cachedb.release.scmConnection`
  - `cachedb.release.scmDeveloperConnection`
  - `cachedb.release.issueUrl`
  - `cachedb.release.ciUrl`
  - maintainer identity alanlari
- artifact coordinate ve public package isimlerini doğrula

## Surumleme

- `-SNAPSHOT` surumunu hedef release surumune çek
- [../../CHANGELOG.md](../../CHANGELOG.md) dosyasini güncelle
- dokümanlardaki public beta vs GA metinlerini kontrol et
- release sonrasında [release-flow.md](release-flow.md) üzerinden `main` branch'ini bir sonraki `-SNAPSHOT` surume ac

## Kanıt

- production evidence workflow yesil
- coordination evidence workflow yesil
- runtime hotspot değisiklikleri için güncel benchmark veya smoke kanıti var

## Publishing

- `oss-release` Maven profilinin sources ve javadocs üretebildigini doğrula
- seçilen release kanali için signing config ve seçret'lari doğrula
- hedef release workflow ve credential'lari doğrula

## GitHub / Repo Ayarlari

- private vulnerability reporting'i ac
- default branch için branch protection tanımla
- evidence workflow'larini required check yap
- dış katilimcilar için label ve milestone'lari hazırla

## Dokümantasyon

- README ve Türkçe README güncel
- production recipe rehberi güncel
- yeni tuning parametreleri dokümante edildi
- public beta sinirlari açıkça yazıli
