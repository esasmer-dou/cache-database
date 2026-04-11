# cachedb-production-tests

This module contains production-like ecommerce DAO load and breaker tests for `cache-database`.

If you are deciding which application surface to use before running these scenarios, start with the decision guide in [../docs/production-recipes.md](../docs/production-recipes.md).
If you are preparing a public-beta release, also review [../docs/public-beta-readiness.md](../docs/public-beta-readiness.md) and [../docs/release-checklist.md](../docs/release-checklist.md).

The repo also ships official CI evidence lanes:

- workflow: [../.github/workflows/production-evidence.yml](../.github/workflows/production-evidence.yml)
- local runner: [../tools/ci/run-production-evidence.ps1](../tools/ci/run-production-evidence.ps1)
- coordination runner: [../tools/ci/run-multi-instance-coordination-evidence.ps1](../tools/ci/run-multi-instance-coordination-evidence.ps1)
- summary generator: [../tools/ci/write-production-evidence-summary.ps1](../tools/ci/write-production-evidence-summary.ps1)

Those lanes cover two different concerns:

- benchmark and recipe evidence for production-facing read-model/runtime decisions
- multi-instance coordination evidence for shared Redis + shared PostgreSQL deployments

Coverage:

- campaign, SMS, or push-driven traffic spikes
- browse, product detail, add-to-cart, and checkout mixes
- hot SKU inventory contention
- write-behind backlog and cache-thrash breaker scenarios
- full-scale `50k TPS` benchmark suites and summary reports

Runnable entrypoint:

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

Multi-instance coordination smoke:

```powershell
.\tools\ops\cluster\run-multi-instance-coordination-smoke.ps1 `
  -RedisUri "redis://default:welcome1@127.0.0.1:56379" `
  -PostgresUrl "jdbc:postgresql://127.0.0.1:55432/postgres" `
  -PostgresUser "postgres" `
  -PostgresPassword "postgresql"
```

This smoke validates three production-critical coordination behaviors on one machine:

- worker consumer names stay instance-unique while consumer groups stay shared
- singleton cleanup/history/report loops fail over through the Redis leader lease
- abandoned write-behind pending work is claimed and drained by another instance

It writes:

- `target/cachedb-prodtest-reports/multi-instance-coordination-smoke.json`
- `target/cachedb-prodtest-reports/multi-instance-coordination-smoke.md`

Notes:

- workloads are modeled for 50k TPS class spikes and scaled down locally with `scaleFactor`
- reports are written to `target/cachedb-prodtest-reports` in JSON and Markdown
- each scenario also writes dedicated churn reports as `*-profile-churn.json` and `*-profile-churn.md`
- tables are recreated per run under the `cachedb_prodtest_*` prefix
- shared runtime/config tuning catalog: [../docs/tuning-parameters.md](../docs/tuning-parameters.md)
- on one workstation, `HOSTNAME` is shared across local processes; use explicit `cachedb.runtime.instance-id` values or the coordination smoke runner when you want to simulate multiple app instances

Full-scale suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Included `50k TPS` scenarios:

- `campaign-push-spike-50k`
- `weekend-browse-storm-50k`
- `flash-sale-hot-sku-contention-50k`
- `checkout-wave-after-sms-50k`
- `loyalty-reengagement-mix-50k`
- `catalog-cache-thrash-50k`
- `write-behind-backpressure-50k`
- `inventory-reconciliation-aftershock-50k`

This suite writes `full-scale-50k-suite.json` and `full-scale-50k-suite.md`.

Scale ladder benchmark:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

This run writes `full-scale-50k-scale-ladder.json` and `full-scale-50k-scale-ladder.md`.

Representative container-capacity benchmark:

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

This run writes `representative-container-capacity-benchmark.*`.

Repository recipe comparison:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain" `
  "-Dcachedb.prod.recipeBenchmark.warmupIterations=10000" `
  "-Dcachedb.prod.recipeBenchmark.measuredIterations=50000"
```

This run writes `repository-recipe-comparison.json` and `repository-recipe-comparison.md`.

It compares the same CacheDB feature set across three consumption styles:

- `JPA-style domain module`: generated package module plus grouped queries/commands/pages
- `Generated entity binding`: compile-time generated entity helper surface
- `Minimal repository`: direct `EntityRepository` and `ProjectionRepository` usage

Important note:

- this benchmark is intentionally about CacheDB API-surface overhead, not external Hibernate/JPA runtime cost
- use the existing production scenario runners for end-to-end Redis/PostgreSQL latency and throughput

