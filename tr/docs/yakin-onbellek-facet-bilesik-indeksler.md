# Yakın Önbellek, Gruplanmış Toplamlar ve Bileşik İndeksler

[English](../../docs/near-cache-facets-indexes.md)

Bu özellikler, açıkça seçilmiş ve sınırlandırılmış aktif veriyi Redis üzerinden
okumayı kolaylaştırır. Her SQL sorgusunu kendiliğinden önbelleğe almaz; veritabanının
tüm geçmişini toplam hesaplarına katmaz. Kütüphane Java 17 uyumluluğunu korur;
derleme ortamı ve örnek uygulamalar Java 21 kullanır.

## Ürün Stoğu: Tekrarlanan Okumalar

```java
import com.reactor.cachedb.annotations.*;

@CacheEntity(table = "products", indexedColumns = {"tenant_id", "status", "stock"})
@CacheCompositeIndex(columns = {"tenant_id", "status"})
@CacheNearPolicy(maxEntries = 2048, ttlSeconds = 10,
        softTtlSeconds = 2, maxBytes = 4 * 1024 * 1024)
public record Product(
        @CacheId(column = "id") Long id,
        @CacheTenantId @CacheColumn("tenant_id") String tenant,
        @CacheColumn("status") String status,
        @CacheColumn("stock") int stock) {}
```

Aktif veri kümesini Redis'e önceden yükledikten sonra `findAll(ids)`, önce JVM
içindeki L1 önbelleğine bakar. Yalnızca bulunamayan benzersiz ID'leri Redis'ten
ister. Sonuçta giriş sırası ve tekrarlanan ID'ler korunur; null ID'ler yok sayılır.
Değiştirilebilir nesneler farklı çağrılar arasında paylaşılmaz. Tamamen değişmez
olduğu generated metadata ile bilinen record nesneleri L1'de yeniden kullanılabilir.

Soft TTL isteğe bağlıdır; varsayılanı sıfırdır. Soft TTL dolmuş, hard TTL henüz
dolmamışsa okuma mevcut değeri bekletmeden döndürebilir. Arka planda yalnızca
Redis'ten yenileme yapılır. Ortak executor en fazla iki worker ve 256 kuyruk öğesi
barındırır. Aynı kayıt için yenilemeler tekilleştirilir. Kuyruk dolduğunda okuma
bekletilmez ve ayrılan rezervasyon serbest bırakılır.

Hard TTL, Redis'te kalan süre, bağlantı koptuğunda geçersizleştirme ve bellek sınırları
geçerliliğini korur. `rejectedRefreshes()` ve `failedRefreshes()` kuyruk baskısını
ve yenileme hatalarını gösterir. Bu davranış eventual consistency sağlar;
stok rezervasyonu veya ödeme işlemi için güçlü tutarlılık garantisi değildir.
Redis komutları ve havuzdan bağlantı bekleme süresi için sonlu zaman aşımı kullanın.
Yenilemeler mevcut Redis havuzunu paylaşır; en fazla iki arka plan worker'ı ve
geçersizleştirme aboneliği için kapasite ayırın. Sınırlı kuyruk, sonsuza kadar
bekleyen bir bağlantıyı kendiliğinden düzeltmez.

Dört bitlik örneklemeli sıklık sayacı, sık okunan kayıtlara kısa ve sınırlı bir
ikinci şans tanır. Böylece tek seferlik taramalar sık kullanılan kayıtları daha az
yerinden eder. Her segmentte en fazla 4096 long ayrılır. Bu alan kayıt rezervasyonuna
ektir; toplam kayıt ve store sayısı da çalışma zamanı sınırlarına tabidir.

## Destek Paneli: Duruma Göre Toplamlar

```java
import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import java.util.Map;

@CacheRepository(entity = Product.class)
public interface Products extends CacheDbRepository<Product, Long> {
    @CacheFacet(groupBy = "status")
    Map<String, Long> totals();

    @CacheFacet(groupBy = "status", function = AggregateFunction.SUM, field = "stock")
    Map<String, AggregateResult> stockByTenant(String tenant);
}
```

Bu metotlar derleme sırasında doğrudan repository çağrılarına dönüştürülür;
reflection veya runtime dynamic proxy kullanılmaz. Filtreler `ByFieldAndField`
adlandırmasındaki parametrelerden çıkarılır. Boolean alanlar için `True` ve `False`
son ekleri desteklenir. Filtresiz metotlar parametresiz tanımlanır.

Gruplama alanı indeksli olmalıdır. En fazla 256 farklı, null olmayan grup üretilir.
Aday kayıt sayısı sınırlandırılır; işlenen veri için 16 MiB bütçe uygulanır.
Gruplar anahtara göre kararlı sırada döner. Sınır aşıldığında toplam sessizce
kısaltılmaz; işlem hata verir.

`aggregateBy` her grup için kayıt sayısını, toplamı, en küçük ve en büyük değeri ve
ortalamayı döndürür. `decimalAverage()`, DECIMAL128 hassasiyetini korur. Mevcut
`average()` metodu OptionalDouble döndürmeye devam eder. Generated metotlar
`Map<String, Long>`, `Map<String, BigDecimal>` veya `Map<String, AggregateResult>`
döndürebilir. Long dönüşümü kesirli veya taşan değeri sessizce değiştirmez; hata verir.

