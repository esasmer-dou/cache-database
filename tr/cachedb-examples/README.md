# cachedb-examples

Bu modül, `cache-database` için çalıştırılabilir örnekler içerir.

## Yük Senaryosu Çalışma Alanı

Bu çalışma alanı aynı anda iki yüz açar:

- veri seed etmek ve yük profillerini başlatmak için Bootstrap + AJAX kontrol UI'i
- backlog, incident, memory ve routing izlemek için mevcut CacheDB admin dashboard

Demo domain:

- `DemoCustomerEntity`
- `DemoProductEntity`
- `DemoCartEntity`
- `DemoOrderEntity`
- `DemoOrderLineEntity`

UI görünümleri:

- customers
- products
- carts
- orders
- order lines

Varsayılan seed hacmi:

- customers: `1,800`
- products: `1,400`
- carts: `4,500`
- orders: `3,600`
- order lines: `54,000`
- toplam: `65.300`

Bu varsayılan profil, Spring Boot demo içinde daha interaktif kalırken yine de gerçeğe yakın bir e-ticaret dilimi hissi versin diye seçildi. Fiziksel hacmin büyük kısmı yine sipariş satırlarında kalır; ama toplam footprint daha küçük olduğu için `Seed`, `Clear`, `Fresh Start` ve `LOW / MEDIUM / HIGH` geçişleri tekrarlı gözlem koşularında daha kullanışlı kalır.

Yük profilleri:

- `LOW`: gündüz trafiğine yakın katalog gezme, tüm müşteri taraması ve hafif toplu sepet/ürün güncellemesi
- `MEDIUM`: büyük katalog okumaları, en çok sipariş veren müşterinin siparişleri ve dengeli toplu yazmalar
- `HIGH`: tüm müşteri taramaları, çok satırlı sipariş okumaları ve yoğun stok/sepet/sipariş dalgalari

Standalone demo çalıştırma:

```powershell
mvn -q -pl cachedb-examples -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.examples.demo.DemoLoadMain" `
  "-Dcachedb.demo.redisUri=redis://default:welcome1@127.0.0.1:56379" `
  "-Dcachedb.demo.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres" `
  "-Dcachedb.demo.jdbcUser=postgres" `
  "-Dcachedb.demo.jdbcPassword=postgresql"
```

Varsayılan URL'ler:

- demo load UI: `http://127.0.0.1:8090`
- admin dashboard: `http://127.0.0.1:8080/dashboard`

Spring Boot demo çalıştırma:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Spring Boot URL'leri:

- demo load UI: `http://127.0.0.1:8090/demo-load`
- admin dashboard: `http://127.0.0.1:8090/cachedb-admin?lang=tr`

Spring Boot notları:

- load UI ve admin dashboard aynı uygulama portunu kullanır
- Spring Boot modunda ikinci bir dahili admin HTTP server açılmaz
- aynı seed hacmi ve LOW / MEDIUM / HIGH senaryoları yeniden kullanılir
- ağır yuk altında standalone davranışını korumak için demo Redis pool varsayılan olarak genişletilir
- Spring Boot demo içinde foreground repository Redis trafiği ile background worker/admin trafiği ayrı pool'lara ayrılır
- `Start LOW / MEDIUM / HIGH` gizlice seed başlatmaz; veri hazır değilse UI doğrudan hata verir ve önce `Seed Demo Data` ister
- Spring Boot demo artık zero-glue generated registrar discovery kullanıyor; yani explicit `GeneratedCacheBindings.register(...)` çağrısı olmadan binding'ler otomatik kaydolur

## Read-Model Örneği

Production benzeri relation-heavy ekran deseni için şu örneğe bak:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Bu örnek şu yaklaşımı gösterir:

- önce özet sorgu
- sonra açık detail fetch
- preload gerekiyorsa bile ilişkiyi bilinçli olarak sınırlama
- generated binding sınıflari ve fluent `QuerySpec.where(...).orderBy(...).limitTo(...)` kullanımı
- `DemoOrderEntityCacheBinding.orderSummary(orderRepository)` gibi generated projection helper'ları
- `DemoOrderEntityCacheBinding.topCustomerOrders(orderSummaryRepository, customerId, 24)` gibi generated named query helper'ları
- `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(orderRepository, 8)` gibi generated fetch preset helper'ları
- `UserEntityCacheBinding.usersPage(session, 0, 25)` gibi generated page preset helper'ları
- `UserEntityCacheBinding.activateUser(session, 41L, "alice")` gibi generated write command helper'ları
- `UserEntityCacheBinding.using(session).queries().activeUsers(25)` gibi session'a bağlı kullanım gruplari
- `com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session).users().queries().activeUsers(25)` gibi package seviyesinde domain modülleri

Örnekte şunlar kullanılir:

- `FetchPlan.withRelationLimit("orderLines", 8)`
- büyük eager object graph yerine ayrı summary read model
- sadece base entity payload yolunu değil projection-specific Redis index'lerini kullanma
- read-model bakimini foreground write path dışına itmek için `EntityProjection.asyncRefresh()`

Önemli not:

- şu anki async projection refresh Redis Stream tabanlı durable eventual consistency modelidir
- production write overhead'ini ve read payload boyutunu düşürmeye yardım eder
- refresh event'leri process restart sonrasında kaybolmaz; Redis consumer group üzerinden işlenebilir
- ama henüz poison queue veya replay tooling içeren tam bir projection platformu değildir

Önerilen akış:

1. Demo load UI'i ac.
2. `Seed Demo Data` butonuna bas.
3. Sırayla `LOW`, sonra `MEDIUM`, sonra `HIGH` yüklerini bas.
4. Paralelde admin dashboard'u açık tut ve şunları izle:
   - write-behind backlog
   - Redis memory
   - incidents
   - runtime profile
   - alert routing
   - incident severity trendleri

Runtime tuning:

- demo Redis bağlantı ve pool ayarları: `cachedb.demo.redis.*`
- demo PostgreSQL bağlantı ayarları: `cachedb.demo.postgres.*`
- demo'ya özel core override: `cachedb.demo.config.*`
- global core override: `cachedb.config.*`
- demo cache policy ve seed satır sayilari: `cachedb.demo.cache.*`, `cachedb.demo.seed.*`
- demo view ve stop/error davranisi: `cachedb.demo.view.*`, `cachedb.demo.stop.*`, `cachedb.demo.error.*`
- demo load profilleri: `cachedb.demo.load.low.*`, `cachedb.demo.load.medium.*`, `cachedb.demo.load.high.*`
- demo UI worker/refresh kontrolleri: `cachedb.demo.ui.*`

Örnekler:

```powershell
-Dcachedb.demo.redis.pool.maxTotal=96
-Dcachedb.demo.postgres.connectTimeoutSeconds=15
-Dcachedb.demo.config.writeBehind.workerThreads=8
-Dcachedb.config.redisGuardrail.usedMemoryWarnBytes=2147483648
```

Tam tuning kataloğu:

- [../docs/tuning-parameters.md](../docs/tuning-parameters.md)
