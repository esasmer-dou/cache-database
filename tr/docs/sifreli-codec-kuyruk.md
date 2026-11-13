# Üretilen Şifreli Codec ve Yazma Kuyruğu

## Açık Anahtar Bağlama

`EntityMetadata.encryptedColumns()`, fiziksel sütun adını çekirdekteki
`EncryptedColumnDescriptor(keyRing, Scope, blindIndex)` ile eşleyen,
değiştirilemez bir map döndürür. Çekirdek annotation paketine bağımlı değildir.
REDIS_AND_SOURCE sütunları için `columnTypes()` String bildirir; JDBC,
E1 zarfını sayı, tarih veya enum olarak çözümlememelidir.

```java
EntityCodec<Order> codec = OrderCacheBinding.CODEC.bindEncryption(provider);
if (!codec.encryptionBound()) {
    throw new IllegalStateException("Şifreleme anahtarı sağlayıcısı bağlanmadı");
}
SourceMapping<Order> source = SourceMapping.entity(OrderCacheBinding.METADATA, codec);
```

`OrderCacheBinding.codec(provider)` eşdeğer bir oluşturma metodudur.
Her bağlama, final motor alanı ve çalışma ortamına özgü özel alan codec'leri olan
yeni bir örnek döndürür. Değişken küresel anahtar, hizmet bulucu, entity reflection'ı
veya ThreadLocal sağlayıcı yoktur. Özel alan codec'leri deterministik,
sınırlandırılmış ve eşzamanlı kullanıma uygun olmalıdır.

Statik CODEC bağlanmamıştır; korunan işlemleri değer döndürmeden reddeder.
Üst veri anahtarsız okunabilir. `encryptionBound()`, bağlanmamış codec'te ve
varsayılan uygulamada false; açıkça bağlanmış üretilen codec'te true döner.
Şifreli sütun üst verisi bulunan bir kaydın kabulü için bu durum true olmalıdır.
İşlem yapmayan varsayılan bağlama metodu şifreleme desteği sayılmaz.
Bağlama anında güncel anahtar, byId ile aynı AES/HMAC baytlarıyla okunabilmelidir.
Sağlayıcının ve özel codec'in hata nedenleri/değerleri dışarı taşınmaz.

Kaynak eşlemeleri de aynı bağlı çalışma zamanı codec'ini kullanmalıdır.
Statik SOURCE, bağlanmamış çözümleyiciyi tuttuğu için onun yerine kullanılamaz.
`source(provider)` açık bir kaynak oluşturma metodudur.
`PayloadEntityCodec`, bağlama durumunu ve tüm yeni metotları devreder;
çerçeveleme, şema ve sıkıştırma katmanları şifrelemenin dışında kalır.

## Ayrı Veri Biçimleri

| Metot | REDIS_ONLY korunan alan | REDIS_AND_SOURCE korunan alan |
| --- | --- | --- |
| `toColumns(entity)` | Orijinal mantıksal/SQL uyumlu değer | Orijinal mantıksal değer |
| `fromLogicalColumns(map)` | İş kuralı/PATCH için mantıksal nesne | Mantıksal nesne; SQL saklama çözümlemesi değildir |
| `toSourceColumns(entity)` | Orijinal SQL uyumlu değer | E1 şifreli String |
| `fromColumns(sqlRow)` | Orijinal SQL çözümlemesi | E1 String gerekir; çözüp alan tipine dönüştürür |
| `toRedisValue` / `fromRedisValue` | Uzunluk önekli alanda E1 | Uzunluk önekli alanda E1 |
| `toIndexColumns(entity)` | Kör indeks varsa HMAC; yoksa sütun atlanır | Aynı |
| `indexQueryValue(column, typedLiteral)` | Yalnız EQ için aynı HMAC | Aynı |
| `toQueuedColumns(entity[, assignments])` | **E1; açık metin değil** | E1 |
| `sourceColumnsFromQueued(id, decodedColumns)` | Doğrula, SQL sütununun metin değerini oluştur | Doğrula, orijinal E1'i koru |

Şifrelenmemiş eski codec'lerin varsayılan davranışı ve ileti baytları değişmez.
`toColumns` ve `entityColumnValue`, iş kuralları için mantıksal erişimdir;
Redis'e kalıcı yazılacak indeks veya kuyruk çıktısı değildir.
Mantıksal PATCH nesnesi oluşturma ile SQL saklama verisini okuma ayrıdır.

Korunan codec'in `indexQueryValue` metodu, kanonik fiziksel sütun adı bekler.
Bilinmeyen ad, Java alan adı veya büyük/küçük harfi uyuşmayan ad reddedilir;
korunmamış sorgu değeri döndürülmez. Sütun adı çağrıdan önce çözülmelidir.

