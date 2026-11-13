# Sanal Thread Hazırlığı

[English](../../docs/virtual-thread-readiness.md)

## Platform Koşulları

CacheDB, Java 21+ (LTS) gerektirir. Java 17 kullanmaya devam eden uygulamalar
0.18.x sürümünde kalmalıdır. Framework derlemesi için Maven 3.9+ gerekir.
Annotation işleme açıkça yapılandırılır; ön izleme API'leri kullanılmaz.

Yakın önbellek yenilemesinde her görev için yeni bir sanal thread açılır; sanal
thread havuzu kullanılmaz. Her runtime'daki semafor en fazla 258 yenilemeye izin
verir. Aynı kayıt için yinelenen yenilemeler birleştirilir; sınır dolduğunda
çağıran thread bekletilmeden istek reddedilir. Executor ilk ihtiyaçta oluşturulur.
Aynı anda en fazla iki yenileme callback'i I/O yapar; önceki worker baskısı korunur.
Kabul edilen diğer sanal thread'ler semafor üzerinde bekler.
Kapanış, görevleri kesmeye çalışır ve en fazla iki saniye bekler. Java, kesilme
isteğine uymayan kullanıcı kodunu zorla sonlandıramaz.

`spring.threads.virtual.enabled`, Spring'in yönettiği thread'leri etkiler.
CacheDB'nin eşzamanlılık sınırlarını veya açık executor kurallarını kaldırmaz.
Bu ayarın iki değerinde de CacheDB yenilemeleri sanal thread kullanır.
Tenant, nedensel tutarlılık ve izleme bilgileri açık bağlam sarmalayıcılarıyla
taşınmalı; miras alınan thread-local durumuna güvenilmemelidir.

## Repository Başlatma

Repository oluşturulurken indeks imzasını doğrulamak için Redis'e erişilebilir. Bu nedenle
oluşturma işlemi `ConcurrentHashMap.computeIfAbsent` dışında yürütülür; bu metodun iç monitörü,
ağ I/O'su sırasında Java 21 sanal thread'ini taşıyıcı thread'e sabitleyebilir. Session, tek
repository önbelleğini korur; 64 sabit reentrant kilit ve en fazla 64 etkin başlatma kaydı kullanır.
Önbellekte bulunan repository için binding ve policy doğrulanır; başlatma kilidi, ek kayıt
ayırma veya I/O gerekmez.

Aynı anahtara gelen eşzamanlı çağrılar tek örnek oluşturur. Farklı anahtarların iç içe
başlatılması, ilgili kilit uygunsa desteklenir; aynı kilidin aynı thread tarafından yeniden
alınması da buna dahildir. Aynı anahtara dönen döngü, başka thread'in tuttuğu kilidi gerektiren
iç çağrı veya dolmuş başlatma kapasitesi, iç oluşturucu I/O yapmadan hata üretir. İç çağrılar,
başka bir başlatma kilidini tutarken beklemez. Hata durumunda kayıtlar ve kilitler bırakılır;
sonraki çağrı yeniden deneyebilir. Anahtar başına büyüyen kilit haritası, yeni executor veya
SQL okuması eklenmez. Bu örtük monitör yolu, aşağıdaki açık monitör envanterinden ayrıdır.

`CacheRepositoryInitializationRedisTest`; örnek kimliğini, metadata doğrulamasını, iç içe
başlatmayı, kapasiteyi ve hata sonrası toparlanmayı denetler.
`CacheRepositoryInitializationVirtualThreadRedisTest`, sınırlı Redis beklemeleri ve yerel
JFR pinning olaylarıyla gerçek sanal thread oluşturucularını çalıştırır. Bu hedefli testler,
her kullanıcı callback'inin veya her uygulama iş yükünün güvenli olduğunu kanıtlamaz.

## Monitör Denetimi

