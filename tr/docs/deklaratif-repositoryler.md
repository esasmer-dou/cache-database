# Deklaratif Repository Kullanımı

English version: [../../docs/declarative-repositories.md](../../docs/declarative-repositories.md)

CacheDB uygulamalarında önerilen ana kullanım biçimi budur. Route sözleşmesini
bir interface üzerinde tanımlarsın. Annotation processor bu tanımı derleme
sırasında kontrol eder; reflection kullanmayan implementasyonu ve istenirse
Spring bean'ini üretir.

`GeneratedCacheModule`, geriye dönük uyumluluk ve düşük seviyeli işler için
korunur. Yeni uygulama kodunda başlangıç noktası çoğunlukla
`@CacheRepository` olmalıdır.

## 1. Provider Starter'ı Ekle

BOM'u bir kez ekle ve yalnızca bir SQL provider starter seç. `0.10.0`, kimlik
doğrulaması istemeyen CacheDB Maven deposunda değişmez paket olarak yayımlanır.

```xml
<properties>
    <cachedb.version>0.10.0</cachedb.version>
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
</dependencies>
```

SQL Server kullanacaksan `cachedb-spring-boot-starter-postgres` yerine
`cachedb-spring-boot-starter-mssql` ekle. İki provider starter'ı aynı anda
ekleme. Classpath'te tek provider varsa `cachedb.sql.provider=AUTO` onu seçer.
Birden fazla provider bulunursa sistem sessizce seçim yapmak yerine başlangıcı
durdurur.

Projede başka bir dependency zaten Spring `DataSource` oluşturuyorsa yalnızca
CacheDB için `spring-boot-starter-jdbc` ekleme. Seçtiğin veritabanının JDBC
driver'ı runtime dependency olarak bulunmalıdır.

`cachedb-processor` paketini
`maven-compiler-plugin.annotationProcessorPaths` altında tanımla. İki sample
projenin POM dosyalarında çalışır hâli bulunur.

## 2. Her Aggregate veya Route Grubu İçin Bir Repository Tanımla

```java
@CacheRepository(entity = OrderEntity.class)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @CacheLookup(idParameter = "orderId", relation = "lines",
            relationLimitParameter = "linePreview", maxRelationRows = 50)
    HotLookup<OrderEntity> detail(Long orderId, int linePreview);

    @HotRoute(
            value = "customer-order-timeline",
            population = HotRoute.Population.DECLARED_WARM,
            projection = OrderSummary.class,
            pageSize = 100,
            hotWindow = 1_000,
            memoryBudgetBytes = 16_777_216,
            coverageScopeParameter = "customerId"
    )
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId", parameter = "customerId"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE,
                            constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    HotWindow<OrderSummary> timeline(long customerId, WindowRequest window);

    @SourceRoute(value = "customer-order-archive", projection = OrderSummary.class,
            maxRows = 500, timeoutSeconds = 15)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "customerId", parameter = "customerId"),
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            },
            windowParameter = "window"
    )
    SourceWindow<OrderSummary> archive(long customerId, WindowRequest window);

    @WarmRoute(value = "warm-customer-order-timeline", from = "timeline",
            maxRows = 1_000, maxRowsParameter = "maxRows",
            coverageScopeParameter = "customerId", targetParameter = "target")
    CacheWarmPlan warmTimeline(long customerId, int maxRows, CacheWarmTarget target);
}
```

Processor; yanlış alan adını, uyumsuz parametre tipini, tekrarlanan route adını,
kullanılmayan parametreyi, güvenli olmayan limiti, hatalı warm scope'unu ve
desteklenmeyen abstract metodu derleme sırasında reddeder.

## 3. Dönüş Tiplerinin Anlamını Bil

