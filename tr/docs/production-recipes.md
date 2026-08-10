# Production Reçeteleri

Bu rehber tek bir soruya cevap verir:

Production'da çalışan ekip hangi durumda hangi CacheDB kullanım yüzeyini seçmeli?

Daha üst seviye konumlandırma için [CacheDB Bir ORM Alternatifi Olarak](./orm-alternative.md)
dokümanını oku. Kavramlar, relation/projection farkı ve route contract tanımı
için [Kavramlar ve Kabuller](./kavramlar-ve-kabuller.md) sayfasına bak.
Property seçmeden önce production ayar mantığını görmek istiyorsan
[Production Tuning Rehberi](./production-tuning-rehberi.md) ile başla.
Repo'yu dış kullanıcıya açma hazırlığı için [Production GA Criteria](../../PRODUCTION_GA_CRITERIA.md),
[Stable Release Launch Kit](./stable-release-launch-kit.md) ve [Release Checklist](./release-checklist.md)
dokümanlarına bak.

CacheDB'nin temel önceliği değişmez:

- çalışma zamanı ek yükü düşük kalmalı
- API, gerçek uygulama ekiplerinin kullanabileceği kadar ergonomik olmalı
- pahalı okuma şekilleri projection ve okuma modeliyle sınırlanmalı

## 30 Saniyelik Seçim

Kısa kural:

- Yeni production servislerinde her aggregate veya route grubu için bir `@CacheRepository` arayüzü tanımla.
- Yalnızca Redis'ten çalışacak okumaları `@CacheLookup` veya `@HotRoute` ile belirt; kalıcı geçmişi `@SourceRoute` ile ayır.
- Çok ilişkili listelerde, yönetim panellerinde ve global sıralamada projection döndür.
- Önceden yüklenmesi gereken her route için aynı sorgudan türeyen bir `@WarmRoute` tanımla.
- Generated binding ve düşük seviyeli repository'leri yalnızca ölçülmüş altyapı veya uyumluluk işlerinde kullan.
- Global sıralı ekranlarda ranked projection kullan.

## Kullanım Senaryosu Rehberi

| Senaryo | Önerilen tasarım | Kaçınılacak tasarım |
| --- | --- | --- |
| Müşteri detayında 1.000+ sipariş | Customer root entity, order summary projection, root başına sınırlı Redis penceresi | İlk ekranda tam müşteri veri grafiğini ve tüm order line'ları yüklemek |
| Çok satırlı sipariş detayı | Order detail'i açıkça yükle, yalnızca küçük `orderLines` önizlemesi preload et | Kullanıcı istemeden yüzlerce/binlerce satırı yüklemek |
| Global "en yüksek değerli siparişler" yönetim paneli | Önceden hesaplanmış iş skoru olan ranked projection | Geniş entity scan yapıp bellek içinde sıralamak |
| Admin CRUD ekranı | Açık detay ve komut metotları olan deklaratif repository | Redis'te bulunmayan kaydı kalıcı 404 gibi yorumlamak |
| Write-behind repair veya replay worker | Açık limit, retry ve checkpoint içeren düşük seviyeli repository | Operasyonel işi normal istek route'u arkasına saklamak |
| Mevcut ORM akışının geçişi | Geçiş Planlayıcı, dry-run ön ısıtma, staging ön ısıtma, yan yana karşılaştırma | Sadece Redis hızlı diye kör canlı geçiş yapmak |

En yaygın hata Redis'i sihirli bir tam veri grafiği cache'i gibi kullanmaktır.
CacheDB, okuma modelini seçilen SQL provider'daki kalıcı geçmişten bilinçli
olarak daha küçük tasarlandığında daha iyi sonuç verir.

## Projection Ne Zaman Şarttır?

Şu şekillerde projection'ı öneri değil, tasarım gereği zorunlu gibi düşün:

