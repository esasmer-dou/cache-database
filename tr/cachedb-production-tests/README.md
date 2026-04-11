# cachedb-production-tests

Bu modul, `cache-database` icin production-benzeri e-ticaret DAO yuk ve kirma testlerini icerir.

Kapsam:

- kampanya, SMS veya push bildirimi sonrasi ani trafik sicrmalari
- browse, urun detay, sepete ekleme ve checkout karisimi
- sicak SKU uzerinde inventory contention
- write-behind backlog birikimi ve cache thrash kirma senaryolari
- tam olcek `50k TPS` benchmark paketleri ve ozet raporlar

Calistirilabilir giris noktasi:

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

- is yukleri 50k TPS sinifi spike senaryolari icin modellenmistir; lokal ortamda `scaleFactor` ile kucultulur
- raporlar `target/cachedb-prodtest-reports` altina JSON ve Markdown olarak yazilir
- her scenario kosusu ayrica `*-profile-churn.json` ve `*-profile-churn.md` dosyalari uretir
- tablolar `cachedb_prodtest_*` on eki ile her kosuda yeniden olusturulur
- ortak runtime/config tuning katalogu: [../docs/tuning-parameters.md](../docs/tuning-parameters.md)

Tam olcek suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Icerdigi `50k TPS` senaryolari:

- `campaign-push-spike-50k`
- `weekend-browse-storm-50k`
- `flash-sale-hot-sku-contention-50k`
- `checkout-wave-after-sms-50k`
- `loyalty-reengagement-mix-50k`
- `catalog-cache-thrash-50k`
- `write-behind-backpressure-50k`
- `inventory-reconciliation-aftershock-50k`

Bu suite `full-scale-50k-suite.json` ve `full-scale-50k-suite.md` dosyalarini uretir.

Kademeli olcek benchmark:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Bu kosu `full-scale-50k-scale-ladder.json` ve `full-scale-50k-scale-ladder.md` dosyalarini uretir.

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

Bu kosu `representative-container-capacity-benchmark.*` dosyalarini uretir.

Guardrail-aware profil karsilastirmasi:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.GuardrailProfileComparisonMain" `
  "-Dcachedb.prod.scaleFactor=0.50" `
  "-Dcachedb.prod.guardrail.compareProfiles=STANDARD,BALANCED,AGGRESSIVE" `
  "-Dcachedb.prod.guardrail.compareScenarios=campaign-push-spike-50k,weekend-browse-storm-50k,write-behind-backpressure-50k"
```

Bu kosu `guardrail-profile-comparison.*` dosyalarini uretir ve throughput, backlog, Redis memory, compaction pending ve balance score dengesini karsilastirir.

Production certification:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain" `
  "-Dcachedb.prod.certification.scenario=campaign-push-spike" `
  "-Dcachedb.prod.certification.scaleFactor=0.02"
```

Bu kosu `production-certification-report.json` ve `production-certification-report.md` uretir. Certification raporu sunlari birlestirir:

- representative benchmark kosusu
- restart/recover dogrulamasi
- crash/replay chaos dogrulamasi
- fault injection dogrulamasi
- TPS, backlog, hata, hard rejection, rebuild basarisi ve restart recovery icin acik go/no-go gate'leri

Admin gozlemlenebilirlik:

- `/api/prometheus` write-behind, DLQ, planner, guardrail ve runtime-profile metriklerini Prometheus text formatinda verir
- `/api/query-index/rebuild` pressure dustukten sonra degraded namespace'leri recover etmek icin kullanilabilir
- `/api/deployment` canli deployment/runtime topology ozetini verir
- `/api/schema/status` bootstrap modu, validation ozeti ve migration adim sayilarini verir
- `/api/schema/history` son schema plan/apply gecmisini migration gorunurlugu icin verir
- `/api/schema/ddl` entity bazli uretilmis bootstrap DDL ciktisini verir
- `/api/registry` kayitli entity/API yuzeyini ve cache kontratini verir
- `/api/profiles` yerlesik starter runtime profillerini verir
- `/api/triage` mevcut ana darboğaz adayini ve destekleyici kanitlari verir
- `/api/services` write-behind, recovery, guardrail, query, schema ve incident delivery icin servis bazli ozet saglik bilgisi verir
- `/api/alert-routing` incident bildirim route'larini, retry policy'lerini, fallback yollarini, escalation seviyesini, delivery sayaçlarini ve son delivery/hata isaretlerini verir
- `/api/alert-routing/history` delivery/failed/dropped trend analizi icin server-side kanal gecmisini verir
- `/api/incident-severity/history` INFO/WARNING/CRITICAL sinyal sicrama trendleri icin server-side incident severity bucket'larini verir
- `/api/failing-signals` severity, aktif sayi ve son incident sikligina gore siralanmis en onemli failing signal ozetini verir
- `/api/history` backlog, Redis memory, dead-letter buyumesi, runtime profile ve health durumu icin server-side sample edilmis trend/gecmis noktalarini verir
- `/api/runbooks` yuksek sinyalli production sorunlari icin varsayilan operator runbook'larini verir
- `/api/certification` son production gate, certification, soak ve fault-injection artefaktlarini listeler
- `/dashboard` artik Triage, Service Status, Alert Routing, Runbooks, Deployment, Schema Status, Schema History, Starter Profiles, API Registry, Schema DDL ve Certification bolumlerini admin UI uzerinde gosterir
- `/dashboard` ayrica AJAX auto-refresh kontrolu, manuel refresh, sayfayi tam yenilemeden backlog, Redis memory, dead-letter, kanal bazli alert route trend/gecmisi, incident severity trendleri ve top failing signal kartlari sunar