| Dönüş tipi | Kullanılan katman | Sözleşme |
| --- | --- | --- |
| `HotLookup<T>` | Yalnızca Redis | `NOT_CACHED`, SQL satırının olmadığı anlamına gelmez |
| `HotWindow<T>` | Yalnızca Redis | Route kapsam bilgisini ve keyset cursor'ını taşır |
| `SourceWindow<T>` | Yalnızca SQL | Sınırlı kalıcı okuma yapar; veriyi kendiliğinden Redis'e koymaz |
| `WriteReceipt<T, ID>` | Redis ve write-behind | Kabul edilen sürümü ve kalıcılık durumunu gösterir |
| `CacheWarmPlan` | Çalıştırılana kadar I/O yapmaz | Tanımlanan route sorgusunu warm için yeniden kullanır |

`HotLookup.NOT_CACHED` sonucunu HTTP 404'e çevirmemelisin. Bu sonuç yalnızca
kaydın Redis'te bulunmadığını söyler. Açık bir SQL detay route'u kullan, gereken
scope'u warm et veya verinin hızlı erişim alanında olmadığını belirten bir cevap
dön. Tombstone bilinen bir silmeyi gösterebilir; cache miss kalıcı yokluğu
kanıtlamaz.

```java
OrderEntity order = orders.detail(id, 20).orElseThrow(status -> switch (status) {
    case TOMBSTONED -> new OrderNotFoundException(id);
    case NOT_CACHED, OUTSIDE_HOT_POLICY -> new HotDataUnavailableException(id, status);
    case HIT -> new IllegalStateException("ulaşılamaz durum");
});
```

## 4. Redis Route'una Geçmeden Önce Warm Çalıştır

```java
CacheWarmResult result = cacheDatabase.warm(
        orders.warmTimeline(customerId, 1_000, CacheWarmTarget.PROJECTIONS_ONLY)
);

HotWindow<OrderSummary> firstPage = orders.timeline(
        customerId,
        WindowRequest.first(100)
);

List<OrderSummary> rows = firstPage.completeItems();
```

Warm, görünmeyen bir SQL fallback değildir. Kontrollü ön yükleme işlemidir.
Projection-only warm, full entity payload'unu taşımadan yalnızca adı verilen
projection'ı ve route kapsam bilgisini Redis'e yerleştirir. Full entity warm'ı
yalnızca detay route'u gerçekten entity payload'una ihtiyaç duyuyorsa kullan.

Endpoint için güvenli varsayılan `completeItems()` metodudur. Coverage eksik,
eski veya tamamlanmamışsa `HotRouteUnavailableException` fırlatır. Böylece
Redis'teki eksik pencere başarılı fakat boş bir sonuç gibi görünmez. `items()`
yalnızca uygulama coverage bilgisini ve kısıtlı sonucu birlikte gösterecekse
bilinçli olarak kullanılmalıdır.

`WindowRequest`, offset yerine keyset cursor kullanır. Bir hızlı erişim route'u
tek istekte en fazla 1.000 satır kabul eder; route daha küçük bir sınır da
belirleyebilir. Büyük backfill işlemleri tek ve sınırsız sorgu hâline
getirilmemeli; checkpoint kullanan sınırlı işler hâlinde yürütülmelidir.

Yalnızca ilk sayfayı veya top-N sonucunu veren bir yol için `int limit` parametresi
ve `limitParameter` kullan. Generated kod `WindowRequest.first(limit)` çağrısını
üretir. Kullanıcı sonraki sayfaya `nextCursor` ile geçecekse açık
`WindowRequest` parametresini koru.

```java
@HotRoute(value = "low-stock", projection = ProductAvailability.class)
@CacheRouteQuery(limitParameter = "limit")
HotWindow<ProductAvailability> lowStock(int limit);
```

## 5. Liste Ekranlarında Projection Kullan

```java
@CacheProjectionRecord(
        source = ProductEntity.class,
        id = "productId",
        name = "product-availability",
        rankedBy = {"stock_status", "updated_at"},
        factoryMethod = "fromEntity",
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record ProductAvailability(
        Long productId,
        String sku,
        String stockStatus,
        int availableQuantity,
        long updatedAt
) {
    public static ProductAvailability fromEntity(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        return new ProductAvailability(
                product.productId,
                product.sku,
                product.stockStatus,
                Math.max(0, stock - reserved),
                product.updatedAt
        );
    }
}
```