Uygun indeksli tekil toplamlar, en fazla 512 aday için tek bir salt okunur Lua
çağrısı kullanır. Sayısal hesap Java'da yapılır; parasal değerler Lua'nın kayan
nokta aritmetiğine aktarılmaz. Süresi dolmuş veya silinmiş kayıtlar dışarıda bırakılır.
Cluster anahtarları aynı slotta değilse sınırlı pipeline yoluna dönülür. Adayların
bulunması ve yardımcı işlemler ayrıca çağrı yapabilir; bu bir HTTP isteğinin
tamamının tek çağrıda biteceği anlamına gelmez.

## Çok Kiracılı Kullanım

```java
import com.reactor.cachedb.core.tenant.CacheTenantContext;

try (var scope = CacheTenantContext.withTenant(authenticatedTenant)) {
    var counts = products.totals();
    var receipt = session.inTransaction(tx -> {
        tx.put(firstProduct);
        tx.put(secondProduct);
    });
}
```

Tenant kimliği, repository, session ve iş nesneleri uygulama tarafından sağlanır.
İstek başlığındaki tenant değerini doğrulamadan yetki bilgisi olarak kullanmayın.
Transaction içindeki komutların gerçek kabul makbuzu commit sonunda üretilir.
Sahiplik, ilk değişiklikten önce ve Redis'teki atomik script içinde tekrar denetlenir.

Silme işleminde mevcut bir sahiplik işareti gerekir. Yeni kayıt için ilk sahiplik
kurulumu, SQL tarafındaki sahipliği eksiksiz bildiren güvenilir bir loader ister.
Bilinmeyen kayıt kendiliğinden güvenli kabul edilmez. Tenant batch'i açık tenant
kapsamı ister; genel yönetici kapsamı bunun yerine geçmez. Kapsam değiştirildikten
sonra batch başka tenant adına kullanılamaz. Cluster batch anahtarları aynı slotta olmalıdır.

JVM içindeki sahiplik notu eski kalsa bile Redis'teki atomik denetim geçerlidir.
Bu notlar repository başına en fazla 16.384 kayıt ve 4 MiB mantıksal rezervasyonla
sınırlıdır. Redis sahiplik işaretleri kalıcıdır; aynı ID'nin tenant'ı değiştirilemez.

Tenant partition sorgularında filtreleme, her bölümün ilk kayıtları seçilmeden önce
yapılır. Tenant projection sorguları, sayfalama öncesinde ana kaydın sahipliğini
denetler ve ortak projection sorgu önbelleğini kullanmaz. Tenant alanını cevapta
göstermek zorunlu değildir. İç tenant indeksi, diğer tenant'ların küresel üyeliklerini
taramayı önler; indeks yeniden kurulurken bu üyelikler de güncellenir.
Bu uyumluluk yolları en fazla 10.000 aday ve 16 MiB
işleme bütçesiyle çalışır. Daha büyük ekranlarda tenant indeksli ayrı bir read model
tanımlayın. Her partition için ayrı SQL çağrısı yapılmaz; SQL geçmişi yüklenmez.
Tenant projection sunulmadan önce ana kayıtların sahiplik işaretleri kurulmuş olmalıdır.

## Farklı Pod'da Kendi Yazdığını Okumak

```java
import com.reactor.cachedb.core.causal.CacheCausalContext;

String token;
try (var scope = CacheCausalContext.withReceipt(receipt.redisNamespace(), receipt)) {
    token = CacheCausalContext.encodeToken();
}
// Sonraki istekte, kimlik doğrulama ve tenant kapsamı kurulduktan sonra:
try (var scope = CacheCausalContext.withToken(token)) {
    var product = entityRepository.findById(receipt.id());
}
```

L1'deki sürüm eski veya bilinmiyorsa Redis'ten yeniden okunur. Okunan sürüm istenen
alt sınıra ulaşmıyorsa eski veri döndürülmez; işlem hata verir. Token en fazla
128 kimlik ve 4096 karakter taşır. İmzalı değildir; yetki belgesi, SQL'e kalıcı
yazılma kanıtı veya projection güncellik belgesi değildir. HTTP başlığına sınır
koyun ve çağıranı ayrıca doğrulayın. Kapsamlar ters sırayla kapatılır; executor
thread'lerine kendiliğinden taşınmaz.

## Bileşik İndeks ve JDBC Geçişi

Bileşik indeks, 2–4 farklı kalıcı alan içerir. Alanlar generated indeks tanımına
eklenir; entity başına en fazla 32 bileşik indeks bulunabilir. Değerler uzunluk
önekli URL encoding ile kodlanır. Herhangi bir bileşen null ise kayıt o bileşik
indekse eklenmez. Kodlanmış tuple en fazla 4096 karakterdir.

Binding'leri yeniden üretin, tüm yazıcıları güncelleyin, ardından mevcut verinin
indekslerini yeniden kurun. Güncellemede eski üyelik kaldırılır ve yeni üyelik
eklenir. Temizleme ve yeniden kurma işlemleri bileşik kümeleri de kapsar. Bileşik
indeksler mevcut indeks bakım ve degraded-index recovery sözleşmesini izler;
çok anahtarlı sorguyu transaction snapshot'ına dönüştürmez. Atomik batch'ler,
okumalardan önce indekslerin yeniden hazırlanmasını gerektiren işareti bırakır.

JDBC işlemlerinin bağımsız anahtarlar arasında gruplanması varsayılan olarak kapalıdır.
FK, unique constraint, trigger ve iş sırası kuralları uygunsa entity üzerinde
`independentWriteReordering = true` seçebilirsiniz. Aynı kayda ait UPSERT/PATCH/DELETE
sırası bu modda da korunur. Farklı satırların veya tabloların transaction bağımlılıklarını
gizlemek için bu ayarı açmayın.
