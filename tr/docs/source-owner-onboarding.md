# Mevcut Kimlikler İçin Sahiplik Hazırlığı

Korunan bir ad alanında, doğrulanmış sahibi bulunmayan kimliğin yazılması reddedilir.
Sahip kaydının bulunmaması, kimliğin yeni olduğunu kanıtlamaz: kimlik, soğuk SQL
geçmişinde başka bir kiracıya ait olabilir.

`CacheDatabase.prepareSourceOwnerOnboarding`, sınırlı bir aday iş listesi için
yönetimsel ve kaldığı yerden sürdürülebilir bir hazırlık akışı sağlar. Kapsamı,
mevcut Redis kalıntılarını ve gözlenen yoklukları içerir. Tüm SQL geçmişinin
envanterini çıkarmaz; çağıran için kimlik ayırmaz, önbellek kaydı oluşturmaz,
kaynak satırlarını silmez ve erişimi açmaz. WARMED durumu yalnızca bu iş listesini
kapsar.

## Ön Koşullar

Yetkiyi HTTP parametresinden türetmeyen, güvenilir bir yönetim giriş noktası kullanın.
Gerçek ad alanı/kiracı nesil korumasını CLOSED durumunda hazırlayın. Repository
nesnelerini yayımlamadan önce varlığı kaydedin ve gerçek çalışma zamanı kaydını
hazırlayın. Tanımlanmış üreticiler, okumalar, doldurma işlemleri ve kuyruk/yeniden
oynatma yolları gerçekten kapanıp boşalmalıdır.
Bilinmeyen veya özgün nesil damgası taşımayan dış yollar desteklenmemiş ya da eksik
kalmalıdır. Boş bir çalışma zamanı listesi kapanış kanıtı değildir.

`CacheDatabase.prepareSourceOwnerOnboarding`, gerçek köke bağlı yerel
`ClosedRuntime` adaptörünü sağlar; uygulamanın bunun yerine bir callback yazması
gerekmez. Desteklenen noktasal erişim profili, **birlikte** `CLIENT_ONLY` çalışma
rolünü ve `REDIS_ONLY` okuma modunu, standalone Redis üzerinde gerektirir. Ön plan
ve arka plan işlemleri aynı gerçek `JedisPooled` istemcisini kullanmalıdır. L1;
varsayılan, varlık ve annotation politikalarında kapalı olmalıdır. Adaptör; kökün
gerçek JDBC korumasını, kayıtlı kök listesini, nesil bariyerini, okuma bariyerini,
kuyruk yollarını ve kanonik Redis düzenini bağlar. Ek dış depo katılımcıları,
ilişkiler, projeksiyonlar, bileşik indeksler ve ek korunan depo katılımcıları
desteklenmez. Yalnızca kütüphanenin String/Long kimlik codec'leri kullanılır;
eşleşen bir biçim belirteci, özel JDBC çözümleme callback'lerini yetkilendirmez.

Kaynak tabloda, kiracı sütunuyla başlayan incelenmiş bir indeks, tüm ad alanında
benzersiz ve boş olamayan tek sütunlu kimlik ile tam sayılı sürüm sütunu gereklidir.
SQL Server kiracı sütunu NVARCHAR olmalıdır. PostgreSQL UTF8, Oracle AL32UTF8
kodlaması ve VARCHAR kiracı sütunu gerektirir. Mevcut koruma bu kiracı koşullarını
doğrular. Okuyucu, aynı tam bayt eşitliği koşulunu kullanır; dönen kiracıyı ve
kanonik kimliği ayrıca Java'da birebir karşılaştırır. Büyük/küçük harf ve sondaki
boşluklar dönüştürülmez.

## Yönetim Çağrıları

Seçilen JDBC sağlayıcısı için V019_3__source_owner_onboarding.sql dosyasını V019,
V019_1 ve V019_2 sonrasında açıkça kurun. Çalışma zamanında otomatik DDL yoktur.
JDBC okuyucusunu oluşturmadan önce kaynak eşlemesinin ve sahiplik planının
parmak izlerini inceleyin.

Bu Long kimlik örneğinde uygulama; `database`, `metadata`, `enrollment`,
`nativePlan`, `approvedRoster`, `existingCandidates`,
`reviewedOwnershipPlanFingerprints`, `generation`, `tenant`, `operationId` ve
`operatorToken` değişkenlerini güvenilir yönetim yapılandırması üzerinden önceden
hazırlamıştır. Metadata, çalışma zamanı kaydı ve yerel plan aynı kayıtlı veritabanı
köküne ait olmalıdır. İncelenmiş parmak izi kümesi, tam sahiplik planını
yetkilendirmelidir; yeni hesaplanan bir parmak izini yalnızca istek tarafından
sunulduğu için onaylamayın. Onaylı liste, çağıranın kapanış beyanını değil, tüm
gerçek kayıtlı kökleri belirtir. İlk adımdan önce işlem kimliğini kalıcı olarak
kaydedin; sürdürürken aynı isteği ve iş listesini koruyun.