Referans inceleme, v0.18.1 sürümünü
(`3a212461fd835c783cf84d3c6b656b375df40a19`) kapsar: 486 ana Java dosyasında,
16 dosyaya dağılmış 57 monitör noktası vardır. Bunların 23'ü bloklayıcı veya
bloklama riski taşıyan, 34'ü yalnızca bellek içinde çalışan nokta olarak
sınıflandırılmıştır. Geçişte 39 nokta reentrant kilide taşınır: 23 riskli nokta ve
aynı kilit ailesini paylaşan 16 bellek içi nokta. Bağımsız 18 bellek içi monitör korunur.

Aynı veriyi koruyan kilitler birlikte dönüştürülmelidir. Yalnızca yazarı
değiştirip okuyucuyu başka bir monitörde bırakmak veri yarışına yol açar.
Reentrant kilit, monitör kaynaklı thread sabitlenmesini önler; Redis/JDBC
çağrılarını hızlandırmaz veya veritabanının kapasitesini artırmaz.

| Modül | Ana Java dosyası | Açık nokta | PURE-MEMORY | BLOCKING |
| --- | ---: | ---: | ---: | ---: |
| cachedb-storage-redis | 47 | 27 | 14 | 13 |
| cachedb-storage-jdbc | 23 | 1 | 0 | 1 |
| cachedb-core | 251 | 10 | 7 | 3 |
| cachedb-starter | 88 | 5 | 4 | 1 |
| cachedb-spring-boot-starter | 77 | 14 | 9 | 5 |
| Toplam | 486 | 57 | 34 | 23 |

Aşağıdaki envanterde referans sürümün satır konumları yer alır. `LOCK Pn`,
aşağıda açıklanan kilit ailesi dönüşümünü gösterir. `PURE-MEMORY / KEEP`, referans
gövdenin sınıflandırmasıdır; paylaşılan her okuyucunun eski kilidini koruduğu
anlamına gelmez. Satırlar değiştiğinde sınıf ve metot adlarını esas alın.

## Kaynak anahtarı

Yollar repo köküne göredir. `Fnn:Lx`, ilgili dosyadaki tam monitör başlangıç satırını
gösterir. Fiziksel `com/cachedb` klasörlerine rağmen Java paketleri
`com.reactor.cachedb` ile başlar.

| Anahtar | Kaynak |
| --- | --- |
| F01 | [RedisNearCacheCoordinator.java](../../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisNearCacheCoordinator.java) |
| F02 | [RedisWatchRegistry.java](../../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisWatchRegistry.java) |
| F03 | [RedisEntityRepository.java](../../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisEntityRepository.java) |
| F04 | [RedisProducerGuard.java](../../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java) |
| F05 | [RedisProjectionRepository.java](../../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProjectionRepository.java) |
| F06 | [RedisQueryIndexManager.java](../../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisQueryIndexManager.java) |
| F07 | [AbstractJdbcWriteBehindFlusher.java](../../cachedb-storage-jdbc/src/main/java/com/cachedb/jdbc/AbstractJdbcWriteBehindFlusher.java) |
| F08 | [DefaultEntityRegistry.java](../../cachedb-core/src/main/java/com/cachedb/core/registry/DefaultEntityRegistry.java) |
| F09 | [StoragePerformanceCollector.java](../../cachedb-core/src/main/java/com/cachedb/core/queue/StoragePerformanceCollector.java) |
| F10 | [AdminConsistencyInspector.java](../../cachedb-starter/src/main/java/com/cachedb/starter/AdminConsistencyInspector.java) |
| F11 | [CacheDatabase.java](../../cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabase.java) |
| F12 | [CacheDatabaseAdminHttpServer.java](../../cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabaseAdminHttpServer.java) |
| F13 | [CacheDatabaseSchemaAdmin.java](../../cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabaseSchemaAdmin.java) |
| F14 | [CacheDistributedJobExecutor.java](../../cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheDistributedJobExecutor.java) |
| F15 | [JacksonJsonArrayCodec.java](../../cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/snapshot/JacksonJsonArrayCodec.java) |
| F16 | [SnapshotJobs.java](../../cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/snapshot/SnapshotJobs.java) |

## Eksiksiz envanter

### Redis

