# Sınırlı Çalışma İşlemleri

Bu sayfadaki yeni API'ler için 0.13.0 veya üzeri gerekir. Üretilmiş binding'leri
yeniden derlemeden önce BOM, processor ve sağlayıcı starter'ını aynı sürümde tutun.
Mevcut saklama biçimini değiştirmeden önce
[sürüm notlarını](releases/v0.13.0.md) okuyun.

## İhtiyaca Göre Seçin

| İhtiyaç | Kullanılacak yapı | Temel sınır |
|---|---|---|
| Salt okunur SQL kayıtlarını dönüştürmek | @CacheSourceRecord | Primitive alan SQL NULL kabul etmez |
| Birden fazla tabloyu birleştirmek | SnapshotPlan ve tipli ilişkiler | Tek ve bütçesi belli SQL işlemi |
| Hazır cevapları sayfalı okumak | SnapshotIndexSpec ve WindowRequest | Okuma sırasında SQL çalışmaz |
| Birkaç kökü yenilemek | SnapshotRootScope ve refreshRoots | Her kaynak için açık kapsam |
| Değişmeyen JSON'u tekrar kullanmak | withContentReuse() | Değişen veri için yeni bellek gerekir |
| Dış yazımları paralel tüketmek | JdbcOutboxPartition | Aynı entity aynı bölümde kalır |
| İlişkili yazımları birlikte kabul etmek | CacheWriteBatch | Redis kabulü SQL commit değildir |
| Ortak SQL sequence kullanmak | JdbcPooledSequenceIdGenerator | Kullanılmayan kimlikler boşluk bırakabilir |
| Redis belleğini dağıtmak | JedisClusterOperations | Tek atomik kapsam tek slot'ta kalır |

## Kayıt Sayısı Politikası

```java
var bounded = CachePolicy.builder().hotEntityLimit(1_000).build();
var unboundedCount = CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).build();
var noAdmission = CachePolicy.builder().hotEntityLimit(CachePolicy.DISABLED).build();
```

| Değer | Davranış |
|---|---|
| Pozitif | Kayıt sayısı sınırı; tahliye ve diğer politikalar ayrıca ayarlanır |
| -1 / UNBOUNDED | Sayısal üst sınır yoktur; fiziksel bellek sınırsız değildir |
| 0 / DISABLED | Yazım, warm ve SQL'den yükleme entity'yi Redis'e kabul etmez; okuma penceresi sıfırdır |
| -1'den küçük | Yapılandırma hatası |
| Belirtilmemiş | Mevcut 1.000 kayıt varsayılanı korunur |

YAML'daki hot-entity-limit aynı değerleri kullanır. Önceki ayarda sınırsız kullanım
için sıfır seçtiyseniz yükseltmeden önce -1 yapın. Politika değişikliği mevcut Redis
kayıtlarını kendiliğinden silmez; bunun için açık mutabakat/tahliye işlemi kullanın.
Kayıt kabulünü kapatmak SQL'e yazmayı veya sürüm/kalıcılık bilgisini kapatmaz.
Redis-only route da kendiliğinden SQL'e yönelmez. Projection penceresi ve snapshot
kataloğu kendi saklama sözleşmelerini kullanır.
Redis maxmemory sınırını, tahliye politikasını ve indeks/metadata belleğini birlikte
ölçün. -1 değeri kapasite planlamasının yerine geçmez.

## İndeksli ve Artımlı Katalog

Bu örnek, Long kimlikli OrderEntity ve CustomerEntity için üretilmiş SOURCE alanlarını
kullanır. Alanları kendi şemanızla eşleştirin. OrderSummary.from uygulamanızın iş
eşlemesidir; framework bu iş kararını kendiliğinden vermez.

