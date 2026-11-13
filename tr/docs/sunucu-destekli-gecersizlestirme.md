# Sunucu Destekli Yakın Önbellek Geçersizleştirmesi

Yakın önbellek geçersizleştirmesinin üç modu vardır. Bu mekanizma asenkron önbellek tutarlılığı sağlar; doğrusallaştırılabilir yazma sonrası okuma garantisi vermez. Redis okumaları, kesin TTL, nedensellik ve sürüm denetimleri, kiracı (tenant) kontrolleri ve veri boyutu sınırları belirleyici olmaya devam eder.

| Yapılandırılan mod | Kullanılabilir geçersizleştirme kanalı |
| --- | --- |
| `PUBSUB` | Mevcut uygulama kanalı; PUBLISH yapmayan harici yazıcılar görülmez. |
| `SERVER_TRACKING` | Her master üzerinde RESP2 BCAST takibi; tüm abonelikler ve kontrol bağlantılarının sağlık denetimleri başarılı olmadan L1 kullanılmaz. Otomatik PUBSUB geçişi yoktur. |
| `AUTO` (varsayılan) | Eksiksiz sunucu takibi denenir; başarısızsa koordinatör başına tek uyarıyla sağlıklı PUBSUB kullanılır. Kısmi takip kabul edilmez. Sağlıklı PUBSUB; gerçek bağlantı, sağlık, ad alanı veya topoloji değişimi yeniden doğrulama gerektirene kadar korunur. |

`invalidationMode()` en son başarıyla seçilen kanalı verir; başlangıç değeri PUBSUB'dir. Birlikte `connected()` okunmalıdır: mod bilgisi tek başına kullanılabilir L1 kanıtı değildir. `trackingReady()` ve `trackingReadySupplier()`, yalnız yapılandırmayı değil, sağlıklı ve eksiksiz sunucu takibini gerektirir.

## Yapılandırma Ve Yazma Uyumluluğu

`NearCacheInvalidationConfig` ayrı ve değişmez bir yapılandırma record'udur; `NearCachePolicy` kurucusunu veya record yapısını değiştirmez.

```java
import com.reactor.cachedb.core.config.NearCacheInvalidationConfig;
import com.reactor.cachedb.core.config.NearCacheInvalidationMode;
import com.reactor.cachedb.redis.RedisNearCacheCoordinator;

var settings = NearCacheInvalidationConfig.defaults()
        .withMode(NearCacheInvalidationMode.AUTO);
var coordinator = new RedisNearCacheCoordinator(redisOperations, keyStrategy, settings);
```

İki parametreli koordinatör kurucusu bu varsayılanları kullanır. Yapılandırma adaptörleri `cachedb.near-cache.invalidation-mode` değerini `mode` alanına, `cachedb.near-cache.publish-legacy-invalidations` değerini `publishLegacyInvalidations` alanına bağlamalıdır. Record kendi başına Spring özellik bağlaması yapmaz.

Karma sürümlü sistemler için eski yayınlama bayrağı varsayılan olarak true'dur. **Yapılandırma kurucusu, AUTO veya PUBSUB ile legacy=false birleşimini reddeder.** Eski yayınlamayı kapatmak yalnız açık SERVER_TRACKING modu ve tüm tüketicileri kapsayan geçiş sözleşmesiyle geçerlidir: aynı anahtar alanındaki her L1 tüketicisi, PUBSUB'a geri dönüş olmadan SERVER_TRACKING kullanmalıdır. Takibi desteklemek veya geçiş anında sağlıklı olmak yeterli değildir.

| Yapılandırma | Sonuç |
| --- | --- |
| AUTO + legacy=true | Geçerli; takip kaybında sağlıklı PUBSUB kullanılabilir. |
| AUTO + legacy=false | Yapılandırma oluşturulurken reddedilir. |
| PUBSUB + legacy=false | Yapılandırma oluşturulurken reddedilir. |
| SERVER_TRACKING + legacy=false | Yalnız tüm tüketicileri kapsayan sözleşmeyle geçerli; takip kaybında L1 kapalı kalır, PUBSUB kullanılmaz. |

