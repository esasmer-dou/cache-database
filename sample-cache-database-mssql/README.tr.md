# CacheDB MSSQL REST API Örneği

[English](README.md) | Türkçe

Bu proje, CacheDB’nin Redis ve Microsoft SQL Server ile nasıl kullanılacağını gösteren bağımsız bir Spring Boot REST API örneğidir. PostgreSQL örneğiyle aynı domain ve API akışını kullanır; fark olarak SQL provider açıkça MSSQL seçilir.

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

<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-storage-mssql</artifactId>
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

1. Redis ve SQL Server’ı başlat:

```bash
docker compose up -d
```

2. API’yi başlat:

```bash
mvn spring-boot:run
```

3. Hazırlık durumunu kontrol et:

```bash
curl http://127.0.0.1:8092/api/health/ready
```

4. Demo verisini üret:

```bash
curl -X POST "http://127.0.0.1:8092/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"
```

5. CacheDB yönetim ekranını aç:

```text
http://127.0.0.1:8092/cachedb-admin
```

## Ana API Akışı

| Adım | Endpoint | Ne gösterir? |
|---|---|---|
| Sağlık | `GET /api/health/ready` | Redis bağlantısı ve arka plan yazma özeti |
| Veri üretme | `POST /api/demo/seed` | CacheDB yazma yolu, SQL Server kalıcılığı, projection yenileme |
| Müşteri detay | `GET /api/customers/1?orderPreview=5` | Sınırlı sipariş önizlemesiyle entity detayı |
| Sipariş listesi | `GET /api/customers/1/orders?limit=20` | Özet okuma modeliyle listeleme |
| Sipariş detay | `GET /api/orders/10001?linePreview=5` | Sınırlı satır önizlemesiyle detay okuma |
| Yüksek değerli sipariş | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Global sıralı projection sorgusu |
| Panel | `GET /api/dashboard/commerce?limit=25` | Projection ve ticket sorgularıyla küçük dashboard |
| Ayarlar | `GET /api/tuning` | Aktif CacheDB politikaları ve koruma eşikleri |

## Bu README’de Geçen Temel Terimler

CacheDB’ye yeni başlıyorsan önce bu bölümü oku. Örnek projede bazı CacheDB terimleri sık geçer; kodu incelemeden önce bu kavramların ne anlama geldiğini bilmek işleri netleştirir.

| Terim | Bu örnekte ne anlama gelir? | Nerede görülür? |
|---|---|---|
| CacheDB entity | `@CacheEntity` ile işaretlenmiş Java sınıfıdır. Bir SQL Server tablosunu, bir Redis namespace’ini ve generated repository yüzeyini temsil eder. | `domain/CustomerEntity.java`, `domain/OrderEntity.java` |
| Generated binding | Build sırasında üretilen bağlayıcı sınıftır. Örneğin `OrderEntityCacheBinding`; repository oluşturma, named query, fetch preset ve projection repository metotlarını type-safe şekilde sunar. Uygulamanın kullandığı pratik ORM yüzeyi budur. | `SampleRepositories.java`, `OrderEntityCacheBinding.customerTimeline(...)` gibi controller çağrıları |
| Entity repository | Full entity nesneleri için CRUD ve sınırlı sorgu API’sidir. Create, update, delete ve detay okumalarında kullanılır. | `EntityRepository<OrderEntity, Long>` |
| Projection | Entity’den türetilmiş küçük okuma modelidir. Liste, dashboard ve global sıralı ekranlarda full entity yükleme maliyetini önlemek için kullanılır. | `OrderReadModels.OrderSummary` |
| Okuma modeli | Kullanıcı ekranının ihtiyaç duyduğu sade veri şeklidir. Bu örnekte `OrderSummary`, sipariş listesi ve yüksek değerli sipariş ekranının okuma modelidir. | `readmodel/OrderReadModels.java` |
| Projection repository | Full entity yerine projection satırlarını sorgulayan repository’dir. | `ProjectionRepository<OrderSummary, Long>` |
| Named query | Entity üzerinde tanımlanan ve generated binding üzerinden kullanılan hazır `QuerySpec` metodudur. Controller içinde kontrolsüz ve sınırsız sorgu yazılmasını engeller. | `customerTimelineQuery`, `recentHighValueOrdersQuery` |
| Fetch preset | Detay route’u için adlandırılmış fetch planıdır. Hangi relation’ın yükleneceğini ve kaç child satıra izin verileceğini belirler. | `ordersPreviewFetchPlan`, `linePreviewFetchPlan` |
| Relation loader | Child koleksiyonları sadece fetch preset istediğinde dolduran açık koddur. Yanlışlıkla `N+1` benzeri yükleme yapılmasını engeller. | `CustomerOrdersRelationBatchLoader`, `OrderLinesRelationBatchLoader` |
| Aktif veri seti | Policy’ye göre Redis’te kalmasına izin verilen veri alt kümesidir. Örneğin son siparişler veya operasyonel olarak aktif kayıtlar. | `SampleCacheDbTuningConfig` |
| Write-behind | Yazının önce Redis üzerinden kabul edilip kalıcı SQL Server satırına arka planda taşındığı yazma modelidir. | Seed akışı, create endpoint’leri, yönetim ekranındaki write-behind paneli |
| Guardrail | Production’da pahalı hataları engelleyen güvenlik sınırıdır. Örneğin sınırsız entity taraması veya Redis bellek baskısı. | `ReadShapeGuardrailConfig`, `RedisGuardrailConfig` |

