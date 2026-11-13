# Spring Cache Bağdaştırıcısı

Java 21 üzerinde, mevcut sağlayıcıdan bağımsız starter içinde isteğe bağlı
senkron Spring Cache desteğidir. Annotation desteğini açıkça @EnableCaching
ile etkinleştirin. Metot sonuçları için entity binding veya SQL kalıcılığı
gerekmez.

Bu bağdaştırıcı yalnızca senkron metot değerlerini destekler. Cache.retrieve
metodunun iki biçimi de Spring'in isteğe bağlı UnsupportedOperationException
varsayılanını korur. Spring, CompletableFuture ve reactive @Cacheable
dönüşlerinde sync=true dahil retrieve kullanır; bu dönüş tipleri desteklenmez
ve kaynak metot çağrılmadan hata verir. Bu metot önbelleği annotation'ları
olmadan ayrı asenkron/reactive repository API'lerini kullanın. Bloklayan bir
retrieve uyarlaması eklenmez.
Bkz. [Spring Cache](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/Cache.java)
ve [annotation yönlendirmesi](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/interceptor/CacheAspectSupport.java).

## Yapılandırma

~~~yaml
cachedb:
  spring-cache:
    enabled: true
    key-prefix: orders-prod
    max-concurrent-operations: 256
    max-in-flight-keys: 1024
    max-waiters: 4096
    load-timeout: 30s
    caches:
      prices:
        ttl: 5m
        tenant-scoped: true
        allow-null-values: false
        max-value-bytes: 1048576
        near-cache:
          max-entries: 10000
          max-bytes: 8388608
          ttl: 30s
          soft-ttl: 10s
~~~

Yalnızca bildirilen 1..256 önbellek oluşturulur; dinamik veya sınırsız önbellek
oluşturulmaz. TTL zorunludur, pozitif ve tam milisaniye cinsinden olmalıdır;
üst sınırı yedi gündür. Bir baytlık null/değer zarfı dahil varsayılan değer
sınırı 1 MiB, kesin üst sınır 16 MiB'dir.

NearCachePolicy kayıt sayısı, bayt, TTL ve soft-TTL sınırlarını doğrular.
Bir manager içindeki bütün L1 bölümlerinin toplam sınırı 64.000 kayıt ve
64 MiB'dir. Kesin TTL en fazla 60 saniyedir ve Redis TTL'ini aşamaz. Soft TTL
sıfır veya kesin TTL'den küçüktür. Soft TTL dolunca eski değer sunulmaz;
senkron Redis okumasına geçilir. L1 yalnızca kodlanmış String değerleri tutar;
her çağıran değeri ayrı çözer.

Nesil (generation) ipuçları ayrıca önbellek başına 256, manager başına
4096 kapsamla sınırlıdır. Yerel ipucu TTL'i L1'in kesin TTL'ini aşmaz; L1
kapalıysa değer TTL'i ile 60 saniyenin küçük olanını aşamaz. LRU ile çıkarılan
kapsamın epoch kimliği, değer kayıtları taranmadan geçersizleşir. Bellek
hesabına String depolamasının yanında kayıt başına ihtiyatlı bir 768 bayt
payı eklenir. Bunlar muhasebe sınırlarıdır; ölçülmüş JVM heap boyutu değildir.

Aynı veriyi paylaşan replikalar uyumlu TTL/null/codec/anahtar şemaları
kullanmalıdır. Uyumsuz geçişlerde yeni bir önek veya önbellek adı seçin. Önek,
dağıtım ve Redis veritabanı bazında ayrılmalı; Redis hash tag için kullanılan
süslü parantezleri içermemelidir. DataSource kullanmayan, yalnızca önbellek
amaçlı bir uygulamada cachedb.enabled=false ayarlayın ve sınırları açıkça
belirlenmiş bir RedisClientOperations bean'i sağlayın. İkinci bir bağlantı
fabrikası oluşturulmaz.

## Spring Bağlantıları

cachedb.spring-cache.enabled=true özelliği desteği açar. Uygulama bir
CacheManager sağlıyorsa bu otomatik yapılandırma tamamen geri çekilir.
@EnableCaching ayrıca uygulama tarafından etkinleştirilmelidir.

