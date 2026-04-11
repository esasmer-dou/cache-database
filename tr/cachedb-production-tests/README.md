# cachedb-production-tests

Bu modül, `cache-database` için production-benzeri e-ticaret DAO yuk ve kırma testlerini içerir.

Kapsam:

- kampanya, SMS veya push bildirimi sonrasi ani trafik sıçramaları
- browse, ürün detay, sepete ekleme ve checkout karışımı
- sıcak SKU üzerinde inventory contention
- write-behind backlog birikimi ve cache thrash kırma senaryoları
- tam ölçek `50k TPS` benchmark paketleri ve özet raporlar

Çalıştırılabilir giriş noktası:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.EcommerceProductionScenarioMain" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres" `
  "-Dcachedb.prod.postgres.user=postgres" `
  "-Dcachedb.prod.postgres.password=postgresql"
```

Smoke test:

```powershell
mvn -q -pl cachedb-production-tests -am -Dtest=EcommerceProductionScenarioSmokeTest "-Dsurefire.failIfNoSpecifiedTests=false" test `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:56379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:55432/postgres" `
  "-Dcachedb.prod.postgres.user=postgres" `
  "-Dcachedb.prod.postgres.password=postgresql"
```

Notlar:

- is yükleri 50k TPS sınıfi spike senaryoları için modellenmiştir; lokal ortamda `scaleFactor` ile küçültülür
- raporlar `target/cachedb-prodtest-reports` altına JSON ve Markdown olarak yazılır
- her scenario koşusu ayrıca `*-profile-churn.json` ve `*-profile-churn.md` dosyaları üretir
- tablolar `cachedb_prodtest_*` on eki ile her koşuda yeniden oluşturulur
- ortak runtime/config tuning kataloğu: [../docs/tuning-parameters.md](../docs/tuning-parameters.md)

Tam ölçek suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

İçerdiği `50k TPS` senaryoları:

- `campaign-push-spike-50k`
- `weekend-browse-storm-50k`
- `flash-sale-hot-sku-contention-50k`
- `checkout-wave-after-sms-50k`
- `loyalty-reengagement-mix-50k`
- `catalog-cache-thrash-50k`
- `write-behind-backpressure-50k`
- `inventory-reconciliation-aftershock-50k`

Bu suite `full-scale-50k-suite.json` ve `full-scale-50k-suite.md` dosyalarıni üretir.

Kademeli ölçek benchmark:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Bu koşu `full-scale-50k-scale-ladder.json` ve `full-scale-50k-scale-ladder.md` dosyalarıni üretir.

Temsilci container-capacity benchmark:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepresentativeCapacityBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.fullSuite.durationSeconds=5" `
  "-Dcachedb.prod.fullSuite.datasetScale=0.0002" `
  "-Dcachedb.prod.fullSuite.maxCustomers=50" `
  "-Dcachedb.prod.fullSuite.maxProducts=20" `
  "-Dcachedb.prod.fullSuite.maxHotProducts=5"
```

Bu koşu `representative-container-capacity-benchmark.*` dosyalarıni üretir.

Guardrail-aware profil karşılastirmasi:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.GuardrailProfileComparisonMain" `
  "-Dcachedb.prod.scaleFactor=0.50" `
  "-Dcachedb.prod.guardrail.compareProfiles=STANDARD,BALANCED,AGGRESSIVE" `
  "-Dcachedb.prod.guardrail.compareScenarios=campaign-push-spike-50k,weekend-browse-storm-50k,write-behind-backpressure-50k"
```

Bu koşu `guardrail-profile-comparison.*` dosyalarıni üretir ve throughput, backlog, Redis memory, compaction pending ve balance score dengesini karşılastirir.

Production certification:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain" `
  "-Dcachedb.prod.certification.scenario=campaign-push-spike" `
  "-Dcachedb.prod.certification.scaleFactor=0.02"
```

Bu koşu `production-certification-report.json` ve `production-certification-report.md` üretir. Certification raporu şunları birlestirir:

- representative benchmark koşusu
- restart/recover doğrulamasi
- crash/replay chaos doğrulamasi
- fault injection doğrulamasi
- TPS, backlog, hata, hard rejection, rebuild başarisi ve restart recovery için açık go/no-go gate'leri

Admin gözlemlenebilirlik:

