# Bileşik Aralık İndeksleri

Bileşik indeks, bir eşitlik öneğini isteğe bağlı sıralama kolonuyla birleştirir.
`sortBy` içermeyen indeksin mevcut eşitlik yerleşimi ve imzası aynen korunur.
Eşitlik öneği, `sortBy` yoksa 2..4 alan içerir. Ayrı bir sıralama alanı tanımlandığında
1..4 alan yeterlidir. Örneğin `columns = {"tenantId"}, sortBy = "createdAt"` ile
ikinci bir eşitlik alanı eklemek zorunda kalmadan tenant bazında tarih sıralı liste hazırlanabilir.

## İndeks Tanımı

```java
@CacheEntity(table = "orders", redisNamespace = "orders",
        indexedColumns = {"tenantId", "status", "priority"})
@CacheCompositeIndex(name = "tenant_status",
        columns = {"tenantId", "status"}, sortBy = "priority")
public record Order(
        @CacheId(column = "order_id") Long id,
        @CacheTenantId @CacheColumn("tenant_id") String tenantId,
        @CacheColumn("status") String status,
        @CacheColumn("priority") Integer priority) {}
```

İşlemci, indeks tanımında Java alan adını ve kalıcı kolon adını çözer. Sıralama alanı kalıcı
olmalı, eşitlik öneğinde bulunmamalı ve mevcut Redis skor dönüşümünü desteklemelidir.
Önek, kimlik ve sıralama alanları otomatik indekslenir. Desteklenmeyen özel codec'ler ile
geçersiz tanımlar derleme hatasıdır.

Depolama tanımı byte/short/int/long/float/double ve sarmalayıcılarını; Instant, LocalDate,
LocalDateTime ve OffsetDateTime türlerini kabul eder. Bir türün depolanabilmesi, double
skorun mantıksal sırasını kayıpsız koruduğu anlamına gelmez.

## Sıradan Sorgularda Tür Doğruluğu

```java
QuerySpec query = QuerySpec.allOf(
        QueryFilter.eq("tenant_id", tenantId),
        QueryFilter.eq("status", "OPEN"),
        QueryFilter.gte("priority", 10),
        QueryFilter.lt("priority", 100))
    .orderBy(QuerySort.asc("priority"))
    .limitTo(50);
List<Order> orders = repository.query(query);
```

Uygun tek skor sıralamalı veya sırasız, yalnızca Redis kullanan sorgular sınırlı sıralı pencereyi
kullanır. Sadece skora göre sıralanan sıradan sorguda eşit skorların kimlik sırası tanımlı
değildir; kararlı sayfalama sözleşmesi oluşmaz. Açıkça eklenen kimlik sıralaması, Java'nın
mantıksal tür karşılaştırıcısını korur. Tipli keyset OR dallarında yalnızca bütün dalların ortak
eşitlik koşulları aday seçimi için kullanılır; kalan koşullar türlerini korur.

2^53 üzerindeki Long değerleri, birbirine çok yakın ondalık sayılar, işaretli kayan nokta sıfırı,
milisaniyeden hassas zamanlar ve saat dilimi ofsetli tarihler Redis double skoruyla güvenle
karşılaştırılamaz. Byte, short, int ve LocalDate doğrudan uygundur. Sıradan Long skor penceresi
sorguları, aşağıdaki ayrı ad alanı/indeks Long53 doğrulamasıyla uygun hâle gelebilir. Diğer desteklenen depolama türlerinde eşitlik öneğinin bütün adayları alınır;
payload koşulları ve mantıksal sıralama tam tür değerleriyle uygulanır. Yuvarlanmış skora
dayanan dışlayıcı aralık sınırı kullanılmaz. BigDecimal, `sortBy` depolama türü değildir.

## Long Milisaniye Zaman Çizelgeleri