Redis istemcisi sırasıyla mevcut RedisClientOperations, CacheDatabase içindeki
redisOperations() veya cacheDbJedisPooled adlı bean üzerinden seçilir.
CacheDatabase varsa abonelik için backgroundRedisOperations() kullanılır;
yoksa aynı RedisClientOperations kullanılır. Birden fazla aday varsa tek
seçimi @Primary veya açık yapılandırmayla belirleyin. İstemciler ödünç alınır;
manager kapatılırken kapatılmaz.

Oluşturulan manager bean'inin adı cacheDbCacheManager'dır.
cacheDbKeyGenerator adlı KeyGenerator bean'i yalnızca aynı adlı bir bean
yoksa sağlanır. Önbelleğe özel değer kodlayıcısı için bir
CacheDbCacheCodec<V> bean'i tanımlayın. Micrometer MeterRegistry mevcutsa
önbellek ölçümleri bağlanır. Hiçbir bean SQL/entity işlemlerini veya uygulamanın
erişim denetimini kendiliğinden devralmaz.

## Annotation ve Anahtar Sözleşmesi

~~~java
@EnableCaching
@Configuration(proxyBeanMethods = false)
class MethodCachingConfiguration {}

@Service
class Prices {
    @Cacheable(cacheNames = "prices", sync = true)
    public String read(String sku, int branch) {
        return queryPriceWithAnApplicationDeadline(sku, branch);
    }
    @CachePut(cacheNames = "prices")
    public String update(String sku, int branch) {
        return updatePriceAndReturnIt(sku, branch);
    }
    @CacheEvict(cacheNames = "prices", allEntries = true)
    public void clearCurrentTenant() {}
}
~~~

Uygulamanın CachingConfigurer bean'i yoksa bağdaştırıcı kendi
CachingConfigurer bean'i üzerinden CacheDbKeyGenerator'ı varsayılan yapar.
Mevcut bir CachingConfigurer'ın üzerine yazılmaz. Böyle bir bean varsa
@CacheConfig(keyGenerator = "cacheDbKeyGenerator"), ilgili metot niteliği
veya CachingConfigurer.keyGenerator() üzerinden bu bean'i açıkça seçin.

Sıfır argüman doğrudan SimpleKey.EMPTY üretir. Null olmayan tek skaler argüman
kendi tipli kimliğini kullanır; sıradan tek String de doğrudan korunur.
Ayrılmış ck1: önekiyle başlayan String, üretilen bileşik String anahtarlarla
çakışmayan ayrı bir alana kodlanır. Birden çok argüman ve tek null; tip,
argüman sayısı ve tam uzunluk çerçevesi içeren String anahtara dönüştürülür.
Metot adı kimliğe eklenmez: aynı önbellekte eşit argümanlar bilerek aynı
kaydı paylaşır.

Doğrudan Cache anahtarları temel tiplerin sarmalayıcılarını, String, sonlu
sayı, UUID, boyutu sınırlı BigInteger/BigDecimal ve SimpleKey.EMPTY değerlerini
destekler. Farklı tipler farklı kimliklerdir. Diğer nesneler ve diziler için
açık bir String KeyGenerator gerekir. Girdi en fazla 65.536 karakter,
bileşik anahtar en fazla 128 argüman, sayısal girdi en fazla 4096 bittir.
UTF-8/base64url çıktısı 1024 karakteri aşarsa tam tipli girdinin SHA-256 özeti
h: önekiyle kullanılır. Bozuk Unicode reddedilir. Kimlik için toString veya
hashCode kısayolu, reflection ya da Java nesne serileştirmesi kullanılmaz.

**Dolu SimpleKey sınırı:** Spring 6.1'in parametreleri dışarıya açılmayan dolu
SimpleKey nesneleri desteklenmez. Reflection veya kayıplı metin dönüşümü yerine
çözüm yolunu belirten bir configurer/KeyGenerator hatası verilir. Normal çok
argümanlı annotation kullanımı, sağlanan varsayılan String üreticisiyle
otomatik çalışır. Mevcut CachingConfigurer bu üreticiyi açıkça seçmelidir.
Bkz. [Spring SimpleKey kaynak kodu](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/interceptor/SimpleKey.java).

