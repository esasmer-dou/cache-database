# Şifreli Depolama Sınırları

## Yapılandırma

Anahtar sağlayıcısını açıkça verin. Varsayılan anahtar üretilmez; sağlayıcı,
küresel bir hizmet bulucudan veya iş parçacığı bağlamından çıkarılmaz.

```java
CacheRuntimeOptions options = CacheRuntimeOptions.builder()
        .keyProvider(keyProvider)
        .build();
CacheDatabase database = new CacheDatabase(redis, dataSource, baseConfig, options);
```

Sağlayıcı başvurusu, options.toBuilder() ile korunur. Mevcut 21 bileşenli
CacheDatabaseConfig record'u ve database.config() değişmez. Eski kurucular,
sağlayıcısız varsayılan çalışma zamanı seçeneklerini kullanır; şifresiz eşlemelerin
davranışı değişmez.
Şifreli üst veride kayıt işlemi, içerik çerçevelemesinden önce codec'i bağlar ve
encryptionBound() değerinin true olmasını gerektirir. İşlem yapmayan varsayılan
bindEncryption uygulaması şifreleme desteği sayılmaz. Açıkça bağlanmış bir codec
doğrudan verilebilir. Sağlayıcı hatasında açık metne dönülmez.
Yapılandırma hata çıktıları sağlayıcının toString metodunu çağırmaz.

Spring Boot'ta tekil veya açıkça birincil seçilmiş CacheKeyProvider bean'i, uygulama
özelleştiricilerinden önce çalışma zamanı seçeneklerine aktarılır. Belirsiz sağlayıcı
seçimi reddedilir. Kullanıcının sağladığı CacheRuntimeOptions bean'i belirleyicidir;
sağlayıcısını kendisi ayarlamalıdır. Anahtar baytları yapılandırma özelliklerinden
okunmaz ve seçenekler bağlanırken günlüğe yazılmaz.

## Çalışma Zamanına Bağlı Kaynaklar

Kaynak okumaları ve anlık görüntüler dahil kayıtlı çalışma zamanı codec'ini kullanın:

```java
var binding = database.register(OrderCacheBinding.METADATA,
        OrderCacheBinding.CODEC, CachePolicy.defaults());
EntityCodec<Order> codec = binding.codec();
SourceMapping<Order> source = database.sourceMapping(OrderCacheBinding.SOURCE);
SourceMapping<Order> sameRuntime = OrderCacheBinding.sourceMapping(database);
```

Statik CODEC ve SOURCE bağlanmamıştır. Yeniden bağlama yeni bir örnek oluşturur;
üretilen statik alanları değiştirmez. Farklı tablo, sütun düzeni veya uyumsuz üst
veriye sahip şifreli kaynak eşlemesi reddedilir. Bağlanmamış şifreli çözümleyici,
eski açık metni okumak yerine işlemi reddeder. Kayıtlı JDBC kaynak yükleyicileri
bağlı codec kullanır. Rastgele şifrelenmiş REDIS_AND_SOURCE sütunları üzerindeki
SQL koşulları JDBC erişiminden önce reddedilir; kaynak SQL için mantıksal koşulun
yerine kör indeks belirteci konmaz. REDIS_ONLY kör EQ, mantıksal SQL değeriyle
çalışır. Kimliğe göre kaynak okuması bağlı codec ile çözülür. Şifreli eşlemelerde
opak SourceSqlRepository kullanımı; filtre/sıralama/gruplama güvenle doğrulanamadığı
için bağlantı açılmadan reddedilir. Ayrı ve doğrulanmış kaynak kör indeks protokolü
sağlanmaz.

## Redis ve SQL Veri Biçimleri

İş kuralları ve PATCH nesnesi oluşturmak için mantıksal toColumns değerlerini
kullanın. Her iki kapsamda da Redis entity içeriği ve korunan kuyruk alanları
şifrelidir; birleştirilmiş son durum kayıtları, yeniden denemeler ve hatalı ileti
kuyruğu (DLQ) buna dahildir. toIndexColumns, kör HMAC belirteçleri üretir veya kör
indeksi olmayan şifreli alanı atlar. Mantıksal/kaynak sütun map'i, indeks üst verisi
olarak saklanmamalıdır.

