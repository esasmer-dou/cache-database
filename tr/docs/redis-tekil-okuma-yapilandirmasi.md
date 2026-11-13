# Redis Tekil Okumalarının Birleştirilmesi

Her CacheDatabase, ön plan istemcisi için tek bir RedisReadRuntime yönetir. DefaultCacheSession, farklı politikalarla oluşturulan repository nesneleri ve fetch-plan kopyaları bu runtime'ı ve sınırlı kabul durumunu paylaşır. Repository nesneleri kendi transportlarını oluşturmaz veya kapatmaz. Eski bağımsız repository/session kurucuları, yaşam döngüsü sahibi açıkça ortak okuma durumu sağlamadıkça doğrudan okumayı korur.

Bu entegrasyon yalnız senkron findById ve findHotById okumalarının transportunu yönlendirir. Toplu okuma, sorgu ve yenileme davranışları değişmez. Varlık çözümlemesi, kiracı denetimleri, sıcak veri politikası, kaynak sisteme dönüş ve fetch-plan genişletmesi transport tamamlandıktan sonra çağıran thread üzerinde kalır; uygulama nesneleri veya çağıranın ThreadLocal durumu ham okuma birleştiricisi üzerinden paylaşılmaz.

## Yapılandırma

Spring, `cachedb.redis.read-coalescing.*` ayarlarını ayrı bir
`CacheRuntimeOptions.redisReadCoalescing()` bean'ine bağlar. CacheRuntimeOptions değişmez, final,
sıradan bir sınıftır; record değildir. Builder/toBuilder() seçili ayarları korur; sıralı
CacheRuntimeOptionsCustomizer bean'leri runtime özellik eşlemesinden sonra çalışır.
Mevcut CacheDatabaseConfigCustomizer bean'leri yalnız temel yapılandırmayı özelleştirir.

CacheDatabaseConfig'in 21 record bileşeni, kanonik kurucuları, Java 21 record-pattern bileşen
sayısı ve eşitlik yapısı değişmez. Eski CacheDatabase kurucuları varsayılan runtime seçeneklerini
kullanır. Temel yapılandırmayı değiştirmeden davranış seçmek için options argümanı ekleyin:

```java
var options = CacheRuntimeOptions.builder()
        .redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(false).build())
        .build();
var database = new CacheDatabase(redisOperations, dataSource, baseConfig, options);
var bootstrap = CacheDatabase.bootstrap(redisOperations, dataSource)
        .config(baseConfig).runtimeOptions(options)
        .customizeRuntime(builder -> builder.redisReadCoalescing(options.redisReadCoalescing()));
```

`database.runtimeOptions()` seçili değişmez nesneyi sunar; `database.config()` hâlâ kararlı temel
record'u döndürür. Bootstrap runtime özelleştiricilerini bağlantı açmadan önce çözer.
Runtime seçenekleri geliştirme/üretim/temel profil değişikliklerinden bağımsızdır.

```properties
cachedb.redis.read-coalescing.enabled=false
cachedb.redis.read-coalescing.max-batch-keys=512
cachedb.redis.read-coalescing.max-queued-requests=4096
cachedb.redis.read-coalescing.max-in-flight-batches=0
cachedb.redis.read-coalescing.max-queued-bytes=16777216
cachedb.redis.read-coalescing.max-reply-bytes=16777216
cachedb.redis.read-coalescing.max-live-reply-bytes=67108864
cachedb.redis.read-coalescing.timeout-millis=5000
cachedb.redis.read-coalescing.max-async-direct=16
```

Okuma birleştirme, düşük yükte gecikmeyi artırmamak için varsayılan olarak kapalıdır. Yalnızca eşzamanlı okuma yükünüzde yararını ölçtükten sonra etkinleştirin. Birleştirme kapalıyken senkron repository okumaları mevcut doğrudan MGET/nedensel okuma yolunu korur: coalescer future'ı, kuyruk, EVAL transportu veya executor geçişi eklenmez. Mevcut doğrudan istemci sınırları ve zaman aşımları geçerli kalır.