## Gerçek Sıfır RTT L1 ve Atomik Okuma

Geçerli ve soft TTL'i dolmamış bir L1 isabeti **sıfır Redis I/O** yapar.
Nesil/sürüm sorgusu, istemci erişimi, GET/PTTL/EVAL veya isabet başına sağlık
yoklaması yoktur. Nesil ipucu ve değer, sınırlandırılmış yerel bellekte tutulur;
etkin abonelik oturumuna ve kapsamın epoch kimliğine bağlıdır. Kabul edilmiş,
değişmez kodlanmış değer her isabette yeniden UTF-8 bayt dizisine kopyalanmaz
ve doğrulanmaz.

İsteğe bağlı önbelleğin Redis anahtar düzeni nesle göre adreslenir:

~~~text
<prefix>:scache:<cacheName>:{<sha256(typed-cache/tenant-scope)>}:generation
<prefix>:scache:<cacheName>:{<scope>}:state
<prefix>:scache:<cacheName>:{<scope>}:revision:<encodedKey>
<prefix>:scache:<cacheName>:{<scope>}:g<incarnation>-<generation>:<encodedKey>
~~~

Nesli bilinen normal bir L1 kaçırması **tek EVAL** ile nesil/incarnation,
anahtar sürümü, değer ve STRLEN/PTTL sınırlarını atomik olarak okur.
Ayrı durum sorgusu ve değer sorgusu yapılmaz. Birleştirilmiş yüklemenin sahibi,
kaynağı çağırmadan önce bu Redis okumasını tekrarlamaz; ikinci kontrol yalnızca
yereldir. Kaynak tamamlandıktan sonraki koşullu önbellek yazması ayrı işlemdir.

