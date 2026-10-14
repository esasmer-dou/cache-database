# Snapshot Kaynakları ve İlişkiler

CacheDB 0.12.0 veya üzerini kullanın. Bu API, sınırları belli bir okuma işini
tanımlar. Kaynaklar aynı tutarlı transaction içinde okunur.
Bu yapı lazy ORM, CDC veya her sorguyu otomatik önbelleğe alan bir katman değildir.
Önce [katalog yenileme rehberini](snapshot-projectionlar.md) okuyun.

## 1. Kaynağı ve Kapsamı Tanımlayın

Mevcut @CacheEntity için derleyici EntityCacheBinding.SOURCE üretir.
Bu alan tablo eşlemesini ve kolon dönüşümünü birleştirir. Spring bağımlılığı getirmez.
Yalnızca kaynak olarak kullanılan entity için CRUD kaydı veya ayrı entity önbelleği gerekmez.

```java
var orders = SnapshotSource.entity("orders", OrderEntityCacheBinding.SOURCE)
        .where(OrderEntityFields.status, "OPEN");
var items = SnapshotRelation.strings("items", "order_items", "order_id", "product_id")
        .where(SnapshotPredicate.in("order_id", orders.select(OrderEntityFields.id)));
var sources = SnapshotPlan.inputs(orders, items);
```

Örnekteki entity ve kolonları kendi şemanıza göre değiştirin.
Filtre değeri SQL'e parametre olarak bağlanır. Alt sorgu veritabanında çalışır.
Kimlikler Java'ya çekilip uzun bir liste olarak SQL'e geri gönderilmez.
Her kaynak aynı tutarlı bağlantıda bir kez sorgulanır. JDBC fetch işlemleri
nedeniyle ağ turu sayısı sorgu sayısından fazla olabilir.

| Tanım | Anlamı | Yanlış kullanım |
|---|---|---|
| Kaynak adı | Plan içindeki benzersiz ad | Tekrar eden ad reddedilir |
| SOURCE | Tablo, kolonlar ve dönüşüm | Yanlış eşleme yanlış veri getirir |
| where | Okunacak kayıt kapsamı | Dar kapsam eksik cevap üretir |
| select | Alt sorguda seçilen tek kolon | Tanımsız kolon reddedilir |
| inputs | Mapping içinde kullanılacak kaynaklar | Eksik kaynak okunamaz |
| withoutPerSourceLimit | Tek kaynağın satır sınırını kaldırır | Toplam bütçeler devam eder |

Ardışık where çağrıları AND ile birleşir. Alternatif koşullar için or kullanın.
eq(field, null), IS NULL anlamına gelir. 0.13 ile ne, gt, gte, lt, lte, between,
isNull, isNotNull ve inValues koşulları da kullanılabilir. Metin kolon adı veya
üretilmiş CacheField kullanıldığında değerler SQL'e parametre olarak bağlanır.
Boş inValues listesi reddedilir. Oracle listeleri 900 değerlik parçalara böler.
SQL Server'ın toplam 2.100 parametre sınırı çalıştırmadan önce denetlenir.
Sistem serbest iş ifadelerini SQL'e çevirmeye çalışmaz.
Kolonlar eşlemeye karşı doğrulanır. Üretilmiş alanlar yazım ve tip kontrolüne
yardım eder; tablolar arasındaki ilişkinin iş açısından doğruluğunu kanıtlamaz.
Kaynak kapsamını ve indeksleri kontrol edin.
Tablo, kolon ve filtre kapsamı güvenilir uygulama tanımı olarak kalmalıdır.
Değiştirilebilir nesneler ve tanımsız kolonlar erken hata verir.
Sağlayıcıya özel SQL gerekiyorsa açık ve sabit bir SELECT kaynağı kullanın.
Bu ham kaynağa yeni where API'si eklenemez.

## 2. Entity Kimliği Olmadan Record Okuyun

Aşağıdaki public record'u, paketi tanımlı ayrı bir Java dosyasına yazın:

```java
@CacheSourceRecord(table = "order_items")
public record OrderItemRow(
        @CacheColumn("order_id") String orderId,
        @CacheColumn("product_id") String productId,
        Integer quantity) {}
```

Annotation'lar com.reactor.cachedb.annotations paketindedir.
Normal annotation processor aynı derlemede OrderItemRowSourceBinding.SOURCE
ve OrderItemRowFields sınıflarını üretir. Üretilmiş dosyaları elle değiştirmeyin.
SnapshotSource.entity("items", OrderItemRowSourceBinding.SOURCE) ile kullanın.
Factory adı ortaktır; record yazılabilir entity'ye dönüşmez.

Desteklenen alanlar: String, UUID, int/Integer, long/Long, double/Double,
boolean/Boolean, BigDecimal, LocalDate, LocalDateTime ve Instant.
0.13 ile OffsetDateTime, LocalTime, BigInteger, float/Float, short/Short,
byte/Byte ve Java enum tipleri de desteklenir. Enum dönüşümü derleyicinin ürettiği
State::valueOf çağrısıyla yapılır; reflection kullanılmaz.
CacheColumn yoksa alan adı gerçek kolon adı olarak kullanılır.
İç içe veya generic record, desteklenmeyen tip, güvensiz ad ve tekrar eden kolon
derleme hatasıdır. Dönüşüm reflection değil, doğrudan constructor çağrısı kullanır.
Eksik kolon hatadır. Boxed tipte null korunur. Hiçbir primitive alan null olamaz;
0.13 ile boolean da bu kurala uyar. Tam sayı taşması ve 0/1 dışındaki sayısal
boolean değerler reddedilir. Yokluk sıfırdan farklıysa boxed tip seçin.
Float taşması ve sıfıra yuvarlanarak kaybolan değer reddedilir; olağan kayan noktalı
yuvarlama devam eder. Timestamp/Instant, OffsetDateTime'a UTC ile çevrilir.
Saat dilimi olmayan bir kolondan özgün offset bilgisi çıkarılamaz.
LocalTime, JDBC Time kabul eder. Oracle'da bağımsız TIME kolonu yerine kullandığınız
saklama biçimini açık bir skaler SELECT ile uyarlayın.

## 3. İlişkileri Bir Kez Hazırlayın

```java
var productsByOrder = rows.lists(items);
var ordersByProduct = rows.membership(items.reverse());
var products = productsByOrder.get(orderId);
boolean inAny = ordersByProduct.containsAny(productId, requiredOrderIds);
boolean inAll = ordersByProduct.containsAll(productId, requiredOrderIds);
```

Buradaki items, ilk bölümdeki iki kolonlu ilişkidir.
Yön order_id alanından product_id alanına doğrudur.
String ilişki yardımcısı yalnızca bu iki kolonu okur.
reverse aynı kaynağı kullanır; yeni SQL çalıştırmaz.
0.13 ile sayısal kimlikler için SnapshotRelation.longs(...), UUID için
SnapshotRelation.uuids(...) kullanılabilir. Farklı uç tipleri için
SnapshotRelation.of(..., Long.class, ..., UUID.class) seçin.
Tipli listeler, Comparator verilmezse kaynak sırasını korur; String yardımcılarının
metin sıralamasıyla aynı davranış olduğu varsayılmamalıdır.
UUID nesnesi, standart UUID metni veya 16 byte'lık gösterim kabul edilir.
Ek alanlar için record kaynağı kullanın.
rows.lists(source, key, value, comparator) ve rows.membership(source, key, value)
bu kayıtlarla da çalışır.

| Yardımcı | Davranışı |
|---|---|
| unique | Tek anahtara tek değer; tekrar eden kimlik hatadır |
| lists(relation) | Hedefleri metin sırasına dizer; tekrarları korur |
| Genel lists | Comparator açık seçilir; tekrarlar korunur |
| membership | Yalnızca üyelik kontrolünde tekrarları birleştirir |
| Eksik anahtar | Boş liste veya boş küme davranışı |
| Boş containsAll | Anahtar bulunmasa da true |
| Boş containsAny | false |
| Null ilişki ucu | Satırı sessizce atmak yerine hata |