Yuksek sinyalli cekirdek test matrisi:
- kampanya tetikli browse ve checkout burst'leri
- write-behind backlog ve backpressure buyumesi
- PostgreSQL yavaslamasi veya gecici kaybi ve replay recovery
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

Bu kosu `*-soak.json` ve `*-soak.md` dosyalari uretir. Rapor sunlari icerir:

- min/ort/max TPS
- maksimum Redis memory envelope
- maksimum backlog ve compaction pending
- runtime profile switch toplamları
- iterasyon bazli health ozeti

Uzun soak planlari:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionSoakPlanMain" `
  "-Dcachedb.prod.soak.plans=soak-1h:campaign-push-spike:0.02:1:3600:false,soak-4h:campaign-push-spike:0.02:1:14400:false"
```

Bu kosu `production-soak-plan-report.json` ve `production-soak-plan-report.md` dosyalarini uretir.

Restart recovery suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RestartRecoverySuiteMain" `
  "-Dcachedb.prod.restart.cycles=3"
```

Bu kosu `restart-recovery-suite.json` ve `restart-recovery-suite.md` dosyalarini uretir.

Crash/replay chaos suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.CrashReplayChaosMain"
```

Bu kosu `crash-replay-chaos-suite.json` ve `crash-replay-chaos-suite.md` dosyalarini uretir. Su senaryolari kapsar:

- latest-state delete restart sonrasi stale resurrection olmadan korunur
- exact-sequence order durumu restart sonrasi son duruma converge eder
- manual dead-letter replay restart sonrasi da erisilebilir kalir

Fault injection suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FaultInjectionMain"
```

Bu kosu `fault-injection-suite.json` ve `fault-injection-suite.md` dosyalarini uretir. Su senaryolari kapsar:

- yarim flush restart sonrasi toparlanir
- gecici PostgreSQL kaybi DLQ olusturur ve replay ile toparlanir
- restart sonrasi stale replay ordering kurallariyla reddedilir
- tekrarli outage/replay donguleri bounded recovery-soak olarak dogrulanir

Production gate:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionGateMain"
```

Bu kosu `production-gate-report.json` ve `production-gate-report.md` dosyalarini uretir. Sunlari birlestirir:

- production certification
- crash/replay chaos suite
- fault injection suite
- drain completion ve hard-rejection kontrolleri

Son temiz kosu durumu:

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

Bu kosu `production-gate-ladder-report.json` ve `production-gate-ladder-report.md` dosyalarini uretir.

Yararli override'lar:

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

Birden fazla catalog girdisi `|` ile ayrilir.

Flush notu:

- benchmark profilleri PostgreSQL flush hattinda entity-aware state compaction ve batch policy kullanir
- `customer`, `inventory` ve `cart` upsert akislari daha agresif compaction ve copy yollarina itilir
- `order` yazmalari daha tutucu tutulur ve daha dogrudan persist edilir

Entity semantics matrisi:

| Entity | UPSERT semantigi | DELETE semantigi | Production niyeti |
| --- | --- | --- | --- |
| `EcomCustomerEntity` | `LATEST_STATE` | `LATEST_STATE` | kampanya ve musteri profil guncellemeleri son bilinen duruma katlanir |
| `EcomInventoryEntity` | `LATEST_STATE` | `LATEST_STATE` | sicak SKU stok guncellemelerinde tum ara adimlar yerine son stok dogrusu oncelenir |
| `EcomCartEntity` | `LATEST_STATE` | `LATEST_STATE` | sepet durumu degisebilir session state olarak ele alinir |
| `EcomOrderEntity` | `EXACT_SEQUENCE` | `EXACT_SEQUENCE` | order yazmalari sira onemli oldugu icin daha tutucu tutulur |

Hard-limit shedding notu:

- runtime hard-limit modunda page cache write, read-through cache fill, hot-set tracking, query index write, query index read ve planner learning ozellikleri shed edilir
- query index write shed oldugunda namespace degraded olarak isaretlenir ve sorgular entity key taramasi ile residual evaluation fallback yoluna duser
- delete islemleri sinirli TTL ile Redis tombstone birakir; okumalar tombstone'u dikkate aldigi icin stale Redis payload silinmis entity'yi diriltemez
- query/index recovery admin katmanindan tetiklenebilir; pressure dustukten sonra degraded namespace manual veya otomatik rebuild ile toparlanabilir

Admin rebuild ve recovery:

- `POST /api/query-index/rebuild?entity=UserEntity&note=manual-recover` tek entity namespace'i rebuild eder
- `POST /api/query-index/rebuild?note=manual-recover-all` tum kayitli entity namespace'lerini rebuild eder
- rebuild beklerken degraded namespace okunabilir kalir; sorgular full-scan fallback ile calisir
- namespace ve query-class hard-limit policy'leri ile exact lookup acik tutulurken text, relation veya sort agirlikli sorgular shed edilebilir

Runtime profile switching notu:

- runtime switching production odakli profillerde varsayilan olarak aciktir
- hedef gecisler `NORMAL -> STANDARD`, `WARN -> BALANCED`, `CRITICAL -> AGGRESSIVE` seklindedir
- switching tek sample ile degil, ardisik pressure sample sayilariyla calisir
- comparison suite zorunlu profil kullandiginda runtime auto-switch kapatilir
- scenario ve suite raporlari switch count ve timeline bilgisini yazar
- ayri profile churn raporlari her scenario icin yapisal switch event'lerini uretir
- switch event'leri diagnostics stream'e `RUNTIME_PROFILE_SWITCH` olarak da yazilir
