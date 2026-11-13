# Bağımsız Redis İndeks Anlık Görünümleri

## Kullanım Koşulları

Bağımsız Redis üzerinde çalışan JedisPooledOperations, sınırlı aggregate ve facet indeks
okumalarını havuzdan ayırdığı tek bağlantıda yerel Redis komutlarıyla yürütebilir. Yeni bağımlılık
veya açık yapılandırma record türlerinde değişiklik gerekmez. Cluster adaptörleri ve özel
RedisClientOperations uygulamaları mevcut Lua davranışını korur.

Repository uygunluk koşulları değişmez: adayların indeks üzerinden eksiksiz çözülmesi, alanların
indeksli olması, desteklenen sıcak küme politikası, kiracı doğrulaması ve aggregate sınırları
geçerliliğini korur. Desteklenmeyen sorgular mevcut payload yolunu kullanır. Sürümü doğrulanmayan
aggregate işlemleri, ilgili etkin nedensel okuma gereksinimlerini karşılamaz; bu durumda sürümü
doğrulanmış tekil veya toplu okumaları kullanın.

## Çağrıya Özel Binary COUNT Anahtarları

Binary COUNT(*) anahtar yolu, bağımsız Redis üzerinde tam olarak yerleşik `JedisPooledOperations`
adaptörüne özeldir. Jedis'in varsayılan UTF-8 kodlamasını ve tam olarak `RedisKeyStrategy` veya
`ClusterHashTagKeyStrategy` türünü gerektirir; ikincisinin bağımsız Redis'te kullanılması Cluster
çalıştırmasını etkinleştirmez. Eksiksiz indeks çözümlemesi, COUNT_WINDOW politikası ve mevcut sorgu
uygunluk denetimleri korunur. Bu COUNT anahtar yolu, COUNT(field), sayısal aggregate veya facet
yönlendirmesini değiştirmez.

Aday kimlikleri çağrıya özel olarak sabitlenir. Yerleşik önekler bir kez, her kimlik pencere başına
bir kez kodlanır; kayıt, silinme ve varsa sahiplik anahtarları doğrudan baytlardan oluşturulur.
Başarılı kiracılı EVAL yolundan önce tam String anahtar listeleri üretilmez. Özel adaptörler,
anahtar stratejisi alt sınıfları, Cluster adaptörleri ve UTF-8 dışı Jedis kodlamaları mevcut String
yolunu korur. Özel Connection alt sınıflarında bağımsız EVAL betik tamponları ve String tabanlı
yerel komut oluşturma davranışı korunur. Çağrılar arasında anahtar veya ACL önbelleği eklenmez.

513..10.000 adaylı uygun kiracısız sorgularda önce yerel yol denenir: her 512 kayıtlık pencere,
MULTI, kayıtlar için vektör EXISTS, silinme işaretçileri için vektör EXISTS ve EXEC kullanır.
Karışık kayıt varlığı veya doğrulanmış bir yerel komut yeteneği reddinde, bütün yerel pencerelerin
yanıtları okunup doğrulandıktan sonra mevcut sınırlı Lua aşamasına geçilebilir. Bu yerel aday
sınırının üzerinde mevcut sınırlı EVAL aşaması kendi sınırları altında kullanılabilir.
Birden çok pencereli kiracılı sorgularda önce EVAL denenir. Son kiracılı pencere 1..32 kayıt
içeriyorsa isteğe bağlı toplu aşama yine reddedilir ve mevcut sıralı yönlendirme kullanılır.
Kiracılı yerel alternatife yalnızca EVAL komutu veya anahtar seçicisi nedeniyle oluştuğu tam
doğrulanan bir ret sonrasında geçilir. Alternatif yol uygun değilse ilk gerçek ACL hatası korunur;
yetkili sıfır sonucu geçerli bir değer olarak kalır. Diğer hatalar ve iptal, yetenek yokluğu sayılmaz.

Binary hazırlık, bağlantı alınmadan önce 512 kayıtlık pencereleri, anahtarlarda 16.384 ve kiracı
kimliğinde 4.096 UTF-16 kod birimi sınırını, pencere başına korumacı 16 MiB anahtar bütçesini korur.
Aşamanın tamamında gerçek kodlanmış argümanlar ve çerçeveleme mevcut 16 MiB bütçesine yazılır;
yerel isteklerde komut ve sınırlı yanıt payları da korunur. İptal denetimleri, özgün Lua, bildirilen
anahtarlar, sahiplik eşitliği ve bütün yanıt doğrulamaları değişmez. Kiracılı yerel alternatifin
ihtiyaç duyduğu String anahtar listeleri yalnızca doğrulanmış EVAL reddinden sonra oluşturulur.