Hesaplanan alanlar için `factoryMethod` kullan. Mapping derleme sırasında
üretilir ve reflection kullanılmaz. Çok ilişkili listeler, top-N ekranları,
global sıralama ve dashboard kartları full aggregate yerine projection ile
çalışmalıdır.

## 6. Command Onay Seviyesini Açıkça Belirt

Repository'den gelen `save`, `saveAll` ve `deleteById` metotları
`WriteReceipt` döndürür. İstersen iş komutuna anlamlı bir ad verip beklenen onay
seviyesini de tanımlayabilirsin.

```java
@CacheCommand(
        operation = CacheCommand.Operation.SAVE,
        acknowledgement = CacheCommand.Acknowledgement.SQL_DURABLE,
        durabilityTimeoutMillis = 2_500
)
WriteReceipt<OrderEntity, Long> persistOrder(OrderEntity entity);
```

Asenkron API'lerde kalıcılık bekleniyorsa `REDIS_ACCEPTED` kullan ve pending
durumunu görünür kıl. Çağıranın SQL kalıcılığını beklemesi gerekiyorsa
`SQL_DURABLE` seç. Batch komutları derleme sırasında üst sınırla korunur.

ID üretimini entity alanında tanımlayabilirsin:

```java
@CacheId(column = "job_id")
@CacheGeneratedId(value = CacheGeneratedId.Strategy.SEQUENCE,
        sequence = "report-jobs", allocationSize = 64)
public Long jobId;
```

`UUID`, `ULID` ve Redis tabanlı `SEQUENCE` desteklenir. Tekrar denemelerde aynı
işlemin bir kez uygulanması API veya komut sözleşmesidir. Çağıran sabit bir ID
ya da idempotency anahtarı üretmelidir; CacheDB bunu annotation üzerindeki bir
bayraktan varsaymaz.

Optimistic kısmi komutta Redis'teki güncel sürümü kullan ve yeniden tam entity
üret:

```java
WriteReceipt<OrderEntity, Long> receipt = orders.updateHot(
        orderId,
        current -> current.withStatus("PAID")
);
```

Entity Redis'te yoksa `updateHot`, `HotUpdateUnavailableException` fırlatır.
SQL'den gizlice okuyup eksik payload'u birleştirmez. Güncel kalıcı satırın önce
okunması gerekiyorsa bu iş için açık bir SQL tabanlı komut akışı tanımla.

## 7. Özel SQL Sorgularını Açık ve Salt Okunur Tut

Route DSL ile ifade edilemeyen, sınırlı kalıcı okumalar için `@SourceSql`
kullan.

```java
@SourceSql(
        value = "SELECT order_id, customer_id, order_date, status "
                + "FROM orders WHERE customer_id = ? ORDER BY order_date DESC",
        parameters = "customerId",
        maxRows = 100,
        queryTimeoutSeconds = 10
)
SourceWindow<OrderEntity> recentSourceOrders(long customerId);
```

Processor ve runtime; veri değiştiren CTE'leri, yorum satırlarını, çoklu
statement'ları ve placeholder uyuşmazlığını reddeder. Dinamik tablo/kolon adı
veya veritabanına özel yazma prosedürü gerekiyorsa annotation içinde SQL
birleştirmek yerine gözden geçirilmiş, provider'a ait bir adapter kullan.

## 8. Test ve Operasyon Sözleşmesini Doğrula

```java
@SpringBootTest
@Import(CacheDbTestConfiguration.class)
class OrderRouteIT {
    @Autowired OrderRepository orders;
    @Autowired CacheDbTestProbe cacheDb;

    @Test
    void warmedTimelineIsComplete() {
        cacheDb.warm(orders.warmTimeline(42L, 1_000));
        CacheDbAssertions.requireComplete(
                orders.timeline(42L, WindowRequest.first(100))
        );
    }
}
```