- global sorted veya range odaklı liste ekranları
- ilk ekranda bütün veri grafiğini istemeyen liste ve yönetim paneli ekranları
- yalnızca küçük child önizlemesi gösteren relation satırları
- geniş aday kümesinde kararlı bir iş sıralaması isteyen ekranlar

Son durumda `rank_score` gibi projection'a özel bir sıralama alanı üret ve
sorguyu tek bir sorted index üzerinden kur. Büyük global listelerde pahalı
multi-sort sınır maliyetinden kaçmanın production dostu yolu budur.

Projection üzerinde bu niyeti açıkça belirt:

```java
EntityProjection<DemoOrderEntity, HighLineOrderSummaryReadModel, Long> projection =
        EntityProjection.<DemoOrderEntity, HighLineOrderSummaryReadModel, Long>builder(...)
                .rankedBy("rank_score")
                .asyncRefresh()
                .build();
```

Bu tanım CacheDB'ye projection'ın önceden sıralanmış bir iş alanı taşıdığını
söyler. Böylece projection repository, geniş candidate scan'e düşmeden ranked
top-window yolunu kullanabilir.

## Karar Akışı

```mermaid
flowchart TD
    A["Route kalıcı geçmişe ihtiyaç duyuyor mu?"] -->|Evet| B["Sınırlı bir SourceRoute tanımla"]
    A -->|Hayır| C["Route yalnızca Redis'ten mi çalışmalı?"]
    C -->|Evet| D["CacheLookup veya HotRoute tanımla"]
    C -->|Hayır| B
    D --> E["Sonuç liste, yönetim paneli veya global sıralama mı?"]
    E -->|Evet| F["Projection döndür ve eşleşen WarmRoute ekle"]
    E -->|Hayır| G["Sınırlı entity lookup veya pencere döndür"]
```

## Karar Tablosu

| Durum | Önerilen yüzey | Neden | Ne zaman aşağı inilir? |
| --- | --- | --- | --- |
| Tipik iş CRUD'u ve servis katmanı | `@CacheRepository`, inherited komutlar ve açık lookup metotları | Tek enjekte edilen sözleşme, derleme zamanı kontrolü, reflection yok | Altyapı kodu daha düşük seviye kontrol istemedikçe bu yüzeyde kal |
| Kritik liste veya yönetim paneli | Projection döndüren `@HotRoute` | Route kendi sayfa, pencere, coverage ve bellek sözleşmesini taşır | Sözleşmeyi atlamak yerine projection'ı yeniden tasarla |
| Kalıcı arşiv veya denetim geçmişi | Sınırlı `@SourceRoute` ya da gözden geçirilmiş `@SourceSql` | SQL erişimi görünür kalır ve Redis'i kendiliğinden doldurmaz | Okumayı saklamak yerine index, timeout ve satır limitini ayarla |
| Çok ilişkili detay | Derleme zamanında sınırlı ilişki önizlemesi olan `@CacheLookup` | Tam veri grafiği yüklemesini ve N+1 davranışını önler | Ek detayı ayrı bir route ile getir |
| Replay, recovery ve iç worker'lar | Düşük seviyeli repository veya provider adaptörü | Retry, checkpoint ve operasyonel durum açık kalır | Bu yüzeyi normal istek koduna açma |

## Resmi Öneri Merdiveni

1. Entity üzerinde tablo eşlemesini ve ilişki metadata'sını tanımla.
2. Uygulama davranışını enjekte edilebilir bir `@CacheRepository` arayüzüne taşı.
3. Redis, kalıcı kaynak, warm ve komut metotlarını açıkça ayır.
4. Tam entity penceresini büyütmeden önce projection kullan.
5. Generated binding veya provider repository'sine yalnızca ölçülmüş altyapı işlerinde in.

Bu yaklaşım uygulama API'sini sade tutarken sorgu şeklini ve çalışma politikasını
derleme zamanında görünür kılar.

## Çok Pod Koordinasyon Smoke'u

