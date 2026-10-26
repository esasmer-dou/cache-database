# Deklaratif Veri Kontrolleri

[English](../../docs/declarative-data-controls.md) · [Sürüm sözleşmeleri](releases/v0.17.0.md)

Bu özellikleri Redis için tanımladığınız aktif kümede kullanın. SQL geçmişinin
tamamı kendiliğinden yüklenmez. SQL şemasını hazırlayın, CacheDatabase ve worker'ı
başlatın; mevcut kayıtları önbellekten sunmadan önce ön yükleme yapın.

## Yapılandırma

| Ayar | Varsayılan | Amaç |
| --- | --- | --- |
| `cachedb.config.resourceLimits.maxWatchersPerEntity` | `64` | Repository başına yerel dinleyici sınırı; 1..64 arası. |
| `cachedb.config.resourceLimits.defaultCachePolicy.compressionThresholdBytes` | `0` | Varsayılan entity politikasının sıkıştırma eşiği; sıfır bu ayarı kapatır. |
| `cachedb.config.mutations.maxPatchAttempts` | `4` | Sürüm kontrollü güncellemede sınırlı deneme; en çok 16. |
| `cachedb.config.queryIndex.maxMaterializedCandidateIds` | `10000` | Belleğe alınan aday sınırı; aşılırsa eksik toplam yerine hata döner. |

Ayarları başlangıçta uygulayın. Entity'ye özel politika varsayılanın yerini alabilir.
Annotation ve politika birlikte sıkıştırma eşiği belirtiyorsa büyük olan kullanılır.
Yeni yazmalarda sıkıştırmayı kapatmak için ikisini de sıfırlayın; önceden sıkıştırılmış
veri okunmaya devam eder. Callback kısa olmalı; çakışmalar iş akışında ele alınmalı;
bildirim kaybı, gecikme ve Redis belleği izlenmelidir.

## Stok: Değer Atamak mı, Miktarı Değiştirmek mi?

Normal PATCH, “stoğu 10 yap” demektir. Delta ise “mevcut kabul edilmiş stoktan 3
çıkar, ama sıfırın altına düşürme” anlamına gelir. İkinci işlemi kilitsiz bir Java
okumasının ardından mutlak değer yazarak kurmayın; başka isteğin değişikliği kaybolabilir.

```java
package example.inventory;

import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "inventory", redisNamespace = "inventory",
        indexedColumns = {"sku_id", "status", "available_units"})
public record Inventory(
        @CacheId(column = "sku_id") Long skuId,
        @CacheColumn("available_units") Integer availableUnits,
        @CacheColumn("status") String status) {}
```

```java
package example.inventory;

import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.query.AggregateFunction;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import java.math.BigDecimal;

@CacheRepository(entity = Inventory.class)
public interface InventoryRepository extends CacheDbRepository<Inventory, Long> {
    @CachePatch
    boolean reserve(@CacheId Long skuId,
            @CacheDelta(value = "available_units", min = 0) Integer change);

    long countByStatus(String status);

    @CacheAggregate(function = AggregateFunction.SUM, field = "available_units")
    BigDecimal sumUnitsByStatus(String status);
}
```

SQL tablosu ve çalışan `database` nesnesi hazırken:

```java
database.registerJdbcBacked(InventoryCacheBinding.METADATA,
        InventoryCacheBinding.CODEC);
var inventory = new InventoryRepositoryCacheDbImplementation(database);
var receipt = inventory.save(new Inventory(101L, 10, "ACTIVE"));
boolean accepted = inventory.reserve(101L, -3); // mevcut stok 7 olur
long activeRows = inventory.countByStatus("ACTIVE");
BigDecimal units = inventory.sumUnitsByStatus("ACTIVE"); // bu aktif kümede 7
```

Üretilen boolean delta metodunun `false` döndürmesi, tam bir önbellek kaydı
bulunmadığını belirtir. `CacheDeltaBoundsException`, tanımlanan sınırın aşıldığını
gösterir. Sürüm çakışması ayrı bir hatadır: PATCH denemeleri
`cachedb.config.mutations.maxPatchAttempts` ile sınırlıdır; varsayılan 4, üst sınır
16'dır. Sınır aşımını tekrar denemeyin. Ağ zaman aşımında sonucu belirsiz kalan
bir delta komutunu da körlemesine yinelemeyin; komutun iş seviyesinde idempotency
anahtarı yoktur. Redis kabulü SQL commit'i değildir. İş sınırı gerektiriyorsa ilgili
receipt için kalıcılık sonucunu ayrıca bekleyin.

SQL kuyruğuna ikinci bir artırma komutu değil, hesaplanmış mutlak alan değeri
gider. Redis'teki tam kayıt yine çözülüp kodlanır; bu sıfır kopyalı güncelleme
değildir. Bir istekte birden fazla delta varsa tamamı sınır kontrolünden geçmelidir.