Test scope'una `cachedb-spring-boot-test` ekle. Provider kimliği, write-behind
kuyruğu, dead letter sayısı, projection gecikmesi ve Redis baskısını görmek
için `cachedb` Actuator endpoint'ini yalnızca iç ağda aç. Eksik provider paketi
ve birden fazla provider bulunan classpath gibi sorunları deploy öncesinde
durdurmak için build'e `cachedb-maven-plugin:doctor` ekle.

## 9. GeneratedCacheModule'dan Geçiş

1. Mevcut entity ve generated binding'leri koru.
2. Gerçek bir uygulama route'u için bir `@CacheRepository` interface'i ekle.
3. Yalnızca Redis'ten çalışacak sorguyu `@HotRoute` olarak taşı.
4. Arşiv ve geçmiş okumalarını `@SourceRoute` veya gözden geçirilmiş
   `@SourceSql` ile ayır.
5. Önceden doldurulması gereken her route için `@WarmRoute` üret.
6. Uygulama servisine generated repository'yi enjekte et.
7. `GeneratedCacheModule` kullanımını yalnızca uyumluluk veya düşük seviyeli
   işlerle sınırla.
8. Veri eşitliği, kapsam, gecikme, bellek ve rollback kanıtı geçmeden cutover
   yapma.

Repository API'sinde composite primary key bilinçli olarak desteklenmez. Sabit
bir surrogate ID kullan; iş anahtarını doğrulanan ve indexlenen alanlar olarak
modelle. Anahtar parçalarını çağrı noktalarında belirsiz bir string içinde
birleştirme.

## 10. OR Kullanımını Açıkça Belirt

Aynı gruptaki predicate'ler AND ile, farklı gruplar ise OR ile birleştirilir.
Yeni grup sorgu kapsamını genişlettiği için processor açık onay ister:

```java
@CacheRouteQuery(
        predicates = {
                @CachePredicate(field = "orderDate", operator = CachePredicate.Operator.GTE,
                        parameter = "cutoff", group = 0),
                @CachePredicate(field = "status", operator = CachePredicate.Operator.IN,
                        constants = {"NEW", "PAID", "PICKING"}, group = 1)
        },
        explicitDisjunction = true,
        orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
        windowParameter = "window"
)
HotWindow<OrderSummary> recentOrActive(long cutoff, WindowRequest window);
```

`explicitDisjunction = true` olmadan birden fazla grup içeren repository
derlenmez. Annotation'ı görsel olarak bölmek için yeni grup açma. İş kuralı AND
ise koşulları aynı grupta tut.

## 11. Warm Sırasında Veri Şeklini Plan Belirlesin

Entity veya projection hedefini generated metodun tipli parametresiyle seç.
Oluşan plan bu kararı taşır; çalıştırma aşamasında yalnızca deneme veya uygulama
modunu belirt:

```java
CacheWarmTarget target = projectionOnly
        ? CacheWarmTarget.PROJECTIONS_ONLY
        : CacheWarmTarget.ENTITY_AND_PROJECTIONS;
CacheWarmPlan plan = orders.warmTimeline(customerId, 1_000, target);

CacheWarmExecution execution = cacheDatabase.executeWarm(
        plan,
        dryRun ? CacheWarmExecutionMode.DRY_RUN : CacheWarmExecutionMode.APPLY
);
CacheWarmSummary summary = execution.summary("customer-orders");

log.info("route={} scope={} read={} submitted={} target={}",
        summary.routeName(), summary.scope(), summary.rowsReadFromSource(),
        summary.rowsSubmittedToRedis(), summary.target());
```

Aynı yol için ayrı projection/entity warm metotları tanımlama ve ikinci bir
koşuldan `warmProjections(plan)` çağırma. Tipli hedef, string veya gizli boolean
bayrağı olmadan tek generated plan sözleşmesi üretir. Deneme modu Redis'i değiştirmez.