Yeni bir Kubernetes reçetesine güvenmeden önce aynı Redis/kaynak veritabanı
çifti üzerinde local multi-instance coordination smoke'u bir kez çalıştır.
Aşağıdaki komut varsayılan PostgreSQL demo provider'ını kullanır:

```powershell
.\tools\ops\cluster\run-multi-instance-coordination-smoke.ps1 `
  -RedisUri "redis://default:welcome1@127.0.0.1:56379" `
  -PostgresUrl "jdbc:postgresql://127.0.0.1:55432/postgres"
```

Bu smoke şu davranışları doğrular:

- consumer group'lar ortak kalırken consumer name'lerin instance-unique olması
- cleanup/history/report gibi singleton döngülerin Redis leader lease ile tek node'da çalışması
- abandon olmuş write-behind pending işinin başka bir instance tarafından claim edilip drain edilmesi

Neden önemli:

- shared Redis stream modelinde doğruluk unique consumer kimliğine bağlıdır
- singleton operasyon döngüleri gerçekten singleton kalınca cluster gürültüsü azalır
- çok pod deploy öncesi koordinasyon regresyonlarını yakalamanın en hızlı yolu budur

Local not:

- aynı workstation üzerinde `HOSTNAME` genelde tüm process'ler için aynıdır
- Kubernetes pod'larında pod hostname'leri zaten unique gelir
- localde birden fazla process kaldırıyorsan açık `cachedb.runtime.instance-id` ver ya da smoke runner'ı kullan

## Benchmark Nasıl Okunmalı?

Reçete benchmark'ı şu üç CacheDB kullanım stilini aynı repository yolu üzerinde
karşılaştırır:

- `JPA-style domain module`
- `Generated entity binding`
- `Minimal repository`

Çalıştırmak için:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

Çıktılar:

- `target/cachedb-prodtest-reports/repository-recipe-comparison.md`
- `target/cachedb-prodtest-reports/repository-recipe-comparison.json`

Önemli not:

- bu benchmark CacheDB API yüzeyi ek yükünü ölçer
- dış Hibernate/JPA runtime maliyetini ölçmez
- Redis/kaynak veritabanı üzerindeki end-to-end production senaryolarının yerine geçmez

Son yerel ölçümün özeti:

- `Generated entity binding`: bu yerel koşuda ortalamada en hızlı yüzey
- `Minimal repository`: bu yerel koşuda en düşük p95
- `JPA-style domain module`: gruplanmış ergonomik yüzey, makul wrapper maliyeti

Çıkarım:

- ergonomik yüzeyler sıfır maliyetli değildir
- ancak doğrudan repository kullanımıyla aynı düşük ek yük bandında kalır
- çoğu iş kodunu erkenden minimal repository stiline zorlamak doğru değildir
- asıl production maliyeti sorgu şekli, ilişki yükleme, Redis contention ve write-behind baskısından gelir

## Ekip Tipine Göre Tavsiye

### Ürün Servis Ekipleri

Enjekte edilen bir `@CacheRepository` ile başla.

Bu yol şunları birlikte verir:

- küçük ve iş alanına özel bir arayüz
- otomatik Spring bean kaydı
- derleme zamanında route ve parametre kontrolü
- Redis, kaynak veritabanı, warm ve komut davranışlarının açık ayrımı

### Birkaç Kritik Endpoint'i Olan Ekipler

Tek repository sözleşmesinde kal. Ölçülen kritik route'u `@HotRoute`, projection,
sınırlı `WindowRequest` ve eşleşen `@WarmRoute` ile açık hale getir.

Bu genelde en iyi orta noktadır:

- servis kodunun geri kalanı okunabilir kalır
- kritik endpoint ölçülebilir bellek ve coverage sözleşmesi kazanır
- route sessizce pahalı entity taramasına düşmez

### Platform, Worker ve Operasyon Ekipleri

