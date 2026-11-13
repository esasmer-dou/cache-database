# Entity Kapsamında Tenant Veri Koruma

`CacheDataProtection.purgeTenant(entityClass, tenant, options)`, kayıtlı tek bir entity ad alanı ve tek bir tenant için yönetim yetkisi gerektiren, sınırlı ve kaldığı yerden sürdürülebilen bir işlemdir. Tenant'ın bütün keyspace'ini silmez. Native plan; kaynak tabloyu, Redis entity/sayfa/indeks/projection depolarını ve bu entity'yi taşıyabilen bütün yazma, yeniden deneme, outbox, kurtarma ve DLQ stream'lerini açıkça belirtir. Ek depolar `PurgeParticipant` ile tanımlanır; gerekli bir katılımcı eksikse veya doğrulanamıyorsa sonuç `INCOMPLETE` olur.

## Veritabanı Hazırlığı

Tenant veri korumasını etkinleştirmeden önce ilgili sağlayıcının `V019__tenant_protection.sql`, `V019_1__purge_facade.sql` ve `V019_2__purge_list_scan_kind.sql` dosyalarını bu sırayla kendi veritabanı migration sisteminiz üzerinden kurun. Runtime kodu DDL çalıştırmaz. Artımlı migration, yerleşik `LIST` tarama türünü ekler; bilinmeyen türlerin reddedilmesi ve diğer tarama kısıtları korunur. Daha önce uygulanmış migration dosyalarını değiştirmeyin ve kalıcı güvenlik fence kayıtlarını silmeyin.

Güncel korumalı çalışma zamanı API'leri için sağlayıcının kanonik migration dosyalarını, aradaki sahiplik ve ilk kurulum migration'larıyla birlikte `V019_6__empty_tenant_activation.sql` dahil olacak şekilde kurun. Yeni `cachedb_empty_activation` tablosu yönetim düzeyindeki açılış kararlarını saklar. İş verisi değildir; tenant temizliğinde silinmemelidir.

## Sözleşme

Önce kalıcı SQL guard kapanır. Aynı guard satırını kilitlemiş iş işlemleri, kapanış commit edilmeden önce tamamlanır. Flusher, filtreleme ve DML'den önce aynı guard kilidini kendi iş JDBC transaction'ında almalıdır. Normal okuma ve yazmalar generation öğrenmek veya kimlik ayırmak için SQL'e gitmez.

Ardından Redis ad alanının kabul kapısı ve aynı hash slot'undaki kuyruk bariyerleri kapanır. `TenantReadFence`; bütün runtime'lardaki kayıtlı üretici, okuma, L1, gecikmiş doldurma ve projection yenileme yollarını kapatıp devam eden işleri bitirmelidir. Kanıt; güncel lease, generation ve manifest'e bağlı olmalıdır. Pub/Sub bildirimi veya yerel CLOSED bayrağı yeterli değildir. Koşulsuz izin veren varsayılan uygulama yoktur.

Keşif, kalıcı sahiplik işaretlerini ve tanımlı ad alanının tamamını tarar. Hot indekste bulunmayan soğuk veya önbellekten çıkarılmış kimlikler de kapsanır. Bulunan ortak üyelikler ve tanımlı sayfalar ayrıca taranır. Her sınırlı wire yanıtı, en fazla 128 öğelik parçalar tüketilmeden önce bütünüyle kalıcılaştırılır. Kuyruk snapshot'ları üst stream kimliğini ve sunucu kimliğini korur. Eski iletinin özgün generation bilgisi, güncel generation ile değiştirilmez. Sahiplik veya indeks üyeliği silinmeden önce sabit iş listesi mühürlenir.

Kuyruk temizliği değişmez ileti içeriğini karşılaştırır ve yalnızca bütünüyle bu kapsama ait iletileri siler. Karışık atomik işlemler, eski zarfı belirsiz iletiler, bilinmeyen sahiplik, desteklenmeyen düzen veya kanıtlanamayan geçmiş için kapılar kapalı kalır ve `INCOMPLETE` döner. Compaction payload'ları sahiplik işaretlerinden önce temizlenir. Native kimlik temizliği gerçek sayfa/indeks/projection tanımlarını kullanır ve yerel durumu geçersizleştirir. Tamamlama için bağımsız checkpoint'leri olan ikinci bir ad alanı/sahiplik/ortak üyelik/sayfa/stream taraması, kaynak sorgusu ve katılımcı doğrulaması gerekir. Ardından geçici ham kimlikler, tarama konumları ve kuyruk payload'ları sınırlı SQL transaction'larıyla kaldırılır.

