# Sınırlı Asenkron ve Reactor API'leri

## API sözleşmeleri

Java 21 veya üzeri gerekir. Asenkron core API, Reactor veya Spring bağımlılığı eklemez.
EntityRepository.async(executor), AsyncEntityRepository döndürür: findByIdAsync,
findHotByIdAsync, findAllAsync, saveAsync, deleteAsync, queryAsync, aggregateAsync ve aggregateByAsync.
saveAsync kabul edilen T entity'sini, deleteAsync Void döndürür; mevcut EntityRepository sözleşmesi
korunur. Future'ın tamamlanması kaynak SQL transaction'ının commit edildiğini KANITLAMAZ.

CacheDbRepository.asyncWithReceipt(executor), ayrı AsyncCacheDbRepository tipini döndürür.
async(executor) overload'u da receipt içeren bu sözleşmeyi korur.
saveAsync/deleteAsync GERÇEK WriteReceipt<T, ID> sonucunu; entity, namespace, pozitif version ve
acceptedAt alanlarıyla döndürür. Receipt.entity() kabul edilen entity'dir. Sürüm uydurulmaz;
EntityRepository'ye benzetmek için receipt sessizce atılmaz. Redis kabulü SQL kalıcılığı değildir.
Hot-cache okuması ile açık source okuması ayrıdır; gizli source fallback eklenmez.

CacheSession, asyncEntityRepository(metadata, codec[, policy][, executor]) sağlar.
Executor belirtilmeyen overload'lar yapılandırılmış asyncExecutor() gerektirir; yoksa açık hata
verir. Global veya her çağrıda yeni executor oluşturulmaz.

## Executor sahipliği ve kapasite

Her runtime için yaşam döngüsü açıkça yönetilen tek gate kullanın. Uygulamanın yapılandırdığı
trace propagator'ı geçirin:

```java
try (var gate = new CacheAsyncExecutor(1024, config.traceContextPropagator())) {
    var async = repository.async(gate);
    var pending = async.findByIdAsync(id);
    // Future zincirini kullanın; event-loop üzerinde get() veya join() ile beklemeyin.
}
```

Gate'i kullandığı Redis/JDBC havuzlarından önce kapatın. Paylaşılan varsayılan executor ancak
sahipliği ve kapanışı açıkça yönetiliyorsa güvenlidir. CacheAsyncExecutor kendi oluşturduğu
virtual-thread executor'ını kapatır; dışarıdan verilen Executor'ı kapatma sorumluluğu çağırandadır.
Bağımsız kullanımın thread-local trace varsayılanı, uygulamanın özel propagator'ının yerine geçmez.

Varsayılan eşzamanlılık 1024, mutlak üst sınır 65.536'dır. Her kabul edilen iş için yeni virtual
thread oluşturulur; virtual thread havuzu kurulmaz. Beklemeyen tryAcquire kapasite dolduğunda
CacheAsyncRejectedException üretir; gizli kuyruk büyütmez. Semaphore adil olsa da tryAcquire FIFO
garantisi vermez. Dış executor işi hızla kabul etmeli veya reddetmeli; sessizce atmamalı, çağıranı
bekletmemelidir. Caller-runs davranışı repository I/O başlamadan reddedilir.

İki kapasite ayrı ayrı sınırlıdır: gerçek işlem permit'i ve henüz tüketilmemiş dispatch kaydı.
Başlamadan iptal, işlem permit'ini bırakır ve repository metodunun çalışmasını önler. Dış executor
kuyruğundaki iptal kaydı ise tüketilene kadar dispatch hakkını tutar. Bu nedenle availablePermits
pozitifken yeni dispatch reddedilebilir. İptal fırtınası sınırsız kuyruk çöpü oluşturamaz.
Dış executor'ın işi sessizce kaybetmesi sözleşme ihlalidir; gerçek I/O sürerken kapasiteyi sıfırlayıp
sorunu gizlemek güvenli değildir.

İş başladıktan sonra cancel(true) yalnız sonuç teslimini durdurur. Driver I/O'sunu kesmez, kabul
edilen yazıyı geri almaz, rollback yapmaz ve permit'i erken bırakmaz. Kapasite, gerçek I/O ve
context temizliği bittikten sonra bırakılır; future sonucu ardından yayımlanır. Timeout/orTimeout
da çalışan kapasiteyi açmaz. Redis/JDBC/network/pool timeout'larını ayrıca sonlu ayarlayın.

