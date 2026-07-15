# CacheDB PostgreSQL REST API Örneği

[English](README.md) | Türkçe

Bu proje, CacheDB’nin Redis ve PostgreSQL ile nasıl kullanılacağını gösteren bağımsız bir Spring Boot REST API örneğidir. Kullanıcının ana CacheDB reposunu indirip yerelde build etmesi gerekmez; proje CacheDB’yi Maven paketi olarak tüketir.

Örnek senaryo bir e-ticaret destek sistemidir:

- Müşteriler sürekli sipariş verir.
- Siparişlerin birden fazla satırı vardır.
- Ürünler kategoriye göre sık okunur.
- Destek talepleri operasyon paneline veri sağlar.
- Müşteri sipariş zaman çizelgesi, tüm aggregate yüklenmeden özet okuma modeli üzerinden döner.

## Bu Örnekte Ürün Konumlandırması

Bu örnek, CacheDB'yi PostgreSQL'in önüne konan şeffaf bir cache gibi
konumlandırmaz. API bilinçli olarak ikiye ayrılır: Redis'teki aktif veri setini
okuyan operasyonel yollar ve PostgreSQL'i açıkça kullanan arşiv/geçmiş yolları.

| Yol tipi | Örnek | Veri yolu | Sözleşme |
|---|---|---|---|
| Operasyonel entity yazma | `POST /api/orders` | Önce Redis, sonra PostgreSQL'e write-behind | Yazı CacheDB üzerinden kabul edilir ve arka planda kalıcılaştırılır. Kaydın Redis'te kalıp kalmayacağını hot policy belirler. |
| Operasyonel liste | `GET /api/customers/{id}/orders` | Redis projection: `OrderSummary` | Liste tam sipariş aggregate verisi yerine sınırlı bir read-model okur. |
| Seçilmiş detay | `GET /api/orders/{id}` | Redis entity + sınırlı ilişki önizlemesi | Aktif veri setindeki kayıtlar için çalışır. Sipariş aktif veri setinin dışındaysa açık bir detay SQL yolu tasarlanmalıdır. |
| Arşiv/geçmiş | `GET /api/orders/archive` | Doğrudan PostgreSQL sorgusu | Eski geçmiş, export ve audit okumaları varsayılan olarak Redis'i büyütmemelidir. |
| Panel | `GET /api/dashboard/commerce` | Redis projection ve sınırlı entity sorgusu | Panel satırları ekran ihtiyacına göre önceden şekillendirilmiş veriden okunur. |

BEST: önce aktif okuma/yazma yolunu tasarla, hot policy kararını ver,
listeleri projection repository üzerinden oku ve arşiv/geçmiş okumalarını açık
PostgreSQL sorgusu olarak tut.

ANTI-PATTERN: geniş ve dinamik bir entity sorgusunun veriyi Redis'te
bulamamasını, PostgreSQL'i taramasını, Redis'i doldurmasını ve production bellek
sınırları altında yine de öngörülebilir kalmasını beklemek.

## Bağımlılık Modeli

Bu proje CacheDB’yi dış Maven paketi olarak kullanır:

```xml
<properties>
  <java.version>21</java.version>
  <cachedb.version>0.4.1</cachedb.version>
</properties>

<repositories>
  <repository>
    <id>cache-database-github-packages</id>
    <name>CacheDB GitHub Packages</name>
    <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
  </repository>
</repositories>

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
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <release>${java.version}</release>
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

Yani kullanıcı ana projeyi önce derlemek zorunda değildir. CacheDB `0.4.1`, ana repodan GitHub Packages'a yayımlanır ve bu örnek proje paketi oradan çeker.
`cachedb-annotations` ve `cachedb-processor`, `OrderEntityCacheBinding` gibi generated binding sınıflarının üretilmesi için gereklidir.

Çalıştırma ve build gereksinimi: JDK 21 kullan. Örnek `pom.xml` içinde
`<java.version>21</java.version>` tanımlıdır ve proje Java release 21 ile derlenir.

GitHub Packages Maven erişimi için kimlik bilgisi gerekir. `pom.xml` içindeki `<repository><id>` değeri ile Maven `settings.xml` içindeki `<server><id>` değeri aynı olmalıdır:

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

`read:packages` yetkisi olan bir GitHub personal access token tanımladıktan sonra proje doğrudan build edilir:

```bash
export GITHUB_ACTOR=github-kullanici-adin
export GITHUB_TOKEN=read-packages-token
mvn clean package
```

Bu ayar yapılmazsa repository URL doğru olsa bile Maven genellikle `401 Unauthorized` hatası verir.

## 0.4.1 İçin Doğrulanmış Deklaratif Akış

Bu örnek CacheDB `0.4.1` ile çalışacak şekilde hazırlanmıştır. Buradaki temel
sözleşme şudur:

1. Yazılar CacheDB üzerinden alınır ve PostgreSQL’e write-behind ile aktarılır.
2. PostgreSQL’de önceden duran kayıtlar uygulama açılır açılmaz Redis’e kendiliğinden yüklenmez.
3. Hızlı çalışması gereken yollar için sınırlı bir aktif entity seti veya projection gerekir.
4. Warm/backfill akışı, kayıtlı JDBC loader üzerinden PostgreSQL’den okur ve sonra Redis/projection satırlarını doldurur.
5. Yük testi, seed verisi PostgreSQL’e kalıcı olarak indikten ve warm adımı bittikten sonra koşulmalıdır.

Bu sözleşme örnek kodda açıkça görünür.

### 0.4.1 Sürümünde Doğrulanan Production Sözleşmeleri

- Komut endpoint’leri `202 Accepted` döner. Bu yanıt, komutun Redis tarafından kabul edildiğini söyler; SQL’e kalıcı yazımın tamamlandığını söylemez.
- Alt kayıt yazılmadan önce indeksli tek bir SQL `EXISTS` sorgusu çalışır. Ana kayıt henüz kalıcı değilse request thread’i bekletilmez; `Retry-After` ile birlikte `409 Conflict` döner.
- Aggregate silme işlemi örtük değildir. Siparişin satırları varsa silme reddedilir. Aggregate’in tamamını silmek için açık ve transactional bir command yazılmalıdır.
- Her entity, kullanıldığı okuma yoluna uygun bir admission policy kullanır. Sipariş, katalog, destek, lojistik, raporlama ve audit verileri tek bir yanıltıcı varsayılan policy’yi paylaşmaz.
- Parasal alanlarda `BigDecimal` ve `NUMERIC(19,4)` kullanılır. Redis sıralama puanları parasal değer olmadığı için `double` kalır.
- Relation loader’lar her ana kayıt için ayrı sorgu çalıştırmak yerine sınırlı `IN (...)` batch’leri kullanır.
- Warm/backfill işlemi sınırlı bir asenkron job olarak çalışır: `1` worker ve `8` elemanlık kuyruk. İşlemi `POST` ile başlat, sonucu `/api/warm/jobs/{jobId}` üzerinden izle.
- `/api/health/live` process’in çalıştığını, `/api/health/ready` ise Redis, SQL ve write-behind durumunu gösterir.
- Sınırı aşan route limitleri sessizce küçültülmez; `400 Bad Request` ile reddedilir.
- Uygulama servisleri yalnızca üretilmiş bir `GeneratedCacheModule.Scope` kullanır; repository veya binding nesnelerini elle oluşturmaz.
- Her entity için admission policy `application.yml` üzerinden tanımlanır. `cachedb.registration.source: jdbc` ayarı, veritabanı kayıt kaynağını açıkça belirtir.
- Named query, fetch planı, projection ve tip güvenli warm planları derleme sırasında üretilir. `ProjectionSchema`, projection alan sırasını ve serileştirme sözleşmesini açık tutar.

`0.4.1` sürümünde JDBC işlemleri de sınırlıdır: warm için kayıtlı JDBC sorguları 15
saniyede, write-behind SQL işlemleri 20 saniyede zaman aşımına uğrar. Admin
istek ve arka plan kuyruklarının kapasitesi `application.yml` içinde açıkça
tanımlanmıştır. Sürüm kontrollü hydration, eski bir warm sonucunun
Redis'teki daha yeni kaydı ezmesini engeller.

```java
@Bean
CacheDatabaseConfigCustomizer sampleCacheDbTuning() {
    return (builder, properties) -> builder
            .readThrough(ReadThroughConfig.builder()
                    .mode(ReadThroughMode.REDIS_ONLY)
                    .failOnMissingLoader(false)
                    .hydrateLoadedEntities(false)
                    .maxQueryLoadRows(1_000)
                    .queryTimeoutSeconds(15)
                    .build())
            .writeBehind(WriteBehindConfig.builder()
                    .workerThreads(2)
                    .batchSize(128)
                    .statementTimeoutSeconds(20)
                    .build());
}
```

Spring Boot, generated registrar ve `application.yml` içindeki entity bazlı policy kataloğunu kullanarak JDBC kaydını otomatik yapar. Uygulama kodu `registerJdbcBacked(...)` çağırmamalı ve her entity için ayrı repository bean’i oluşturmamalıdır.

```yaml
cachedb:
  registration:
    source: jdbc
    fail-on-unknown-entity: true
```

Ön yükleme yolu, tam tablo taraması yerine generated domain scope ve sorgu şekline uygun tip güvenli plan kullanır:

```java
CacheWarmPlan plan = domain.orders().warmPlan(
        "sample-customer-orders",
        domain.orders().queries().customerTimelineQuery(customerId, limit),
        limit
);
CacheWarmResult result = cacheDatabase.warmProjections(plan);
```
Uygulama hazır olduktan sonra yerel yük kapısını şu komutla çalıştır:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-load-test.ps1 `
  -RouteProfile hot-timeline `
  -Concurrency 4 `
  -DurationSeconds 10 `
  -SeedCustomers 10 `
  -OrdersPerCustomer 20 `
  -LinesPerOrder 4 `
  -WarmCustomers 10 `
  -WarmLimit 100 `
  -MaxP95Millis 500
```

`0.2.0` sürümünden korunan tarihsel yerel yük testi başlangıç değeri:

| Provider | Versiyon | Yol profili | Sonuç |
|---|---:|---|---|
| PostgreSQL | `0.2.0` | `hot-timeline` | `ok=605`, `fail=0`, `p95=184 ms` |

Bu sonuç production benchmark değildir; örnek proje için smoke gate kanıtıdır.
Staging ortamında veri hacmini büyüt, testi daha uzun çalıştır ve Redis bellek
kullanımı, projection gecikmesi, write-behind backlog, PostgreSQL gecikmesi ve
JVM GC metriklerini birlikte izle.

## Yerelde Çalıştırma

1. Redis ve PostgreSQL’i başlat:

```bash
docker compose up -d
```

2. API’yi başlat:

```bash
mvn spring-boot:run
```

3. Hazırlık durumunu kontrol et:

```bash
curl http://127.0.0.1:8091/api/health/ready
```

4. Demo verisini üret:

```bash
curl -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. CacheDB yönetim ekranını aç:

```text
http://127.0.0.1:8091/cachedb-admin
```

## Ana API Akışı

| Adım | Endpoint | Ana veri yolu | Ne gösterir? |
|---|---|---|---|
| Sağlık | `GET /api/health/ready` | Çalışma zamanı kontrolü | Redis bağlantısı ve arka plan yazma özeti |
| Veri üretme | `POST /api/demo/seed` | Redis yazma, PostgreSQL’e arka plan kalıcılık | CacheDB yazma yolu, SQL kalıcılığı, projection yenileme |
| Müşteri detay | `GET /api/customers/1?orderPreview=5` | Redis entity + sınırlı ilişki önizlemesi | Sınırlı sipariş önizlemesiyle entity detayı |
| Sipariş listesi | `GET /api/customers/1/orders?limit=20` | Redis projection: `OrderSummary` | Tüm aggregate yüklenmeden müşteri sipariş listesi |
| Sipariş warm/backfill | `POST /api/warm/orders/customer/1?limit=100` | PostgreSQL okuma -> Redis’e yerleştirme | Aktif veri seti ve projection yolu için açık warm/backfill |
| Sipariş detay | `GET /api/orders/10001?linePreview=5` | Redis entity + sınırlı sipariş satırı | Sınırlı satır önizlemesiyle detay okuma |
| Yüksek değerli sipariş | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Redis ranked projection: `OrderSummary` | Global sıralı projection sorgusu |
| Arşiv siparişleri | `GET /api/orders/archive?customerId=1&limit=20` | Doğrudan PostgreSQL sorgusu | Aynı `OrderSummary` yanıt şekliyle arşiv okuması |
| Panel | `GET /api/dashboard/commerce?limit=25` | Redis projection + Redis entity sorgusu | Projection ve destek talebi sorgularıyla küçük panel |
| Ayarlar | `GET /api/tuning` | Çalışma zamanı ayarları | Aktif CacheDB politikaları ve koruma eşikleri |

Bu örnek “her şeyi Redis’ten oku” demek değildir. Production’da kural şudur:

| İhtiyaç | BEST yol | Neden? |
|---|---|---|
| Operasyonel entity oluşturma veya güncelleme | CacheDB entity repository | Yazı Redis üzerinden kabul edilir, PostgreSQL’e write-behind ile kalıcılaştırılır |
| Büyüyen listenin ilk sayfasını gösterme | `OrderSummary` kullanan projection repository | Ekran tam `OrderEntity` yerine küçük, sıralanmış özet satır okur |
| Seçilmiş detay ekranını gösterme | Sınırlı fetch preset kullanan entity repository | Sadece seçilen aggregate ve sınırlı alt kayıt önizlemesi yüklenir |
| Eski geçmişi veya export verisini okuma | Açık SQL yolu | Arşiv ve raporlama okumaları Redis’i şişirmemeli, SQL sorgu planı üzerinden çalışmalıdır |

## Kopyala-Çalıştır Aktif Veri Seti Akışı

Bu bölüm, doğru modeli baştan sona denemen için hazırlandı. Komutlar,
uygulamanın `8091` portunda çalıştığını ve terminalde
`sample-cache-database-postgresql` dizininde olduğunu varsayar.

### Senaryo 1: SQL’deki Mevcut Kayıt Redis’e Kendiliğinden Gelmez

Önce CacheDB üzerinden veri üret; böylece PostgreSQL’de kalıcı satırlar oluşsun:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

Şimdi Redis’i temizle. Bu adım, PostgreSQL’de veri olan ama Redis aktif veri
seti boş olan mevcut sistem durumunu simüle eder:

```bash
docker compose exec redis redis-cli FLUSHDB
```

Bu Redis projection yolu artık boş liste dönebilir; çünkü aktif projection seti
boştur:

```bash
curl.exe "http://127.0.0.1:8091/api/customers/1/orders?limit=5"
```

Açık SQL arşiv yolu ise kalıcı satırları görmeye devam eder:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/archive?customerId=1&limit=5"
```

Production anlamı: entity/projection okumaları aktif veri seti okumasıdır.
Mevcut SQL kayıtları, Redis okuma yolları çalışmadan önce açık warm/backfill
yoluyla Redis’e alınmalıdır.

### Senaryo 2: Bir Route İçin Redis Projection Warm Et

Önce dry-run çalıştır. Bu çağrı PostgreSQL’den kaç satır okunacağını gösterir,
ama Redis’i değiştirmez:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&dryRun=true"
```

Her warm `POST` çağrısı `202` ve bir `jobId` döner. Job `COMPLETED` durumuna
gelmeden hedef okuma yolunu çalıştırma:

```powershell
$job = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true"
do {
    Start-Sleep -Milliseconds 250
    $state = Invoke-RestMethod "http://127.0.0.1:8091/api/warm/jobs/$($job.jobId)"
} while ($state.status -in @("QUEUED", "RUNNING"))
if ($state.status -ne "COMPLETED") { throw ($state.error | ConvertTo-Json -Compress) }
```

Başlatılan job, müşteri sipariş listesi için yalnızca `OrderSummary` projection’ını warm eder:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true"
```

Artık sipariş listesi Redis projection satırlarını okur:

```bash
curl.exe "http://127.0.0.1:8091/api/customers/1/orders?limit=5"
```

Projection-only warm, liste ve panel ekranları için doğru modeldir. Bu ekranda
tam `OrderEntity` veri gövdesine ihtiyaç yoktur.

### Senaryo 3: Detay Ekranı İçin Full Entity Warm Et

Seçilmiş sipariş detayı da Redis’ten okunacaksa full entity penceresini ayrıca
warm et:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=false"
```

Sonra seçilmiş detay yolu Redis entity verisini okuyabilir:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/10001?linePreview=5"
```

Production kuralı: sadece liste var diye full entity warm etme. Full entity,
seçilmiş detay veya command route gerçekten ihtiyaç duyuyorsa Redis’te tutulmalıdır.

### Senaryo 4: Yeni Yazılar Redis’e Hemen Girer

CacheDB üzerinden müşteri oluştur:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/customers" -H "Content-Type: application/json" -d '{"customerId":9001,"taxNumber":"TAX-9001","customerType":"RETAIL","segment":"VIP","status":"ACTIVE"}'
```

CacheDB üzerinden sipariş oluştur:

```bash
curl.exe -X POST "http://127.0.0.1:8091/api/orders" -H "Content-Type: application/json" -d '{"orderId":90010001,"customerId":9001,"orderAmount":725.50,"currencyCode":"USD","orderType":"EXPRESS","status":"PAID","lineCount":0}'
```

Redis projection yolunu oku:

```bash
curl.exe "http://127.0.0.1:8091/api/customers/9001/orders?limit=5"
```

Seçilmiş Redis entity detayını oku:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/90010001?linePreview=5"
```

Production anlamı: CacheDB yazma yolları Redis’i önce doldurur, kalıcı satırı
PostgreSQL’e write-behind ile taşır. Mevcut SQL kayıtları warm ister; yeni
CacheDB yazıları ayrıca warm gerektirmez.

### Senaryo 5: Arşiv ve Export SQL’de Kalır

Eski geçmiş, export, audit ve tek seferlik arama için açık SQL yolunu kullan:

```bash
curl.exe "http://127.0.0.1:8091/api/orders/archive?customerId=1&beforeOrderDate=9999999999999&beforeOrderId=9999999999999&limit=20"
```

Arşiv ihtiyacını Redis aktif veri setini tüm tabloyu kapsayacak kadar büyüterek
çözmeye çalışma. Bu yaklaşım Redis’i ikinci bir arşiv veritabanına çevirir.

### Kopyala-Çalıştır Deklaratif Uygulama

Uygulama yalnızca modeli ve sorgu yollarının sınırlarını tanımlar. CacheDB tip güvenli erişim katmanını üretir; Spring Boot da JDBC yükleyicilerini otomatik kaydeder.

#### 1. Entity ve sınırlı sorguyu tanımla

```java
@CacheEntity(table = "sample_orders", redisNamespace = "sample-orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Long orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;

    @CacheColumn("status")
    public String status;

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<OrderEntity, OrderReadModels.OrderSummary, Long> orderSummaryProjection() {
        return OrderReadModels.ORDER_SUMMARY_PROJECTION;
    }

    @CacheNamedQuery("customerTimeline")
    public static QuerySpec customerTimelineQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }
}
```

Sorgunun adı ve üst sınırı bellidir. Controller, çalışma anında sınırsız veya rastgele sorgu üretmez.

#### 2. Serileştirme ve indeks kolonlarını bir kez tanımla

```java
public final class OrderReadModels {
    private static final ProjectionSchema<OrderSummary> ORDER_SUMMARY_SCHEMA =
            ProjectionSchema.<OrderSummary>builder()
                    .longColumn("order_id", OrderSummary::orderId)
                    .longColumn("customer_id", OrderSummary::customerId)
                    .longColumn("order_date", OrderSummary::orderDate)
                    .decimalColumn("order_amount", OrderSummary::orderAmount)
                    .stringColumn("status", OrderSummary::status)
                    .decodeWith(row -> new OrderSummary(
                            row.longValue("order_id"),
                            row.longValue("customer_id"),
                            row.longValue("order_date"),
                            row.decimal("order_amount"),
                            row.string("status")
                    ))
                    .build();

    public static final EntityProjection<OrderEntity, OrderSummary, Long> ORDER_SUMMARY_PROJECTION =
            EntityProjection.<OrderEntity, OrderSummary, Long>of(
                    "order-summary",
                    ORDER_SUMMARY_SCHEMA,
                    OrderSummary::orderId,
                    order -> new OrderSummary(
                            order.orderId,
                            order.customerId,
                            order.orderDate,
                            order.orderAmount,
                            order.status
                    )
            ).rankedBy("order_date").asyncRefresh();

    public record OrderSummary(
            Long orderId,
            Long customerId,
            Long orderDate,
            BigDecimal orderAmount,
            String status
    ) {
    }
}
```

`ProjectionSchema` reflection kullanmaz. Redis’e yazma, Redis’ten okuma ve sorgu indeksi kolonları için tek doğruluk kaynağıdır.

#### 3. Her entity için policy değerlerini yapılandırmaya koy

```yaml
cachedb:
  registration:
    source: jdbc
    fail-on-unknown-entity: true
    entities:
      OrderEntity:
        hot-entity-limit: 100000
        page-size: 100
        entity-ttl-seconds: 0
        page-ttl-seconds: 60
        hot-policy:
          mode: COMPOSITE
          composite-operator: ANY
          children:
            - mode: TIME_WINDOW
              time-column: order_date
              hot-for-seconds: 7776000
            - mode: STATE_WINDOW
              state-column: status
              state-values: [NEW, PAID, PICKING, OPEN, PENDING]