## Kaynak Seçimi

- `includeSource=false`, yalnızca Redis/projection/indeks ve sahipliği doğrulanmış kuyruk iletilerini temizler. Kanonik SQL DELETE çalıştırmaz veya kuyruğa yazmaz. Kaynak satırları kalır, `sourceRows` sıfırdır ve son durum `MIGRATION_REQUIRED` olur; kabul kapıları açılmaz.
- `includeSource=true`, kapanıştan sonra tam tenant eşleşmesiyle ve uygun indeksle en fazla 128 kaynak satırını fiziksel olarak siler. Kaynak DELETE ve kalıcı ilerleme/sayaç güncellemesi aynı guard kilitli transaction'da commit edilir. `COMPLETE` durumunda bile tenant kapalı kalır.

Silmenin tamamlanması, yeniden açma izni değildir. Korunan kaynak veya geçmiş, sessizce yeni generation verisine dönüştürülemez. Migration; Redis ve kalıcı version/tombstone üst sınırlarını çıkarmalı, ad alanı + tenant çiftinin güvenlik üst sınırını korumalı, sürüm aralıklarını yalnızca yönetim veya arka plan yoluyla ayırmalı ve bütün replay/harici olayların özgün generation bilgisini taşıdığını kanıtlamalıdır. Doğrulanamayan yeniden açma reddedilir. Sürüm üst sınırı 9007199254740991'dir; tükenme veya kurtarma belirsizliği sayaçları geri almaz ve sahte receipt üretmez.

### Boş Tenant'ı Açık Onayla Yeniden Açma

`reenableEmptyTenant`, `includeSource=true` ile yapılan silme `COMPLETE` durumuna ulaştıktan sonra verilen ayrı bir yönetim kararıdır. İncelenmiş tek bir entity ad alanını ve açılış kapsamını doğrulayabilen kayıtlı çalışma zamanı yollarını destekler. Yedekten dönmez, eski verinin sahipliğini devralmaz, korunmuş satırları yeni veri saymaz ve bağımlı ad alanlarını kendiliğinden açmaz.