Bir pod PUBSUB'a dönerken takipte kalan diğer pod'lar PUBLISH'i atlıyorsa o pod eski L1 verisini tutabilir. Yazıcının kendi tracking-ready denetimi, başka bir pod'un geçersizleştirme kanalının hazır olduğunu kanıtlamaz. Bu nedenle yalnız mapper supplier'ına güvenmek yerine AUTO modunda eski yayınlamayı kapatmak reddedilir.

Eski yayınlamayı açıkça kapatan ayarlar:

```properties
cachedb.near-cache.invalidation-mode=SERVER_TRACKING
cachedb.near-cache.publish-legacy-invalidations=false
```

İki ayar da üç parametreli koordinatör kurucusuna iletilen aynı doğrulanmış NearCacheInvalidationConfig içinde bağlanmalıdır. Koordinatör AUTO varsayılanında bırakılıp legacy=false yalnız mapper tarafına bağlanmamalıdır.

Yazma adaptörleri argüman oluştururken `coordinator::publishLegacyInvalidations` sonucunu kullanmalıdır. Sonuç yalnız doğrulanmış bayrak false VE eksiksiz takip o anda sağlıklıyken false olur. Takip kaybında L1 kapalı kalırken supplier true döndürür. Record ayarını değiştirmek mevcut yazma eşleyicisindeki PUBLISH'i kendiliğinden kaldırmaz. Geçersizleştirme argümanları atlanırken protokoldeki konumsal argümanlar veya kiracı işaretçisinin ofsetleri kaydırılmamalıdır.

| Ayar | Varsayılan | Geçerli aralık / anlam |
| --- | --- | --- |
| maxMasters | 16 | 1..16 hedef master |
| maxNodes | 64 | maxMasters..64 bilinen düğüm havuzu; replikalar dahil |
| maxNamespaces | 256 | 1..256 etkin ad alanı |
| ioTimeoutMillis | 500 | 50..2000; bağlantı oluşturulduktan sonraki soket okumaları |
| heartbeatMillis | 100 | 25..1000; ioTimeoutMillis değerinin en fazla yarısı |
| healthTimeoutMillis | 1000 | ioTimeoutMillis + heartbeatMillis .. 10000; L1 kabulünün geçerlilik süresi |
| maxAttempts | 5 | 1..8; yeniden deneme grubu başına |
| initialBackoffMillis | 100 | 100..1000 |
| maxBackoffMillis | 5000 | initialBackoffMillis..5000 |
| retryCooldownMillis | 30000 | 1000..60000; başarısız deneme grupları arasındaki bekleme; sağlıklı fallback ömrü değildir |

Yeniden denemeler sınırlı gruplar halinde sürer; sınırsız iş kuyruğu yoktur. Sağlıklı AUTO fallback, dönemsel takip denemesi için kesilmez. Yalnız ACL yeteneği değiştiğinde bağlantı kopması veya topoloji olayı oluşmuyorsa takibi yeniden doğrulamak için `topologyChanged()` çağrılmalı veya çalışma zamanı yeniden başlatılmalıdır. Böylece dönemsel L1 temizliği ve ek yoklama soketleri önlenir. Ad alanı kaydı veya açık topoloji bildirimi bağlantı neslini değiştirir. Yeni ad alanı kaydı mevcut L1 girdilerini temizler ve eksiksiz yeniden doğrulama gerektirir. Takip öneki bütçesi aşılırsa AUTO, PUBSUB kullanır; koordinatörün ayrı 256 yerel depo / 64000 girdi / 64 MiB tahsis sınırları kendi limitlerini aşan kayıtları reddetmeye devam eder.

## Bağlantılar Ve Protokol

Her master için yeni bir abonelik bağlantısı S ve ayrı, yeni bir kontrol bağlantısı C vardır. Adaptörler mevcut havuzun factory nesnesini `RedisTrackingConnectionProvider` ile sunar; `RedisTrackingNode.openDedicated(int)`, `pool.getFactory().makeObject()` kullanır; `getResource()` veya `borrowObject()` kullanmaz. Kapanışta factory nesneleri yok edilir; paylaşılan havuza geri verilmez.