## Dashboard: Aktif Kümeyi Saymak ve Toplamak

COUNT ve diğer toplamlar “veritabanındaki bütün kayıtlar” anlamına gelmez. Son
90 günlük aktif küme tanımladıysanız, sonuç o anda Redis'te bulunan ve sunulabilen
veriyi kapsar. Tam geçmişi içeren mali rapor için açık bir SQL raporlama yolu kullanın.

İndeksle karşılanan filtrelerde kimlikler entity verisi okunmadan bulunabilir.
COUNT, veri gövdesine GET/MGET yapmadan kayıt ve silme işaretini kontrol eder.
Sayısal alan indeksliyse toplamda orijinal indeks metadata değeri kullanılır;
Redis'in `double` skorundan parasal sonuç üretilmez. İndeks dışı filtre/alan veya
dinamik kabul politikası varsa sınırlı entity okuması yapılabilir. Boş SUM sıfır;
boş MIN/MAX `null`; boş AVG `OptionalDouble.empty()` döndürür. COUNT eşleşen
satırları sayar; AVG sayısal alanı `null` olan satırları ortalamaya katmaz.

## SaaS: Doğrulanmış Tenant Kapsamı Açmak

Kalıcı bir String alanı veya record bileşenini `@CacheTenantId` ile işaretleyin.
Processor, seçili indeksler kullanılsa bile bu alanın SQL kolonunu exact indeks
kapsamına ekler.

```java
try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant(
        authenticatedTenantId)) {
    // Entity'sinde @CacheTenantId bulunan bir repository kullanın.
    tenantRepository.findHotById(recordId);
}
```

Kapsamı bir göreve taşımak için `wrap`, `wrapCallable` veya `wrapSupplier`
kullanın. ThreadLocal, executor veya sanal thread'e kendiliğinden aktarılmaz.
Görev hata verse de önceki kapsam geri yüklenir. MDC'yi veya gelen HTTP başlığını
tek başına yetkilendirme bilgisi kabul etmeyin.

Sorgu, sayım ve toplamlara tenant filtresi eklenir. Başkasının kimliğiyle okuma
ve yazma reddedilir. Tenant alanı PATCH ile değiştirilemez. Sahiplik bilinmiyorsa
güvenilir kimlik yükleyicisinden doğrulanır; Redis'in atomik yazma fonksiyonu da
sahipliği denetler. Silinen bir kimlik başka tenant'a devredilemez.

Güvenilir kaynak, silinmiş satırları da kapsayan `EntityOwnershipLoader` sözleşmesini
uygulamalıdır. `registerJdbcBacked` bunu sağlar. Toplu yazmada bilinmeyen kimlikler,
her kayıt için ayrı istek yerine en çok 500'lük SQL gruplarıyla denetlenir.

Tenant entity'sinde kapsam yoksa erişim reddedilir. Güvenilir bakım işi açıkça
`CacheTenantContext.administrative()` açıp try-with-resources ile kapatmalıdır.
Gelen istek ve servis sınırında kapsam açılmasını uygulama sağlamalıdır. Genel SQL, özel kaynak yükleyicileri ve
snapshot kaynakları açık uygulama sözleşmeleridir; her SQL'in güvenle yeniden
yazılacağını varsaymayın. Henüz uygulanmamış tenant batch/partition/projection
birleşimleri, izolasyonu atlamak yerine kapalı kalır.

## Operasyon: Yerel Filtre Değişikliklerini İzlemek

```java
var rows = database.repository(InventoryCacheBinding.METADATA,
        InventoryCacheBinding.CODEC);
try (var registration = rows.registerWatcher(
        com.reactor.cachedb.core.query.QuerySpec.where(
                InventoryCacheBinding.Columns.STATUS.eq("LOW_STOCK")),
        event -> System.getLogger("inventory").log(
                System.Logger.Level.INFO, event.changeType().name()))) {
    // Kaydı, gerekli uygulama yaşam döngüsü boyunca açık tutun.
}
```

Spring bean'inde `CacheChangeEvent<Inventory, Long>` alan public bir `void` metoda
`@CacheWatcher(entity = Inventory.class, filterField = "status", filterEquals = "LOW_STOCK")`
eklenebilir. Processor doğrudan metot referansı üretir; olay çağrısı reflection
kullanmaz. Registration kapatıldığında sonraki bildirimler devre dışı kalır.

Watcher, yerel önbellek kabulünü bildirir; SQL kalıcılığını veya bütün pod'ların
değişikliklerini değil. Başlangıç verisini oynatma, kalıcı replay, tam bir kez
teslim veya sıralı pencere transaction garantisi yoktur. Kuyruk sınırlıdır;
kayıpları izleyin ve callback içinde bekleyen işler yapmayın. Güvenilir iş
olayları için outbox/CDC kullanın.