`ACTIVE`, işlem kabulünün yeniden açıldığını doğrular; kullanıcının verdiği kayıt kimlikleri için sahiplik izni oluşturmaz. Silme işlemi, temizlenen kayıtların sahiplik işaretlerini de kaldırır; yeniden açma bunları geri getirmez. SQL ve Redis kapıları OPEN olsa bile `save` ve `saveWithReceipt`, sahipliği doğrulanmamış kimliği `MIGRATION_REQUIRED` ile reddeder. Desteklenen tek Redis sunuculu, Long kimlikli INITIAL profilinde [sınırlı yeni kayıt hazırlama](tenant-protection-components.md#sınırlı-bir-yeni-kayıt-aralığı-hazırlama) işlemiyle, yalnız bu ad alanına ayrılmış yeni bir aralığı ayrıca hazırlayın. Bu bir yönetim adımıdır; rastgele veya daha önce silinmiş kimlikleri oluşturma izni değildir. Redis'e elle sahiplik işareti eklemeyin veya normal yazma yoluna SQL sorgusu koymayın. Bu sınırlama, tenant veri koruması etkin olmayan sıradan repository'ler için geçerli değildir.

Örneğin A müşterisinin 42 numaralı siparişi silinip EMPTY açılışı tamamlandığında, kuşak denetimi altında boş sonuç okumak mümkündür. Bu işlem, 42 numaralı siparişi yeniden oluşturma veya 43 numaralı siparişi ekleme yetkisi vermez. EMPTY açılışını tek başına müşteriyi silip yeniden oluşturma akışı olarak kullanmayın. [Kaynaktan ilk doldurma](initial-source-bootstrap.md) ve [mevcut kimliklerin sahipliğini hazırlama](source-owner-onboarding.md) işlemleri de genel amaçlı yeni kimlik tahsisi sağlamaz.

İlk çağrıdan önce tek bir açılış UUID'sini, özgün silme seçeneklerini, tamamlama belgesini ve planın tam manifest'ini kalıcı olarak saklayın. Aşağıdaki örnek yalnızca bir sınırlı adımı yürütür. Devam bilgisini sonraki yönetim işi için koruyun; HTTP isteğinde sıkı döngü kurmayın:

```java
var request = new EmptyTenantActivation.Request(
        persistedActivationId, EmptyTenantActivation.Mode.EMPTY,
        options, completedPurge.evidence(), registeredPlan.manifestToken());
try (var admin = CacheTenantContext.administrative()) {
    var next = protection.reenableEmptyTenant(
            Order.class, tenant, request, savedContinuation);
    savedContinuation = next.continuation();
    // İşlem kabulü için ACTIVE gerekir; yazma ayrıca doğrulanmış kimlik sahipliği gerektirir.
}
```

Sonucu belirsiz çağrıdan sonra aynı istekle devam edin. Yönetim lease'inin süresi dolarsa yeni bir açılış UUID'si üretmeyin; aynı istek için yeniden yetki alın. Belge, manifest veya açılış kimliği uyuşmuyorsa işlem reddedilir. İncelenmiş silme/açılış süreci boyunca harici veritabanı yazmalarını durdurun; bu API, CacheDB dışından yapılan yazmaları engelleyemez.

| Kesinti | Devam etme sözleşmesi |
| --- | --- |
| SQL, `PREPARED` durumunu kaydeder | Yeni generation kapalı kalır; aynı açılış sürdürülür. |
| SQL, `SQL_OPEN` durumunu kaydeder; native OPEN henüz yoktur | Özgün sürüm aralığı korunur ve native açılış sürdürülür. Yeni aralık ayrılmaz. |
| Native OPEN başarılı olur, ancak yanıtı kaybolur | Eşleşen native receipt doğrulanır. Yeni satırlar silinmez, sayaç sıfırlanmaz. |
| Tamamlanmış istek yeniden gönderilir | Yeni kontrol epoch'u alınmadan ve sürüm aralığı yeniden kurulmadan aynı `ACTIVE` kararı doğrulanır. |

SQL lease'inin süresinin dolması, Redis'e önceden gönderilmiş bir işlemi anında iptal etmez. Gecikmiş işlem yalnızca aynı, geri alınmayan `SQL_OPEN` kararını ilerletebilir. SQL tarafında tamamlama hâlâ geçerli lease gerektirir; native tarafta eski epoch, yeni epoch ilgili guard'a ulaştıktan sonra reddedilir. Lease süresinin dolmasını geri alma yöntemi olarak kullanmayın.

## İlk Korumalı Ad Alanının Hazırlanması

`CacheInitialNamespace` ve `CacheInitialTenantBootstrap`, korumalı bir ad alanının ilk kez ve açık bir yönetim işlemiyle doldurulmasını sağlar. Bu işlem, silinmiş bir tenant'ı yeniden açmak veya mevcut bir CacheDB ad alanını devralmak değildir. Mevcut bir kaynak tablo ancak sahiplik ve kaynak sürümü sözleşmesi incelendikten sonra bu kapsama alınabilir. Redis'te anahtar bulunmaması, geçmişin güvenli olduğu anlamına gelmez.

Bu sınırlı kullanım modeli; standalone Redis, kapalı L1, `CLIENT_ONLY`, `REDIS_ONLY`, kanonik `Long` kimlikler ve sınırları açıkça belirlenmiş tekil kayıt okuma penceresi gerektirir. İlişkiler, projection'lar, kotalar, Redis Cluster ve sahipliği bilinmeyen eski depolar bu ilk doldurma modeline dahil değildir. Kaynak satırlarının tenant sahipliği kesin olmalı ve sürümleri desteklenen pozitif tam sayılardan oluşmalıdır. Başında `(tenant, id)` ve `(tenant, version)` sütunları bulunan indeksler gerekir. Seçilen pencerenin dışındaki kaynak sürümleri de tenant'ın sürüm üst sınırına dahil edilir.

Bu modeli kullanmadan önce ilgili sağlayıcının kanonik `V014` ve `V019` migration dosyalarını, `V019_6__empty_tenant_activation.sql` dahil olacak şekilde ve sırasıyla kendi migration sisteminiz üzerinden kurun. Sahiplik işareti, OPEN bayrağı, generation izni veya sürüm aralığı oluşturarak doğrulamaları atlamayın.

Kurulum sırası şöyledir:

1. Gerçek metadata ve codec'i kaydedin. Kaynak tabloyu, tenant ve sürüm sütunlarını, kimlik aralığını, satır/sayfa/bellek bütçelerini ve Redis düzenini inceleyin.
2. İlk SQL generation kaydını CLOSED durumunda hazırlayın. Ad alanını hazırlayıp descriptor'ını inceleyin. SQL tabanlı ad alanı kapısı, Redis runtime listesi oluşturulmadan önce tek kullanımlık ilk kurulum iznini tüketir. Redis'teki liste kaybolduğunda bu mekanizma otomatik kurtarma yolu olarak kullanılamaz.
3. İşleme katılan bütün runtime'ları kaydedin. Gerçek runtime kontrol katılımcısını, ilk native pencere katılımcısını ve `JdbcInitialBootstrapPurgeParticipant` sınıfını aynı incelenmiş purge planına ekleyin. Runtime listesini ve manifest'i tam kapsamıyla onaylayın. Hiçbir katılımcı, tanımlanmamış bir deponun yerine geçemez.
4. Bootstrap servisini bu ad alanı, runtime kaydı ve onaylı plandan oluşturun. Yönetim yetkisiyle `step(request)` çağrıları yapın; sınırlı devam çağrılarında aynı işlem UUID'sini ve isteği koruyun.
5. Native depo ve kuyruk kapılarının kapanmasını, devam eden okuma/doldurma işlemlerinin bitmesini, gerçek sahiplik/ad alanı ve kalıcı sürüm envanterinin çıkarılmasını, tam tenant eşleşmesiyle kaynak sürümlerinin incelenmesini, kaydedilmiş sayfaların doldurulup doğrulanmasını bekleyin. Normal uygulama trafiğini ancak `step`, `ACTIVE` döndürdükten sonra başlatın. Sonlandırma kesilirse bu son durum kaydedilmeden önce native OPEN korunmuş olabilir.

Sayfa kaydı veya doldurma onayı sırasında kesinti olursa aynı kalıcı iş ve aynı şifreli içerikle devam edilir. Sonucu belirsiz bir çağrı, farklı bir işlem oluşturma veya yeni bir sürüm aralığı ayırma izni sayılmaz. Normal korumalı okumalar yalnızca Redis üzerinden çalışır; pencere dışındaki kayıtlar SQL'den sessizce alınarak önbelleğe eklenmez.

### Kesintilerde Davranış

| Kesinti | Beklenen davranış |
| --- | --- |
| Sayfa kaydının veya uygulanmış sayfanın onayı kaybolur | Aynı istek ve commit edilmiş sayfayla devam edilir. İçerik çoğaltılmaz veya yeniden şifrelenmez. Aktivasyona kadar kabul kapısı kapalı kalır. |
| SQL aktivasyonu commit edilir, ancak native OPEN oluşmaz | Redis CLOSED kalır; özgün aktivasyon ve sürüm aralığı korunur. `RECOVERY_REQUIRED` döner. Otomatik yeniden açma bilerek desteklenmez. |
| Native OPEN oluşur, yalnızca sonlandırma kesilir | Ancak aynı özgün OPEN doğrulandıktan sonra sonlandırmaya devam edilebilir. Kapı yeniden kapatılamaz; yeni aralık ayrılamaz veya kurulamaz. |
| Bilinmeyen generation, tüketilmiş ilk kurulum izni veya tamamlanmış purge görülür | İşlem güvenli biçimde reddedilir. Yeni işlem UUID'si, Redis listesinin kaybı veya kaynak satırlarının korunmuş olması yeni bir ilk kurulum yetkisi vermez. |

`CacheInitialSourceBootstrapRedisTest`; PostgreSQL, MSSQL ve Oracle üzerinde ilk doldurma, commit edilmiş sayfanın onay kaybı, korumalı erişim, kaynak/native purge ve belirsiz aktivasyonda kapalı kalma senaryolarını kapsar. Bu sınırlı model; her türlü eski sistem geçişini, yedekten dönüşü veya uygulamanın kurulduğu altyapıyı doğrulanmış saymaz.

## Kullanım

Runtime; incelenmiş bir `RedisTenantPurgePlan`, entity registry, sonlu timeout'ları olan sahipli transport'lar ve doğrulanmış dağıtık `TenantReadFence` sağlar.

```java
var protection = new CacheDataProtection(registry, List.of(registeredPlan), 2);
CacheDataProtection.Continuation continuation = null;
var options = new PurgeOptions(operationId, tenant,
        ProtectionKey.hash(operatorReference), true, 128, 120, 30_000,
        PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
try (var admin = CacheTenantContext.administrative()) {
    CacheDataProtection.Result result =
            protection.purgeTenant(Order.class, tenant, options, continuation);
    continuation = result.continuation(); // son durumda null
    // RUNNING sürerken aynı operationId ve continuation ile sürdürün.
}
```

RUNNING sonucu, thread veya bağlantı değil, açık ve süreli bir yönetim lease'i tutar. Dağıtık onayların aynı owner/epoch üzerinde kalması için sonraki sınırlı çağrıya continuation değerini geçirin. Aynı continuation'ın eşzamanlı kullanımı kalıcı çağrı kilidiyle reddedilir. Continuation kaybolursa aynı işlem UUID'siyle lease süresinin dolması ve güvenli takeover gerekir. `abandon(continuation)`, kabul kapılarını açmadan yönetim sahipliğini bırakır.

İstek thread'inde sıkı döngü kurmayın. `BUSY` veya geçici depolama hatasında sınırlı yeniden deneme ve bekleme uygulayın; aynı işlem UUID'siyle sürdürün. Sonucu belirsiz işler aynı kalıcı konumdan tekrar denenir. Sayaç ve konum, Redis sonuç onayından önce commit edilir. Lease kaybı veya iptal, kabul kapılarını açmaz. Facade'ın gizli executor veya scheduler'ı yoktur; eşzamanlı yönetici sınırı dolduğunda isteği reddeder. `maxCallMillis`, zaten çalışan fiziksel I/O'yu zorla sonlandırmaz; işbirlikçi drain adımını sınırlar. JDBC/socket timeout'ları ayrıca zorunludur ve kapasite ancak fiziksel I/O bittikten sonra bırakılır.

`Result.queueEntries`, bütünüyle sahip olunan stream iletilerini fiziksel Redis anahtarı ve kaynak satırı sayaçlarından ayrı tutar. Receipt ve audit; ham tenant kimliği, payload veya tarama cursor'u yerine hash'lenmiş kapsam/tenant/operatör token'larını içerir. Onay, yalnızca o istekte kullanıcının verdiği tenant ile tam eşleşir.

## Sınırlar

Native plan; en fazla 64 entity, entity başına sekiz kimliği koruyan Redis hedefi, açıkça yönlendirilmiş 128 stream, hedef başına 128 tanımlı sayfa, parça başına 128 öğe ve 1 MiB bekleyen yanıtla sınırlıdır. SCAN COUNT 500 yalnızca ipucudur: fiziksel olarak sınırı aşan yanıt kesilip devam edilmez, işlem güvenli biçimde durur. SQL journal'larında satır ve toplam bayt bütçeleri vardır. Cluster'da ad alanı/kimlik temizliği incelenmiş ortak hash slot düzenini gerektirir; kuyruk bariyeri her stream'in gerçek slot'unu ve primary'sini kullanır.

Sahipliği belirlenemeyen ortak planner önbellekleri, karışık projection'lar, bilinmeyen eski depolar veya doğrulanmamış geçmiş düzenler için açık sahiplik/doğrulama adaptörü gerekir; aksi halde sonuç `INCOMPLETE` olur. Başka tenant veya entity verisi sahiplenilmiş varsayılmaz. Rastgele Spring Cache metot değerleri, açıkça sahipliği tanımlı bir katılımcı kaydedilmedikçe bu entity kapsamına girmez.

SQL yazma fence'leri, güvenlik generation/highwater bilgisi ve sınırlı ilerleme/audit tombstone'ları bilerek korunur; güvenlik metadata'sında kimlikler kalabilir. API; yedekleri, veritabanı redo/WAL kayıtlarını, harici dışa aktarımları veya kayıt dışı depoları silmez ve KVKK/GDPR uygunluk sertifikası değildir. Yerleşik indeks üzerindeki retention taraması ayrı bir özelliktir; bütün soğuk SQL geçmişini kapsamaz.
