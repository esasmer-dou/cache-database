# Sınırlandırılmış Redis Tekil Okumaları

Redis tekil okuma birleştirmesi, uygun önbellek kaçırmalarını yapay bir bekleme süresi eklemeden gruplar.
Java 21 gerektirir. Yakın önbellekte bulunan kayıtlar Redis taşıma katmanına girmez.
Okuma birleştirme, düşük yükte gecikmeyi artırmamak için varsayılan olarak kapalıdır.
Etkinleştirmeden önce kendi eşzamanlı okuma yükünüzde açık ve kapalı durumları karşılaştırın.

## Ölçülen Kazanç ve Maliyetler

10 Kasım 2026 tarihli aday karşılaştırmasında aynı SDK, birleştirme açık ve kapalı olarak ölçüldü.
Her hücre üç ayrı JVM sürecinde çalıştırıldı. Çalıştırıcı sınırı 1 CPU / 4 GiB, Redis sınırı
100m CPU / 384 MiB idi. Aşağıdaki oranlar açık durumun kapalı duruma bölünmesidir;
0.18.1 sürümüyle karşılaştırma değildir. İşlem hacminde yüksek, p50 gecikmede düşük oran daha iyidir.

| İş Yükü | İşlem Hacmi Oranı | p50 Gecikme Oranı | Değerlendirme |
| --- | ---: | ---: | --- |
| Tek çağıran | 0,7132× | 1,3528× | Düşük eşzamanlılıkta birleştirme ek maliyet getiriyor. |
| 16 çağıran | 0,4693× | 2,2374× | Ölçülen bu iş yükünde birleştirmeyi açmak uygun değil. |
| 64 çağıran | 1,4439× | 0,2745× | Hızlandı; ancak aday kabulündeki zorunlu 2× hedefini karşılamadı. |
| 1.000 sanal çağıran | 2,2486× | 0,4648× | Bu yüksek eşzamanlılık senaryosunda yarar sağladı. |

Bu sonuçlar sürüme yayım onayı vermez, kabul eşiklerini değiştirmez ve sizin ortamınız için aynı
kazancı garanti etmez. Uygulamanızın eşdeğer iş yükünde gecikme ve bellek bütçeleri içinde yarar
görmeden birleştirmeyi açmayın. Yalnız benchmark oranını artırmak için eşzamanlılığı yükseltmeyin.

## Desteklenen Okumalar

Varlık `findById`, `findHotById` çağrıları ve bunların asenkron karşılıkları ortak, sınırlandırılmış tekil okuma
çalışma zamanını kullanabilir. Yerel asenkron `findAll`, okumaları sınırlı pencerelerle yürütür; giriş sırasını
ve yinelenen kimlikleri korur. Dönen içeriğin toplam kodlanmış boyutu 16 MiB ile sınırlıdır; bu API sınırsız
veri dışa aktarımı için uygun değildir.
Fetch plan, kaynak veritabanına başvurma veya pozitif nedensel sürüm gereksinimi içeren okumalar başka bir
sınırlandırılmış yoldan yürütülebilir. Etkin bir iş birimi içindeki asenkron işlemler, iş birimi başka bir
iş parçacığına taşınmadan reddedilir.

Nedensel tekil okumalar ve `findAll`, sürüm doğrulamasını korur. Bağlamın taşınması tek başına sorgu, sayfa,
aggregate, partition veya projection sonucunun güncelliğini doğrulamaz. Uygulanmış sürümü doğrulayacak açık
bir protokol bulunmadığında bu işlemler ilgili etkin nedensel gereksinimleri reddeder. Bir yazma makbuzu,
her türetilmiş sorgunun kendi yazdığını okuma garantisi taşıdığı anlamına gelmez.

Alt düzey `RedisReadRuntime`, ham yükü, silinme işaretini, TTL değerini ve gözlem zamanını döndürür.
Varlık çözümlemez ve kiracı sahipliğine karar vermez. Aynı slot içindeki varlık/silinme işareti/TTL okuması
atomiktir; farklı slotlar ayrı gözlemlerdir ve dağıtık bir anlık görüntü oluşturmaz.
Bulunamayan metinler null olur. TTL, Redis anlamlarını izler: -1 kalıcı, -2 bulunamadı, -3 istenmedi.
Gözlem zamanı ağ erişiminden önce alınır; pozitif TTL kullanılırken geçen süre düşülmelidir.

