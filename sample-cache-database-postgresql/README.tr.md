# CacheDB PostgreSQL REST API Örneği

[English](README.md) | Türkçe

Bu proje, CacheDB’nin Redis ve PostgreSQL ile nasıl kullanılacağını gösteren bağımsız bir Spring Boot REST API örneğidir. Kullanıcının ana CacheDB reposunu indirip yerelde build etmesi gerekmez; proje CacheDB’yi Maven paketi olarak tüketir.

Örnek senaryo bir e-ticaret destek sistemidir:

- Müşteriler sürekli sipariş verir.
- Siparişlerin birden fazla satırı vardır.
- Ürünler kategoriye göre sık okunur.
- Destek talepleri operasyon paneline veri sağlar.
- Müşteri sipariş zaman çizelgesi, tüm aggregate yüklenmeden özet okuma modeli üzerinden döner.

## Bağımlılık Modeli

Bu proje CacheDB’yi dış Maven paketi olarak kullanır:

```xml
<repository>
  <id>cache-database-github-packages</id>
  <url>https://maven.pkg.github.com/esasmer-dou/cache-database</url>
</repository>

<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Yani kullanıcı ana projeyi önce build etmek zorunda değildir. CacheDB `0.1.0`, ana repodan GitHub Packages’a yayınlanır ve bu örnek proje paketi oradan çeker.

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

`read:packages` yetkisi olan bir token tanımladıktan sonra proje doğrudan build edilir:

```bash
export GITHUB_ACTOR=github-kullanici-adin
export GITHUB_TOKEN=read-packages-token
mvn clean package
```

Bu ayar yapılmazsa repository URL doğru olsa bile Maven genellikle `401 Unauthorized` hatası verir.

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

## Bu README’de Geçen Temel Terimler

CacheDB’ye yeni başlıyorsan önce bu bölümü oku. Örnek projede bazı CacheDB terimleri sık geçer; kodu incelemeden önce bu kavramların ne anlama geldiğini bilmek işleri netleştirir.

| Terim | Bu örnekte ne anlama gelir? | Nerede görülür? |
|---|---|---|
| CacheDB entity | `@CacheEntity` ile işaretlenmiş Java sınıfıdır. Bir SQL tablosunu, bir Redis namespace’ini ve generated repository yüzeyini temsil eder. | `domain/CustomerEntity.java`, `domain/OrderEntity.java` |
| Generated binding | Derleme sırasında üretilen bağlayıcı sınıftır. Örneğin `OrderEntityCacheBinding`; repository oluşturma, named query, fetch preset ve projection repository metotlarını tip güvenli şekilde sunar. Uygulamanın kullandığı pratik ORM yüzeyi budur. | `SampleRepositories.java`, `OrderEntityCacheBinding.customerTimeline(...)` gibi controller çağrıları |
| Entity repository | Tam entity nesneleri için CRUD ve sınırlı sorgu API’sidir. Oluşturma, güncelleme, silme ve detay okumalarında kullanılır. | `EntityRepository<OrderEntity, Long>` |
| Projection | Entity’den türetilmiş küçük okuma modelidir. Liste, panel ve global sıralı ekranlarda tam entity yükleme maliyetini önlemek için kullanılır. | `OrderReadModels.OrderSummary` |
| Okuma modeli | Kullanıcı ekranının ihtiyaç duyduğu sade veri şeklidir. Bu örnekte `OrderSummary`, sipariş listesi ve yüksek değerli sipariş ekranının okuma modelidir. | `readmodel/OrderReadModels.java` |
| Projection repository | Tam entity yerine projection satırlarını sorgulayan repository’dir. | `ProjectionRepository<OrderSummary, Long>` |
| Named query | Entity üzerinde tanımlanan ve generated binding üzerinden kullanılan hazır `QuerySpec` metodudur. Controller içinde kontrolsüz ve sınırsız sorgu yazılmasını engeller. | `customerTimelineQuery`, `recentHighValueOrdersQuery` |
| Fetch preset | Detay yolu için adlandırılmış fetch planıdır. Hangi ilişkinin yükleneceğini ve kaç alt satıra izin verileceğini belirler. | `ordersPreviewFetchPlan`, `linePreviewFetchPlan` |
| Relation loader | Alt koleksiyonları sadece fetch preset istediğinde dolduran açık koddur. Yanlışlıkla `N+1` benzeri yükleme yapılmasını engeller. | `CustomerOrdersRelationBatchLoader`, `OrderLinesRelationBatchLoader` |
| Aktif veri seti | Policy’ye göre Redis’te kalmasına izin verilen veri alt kümesidir. Örneğin son siparişler veya operasyonel olarak aktif kayıtlar. | `SampleCacheDbTuningConfig` |
| Write-behind | Yazının önce Redis üzerinden kabul edilip kalıcı SQL satırına arka planda taşındığı yazma modelidir. | Veri üretme akışı, oluşturma çağrıları, yönetim ekranındaki write-behind paneli |
| Guardrail | Production’da pahalı hataları engelleyen güvenlik sınırıdır. Örneğin sınırsız entity taraması veya Redis bellek baskısı. | `ReadShapeGuardrailConfig`, `RedisGuardrailConfig` |

README’de “generated binding mantığını öğren” denildiğinde kastedilen şudur: `@CacheEntity`, named query, fetch preset ve projection tanımlarının derleme sırasında `repository(...)`, `customerTimeline(...)`, `ordersPreviewRepository(...)` ve `orderSummary(...)` gibi üretilmiş metotlara nasıl dönüştüğünü incelemek. Uygulamanın ORM gibi kullandığı yüzey bu üretilmiş metotlardır.

## Katman Katman Mimari

Bu örnek küçük tutuldu, fakat production servislerde kullanılması gereken yapıyı izler.

| Katman | Ana dosyalar | Sorumluluk | Production kuralı |
|---|---|---|---|
| API | `web/*Controller.java` | İsteği doğrular, limitleri sınırlar, güvenli endpoint sunar | Sınırsız liste çağrısı açma |
| Service | `SampleSeedService.java`, controller metotları | İş akışını, kayıt sırasını ve retry davranışını yönetir | Yazma ve ilişki sırasını açık tut |
| CacheDB repository | `SampleRepositories.java` | Generated `EntityRepository` ve `ProjectionRepository` bean’lerini üretir | Generated binding’leri ORM yüzeyi olarak kullan |
| Entity mapping | `domain/*Entity.java` | Java alanlarını SQL kolonlarına ve Redis namespace’lerine bağlar | Tablo, id, kolon ve relation tanımı açık olmalı |
| Relation yükleme | `relation/*BatchLoader.java` | Child koleksiyonları sadece fetch preset istediğinde yükler | Tam aggregate yerine sınırlı önizleme kullan |
| Okuma modeli | `readmodel/OrderReadModels.java` | Liste ve panel satırlarını küçük projection veri gövdesi olarak tutar | Büyüyen listelerde projection kullan |
| Kalıcı veri | `schema.sql` | Primary key, foreign key ve okuma yolu indekslerini sahiplenir | Tam geçmişin doğruluk kaynağı SQL’dir |
| Çalışma zamanı ayarları | `SampleCacheDbTuningConfig.java` | Aktif veri politikası, limitler, koruma eşikleri ve write-behind ayarlarını verir | Ayarı tahminle değil okuma yolu ve bellek bütçesiyle yap |

### API Katmanı: ORM’e Gitmeden Önce İsteği Sınırla

`CustomerController`, kullanıcının verdiği limiti olduğu gibi CacheDB’ye taşımaz. Önce güvenli aralığa çeker:

```java
@GetMapping("/{customerId}/orders")
public List<OrderReadModels.OrderSummary> orderTimeline(
        @PathVariable long customerId,
        @RequestParam(defaultValue = "20") int limit
) {
    return OrderEntityCacheBinding.customerTimeline(
            orderSummaryRepository,
            customerId,
            clamp(limit, 1, 1_000)
    );
}
```

Buradaki asıl mesele `1_000` sayısı değildir. Asıl mesele okuma yolu sözleşmesidir. Bir liste çağrısı Redis’e veya PostgreSQL’e gitmeden önce maksimum sonuç sınırını bilmelidir.

### Repository Katmanı: Generated Binding ORM Yüzeyidir

`SampleRepositories`, CacheDB’nin generated binding sınıflarını Spring bean haline getirir:

```java
@Bean
EntityRepository<OrderEntity, Long> orderRepository(CacheDatabase cacheDatabase) {
    return OrderEntityCacheBinding.repository(cacheDatabase);
}

@Bean
ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository(
        EntityRepository<OrderEntity, Long> orderRepository
) {
    return OrderEntityCacheBinding.orderSummary(orderRepository);
}
```

Controller’lar bu repository’leri ORM gibi kullanır. Fakat bu yüzey klasik dinamik ORM sorgu katmanı gibi sınırsız değildir. Named query, fetch preset, projection ve relation loader tanımları kodda açıktır ve derleme sırasında generated binding’e dönüşür.

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
        .ordersPreviewRepository(customerRepository, clamp(orderPreview, 1, 25))
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
        Double orderAmount,
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

`OrderSummary`, “müşteri sipariş listesi ve yüksek değerli sipariş listesi için `OrderSummary` kullan” önerisinin gerçek karşılığıdır. Yer tutucu bir kavram değildir.

### 1. Okuma modelinin şekli

`OrderSummary` sadece liste ekranının ihtiyaç duyduğu kolonları taşır:

```java
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        Double orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

Bu modelde sipariş satırı, ürün detayı, müşteri detayı veya denetim geçmişi yoktur. Bunlar detay ekranının veya arşiv akışının konusudur.

### 2. Projection tam entity’den summary üretir

`OrderReadModels.ORDER_SUMMARY_PROJECTION`, CacheDB’ye küçük satırın nasıl üretileceğini ve saklanacağını söyler:

```java
(OrderEntity order) -> new OrderSummary(
        order.orderId,
        order.customerId,
        order.orderDate,
        order.orderAmount,
        order.currencyCode,
        order.orderType,
        order.status,
        order.lineCount,
        order.priorityScore
)
```

Projection, sık kullanılan ekranların sıralama alanlarına göre önceden sıralı tutulur:

```java
).rankedBy("order_date", "priority_score").asyncRefresh();
```

Bu yüzden müşteri sipariş listesi `order_date` ile, yüksek değerli sipariş listesi `priority_score` ile sıralanabilir; tam `OrderEntity` veri gövdesi yüklenmez.

### 3. Entity projection’ı CacheDB’ye açar

`OrderEntity`, projection tanımını CacheDB’ye tanıtır:

```java
@CacheProjectionDefinition("orderSummary")
public static EntityProjection<OrderEntity, OrderReadModels.OrderSummary, Long> orderSummaryProjection() {
    return OrderReadModels.ORDER_SUMMARY_PROJECTION;
}
```

Generated binding sonrasında şu metotları sunar:

```java
OrderEntityCacheBinding.orderSummary(orderRepository)
OrderEntityCacheBinding.customerTimeline(orderSummaryRepository, customerId, limit)
OrderEntityCacheBinding.recentHighValueOrders(orderSummaryRepository, minimumAmount, limit)
```

### 4. API yolu projection repository kullanır

Sık kullanılan müşteri sipariş listesi bilinçli olarak projection yoludur:

```java
@GetMapping("/{customerId}/orders")
public List<OrderReadModels.OrderSummary> orderTimeline(
        @PathVariable long customerId,
        @RequestParam(defaultValue = "20") int limit
) {
    return OrderEntityCacheBinding.customerTimeline(
            orderSummaryRepository,
            customerId,
            clamp(limit, 1, 1_000)
    );
}
```

Yanıt zaten ekranın ihtiyaç duyduğu şekildedir. UI arka planda gizli tam `OrderEntity` almaz.

## Sorgu Akışı: Redis mi PostgreSQL mi?

Bu örnek artık iki yolu da açık gösterir. Sık kullanılan operasyonel okumalar CacheDB/Redis üzerinden gider. Arşiv okumaları doğrudan PostgreSQL üzerinden yapılır.

| Çağrı | İlk çalışma yolu | PostgreSQL ne zaman kullanılır? | Redis davranışı | Neden? |
|---|---|---|---|---|
| `POST /api/customers` | `EntityRepository.save` | Write-behind satırı arka planda kalıcılaştırır | Aktif veri politikası kabul ederse entity Redis’e girer | Normal yazma yolu |
| `POST /api/orders` | `JdbcTemplate` FK hazırlık kontrolü, sonra `EntityRepository.save` | Alt kayıt yazmadan önce üst müşteri SQL’de kalıcı mı diye bakılır | Sipariş Redis üzerinden kaydedilir ve write-behind kuyruğuna girer | FK ihlalini engeller, Redis-first yazma modelini korur |
| `GET /api/customers/{id}/orders` | `ProjectionRepository<OrderSummary>` | Sık kullanılan liste yolunda kullanılmaz | Redis projection verisini ve indeksini okur; eksik projection satırını Redis’teki base entity verisinden ısıtabilir | Hızlı müşteri zaman çizelgesi |
| `GET /api/orders/high-value` | `ProjectionRepository<OrderSummary>` | Sık kullanılan liste yolunda kullanılmaz | Ranked Redis projection verisini okur | Hızlı global sıralı iş listesi |
| `GET /api/orders/{id}` | `linePreview` fetch preset ile `EntityRepository.findById` | Bu örnek endpoint’i Redis’te bulunamama durumunda SQL’e gitmez | Redis entity verisini okur, relation loader sınırlı satır sorgusunu Redis üzerinden yapar | Sık kullanılan sipariş detay ekranı |
| `GET /api/orders/archive` | `JdbcTemplate.query` | Doğrudan PostgreSQL okur | Redis’i değiştirmez | Arşiv/geçmiş okuması |
| `GET /api/products/active` | `EntityRepository.query` | Bu örnek endpoint’inde kullanılmaz | Sınırlı Redis entity sorgusu | Küçük katalog listesi |
| `GET /api/tickets/open` | `EntityRepository.query` | Bu örnek endpoint’inde kullanılmaz | Sınırlı Redis entity sorgusu | Operasyon kuyruğu |
| `GET /api/dashboard/commerce` | Projection sorgusu + destek talebi entity sorgusu | Bu örnek endpoint’inde kullanılmaz | Redis projection ve Redis entity sorgusunu birleştirir | Panel ilk ekranı |

Önemli kural: CacheDB repository okumaları, Redis’te bulunamayan her kayıt için otomatik veritabanı taraması yapacak anlamına gelmez. Bu örnekte `EntityRepository.findById` ve normal `query(...)` yolları Redis/aktif veri seti yollarıdır. Arşiv veya tam geçmiş okuması gerekiyorsa bunu açık SQL yolu olarak tasarla. Bu örnekteki arşiv yolu:

```java
@GetMapping("/archive")
public List<OrderReadModels.OrderSummary> archiveFromSql(
        @RequestParam long customerId,
        @RequestParam(required = false) Long beforeOrderDate,
        @RequestParam(defaultValue = "100") int limit
) {
    return jdbcTemplate.query(ARCHIVE_SQL, rowMapper, customerId, upperBound, safeLimit);
}
```

SQL yolu aynı yanıt modelini kullanır, fakat veri kaynağı farklıdır:

```sql
SELECT order_id, customer_id, order_date, order_amount, currency_code,
       order_type, status, line_count, priority_score
FROM sample_orders
WHERE customer_id = ? AND order_date < ?
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
| Aktif veri penceresi | `hotEntityLimit=5000` | Yerel testte Redis büyümesini sınırlar |
| Entity TTL | `0` | Bu örnekte entity kayıtları TTL ile silinmez |
| Sayfa TTL | `120s` | Sayfa cache’i kısa süreli tutulur |
| Aktif veri politikası | son 90 gün veya aktif operasyon durumları | Sadece LRU’dan daha gerçekçi bir iş kuralı sağlar |
| Entity sorgu limiti | `250` | Büyük entity taramalarını engeller |
| Projection sorgu limiti | `1000` | Zaman çizelgesi penceresine izin verir, sınırsız okumayı engeller |
| Redis koruma eşikleri | uyarı %75, kritik %88 | Bellek baskısını erken görünür yapar |

Production’da Redis `maxmemory` mutlaka verilmelidir, `maxmemory-policy=noeviction` korunmalıdır, bağlantı havuzları hedef trafiğe göre ayarlanmalıdır ve yönetim ekranı gateway/auth arkasında tutulmalıdır.

## Postman

İçe aktarılacak dosya:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

Koleksiyon; sağlık kontrolü, veri üretme, müşteri sipariş listesi, detay, yüksek değerli projection, arşiv SQL okuması, panel, update, delete ve tuning çağrılarını içerir.

## PostgreSQL Notları

Şema `src/main/resources/schema.sql` ile kurulur. Primary key, foreign key ve aktif okuma yolları için indeksler vardır:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

Seed endpoint’i veriyi CacheDB üzerinden yazar ve alt kayıtları yazmadan önce üst kayıtların SQL tarafına düşmesini bekler. Bunun nedeni şemada foreign key bulunmasıdır.

`POST /api/orders` da müşteri satırı PostgreSQL tarafında kalıcı hale gelene kadar kısa süre bekler. Müşteri az önce oluşturulduysa ve write-behind henüz SQL’e yazmadıysa çağrı `409` döner; istemci kısa süre sonra tekrar denemelidir.

## Sorun Giderme

CacheDB dependency’leri çözülemiyorsa `pom.xml` içindeki paket repository erişimini kontrol et.

Seed endpoint’i yavaş görünüyorsa `GET /api/health/ready` ve yönetim ekranındaki arka plan yazma bölümünü kontrol et. Seed akışı foreign key hatası üretmemek için SQL satırlarının oluşmasını bekler.

Seed sonrası liste hemen boş dönerse birkaç saniye bekleyip tekrar dene. Projection yenileme arka planda çalışır.
