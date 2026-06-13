# CacheDB

English version: [../README.md](../README.md)

CacheDB, Redis'i sıcak okuma/yazma yolu olarak kullanan, PostgreSQL'i ise
kalıcı doğruluk kaynağı olarak koruyan bir Java persistence kütüphanesidir.
Amaç, ORM'e benzeyen geliştirme ergonomisini korurken sıcak veri yolunu açık,
sınırlı, ölçülebilir ve production ortamında yönetilebilir hale getirmektir.

CacheDB şu iddiayla konumlanır:

- verinin tamamını Redis'e taşımak gerekmez
- sıcak olan veri ve route açıkça tanımlanmalıdır
- PostgreSQL kalıcı geçmişin sahibi olmaya devam etmelidir
- ilişki yoğun ve global sıralı ekranlarda projection/read-model tasarımın
  parçasıdır
- çalışma zamanı reflection'ı yerine derleme zamanında üretilen metadata
  kullanılmalıdır

Kısa karar: CacheDB bugün güçlü bir açık beta seviyesindedir. Kontrollü pilot,
staging karşılaştırması ve route bazlı cutover için uygundur; hâlâ koşulsuz GA
olarak duyurulmamalıdır.

## İlk Bakışta Ne İşe Yarar?

CacheDB özellikle şu problemlere odaklanır:

| Problem | CacheDB yaklaşımı |
| --- | --- |
| Çok okunan tek entity'lerde düşük gecikme | Redis öncelikli entity repository |
| Yazmanın kalıcı olması | PostgreSQL'e write-behind flush |
| İlişki sayısı zamanla büyüyen ekranlar | Relation limit, projection ve summary-first okuma |
| Global top-N, dashboard ve sıralı iş ekranları | Ranked projection ve route contract |
| Mevcut PostgreSQL + ORM sisteminden geçiş | Migration Planner, warm, dry-run ve side-by-side comparison |
| Redis'in sınırsız büyümesi | Hot policy, tenant quota, payload budget ve admission telemetry |
| Çok pod'lu Kubernetes çalışma modeli | Pod-unique consumer adı, Redis leader lease ve coordination evidence |

## Bu Repo'da Nereden Başlamalısın?

| Aradığın cevap | Oku |
| --- | --- |
| "Tüm doküman haritası nerede?" | [Doküman Haritası](DOKUMAN_HARITASI.md) |
| "CacheDB bana uygun mu?" | [ORM Alternatifi Rehberi](docs/orm-alternative.md) |
| "Sıfırdan nasıl çalıştırırım?" | [Başlangıç Rehberi](docs/getting-started.md) |
| "Spring Boot projemde hangi dependency gerekir?" | [Spring Boot Starter](docs/spring-boot-starter.md) |
| "Entity, relation, projection ve route contract ne demek?" | [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md) |
| "Gerçek hayatta nasıl modellemeliyim?" | [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md) |
| "Redis memory ve performansı nasıl ayarlamalıyım?" | [Production Tuning Rehberi](docs/production-tuning-rehberi.md) |
| "Tüm property'ler ve varsayılanlar nerede?" | [Tuning Parametreleri](docs/tuning-parameters.md) |
| "Mevcut PostgreSQL sistemimi nasıl taşırım?" | [Geçiş Planlayıcı](docs/migration-planner.md) |
| "Production'a çıkmadan önce ne kanıtlamalıyım?" | [Production Reçeteleri](docs/production-recipes.md) |
| "GA için hâlâ eksik olan kapılar neler?" | [Production GA Criteria](../PRODUCTION_GA_CRITERIA.md) |

## Doğru Başlangıç Yolunu Seç

| Durum | Önerilen yol | Neden |
| --- | --- | --- |
| Yeni Spring Boot servisi | `cachedb-spring-boot-starter` | En az kurulum, aynı porttan admin UI, Spring `DataSource` entegrasyonu |
| Zaten JPA kullanan Spring Boot uygulaması | Starter + mevcut `DataSource` | JPA zaten `DataSource` oluşturuyorsa JDBC starter tekrar eklenmez |
| Plain Java servisi | `cachedb-starter` | Başlatma, kapatma ve bağlantı yaşam döngüsü sende kalır |
| Mevcut PostgreSQL + ORM sistemi | Migration Planner | Şema keşfi, warm planı, compare ve cutover raporu üretir |
| Çok ilişkili liste ekranı | Projection/read-model | İlk ekranda bütün object graph yüklenmez |
| Worker, replay, repair veya batch job | Doğrudan repository | Daha az soyutlama, daha açık performans davranışı |

BEST: Önce tek sıcak route seç, Redis hot-set kararını ver, staging ortamında
warm ve compare çalıştır, sonra production cutover kararı al.

ANTI-PATTERN: Tüm veritabanını entity olarak işaretleyip Redis'in her şeyi
otomatik hızlandırmasını beklemek.