- `/api/prometheus` write-behind, DLQ, planner, guardrail ve runtime-profile metriklerini Prometheus text formatinda verir
- `/api/query-index/rebuild` pressure düştukten sonra değraded namespace'leri recover etmek için kullanılabilir
- `/api/deployment` canli deployment/runtime topology özetini verir
- `/api/schema/status` bootstrap modu, validation özeti ve migration adim sayilarini verir
- `/api/schema/history` son schema plan/apply geçmişini migration görünürlüğü için verir
- `/api/schema/ddl` entity bazlı üretilmiş bootstrap DDL çıktisini verir
- `/api/registry` kayıtlı entity/API yüzeyini ve cache kontratini verir
- `/api/profiles` yerleşik starter runtime profillerini verir
- `/api/triage` mevcut ana darboğaz adayini ve destekleyici kanıtlari verir
- `/api/services` write-behind, recovery, guardrail, query, schema ve incident delivery için servis bazlı özet sağlik bilgisi verir
- `/api/alert-routing` incident bildirim route'larini, retry policy'lerini, fallback yollarini, escalation seviyesini, delivery sayaçlarini ve son delivery/hata işaretlerini verir
- `/api/alert-routing/history` delivery/failed/dropped trend analizi için server-side kanal geçmişini verir
- `/api/incident-severity/history` INFO/WARNING/CRITICAL sinyal sicrama trendleri için server-side incident severity bucket'larini verir
- `/api/failing-signals` severity, aktif sayi ve son incident sıklığına göre sıralanmis en önemli failing signal özetini verir
- `/api/history` backlog, Redis memory, dead-letter büyümesi, runtime profile ve health durumu için server-side sample edilmis trend/geçmis noktalarini verir
- `/api/runbooks` yüksek sinyalli production sorunlari için varsayılan operator runbook'larini verir
- `/api/certification` son production gate, certification, soak ve fault-injection artefaktlarini listeler
- `/dashboard` artık Triage, Service Status, Alert Routing, Runbooks, Deployment, Schema Status, Schema History, Starter Profiles, API Registry, Schema DDL ve Certification bölümlerini admin UI üzerinde gösterir
- `/dashboard` ayrıca AJAX auto-refresh kontrolu, manuel refresh, sayfayi tam yenilemeden backlog, Redis memory, dead-letter, kanal bazlı alert route trend/geçmisi, incident severity trendleri ve top failing signal kartlari sunar

Yüksek sinyalli çekirdek test matrisi:
- kampanya tetikli browse ve checkout burst'leri
- write-behind backlog ve backpressure büyümesi
- PostgreSQL yavaşlaması veya geçici kaybı ve replay recovery
- restart/crash/replay correctness
- 1h ve 4h soak boundedness

Soak runner:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionSoakMain" `
  "-Dcachedb.prod.soak.scenario=campaign-push-spike" `
  "-Dcachedb.prod.soak.scaleFactor=0.02" `
  "-Dcachedb.prod.soak.iterations=3"
```

Bu koşu `*-soak.json` ve `*-soak.md` dosyaları üretir. Rapor şunları içerir:

- min/ort/max TPS
- maksimum Redis memory envelope
- maksimum backlog ve compaction pending
- runtime profile switch toplamları
- iterasyon bazlı health özeti

Uzun soak planları:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionSoakPlanMain" `
  "-Dcachedb.prod.soak.plans=soak-1h:campaign-push-spike:0.02:1:3600:false,soak-4h:campaign-push-spike:0.02:1:14400:false"
```

Bu koşu `production-soak-plan-report.json` ve `production-soak-plan-report.md` dosyalarıni üretir.

Restart recovery suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RestartRecoverySuiteMain" `
  "-Dcachedb.prod.restart.cycles=3"
```

Bu koşu `restart-recovery-suite.json` ve `restart-recovery-suite.md` dosyalarıni üretir.

Crash/replay chaos suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.CrashReplayChaosMain"
```

Bu koşu `crash-replay-chaos-suite.json` ve `crash-replay-chaos-suite.md` dosyalarıni üretir. Şu senaryoları kapsar:

- latest-state delete restart sonrasi stale resurrection olmadan korunur
- exact-sequence order durumu restart sonrasi son duruma converge eder
- manual dead-letter replay restart sonrasi da erişilebilir kalir

Fault injection suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FaultInjectionMain"
```

Bu koşu `fault-injection-suite.json` ve `fault-injection-suite.md` dosyalarıni üretir. Şu senaryoları kapsar:

- yarım flush restart sonrasi toparlanir
- geçici PostgreSQL kaybı DLQ oluşturur ve replay ile toparlanir
- restart sonrasi stale replay ordering kurallariyla reddedilir
- tekrarlı outage/replay döngüleri bounded recovery-soak olarak doğrulanir

Production gate:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionGateMain"
```

