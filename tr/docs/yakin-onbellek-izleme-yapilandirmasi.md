# Yakın Önbellek İzleme Yapılandırması

Yakın önbellek, `NearCachePolicy` ile açıkça etkinleştirilir. Geçersizleştirme ayarları çalışma
zamanının tamamında geçerlidir; bütün repository örnekleri, ön plan ve arka plan yazıcıları ile
atomik yazma grupları aynı yapılandırmayı kullanır.

## Yapılandırma

```java
var invalidation = NearCacheInvalidationConfig.builder()
        .mode(NearCacheInvalidationMode.AUTO)
        .publishLegacyInvalidations(true)
        .maxMasters(16).maxNodes(64).maxNamespaces(256)
        .ioTimeoutMillis(500).heartbeatMillis(100).healthTimeoutMillis(1000)
        .maxAttempts(5).initialBackoffMillis(100).maxBackoffMillis(5000)
        .retryCooldownMillis(30000)
        .build();
var options = CacheRuntimeOptions.builder().nearCacheInvalidation(invalidation).build();
var database = new CacheDatabase(redisOperations, dataSource, baseConfig, options);
```

Spring Boot aynı değerleri `cachedb.near-cache` altında bağlar:

```yaml
cachedb:
  near-cache:
    invalidation-mode: AUTO
    publish-legacy-invalidations: true
    max-masters: 16
    max-nodes: 64
    max-namespaces: 256
    io-timeout-millis: 500
    heartbeat-millis: 100
    health-timeout-millis: 1000
    max-attempts: 5
    initial-backoff-millis: 100
    max-backoff-millis: 5000
    retry-cooldown-millis: 30000
```

Geçersiz birleşimler bağlantı oluşturulmadan reddedilir. Birincil düğüm, toplam düğüm ve ad alanı
üst sınırları 16/64/256; deneme sayısının üst sınırı 8'dir. Soket zaman aşımı 50..2000 ms,
kalp atışı aralığı 25..1000 ms olmalı; aralık soket zaman aşımının yarısını aşmamalıdır.
Sağlık zaman aşımı en az soket zaman aşımı ile kalp atışı aralığının toplamı, en çok 10000 ms'dir.
İlk bekleme 100..1000 ms; en uzun bekleme bundan küçük olmamak üzere en çok 5000 ms'dir.
Yeniden deneme döngüleri arasındaki bekleme 1000..60000 ms'dir. Sınırlar PUBSUB modunda da geçerlidir.

Runtime özellikleri ayrı, değişmez, sıradan CacheRuntimeOptions sınıfında tutulur.
CacheDatabaseConfig özgün 21 bileşenli record olarak kalır; record-pattern ve eşitlik yapısı değişmez.
Bootstrap, runtimeOptions(options) ve customizeRuntime(builder -> ...) yöntemlerini sunar.
Spring CacheRuntimeOptionsCustomizer bean'leri runtime özellik eşlemesinden sonra çalışır;
mevcut CacheDatabaseConfigCustomizer bean'leri yalnız temel record'u yapılandırır.

## Karma Sürüm Güvenliği

Varsayılan `AUTO + publish-legacy-invalidations=true`, sunucu izlemesini destekleyen tüketiciler
ile PUBSUB'a dönen tüketicilerin birlikte çalışmasını korur. Her yazıcı eski geçersizleştirme
mesajını yayımlamaya devam eder. Bir yazıcının yerel izleme hazırlığı, başka bir pod'un modunu göstermez.

`publish-legacy-invalidations=false` **yalnızca açık SERVER_TRACKING** ile kabul edilir.
Bu seçenekten önce **aynı anahtar alanındaki bütün L1 tüketicilerini** PUBSUB'a geri dönmeyen
SERVER_TRACKING moduna yükseltin veya bu tüketicilerde L1'i kapatın. Bu, açık bir dağıtım
sözleşmesidir; sistem diğer tüketicileri otomatik keşfetmez. AUTO+false ve PUBSUB+false,
builder kopyaları ve özellik bağlama sırasında da reddedilir.

Katı modda izleme bağlantısının kaybı L1'i temizler ve devre dışı bırakır; PUBSUB'a geri dönülmez.
Yayımlama yalnızca gerekli bütün birincil düğümler izlemeye hazırken durdurulur. Bağlantı kaybı,
sağlık süresinin dolması, bilinen MOVED/ASK veya topoloji bildirimi eski yayımlamayı yeniden
etkinleştirir ve yerel kabulü kapatır. L1 kabulü için yeni bağlantı kuşağının tamamı doğrulanmalıdır.

