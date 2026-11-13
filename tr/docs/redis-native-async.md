# Redis Yerel Asenkron Okumaları

Redis varlık repository'leri, çalışma zamanının sahip olduğu `CacheAsyncExecutor` üzerinden yerel asenkron
tekil ve kimlik listesi okumaları sağlar. Ham taşıma katmanı, her istek için yürütücüde bir iş parçacığı
bekletmeden işleri kuyruğa alır. Java 21 gerekir. Asenkron API, Jedis'i bloklamayan bir istemciye dönüştürmez;
soket I/O'su yine sınırlandırılmış sanal iş parçacığı gruplarında yürür.

## Kullanım ve Yapılandırma

```java
var options = CacheRuntimeOptions.builder()
        .asyncMaxConcurrent(1024)
        .redisReadCoalescing(RedisReadCoalescingConfig.builder()
                .maxLiveReplyBytes(64L * 1024 * 1024)
                .build())
        .build();
// options nesnesini CacheDatabase constructor veya bootstrap yapılandırmasına verin.
var async = repository.async();
CompletableFuture<Optional<Customer>> row = async.findByIdAsync(id);
```

Çalışma zamanı, repository'ler arasında tek bir kabul denetimini ve ham okuma çalışma zamanını paylaşır.
Seçenekler değişmezdir; builder/toBuilder desteklenir. Mevcut temel yapılandırma kaydı değişmez.
`asyncMaxConcurrent` varsayılan olarak 1024'tür ve 1..65536 değerlerini kabul eder. Bu, kabul üst sınırıdır;
verim önerisi veya JVM bellek bütçesi değildir. Core kabulü hem yerel işlemleri hem yürütücüye aktarılan işleri kapsar.

Spring seçenekleri arasında `cachedb.async.max-concurrent`, `cachedb.redis.read-coalescing.enabled` ve
`cachedb.redis.read-coalescing.max-live-reply-bytes` bulunur.
Tek veya primary Executor bean'i, alternatif yollar için dışarıdan alınarak kullanılır. Birden fazla belirsiz
aday varsa rastgele seçim yapılmaz; başlangıç hata verir. CacheRuntimeOptions bean'i ya da sıralı
CacheRuntimeOptionsCustomizer ile açık seçim yapılabilir.
Dış yürütücü yoksa çalışma zamanı, sınırlandırılmış sanal iş parçacığı yürütücüsünün sahibidir.
Temel yapılandırmadaki `traceContextPropagator` korunur.

Üretilen makbuz odaklı repository arayüzü de `asyncExecutor()` çağrısını alttaki repository'ye devreder.
Böylece asenkron metotları aynı kabul denetimini kullanır; ancak bu arayüz sınırlandırılmış yürütücü aktarımı
kullanmayı sürdürebilir. Buradaki yürütücüye aktarmadan okuma uygunluğu, RedisEntityRepository'nin
AsyncEntityRepository API'si içindir.

## Desteklenen Yollar

Fetch plan içermeyen uygun bir L1 isabeti, zaten tamamlanmış bir future döndürebilir.
Uygun `findHotByIdAsync` kaçırmaları doğrudan ham çalışma zamanına alınır; isabet, silinme işareti,
politika dışı ve önbellekte yok sonuçları korunur.
`findByIdAsync` aynı yolu kullanır. Kaynak yükleme kapalıysa veya yükleyici kayıtlı değilse yalnızca boş
sonuç dönmek için yürütücü işi başlatılmaz. Gerçek kaynak başvurusu yalnız sınırlandırılmış yürütücüde
çalışır. Eksik yükleyici hatası yapılandırılmış politikaya uyar.

`findAllAsync` en fazla 100000 null olmayan kimlik kabul eder. Ham okumada yinelenen kimlikleri birleştirir;
sonuçta giriş sırasını ve tekrarları korur. Değişebilir yinelenen varlıklar ayrı nesneler olarak çözümlenir.
Bir pencerede en fazla 512 benzersiz tekil işlem açıktır. Sonraki pencere hem veriyi hem gerçek fiziksel
tamamlanmayı bekler. Benzersiz sonuçlar ve tekrarları genişletilmiş sonucun kodlanmış boyutu ayrı ayrı
16 MiB ile sınırlıdır. Bulunamayan, silinmiş veya politika dışı satırlar yerel toplu okuma sözleşmesine
göre atlanır; bu yol SQL kaynak yüklemesi eklemez. Yönlendirme sınırları ve daha düşük arka uç sınırları geçerlidir.

