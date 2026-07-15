# Stable Release Launch Kit

English version: [../../docs/stable-release-launch-kit.md](../../docs/stable-release-launch-kit.md)

Bu sayfa, beta olmayan bir CacheDB release'ini GitHub Releases veya seçilen
başka bir resmi paket kanalı üzerinden yayınlarken kullanılmalıdır.

## GitHub About Alanı

```text
Redis-first Java data layer with bounded hot sets, projections, compile-time generated APIs, and durable SQL write-behind.
```

## Önerilen Topic'ler

```text
java, redis, sql, postgresql, mssql, cache, cqrs, projections, orm-alternative, spring-boot
```

## Resmi Dağıtım Kanalı

`v0.4.1` için seçilen resmi dağıtım kanalı GitHub Release asset'idir:

```text
cache-database-0.4.1-github-release.zip
```

Paket; Maven modül jar'larını, source jar'larını, javadoc dosyalarını, README'yi,
güvenlik/topluluk dosyalarını, İngilizce dokümanları ve Türkçe dokümanları
içerir. Bu release için Maven Central zorunlu değildir; çünkü seçilen resmi
paket dağıtım kanalı GitHub Release'tir.

## Release Konumlandırması

`cache-database v0.4.1`

CacheDB `v0.4.1`, uygulama tarafındaki kullanımı deklaratif hale getiren stable
framework release'idir. Uygulama tek bir generated domain scope kullanır,
entity bazlı aktif veri politikalarını YAML içinde tanımlar ve query,
projection, relation ile warm işlemlerini manuel repository factory yazmadan
çalıştırır. Redis-first model, sınırlı aktif veri setleri, kalıcı SQL
write-behind ve açık route sözleşmeleri korunur.

Bu release, her uygulamanın kendi production trafiğini ek doğrulama olmadan
CacheDB'ye kesebileceği anlamına gelmez. Cutover öncesinde her uygulama için
route envanteri, warm-up, side-by-side comparison, Redis bellek bütçesi,
rollback planı ve ortama özel HA kanıtı gerekir.

MSSQL, canlı SQL Server evidence hattı olan açıkça seçilen bir provider'dır.
Restart/reconnect kontrolü, concurrency ve lock-classification kapsamı,
outbox/checkpoint desteği ve migration planner coverage vardır. Bu yine de her
SQL Server HA veya Always On topolojisinin otomatik sertifikalı olduğu anlamına
gelmez; bu topolojiler tüketen uygulamanın staging ortamında ayrıca
kanıtlanmalıdır.

## Release Note Şablonu

```markdown
## cache-database v0.4.1

Bu stable release, mevcut SQL kullanan uygulamalar için geçiş yolunu daha uygulanabilir hale getirir.

### Stable olan alanlar

- Sınırlı hot-set policy'leriyle Redis-first entity repository'leri.
- Tip güvenli CRUD, query, relation, projection ve warm işlemleri için tek compile-time generated domain scope.
- Entity bazlı deklaratif policy yapılandırması ve açık JDBC registration seçimi.
- PostgreSQL ve açıkça seçilen MSSQL kalıcı SQL provider yolları.
- İki aşamalı generated JDBC source ve relation-loader registration.
- Route'a göre şekillenen query loader'lar üzerinden kontrollü read-through ve warm/backfill.
- İlişki yoğun ve global sıralı route'lar için projection/read-model reçeteleri.
- Şema keşfi, warm-up, comparison ve rapor üretimi için Migration Planner akışı.
- Çok pod coordination, leader lease ve lokal Docker HA preflight evidence.
- Docker Compose, Postman koleksiyonu ve yerel hot-route load script'leri olan PostgreSQL ve MSSQL REST örnekleri.
- Resmi paket dağıtım kanalı olarak GitHub Release asset'i.

### Provider sınırları

- PostgreSQL varsayılan provider yoludur.
- MSSQL, SQL Server sample ve integration kanıtı olan açıkça seçilen provider olarak kullanılabilir.
- SQL Server HA veya Always On hazırlığı, production iddiasının parçasıysa tüketen uygulamanın staging topolojisinde ayrıca kanıtlanmalıdır.
- Maven Central bu release için opsiyoneldir; seçilen resmi dağıtım kanalı GitHub Release'tir.

### Production kullanımı

Bu release'i production odaklı pilotlar ve kontrollü cutover'lar için kullan.
Ancak her kritik route için route contract, warm-up evidence, side-by-side
comparison, Redis bellek bütçesi ve rollback planı oluşmadan cutover yapma.
```

## Yayın Kontrol Listesi

- `pom.xml` ve tüm modül parent versiyonları stable sürümü kullanıyor.
- Release note `docs/releases/v0.4.1.md` altında var.
- `mvn -DskipTests package` geçiyor.
- Public API compatibility kontrolü geçiyor.
- Türkçe dokümantasyon kalite kontrolü geçiyor.
- Lokal Docker HA preflight geçiyor veya son CI evidence yeşil.
- `Public Beta Readiness` ve `Production Evidence` release commit'i için yeşil.
- `Production GA Release Readiness`, `v0.4.1` için yeşil.
- GitHub Release prerelease olarak işaretli değil.
- GitHub Release asset'i `cache-database-0.4.1-github-release.zip` olarak eklendi.