Nesne döndüren PATCH için tam nesnenin sıcak önbellekte bulunması gerekir.
Yalnızca atamaları kabul eden soğuk makbuz komutu, açık kimlik/doğrulanmış kiracı
kuyruk metodunu kullanır; SQL üzerinden başlangıç verisi yüklemez. Gerçek null değişmez;
literal __NULL__, cachedb:__NULL__, ~P:N ve ~P:V metinleri ileti çerçevelemesinden
önce şifrelenir. Şifreli alandaki aritmetik delta, sayısal hata mesajları
oluşmadan reddedilir.

Hazırlanan korumalı Redis parçası ve geçici korumalı SQL toplu işlemi için ayrı
8 MiB UTF-8 içerik/sütun bütçesi vardır. Bu, süreç genelinde eşzamanlılık sınırı
değildir; istek kabulünü ve worker eşzamanlılığını da sınırlandırın.
saveAll mevcut parçalı, atomik olmayan davranışını korur; önceki parçalar
kabul edilmiş olabilir.

Arka plan yazma işleyicisi, gerekli bütün silme dışı üyelerin kimliğini doğrular;
fence işlemi ve iş verisi DML'inden önce değiştirilemez geçici SQL kopyaları
oluşturur. REDIS_ONLY, SQL sütununun orijinal metin değerini geri oluşturur;
REDIS_AND_SOURCE doğrulanmış şifreli zarfı korur. Bu SQL kopyaları, kuyruktaki
orijinallerin yerine konmaz. Yeniden deneme, son durum birleştirmesi, DLQ ve
yeniden işleme korunan değerleri taşır.

Çalışma zamanı kuyruk eşleyicisi, Q1 belirtecine ek olarak kayıtlı şifreleme üst
verisini kullanır. Şifreli sütun taşımayan DELETE hataları da temizlenir.
Otomatik ve elle yeniden işleme hataları, tanı kaydı ve sonuç yayımından önce
temizlenir. Arşiv kopyaları korunan sütunları saklar; eski hata metinlerini ve
bilinmeyen alanları kopyalamaz. Şifreli alanı açık metin taşıyan eski kuyruk
kayıtları yeniden yayımlanamaz; açık bir veri göçü gerekir. Eski kayıt sessizce
yeni ve geçerli bir kayda dönüştürülmez.

Kaynak/sağlayıcı hatalarından çözülen SQL parametreleri veya özgün hata zincirleri
worker tanı bilgisine, DLQ'ya, yeniden işleme sonuçlarına veya arşivlere taşınmaz. Yalnızca güvenli doğrudan yeniden deneme
sınıflaması, doğrulanmış SQLState ve üretici kodu korunur. JDBC parametre/değer
kaydı tutmayı kapatın; alan şifreleme, hata kütüphaneye dönmeden önce sürücünün
kendi günlüğüne yazdığı değerleri temizleyemez.

## Şema ve Sahiplik

REDIS_AND_SOURCE, yazılan gerçek ASCII zarfı saklayabilecek bir JDBC metin sütunu
gerektirir. 65.536 bayt sınırı zarfın üst sınırıdır; sütunun en az bu büyüklükte
olması gerekmez. VARCHAR/NVARCHAR ve uzun metin karşılıkları JDBC üst verisiyle
doğrulanır. Şema bilgisi önbellekten gelse bile her yazmada gerçek zarf boyutu hem
sütun kapasitesiyle hem de zarfın üst sınırıyla karşılaştırılır. Doğrulanmış küçük
bir VARCHAR2 sütunu küçük şifreli değerleri saklayabilir. Ham CLOB, sayısal ve
ikili sütun eşlemeleri desteklenmez.

