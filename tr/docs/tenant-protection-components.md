# Tenant Koruma Bileşenleri

`JdbcTenantPurgeGuard`, `RedisGenerationBarrier`, `RedisTenantVersionAllocator`,
`JdbcTenantVersionAllocator`, `RedisVersionInventory`, `RedisPurgeScanner` ve
`JdbcPurgeScanJournal`; kabul engeli, sürüm aralığı ve sürdürülebilir keşif için alt düzey
bileşenlerdir. **Uçtan uca tenant silme hizmeti değildir.** Keşif; kimlikleri, projeksiyonları,
ortak indeks üyeliklerini, kuyrukları/DLQ'ları veya L1 kopyalarını silmez. Bu bileşenlerin
kurulması, tam silme uç noktası açmak için yeterli değildir.

## Kapsam ve Kaynak Verisi

Kapsam tek bir **(Redis namespace, tenant)** çiftidir; tenant'ın bütün keyspace'i değildir.
Diğer tenant'lar ve namespace'ler etkilenmez. Silme sonrasında çift CLOSED kalır. Yeniden açma,
ayrı yetkili işlemle ve doğrulanmış yeni kuşakla yapılabilir.

`PurgeOptions.includeSource=false`, yalnız Redis temizliği anlamına gelir: doğrulanmış bir
temizleme motoru sahipliği kanıtlanan Redis verisini, projeksiyonları, indeks/sahiplik durumunu
ve tamamı bu çifte ait eski kuşak kuyruk girdilerini kaldırır. Canonical write-behind DELETE
çağrılamaz ve hiçbir SQL DELETE kuyruğa yazılamaz. Kaynak satırları korunur, kalıcı SQL guard
yine kapanır ve `sourceRows` sıfır kalır. Bu bileşenler yıkıcı Redis/projeksiyon/kuyruk
temizleme motorunu henüz sağlamaz.

`includeSource=true`, **guard kapandıktan sonra** açık ve sınırlı JDBC kaynak silmeye izin
verir; canonical DELETE kuyruğu oluşturmaz. `deleteSource`, aynı guard kilidi ve transaction
altında en çok 128 birebir tenant eşleşmeli fiziksel satırı siler ve sayıyı ilerlemeyle birlikte
commit eder. false değerinde SOURCE aşaması yasaktır; true değerinde doğrulamaya geçmeden önce
bu aşama `deleteSource` ile tamamlanmalıdır. Redis kimliği, fiziksel anahtar ve doğrudan kaynak
satırı sayıları ayrıdır. Sonucu bilinmeyen Redis değişimi sıfır silinmiş gibi raporlanmaz.

Yalnız Redis silme işleminden sonra korunan SQL/geçmiş verisi veya eski dış olay yükü sessizce
yeni kuşağa alınamaz. `prepareReenable` ve `openPreparedGeneration`, böyle bir işlem için
`MIGRATION_REQUIRED` üretir. Otomatik yeniden kabul veya bu engeli atlayan bir API yoktur;
ayrıca doğrulanmış kaynak/geçmiş geçiş sözleşmesi gerekir. Retention `DELETE` ayrı işlemdir
ve normal SQL write-behind yolunu bilerek kullanır.

## Uyumluluk ve Kabul

Mevcut `WriteOperation`, `QueuedWriteOperation`, `WriteReceipt`, `CacheDatabaseConfig`
ve `ExternalChangeEvent` record bileşenleri değişmez. Özgün kuşak ayrı
`TenantGenerationEnvelope<T>` ile taşınır; trace metadata'sına veya iş sütunlarına gizlenmez.
Redis kabulü tekil SQL commit anlamına gelmez. Mevcut son-durum kalıcılık sözleşmesi değişmez;
eski kuşağın reddi, o eski yazının kalıcılaşması olarak sayılamaz.

Sağlayıcıya uygun V019 geçişini uygulamanın veritabanı geçiş aracıyla kurun; çalışma anında
DDL yoktur. Eksik guard/kuşak güvenli biçimde reddedilir. Yönetimsel kapanış ile flusher'ın
`authorizeWrite(s)` çağrıları aynı kalıcı guard satırını kilitler. Flusher, DML/fence
filtrelemesinden önce **aynı fiziksel JDBC bağlantısını ve iş transaction'ını** kullanır;
kilit commit/rollback sonuna kadar tutulur. Önceden başlamış korumalı commit kapanıştan önce biter.