```java
var orders = SnapshotSource.entity("orders", OrderEntityCacheBinding.SOURCE)
        .where(SnapshotPredicate.isNull("deleted_at"));
var customers = SnapshotSource.entity("customers", CustomerEntityCacheBinding.SOURCE);
var scope = SnapshotRootScope.builder()
        .source(orders, ids -> SnapshotPredicate.inValues("order_id",
                ids.stream().map(Long::valueOf).toList()))
        .source(customers, ids -> SnapshotPredicate.in("customer_id",
                orders.where(SnapshotPredicate.inValues("order_id",
                        ids.stream().map(Long::valueOf).toList())).select("customer_id")))
        .build();

SnapshotPlan<OrderEntity, OrderSummary> plan = new SnapshotPlan<>(
        "order-timeline", orders, order -> order.orderId.toString(),
        SnapshotPlan.inputs(orders, customers), OrderSummary.class, rows -> {
            var customerById = rows.unique(customers, customer -> customer.customerId);
            return order -> List.of(OrderSummary.from(order, customerById.get(order.customerId)));
        })
        .indexed(SnapshotIndexSpec.of(order -> order.customerId.toString(),
                order -> -order.orderDate.toEpochMilli()))
        .scoped(scope)
        .withContentReuse();
```

İndeks kök kayıtları sıralar; bu örnekte kök order'dır. Her kökün cevabı yine JSON
listesidir. Puan küçükten büyüğe sıralandığından negatif zaman değeri en yeniyi başa
getirir. Eşit puanda kimlikler metin sırasıyla dizilir, sayısal sıra varsayılmamalıdır.
Double her büyük tam sayıyı tam gösteremez; 2^53 üzerindeki kimlikler ve hassas mali
tutarlar açık bir hassasiyet sözleşmesi olmadan sıralama puanı yapılmamalıdır.

```java
SnapshotRepository repository = jobs.repository("order-timeline");
jobs.refresh("order-timeline", true); // Önce tam ve tutarlı katalog hazırlanır.
var first = repository.findPage("42", WindowRequest.first(50));
var next = first.nextRequest(50).map(request -> repository.findPage("42", request));
repository.refreshRoots(List.of("9001", "9002")); // Açık komut; GET bunu tetiklemez.
```

Tam yenileme, içerik ve bölüm indekslerini yeni nesilde hazırlar; aktif işaretçiyi
bir kez değiştirir. Okuma yalnızca Redis'ten yapılır. Cursor, plan/bölüm/nesil/sürüm
bilgisine bağlıdır. Tam veya artımlı yenilemeden sonra eski cursor reddedilir;
ilk sayfadan başlayın. WindowRequest tek istekte en fazla 1.000 kayıt kabul eder.
İçerik ayrıca batch-target-size sınırına (varsayılan 4 MiB) sığmalıdır.
Fazla büyük sayfa kesilmez; hata verir. Pencereyi küçültün veya tekil okuma kullanın.

Artımlı yenileme için önce tam katalog gerekir. Her girdiye açık kapsam verilir.
Komuta gelen kök kimlikleri String'dir; kapsam fonksiyonunda gerçek SQL tipine
çevirin. shared(source), küçük bir sözlük tablosunun tamamını yeniden okumaya açık
izin verir; otomatik varsayılan değildir. Yanlış kapsam yanlış iş sonucu doğurabilir.
Kaynak üyeliğini ve tam cevap eşleşmesini test edin.
Seçilen kök yoksa silinir; varsa içerik ve indeks puanı birlikte değiştirilir.
Kilit sahibi ve aktif nesil aynı Redis işlemi içinde denetlenir.

Kimlik sayısı batch-rows, serileştirilmiş değişiklik batch-target-size sınırına
sığmalıdır. Büyük işleri açık partilere ayırın. Dokunulmayan kökün refreshedAt bilgisi
değişmez. Artımlı iş bütün kataloğun süresini uzatmaz veya eski veriyi güncel göstermez.
Veri yaşı sözleşmesi gerektiriyorsa tam periyodik yenilemeyi sürdürün.

İçerik paylaşımı, kök başına SHA-256 referansı tutar ve değişmeyen JSON'u nesiller
arasında kullanır. İndeks ve hash metadata'sı yine bir süre birlikte bulunur.
Her cevap değişirse eski ve yeni içerik atomik yayın için birlikte saklanır.
Bu seçenek belleği kesin yarıya indirmez ve SQL hazırlığını kaldırmaz.
Temizlik sınırlı partilerle yapılır; aktif/hazırlanan içeriğin referansları denetlenir.

## Parçalı Outbox Tüketimi

```java
// Üretici bu değeri outbox satırıyla aynı SQL işleminde kaydeder.
var shard = new JdbcOutboxPartition(8, podShardIndex, "partition_key");
int partitionKey = shard.partitionFor("OrderEntity", orderId.toString());

var feed = JdbcOutboxExternalChangeFeedAdapter.builder(dataSource, outboxDialect)
        .adapterName("orders-feed")
        .outboxTable("cachedb_outbox")
        .checkpointTable("cachedb_outbox_adapter_checkpoint")
        .partition(shard)
        .batchSize(128)
        .build();
```