Bu değişiklik Lua algoritmasını veya anlık görünüm garantilerini değil, Java anahtar hazırlığını
değiştirir. Ne 2x hızlanma ne de facet performans kabulü kanıtlar. Performans iddiasından önce aynı
sınırlı yükte bellek tahsisini, uçtan uca gecikmeyi ve Redis CPU kullanımını ölçün.

## Tutarlılık ve Kiracı Denetimi

Yerel COUNT(*) yolu, iki vektör EXISTS okumasını ve kiracılı okumada her sahiplik anahtarı için sınırlı bir
GETRANGE komutunu salt okunur MULTI/EXEC içinde çalıştırır. Tek kiracılı pencerede 32 kaydın üzerinde
sınırlı Lua yolu tercih edilir. 513..10.000 adaylı uygun kiracısız COUNT(*) sorgularında önce yerel
512 kayıtlık gruplar ayrılmış tek bağlantıda topluca gönderilir. Son grup daha küçük olsa da aynı
akışa katılır. Sınırlı Lua alternatifine kiracı kimliği ve sahiplik anahtarları için null verilir;
COUNT(*) kayıt içeriğini veya indeks metadata alanlarını okumaz. Kiracılı sorguların küçük son
grup davranışı değişmez. Birden çok gruptan oluşan kiracılı sorgularda önce sınırlı COUNT(*) Lua
grupları denenir. Lua başarılıysa öncesinde MULTI/EXEC veya her sahip için ayrı okuma gönderilmez.
Yerel komutlara ancak EVAL komutu ya da anahtar seçicisi nedeniyle oluştuğu doğrulanan bir yetki
reddinden sonra, bütün Lua yanıtları okunup doğrulanınca geçilebilir. Alternatif okuma da
tamamlanamıyorsa asıl yetki hatası korunur; boş bir optimizasyon sonucu olarak sunulmaz.
Her Lua grubu kendi aktarım bütçesini, ACL denetimini ve
anlık görünüm sınırını korur. İsteğe bağlı toplu işlem desteklenmiyorsa bütün gruplar mevcut
sıralı yoldan yeniden değerlendirilir. Sorgunun tamamını kapsayan tek bir Lua betiği kullanılmaz.

Özel adaptörler, `supportsUnownedIndexCounts()` ile açıkça destek bildirmedikçe yalnızca mevcut
kiracılı toplu işlem çağrılarını alır. Destek bildiren adaptör, null kiracı kimliğini ve null
sahiplik anahtarlarını kabul etmelidir. İsteğe bağlı toplu işlem kullanılamadığında, ihtiyaç
duyulmayan metadata anahtarları çözülmeden mevcut sınırlı gruplara geri dönülür. Performansı kendi
iş yükünüzde sorgunun tamamı için ölçün; komutları topluca göndermek tek başına hızlanma kanıtı değildir.

Her grup gerçek kayıt, silinme, metadata ve sahiplik anahtarlarını açıkça bildirir. Yerel sonuç yalnızca bütün aday
kayıtlar mevcutken ve hiçbir adayın silinme işaretçisi yokken kabul edilir. Karışık bir pencere
mevcut Lua uygulamasına bir kez yönlendirilir; sıfır sonucu olarak sunulmaz.

Sahiplik karşılaştırması UTF-8 baytları üzerinden tam eşitlikle yapılır. GETRANGE sıfırdan başlar;
**dahil olan** bitiş konumu kiracı kimliğinin bayt uzunluğudur. Böylece en fazla bir ek bayt okunur.
Aynı kiracı önekiyle başlayan daha uzun bir sahiplik değeri erişim izni vermez. Eksik veya başka
kiracıya ait sahiplik işaretçisi kaydı dışarıda bırakır. Okuma işlemi sahiplik işaretçisi oluşturmaz
veya onarmaz. COUNT(*) Lua yolu, sahiplik değerlerini sınırlı GETRANGE okumalarıyla denetler.
Yalnızca GETRANGE için doğrulanan bir ACL reddinde, ilgili sahiplik anahtarı mevcut STRLEN/GET
yoluyla okunabilir. İzin verilen bir komutun WRONGTYPE gibi hataları bastırılmaz.
Yerel geri dönüş yolunda standart Jedis Connection, çağrıya özel kodlanmış bir
GETRANGE argüman şablonu kullanır;
şablon sonraki anahtara geçmeden önce mevcut anahtarın bütün baytları serileştirilir. Komut sayısı,
yanıt sırası, sahiplik okumasının dahil olan üst sınırı ve atomik MULTI/EXEC davranışı değişmez.
Özel Connection alt sınıflarında ortak şablon yerine bağımsız argüman nesneleri korunur.