| Kimlik | Sınıf/üye | Nokta; monitör | Sınıf; karar | Senkron zincir dahil gerekçe |
| --- | --- | --- | --- | --- |
| R01 | RedisNearCacheCoordinator.register | F01:L42; this | BLOCKING; LOCK P1 | L57-58 platform thread oluşturur/başlatır: native yaşam döngüsü ve inherited-thread-local childValue callback'leri bloklayabilir. listen yeni thread'de çalışır, bu monitör altında değil. |
| R02 | RedisNearCacheCoordinator.refresh | F01:L74; this | BLOCKING; LOCK P1 | L84 ThreadPoolExecutor.execute, queue.offer/executor kilitleri ve thread oluşturmaya gider; bekleyebilir. AbortPolicy işi çağıran thread'de çalıştırmaz. |
| R03 | RedisNearCacheCoordinator.close | F01:L149; this | PURE-MEMORY; KEEP | Yalnız executor referansı ve closed/connected durumu. Clear, shutdownNow, awaitTermination(2 s), unsubscribe ve interrupt L153-162'de, monitör dışında. |
| R04 | RedisNearCacheCoordinator.Store.lookup | F01:L184; segment | PURE-MEMORY; KEEP | String anahtarlı lookup, süre dolumu, sınırlı frekans sayaçları ve sürüm karşılaştırması. |
| R05 | RedisNearCacheCoordinator.Store.getDecoded | F01:L197; segment | PURE-MEMORY; KEEP | Payload String karşılaştırması/süre/sayaç; decoded referansı döner, codec çağırmaz. |
| R06 | RedisNearCacheCoordinator.Store.put (version overload) | F01:L220; segment | PURE-MEMORY; KEEP | Epoch/sürüm kontrolü, sınırlı eviction/frekans bakımı/map ekleme; decoded nesne callback'i yok. |
| R07 | RedisNearCacheCoordinator.Store.put (son kontrol) | F01:L235; segment | PURE-MEMORY; KEEP | Epoch değiştiyse String anahtarlı kaydı siler. Eski epoch tabanlı doldurmalar genel geçersizleştirme korumasını tutar; kimlik bazlı damgalar [tekil okuma yapılandırmasında](redis-tekil-okuma-yapilandirmasi.md) açıklanır. |
| R08 | RedisNearCacheCoordinator.Store.invalidate | F01:L239; segment | PURE-MEMORY; KEEP | Epoch artırımı bloktan önce; blok yalnız kaydı siler ve byte sayısını düzeltir. |
| R09 | RedisNearCacheCoordinator.Store.clear | F01:L243; her segment | PURE-MEMORY; KEEP | Sınırlı kayıt/sketch/sayaçları segment bazında temizler. |
| R10 | RedisWatchRegistry.register | F02:L30; registry this | BLOCKING; LOCK P2 | L35 new Watch, L74 UUID.randomUUID başlatır; SecureRandom/provider başlatma/entropy bloklayabilir. Listener saklanır, çağrılmaz. Provider/başlatma durumuna bağlı risk. |
| R11 | RedisWatchRegistry.Dispatcher.submit | F02:L90; dispatcher this | BLOCKING; LOCK P2 | L93 ArrayBlockingQueue.offer kapasite beklemese de ReentrantLock alır. L95 thread oluşturma/başlatma da yaşam döngüsü işidir. Callback inline çalışmaz. |
| R12 | RedisWatchRegistry.Dispatcher.run, finally | F02:L105; dispatcher this | PURE-MEMORY; KEEP | Yalnız queuedBytes azaltılır. queue.take, task.action.run ve hata logu L102-104'te dışarıdadır. R11 ile aynı kilide taşınmalı. |
| R13 | RedisEntityRepository.VerifiedOwners.get | F03:L115; owners this | BLOCKING; LOCK P3 | String.valueOf(Object), ID.toString callback'ini monitör içinde çağırır. String ID bellek işidir; public generic ID sözleşmesi bunu garanti etmez. |
| R14 | RedisEntityRepository.VerifiedOwners.put | F03:L116; owners this | BLOCKING; LOCK P3 | L117 aynı genişletilebilir dönüşüm; sonraki LRU/byte hesabı sınırlı bellek işidir: 16384 kayıt/4 MiB. |
| R15 | RedisEntityRepository.registerWatcher | F03:L139; repository this | BLOCKING; LOCK P3 | metadata.tenantFieldName, activeTenant -> TenantAccess.active, iç R16 ve R10 zinciri. Metadata public SPI'dır; burada doğrudan loader/Redis çağrılmaz. |
| R16 | RedisEntityRepository.registerWatcher, iç blok | F03:L143; paylaşılan watchState | BLOCKING; LOCK P3 | tenantQuery -> TenantAccess.scoped metadata metotlarını çağırır; registry.register UUID üretimine gider. Repository view'ları aynı guard kimliğini paylaşmalıdır. |
| R17 | RedisProducerGuard.runtimeProfileEvents | F04:L223; this | PURE-MEMORY; KEEP | En fazla 64 immutable olay kaydını kopyalar. |
| R18 | RedisProducerGuard.manualRuntimeProfileOverride | F04:L227; this | PURE-MEMORY; KEEP | Yalnız AtomicReference.get. |
| R19 | RedisProducerGuard.setManualRuntimeProfile | F04:L231; this | BLOCKING; LOCK P4 | applyRuntimeProfileOverride L448 -> recordRuntimeProfileEvent L548 -> Redis XADD L570; profil değişmiş ve diagnostics açıksa. |
| R20 | RedisProducerGuard.clearManualRuntimeProfileOverride | F04:L237; this | BLOCKING; LOCK P4 | R22'yi reentrant çağırır; oluşan profil geçişi XADD diagnostics yayını yapabilir. |
| R21 | RedisProducerGuard.activeRuntimeProfileProperties | F04:L244; this | PURE-MEMORY; KEEP | Redis I/O yapan sampleSafely L243 monitör alınmadan ÖNCE çalışır. Blok yalnız config/snapshot String haritası kurar; dış metodun tamamıyla karıştırılmamalı. |
| R22 | RedisProducerGuard.updateRuntimeProfile | F04:L377; this | BLOCKING; LOCK P4 | Normal geçiş L434 ve manual override L393 aynı diagnostics XADD zincirine gider. Sampling'in dışarıda olması bu I/O'yu kaldırmaz. |
| R23 | RedisProjectionRepository.ProjectionQueryCache.get | F05:L693; query-cache this | PURE-MEMORY; KEEP | Private String map/süre temizliği. Okuma yolu atıl: hotQueryCacheKey L291 daima null döner. |
| R24 | RedisProjectionRepository.ProjectionQueryCache.put | F05:L699; query-cache this | PURE-MEMORY; KEEP | Private liste kopyası/LRU (512 kayıt); öğe codec/hash çağrısı yok. Şu an çağrılmaz: cacheHotQuery L279 no-op. Yeniden açılmadan liste uygulamaları incelenmeli. |
| R25 | RedisProjectionRepository.ProjectionQueryCache.clear | F05:L711; query-cache this | PURE-MEMORY; KEEP | L85-86 invalidator'ü yalnız map temizler; warmup loader çağırmaz. |
| R26 | RedisQueryIndexManager.checkCompositeSignature | F06:L64; this | BLOCKING; LOCK P5 | GET L68, koşullu EVAL L74, metadata SPI ve warning logger L88 monitör içindedir. Constructor ve beş saniyelik yenileme buraya gelir. |
| R27 | RedisQueryIndexManager.rebuildFromEntityStore | F06:L232; this | BLOCKING; LOCK P5 | Rebuild-slot/degraded Redis kontrolleri, SCAN/EXISTS/GET/SET/DEL L235-281, codec.fromRedisValue L272 ve reindexEntity -> pipeline.sync. 100000 tarama turu sınırı I/O'yu bellek işi yapmaz. |

