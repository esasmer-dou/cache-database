# Önbellek Gözlemleri ve İzleme

CacheDB'nin dış bağımlılık gerektirmeyen gözlem SPI'si, eşzamanlı, asenkron ve projeksiyon
görünümlerinde mantıksal repository çağrısını tek gözlem olarak kaydedebilir.
İsteğe bağlı Micrometer uyarlayıcısı gerçek ObservationRegistry olayları üretir.
Native izleme, uygulamanın izleme işleyicisini ve dışa aktarıcısını gerektirir; bağlamdaki metadata
tek başına native span bağlantısı değildir.

## Native Çalışma Zamanı Yapılandırması

Gözlemleri çalışma zamanını kullanıma açmadan önce yapılandırın:

~~~java
var options = CacheRuntimeOptions.builder().observationRecorder(recorder).build();
var database = new CacheDatabase(redis, dataSource, config, options);
var repository = database.repository(metadata, codec);
~~~

Redis repository nesneleri somut RedisEntityRepository olarak kalır. Bakım, dış değişiklikleri
uygulama ve açıkça tanımlanmış sözlüksel pencere yetenekleri genel bir sarmalayıcıyla değiştirilmez.
Fetch-plan görünümleri gözlem sahibini paylaşır. Count, receipt, toplu işlem ve sözlüksel pencere
çağrıları tek gözlem sınırı oluşturur; aynı sahibin iç çağrıları bu sınırı kullanır. Gözlem kapalıyken
repository, lambda oluşturmadan ve gözlem bağlamını yakalamadan doğrudan olağan yolu çalıştırır.

CacheAsyncContext mevcut executor üzerinden gözlem sahibini taşır; başarıda ve hatada çalışanın
önceki bağlamını geri yükler. Asenkron görünüm, asıl nesnenin yürütme ve özgün future davranışını
korur; gözlem yeni zamanlayıcı, taşıma kuyruğu veya yeniden deneme politikası eklemez.
Parametresiz async görünümü çalışma zamanına ait sınırlı executor'ı kullanır. Redis nokta/toplu
okuma uygunluğu, fiziksel tamamlanma hesabı ve iptal native nesnenin kararlarıdır; gözlem
sarmalayıcısı doğrudan kuyruğa gönderimi bir görev aktarıcısıyla değiştirmez.

Native JDBC flusher; doğrulama, kalıcı yazma çiti, DML, commit/rollback ve bağlantı durumunun geri
yüklenmesini kapsayan gerçek transaction sınırını gözlemler. Bağlantının alınması ve yazma çitinin
hazırlanması bu sınırdan öncedir. Her satır değil, her transaction denemesi tek kapsam oluşturur.
SQL yeniden denemeleri depolama sağlayıcısının sorumluluğundadır.

## Özel Repository İçin Açık Dekorasyon

~~~java
var recorder = new MicrometerCacheObservationRecorder(
        registry, new CacheObservationTagPolicy(Set.of("Order"), Set.of("latest_orders")));
var observed = ObservingEntityRepository.wrap(repository, "Order", recorder);
var result = observed.findById(orderId);
~~~

Dekoratör; public aşırı yüklemeleri, sürümlü/receipt API'lerini, yetenekleri ve fetch/projeksiyon
görünümlerini asıl nesneye iletir; onun yürütme stratejisini değiştirmez.
Asenkron çağrılar özgün CompletableFuture'ı döndürür; iptal ve native yürütme davranışı korunur.
Tek gözlem sınırı kullanın: hem asenkron nesneyi hem onun çağırdığı eşzamanlı repository'yi sarmayın.
Görünüm oluşturma, yetenek sorgulama, watcher kaydı ve aggregateFieldValue satır başına gözlem
üretmez. Dekoratör yeni executor, görev aktarıcısı, yeniden deneme, Redis/JDBC çağrısı veya sonuç
kopyası eklemez.

NoOpCacheObservationRecorder.INSTANCE aynı kapsam/bağlama nesnelerini kullanır.
Gözlem kapalıyken ObservingEntityRepository.wrap, sarılmamış nesnenin aynısını döndürür.
Bu repository yolunda sarmalayıcı, lambda, etiket, gözlem veya future oluşturulmaz.
Bu nesne kimliği güvencesi ölçülmüş gecikme iddiası değildir. Özel uygulamalar, gözlem kapalıyken
isteğe bağlı bağlam işlemlerini de atlamalıdır.

