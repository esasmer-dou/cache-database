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
  - maintainer identity alanları
- artifact coordinate ve public package isimlerini doğrula

## Sürümleme

- `-SNAPSHOT` sürümünü hedef release sürümüne çek
- [../../CHANGELOG.md](../../CHANGELOG.md) dosyasını güncelle
- dokümanlardaki public beta vs GA metinlerini kontrol et
- release sonrasında [release-flow.md](release-flow.md) üzerinden `main` branch'ini bir sonraki `-SNAPSHOT` sürüme aç

## Kanıt

- production evidence workflow yeşil
- coordination evidence workflow yeşil
- framework GA için lokal Docker veya CI outage/restart evidence yeşil
- release iddiası managed Redis topolojisini kapsıyorsa staging Redis HA
  workflow yeşil
- tam migration route coverage, generic framework release için değil,
  production cutover yapacak uygulama için zorunlu
- runtime hotspot değişiklikleri için güncel benchmark veya smoke kanıtı var

## Publishing

- `oss-release` Maven profilinin sources ve javadocs üretebildiğini doğrula
- Linux release package yolunun geçtiğini doğrula; yalnızca geliştirici makinesindeki build yeterli değildir
- seçilen release kanalı için signing config ve secret'ları doğrula
- hedef release workflow ve credential'ları doğrula
- Maven Central resmi dağıtım kanalı olarak seçildiyse GA duyurusu öncesinde
  signed publish sonucunun başarılı olduğunu doğrula

## GitHub / Repo Ayarları

- private vulnerability reporting'i aç
- default branch için branch protection tanımla
- evidence workflow'larını required check yap
- dış katılımcılar için label ve milestone'ları hazırla

## Dokümantasyon

- README ve Türkçe README güncel
- production recipe rehberi güncel
- yeni tuning parametreleri dokümante edildi
- public beta sınırları açıkça yazılı