REDIS_ONLY; SQL'i, replikaları, CDC'yi ve yedekleri açık bırakır. Redis yazma
kuyruğunun açık metin saklayabileceği anlamına gelmez.
REDIS_AND_SOURCE, gerçek şemada yazılacak zarfın tamamına yetecek kapasite ve String
JDBC çözümlemesi gerektirir. Zarf en çok 65.536 ASCII bayttır; bu, sütunun asgari
kapasitesi değildir. Sayısal sütun, byte[] veya JDBC Clob
nesnesi reddedilir. String üst verisi, şemayı kanıtlamaz; gerçek tip ve kapasite
yazımı yapan arka plan işlemi tarafından SQL değişikliğinden önce doğrulanır.
Şema bilgisi önbellekten gelse bile gönderilen her değer yeniden denetlenir.
Küçük VARCHAR2 sütunları, zarf sığıyorsa kullanılabilir; Clob döndüren eşlemeler
kendiliğinden uyumlu değildir. Doğrulanmış String bağdaştırıcısı/şema
yoksa işlem reddedilmelidir. Sessiz kısaltma veya otomatik şema değişikliği yoktur.
Rastgele şifreli SQL sütunu, açık metin EQ/aralık/sıralama/LIKE sorgusunu doğrudan
karşılayamaz.

## Redis Kuyruk Sözleşmesi

UPSERT için kabul edilmiş entity üzerinden `toQueuedColumns(entity)` çağırın.
Sıcak PATCH için `toColumns` ile mantıksal sütunları alın; güncellemeleri
`normalizePatchColumns` ile normalize edin, tam kabul edilmiş durumu
`fromLogicalColumns` ile oluşturun ve
`toQueuedColumns(entity, assignments)` çağırın.

Sıra: mantıksal normalizasyon -> kabul edilmiş entity -> şifreli kuyruk sütunları
-> ileti çerçevelemesi. Literal `__NULL__`, `cachedb:__NULL__`,
`~P:N` ve `~P:V` gerçek null değildir; normalizasyon bunları null'a çeviremez.
Null olmayan korunan değerler, PatchColumnValues kodlamasından veya function
mapper'dan önce E1 olmalıdır. Yalnız gerçek Java null değeri NULL çerçevesi alır.

Atamalar tam kabul edilmiş durumla eşleşmelidir. Kısmi PATCH'e id veya kiracı
sütunu iş verisi ataması olarak eklenmez. Şifreli alanda aritmetik delta derleme
hatasıdır; programatik delta da aritmetik veya sınır hatası değeri açığa çıkarmadan
reddedilmelidir. İzin verilen şifresiz deltalar önce gerçek atamalara dönüştürülür.

Yalnızca atamalarla çalışan soğuk makbuz komutunda
`toQueuedPatchColumns(canonicalId, verifiedTenant, normalizedAssignments)`
kullanılır. Verilen alanlar, açık kimlik/kiracı AAD bağlamıyla doğrudan şifrelenir;
eksik kısmi satırdan nesne oluşturulmaz. Redis'te önceden belirlenmiş sürüm ve
doğrulanmış sahiplik gerekir. Nesne döndüren PATCH yine tam sıcak satırı gerektirir.
Eksik durum, ön planda SQL sorgusuyla başlangıç verisi yüklenmeden reddedilir.

Kuyruk sütun map'inin ayrılmış üst verisi:

- `$cachedb:crypto:format` = `~Q1`; her korunan kuyruk işlemi için zorunludur.
- Kiracılı entity'de `$cachedb:crypto:tenant` = `~T1:<satırın kiracısı>`.
  PATCH kiracı sütununu içermese de gerekir. Önek, eski NULL belirteciyle çakışmaz.
- En çok 128 atama ve iki üst veri alanı bulunabilir. Stream, birleştirilmiş son
  durum, atomik işlem, yeniden deneme ve DLQ bu alanları korumalıdır.
- `__NULL__` ve PATCH `~P:N`/`~P:V` çerçevelerini mevcut ileti kodlayıcısı
  yönetir. Kaynak dönüşümü, RedisWriteOperationMapper bu çerçeveleri çözdükten
  sonra çağrılır.
- Dönüşüm; kanonik işlem kimliğini, varsa id/kiracı atamasını ve sürümü doğrular.
  Bilinmeyen sütunları reddeder, ayrılmış üst veriyi çıkarır ve korunan her
  atamanın kimliğini doğrular.
- REDIS_ONLY, mevcut alan codec'iyle SQL sütun değerine döner.
  REDIS_AND_SOURCE, kimlik ve tip doğrulaması yapar; aynı E1 baytlarını korur.
  Yeniden denemede nonce veya anahtar kimliği değiştirilmez.

Yazma işleyicisinin sırası: korunan Redis zarfını çözümle -> bütün üyeleri doğrula
-> **yeni geçici SQL işlemleri** oluştur -> JdbcWriteFence -> iş verisi DML'i.
Bütün gerekli dönüşümler başarılı olmadan fence veya kısmi DML çalıştırmayın.
DELETE'in korunan alan ataması yoktur; Q1 alan dönüşümüne zorlanmaz.
Atomik işlemde silme dışındaki bütün üyeler, ilk fence çağrısından önce hazırlanır.

