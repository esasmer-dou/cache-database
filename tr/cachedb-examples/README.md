# cachedb-examples

Bu modul, `cache-database` icin calistirilabilir ornekler icerir.

## Yuk Senaryosu Calisma Alani

Bu calisma alani ayni anda iki yuz acar:

- veri seed etmek ve yuk profillerini baslatmak icin Bootstrap + AJAX kontrol UI'i
- backlog, incident, memory ve routing izlemek icin mevcut CacheDB admin dashboard

Demo domain:

- `DemoCustomerEntity`
- `DemoProductEntity`
- `DemoCartEntity`
- `DemoOrderEntity`
- `DemoOrderLineEntity`

UI gorunumleri:

- customers
- products
- carts
- orders
- order lines

Varsayilan seed hacmi:

- customers: `1,800`
- products: `1,400`
- carts: `4,500`
- orders: `3,600`
- order lines: `54,000`
- toplam: `65.300`

Bu varsayilan profil, Spring Boot demo icinde daha interaktif kalirken yine de gercege yakin bir e-ticaret dilimi hissi versin diye secildi. Fiziksel hacmin buyuk kismi yine siparis satirlarinda kalir; ama toplam footprint daha kucuk oldugu icin `Seed`, `Clear`, `Fresh Start` ve `LOW / MEDIUM / HIGH` gecisleri tekrarli gozlem kosularinda daha kullanisli kalir.

Yuk profilleri:

- `LOW`: gunduz trafigine yakin katalog gezme, tum musteri taramasi ve hafif toplu sepet/urun guncellemesi
- `MEDIUM`: buyuk katalog okumalari, en cok siparis veren musterinin siparisleri ve dengeli toplu yazmalar
- `HIGH`: tum musteri taramalari, cok satirli siparis okumalari ve yogun stok/sepet/siparis dalgalari

Standalone demo calistirma:

```powershell
mvn -q -pl cachedb-examples -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.examples.demo.DemoLoadMain" `
  "-Dcachedb.demo.redisUri=redis://default:welcome1@127.0.0.1:56379" `
  "-Dcachedb.demo.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres" `
  "-Dcachedb.demo.jdbcUser=postgres" `
  "-Dcachedb.demo.jdbcPassword=postgresql"
```

Varsayilan URL'ler:

- demo load UI: `http://127.0.0.1:8090`
- admin dashboard: `http://127.0.0.1:8080/dashboard`

Spring Boot demo calistirma:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Spring Boot URL'leri:

- demo load UI: `http://127.0.0.1:8090/demo-load`
- admin dashboard: `http://127.0.0.1:8090/cachedb-admin?lang=tr`

Spring Boot notlari:

- load UI ve admin dashboard ayni uygulama portunu kullanir
- Spring Boot modunda ikinci bir dahili admin HTTP server acilmaz
- ayni seed hacmi ve LOW / MEDIUM / HIGH senaryolari yeniden kullanilir
- agir yuk altinda standalone davranisini korumak icin demo Redis pool varsayilan olarak genisletilir
- Spring Boot demo icinde foreground repository Redis trafigi ile background worker/admin trafigi ayri pool'lara ayrilir
- `Start LOW / MEDIUM / HIGH` gizlice seed baslatmaz; veri hazir degilse UI dogrudan hata verir ve once `Seed Demo Data` ister
- Spring Boot demo artik zero-glue generated registrar discovery kullaniyor; yani explicit `GeneratedCacheBindings.register(...)` cagrisi olmadan binding'ler otomatik kaydolur

## Read-Model Ornegi

Production benzeri relation-heavy ekran deseni icin su ornege bak:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Bu ornek su yaklasimi gosterir:

- once ozet sorgu
- sonra acik detail fetch
- preload gerekiyorsa bile iliskiyi bilincli olarak sinirlama
- generated binding siniflari ve fluent `QuerySpec.where(...).orderBy(...).limitTo(...)` kullanimi
- `DemoOrderEntityCacheBinding.orderSummary(orderRepository)` gibi generated projection helper'lari
- `DemoOrderEntityCacheBinding.topCustomerOrders(orderSummaryRepository, customerId, 24)` gibi generated named query helper'lari
- `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(orderRepository, 8)` gibi generated fetch preset helper'lari
- `UserEntityCacheBinding.usersPage(session, 0, 25)` gibi generated page preset helper'lari
- `UserEntityCacheBinding.activateUser(session, 41L, "alice")` gibi generated write command helper'lari
- `UserEntityCacheBinding.using(session).queries().activeUsers(25)` gibi session'a bagli kullanim gruplari
- `com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session).users().queries().activeUsers(25)` gibi package seviyesinde domain modulleri

Ornekte sunlar kullanilir:

- `FetchPlan.withRelationLimit("orderLines", 8)`
- buyuk eager object graph yerine ayri summary read model
- sadece base entity payload yolunu degil projection-specific Redis index'lerini kullanma
- read-model bakimini foreground write path disina itmek icin `EntityProjection.asyncRefresh()`

Onemli not:

- su anki async projection refresh Redis Stream tabanli durable eventual consistency modelidir
- production write overhead'ini ve read payload boyutunu dusurmeye yardim eder
- refresh event'leri process restart sonrasinda kaybolmaz; Redis consumer group uzerinden islenebilir
- ama henuz poison queue veya replay tooling iceren tam bir projection platformu degildir

Onerilen akis:

1. Demo load UI'i ac.
2. `Seed Demo Data` butonuna bas.
3. Sirayla `LOW`, sonra `MEDIUM`, sonra `HIGH` yuklerini bas.
4. Paralelde admin dashboard'u acik tut ve sunlari izle:
   - write-behind backlog
   - Redis memory
   - incidents
   - runtime profile
   - alert routing
   - incident severity trendleri

Runtime tuning:

- demo Redis baglanti ve pool ayarlari: `cachedb.demo.redis.*`
- demo PostgreSQL baglanti ayarlari: `cachedb.demo.postgres.*`
- demo'ya ozel core override: `cachedb.demo.config.*`
- global core override: `cachedb.config.*`
- demo cache policy ve seed satir sayilari: `cachedb.demo.cache.*`, `cachedb.demo.seed.*`
- demo view ve stop/error davranisi: `cachedb.demo.view.*`, `cachedb.demo.stop.*`, `cachedb.demo.error.*`
- demo load profilleri: `cachedb.demo.load.low.*`, `cachedb.demo.load.medium.*`, `cachedb.demo.load.high.*`
- demo UI worker/refresh kontrolleri: `cachedb.demo.ui.*`

Ornekler:

```powershell
-Dcachedb.demo.redis.pool.maxTotal=96
-Dcachedb.demo.postgres.connectTimeoutSeconds=15
-Dcachedb.demo.config.writeBehind.workerThreads=8
-Dcachedb.config.redisGuardrail.usedMemoryWarnBytes=2147483648
```

Tam tuning katalogu:

- [../docs/tuning-parameters.md](../docs/tuning-parameters.md)