Bu koşu `production-gate-report.json` ve `production-gate-report.md` dosyalarıni üretir. Sunlari birlestirir:

- production certification
- crash/replay chaos suite
- fault injection suite
- drain completion ve hard-rejection kontrolleri

Son temiz koşu durumu:

- `production gate`: `PASS`
- `production certification`: `PASS`
- `crash/replay chaos`: `PASS`
- `fault injection`: `PASS`
- `drain completion`: `PASS`
- `hard rejections`: `PASS`

Production gate ladder:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionGateLadderMain" `
  "-Dcachedb.prod.gateLadder.profiles=baseline:campaign-push-spike:0.02:50:2000:false,heavy:campaign-push-spike:0.05:65:3000:true"
```

Bu koşu `production-gate-ladder-report.json` ve `production-gate-ladder-report.md` dosyalarıni üretir.

Yararlı override'lar:

- `cachedb.prod.catalog.scenarios=<scenario girdileri>`
- `cachedb.prod.catalog.fullScaleScenarios=<scenario girdileri>`
- `cachedb.prod.catalog.representativeScenarioNames=senaryo1,senaryo2`
- `cachedb.prod.fullSuite.scenarios=senaryo1,senaryo2`
- `cachedb.prod.fullSuite.durationSeconds=60`
- `cachedb.prod.fullSuite.workerThreads=64`
- `cachedb.prod.fullSuite.datasetScale=0.25`
- `cachedb.prod.fullSuite.maxCustomers=50`
- `cachedb.prod.fullSuite.maxProducts=20`
- `cachedb.prod.fullSuite.maxHotProducts=5`
- `cachedb.prod.fullSuite.maxHotEntityLimit=200`
- `cachedb.prod.fullSuite.maxPageSize=20`
- `cachedb.prod.seed.mode=bulk`
- `cachedb.prod.seed.postgresBatchSize=1000`
- `cachedb.prod.seed.redisBatchSize=2000`
- `cachedb.prod.seed.unloggedTables=true`
- `cachedb.prod.writeBehind.batchFlushEnabled=true`
- `cachedb.prod.writeBehind.tableAwareBatchingEnabled=true`
- `cachedb.prod.writeBehind.flushGroupParallelism=2`
- `cachedb.prod.writeBehind.flushPipelineDepth=2`
- `cachedb.prod.writeBehind.coalescingEnabled=true`
- `cachedb.prod.writeBehind.maxFlushBatchSize=250`
- `cachedb.prod.writeBehind.batchStaleCheckEnabled=true`
- `cachedb.prod.writeBehind.postgresMultiRowFlushEnabled=true`
- `cachedb.prod.writeBehind.postgresMultiRowStatementRowLimit=64`
- `cachedb.prod.writeBehind.postgresCopyBulkLoadEnabled=true`
- `cachedb.prod.writeBehind.postgresCopyThreshold=128`
- `cachedb.prod.writeBehind.compactionShardCount=4`
- `cachedb.prod.writeBehind.entityFlushPoliciesEnabled=true`
- `cachedb.prod.redis.usedMemoryWarnBytes=2147483648`
- `cachedb.prod.redis.usedMemoryCriticalBytes=3221225472`
- `cachedb.prod.redis.compactionPendingWarnThreshold=1000`
- `cachedb.prod.redis.compactionPendingCriticalThreshold=5000`
- `cachedb.prod.redis.compactionPayloadTtlSeconds=3600`
- `cachedb.prod.redis.compactionPendingTtlSeconds=3600`
- `cachedb.prod.redis.versionKeyTtlSeconds=86400`
- `cachedb.prod.redis.tombstoneTtlSeconds=86400`
- `cachedb.prod.redis.shedQueryIndexWritesOnHardLimit=true`
- `cachedb.prod.redis.shedQueryIndexReadsOnHardLimit=true`
- `cachedb.prod.redis.shedPlannerLearningOnHardLimit=true`
- `cachedb.prod.guardrail.autoDegradeProfileEnabled=true`
- `cachedb.prod.redis.automaticRuntimeProfileSwitchingEnabled=true`
- `cachedb.prod.redis.warnSamplesToBalanced=3`
- `cachedb.prod.redis.criticalSamplesToAggressive=2`
- `cachedb.prod.redis.warnSamplesToDeescalateAggressive=4`
- `cachedb.prod.redis.normalSamplesToStandard=5`
- `cachedb.prod.guardrail.forceDegradeProfile=STANDARD`
- `cachedb.prod.guardrail.compareProfiles=STANDARD,BALANCED,AGGRESSIVE`
- `cachedb.prod.guardrail.compareScenarios=campaign-push-spike-50k,weekend-browse-storm-50k`
- `cachedb.prod.gateLadder.profiles=baseline:campaign-push-spike:0.02:50:2000:false,heavy:campaign-push-spike:0.05:65:3000:true`
- `cachedb.prod.soak.targetDurationSeconds=3600`
- `cachedb.prod.soak.targetTps=5000`
- `cachedb.prod.soak.disableSafetyCaps=true`
- `cachedb.prod.soak.plans=soak-1h:campaign-push-spike:0.02:1:3600:false,soak-4h:campaign-push-spike:0.02:1:14400:false`
- `cachedb.prod.producer.backlogSampleEveryOperations=32`
- `cachedb.prod.producer.backlogSampleIntervalMillis=50`
- `cachedb.prod.pathSeparationEnabled=true`
- `cachedb.prod.readPathWorkerShare=0.60`
- `cachedb.prod.scaleLadder=0.10,0.25,0.50,1.0`

Catalog girdi formati:

```text
name;kind;description;targetTps;durationSeconds;workerThreads;customerCount;productCount;hotProductSetSize;browsePercent;productLookupPercent;cartWritePercent;inventoryReservePercent;checkoutPercent;customerTouchPercent;writeBehindWorkerThreads;writeBehindBatchSize;hotEntityLimit;pageSize;entityTtlSeconds;pageTtlSeconds
```

Birden fazla catalog girdisi `|` ile ayrılır.

Flush notu:

- benchmark profilleri PostgreSQL flush hattında entity-aware state compaction ve batch policy kullanır
- `customer`, `inventory` ve `cart` upsert akışları daha agresif compaction ve copy yollarina itilir
- `order` yazmalari daha tutucu tutulur ve daha doğrudan persist edilir

Entity semantics matrisi:

| Entity | UPSERT semantigi | DELETE semantigi | Production niyeti |
| --- | --- | --- | --- |
| `EcomCustomerEntity` | `LATEST_STATE` | `LATEST_STATE` | kampanya ve müşteri profil güncellemeleri son bilinen duruma katlanir |
| `EcomInventoryEntity` | `LATEST_STATE` | `LATEST_STATE` | sıcak SKU stok güncellemelerinde tüm ara adimlar yerine son stok doğrusu öncelenir |
| `EcomCartEntity` | `LATEST_STATE` | `LATEST_STATE` | sepet durumu değişebilir session state olarak ele alınır |
| `EcomOrderEntity` | `EXACT_SEQUENCE` | `EXACT_SEQUENCE` | order yazmalari sıra önemli olduğu için daha tutucu tutulur |

Hard-limit shedding notu:

- runtime hard-limit modunda page cache write, read-through cache fill, hot-set tracking, query index write, query index read ve planner learning özellikleri shed edilir
- query index write shed olduğunda namespace değraded olarak isaretlenir ve sorgular entity key taraması ile residual evaluation fallback yoluna düşer
- delete islemleri sinirli TTL ile Redis tombstone birakir; okumalar tombstone'u dikkate aldigi için stale Redis payload silinmis entity'yi diriltemez
- query/index recovery admin katmanından tetiklenebilir; pressure düştukten sonra değraded namespace manual veya otomatik rebuild ile toparlanabilir

Admin rebuild ve recovery:

- `POST /api/query-index/rebuild?entity=UserEntity&note=manual-recover` tek entity namespace'i rebuild eder
- `POST /api/query-index/rebuild?note=manual-recover-all` tüm kayıtlı entity namespace'lerini rebuild eder
- rebuild beklerken değraded namespace okunabilir kalir; sorgular full-scan fallback ile çalışir
- namespace ve query-class hard-limit policy'leri ile exact lookup açık tutulurken text, relation veya sort ağırlikli sorgular shed edilebilir

Runtime profile switching notu:

- runtime switching production odaklı profillerde varsayılan olarak açıktir
- hedef geçisler `NORMAL -> STANDARD`, `WARN -> BALANCED`, `CRITICAL -> AGGRESSIVE` şeklindedir
- switching tek sample ile değil, ardışık pressure sample sayilariyla çalışir
- comparison suite zorunlu profil kullandiginda runtime auto-switch kapatilir
- scenario ve suite raporlari switch count ve timeline bilgisini yazar
- ayrı profile churn raporlari her scenario için yapısal switch event'lerini üretir
- switch event'leri diagnostics stream'e `RUNTIME_PROFILE_SWITCH` olarak da yazılır
