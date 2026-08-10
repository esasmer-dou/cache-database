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

BOM'u bir kez ekle ve yalnızca bir SQL provider starter seç. `0.7.0`, GitHub
Packages üzerinden değişmez paket olarak yayımlanmıştır.

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
            coverageScopeParameter = "customerId", projectionsOnly = true)
    CacheWarmPlan warmTimeline(long customerId, int maxRows);
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
        orders.warmTimeline(customerId, 1_000)
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