JSON için liste, varlık kontrolü için küme kullanın.
Listeyi kümeye çevirmek sıralamayı ve tekrarları değiştirir.
İki bağımsız varlık koşulunu aynı çocuğun sağlamasını istemek farklı sonuç üretebilir.

Köke göre DTO dağıtmak için SnapshotLists.distribute(values, dto -> dto.rootIds())
kullanın. DTO her farklı köke bir kez atanır; kendi ilişki listesi değişmez.
Giriş sırası korunur. Dağıtımdan önce sıralayın.
Böylece her kök için bütün DTO'lar taranmaz.
Gerçek çoktan çoğa ilişkilerin bellek maliyeti ise ortadan kalkmaz.

Bu yapılar yalnızca o yenilemeye aittir; global önbellek değildir.
Mapping başında bir kez hazırlayın. Kök eşleme koduna JDBC, Redis veya HTTP
çağrısı eklemeyin. Uygunluk kuralları ve DTO hazırlama uygulamada kalır.

## 4. Sonucu Değiştirmeden Yükseltin

1. Mevcut JSON örneğini veya referans SQL'i saklayın.
2. 0.12.0 ile derleyin. Metadata/codec tekrarını SOURCE ile değiştirin.
3. Koşulları tek tek değiştirip seçilen kimlikleri karşılaştırın.
4. İlişki yönünü yazın. Eksik, tekrar eden ve ters yönlü kayıtları test edin.
5. Null, sıralama, boş liste ve silinmiş kayıtlar dahil tam cevabı karşılaştırın.
6. Tek bağlantıyı, sabit kaynak sorgusu sayısını ve GET'te SQL olmadığını doğrulayın.
7. Zaman aşımı, kilit kaybı, disk ve kaynak bütçesi hatalarını sınayın.
8. Saklama biçimi veya cevap sözleşmesi değişiyorsa ayrı Redis önekiyle başlayın.

Yayın, yeniden deneme, kilit ve veri yaşı sözleşmeleri değişmez.
Başarısız hazırlık başarılı kataloğu değiştirmemelidir.
Yeni yardımcılar gizli SQL fallback, GET'te warm veya sınırsız retry eklemez.
Bağlantı ve socket sürelerini sonlu tutun; iş süresini, satır ve byte sayılarını izleyin.
İndeksleri, heap'i, diski ve Redis'te eski/yeni katalogların birlikte tutulma maliyetini planlayın.

## 5. Filtre ve Tip Örnekleri (0.13)

```java
var orders = SnapshotSource.entity("orders", OrderEntityCacheBinding.SOURCE)
        .where(SnapshotPredicate.gte("order_date", Instant.parse("2026-01-01T00:00:00Z"))
                .and(SnapshotPredicate.inValues("status", List.of("OPEN", "PENDING")))
                .and(SnapshotPredicate.isNull("deleted_at")));
var links = SnapshotRelation.longs("items", "order_items", "order_id", "product_id")
        .where(SnapshotPredicate.in("order_id", orders.select("order_id")));
// Eşleme hazırlığında bir kez oluşturulur; bu çağrılar SQL çalıştırmaz.
var itemsByOrder = rows.lists(links, Comparator.naturalOrder());
var ordersByItem = rows.membership(links.reverse());
```

Kolon adlarını kendi üretilmiş eşlemenize göre seçin. Enum değerleri sıra numarasıyla
değil adıyla bağlanır. Bilinmeyen enum adı ve tam sayı alana gelen kesirli değer hata verir.
İndeksli/artımlı katalog, limitler, atomik yazım ve Cluster ayarları için
[sınırlı çalışma rehberini](sinirli-calisma-islemleri.md) okuyun.