## 5 Dakikada Spring Boot Kurulumu

`cachedb.version` değerini kullandığın release ile aynı tut.

```xml
<properties>
    <cachedb.version>0.1.0-beta.3</cachedb.version>
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

JDBC kuralı:

- Uygulamada henüz `DataSource` yoksa `spring-boot-starter-jdbc` ekle.
- Uygulamada `spring-boot-starter-data-jpa` veya başka bir starter zaten
  `DataSource` oluşturuyorsa JDBC starter'ı tekrar ekleme.
- CacheDB için gereken şey çalışan bir Spring `DataSource` bean'idir.
- `cachedb-annotations` ve annotation processor olarak `cachedb-processor`
  her durumda gereklidir.

Minimal `application.yml`:

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
  admin:
    http-enabled: true
```

Admin UI:

- yönetim paneli: `/cachedb-admin`
- geçiş planlayıcı: `/cachedb-admin/migration-planner`
- sağlık API'si: `/cachedb-admin/api/health`

Production kuralı: `/cachedb-admin/**` doğrudan internete açılmamalıdır.
Gateway veya reverse proxy arkasına alınmalı; gateway auth ya da CacheDB token
auth kullanılmalıdır.

## İlk Entity

CacheDB entity'leri açık alan metadata'sı ile çalışır. Yeni kullanıcı için en
önemli kural: persisted field'lar `private` veya `final` olmamalıdır.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("status")
    public String status;

    public CustomerEntity() {
    }
}
```

Compile sonrasında annotation processor binding sınıflarını üretir. Bu yüzden
runtime'da entity field'larını reflection ile keşfetme maliyeti hedeflenmez.

## İlk Okuma ve Yazma

Normal servis kodunda önce generated yüzeyden başla:

```java
var domain = GeneratedCacheModule.using(session);

CustomerEntity customer = new CustomerEntity();
customer.customerId = 42L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

domain.customers().save(customer);

CustomerEntity loaded = domain.customers()
        .findById(42L)
        .orElseThrow();
```

Davranış:

- `save` sıcak entity'yi Redis'e yazar.
- Kalıcı yazım PostgreSQL write-behind hattına girer.
- `findById` önce Redis'teki hot entity'yi okur.
- Entity hot policy'ye uymuyorsa Redis'e kabul edilmeyebilir veya Redis'ten
  düşürülebilir.

## Relation Nasıl Düşünülmeli?

CacheDB relation'ı Hibernate lazy loading gibi görünmez çalışan bir mekanizma
değildir. Relation yükleme açıkça istenir.

```java
@CacheEntity(
        table = "customers",
        redisNamespace = "customers",
        relationLoader = CustomerOrdersRelationBatchLoader.class
)
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheRelation(
            targetEntity = "OrderEntity",
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderEntity> orders;
}
```

Okuma:

```java
CustomerEntity customer = customerRepository
        .withRelationLimit("orders", 20)
        .findById(customerId)
        .orElseThrow();