## Yapılandırma

Yeni çalışma zamanı seçenekleri, mevcut `CacheDatabaseConfig` kaydından ayrıdır.

```java
var reads = RedisReadCoalescingConfig.builder()
        .enabled(true)
        .maxInFlightBatches(16)
        .maxReplyBytes(16L * 1024 * 1024)
        .maxLiveReplyBytes(64L * 1024 * 1024)
        .timeoutMillis(5000)
        .build();
var options = CacheRuntimeOptions.builder().redisReadCoalescing(reads).build();
```

64 MiB küresel içerik bütçesi, aynı anda en fazla dört adet 16 MiB azami yanıt payı ayırır; gerçekten tutulan
içerik daha küçük olabilir. Sınırları yalnız bağlantı havuzuna göre değil, pod belleğine ve Redis CPU
bütçesine göre belirleyin.

| Seçenek | Varsayılan | Sınır / Anlam |
| --- | --- | --- |
| enabled | false | Okumaları birleştirir; sınırsız taşıma sağlamaz |
| maxBatchKeys | 512 | Kimlik sayısı; her kimlik varlık/silinme işareti çifti, en fazla 1024 fiziksel anahtar |
| maxQueuedRequests | 4096 | 0..4096; seçilmiş işler ve bütçe nedeniyle ayrılan okumalar dahil kuyruk kabul payları |
| maxQueuedBytes | 16 MiB | Kuyruktan kabul edilen işlerin fiziksel tamamlanmaya kadar tutulan toplam kabul hesabı |
| maxInFlightBatches | 0 | 0 için max(2, poolMaxTotal/2); açık değer 1..1024 |
| maxReplyBytes | 16 MiB | Denenen grup başına 1..16 MiB UTF-8 yük/silinme işareti içeriği |
| maxLiveReplyBytes | 256 MiB | Ayrılan ve tutulan toplam ham içerik; en az maxReplyBytes, en fazla 256 MiB |
| timeoutMillis | 5000 | 1..300000; asenkron teslim ve kuyruktaki senkron bekleme süresi |
| maxAsyncDirect | 16 | Birleştirme açıkken taşma için 0..1024 ek kabul sınırı |

32 bağlantılı havuzda eşzamanlı grup sınırı 16, 64 bağlantılı havuzda 32 olur; gizli bir on altı üst sınırı
yoktur. Türetilen değer 1024'ü aşarsa yapılandırma reddedilir. Küresel yanıt belleği bütçesi gerçek
eşzamanlılığı daha düşük bir değerde sınırlayabilir.

`enabled=false` olduğunda uygun yerel asenkron kaçırmalar sınırlandırılmış FIFO kabulünü kullanmayı
sürdürür, ancak her taşıma grubu yalnız bir kimlik içerir. İstek veya bayt kuyruğu dolduğunda eski isteklerin
önüne geçilmez; yeni istek reddedilir.
Senkron repository okumaları birleştirmesiz uyumluluk yolunu korur. Alt düzey `RedisReadRuntime` çağrıları,
birleştirme kapalıyken de korumalıdır.
Adil bir bağlantı havuzu kullanın. Dışarıdan verilen istemci adil olmayabilir; kabul denetimi aynı havuzu
kullanan bağımsız işlere karşı ilerleme garantisi vermez. Soket, bağlantı kurma ve havuz bekleme süreleri
sonlu olmalıdır.

## Geri Basınç ve Bellek

Asenkron I/O, kuyruktaki her istek için bekleyen bir iş parçacığı değil, kabul edilen grup başına bir sanal
iş parçacığı kullanır. Eşzamanlılık ve bellek sınırları uygunsa senkron ham okuma doğrudan çağıran iş
parçacığında başlar. Kilit yalnız kayıt tutmak içindir; ağ erişimi, bekleme ve tamamlama callback'leri
kilit altında çalışmaz.

