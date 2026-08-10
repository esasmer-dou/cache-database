# CacheDB

English version: [../README.md](../README.md)

CacheDB, Redis'i düşük gecikmeli operasyonel veri katmanı olarak kullanan ve
kalıcı doğruluk kaynağını seçilen SQL veritabanında tutan bir Java data-layer
framework'üdür. PostgreSQL ve SQL Server, ayrı starter'ları ve provider'a özel
kanıt hatları olan eşit seviyedeki açık sağlayıcılardır. Amaç, ORM'e benzeyen
geliştirme ergonomisini korurken okuma, yazma, ön yükleme ve arşiv davranışını
çalışma zamanı sihrinin arkasına saklamamaktır.

CacheDB şu iddiayla konumlanır:

- verinin tamamını Redis'e taşımak gerekmez
- sık erişilen veri ve kritik route açıkça tanımlanmalıdır
- seçilen SQL provider kalıcı geçmişin sahibi olmaya devam etmelidir
- ilişki yoğun ve global sıralı ekranlarda projection/read-model tasarımın
  parçasıdır
- çalışma zamanı reflection'ı yerine derleme zamanında üretilen metadata
  kullanılmalıdır

İki provider da aynı CacheDB uygulama modelini destekler: generated repository,
sınırlı aktif yollar, projection, warm/backfill, write-behind, outbox
entegrasyonu ve açık source route'lar. Bağlantı, lock, timeout, indeks ve HA
davranışı veritabanına özgüdür; uygulamanın kendi staging topolojisinde ayrıca
kanıtlanmalıdır.

| Güncel hat | Değer |
| --- | --- |
| Yayımlanmış son sürüm | `v0.7.0` |
| Repo sürümü | `0.7.0` |
| Kütüphane bytecode seviyesi | Java 17 |
| Çalıştırılabilir örnekler | Java 21 |
| Yerel kanıt topolojisi | Redis 8.2.1, PostgreSQL 16, SQL Server 2022 |
| Uygulama API'si | Derleme sırasında üretilen `@CacheRepository` interface'leri |

## Ürün Konumlandırması: CacheDB Nedir, Ne Değildir?

CacheDB, uygulama ile SQL veritabanı arasına konup Redis'te bulamadığı her
kaydı otomatik olarak veritabanından çeken şeffaf bir cache katmanı değildir.
Redis'te kayıt yoksa CacheDB'nin her sorgu şekli için SQL'i tarayıp sonucu
Redis'e doldurması beklenmemelidir.

CacheDB, her dinamik sorguyu karşılayan bire bir Hibernate/JPA alternatifi de
değildir. CacheDB'nin doğru konumu; sınırları belirlenmiş operasyonel okuma ve
yazma yolları için Redis-first çalışan aktif veri seti, projection ve
read-model katmanıdır.

| Net ifade | Çalışma zamanı anlamı |
| --- | --- |
| Redis anlık okuma yoludur | Entity ve projection repository'leri Redis'teki aktif veri setini okur. Kayıt Redis'te bulunmadığında otomatik SQL taraması yapılmaz. |
| SQL kalıcı doğruluk kaynağıdır | PostgreSQL veya MSSQL, write-behind ile kalıcı geçmişi tutar. Arşiv, export, audit ve tam geçmiş okumaları açık SQL yollarıyla tasarlanmalıdır. |
| Hot policy bir sözleşmedir | Kayıt aktif veri politikasının dışındaysa entity veya projection okuması boş dönebilir. Bu veri kaybı değil, beklenen davranıştır. |
| Projection modelin parçasıdır | İlişki yoğun listeler, paneller, zaman çizelgeleri, top-N ve global sıralı ekranlar küçük read-model'ler üzerinden okunmalıdır. |
| Aktif veri seti dışındaki yol açık olmalıdır | Aktif veri seti dışında kalan veri için sınırlı SQL endpoint'i, kayıtlı page loader, warm/backfill job'ı veya migration yolu tasarlanmalıdır. |

| Sınıflandırma | CacheDB'yi bu şekilde konumlandır |
| --- | --- |
| BEST | Yüksek trafikli operasyonel okumalar ve kontrollü write-behind kalıcılık için aktif veri seti ORM/read-model katmanı. |
| ACCEPTABLE | Açık SQL yolları ve route bazlı guardrail'lerle kullanılan Redis-first persistence katmanı. |
| ANTI-PATTERN | Redis'i veritabanının önüne koyup her geniş ORM sorgusunun veriyi Redis'te bulamamasını, SQL'i taramasını, Redis'i doldurmasını ve yine de bellek açısından güvenli kalmasını beklemek. |

