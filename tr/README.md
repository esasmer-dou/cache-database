# cache-database

English version: [../README.md](../README.md)

Bu dosya, projenin kullanıcı odaklı Türkçe giriş dokümanıdır.

`cache-database`, düşük runtime overhead isteyen ama ORM benzeri bir geliştirici
deneyiminden de vazgeçmek istemeyen Java ekipleri için tasarlanmış Redis-first
bir persistence kütüphanesidir.

Temel kural nettir: sıcak okuma/yazma yolunu açık, sınırlı ve ölçülebilir tut.

## Ne Sağlar

- Sıcak uygulama route'ları için Redis-first okuma ve yazma.
- Async write-behind ile PostgreSQL kalıcılığı.
- Runtime reflection yerine compile-time generated metadata.
- Normal serviş kodu için ORM benzeri generated API'ler.
- Pahalı ekranlar için explicit relation loading, projection ve read-model yaklaşımı.
- Profiling ile kanıtlanmış hotspot'larda daha alt repository yüzeyine inme imkanı.
- Spring Boot ile aynı porttan çalışan admin UI, migration planner, warm-up ve compare akışları.

## Mevcut Durum

CacheDB şu aşamada public beta değerlendirmesi ve staging pilotları için uygundur.
Henüz "hiçbir koşul olmadan GA" diye konumlandırılmamalıdır.

Production'a yakın kullanımlarda şu disiplinler zorunlu kabul edilmelidir:

- Redis gerçek bir production bağımlılığı olarak tasarlanmalıdır.
- Relation-ağır liste ekranları projection/read-model ile modellenmelidir.
- Global sorted veya ranked business ekranları ranked projection kullanmalıdır.
- Mevcut ORM'den geçiş, önce warm-up ve side-by-side comparison ile staging'de kanıtlanmalıdır.

## Hangi Yoldan Başlamalısın?

| Durum | Başlangıç | Neden |
| --- | --- | --- |
| Yeni Spring Boot servisi | `cachedb-spring-boot-starter` | En hızlı kurulum, aynı port admin UI, production varsayılanları |
| Plain Java servisi | `cachedb-starter` | Spring Boot olmadan tam bootstrap kontrolü |
| Mevcut PostgreSQL + ORM uygulaması | Admin UI Migration Planner | Şemayı keşfeder, sıcak route seçtirir, Redis'i warm eder, sonuçları karşılaştırır |
| Relation-ağır liste ekranı | Projection/read-model | İlk ekranda full aggregate hydrate etmeyi engeller |
| Kanıtlanmış tek latency hotspot'u | `*CacheBinding` veya doğrudan repository | Sadece ölçülen yerde daha düşük seviye kontrol verir |
| Worker/replay/operasyon kodu | Doğrudan repository | Daha açık, tahmin edilebilir ve düşük allocation'lı yol |

## 5 Dakikada Kurulum

### Maven: Spring Boot

Spring Boot kullanıyorsan ve uygulamada bir Spring `DataSource` varsa bu yolu
kullan.

