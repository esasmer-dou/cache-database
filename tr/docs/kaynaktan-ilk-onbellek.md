# Kaynaktan İlk Önbellek Yüklemesi

İlk yükleme, tek bir varlık ad alanı ve kiracı CLOSED durumundayken açıkça tanımlanmış,
sınırlı bir sıcak veri penceresini doldurur. Ardından kalıcı SQL kabulü ile yerel Redis
etkinleştirmesini koordine eder. Bu bir yönetim işlemidir; normal isteklerde SQL'e dönüş
yolu veya kiracıya ait bütün verileri kapsayan bir silme sertifikası değildir.

## Desteklenen Profil

İlk profil, `CLIENT_ONLY` çalışma rolünü ve `REDIS_ONLY` okumayı gerektirir. Bu koordinatör
diğer rolleri doğrulamaz. Dağıtık işçi ve yeniden oynatma yolları yine açık envanter ve
kapanma kapsamı gerektirir.

INITIAL, ad alanı için tek kullanımlık SQL izni tüketilmeden önce L1 etkin kökleri, Redis Cluster'ı
ve özel Redis işlem uyarlayıcılarını reddeder. Ön plan ve arka plan aynı bağımsız Redis istemcisini
paylaşan standart `JedisPooledOperations` aktarımını kullanmalıdır. Cluster düğümüne havuzlu bir
bağlantı açmak, bağımsız Redis kullanmak anlamına gelmez. Genel varsayılanlarda, kayıtlı varlık
ilkelerinde ve `@CacheNearPolicy` bilgisinde L1 kapalı olmalıdır. Kayıt değişiklikleri ilk yükleme
öncesinde ve koordinatörün her aşamasında yeniden denetlenir. Desteklenmeyen bir ilkenin sonradan
etkinleştirilmesi, başka bir ad alanı izni tüketmek veya etkinleştirmeyi sürdürmek yerine işlemi
güvenli biçimde durdurur.

İlk profil, küresel olarak benzersiz, NULL olmayan tamsayı `Long` kimlikleri kabul eder.
Fiziksel JDBC türü, basamak sayısı, ölçek ve kayıpsız dönüşüm doğrulanır. String/UUID
sıralaması Java dönüşümlerinden veya veritabanı karşılaştırma düzeninden çıkarılmaz;
kanıtlanamayan sıralama için geçiş gerekir.

Kaynakta filtrelenmemiş `(tenant,id)` ve `(tenant,version)` öncül indeksleri ve boyutu
sınırlı, desteklenen sütun türleri bulunmalıdır. Sürümler pozitif, kayıpsız tamsayı olmalı;
`9007199254740991` değerini aşmamalıdır. NULL, sıfır, kesir ve taşma hata sayılır; başka
bir sürüme dönüştürülmez. Mantıksal silme işaretleri dâhil bütün seçim ve yük eşlemesi
parmak izine bağlanır.

Sınırlar: 10.000 seçili satır, sayfa başına 128 satır, 128 kalıcı sayfa, kodlanmış satır
başına 1 MiB, sayfa başına 8 MiB ve açıkça onaylanmış en fazla 64 MiB toplam sayfa boyutu.
Bunlar toplam Redis belleği değildir; yerel indeks ve doğrulama genişlemesi ayrıca fiziksel
olarak sınırlandırılmalıdır. Desteklenen `POINT_NO_QUOTA` profilinde rota/kota rezervasyonu
yoktur. Kotalı rotalar, ilişkiler, projeksiyonlar ve kaynaktan otomatik okuma desteklenmez;
bu denetimler sessizce atlanmaz.

Sıcak pencere, bütün geçmiş kimlikleri tutan bir Redis kaydı değildir. Soğuk kaynak satırları
ve çağıranın gönderdiği yeni kimlikler, etkinleştirme başarılı oldu diye sahipli sayılmaz.

## Kaynağa Yazma İşlemleri İçin Ön Koşul

INITIAL başlamadan önce seçilen kaynak kapsamına yazan bütün uygulamaları durdurun veya
yazmalarını bağımsız olarak engelleyin. Etkinleştirme ve uygulamanın geçişi bitene kadar bu
sınırı koruyun. Mevcut ORM uygulamalarını, stored procedure çağrılarını, veri aktarımlarını,
zamanlanmış işleri ve harici CDC/outbox üreticilerini de kapsama alın.
SQL nesil koruması, protokole uyan CacheDB işçilerini denetler; başka bir uygulamanın doğrudan
gönderdiği SQL'i engellemez. Redis kuyruğunun boş olması veya eşlemenin onaylanmış olması,
bu yazıcıların durduğunu kanıtlamaz.