Sağlayıcınıza göre PostgresOutboxDialect, MssqlOutboxDialect veya OracleOutboxDialect
seçin. Sağlayıcı builder'ları da partition(count, index, column) kabul eder.
(partition_key, id) indeksi oluşturun. Her bölüm en az bir tüketiciye atanmalıdır.
Aynı bölümü alan pod'lar checkpoint satırı kilidiyle sıraya girer; farklı bölümler
ayrı satırlardan ilerler. Otomatik bölüm ataması veya SQL hash dönüşümü yapılmaz.

Üretici ve tüketici aynı entity adını, standart kimlik metnini ve sabit bölüm sayısını
kullanmalıdır. Aynı entity'nin olayları aynı bölümde kalmalı; olay kimlikleri artan ve
commit açısından görünür sırada olmalıdır. Bölüm sayısı değişikliği kontrollü geçiş
gerektirir; eski tek tüketiciyi yeni parçalı tüketicilerle birlikte çalıştırmayın.
Parçalı genel adaptörün varsayılanı checkpoint kilidiyle READ COMMITTED'dır.
Açıkça seçilen izolasyon korunur; eski parçalanmamış varsayılan değiştirilmez.
Henüz hiçbir olay teslim edilmemişken oluşan deadlock/izolasyon çatışması en fazla
üç sınırlı denemeyle yeniden alınır. Teslimat başladıysa aynı poll çağrısı içinde
yan etki sessizce tekrarlanmaz; hata bildirilir.
Teslimat en az bir kez yapılır: Redis kabulünden sonra checkpoint commit olmadan
pod kapanırsa olay tekrar gelebilir. Sürüm/idempotency denetimlerini, sonlu bağlantı
sürelerini, yeniden deneme aralığını ve gecikme/hata uyarılarını koruyun.

## İlişkili Atomik Yazımlar

```java
var receipt = database.writeBatch("checkout-" + requestId)
        .put(order)
        .put(payment)
        .commit();

database.writeBatch("amend-" + requestId)
        .put(updatedOrder, expectedOrderVersion)
        .put(updatedPayment, expectedPaymentVersion)
        .commit();

database.writeBatch("cancel-" + requestId)
        .delete(PaymentEntity.class, paymentId)
        .delete(OrderEntity.class, orderId)
        .commit();
```

Yazılabilir entity ve projection'ları önce kaydedin. Kimlik put öncesinde verilmiş
olmalıdır; batch kimlik tahmin etmez veya eksik alanları birleştirmez. Tam entity verin.
Üst sınır 100 işlem ve 4 MiB kodlanmış komuttur; stream alan sayısı ve yapılandırılmış
kolon sınırı da denetlenir. Tekrarlanan entity/kimlik ve hatalı sürüm beklentisi,
değişiklik yapılmadan reddedilir. Yeniden denemede aynı batch kimliğini kullanın.
Aynı içerikle tekrar, 24 saat boyunca aynı makbuzu verir. Farklı içerikle aynı kimliği
kullanmayın; bu süreden sonra tekrar engelleme garantisi varsaymayın.

Redis, içerik/sürüm/silme işaretlerini ve desteklenen kayıtlı projection içeriklerini
tek işlemde değiştirir. SQL kuyruğuna bölünemeyen tek zarf bırakır. Sorgu indeksleri
eski üyeliği doğru sanmak yerine degraded işaretlenir. Açık rebuild veya ayarlanmış
indeks toparlama/strict-route davranışını kullanın. Sınırlı tenant kabul sözleşmesi
atomik batch'i şimdilik reddeder; kota sessizce atlanmaz.

PostgreSQL, SQL Server ve Oracle flush işlemi zarfı, verilen sırayı koruyarak tek SQL
transaction içinde yazar. Hata olursa bütün grup geri alınır; yeniden deneme ve hata
kuyruğu grubu satırlara bölmemelidir. FK gerektiriyorsa ebeveyni önce ekleyin,
silmede önce bağımlı çocuğu silin.