Spring uyarlayıcısı yalnızca isteğe bağlı ObservationRegistry sınıfı ve registry bean'i varsa
etkinleşir; cachedb.observation.enabled=false otomatik yapılandırmayı kapatır.
Özel recorder bean'i önceliklidir. Runtime-options özelleştiricisi recorder'ı native repository ve
JDBC flusher nesnelerine yerleştirir; repository proxy'si oluşturmaz. İşleyicileri çalışma zamanını
oluşturmadan önce yapılandırın; etkinlik durumu oluşturma sırasında seçilir. Sarmalanmış flusher
döndüren özel factory, native JDBC flusher'ı sarmalamadan önce yapılandırmalıdır.
Zaten gözlemlenen native Redis repository'yi ayrıca dekore etmeyin.
Core/storage modüllerine Micrometer veya OTel bağımlılığı eklenmez.

## Etiketler, Sonuçlar ve Gizlilik

Yalnızca düşük kardinaliteli db.system, cachedb.entity, cachedb.operation, cachedb.tier,
cachedb.outcome ve isteğe bağlı cachedb.route anahtarları yazılır.
Kimlikler, kiracı kimlikleri, veri içerikleri veya URL'ler etikete dönüştürülmez.
CacheObservationTagPolicy başlangıçta tanımlanan izin listelerini kullanır: en fazla 1024 şema
varlık adı ve 256 rota, ad başına en fazla 128 tanımlayıcı karakteri.
Bilinmeyen varlık "unknown" olur; bilinmeyen rota yazılmaz. İstekle dinamik kayıt veya kimlik
özetleme yapılmaz. db.system redis/postgresql/mssql/oracle/other değerleriyle sınırlıdır;
varsayılan taşıma katmanı redis'tir.

Katman, GERÇEKTEN bildirilen en derin L1/REDIS/SOURCE değeridir; bilinmiyorsa yazılmaz.
Süre, o katmanın tekil çalışma süresi değil çağrının tamamıdır.
Açık tombstone sonucu genel sonuç sınıflandırmasında korunur; hata sonucu baskındır.
"hit", SQL kalıcılığı veya commit güvencesi değildir.
Hata dışa aktarımı, özgün mesajı, nedeni ve çağrı yığınını içermeyen sabit bir istisna kullanır.
Özgün iş istisnası çağırana aynen iletilir. Uygulama işleyicileri/filtreleri hassas hata ayrıntılarını
yeniden eklememelidir. instrumentationFailures(), uyarlayıcı hatalarını bildirir.

Gösterge panosunda işlem, varlık, katman ve sonuç boyutlarını kullanın.
L1/REDIS/SOURCE etiketli toplam çağrı sürelerinin dağılımlarını karşılaştırın; bunları birbirinden
ayrı katman süreleri gibi toplamayın. Hata oranı ve gözlem altyapısı hata sayısındaki değişimleri
izleyin. Dışa aktarıcılar sınırlı ve bloklamayan yapıda olmalıdır; kimlik başına seri ve sınırsız
asenkron kuyruk oluşturmayın.

## Asenkron Kapsam Sahipliği

Scope işlemin ömrüdür; Binding ise onu açan iş parçacığına ait sözcüksel bağlamdır.
Her bağlamayı açıldığı iş parçacığında kapatın. İşlemi tamamlanma, hata veya iptalde kapatın.
Kapatma tekrarlanabilir; geç sinyaller yok sayılır. Asenkron geri çağrı özgün future kimliğini korur;
gözlemin tamamlanma geri çağrısı bitmeden başka bir bekleyen bu future'ın sonucunu görebilir.

Özel çalışanlarda açık bağlam aktarımı kullanılabilir:

~~~java
var snapshot = CacheObservationContext.capture();
executor.execute(() -> {
    try (var binding = snapshot.restore()) {
        work();
    }
});
~~~

Yakalanmış boş core bağlamı, çalışanın mevcut core gözlemini de örter.
Bağlamalar hata durumunda bile önceki bağlamı geri yükler; farklı iş parçacığında veya ters sıra
dışında kapatma reddedilir. İç içe bağlam derinliği 64 ile sınırlıdır.
Bu SPI; kiracı/nedensellik/rota aktarımının veya native SDK bağlam aktarımının yerine geçmez.
Birleştirilmiş gruptaki tüm bekleyenler için tek bağlam yerine her isteğin bağlamını ayrı yakalayın.