```

BEST: Detay ekranında küçük önizleme gerekiyorsa `withRelationLimit(...)` kullan.

ANTI-PATTERN: Liste ekranında her müşteri için bütün sipariş geçmişini relation
olarak yüklemek.

## Projection Ne Zaman Şart?

Projection, entity'nin tamamını değil, ekranın ihtiyaç duyduğu küçük ve kararlı
okuma modelini Redis'te tutar.

Projection kullanman gereken durumlar:

- müşteri başına son 1.000 sipariş gibi büyüyen liste ekranları
- dashboard top-N kartları
- global iş önceliği sıralamaları
- sadece özet alanlarla çizilen ilk ekranlar
- detay açılmadan tam entity yüklenmemesi gereken akışlar

Örnek karar:

| Ekran | Kullanılacak model |
| --- | --- |
| Müşteri kartı | `CustomerEntity` |
| Müşteri son 10 sipariş listesi | `CustomerOrderSummaryProjection` |
| Sipariş detay | `OrderEntity` |
| Sipariş satırı önizleme | `withRelationLimit("orderLines", 8)` |
| Global en yüksek riskli siparişler | Ranked projection |

## Redis Belleği Nasıl Kontrol Edilir?

CacheDB tasarımı "TTL koy, Redis büyümesin" seviyesinde kalmamalıdır. Gerçek
production modelinde dört katman birlikte kullanılır:

- entity hot policy: hangi satır Redis'e girebilir?
- route contract: hangi endpoint kaç satır okuyabilir?
- tenant quota: tek müşteri veya tenant belleği tüketebilir mi?
- Redis `maxmemory` ve eviction policy: altyapı sınırı nedir?

Örnek hot policy kararı:

| İhtiyaç | Yaklaşım |
| --- | --- |
| Son 100.000 kayıt hot olsun | `COUNT_WINDOW` |
| Son 90 günlük sipariş hot olsun | `TIME_WINDOW` ve `order_date` |
| Sadece `OPEN/PENDING` işler hot olsun | `STATE_WINDOW` |
| Son 90 gün + açık durum + tenant kotası | `COMPOSITE` + tenant quota |

Detaylı ayar için [Production Tuning Rehberi](docs/production-tuning-rehberi.md)
ve [Tuning Parametreleri](docs/tuning-parameters.md) sayfalarını birlikte oku.

## Mevcut PostgreSQL + ORM Sisteminden Geçiş

Migration Planner'ın amacı tek düğmeyle production cutover yapmak değildir.
Amaç, her production route için şu soruları kanıtlamaktır:

- Bu route entity mi, projection mı, ranked projection mı olmalı?
- Redis'e hangi hot window alınacak?
- PostgreSQL tam geçmişte hangi rolü koruyacak?
- Warm işlemi ne kadar veri okuyacak?
- CacheDB sonucu kaynak veritabanı baseline'ı ile aynı mı?
- Gecikme ve p95 değeri cutover için yeterli mi?
- Rollback planı nedir?

Önerilen sıra:

1. Admin UI'da `/cachedb-admin/migration-planner` ekranını aç.
2. Kaynak veritabanı şemasını keşfet.
3. Route adaylarından birini seç ve forma uygula.
4. Planı oluştur.
5. Scaffold üret.
6. Dry-run warm çalıştır; Redis değişmemelidir.
7. Staging warm çalıştır; Redis hot set dolmalıdır.
8. Side-by-side comparison çalıştır.
9. Raporu indir.
10. Aynı işlemi her production ekranı/API/batch/report route'u için tekrarla.

%100 dönüşüm coverage, tek seçilen tabloyla değil route envanteriyle sağlanır.

## Production'a Yakın Kullanım İçin Kısa Kontrol Listesi

- Redis HA/failover planı var mı?
- PostgreSQL kalıcı doğruluk kaynağı olarak korunuyor mu?
- Dış sistemler PostgreSQL'i değiştiriyorsa outbox/CDC var mı?
- Her sıcak route için route contract yazıldı mı?
- Projection gereken route production strict mode'da entity scan'e düşüyor mu?
- Hot policy ve tenant quota bellek bütçesini koruyor mu?
- Warm işlemi checkpoint/resume destekli mi?
- Side-by-side comparison veri sırası ve üyelik eşleşmesini kanıtladı mı?
- Admin UI yalnızca güvenli operasyon ağı veya gateway arkasında mı?
- Benchmark threshold ve public API compatibility CI'da çalışıyor mu?

## Hızlı Karşılaştırma

| Konu | CacheDB | Geleneksel ORM |
| --- | --- | --- |
| Birincil sıcak okuma yolu | Redis | Veritabanı |
| Kalıcı veri kaynağı | PostgreSQL | Veritabanı |
| Metadata | Derleme zamanında üretilir | Genelde runtime metadata/reflection |
| Relation davranışı | Açık `FetchPlan`, loader ve projection | Çoğu zaman lazy/eager graph |
| Büyük liste ekranı | Projection/read-model | Sıklıkla entity graph veya join |
| En iyi kullanım alanı | Düşük gecikmeli sıcak route'lar | SQL merkezli geniş ilişkisel işlemler |
| Ana risk | Yanlış hot-set ve projection tasarımı | N+1, geniş join ve runtime ORM maliyeti |

## Ölçülmüş Kanıt Nasıl Okunmalı?

Benchmark sonuçları "her zaman CacheDB daha hızlıdır" diye okunmamalıdır.
Doğru okuma şudur:

- generated binding yüzeyi düşük ek yük bandında kalabilir
- minimal repository kritik sıcak yollarda daha fazla kontrol verir
- gerçek production maliyeti çoğu zaman query shape, relation hydration, Redis
  contention ve write-behind baskısından gelir
- relation-heavy ekranlarda ölçümden önce projection tasarımı yapılmalıdır

Raporu yeniden üretmek için:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

## Sonraki Okuma

- [Başlangıç Rehberi](docs/getting-started.md)
- [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)
- [Geçiş Planlayıcı](docs/migration-planner.md)
- [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md)
- [Production Tuning Rehberi](docs/production-tuning-rehberi.md)
- [Tuning Parametreleri](docs/tuning-parameters.md)
- [Production Reçeteleri](docs/production-recipes.md)
- [Production Testleri](cachedb-production-tests/README.md)
- [Örnekler](cachedb-examples/README.md)
- [Mimari](docs/architecture.md)
- [Açık Beta Hazırlık Durumu](docs/public-beta-readiness.md)
- [Release Checklist](docs/release-checklist.md)

## Topluluk ve Proje Dosyaları

- [License](../LICENSE)
- [Contributing](../CONTRIBUTING.md)
- [Security Policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Support](../SUPPORT.md)
- [Changelog](../CHANGELOG.md)