Ön plan Redis-first kalır: kuşak, sahiplik, sürüm tahsisi ve değişim aynı atomik komutta olmalıdır.
Ön planda SQL sorgusu, kimlik rezervasyonu veya SQL defteri yoktur. Önce SQL, sonra Redis kapanır.
Yeni kuşağı kalıcı hazırlayın, Redis CLOSED durumunu ve doğrulanmış aralığı kurun, SQL OPEN commit
edin, ardından Redis'i açın. Lease yenileme ve sahiplik çiti açıktır; lease bırakmak kabulü açmaz.

Varsayılan guard kurucusunda kaynak silme kapalıdır. En çok 64 incelenmiş binding parmak izi,
trigger/RLS etkilerinin sınırlı ve aynı tenant içinde olduğunu doğrular. `validateSource`
ayrıca tenant ile başlayan indeksi, tür/kodlamayı ve kısıtlayıcı yabancı anahtarları denetler.
DDL sonrasında yeniden doğrulayın. PostgreSQL sınırlı tableoid/ctid, SQL Server DELETE TOP,
Oracle ROWID seçimi kullanır. Onay, istenen tenant ile birebir eşleşmelidir.

## Korunan Sürüm Aralıkları

Kimlik sürümlerini silip INCR sayacını birden başlatmak mevcut receipt/causal karşılaştırmalarını
bozar. Korumalı çift başına tek kalıcı SQL sürüm tahsisçisi/üst sınır vardır; satır başına kayıt
yoktur. Yönetim veya sınırlı arka plan işi en çok 1.000.000 sürümlük aralığı kalıcı ayırır.
Ön plan Lua, INCR ardından GET ile tam ondalık metni kullanır. İlk üst sınır
**9.007.199.254.740.991** değeridir. Tükenme, doğrulanmamış envanter veya kurtarma uyuşmazlığı
kabulü reddeder; korumalı yazı eski kimlik-başı INCR yoluna dönmez.

İlk aralığı açmadan önce bütün eski üreticileri/replay yollarını durdurun. Kalıcı Redis sahiplik
işaretlerini VE desteklenen namespace'i; soğuk sürümler ve tombstone'lar dahil tarayın. Yanıtı
işlemeden önce kalıcı kaydedin. Tüketilen her öğe için sahiplik sınıflandırmasına ek olarak
`RedisVersionInventory.maximum` ve `consumeVersionInventory` kullanılmalıdır. Normal tüketim
sayısal üst sınırı doğrulamaz. Yalnız tamamlanmış, bilinmeyen sahipliği bulunmayan keşif mühürlenir.

`scanDurableFences`, silinmiş satır üst sınırları dahil mevcut SQL write-fence birincil
anahtarını en çok 128'lik sayfalarla tarar. Bu tabloda tenant ile başlayan sürüm indeksi
bulunmadığından tek seferlik temkinli tarama bütün fence satırlarını kapsar. Yönetimseldir,
sürdürülebilirdir ve her sayfa zaman sınırlıdır; ön plan taraması değildir.
`probeSourceMaximum`, soğuk iş satırları için (tenant, version) ile başlayan sayısal indeksi
zorunlu tutar. SQL fence kayıtları silinmez. `sealInventory`, rezervasyondan önce doğrulanmış
üst sınırı ve parmak izini kaydeder.

`reserveClosed` veya yalnız arka planda `reserveOpen` kullanın; commit edilmiş
`TenantVersionRange` değerini `RedisTenantVersionAllocator.install` ile kurun. Beklenen
rezervasyon çiti eski/araya girilmiş tekrarları reddeder. Yalnız en son aynı istek idempotenttir.
Yeni aralık öncekinin kullanılmayan bütün değerlerini yakar; kurulum sayacı geri sarmaz.
Guard, üst sınır, aralık ve ilerleme kayıtlarını kalıcı ve süresiz tutun. Redis yeniden
başladığında kalıcı üst sınırın üzerinde yeni aralık ayırın; eski süreç kimliğine bağlı aralıklar
reddedilir.