Makbuz Redis kabulünü bildirir, SQL kalıcılığını değil. İki depo arasında dağıtık
transaction yoktur. Doğrudan SQL okuyan başka uygulama write-behind bitene kadar
eski veriyi görebilir. SQL kalıcılığı gereken yan etkiden önce durability bilgisini
bekleyin. database.isDurable(receipt), bütün üyelerin kabul edilmiş sürümünü SQL
çalıştırmadan denetler. Redis script'i OOM/sunucu hatasında rollback yapan transaction değildir.
noeviction, bellek payı, sonlu komut bütçesi ve kalıcı Redis ayarlarını birlikte kullanın.

## Ortak SQL Sequence

```java
var ids = new JdbcPooledSequenceIdGenerator(dataSource, "order_id_seq", 128, 30);
Long orderId = ids.nextId(); // Yerel ayrılmış kimlikler bitince SQL çağrılır.
```

Üretici gerçek NEXTVAL/NEXT VALUE FOR değerlerini tek ve sınırlı sorguyla alır.
Ardışık aralık varsaymaz; mevcut sequence'in artış miktarını değiştirmez.
Diğer uygulamalar ve pod'lar aynı sequence'i güvenle paylaşabilir.
Yerel kimlik dağıtımı SQL turu yapmaz; yeni parti JDBC üzerinden alınır ve bekler.
Uygun executor, sonlu havuz/sürücü süreleri ve ölçüm kullanın. Kimlik Long'a sığmalıdır.
Parti 1..1.000, sorgu süresi 1..300 saniyedir. Pod kapanırsa kullanılmamış kimlikler
boşluk bırakabilir; bu yapı boşluksuz fatura numarası üreticisi değildir.

## Redis Cluster

```java
String scope = ClusterHashTagKeyStrategy.scopedPrefix("cachedb", "checkout");
var writeBehind = WriteBehindConfig.builder()
        .streamKey(scope + ":stream")
        .compactionStreamKey(scope + ":compaction")
        .deadLetterStreamKey(scope + ":dlq")
        .build();
var config = CacheDatabaseConfig.builder()
        .keyspace(KeyspaceConfig.builder().keyPrefix(scope).build())
        .writeBehind(writeBehind)
        .build();
var clientConfig = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(3_000).socketTimeoutMillis(5_000).build();
var redis = new JedisClusterOperations(new JedisCluster(
        Set.of(new HostAndPort("redis-cluster", 6379)), clientConfig));
var database = new CacheDatabase(redis, dataSource, config);
```

Binding kaydını ve start/close yaşam döngüsünü uygulamanız yönetsin. Ayrı sahip olunan
Redis adaptörünü uygulama kapanırken kapatın. Spring kullanırken RedisClientOperations
ve CacheDatabase bean'lerini açıkça tanımlayın; varsayılan pooled istemci oluşturulmaz.
Özel database bean'inde binding kaydı uygulamanın bootstrap sorumluluğudur.
Snapshot ve periyodik warm aynı adaptörleri kullanır. Dağıtık iş/işletim öneklerini,
script'lerinin gerektirdiği ortak tag'e göre ayarlayın; bağımsız stream ayarlarını
incelemeden varsayılanlarla bırakmayın.

Tam CacheDatabase, ortak tag içeren koordinasyon kapsamı ve eşleşen yazım stream'leri
ister; farklı slot'lar başlangıçta reddedilir. Aynı batch'teki entity'ler aynı kapsamda
olmalıdır. Ayrı tenant/iş alanı kapsamları farklı slot'lara dağıtılabilir; tek büyük
kapsam kendiliğinden birden fazla node'a bölünmez.
Düşük seviyeli ClusterHashTagKeyStrategy("cachedb"), entity namespace'ini tag yapar
ve projection için sahibinin tag'ini korur. streamKey(namespace), aynı kapsamı
kullanan ayrı tüketici yapılandırmasıyla kullanılmalıdır. Slot'lar arası ACID yoktur.

Adaptör primary'lerde sınırlı SCAN, slot'a göre MGET/DELETE, Cluster pipeline ve
bütün primary'lere function yüklemeyi destekler. Bellek koruması kapsamın kendi
primary'sini ölçer; aşırı dolu bir node'u ortalamayla gizlemez. Her node'u ayrı izleyin.
Failover, yeniden bölümleme, replikasyon kalıcılığı ve uygulama kabulü altyapıda
doğrulanmalıdır; üç primary'li yerel test HA sertifikası değildir.