Nedensel toplu okumalar, 128 kimlikli sınırlandırılmış yürütücü gruplarında sürümü yük gözleminden ÖNCE
doğrular; doğrulanmamış bir SQL sonucu kullanmaz. Yalnız betiğin kendi içerik bütçesi hatası tekil okumalara
ayrılabilir. Asenkron toplu fetch plan işlemleri açıkça reddedilir; ilişki yükleyicilerinde uygulanabilir
sonuç boyutu sözleşmesi henüz yoktur. Tekil fetch plan ve kaynak başvuruları sınırlı yürütücüde çalışır;
yükleyici ve codec uygulamanın boyut ve süre sözleşmelerine uymalıdır.
Etkin senkron iş birimi içindeki işlemler çağıran üzerinde reddedilir; iş birimi başka iş parçacığına taşınmaz.
Save/delete/query/aggregate metotları da yerel ham okuma yerine sınırlandırılmış yürütücü aktarımını kullanır.

Query/page/count/aggregate/partition okumaları, uygulanmış sürümü doğrulayamadığında ilgili etkin nedensel
gereksinimleri `CacheCausalReadUnsupportedException` ile reddeder.
Açık bir uygulanmış sürüm protokolü olmayan projection'lar herhangi bir etkin nedensel gereksinimi reddeder.
ThreadLocal yakalamak veya makbuz taşımak bu sonuç kümelerini doğrulamaz. Nedensel garanti için sürümlü
tekil/toplu okumaları kullanın; sorgu sonucu almak için gereksinimi sessizce devre dışı bırakmayın.

## Bağlam, İptal ve Kapatma

Kiracı, nedensel gereksinim, yapılandırılmış iz ve yönlendirme bağlamı gönderim sırasında yakalanır;
mapping, politika, çözümleme, kaynak başvurusu ve yakın önbellek kabulü çevresinde geri yüklenir.
Herkese açık callback'lerden önce tamamlama iş parçacığının eski bağlamı geri konur.
Ham grup, kiracı sahipliğine karar vermez veya GlobalRaw yükünü yorumlamaz.
Yakın önbellek kabulü I/O öncesindeki geçersizleştirme durumunu denetler; geç dönen okuma, geçersiz kılınan
kaydı yeniden doldurmamalıdır.

İptal yalnız işlemin kendi ham bekleyenlerini veya alternatif yol future'ını ayırır; ortak I/O kesilmez.
Teslim süresi soket bitmeden dolabilir. Fiziksel kabul, gerçek I/O ve bağlamlı tamamlama işlemesi çıkana
kadar korunur; future iptali tek başına fiziksel tamamlanma değildir.
Yerel ham future'ı beklemek için ayrıca iş parçacığı veya yürütücü gönderim izni tüketilmez.

Çalışma zamanı ham okumaları boşaltıp yürütücüyü ve istemcileri kapatmadan önce yeni asenkron kabulü durdurur.
Dışarıdan verilen uygulama yürütücüsü CacheDB tarafından kapatılmaz.
Yerel okumanın teslim süresi, yapılandırılmış okuma süresini mapping ve sınırlı yürütücü başvurularında da
kullanır. Çalışma zamanına ait sınırlı alarm mekanizması süre aşımını ayrıca teslim eder; zamanlayıcı iş
parçacığında I/O veya callback çalıştırmaz. Decoder, yükleyici veya soket sürerken herkese açık future hata
ile bitebilir. Bu işler kesilmiş ya da fiziksel olarak bitmiş sayılmaz; sonuç ve teslim kabulü üretici ile
callback'ler gerçekten çıkana kadar tutulur.
Çağıranın verdiği koleksiyonun dolaşılması, API dönmeden çalışan codec/bağlam yakalama kodu, JVM duraklamaları
ve kendi iş parçacığını bekleten uygulama callback'leri zorla durdurulamaz veya kesin süreye bağlanamaz.
Havuz, soket ve kaynak erişim sürelerini sonlu yapılandırın. Satır içi callback'leri bekletmeyin.

