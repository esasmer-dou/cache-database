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

`v0.10.0` için resmi dağıtım kanalları, kimlik doğrulaması istemeyen CacheDB
Maven deposu ile GitHub Release paketidir. GitHub Packages, isteğe bağlı kimlik
doğrulamalı ayna olarak kalır:

```text
cache-database-0.10.0-github-release.zip
```

Paket; 16 public modül ile CacheDB BOM için binary, source, javadoc ve POM
artefact'larını, README'yi, güvenlik/topluluk dosyalarını, İngilizce ve Türkçe
dokümanları içerir. Maven Central zorunlu değildir; anonim Maven2 erişimi ve
GitHub Release paketi resmi dağıtım kanallarıdır.

## Release Konumlandırması

`cache-database v0.10.0`

CacheDB `v0.10.0`; derlenebilir migration projection'ları, toplu SQL Server
write-behind yolu, commit'e bağlı uygulama sertifikası ve anonim Maven erişimi
ekler. Açık production sözleşmeleri korunur. PostgreSQL ile SQL Server örnekleri
aynı uygulama modelini ve provider'a özgü çalışma yolunu gösterir.

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
## cache-database v0.10.0

Bu stable release, mevcut SQL kullanan uygulamalar için geçiş yolunu daha uygulanabilir hale getirir.

### Stable olan alanlar

- Route, kapsam ve sıralama sözleşmesine bağlı keyset cursor ile tipli
  `CursorPage<T>` cevapları.
- Derleme zamanında çözülen repository varsayılanları, route yetenekleri, route
  catalog ve sınırlı operasyon envanteri.
- Tipli warm çalıştırma, dağıtık iş tanımı, yapısal ilerleme bilgisi ve
  dry-run/apply/coverage test kanıtı.
- Receipt beklemesini sınırlayan framework seviyesinde kalıcı batch yazımı.
- Kesin query, lookup, pencere ve warm rolleri için derleme zamanı çıkarımı.
- Warm, coverage ve entegrasyon testleri için generated tipli route referansları.
- Strict coverage kapsam doğrulaması ve toplu HOT route kapasite kanıtı.
- Açık timeout isteyen tekil komut SQL kalıcılık yardımcıları.
- Sınırlı hot-set policy'leriyle Redis-first entity repository'leri.
- Tip güvenli komut, kritik/kaynak route'u, ilişki, projection ve warm planı için derleme zamanında üretilen `@CacheRepository` implementasyonları.
- Entity bazlı deklaratif policy yapılandırması ve açık JDBC registration seçimi.
- Tam olarak bir provider starter ile seçilen PostgreSQL ve SQL Server kalıcı provider yolları.
- İki aşamalı generated JDBC source ve relation-loader registration.
- Açık ve sınırlı kaynak route'ları ile route'tan türeyen warm/backfill; Redis miss arkasında gizli SQL fallback yoktur.
- İlişki yoğun ve global sıralı route'lar için projection/read-model reçeteleri.
- Şema keşfi, warm-up, comparison ve rapor üretimi için Migration Planner akışı.
- Çok pod coordination, leader lease ve lokal Docker HA preflight evidence.
- Redis lease, heartbeat, sınırlı bekleme ve cluster genelinde tekrar önleme kullanan deklaratif periyodik warm planları.
- SQL'i değiştirmeden eski, eksik veya bozuk cache payload'larını kaldıran artımlı policy reconciliation.
- Üretilen sınırlı relation loader'lar, parent bazlı sıralı indeksler, projection record'ları ve strict route sözleşmeleri.
- İyimser yazma receipt'leri, kalıcı parent bağımlılıkları ve açık SQL kalıcılık takibi.
- Pod kaybında işi devralma, terk edilmiş işi sahiplenme ve sınırlı retry sağlayan tip güvenli Redis Stream işleri.
- Redis, SQL, write-behind backlog, dead-letter ve recovery durumu için Spring Boot Actuator health sinyali.
- Docker Compose, Postman koleksiyonu ve yerel hot-route load script'leri olan PostgreSQL ve MSSQL REST örnekleri.
- Resmi paket dağıtım kanalları olarak anonim Maven2 deposu ve GitHub Release paketi.

### Provider sınırları

- PostgreSQL varsayılan provider yoludur.
- MSSQL, SQL Server sample ve integration kanıtı olan açıkça seçilen provider olarak kullanılabilir.
- SQL Server HA veya Always On hazırlığı, production iddiasının parçasıysa tüketen uygulamanın staging topolojisinde ayrıca kanıtlanmalıdır.
- Maven Central isteğe bağlıdır; anonim Maven2 deposu ve GitHub Release resmi dağıtım kanallarıdır.

### Production kullanımı

Bu release'i production odaklı pilotlar ve kontrollü cutover'lar için kullan.
Ancak her kritik route için route contract, warm-up evidence, side-by-side
comparison, Redis bellek bütçesi ve rollback planı oluşmadan cutover yapma.
```

## Yayın Kontrol Listesi

- `pom.xml` ve tüm modül parent versiyonları stable sürümü kullanıyor.
- Release note `docs/releases/v0.10.0.md` altında var.
- `mvn -DskipTests package` geçiyor.
- Public API compatibility kontrolü geçiyor.
- Türkçe dokümantasyon kalite kontrolü geçiyor.
- Lokal Docker HA preflight geçiyor veya son CI evidence yeşil.
- `Framework Readiness` ve `Production Evidence` release commit'i için yeşil.
- `Production GA Release Readiness`, `v0.10.0` için yeşil.
- GitHub Release prerelease olarak işaretli değil.
- `0.10.0` için anonim Maven çözümleme kontrolü geçiyor.
- GitHub Release asset'i `cache-database-0.10.0-github-release.zip` olarak eklendi.