Böylece kimlik bilgileri, TLS soket fabrikası, sunucu adı doğrulaması, uç nokta eşlemesi ve seçili veritabanı korunur. RESP3 ve Jedis'in CacheConnection nesnesi reddedilir; bu uygulama RESP2 takibini bağımsız yönetir. Özel Redis adaptörleri, bu amaca ayrılmış bağlantıları sağlayan SPI'yi uygulamalıdır; aksi halde bilinmeyen bir paylaşılan bağlantı ayrılmaz ve L1 kullanılamaz.

Her master için kurulum:

```text
S: CLIENT ID
S: SUBSCRIBE __redis__:invalidate
C: CLIENT ID
C: CLIENT TRACKING ON REDIRECT <S-id> BCAST
     PREFIX <entityKeyPrefix(namespace1)> PREFIX <entityKeyPrefix(namespace2)> ...
```

S ve C farklı istemci kimliklerine sahip olmalıdır. Bir ad alanı o anda tek slota ait olsa da tüm etkin ad alanları her master'a kaydedilir. Anahtar başına GET ile takip kaydı ve NOLOOP seçeneği yoktur.

Abonelik döngüsü ilk SUBSCRIBE onayından itibaren sonlu zaman aşımı olan RESP2 okumaları kullanır. `JedisPubSubBase.proceed` veya `Connection.setTimeoutInfinite` çağrılmaz: Jedis 5.2'de ikinci yöntem, sonsuz olması gerekmeyen yapılandırılmış bloklama zaman aşımını kullanır. Her S sağlık turu benzersiz bir PING yollar, eşleşen PONG'a kadar geçersizleştirmeleri işler ve ardından `heartbeatMillis` kadar bekler. Boştaki aboneliğin açık kalması için uygulama yazması gerekmez; PONG sonrasında gelen geçersizleştirmeler bir sonraki sınırlı sağlık turunda okunur. Jedis 5.2 veri anlamı korunur: liste, her anahtar için ayrı geçersizleştirmeye açılır; null ileti yükü tüm L1 depolarını temizler. Bilinmeyen varlık önekleri yok sayılır. Bozuk protokol girdisi oturumu başarısız sayar ve L1 kabulünü kapatır; uygulama kuyruğunda geçersizleştirme sessizce düşürülmez.

`RedisKeyStrategy.entityKeyPrefix(String)`, gerçek `entityKey(namespace, "")` sonucuna dayanır; küme hash etiketleri ve özel anahtar düzenleri korunur. `entityKeyPrefixes(Collection<String>)` nesil başına bir defa değişmez bir ad alanı-önek haritası oluşturur. `parseEntityKey(String, Map<String,String>)` her olayda tüm önek metinlerini yeniden oluşturmadan bu haritayı kullanır. İki nokta içeren kimlikler bölünmez. Özel stratejiler kararlı, çakışmayan varlık önekleri kullanmalı ve ayrıştırmayı bellek işlemi olarak tutmalıdır; yavaş callback I/O'su sağlık denetiminin başarısız olmasına yol açabilir.

## Hata Ve Topoloji Güvenliği

Her sağlık denetimi S üzerinden benzersiz PING yollar ve aynı belirteçli PONG'u doğrular. C bağımsız olarak PING ve CLIENT ID denetimi yapar. ROLE ve küme için CLUSTER NODES yaklaşık bir saniyelik aralıklarla denetlenir. S ve C'nin bağımsız sağlık süreleri vardır; hazır olma durumu daha erken dolan süreye bağlıdır. S paketlerinin sessizce kaybolduğu durumda yalnız C'nin yanıt vermesi, hazır olma süresini uzatamaz. Süresi dolmuş kabul, geç PONG veya geciken gözetmen tarafından yeniden açılamaz; o bağlantı nesli temizlenmeli ve değiştirilmelidir.

Kopma, bozuk girdi, kaçırılan sağlık denetimi, rol değişimi, topoloji uyuşmazlığı veya nesil değişimi L1 kabulünü kapatır; hazır olma durumu yeniden açılmadan önce tüm depolar temizlenir. Yeniden bağlantıda yeni S/C kimlikleri alınır. L1 kabul yolundaki süre denetimi, denetleyici gecikse bile sağlık süresi dolduğunda depoları temizler. Sınırlı hata tespit aralığında eski değerler görülebilir; bu senkron geçersizleştirme değildir.