Her grup, I/O herhangi bir değeri belleğe almadan ÖNCE küresel yanıt bütçesinden `maxReplyBytes` kadar pay
ayırır. Yanıt geldiğinde bu pay gerçek ham UTF-8 içerik boyutuna indirilir ve mapping ile satır içi
tamamlama callback'leri bitene kadar tutulur. Fiziksel I/O izinleri callback'lerden önce bırakılır ve
uygun sonraki işler başlatılır. Böylece engellenen bir callback, sınırsız sayıda büyük yanıtın birikmesine
yol açamaz.

Yanıt belleği dolduğunda asenkron okumalar, kendi süre sınırlarına tabi olarak kuyrukta kalır.
Normal senkron ham okumalar, yanıt belleği geçici olarak doluyken aynı sınırlı FIFO kuyruğunda süre
sınırlı bekler. Yalnız bir grubun yanıt payını tutan tamamlama callback'i içinden yeniden giren senkron
okuma hemen `RejectedExecutionException` ile reddedilir; callback'in kendi payını bırakmasını beklemek
kilitlenmeye neden olabilir.
Normal FIFO kabulü dolduğunda senkron çağıran, yanıt payı için AYRI bir taşma sırasında bekleyebilir:
çalışma zamanı başına en fazla 8192 çağıran ve istek/anahtar kabul hesabı için ayrıca 16 MiB.
Bu yol ek bir iş parçacığı oluşturmaz veya başlatmaz. Yalnız mevcut çağıran, isteğin aynı son teslim
zamanını kullanarak bir condition üzerinde bekler. Beklerken fiziksel I/O sayacı veya yanıt payı tutmaz.
Kabul edildiğinde tam yanıt payını ayırır ve doğrudan okumayı yine aynı çağıran yürütür.
Doğrudan ve taşma okumaları yanıt belleği bütçesini aşamaz. Tamamlama callback'i içinden yeniden giren
bir çağrı bu bekleme yoluna alınmaz. Bu, kapasite yetersizliği nedeniyle reddetmedir; varlığın tekil boyut sınırını aştığı
anlamına gelmez. Callback'leri bekletmeyin; ağır uygulama işlerini ayrıca sınırlandırılmış bir uygulama
yürütücüsüne aktarın.

Varsayılan küresel ham içerik sınırı 256 MiB'dir ve havuzdan türetilen eşzamanlılıktan bağımsızdır.
Bu değer, JVM yığınının 256 MiB ile sınırlı kalacağı anlamına GELMEZ. Çözümlenen metinler kaynak bayt
sayısının iki katına kadar yer kaplayabilir; ikili taşıma tamponları geçici olarak bunlarla birlikte
bulunur. RESP üst verisi, tutulan anahtarlar, kuyruktaki istekler, future'lar ve çözümlenmiş uygulama
nesneleri ek bellek gerektirir. Daha geniş yığın payı ayırın veya içerik bütçesini düşürün.
Mapping/callback tamamlandıktan sonra uygulamanın tuttuğu tamamlanmış future'lar çalışma zamanının
sahipliği dışındadır.
Bekleyen asenkron kabul ayrıca kuyruk kapasitesi + türetilen I/O sınırı + asenkron doğrudan okuma sınırı
ile, en fazla 6144 istekle sınırlıdır. Süre aşımı teslimi, gerçek I/O ve callback'ler bitene kadar bu
kabul payını korur.

## Tahsis Güvenliği ve Cluster Davranışı

Lua betiği, MGET değerleri belleğe almadan önce her anahtar için STRLEN denetimi yapar; ardından istenen
PTTL değerlerini okur. İstemci ikili yanıt biçimini, TTL özel değerlerini ve gerçek boyutları da doğrular.
16 MiB deneme bütçesi tüm slotların toplamıdır; slot başına 16 MiB değildir.
Farklı slotlar kalan bütçeyle sırayla okunur; statik slot kotası uygulanmaz.