## Okuma Tutarlılığı

Kimliği bilinen tekil okumalar, yenilemeler ve toplu okumalar, asıl veri I/O'sundan önce
`Store.Stamp(clearEpoch, segmentEpoch)` alır. Toplu damgalar mevcut 512 kimliklik grupla sınırlıdır.
Aynı segmentteki geçersizleştirme veya genel temizleme geç kalmış doldurmayı reddeder; farklı
segmentteki değişiklik gereksiz ret üretmez. Geçerli eşzamanlı L1 isabetinde yeni damga oluşturulmaz.

Sorgu ve pencere okumalarında sonuç kimlikleri I/O öncesinde bilinmediğinden, I/O öncesi genel
epoch koruması sürer. Eski Store epoch/put API'leri önceki genel davranışlarıyla korunur.
Kiracı, nedensel sürüm, atomik iş birimi, silme işareti, kesin TTL ve sıcak veri kabul kuralları
değişmez. Uygulama codec'leri, koşulları, dinleyicileri ve özel istemcileri I/O yapabileceğinden
ayrıca incelenmelidir.

## Gözlemlenebilirlik

Paylaşılan tekil okuma ölçümleri `cachedb.redis.read.coalescing.` önekini kullanır:
`batches`, `coalesced.requests`, `bypassed`, `bypass.disabled`, `bypass.causal`,
`bypass.unitofwork` FunctionCounter sayaçları ve `average.batch.size` Gauge göstergesi.
Toplam bypass, taşıma katmanındaki bypass'ları ve birbirini dışlayan disabled/causal/UOW
nedenlerini içerir. Ölçümler çalışma zamanı genelindedir; kimlik, kiracı, repository veya
instance UUID etiketi taşımaz. Repository ve fetch-plan kopyaları aynı canlı paylaşılan
durumu okur; depolama çağrısı yapılmaz.

`CacheDatabase.nearCacheInvalidationMode()` son uzlaşılan modu döndürür; sağlık bilgisi değildir.
`nearCacheTrackingReadySupplier()` canlıdır; `publishLegacyNearInvalidations()` etkin yayımlama
kararını verir. `nearCacheCoordinator()` ödünç alınan bir kaynaktır; yaşam döngüsünü CacheDatabase yönetir.

Micrometer, tamamı `cachedb.nearcache.` önekiyle, yalnızca belleği okuyan `ready`,
`tracking.ready`, `legacy.publish`, `invalidation.mode{mode}` göstergelerini ve `invalidations`,
`flushes`, `reconnects` sayaçlarını sunar. Mod etiketleri üç enum değeriyle sınırlıdır;
anahtar, kimlik, kiracı veya düğüm etiketi üretilmez. Son mod SERVER_TRACKING olsa da ready=0,
L1'in hazır olmadığını gösterir. Ölçüm toplama Redis veya SQL komutu çalıştırmaz.

Özel Redis istemcileri, sınırlı ve özel bağlantı fabrikaları için `RedisTrackingConnectionProvider`
uygulamalıdır; desteklenmeyen istemcilerde L1 kapalı kalır. İsteğe bağlı
`RedisTopologyChangeSource` bildirimleri sınırlı ve yalnızca bellek içi olmalıdır. Bildirim L1'i
temizler; yeniden bağlantı ve kontrol I/O'sunu çağıran değil gözetmen yürütür. Dışarı yansıyan
MOVED/ASK hataları aynı istisnayla korunur ve bildirim hook'u tarafından yinelenmez. Tekil okuma taşıması, Jedis'in yapılandırılmış MOVED/ASK
yönlendirmesini ve slot haritası yenilemesini korur. Jedis'in kendi içinde giderdiği yönlendirmeler
dönemsel rol ve topoloji denetimleriyle de izlenir. Normal tekil okumaya ek yoklama veya gizli
komut eklenmez.

Özel bağlantılar, ACL izinleri, bütün birincil düğümlerin doğrulanması ve yeniden bağlantı için
[sunucu destekli geçersizleştirme](sunucu-destekli-gecersizlestirme.md) kılavuzuna bakın.