Kaynak yeniden doğrulaması, sayfalar denetlenirken görülen değişiklikleri yakalar. Kontrol dışı
bir yazıcının son denetimden sonra commit yapmayacağını garanti edemez. Kaynağa yazma işlemlerinin
durdurulduğu veya engellendiği ayrıca kanıtlanamıyorsa bu route'u açmayın ve ilk yükleme sonucunu
geçiş onayı saymayın. Koordinatör, harici yazıcıların durduğunu otomatik olarak doğrulamaz.

## Yönetim Sırası

Sağlayıcıya uygun güvenlik geçişlerini açıkça kurun. INITIAL için ayrıca
`V019_3__source_owner_onboarding.sql`, `V019_4__initial_source_bootstrap.sql` ve
`V019_5__namespace_bootstrap_once.sql` gerekir. Çalışma zamanı şema kurmaz.

İlk ad alanında, yerel çalışma zamanı listesi oluşturulmadan ve INITIAL edinilmeden önce
onaylı, kalıcı `JdbcNamespaceBootstrapGate` hakkı tüketilmelidir. Bu hak tam liste anahtarını,
eşlemeyi, Redis çalışma kimliğini ve üretici/okuma çitinin paylaştığı tek süreç kimliğini bağlar.
SQL tüketimi, Redis'e gönderimden önce kaydedilir. Önceden başlatılmış listenin kaybolması,
süresinin dolması, yanıtın kaybolması veya çökme, yeniden oluşturma izni vermez.
SQL kaydının bulunmaması, eski bir kurulumun yeni olduğunu kanıtlamaz.

Ad alanını uygulamanın gerçek `CacheDatabase` örneği üzerinden hazırladıktan sonra
`CacheInitialNamespace.createBootstrap` kullanın. Bu giriş noktası; kayıtlı metadata'yı,
depolama codec'ini, istemciyi, anahtar stratejisini, koruma çitini ve incelenmiş planı bağlar.
İç oluşturma metodu yalnız framework'ün gerçek çalışma zamanına bağlı uyarlayıcısını kabul eder;
uygulama kendi kapanış veya etkinleştirme uyarlayıcısını veremez. Yerel CLOSED bayrağı,
çağıranın Boolean değeri, süreç içi harita veya boş ilan edilmiş liste, dağıtık kapanmayı
kanıtlamaz. Zorunlu manifest, yarıda kalan sayfalar için gerçek
`JdbcInitialBootstrapPurgeParticipant` örneğini içerir.

Özgün nesil, kiracı, işlem UUID'si, operatör belirteci ve sınırlı kiralama süresiyle bir
`InitialTenantBootstrap.Request` oluşturun. Sınırlı `step(request)` çağrılarında aynı isteği
değiştirmeden kullanın. Her çağrı, kabul iznini bırakmadan önce fiziksel G/Ç'sini tamamlar.

Koordinatör, yerel yüklemeden önce şifreli sayfayı kalıcı olarak kaydeder. Yeniden deneme ve
yeniden başlatmada aynı şifreli yük, rastgele nonce değerleri ve anahtarlı satır kanıtı korunur.
Korunan alanların açık metni veya tuzsuz açık metin özeti saklanmaz. Taze kaynak karşılaştırması
üretilmiş sütun erişimiyle bellekte yapılır; yeniden şifrelenmiş bayt eşitliğine veya günlüklere
dayanmaz.

Sürüm envanteri; sınırlı sahip/ad alanı/beyan edilmiş kuyruk taramasını, silmeler dâhil kalıcı
yazma çitlerini ve soğuk/mantıksal silinmiş satırlar dâhil indeksli kaynak uç değerlerini kapsar.
Bilinmeyen sahiplik, karışık eski kuyruklar, boşaltılmamış kabul edilmiş geçmiş veya
kanıtlanamayan harici yollar eksik kalır. Kabul edilmiş olayların özgün nesli korunur.

Onaylanan her sayfa; güncel sahip, yük, kaynak sürümü, kanonik indeksler ve L1 durumu üzerinden
yeniden doğrulanır. TTL kapsamı yerel OPEN anına kadar geçerli kalmalıdır; önceki sayfa onayı
veya özet, güncel yerleşikliği kanıtlamaz. Kalıcı ayırıcı, doğrulanan geçmişin üzerinde sınırlı
bir sürüm aralığı ayırır. SQL OPEN ile etkinleştirme günlüğü aynı işlemde kaydedilir;
ardından Redis koşullu olarak açılır. Redis tam olarak aynı kaydedilmiş etkinleştirmeyi zaten
açtıysa kurtarma, Redis'i kapatmadan veya tüketilmiş aralığı sıfırlamadan sonucu onaylar.

