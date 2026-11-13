# Açık Kiracı Çalışma Zamanı Kaydı

Bu kayıt, gerçek `CacheDatabase` üreticilerini, okumalarını ve yerel önbelleklerini açıkça
onaylanmış bir dağıtım listesine bağlar. Uygulamanın tamamında kişisel veri keşfi veya
kaynak sahipliği hazırlığı yapmaz. İlk desteklenen profil, `CLIENT_ONLY` ve `REDIS_ONLY`
birlikte kullanılarak kanonik işlevlerin çalıştırıldığı; ilişki, ön planda kaynak yükleme ve kayıtlı
projeksiyon içermeyen profildir. Desteklenmeyen yollar güvenli biçimde reddedilir.
Korunmayan varlıkların mevcut hızlı okuma yolları değişmez.

## Başlatma Ve Onay

Standart `CacheDatabase.configureTenantProducer` çağrısı ve oturum yolu, üretici nesnesini
döndürmeden ÖNCE MEVCUT ve yetkilendirilmiş ad alanına ait yeni çalışma kimliğini kalıcı olarak kaydeder.
`prepareTenantProtection` da aynı kayıt yolunu kullanır; ayrı bir üyelik yetkisi oluşturmaz.
Üretici ile gerçek okuma/L1 çiti aynı UUID değerini paylaşır. Üretici yapılandırılmadan ham
repository almak veya üreticiyi onaylamadan yapılandırmak normal okuma/yazma kabulünü açmaz.

Normal yapılandırma eksik bir üyelik kaydı OLUŞTURMAZ. Başlatılmış kaydın kaybolması, TTL
nedeniyle silinmesi veya kaldırılması açık bir kurtarma işlemi gerektirir. Redis'te kaydın
olmaması yeni bir ad alanını kanıtlamaz. Yeni kiracı veya süreç, ad alanının kalıcı ilk
başlatma kaydını sıfırlayamaz.

Ad alanı kaydı, en çok 128 çalışma kimliği içeren kalıcı bir şema-2 hash kaydıdır. İlk onaydan
önce `COLLECTING` durumundadır ve korunan normal okumalar ile yazmalar kapalıdır. Atomik
mühürleme, verilen üye adlarını yalnızca kendi içinde denetlemekle yetinmez: onaylanan listeyi
kaydedilmiş üyelerin TAMAMIYLA karşılaştırır ve bildirilen bütün CLOSED çiftlerini doğrular.
Gerçek bir örneği listeden çıkarmak mühür yayımlanmadan reddedilir. Bilinmeyen şema, ek alan,
beklenmeyen TTL veya değişmiş Redis çalışma kimliği güvenli biçimde reddedilir.

Bütün korunan varlıkları önce kaydedin. Herhangi bir repository alınmadan önce yönetim
bağlamında `database.prepareTenantProtection(maxInFlight)` çağrısını yapın. Yeni bir ad
alanında önce aşağıdaki tek kullanımlık başlatma işlemi tamamlanmalıdır. Hazırlık,
önce bütün üreticileri yapılandırır; ardından gerçek repository ve okuma/L1 katılımcılarını
oluşturur. Kısmi hazırlık başarısız olursa o veritabanı örneğini kullanmaya devam etmeyin.

Gerçek `RedisTenantPurgePlan` için bu kaydın `barrier`, `readFence`, `pointProfileTarget`
ve `controlParticipant` nesnelerini kullanın. Katılımcı bildirimine `runtime-control-roster`
ekleyin. Noktasal profilin boş sayfa listesi, geçmiş sayfaların veya başka depoların hiç
olmadığını kanıtlamaz. Ek depolar için ayrı, incelenmiş bir profil veya adaptör gerekir.

Yönetici; gerçek yerel okuma çitlerinin UUID değerlerini, plan parmak izini, kapsam belirtecini
ve eksiksiz fakat sınırlı kiracı çifti listesini açıkça onaylamalıdır. Her gerçek çalışma
zamanında `ApprovedRoster` ile `sealClosed(plan, approval, closedPairs)` çağrılır. Listedeki
bütün çiftlerin geçerli CLOSED Redis bakım kiralarına sahip olması gerekir. Doğrulama ve
mevcut listenin mühürlenmesi, nesil çiti kullanılarak tek atomik işlemde gerçekleştirilir.

Bu işlem SQL kabulünü açmaz veya doğrulamaz; sahiplik işareti, sürüm aralığı ya da kaynak
geçişi belgesi üretmez. `SEALED` sonrasında kaydedilen yeni örnek/pod kimliği onaysız olarak
kalıcı tutulur ve ad alanı atomik olarak `REVIEW_REQUIRED` durumuna geçer. Yeni örneğin ham
okuma/yazma yolları açılamaz; mevcut üyelerin normal kabulü de kapanır ve eski kapanış kanıtı
geçersizleşir. Kabul, kanonik korunan FCALL dahil gerçek Redis yakalama, yayımlama ve değişiklik
komutunun içinde denetlenir. Önceden alınmış bir bilet sonraki üyelik değişimini aşamaz.
Okuma/L1 tamamlanması kabulü yeniden doğrular; yalnızca ön denetim yapılması yeterli değildir.