Bir Long sıralama kolonu, işbirlikli ve çitlenmiş yeniden oluşturma bütün canlı yerleşik değerlerin
[-9007199254740991, 9007199254740991] aralığında olduğunu doğruladıktan sonra sıradan tek skorlu
sorguları ve açıkça etkinleştirilen keyset pencerelerini hızlandırabilir. Bu sözleşme gerçekçi createdAtEpochMillis zaman çizelgelerini
kapsar; Java Long türünün tamamını güvenli ilan etmez. Değerler Long olmalıdır; Double/BigDecimal
dönüşümü doğrulama sayılmaz. Nanosaniye hassasiyetli Instant bu sözleşmeyle hızlandırılmaz.

~~~java
QuerySpec latest = QuerySpec.allOf(
        QueryFilter.eq("tenant_id", tenantId),
        QueryFilter.eq("status", "OPEN"),
        QueryFilter.gt("created_at_epoch_millis", afterEpochMillis))
    .orderBy(QuerySort.desc("created_at_epoch_millis"))
    .limitTo(50);
~~~

Doğrulamayı etkinleştirmeden önce sıcak yükleme, kaynaktan okuma ve önbelleğe ekleme yolları dâhil
bütün yazıcılar değer alanını doğrulayan protokolü uygulamalıdır. Yalnızca geçiş kilidine katılan
eski yazıcılar yeterli değildir. Değerler, yazıcı kayıtlıyken payload değişikliğinden önce denetlenir.
İlk güvensiz Long, indeks başına tek kalıcı bayrağı güvensiz yapar; iş yazması bu yüzden reddedilmez.
Bayrağı değiştirme ve yazıcı kaydını kaldırma aynı mevcut atomik Lua çağrısında gerçekleşir.
Hızlı okuyucu; doğrulanmış bayrağı, kilidi, etkin yazıcıları, bozulma durumunu, imzayı ve indeks
kapsamını AYNI aralık Lua çağrısında denetler. Ek doğrulama ağ turu, kimlik başına kayıt veya
süresi dolan bir güvenlik kanıtı oluşturulmaz.

Eksik, güvensiz veya geçersiz bayrakta kısmi hızlı sonuçlar atılır; mevcut tam geri dönüş sınırları
içinde eşitlik öneğinin bütün adayları ve özgün mantıksal koşul/sıralama kullanılır.
Güvenli yazma veya güvensiz satırın silinmesi uygunluğu geri getirmez. Yalnızca diğer yazıcıların
engellendiği başarılı yeniden oluşturma, kalan bütün canlı değerler kayıpsızsa doğrulanmış bayrak
yayımlayabilir. Atomik yazma grupları payload değişmeden indeksleri bozulmuş işaretlemeyi sürdürür
ve çitlenmiş yeniden oluşturma gerektirir. Etkin tanım en fazla 32 bayrak kabul eder; her tanımlı
Long bileşiği için bir bayrak tutulur, kimlik başına bayrak tutulmaz. Eski indeks anahtarları
çitlenmiş yeniden oluşturma sırasında temizlenir. Kanıtlara TTL/tahliye
uygulamayın ve onları elle doğrulanmış duruma getirmeyin.

Long sözcüksel pencereleri, tam Long değerini ve Redis üyesini temsil eden String kimliği taşıyan
`4-redis-long-lex-id` sürümünü kullanır. Devam konumu yuvarlanmış Double'dan üretilmez.
Mevcut Long53 bayrağı doğrulanmışken aralık ve keyset okumaları aynı atomik korumalı sıralı indeks
penceresini kullanır. Bayrak güvensiz, eksik veya geçersizse sayfanın TAMAMI sınırlı ve eksiksiz
aday yüklemeye geçer; sıra `Long.compare` ve kimliğin işaretsiz UTF-8 baytlarıyla belirlenir.
Bu geri dönüş Long.MIN_VALUE/MAX_VALUE ve Redis Double'ında aynı skora düşen komşu değerler
dâhil bütün Long alanını destekler. Açık devam değeri güvenli alanın dışındaysa, daha sonra güvenli
yeniden oluşturma yapılsa bile tam değerli geri dönüş kullanılır.
Aralık koşulları tam Long parametreleri ister; Integer, Double veya BigDecimal yerine kullanılamaz.
Yeni doğrulama bayrağı, yazıcı kaydı, yükseltme protokolü veya ek ağ turu oluşturulmaz.