Relation-heavy read-shape comparison:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ReadShapeBenchmarkMain" `
  "-Dcachedb.prod.readShapeBenchmark.ordersPerRead=24" `
  "-Dcachedb.prod.readShapeBenchmark.fullLinesPerOrder=48" `
  "-Dcachedb.prod.readShapeBenchmark.previewLinesPerOrder=8"
```

This run writes `relation-read-shape-comparison.json` and `relation-read-shape-comparison.md`.

It is intentionally focused on application-side read-shape cost, not Redis I/O. It compares:

- summary list
- summary list plus single preview detail
- preview list
- full aggregate list

Use it to make object-graph and relation-hydration tradeoffs visible before widening list endpoints in production.

Guardrail-aware profile comparison:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.GuardrailProfileComparisonMain" `
  "-Dcachedb.prod.scaleFactor=0.50" `
  "-Dcachedb.prod.guardrail.compareProfiles=STANDARD,BALANCED,AGGRESSIVE" `
  "-Dcachedb.prod.guardrail.compareScenarios=campaign-push-spike-50k,weekend-browse-storm-50k,write-behind-backpressure-50k"
```

This run writes `guardrail-profile-comparison.*` and compares throughput, backlog, Redis memory, compaction pending, and balance score across profiles.

Production certification:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain" `
  "-Dcachedb.prod.certification.scenario=campaign-push-spike" `
  "-Dcachedb.prod.certification.scaleFactor=0.02"
```

This run writes `production-certification-report.json` and `production-certification-report.md`. The certification report combines:

- a representative benchmark run
- restart/recover verification
- crash/replay chaos verification
- fault injection verification
- explicit go/no-go gates for TPS, backlog, failures, hard rejections, rebuild success, and restart recovery

Admin observability:

- `/api/prometheus` exposes Prometheus text format for write-behind, DLQ, planner, guardrail, and runtime-profile metrics
- `/api/query-index/rebuild` can recover degraded namespaces after pressure falls
- `/api/deployment` exposes live deployment/runtime topology summary
- `/api/schema/status` exposes bootstrap mode, validation summary, and migration-step counts
- `/api/schema/history` exposes recent schema plan/apply history for migration visibility
- `/api/schema/ddl` exposes generated bootstrap DDL per entity
- `/api/registry` exposes registered entity/API surface and cache contract summary
- `/api/profiles` exposes built-in starter runtime profiles
- `/api/triage` exposes the current suspected bottleneck and supporting evidence
- `/api/services` exposes service-level health summaries for write-behind, recovery, guardrails, query, schema, and incident delivery
- `/api/alert-routing` exposes the configured incident delivery routes, retry policies, fallback paths, escalation level, delivery counts, and last delivery/error markers
- `/api/alert-routing/history` exposes server-side channel history for delivery/failed/dropped trend analysis
- `/api/incident-severity/history` exposes server-side incident severity trend buckets for INFO/WARNING/CRITICAL signal spikes
- `/api/failing-signals` exposes the top failing signals ranked by severity, active count, and recent incident frequency
- `/api/history` exposes server-side sampled trend/history points for backlog, Redis memory, dead-letter growth, runtime profile, and health status
- `/api/runbooks` exposes the default operator runbooks for high-signal production failures
- `/api/certification` exposes the latest production gate, certification, soak, and fault-injection artifacts
- `/dashboard` now renders Triage, Service Status, Alert Routing, Runbooks, Deployment, Schema Status, Schema History, Starter Profiles, API Registry, Schema DDL, and Certification sections on the admin UI
- `/dashboard` also supports AJAX auto-refresh controls, manual refresh, server-side trend charts for backlog, Redis memory, dead-letter growth, channel-level alert route trends/history, incident severity trends, and top failing signal summary cards without a full page reload

High-signal core test matrix:
- campaign-triggered browse and checkout bursts
- write-behind backlog and backpressure growth
- PostgreSQL slowdown or temporary loss with replay recovery
- restart/crash/replay correctness
- 1h and 4h soak boundedness

Soak runner:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionSoakMain" `
  "-Dcachedb.prod.soak.scenario=campaign-push-spike" `
  "-Dcachedb.prod.soak.scaleFactor=0.02" `
  "-Dcachedb.prod.soak.iterations=3"
```

This run writes `*-soak.json` and `*-soak.md` files with:

- min/avg/max TPS
- max Redis memory envelope
- max backlog and compaction pending
- runtime profile switch totals
- per-iteration health summary

Long soak plans:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionSoakPlanMain" `
  "-Dcachedb.prod.soak.plans=soak-1h:campaign-push-spike:0.02:1:3600:false,soak-4h:campaign-push-spike:0.02:1:14400:false"
