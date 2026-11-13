# Kaynak Veriyi İlk Kez Devreye Alma

[English](../../docs/initial-source-bootstrap.md) | [0.19.0 geçiş rehberi](upgrading-to-0.19.md)

İlk yükleme (`INITIAL`), bir entity ad alanı ve bir tenant için önceden tanımlanmış,
sınırlı bir veri kümesini Redis'e yerleştirir. Bu sırada erişim durumu `CLOSED` kalır.
Koordinatör, SQL tarafındaki kalıcı erişim izni ile Redis'in okumaya ve yazmaya açılmasını
belirli bir sırayla yürütür.

Bu işlem yönetim yetkisi gerektirir. Normal bir okuma isteğinde SQL'e otomatik dönüş değildir;
tenant'ın bütün verilerinin devreye alındığını veya üretime geçişin onaylandığını da göstermez.

## Desteklenen Yapılandırma

İlk yükleme, `CLIENT_ONLY` çalışma rolü ve `REDIS_ONLY` okuma modu gerektirir.
Diğer çalışma rolleri bu koordinatörün doğruladığı kapsamda değildir. Dağıtık worker'lar ve
yeniden oynatma yolları ayrıca envantere alınmalı; kapalı erişim durumuna uydukları doğrulanmalıdır.

`INITIAL`, L1 önbelleği açık bir CacheDB kökünü, Redis Cluster'ı ve özel Redis işlem
adaptörlerini ad alanının ilk oluşturma iznini tüketmeden önce reddeder. Ön plandaki ve arka
plandaki işlemler, aynı standalone havuz istemcisini kullanan standart `JedisPooledOperations`
aktarımı üzerinden çalışmalıdır. Bir Cluster düğümüne havuz istemcisiyle bağlanmak, sistemi
standalone yapmaz.

Genel varsayılanlarda, entity'ye özel kayıtlı politikalarda ve `@CacheNearPolicy` tanımlarında
L1 kapalı olmalıdır. Kayıt değişiklikleri, ilk yükleme öncesinde ve koordinatörün her aşamasında
yeniden kontrol edilir. Desteklenmeyen bir politika sonradan açılırsa işlem güvenli biçimde
kapalı kalır; yeni bir ad alanı izni tüketmez veya açılışa devam etmez.

İlk yükleme, tüm kapsamda benzersiz, NULL olmayan tam sayı `Long` kimlikleri kabul eder.
Fiziksel JDBC tipi, basamak sayısı, ondalık basamak sayısı ve kayıpsız dönüşüm doğrulanır.
String/UUID sıralaması, Java dönüşümlerinden veya veritabanı collation ayarından çıkarılmaz.
Desteklenmeyen bir sıralama için ayrı bir geçiş gerekir.

Kaynak tabloda, ilk kolonları sırasıyla `(tenant,id)` ve `(tenant,version)` olan filtresiz
indeksler bulunmalıdır. Kolon tipleri desteklenen ve boyutu sınırlandırılabilen tipler olmalıdır.
Sürüm değerleri pozitif tam sayı olmalı ve `9007199254740991` değerini aşmamalıdır.
NULL, sıfır, kesirli değer veya taşma hata kabul edilir; sessizce düzeltilmez.
Seçimin ve veri eşlemesinin tamamı, mantıksal silme işaretleriyle birlikte bir parmak izine bağlanır.

Seçilen veri kümesi için üst sınırlar şunlardır:

- Toplam 10.000 satır.
- Sayfa başına 128 satır ve kalıcı olarak saklanan en fazla 128 sayfa.
- Kodlanmış satır başına 1 MiB ve kodlanmış sayfa başına 8 MiB.
- Açıkça onaylanmış, en fazla 64 MiB toplam kodlanmış sayfa verisi.

Bu sınırlar toplam Redis bellek tüketimi değildir. Redis indekslerinin ve doğrulama yapılarının
ek maliyeti de fiziksel bellek bütçesine katılmalıdır. Desteklenen `POINT_NO_QUOTA` profilinde
route/kota rezervasyonu yoktur. Kota kullanan route'lar, ilişkiler, projection'lar ve kaynaktan
otomatik okuma (`read-through`) bu profilde desteklenmez; denetimleri sessizce atlanmaz.

Redis'te tutulacak küme, tüm geçmişin kimlik sahipliği defteri değildir. Seçimin dışında kalan
kaynak satırlar ve çağıranın verdiği yeni kimlikler, açılış tamamlandı diye sahiplenilmiş sayılmaz.

## Ön Koşul: Kaynak Veriye Yazan İşlemler

`INITIAL` başlamadan önce, seçilen kaynak kapsamına yazan bütün işlemleri durdurun veya
bağımsız biçimde engelleyin. Bu sınırı hem açılış hem de uygulamanın yeni yola geçişi
tamamlanana kadar koruyun. Mevcut ORM uygulamaları, stored procedure'ler, içe aktarımlar,
zamanlanmış görevler ve harici CDC/outbox üreticileri de buna dahildir.