README’de “generated binding mantığını öğren” denildiğinde kastedilen şudur: `@CacheEntity`, named query, fetch preset ve projection tanımlarının build sırasında `repository(...)`, `customerTimeline(...)`, `ordersPreviewRepository(...)` ve `orderSummary(...)` gibi generated metotlara nasıl dönüştüğünü incelemek. Uygulamanın ORM gibi kullandığı yüzey bu generated metotlardır.

## Katman Katman Mimari

Bu örnek küçük tutuldu, fakat production servislerde kullanılması gereken yapıyı izler.

| Katman | Ana dosyalar | Sorumluluk | Production kuralı |
|---|---|---|---|
| API | `web/*Controller.java` | İsteği doğrular, limitleri sınırlar, güvenli endpoint sunar | Sınırsız liste endpoint’i açma |
| Service | `SampleSeedService.java`, controller metotları | İş akışını, kayıt sırasını ve retry davranışını yönetir | Yazma ve ilişki sırasını açık tut |
| CacheDB repository | `SampleRepositories.java` | Generated `EntityRepository` ve `ProjectionRepository` bean’lerini üretir | Generated binding’leri ORM yüzeyi olarak kullan |
| Entity mapping | `domain/*Entity.java` | Java alanlarını SQL Server kolonlarına ve Redis namespace’lerine bağlar | Tablo, id, kolon ve relation tanımı açık olmalı |
| Relation yükleme | `relation/*BatchLoader.java` | Child koleksiyonları sadece fetch preset istediğinde yükler | Tam aggregate yerine sınırlı önizleme kullan |
| Okuma modeli | `readmodel/OrderReadModels.java` | Liste ve dashboard satırlarını küçük projection payload olarak tutar | Büyüyen listelerde projection kullan |
| Kalıcı veri | `schema.sql` | Primary key, foreign key ve route indekslerini sahiplenir | Tam geçmişin doğruluk kaynağı SQL Server’dır |
| Runtime ayarları | `SampleCacheDbTuningConfig.java`, `application.yml` | Aktif veri politikası, limitler, guardrail, MSSQL lock timeout ve write-behind ayarlarını verir | Ayarı route, SQL Server davranışı ve bellek bütçesiyle yap |

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

Buradaki asıl mesele `1_000` sayısı değildir. Asıl mesele route sözleşmesidir. Bir liste endpoint’i Redis’e veya SQL Server’a gitmeden önce maksimum sonuç sınırını bilmelidir.

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