Lua'nın eriştiği bütün gerçek Redis anahtarları KEYS içinde bildirilir ve aynı
Cluster slot'undadır. Bilinmeyen veya eski nesil ipucu sınırlı yeniden eşitleme
ve yeniden deneme gerektirebilir. EVAL ile ilk kez oluşturulan kapsam normalde
tek çağrıda tamamlanır; ipucu olmadan mevcut kapsama katılmak, süresi dolmuş
ipucu veya eşzamanlı clear ek çağrı gerektirebilir. Toplam en fazla üç deneme
yapılır; ardından belirli bir çekişme hatası verilir. Nesli bilinmeyen her ilk
okuma için tek RTT garantisi yoktur. Lua, anahtar adlarını metadata'dan üretip
Redis'in açık anahtar bildirim sözleşmesini aşmaz.
Bkz. [Redis EVAL sözleşmesi](https://redis.io/docs/latest/commands/eval/).

clear() tek O(1) Lua çağrısında INCR ile nesli artırır ve PUBLISH yapar.
Önden nesil okuması, KEYS/SCAN veya kayıt silme döngüsü yoktur. Eski değerler
kendi TTL'lerini korur. Yerel ve diğer replikalardaki kapsam epoch değişimi,
eski L1 kayıtlarını O(1) işlemle erişilemez kılar. Yeni kayıt kabulünde en fazla
16 eski kayıt temizlenir; LRU bayt/kayıt sınırları uygulanır. Süresi dolmuş
veri sunulmaz. Arka planda heap temizleme iş parçacığı olmadığından, kullanılmayan
eski L1 kayıtları yeni etkinliğe veya bağlantı temizliğine kadar sınırlı yer
tutabilir. CLEAR/reset bildirimi yeni incarnation/sayacı taşır ve yalnızca
önceden bilinen kapsamın ipucunu yeniler. Bildirimler sınırsız tenant kataloğu
oluşturamaz. invalidate(), önceki doluluk taranmadan bilinemediği için false döner.

Kapsam metadata'sı ve anahtar sürüm izlerinin boşta kalma TTL'i, değer TTL'i
artı 60 saniyedir. Eksik generation/state yeni rastgele incarnation oluşturur;
eski sıfırıncı nesil yeniden kullanılmaz. Eksik anahtar sürümü yeni belirteç
alır. Bunlar kimlik ve eşzamanlılık belirteçleridir, kimlik doğrulama sırrı
değildir. İsteklerin sanal iş parçacığı yolunda SecureRandom aygıt I/O'su yapılmaz.

## Pub/Sub ve Hata Davranışı

Kanal <prefix>:scache:invalidation, protokol ise boyutu sınırlı sc1 KEY/CLEAR
mesajlarıdır. Başarılı put/evict/load işlemi ve KEY bildirimi aynı Lua betiğinde
atomiktir. Clear/reset yeni nesli yayımlar. KEY tam olarak ilgili mantıksal
kaydı; CLEAR kapsamı, nesil ipucunu ve epoch kimliğini geçersiz kılar.
Yerel yazma yolu, sonucu belirsiz hatalar dahil, betikten önce ve sonra
ilgili yerel kaydı geçersiz kılar.

Okuma bileti; oturum kimliğini, kapsam kimliğini ve sınırlı sayıda şeride
ayrılmış kayıt epoch'unu **sunucudan okumadan önce** alır. Yerel kayıt kabulü,
bunları geçersizleştirmeyle aynı bellek içi bölüm kilidi altında kontrol eder.
KEY, clear, kapsamın çıkarılması, bağlantı kopması veya yeniden bağlanma
sonrasında geç gelen eski cevap L1'e kabul edilmez. Şerit çakışması yalnızca
fazladan kabul reddine yol açar; değer kimliği olarak kullanılmaz.
ReentrantLock tutulurken kaynak/Redis/future/socket beklenmez.

Abonelik kaybolunca bütün yerel değerler ve nesil ipuçları temizlenir;
L1 okuması ve yeni kayıt kabulü kapanır. Başarılı subscribe ACK tekrar temizlik
yapar ve yeni oturum açar; eski bilet yeniden geçerli olamaz. Bağlantı yokken
okumalar sınırlı, atomik Redis yolunu kullanır; L1'e yeni kayıt kabul edilmez.
Redis işlem hatası da L1'i kapatır ve sahip olunan dinleyiciyi keserek yeni
ACK gerektirir. Redis yedek okuması da başarısızsa hata iletilir;
doğrulanmamış bir kaçırmaya dayanılarak kaynak çağrılmaz.

**Tutarlılık sınırı:** PUBLISH, diğer replikanın bildirimi işlediğini onaylamaz.
Diğer replikadaki görünürlük, uzaktaki yazmanın dönüşüyle değil bildirimin
işlenmesiyle oluşur. Pub/Sub en fazla bir kez teslim sağlar; henüz bilinmeyen
ağ kopması anında tespit edilemez. Tespit edilen kopma aralıkları temizlik
ve yeniden bağlanma korumasıyla ele alınır. Kaybolan mesajlar yeniden oynatılmaz;
dışarıdan yapılan keyfî Redis yazmaları, eviction ve failover otomatik olarak
izlenmez. Senkron doğrulama olmadan replikalar arası doğrusal tutarlı okuma
vaat edilmez. Desteklenmeyen dış değişikliklerde veya tespit edilmemiş
kopmalarda eski verinin yerel kalma süresini TTL sınırlar. Daha güçlü garanti
gerekiyorsa L1'i kapatın. Otomatik CLIENT TRACKING bağlantısı sağlanmaz.
Bkz. [Redis Pub/Sub teslim sınırları](https://redis.io/docs/latest/develop/pubsub/).

## Yükleme ve Eşzamanlılık Sınırları

get(key, Callable) ve @Cacheable(sync = true), sınırlandırılmış
SingleFlightLoader kullanır. Aynı RedisClientOperations nesnesini ve öneki
kullanan manager'lar JVM/classloader içindeki yükleme grubunu paylaşır.
Farklı istemci nesnelerinin aynı sunucuya bağlandığı varsayılmaz. Önbellek
tanımları ve yükleme sınırları aynı olmalıdır. Açık codec bağlamaları yalnızca
aynı sınıfı değil, paylaşılan aynı bean nesnesini kullanmalıdır; kurucu ayarları
veri şemasını değiştirebilir. Varsayılan skaler codec'ler, önbellek tanımları
eşleştiğinde uyumludur.

JVM kayıt tablosu en fazla 256 grup, 1024 kullanım hakkı, toplam 65.536 ayrılmış
anahtar ve 1.048.576 ayrılmış bekleyen çağrı ile sınırlıdır. Son boşta kullanım
hakkı kapatılınca grup kaldırılır. Sahipsiz kalan etkin yüklemeler, aynı kaynak
işini ikinci kez başlatmaya izin vermek için atılmaz. Dağıtık single-flight
garantisi yoktur.

Mantıksal çağrı Redis okumasından önce kaydedilir. Önceden kaydolmuş fakat
kaçırma cevabı gecikmiş çağrı, eşzamanlı tamamlanan yüklemeyi paylaşır; kaynak
ikinci kez çalıştırılmaz. Değişmez kodlanmış sonuç, örtüşen son çağrı ayrılana
kadar tutulur. Tamamlanmadan sonra kaydolan çağrı bu sonucu kullanamaz.
Nesil ve anahtar sürümü gerçek değişiklikleri ayırmaya devam eder.
Etkin mantıksal anahtar başına en fazla bir tamamlanmış sonuç tutulur.
JVM genelinde sınır 64 MiB'dir; sonuç başına 256 bayt ile kodlanmış değer ve
durum anahtarındaki karakterlerin iki katı hesaplanır. Bütçe dolunca gecikmiş
çağrı, kaynağı yeniden başlatmak yerine açık bir yeniden okuma hatası alır.
Zamanlayıcı, tamamlanmış sonuç önbelleği veya ek Redis kontrolü yoktur.
Kayıt sayısı max-in-flight-keys (kesin üst sınır 4096) ve max-waiters
(kesin üst sınır 65.536) ile sınırlanır. Programatik
SingleFlightConfig.maxResultCharacters, Redis'e yayımdan önce uygulanır ve
16 MiB'yi aşamaz. Karakter sınırı, önbelleğin UTF-8 bayt sınırının yerine geçmez.

Yükleme kimliği tenant/incarnation/nesil/anahtar/anahtar sürümünü içerir.
Koşullu LOAD; put/evict/clear yarışlarını ve olmayan anahtarın evict işlemini de
algılar. İlgisiz bir anahtarın yazılması yüklemeyi reddetmez. Kaynak körlemesine
yeniden çağrılmaz. Sahip Callable, açık bağlam sarmalayıcılarıyla çağıranın
platform veya sanal iş parçacığında çalışır; I/O sırasında monitor tutulmaz.
Bekleyen çağrıların bekleme süresi sınırlıdır ve kesilme durumu korunur.
load-timeout yalnızca bekleyen çağrıları sınırlar; kaynağın JDBC/HTTP işlemi
kendi son süresini uygulamalıdır. Takılan kaynak sınırlı permit/yükleme tutar.

## Tenant ve Nedensellik Güvenliği

tenant-scoped: true olan önbellekte clear/invalidate dahil her işlem için
CacheTenantContext.currentTenant() zorunludur. Administrative kapsam tek başına
bu koşulu aşamaz. Tenant, tipli kapsam özetine katılır; ham Redis hash tag olarak
kullanılmaz. Global önbellek bilerek globaldir; tenant'a duyarlı değerler
buraya konmamalıdır.

Boş olmayan CacheCausalContext, sıcak L1 dahil her önbellek erişiminden önce
açık hatayla reddedilir. Otomatik entity RYOW, SQL/kaynak güncelliği, transaction
rollback veya failover kalıcılığı garantisi verilmez. Bu bağdaştırıcı bir
yetkilendirme katmanı değildir.

**Annotation sınırı:** @CachePut kaynak metodu çalıştırdıktan sonra önbelleğe
yazar; @CacheEvict varsayılan olarak başarılı metot dönüşünden sonra çalışır.
Önbellekteki tenant/causal reddi bu metotların kaynak yan etkilerini geri alamaz.
Kaynak erişimini ve bağlamını metot girişinde uygulama doğrulamalıdır.
beforeInvocation=true ile yapılan eviction metottan önce çalışır; ancak tek
başına kaynak işlemini atomik yapmaz. Annotation koşulları veya proxy'yi
atlayan çağrılar önbellek erişimini atlıyorsa, önbellek korumaları kaynak
çağrısının güvenliğini sağlamaz.
Bkz. [Spring CachePut](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/annotation/CachePut.java)
ve [Spring CacheEvict](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/annotation/CacheEvict.java).

~~~java
try (var tenant = CacheTenantContext.withTenant("tenant-a")) {
    prices.read("SKU-42", 3);
}
~~~

Bağlamı farklı bir iş parçacığına taşırken CacheTenantContext.wrap ve
CacheCausalContext.wrap ile açıkça aktarın. Doğrudan ThreadLocal değerlerinin
kendiliğinden taşındığını varsaymayın.

## Değer Kodlayıcıları

Varsayılan JSON codec mevcut Jackson token API'sini kullanır; ObjectMapper
databinding, default typing, reflection veya Class.forName kullanılmaz.
Sonlu skaler değerlerin Java tipleri korunur. POJO ve koleksiyonlar için
CacheValueCodec<V> SPI'ına bir CacheDbCacheCodec<V> bean'i açıkça bağlanmalıdır.
Üretilmiş StreamingJsonCodec doğrudan kullanılabilir; uygulama cachedb-json
bağımlılığını açıkça eklemelidir.

Özel codec iş parçacığı güvenli, deterministik ve reflection kullanmayan bir
uygulama olmalı; kendi tahsis ve derinlik sınırlarını uygulamalıdır.
Bağdaştırıcı UTF-8 boyutunu bayt kopyası ayırmadan, Lua ise saklanan boyutu
GET'ten önce sınırlar. Büyük değerler saklanmaz.

Null ayrı bir zarftır, kaçırma değildir. Null kapalıysa put(null) reddedilir;
Callable null sonucu bekleyenlerle paylaşılır ama önbelleğe alınmaz.
Her çağıran değeri ayrı çözer; değiştirilebilir çözülmüş nesne grafiği paylaşılmaz.

## Kapasite ve İşletim

Kodlayıcı işi dahil işlem kabulü kuyruksuz Semaphore ile sınırlanır:
varsayılan 256, kesin üst sınır 4096'dır. Sınır aşımında süresiz beklemek yerine
hata verilir. Sınırı kullanıcı sayısına değil CPU, konteyner belleği ve Redis
kapasitesine göre ayarlayın. TTL yaşam süresini sınırlar; sınırsız yeni anahtar
gelişinde toplam baytı sınırlamaz. Redis maxmemory ve üst katmanda istek/anahtar
hızı sınırları gereklidir. CacheErrorHandler tenant/codec/causal/eşzamanlılık
hatalarını yutmamalıdır.

Her manager en fazla bir sanal abonelik iş parçacığı ve tek kullanımlık,
sınırlı bir kapatma yardımcısı oluşturur. Executor, yenileme kuyruğu veya sanal
iş parçacığı havuzu yoktur. L1 kapalıysa nesil ipuçlarını tutmak için abonelik
ilk Redis işleminde başlar; L1 açıksa kayıt tamamlanınca başlar. Bu davranış
spring.threads.virtual.enabled değerinden bağımsızdır; çağıranın iş parçacığı
türü değiştirilmez.

Havuzda abonelik bağlantısı ayırın. Bağlantı edinme, bağlanma, okuma ve kopma
tespiti zaman aşımı süreleri sonlu olmalıdır. Arka arkaya 32 abonelik denemesi
ACK almadan biterse L1 kapalı kalır; işletim sırasında manager yeniden
oluşturulmalıdır. Yeniden deneme aralığı 100 ms..5 s'dir. Kapatma en fazla iki
saniye bekler; bağlantı temizliğinin tamamlanması ödünç istemcinin sonlu
zaman aşımlarına bağlıdır. L1 isabet yoluna gizli heartbeat RTT eklenmez.

İstatistikler ve Micrometer; hits, near.hits, misses, puts, evictions, clears,
failures, fenced.loads, rejected, inflight, waiters, invalidation.connected,
late.fills.rejected, notifications, disconnects ve generation.scopes ölçümlerini
sunar. Etiketlerde yalnızca sabit önbellek adları vardır; tenant/anahtar/değer
etiketleriyle sınırsız çeşitlilik oluşturulmaz.

## Metadata Yaşam Döngüsü ve Tenant Verilerinin Silinmesi

Redis TTL'i sabit T olan önbellekte (en fazla yedi gün), generation ve state
kalıcı değildir; boşta kalma süreleri T + 60 saniyedir. Anahtar başına
revision/tombstone aynı süreyi kullanır. Her Redis READ/PUT/NX/LOAD/CHECK/EVICT
işlemi generation/state süresini kendi Lua değerlendirmesinde yeniler.
clear, INCR/PUBLISH değerlendirmesinde bu süreyi yeniler. Revision süresi
anahtara erişimde yenilenir. Gerçek yerel L1 isabeti Redis süresini yenilemez
ve ağ I/O'su yapmaz.

Yeni state, 36 karakterlik incarnation String'idir; nesil sayacı sıfırlanmadan
önce tek SET PX komutuyla oluşturulur. Generation, revision ve değerler de
oluştukları komutta süre alır. Sonraki PEXPIRE çağrısının ACL/çalışma zamanı
hatası, yeni state anahtarını kalıcı bırakamaz. Lua hatası rollback değildir;
sonucu belirsiz hatalar iletilir ve yerel kabul kapatılır. Aynı veriyi paylaşan
replikalar aynı TTL/şemayı kullanmalıdır. State düzeni String'dir; uyumsuz
metadata düzenlerini aynı önekte karıştırmayın. Uyumsuz geçişte yeni önek seçin.

Son başarılı değer yazması en fazla T yaşam süresi alırken kapsam süresi
T + 60 saniyedir. L1 kaydının süresi hem değerin kalan TTL'ini hem 60 saniyeyi
aşamaz. Bu sözleşmede kapsam, değerlerinden ve L1 kayıtlarından önce kendiliğinden
sona eremez. İki kapsam anahtarından biri kaybolursa sayaç sıfırlanmadan ÖNCE
yeni incarnation yazılır; sıfırıncı nesil eski incarnation'a erişmez.
Tenant temizliği için generation metadata'sını silmeyin/sıfırlamayın;
replikalarda TTL'i yerinde ve uyumsuz biçimde değiştirmeyin.

Kullanılmayan tenant ve anahtar revision kayıtları zamanla sona erer.
Ancak sınırsız yeni tenant/anahtar gelişinde TTL, Redis anahtar/bellek sayısına
kesin üst sınır koymaz. Yakın zamanda kullanılan tenant/önbellek başına iki
kapsam anahtarı, revision kayıtları ve eski nesillerin süresi dolmamış değerleri
yer tutar. Geliş hızı sınırlıysa kalma süresi T + 60 saniyeyle, değerlerinki
T ile sınırlıdır. Redis süresinin dolması mantıksal sona ermedir; fiziksel
belleğin anında geri alınacağına dair süre garantisi değildir. Redis maxmemory
ayarlayın; anahtar/bellek büyümesini ve süre dolumunu izleyin. Üst katmanda
tenant/anahtar oluşturma kabulünü sınırlayın. L1/ipucu sınırları, dağıtık
Redis tenant kotası uygulamaz.

Entity sınıfına yönelik tenant purge; keyfî Spring Cache öneklerini, anahtar
şemalarını, sahipliği ve eski nesilleri bilemez. Genel metot önbelleklerindeki
bütün kişisel verileri sildiği iddia edilmez. Bu önbellekler, ayrı ve koordineli
bir purge katılımcısına uygulama tarafından açıkça dahil edilmelidir; burada
otomatik katılımcı API'si veya keşif sağlanmaz. Kapsamı doğrulayın; eşzamanlı
işleri çitleyin; bildirilen her önbelleği/replikayı ve tutulan nesilleri silme
sözleşmesine dahil edin. Seçilen saklama politikası uygunsa kapsamlı clear
mantıksal geçersizleştirme sağlar; ancak eski değerler TTL sonuna kadar kalır,
diğer replikadaki görünürlük bildirimin işlenmesine bağlıdır. Clear, anında
fiziksel silme veya tenant'ın bütün verilerinin silindiği kanıtı değildir.
Açık sahiplik ve silme sözleşmesi yoksa global önbelleğe tenant kişisel verisi
koymayın.