SQL kalıcılığını kanıtlaması gereken komutta sonucu boolean değere indirgeme;
receipt bilgisini koru:

```java
List<WriteReceipt<OrderEntity, Long>> receipts = orders.saveAll(batch);
cacheDatabase.awaitDurableOrThrow(
        receipts,
        Duration.ofSeconds(5),
        "order import/batch-42"
);
```

Timeout oluşursa `WriteBatchDurabilityTimeoutException`; receipt listesini,
timeout değerini ve işlem adını taşır. Satırlar Redis tarafından kabul edilmiş
olabilir. Bu hatayı körlemesine aynı yazıyı tekrar gönderme izni olarak değil,
SQL kalıcılığı henüz kanıtlanamamış bir sonuç olarak ele al.

## 12. Veri Kaynağını Gizlemeden Ortak Pencere Kodunu Kullan

`HotWindow` ve `SourceWindow`, `WindowSlice` interface'ini uygular. Ortak cursor
kodu `hasNext()` ve `nextRequest(limit)` kullanabilir. Redis coverage bilgisi ise
yalnızca `HotWindow` üzerinde kalır:

```java
HotWindow<OrderSummary> page = orders.timeline(customerId, WindowRequest.first(100))
        .requireComplete();

HotWindow<OrderRow> response = page.map(OrderRow::from);
Optional<WindowRequest> next = response.nextRequest(100);
```

`HotLookup.map`; `NOT_CACHED`, `TOMBSTONED` ve `OUTSIDE_HOT_POLICY` durumlarını
da aynen korur. Payload dönüşümü, verinin erişilebilirlik durumunu silemez.

## 13. Generated Route Envanterini İncele

Her generated repository, reflection kullanmayan bir route kataloğu yayımlar.
Spring Boot starter bu katalogları kendiliğinden birleştirir. `cachedb` Actuator
endpoint'ini yalnızca iç operasyon ağında aç:

Repository bean'leri geriye uyum için küçük harfle başlayan mevcut varsayılan
adını korur. İki paket aynı repository interface adını kullanıyorsa farklı bir
`@CacheRepository.springBeanName` belirt; processor çözülmemiş çakışmayı reddeder.
Route kataloğu bean adları ise paket adıyla birlikte kendiliğinden benzersizdir.

```properties
management.endpoints.web.exposure.include=health,info,metrics,cachedb
```

Endpoint; tanımlı repository ve route sayılarını, route türlerini, sınırlı route
ayrıntılarını, hızlı erişim route'larının nasıl doldurulduğunu, periyodik warm
özetlerini ve sonuç kesilmişse truncation bilgisini döndürür. Route ayrıntısı en
fazla 250, warm işi ayrıntısı en fazla 100 kayıttır. Global hızlı erişim route
adları çakışırsa veya `DECLARED_WARM` seçilip generated warm yolu eklenmezse
uygulama başlangıçta durur.

Toplu Micrometer metrikleri route adı veya tenant tag'i üretmez:

- `cachedb.repositories.declared`
- `cachedb.routes.declared`
- `cachedb.routes.hot.population{strategy=...}`
- `cachedb.scheduled.warm.running`
- `cachedb.scheduled.warm.failures`
- `cachedb.scheduled.warm.skipped`

Katalog, uygulamanın hangi route'larla derlendiğini kanıtlar. Redis coverage'ın
tam olduğunu kanıtlamaz. Coverage, veri eşitliği, gecikme, bellek ve kalıcılık
kontrollerini ayrı production kapıları olarak koru.

Entegrasyon testinde operasyon sözleşmesini açıkça doğrula:

```java
cacheDb.requireDeclaredWarmRoute("customer-order-timeline");
cacheDb.warmAndRequireCoverage(
        orders.warmTimeline(42L, 1_000, CacheWarmTarget.PROJECTIONS_ONLY),
        Duration.ofMinutes(5)
);
```