Korumalı işlem başına INFO SERVER ile Redis süreç kimliği denetlenir; retention grubunda bir
kez çalışır. ACL ve gecikme doğrulaması bu maliyeti kapsamalıdır.
[INFO SERVER](https://redis.io/docs/latest/commands/info/) ve
[INCR](https://redis.io/docs/latest/commands/incr/) sözleşmelerine bakın. Aynı Redis sürecine
yerinde geri yükleme yalnız süreç kimliğiyle anlaşılamaz: geri yüklemeden önce kabulü kapatın,
kalıcı durumu uzlaştırın ve yeni aralık ayırın. SQL güvenlik durumunun geriye alınması ayrıca
kurtarma gerektirir; otomatik açılmamalıdır. Bu bileşenler otomatik kurtarma veya geri yüklenmiş
ortam sertifikasyonu sağlamaz.

## Kimlik Ayırmak, Yazma Yetkisi Vermek Değildir

`JdbcFreshIdentityAllocator`, sayısal kayıt kimliklerini yazma sürümlerinden ayrı olarak ayırır.
Bu sınıf bir yönetim bileşenidir; yeni kayıt yazma mekanizması değildir. Kullanımdan önce ilgili
sağlayıcının `V019_7__fresh_identity_allocator.sql` dosyasını önceki V019 migration'larından sonra
açıkça uygulayın. Bu dosya `cachedb_identity_allocator` tablosunu ve ilk kurulumdaki ad alanı
eşlemesini arayan indeksi oluşturur. Çalışma zamanı bu nesneleri kurmaz; tenant temizliği de silmez.

Yönetici, kimlik aralığının bütün tenant'lar, geçmiş veriler ve dışarıdan yazan uygulamalar dikkate
alınarak yalnızca bu ad alanına ayrıldığını ayrıca doğrulamalıdır. Redis'in boş olması, kaynak
tablodan `MAX(id)` okunması veya tenant'ın silinmesi bunu kanıtlamaz. Yetki kimliğini, aralık
sınırlarını ve münhasır kullanım onayını sabit tutun. Kurulmuş bir aralık değiştirilemez veya
genişletilemez.

İlk kurulumda `CacheInitialTenantBootstrap.provisionIdentityDomain(request, domain)`, gerçek ve
henüz kapalı INITIAL oturumunu ve Redis tarafındaki kapanış denetimlerini kullanır. Yapay bir purge
kaydı oluşturmaz, kurulum aşamalarını ilerletmez, kimlik tahsis etmez ve işlem kabulünü açmaz.
Arka plandaki `reserveOpen` ile bakım sırasındaki `reserveClosed`, istek başına en çok 1.024 kimlik
ayırır. Kalıcı tenant kilidi ve ad alanının ortak tahsis kaydı işlemleri sıraya koyar. Normal kayıt
yazmaları bu tabloya sorgu göndermez.

`reserveOpen` ve `reserveClosed` için yalnızca en son isteğin aynısı, beklenen rezervasyon sürümü de korunarak tekrar edilebilir. Eski
bir isteği tekrar etmek için bu sürümü değiştirmeyin. Ayrılan ancak kullanılmayan kimlikler de
tüketilmiş sayılır. Tenant silme, yeniden açma veya süreç yeniden başlatma bu kimlikleri kullanıma
geri vermez. Bu tabloyu kalıcı güvenlik kayıtlarıyla birlikte yedekleyin ve geri yükleyin. Tablo
kayıpsa veya eski bir yedeğe dönmüşse, kullanılmış aralığı yeniden kurmak yerine açık bir kurtarma
işlemi gerekir.

**SQL'de kimlik ayırmak tek başına Redis'te sahiplik oluşturmaz ve `save` yetkisi vermez.**
Rezervasyonu Redis'e elle sahiplik işareti ekleyerek veya normal yazma yoluna SQL sorgusu koyarak
kullanmaya çalışmayın.

### Sınırlı Bir Yeni Kayıt Aralığı Hazırlama

Kayıtlı, tek Redis sunucusu kullanan `POINT_NO_QUOTA` INITIAL profilinde, standart `Long` kimlikler
için `CacheInitialTenantBootstrap.reserveAndAdmitFreshRange(tenant, request)` kullanılabilir.
Bu yönetim işlemi, kalıcı kimlik rezervasyonunu Redis'te atomik sahiplik hazırlığıyla birleştirir.
Önce INITIAL sırasında yalnız bu ad alanına ayrılmış kimlik aralığını tanımlayın, açılışı tamamlayın
ve ardından bir yönetim işinden çağırın:

```java
// domain daha önce açıkça tanımlandı; generation, açılışı tamamlanmış güncel kuşaktır.
// expectedReservationEpoch, kimlik tahsis kaydının konumudur; entity yazma sürümü değildir.
var request = new FreshIdentityRange.Request(
        domain, generation, 32, UUID.randomUUID(), expectedReservationEpoch);
FreshIdentityRange admitted;
try (var admin = CacheTenantContext.administrative()) {
    admitted = bootstrap.reserveAndAdmitFreshRange("tenant-a", request);
}
// Dönen her kimliği bir kez atayın; sonra kayıtlı repository'nin saveWithReceipt yolunu kullanın.
// Bu sonuç WriteReceipt değildir; henüz entity veya SQL satırı yazıldığı anlamına gelmez.
```

Çağrı başına sınır 128 kimliktir. İşlem; gerçek kayıt eşlemesini, onaylı çalışma zamanı listesini,
açık kuşağı ve kontrol sürümünü, Redis primary sürecini ve her kimliğe ait anahtarları denetler.
Tek bir mevcut anahtar bütün aralığın hazırlanmasını engeller; eski veya başka tenant'a ait veri
üzerine yazılmaz. Redis hazırlığına yalnızca yeni commit edilmiş bir rezervasyon ulaşabilir.
Yanıt kaybolsa bile commit edilmiş aynı istek yeniden kabul edilmez. `reserveOpen` üzerinden
tekrar denemeyin; saklanmış bir `FreshIdentityRange` nesnesinden hazırlama yetkisi üretmeyin.
Kalıcı tahsis kaydının konumunu inceleyin ve sonraki aralık için yeni UUID ile yeni istek oluşturun.
Commit edilmiş ancak sonucu belirsiz veya reddedilmiş aralıklar da tüketilmiş sayılır.

Normal kayıtlı yazmalar mevcut Redis receipt ve asenkron SQL kalıcılığı sözleşmesini korur;
kimlik tahsis tablosuna sorgu göndermez. EMPTY açılışı silinen kimlikleri geri vermez: yeni kuşak
için daha ilerideki, çakışmayan bir aralığı hazırlayın. Hazırlık, kalıcı sahiplik işaretini ve `0`
sürüm işaretini tek atomik işlemde ekler; entity veya kuyruk iletisi oluşturmaz. Sıfır, henüz
yazılmamış kaydın başlangıç değeridir; ayrılmış bir yazma sürümü, yazma onayı veya SQL kalıcılığı
kanıtı değildir. İlk normal yazma, Redis sayacından pozitif bir sürüm alır. Silme, hiç kullanılmamış
sahiplik ve sıfır sürüm işaretlerini de keşfedip kaldırır; SQL tahsis kaydını geri almaz.
Kullanılmayan işaretler silinene kadar Redis'te
bellek tüketir. Toplam ön tahsis kapasitesini sınırlayın; bu bir otomatik doldurma döngüsü değildir.
Diğer kimlik codec'leri, Redis Cluster, kota kullanan modeller ve projection'a bağlı yeni kayıt
oluşturma bu yönetim yolunun kapsamı dışındadır.

### Yazma Sürümü Aralığını Açıkça Yenileme

Kayıt kimlikleri ile yazma sürümleri ayrı kalıcı tahsis kayıtlarından ayrılır. Yeni kimlik hazırlamak,
Redis'teki yazma sürümü aralığını büyütmez. INITIAL veya EMPTY açılışı tamamlandıktan sonra, aralık
tükenmeden önce bir yönetim işi daha ilerideki bir aralığı ayırıp Redis'e kurabilir:

```java
// generation ACTIVE durumundadır. expectedVersionEpoch, SQL yazma sürümü rezervasyonunun konumudur.
// Bu değer, reserveAndAdmitFreshRange için kullanılan kimlik tahsis konumu değildir.
UUID refillRequest = UUID.randomUUID(); // Yanıt belirsizse aynı isteği yinelemek için saklayın.
TenantVersionRange installed;
try (var admin = CacheTenantContext.administrative()) {
    installed = bootstrap.refillVersionRange(
            "tenant-a", generation, 4096, refillRequest, expectedVersionEpoch);
}
// Normal repository yazmaları, her yazmada SQL'e başvurmadan sürer.
```

Önce SQL rezervasyonu commit edilir; ardından Redis'teki aralık güncellenir. Yanıtın belirsiz kaldığı
durumda **aynı UUID, adet ve beklenen konumla** yeniden deneyin. Eski aralığı yeniden kullanmak için
yeni istek oluşturmayın. En son commit edilmiş aralığın tamamı elinizdeyse yönetim bağlamında
`bootstrap.installOpenRefill("tenant-a", range)` çağrısını kullanabilirsiniz. Kurulum; aralığın
tamamını kilitli SQL kontrol kaydıyla, saklanan açılış kanıtıyla, onaylı çalışma zamanı listesiyle
ve aynı Redis primary süreciyle karşılaştırır. Aynı isteğin tekrarı tüketilmiş sayacı geri almaz.
Daha yeni rezervasyonla geçersiz kalan aralık, değişen kuşak veya değişen Redis süreci reddedilir;
bu durumlar sessizce onarılmaz.

Tek rezervasyonun üst sınırı `TenantVersionRange.MAX_RANGE` değeridir. Yeni aralığa geçildiğinde
eski aralıkta kullanılmamış sürümler tekrar dağıtılmaz. Otomatik zamanlayıcı kurulmaz; normal yazma
yoluna SQL sorgusu eklenmez. Aralık tükenirse açık yenileme başarılı olana kadar yazmalar reddedilir
ve yazma onayı üretilmez. Bu API, yukarıdaki yeni kayıt hazırlama işleminin tek Redis sunuculu
korumalı profil sınırlarına tabidir.

## Özgün Zarflar ve Okumalar

`evalStamped`, eksik zarfı reddeder; özgün çift/kuşağı, sahipliği ve sürüm tahsisçisi durumunu
atomik denetler. Dış olay özgün yakalama anında damgalanmalıdır; kuyruktan alınırken güncel
kuşak çıkarılamaz. Dış sürüm alanının tahsisçiyle uyumu veya açık eşlemesi de doğrulanmalıdır;
yalnız kuşak damgası yeterli değildir.

`captureOpen`, eşzamansız kaynak okumasından **önce** kuşağı alır. Gecikmiş doldurmada aynı
damgayı `evalOpen` çağrısına taşıyın; kapanış/yeniden açma eski doldurmayı reddetmelidir.
`evalOpen` değişim anahtarları KEYS[3] ile başlar; iç tahsisçi anahtarları sona eklenir.
Bütün veri okumaları, projeksiyonlar, yenilemeler ve hydration yolları atomik kabul denetimi
kullanmalıdır. Ayrı guard GET ardından korumasız işlem güvenli değildir.

L1 isabeti Redis'i atlar. Okuma ve silme onaylarını kapsayan sınırlı, çitli kabul/geçersizleştirme
protokolü kurulana kadar korumalı namespace L1'ini kapalı tutun. Bu yardımcılar repository, dış
olay yürütücüsü veya L1 çağrı yollarını değiştirmez. Üretici/CAS/PATCH/grup/replay/flusher
özgün kuşağı ve ortak tahsisçi sürümünü taşımalıdır. Bileşen testleri bu bütünleşmenin kanıtı değildir.

## Sınırlı Keşif ve Sürdürme

Tenant sıcak indeksi yalnız yerleşik kimlikleri içerir. Kalıcı version:tenant sahiplerini,
desteklenen namespace'i ve ortak set/ZSET üyeliklerini tarayın. Sahipliği birebir çözün.
Bilinmeyen sahip, belirsiz kimlik veya desteklenmeyen düzen eksik/geçiş gerektiren sonuçtur;
yabancı veriyi silme yetkisi değildir. Sahiplik kanıtını silmeden önce keşfi tamamlayıp mühürleyin.

Cluster taraması açık hash etiketiyle aynı slot'a yerleşmiş namespace düzenini destekler.
Her sayfanın öncesi/sonrası rol, slot sahipliği, topoloji ve süreç kimliği denetlenir.
Yeniden başlatma, slot taşıma veya topoloji değişikliği eski cursor'ı geçersiz kılar.
Namespace dışı özel düzen, projeksiyon deposu ve genel kuyruklar ayrı kapsam doğrulaması ister.

`BoundedRedisWire`, açıkça kapatılan RESP2 aktarımını ve tek süre-sonu thread'ini sahiplenir.
Yanıt en çok 1 MiB, 8192 çözümlenmiş düğüm ve altı iç içe düzeydir; SCAN bulk öğesi en çok
1024 bayttır. Bildirilen uzunluklar bellek ayırmadan önce denetlenir. COUNT 500 yanıt sınırı
değil ipucudur. Önceden çözülmüş uç noktalar, kimlik doğrulama/TLS, sonlu bağlantı/okuma/çağrı
süreleri ve aktarım başına en çok sekiz bağlantı yapılandırın. Süre aşımı fiziksel soketi kapatır;
kabul izni ancak fiziksel I/O bittikten sonra bırakılır. Aktarım sayısını sınırlayın ve açıkça kapatın.

Önce bütün sınırlı yanıtı, giriş/sonraki cursor'ı, topolojiyi ve sayfa kimliğini kalıcı kaydedin.
Transaction başına en çok 128 öğe tüketin; yeni işleri, sayısal gözlemleri ve bekleyen ofseti
atomik kaydedin. Cursor ancak yanıt bütünü tüketilince ilerler. Bilinmeyen sahiplik kalıcıdır.
Silme anındaki iş listesi kaynak/öğe/bayt sınırlarına ve transaction sayaçlarına sahiptir;
normal yazma kimlik defteri veya sınırsız Redis geçmiş kümesi değildir.

## Silme ve Kurtarma Sınırları

Tam silme iddiası için yıkıcı kimlik/indeks/projeksiyon/L1 temizliği, aynı slot'ta çitli kuyruk/DLQ
işlemi, replay/flusher bütünleşmesi, bilinmeyen sonuçların uzlaştırılması, audit teslimi ve
kurtarma doğrulaması gerekir. Yalnız tamamı bu çifte ait değişmez eski kuşak girdileri silinebilir.
Yabancı girdiler korunur; karma tenant atomik grubu kısmen silinemez. Bilinmeyen eski zarf geçiş
gerektirir. Tamamlamadan önce geç retry/DLQ kopyalarını durdurun veya boşaltın.

`ACTIVE_STORES_RETAINING_SECURITY_METADATA`; güvenlik çitleri/üst sınırlar, ilerleme/audit ve
tombstone kayıtlarının korunduğunu açıkça kabul eder. Bunlar kimlik ve geri çözülebilir konum
içerebilir; COMPLETE bunların silinmesi değildir. Ham onay yalnız istek içindedir; yeni ilerleme
anahtarları SHA-256 takma kimlik kullanır. Tenant kimliği, veri veya konum loglamayın; sınırlı,
opak operatör kimliği ve düşük kardinaliteli metrik kullanın. KVKK/GDPR sertifikasyonu, yedek/dış
sistem silme veya bütün geçmişe retention garantisi değildir. Ayrı ZSET retention taraması
yalnız indeksli yerleşik kümeyi kapsar.

Genel korumalı komut yardımcıları toplam 4096 anahtar, 8192 argüman, anahtar başına 8192 UTF-8
baytı, argüman/betik kaynağı başına 256 KiB ve toplam 8 MiB komut bütçesiyle sınırlıdır.
Bozuk UTF-16 kodlamadan önce reddedilir; bayt sayımı kodlanmış kopyalar ayırmaz.
Yardımcılar yapılandırılmış Redis istemcisini ödünç alır; sonlu istemci/havuz süreleri gerekir.
Sınırlı ortak Lua kaynağı değişmez veri olarak tutulur; her ön plan çağrısında yeniden açılmaz.

## Varlığa Ait Kapsam

Varlık temizleme işlemi yalnızca kayıtlı varlığın namespace'i ile istenen tenant'ı ve açıkça
bildirilmiş, bu varlığa ait depoları kapsar. Diğer varlıklarda tenant genelinde yazma kapatma
veya kişisel verileri otomatik bulma işlemi değildir. Genel Spring Cache metot değerleri bir
varlık sınıfına otomatik olarak bağlanamaz; sahiplik katılımcısı açıkça kaydedilip zorunlu
katılımcı listesine eklenmedikçe bu kapsamın dışındadır. Bildirilmiş zorunlu katılımcının
bulunmaması veya sahipliği doğrulayamaması, sonucun eksik olması demektir.

Karışık projeksiyonlar ve atomik kuyruk kayıtları diğer varlıkların ve tenant'ların verilerini
korumalıdır. Desteklenmeyen sahiplik, nesli bilinmeyen eski kayıtlar veya tamamlanmamış sınırlı
doğrulama, başarılı silme olarak raporlanamaz. Bu kapsam evrensel kişisel veri silme, KVKK/GDPR
sertifikası ya da yedek, dışa aktarma, log ve harici sistemlerde silme garantisi değildir.