Bu bilinçli bir tasarım tercihidir. Bir okuma/yazma yolu production'a çıkmadan
önce şu kararlar verilmelidir: Redis'te hangi veri kalacak, hangi veri yalnızca
SQL'de kalacak, ekranı hangi projection besleyecek ve istenen veri aktif veri
setinin dışındaysa uygulama hangi açık yoldan cevap verecek?

## İlk Bakışta Ne İşe Yarar?

CacheDB özellikle şu problemlere odaklanır:

| Problem | CacheDB yaklaşımı |
| --- | --- |
| Çok okunan tek entity'lerde düşük gecikme | Redis öncelikli entity repository |
| Yazmanın kalıcı olması | SQL write-behind flush |
| İlişki sayısı zamanla büyüyen ekranlar | Relation limit, projection ve summary-first okuma |
| Global top-N, dashboard ve sıralı iş ekranları | Ranked projection ve route contract |
| Mevcut SQL veritabanı + ORM sisteminden geçiş | Migration Planner, warm, dry-run ve side-by-side comparison |
| Redis'in sınırsız büyümesi | Hot policy, tenant quota, payload budget ve admission telemetry |
| Çok pod'lu Kubernetes çalışma modeli | Pod-unique consumer adı, Redis leader lease ve coordination evidence |

## Bu Repo'da Nereden Başlamalısın?

| Aradığın cevap | Oku |
| --- | --- |
| "Tüm doküman haritası nerede?" | [Doküman Haritası](DOKUMAN_HARITASI.md) |
| "CacheDB bana uygun mu?" | [ORM Alternatifi Rehberi](docs/orm-alternative.md) |
| "Sıfırdan nasıl çalıştırırım?" | [Başlangıç Rehberi](docs/getting-started.md) |
| "Çalışan REST API örneği nerede?" | [PostgreSQL Örneği](../sample-cache-database-postgresql/README.tr.md) veya [SQL Server Örneği](../sample-cache-database-mssql/README.tr.md) |
| "Spring Boot projemde hangi dependency gerekir?" | [Spring Boot Starter](docs/spring-boot-starter.md) |
| "Birden fazla pod aktif veri setini düzenli olarak nasıl yeniler ve temizler?" | [Periyodik Warm ve Aktif Veri Seti Uzlaştırması](docs/periodik-warm.md) |
| "Entity, relation, projection ve route contract ne demek?" | [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md) |
| "Gerçek hayatta nasıl modellemeliyim?" | [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md) |
| "Redis memory ve performansı nasıl ayarlamalıyım?" | [Production Tuning Rehberi](docs/production-tuning-rehberi.md) |
| "Tüm property'ler ve varsayılanlar nerede?" | [Tuning Parametreleri](docs/tuning-parameters.md) |
| "Mevcut SQL veritabanı sistemimi nasıl taşırım?" | [Geçiş Planlayıcı](docs/migration-planner.md) |
| "Production'a çıkmadan önce ne kanıtlamalıyım?" | [Production Reçeteleri](docs/production-recipes.md) |
| "GA için hâlâ eksik olan kapılar neler?" | [Production GA Criteria](../PRODUCTION_GA_CRITERIA.md) |
| "GA release çıkabilir mi, nasıl karar verilir?" | [Production GA Release Runbook](docs/production-ga-release-runbook.md) |

## Doğru Başlangıç Yolunu Seç

| Durum | Önerilen yol | Neden |
| --- | --- | --- |
| Önce çalışan bir örnek görmek istiyorsun | [PostgreSQL Örneği](../sample-cache-database-postgresql/README.tr.md) veya [SQL Server Örneği](../sample-cache-database-mssql/README.tr.md) | REST API, Docker Compose, şema, seed verisi ve Postman koleksiyonu hazırdır |
| Yeni Spring Boot servisi | `cachedb-spring-boot-starter-postgres` veya `cachedb-spring-boot-starter-mssql` | Açık provider seçimi ve Spring `DataSource` entegrasyonu |
| Zaten JPA kullanan Spring Boot uygulaması | Starter + mevcut `DataSource` | JPA zaten `DataSource` oluşturuyorsa JDBC starter tekrar eklenmez |
| Plain Java servisi | `cachedb-starter` | Başlatma, kapatma ve bağlantı yaşam döngüsü sende kalır |
| Mevcut SQL veritabanı + ORM sistemi | Migration Planner | Şema keşfi, warm planı, compare ve cutover raporu üretir |
| Çok ilişkili liste ekranı | Projection/read-model | İlk ekranda bütün object graph yüklenmez |
| Worker, replay, repair veya batch job | Doğrudan repository | Daha az soyutlama, daha açık performans davranışı |