## 14. Tekrarlanan Route Kurallarını Repository Düzeyinde Tanımla

`@CacheRepositoryDefaults`, route davranışını gizlemeden tekrar eden değerleri
tek yerde toplar. Annotation processor bütün değerleri derleme sırasında çözer.
Metot üzerinde açıkça yazılan değer her zaman repository varsayılanından önce gelir.

```java
@CacheRepository(entity = OrderEntity.class)
@CacheRepositoryDefaults(
        hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500,
        sourceTimeoutSeconds = 15
)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @HotRoute(
            value = "customer-order-timeline",
            projection = OrderSummary.class,
            hotWindow = 1_000,
            memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "customerId"
    )
    // Örneği kısa tutmak için @CacheRouteQuery burada gösterilmedi.
    HotWindow<OrderSummary> timeline(long customerId, WindowRequest window);
}
```

Ham byte sayıları yerine `CacheMemoryBudget.MIB_*` sabitlerini kullan. Bunlar
Java derleme zamanı sabitidir ve annotation içinde kullanılabilir. Repository
varsayılanı global ayar değildir. Projection, aktif veri penceresi, kapsam ve
özel route kararları metot üzerinde görünür kalır.

## 15. Cursor Bilgisini HTTP Sınırında Kaybetme

Sayfalanan sonucu yalın listeye çevirme. `CursorPage<T>`, sonraki sayfa için
gereken kapalı cursor bilgisini yanıtla birlikte taşır:

```java
public CursorPage<OrderSummary> timeline(long customerId, int limit, String after) {
    return orders.timeline(customerId, WindowRequest.of(limit, after)).completePage();
}
```

```json
{
  "items": [{ "orderId": 10042, "status": "PAID" }],
  "nextCursor": "opaque-token"
}
```

Yeni cursor; generated route adına, route kapsamına ve sıralama sözleşmesine
bağlıdır. Müşteri 42 için üretilen cursor müşteri 43 veya başka bir route için
kullanılırsa `CursorContractMismatchException` oluşur. Eski cursor'lar geriye
uyumluluk için okunur; yeni üretilen token'lar daha güçlü sözleşmeyi taşır.

## 16. Kalıcı Toplu Aktarım İçin Framework Batch Writer Kullan

`CacheDurableBatchWriter`; batch boyutunu, bekleyen receipt sınırını ve SQL
kalıcılık beklemesini yönetir. Redis-first yazma davranışını değiştirmez.
`finish()`, bekleyen bütün receipt'ler seçilen SQL provider'da kalıcı olmadan
dönmez.

```java
try (var batch = cacheDatabase.durableBatchWriter(
        "catalog import",
        128,
        1_024,
        Duration.ofSeconds(30),
        productRepository::saveAll
)) {
    sourceRows.forEach(batch::add);
}
```

İşlemi idempotent tasarla. Kalıcılık zaman aşımı, SQL sonucunun henüz
kanıtlanamadığını gösterir; korumasız biçimde aynı yazıyı tekrar gönderme izni vermez.

## 17. Entegrasyon Testinde Warm Yolculuğunun Tamamını Kanıtla

Test starter; deneme, uygulama ve coverage kontrolünü production akışına benzer
tek bir yolculuk olarak çalıştırabilir:

```java
CacheDbWarmRouteEvidence evidence = cacheDb.dryRunApplyAndRequireCoverage(
        orders.warmTimeline(42L, 1_000, CacheWarmTarget.PROJECTIONS_ONLY),
        Duration.ofMinutes(5)
);

assertThat(evidence.dryRun().result().submittedRows()).isZero();
assertThat(evidence.coverage().complete()).isTrue();
```

Bu kanıt; deneme çalışmasının Redis'i değiştirmediğini, uygulamanın aynı planı
kullandığını ve doğru route/kapsam için güncel coverage bulunduğunu gösterir.
Veri eşitliği, gecikme, bellek ve failover testlerinin yerini almaz.