Örnekteki zaman çizelgesi biçimi için Long alanına sıralı bileşik indeks tanımlayın ve açıkça etkinleştirin:

```java
@CacheCompositeIndex(name = "customer_status_date",
        columns = {"customerId", "status"}, sortBy = "orderDate")
// OrderEntity: Long orderId, Long customerId, String status, Long orderDate
// @CacheColumn("order_date") Long orderDate, iş alanındaki milisaniye değeridir.

@HotRoute(value = "timeline", coverageScopeParameter = "customerId")
@CacheRouteQuery(redisLexicalIdTie = true, redisCompositeIndex = "customer_status_date",
    predicates = {
        @CachePredicate(field = "customerId"),
        @CachePredicate(field = "status", constants = "OPEN"),
        @CachePredicate(field = "orderDate", parameter = "minimum",
                operator = CachePredicate.Operator.GTE)
    },
    orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC))
HotWindow<OrderEntity> timeline(Long customerId, Long minimum, WindowRequest window);
```

İndeks anotasyonu OrderEntity'ye, rota ise repository arayüzüne eklenir. Java alan adları
`order_date` gibi kalıcı kolon adlarına çözümlenir. Mevcut örnek rotalar bu sözleşmeyi kendiliğinden
kazanmaz. İlk/sonraki WindowRequest çağrılarında AYNI tipli koşullar ve kapsam kullanılmalıdır.
Üretilen Long rotaları, tipli Java kimliğiyle yeniden dilimleme yerine tam Long native yeteneğini
çağırır. İsteğe bağlı ham devam parametreleri nullable Long ve String kimliktir; her iki alan
LT/DESC veya GT/ASC kullanır. Birlikte verilmeleri gerekir; opak imleçle birleştirilemezler.
Sürüm 3 Double tabanlı kalır ve Long rotalarını reddeder; eski tipli imleçlerin anlamı değişmez.

## Açık Redis İmleç Sözleşmesi

Redis sözcüksel sayfalaması ayrıca etkinleştirilir; mevcut tipli imleçlerin anlamını değiştirmez.

```java
@CacheRepository(entity = Order.class)
public interface Orders extends CacheDbRepository<Order, Long> {
    @HotRoute(value = "open-priorities", coverageScopeParameter = "tenantId")
    @CacheRouteQuery(
        redisLexicalIdTie = true, redisCompositeIndex = "tenant_status",
        predicates = {
            @CachePredicate(field = "tenantId"),
            @CachePredicate(field = "status", constants = "OPEN")
        },
        orderBy = @CacheOrder(field = "priority", direction = CacheOrder.Direction.ASC))
    HotWindow<Order> openPriorities(String tenantId, WindowRequest window);
}
```

İlk istek `WindowRequest.first(50)` ile yapılır. Devamında `result.nextCursor()` değeri
`WindowRequest.after(token, 50)` metoduna verilir. `HotWindow` kapsama kanıtını korur.
Eksik veya güncelliğini yitirmiş kapsamın başarı gibi görünmemesi için `completeItems()` kullanın.

Sıra önce skora, ardından kimliğin işaretsiz UTF-8 baytlarına göredir. Sayısal kimlikler bile
Redis'te metin üyedir: ASC eşitlik sırası `1,10,2` olur, sayısal sıra değildir.
DESC hem skoru hem kimlik sırasını tersine çevirir. Bu rota için adlandırılmış sıralı bileşik
indeks, bütün önek kolonlarında EQ, desteklenen indeksli koşullar ve byte/short/int/LocalDate
skoru (v3) veya tam Long skoru (v4) gerekir. Desteklenmeyen tipli işleç, indekslenmemiş koşul, kaynak rotası veya
projection rotası açık derleme hatası üretir.