Örnek, runtime genelindeki ham yanıt içeriği bütçesini 64 MiB'ye indirir; maxLiveReplyBytes varsayılan olarak 256 MiB'dir ve bu kesin üst sınırı aşamaz. enabled=false olsa da en az maxReplyBytes kadar olmalıdır. Diğer değerler transport varsayılanlarıdır. maxBatchKeys en fazla 512 varlık kimliğini sayar; her kimlik için varlık/silme işareti çifti bulunur. Kuyruk sayısı 4096 ile sınırlıdır; kuyruktaki anahtarların ve grup başına yanıtın bütçesi ayrı ayrı en fazla 16 MiB'dir. maxInFlightBatches=0, max(2, physicalPoolCapacity / 2) değerini üretir; türetilen veya açık değer RedisReadCoalescingConfig.HARD_IN_FLIGHT sınırına uymalıdır. timeoutMillis 1..300000, maxAsyncDirect 0..1024 aralığındadır. Geçersiz ayarlar yapılandırma doğrulamasında reddedilir.

Aynı 64 MiB bütçesi YAML üzerinden de bağlanabilir:

```yaml
cachedb:
  redis:
    read-coalescing:
      enabled: true
      max-reply-bytes: 16777216
      max-live-reply-bytes: 67108864
```

## Kapasite Ve Bellek

RedisClientOperations.physicalPoolCapacity(), kapasitesi bilinmeyen özel istemciler için korumacı biçimde iki döndürür. JedisPooledOperations gerçek ön plan havuzunun maxTotal değerini okur; JedisClusterOperations düğüm havuzlarının toplamını değil, en küçük kapasitesini döndürür. Boş küme havuz haritasında, bulunan havuzlardan yeni bir runtime oluşturulana kadar iki kullanılır. Bilinen bir Jedis havuzunda maxTotal <= 0 ise yapılandırma reddedilir; sınırsız havuz sessizce sınırlı kabul edilmez. Özel adaptörler sonlu havuz ve soket sınırlarından sorumludur. Kapasite tespiti yalnız bellek işlemlerinden oluşur; bağlantı ödünç almaz. Kabul bütçesi runtime oluşturulurken sabitlenir; bilinçli havuz boyutu değişikliğinden sonra runtime yeniden oluşturulmalıdır.

maxLiveReplyBytes, ayrılan ve tutulan ham yanıt içeriği için runtime genelinde ayrı bir kabul bütçesidir; JVM heap sınırı değildir. Her grup fiziksel I/O öncesinde maxReplyBytes kadar yer ayırır; transport veya teslim aşaması ham içeriği tutarken bu kapasite kullanımda kalır. Yukarıdaki YAML ile maxInFlightBatches daha büyük olsa da en fazla dört tam 16 MiB ayırma sığar. Bellek kapasitesi beklenirken kuyruk ve süre sınırları geçerlidir. Çözümleme, Jedis/RESP çerçeveleme, String/nesne ek yükü, çağıranın tuttuğu değerler ve uygulama nesne ağları için ayrıca pod belleği gerekir. İki bellek bütçesi ve fiziksel eşzamanlılık, pod belleği ve Redis CPU kapasitesine göre ayarlanmalıdır; yalnız havuz boyutları artırılmamalıdır. Birleştirme kapalıyken veya nedensellik/unit-of-work nedeniyle kullanılan senkron doğrudan yollar mevcut istemci davranışını korur; birleştiricinin kabul sınırları bu yollar için geçerli sayılmaz. Türetilen en küçük eşzamanlılık değeri iki olduğunda ve fiziksel havuz tek bağlantı içerdiğinde de bağlantı havuzunun gerçek sınırı belirleyicidir.

## Bypass Ve Doğruluk