~~~java
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.JdbcSourceOwnerOnboarding;
import com.reactor.cachedb.starter.CacheSourceOwnerOnboarding;

// Uygulamanın yönetim metodu içinde, yukarıdaki yapılandırılmış girdilerle.
CacheSourceOwnerOnboarding<Long> bootstrap;
SourceOwnerOnboarding.Request request;
try (var administrative = CacheTenantContext.administrative()) {
    var ids = JdbcSourceOwnerOnboarding.longIds();
    bootstrap = database.prepareSourceOwnerOnboarding(
            metadata, enrollment, nativePlan, approvedRoster, ids,
            existingCandidates, reviewedOwnershipPlanFingerprints);
    request = new SourceOwnerOnboarding.Request(
            bootstrap.plan(), generation, tenant, operationId, operatorToken, 30);
}
~~~

Fabrika metodu yapılandırmayı doğrular; hazırlığı tamamlamaz.
`bootstrap.plan()` isteğin planıdır. `bootstrap.worklistToken()`, aday
yapılandırmasını tanımlar; **tamamlanmış bir çalışma zamanı envanter mührü değildir**.

Bir adım devam eden işlemlerin bitmesini beklerken, her kayıtlı kök
`bootstrap.pendingClosure()` ile sunulan **aynı fiziksel kiralamayı** mühürlemelidir.
Kendi yönetim döngüsü, o kökün çalışma zamanı kaydını ve yerel planını kullanarak
`enrollment.sealClosed(nativePlan, approvedRoster, List.of(closedPair))` çağrısını
yapar; ardından `enrollment.pollControl(1, 100)` çağrılarını sürdürür. Kökler ayrı
süreçlerdeyse gerçek kapalı çifti güvenilir bir yönetim kanalı üzerinden iletin.
Başka bir kiralama üretmeyin; liste onayını devam eden işlemlerin bittiğine dair
onay saymayın. Diğer köklerin bu döngüleri, başlatıcı adımla eşzamanlı çalışmalıdır;
sonraki bir adım yeni kiralama alıp güncel onaylar gerektirebilir.

Her zamanlanmış yönetim çağrısında tek sınırlı adım çalıştırın; sıkı döngü kurmayın:

~~~java
try (var administrative = CacheTenantContext.administrative()) {
    var checkpoint = bootstrap.step(request);
    // FENCING/RUNNING: isteği koruyup başka bir sınırlı adım zamanlayın.
    // WARMED: bu aday iş listesi hazırdır; erişim CLOSED kalır.
    // INCOMPLETE: belirsiz sahipliği inceleyin; erişim CLOSED kalır.
}
~~~

Zaman aşımı veya hatada işlem/istek bilgisini koruyun; başka bir sınırlı denemeden
önce nedeni inceleyin. Eksik kapanışı veya değişen mührü aşmak için günlüğü atmayın,
iş listesini değiştirmeyin ve yerine yeni bir işlem oluşturmayın.

Aday girdisi en fazla 4096 farklı kanonik kimlik, toplam 1 MiB kimlik baytı ve
128 sayfa içerir. Her sayfa en fazla 128 kimlik / 128 KiB içerir; planın daha düşük
sınırlarına da uyar. Bu yalnızca yapılandırılmış aday iş listesidir; ad alanının,
SQL geçmişinin, dış depoların veya yeniden oynatma geçmişinin tam envanter kanıtı
değildir. Fabrika metodunda gizli SCAN, wildcard envanteri veya arka plan yürütücüsü
yoktur.

FENCING sırasında yerel komut; işlem/nesil ve gerçek Redis çalışma kimliği altında
her adayın kalıntı varlığını, türlerini, sınırlı veri/metadata parmak izlerini,
sürümlerini, silme işaretlerini, sahiplerini ve mutlak sona erme zamanlarını mühürler.
Her sayfada incelenen değerler en fazla 1 MiB (veya planın daha düşük bayt sınırı)
ve 128 bilinen metadata alan adıyla sınırlıdır. Değer okunmadan önce uzunluk
kontrolleri yapılır; bilinmeyen alanlar, boş olmayan kuyruk geçmişi ve kimlik bazlı
compaction geçmişi güvenli biçimde reddedilir. Çok sayfalı mühürleme kesilirse
tamamlanmış sayfalar korunur. Yeniden deneme bunları karşılaştırır; değişmiş
kalıntıların üzerine yazamaz veya silinmiş, kaydedilmiş bir sayfa mührünü yeniden
oluşturamaz.