Çok kimlikli bir denemede içerik bütçesi aşılırsa sınırlandırılmış kısmi yanıt bırakılır ve en fazla 512
tekil kimlik yeniden okunur. Her kimlik, yapılandırılmış tekil bütçenin tamamını alır. Ayrı ayrı geçerli
satırlar, yalnız toplam grup büyük olduğu için reddedilmez. Belirlenmiş boyut hatasını yalnız kendi
varlık/silinme işareti çifti sınırı aşan kimlik alır. Grupları ayırma işlemi, teslimden önce 512 büyük
yanıtı biriktirmez. Kabul baskısı kuyruktaki bir okumanın süre aşımına uğramasına yine de neden olabilir.

Ayrılan grupların bekleyen istekleri AYNI adet ve bayt sınırına tabidir; ikinci bir bekleme kuyruğu yoktur.
Kuyruktan kabul edilen istek, I/O için seçildiğinde ve bütçe nedeniyle tekil yeniden okumaya geçtiğinde
ilk kabul payını korur. Böylece seçilmiş grubun arkasında dolu bir kuyruk daha birikerek sınır aşılamaz.
Tekil yeniden okumalar ilk sırayı korur ve yeniden birleştirilmez. Bekleyen tekil isteğin iptali kendi
kabul payını tam olarak kaldırır. Fiziksel I/O sürerken iptal veya süre aşımı olursa pay, I/O bitene kadar
tutulur. Bu bilinçli, ihtiyatlı hesap nedeniyle kuyruk geçici olarak boş görünse bile yeni iş reddedilebilir.
Normal FIFO ve ayrılan grupların bekleyenleri birlikte 4096 isteği ve 16 MiB kabul hesabını aşmaz.
Çağıranın yürüttüğü senkron taşma bekleyişi ayrıdır; en fazla 8192 bekleyen ve ayrıca 16 MiB kabul payı
ile sınırlıdır. 4096 değeri, iki yoldaki TÜM bekleyen çağıranların toplam sınırı değildir.
Taşma sırasında senkron çağıranlar kendi aralarında FIFO düzenini korur; yeni senkron çağrı baştakinin
önüne geçemez. Fiziksel ve yanıt payı elverdiğinde mevcut normal FIFO işleri önce boşaltılır.
Sürekli asenkron FIFO trafiği taşma isteğini kendi süre sınırına kadar geciktirebilir; tüm yollar arasında
koşulsuz adalet garantisi verilmez. Her pay serbest bırakma olayı yalnız taşma sırasının başını uyandırır;
tam bir yanıt payı daha varsa sonraki çağıran zincirleme uyandırılır.
Süre aşımı veya kesme, yalnız o çağıranın tam kabul payını kaldırır. Kapatma tüm taşma bekleyenlerini
reddetmek üzere uyandırır; bekleyen soket veya callback bitmiş gibi fiziksel ve yanıt payı bırakılmaz.

Standalone taşıma, pipeline içinde ikili EVAL kullanır. Cluster taşıma, MOVED slot yenilemesini ve hedef
bağlantıda ASKING yürütülmesini içeren normal Jedis ikili EVAL yürütücüsünü, sürücünün sınırlı deneme
ayarlarıyla kullanır. Ek bir taşıma yeniden denemesi yapılmaz. ACL, veri türü, bozuk yanıt ve soket
hataları sessizce yeniden denenmez veya korumasız GET'e düşürülmez. Farklı slotlar ve yönlendirmeler ek
ağ alışverişi gerektirebilir. EVAL; STRLEN, iç MGET ve isteğe bağlı PTTL içerir; bir mantıksal grup, Redis'te
tek komut anlamına gelmez. ACL izinleri EVAL, STRLEN, MGET ve PTTL işlemlerini kapsamalıdır.

## Süre Aşımı, İptal ve Kapatma

Birleştirme için zamanlayıcı beklemesi yoktur. Kabul edilen her asenkron isteğin sınırlı bir teslim alarmı
vardır. Alarm iş parçacığı I/O yapmaz, future tamamlamaz ve callback çalıştırmaz; süre aşımı teslimi
ayrıca başlatılır. Soket hâlâ beklerken etkin isteğin teslim süresi dolabilir. Future süre aşımına uğradı
veya iptal edildi diye fiziksel izinler ya da tutulan yanıt belleği serbest bırakılmaz.
JVM duraklamaları, CPU yetersizliği ve iş parçacığı oluşturma hataları kesin gerçek zaman garantisini
engeller.