Sayısal aggregate, COUNT(field) ve facet işlemleri, üç aşamalı yerel protokolü yalnızca WATCH
kapsamının tamamı 32 anahtara sığıyorsa kullanır; bu sınır,
küresel okumada en fazla on, kiracılı okumada en fazla sekiz kayda karşılık gelir:

1. Her adayın kayıt, silinme, sahiplik ve gerekli metadata hash anahtarları WATCH kapsamına alınır;
   ardından vektör varlık denetimleri ve sınırlı sahiplik okumaları yapılır.
2. Yalnızca sahiplik denetimini geçen kayıtların alanları HSTRLEN ile ölçülür, MULTI onayı alınır ve
   metadata bayt bütçesi doğrulanır. ACL kısıtlamalarında da MULTI başarılı olmadan HGET gönderilmez.
3. Varlık ve sahiplik denetimleri, ardından HSTRLEN, gerekli HGET komutları ve EXEC kuyruğa alınır.
   Sonuç ancak aynı işlem içindeki denetimler ilk kabul kararını ve alan boyutlarını doğrularsa
   döndürülür. WATCH nedeniyle iptal edilen EXEC bu komutları çalıştırmaz; mevcut Lua yoluna bir kez geçilir.

WATCH yalnızca istenen alanları değil, metadata hash anahtarının tamamını izler. İlgisiz bir hash
alanının eşzamanlı güncellenmesi de korumacı biçimde Lua yoluna geçişe neden olabilir. WATCH,
ACL değişikliklerini izlemez; bu nedenle varlık, sahiplik ve boyut denetimleri EXEC içinde de
tekrarlanır. Bütün sahiplikler başka kiracılara aitse bile sıfır dönmeden önce aynı işlem içinde
yeniden doğrulanır. Böylece hiçbir anda birlikte geçerli olmayan izinlere dayanarak veri veya
yanlış sıfır sonucu döndürülmez. Sınırlı protokol grupları topluca gönderilir; EVAL izni yokken de
tek alanlı ve iki alanlı yerel okuma sınırları korunur.

Daha büyük metadata pencereleri doğrudan mevcut atomik, sınırlı ve salt okunur
Lua algoritmasını kullanır. Öncesinde yerel deneme veya WATCH kaydı yapılmaz. Sahiplik değerleri
alınmadan önce uzunlukları, kabul edilen metadata alınmadan önce HSTRLEN ile boyutları denetlenir.
Yeni sahiplik doğrulama işareti, yazıcı dönemi veya onarım yetkisi eklenmez. Lua yolu, yerel denemenin
geçici iki saniyelik varsayılanlarını değil, adaptörün yapılandırılmış havuz ve bağlantı zaman
aşımlarını kullanır. Havuz bekleme, bağlantı kurma ve soket okuma sürelerini sonlu yapılandırın.

Bağımsız Redis'te, 512'den fazla adayı olan uygun COUNT(*) gruplamalarında adaptör her kayıt için
ayrı çift göndermek yerine sınırlı grup/sayı çiftleri döndürebilir. Belirsiz `__NULL__` grupları,
payload üzerinden hedefli doğrulama için adayların özgün konumlarını korur. Toplu sayılar aynı
işlem geneli aday, grup ve bayt sınırlarına tabidir. COUNT(field) ve sayısal gruplamalar mevcut
değer yolunu kullanır. Özel adaptörler ve Cluster adaptörleri bu isteğe bağlı optimizasyonu
reddedebilir. Gruplama sırasında sahiplik, uzunluk denetimli GET ile doğrulanır; ek MGET izni gerekmez.

Yerleşik bağımsız Redis adaptörü ve anahtar düzeni, Jedis'in varsayılan UTF-8 kodlamasıyla
kullanıldığında facet anahtarları, her tam anahtar için Java String oluşturulmadan kodlanabilir.
Özel adaptörler, özel anahtar düzenleri, Cluster ve farklı Jedis karakter kodlamaları mevcut
String tabanlı yolu kullanır. Sıkıştırılmış yanıtlar çözümlenirken Jedis'in karakter çözümleme
davranışı ve mevcut UTF-16 ağırlıklı bayt bütçesi korunur. Bu iç optimizasyonlar ACL, aday, grup,
boş değer işaretçisi veya aktarım boyutu sınırlarını gevşetmez.

