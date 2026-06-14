# Maven Central Publish Checklist

Maven Central resmi dağıtım kanalı olarak seçildiyse bu kontrol listesi
kullanılmalıdır. Maven Central tek seçenek değildir; ancak public Java tüketimi
için BEST varsayılan kanaldır.

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
- `Maven Central Publish` hedef tag üzerinde manuel çalıştırılıyor
- GA için `gaRelease=true` kullanılıyor; böylece workflow imzalı artifact
  göndermeden önce GA preflight kontrolünü çalıştırıyor

## Versioning

- release versiyonu seçildi
- release commit'te `-SNAPSHOT` kaldırıldı
- [../../CHANGELOG.md](../../CHANGELOG.md) güncellendi
- docs ve release note içindeki beta/GA metinleri gözden geçirildi
- Maven Central resmi dağıtım kanalı olarak açıkça seçildi

## Kanıt

- production evidence workflow yeşil
- coordination evidence workflow yeşil
- framework GA hedefleniyorsa lokal Docker veya CI outage/restart evidence yeşil
- release iddiası managed Redis topology evidence içeriyorsa staging Redis HA
  workflow yeşil
- release iddiası managed SQL Server HA veya Always On evidence içeriyorsa
  staging MSSQL HA workflow yeşil
- production cutover yapacak uygulama için tam migration route coverage
  doğrulaması yeşil
- GitHub release yayınlanmadan önce seçilen opsiyonel kapılarla
  `Production GA Release Readiness` yeşil
- public-beta readiness workflow yeşil
- performans hassas değişiklikler için güncel benchmark kanıtı mevcut

## GitHub Release

- release note hazır
- tag seçildi
- tag için imzalı Maven Central publish tamamlandı
- release başlığı seçildi
- gerekiyorsa release artifact'ları yüklendi

## Publish Sonrası

- sıfırdan açılan örnek bir projede dependency tüketimi smoke-test edildi
- temiz Maven cache ile annotation-processor yolu doğrulandı
- eğer release branch akışı kullanıyorsan bir sonraki development versiyonu geri açıldı