BEST: Önce tek kritik route seç, Redis hot-set kararını ver, staging ortamında
warm ve compare çalıştır, sonra production cutover kararı al.

ANTI-PATTERN: Tüm veritabanını entity olarak işaretleyip Redis'in her şeyi
otomatik hızlandırmasını beklemek.

## On Dakikalık Öğrenme Akışı

1. [PostgreSQL örneğini](../sample-cache-database-postgresql/README.tr.md) veya
   [SQL Server örneğini](../sample-cache-database-mssql/README.tr.md) `demo`
   profiliyle çalıştır.
2. Kalıcı demo verisini oluştur ve dağıtık seed işinin tamamlanmasını bekle.
3. SQL source route'u doğrulamak için arşiv endpoint'ini çağır.
4. Yalnızca projection hazırlayan warm işini çalıştır ve route coverage'ı bekle.
5. Aynı Redis aktif yolunu çağır; üyelik ve sıralamayı SQL sonucuyla karşılaştır.
6. Herhangi bir limiti değiştirmeden önce `/api/tuning`, readiness ve yönetim
   ekranını incele.

Bu sıra ürün sözleşmesini, sınırsız CRUD metotlarıyla başlamaktan daha doğru
öğretir.

## 5 Dakikada Spring Boot Kurulumu

`cachedb.version` değerini kullandığın release ile aynı tut. `0.7.0`, GitHub
Packages ve GitHub Release paketi üzerinden dağıtılan değişmez sürümdür.

```xml
<properties>
    <cachedb.version>0.7.0</cachedb.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.reactor.cachedb</groupId>
            <artifactId>cachedb-bom</artifactId>
            <version>${cachedb.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter-postgres</artifactId>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
    </dependency>
    <!-- İsteğe bağlı: yönetim ekranı ve geçiş planlayıcı -->
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter-admin</artifactId>
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

Yayımlanmış artifact'ler GitHub Packages üzerinden sunulur. Şirket parent POM'u
bu repository'yi sağlamıyorsa consumer POM'a şu tanımı ekle:

```xml
<repositories>
    <repository>
        <id>cache-database-github-packages</id>
        <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
    </repository>
</repositories>
```

Repository kimliği Maven server kimliğiyle aynı olmalıdır:

```xml
<settings>
    <servers>
        <server>
            <id>cache-database-github-packages</id>
            <username>${env.GITHUB_ACTOR}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

`read:packages` yetkili token kullan. `0.7.0` değişmez paket olarak
yayımlanmıştır; consumer build'i için CacheDB kaynak reposuna ihtiyaç yoktur.

JDBC kuralı:

| SQL sağlayıcısı | Provider starter | JDBC driver | Çalışan örnek |
| --- | --- | --- | --- |
| PostgreSQL | `cachedb-spring-boot-starter-postgres` | `org.postgresql:postgresql` | [PostgreSQL örneği](../sample-cache-database-postgresql/README.tr.md) |
| SQL Server | `cachedb-spring-boot-starter-mssql` | `com.microsoft.sqlserver:mssql-jdbc` | [SQL Server örneği](../sample-cache-database-mssql/README.tr.md) |

- Uygulamada henüz `DataSource` yoksa `spring-boot-starter-jdbc` ekle.
- Uygulamada `spring-boot-starter-data-jpa` veya başka bir starter zaten
  `DataSource` oluşturuyorsa JDBC starter'ı tekrar ekleme.
- CacheDB için gereken şey çalışan bir Spring `DataSource` bean'idir.
- `cachedb-annotations` ve annotation processor olarak `cachedb-processor`
  her durumda gereklidir.
- Yalnızca bir provider starter seç. PostgreSQL için
  `cachedb-spring-boot-starter-postgres`, SQL Server için
  `cachedb-spring-boot-starter-mssql` kullan.
- Classpath'te tek provider varsa `cachedb.sql.provider=AUTO` onu seçer. Birden
  fazla provider bulunursa sistem sessizce seçim yapmak yerine başlangıcı
  durdurur.