V3 için isteğe bağlı ham `@CacheSeek` parametreleri skor/kimlik sırasında nullable Double ve String
olmalıdır; ASC için GT, DESC için LT kullanılır. Ham değerler opak imleçle birleştirilemez.
Tipli bir sayısal kimlik parametresi Redis'in metin üyesiyle birbirinin yerine kullanılamaz.

`3-redis-score-lex-id` ve `4-redis-long-lex-id` sürümleri rota, kapsam, etkin kiracı,
ad alanı/anahtar öneği, indeks imzası, koşullar ve yönü bağlar. V4 ayrıca tam Long sırasını bağlar.
Tipli v2, sözcüksel v3 ve Long v4 birbirini reddeder; değişen sözleşmede ilk sayfadan başlanır.
Hiçbir mevcut imleç biçimi yeniden yazılmaz. V4 tekrarlanan alanları, yanlış skor/kimlik türlerini
ve bozuk UTF-8'i reddeder. Token en çok 8192 karakter, kimlik en çok 4096 UTF-8 baytıdır.
Korunan alanlardaki (`@CacheEncrypted(blindIndex = true)`) eşitlik değerleri, sözleşme parmak izi hesaplanmadan ÖNCE yapılandırılmış anahtarlı
kör indeks dönüşümünden geçirilir; ham korunan değerler özetlenmez veya token'a eklenmez.
Bu anahtarlı değer koruması token imzası DEĞİLDİR. Token yetkilendirme belgesi değildir;
kiracı erişimi ve kayıtlı sahiplik ayrıca denetlenir.

## Desteklenen Sıralama Matrisi

| Sıralama alanı | Sıradan mantıksal sorgu | İndeksli sözcüksel keyset/aralık | Sözcüksel hızlı yol uygun değilse |
| --- | --- | --- | --- |
| byte / short / int / LocalDate | Tam mantıksal sıra | V3, sağlıklı, çitlenmiş ve eksiksiz indeks | Sınırlı ve eksiksiz kayıpsız aday yükleme |
| Long milisaniye veya başka Long | Tam Long sırası | V4, aynı doğrulanmış Long53 bayrağı ve güvenli devam değeri | Bütün Long alanında sınırlı, eksiksiz Long/UTF-8-kimlik sırası |
| float / double | İşaretli sıfır dâhil mantıksal karşılaştırıcı | Açık sözcüksel rotalarda desteklenmez | Açık derleme hatası; tipli mantıksal sorgu/keyset kullanılır |
| Instant / LocalDateTime / OffsetDateTime | Hassasiyet/ofset anlamını koruyan mantıksal karşılaştırıcı | Değerler milisaniyeye denk gelse bile açık sözcüksel rotalarda desteklenmez | Açık derleme hatası; tipli mantıksal sorgu/keyset kullanılır |
| BigDecimal | Diğer koşullar uygunsa tipli mantıksal sorgu | Sıralı bileşik indeks tanımında desteklenmez | Açık tanım/rota hatası |
| null sıralama değeri | Mevcut mantıksal null davranışı | Eksiksiz sıralı kapsam veya sözcüksel devam konumu yoktur | Açık imleç hatası; kısmi sayfa dönülmez |

Matris, Long alanının adından zaman birimi çıkartmaz. Milisaniyeler, zaman damgaları özel olarak
yuvarlandığı veya kırpıldığı için değil, sayısal değerleri Long53 alanına uyduğu için desteklenir.

Devam konumu son incelenen satır veya canlı sıra numarası değil, son DÖNDÜRÜLEN (skor, kimlik)
çiftidir. İmlecin dayandığı kayıt silinse de devam edilir. İmleçten sonra eklenenler görünebilir;
öncesine eklenenlere geri dönülmez. Sayfayla yarışan yeni kayıt, gelen imleçten sonra olsa bile
görünmesi garanti değildir; dönen yeni imlecin gerisinde kalmış olabilir. Sayfa sonu abonelik
değildir; sonraki eklemeler geriye dönük devam token'ı oluşturmaz. Güncelleme, süre dolumu ve
silme sonraki sayfaları değiştirebilir.
Bu bir snapshot değildir; sıralama konumu değişen kayıtlarda boşluksuz veya tekrarsız gezinme
garantisi vermez.