Redis'in aynı kalıcı etkinleştirme için OPEN olduğu doğrulandıktan sonra `SQL_OPEN` aşaması,
sınırlı günlük temizliği tamamlanana kadar sürebilir. Aynı isteği `ACTIVE` durumuna kadar sürdürün.
Her devam çağrısı, yükü belleğe almadan en fazla bir indeksli geçici satırı kaldırır; yakalanmış
sayfalar 8 MiB, ham yanıt çerçeveleri 1 MiB ile sınırlıdır. Sayfalar, tarama konumları, iş listeleri
ve işlem/çağrı günlükleri özgün INITIAL işlem UUID'siyle temizlenir. Sonraki silme işleminin
farklı UUID kullanması bu günlükleri kapsam dışında bırakmaz. Silme katılımcısı, kaldırma
başlamadan önce özgün UUID'yi kalıcı olarak kaydeder ve temizliği ayrıca doğrular.
Sahipliği belirlenemeyen eski işlemler INCOMPLETE kalır; verileri sessizce silinmez.

Temizlik başladıktan sonra Redis CLOSED olursa kısmen kaldırılmış sayfalardan yeniden yükleme
yapılmaz; açık kurtarma gerekir. Ad alanı hakları, nesil korumaları, ayırıcının üst sınırı ve
sürüm/yazma çitleri güvenlik metadatası olarak tutulur.

Sonlandırma yalnızca gerçek kökün sahip olduğu koordinatör üzerinden yapılır. JDBC oturumu ve
etkinleştirme veri nesnesi, `ACTIVE` durumuna geçiş veya temizlik yetkisi vermez. Her temizlik
parçası, Redis'teki özgün OPEN etkinleştirmesini fiziksel olarak yeniden gözlemler. Bu gözlem aynı
kökün doğrulanmış SQL işleminde yalnız bir kez tüketilir. Salt günlük codec'i ve temizlik
tanımlayıcıları sonlandırma çalıştırmaz.

`SQL_OPEN` aşamasında `prepareActivation`, özgün sürüm aralığı dâhil kaydedilmiş etkinleştirmeyi
değiştirmeden döndürür veya `RECOVERY_REQUIRED` hatası verir. Yeni yönetim kiralaması başka bir
aralık ayırmaz. Daha önce tüketilmiş sürüm tüketilmiş olarak kalır.

Yerel gözlem, gecikmiş eski kapatma komutlarını geçerli yönetim SQL kiralamasının sıra numarasıyla
engeller; etkinleştirmeyi, ayırıcıyı veya nesli değiştirmez. Protokole uyan yeni bir kapatma,
daha yeni SQL kiralaması için mevcut koruma işleminin bitmesini bekler. Aynı geçerli kiralama,
satır kilidi altında temizlikten önce ve kayıttan hemen önce yeniden doğrulanır; hata olursa
parça geri alınır. Bu sıralama SQL ile Redis arasında atomik işlem veya kesin toplam G/Ç süresi
sınırı değildir. Doğrudan Redis yönetimi, uydurulmuş yerel kiralama nesneleri ve harici SQL yazarları
bu protokolle sertifikalandırılmaz; bağımsız işletimsel engelleme gerektirir.

`close()` koordinatöre yeni çağrıları durdurur. Daha önce kabul edilmiş eşzamanlı adım mevcut
sınırlı parçayı tamamlayıp kaydedebilir. Kapatma fiziksel G/Ç'yi iptal etmez ve dağıtık boşaltma
onayı üretmez. Çağrı fiziksel olarak çıkana kadar SQL ve Redis kaynaklarını açık tutun.
Kesinti veya belirsiz sonuçtan sonra özgün isteği yeni ve geçerli bir koordinatörle sürdürün.

## Hata Ve Kapsam

Yalnızca geçici hatalarda aynı istekle sınırlı yeniden deneme yapın. INCOMPLETE,
MIGRATION_REQUIRED veya bilinmeyen etkinleştirme durumunda açık inceleme gerekir.
Güvenlik metadatasını silmek/sıfırlamak veya otomatik olarak yeni ad alanı varsaymak çözüm
değildir. Belirsiz ilk oluşturma için otomatik onarım sağlanmaz.

INITIAL, silme sonrası yeniden açma yolu değildir. Kaynağı bırakan ve kapalı kalan
yalnızca Redis silmeleri dâhil mevcut silme geçmişi reddedilir. Kayıtsız harici depolar,
genel Spring Cache değerleri, yedekler ve geçmiş dışa aktarımlar bu varlık sahipli kapsamın
dışındadır. Evrensel silme veya hukuki sertifikasyon iddiası yoktur.
