# Tipli Snapshot Okumaları, Çalışma Rolleri ve Uygulama Testleri

[English](../../docs/typed-snapshots-runtime-testing.md) | Türkçe

Bu sayfa 0.14.0 API'sini anlatır. Yayımlanmış 0.13.0 paketinde bu eklemeler yoktur.
Framework, starter ve processor sürümlerini birlikte güncelleyin; farklı sürümlerle
üretilmiş sınıfları aynı uygulamada karıştırmayın.

Record tabanlı snapshot yanıtlarını Jackson olmadan hazırlamak ve okumak için
[üretilmiş JSON codec'leri](#jackson-olmadan-üretilmiş-json-codec-kullanımı) bölümüne bakın.

## Jackson Olmadan Üretilmiş JSON Codec Kullanımı

Bu seçenek, record biçimindeki yanıtları çalışma anında sınıf keşfi ve otomatik
nesne eşleme yapmadan JSON'a dönüştürür. Mevcut `ObjectMapper` kullanan metotlar
değişmez. Bu özellik, CacheDB veya Spring'in tüm bileşenlerinden Jackson'ı kaldırmaz.

### 1. İsteğe Bağlı Modülü Ekleyin

CacheDB BOM'u aynı sürümle içe aktarılmışsa şu bağımlılık yeterlidir:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-json</artifactId>
</dependency>
```

`cachedb-annotations` derleme bağımlılığında, `cachedb-processor` ise derleyicinin
annotation processor ayarlarında bulunmalıdır. Tümünü 0.14.0 ile hizalayın.
Yeni modül, Gson'ın katı kurallarla çalışan JSON okuyucusunu kullanır; otomatik
nesne eşlemesi yapmaz. Üretilen codec'ler, boyut ve derinlik sınırlarını uygulayan
CacheDB token yazıcısını kullanır. Bu yolda Gson yazıcısının ilk yükleme maliyeti
oluşmaz. Özel `StreamingJsonCodec` sınıfları, açıkça `GeneratedJsonWriter`
uygulamadıkları sürece mevcut Gson yazıcısını kullanmaya devam eder. Jackson
yolunda kalan uygulamalara yeni bir çalışma zamanı bağımlılığı eklenmez.

CacheDB BOM'u Gson sürümünü test edilen 2.13.2 ile hizalar. Başka bir BOM veya
parent bu sürümü değiştiriyorsa `mvn dependency:tree -Dincludes=com.google.code.gson:gson`
ile sonucu kontrol edin ve aşağıdaki doğrudan bağımlılığı ekleyin. Eski sürümler
gereken streaming denetimlerini içermez:

```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.13.2</version>
</dependency>
```

### 2. Yanıt Modelini Tanımlayın

`example/CatalogSummary.java` dosyası:

```java
package example;

import com.reactor.cachedb.annotations.CacheJsonRecord;

@CacheJsonRecord
public record CatalogSummary(String title) {}
```

Derleyici `example.CatalogSummaryJsonCodec` sınıfını ve `INSTANCE` alanını üretir.
Üretilen sınıfı elle yazmayın ve kaynak olarak Git'e eklemeyin. Public iç record'lar
da desteklenir: `Outer.Summary` için aynı pakette `Outer_SummaryJsonCodec` üretilir.

### 3. Snapshot Planında Codec Seçin

Kaynak veritabanında `products(product_id, title)` tablosu bulunduğunu varsayalım:

```java
package example;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.spring.boot.snapshot.*;
import java.util.List;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class CatalogSnapshots {
    public record Product(long id, String title) {}

    @Bean
    SnapshotPlan<Product, CatalogSummary> catalog() {
        var source = SnapshotSource.entity("products", new SourceMapping<Product>(
                "products", List.of("product_id", "title"), row -> new Product(
                        ((Number) row.get("product_id")).longValue(),
                        (String) row.get("title"))));
        return SnapshotPlan.streaming("catalog", source,
                        product -> Long.toString(product.id()), CatalogSummary.class,
                        product -> List.of(new CatalogSummary(product.title())))
                .withJsonCodec(CatalogSummaryJsonCodec.INSTANCE);
    }
}
```

Bütün planlarda codec seçilmişse Spring snapshot kurulumu `ObjectMapper` bean'ini
istemeden çalışır. Eski ve yeni planlar birlikte kullanılıyorsa eski planlar mevcut
mapper ayarlarıyla devam eder. Elle kurulum için `SnapshotJobs.jacksonFree(...)`
kullanılabilir; codec'i eksik bir plan varsa işleyici başlatılmadan hata verilir.

Veri hazırlama sırası değişmez: framework tanımlanan SQL kaynağını okur, sınırlı
payload'ları hazırlar ve yeni nesli atomik olarak yayımlar. Tipli okumalar hazır
JSON'u çözümler; veri eksik diye kendiliğinden SQL sorgusu çalıştırmaz.
`SnapshotPlan.map(...)` yeni bir yanıt sözleşmesi oluşturduğu için eski codec'i
taşımaz. Dönen plana yeni yanıt tipinin codec'ini ayrıca ekleyin.

Aynı codec ile altyapısız test yazabilirsiniz:

```java
var raw = new com.reactor.cachedb.spring.boot.snapshot.InMemorySnapshotRepository();
var catalog = raw.typedRepository(example.CatalogSummaryJsonCodec.INSTANCE);
raw.putSnapshot("42", "[{\"title\":\"Mevcut stok\"}]");
assert catalog.lookup("42").values().get(0).title().equals("Mevcut stok");
```

### Desteklenen Tipler ve Sınırlar

| Java tipi | JSON karşılığı |
|---|---|
| İlkel tipler, sarmalayıcıları, String, BigInteger, BigDecimal | Skaler değerler; sonlu kayan noktalı sayılar |
| UUID, desteklenen java.time tipleri, enum | Metin; enum sabitinin adı |
| Annotation eklenmiş record, List, String anahtarlı Map | İç içe nesne, dizi ve nesne |

Desteklenen zaman tipleri: Instant, LocalDate, LocalTime, LocalDateTime,
OffsetDateTime ve ZonedDateTime. Koleksiyonların içindeki değerler null olabilir;
en dıştaki snapshot dizisi ve bu dizinin record öğeleri null olamaz.
Eksik ilkel alanlar sıfır/false değerini alır. Aynı alan yinelenirse son değer
kullanılır. Bilinmeyen alanlar varsayılan olarak hatadır; yalnızca açıkça
`@CacheJsonRecord(ignoreUnknown = true)` seçilirse atlanır.

Varsayılan sınırlar: iç içe derinlik 64, dizi/Map başına 100.000 öğe, sayısal metin
uzunluğu ve üssün mutlak değeri en fazla 1.024, kök JSON belgesi başına 16.777.216
UTF-16 karakter birimi. Bunlar Redis bellek kotası değildir. Büyük veya güvenilmeyen
verilerde ihtiyaca göre daha dar sınırlar belirleyin:

```java
var codec = new example.CatalogSummaryJsonCodec(
        new com.reactor.cachedb.json.JsonCodecLimits(16, 1000, 128, 1_048_576));
// Plan üzerinde bu örneği seçin: plan.withJsonCodec(codec).
```

Çalışma anında reflection, sınıf taraması veya sessizce başka bir eşleyiciye geçiş
yoktur. Desteklenmeyen tipler, generic record'lar ve Jackson/Gson eşleme
annotation'ları derleme hatası verir. JSON alanları record bileşenlerinin adlarıdır;
özel adlandırma, serializer veya esnek tip dönüşümü ayarları taklit edilmez.
Bu sözleşmeler için eski mapper'ı koruyun ya da açık bir `JsonArrayCodec` sağlayın.
Mevcut planı değiştirmeden önce gerçek DTO örneklerinde alan değerlerini, sayısal
hassasiyeti, null davranışını ve sıralamayı karşılaştırın. JSON kaçışları ve boşluklar
birebir aynı olmayabilir; içerik hash'lerinin değişmeyeceğini varsaymak yerine
encoder değişiminde veriyi yeniden hazırlayın.

### İlk Kullanım Süresini Tekrarlanabilir Ölçün

```powershell
./tools/ci/check-jackson-free-latency.ps1 -Runs 5 -PackagedArtifacts
```

`-PackagedArtifacts`, ayrı class dizinleri yerine derlenen modül JAR'larını çalıştırır.
Rapor, JAR'ların SHA-256 değerlerini de saklar. Katı CI kontrolü bu kipi kullanır.

Araç, gerçek üretilmiş codec örneğini Jackson JAR'ları bulunmayan classpath ile
çalıştırır. Her senaryo için beş ayrı JVM açılır: çözümleme, yazıp okuma ve bellek
içi snapshot sözleşmesi. İlk çağrı ve sonraki 20 çağrının tamamı saklanır; ısınma
bahanesiyle hiçbir örnek atılmaz. Bütün sürelerin 50 ms'den kısa olması gerekir.
Derleme ve JVM sürecinin açılışı ölçülmez; senaryonun ilk hazırlığı ölçülür.
`-ReportOnly` süre aşımında komutu durdurmaz ama başarısız sonucu değiştirmez;
işlevsel hata her durumda komutu başarısız yapar. Çıktılar
`target/jackson-free-latency/` altında tutulur. Bu kontrol production yük kapasitesi
veya bellek tahsis testi değildir; başka senaryoları ya da JVM'leri sertifikalandırmaz.

## İhtiyaca Göre Seçim

| İhtiyaç | Kullanım | Sınır |
|---|---|---|
| Hazır cevabı Java nesneleriyle okumak | `TypedSnapshotRepository<ID, V>` | Yalnızca Redis |
| Eksik kayıtla kullanılamayan kataloğu ayırmak | `SnapshotLookup<V>` | Kendiliğinden SQL'e geçmez |
| Birden fazla kökü tutarlı okumak | `lookupAll` / `findByIds` | Aynı nesil, en fazla 1.000 kimlik |
| Bağımsız kökleri büyük bir listede biriktirmeden hazırlamak | `SnapshotPlan.streaming` | Açıkça seçilen, kayıt bazlı dönüşüm |
| API pod'larını SQL işleyicilerinden ayırmak | `CacheRuntimeRole` | Ayrı işleyici pod'ları gerekir |
| İş kodunu konteyner açmadan test etmek | `InMemoryCacheDatabase` | Test aracıdır; kalıcılık kanıtı değildir |

## Stok Kataloğunu Tanımlayın

Örnekte kaynak tablo `inventory(stock_id, sku, available)` biçimindedir.
Kimlik ve stok miktarı, sağlayıcıya özgü bir sayısal sınıfa bağlanmadan okunur.

```java
import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.spring.boot.snapshot.*;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InventorySnapshots {
    public record Stock(long id, String sku, int available) {}

    @Bean
    SnapshotPlan<Stock, Stock> inventorySnapshot() {
        var source = SnapshotSource.entity("stock", new SourceMapping<Stock>(
                "inventory", List.of("stock_id", "sku", "available"), row -> new Stock(
                        ((Number) row.get("stock_id")).longValue(),
                        (String) row.get("sku"),
                        ((Number) row.get("available")).intValue())));

        return SnapshotPlan.streaming("inventory", source,
                        stock -> Long.toString(stock.id()), Stock.class, List::of)
                .indexed(SnapshotIndexSpec.of(stock -> "all", Stock::available))
                .scoped(SnapshotRootScope.builder()
                        .source(source, ids -> SnapshotPredicate.inValues("stock_id",
                                ids.stream().map(Long::valueOf).toList()))
                        .build());
    }
}
```

İndeks küçükten büyüğe sıralanır; stok miktarı az olan ürünler önce gelir.
Eşit puanlarda kimliğin metinsel sırası kullanılır. Tarihe göre yeniden eskiye
sıralama için [snapshot rehberindeki](snapshot-projectionlar.md) negatif zaman
damgası örneğine bakın.

`streaming`, her kökün diğer köklerden bağımsız dönüştürülebileceğini açıkça belirtir.
Dönüştürücüye bütün kaynak kataloğu verilmez. Mevcut `new SnapshotPlan<>(...)`
tanımları, tek kaynaklı olsalar bile bütün `SnapshotRows` verisini görmeye devam eder.
Birden çok kaynağı birleştiren planların sınırlı kaynak listeleri ve ilişki indeksleri
JVM belleğinde kalır. Streaming yalnızca kök listesini kaldırır; geçici dosyanın
indeksini, JDBC sürücüsünün tamponlarını ve kullanıcıya dönen cevabı ortadan kaldırmaz.

Tam katalog taraması, bilinçli bir hazırlık işlemidir; GET isteğinin yan etkisi değildir.
Büyük kaynakları filtreleyin. Zamanlanmış yenilemeyi açmadan önce satır/bayt sınırını,
JDBC parti boyutunu, hazırlık süresini ve geçici disk alanını ayarlayın.
Hazırlık başarısız olursa önceki nesil kullanılmaya devam eder.

## Veriyi ve Durumunu Okuyun

```java
TypedSnapshotRepository<Long, InventorySnapshots.Stock> repository =
        jobs.typedRepository("inventory", InventorySnapshots.Stock.class, Object::toString);

SnapshotLookup<InventorySnapshots.Stock> one = repository.lookup(42L);
var several = repository.lookupAll(List.of(42L, 43L));
var first = repository.findTypedPage("all", WindowRequest.first(50));
var next = first.nextRequest(50).map(window -> repository.findTypedPage("all", window));
repository.refreshRoots(List.of(42L)); // Açık SQL komutu; yukarıdaki kapsam tanımını gerektirir.
```

`WindowRequest` için `com.reactor.cachedb.core.repository.WindowRequest` sınıfını
içe aktarın. `jobs`, Spring'in oluşturduğu `SnapshotJobs` bean'idir.
Sınıf parametresi alan metot, yanlış DTO türünü repository oluşturulurken yakalar.
JSON okuyucusu plan başına hazırlanır; her okumada yeni bir tür tanımı üretilmez.

| Durum | Anlamı | Uygulamanın kararı |
|---|---|---|
| `FOUND` | Kök aktif nesilde var | `values()` kullanılabilir |
| `STALE_WARNING` | Kök var; uyarı yaşı aşılmış | Ekranın güncellik kuralına göre gösterin veya reddedin |
| `ABSENT_IN_GENERATION` | Katalog kullanılabilir; seçilen kök yok | İş kuralına uygun eksik kayıt cevabı verin |
| `SNAPSHOT_UNAVAILABLE` | Aktif yayın işaretçisi yok | Açık bir kullanılamıyor cevabı veya ayrıca tanımlanmış kaynak yolu kullanın |

Hazırlanmış `[]`, mevcut ama boş bir sonuçtur. `findValuesById`, bunu
`Optional.of(List.of())` olarak korur; `Optional.empty()` ile karıştırmaz.
`refreshedAt`, `ageMillis` ve `generation` alanları kayıt ve güncellik kontrolleri
için kullanılabilir. `warn-age=PT0S` uyarıyı kapatır; mevcut varsayılan PT3M'dir.
Uyarı, veriyi kendiliğinden silmez ve HTTP durum kodu belirlemez. Bozuk içerik,
bağlantı sorunu veya JSON dönüşüm hatası gizlenmez; işlem açıkça hata verir.

Toplu okuma tek Lua işlemiyle, tek nesilden yapılır. Tekrarlanan kimlikler sonuç
haritasında birleştirilir. Serileştirilmiş cevap bütçesi en fazla 4 MiB'dir;
`batch-target-size` ile daha düşük tutulabilir. Aşımda sonuç kesilmez, hata verilir.
Tek kök okuması, daha büyük bir kökü döndürebilen mevcut davranışı korur.
Yine de DTO ve HTTP cevap boyutuna uygulama seviyesinde sınır koyun.

Tam yayın, önceki neslin bütün cursor'larını geçersiz kılar. Artımlı yenileme ise
yalnızca seçilen köklerin eski ve yeni bölümlerini etkiler. A bölümündeki yenileme,
B bölümünün sayfalamasını bozmaz. B'nin kendisi değişirse B'de sayfalamaya yeniden
başlanır. Cursor, nesli, bölümün revizyonunu ve son puan/kimlik çiftini taşır;
değişen bir bölümü kesintisiz canlı liste olarak izleme garantisi vermez.

## API ve İşleyici Pod'larını Ayırın

```properties
# API dağıtımı
cachedb.runtime-role=CLIENT_ONLY
```

```properties
# Aynı entity/projection tanımları ve Redis ayarlarıyla çalışan işleyici dağıtımı
cachedb.runtime-role=WORKER_ONLY
```

| Rol | Redis okuması / yazma kabulü | Otomatik arka plan işleri |
|---|---|---|
| `FULL` (varsayılan) | Açık | Mevcut özellik ayarlarına göre açık |
| `CLIENT_ONLY` | Açık | SQL aktarımı, DLQ, temizlik, geçmiş toplama, zamanlanmış warm/snapshot ve iş tüketimi kapalı |
| `WORKER_ONLY` | Uygulama kullanabilir | İşleyici özellikleri açık |

`WORKER_ONLY`, uygulamanın REST controller'larını kapatmaz veya güvenli hâle getirmez.
İşleyici dağıtımında uygulama uç noktalarını açmayın ya da ayrıca koruyun.
`CLIENT_ONLY`, tek başına bütün sistemi çalıştırmaz: işleyici yoksa yazılar Redis'te
birikir ve SQL'de kalıcı hâle gelmez. Kuyruk uzunluğunu, en eski bekleyen işin yaşını,
DLQ'yu, projection gecikmesini ve Redis belleğini izleyin. Her iki dağıtımda da bağlantı
zaman aşımı ve havuz sınırlarını belirleyin. İşleyiciye aynı metadata, codec ve
projection tanımları gerekir; yalnızca aynı Redis'e bağlanması yeterli değildir.

Redis Cluster'da etiketsiz genel önek, namespace başına dağılımı açar. Entity,
projection, sürüm, silinme işaretçisi, aktif küme ve yazma stream'i aynı slotta kalır.
Sınırlı işleyici havuzu, farklı slotları tek XREADGROUP çağrısına koymadan okur.
Admin stream listesi, admin servisi oluşturulduktan sonra kaydedilen namespace'leri
de izler. Backlog tahmini, compaction işlemlerinin yanında atomik batch zarflarını
da kapsar; SQL commit makbuzu yerine geçmez.
Açık `{scope}` etiketi, ilişkili atomik batch'ler için ortak slot davranışını korur;
stream'leri de aynı etiketle yapılandırın. Farklı slotları kapsayan atomik batch,
veriyi değiştirmeden reddedilir. Ayrı slotlar, SQL ve Redis'i kapsayan dağıtık bir
transaction oluşturmaz.

Uygulama namespace'inde önceden `{...}` etiketi bulunan snapshot planları artık kendi
birleşik etiketini kullanır. Anahtar düzeni değişeceği için trafiği taşımadan önce
bu planları yeniden hazırlayın. Etiketsiz snapshot namespace'lerinin düzeni korunur.
Aynı entity'ye geçiş sırasında iki farklı anahtar stratejisiyle yazmayın.
Function yeniden yüklemesi yalnızca açık bir eksik fonksiyon cevabında, bir kez
yeniden dener. Belirsiz ağ hatasını tekrar yazmaya dönüştürmez; sürüm ve içerik
uyuşmazlığı kontrollerini de atlamaz.

## Redis ve SQL Olmadan Test Edin

`OrderEntity` için üretilmiş binding'i kullanarak:

```java
var memory = new com.reactor.cachedb.core.testing.InMemoryCacheDatabase();
var orders = OrderEntityCacheBinding.repository(memory);
var accepted = orders.saveWithReceipt(order);

assert orders.findById(accepted.id()).isPresent();
assert !memory.isDurable(accepted);
memory.markDurable(accepted); // Test onayıdır; gerçek SQL commit'i değildir.
assert memory.awaitDurable(accepted, java.time.Duration.ofMillis(25));

var batch = memory.writeBatch("checkout-123").put(order).put(payment).commit();
```

Batch oluşturmadan önce iki entity'nin de binding'ini kaydedin.
Üretilen `@CacheRepository` sınıfları `CacheSession` da kabul eder. Arayüzün adı
`Orders` ise `new OrdersCacheDbImplementation(memory)` ile oluşturabilirsiniz.
Kaynak yolları, kaynak okuyabilen bir session sağlanmadıkça açıkça hata verir.
Aktif küme kapsamı uydurulmaz: kapsam kontrolü yapan yolları test ederken istediğiniz
`RouteCoverage` verisini `putCoverage` ile hazırlayın.

Test kiti dört durumlu okumayı, codec kopyalarını, TTL/policy kontrolünü, kayıt sayısı
sınırını, tahliyeyi, sürümlü okumayı, CAS'i, sınırlı sorguyu, sayfalamayı, projection'ı
ve atomik batch geri alımını destekler. Her test session'ında entity türü başına tek
policy kullanılır. İlişkiler kendiliğinden yüklenmez; desteklenmeyen ilişki planı
açıkça hata verir. `InMemorySnapshotRepository` ile hazır JSON, tipli okuma, bölüm
sayfalaması, sona erme ve açık yenileme fonksiyonu tanımlanabilir.

Otomatik batch kimlikleri yalnızca ilgili test session'ı içinde kullanılır;
snapshot nesil kimlikleri ise test JVM'i içinde benzersizdir. Bu kimlikler için
kriptografik rastgelelik kullanılmaz. Kimlikleri kalıcı olarak saklamayın veya
üretim kimliği olarak kullanmayın. TTL süresi dolmuş bir entity, içeriği çözülmeden
önce elenir. Sıralamada kolonlar her karşılaştırmada yeniden çıkarılmaz; aday
başına bir kez hazırlanır. Test kiti yine bellek içi tarama yapar; üretim indeksi
veya sorgu maliyeti simülatörü değildir.
Yalnızca kayıt sayısını denetleyen kabul kontrolünde ve filtresiz, sıralamasız
sorgularda kullanılmayan kolonlar çıkarılmaz.

Bu testler SQL kısıtlarını, kaynakla eşleşmeyi, Lua doğruluğunu, failover'ı, yeniden
oynatma sırasını, bellek kapasitesini veya üretim yükünü kanıtlamaz. Bunlar için
gerçek Redis ve sağlayıcı entegrasyon testlerini koruyun. Test süresi JVM'e ve
makineye bağlıdır; kısa bir birim testi üretim gecikmesi ölçümü değildir.

### Test Hazırlığı Dâhil Süreleri Ayrı Ölçün

Hedefli testleri normal test JDK'inizle tekrarlamak için:

```powershell
# JAVA_HOME test JDK'inizi göstermeli; Maven PATH içinde bulunmalı.
./tools/ci/check-inmemory-latency.ps1 -Runs 5 -ReportOnly
```

CI, orijinal senaryolar için bu raporlama kipini kullanır: işlevsel başarı zorunlu
kalır ve test metodunun tüm süresi görünür olur. Sürümün katı gecikme hedefi,
ilk kullanım dâhil aşağıdaki Jackson'sız üretilen codec/snapshot senaryosudur.
JUnit metodunun toplam süresini repository çağrısının gecikmesi olarak yorumlamayın.

`-ReportOnly` verilmezse isteğe bağlı sınır, kaydedilen her koşuda **her testin**
50 ms'den kısa sürmesidir.
Sınıf ortalaması alınmaz; yavaş örnekler p95 hesabının arkasında gizlenmez. Komut;
işlevsel hata, eksik veya eski rapor, atlanan test ya da süre aşımı olduğunda
başarısız olur. Koşu kayıtları ve `summary.json`, `target/inmemory-unit-latency/`
altına yazılır. `-ReportOnly`, aşılan süre sınırını raporda başarısız olarak korur
ancak yalnızca bu nedenle komutu durdurmaz; işlevsel hatalar her zaman durdurur.
Bilerek ön ısıtma yapılmaz. Test sırasında gerçekleşen ilk hazırlıklar süreye
dahildir; Maven başlangıcı, JVM sürecinin açılması ve derleme dahil değildir.
Bu kontrol, yeni bir JVM'in ve Jackson tip hazırlığının her makinede toplam
50 ms içinde tamamlanacağı garantisini vermez.

Bu sınır, repository çağrısının değil, test metodunun toplam süresini denetler.
Örneğin `typedRepository(new ObjectMapper(), Summary.class)` çağrısında,
uygulamanın oluşturduğu mapper'ın hazırlığı da ölçülür. Callback'ler, codec'ler
ve ilk assertion çağrıları ayrıca hazırlık maliyeti doğurabilir. Hatanın nedenini
araştırırken bu aşamaları ayrı ölçün; testin toplam sonucunu ise koruyun.
İlk çağrı gecikmesine duyarlı record tabanlı snapshot'larda aşağıdaki üretilmiş
codec yolunu açıkça seçin ve geçişten önce veri eşleşmesini doğrulayın. CacheDB,
bir süre hedefine ulaşmak için verilen mapper'ı veya özel ayarlarını sessizce
değiştirmez.

Byte-limit kontrolleri geçici byte dizisi oluşturmadan UTF-8 uzunluğunu hesaplar.
In-memory test aracı, bu uzunluğu değiştirilemez payload ile birlikte saklar;
kayıt veya yenileme sırasında günceller. Pencere sınırları ve tek büyük kökün
okunmasıyla ilgili mevcut davranış değişmez. Bu uzunluk, yalnızca kodlanmış
payload boyutudur; Redis'in toplam bellek ihtiyacı değildir. Anahtar, indeks ve
bellek ayırıcısının ek maliyetleri ayrıca hesaba katılmalıdır. JDBC hazırlığında
kolon adlarının indeksi sonuç kümesi başına bir kez oluşturulur. Satır değerleri
birbirinden bağımsızdır; decoder bu satırları daha sonra kullanmak üzere tutabilir.

İlk kullanım ile JVM hazır olduktan sonraki işlemleri ayrı incelemek için isteğe
bağlı `inmemory-benchmarks` Maven profili kullanılabilir. JMH bağımlılıkları
kütüphane paketlerine eklenmez:

```powershell
./tools/ci/check-inmemory-jmh.ps1
```

Araç; entity, atomik batch, 100 satırlık sayfalama ve tipli snapshot senaryolarını
sonuç doğrulamalarıyla ölçer. Her çağrıda yeni test verisi oluşturulur. JVM hazırken
yapılan snapshot ölçümü, uygulamadaki gibi aynı mapper'ı kullanır; ilk kullanım
ölçümünde mapper oluşturma ve tip hazırlığı da süreye dahildir. İlk kullanım için
ön ısıtmasız beş ayrı JVM; hazır JVM ölçümü için beş ısınma ve beş ölçüm turuyla
üç ayrı JVM çalıştırılır. JSON sonuçları ve GC çıktıları `target/inmemory-latency/`
altındadır. Varsayılan kontrol, gözlenen bütün örneklerin 50 ms'den kısa olmasını
ister. Açıkça `-Acceptance Warmed` seçilirse yalnızca hazır JVM örnekleri kontrol
edilir; bu seçim ilk kullanım hedefini karşılamış sayılmaz.

JMH desteklenmeyen JVM uyarısı verirse komut başarısız olur; bu sonuçlar kabul
kanıtı değildir. Test edilen OpenJ9 0.43.0 sürümü bu uyarıyı verir.
`-BenchmarkJavaHome <desteklenen-JDK>` ile normal test JDK'ini değiştirmeden ayrı
inceleme yapılabilir. Farklı JVM'deki sonuç, hedef JVM'i doğrulamaz. Hedef JDK için
JUnit raporunu esas alın; hazır JVM'de geçen ölçüm, ilk kullanımda başarısız olan
süre kontrolünü kapatmaz.

`CacheSession.awaitDurable`, tekil ve atomik batch makbuzlarını destekler. Sistem
saatindeki değişikliklerden etkilenmeyen süre ölçümüyle ve 5/10/25 ms beklemelerle kontrol yapar; thread kesilme işaretini
korur. Süre dolunca false döner; kabul edilmiş yazıyı iptal etmez veya yeniden
göndermez. Redis soket zaman aşımını da ayarlayın: bekleme süresi dolması, bloklanmış
bir ağ çağrısını kendiliğinden kesmez.
Bekleme ve süre sınırı testleri, gerçek uyku yerine dahili monoton saat arayüzünü
kullanır. Kullanıcının çağırdığı API ise gerçek monoton süreyi ve aynı bekleme
aralıklarını kullanmaya devam eder.