Kuyruktaki iptal, isteği ve tam kabul bayt hesabını callback'lerden önce kaldırır.
Bir bekleyenin iptali ortak I/O'yu kesmez ve başka bir isteği iptal etmez.
Kuyruktaki senkron bekleme, monitor dışında süre sınırlı future beklemesiyle yapılır.
Senkron taşmanın condition bekleyişi kayıt tutma kilidini bırakır. Kesme, kesme isteyen çağıran görevi
iptali dahil, yalnız ilgili bekleyenin payını kaldırır; başka bir isteği iptal etmez.
Dışarıdaki çağıran future'ının kesme istemeden iptal edilmesi, çağıranın senkron işlemini durdurmaz.
Doğrudan çağıranın yürüttüğü senkron I/O, taşıma süre sınırlarına bağlıdır. Bekleme süre aşımı, nedeni `TimeoutException`
olan `JedisConnectionException` üretir.
Ham teslim süresi, uygulamanın mapping, kaynak erişimi veya fetch plan işlemlerinin tamamı için uçtan
uca süre sınırı değildir.

Kapatma yeni işleri reddeder, kuyruktakileri ayırır ve gerçek I/O için en fazla iki saniye bekler.
Etkin teslim alarmları bu süreden sonra da geçerlidir. Canlı I/O ve engellenen callback'ler daha sonra
bitebilir; kapanış bitmiş görünsün diye sayaçlar veya bellek payı sıfırlanmaz.
Okuma çalışma zamanını dışarıdan verilen istemciden önce kapatın; bu nesne Redis istemcisini kapatmaz.

## Ölçümler ve Sorun Giderme

`metrics()`, çalışma zamanı başına batches, coalescedRequests, averageBatchSize ve bypassed değerlerini
sunar. Batches, içerik bütçesi hataları dahil mantıksal taşıma denemelerini sayar.
CoalescedRequests, denenen gruplardaki ilk kimliğin üzerindeki kimlikleri sayar; kanıtlanmış ağ komutu
tasarrufu değildir. Bypassed, kapalı/doğrudan kabulleri ve bütçe hatasından sonra ayrılan kimlikleri sayar.

`deliveryMemory()`, reservedBytes, limitBytes ve batches değerlerini sunar. Ayrılan baytlar, etkin I/O
için azami yanıt paylarını ve bitmemiş mapping/callback'lerin tuttuğu gerçek ham içeriği kapsar.
Grup sayısında I/O'su bitmiş callback'ler bulunabilir. Dört taşıma sayacı tek başına tutulan yanıt
belleğini ölçmez.
`syncOverflowWaits()`, ayrı çağıran bekleme yolu için waiters, retainedBytes, limit ve byteLimit değerlerini
sunar. Taşma sırasında bekleyenler `syncDirect` I/O değildir ve ham asenkron teslim kabulünü tüketmez.

Reddetme veya süre aşımı artıyorsa sınırları büyütmeden önce kuyruk baskısını, deliveryMemory değerlerini,
yavaş callback'leri, Redis gecikmesini ve havuz beklemesini inceleyin. Boyut hatasında varlık ile silinme
işaretinin toplam boyutuna bakın.
ACL hatasında yalnız gerekli betik/komut izinlerini tanımlayın; korumalı yolu GET ile değiştirmeyin.
Cluster hatalarında topolojiyi, yönlendirmeleri ve yeniden deneme süresini inceleyin. Yakın önbellek,
topoloji belirsizken güvenli tarafta kalıp kabulü reddetmelidir; sürücünün içeride çözdüğü yönlendirme,
yerel geçersizleştirmenin hemen yapıldığını kanıtlamaz.
Performans amacıyla birleştirmeyi açmadan önce hedef ortamda Lua/STRLEN maliyetini ve farklı slot
gecikmesini ölçün.