- Yönetim ekranına ihtiyacın varsa `cachedb-spring-boot-starter-admin` ekle.
  Bu modül çekirdek runtime starter'ın parçası değildir.
- Önerilen uygulama API'si için [Deklaratif Repository Kullanımı](docs/deklaratif-repositoryler.md),
  provider ayarları için [Veritabanı Sağlayıcı SPI](docs/veritabani-provider-spi.md)
  sayfasına bak.

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
  registration:
    source: jdbc
    fail-on-unknown-entity: true
    entities:
      CustomerEntity:
        hot-entity-limit: 50000
        page-size: 100
        hot-policy:
          mode: STATE_WINDOW
          state-column: status
          state-values: [ACTIVE]
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

Repository sözleşmesini tanımla. Processor; route alanlarını, parametreleri,
limitleri ve dönüş tiplerini derleme sırasında kontrol eder. Ardından Spring
bean'ini ve reflection kullanmayan implementasyonu üretir:

```java
@CacheRepository(entity = CustomerEntity.class)
public interface CustomerRepository extends CacheDbRepository<CustomerEntity, Long> {
    @CacheLookup(idParameter = "customerId")
    HotLookup<CustomerEntity> detail(Long customerId);
}
```

Üretilen repository'yi uygulama servisine enjekte et:

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 42L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

WriteReceipt<CustomerEntity, Long> receipt = customers.save(customer);

CustomerEntity loaded = customers.detail(42L).orElseThrow(status ->
        new IllegalStateException("Müşteri Redis'te kullanıma hazır değil: " + status)
);
```

Davranış:

- `save` entity'yi Redis'e yazar.
- Kalıcı yazım seçilen SQL write-behind hattına girer.
- `detail` yalnızca Redis'ten okur; `NOT_CACHED`, SQL satırının olmadığı
  anlamına gelmez.
- Entity etkin veri politikasına uymuyorsa Redis’e kabul edilmeyebilir veya Redis’ten
  düşürülebilir.
- Arşiv veya etkin veri kümesinin dışındaki okumalar açık ve sınırlı bir
  `@SourceRoute` üzerinden yapılmalıdır.
- Redis'te önceden hazırlanmış kapsama ihtiyaç duyan route'lar `@WarmRoute`
  tanımlamalı; cutover sonrasında uygulama endpoint'lerinde
  `HotWindow.completeItems()` kullanılmalıdır.

`GeneratedCacheModule`, geriye dönük uyumluluk ve düşük seviyeli işler için
korunur. Yeni servis kodunda generated repository kullanılması önerilir. Devamı
için [Deklaratif Repository Kullanımı](docs/deklaratif-repositoryler.md)
sayfasına bak.

## Relation Nasıl Düşünülmeli?

CacheDB relation'ı Hibernate lazy loading gibi görünmez çalışan bir mekanizma
değildir. Relation yükleme açıkça istenir.

Relation'ı üç ayrı katman olarak düşün:

| Katman | Ne işe yarar? | CacheDB preload için şart mı? |
| --- | --- | --- |
| Kaynak veritabanındaki primary/foreign key | Kalıcı veri bütünlüğünü korur, orphan satır oluşmasını engeller | Önerilir, ama tek başına yeterli değildir |
| `@CacheRelation` metadata'sı | Parent entity alanının hangi hedef entity ile ilişkili olduğunu CacheDB'ye anlatır | Evet |
| Üretilen/özel loader + `@CacheLookup` | İstenen ilişkiyi sınırlı ve toplu biçimde yükler | Evet |

Kural net:

- Veritabanındaki foreign key, CacheDB relation'ını otomatik oluşturmaz.
- `@CacheRelation`, veritabanında constraint oluşturmaz.
- `kind = ONE_TO_MANY` bir DDL tanımı değil, relation şekli bilgisidir.
- `mappedBy`, hedef entity üzerinde parent id taşıyan alanı göstermelidir.
- Tip güvenli hedef ve sınırlı sıralama bilgisi verildiğinde processor standart
  partitioned loader'ı üretir. `@CacheEntity.relationLoader` yalnızca özel yükleme
  mantığı gerektiğinde kullanılmalıdır.
- Repository sözleşmesi ilişkiyi sınırlı bir `@CacheLookup` ile açıkça istemelidir.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheRelation(
            target = OrderEntity.class,
            // OrderEntity.customerId alanı; order tablosundaki customer_id kolonuna map edilir.
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 100,
            parentBatchSize = 32,
            orderBy = {"orderDate DESC", "orderId DESC"}
    )
    public List<OrderEntity> orders;
}
```