Başarılı bir yerel toplu okuma, kayıt varlığı, sahiplik ve metadata için tutarlı tek bir durum
gözlemler. Birden çok repository okuma grubu **küresel bir anlık görünüm oluşturmaz**. Adayların
belirlenmesi ve belirsiz boş değerlerin payload üzerinden yeniden doğrulanması da ayrı
gözlemlerdir. Bu özellik anlık görünüm, boşluksuz sayfalama veya eşzamanlı ekleme garantisi sağlamaz.

## Değerler ve Sınırlar

Ham ondalık metin değişmez. SUM, AVG, MIN ve MAX, Lua kayan nokta hesabını değil, mevcut Java
sayısal davranışını ve sınırlı BigDecimal çözümlemesini kullanır. Boş metadata değeri geçerlidir.
Gerekli alanın eksikliği indeksin yeniden oluşturulmasını isteyen bir hatadır. Mevcut __NULL__
belirsizliği ve hedefli canlı payload doğrulaması korunur.

- Her çağrı, listeleri hizalı 1..512 aday kimliği kabul eder.
- Yerel metadata yolu en fazla 512 alan okur: tek alan için 512, iki alan için 256 kayıt.
  Repository, indeks yoluna uygun iki alanlı facet işlemlerini 256 kayıtlık gruplara ayırır.
  Tek alanlı ve yalnızca payload kullanan gruplar 512 kayıtta kalır. Adaptöre doğrudan gönderilen
  daha büyük iki alanlı çağrı mevcut sınırlı Lua yolunu kullanır. İşlem geneli sınırları gruplar
  arasında sıfırlanmaz.
- Yerel facet işlemlerinde WATCH kapsamı en fazla 32 anahtardır. Sayısal aggregate işlemlerinde
  mevcut 2.048 anahtarlık üst sınır korunur. Bir protokol aşamasında denetim komutları dahil
  en fazla 516 komut bulunur. En büyük kiracılı COUNT, 514 okuma ile MULTI ve EXEC içerir.
- Anahtarlar için 16.384, alan ve kiracı kimliği için 4.096 UTF-16 kod birimi sınırı korunur.
- Her yerel istek aşaması, kodlanmış argümanlar ve çerçeveleme için korumacı 16 MiB bütçe kullanır.
  Tekrarlanan anahtar veya alanlar sığmazsa, kendi korumaları altında çalışan daha kısa Lua isteği
  kullanılabilir.
- Kabul edilen ham metadata toplamı en fazla 16 MiB'dir; HGET çalışmadan önce HSTRLEN ile denetlenir.
  Hash değişmişse kuyruktaki HGET komutları çalışmadan EXEC iptal olur.
- Yerel sahiplik yanıtları ve COUNT(*) Lua GETRANGE okumaları, kiracının UTF-8 bayt uzunluğu artı
  bir baytla sınırlıdır. Diğer Lua sahiplik okumaları uzunluk denetimini korur; gereğinden büyük
  sahiplik değerleri belleğe alınmaz.
- Java tarafındaki UTF-16 kod birimi başına iki bayt içerik bütçesi 16 MiB olarak kalır. Bunlar
  içerik ve aşama sınırlarıdır; toplam JVM belleğinin veya tüm RESP çerçevelerinin 16 MiB'ye
  sığacağı anlamına gelmez.
- İşlem genelindeki aday, facet grubu ve bayt sınırları artırılmaz.

Adaptör API'sindeki eski aggregate çağrısı ile sahiplik bilgisi alan sürümünün imzaları ve
değiştirilemez sonuç türleri korunur. Hizalı alt düzey sayım çağrısı örneği:

~~~java
RedisClientOperations operations = JedisPooledOperations.of(redis);
var batch = operations.aggregateIndexBatch(
        entityKeys, tombstoneKeys, indexMetadataKeys, ownerKeys,
        tenantId, null, true).orElseThrow();
long liveOwnedRows = batch.count();
~~~

Anahtarlar ve sahiplik işaretçileri uygulamanın mevcut CacheDB ad alanı ve anahtar stratejisinden
gelmelidir. İndekste bulunmak tek başına erişim yetkisi değildir.

## Hata ve Kapasite Yönetimi

Her yerel deneme, protokolün tamamında aynı bağlantıyı ayırır. Sağlıklı bağlantı, ancak EXEC veya
UNWATCH bağlantı durumunu temizledikten sonra aynı havuza döner. Bekleyen yanıtlar, kirli işlem
durumu, belirsiz WATCH durumu ve taşıma hataları, açık SDK üzerinden bağlantının bozuk olarak
havuzdan çıkarılmasına yol açar. Jedis istemci tarafı önbellek bağlantıları, kendi protokolleri
atlanmadan Lua yolunda kalır.