Entegrasyon, L1 isabetsizliğinden sonra ve asıl veri okumasından önce, ilk çağıran thread üzerinde uygunluk denetimi yapar. Pozitif nedensel minimum sürüm veya CacheUnitOfWork.active() birleştiriciyi atlar. Nedensel okumalar, eksik veya eski verinin reddedilmesi dahil sürüm okumasını ve doğrulamasını korur. Unit-of-work okumaları başka çağıranların transport grubuna katılmaz.

L1 isabetleri transport kabulü gerektirmez. Birleştirme etkin tekil okumalar, yalnız okuma öncesinde L1 doldurması uygunsa veriyi Redis PTTL ile birlikte ister. TTL başlangıcı RedisPointRead.observedAtNanos() değeridir; ağ, teslim, çözümleme ve politika işlemlerinde geçen süre yerel kabulden önce düşülür. İstenmemiş veya süresi dolmuş TTL önbelleğe alınamaz. Redis'in kalıcı/eksik kayıt TTL anlamları korunur.

Kimliği bilinen tekil okumalar, yenilemeler ve sınırlı toplu gruplar, asıl veri I/O'sundan önce
Store.Stamp alır. Sorgu/pencere okumalarında sonuç kimlikleri henüz bilinmediğinden, I/O öncesi
genel Store.epoch() koruması sürer. Eski bir yanıt alındıktan sonra doldurma koruması alınmamalıdır.
Eski epoch tabanlı Store.put yöntemleri önceki genel geçersizleştirme davranışını korur.

## Metrikler Ve Yaşam Döngüsü

CacheDatabase.redisReadRuntime() ve RedisEntityRepository.redisReadRuntime(), tanı ve entegrasyon için aynı ödünç transportu sunar. Repository üzerinden kapatılmamalıdır. CacheDatabase.redisReadMetrics(), transport anlık görüntüsünü ayrı disabled, causal ve unitOfWork bypass sayaçlarıyla birleştirir; bypassed() bunları ve transport taşma bypass'larını toplar. Neden sayaçları birbirini dışlar; öncelik sırası kapalı birleştirme, nedensellik ve unit-of-work'tür. Ham runtime.metrics(), transporta girilmeden verilen politika kararlarını bilerek göremez.

CacheDatabase kapatılırken önce ortak tekil okuma kabulü, kapalı birleştirme ve nedensel/unit-of-work doğrudan yolları dahil kapatılır; ardından diğer kaynaklar sonlandırılır. Kuyruktaki okumalar transport tarafından reddedilir. Devam eden fiziksel I/O erken tamamlanmış sayılmaz; sonlu havuz/soket süreleri zorunludur. Repository ve fetch-plan kopyaları, sahipleri kapandıktan sonra yeni tekil okumaları reddeder. Dışarıdan verilen Redis istemcisi ödünç kalır; CacheDatabaseBootstrap tarafından oluşturulan istemci bootstrap sahipliği üzerinden kapatılır.

Doğrulama sınıfları RedisReadWiringRedisTest, RedisRepositoryReadStateTest, RedisReadCoalescingIntegrationConfigTest, RedisReadCoalescingBindingTest ve RedisReadPhysicalCapacityClusterTest'tir. Redis destekli testler cachedb.snapshot.redis=true, küme kapasitesi testleri cachedb.redis.cluster=true gerektirir. Testler mevcut, yalıtılmış uç noktaları kullanır; Redis kurulumu yapmaz.

[Kaydedilen aday matrisi](performans-olcumleri.md), açıkça etkinleştirilen kullanımda yüksek
eşzamanlılık kazancı gösterir; tek çalışan için <=%3 p50 ölçütünü karşılamaz. Ürün varsayılanı false
olarak kalır; birleştirmenin kendiliğinden açılması doğrulanmış değildir. Nihai paketin, iş yükünün
ve kurulumun kabulü, bu aday sayılarından ve işlevsel testlerden ayrıdır.