close yeni işi reddeder ve varsayılan iki saniye bekler; açık ayarla üst sınır 30 saniyedir.
Süre aşılırsa yalnız sahip olduğu worker'ların kesilmesini ister ve hata bildirir; hâlâ çalışan
I/O sayılmaya devam eder. awaitIdle fiziksel işlemleri izler; iptal edilmiş dış kuyruk kayıtlarının
tüketildiğini kanıtlamaz. Gizli timer veya otomatik retry yoktur. Yalnız idempotency doğrulanmış
işlemlerde sınırlı retry bütçesi/jitter kullanın; timeout alan yazı kabul edilmiş olabilir.

Future continuation'ları yakalanan işlem context'inin dışında çalışır. Ağır veya bloklayan kullanıcı
continuation'ları repository kapasitesi yerine ayrı, sınırlı uygulama executor'ı kullanmalıdır.

## Girdi sınırları ve context

ID koleksiyonu QuerySpec.MAX_LIMIT (100.000), receipt döndüren saveAll 1000 entity, aggregate facet
256 grup ile sınırlıdır. QuerySpec sınırları korunur. Fazla sonuç sessizce kesilmez, hata üretilir.
Listeler çağrı/assembly anında kopyalanır; null ID/eleman reddedilir. Entity nesne ağacı klonlanmaz:
immutable entity kullanın veya future iptal edilse bile fiziksel I/O bitene kadar değiştirmeyin.

CacheAsyncContext.wrap(configuredPropagator, callback), kayıt anındaki tenant/admin, causal ve
sınırlı trace/route bilgisini yakalar. Boş context de uygulanır. Decode, tenant/owner denetimi,
routing ve L1 kabulü bu scope içinde yürür. Havuz worker'ının önceki durumu finally ile geri
yüklenir; trace scope'u hata halinde de kendi değişikliklerini geri almalıdır.
Context'i dispatch sonrasında yabancı worker üzerinde yakalamayın. Mutable atomic-batch veya
unit-of-work durumu thread'ler arasında paylaşılamaz.

snapshot() sayaçlarını sınırlı, düşük cardinality'li metriklerle izleyin; tenant/ID/payload tag'i
eklemeyin. accepted sayacı gate kabulüdür; Redis yazma kabulü veya SQL kalıcılığı değildir.

## İsteğe Bağlı Reactor Adapter'ı

Diğer CacheDB artifact'leriyle aynı sürümde com.reactor.cachedb:cachedb-reactor ekleyin.
reactor-core kullanır; Spring isteğe bağlıdır. Spring factory kaydı stateless'tir ve executor
oluşturmaz. Spring kullanmayan uygulama factory'yi doğrudan oluşturabilir.

```java
var factory = new ReactiveEntityRepositoryFactory();
var reactive = factory.from(repository, gate);
var result = reactive.findById(id)
    .contextWrite(context -> context.put(CacheReactorContext.TENANT, authenticatedTenant));
```

Publisher cold'dur: her subscription, deferContextual ve future supplier ile yeni iş oluşturur.
Optional.empty boş Mono'ya, sonlu query/findAll listesi Flux öğelerine dönüşür. İptal, subscription'a
özel future'a iletilir; yukarıdaki fiziksel I/O kuralları korunur. Dahilî scheduler, boundedElastic
offload, global Reactor hook veya otomatik retry kurulmaz. from(asyncRepository) native asenkron
uygulamayı korur. Fallback adapter bloklayan çağrıları offload eder; tek başına read coalescing veya
sıfır-offload Redis fast path sağlamaz.

CacheReactorContext.TENANT güvenilir, en fazla 256 karakterlik String; CAUSAL_TOKEN ise
CacheCausalContext.encodeToken() sonucudur. Yanlış tip/token dispatch öncesinde reddedilir.
Eksik anahtar BOŞ tenant/causal durumudur; ortamın worker/admin yetkisi devralınmaz. Causal token
kimlik doğrulama değildir. Trace, bu iki anahtardan değil yapılandırılmış TraceContextPropagator'dan
taşınır. Subscriber thread'inin önceki durumu kayıt çağrısından hemen sonra geri yüklenir.

Event-loop üzerinde get/join ile beklemeyin ve paylaşılan hot future kullanmayın. İptali rollback,
entity sonucunu SQL kalıcılığı veya offload'u native toplu Redis işlemi olarak yorumlamayın.