Sonlu havuz bekleme ve soket okuma süreleri korunur. Sınırsız havuz beklemesi yerel bağlantı alma
işlemi için iki saniyeyle sınırlanır. Sınırsız soket okuması geçici olarak iki saniyeye indirilir;
eski değer yalnızca sağlıklı dönüşte geri yüklenir. Sonlu bir **bağlantı kurma zaman aşımı** da
ayarlayın: havuz bekleme sınırı tek başına bağlantı oluşturmayı, kimlik doğrulamayı veya ilk
hazırlığı sınırlamaz. Yeni bir işlem geneli zaman sınırı eklenmez. Çağrının iptali, soket zaman
aşımının yerine geçmez.

Yerel yolun uygun olmaması, karışık kayıt varlığı veya WATCH iptali sonrasında mevcut Lua yoluna
yalnızca bir kez başvurulur. Bütçe, şema ve G/Ç hataları yeniden denenmez. G/Ç hatasından sonra
başka bağlantıda Lua isteği başlatılmaz. Çağrı eşzamanlılığını ve havuz kapasitesini sınırlayın;
daha çok ayrılmış bağlantı Redis kapasitesini kendiliğinden artırmaz.

Bu yol, kayıt başına Lua komut çağrısını azaltmak için daha fazla yerel protokol aşaması kullanır.
Kendiliğinden daha yüksek işlem hacmi garantisi vermez. Temsili kiracı ve eşzamanlı yazma yükünde
sonuç eşitliğini, gecikmeyi, bellek tahsisini, sunucu CPU kullanımını, WATCH iptallerini, EVAL
sıklığını ve havuz beklemelerini karşılaştırın.

## Kısıtlı ACL ile Uyumluluk

Yanıtların tamamı tüketilmişse, Redis'in yalnızca yerel algoritmada kullanılan WATCH, MULTI, EXEC
veya GETRANGE komutunu açıkça reddetmesi mevcut Lua algoritmasına bir kez geçiş sağlayabilir.
Komut reddi gerçekten gönderilen komutla eşleşmelidir: doğrudan Jedis erişim denetimi hatası veya
hizalı EXEC yanıtında EXEC komut reddini saran tam Redis EXECABORT biçimi kabul edilir.
Anahtar erişim izni hatası, komut yeteneğinin bulunmadığı anlamına gelmez. Yerel metadata yolunda,
HSTRLEN/HGET için biçimi doğrulanmış ve yanıtları tamamen tüketilmiş bir izin reddi, mevcut Lua
uygulamasının bir kez denenmesini sağlayabilir. Redis'in EXEC sırasında bildirdiği ACL değişikliği
de bu kapsamdadır. Bu durum veri döndürme yetkisi vermez: Lua işlemi güncel izinlerle ayrıca
başarılı olmalıdır. Tanınmayan izin hataları hata olarak kalır.

Lua yoluna geçmeden önce yerel bağlantı bozuk olarak işaretlenir ve havuzdan çıkarılır. Aynı
aşamadaki diğer yanıtlar yapısal olarak geçerli olmalıdır; izin reddi başka hataları gizleyemez.
Bağlantının çıkarılması başarısız olursa Lua denenmez. Lua, aynı kimlik bilgileri ve anahtar
izinleriyle çalışır; hatası başka deneme yapılmadan iletilir. ACL izinleri genişletilmez veya
onarılmaz.

NOAUTH, WRONGPASS, WRONGTYPE, bozuk yanıt, G/Ç hatası, doğrulanamayan WATCH durumu ve UNWATCH
temizleme reddi hata olarak kalır. Doğrulanmış EXEC çağrısının olağan WATCH iptali sonucu, mevcut
eşzamanlılık geri dönüş yolunu kullanır; belirsiz protokol durumu bununla aynı şey değildir.

İzin kararı önbelleğe alınmaz. EVAL ile uyumlu kısıtlı ACL altında her çağrı yeniden bir yerel
deneme, bağlantının çıkarılması ve Lua çalıştırma maliyeti doğurabilir. Yerel algoritmanın
performansı isteniyorsa gerekli komut izinlerini açıkça tanımlayın; Lua uyumluluğu tek başına
yerel algoritmanın performansını sağlamaz.

İndeks yoluna uygun Cluster veya özel adaptör Lua kullansa da iki alanlı repository pencereleri
küçülür. Bu değişiklik gidiş-dönüş sayısını artırabilir; küçük pencere veya yerel komut kullanımı
kendiliğinden daha yüksek işlem hacmi anlamına gelmez.
