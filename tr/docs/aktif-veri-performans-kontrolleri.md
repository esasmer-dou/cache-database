# Okuma ve Yazma Maliyetini Sınırlama

[English](../../docs/hot-path-controls.md)

Bu özellikler Redis öncelikli modeli geliştirir. Her SQL sorgusunu kendiliğinden
Redis üzerinde eksiksiz çalıştırma garantisi vermez. Okuma kapsamını, kaynak
sorgusunun satır sınırını, zaman aşımını ve kalıcı yazma sürüm kontrolünü koruyun.

## Hangi Durumda Ne Kullanılır?

| İhtiyaç | Araç | Dikkat edilmesi gereken |
| --- | --- | --- |
| Geniş tabloda birkaç alanla arama | Seçili alanlara indeks | Diğer filtreler sınırlı aday kümesinde değerlendirilir |
| Aynı eksik kayda eşzamanlı erişim | Single-flight | Koordinasyon tek JVM içindedir |
| Çok sık okunan tekil kayıt | Erişim birleştirme, isteğe bağlı L1 | LRU yaklaşık çalışır; L1 anlık tutarlılık sağlamaz |
| Stok alanlarını değiştirme | `@CachePatch` | Mutlak değer gönderilir; artırma komutu değildir |
| SQL'den hazırlanan ürün kataloğu | Otomatik dirty-root | SQL'e aktarım tamamlandıktan sonra yenileme planlanır |
| Birden fazla ilişkili kayıt | İşlem birimi | Önce tek Redis kabulü, ardından tek SQL işlemi |

## Seçili İndeksler ve Tipli Kolonlar

```java
@CacheEntity(table = "products", redisNamespace = "products",
        indexedColumns = {"product_id", "category", "stock_quantity"})
public class Product {
    @CacheId(column = "product_id") public Long productId;
    @CacheColumn("category") public String category;
    @CacheColumn("stock_quantity") public Integer stock;
    @CacheColumn("description") public String description;
}
```

Derleyici `ProductCacheBinding.Columns.PRODUCT_ID`, `CATEGORY`, `STOCK_QUANTITY`
ve `DESCRIPTION` alanlarını üretir. Her tanım entity türünü, SQL kolonunu, Java
alan adını ve değer türünü taşır. Kolon adını elle yazmak yerine bu tanımı kullanın:

```java
var source = SnapshotSource.entity("products", ProductCacheBinding.SOURCE)
        .where(SnapshotPredicate.gte(ProductCacheBinding.Columns.STOCK_QUANTITY, 1));
```

Üretilen codec, filtre ve sıralama sırasında alanı doğrudan okur; her satır için
ayrıca kolon haritası oluşturmaz. Eski codec'ler harita kullanan yolla çalışmayı
sürdürür. Varsayılan `indexedColumns = {"*"}` önceki indeksleri korur. Listeyi
daraltmadan önce bütün sorguları inceleyin; bakım araçlarıyla indeksleri yeniden
oluşturup üyelik ve sıralama sonuçlarını karşılaştırın. Partition ve sıralama
indekslerinin alanları kapsama eklenir. Gerekirse
`QueryIndexConfig.sparseIndexMetadata` ile bu optimizasyonu kapatabilirsiniz.

## Aynı Kaynak Sorgusunu Birleştirme

```java
var reads = ReadThroughConfig.builder()
        .mode(ReadThroughMode.READ_THROUGH_QUERY)
        .singleFlight(new SingleFlightConfig(true, 1024, 2048, 10_000))
        .build();
var policy = CachePolicy.builder()
        .hotEntityLimit(10_000)
        .accessDebounceMillis(500)
        .recentTouchSlots(8192)
        .build();
```

Aynı kimlik, sayfa veya sorgu için gelen eşzamanlı eksikler tek kaynak yüklemesini
bekler. Çağıranlara ortak bir değiştirilebilir nesne verilmez; sonuçlar ayrı ayrı
çözülür. Hata ve zaman aşımında bekleme izinleri bırakılır; tamamlanan işler
bellekte tutulmaz. Bekleyenin zaman aşımı, SQL sorgusunu çalıştıran ilk çağrıyı
iptal etmez. JDBC sorgu zaman aşımını da ayarlayın.

Varsayılan sonuç sınırı iş başına 16 Mi karakterdir. Küçük pod'larda sorgu ve satır
sınırlarını daha düşük tutun. Farklı ilişki yükleme planları ve route sözleşmeleri
yanlışlıkla aynı işte birleştirilmez.