Örneğin `VARCHAR(1024)`, en çok 1024 ASCII karakterlik bir zarfı kabul eder.
1025 karakterlik zarf ise kalıcı fence hazırlığı, işlem sınırı değişikliği veya
iş tablosuna yazma başlamadan tüm flush çağrısının reddedilmesine neden olur.
Bu kontrol Oracle'ın sonraki parçalarına ayrılacak kayıtları da kapsar. PATCH
yalnızca gönderilen şifreli alanları denetler; SQL NULL için zarf alanı gerekmez.
Sütunu açık metne göre değil, şifreleme ve Base64 sonrasındaki boyuta göre planlayın.
Kontrol arka plandaki SQL yazımında yapılır: Redis'in verdiği `WriteReceipt`,
SQL işleminin tamamlandığı anlamına gelmez. Yazma hatalarını izleyin ve yapılandırılan
kurtarma politikasını uygulayın. Entity'deki String tipi tek başına SQL türünü
ve JDBC çözümleme yolunu doğrulamaz.

Şema doğrulaması, işleyici başına en çok 4096 değiştirilemez entity eşlemesini
önbelleğe alır. Şema değişimini çalışma zamanı yeniden başlatma/doğrulamasıyla
birlikte yönetin; doğrulanmış eşlemenin arkasında canlı şema değiştirmek desteklenen
bir veri göçü yolu değildir. JDBC üst veri erişimi için bağlantı/ağ zaman aşımını,
SQL komutları için de sorgu zaman aşımını sınırlandırın.

Şifreli kiracı yazmaları, Redis'te önceden oluşturulmuş ve eşleşen sahiplik kaydı
gerektirir. Eksik veya uyuşmayan sahiplik, istek yolunda SQL araması ya da sahiplik
rezervasyonu olmadan reddedilir. Etkinleştirmeden önce doğrulanmış sahiplik
oluşturmayı düzenleyin. Şifreleme; yetkilendirme, kalıcı kiracı koruması veya
silme koruması oluşturmaz.

## Kısmi Güncelleme Komutları

`patch(id, assignments)`, birleştirilmiş nesneyi döndürdüğü için Redis'te tam
nesnenin bulunmasını gerektirir. Soğuk PATCH etkinse `patchWithReceipt`, nesne
yükü bulunmadığında yalnızca atamalardan oluşan komutu kabul edebilir. Bu durumda
makbuzdaki nesne null olur. Redis'te önceden belirlenmiş sürüm ve kiracılı
varlıklarda doğrulanmış, eşleşen sahiplik gerekir. Eksik durum kabulden önce
reddedilir; şifreli komutlar sürüm, sahiplik veya kısmi nesne oluşturmak için SQL'e
başvurmaz.

Kısmi kuyruk kodlaması, kanonik kimliği ve açıkça doğrulanmış kiracıyı alanın
AAD bağlamına bağlar. Yalnızca atanan alanlar kuyruğa yazılır. REDIS_ONLY
atamalarının şifresi değiştirilemez, geçici SQL kopyasında çözülür;
REDIS_AND_SOURCE atamaları doğrulanmış E1 zarfını korur. Atanmayan alanlar
oluşturulmaz. `cachedb:__NULL__`, `__NULL__` ve `~P:N` metinleri PATCH taşıma
çerçevesinden önce şifrelenir; gerçek null, çözümleme sonrasında da gerçek null kalır.

## Kaynak Sorgusu Güvenliği

Türü belirli REDIS_ONLY kör EQ filtreleri, özgün mantıksal SQL parametresini korur.
HMAC belirteçleri Redis aday araması ve imleç sözleşmesi içindir; SQL parametresi
veya kalan koşulların değeri olarak kullanılmaz. Bağlı codec ile kimlik üzerinden
okuma, REDIS_AND_SOURCE değerlerinin şifresini çözer.

REDIS_AND_SOURCE filtresi, mantıksal değer veya kör belirteç bağlanarak rastgele
şifreli sütunda değerlendirilemez. Bu filtreler, korunan sıralama/gruplama rolleri
ve şifreli eşlemelerde içeriği çözümlenmeyen SQL sorguları, SQL çalıştırılmadan önce
reddedilir. Kaynak kör indeks şeması veya geçişi varsayılmaz. Bu işlemler ancak
ayrıca doğrulanmış bir kaynak kör indeks protokolüyle etkinleştirilebilir.