## Sınırlar ve Geri Dönüş

Hızlı yol VERIFIED durumunu, sağlıklı ve tam skor kapsamını, uyumlu sorguyu gerektirir.
Her tek slotlu pencere Lua çağrısı; bakım kilidi/etkin yazıcı yokluğunu, bozulmamış durumu,
beklenen imzayı ve SET/ZSET eleman sayılarını pencere okumasıyla atomik denetler. Bu denetim,
sonraki payload okumalarını veya diğer turları kapsayan atomik snapshot oluşturmaz.
İndeks okumasından sonra payload koşulları, tombstone, kiracı sahipliği ve sıcak veri politikası
denetlenir. Sınırlar toplam dört tur, 10000 incelenen kimlik ve 16 MiB kimlik/payload verisidir.
Aralık Lua yanıtı en çok 2500 kimlik ve en çok 64 eşitlik sınırı araması içerir. Payload,
128 kimliklik alt gruplarla ve en çok 384 anahtarla okunur. Redis bağlantı/soket zaman aşımları
geçerlidir. Sorgu süresi sınırlı adımlar arasında ve sonuç dönmeden denetlenir.

Bütçesi dolan veya uygun olmayan hızlı çalışmanın kısmi sonucu atılır. Geri dönüş, eşitlik
öneğinin bütün adaylarını veya bozulmuş indekste izin verilen sınırlı payload taramasını tamamlar.
10000/yapılandırılmış aday ve 16 MiB sınırı korunur; kapasite aşımında sessiz kırpma yerine hata
verilir. Bu sınırlı aday yüklemesidir; O(log N + sayfa boyutu) garantisi değildir.
Katı rotalar tam taramayı yine yasaklar. Redis veya codec hatası sessiz kaynak tekrarına
dönüştürülmez.

İndekste bulunup payload'ı kaybolmuş kayıtlarda tutarsızlık denetimi ve yapılandırılmış kaynaktan
okuma davranışı korunur. Sözcüksel kaynak geri dönüşünde önce sınırlı bütün veri alınmalı, sonra
Redis sırasına göre sıralanmalıdır; tamlık kanıtlanmadan kaynak kapasitesine ulaşılırsa hata verilir.
Tombstone ve belirlenmiş yabancı sahiplik dışlanır. SQL sırasının Redis sırasıyla aynı olduğu
varsayılmaz. Fetch plan ilişkileri toplu yüklenir. Redis kabul politikası kaynak görünürlüğünü
değil, önbellekte tutulacak satırları yönetir.

Null sıralama değeri eşitlik üyeliğini korur fakat skora katılmaz; tam skor penceresini engeller.
Açık imleç null veya sonlu olmayan skoru sıralayamaz. Sıradan sorgunun mantıksal null davranışı
korunur. Bu açıkça etkinleştirilen özellikte bileşik aday yükleme ve imleçler, gerekli bir satırın yokluğunu
doğrulayamadığından sorgulanan ad alanının minimum sürüm gereksinimlerini reddeder. Yalnızca başka
bir ad alanındaki gereksinim bu sorguyu reddettirmez. Daha zayıf SQL yerine sürümü doğrulanan tekil
kayıt/findAll okumalarını kullanın; bu açık özellik kısıtı, nedensel sıralı sorgu desteği değildir.
SET/ZSET eleman sayılarının eşitliği, üyelik veya skor doğruluğunun tek başına kanıtı değildir.

## Yazma ve Yeniden Oluşturma