```

This run writes `production-soak-plan-report.json` and `production-soak-plan-report.md`.

Restart recovery suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RestartRecoverySuiteMain" `
  "-Dcachedb.prod.restart.cycles=3"
```

This run writes `restart-recovery-suite.json` and `restart-recovery-suite.md`.

Crash/replay chaos suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.CrashReplayChaosMain"
```

This run writes `crash-replay-chaos-suite.json` and `crash-replay-chaos-suite.md`. It covers:

- latest-state delete surviving restart without stale resurrection
- exact-sequence order state converging after restart
- manual dead-letter replay remaining available after restart

Fault injection suite:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FaultInjectionMain"
```

This run writes `fault-injection-suite.json` and `fault-injection-suite.md`. It covers:

- mid-flush restart recovery
- transient PostgreSQL loss leading to DLQ and replay recovery
- replay ordering after restart with stale replay rejection
- repeated outage/replay cycles as a bounded recovery-soak check

Production gate:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionGateMain"
```

This run writes `production-gate-report.json` and `production-gate-report.md`. It composes:

- production certification
- crash/replay chaos suite
- fault injection suite
- drain completion and hard-rejection checks

Latest clean-run status:

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

This run writes `production-gate-ladder-report.json` and `production-gate-ladder-report.md`.

Useful overrides:

- `cachedb.prod.catalog.scenarios=<scenario entries>`
- `cachedb.prod.catalog.fullScaleScenarios=<scenario entries>`
- `cachedb.prod.catalog.representativeScenarioNames=scenario1,scenario2`
- `cachedb.prod.fullSuite.scenarios=scenario1,scenario2`
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

Catalog entry format:

```text
name;kind;description;targetTps;durationSeconds;workerThreads;customerCount;productCount;hotProductSetSize;browsePercent;productLookupPercent;cartWritePercent;inventoryReservePercent;checkoutPercent;customerTouchPercent;writeBehindWorkerThreads;writeBehindBatchSize;hotEntityLimit;pageSize;entityTtlSeconds;pageTtlSeconds
```

Multiple catalog entries are separated with `|`.

Flush note:

- benchmark profiles use entity-aware state compaction and batch policies on the PostgreSQL flush path
- `customer`, `inventory`, and `cart` upserts are pushed toward more aggressive compaction and copy paths
- `order` writes stay more conservative and closer to direct persistence

Entity semantics matrix:

| Entity | UPSERT semantics | DELETE semantics | Production intent |
| --- | --- | --- | --- |
| `EcomCustomerEntity` | `LATEST_STATE` | `LATEST_STATE` | campaign and customer-profile updates collapse to the latest known state |
| `EcomInventoryEntity` | `LATEST_STATE` | `LATEST_STATE` | hot SKU stock updates prefer final stock truth over replaying every intermediate mutation |
| `EcomCartEntity` | `LATEST_STATE` | `LATEST_STATE` | cart state is treated as mutable session state |
| `EcomOrderEntity` | `EXACT_SEQUENCE` | `EXACT_SEQUENCE` | order writes stay conservative because ordering matters |

Hard-limit shedding note:

- runtime hard-limit mode sheds page cache writes, read-through cache fills, hot-set tracking, query index writes, query index reads, and planner learning
- if query index writes are shed, the namespace is marked degraded and queries fall back to entity-key scans plus residual evaluation
- delete operations now leave Redis tombstones behind for a bounded TTL, and reads honor tombstones so stale Redis payloads cannot resurrect deleted entities
- query/index recovery is available through the admin layer; degraded namespaces can be rebuilt manually, and auto-rebuild may run after pressure falls back below hard-limit conditions

Admin rebuild and recovery:

- `POST /api/query-index/rebuild?entity=UserEntity&note=manual-recover` rebuilds one entity namespace
- `POST /api/query-index/rebuild?note=manual-recover-all` rebuilds every registered entity namespace
- runtime recovery keeps degraded namespaces readable through full-scan fallback while rebuild is pending
- namespace and query-class hard-limit policies can keep exact lookups alive while shedding text, relation, or sort-heavy queries

Runtime profile switching:

- runtime switching is enabled by default in production-oriented profiles
- target transitions are `NORMAL -> STANDARD`, `WARN -> BALANCED`, `CRITICAL -> AGGRESSIVE`
- switching uses consecutive pressure samples instead of a single sample
- comparison suites disable runtime auto-switch when a profile is forced
- scenario and suite reports include switch count and timeline data
- separate profile churn reports write structured switch events for each scenario
- switch events are also written to the diagnostics stream as `RUNTIME_PROFILE_SWITCH`