## W3C Metadata ve Toplu Yazma Bağlantıları

CacheTraceparent sınırlı bir W3C sürüm-00 alt kümesini kabul eder: tam 55 küçük onaltılık karakter,
sıfır olmayan trace/span kimlikleri ve 128 karakterlik protokol üst sınırı.
Bilinmeyen sürümler kesilmez, reddedilir.
withTraceparent(existing, header), diğer bütün traceMetadata girdilerini ve codec biçimini korur.
Mevcut sekiz girdi/512 karakter sınırları değişmez. Açık withTraceparent yardımcısı taşmada hata
üretir. İsteğe bağlı çalışma zamanı zenginleştirmesi ise yeni başlığı eklemez, invalidHeaders()
sayacını artırır ve mevcut bütün alanları aynen korur.

ObservingTraceContextPropagator(delegate, actualNativeHeaderSupplier), yapılandırılmış aktarıcıyı
gerçek etkin W3C başlığıyla zenginleştirebilir. Diğer girdileri korur, geçersiz başlıkları reddeder,
invalidHeaders() sayacını artırır; kimlik veya örnekleme bayrağı üretmez.
Tipli CacheTraceparentSupplier'ı CacheRuntimeOptions.observationTraceparentSupplier üzerinden veya
Spring bean'i olarak açıkça yapılandırın. Bağımsız FlushObservationConfiguration örneği, etkin native
OTel span'inden gerçek başlığı özgün bayrak bitleriyle alır; MDC'den örnekleme bayrağı türetmez.
Yetkili native modunda supplier boşsa veya hata verirse yeni türetilmiş MDC başlığı kabul edilmez;
TraceContexts içinde önceden açıkça verilmiş metadata ve diğer alanlar korunur.

Native yazma sınırı, mevcut ilişkilendirme alanları dolu olsa da WriteOperation oluşturulmadan önce
başlığı yakalar. Asenkron yazma bunu executor'a göndermeden önce yapar. Temel yapılandırmanın iz
aktarıcısı ve public record biçimi değişmez. Hem etkin recorder hem açık supplier yoksa ek bir
zenginleştirme sarmalayıcısı kurulmaz.

Otomatik JDBC kaynak bağlantısı toplama, açık supplier yapılandırılmadıkça kapalıdır. Böylece eski
MDC kaynaklı başlıklar sessizce native bağlantılara dönüştürülmez. Özel factory,
withObservationOriginLinksEnabled(true) ile doğrulanmış kaynakları tüketmeyi açıkça etkinleştirebilir;
üreticilerin gerçek native başlıkları kaydetmesini sağlamak onun sorumluluğundadır.
Native flusher en fazla 10000 işlem kaynağını inceler; ilk bağlamadan önce en fazla 32 benzersiz
bağlantıyı gözlem kapsamına yerleştirir.

CacheTraceparent.originLinks en fazla 10000 başlığı inceler; bozuk eski değerleri atlar,
trace/span çiftlerini tekilleştirir ve en fazla 32 değişmez bağlantı döndürür.
İşleyicilerin onStart sırasında görebilmesi için bağlantıları ilk bağlamadan önce verin:

~~~java
var scope = CacheObservations.start(recorder, CacheOperation.FLUSH_BATCH, "Order");
try {
    scope.originLinks(CacheTraceparent.originLinks(batchTraceparents));
    scope.databaseSystem("postgresql");
    try (var binding = CacheObservationContext.attach(scope)) {
        scope.tier(Tier.SOURCE);
        flushBatch();
    }
} catch (RuntimeException | Error failure) {
    scope.error(failure);
    throw failure;
} finally {
    scope.close();
}
~~~

CacheMicrometerObservationContext.operation()/originLinks(), işleyici girdileridir.
cachedb.origin.links bağlam verisidir, metrik etiketi değildir.
Micrometer gözleminin üst bağlamını temizlemek, mevcut native SDK üst span'inin devralınmasını
tek başına engellemez. DefaultTracingObservationHandler bu özel listeyi otomatik olarak işlemez.

## Çalıştırılabilir Tüketici OTel Köprüsü