SET mevcut önek kodlamasını korur; ZSET anahtarı sonuna `:sort` eklenir.
Yalnızca sıralı tanımda imza `name=prefix1,prefix2>sortColumn` içerir.
`$cachedb:cidx-sort:<name>` metadatası, son ayıraçtan ayrılan `<zsetKey>|<score>` değeridir.
4096 metadata alanı, 32 sıralama işareti, 128 karakterlik güvenli ad, işaret başına 8192 karakter
ve sonlu skor sınırları korunur. Önek/skor değişikliği, silme ve yeniden oluşturma eski ZSET
üyelerini kaldırır. Eksik metadata indeksi bozulmuş sayar. Kayıt yazımı ardından Java ile
indeks güncellenmesi, kayıt ve indeks için atomik işlem garantisi değildir.

Yeni ad alanları dâhil, sıralı indeks açık ve işbirlikçi çitle yeniden oluşturulur:
`RedisEntityRepository.rebuildCompositeIndexesFenced(false, "schema-change")`.
Sıradan otomatik yeniden oluşturma sıralı indeksi VERIFIED durumuna yükseltmez. Bütün yazıcıları
işbirlikçi protokole yükseltin; uyumsuz/doğrudan yazıcıları durdurup bakım aralığı planlayın.
Repository üzerinden çağrılan Redis Functions dış yazıcı kapsamına katılır. Desteklenmeyen
atomik batch yolları, degraded işaretiyle doğrulanmış imzanın yayımlanmasını geçersiz kılar.

Çit, kayıtlı yazıcı varken bakımın başlamasını ve bakım sırasında yeni işbirlikçi yazıcıları
engeller. Sadece recency ZADD veya yardımcı metadata güncellemesi yazıcı kaydı/token üretmez.
Gerçek kayıt ekleme, payload/indeks değişikliği ve eviction çit kullanır; gerçekten kayıt atan
okuma yalnızca yıkıcı adımı için çite girer. Doğrulanmış imza ancak kilit, yazıcı kaydı ve bozulma
işareti geçerliyken yayımlanır.
Çit anahtarları süre dolunca güvenliği bırakmaz: çöken veya yanıtı kaybolan yazıcı/bakım yürütücüsü
bütün yazıcılar durdurularak çevrimdışı kurtarma gerektirebilir. Zamanlayıcıyla zorla kilit açmayın
ve engelleyici kayıtları silmeyin. Sonraki başarısız yazım indeksi yeniden bozulmuş duruma getirir.

## Kalıcı Yazıcı Sınırı ve Kurtarma

Yazıcı HASH'i, canlı yazıcılarla sonucu bilinmeyen/yetim kayıtların toplamında en çok 10000
girdi tutar. Atomik kayıt betiği, sınır doluyken yeni yazıcıyı mutasyon gövdesinden önce reddeder.
Kayıt bırakma veya silme yanıtının kaybolması kalıcı engel bırakabilir; tek yazıcı girdisi bile
bakımı engeller. TTL, kayıt yaşına göre otomatik silme veya zorla kilit açma güvenli değildir.

Çevrimdışı kurtarma için önce bütün yazıcıları durdurup yalıtın ve uçuş hâlindeki komutları
tamamlayın. Bakım kilidi ile en çok 10000 yazıcı girdisinin sınırlı inceleme kanıtını saklayın.
Bir operatör kalıcı kilidi/yazıcı kaydını temizlemeden ÖNCE ad alanını bozulmuş işaretleyin ve
imzasını geçersiz kılın. Sonra tek bir açık çitli yeniden oluşturma çalıştırıp imza yayımını
doğruladıktan sonra trafiği açın. Başarısız kurtarma bozulmuş durumda kalır. UUID yazıcı token'ı
sürecin durduğunu kanıtlamaz; yakın zamanda etkinlik görülmemesi yeterli değildir.

Sınırlı geri dönüşleri, bozulmuş durumu, yeniden oluşturma hatalarını, süre ve kapasite aşımlarını
izleyin. Yayına almadan önce gerçekçi eşzamanlılıkta etkin yazma/erişim maliyetini ve sorgu p99'unu ölçün.