Her adım, ilk Redis sahiplik kurulumundan önce en fazla 128 kanonik kimliği kalıcı
olarak kaydeder. Tek indeksli kaynak sorgusu en fazla 129 satır döndürür; ek satır
sınır aşımı sayılır. Girdi kimlikleri, günlük boyutu, sorgu süreleri ve eşzamanlı
yönetim çağrıları sınırlıdır. Redis köprüsü, ayrıştırmadan önce ağ girdisini
sınırlamalıdır; SCAN COUNT kesin yanıt sınırı değildir. Kaydedilmiş bir sayfanın
yinelenmesi idempotenttir. Başarısız kurulumda bekleyen sayfa korunur ve
imleç ilerlemez.

Yalnızca birebir ve tek anlamlı kaynak sahipliği, Redis'te zaten var olan kimlik
için kurulum yetkisi verebilir. Köprü; nesli, dağıtık kapanışı, kiralama sahibini,
mevcut kalıntıları ve başka kiracıya ait sahiplik çakışmalarını atomik olarak yeniden
doğrular. Yerel sahip yazımı, aynı atomik komutta FENCING kalıntı mührünü de
karşılaştırır. İdempotent sayfa kayıtları, SQL sahiplik/sürüm kanıtlarını ve kurulum
sonrasındaki tam durumu bağlar. Yeniden denemeler silinen sahip kaydını onarmaz;
değişmiş veri, sürüm, silme işareti veya sona erme zamanını sessizce yeniden
mühürlemez. Eksik mühür, eksik kanıttır; kurulum sırasında yeni bir belge üretme
izni değildir. Eksik kaynak satırı, bilinmeyen geçmiş veya belirsiz sahiplik bir
sahiplik iddiasına izin vermez. Bu adım veri, sürüm, TTL veya silme işareti üretmez
ya da değiştirmez. Redis kalıntısı bulunmayan kimlik yok olarak kalır; bu API onu
oluşturmaz.

## Sınırlar Ve Kurtarma

WARMED veya INCOMPLETE sonrasında SQL koruması CLOSED kalır. İlk açılış için özgün
yeniden oynatma geçmişi, kalıcı sürüm üst sınırı/aralığı ve dağıtık görünürlük ayrıca
doğrulanmalıdır; WARMED bu doğrulamaların belgesi değildir. Bu hazırlık, OPEN
kiracıları ve mevcut temizleme geçmişini reddeder. Yalnız Redis'i temizleyen bir
işlemden sonra kiracıyı yeniden açamaz.

Normal yazma yolunda SQL ile kimlik ayırma yoktur. Soğuk SQL satırları, büyüyen bir
Redis sahiplik geçmişine kopyalanmaz. Bilinmeyen çağıran kimlikleri, ayrı
[sınırlı yeni kimlik hazırlığı](tenant-protection-components.md) kapsamında açıkça
hazırlanmadıkça reddedilir. Bu yönetim işlemi, yalnız ilgili ad alanına ayrılmış sayısal
bir kimlik aralığı gerektirir ve silinen kimlikleri yeniden kullanmaz. Sahiplik hazırlığı
API'si kendi başına yeni kayıt ekleme yetkisi vermez. Bu API'leri genel ekleme veya tüm
geçmişi hazırlama kestirmesi saymayın.

Günlük, ad alanı/kiracı çifti başına tek sınırlı satır tutar; en fazla bir bekleyen
kimlik grubu ile takma adlı işletmen/ilerleme verilerini içerir. Tamamlanan
sayfaların kimlikleri günlükten çıkarılır. Korunan güvenlik verileri, yedekler,
diğer varlıklar, genel Spring Cache değerleri ve tanımlanmamış dış depolar bu API
ile silinmez veya doğrulanmaz. Evrensel kişisel veri silme ya da mevzuat
sertifikasyonu iddiası yoktur.
Son durum kayıtları ve korunan envanter belirteci, tarihsel iş listesi sonuçlarıdır;
güncel kayıtlı kök listesi, işlemlerin bitmesi veya erişim açılışı belgesi değildir.
Bilinmeyen sahiplik, sahiplik iddiası olmadan INCOMPLETE ile sonuçlanır ve her
sonuçta erişim CLOSED kalır.

## Yeterlilik Durumu

Yerel adaptör, yukarıdaki profil için uygulanmıştır. Güncel derleme ve gerçek
Redis/JDBC çalışma zamanlarıyla odaklı yeterlilik doğrulaması henüz beklemektedir.
API ve örnek; tam envanter, INITIAL etkinleştirmesi, otomatik OPEN veya
üretim/sürüm kabulü sağlamaz.