Ürün route'larında generated repository kullan. Düşük seviyeli
`EntityRepository` / `ProjectionRepository` yüzeyine yalnızca framework,
repair, replay veya migration altyapısı geliştirirken in.

Bu yol şu durumlarda daha doğrudur:

- kod ürün endpoint'inden çok operasyonel akış ise
- helper ergonomisinden çok açıklık gerekiyorsa
- replay, repair veya batch mantığında en küçük abstraction yüzeyi isteniyorsa

## JPA/Hibernate'ten Geçiş Yolu

JPA/Hibernate alışkanlığındaki lazy-loading ve örtülü SQL davranışını CacheDB
içinde yeniden üretme.

Şu geçiş yolunu kullan:

1. Kalıcı tablo eşlemesini ve ilişkileri entity üzerinde tanımla.
2. Her uygulama route'u için bir repository metodu tanımla.
3. Geniş eager okumaları projection ve açık detay lookup modeline çevir.
4. Arşiv ve geçmiş okumalarını sınırlı kaynak route'larına taşı.
5. Trafiği değiştirmeden önce warm route ekle ve coverage kanıtla.
6. Veri eşitliği, gecikme, bellek ve rollback kontrolleri geçene kadar eski ORM route'unu açık tut.

Bu yol ekiplerin zihinsel modelini tamamen bozmaz, ama onları daha düşük ek
yüklü sorgu şekillerine yönlendirir.

## Reçete 1: Varsayılan Servis Ekibi

Şu durumlarda kullan:

- hızlı başlangıç istiyorsan
- ekip JPA/Hibernate benzeri alışkanlıktan geliyorsa
- endpoint'lerin çoğu normal CRUD veya filtreli liste ise

Önerilen yüzey:

```java
@CacheRepository(entity = UserEntity.class)
public interface UserRepository extends CacheDbRepository<UserEntity, Long> {

    @HotRoute(value = "active-users", pageSize = 25, hotWindow = 10_000,
            memoryBudgetBytes = 16_777_216L)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", constants = "ACTIVE"),
            orderBy = @CacheOrder(field = "username"),
            windowParameter = "window"
    )
    HotWindow<UserEntity> active(WindowRequest window);
}

List<UserEntity> activeUsers = users.active(WindowRequest.first(25)).completeItems();
```

Neden varsayılan:

- derleme zamanında generated
- reflection scan yok
- çalışma zamanında metadata keşfi yok
- wrapper maliyeti production için yeterince düşük

## Reçete 2: Kritik Endpoint, Daha Açık Entity Sahipliği

Şu durumda kullan:

- tek bir ekran veya API gecikme hassas hale geldiyse
- hâlâ generated helper kullanmak istiyorsan
- package-level module'dan biraz daha açık entity yüzeyi istiyorsan

Önerilen yüzey:

```java
var users = UserEntityCacheBinding.using(session);
List<UserEntity> activeUsers = users.queries().activeUsers(25);
```

Neden:

- bir grouping katmanı daha azdır
- entity kontratının sahibi daha nettir
- hâlâ derleme zamanında generated ve düşük ceremony taşır

## Reçete 3: Çok İlişkili Read Model

Şu durumda kullan:

- order summary, preview line veya yönetim paneli satırı gibi ekranların varsa
- full entity hydration pahalıya mal oluyorsa
- ilk ekran bütün veri grafiğini istemiyorsa

Önerilen desen:

1. Summary listesini projection repository ile sorgula.
2. Detayı kullanıcı istediğinde açıkça yükle.
3. İlişki önizlemelerini `@CacheLookup(maxRelationRows=...)` ile sınırla.
4. Global top-N veya threshold odaklı ekranlarda geniş entity query yerine projection'a özel ranked alan kullan.
5. Bu ranked alanı `rankedBy(...)` ile işaretle.
6. Full entity page/result boyutunu `hotEntityLimit` altında tut; pencere büyük olmak zorundaysa bunu projection penceresi olarak tasarla.