### JDBC ve core

| Kimlik | Sınıf/üye | Nokta; monitör | Sınıf; karar | Senkron zincir dahil gerekçe |
| --- | --- | --- | --- | --- |
| J01 | AbstractJdbcWriteBehindFlusher.compactIndependentWrites | F07:L207; reorderLoggedEntities | BLOCKING; LOCK P6 | System.Logger INFO L212-214 senkron console/dosya/network sink'ine yazabilir. Çevreleyen JDBC transaction bu bloğun içinde değildir. |
| C01 | DefaultEntityRegistry.register (5 argüman) | F08:L34; this | BLOCKING; LOCK P7 | EntityRegistry.super.register dış monitörü tutarken yedi argümanlı C02'ye gider. Loader nesneleri geçirilir, çalıştırılmaz. |
| C02 | DefaultEntityRegistry.register (7 argüman) | F08:L45; this | BLOCKING; LOCK P7 | Metadata entityName/compressionThresholdBytes ve PayloadEntityCodec.wrap -> constructor schemaVersion/payloadUpcaster/upcaster.targetVersion genişletilebilir senkron çağrılardır. Üretilenler genellikle bellek işidir; SPI zorunlu tutmaz. |
| C03 | DefaultEntityRegistry.registerProjection | F08:L99; this | BLOCKING; LOCK P7 | metadata.entityName ve projection.name kullanıcı uygulamalarıdır; basit getter olmak zorunda değildir. İş projection mapper'ı çağrılmaz. |
| C04 | DefaultEntityRegistry.find | F08:L153; this | PURE-MEMORY; KEEP | String map/Optional; metadata/loader callback'i yok. |
| C05 | DefaultEntityRegistry.findProjection | F08:L158; this | PURE-MEMORY; KEEP | İki String map lookup; projection çalıştırılmaz. |
| C06 | DefaultEntityRegistry.projections | F08:L167; this | PURE-MEMORY; KEEP | Binding referanslarını kopyalar; callback yok. Entity başına projection sayısı açıkça sınırlı değil; ayrı bellek/gecikme incelemesi gerekir. |
| C07 | DefaultEntityRegistry.all | F08:L176; this | PURE-MEMORY; KEEP | Binding referanslarını kopyalar; entity sayısı register içinde sınırlanır. |
| C08 | StoragePerformanceCollector.LatencyAccumulator.record | F09:L214; samples | PURE-MEMORY; KEEP | ArrayDeque<Long> ekleme/temizleme, SAMPLE_LIMIT=256; atomik metrikler dışarıda güncellenir. |
| C09 | StoragePerformanceCollector.LatencyAccumulator.snapshot | F09:L228; samples | PURE-MEMORY; KEEP | En fazla 256 Long kopyalar; sıralama/yüzdelikler monitörden sonra. |
| C10 | StoragePerformanceCollector.LatencyAccumulator.clear | F09:L249; samples | PURE-MEMORY; KEEP | Yalnız sınırlı deque temizlenir. |