## Kapasite ve Gözlemlenebilirlik

Ham yanıt belleği fiziksel I/O'dan ayrı denetlenir. Bellek payı tahsisten önce ayrılır; mapping/callback
çıkana kadar hesaptan düşülmez. Ayrıntılar için [sınırlandırılmış tekil okumalar](redis-read-coalescing.md).
Küresel yanıt bütçesi düşükse havuzda boş bağlantı olsa bile gerçek I/O eşzamanlılığı azalabilir.
Birleştirme kapalıyken de yerel asenkron okumalar tekil FIFO kabulünü kullanır; havuzda sınırsız bekleyen oluşturmaz.

Bir çalışma zamanındaki TÜM repository görünümleri ayrı bir yerel sonuç bütçesini paylaşır. Bu sınır,
maxLiveReplyBytes ile 128 MiB'nin küçük olanıdır; ham yanıt bütçesinden ayrıdır.
Her toplu çağrı, giriş koleksiyonu dolaşılmadan ÖNCE 48 MiB ayırır: benzersiz kodlanmış/çözümlenen içerik
için 16 MiB, yinelenen kimliklerle genişletilmiş sonuç işi için 16 MiB ve geçici okuma alanı için 16 MiB.
Varsayılan sınır iki eşzamanlı toplu okumayı, 64 MiB ayarı birini kabul eder. 48 MiB'den düşük sınırda
toplu çağrı kapasite nedeniyle reddedilir; bu, varlığın boyut sınırını aştığı anlamına gelmez.
Yürütücü başvurusu 32 MiB ayırır. Ham yanıta dayanan tekil kaçırma ek içerik payı ayırmaz; ancak tüm yerel
teslim payları ve alarmları çalışma zamanı başına en fazla 6144 adettir. Core kabulü daha düşük sınır koyabilir.

Her benzersiz yük, codec çalışmadan önce hesaba katılır. Tekrarlarla genişleyen sonuç önce doğrulanır;
değişebilir tekrarlar sınırsız yeniden serileştirme yerine eldeki sınırlandırılmış yükten çözümlenir.
Sonuç payı giriş hazırlığı, gerçek üretici ve satır içi tamamlama/iptal callback'leri bitene kadar tutulur.
İptal veya süre aşımı, çalışma zamanı sonucu hâlâ tutabiliyorken bellek kapasitesini yeniden kullanıma açmaz.

Bunlar kodlanmış içerik ve kabul sınırlarıdır; JVM yığınının kesin üst sınırı değildir.
Özel codec, kaynak yükleyici ve ilişki uygulamaları kendi azami genişleme/sonuç boyutu sözleşmesini sağlamalıdır;
SDK keyfî nesne ağacını inceleyemez veya zorla durduramaz. Metinler, RESP tamponları, indeksler, future'lar,
iş parçacığı yığınları ve uygulamanın tuttuğu sonuçlar ek bellek ister. Varsayılan ham ve yerel rezervasyon
üst sınırları ayrı ayrı 256 MiB ve 128 MiB'dir. Pod belleğini ikisi ve ek yük için boyutlandırın veya ayarı
düşürün. Bu API sınırsız veri dışa aktarımı için değildir.

Yürütücünün `snapshot()` sayaçları iş parçacığına aktarılan işleri saymayı sürdürür; yerel kabul, yürütücü
gönderimi sayılmaz. `externalSnapshot()`, yerel dış tamamlama kabulünü, bekleyen işlemleri ve sonuçlarını sunar.
Küresel kullanılabilir izinler iki yolu da kapsar. Taşıma ölçümleri ağ komutunu değil, mantıksal denemeyi sayar.
`CacheDatabase.nativeReadMemory()`, ham deliveryMemory değerlerinden ayrı olarak reservedBytes, limitBytes,
deliveries ve expiryWorkers ölçümlerini sunar. İki bütçeyi, kuyruk baskısını, havuz beklemesini, Redis/kaynak
gecikmesini ve callback süresini birlikte izleyin.
Otomatik taşıma/kaynak yeniden deneme döngüsü eklenmez; değişikliklerden sonra açık kimlik/makbuz kurallarıyla toparlanın.