## Dağıtım: Redis'i Boşaltmadan Eski Veriyi Okumak

`@CacheSchemaVersion(version = 2, upcaster = InventoryV2Upcaster.class)`, yan etkisiz
bir dönüştürücü seçer. Generated entity codec'inde uzunluk önekli gövdeyi dönüştürün;
veriyi JSON sanıp metin değiştirme yapmayın. Örneğin açık bir alan adı geçişi,
diğer kolonları koruyabilir:

```java
public final class InventoryV2Upcaster
        implements com.reactor.cachedb.core.codec.CachePayloadUpcaster {
    public int targetVersion() { return 2; }
    public String upcast(String body, int fromVersion, int toVersion) {
        if (fromVersion != 1 || toVersion != 2)
            throw new IllegalArgumentException("Unsupported schema transition");
        var fields = com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.decode(body);
        String old = fields.remove("quantity");
        fields.putIfAbsent("available_units", old == null ? "0" : old);
        return com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.encode(fields);
    }
}
```

Örnekteki sıfır varsayımı bir iş kararıdır; framework bu kararı kendisi vermez.
Yeni okuyucu eski gövdeyi dönüştürebilir; eski okuyucu ileri sürümü otomatik
anlayamaz. Okuyucu/yazıcı geçişini ve SQL DDL'ini ayrı planlayın. Okuma sırasında
dönüşüm yapılması, Redis kaydının yeniden yazıldığı anlamına gelmez.

## Büyük Belgeler: Sıkıştırmayı Seçerek Açmak

```java
var policy = com.reactor.cachedb.core.cache.CachePolicy.builder()
        .hotEntityLimit(5_000)
        .compressionThresholdBytes(4096)
        .build();
```

Alternatif olarak `@CacheEntity` üzerinde `compressionThresholdBytes = 4096`
tanımlayın. Eşik, Java karakter sayısını değil UTF-8 bayt sayısını kullanır.
Küçülme hesabına Base64 maliyeti de katılır. Varsayılan sıfırdır; sıkıştırma kapalı
başlar. Ayarı kapatmak, eski sıkıştırılmış kayıtların okunmasını engellemez.
Açılan veri 16 MiB ile sınırlıdır; codec'in daha düşük sınırı varsa o da geçerlidir.
Gerçek verinizle Redis boyutunu, CPU'yu ve yüksek yüzdelik gecikmeyi ölçün; sabit
bir sıkıştırma yüzdesi varsaymayın.

Sıkıştırılmış/şema dönüşümü kullanılan yollarda L1 nesne paylaşımı kapalıdır.
Küçük bir ağ kaydı, bütçeye katılmamış büyük bir çözülmüş nesneyi gizlememelidir.

### Snapshot Geçici Dosyaları

Snapshot JSON cevabı, entity veri çerçevesi değildir; bu nedenle ayrı bir seçenek kullanır:

```java
var compactPlan = existingPlan.withSpoolCompression(4096);
// compactPlan değerini, mevcut SnapshotPlan ile aynı yöntemle kaydedin.
```

Yalnızca küçülme sağlayan ve en çok 16 MiB olan geçici disk blokları sıkıştırılır.
Daha büyük kök kayıtlar mevcut akış tabanlı disk yolunu kullanır. Yayımlanan JSON,
içerik özeti, mantıksal bayt sayısı ve yayın grubu sınırları değişmez. Bu seçenek
geçici disk alanını azaltır; snapshot Redis belleğini veya HTTP boyutunu azaltmaz.
Kök kayıt başına ek flush, okuma ve sıkıştırma işi doğurur. Yalnızca disk baskısı
bu maliyeti gerektiriyorsa açın.

## Sıralı Okuma ve Yerel Önbellek

Desteklenen COUNT_WINDOW yollarında aynı slot'taki sunucu çağrısı, sıralı veriyi
TTL bilgisiyle birlikte döndürür. L1'e eklenirken cevabı alma ve çözme süresi düşülür;
mevcut geçersiz kılma denetimi de korunur. Redis kaydının ömrü uzatılmaz. TTL
sağlamayan özel istemci adaptörü, bu hızlı yoldan L1'i dolduramaz. Erişim kaydı ayrı
olabilir; tek veri alma çağrısı, bütün isteğin tek Redis komutu olduğu anlamına gelmez.

Sayısal değişim ve toplam hesaplamalarında en çok 1.024 basamak ve mutlak değeri
1.024 olan ölçek kabul edilir. Aşırı ölçekli sayılar, büyük ara bellek oluşturmadan reddedilir.
