# cachedb-examples

Bu modül, `cache-database` için çalıştırılabilir örnekler içerir.

İki amaç için kullanılır:

- Demo yük altında Redis öncelikli çalışma davranışını gözlemlemek.
- Gerçek bir PostgreSQL demo şeması üzerinde geçiş planlayıcı akışını prova etmek.

## Spring Boot Demo

Önerilen demoyu şu komutla başlat:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Açılacak adresler:

- demo yük arayüzü: `http://127.0.0.1:8090/demo-load`
- yönetim paneli: `http://127.0.0.1:8090/cachedb-admin?lang=tr`
- geçiş planlayıcı: `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`

Yük arayüzü ve yönetim paneli aynı Spring Boot uygulama portunu kullanır. Bu
modda ikinci bir public admin server açılmaz.

## Yük Senaryosu Çalışma Alanı

Demo çalışma alanı şunları içerir:

- veri seed etmek ve yük profillerini başlatmak için Bootstrap + AJAX kontrol arayüzü
- backlog, incident, memory, routing ve geçiş planlama sayfalarını içeren CacheDB yönetim paneli

Demo domain:

- `DemoCustomerEntity`
- `DemoProductEntity`
- `DemoCartEntity`
- `DemoOrderEntity`
- `DemoOrderLineEntity`

Varsayılan seed hacmi:

- customers: `1,800`
- products: `1,400`
- carts: `4,500`
- orders: `3,600`
- order lines: `54,000`
- toplam: `65,300`

Bu hacim, ilişki-ağır davranışı gösterecek kadar büyük; lokal demo tekrarlarını
zorlamayacak kadar sınırlıdır.

## Hangi Düğmeye Basmalıyım?

Normal yük demosu için:

1. `http://127.0.0.1:8090/demo-load` adresini aç.
2. `Seed Demo Data` düğmesine bas.
3. `LOW` yükünü başlat ve yönetim panelindeki metrikleri izle.
4. Sistem stabil görünüyorsa `MEDIUM` yüküne geç.
5. `HIGH` yüküne yalnızca önceki profil stabil kaldıysa geç.
6. Write-behind backlog, Redis memory, incident ve runtime profile alanlarını izle.

Veri hazır değilken `LOW / MEDIUM / HIGH` başlatırsan arayüz hata verir ve önce
seed ister. Yük düğmeleri artık arka planda gizlice seed başlatmaz.

Yük profilleri:

- `LOW`: katalog gezme, tüm müşteri taraması ve hafif toplu sepet/ürün güncellemesi
- `MEDIUM`: daha büyük okumalar, en çok sipariş veren müşteri sorguları ve dengeli toplu yazmalar
- `HIGH`: kampanya saati davranışı, full customer scan, çok satırlı sipariş okumaları ve yoğun stok/sepet/sipariş dalgalanması

## Geçiş Planlayıcı Demo Akışı

Mevcut PostgreSQL geçiş davranışını denemek için:

1. `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr` adresini aç.
2. `Demo şemayı kur ve seed et` düğmesine bas.
3. PostgreSQL şema keşfini çalıştır.
4. Müşteri-sipariş gibi önerilen bir akış seç.
5. `Forma uygula` düğmesine bas.
6. `Planı oluştur` düğmesine bas.
7. Java iskeleti istiyorsan scaffold üret.
8. Dry-run ön ısıtma çalıştır.
9. Gerçek staging ön ısıtma çalıştır.
10. Yan yana karşılaştırma çalıştır.
11. Geçiş raporunu indir.

Hazırlanan demo nesneleri:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

Karşılaştırma sonuç akış hazır değil diyorsa önce raporu incele. CacheDB tarafı
hızlı görünse bile PostgreSQL ile ilk sayfa üyeliği ve sıralaması eşleşmeden
canlıya geçilmemelidir.

## Standalone Demo

Spring Boot dışında çalıştırmak istediğinde standalone modu kullan:

```powershell
mvn -q -pl cachedb-examples -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.examples.demo.DemoLoadMain" `
  "-Dcachedb.demo.redisUri=redis://default:welcome1@127.0.0.1:56379" `
  "-Dcachedb.demo.jdbcUrl=jdbc:postgresql://127.0.0.1:55432/postgres" `
  "-Dcachedb.demo.jdbcUser=postgres" `
  "-Dcachedb.demo.jdbcPassword=postgresql"
```

Varsayılan standalone URL'ler:

- demo yük arayüzü: `http://127.0.0.1:8090`
- yönetim paneli: `http://127.0.0.1:8080/dashboard`

## Read-Model Örneği

Production benzeri ilişki-ağır ekran deseni için:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](../../cachedb-examples/src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Bu örnek, yaygın "müşterinin çok siparişi var" problemini temsil eder:

- önce özet sorgu çalışır
- kullanıcı satırı açınca detay ayrıca yüklenir
- önizleme gerekiyorsa ilişki yükleme sınırlandırılır
- geniş base entity decode etmek yerine projection'a özel Redis index'i kullanılır
- `EntityProjection.asyncRefresh()` ile read-model bakımı foreground write path dışına taşınır

Örnekte gösterilen generated helper'lar:

- `DemoOrderEntityCacheBinding.orderSummary(orderRepository)`
- `DemoOrderEntityCacheBinding.topCustomerOrders(orderSummaryRepository, customerId, 24)`
- `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(orderRepository, 8)`
- `UserEntityCacheBinding.usersPage(session, 0, 25)`
- `UserEntityCacheBinding.activateUser(session, 41L, "alice")`
- `UserEntityCacheBinding.using(session).queries().activeUsers(25)`
- `com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session).users().queries().activeUsers(25)`

Tutarlılık notu:

- async projection refresh Redis Stream tabanlı ve durable çalışır
- refresh event'leri process restart sonrasında kaybolmaz
- projection okumaları tasarım gereği eventual consistency taşır
- migration cutover kararları yine yan yana parity check ile verilmelidir

## Runtime Tuning

Yaygın demo ayarları:

- demo Redis bağlantı ve pool ayarları: `cachedb.demo.redis.*`
- demo PostgreSQL bağlantı ayarları: `cachedb.demo.postgres.*`
- demo'ya özel core override: `cachedb.demo.config.*`
- global core override: `cachedb.config.*`
- demo cache policy ve seed satır sayıları: `cachedb.demo.cache.*`, `cachedb.demo.seed.*`
- demo view ve stop/error davranışı: `cachedb.demo.view.*`, `cachedb.demo.stop.*`, `cachedb.demo.error.*`
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