## Sorgu ve Toplu İşlem Sınırları

Şifreli alanda yalnız kör EQ desteklenir. EQ dışı koşullar, sıralama, facet,
toplulaştırma değeri, bileşik/bölümlü indeks rolleri ve şifreli ilişki sorgu yolları
reddedilir. Kesin indeks aday anahtarı, orijinal tipli EQ değeriyle codec üzerinden
oluşturulur; son eleme orijinal mantıksal koşulu korur.
Önceden hesaplanmış mantıksal map, korunan indeks verisi sayılmaz.
Korunan alandan aralık, önek veya metin belirteci indeksi üretilmez.

Kör HMAC, sabit entity namespace'ini ve ayrı, sabit bir anahtarı kullanır.
HMAC anahtarı değişmediğinde AES rotasyonu eşitlik belirteçlerini korur.
Aynı entity/anahtar halkasındaki kiracılar arasında eşit değerler ilişkilendirilebilir;
kiracıya özgü kriptografik kör indeks yalıtımı sağlanmaz.
Kiracıya bağlı aday/sahiplik doğrulaması yine gerekir. HMAC anahtarı, halka veya
belirteç protokolünü değiştirmek açık bir indeks göçü gerektirir.

Şifreli atomik toplu işlemler ve unit-of-work, AES anahtarından bağımsız HMAC
malzemesiyle alan ayrımlı ~I1: niyet parmak izi kullanır. Şifreli sütunlar her iki
kapsamda da kuyrukta E1 olarak kalır. Açık metin niyet veya sözlük saldırısına açık,
anahtarsız/tuzlanmış özet Redis'e yazılmaz. Yeni AES nonce'u ve AES anahtar kimliği,
niyet parmak izine girmez.

Aynı batch kimliği ve iş niyetiyle yeni bir oluşturucudan yapılan yeniden deneme,
mevcut 24 saatlik tekilleştirme süresinde önceki stream kimliği ve sürümleri döndürür.
AES rotasyonunda HMAC anahtarı sabit kaldığında parmak izi değişmez. Sonucu bilinmeyen
bir kabulün yeniden denenmesi, yayımlanmış şifreli içeriği yeni nonce'lu içerikle
değiştirmez. Değişen iş değeri, işlem sırası veya açık CAS beklentisi çakışma üretir.

PATCH kimliği, çözülmüş tam satırı veya istek sırasında okunan CAS sürümünü değil,
normalize edilmiş atamaları kapsar. Son eleme ve kaynak SQL parametreleri mantıksal
kalır. Şifreli aritmetik delta desteklenmez. Kiracılı atomik işlemler eşleşen,
önceden oluşturulmuş Redis sahipliği gerektirir; istek yolunda SQL başlangıç araması
yapılmaz.

Oluşturulan codec, kanonik alan kodlamasını ve niyet HMAC'ini açık SPI metotlarıyla
sağlar. Özel codec bu sözleşmeyi uygulamalı; alan kodlaması belirlenimci olmalıdır.
HMAC malzemesi veya niyet yeteneği yoksa INTENT_KEY_UNAVAILABLE oluşur; kabul/yazma
yapılmaz ve başarısız hazırlıktan sonra kalan üyeler commit edilemez.
Yalnız şifresiz üyeler içeren toplu işlemlerin mevcut parmak izi biçimi korunur.
Mevcut 100 işlem, 2000 stream alanı ve 4 MiB ileti sınırları büyütülmez.
HMAC anahtarı değişimi, tekilleştirme süresini ve indeks göçünü birlikte yönetmeyi
gerektirir.

Şifreli alandaki eski açık metin, eski indeks üyelikleri ve kuyruk/DLQ verileri
için açık bir veri göçü ve boşaltma/temizleme politikası gerekir.
Annotation eklemek, mevcut açık metni geriye dönük korumaz veya silmez.

Anahtar sınırları ve ileti biçimi için [alan şifreleme rehberine](alan-sifreleme.md),
null ve derleme kuralları için [şifreli codec rehberine](sifreli-codec-kuyruk.md) bakın.
