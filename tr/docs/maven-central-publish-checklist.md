# Maven Central Publish Checklist

İlk Maven Central publication öncesinde bu kontrol listesi kullanılmalıdır.

## Coordinates ve Metadata

- final `groupId`, `artifactId` ve version stratejisi doğrulandı
- [../../pom.xml](../../pom.xml) içindeki repo URL, SCM URL, issue URL ve CI URL doğrulandı
- [../../pom.xml](../../pom.xml) içindeki placeholder maintainer identity alanları dolduruldu
- lisans seçimi netleştirildi

## Artifact Şekli

- `oss-release` profili temiz build alıyor
- sources jar üretiliyor
- javadocs jar üretiliyor
- signing, gerçek key ile CI veya release ortamı içinde çalışıyor

## Publishing Setup

- Sonatype Central veya hedef registry hesabı hazır
- namespace ownership doğrulandı
- GitHub Actions veya release ortamında gerekli secret'lar tanımlı
- release workflow signed artifact publish edebiliyor

## Versioning

- release versiyonu seçildi
- release commit'te `-SNAPSHOT` kaldırıldı
- [../../CHANGELOG.md](../../CHANGELOG.md) güncellendi
- docs ve release note içindeki beta/GA metinleri gözden geçirildi

## Kanıt

- production evidence workflow yeşil
- coordination evidence workflow yeşil
- public-beta readiness workflow yeşil
- performans hassas değişiklikler için güncel benchmark kanıtı mevcut

## GitHub Release

- release note hazır
- tag seçildi
- release başlığı seçildi
- gerekiyorsa release artifact'ları yüklendi

## Publish Sonrası

- sıfırdan açılan örnek bir projede dependency tüketimi smoke-test edildi
- temiz Maven cache ile annotation-processor yolu doğrulandı
- eğer release branch akışı kullanıyorsan bir sonraki development versiyonu geri açıldı