Küme doğrulaması ilan edilen tüm master'ları bulunan master kimlikleriyle karşılaştırır; rol, slot ve yapılandırma dönemi görünümlerinin uyumunu gerektirir. Bilinen havuz haritasındaki değişimler de izlenir. Slot taşıma işaretleri veya eksik uzlaşma takip kabulünü engeller. İşlenen MOVED veya slot önbelleği yenilemesinde Redis entegrasyonu `coordinator.topologyChanged()` çağırmalıdır; olay biliniyorsa periyodik denetim beklenmemelidir.

Adaptör, CLUSTER NODES adreslerinden kimlik bilgisiz bağlantı üretmez. Mevcut Jedis havuz haritası ilan edilen bir master'ı içermiyorsa doğru ayarlı havuz Jedis tarafından bulunana kadar takip hazır olmaz. Bilinen bir düğüm havuzuna ulaşılamaması da korumacı biçimde doğrulamayı başarısız yapabilir.

Master üst sınırında çalışma zamanı en fazla 32 kalıcı takip soketi, bir geçici düğüm denetim soketi, bir ana denetleyici sanal thread'i ve 32 okuyucu/kontrol sanal thread'i kullanır. PUBSUB iki özel soket, iki çalışan ve ana denetleyici kullanır. Başarısız nesil sonlandırılmadan yenisi soket açmaz. Geçersizleştirilen anahtar başına çalışan veya kuyruk yoktur. Varsayılan sağlık denetimi yükü master başına yaklaşık 30 komut/saniye, kümede buna ek iki topoloji komutu/saniyedir; Redis CPU/ağ bütçesinde çalışma zamanı örneği sayısıyla çarpılmalıdır. Ad alanı öneklerinin toplam UTF-8 boyutu 1 MiB, topoloji metni 128 KiB, çözümlenmiş geçersizleştirme listesi 64000 anahtarla sınırlıdır. Kimlik sınırı 4096, ayrıştırılan tam anahtar sınırı 8192 karakterdir.

Denetleyici, düğüm keşfi sırasında her C soketinin sahibidir. Yalnız bellekte çalışan devir kapısı, oturum iptaliyle eşgüdümlü olarak mevcut C çalışanını yayımlar ve başlatır. Bu çalışan CLIENT ID işlemini yapar, S'yi oluşturur, S kimliğini alır ve takibi etkinleştirir. C çalışanı, eşdeğer bir devir kapısı okuyucuyu başlatana kadar S'nin sahibidir. Her devirde kapanma, iptal ve bağlantı nesli yeniden denetlenir. Durdurma yalnız iptal işaretini koyar ve yayımlanmış sahipleri keser; başka bir thread üzerinden Jedis close/disconnect veya flush çağırmaz. Geç dönen factory/komut sonucu, sonraki soket açılmadan ya da okuyucu/sağlık döngüsü başlatılmadan mevcut sahibi tarafından temizlenir. Kapanıştan sonra devam edebilecek zaman aşımı yardımcısı, ayrık devam işi veya yeniden bağlantı görevi bırakılmaz.

Kapatma, iptalden önce hazır olmayı ve yeni devirleri engeller; denetleyiciyi ve yayımlanmış bütün S/C sahiplerini ortak iki saniyelik bütçeyle bekler. Başarılı kapatma, bu sahiplerin sonlandığı ve factory temizliğinin tamamlandığı anlamına gelir. Kesilmiş çağıranlar da aynı bütçe içinde bekler; kesilme durumları korunur. Bir sahip bütçeyi aşarsa kapatma hata fırlatır ve `DEDICATED_CONNECTION_STOP_TIMEOUT` kaydeder; temizlik başarılı gösterilmez ve yeni bağlantı nesli kabul edilmez. `makeObject()` sırasında orijinal factory'nin bağlantı, kimlik doğrulama ve TLS zaman aşımları geçerlidir; geçersizleştirme I/O zaman aşımı bağlantı oluşturulduktan sonra uygulanır. Orijinal süreler sonlu ayarlanmalıdır. Desteklenen factory ile kimlik bilgisi/TLS/adres eşleme/temizleme geri çağrıları, sanal thread kesilmesine ve süre sınırlarına uymalıdır. Kesilemeyen rastgele özel/native geri çağrılar desteklenmez. Java, böyle bir geri çağrıyı özel soketini güvenle bırakmaya zorlayamaz; işi zaman aşımı thread'ine aktarmak sızıntıyı çözmek yerine gizler.