Örnek:

```java
List<OrderSummary> topOrders = orders.customerTimeline(
        customerId,
        WindowRequest.first(24)
).completeItems();

OrderEntity order = orders.detail(orderId, 8)
        .orElseThrow(status -> mapHotLookupFailure(orderId, status));
```

Müşteri timeline ekranı için production'a uygun şekil şudur:

- Redis müşteri root entity'sini tutar
- Redis müşteri başına sınırlı order summary projection penceresini tutar; örneğin son 1.000 özet
- Seçilen SQL provider bütün order geçmişi ve arşiv okumaları için kalıcı kaynak olarak kalır
- detay ekranı bütün müşteri veri grafiğini değil, tek order'ı veya küçük bir relation önizlemesini açıkça okur

Entity hot set için kabul kuralını açık seç:

- `COUNT_WINDOW` varsayılandır; son erişilen veya yazılan entity id'lerini `hotEntityLimit` sınırına kadar Redis'te tutar
- `TIME_WINDOW`, iş kuralı "son 90 günün order kayıtları Redis'te tutulsun" ise doğru tercihtir
- `STATE_WINDOW`, yalnız `OPEN` ve `PENDING` gibi durumların Redis'te kalması gerektiğinde kullanılır
- `COMPOSITE`, Redis kabul kararı birden fazla kurala bağlıysa kullanılır; örneğin "son 90 gün içinde ve `OPEN/PENDING` durumunda"
- `CUSTOM_PREDICATE`, VIP müşteri veya tenant bazlı özel Java predicate kuralları için ayrılır

Örnek:

```java
CachePolicy recentOrders = CachePolicy.builder()
        .hotEntityLimit(100_000)
        .pageSize(100)
        .hotPolicy(EntityHotPolicy.builder()
                .mode(EntityHotPolicyMode.TIME_WINDOW)
                .timeColumn("order_date")
                .hotForDays(90)
                .admitOnRead(false)
                .evictWhenRejected(true)
                .build())
        .build();
```

Bu, `entityTtlSeconds` ile aynı şey değildir. TTL, kaydın Redis'e yazıldığı ana göre çalışır. `TIME_WINDOW` ise entity payload içindeki iş tarihine göre karar verir.

Production hot set çoğu zaman tek kuralla açıklanmaz. Bunu uygulama kodunun
içinde gizli if/else olarak bırakma; policy içinde açık tanımla:

```java
CachePolicy activeRecentOrders = CachePolicy.builder()
        .hotEntityLimit(100_000)
        .hotPolicy(EntityHotPolicy.allOf(List.of(
                EntityHotPolicy.timeWindow("order_date", Duration.ofDays(90).toSeconds()),
                EntityHotPolicy.stateWindow("status", List.of("OPEN", "PENDING"))
        )))
        .build();
```

Bir satır birden fazla akış nedeniyle Redis'e alınabiliyorsa `EntityHotPolicy.anyOf(...)`
kullan. Örnek: "son 90 günün order'ı" veya "VIP müşterinin order'ı" veya
"destek ekibinin manuel takip ettiği order". `CUSTOM_PREDICATE` kullanacaksan
kural kısa, deterministik ve yerel kalmalıdır; predicate veritabanına veya uzak
servise gitmemelidir.

### Route Seviyesinde Cache Contract

Entity policy hangi satırın Redis'e girebileceğini söyler. Bir ekranın nasıl
okunacağını tek başına anlatmaz. Kritik production akışlarında route contract
tanımla:

```java
RouteCacheContract customerOrdersTimeline = RouteCacheContract.builder()
        .routeName("CustomerOrdersTimelineRoute")
        .entityName("OrderEntity")
        .projectionName("CustomerOrderSummaryHot")
        .pageSize(100)
        .hotWindow(1_000)
        .projectionRequired(true)
        .maxColdReadSize(100)
        .memoryBudgetBytes(256L * 1024L * 1024L)
        .strictMode(RouteCacheStrictMode.FAIL_FAST)
        .tenantQuota(new TenantCacheQuota("tenant_id", 50_000, 128L * 1024L * 1024L, true))
        .build();
```