### Starter ve Spring Boot

| Kimlik | Sınıf/üye | Nokta; monitör | Sınıf; karar | Senkron zincir dahil gerekçe |
| --- | --- | --- | --- | --- |
| S01 | AdminConsistencyInspector.scanKeyCountIncrementally | F10:L139; progress | BLOCKING; LOCK P8 | Sınırlı adım döngüsünde jedis.scan L142. Cursor/sayaç/tamamlanma aynı progress için seri kalmalıdır. |
| S02 | AdminConsistencyInspector.IncrementalKeyScan.completedCount | F10:L173; progress this | PURE-MEMORY; KEEP | Yalnız long döner; S01 ile aynı nesne olduğundan kilit birlikte taşınmalı. |
| S03 | CacheDatabase.onDurableEntityChange | F11:L98; this | PURE-MEMORY; KEEP | <=64 kapasite kontrolü ve CopyOnWriteArrayList.add; removal closure döner, çalıştırmaz. publishDurableChange L106 listener'ları bu monitörün dışında çağırır. Listener uygulamaları I/O yapabilir; ayrı inceleme gerekir, tüm listener'lar pure kabul edilmez. |
| S04 | CacheDatabaseAdminHttpServer.ensureBackgroundExecutor | F12:L1127; this | PURE-MEMORY; KEEP | boundedExecutor L1356 yalnız executor/queue/AbortPolicy oluşturur. Prestart/submit/thread-factory çağrısı yok; L1108 submit dönüşten sonradır. |
| S05 | CacheDatabaseSchemaAdmin.migrationHistory | F13:L231; migrationHistory | PURE-MEMORY; KEEP | Sahip olunan synchronized ArrayList kopyalanır; SQL L192-210 dışarıdadır. History retention sınırı yoktur; KEEP, sınırsız kopyaya ölçek onayı değildir. |
| B01 | CacheDistributedJobExecutor.localExecutor | F14:L171; this | PURE-MEMORY; KEEP | Lazy executor/queue oluşturma; monitör altında iş gönderme/başlatma yok. |
| B02 | CacheDistributedJobExecutor.heartbeatExecutor | F14:L181; this | PURE-MEMORY; KEEP | Scheduled executor oluşturur; monitör içinde schedule etmez. |
| B03 | CacheDistributedJobExecutor.close, referans alma | F14:L309; this | PURE-MEMORY; KEEP | Executor referansları alınır; shutdown L313-315 dışarıdadır. |
| B04 | JacksonJsonArrayCodec.prepared | F15:L28; codec this | BLOCKING; LOCK P9 | mapper.readerForListOf L30 custom deserializer'ları eager çözümleyip contextualize ederek kullanıcı koduna gidebilir; pure garantisi yoktur. decodeArray/readValue ve OutputStream yazımı başka yerdedir. |
| B05 | SnapshotJobs.withDatabase | F16:L79; jobs this | PURE-MEMORY; KEEP | Plan/record erişimi, CacheDatabase'in gerçek DefaultEntityRegistry.find çağrısı ve S03 kaydı. L90-114 lambda saklanır, çalışmaz. Sonraki codec.fromColumns/rootIdExtractor çağrıları bloklayabilir; bu uygulamalar pure sayılmaz. |
| B06 | SnapshotJobs.heartbeat | F16:L262; jobs this | PURE-MEMORY; KEEP | Yalnız lazy scheduler oluşturma; L408-426 heartbeat schedule/eval dönüşten sonra. |
| B07 | SnapshotJobs.refresh, kabul | F16:L347; job | PURE-MEMORY; KEEP | closed kontrolü, busy CAS, worker kaydı. Redis lease SET L362'de dışarıda başlar. |
| B08 | SnapshotJobs.refresh, finally | F16:L388; job | PURE-MEMORY; KEEP | Yalnız worker/busy temizlenir; L381-385 Redis cleanup önce çalışır. |
| B09 | SnapshotJobs.execute, renewal lambda | F16:L430; guard | BLOCKING; LOCK P10 | RENEW eval L418 dışarıda; fakat worker.interrupt L433 senkron interruptible-channel blocker/close ve JDK kilitlerine gider. Worker durumuna bağlıdır; yalnız atomik flag değildir. |
| B10 | SnapshotJobs.execute, finally | F16:L531; guard | PURE-MEMORY; KEEP | Yalnız active=false. renewal.cancel(false) L534 dışarıda; geç interrupt önleme el sıkışması için B09 ile taşınmalı. |
| B11 | SnapshotJobs.start | F16:L749; jobs this | BLOCKING; LOCK P10 | scheduleWithFixedDelay L765/L780 scheduled-executor queue kilitlerini alır ve thread başlatır. refresh/dirtyTick worker'da çalışır; inline değil. |
| B12 | SnapshotJobs.close | F16:L800; jobs this | BLOCKING; LOCK P10 | İç B13 interrupt, future.cancel(true), scheduler/heartbeat.shutdownNow L809-811 executor/queue kilitleri ve interrupt içerir. Private changeRegistration, S03 removal closure'ıdır; dışarıdan verilmiş keyfi close değildir. L804 hata logu da koşullu sink riskidir. |
| B13 | SnapshotJobs.close, worker | F16:L806; job (dış jobs this tutuluyor) | BLOCKING; LOCK P10 | job.worker.interrupt L807 senkron blocker cleanup yapabilir; yalnız dış monitörü değiştirmek yetmez. |
| B14 | SnapshotJobs.Job.codec | F16:L830; job this | PURE-MEMORY; KEEP | SnapshotPlan.jsonCodec L100 final JsonSnapshotProjection record'un codec alanını döndürür. Fallback JacksonJsonArrayCodec constructor yalnız referans atar; prepared/deserialization burada çağrılmaz. |