tools/benchmarks/observation-otel altındaki bağımsız örnek, izleme/SDK bağımlılıklarını kendisi taşır.
Mevcut CacheDB modüllerinin bağımlılıklarını değiştirmez; varsayılan reactor'a dahil değildir.
LinkedFlushObservationHandler varsayılan Micrometer izleme işleyicisini genişletir:
normal gözlemler mevcut davranışı korur; FLUSH_BATCH, gerçek OtelTracer üzerinden TEK native span
başlatmadan önce setNoParent ve addLink kullanır.
İşleyici public Micrometer/OTel API'lerini kullanır, bağlantılarda bütün W3C bayrak bitlerini korur
ve kaynak izleri hiçbir zaman üst span olarak geri yüklemez.

Bunu bağımsız bir varsayılan izleme işleyicisinin YERİNE kaydedin.
Elle yapılandırılan registry için:

~~~java
registry.observationConfig()
        .observationHandler(new LinkedFlushObservationHandler(otelTracer));
~~~

Spring Boot 3.3'te yerine geçen işleyiciyi DefaultTracingObservationHandler alt tipi bean olarak
tanımlayın; varsayılan işleyicinin eksik-bean koşulu böylece devreden çıkar:

~~~java
@Bean
@Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
LinkedFlushObservationHandler defaultTracingObservationHandler(io.micrometer.tracing.Tracer tracer) {
    if (!(tracer instanceof io.micrometer.tracing.otel.bridge.OtelTracer otel)) {
        throw new IllegalStateException("The flush-link example requires the OTel backend");
    }
    return new LinkedFlushObservationHandler(otel);
}
~~~

Uygulamanın bağımlılık platformuyla uyumlu micrometer-tracing-bridge-otel ve OTel SDK/dışa aktarıcı
kullanın. Bağımsız örnek Micrometer Tracing 1.3.6 ve OTel 1.38.0 sürümlerini sabitler.
Aynı gözlem için ikinci bir native span oluşturucu kaydetmeyin. Metrik işleyicileri birlikte
kullanılabilir. Örnekleyici toplu yazma span'ini atabilir; bağlantılar dışa aktarımı veya örneklemeyi
zorlamaz. Saklama sözleşmesi yalnızca traceparent içerdiğinden tracestate yeniden üretilmez.

Bağımsız kabul testi gerçek SdkTracerProvider, SimpleSpanProcessor ve InMemorySpanExporter kullanır.
Gerçek SpanData bağlantılarını/bayraklarını, geçersiz üst span kimliğini, tek toplu yazma span'ini,
SQL alt span ilişkisini, normal işleyici davranışını ve asenkron bağlam geri yükleme/hata/iptali
denetler. Native yazıcı/başlık testleri, örneklenmemiş bayrakları ve ilişkilendirme alanlarının
korunmasını da denetler. İsteğe bağlı NativeWriterFlushAcceptanceTest, gerçek JDBC transaction
sınırı, PostgreSQL DML ve SDK SpanData bağlantıları kullanır. cachedb.observation.postgres=true
ile etkinleştirin; cachedb.observation.jdbc.url ve gerekirse user/password sistem özelliklerini
sağlayın. Test benzersiz bir şema oluşturup kaldırır; ayrılmış test veritabanı kullanın.
Bu test, yayımdaki dışa aktarıcıyı veya uygulamaya ait her flusher factory'sini belgelemez.

İlgili CacheDB paketleri Maven deposunda mevcutken:

~~~shell
mvn -f tools/benchmarks/observation-otel/pom.xml -Dcachedb.version=0.19.0 test
~~~

Uygulamada dışa aktarıcı kuyruklarını, yeniden denemeleri ve zaman aşımlarını sınırlandırın.
Tek toplu yazma gözlemi seçilen denemeyi kapsar; başarılı gözlem işlemin commit edildiğini kanıtlamaz.
Native iz sürekliliği, uygulamanın yazma ve toplu aktarma sınırlarında doğrulanmış metadata'yı
iletmesini de gerektirir; yalnızca işleyiciyi kaydetmek depolama katmanına gözlem eklemez.

API kaynakları: [Micrometer bağlantıları](https://github.com/micrometer-metrics/tracing/blob/v1.3.6/micrometer-tracing/src/main/java/io/micrometer/tracing/Span.java),
[OTel köprüsü](https://github.com/micrometer-metrics/tracing/blob/v1.3.6/micrometer-tracing-bridges/micrometer-tracing-bridge-otel/src/main/java/io/micrometer/tracing/otel/bridge/OtelSpanBuilder.java),
[OTel span oluşturucusu](https://github.com/open-telemetry/opentelemetry-java/blob/v1.38.0/api/all/src/main/java/io/opentelemetry/api/trace/SpanBuilder.java).