SQL nesil koruması, protokole katılan CacheDB worker'larını sınırlar. Başka bir uygulamanın
doğrudan SQL yazmasını engellemez. Redis kuyruğunun boş olması veya veri eşlemesinin incelenmiş
olması, bu yazıcıların durduğunu kanıtlamaz.

Kaynak yeniden doğrulanırken görülen değişiklikler tespit edilir. Ancak denetimsiz bir yazıcının
son kontrolden sonra commit etmeyeceği garanti edilemez. Kaynak yazıcılarının durduğu veya
engellendiği bağımsız olarak doğrulanamıyorsa bu yolu açmayın. İlk yükleme sonucunu üretime
geçiş onayı olarak kullanmayın; koordinatör bu ön koşulu kendiliğinden onaylamaz.

## Yönetim Akışı

1. Seçili veritabanı sağlayıcısının güvenlik migration'larını açıkça uygulayın.
   `INITIAL` için ayrıca sırasıyla `V019_3__source_owner_onboarding.sql`,
   `V019_4__initial_source_bootstrap.sql` ve `V019_5__namespace_bootstrap_once.sql` gerekir.
   Çalışma zamanı kodu şema kurmaz.
2. İlk ad alanı için, Redis çalışma zamanı kayıt listesi oluşturulmadan ve `INITIAL` izni
   alınmadan önce, incelenmiş kalıcı `JdbcNamespaceBootstrapGate` iznini tüketin.
   İzin; tam kayıt listesi anahtarına, veri eşlemesine, Redis run ID değerine ve üretici ile
   okuma korumasının kullandığı aynı çalışma zamanı örneğine bağlıdır. SQL tüketimi,
   Redis'e komut gönderilmeden önce commit edilir. Başlatılmış kayıt listesinin kaybolması,
   süresinin dolması, yanıtın kaybolması veya çökme, onu yeniden oluşturma izni vermez.
   Yalnız SQL kaydının bulunmaması, eski bir kurulumun hiç var olmadığını kanıtlamaz.
3. Ad alanını gerçek `CacheDatabase` kökü üzerinde hazırladıktan sonra
   `CacheInitialNamespace.createBootstrap` kullanın. Bu çağrı kayıtlı metadata'yı, depolama
   codec'ini, istemciyi, anahtarları, korumayı ve incelenmiş planı birbirine bağlar.
   İç factory yalnız framework köküne ait Redis adaptörünü kabul eder. Uygulama kendi
   kapatma veya açılış adaptörünü veremez. Yerel bir `CLOSED` bayrağı, çağıranın verdiği
   Boolean, süreç içi bir map veya boş olduğu beyan edilen liste, dağıtık kapanışı kanıtlamaz.
   Zorunlu katılımcı manifesti, yarıda kalan sayfalar için gerçek
   `JdbcInitialBootstrapPurgeParticipant` katılımcısını da içermelidir.
4. İlk nesil değeri, tenant, işlem UUID'si, operatör token'ı ve süre sınırı olan lease ile
   tek bir `InitialTenantBootstrap.Request` oluşturun. Sınırlı `step(request)` çağrılarında
   bu isteği değiştirmeden yeniden kullanın. Her çağrı, işlem iznini bırakmadan önce
   başlattığı fiziksel I/O'yu tamamlar.

Koordinatör, Redis'e yüklemeden önce şifreli sayfayı kalıcı olarak kaydeder. Aynı şifreli veri,
rastgele nonce değerleri ve anahtarlı satır kanıtı, tekrar denemelerde ve yeniden başlatmalarda
korunur. Korumalı açık veri veya bu verinin tuzsuz hash'i saklanmaz. Kaynakla yeni karşılaştırmalar,
üretilmiş kolon erişimiyle bellekte yapılır; yeniden şifrelenmiş baytların eşitliğine veya loglara dayanmaz.

Sürüm envanteri; Redis sahiplik ve ad alanı kayıtlarının, beyan edilmiş kuyrukların sınırlı
taramasını, silmeler dahil kalıcı sürüm engellerini ve kaynak indekslerindeki uç değerleri kapsar.
Redis'e alınmayan ve mantıksal olarak silinmiş kaynak satırlar da bu incelemeye dahildir.
Bilinmeyen sahiplik, karışık eski kuyruklar, henüz tamamlanmamış kabul edilmiş işlem geçmişi
ve doğrulanamayan harici yollar eksik kapsam olarak kalır. Kabul edilmiş olayların ilk nesil
değeri sonradan değiştirilmez.

Onaylanmış her sayfa; güncel sahiplik, veri, kaynak sürümü, standart indeksler ve L1 durumu
üzerinden yeniden doğrulanır. TTL, Redis `OPEN` durumuna geçene kadar yeterli kalmalıdır.
Eski sayfa onayı veya özet hash, verinin şu anda Redis'te bulunduğunu kanıtlamaz.
Kalıcı sürüm dağıtıcısı, doğrulanmış geçmişin üzerinde sınırlı bir sürüm aralığı ayırır.
SQL `OPEN` durumu ile açılış günlüğü aynı işlemde commit edilir; Redis daha sonra koşullu
olarak açılır. Redis aynı commit edilmiş açılışı zaten uyguladıysa kurtarma bunu onaylar;
Redis'i tekrar kapatmaz veya tüketilmiş sürüm aralığını sıfırlamaz.