Özel bağlantının temizlenememesi koordinatörü kalıcı olarak devre dışı bırakır; neden giderildikten sonra koordinatör yenilenmelidir. Jedis, uygulama boyut denetimlerinden önce RESP bellek tahsislerini yapar; bu denetimler kötü niyetli RESP girdilerine karşı bellek güvenlik duvarı değildir. Güvenilir Redis uç noktaları, sunucu çıkış tamponu limitleri ve sınırlı küme boyutu kullanılmalıdır.

## Geç Doldurma Koruması

`Store.Stamp(long clearEpoch, long segmentEpoch)`, tüm depo temizliğini segment geçersizleştirmesinden ayırır. Redis okumasından önce tam kimlik için stamp alın; yeni put overload'una iletin:

```java
var stamp = store.capture(id);
var reply = readRedisOutsideAnyStoreLock(id);
if (coordinator.connected()) {
    store.put(id, reply.payload(), reply.decoded(), reply.ttlMillis(), stamp, reply.version());
}
```

Stamp overload'larını kullanan çoklu kimlik okumasında her kimlik için ilgili Redis okumasından önce ayrı stamp alınmalıdır; tek kimliğin segment stamp'i tüm toplu okumayı temsil edemez. Bir sorgu/pencere kimlikleri ancak Redis yanıtında öğreniyorsa okumadan önce alınan eski global epoch ve eski put overload'u korunmalıdır. Yanıt geldikten sonra kimlik bazında stamp almak güvenli değildir. Bu okumalar için global API korumacı ve güvenli bir seçenektir. L1 isabetinden önce ve sonra da hazır olma durumu denetlenmelidir. Önbellekte bulunamayan kayıtların okuma yolu; Redis TTL, silme işaretleri, kiracı görünürlüğü, nedensel minimum sürüm ve veri kabul bütçelerini korumalıdır.

Bir kimliğin geçersizleştirilmesi kendi segment sayacını, o segmentin yalnız bellek işlemleri yapan kilidi altında artırır. Eski segment stamp'i ile gelen geç put reddedilir. Başka segmentteki geçersizleştirme put'u reddetmez. Tüm depo temizliği clearEpoch'u artırarak bütün eski stamp'leri reddeder. Mevcut `epoch()` ve tüm `put(..., long observedEpoch[, long version])` overload'ları ikili uyumluluk için eski global epoch anlamını korur. Stamp sayaçları mevcut sürüm ve TTL denetimlerinin yerine geçmez.

Store monitörleri, yaşam döngüsü kilidi veya hazır olma kapısı altında Redis kontrol I/O'su yapılmaz. Oluşturma, keşif, kayıt, sürekli çalışma ve temizleme boyunca her soketin aynı anda tam bir I/O sahibi vardır. S, devirden sonra tek okuyucuya aittir; C çalışanı da kendi devrinden sonra kayıt/sağlık komutlarının bütün okuma, yazma ve kapatma işlemlerini yürütür. Genel Jedis Connection thread güvenliği varsayılmaz: `sendCommand`, yazma hatasında giriş akışını okumayı deneyebilir. S üzerinde eşzamanlı okuma/yazma, soket I/O kilidi veya intrinsic monitör gerekmez.

## Operasyon Ve Doğrulama

`invalidationsReceived()`, `flushAllEvents()`, `trackingReconnects()`, gerçek mod ve hazır olma durumu ayrı yayımlanmalıdır. Mod gauge'i PUBSUB=0 ve SERVER_TRACKING=1 olarak eşlenebilir. `invalidationFailure()` sınırlı bir hata kategorisi tutar; AUTO sağlıklı PUBSUB durumunda takip doğrulama hatasını korur. `trackingClientIds()` yalnız sağlıklı takipte düğüm -> [S-id,C-id] tanı haritası verir; bu kimlikler metrik etiketi yapılmamalıdır. Yeniden bağlantı sayacı ilkinden sonraki başarılı takip kurulumlarını sayar.