```

CacheDB başlangıçta iki aşama çalıştırır. Önce her entity kendi policy değeri ve JDBC kaynağıyla kaydedilir; sonra ilişki ve sayfa yükleyicileri bağlanır. Böylece ana entity’nin policy değeri yanlışlıkla alt entity’ye taşınmaz. Entity adı hatalıysa uygulama başlangıçta durur.

#### 4. Tek bir generated domain bean’i aç

```java
@Configuration(proxyBeanMethods = false)
public class CacheDbDomainConfig {
    @Bean
    GeneratedCacheModule.Scope domain(CacheDatabase cacheDatabase) {
        return GeneratedCacheModule.using(cacheDatabase);
    }
}
```

Her entity veya projection için ayrı Spring bean’i oluşturma. `GeneratedCacheModule.Scope`, derleme sırasında üretilen ve paketteki tüm entity’leri tip güvenli biçimde açan değişmez uygulama yüzeyidir.

#### 5. Uygulama kodunda generated DSL’i kullan

```java
@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final GeneratedCacheModule.Scope domain;

    public CustomerController(GeneratedCacheModule.Scope domain) {
        this.domain = domain;
    }

    @GetMapping("/{customerId}/orders")
    public List<OrderReadModels.OrderSummary> timeline(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return domain.orders().projections().orderSummary().query(
                domain.orders().queries().customerTimelineQuery(customerId, safeLimit)
        );
    }
}
```

Controller; `EntityRegistry`, Redis anahtarları, codec, JDBC loader veya projection implementasyon sınıflarını bilmez.

#### 6. Tip güvenli planla ön yükleme yap

```java
@Service
public class CustomerOrderWarmService {
    private final CacheDatabase cacheDatabase;
    private final GeneratedCacheModule.Scope domain;

    public CustomerOrderWarmService(CacheDatabase cacheDatabase, GeneratedCacheModule.Scope domain) {
        this.cacheDatabase = cacheDatabase;
        this.domain = domain;
    }

    public CacheWarmResult dryRun(long customerId, int limit) {
        return cacheDatabase.dryRun(plan(customerId, limit));
    }

    public CacheWarmResult warmProjection(long customerId, int limit) {
        return cacheDatabase.warmProjections(plan(customerId, limit));
    }

    public CacheWarmResult warmEntityAndProjection(long customerId, int limit) {
        return cacheDatabase.warm(plan(customerId, limit));
    }