Varsayılan 500 ms erişim aralığı aynı kaydın LRU bilgisini her okumada Redis'e
yazmayı önler. Her erişimin kaydedilmesi gerekiyorsa `accessDebounceMillis(0)`
kullanın. Tenant sahipliği ve bayt kotası korunur. Olağan toplu erişimler tenant
bazında birleştirilir; kota baskısında veya sahiplik değiştiğinde mevcut kilitli
kabul ve tahliye davranışı sürer. Redis Cluster'da aynı slot koşulu korunur.

## Yalnızca Belirli Alanları Güncelleme

```java
@CacheRepository(entity = Product.class)
public interface Products extends CacheDbRepository<Product, Long> {
    @CachePatch
    WriteReceipt<Product, Long> replaceStock(
            @CacheId Long id, @CacheColumn("stock_quantity") Integer stock);
}
```

Olmayan kolon, yanlış tür veya kimlik/sürüm/silme alanı değişikliği derleme
sırasında reddedilir. Kuyruk ve SQL UPDATE yalnızca değişen kolonları taşır.
Redis'teki tam kayıt varsa, mevcut biçim korunarak Java tarafında çözülür ve
yeniden kodlanır. Bu yol sıfır kopyalı bir bayt birleştirme işlemi değildir;
Türkçe karakterler ve eski payload biçimi korunur.

Kayıt Redis'te yoksa receipt döndüren komut mevcut SQL satırını güncelleyebilir.
Redis sürümü de yoksa yalnızca sürümü okuyan dar bir kaynak sorgusu gerekir.
Eksik alanlardan uydurma bir entity oluşturulmaz; receipt içindeki `entity` boş
olabilir. Entity döndüren patch metodu ise tam bir önbellek kaydı ister. PATCH
silinmiş kaydı geri oluşturamaz. Önceki sürüm henüz SQL'e aktarılmadıysa kalıcı
sürüm kontrolü işlemi bekletir. Delta işlemleri birleştirilerek atılmaz.
Önbellekte olmayan kaydın projection yenilemesi SQL'e aktarımı bekler ve asenkron
çalışır.

## Kataloğu Otomatik Yenileme

Üretilen entity kaynağına dayalı snapshot planına şu tanımları ekleyin:

```java
plan.scopedBy(source, ProductCacheBinding.Columns.PRODUCT_ID)
        .autoDirtyOnRootEntity();
```

```yaml
cachedb:
  snapshots:
    jobs:
      stock-catalog:
        enabled: true
        dirty-flush-interval: PT1S
        dirty-flush-threshold: 20
```

Spring bağlantıyı kurar. `SnapshotJobs` nesnesini kendiniz oluşturuyorsanız
`start()` öncesinde `.withDatabase(database)` çağırın. Zamanlanmış işler yalnızca
WORKER/FULL rolünde çalışır; ortak Redis lease'i yayınlama işlemini koordine eder.
Redis'in yazmayı kabul etmesi tek başına SQL tabanlı kataloğu yenilemez; SQL'e
aktarım tamamlanmalıdır.
Aynı snapshot planlarını işçi uygulamasına da kaydedin. Yalnızca istemcide
tanımlanan plan, farklı bir uygulamanın SQL commit'ini kendiliğinden gözleyemez.

Değişen kök kimlikleri sınırlı bir tamponda birikir. Taşma, belirsiz çocuk silme
veya ebeveyn değiştirme durumunda yanlış kimlik tahmin etmek yerine tam uzlaştırma
istenir. Bildirim köprüsü kalıcı bir CDC günlüğü değildir. Kaçan değişiklikler ve
uygulama dışı SQL yazmaları için periyodik tam yenileme ve veri yaşı denetimleri
gereklidir.

## Atomik İşlem Birimi

```java
var receipt = database.inTransaction(tx -> {
    tx.put(order);
    tx.putAll(lines);
});
boolean durable = database.awaitDurable(receipt, Duration.ofSeconds(5));
```

Spring servisinde `@CacheTransactional(awaitDurable = true)` aynı eşzamanlı sınırı
açar. Açık komutları `CacheUnitOfWork.current()` üzerinden ekleyin. Entity döndüren
`save` ve `void deleteById` bu birime katılır. `saveAll` dahil receipt döndüren
metotlar commit öncesinde gerçek bir kabul sonucu üretemez; bu nedenle yazma
yapmadan hata verir. Toplu kayıt için `tx.putAll` kullanın.

Sınırlar: 100 farklı entity kimliği, 4 MiB kodlanmış argüman ve 2000 kuyruk alanı.
Atomik PATCH tam, sürümlü bir Redis kaydı ister. İç içe veya farklı veritabanlarına
yayılmış işlemler, asenkron metotlar ve sınırlı tenant kabul sözleşmeleri
reddedilir. Redis Cluster üyeleri aynı hash-tag slotunda olmalıdır. Commit öncesi
hata, biriken yazmaları iptal eder. Kabulden sonra SQL bekleme süresinin dolması
**rollback değildir**. `CacheTransactionDurabilityTimeoutException.receipt()`
sonucunu saklayıp işçi durumunu inceleyin. Spring servis metodu dışarıdan bean
proxy'si üzerinden çağrılmalı, public ve final olmayan bir metot olmalıdır.
Kendi içinden çağırma sınırı açmaz. Bu özellik dağıtık XA işlemi değildir.