Route contract şu durumlarda gereklidir:

- endpoint'in ilk sayfa boyutu belliyse
- Redis bütün tabloyu değil, sınırlı pencereyi tutacaksa
- projection yerine entity scan'e düşmek production için riskliyse
- tek tenant veya tek müşteri Redis belleğini tüketebilecekse

Production strict mode'da projection isteyen route, `entity:...` yoluna düşerse
fail-fast davranmalıdır. "Çalıştı ama pahalı yoldan çalıştı" staging sırasında
görülebilir; production için güvenli davranış değildir.

Runtime'da quota ve admission telemetry bu route'a yazılsın istiyorsan contract'ı
route çalışırken context'e koy:

```java
List<OrderSummaryReadModel> page = RouteCacheContext.supplyWithContract(
        customerOrdersTimeline,
        () -> summaries.query(customerOrdersQuery(customerId, 100))
);
```

`tenantQuota(...)` sınırlıysa CacheDB, mevcut route context'i için tenant hot-set
üyeliğini takip eder. Tenant satır limiti aşılırsa `evictOnBreach=true` iken en
eski tenant üyesi Redis'ten çıkarılır; aksi durumda yeni satır Redis'e kabul
edilmez. Memory budget kontrolüne tenant takip key'leriyle birlikte Redis'teki
gerçek entity payload byte ölçümü de dahildir. CacheDB kabul edilen entity key'i
için Redis `MEMORY USAGE` değerini ölçer ve tenant bazında payload sayacı tutar;
aynı entity tekrar yazıldığında bellek iki kez sayılmaz.

Bu bütçeyi kapasite hesabının tek kaynağı olarak değil, route seviyesinde bir
güvenlik sınırı olarak düşün. Staging warm bittikten sonra planner tahminini
gerçek Redis kullanımıyla yine karşılaştır; çünkü Redis object overhead,
projection key'leri, index key'leri ve page-cache key'leri de bellek tüketir.

Neden:

- ilk okumada geniş nesne grafiği oluşmasını engeller
- Redis payload ve decode maliyetini düşürür
- uygulama ekibi için API doğal kalır

Repo içi ölçüm yüzeyleri:

- özet liste, önizleme listesi ve tam veri grafiği maliyeti için `ReadShapeBenchmarkMain`
- ranked projection top-window ile geniş candidate scan farkı için `RankedProjectionBenchmarkMain`

### Kaynak Veritabanı Outbox / CDC Adapter Örnekleri

Kaynak veritabanı CacheDB dışında da değişebiliyorsa Redis'in kendiliğinden
güncel kalacağını varsayma. Gerçek bir feed kur: outbox, Debezium, Kafka veya
başka bir CDC kaynağı. Starter, varsayılan provider yolu için somut PostgreSQL
outbox adapter'ı sunar; MSSQL provider tarafında da SQL Server checkpoint
semantiğine uygun açıkça seçilen adapter yolu vardır.

Beklenen outbox şekli:

```sql
CREATE TABLE cachedb_outbox (
    id BIGSERIAL PRIMARY KEY,
    entity_name TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload_json TEXT,
    entity_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_source TEXT NOT NULL
);
```

Kullanım:

```java
PostgresOutboxExternalChangeFeedAdapter adapter =
        PostgresOutboxExternalChangeFeedAdapter.builder(dataSource)
                .adapterName("orders-cachedb-feed")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(500)
                .pollIntervalMillis(1_000)
                .build();

adapter.start(event -> {
    // ExternalChangeEvent'i açık bir CacheDB upsert/delete veya projection refresh komutuna çevir.
    // Sink idempotent olmalı; CDC feed'i hata sonrası aynı olayı tekrar verebilir.
});
```

