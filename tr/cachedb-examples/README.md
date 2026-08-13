# CacheDB Örnekleri ve Operasyon Demosu

[English](../../cachedb-examples/README.md)

Bu modül, CacheDB geliştiricileri ve operasyon ekipleri için hazırlanmış bir
çalışma alanıdır. Yük profillerini, yönetim ekranlarını, geçiş planlamasını ve
düşük seviyeli uyumluluk yüzeylerini çalıştırır. Bir uygulamayı öğrenmenin en
kısa yolu değildir.

> Uygulama geliştiricileri önce bağımsız PostgreSQL veya SQL Server REST API
> örneğinden başlamalıdır. Yönetim ekranını incelemek, geçiş provası yapmak ya
> da çalışma zamanı davranışını kontrollü yük altında doğrulamak istiyorsan bu
> modülü kullan.

## Doğru Örneği Seç

| Amacın | Başlangıç noktası |
| --- | --- |
| PostgreSQL uygulaması geliştirmek | [PostgreSQL REST API örneği](../../sample-cache-database-postgresql/README.tr.md) |
| SQL Server uygulaması geliştirmek | [SQL Server REST API örneği](../../sample-cache-database-mssql/README.tr.md) |
| Generated repository öğrenmek | [Deklaratif repository rehberi](../docs/deklaratif-repositoryler.md) |
| Yönetim ekranını ve yük profillerini işletmek | Bu modülle devam et |
| Mevcut sistem geçişini prova etmek | [Geçiş Planlayıcı Demo Akışı](#geçiş-planlayıcı-demo-akışı) |
| Eski/generated binding uyumluluğunu incelemek | [Düşük Seviyeli Uyumluluk Örnekleri](#düşük-seviyeli-uyumluluk-örnekleri) |

İki amaç için kullanılır:

- Demo yük altında Redis öncelikli çalışma davranışını gözlemlemek.
- Gerçek bir PostgreSQL demo şeması üzerinde geçiş planlayıcısı akışını prova etmek.

Başarılı bir koşu yerel davranış kanıtı üretir. Kubernetes kapasitesini
belirlemez; test ortamındaki veri eşitliği, failover ve geri dönüş testlerinin
yerine geçmez.

## Demo İçin Ürün Konumlandırması

Bu demo, şeffaf bir veritabanı cache kıyaslaması değildir. Demo şu davranışı
gösterir: sınırları belirlenmiş aktif veri seti, projection ve açık SQL yolları.

- Redis, anlık entity ve projection yollarını besler.
- PostgreSQL, kalıcı geçmişten ve migration kaynak verisinden sorumludur.
- İlişki yoğun ekranlar projection veya sınırlı ilişki önizlemesiyle okunmalıdır.
- Arşiv, tam geçmiş, export ve repair akışları açık SQL yollarıyla tasarlanmalıdır.

Bir demo yolu Redis'ten veri döndürmüyorsa bu, kalıcı satırın kaybolduğu
anlamına gelmez. Genellikle bu yol aktif veri setinin dışındadır, projection
önceden yüklenmemiştir veya ekranın açık bir SQL yoluna ihtiyacı vardır.

## Spring Boot Demo

Önerilen demoyu şu komutla başlat:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Bu script beklenen Redis/PostgreSQL topolojisini hazırlayıp doğru Spring Boot
profilini başlattığı için desteklenen yerel giriş noktasıdır. Standalone modu
özellikle test etmiyorsan topolojiyi ayrı Maven komutlarıyla yeniden kurma.

Açılacak adresler:

- demo yük arayüzü: `http://127.0.0.1:8090/demo-load`
- yönetim paneli: `http://127.0.0.1:8090/cachedb-admin?lang=tr`
- geçiş planlayıcı: `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr`

Yük arayüzü ve yönetim paneli aynı Spring Boot uygulama portunu kullanır. Bu
modda ikinci bir public admin server açılmaz.

## Yük Senaryosu Çalışma Alanı

Demo çalışma alanı şunları içerir:

- veri seed etmek ve yük profillerini başlatmak için Bootstrap + AJAX kontrol arayüzü
- backlog, olay, bellek, yönlendirme ve geçiş planlama sayfalarını içeren CacheDB yönetim paneli

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

## İlk Başarılı Çalıştırma

Normal yük demosu için:

1. `http://127.0.0.1:8090/demo-load` adresini aç.
2. `Seed Demo Data` düğmesine bas.
3. `LOW` yükünü başlat ve yönetim panelindeki metrikleri izle.
4. Sistem stabil görünüyorsa `MEDIUM` yüküne geç.
5. `HIGH` yüküne yalnızca önceki profil stabil kaldıysa geç.
6. Write-behind backlog, Redis belleği, olay ve çalışma profili alanlarını izle.
7. Backlog sürekli büyüyorsa, readiness bozuluyorsa veya Redis uyarı eşiğine
   geldiyse yükü artırma; bu durumda daha yüksek profil anlamlı kanıt üretmez.

Veri hazır değilken `LOW / MEDIUM / HIGH` başlatırsan arayüz hata verir ve önce
seed ister. Yük düğmeleri artık arka planda gizlice seed başlatmaz.

Yük profilleri:

- `LOW`: katalog gezme, tüm müşteri taraması ve hafif toplu sepet/ürün güncellemesi
- `MEDIUM`: daha büyük okumalar, en çok sipariş veren müşteri sorguları ve dengeli toplu yazmalar
- `HIGH`: kampanya saati davranışı, tam müşteri taraması, çok satırlı sipariş okumaları ve yoğun stok/sepet/sipariş dalgalanması

## Geçiş Planlayıcı Demo Akışı

Mevcut SQL veritabanı geçiş davranışını hazır PostgreSQL demo veri setiyle
denemek için:

1. `http://127.0.0.1:8090/cachedb-admin/migration-planner?lang=tr` adresini aç.
2. `Demo şemayı kur ve seed et` düğmesine bas.
3. PostgreSQL demo veri seti üzerinde şema keşfini çalıştır.
4. Müşteri-sipariş gibi önerilen bir akış seç.
5. `Forma uygula` düğmesine bas.
6. `Planı oluştur` düğmesine bas.
7. Java iskeleti istiyorsan scaffold üret.
8. Dry-run ön yükleme çalıştır.
9. Gerçek staging ön yükleme çalıştır.
10. Yan yana karşılaştırma çalıştır.
11. Geçiş raporunu indir.

Hazırlanan demo nesneleri:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

Karşılaştırma sonuç akış hazır değil diyorsa önce raporu incele. CacheDB tarafı
hızlı görünse bile kaynak veritabanı referans sonucu ile ilk sayfa üyeliği ve sıralaması eşleşmeden
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

## Önerilen Uygulama API'si

Yeni uygulama kodunda repository-first örnek projeleri kullan:

- [PostgreSQL örneği](../../sample-cache-database-postgresql/README.tr.md)
- [SQL Server örneği](../../sample-cache-database-mssql/README.tr.md)
- [Deklaratif repository rehberi](../docs/deklaratif-repositoryler.md)

Bu uygulamalarda tablo eşlemesi entity üzerinde, route sözleşmeleri
`@CacheRepository` arayüzlerinde, servis kullanımı ise Spring tarafından
enjekte edilen generated repository'ler üzerindedir.

## Düşük Seviyeli Uyumluluk Örnekleri

Production benzeri ilişki-ağır ekran deseni için:

- [src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java](../../cachedb-examples/src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Bu örnek, yaygın "müşterinin çok siparişi var" problemini temsil eder:

- önce özet sorgu çalışır
- kullanıcı satırı açınca detay ayrıca yüklenir
- önizleme gerekiyorsa ilişki yükleme sınırlandırılır
- geniş base entity decode etmek yerine projection'a özel Redis index'i kullanılır
- `EntityProjection.asyncRefresh()` ile read-model bakımı ön plandaki yazma akışının dışına taşınır

Bu modül aşağıdaki düşük seviyeli generated binding örneklerini de korur. Bu
örnekler uyumluluk testi, wrapper benchmark'ı ve framework iç yapısını göstermek
içindir; yeni uygulamalar için önerilen API değildir:

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
- geçiş kararları yine yan yana veri eşitliği kontrolüyle verilmelidir

## Kanıtın Sınırı

Bu demo bir yol şeklinin, guardrail'in, geçiş planının veya operasyon ekranının
yerel topolojide doğru çalıştığını gösterebilir. Production kapasite sonucu
vermez. Production kanıtı; gerçek ağ yolu, container limitleri, Redis
topolojisi, veritabanı bağlantı bütçesi, veri şekli ve beklenen eş zamanlılıkla
üretilmelidir.

## Runtime Ayarları

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

## Sorun Giderme

| Belirti | Yapılacak işlem |
| --- | --- |
| Yük profili verinin eksik olduğunu söylüyor | Önce `Seed Demo Data` çalıştır ve tamamlanmasını bekle |
| Geçiş planlayıcı yol adayı göstermiyor | Demo şemasını kurup seed et, ardından keşfi yeniden çalıştır |
| CacheDB hızlı fakat karşılaştırma hazır değil | Üyelik ve sıralama eşitliğini düzelt; gecikme tek başına geçiş sinyali değildir |
| Backlog sürekli büyüyor | Yükü artırmayı bırak; SQL gecikmesi, worker kapasitesi, yeniden deneme ve Redis baskısını incele |
| Uygulama portu kullanılıyor | Önceki demo sürecini durdur veya demo portunu değiştir |