## İzleme ve SQL'e Aktarımı Bekleme

`TraceContextPropagator` en çok sekiz alan taşır; alan adı ve değeri ayrı ayrı
512 karakterle sınırlıdır. Spring MDC köprüsü yalnızca izinli W3C bağlamını ve
ilişkilendirme kimliklerini alır. İşçi, kurtarma ve iş metotlarında bağlam açılır
ve sonunda kapatılır. Bu köprü kendi başına OpenTelemetry span'i veya exporter
oluşturmaz. SQL grupları izleme uğruna bölünmez; ilk işlemin bağlamı kullanılır,
diğer bağlamlar kuyrukta korunur. Bu alanlara gizli bilgi koymayın.

`CacheDatabaseMetricsMeterBinder` yerel kuyruk/yazma sayaçlarını ve Redis/SQL süre
ölçümlerini sunar. Prometheus sayaç adlarına `_total` ekler. Süre ölçümleri için
storage performance collection açık olmalıdır; kapalıysa bu süre sayaçları sıfır
kalır. Aynı JVM'de kalıcılık işareti ilerleyince bekleyen çağrılar doğrudan
uyandırılır. Farklı süreçler
arasında 2/4/8/15/25 ms ile sınırlı artan sorgulama kullanılır. Gerçek gecikmeye
ağ ve işlemci zamanlaması da etki eder.

## İsteğe Bağlı L1 Önbellek

```java
@CacheNearPolicy(maxEntries = 1024, ttlSeconds = 2, maxBytes = 4 * 1024 * 1024)
```

Entity'ye bu annotation'ı ekleyin veya `CachePolicy.Builder.nearCachePolicy`
kullanın. Varsayılan kapalıdır. Kodlanmış veri saklanır ve her okumada ayrı nesneye
çözülür; record türleri de değiştirilebilir içerik taşıyabileceği için istisna
değildir. Çalışma zamanı toplam sınırı 64000 kayıt ve tahmini 64 MiB rezervasyondur.
Süre 60 saniyeyi ve Redis kaydının gözlenen kalan ömrünü aşamaz. Bölümlenmiş
tahliye nedeniyle gerçek kayıt sayısı üst sınırdan düşük kalabilir.

Tek bir arka plan aboneliği diğer pod'lardaki kopyaları geçersiz kılar. Bağlantı
kesilince veya yeniden kurulunca yerel kayıtlar temizlenir; bağlantı yokken Redis
kullanılır. Geçersiz kılma sırasında başlayan eski bir doldurma reddedilir.
Pub/sub kalıcı değildir: bu özellik süreyle sınırlı eventual consistency sunar,
anlık tutarlılık sağlamaz. Harici SQL/Redis değişiklikleri desteklenen apply veya
uzlaştırma yollarından geçmelidir. Sınırlı tenant route'ları L1'i kullanmaz.
Her uzak yazmayı hemen görmesi gereken okumalarda L1'i açmayın.

## Güvenli Sürüm Geçişi

BOM, processor, starter, işçi ve örnek sürümlerini hizalayıp üretilen sınıfları
yeniden derleyin. Eski kurucular ve mevcut küçük Redis payload'ları korunur.
PATCH üreten uygulamaları açmadan önce eski işçileri durdurup kuyruğu boşaltın;
eski işçiler yeni işlem türünü çözemez. Eski zarflarda izleme alanı isteğe bağlıdır.
Varsayılan Redis Function kütüphane sürümü de `0.16.0` olur. Yeni üreticileri
açmadan önce kütüphaneyi güncelleyin. Özel dağıtımlarda da kaynak ve sürümü
hizalayın; içerik değişikliği korumasını devre dışı bırakmayın.
Kalıcı fence metadata tablosunu ve Redis sürüm sayaçlarını silmeyin. Sağlayıcıya
özel HA davranışını kendi topolojinizde doğrulayın.

L1 için Redis kullanıcısının ilgili kanalda `SUBSCRIBE` ve `PUBLISH` izni gerekir.
Yayın yetkisi yazma başlamadan denetlenir; Lua'da yazmadan sonra oluşan hata,
önceki yazmaları geri almaz. Bkz.
[Redis Lua ACL API](https://redis.io/docs/latest/develop/programmability/lua-api/#redisacl_check_cmdcommand-arg).