## Açılış Sonrası Temizlik Ve Kurtarma

Tam olarak commit edilmiş Redis açılışının `OPEN` olduğu görüldükten sonra, sınırlı günlük
temizliği sürerken durum `SQL_OPEN` kalabilir. Durum `ACTIVE` olana kadar aynı istekle devam edin.
Her devam çağrısı, verisini okumadan en fazla bir indekslenmiş geçici satırı kaldırır.
Saklanan sayfalar 8 MiB, ham çerçeveler 1 MiB ile sınırlıdır.

Saklanan sayfalar, tarama konumları, iş listeleri ve facade/çağrı checkpoint'leri, ilk
`INITIAL` işleminin UUID'si altında temizlenir. Daha sonraki bir purge başka UUID kullansa da
bu bağ korunur. Purge katılımcısı, silmeden önce ilk UUID'yi kalıcı olarak bağlar ve temizliği
bağımsız biçimde doğrular. Sahibi belirlenemeyen eski işlemler `INCOMPLETE` kalır;
verileri sessizce silinmez.

Temizlik başladıktan sonra Redis'in `CLOSED` olması açık bir kurtarma işlemi gerektirir;
kısmen silinmiş sayfalardan yeniden yükleme yapılmaz. Ad alanı izinleri, nesil korumaları,
sürüm dağıtıcısının en yüksek ayrılmış değeri ve sürüm/yazma engelleri güvenlik metadata'sı
olarak saklanmaya devam eder.

Sonlandırma yalnız köke ait koordinatör üzerinden yapılabilir. Bir JDBC oturumu veya açılış
DTO'su, `ACTIVE` durumuna geçme ya da temizlik yetkisi vermez. Her temizlik parçası, ilk Redis
açılışının hâlâ `OPEN` olduğunu fiziksel olarak yeniden gözlemler. Bu gözlem aynı kökün
doğrulanmış SQL transaction'ında yalnız bir kez tüketilir. Günlük codec'i ve temizlik
tanımları tek başına sonlandırma çalıştırmaz.

`SQL_OPEN` durumunda `prepareActivation`, ilk commit edilmiş açılışı ve ilk sürüm aralığını
değiştirmeden döndürür veya `RECOVERY_REQUIRED` ile durur. Yenilenen yönetim lease'i başka bir
aralık ayırmaz. Daha önce tüketilen bir sürüm yeniden kullanılabilir hâle gelmez.

Redis gözlemi, gecikmiş eski kapatma komutlarını güncel yönetim SQL lease epoch değeriyle
engeller; açılışı, sürüm dağıtıcısını veya nesli değiştirmez. Yeni SQL lease'i gerektiren,
protokole katılan bir kapatma işlemi, mevcut SQL koruma transaction'ını bekler.
Tam olarak geçerli lease, satır kilidi altında hem temizlikten önce hem commit öncesinde
doğrulanır; başarısızlıkta o parça geri alınır.

Bu sıralama, SQL ile Redis arasında atomik transaction veya toplam fiziksel I/O süresi için
kesin bir üst sınır değildir. Doğrudan Redis yönetimi, uydurulmuş lease DTO'ları ve harici
SQL yazıcıları bu protokolün doğruladığı kapsamda değildir; ayrıca operasyonel olarak engellenmelidir.

`close()`, koordinatöre yeni çağrı kabulünü durdurur. Daha önce kabul edilmiş senkron bir
adım, yürüttüğü sınırlı parçayı tamamlayıp commit edebilir. Kapatma fiziksel I/O'yu iptal etmez
ve dağıtık işlerin tamamen bittiğini onaylamaz. Çağrı fiziksel olarak bitene kadar SQL ve Redis
kaynaklarını açık tutun. Kesilmiş veya sonucu belirsiz bir işlemde, yeni ve geçerli bir
koordinatör üzerinden ilk istekle devam edin.

## Hata Durumları Ve Kapsam Dışı İşlemler

Yalnız geçici hatalarda, aynı istekle ve sınırlı sayıda tekrar deneyin. `INCOMPLETE`,
`MIGRATION_REQUIRED` veya bilinmeyen açılış durumu açık inceleme gerektirir.
Güvenlik metadata'sını silmeyin ya da sıfırlamayın; otomatik olarak yeni ad alanı varsaymayın.
İlk oluşturmanın sonucu belirsizse kendiliğinden onarım yapılmaz.

`INITIAL`, purge sonrasında erişimi yeniden açma yolu değildir. Kaynak satırları bilerek
koruyan ve kapalı kalan Redis-only purge işlemleri dahil, mevcut purge geçmişi reddedilir.
Kayıtlı olmayan harici depolar, genel Spring Cache değerleri, yedekler ve eski dışa aktarımlar
bu entity sahipliği kapsamının dışındadır. Süreç, tüm verilerin silindiği veya hukuki
uyumluluğun sağlandığı yönünde bir onay vermez.