Kayıt ve onay girdilerinde TTL yoktur. Sürecin kapanması, kökün kapatılması, eksik ACK, süre
dolması veya başarısız hazırlık üyeyi silmez. **Bu ilk API; üye emekliye ayırmayı, yeniden
onaylamayı, kademeli yeniden başlatmayı veya esnek üyeliği DESTEKLEMEZ.** Geç katılım için
ayrıca doğrulanmış CLOSED kurtarma/geçişi gerekir; burada yerinde kurtarma komutu sunulmaz.
Otomatik örnek değişiminin kesintisiz çalışması gereken dağıtımlarda bu sabit üyelik profilini
kullanmayın. Hizmeti açmak için kontrol anahtarlarını silmeyin veya değiştirmeyin. Ölü/eksik
üye çoğunluğu azaltma gerekçesi değil, çözülmesi gereken kalıcı kanıttır.

Eksiksiz katılım, bu kabul protokolünü kullanan standart korunan örnekleri kapsar; eski
ikili sürümler, rastgele Redis istemcileri, özel adaptörler ve ilgisiz uygulama önbellekleri
kapsam dışıdır. İlk etkinleştirmeden önce eski veya karma sürümlü korunan yazıcılar durdurulup
çitlenmelidir. Uygulamanın onay geri çağrısı onların yokluğunu kanıtlayamaz. Kayıt, kaynak
sahipliği belgesi oluşturmaz ve bütün geçmiş veri depolarının envanterini doğrulamaz.

## Ad Alanının İlk Başlatılması

İlk başlatma, gerçek kök üzerinde açıkça yürütülen bir yönetim işlemidir. Önce kullanılan
sağlayıcıda kiracı/INITIAL metadata şemasını kurun. Gerçek SQL koruma kaydını INITIAL
başlamadan önce CLOSED olarak hazırlayın. Nesil henüz kullanılmamış olmalıdır: kira
sayacı sıfır, işlem kimliği boş, önceki INITIAL veya silme günlüğü yok. Ad alanının gerçekten
yeni olduğu ayrıca incelenip onaylanmalıdır; boş Redis veya eksik SQL satırları bu kararı
uygulamanın yerine vermez.

Herhangi bir repository alınmadan önce `database.prepareInitialNamespace(metadata,
nativeBinding, guard, ids, firstId, lastId, maxRows, pageBytes, rowBytes, residentBytes,
maxInFlight)` kullanın. Bağlama, kökün kanonik yerleşimine; koruma nesnesi ise gerçek
DataSource'a ait olmalıdır. Şifreli alanlar yapılandırılmış gerçek anahtar sağlayıcısını ve
kayıtlı, çalışma zamanına bağlanmış codec'i gerektirir. İlk profil yalnızca kanonik Long
kimliklerini kabul eder. Bileşik/bölümlü indeksler, ilişkiler, projeksiyonlar ve yol kotası
rezervasyonları desteklenmez.

Yardımcının `window()` ve `descriptor()` değerleri incelenebilir plan kimlikleridir;
oluşturma yetkisi değildir. Descriptor içindeki `namespaceFingerprint()` değerini açıkça
onaylayın ve AYNI gerçek koruma nesnesiyle `JdbcNamespaceBootstrapGate` oluşturun.
INITIAL kiralaması alınmadan ÖNCE `namespace.bootstrap(gate, generation)` çağrılır.
Kök; gerçek kaynakları, kanonik üyelik anahtarını, alan eşlemesini ve Redis çalışma
kimliğini SQL tüketiminden önce doğrular. SQL, kalıcı tek kullanımlık ad alanı kaydını
commit eder; ardından özel yerel başlatıcı üyelik kaydını oluşturur. Bu işlemle gerçek
üretici ve okuma çiti aynı UUID değerini kullanır. Belirsiz commit, kayıp Redis yanıtı veya
kısmi başlatma kurtarma gerektirir. Kalıcı kaydı silmeyin; başka kiracıyla yeniden başlatma
hakkı üretmeyin ve hizmeti açmak için üyelik kaydını değiştirmeyin.

Sonra bütün gerçek çalışma zamanlarını hazırlayın ve kaydedilmiş üyelerin tamamını
onaylayın. Gerçek plana tam çalışma zamanı katılımcısını, bu yardımcının penceresi için
`enrollment.initialSourceParticipant` sonucunu ve eşleşen
`JdbcInitialBootstrapPurgeParticipant` nesnesini ekleyin. SQL katılımcısının adı
`initial-source-journal`, yerel katılımcının adı `initial-native-window` değeridir.
İncelenen bildirim bütün gerçek depoları ve kuyrukları kapsamalıdır. Bu katılımcılar geçmiş
envanterin veya kaynak sahipliği kanıtının yerine geçmez.