Redis ACL'leri CLIENT ID / TRACKING, geçersizleştirme kanalına SUBSCRIBE, PING, ROLE ve küme inceleme komutlarını; PUBSUB için mevcut uygulama kanalını kapsamalıdır. CLIENT KILL hata testi yetkisidir, üretim çalışma zamanı gereksinimi değildir. Takip veritabanından bağımsızdır: farklı mantıksal veritabanlarındaki aynı önekler güvenli ek geçersizleştirmeler yaratabilir; başka veritabanının verisi okunmaz.

Doğrulama sınıfları `RedisNearCacheStampTest`, `RedisServerTrackingTest`, `RedisServerTrackingClusterTest` `RedisTrackingBlackholeTest`, `RedisTrackingIdleHeartbeatTest`, `RedisTrackingHeartbeatLeaseTest`, `RedisTrackingStartupCancellationTest` ve `RedisTrackingStartupSocketCancellationTest` sınıflarıdır. Tek sunucu ve paket kaybı testleri `cachedb.snapshot.redis=true` ile, küme testleri `cachedb.redis.cluster=true` ile etkinleşir. Tek sunucu test düzeneği mevcut Redis test uç noktasını veya kimlik doğrulama/TLS/sıfır dışı veritabanı doğrulaması için `cachedb.tracking.redis.uri` değerini kullanır. Paket kaybı test düzenekleri yalıtılmış, şifrelenmemiş bir Redis uç noktası gerektirir; gerçek TCP soketlerini açık tutarken S veya C paketlerini ayrı ayrı atar. Küme testleri en az üç gerçek master gerektirir ve her master'ı kapsar. Boşta bağlantı testi, SERVER_TRACKING, PUBSUB ve takip reddi enjekte edilmiş AUTO fallback için gerçek S/C soketlerini uygulama yazması olmadan 32 saniye tutar. Hazır olma kesintisi, temizleme, soket değişimi, havuzdan ödünç alma veya aynı sokette eşzamanlı Jedis I/O'su olmadığını denetler. Kabul süresi testi, süre dolmasını ve nanoTime taşmasını deterministik olarak kapsar. Başlatma testi; factory oluşturmasını, ROLE, C/S CLIENT ID, SUBSCRIBE ve TRACKING onaylarını durdurur; iptalden sonra başarılı dönen geç yanıtları da kapsar. Temizliğin yalnız sahip tarafından yapıldığını, yeni soket/okuyucu/sağlık döngüsü başlamadığını, yaşayan sahip kalmadığını ve kesilmiş çağıranın sınırlı sürede kapandığını denetler. Yerel RESP testi, gerçek AUTH veya TRACKING yanıtını bekleterek kapatma sonrasında karşı uç EOF'unu ve factory temizliğinin tamamlanmasını doğrular; Redis hizmeti gerektirmez ve Redis/TLS/küme kabulü sayılmaz. Bu testler bağımsız S/C paket kaybı testlerinin yerine geçmez.

Kabul ayrıca gerçek repository çağrı noktalarının entegrasyonunu, kimlik doğrulama/TLS/veritabanı matrislerini, rol/failover/MOVED hata enjeksiyonunu ve eski yayınlama kapalıyken 1000 CacheDB yazmasını gerektirir. Bu yalıtılmış çalışmanın öncesi ve sonrası arasında INFO commandstats publish çağrı farkı denetlenmelidir; yalnız ham yazıcı testleri sıfır PUBLISH kullanan yazma yolu davranışını kanıtlamaz. Bu doğrulama tamamlanana kadar karma sürüm uyumluluğu korunmalıdır.

Protokol kaynakları: [Redis istemci tarafı önbellekleme](https://redis.io/docs/latest/develop/reference/client-side-caching/), [Jedis 5.2 abonelik çözümlemesi](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/JedisPubSubBase.java), [Jedis 5.2 bağlantı zaman aşımı ve I/O](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/Connection.java), [Jedis 5.2 bağlantı fabrikası](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/ConnectionFactory.java), [Java 21 soket kesilmesi](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html).