## Kilit Aileleri

| Aile | Değişiklik ve korunan sözleşme |
| --- | --- |
| P1 | Yakın önbellek yaşam döngüsü kilidi; geç oluşturma, sınır, tekilleştirme ve kapanış yarışları korunur |
| P2 | İzleyici kayıt/dağıtım kilitleri; kuyruk, byte ve izleyici sınırları korunur; callback'ler kilit dışında kalır |
| P3 | Doğrulanmış sahiplik ve izleyici kayıt kilitleri; tenant denetimi ve repository görünümlerinin ortak durumu korunur |
| P4 | Runtime profil kilidi; yeniden giriş, geçiş sırası ve tanılama yayını korunur |
| P5 | Sorgu indeksi yaşam döngüsü kilidi; şema imzası, bozulma durumu ve yeniden oluşturma sırası korunur |
| P6 | JDBC gruplama günlüğü kilidi; sınırlı ve tek seferlik günlük kaydı ile yazma sırası korunur |
| P7 | Entity kayıt defterinin ortak okuma/yazma kilidi; yinelenen kayıt ve loader seçimi korunur |
| P8 | Tarama ilerleme kilidi; sınırlı cursor ilerletme ve tamamlanan sayımlar korunur |
| P9 | Jackson okuyucusunun geç oluşturma kilidi; mapper davranışı ve tekrar kullanım denetimi korunur |
| P10 | Snapshot yaşam döngüsü, görev ve yenileme kilitleri; lease sahipliği, iptal ve worker devri korunur |