Controller’lar bu repository’leri ORM gibi kullanır. Fakat bu yüzey klasik dinamik ORM query katmanı gibi sınırsız değildir. Named query, fetch preset, projection ve relation loader tanımları kodda açıktır ve build sırasında generated binding’e dönüşür.

### Entity Katmanı: SQL Mapping ve Cache Mapping Açık Tanımlıdır

`OrderEntity`; tabloyu, Redis namespace’i, primary key’i, kolonları, relation’ı, named query’leri ve projection’ı tanımlar:

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

Bu gizli bir mekanizma değildir. Tablo ve kolon tanımı CacheDB’ye entity’nin nasıl yazılıp okunacağını söyler. Relation metadata’sı ise fetch plan istendiğinde Java object graph’ının nasıl bağlanacağını söyler.

### Relation Modeli: Foreign Key ve `@CacheRelation` Farklı İşleri Çözer

Database foreign key veri bütünlüğünü korur:

```sql
CONSTRAINT fk_sample_orders_customer
FOREIGN KEY (customer_id) REFERENCES sample_customers(customer_id)
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
| Foreign key var ve `@CacheRelation` var | SQL Server bütünlüğü korur, CacheDB istenirse object graph yükler |
| Foreign key var ama `@CacheRelation` yok | SQL Server doğru kalır, fakat CacheDB Java child koleksiyonunu otomatik doldurmaz |
| `@CacheRelation` var ama foreign key yok | CacheDB eşleşen kolonla sorgu yapabilir, fakat SQL Server orphan kayıtları engellemez |
| İkisi de yok | Sadece açık child sorguları kullanılmalı; ORM tarzı relation beklenmemeli |

Production önerisi: ikisini birlikte kullan. Foreign key doğruluk içindir; `@CacheRelation` ve fetch preset kontrollü object graph yüklemek içindir.

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

Yani detay ekranı “son 5 siparişi” gösterebilir; ama müşterinin tüm tarihsel siparişlerini ve tüm satırlarını tek response içinde yüklemez.

### Projection Katmanı: Büyüyen Listeler `OrderSummary` Kullanır

Sipariş listesi ve yüksek değerli sipariş ekranı full `OrderEntity` yerine `OrderSummary` kullanır:

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

Projection gerçek ekranların sıralama alanlarına göre ranked tutulur:

```java
).rankedBy("order_date", "priority_score").asyncRefresh();
```

Böylece Redis içinde liste satırları küçük kalır. Full entity hâlâ detay ekranı için vardır; fakat liste ekranı tam aggregate yükleme maliyetini ödemez.

## Bu Örnekteki Gerçek Hayat Senaryoları

| Senaryo | Endpoint | CacheDB şekli | Neden bu şekil? |
|---|---|---|---|
| Müşteri sipariş geçmişini açar | `GET /api/customers/{id}/orders?limit=20` | `OrderSummary` projection sorgusu | Büyüyen liste, küçük payload, sabit sıralama |
| Kullanıcı tek sipariş açar | `GET /api/orders/{id}?linePreview=5` | Entity detay ve sınırlı relation önizlemesi | Detay daha fazla veri ister, fakat yine de tüm satırları zorla yüklemez |
| Operasyon yüksek değerli siparişlere bakar | `GET /api/orders/high-value?minimumAmount=500&limit=25` | Ranked projection sorgusu | Global sıralı ekran full entity taramamalı |
| Kategoriye göre ürün listesi | `GET /api/products/active?category=electronics&limit=20` | Named entity query | Küçük ve sınırlı katalog rotası entity query için uygundur |
| Destek kuyruğu | `GET /api/tickets/open?limit=25` | Status indeksli named entity query | Operasyon kuyruğu sınırlı ve filtrelidir |
| Ticaret paneli | `GET /api/dashboard/commerce?limit=25` | Projection ve ticket entity sorgusu | Dashboard küçük, önceden şekillenmiş okuma modellerini birleştirir |
| Müşteri oluşturma | `POST /api/customers` | Entity save | Redis-first yazma, SQL Server’a arka plan yazma |
| Sipariş oluşturma | `POST /api/orders` | FK hazırlık kontrolüyle entity save | Child kayıt, parent SQL Server’da kalıcı olana kadar bekler |
| Sipariş durum güncelleme | `PATCH /api/orders/{id}/status` | Entity oku, nesneyi değiştir, kaydet | Partial update açık full-entity save olarak uygulanır |
| Sipariş silme | `DELETE /api/orders/{id}` | Repository delete | Aktif cache kaydını kaldırır ve kalıcı delete işini sıraya alır |

## İlk Gün Kullanımı ve Yük Büyüdükçe Yapılacaklar

| Aşama | Veri şekli | Yapılacak iş |
|---|---|---|
| İlk gün lokal demo | 20 müşteri, müşteri başına 40 sipariş, sipariş başına 4 satır | Seed çalıştır, API cevaplarını incele, yönetim ekranını aç, generated binding mantığını öğren |
| İlk staging denemesi | Binlerce müşteri, gerçeğe yakın sipariş dağılımı | API limitlerini projection penceresinin altında tut, SQL Server indekslerini doğrula, projection gecikmesini izle |
| Trafik artışı | Çok sayıda müşteri sürekli sipariş listesi açar | Redis `maxmemory` değerini artır, `hotEntityLimit` değerini bellek bütçesine göre ayarla, listeyi projection’da tut |
| Büyük müşteri fan-out | Bazı müşterilerde binlerce sipariş oluşur | `Customer -> tüm Orders` yükleme; `OrderSummary` timeline ve açık sipariş detayı kullan |
| Dashboard büyümesi | Global sıralı ve KPI ekranları artar | Full entity tekrar kullanmak yerine route’a özel projection ekle |
| SQL Server yoğunluğu | Lock beklemeleri veya pool baskısı görünür | Hikari pool, lock timeout, query timeout ve batch size birlikte ayarlanmalı |
| Çok podlu runtime | Birden fazla application container çalışır | Unique consumer name ve leader lease ayarlarını açık tut |

## Tuning Rehberi

Önce örnekteki ayarlarla başla, sonra ölçüme göre değiştir:

| Sinyal | Nereden bakılır? | Aksiyon |
|---|---|---|
| Redis belleği hızlı büyüyor | Yönetim ekranı, Redis `INFO memory`, `/api/tuning` | Aktif pencereyi daralt, hot policy’yi sıkılaştır, projection payload’ını küçült |
| Sipariş listesi yavaş | API latency ve route etiketi | Route’un entity fallback değil `projection:order-summary` kullandığını doğrula |
| SQL Server okuma yükü artıyor | SQL Server metrikleri, slow query kanıtı | Tekrarlanan liste ekranlarını projection’a taşı, route indekslerini ekle |
| Write-behind backlog büyüyor | Yönetim ekranındaki write-behind bölümü | Worker/batch ayarını dikkatli artır, SQL Server lock durumunu incele, backpressure uygula |
| Projection gecikmesi büyüyor | Yönetim ekranındaki projection telemetry | Refresh baskısını azalt veya projection’ları route bazında ayır |
| Response payload büyüyor | API payload boyutu | Controller limitini düşür, detay için ayrı follow-up endpoint kullan |
| Lock beklemeleri artıyor | SQL Server wait stat ve uygulama timeout kayıtları | Batch size düşür, isolation ayarını gözden geçir, transaction süresini kısa tut |

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

## CachePolicy Parametreleri Nasıl Ayarlanır?

Cache ayarını şu sırayla yap. İlk refleks Redis belleğini büyütmek olmamalıdır.

1. Route sözleşmesini belirle: endpoint limiti, sıralama, detay/önizleme ayrımı ve projection gerekip gerekmediği.
2. Redis bütçesini belirle: `maxmemory`, `maxmemory-policy=noeviction`, uyarı eşiği ve kritik eşik.
3. Redis’e kabul kuralını belirle: hangi kayıtlar aktif veri setine girebilir?
4. Redis’te kalma kuralını belirle: kayıt sayısı, zaman penceresi, durum penceresi ve TTL davranışı.
5. Yazma baskısını belirle: write-behind worker sayısı, batch boyutu, retry ve backlog eşikleri.

### CachePolicy Parametreleri

| Parametre | Neyi kontrol eder? | Ne zaman artırılır? | Ne zaman azaltılır? | Production önerisi |
|---|---|---|---|---|
| `hotEntityLimit` | Varsayılan `CachePolicy` için aktif entity penceresini sınırlar. Redis büyümesine karşı ilk kaba sınırdır. | Redis’te boşluk varsa ve sınırlı aktif okuma route’larında miss yüksekse. | Redis belleği hızlı büyüyorsa veya tek route belleği domine ediyorsa. | Bellek bütçesinden hesapla: ortalama entity payload + indeks maliyeti x beklenen hot satır. Projection window yerine kullanılmamalıdır. |
| `pageSize` | Çağıran taraf daha sıkı limit vermediğinde kullanılan varsayılan sayfa boyutudur. | Normal ekranlar daha büyük ilk sayfa istiyorsa ve payload küçükse. | API response büyüyorsa veya UI küçük bir ilk sayfa gösteriyorsa. | Gerçek UI sayfa boyutuna yakın tut; çoğu ekranda `50-100` yeterlidir. Büyük export işleri entity page cache kullanmamalıdır. |
| `lruEvictionEnabled` | Count window aşıldığında eski aktif kayıtların dışarı itilmesine izin verir. | Geniş bir çalışma seti varsa ve normal cache churn kabul edilebiliyorsa. | Katı hot policy davranışı istiyorsan ve sessiz churn istemiyorsan. | Çoğu online iş yükünde açık kalabilir; yine de Redis `maxmemory` ve route limitleri zorunludur. |
| `entityTtlSeconds` | Full entity kayıtları için opsiyonel TTL’dir. `0`, entity’nin TTL ile düşmeyeceği anlamına gelir. | Veri geçiciyse, yeniden üretilebiliyorsa veya cold-load güvenliyse. | İş detayı stabil kalmalıysa ve hot-set davranışı policy ile yönetilecekse. | Kalıcı iş entity’lerinde genelde `0` kullan; TTL’i ephemeral view, session veya kısa ömürlü operasyon kayıtlarında kullan. |
| `pageTtlSeconds` | Cache’lenen sayfa/sorgu sonuçlarının TTL değeridir. | Liste sonuçları tekrar kullanılıyorsa ve kısa süreli bayatlık kabul edilebiliyorsa. | Liste çok sık değişiyorsa veya eski sıralama kullanıcıya görünüyorsa. | Kısa tut; genelde `30-120s`. Projection satırları sayfa cache’inden daha uzun yaşayabilir. |
| `hotPolicy` | Bir satırın Redis’e girip giremeyeceğini belirleyen kabul kuralıdır. | Basit LRU yerine iş kuralına göre cache istiyorsan. | Kural çok fazla kayıt kabul ediyorsa veya önemli okumaları kaçırıyorsa. | Composite policy tercih et: yakın zaman OR aktif durum OR özel iş kuralı. |

### Hot Policy Modları

| Mod | Ne zaman kullanılır? | Örnek | Risk |
|---|---|---|---|
| `COUNT_WINDOW` | Basit ve sayıyla sınırlı hot set yeterliyse. | Son `N` ürün veya kategori kaydını tutmak. | İş önemini bilmez; gürültülü route’lar faydalı kayıtları dışarı itebilir. |
| `TIME_WINDOW` | Gerçek iş kuralı yakın zamansa. | `order_date` ile son 90 gün siparişlerini tutmak. | Eski ama operasyonel olarak aktif kayıtlar state policy ile birleşmezse dışarıda kalabilir. |
| `STATE_WINDOW` | Kaydın durumu hot olmasını belirliyorsa. | `OPEN`, `PENDING`, `PAID`, `ACTIVE` kayıtlarını tutmak. | Büyük status kümeleri çok fazla veri kabul edebilir. Count veya time sınırıyla birlikte düşünülmelidir. |
| `CUSTOM_PREDICATE` | Hot olma kararı domain mantığına bağlıysa. | VIP müşteri siparişlerini veya tenant’a özel premium route’ları kabul etmek. | Okuması ve işletmesi daha zordur; gerçek veri dağılımıyla test edilmelidir. |
| `COMPOSITE` | Gerçek sistemde birden fazla kural gerekiyorsa. | Son 90 gün OR aktif durum OR aktif ürün. | `ANY` fazla veri kabul edebilir; `ALL` fazla veri reddedebilir. Staging bellek ölçümüyle doğrulanmalıdır. |

Örnek projede `ANY` kullanılır; çünkü bir sipariş ya yakın tarihli olduğu için ya da operasyonel olarak aktif olduğu için önemli olabilir. Daha sıkı bellek profilinde `ALL` ancak kayıt tüm child kuralları sağlamalıysa seçilmelidir.

### Admission Source Bayrakları

`EntityHotPolicy` kaydın hangi kaynaktan Redis’e kabul edilebileceğini de yönetir:

| Bayrak | Anlamı | Ne zaman değiştirilir? |
|---|---|---|
| `admitOnWrite` | Yeni yazılan kayıt Redis’e alınabilir. | Sadece write-heavy ama cold olan verinin Redis’i kirletmesini istemiyorsan kapat. |
| `admitOnRead` | Cold read sonucu Redis’e promote edilebilir. | Arşiv route’larında tek seferlik okumaların hot olmasını istemiyorsan kapat. |
| `admitOnWarm` | Warm/backfill işleri Redis’i doldurabilir. | Warm job sadece SQL doğrulaması yapacaksa ve Redis’i değiştirmemeliyse kapat. |
| `evictWhenRejected` | Kayıt artık policy’ye uymuyorsa aktif setten düşürülebilir. | Policy geçişinde daha yumuşak davranış istiyorsan geçici olarak kapat. |

### Read Guardrail Parametreleri

Cache policy Redis’te neyin kalabileceğini belirler. Read guardrail ise çağıranın ne isteyebileceğini sınırlar.

| Parametre | Production kullanımı |
|---|---|
| `maxEntityQueryLimit` | Düşük tutulmalıdır. Entity query full object hydrate eder ve relation işini tetikleyebilir. Normal ekranlarda `100-250` bandı uygundur. |
| `maxProjectionQueryLimit` | Projection payload küçük olduğu için daha yüksek olabilir. Timeline ve dashboard pencerelerinde kullanılır. |
| `hotSetHeadroom` | İstenen pencereyle hot-set sınırı arasında boşluk bırakır. Sorgular sık sık hot window kenarına geliyorsa artırılır. |
| `rejectEntityQueryOverLimit` | Açık kalmalıdır. Route daha çok satır istiyorsa global limiti artırmak yerine projection route tasarlanmalıdır. |
| `rejectProjectionQueryOverLimit` | Açık kalmalıdır. Sadece payload boyutu ölçülmüş belirli projection route’larında artırılmalıdır. |

### Redis Guardrail Parametreleri

Redis sınırsız heap değildir; sınırlandırılmış runtime bağımlılığıdır.

| Parametre | Production kullanımı |
|---|---|
| `usedMemoryWarnMaxmemoryPercent` | Cache büyümesini yavaşlatmak için ilk sinyaldir. Başlangıç için `%70-80` uygundur. |
| `usedMemoryCriticalMaxmemoryPercent` | Kritik baskı seviyesidir. Başlangıç için `%85-90` uygundur; üstünde read/write shedding alanına yaklaşılır. |
| `expectedMaxmemoryPolicy` | Bu modelde `noeviction` kullanılmalıdır. Neyin tutulacağına CacheDB karar vermeli; Redis rastgele gerekli key düşürmemelidir. |
| `producerBackpressureEnabled` | Açık kalmalıdır; Redis baskı altındayken producer tarafı yavaşlar. |
| `writeBehindBacklogWarnThreshold` | SQL Server flush gecikmesi kullanıcıya görünmeden önce alarm üretir. Daha sıkı consistency beklentisinde düşürülür. |
| `writeBehindBacklogCriticalThreshold` | Kritik backlog seviyesidir. Bu noktada SQL Server lock, pool saturation ve batch size incelenmelidir. |

### Write-Behind Parametreleri

Write-behind tuning, cache admission’dan ayrı düşünülmelidir. SQL Server yavaşlığını Redis’e daha az veri alarak çözmeye çalışma; Redis belleği de problemse ayrıca ele al.

| Parametre | Ne zaman artırılır? | Ne zaman azaltılır? |
|---|---|---|
| `workerThreads` | Backlog büyüyor ve SQL Server tarafında boş connection/CPU varsa. | SQL Server pool doluyorsa veya lock beklemeleri artıyorsa. |
| `batchSize` / `maxFlushBatchSize` | Backlog büyüyor ve SQL Server batch yazmayı iyi kaldırıyorsa. | Latency zıplıyorsa, lock artıyorsa veya transaction çok büyüyorsa. |
| `tableAwareBatchingEnabled` | Genelde açık kalır; yazıları tabloya göre gruplayarak flush davranışını iyileştirir. | Nadiren, sadece debugging veya provider’a özel problem varsa kapatılır. |
| `coalescingEnabled` | Genelde açık kalır; aynı entity üzerindeki tekrar eden update’leri birleştirebilir. | Her ara durumun kalıcı olarak görünmesi gerekiyorsa kapatılır. |
| `maxFlushRetries` / `retryBackoffMillis` | Deploy veya failover sırasında transient SQL Server hataları varsa. | Retry kalıcı hataları saklıyorsa ve DLQ büyüyorsa. |

### MSSQL’e Özel Notlar

SQL Server için cache policy ve write-behind tuning, lock davranışıyla birlikte doğrulanmalıdır:

| Ayar | Öneri |
|---|---|
| Hikari `maximum-pool-size` | Sadece artırma. SQL Server CPU, worker thread ve lock beklemeleriyle birlikte ayarla. |
| `lock-timeout-millis` | Sonlu kalmalıdır; bloklanan flush işlemleri worker thread’i sonsuza kadar tutmak yerine hata alıp retry edebilmelidir. |
| `query-timeout-seconds` | Liste ve warm route’ları için sonlu kalmalıdır. Uzun süren sorgu ya indekslenmeli ya projection’a taşınmalıdır. |
| `transaction-isolation` | Muhafazakâr başla, sonra kendi contention profilinle doğrula. Daha yüksek isolation daha fazla blocking yaratabilir. |
| Batch size | SQL Server, PostgreSQL’den farklı tepki verebilir. Sadece wait stat ve flush latency kontrolünden sonra artırılmalıdır. |

### Pratik Profiller

| Profil | Ne zaman kullanılır? | Ayar yönü |
|---|---|---|
| Küçük admin uygulaması | Trafik düşük, listeler sınırlı | `hotEntityLimit=1_000`, `pageSize=50`, kısa page TTL, düşük query limit |
| Ticaret timeline ekranı | Müşteri sipariş geçmişi sürekli büyür | Projection zorunlu, `maxProjectionQueryLimit=1_000`, entity detay limiti `100-250`, 90 gün veya aktif durum hot policy |
| Dashboard/KPI | Tekrarlanan global sıralı okumalar vardır | Dedicated projection, kısa page TTL, sıkı entity query limiti, ranked projection alanları |
| Arşiv okuma | Eski kayıtlar nadiren okunur | `admitOnRead` kapatılabilir, entity TTL kısa veya `0`, okuma SQL Server cold path’ten yapılır |
| Yüksek yazma trafiği | Yazılar burst şeklinde gelir | Write-behind worker ve batch ayarlanır, Redis guardrail sıkı tutulur, backlog izlenir |

## MSSQL Provider Ayarları

Temel provider seçimi `application.yml` içindedir:

```yaml
cachedb:
  sql:
    provider: mssql
