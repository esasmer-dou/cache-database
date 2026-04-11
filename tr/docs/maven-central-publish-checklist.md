# Maven Central Publish Checklist

Ilk Maven Central publication oncesinde bu kontrol listesi kullanilmalidir.

## Coordinates ve Metadata

- final `groupId`, `artifactId` ve version stratejisi dogrulandi
- [../../pom.xml](../../pom.xml) icindeki repo URL, SCM URL, issue URL ve CI URL dogrulandi
- [../../pom.xml](../../pom.xml) icindeki placeholder maintainer identity alanlari dolduruldu
- lisans secimi netlestirildi

## Artifact Sekli

- `oss-release` profili temiz build aliyor
- sources jar uretiliyor
- javadocs jar uretiliyor
- signing, gercek key ile CI veya release ortami icinde calisiyor

## Publishing Setup

- Sonatype Central veya hedef registry hesabi hazir
- namespace ownership dogrulandi
- GitHub Actions veya release ortaminda gerekli secret'lar tanimli
- release workflow signed artifact publish edebiliyor

## Versioning

- release versiyonu secildi
- release commit'te `-SNAPSHOT` kaldirildi
- [../../CHANGELOG.md](../../CHANGELOG.md) guncellendi
- docs ve release note icindeki beta/GA metinleri gozden gecirildi

## Kanit

- production evidence workflow yesil
- coordination evidence workflow yesil
- public-beta readiness workflow yesil
- performans hassas degisiklikler icin guncel benchmark kaniti mevcut

## GitHub Release

- release note hazir
- tag secildi
- release basligi secildi
- gerekiyorsa release artifact'lari yuklendi

## Publish Sonrasi

- sifirdan acilan ornek bir projede dependency tuketimi smoke-test edildi
- temiz Maven cache ile annotation-processor yolu dogrulandi
- eger release branch akisi kullaniyorsan bir sonraki development versiyonu geri acildi