Okuma:

```java
@CacheLookup(idParameter = "customerId", relation = "orders",
        relationLimitParameter = "orderPreview", maxRelationRows = 25)
HotLookup<CustomerEntity> detail(Long customerId, int orderPreview);

CustomerEntity customer = customers.detail(customerId, 20)
        .orElseThrow(status -> mapHotLookupFailure(customerId, status));
```

Sık görülen durumlar:

| DB foreign key | `@CacheRelation` | Üretilen/özel loader | Sonuç |
| --- | --- | --- | --- |
| Var | Yok | Yok | Veritabanı tutarlıdır, ama CacheDB'nin preload edebileceği bir relation yolu yoktur. |
| Yok | Var | Var | `mappedBy` sorgulanabiliyorsa CacheDB preload yapabilir; fakat orphan veya tutarsız satır riski sana aittir. Bu yol sadece legacy veya soft relation için kabul edilebilir. |
| Var | Var | Yok | Batch-only relation için üretilebilir veya özel yükleme bilgisi yoksa derleme hatası alınır. |
| Var | Var | Var | BEST: kalıcı veri bütünlüğü, açık metadata ve limitli batch preload birlikte vardır. |

BEST: Detay ekranındaki küçük önizleme için sınırlı `@CacheLookup` kullan.

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
| Sipariş satırı önizleme | `linePreview=8` kullanan `@CacheLookup` |
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

## Mevcut SQL Veritabanı + ORM Sisteminden Geçiş

Migration Planner'ın amacı tek düğmeyle production cutover yapmak değildir.
Amaç, her production route için şu soruları kanıtlamaktır:

- Bu route entity mi, projection mı, ranked projection mı olmalı?
- Redis'e hangi hot window alınacak?
- Kalıcı SQL veritabanı tam geçmişte hangi rolü koruyacak?
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
10. Aynı işlemi her production ekranı, API, batch ve report yolu için tekrarla.

%100 dönüşüm coverage, tek seçilen tabloyla değil route envanteriyle sağlanır.

## Production'a Yakın Kullanım İçin Kısa Kontrol Listesi

- Redis HA/failover planı var mı?
- Seçilen SQL provider kalıcı doğruluk kaynağı olarak korunuyor mu?
- Dış sistemler kaynak veritabanını değiştiriyorsa outbox/CDC var mı?
- Her kritik route için route contract yazıldı mı?
- Projection gereken route production strict mode'da entity scan'e düşüyor mu?
- Hot policy ve tenant quota bellek bütçesini koruyor mu?
- Warm işlemi checkpoint/resume destekli mi?
- Side-by-side comparison veri sırası ve üyelik eşleşmesini kanıtladı mı?
- Admin UI yalnızca güvenli operasyon ağı veya gateway arkasında mı?
- Benchmark threshold ve public API compatibility CI'da çalışıyor mu?

## Hızlı Karşılaştırma

| Konu | CacheDB | Geleneksel ORM |
| --- | --- | --- |
| Birincil düşük gecikmeli okuma katmanı | Redis | Veritabanı |
| Kalıcı veri kaynağı | SQL veritabanı | Veritabanı |
| Metadata | Derleme zamanında üretilir | Genelde runtime metadata/reflection |
| Relation davranışı | Açık `FetchPlan`, loader ve projection | Çoğu zaman lazy/eager graph |
| Büyük liste ekranı | Projection/read-model | Sıklıkla entity graph veya join |
| En iyi kullanım alanı | Düşük gecikmeli kritik route'lar | SQL merkezli geniş ilişkisel işlemler |
| Ana risk | Yanlış hot-set ve projection tasarımı | N+1, geniş join ve runtime ORM maliyeti |

## Ölçülmüş Kanıt Nasıl Okunmalı?

Benchmark sonuçları "her zaman CacheDB daha hızlıdır" diye okunmamalıdır.
Doğru okuma şudur:

- generated binding yüzeyi düşük ek yük bandında kalabilir
- minimal repository kritik düşük gecikmeli yollarda daha fazla kontrol verir
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
- [Production GA Criteria](../PRODUCTION_GA_CRITERIA.md)
- [Release Checklist](docs/release-checklist.md)

## Topluluk ve Proje Dosyaları

- [License](../LICENSE)
- [Contributing](../CONTRIBUTING.md)
- [Security Policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Support](../SUPPORT.md)
- [Changelog](../CHANGELOG.md)