Somut koordinatörü `namespace.createBootstrap(enrollment, plan, approval, rangeSize,
reviewedWindowFingerprints)` ile oluşturun. Sınırlı `step(request)` çağrılarında aynı işlem
kimliğini kullanın. `namespace.pendingClosure()`, yalnızca gerçek koordinatörün kurduğu
kirayı gösterir; diğer kökler aynı CLOSED onayını açıkça mühürleyip kendi kontrollerini
yoklayabilir. `BUSY`, fiziksel teslim bittikten sonra aynı sınırlı aşamanın yeniden
denenmesini gerektirir. Ölü veya eksik bir çalışma zamanı örtük ACK sayılmaz.

INITIAL seçilen kaynak yükünü bir kez yakalar, CLOSED durumunda şifreli Redis değerlerini
ve kör indeksleri kurar, commit edilmiş bütün sayfaları yeniden doğrular ve çitlenmiş sürüm
aralığını yükler. SQL OPEN commit işlemi, koşullu Redis OPEN işleminden önce tamamlanır.
Yerel yükleme iş kaynak tablosuna DML uygulamaz, yalnızca sahiplik işareti yazmaz, kuyruk
oluşturmaz ve ön planda SQL geri dönüşü kullanmaz. Etkinleştirme yanıtı kaybolursa aynı
commit edilmiş etkinleştirme kurtarılmalıdır; zaten OPEN olan aralık yeniden kurulmaz veya
geriye alınmaz. Desteklenmeyen profil ve eksik envanter kapalı kalır. Yönetim süre bütçesi,
fiziksel taşımanın zaman aşımını değiştirmez; özel bağlantı fabrikasında kesin toplam süre
garantisi vermez.

## Sınırlı Kontrol Yoklaması

Her gerçek örneğin yönetim döngüsünden `pollControl(maxRequests, maxMillis)` çağrılır;
bu gereklilik yalnızca istemci olarak çalışan dağıtımlar için de geçerlidir. Katalog,
veritabanı örneği başına en çok 64 çift içerir. Tek çağrı 1..64 istek ve 1..5000 milisaniye
kabul eder. Yoklama, açıkça belirtilmiş çiftler üzerinde dönüşümlü ilerler; Redis SCAN ile
anahtar alanını veya rastgele kiracı verilerini taramaz. İstek ve kira yetkisi mevcut
çift bazlı kapatma günlüğünde kalır.

Yeni thread, Redis adresi, bağlantı havuzu, SQL sorgusu veya otomatik yeniden deneme
oluşturulmaz. Süre bütçesi fiziksel komutlar arasında denetlenir; soketi kesin toplam süre
sonunda kapatma garantisi değildir. Gerçek taşımanın bağlantı bekleme ve okuma sürelerini
sınırlayın. İncelenen, yerel ACK verilen, bekleyen ve eksik kapsam sayaçlarını ham kiracı
etiketleri kullanmadan izleyin. Bekleme veya taşıma hatasında geri çekilerek sınırlı yoklamayı
aynı yönetim işlemi kapsamında yineleyin; eksik üyelik kanıtını silmeyin.

`locallyAcknowledged`, dağıtık kapanış anlamına gelmez. Eksik veya eskimiş ACK, işin fiziksel
olarak bittiğini kanıtlamaz. Bilinen fakat bekleyen üyelik DRAINING kalır; `requireClosed`
bu durumu `INCOMPLETE_INVENTORY` ile reddeder. Belirsiz üretici sonucu, bilinmeyen kapsam,
bozuk şema veya Redis çalışma kimliği değişimi CLOSED belgesi üretemez.

## Sahip Olunan Teslim Sınırı

Onaylanan uygulama yollarında `enrollment.deliver(metadata, tenant, read, delivery)` kullanın.
Senkron `delivery` geri çağrısı, sahip olunan gerçek teslim tamamlanmadan dönmemelidir.
Buradan başka bir asenkron işe geçmeyin; yanıt yazıcısını, future değerini veya sonucu daha
sonraki teslim için dışarı taşımayın. Bu yollar ayrı fiziksel tamamlanma entegrasyonu gerektirir.

Bilet, tüketicinin fiziksel çalışması boyunca tutulur. Çağıranın beklemekten vazgeçmesi veya
kabulün kapatılması bileti erkenden serbest bırakmaz ve kapanış kanıtı oluşturmaz. Kaynak ve
yanıt taşıması kendi sonlu G/Ç süresi ile yanıt boyutu sınırlarını uygulamalıdır.

Ham repository nesnesinin nesil denetimi; daha sonraki JSON üretimini, ağ teslimini veya
üçüncü taraf önbelleklerini onaylamaz. Kapsam yalnızca bildirilen örnekler ve yollardır;
kapatmadan önce teslim edilmiş kopyalar geri alınamaz. `controlStoreKey`, yönetim envanteri
için gerçek güvenlik deposunun anahtarını verir. Katılımcı paylaşılan listeyi doğrular;
anahtar veya başka kiracı katkısı silmez. Kaynak sahipliği hazırlığı, geçmiş depo envanteri,
genel Spring Cache sahipliği ve uçtan uca kaynak silme ayrı sözleşmelerdir.