```

Örnek ayrıca MSSQL’e özel kilit ve timeout ayarları verir:

```yaml
cachedb:
  sql:
    mssql:
      lock-timeout-millis: 5000
      query-timeout-seconds: 10
      transaction-isolation: serializable
```

Gerçek ortamda `tempdb` yerine ayrı bir uygulama veritabanı kullanılmalıdır. Hikari pool boyutu hedef trafiğe göre ayarlanmalı ve kilit davranışı kendi SQL Server topolojinizde doğrulanmalıdır.

Örnekteki aktif veri politikası, kaydı son 90 günlük sipariş penceresindeyse veya `ACTIVE`, `NEW`, `PAID`, `OPEN`, `PENDING` gibi aktif operasyon durumlarından birini taşıyorsa Redis’e kabul eder.

## Neden Projection Kullanılıyor?

Bir müşterinin sipariş sayısı zamanla binleri bulabilir. Liste ekranında `Customer -> tüm Orders -> tüm Lines` yüklemek production için doğru değildir. Bu örnekte liste ekranı `OrderSummary` üzerinden döner; kullanıcı tek bir siparişi seçtiğinde detay ayrıca yüklenir.

BEST:

- Müşteri sipariş listesi ve yüksek değerli sipariş listesi için `OrderSummary` kullan.
- API `limit` değerini projection penceresinin içinde tut.
- `OrderEntity` ve satır ilişkisini sadece detay ekranında yükle.
- SQL Server’ı tam geçmiş için kalıcı kaynak olarak bırak.

ANTI-PATTERN:

- Tek response içinde müşterinin tüm siparişlerini ve tüm satırlarını döndürmek.
- Sınırsız `findAll` benzeri endpoint açmak.
- PostgreSQL ve SQL Server’ın kilit, isolation ve batch davranışının bire bir aynı olduğunu varsaymak.

## Postman

İçe aktarılacak dosya:

```text
postman/cache-database-mssql-sample.postman_collection.json
```

Koleksiyon; sağlık kontrolü, veri üretme, müşteri sipariş listesi, detay, dashboard, update, delete ve tuning çağrılarını içerir.

## SQL Server Notları

Şema `src/main/resources/schema.sql` ile kurulur. Primary key, foreign key ve aktif route’lar için indeksler vardır:

- `sample_orders(customer_id, order_date DESC, order_id DESC)`
- `sample_orders(priority_score DESC, order_date DESC)`
- `sample_order_lines(order_id, line_number)`
- `sample_support_tickets(status, priority, updated_at DESC)`

Seed endpoint’i veriyi CacheDB üzerinden yazar ve child kayıtları yazmadan önce parent kayıtların SQL tarafına düşmesini bekler. Bunun nedeni şemada foreign key bulunmasıdır.

`POST /api/orders` da müşteri satırı SQL Server tarafında kalıcı hale gelene kadar kısa süre bekler. Müşteri az önce oluşturulduysa ve write-behind henüz flush etmediyse endpoint `409` döner; client kısa süre sonra tekrar denemelidir.

## Sorun Giderme

SQL Server’ın hazır hale gelmesi Redis’e göre daha uzun sürebilir. İlk başlatmada hata alırsan SQL Server healthcheck tamamlandıktan sonra `mvn spring-boot:run` komutunu tekrar çalıştır.

Dependency çözümleme hatasında `pom.xml` içindeki paket repository erişimini kontrol et.

Seed sonrası liste hemen boş dönerse birkaç saniye bekleyip tekrar dene. Projection yenileme arka planda çalışır.