Metadata, codec, ID, günlük hedefi ve kullanıcı callback'leri serbest kullanıcı
kodu çalıştırabilir. Bir metodun getter gibi adlandırılması, bloklamayacağını
kanıtlamaz. Kullanıcı kodu kendi zaman aşımı ve thread güvenliği kurallarına
uymaya devam etmelidir.

## JFR Doğrulaması

İsteğe bağlı profil, `jdk.VirtualThreadPinned` olayını stack trace ile ve
20 ms eşiğinde kaydeder. Her modül/fork için ayrı, boyutu sınırlı bir kayıt oluşur.

```bash
mvn -B -Pvirtual-thread-readiness \
  -Dcachedb.snapshot.redis=true \
  -Dcachedb.snapshot.redis.host=redis \
  -Dcachedb.snapshot.redis.port=6379 verify
```

```powershell
./tools/ci/test-virtual-thread-pinning.ps1 -EvidenceDirectory /evidence/controls
./tools/ci/check-virtual-thread-pinning.ps1 `
  -RecordingPath @('/evidence/redis.jfr', '/evidence/starter.jfr')
```

Kontrol aracı, hata vermesi gereken gerçek bir monitör sabitlenmesi kaydı ve
geçmesi gereken bir reentrant kilit kaydı üretir. Okuyucu; bozuk, değişmiş, fazla
büyük, kesilmiş veya veri kaybı içeren kanıtı reddeder. Çıkış kodunun 0 olması,
yalnızca verilen kayıtlarda eşleşen CacheDB frame'i görülmediği anlamına gelir;
bütün senaryoların çalıştığını kanıtlamaz. Kapsam, gerekli suite ve atlanan test
denetimleriyle ayrıca doğrulanır.

Referans odaklı doğrulama HotSpot/Temurin 21.0.7 ile yapılmıştır. Pozitif kontrolde
bir sabitlenme olayı görülmüş; negatif kontrolde ve repository/snapshot
testlerinin üç kaydında olay görülmemiştir. Sağlayıcı, Cluster, async ve uygulamaya
özgü yükler ayrıca eksiksiz doğrulanmalıdır. Başka bir JVM/üreticide ölçüm
yeteneği ayrıca kontrol edilmelidir; eksik ölçüm desteği güvenlik kanıtı değildir.

## Çalışma Ayarları

Sanal thread kullanmak CPU, bellek, soket veya bağlantı havuzu sınırlarını
kaldırmaz. Kabul sınırlarını, Redis/JDBC zaman aşımlarını ve geri basıncı koruyun.
Bloklayıcı işi monitör içinde çalıştırmayın. İsteği beklenmedik biçimde çağıran
thread üzerinde çalıştıran reddetme kurallarından kaçının.

### Redis URI Kısayolları

`CacheDatabaseBootstrap.connect(uri, dataSource)` ve `.redis(uri)`, URI ile oluşturdukları istemcilerin
yaşam döngüsünü yönetir. Havuzda bekleyen thread'ler adil sırayla kabul edilir; diğer Jedis 5.2 URI
varsayılanları korunur: `maxTotal=8`, `maxIdle=8`, `minIdle=0`, boş bağlantılarda LIFO kullanımı,
`maxWait=-1` (süresiz havuz beklemesi), bağlantı/okuma zaman aşımı 2000 ms ve bloklayan soket zaman
aşımı 0 (sonsuz). Bu bir uyumluluk davranışıdır; **üretim için sınırlandırılmış çalışma sözleşmesi
değildir**. Adil sıra, süre sınırı eklemez veya CacheDB'nin yapılandırılmış havuz varsayılanlarını uygulamaz.
[SDK URI yolu](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/UnifiedJedis.java)
ve [temel havuz varsayılanları](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.12.0/src/main/java/org/apache/commons/pool2/impl/BaseObjectPoolConfig.java)
kaynaklarına bakın.

TLS, kimlik bilgileri, veritabanı ve sorgu protokolü SDK'nın URI anlamını korur. URI'de port
belirtilmezse -1 korunur; kendiliğinden 6379 atanmaz. Portu açıkça yazın.
Arka plan URI'si oluşturulamazsa daha önce oluşturulmuş, sahip olunan ön plan istemcisi kapatılır.

Üretimde sonlu havuz, bağlantı, okuma ve bloklama zaman aşımlarını `RedisConnectionConfig` ile açıkça
seçin; havuzu iş yüküne göre boyutlandırın ve istemci yaşam döngüsünü Bootstrap dışında yönetin.
Aşağıdaki değerler açık bir örnektir; her ortam için geçerli boyutlandırma varsayılanları değildir:

```java
var connections = RedisConnectionConfig.builder().uri("redis://redis-service:6379")
        .poolMaxTotal(32).poolMaxIdle(32).poolMinIdle(0)
        .poolMaxWaitMillis(2000).connectionTimeoutMillis(2000)
        .readTimeoutMillis(2000).blockingReadTimeoutMillis(5000).build();
try (var redis = connections.createClient();
        var database = CacheDatabaseBootstrap.using(redis, dataSource).config(baseConfig).build()) {
    database.start();
    // Uygulama işlemleri.
}
```

Bootstrap, sağlanan istemciyi havuzunu değiştirmeden ödünç alır; o istemciyi kapatmaz.
Başlatma hataları dahil, dışarıda sahip olunan istemcilerden önce veritabanını kapatın.

HotSpot Java 21 üzerinde büyük L1 heap'leri için `-XX:+UseZGC -XX:+ZGenerational` değerlendirilebilir.
Bu bir ayar önerisidir; ölçülmüş CacheDB kazancı değildir. Üretim ayarını
değiştirmeden önce aynı yük ve bellek sınırlarında karşılaştırın. Bu çöp toplayıcı
bayraklarını OpenJ9 kurulumuna aynen taşımayın; OpenJ9'un kendi
[GC politikalarını](https://eclipse.dev/openj9/docs/gc/) kullanın ve ölçüm desteğini
seçtiğiniz JVM sürümünde doğrulayın. HotSpot sonuçları, OpenJ9 için performans
veya sabitlenme kanıtı değildir.
[Ölçüm yöntemine bakın](performans-olcumleri.md).