Orijinal QueuedWriteOperation nesnesini değiştirmeyin. Yeniden deneme, son durum
birleştirmesi, DLQ ve yeniden işleme şifreli orijinali korumalıdır.
Şifre çözümünden sonra oluşan SQLException, SQL alan değerlerini içerebilir.
Günlüğe veya DLQ'ya özgün hata mesajı, cause, next-exception ya da suppressed zinciri
yazılmamalıdır. `CacheFieldRedactor.redactSqlFailure(SQLException)`, sabit mesajla
yalnızca geçerli beş karakterli SQLState'i, üretici kodunu ve doğrudan geçici/
kurtarılabilir/zaman aşımı sınıflamasını korur. Yeniden deneme sınıflaması sarmalanmış
kök hataya bağlıysa önce sınırlı ve güvenli bilgi çıkarın; özgün hatayı saklamayın.

## AAD, Kör İndeks ve Null

AAD, E1 motor biçimindedir: `namespace|id|column`. Kiracılı entity'de namespace,
satırdan `base + ":tenant:" + base64url(UTF8(tenant))` olarak üretilir.
Kiracı boş olamaz; en çok 512 UTF-8 bayttır. Birleşik namespace 1024 baytı geçemez.
ThreadLocal üzerinden örtülü kiracı okunmaz. Kimlik, mevcut skaler serileştirmeyle
kanonik ve null olmayan bir değer olmalıdır; motorun ayraç/uzunluk kuralları geçerlidir.
Yetkilendirme, sahiplik ve kalıcı kiracı koruması ayrıca doğrulanmalıdır.
Kuyruktaki kiracı üst verisi bir yetki belgesi değildir.

İki argümanlı `indexQueryValue` metodu kiracı parametresi almaz. HMAC alanı,
kiracıya bağlı AAD yerine sabit entity namespace'idir. Aynı entity/anahtar
halkasındaki kiracılar eşit belirteçler görebilir. Eşitlik ve sıklık sızıntısı vardır;
kiracıya özgü kriptografik kör indeks yalıtımı sağlanmaz. İndeks adayları ve sahiplik
kontrolleri her yolda kiracıya göre sınırlandırılmalıdır.
Belirteci iki kez özetlemeyin. AES rotasyonunda ayrı HMAC anahtarı sabit kalır.
HMAC anahtarı, anahtar halkası veya protokol değişikliği açık indeks göçü gerektirir.

Null kabul eden korunan alan null kalır; özel codec null ile çağrılmaz.
Boş metin, JSON `null` metni, `__NULL__`, `~P:N` ve E1'e benzeyen metin
birbirinden farklıdır. Null olmayan özel değerlerde mevcut encode/decode/
toColumnValue işlevleri kullanılır. JSON alanı bir bütün olarak şifrelenir;
dış uzunluk öneki değişmez. Korunan içerikte yinelenen bilinen sütun reddedilir;
bilinmeyen sütunlar eski davranışla yok sayılır.
Eski açık metin entity, kuyruk, DLQ ve indeks üyelikleri için açık bir veri göçü,
kuyruk boşaltma ve temizleme planı gerekir. Annotation eklemek veri göçü değildir.

## Derleme Sınırları

`@CacheEncrypted` yalnız FIELD ve RECORD_COMPONENT hedeflerini destekler.
Getter/erişim metodu üzerindeki kullanım Java derleme hatasıdır. Record annotation'ı
backing field'a yayılır; erişim metoduna yayılmaz. Kalıcı record bileşenini işaretleyin.

Mevcut skaler/enum alanlar, record'lar, değiştirilebilir kalıcı alanlı bean'ler
ve açık codec kullanan jenerik olmayan adlandırılmış özel tipler desteklenir.
Korunan entity'de en çok 32 şifreli ve toplam 128 kalıcı sütun olabilir.
Şifresiz geniş entity'lerin davranışı değişmez.
Şifreli jenerik/dizi/tip değişkeni alanlar, özel id/kiracı codec'leri, kalıcı olmayan
veya statik hedefler, kapalı named-query/projection üreticileri, şifreli kaynaktan
projection ve kapalı @SourceSql yönlendirmeleri reddedilir.
Düz @CacheJsonRecord içinde kısmi alan şifreleme desteklenmez; kalıcı JSON alanını
bir bütün olarak şifreleyin. Annotation sessizce yok sayılmaz.

Id, kiracı, sürüm ve mantıksal silme sütunları şifrelenemez.
Kör indeksi olmayan korunan sütun, wildcard dahil indekslenemez;
indexedColumns içinde açıkça dışarıda bırakılmalıdır.
blindIndex=true yalnızca eşitlik üyeliği ekler. Kör indeks olsa bile bileşik
önek/sıralama, bölüm anahtarı/sıralaması, ilişki mappedBy/order alanları,
facet grubu/değeri, COUNT(field) dahil toplulaştırma değeri, sıralama/seek ve
EQ dışı koşullar yasaktır. İzin verilen kör EQ filtresiyle COUNT(*) kullanılabilir.
Programatik sorgu/delta API'leri de üst veri doğrulaması gerektirir;
işlemci rastgele Java metot gövdelerini analiz etmez.