Production kuralları:

- `id` monoton artmalı
- sink idempotent olmalı
- her mantıksal consumer için sabit bir `adapterName` kullanılmalı
- duplicate işleme güvenli değilse runner singleton veya leader-elected çalışmalı
- yerleşik parser için `payload_json` düz JSON olmalı; karmaşık payload gerekiyorsa ham değer `_payload` altında taşınır
- adapter domain tablolarını değiştirmez; yalnız outbox okur ve checkpoint tablosunu yazar

## Reçete 4: Kanıtlanmış Hotspot veya Batch Döngüsü

Bunu yalnız şu durumda kullan:

- profiling bu endpoint'in hâlâ kritik olduğunu gösteriyorsa
- sorgu ve fetch plan üzerinde tam kontrol istiyorsan
- kod daha çok altyapı veya operasyonel karakterdeyse

Önerilen yüzey:

```java
List<UserEntity> activeUsers = userRepository.query(
        QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(25)
);
```

Neden:

- en küçük abstraction yüzeyi
- allocation, limit ve sorgu şekli üzerinde en net kontrol

Bedeli:

- daha fazla ceremony
- uygulama kodunda daha fazla query/fetch glue

## Production Guardrail'ları

Hangi reçeteyi seçersen seç, production için şu kurallar geçerlidir:

- foreground repository Redis trafiğini background worker/admin Redis trafiğinden ayır
- liste ekranları ve yönetim panellerinde projection kullan
- eager geniş relation yerine özet sorgu + açık detay okuması kullan
- önizleme ilişkilerini `@CacheLookup` ile sınırla
- global sorted/range liste ekranlarını projection-first ele al
- iş sırası önemliyse pre-ranked projection alanı tercih et
- page/result boyutunu entity hot window altında tut; varsayılan read-shape guardrail bunu `hotSetHeadroom` ile zorlar
- hot kabul kuralı sadece son erişim sayısı değil, iş tarihi veya durum ise `hotPolicy.mode=TIME_WINDOW` ya da `STATE_WINDOW` kullan
- Redis kabul kararı iş tarihi, durum, tenant veya özel predicate birleşiminden oluşuyorsa `hotPolicy.mode=COMPOSITE` kullan
- kritik ekranlar için route-level cache contract tanımla: page size, Redis penceresi, projection zorunluluğu, cold-read üst sınırı, memory budget ve tenant quota
- büyük hot set'lerde warm/backfill işlerini checkpoint, resume, batch size, fetch size ve row-rate limit ile çalıştır
- staging warm bittikten sonra planner tahminini Redis `MEMORY USAGE` ve key-prefix breakdown ile kalibre et
- kaynak veritabanı CacheDB dışında da güncelleniyorsa CDC, Debezium, Kafka veya outbox adaptörü kullan
- "müşteri başına son 1.000 order" gibi sınırlı projection pencereleri için `readShapeGuardrail.maxProjectionQueryLimit` kullan
- CacheDB'ye ayrılmış Redis'te `maxmemory` ayarla ve `maxmemory-policy=noeviction` kullan; page-cache, read-through, hot-set ve query-index yazımlarını Redis'in rastgele eviction davranışına değil CacheDB guardrail'larına bırak
- normal uygulama kodunda deklaratif repository API'sini koru
- düşük seviyeli provider API'lerini yalnızca ölçülmüş altyapı işlerinde kullan
- yönetim arayüzünü ana runtime path'i şekillendiren yer değil, gözlem ve kontrol yüzeyi olarak düşün

## Kaçınılacak Pattern'ler

Şunlardan kaçın:

- her liste endpoint'inde tam veri grafiğini yüklemek
- ilk sorgu içinde yüzlerce relation child'ı tek seferde çekmek
- kötü liste şeklini projection'a taşımak yerine `hotEntityLimit` değerini sürekli büyütmek
- `entityTtlSeconds` değerini "son 90 iş günü" gibi yorumlamak; bunun için `TIME_WINDOW` kullan
- CacheDB belleğini kontrol etmek için Redis random veya all-keys eviction davranışına güvenmek
- foreground repository trafiği ile background worker'ları aynı Redis pool'da toplamak
- uygulama kodunun tamamında route sözleşmelerini düşük seviyeli repository çağrılarıyla atlamak
- Redis gecikmesini yalnızca Redis'in kendisiyle açıklamak

Çoğu zaman asıl maliyet sorgu şekli ve ne kadar büyük veri grafiği
yüklediğindir.

## Spring Boot Reçetesi

Çoğu production servis için iyi başlangıç:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
    background:
      enabled: true
```

Ardından generated registrar'lar entity'leri kaydetsin, repository processor ise
her `@CacheRepository` implementasyonunu Spring bean olarak oluştursun. Uygulama
servisleri yalnızca kendi domain repository arayüzünü enjekte etsin.

## Çok Pod'lu Kubernetes Reçetesi

Birden fazla application pod aynı Redis'e ve aynı kaynak veritabanı topolojisine
bağlandığında şu kuralları açık tut:

- consumer group'ları pod'lar arasında ortak bırak
- CacheDB'nin consumer adlarına otomatik instance id eklemesine izin ver
- cleanup/report/history gibi singleton döngüler için Redis leader lease'i açık tut
- worker thread ve flush parallelism değerlerini pod bazlı değil, cluster toplamı olarak hesapla
- Redis'i koordinasyon katmanının kritik bağımlılığı olarak düşün

Önerilen başlangıç:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://redis:6379
    background:
      enabled: true
  runtime:
    append-instance-id-to-consumer-names: true
    leader-lease-enabled: true
```

Bu varsayımla:

- write-behind, DLQ replay, projection refresh ve incident-delivery DLQ worker'ları ortak consumer group üzerinden yatay ölçeklenir
- consumer adları çözümlenmiş instance id sayesinde pod-unique olur
- cleanup/report/history döngüleri Redis lease ile singleton kalır
- pod düşmesi tek başına veri kaybı anlamına gelmez; pending stream işi başka pod tarafından claim edilebilir

Değişmeyen gerçekler:

- Redis hâlâ ana koordinasyon bağımlılığıdır
- async projection refresh eventual consistency taşır
- `at-least-once` delivery modelinde kaynak veritabanı version guard correctness'in parçası olmaya devam eder

## Kısa Playbook

Bir engineering playbook'a kopyalanacak kısa kural seti:

- varsayılan uygulama kodu: enjekte edilen `@CacheRepository`
- kritik listeler: `@HotRoute`, projection, sınırlı pencere ve coverage
- arşiv/geçmiş: sınırlı `@SourceRoute`; cache miss sonrası örtülü SQL fallback yok
- ön yükleme: aynı kritik route'tan türeyen `@WarmRoute`
- worker ve replay kodu: düşük seviyeli repository veya provider adaptörü
- liste ve yönetim paneli okumaları: önce projection, sonra gerekirse tam veri grafiği
- ilişki önizlemeleri: sınırlı `@CacheLookup`
- düşük seviyeli API: yalnızca profiling sonrası veya altyapı işinde

İlgili dokümanlar:

- [Kavramlar ve Kabuller](./kavramlar-ve-kabuller.md)
- [Spring Boot Starter](./spring-boot-starter.md)
- [Production Tuning Rehberi](./production-tuning-rehberi.md)
- [Tuning Parametreleri](./tuning-parameters.md)
- [Kullanım Senaryosu Örnekleri](./use-case-examples.md)
- [Production Tests](../../cachedb-production-tests/README.md)
- [CI production evidence workflow](../../.github/workflows/production-evidence.yml)
- [CI local runner](../../tools/ci/run-production-evidence.ps1)