```xml
<properties>
    <cachedb.version>0.1.0-beta.2</cachedb.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.cachedb</groupId>
                        <artifactId>cachedb-processor</artifactId>
                        <version>${cachedb.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Uygulaman zaten `spring-boot-starter-data-jpa` veya benzeri bir starter ile
`DataSource` oluşturuyorsa, sadece CacheDB için ayrıca
`spring-boot-starter-jdbc` ekleme. CacheDB'nin ihtiyacı olan şey mevcut bir
`DataSource` bean'idir; aynı JDBC auto-configuration'ı iki kez açmak gerekmez.

### Maven: Plain Java

`CacheDatabase` nesnesini kendin bootstrap etmek istiyorsan bu yolu kullan.

```xml
<properties>
    <cachedb.version>0.1.0-beta.2</cachedb.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
        <version>5.2.0</version>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.cachedb</groupId>
                        <artifactId>cachedb-processor</artifactId>
                        <version>${cachedb.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Minimal Spring Boot Ayarı

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/app
    username: app
    password: app

cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
```

Uygulama açıldıktan sonra admin UI aynı uygulama portundan gelir:

- dashboard: `/cachedb-admin`
- migration planner: `/cachedb-admin/migration-planner`
- health API: `/cachedb-admin/api/health`

## İlk Gün Uygulama Akışı

1. Maven bağımlılıklarını ekle.
2. PostgreSQL ve Redis bağlantılarını tanımla.
3. İlk sıcak entity üzerinde `@CacheEntity` kullan.
4. Annotation processor'ın binding sınıflarını üretmesine izin ver.
5. Uygulama kodunda `GeneratedCacheModule.using(session)...` ile başla.
6. Full aggregate yüklememesi gereken liste ekranları için projection ekle.
7. "Son 8 sipariş satırı" gibi preview alanlarında `withRelationLimit(...)` kullan.
8. Sadece ölçülmüş hotspot'ları daha alt repository seviyesine indir.

## Yaygın Kullanım Senaryoları

### Senaryo 1: Bir Müşterinin Çok Fazla Siparişi Var

Kullanıcı müşteri detayını açtığında son 1.000 siparişi tarih sırasına göre
görmek istiyorsa, tüm customer aggregate'ini bütün order line'larla birlikte
yükleme.

Bu şekli kullan:

- `CustomerEntity` root entity olarak kalır.
- `OrderEntity` PostgreSQL'de tam tarihçe olarak durur; Redis'te yalnızca gerekli sıcak pencere tutulur.
- Customer-order summary projection ekranın ihtiyaç duyduğu liste alanlarını taşır.
- Liste sorgusu `order_date DESC, order_id DESC` ile sıralanır.
- Full order detail yalnızca kullanıcı tek bir siparişi açtığında yüklenir.

Sonuç: müşteri başına sipariş sayısı artsa bile ilk ekran sınırlı kalır.

### Senaryo 2: Dashboard En Önemli Business Satırlarını Gösteriyor

Ekran global olarak business priority, revenue, risk veya benzeri bir skora göre
sıralanıyorsa ranked projection kullan.

Bu şekli kullan:

- projection üzerinde kararlı bir `rank_score` veya eşdeğer alan üret
- sorguyu bu ranking alanı üzerinden indexle
- ilk sayfa veya sıcak pencereyi Redis'te tut
- geniş entity payload'larını çekip sonra sıralama yapmaktan kaçın

### Senaryo 3: Mevcut ORM Route'unu Taşıyorsun

PostgreSQL ve farklı bir ORM ile çalışan bir uygulamayı doğrudan taşımaya
çalışma. Önce route'u kanıtla.

Migration Planner akışı:

1. `/cachedb-admin/migration-planner` ekranını aç.
2. PostgreSQL şemasını keşfet.
3. Önerilen root/child route'lardan birini seç.
4. Önerilen entity/projection scaffold'unu üret.
5. Redis'i değiştirmeden dry-run warm çalıştır ve SQL'i incele.
6. Staging warm ile Redis sıcak setini doldur.
7. Side-by-side comparison çalıştır.
8. Veri eşleşmesi, sıralama ve latency kabul edilebilir değilse cutover yapma.

Tam sistem dönüşümünde bu işi route bazında tekrarla. Her production ekranı ve
API şu sınıflardan birine girmeli:

- generated CRUD route
- projection/read-model route
- ranked projection route
- direct repository/worker route
- bilinçli olarak PostgreSQL cold path'te bırakılan route

## Neden CacheDB?

Şu durumlarda CacheDB mantıklı bir seçimdir:

- düşük gecikmeli okuma önemliyse
- Redis zaten gerçek bir production bağımlılığıysa
- read-model şekli üzerinde açık kontrol istiyorsan
- relation fan-out zaman içinde büyüyebiliyorsa
- runtime reflection istemiyor ama generated ergonomi istiyorsan
- PostgreSQL/ORM route'larını aşamalı şekilde Redis-first sıcak yola taşımak istiyorsan

## Neden CacheDB Değil?

Şu durumlarda geleneksel JPA/Hibernate benzeri bir stack daha uygun olabilir:

- uygulamanın ana yükü SQL join ve raporlama ise
- implicit ORM davranışı ürün beklentisinin parçasıysa
- ekip projection/read-model tasarımını sahiplenmek istemiyorsa
- Redis production runtime planının parçası değilse

Bu ayrım bilinçlidir. CacheDB, persistence davranışını gizlemekten çok explicit
kontrol, sınırlı sıcak yol ve tahmin edilebilir runtime overhead için optimize
edilir.

## Hızlı Karşılaştırma

| Konu | CacheDB | Geleneksel ORM |
| --- | --- | --- |
| Birincil okuma yolu | Redis-first | Database-first |
| Kalıcılık | Write-behind ile PostgreSQL | Database transaction yolu |
| Metadata | Compile-time generated | Genelde runtime reflection ve ORM metadata |
| Relation modeli | Explicit fetch plan, loader, projection | Çoğu zaman implicit lazy/eager davranış |
| Sıcak liste ekranları | Önce projection/read-model | Çoğu zaman önce entity graph |
| En iyi uyum | Düşük gecikmeli servisler, Redis merkezli sistemler | SQL merkezli ilişkisel uygulamalar |

## Ölçülmüş Kanıt

Buradaki iddia ergonominin bedava olduğu değil. Daha pratik iddia şudur:
generated ergonomi, minimal repository yolu ile aynı düşük-overhead bandında
kalabilir; production maliyetinin büyük kısmı ise genelde query şekli, relation
hydration, Redis contention ve write-behind baskısından gelir.

Son yerel recipe benchmark özeti:

| Yüzey | Avg ns | p95 ns | Nasıl okunmalı |
| --- | ---: | ---: | --- |
| Generated entity binding | 6005 | 13400 | Bu yerel koşuda ortalamada en hızlı yüzey |
| JPA-style domain module | 8059 | 20300 | Gruplanmış ergonomik yüzey, makul wrapper maliyeti |
| Minimal repository | 15075 | 9600 | Bu yerel koşuda en düşük p95 |

![Repository recipe benchmark](../docs/assets/repository-recipe-benchmark.svg)

Raporu yeniden üretmek için:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

## Production Recipe Merdiveni

![Production recipe ladder](../docs/assets/production-recipe-ladder.svg)

Pratik kural:

1. `GeneratedCacheModule.using(session)...` ile başla.
2. Sıcak endpoint'leri yalnızca ölçümden sonra `*CacheBinding.using(session)...` tarafına indir.
3. Kanıtlanmış hotspot, replay veya worker kodunu doğrudan repository seviyesine çek.
4. Relation-ağır ve global sorted ekranlarda projection/read-model kullan.

## Sonraki Okuma

- [Getting Started](docs/getting-started.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)
- [Geçiş Planlayıcı](docs/migration-planner.md)
- [Production Recipes](docs/production-recipes.md)
- [ORM Alternatifi Rehberi](docs/orm-alternative.md)
- [Tuning Parameters](docs/tuning-parameters.md)
- [Production Tests](cachedb-production-tests/README.md)
- [Examples](cachedb-examples/README.md)
- [Architecture](docs/architecture.md)
- [Public Beta Readiness](docs/public-beta-readiness.md)
- [Release Checklist](docs/release-checklist.md)

## Topluluk

- [License](../LICENSE)
- [Contributing](../CONTRIBUTING.md)
- [Security Policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Support](../SUPPORT.md)
- [Changelog](../CHANGELOG.md)