    private CacheWarmPlan plan(long customerId, int limit) {
        return domain.orders().warmPlan(
                "customer-order-window-" + customerId,
                domain.orders().queries().customerTimelineQuery(customerId, limit),
                limit
        );
    }
}
```

Değişiklik yapmadan önce `dryRun`, liste ve dashboard yolları için `warmProjections`, aynı aralıkta tam entity detayı da gerekiyorsa `warm` kullan. HTTP üzerinden başlatılan ön yükleme işi asenkron ve sınırlı kalmalıdır.

#### 7. Arşiv ve geçmiş sorgularını açık SQL yolu olarak bırak

CacheDB, Redis’te bulunamayan her kayıt için sınırsız veritabanı sorgusu çalıştırmaz. Eski geçmiş, dışa aktarım ve denetim sorguları indeksli ve açık SQL yollarında kalır. Keyset pagination ve kesin bir sayfa üst sınırı kullan.

| Sorgu yolu | Redis entity | Redis projection | PostgreSQL |
|---|---:|---:|---:|
| Müşteri sipariş listesi | Hayır | Evet | Yalnızca ön yükleme sırasında |
| Seçilmiş güncel sipariş detayı | Evet | İsteğe bağlı | Yalnızca açık eski-detay yolunda |
| Sipariş oluşturma/güncelleme | Policy sonucuna bağlı | Tanımlıysa yenilenir | Write-behind ile kalıcılaştırılır |
| Arşiv/dışa aktarım | Hayır | Hayır | Evet |

## Bu README’de Geçen Temel Terimler

| Terim | Bu örnekteki anlamı | Somut karşılığı |
|---|---|---|
| Entity | Bir SQL tablosuna ve Redis namespace’ine bağlanan tam komut/detay modeli | `OrderEntity` |
| Generated binding | Derleme sırasında üretilen metadata, sorgu, fetch preset, komut ve projection kodu | `OrderEntityCacheBinding` |
| Generated domain module | Controller ve servislerin kullandığı, paket düzeyindeki tek tip güvenli giriş noktası | `GeneratedCacheModule.Scope` |
| Projection | Tam aggregate yerine liste veya dashboard için kullanılan küçük okuma modeli | `OrderSummary` |
| Projection schema | Payload ve indeks kolonlarını tek yerde tanımlayan reflection’sız şema | `ProjectionSchema<OrderSummary>` |
| Named query | Adı, sıralaması ve sınırı belirli tekrar kullanılabilir sorgu sözleşmesi | `customerTimelineQuery(...)` |
| Fetch preset | Detay ekranında hangi ilişkinin kaç satır yükleneceğini belirleyen tanım | `linePreview(...)` |
| Policy kataloğu | Her entity’ye ayrı etkin veri ve boyut kuralı atayan YAML haritası | `cachedb.registration.entities` |
| Warm plan | JDBC’den Redis’e yapılacak sınırlı ön yükleme sözleşmesi | `domain.orders().warmPlan(...)` |
| Write-behind | Komutun önce Redis tarafından kabul edilmesi, SQL kalıcılığının asenkron tamamlanması | `202 Accepted`, worker metrikleri |
| Guardrail | Aşırı sonuç boyutunu veya bellek baskısını reddeden kesin sınır | API, sorgu şekli ve Redis sınırları |

## Katman Katman Mimari

| Katman | Ana dosyalar | Sorumluluk | Kural |
|---|---|---|---|
| API | `web/*Controller.java` | Girdiyi doğrular ve generated domain DSL’i çağırır | Sınırsız liste açma |
| Domain tanımı | `domain/*Entity.java` | SQL mapping, sorgu, ilişki, fetch preset ve komutlar | Sözleşmeleri açık ve derleme zamanında üretilebilir tut |
| Okuma modeli | `readmodel/*ReadModels.java` | Küçük projection şeması ve entity’den ekrana dönüşüm | Büyüyen veya genel sıralı listelerde projection kullan |
| Domain erişimi | `SampleCacheDbDomainConfig.java` | Tek generated package scope bean’ini açar | Entity başına repository bean’i oluşturma |
| Ön yükleme servisi | `SampleWarmBackfillService.java` | Tip güvenli planı ve dry-run/projection/full modunu seçer | İşi sınırla, HTTP çalıştırmasını asenkron tut |
| Kalıcı veri sorgusu | `JdbcTemplate` kullanan arşiv metotları | Eski geçmişi ve dışa aktarımı indeksli SQL’den okur | Keyset pagination ve kesin üst sınır kullan |
| Runtime policy | `application.yml` | Entity bazlı etkin veri kuralı ve JDBC kaydı | Bilinmeyen entity adında başlangıcı durdur |
| Platform ayarı | `SampleCacheDbTuningConfig.java` | Thread, kuyruk, timeout, bellek ve write-behind sınırları | Ölçülmüş yüke göre ayarla |

### API Katmanı: ORM’e Gitmeden Önce İsteği Sınırla

```java
int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
return domain.orders().projections().orderSummary().query(
        domain.orders().queries().customerTimelineQuery(customerId, safeLimit)
);
```

### Deklaratif Domain Erişimi: Tek Bean, Repository Wiring Yok

```java
@Bean
GeneratedCacheModule.Scope domain(CacheDatabase cacheDatabase) {
    return GeneratedCacheModule.using(cacheDatabase);
}
```

Spring Boot generated registrar sınıflarını bulur ve uygulama bean’i oluşturulmadan önce YAML policy değerlerini uygular. Uygulama kodu binding’leri elle kaydetmez.

### Entity Katmanı: SQL Mapping ve Cache Mapping Açık Tanımlıdır

`OrderEntity`; tabloyu, Redis namespace’ini, primary key’i, kolonları, ilişkiyi, named query’leri ve projection’ı tanımlar:

```java
@CacheEntity(
        table = "sample_orders",
        redisNamespace = "sample-orders",
        relationLoader = OrderLinesRelationBatchLoader.class
)
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheRelation(
            targetEntity = "OrderLineEntity",
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderLineEntity> lines;
}
```

Bu gizli bir mekanizma değildir. Tablo ve kolon tanımı CacheDB’ye entity’nin nasıl yazılıp okunacağını söyler. Relation metadata’sı ise fetch plan istendiğinde Java nesne grafiğinin nasıl bağlanacağını söyler.

### Relation Modeli: Foreign Key ve `@CacheRelation` Farklı İşleri Çözer

Veritabanındaki foreign key veri bütünlüğünü korur:

```sql
customer_id BIGINT NOT NULL REFERENCES sample_customers(customer_id)
```

`@CacheRelation` uygulamanın okuma şeklini tanımlar:

```java
@CacheRelation(
        targetEntity = "OrderEntity",
        mappedBy = "customerId",
        kind = CacheRelation.RelationKind.ONE_TO_MANY,
        batchLoadOnly = true
)
public List<OrderEntity> orders;
```

Doğru yorum şu şekildedir:

| Durum | Davranış |
|---|---|
| Foreign key var ve `@CacheRelation` var | SQL bütünlüğü korur, CacheDB istenirse object graph yükler |
| Foreign key var ama `@CacheRelation` yok | SQL doğru kalır, fakat CacheDB Java child koleksiyonunu otomatik doldurmaz |
| `@CacheRelation` var ama foreign key yok | CacheDB eşleşen kolonla sorgu yapabilir, fakat SQL orphan kayıtları engellemez |
| İkisi de yok | Sadece açık child sorguları kullanılmalı; ORM tarzı relation beklenmemeli |

Production önerisi: ikisini birlikte kullan. Foreign key doğruluk içindir; `@CacheRelation` ve fetch preset kontrollü nesne grafiği yüklemek içindir.

### Fetch Preset: Detay Ekranı Tam Aggregate Değil, Önizleme Alır

`CustomerEntity` küçük bir sipariş önizlemesi sunar:

```java
@CacheFetchPreset("ordersPreview")
public static FetchPlan ordersPreviewFetchPlan(int orderLimit) {
    return FetchPlan.of("orders").withRelationLimit("orders", Math.max(1, orderLimit));
}
```

`CustomerController.detail` bu preset’i kullanır:

```java
return CustomerEntityCacheBinding
        .ordersPreviewRepository(
                customerRepository,
                ApiLimits.requireInRange("orderPreview", orderPreview, 1, 25)
        )
        .findById(customerId)
        .orElseThrow(...);
```

Yani detay ekranı “son 5 siparişi” gösterebilir; ama müşterinin tüm tarihsel siparişlerini ve tüm satırlarını tek yanıtta yüklemez.

### Projection Katmanı: Büyüyen Listeler `OrderSummary` Kullanır

Sipariş listesi ve yüksek değerli sipariş ekranı tam `OrderEntity` yerine `OrderSummary` kullanır:

```java
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        BigDecimal orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

Projection gerçek ekranların sıralama alanlarına göre önceden sıralı tutulur:

```java
).rankedBy("order_date", "priority_score").asyncRefresh();
```

Böylece Redis içinde liste satırları küçük kalır. Tam entity hâlâ detay ekranı için vardır; fakat liste ekranı tam aggregate yükleme maliyetini ödemez.

## Uçtan Uca OrderSummary Örneği

`OrderSummary`, müşteri sipariş listesi ve sıralı sipariş ekranları için kullanılan okuma modelidir. Sipariş satırlarını, müşteri detayını ve denetim geçmişini bilerek içermez.

1. `OrderEntity`, `@CacheProjectionDefinition("orderSummary")` tanımını yapar.
2. `OrderReadModels`, tek bir `ProjectionSchema<OrderSummary>` ve entity’den summary’ye dönüşüm tanımlar.
3. Processor, `domain.orders().projections().orderSummary()` yolunu üretir.
4. Ön yükleme kodu `domain.orders().warmPlan(...)` kullanır.
5. Controller, generated named query ile generated projection repository’yi birleştirir.

```java
return domain.orders().projections().orderSummary().query(
        domain.orders().queries().recentHighValueOrdersQuery(minimumAmount, safeLimit)
);
```

Yanıt doğrudan ekranın ihtiyacı olan şekildedir. Arka planda tam entity yüklenmez; aynı şema Redis serileştirmesini ve indeks kolonlarını yönetir.

## Sorgu Akışı: Redis mi PostgreSQL mi?

Bu örnek artık iki yolu da açık gösterir. Sık kullanılan operasyonel okumalar CacheDB/Redis üzerinden gider. Arşiv okumaları doğrudan PostgreSQL üzerinden yapılır.

| Çağrı | İlk çalışma yolu | PostgreSQL ne zaman kullanılır? | Redis davranışı | Neden? |
|---|---|---|---|---|
| `POST /api/customers` | `domain.<entity>().save(...)` | Write-behind satırı arka planda kalıcılaştırır | Aktif veri politikası kabul ederse entity Redis’e girer | Normal yazma yolu |
| `POST /api/orders` | `JdbcTemplate` FK hazırlık kontrolü, sonra `domain.<entity>().save(...)` | Alt kayıt yazmadan önce üst müşteri SQL’de kalıcı mı diye bakılır | Sipariş Redis üzerinden kaydedilir ve write-behind kuyruğuna girer | FK ihlalini engeller, Redis-first yazma modelini korur |
| `GET /api/customers/{id}/orders` | `domain.orders().projections().orderSummary()` | Sık kullanılan liste yolunda kullanılmaz | Redis projection verisini ve indeksini okur; eksik projection satırını Redis’teki base entity verisinden ısıtabilir | Hızlı müşteri zaman çizelgesi |
| `GET /api/orders/high-value` | `domain.orders().projections().orderSummary()` | Sık kullanılan liste yolunda kullanılmaz | Ranked Redis projection verisini okur | Hızlı global sıralı iş listesi |
| `GET /api/orders/{id}` | `linePreview` fetch preset ile `domain.<entity>().findById(...)` | Bu örnek endpoint’i Redis’te bulunamama durumunda SQL’e gitmez | Redis entity verisini okur, relation loader sınırlı satır sorgusunu Redis üzerinden yapar | Sık kullanılan sipariş detay ekranı |
| `GET /api/orders/archive` | `JdbcTemplate.query` | Doğrudan PostgreSQL okur | Redis’i değiştirmez | Arşiv/geçmiş okuması |
| `GET /api/products/active` | `domain.products().projections().productAvailability()` | Bu örnek endpoint’inde kullanılmaz | Sınırlı Redis projection sorgusu | Küçük katalog listesi |
| `GET /api/tickets/open` | `domain.<entity>().queries()` | Bu örnek endpoint’inde kullanılmaz | Sınırlı Redis entity sorgusu | Operasyon kuyruğu |
| `GET /api/dashboard/commerce` | Projection sorgusu + destek talebi entity sorgusu | Bu örnek endpoint’inde kullanılmaz | Redis projection ve Redis entity sorgusunu birleştirir | Panel ilk ekranı |

Önemli kural: CacheDB repository okumaları, Redis’te bulunamayan her kayıt için otomatik veritabanı taraması yapacak anlamına gelmez. Bu örnekte `domain.<entity>().findById(...)` ve normal `query(...)` yolları Redis/aktif veri seti yollarıdır. Arşiv veya tam geçmiş okuması gerekiyorsa bunu açık SQL yolu olarak tasarla. Bu örnekteki arşiv yolu:

```java
@GetMapping("/archive")
public List<OrderReadModels.OrderSummary> archiveFromSql(
        @RequestParam long customerId,
        @RequestParam(required = false) Long beforeOrderDate,
        @RequestParam(required = false) Long beforeOrderId,
        @RequestParam(defaultValue = "100") int limit
) {
    return jdbcTemplate.query(ARCHIVE_SQL, rowMapper, customerId, upperBound, upperBound, upperId, safeLimit);
}
```

SQL yolu aynı yanıt modelini kullanır, fakat veri kaynağı farklıdır:

```sql
SELECT order_id, customer_id, order_date, order_amount, currency_code,
       order_type, status, line_count, priority_score
FROM sample_orders
WHERE customer_id = ?
  AND (order_date < ? OR (order_date = ? AND order_id < ?))
ORDER BY order_date DESC, order_id DESC
OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
```

Production’da karar şu şekilde verilmelidir:

| Ekran tipi | Redis/CacheDB kullan | PostgreSQL kullan |
|---|---|---|
| İlk sayfa zaman çizelgesi | Evet, projection | Hayır |
| Yüksek değerli global liste | Evet, ranked projection | Hayır |
| Seçilmiş aktif sipariş detayı | Evet, sınırlı ilişki önizlemeli entity detayı | Sadece açık eski-kayıt detay yolu tasarlarsan |
| Eski arşiv/geçmiş sayfası | Bilinçli ısıtılmadıysa hayır | Evet, sınırlandırılmış SQL sorgusu |
| Export/raporlama işi | Genelde hayır | Evet, batch/raporlama yolu |
| Geçiş ısıtma/backfill | Seçilen aktif veri seti Redis’e yazılır | Kaynak satırlar PostgreSQL’den okunur |

## Bu Örnekteki Gerçek Hayat Senaryoları

| Senaryo | Endpoint | CacheDB şekli | Neden bu şekil? |
|---|---|---|---|
| Müşteri sipariş geçmişini açar | `GET /api/customers/{id}/orders?limit=20` | `OrderSummary` projection sorgusu | Büyüyen liste, küçük veri gövdesi, sabit sıralama |
| Kullanıcı tek sipariş açar | `GET /api/orders/{id}?linePreview=5` | Entity detay ve sınırlı relation önizlemesi | Detay daha fazla veri ister, fakat yine de tüm satırları zorla yüklemez |
| Operasyon yüksek değerli siparişlere bakar | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Ranked projection sorgusu | Global sıralı ekran tam entity taramamalı |
| Kullanıcı eski geçmişi açar | `GET /api/orders/archive?customerId=1&limit=20` | `OrderSummary` dönen doğrudan PostgreSQL sorgusu | Arşiv okuması özellikle istenmedikçe Redis’i kirletmemeli |
| Kategoriye göre ürün listesi | `GET /api/products/active?category=electronics&limit=20` | Named entity query | Küçük ve sınırlı katalog yolu entity query için uygundur |
| Destek kuyruğu | `GET /api/tickets/open?limit=25` | Durum indeksli named entity query | Operasyon kuyruğu sınırlı ve filtrelidir |
| Ticaret paneli | `GET /api/dashboard/commerce?limit=25` | Projection ve destek talebi entity sorgusu | Panel küçük, önceden şekillenmiş okuma modellerini birleştirir |
| Müşteri oluşturma | `POST /api/customers` | Entity save | Redis-first yazma, SQL’e arka plan yazma |
| Sipariş oluşturma | `POST /api/orders` | FK hazırlık kontrolüyle entity save | Child kayıt, parent SQL’de kalıcı olana kadar bekler |
| Sipariş durum güncelleme | `PATCH /api/orders/{id}/status` | Entity oku, nesneyi değiştir, kaydet | Partial update açık tam entity kaydı olarak uygulanır |
| Sipariş silme | `DELETE /api/orders/{id}` | Repository delete | Aktif cache kaydını kaldırır ve kalıcı delete işini sıraya alır |

## İlk Gün Kullanımı ve Yük Büyüdükçe Yapılacaklar

| Aşama | Veri şekli | Yapılacak iş |
|---|---|---|
| İlk gün yerel demo | 20 müşteri, müşteri başına 40 sipariş, sipariş başına 4 satır | Veri üretmeyi çalıştır, API cevaplarını incele, yönetim ekranını aç, generated binding mantığını öğren |
| İlk staging denemesi | Binlerce müşteri, gerçeğe yakın sipariş dağılımı | API limitlerini projection penceresinin altında tut, SQL indekslerini doğrula, projection gecikmesini izle |
| Trafik artışı | Çok sayıda müşteri sürekli sipariş listesi açar | Redis `maxmemory` değerini artır, `hotEntityLimit` değerini bellek bütçesine göre ayarla, listeyi projection’da tut |
| Büyük müşteri yayılımı | Bazı müşterilerde binlerce sipariş oluşur | `Customer -> tüm Orders` yükleme; `OrderSummary` zaman çizelgesi ve açık sipariş detayı kullan |
| Panel büyümesi | Global sıralı ve KPI ekranları artar | Tam entity tekrar kullanmak yerine okuma yoluna özel projection ekle |
| Çok podlu çalışma | Birden fazla uygulama container’ı çalışır | Pod’a özel consumer name ve leader lease ayarlarını açık tut |

## Tuning Rehberi

Önce örnekteki ayarlarla başla, sonra ölçüme göre değiştir:

| Sinyal | Nereden bakılır? | Aksiyon |
|---|---|---|
| Redis belleği hızlı büyüyor | Yönetim ekranı, Redis `INFO memory`, `/api/tuning` | Aktif pencereyi daralt, aktif veri politikasını sıkılaştır, projection veri gövdesini küçült |
| Sipariş listesi yavaş | API gecikmesi ve okuma yolu etiketi | Yolun entity fallback değil `projection:order-summary` kullandığını doğrula |
| SQL okuma yükü artıyor | SQL metrikleri, yavaş sorgu kayıtları | Tekrarlanan liste ekranlarını projection’a taşı, okuma yolu indekslerini ekle |
| Write-behind backlog büyüyor | Yönetim ekranındaki write-behind bölümü | Worker/batch ayarını dikkatli artır, SQL lock durumunu incele, backpressure uygula |
| Projection gecikmesi büyüyor | Yönetim ekranındaki projection telemetrisi | Yenileme baskısını azalt veya projection’ları okuma yolu bazında ayır |
| Yanıt veri gövdesi büyüyor | API yanıt boyutu | Controller limitini düşür, detay için ayrı takip çağrısı kullan |

Örneğin temel tuning kodu:

```java
CachePolicy.builder()
        .hotEntityLimit(5_000)
        .pageSize(100)
        .entityTtlSeconds(0)
        .pageTtlSeconds(120)
        .compositeHotPolicy(EntityHotPolicyCompositeOperator.ANY, List.of(
                EntityHotPolicy.timeWindow("order_date", 90L * 24L * 60L * 60L),
                EntityHotPolicy.stateWindow("status", List.of("ACTIVE", "NEW", "PAID", "PICKING", "OPEN", "PENDING")),
                EntityHotPolicy.stateWindow("active_status", List.of("ACTIVE"))
        ))
        .build()
```

Bu politika şunu söyler: kayıt son 90 günlük iş penceresindeyse veya operasyonel olarak aktifse aktif veri setinde kalabilir. Bu yaklaşım “en son okunan neyse onu cache’le” davranışından daha gerçekçidir.

## SampleCacheDbTuningConfig Referansı

`SampleCacheDbTuningConfig`, bu örneğin tüm CacheDB tuning ayarlarını topladığı sınıftır. Küçük görünür, fakat her satırı Redis belleği, SQL yazma baskısı, okuma limiti veya production güvenliği üzerinde etkilidir.

### ResourceLimits

| Parametre | Örnek değer | Anlamı | Ne zaman değiştirilir? |
|---|---:|---|---|
| `defaultCachePolicy` | özel `CachePolicy` | Entity özelinde başka policy verilmemişse Redis kabul, Redis’te kalma, TTL ve sayfa davranışını belirleyen varsayılan politikadır. | Önce okuma yolu sözleşmelerini ve Redis bellek bütçesini netleştir; sonra değiştir. |
| `maxRegisteredEntities` | `64` | Bu CacheDB runtime içinde izin verilen maksimum entity metadata kayıt sayısıdır. Yanlış package scan veya beklenenden büyük model yüzeyini erken yakalar. | Uygulamada gerçekten daha fazla CacheDB entity varsa artır. Kontrolsüz taramayı saklamak için artırma. |
| `maxColumnsPerOperation` | `64` | Tek generated operasyonun işleyebileceği maksimum kolon sayısıdır. Çok geniş entity ve beklenmeyen veri gövdesi büyümesine karşı koruma sağlar. | Sadece ölçülmüş, bilinçli geniş entity için artır. Şişen tabloları mümkünse projection veya ayrı okuma modeliyle sadeleştir. |

### Varsayılan CachePolicy

| Parametre | Örnek değer | Anlamı | Production önerisi |
|---|---:|---|---|
| `hotEntityLimit` | `5_000` | Varsayılan policy için Redis’te tutulabilecek aktif tam entity penceresinin kaba sınırıdır. | Ortalama entity boyutu + indeks maliyeti x beklenen aktif satır hesabıyla belirle. Büyük liste ekranları için bunu büyütme; projection kullan. |
| `pageSize` | `100` | Çağıran taraf daha küçük limit vermediyse kullanılan varsayılan sayfa/sorgu boyutudur. | Gerçek UI sayfa boyutuna yakın tut. Bir ekran sürekli daha fazlasını istiyorsa projection yolu tasarla. |
| `entityTtlSeconds` | `0` | Tam entity key’leri TTL ile düşmez. Redis’te kalma davranışı süre dolmasına değil policy’ye bağlıdır. | Kalıcı iş entity’leri için doğru başlangıçtır. Pozitif TTL’i sadece yeniden üretilebilir geçici veride kullan. |
| `pageTtlSeconds` | `120` | Cache’lenen sayfa/sorgu sonucunun TTL değeridir; bu örnekte iki dakikadır. | Kısa tut. Sayfa sıralaması bayatlayabilir; projection satırı sayfa cache’inden daha uzun yaşayabilir. |
| `compositeHotPolicy(ANY, ...)` | `ANY` | Alt policy’lerden biri eşleşirse kayıt Redis’e kabul edilebilir. Bu örnekte yakın tarihli sipariş OR operasyonel durum OR aktif ürün/durum alanı kullanılır. | Operasyonel sistemlerde pratik bir başlangıçtır ama fazla veri kabul edebilir. `ALL` gibi daha sıkı seçimleri staging bellek ölçümüyle doğrula. |
| `timeWindow("order_date", 90 gün)` | `90 gün` | `order_date` son 90 gün içindeyse kayıt Redis’te kalabilir. | İş penceresine göre ayarla. Örneğin faturalama 13 ay isteyebilir; destek kuyruğu birkaç gün isteyebilir. |
| `stateWindow("status", ACTIVE/NEW/PAID/PICKING/OPEN/PENDING)` | aktif operasyon durumları | Kayıt operasyonel olarak aktif durumdaysa zaman penceresi dışında olsa bile Redis’e girebilir. | Durum listesini küçük tut. Geniş durum kümeleri eski veriyi gereğinden fazla Redis’e alabilir. |
| `stateWindow("active_status", ACTIVE)` | `ACTIVE` | Ürün/katalog tarzı kayıtlarda aktif kayıtların Redis’te kalmasına izin verir. | Küçük aktif kataloglarda kullan. Arşiv durumlarını bu alana koyma. |

### ReadShapeGuardrailConfig

| Parametre | Örnek değer | Anlamı | Production önerisi |
|---|---:|---|---|
| `enabled` | `true` | Okuma şekli korumasını açar. Büyük ve riskli sorgular sessizce pahalı çalışmak yerine reddedilir. | Production’da açık kalmalı. Kapatmak, yanlış liste çağrılarını runtime riskine çevirir. |
| `maxEntityQueryLimit` | `250` | Tam entity sorgu yüzeyleri için maksimum satır limitidir. | Düşük tut. Entity satırları büyüktür ve relation yükünü tetikleyebilir. |
| `maxProjectionQueryLimit` | `1_000` | Projection sorgu yüzeyleri için maksimum satır limitidir. | Entity limitinden yüksek olabilir; yine de sınırsız bırakılmamalıdır. |
| `hotSetHeadroom` | `10` | İstenen pencereyle aktif veri seti sınırı arasında güvenlik payı bırakır. | Sorgular sık sık aktif veri seti sınırına dayanıyorsa artır. |

### RedisGuardrailConfig

| Parametre | Örnek değer | Anlamı | Production önerisi |
|---|---:|---|---|
| `enabled` | `true` | Redis bellek baskısı ve runtime güvenlik kontrollerini açar. | Açık kalmalı. Redis sınırsız heap değildir. |
| `producerBackpressureEnabled` | `true` | Redis veya write-behind baskısı arttığında producer tarafını yavaşlatır. | Ani yazma yüklerinde sistemi büyüyen kuyrukla boğmamak için açık tut. |
| `usedMemoryWarnMaxmemoryPercent` | `75` | Redis `used_memory / maxmemory` oranı için uyarı eşiğidir. | Kritik seviyeye gelmeden kabul politikasını ve aktif pencereyi gözden geçirmek için kullan. |
| `usedMemoryCriticalMaxmemoryPercent` | `88` | Kritik Redis bellek baskısı eşiğidir. | Bu seviyede aktif pencereyi büyütme; büyük key prefix’lerini, projection/index maliyetini ve yazma kuyruğunu incele. |
| `expectedMaxmemoryPolicy` | `noeviction` | Beklenen Redis eviction policy değeridir. CacheDB hangi verinin tutulacağına kendisi karar vermek ister. | `noeviction` kullan. Redis’in rastgele key düşürmesi projection/index tutarlılığını bozabilir. |
| `warnOnMissingMaxmemory` | `true` | Redis’te `maxmemory` yoksa uyarı üretir. | Açık kalmalı. Limitsiz Redis local demo için kolaydır ama production davranışı için güvenli değildir. |
| `writeBehindBacklogWarnThreshold` | `500` | Kalıcı yazma kuyruğu için uyarı eşiğidir. | Daha sıkı durability penceresi istiyorsan düşür; ancak önce SQL throughput ölç. |
| `writeBehindBacklogCriticalThreshold` | `2_000` | Kalıcı yazma kuyruğu için kritik eşiktir. | Bu seviyede SQL lock, pool saturation, batch size ve Redis belleği birlikte incelenmelidir. |
| `automaticRuntimeProfileSwitchingEnabled` | `true` | Guardrail baskı gördüğünde CacheDB’nin runtime davranışını daha korumacı profile alabilmesine izin verir. | Ayrı bir operasyon kontrol katmanın yoksa açık tut. |

### WriteBehindConfig

| Parametre | Örnek değer | Anlamı | Production önerisi |
|---|---:|---|---|
| `workerThreads` | `2` | Redis üzerinden kabul edilen yazıları SQL’e taşıyan arka plan worker sayısıdır. | Sadece SQL tarafında boş connection/CPU varsa ve backlog büyüyorsa artır. |
| `batchSize` | `128` | Bir flush döngüsünde gruplanması hedeflenen yazı sayısıdır. | Throughput için artırmadan önce SQL lock süresi ve flush latency ölç. |
| `maxFlushBatchSize` | `128` | Tek flush batch’i için üst sınırdır. | Load test daha büyük batch’in faydalı olduğunu göstermedikçe `batchSize` ile hizalı tut. |
| `tableAwareBatchingEnabled` | `true` | Yazıları tabloya göre gruplayarak SQL batch davranışını iyileştirir. | Çoğu production workload’unda açık kalmalı. |
| `batchFlushEnabled` | `true` | Tek tek yazmak yerine batch flush davranışını açar. | Provider’a özel bir problem debug etmiyorsan açık tut. |
| `coalescingEnabled` | `true` | Aynı entity üzerindeki tekrar eden update’leri güvenli olduğunda birleştirir. | Update ağırlıklı workload için açık tut. Her ara durumun kalıcı görünmesi gerekiyorsa kapat. |
| `maxFlushRetries` | `5` | Geçici SQL flush hataları için retry sayısıdır. | Sınırlı retry iyidir; kalıcı hatalar sonsuz retry ile saklanmamalıdır. |
| `retryBackoffMillis` | `500` | Flush retry denemeleri arasındaki bekleme süresidir. | Çok düşük değer retry fırtınası yaratır; çok yüksek değer durability lag üretir. SQL failover davranışına göre ayarla. |

### Bu README’de Geçen Ama Bu Sınıfta Açık Set Edilmeyen Ayarlar

| Parametre | Neden yine anlatılıyor? |
|---|---|
| `lruEvictionEnabled` | `CachePolicy` tarafından desteklenir; bu sınıfta açık set edilmediği için framework varsayılanı geçerlidir. Production ekipleri count-window davranışını sık tune ettiği için dokümanda tutulur. |
| `admitOnWrite`, `admitOnRead`, `admitOnWarm`, `evictWhenRejected` | `EntityHotPolicy` tarafından desteklenir; bu örnek helper constructor’ların varsayılan kabul davranışını kullanır. Migration, arşiv ve warm-up akışlarında sık ihtiyaç duyulduğu için dokümanda yer alır. |
| `rejectEntityQueryOverLimit`, `rejectProjectionQueryOverLimit` | Read guardrail tarafında desteklenir; bu örnek limitleri set eder ve varsayılan reddetme davranışına güvenir. |

## Gerçek Hayat Ayar Senaryoları

Bu senaryolar evrensel default değildir; ilk staging denemesi için başlangıç profilleridir. En yakın senaryodan başla, sonra staging verisiyle Redis bellek kullanımı, SQL gecikmesi, projection gecikmesi ve write-behind backlog değerlerini ölç.

| Senaryo | Entity ve okuma modeli | Önerilen değerler | Neden bu değerler? |
|---|---|---|---|
| E-ticaret müşteri sipariş zaman çizelgesi | `CustomerEntity`, `OrderEntity`, `OrderLineEntity`, `OrderSummary` projection | `hotEntityLimit=100_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=60`, `compositeHotPolicy=ANY`, `timeWindow("order_date", 90 gün)`, `stateWindow("status", NEW/PAID/PICKING/OPEN/PENDING)`, `maxEntityQueryLimit=250`, `maxProjectionQueryLimit=1_000`, Redis uyarı/kritik `75/88`, `workerThreads=4`, `batchSize=256`, `maxFlushBatchSize=256` | Liste ekranı Redis projection üzerinden `OrderSummary` okumalıdır; seçilen sipariş detayı ise sınırlı satır önizlemesiyle tam entity okuyabilir. 90 günlük pencere güncel ticaret trafiğini aktif veri setinde tutar; aktif durumlar daha eski ama operasyonel siparişleri de Redis’te tutar. |
| Lojistik gönderi takibi | `ShipmentEntity`, `ShipmentEventEntity`, `RouteStopEntity`, `ShipmentTimelineSummary` projection | `hotEntityLimit=150_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=30`, `compositeHotPolicy=ANY`, `timeWindow("updated_at", 14 gün)`, `stateWindow("shipment_status", IN_TRANSIT/OUT_FOR_DELIVERY/DELAYED/EXCEPTION)`, `maxEntityQueryLimit=200`, `maxProjectionQueryLimit=1_000`, Redis uyarı/kritik `70/85`, `workerThreads=4`, `batchSize=256`, `maxFlushRetries=8`, `retryBackoffMillis=1_000` | Lojistik verisi sık değişir ve kullanıcılar aynı aktif gönderileri tekrar tekrar açar. Aktif ve problemli gönderiler Redis’te kalır, olay zaman çizelgesi projection olur, retry/backoff ise geçici veritabanı veya ağ baskısını daha güvenli karşılar. |
| Raporlama ve denetim arşivi | `ReportJobEntity`, `AuditEventEntity`, `LedgerEntryEntity`, `ReportRunSummary` projection | `hotEntityLimit=5_000`, `pageSize=50`, `entityTtlSeconds=0`, `pageTtlSeconds=30`, `compositeHotPolicy=ANY`, `timeWindow("created_at", 1 gün)`, `stateWindow("status", QUEUED/RUNNING/FAILED)`, `maxEntityQueryLimit=100`, `maxProjectionQueryLimit=500`, Redis uyarı/kritik `70/80`, `workerThreads=2`, `batchSize=64`, arşiv policy özelleştirilecekse `admitOnRead=false` | Büyük rapor ve export okumaları SQL öncelikli olmalıdır. Redis sadece canlı rapor işleri ve küçük özetleri tutmalı; eski audit ve ledger geçmişi PostgreSQL’de kalmalı ve açık SQL/raporlama yolu üzerinden okunmalıdır. |
| Destek operasyon kuyruğu | `SupportTicketEntity`, `TicketMessageEntity`, `CustomerEntity`, `OpenTicketSummary` projection | `hotEntityLimit=50_000`, `pageSize=50`, `entityTtlSeconds=0`, `pageTtlSeconds=20`, `compositeHotPolicy=ANY`, `timeWindow("updated_at", 30 gün)`, `stateWindow("status", OPEN/PENDING/ESCALATED/SLA_BREACH)`, `maxEntityQueryLimit=200`, `maxProjectionQueryLimit=1_000`, Redis uyarı/kritik `75/88`, `workerThreads=3`, `batchSize=128`, `coalescingEnabled=true` | Operasyon ekipleri aynı açık destek taleplerini ve kuyrukları defalarca açar. Açık ve eskale destek talepleri Redis’te kalır, kuyruk satırları küçük projection olur, tüm mesaj geçmişi sadece detay ekranında yüklenir. |
| Ürün katalog ve stok uygunluğu | `ProductEntity`, `WarehouseStockEntity`, `InventoryReservationEntity`, `ProductAvailabilitySummary` projection | `hotEntityLimit=25_000`, `pageSize=100`, `entityTtlSeconds=0`, `pageTtlSeconds=15`, `compositeHotPolicy=ANY`, `stateWindow("active_status", ACTIVE)`, `stateWindow("stock_status", IN_STOCK/LOW_STOCK)`, `timeWindow("updated_at", 7 gün)`, `maxEntityQueryLimit=250`, `maxProjectionQueryLimit=1_000`, Redis uyarı/kritik `70/85`, `workerThreads=3`, `batchSize=256`, `coalescingEnabled=true` | Kategori ve ürün liste ekranları hızlı uygunluk bilgisi ister, fakat stok güncellemeleri gürültülü olabilir. Aktif ürünler ve düşük stoklu ürünler Redis’te kalır, liste ekranları projection okur, coalescing aynı ürün için tekrarlanan stok update’lerini azaltır. |

BEST: en yakın senaryodan başla, staging warm-up çalıştır, sonra tahmini Redis belleğini gerçek `MEMORY USAGE` değerleriyle key prefix bazında karşılaştır. ANTI-PATTERN: en büyük `hotEntityLimit` değerini her servise kopyalayıp Redis’in modeli taşımasını beklemek.

## Aktif Veri Setinin Dışında Ne Olur?

CacheDB, Redis’te bulunmayan her entity için otomatik PostgreSQL taraması yapan dinamik ORM gibi davranmaz. Entity repository okumaları, sınırlandırılmış aktif veri seti okumalarıdır. PostgreSQL kalıcı veri kaynağı olmaya devam eder; ancak arşiv, export, eski geçmiş ve özellikle soğuk detay ekranları açık SQL yolları veya kontrollü warm/backfill akışıyla tasarlanmalıdır.

Önemli istisna `findPage(PageWindow)` davranışıdır: read-through açıksa ve `EntityPageLoader` kayıtlıysa sayfa cache'te yokken loader PostgreSQL okuyabilir. Bu davranış, her entity query için genel fallback varmış gibi yorumlanmamalıdır.

| Operasyon | Kayıt aktif veri setindeyse | Kayıt aktif veri setinin dışındaysa | PostgreSQL davranışı | Redis/cache davranışı |
|---|---|---|---|---|
| `findById(id)` | Redis’teki entity döner ve istenen fetch preset uygulanır. | Boş döner; örnek controller’lar bunu çoğunlukla `404` olarak gösterir. | Repository okuması PostgreSQL’e gitmez. | Eksik, tombstone yazılmış veya policy tarafından reddedilmiş entity servis edilmez. `evictWhenRejected=true` ise eski aktif-set kaydı temizlenebilir. |
| `query(QuerySpec)` | Redis indekslerini ve Redis veri gövdelerini kullanır; yalnızca policy tarafından kabul edilmiş eşleşen kayıtları döner. | Daha az satır veya boş liste döner. | Eksik satırı tamamlamak için PostgreSQL taraması yapılmaz. | Sadece Redis’te indekslenmiş ve policy tarafından kabul edilmiş satırlar görünebilir. |
| `findPage(PageWindow)` | Sayfa cache’te varsa doğrudan döner. | Read-through ve `EntityPageLoader` tanımlıysa loader PostgreSQL okuyabilir; yoksa page-cache ayarına göre boş döner veya hata verir. | Sadece açık tanımlanmış page loader PostgreSQL’e gidebilir. | Yüklenen sayfa, guardrail izin verirse cache’e alınır. |
| Projection sorgusu | Redis projection indekslerinden küçük özet satırları döner. | Sadece projection penceresinde bulunan satırlar döner. | Projection sorgusu PostgreSQL taraması yapmaz. | Zaman çizelgesi, panel ve top-N ekranları için doğru okuma şeklidir. |
| Açık arşiv/raporlama yolu | Normal ilk ekran yüklemesi için genelde gerekmez. | Eski geçmiş, export, audit ve soğuk detay ekranlarında kullanılmalıdır. | PostgreSQL indeksli ve sınırlı SQL ile okunur. | Bu yol özellikle warm etmiyorsa Redis’i değiştirmemelidir. |
| `save(entity)` | Kalıcı yazı kuyruğa alınır; Redis state ve indeksler güncellenir. | Kalıcı yazı yine kuyruğa alınır, fakat tam entity Redis tarafından reddedilebilir veya Redis’ten düşürülebilir. | Write-behind değişikliği PostgreSQL’e yazar. | Entity’nin Redis’te kalıp kalmayacağına hot policy karar verir. Yeni yazılmış soğuk kayıt Redis entity okumasında görünmeyebilir. |
| `deleteById(id)` | Redis state temizlenir veya tombstone yazılır; kalıcı delete kuyruğa alınır. | Uygulama delete verdiyse kalıcı delete yine write-behind akışına girer. | PostgreSQL delete write-behind ile flush edilir. | Sonraki Redis okumaları boş döner ve projection payload’ları temizlenir. |
| Warm/backfill | Policy tarafından kabul edilen satırlar Redis’e hydrate edilir. | Reddedilen satırlar yalnızca PostgreSQL’de kalır. | Kaynak satırlar PostgreSQL’den okunur. | `admitOnWarm` ve `hotPolicy`, Redis’e kabul kararını verir. |

| Senaryo | Ayar şekli | Aktif veri seti dışından okuma | Aktif veri seti dışından yazma | Doğru okuma yolu tasarımı |
|---|---|---|---|---|
| E-ticaret müşteri sipariş zaman çizelgesi | Son 90 gün OR `NEW/PAID/PICKING/OPEN/PENDING`, `OrderSummary` projection, `maxProjectionQueryLimit=1_000`. | 2 yıl önce tamamlanmış sipariş Redis zaman çizelgesi sorgusunda beklenmez. Sipariş Redis’e kabul edilmemişse `findById` boş dönebilir. | Eski tamamlanmış siparişe düzeltme yazılırsa kayıt PostgreSQL’e kalıcı yazılır; Redis tam entity’yi reddedebilir veya düşürebilir. | Zaman çizelgesi `OrderSummary` projection okur; arşiv/detay geçmişi `WHERE customer_id=? ORDER BY order_date DESC LIMIT ?` gibi indeksli PostgreSQL yolundan okunur. |
| Lojistik gönderi takibi | Son 14 gün OR `IN_TRANSIT/OUT_FOR_DELIVERY/DELAYED/EXCEPTION`, gönderi zaman çizelgesi projection. | Geçen ay teslim edilmiş gönderinin Redis takip listesinde olması beklenmez. | Geç durum düzeltmesi PostgreSQL’e kalıcı yazılır; Redis yalnızca yeni durum/zaman policy’ye uyuyorsa kabul eder. | Aktif takip ekranı projection okur; teslim edilmiş gönderi geçmişi PostgreSQL arşiv yolundan okunur. |
| Raporlama ve denetim arşivi | Sadece canlı rapor işleri, küçük `ReportRunSummary`, arşivlerde çoğunlukla `admitOnRead=false`. | Eski audit ve ledger satırları tek seferlik okuma yüzünden Redis’e taşınmaz. | Yeni audit satırları kalıcıdır; eski arşiv satırları Redis’i kirletmemelidir. | Panel canlı özetleri Redis’ten okur; audit/export işleri rapora özel indekslerle PostgreSQL’den okunur. |
| Destek operasyon kuyruğu | Son 30 gün OR `OPEN/PENDING/ESCALATED/SLA_BREACH`, `OpenTicketSummary` projection. | 30 günden eski kapalı destek talebi entity repository okumasında dönmeyebilir. | Talep yeniden açılırsa durum değişir; write sonrası policy bu kaydı Redis’e tekrar kabul edebilir. | Kuyruk ekranı projection okur; kapalı talep araması PostgreSQL geçmiş yolundan, aktif detay ise kabul edilmişse Redis’ten okunur. |
| Ürün katalog ve stok uygunluğu | `ACTIVE` ürünler OR `IN_STOCK/LOW_STOCK` OR son 7 gün içinde güncellenenler, availability projection. | Yayından kalkmış SKU public katalog projection’ında görünmez. | Yayından kalkmış ürünlerde admin değişiklikleri kalıcıdır; Redis yalnızca policy kabul ederse tutar. | Public kategori sayfaları projection okur; admin soğuk detay ve toplu katalog export PostgreSQL’den okunur. |

BEST: aktif kullanıcı deneyimi için bir okuma yolu, soğuk geçmiş için ayrı ve açık bir okuma yolu tasarla. ANTI-PATTERN: geniş entity query Redis’te bulamayınca PostgreSQL’i tarasın, Redis’i doldursun ve buna rağmen bellek sınırı korunsun diye beklemek.

## CachePolicy Parametreleri Nasıl Ayarlanır?

Cache ayarını şu sırayla yap. İlk refleks Redis belleğini büyütmek olmamalıdır.

1. Okuma yolu sözleşmesini belirle: çağrı limiti, sıralama, detay/önizleme ayrımı ve projection gerekip gerekmediği.
2. Redis bütçesini belirle: `maxmemory`, `maxmemory-policy=noeviction`, uyarı eşiği ve kritik eşik.
3. Redis’e kabul kuralını belirle: hangi kayıtlar aktif veri setine girebilir?
4. Redis’te kalma kuralını belirle: kayıt sayısı, zaman penceresi, durum penceresi ve TTL davranışı.
5. Yazma baskısını belirle: write-behind worker sayısı, batch boyutu, retry ve backlog eşikleri.

### CachePolicy Parametreleri

| Parametre | Neyi kontrol eder? | Ne zaman artırılır? | Ne zaman azaltılır? | Production önerisi |
|---|---|---|---|---|
| `hotEntityLimit` | Varsayılan `CachePolicy` için aktif entity penceresini sınırlar. Redis büyümesine karşı ilk kaba sınırdır. | Redis’te boşluk varsa ve sınırlı aktif okuma yollarında Redis’te bulunamama oranı yüksekse. | Redis belleği hızlı büyüyorsa veya tek okuma yolu belleği domine ediyorsa. | Bellek bütçesinden hesapla: ortalama entity veri gövdesi + indeks maliyeti x beklenen aktif satır. Projection penceresi yerine kullanılmamalıdır. |
| `pageSize` | Çağıran taraf daha sıkı limit vermediğinde kullanılan varsayılan sayfa boyutudur. | Normal ekranlar daha büyük ilk sayfa istiyorsa ve veri gövdesi küçükse. | API yanıtı büyüyorsa veya UI küçük bir ilk sayfa gösteriyorsa. | Gerçek UI sayfa boyutuna yakın tut; çoğu ekranda `50-100` yeterlidir. Büyük export işleri entity page cache kullanmamalıdır. |
| `lruEvictionEnabled` | Count window aşıldığında eski aktif kayıtların dışarı itilmesine izin verir. | Geniş bir çalışma seti varsa ve normal cache yenilenmesi kabul edilebiliyorsa. | Katı aktif veri politikası istiyorsan ve sessiz veri değişimi istemiyorsan. | Çoğu online iş yükünde açık kalabilir; yine de Redis `maxmemory` ve okuma yolu limitleri zorunludur. |
| `entityTtlSeconds` | Tam entity kayıtları için opsiyonel TTL’dir. `0`, entity’nin TTL ile düşmeyeceği anlamına gelir. | Veri geçiciyse, yeniden üretilebiliyorsa veya eski-kayıt yükleme güvenliyse. | İş detayı stabil kalmalıysa ve aktif veri seti davranışı policy ile yönetilecekse. | Kalıcı iş entity’lerinde genelde `0` kullan; TTL’i geçici view, session veya kısa ömürlü operasyon kayıtlarında kullan. |
| `pageTtlSeconds` | Cache’lenen sayfa/sorgu sonuçlarının TTL değeridir. | Liste sonuçları tekrar kullanılıyorsa ve kısa süreli bayatlık kabul edilebiliyorsa. | Liste çok sık değişiyorsa veya eski sıralama kullanıcıya görünüyorsa. | Kısa tut; genelde `30-120s`. Projection satırları sayfa cache’inden daha uzun yaşayabilir. |
| `hotPolicy` | Bir satırın Redis’e girip giremeyeceğini belirleyen kabul kuralıdır. | Basit LRU yerine iş kuralına göre cache istiyorsan. | Kural çok fazla kayıt kabul ediyorsa veya önemli okumaları kaçırıyorsa. | Composite policy tercih et: yakın zaman OR aktif durum OR özel iş kuralı. |

### Aktif Veri Politikası Modları

| Mod | Ne zaman kullanılır? | Örnek | Risk |
|---|---|---|---|
| `COUNT_WINDOW` | Basit ve sayıyla sınırlı aktif veri seti yeterliyse. | Son `N` ürün veya kategori kaydını tutmak. | İş önemini bilmez; gürültülü okuma yolları faydalı kayıtları dışarı itebilir. |
| `TIME_WINDOW` | Gerçek iş kuralı yakın zamansa. | `order_date` ile son 90 gün siparişlerini tutmak. | Eski ama operasyonel olarak aktif kayıtlar state policy ile birleşmezse dışarıda kalabilir. |
| `STATE_WINDOW` | Kaydın durumu aktif veri setine girip girmeyeceğini belirliyorsa. | `OPEN`, `PENDING`, `PAID`, `ACTIVE` kayıtlarını tutmak. | Büyük durum kümeleri çok fazla veri kabul edebilir. Count veya time sınırıyla birlikte düşünülmelidir. |
| `CUSTOM_PREDICATE` | Aktif veri setine girme kararı domain mantığına bağlıysa. | VIP müşteri siparişlerini veya tenant’a özel premium yolları kabul etmek. | Okuması ve işletmesi daha zordur; gerçek veri dağılımıyla test edilmelidir. |
| `COMPOSITE` | Gerçek sistemde birden fazla kural gerekiyorsa. | Son 90 gün OR aktif durum OR aktif ürün. | `ANY` fazla veri kabul edebilir; `ALL` fazla veri reddedebilir. Staging bellek ölçümüyle doğrulanmalıdır. |

Örnek projede `ANY` kullanılır; çünkü bir sipariş ya yakın tarihli olduğu için ya da operasyonel olarak aktif olduğu için önemli olabilir. Daha sıkı bellek profilinde `ALL` ancak kayıt tüm child kuralları sağlamalıysa seçilmelidir.

### Admission Source Bayrakları

`EntityHotPolicy` kaydın hangi kaynaktan Redis’e kabul edilebileceğini de yönetir:

| Bayrak | Anlamı | Ne zaman değiştirilir? |
|---|---|---|
| `admitOnWrite` | Yeni yazılan kayıt Redis’e alınabilir. | Sadece yazma ağırlıklı ama nadir okunan verinin Redis’i kirletmesini istemiyorsan kapat. |
| `admitOnRead` | Eski-kayıt okuması sonucu Redis’e alınabilir. | Arşiv yollarında tek seferlik okumaların aktif veri setine girmesini istemiyorsan kapat. |
| `admitOnWarm` | Isıtma/backfill işleri Redis’i doldurabilir. | Isıtma işi sadece SQL doğrulaması yapacaksa ve Redis’i değiştirmemeliyse kapat. |
| `evictWhenRejected` | Kayıt artık policy’ye uymuyorsa aktif setten düşürülebilir. | Policy geçişinde daha yumuşak davranış istiyorsan geçici olarak kapat. |

### Read Guardrail Parametreleri

Cache policy Redis’te neyin kalabileceğini belirler. Okuma koruma eşiği ise çağıranın ne isteyebileceğini sınırlar.

| Parametre | Production kullanımı |
|---|---|
| `maxEntityQueryLimit` | Düşük tutulmalıdır. Entity query tam nesne yükler ve relation işini tetikleyebilir. Normal ekranlarda `100-250` bandı uygundur. |
| `maxProjectionQueryLimit` | Projection veri gövdesi küçük olduğu için daha yüksek olabilir. Zaman çizelgesi ve panel pencerelerinde kullanılır. |
| `hotSetHeadroom` | İstenen pencereyle aktif veri seti sınırı arasında boşluk bırakır. Sorgular sık sık aktif pencere sınırına geliyorsa artırılır. |
| `rejectEntityQueryOverLimit` | Açık kalmalıdır. Okuma yolu daha çok satır istiyorsa global limiti artırmak yerine projection yolu tasarlanmalıdır. |
| `rejectProjectionQueryOverLimit` | Açık kalmalıdır. Sadece veri gövdesi boyutu ölçülmüş belirli projection yollarında artırılmalıdır. |

### Redis Guardrail Parametreleri

Redis sınırsız heap değildir; sınırlandırılmış çalışma zamanı bağımlılığıdır.

| Parametre | Production kullanımı |
|---|---|
| `usedMemoryWarnMaxmemoryPercent` | Cache büyümesini yavaşlatmak için ilk sinyaldir. Başlangıç için `%70-80` uygundur. |
| `usedMemoryCriticalMaxmemoryPercent` | Kritik baskı seviyesidir. Başlangıç için `%85-90` uygundur; üstünde read/write shedding alanına yaklaşılır. |
| `expectedMaxmemoryPolicy` | Bu modelde `noeviction` kullanılmalıdır. Neyin tutulacağına CacheDB karar vermeli; Redis rastgele gerekli key düşürmemelidir. |
| `producerBackpressureEnabled` | Açık kalmalıdır; Redis baskı altındayken producer tarafı yavaşlar. |
| `writeBehindBacklogWarnThreshold` | SQL flush gecikmesi kullanıcıya görünmeden önce alarm üretir. Daha sıkı consistency beklentisinde düşürülür. |
| `writeBehindBacklogCriticalThreshold` | Kritik backlog seviyesidir. Bu noktada SQL lock, pool saturation ve batch size incelenmelidir. |

### Write-Behind Parametreleri

Write-behind tuning, cache admission’dan ayrı düşünülmelidir. SQL yavaşlığını Redis’e daha az veri alarak çözmeye çalışma; Redis belleği de problemse ayrıca ele al.

| Parametre | Ne zaman artırılır? | Ne zaman azaltılır? |
|---|---|---|
| `workerThreads` | Backlog büyüyor ve SQL tarafında boş connection/CPU varsa. | SQL pool doluyorsa veya lock beklemeleri artıyorsa. |
| `batchSize` / `maxFlushBatchSize` | Backlog büyüyor ve SQL batch yazmayı iyi kaldırıyorsa. | Latency zıplıyorsa, lock artıyorsa veya transaction çok büyüyorsa. |
| `tableAwareBatchingEnabled` | Genelde açık kalır; yazıları tabloya göre gruplayarak flush davranışını iyileştirir. | Nadiren, sadece debugging veya provider’a özel problem varsa kapatılır. |
| `coalescingEnabled` | Genelde açık kalır; aynı entity üzerindeki tekrar eden update’leri birleştirebilir. | Her ara durumun kalıcı olarak görünmesi gerekiyorsa kapatılır. |
| `maxFlushRetries` / `retryBackoffMillis` | Deploy veya failover sırasında transient SQL hataları varsa. | Retry kalıcı hataları saklıyorsa ve DLQ büyüyorsa. |

### Pratik Profiller

| Profil | Ne zaman kullanılır? | Ayar yönü |
|---|---|---|
| Küçük admin uygulaması | Trafik düşük, listeler sınırlı | `hotEntityLimit=1_000`, `pageSize=50`, kısa page TTL, düşük sorgu limiti |
| Ticaret zaman çizelgesi ekranı | Müşteri sipariş geçmişi sürekli büyür | Projection zorunlu, `maxProjectionQueryLimit=1_000`, entity detay limiti `100-250`, 90 gün veya aktif durum politikası |
| Panel/KPI | Tekrarlanan global sıralı okumalar vardır | Ekrana özel projection, kısa page TTL, sıkı entity sorgu limiti, ranked projection alanları |
| Arşiv okuma | Eski kayıtlar nadiren okunur | `admitOnRead` kapatılabilir, entity TTL kısa veya `0`, okuma SQL eski-kayıt yolundan yapılır |
| Yüksek yazma trafiği | Yazılar ani yük dalgaları halinde gelir | Write-behind worker ve batch ayarlanır, Redis koruma eşikleri sıkı tutulur, backlog izlenir |

## Neden Projection Kullanılıyor?

Bir müşterinin sipariş sayısı zamanla binleri bulabilir. Liste ekranında `Customer -> tüm Orders -> tüm Lines` yüklemek production için doğru değildir. Bu örnekte liste ekranı `OrderSummary` üzerinden döner; kullanıcı tek bir siparişi seçtiğinde detay ayrıca yüklenir.

BEST:

- Müşteri sipariş listesi ve yüksek değerli sipariş listesi için `OrderSummary` kullan.
- API `limit` değerini projection penceresinin içinde tut.
- `OrderEntity` ve satır ilişkisini sadece detay ekranında yükle.
- PostgreSQL’i tam geçmiş için kalıcı kaynak olarak bırak.

ANTI-PATTERN:

- Tek yanıtta müşterinin tüm siparişlerini ve tüm satırlarını döndürmek.
- Sınırsız `findAll` benzeri çağrı açmak.
- Redis belleğini sınırsız kabul etmek.

## Ayar Noktaları

Örnek başlangıç ayarları `SampleCacheDbTuningConfig` içinde yer alır:

| Alan | Örnek ayar | Neden? |
|---|---|---|
| Aktif veri penceresi | Varsayılan `hotEntityLimit=25_000`, route profillerinde `150_000` seviyesine kadar | Redis büyümesini sınırlarken farklı gerçek hayat okuma sözleşmelerini gösterir |
| Entity TTL | `0` | Bu örnekte entity kayıtları TTL ile silinmez |
| Sayfa TTL | Varsayılan `90s`; katalog ve destek profillerinde daha kısa | Sayfa cache’i kısa süreli tutulur |
| Aktif veri politikası | yola göre değişen zaman/durum politikaları | Ticaret, katalog, destek, lojistik, raporlama ve audit için farklı kabul profilleri gösterir |
| Entity sorgu limiti | `250` | Büyük entity taramalarını engeller |
| Projection sorgu limiti | `1000` | Zaman çizelgesi penceresine izin verir, sınırsız okumayı engeller |
| Redis koruma eşikleri | uyarı %75, kritik %88 | Bellek baskısını erken görünür yapar |

Production’da Redis `maxmemory` mutlaka verilmelidir, `maxmemory-policy=noeviction` korunmalıdır, bağlantı havuzları hedef trafiğe göre ayarlanmalıdır ve yönetim ekranı gateway/auth arkasında tutulmalıdır.

## Genişletilmiş API Senaryoları

Örnek proje artık birden fazla production tarzı okuma/yazma şeklini gösterir. Postman’ı açmadan önce bu tabloya bakarsan hangi çağrının neyi kanıtladığı daha net olur.

| Senaryo grubu | Ana endpoint’ler | Ne gösterir? |
|---|---|---|
| Ticaret zaman çizelgesi | `/api/customers/{id}/orders`, `/api/orders/high-value`, `/api/orders/archive` | Projection-first sipariş listesi, açık detay yükleme ve tam geçmiş için SQL arşiv yolu |
| Katalog ve stok | `/api/products/active`, `/api/products/low-stock`, `/api/products/{id}/stock` | Ürün uygunluk projection’ı, durum bazlı aktif veri seti ve stok update kabul davranışı |
| Destek operasyonu | `/api/tickets/open`, `/api/tickets/{id}`, `/api/tickets/{id}/status` | Açık kuyruk Redis’ten okunur; yeniden açılan veya eskale edilen kayıt aktif sete döner |
| Lojistik takip | `/api/shipments/active`, `/api/shipments/exceptions`, `/api/shipments/{id}` | Gönderi özet projection’ı ve sınırlı olay önizlemesi |
| Raporlama ve audit | `/api/reports/jobs/live`, `/api/reports/audit/security`, `/api/reports/audit/archive` | Canlı rapor işleri Redis’te, audit/arşiv okumaları açık SQL yolunda kalır |
| Paneller ve tuning | `/api/dashboard/commerce`, `/api/dashboard/operations`, `/api/tuning/profiles` | Birden fazla projection’dan panel okuması ve route bazlı tuning profilleri |

## Postman

İçe aktarılacak dosya:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

Koleksiyon senaryoya göre gruplanmıştır: platform hazırlığı, ticaret, katalog/stok, destek, lojistik, raporlama/audit, paneller ve tuning profilleri.

## PostgreSQL Notları

Şema `src/main/resources/schema.sql` ile kurulur. Primary key, foreign key ve aktif okuma yolları için indeksler vardır:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_products(category, active_status, stock_status, updated_at DESC)`
- `sample_support_tickets(status, priority, updated_at DESC)`
- `sample_shipments(shipment_status, risk_score DESC, updated_at DESC)`
- `sample_shipments(customer_id, updated_at DESC, shipment_id DESC)`
- `sample_shipment_events(shipment_id, event_time DESC, event_id DESC)`
- `sample_report_jobs(status, updated_at DESC, report_job_id DESC)`
- `sample_audit_events(entity_name, entity_id, created_at DESC)`

Seed endpoint’i veriyi CacheDB üzerinden yazar ve alt kayıtları yazmadan önce üst kayıtların SQL tarafına düşmesini bekler. Bunun nedeni şemada foreign key bulunmasıdır.

`POST /api/orders`, indeksli tek bir SQL varlık kontrolü yapar. Ana müşteri henüz kalıcı değilse request thread’ini bekletmeden `Retry-After: 1` ile birlikte `409` döner; istemci kısa bir süre sonra tekrar denemelidir.

## Sorun Giderme

CacheDB dependency’leri çözülemiyorsa `pom.xml` içindeki paket repository erişimini kontrol et.

Seed endpoint’i yavaş görünüyorsa `GET /api/health/ready` ve yönetim ekranındaki arka plan yazma bölümünü kontrol et. Seed akışı foreign key hatası üretmemek için SQL satırlarının oluşmasını bekler.

Seed sonrası liste hemen boş dönerse birkaç saniye bekleyip tekrar dene. Projection yenileme arka planda çalışır.
