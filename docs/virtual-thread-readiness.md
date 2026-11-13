# Virtual Thread Readiness

[Türkçe](../tr/docs/sanal-thread-hazirligi.md)

## Platform Contract

CacheDB requires Java 21+ (LTS). Applications staying on Java 17 must use 0.18.x.
Maven 3.9+ is required to build the framework. Annotation processing is explicit;
preview APIs are not used.

Near-cache refresh uses a virtual thread per task, not a virtual-thread pool.
A per-runtime semaphore limits admitted refreshes to 258, duplicate identities
share one refresh, and overload is rejected without blocking the caller.
Only two refresh callbacks may perform I/O at once, preserving the previous
worker pressure; the remaining admitted virtual threads wait on a semaphore.
The executor is created lazily. Shutdown interrupts work and waits at most two
seconds; a non-cooperative user callback cannot be forcibly terminated by Java.

`spring.threads.virtual.enabled` controls Spring-managed threads. It does not
remove CacheDB's concurrency limits or replace its explicit executor policies.
CacheDB refresh uses virtual threads with either property value. Tenant, causal
and trace state must travel through explicit context wrappers, never through
inheritable thread-local state.

## Repository Initialization

Repository constructors can access Redis to verify index signatures. They therefore run
outside `ConcurrentHashMap.computeIfAbsent`, whose internal monitor can pin a Java 21
virtual thread during network I/O. The session retains one repository cache, with 64 fixed
reentrant lock stripes and at most 64 active initialization frames. Cached hits still validate
the binding and policy, then return without initialization locking, frame allocation or I/O.

Concurrent calls for the same key publish one instance. Different-key nested initialization
is supported when its stripe is available, including same-stripe reentrancy. An exact-key
cycle, contended nested miss or exhausted frame capacity fails before the nested constructor
performs I/O. Nested calls never wait while holding another initialization stripe. Failures
and errors release frames and locks; a later call can retry. No per-key lock map, new executor
or SQL read is introduced. This implicit-monitor path is separate from the explicit-monitor
baseline inventory below.

`CacheRepositoryInitializationRedisTest` covers identity, metadata validation, nesting,
capacity and failure recovery. `CacheRepositoryInitializationVirtualThreadRedisTest` exercises
actual virtual-thread constructors with bounded Redis waits and native JFR pinning events.
These targeted checks do not certify arbitrary user callbacks or every application workload.

## Monitor Audit

The baseline audit covers v0.18.1 (`3a212461fd835c783cf84d3c6b656b375df40a19`):
486 main Java files and 57 intrinsic-monitor sites in 16 files. It classified
23 sites as blocking or potentially blocking and 34 as pure memory.
The migration replaces 39 sites with reentrant locks: the 23 risk sites plus
16 pure-memory sites sharing their lock families. Eighteen independent,
pure-memory monitor sites remain.

A lock family must migrate together. Converting only a writer while leaving its
readers synchronized on another object creates a data race. Reentrant locks
remove monitor pinning; they do not make Redis/JDBC calls faster or increase
database capacity.

| Module | Main Java files | Explicit sites | PURE-MEMORY | BLOCKING |
| --- | ---: | ---: | ---: | ---: |
| cachedb-storage-redis | 47 | 27 | 14 | 13 |
| cachedb-storage-jdbc | 23 | 1 | 0 | 1 |
| cachedb-core | 251 | 10 | 7 | 3 |
| cachedb-starter | 88 | 5 | 4 | 1 |
| cachedb-spring-boot-starter | 77 | 14 | 9 | 5 |
| Total | 486 | 57 | 34 | 23 |

The inventory below records baseline source locations. `LOCK Pn` identifies an
applied lock-family conversion described below; `PURE-MEMORY / KEEP` describes
the baseline body, not a claim that every shared reader retained its old lock.
Class/member names remain the primary lookup when line numbers change.

## Source key

Paths below are repository-relative. `Fnn:Lx` in the inventory resolves to that
file and exact monitor-start line. Runtime packages start with `com.reactor.cachedb`,
despite the physical `com/cachedb` directories.

| Key | Source |
| --- | --- |
| F01 | [RedisNearCacheCoordinator.java](../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisNearCacheCoordinator.java) |
| F02 | [RedisWatchRegistry.java](../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisWatchRegistry.java) |
| F03 | [RedisEntityRepository.java](../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisEntityRepository.java) |
| F04 | [RedisProducerGuard.java](../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java) |
| F05 | [RedisProjectionRepository.java](../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProjectionRepository.java) |
| F06 | [RedisQueryIndexManager.java](../cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisQueryIndexManager.java) |
| F07 | [AbstractJdbcWriteBehindFlusher.java](../cachedb-storage-jdbc/src/main/java/com/cachedb/jdbc/AbstractJdbcWriteBehindFlusher.java) |
| F08 | [DefaultEntityRegistry.java](../cachedb-core/src/main/java/com/cachedb/core/registry/DefaultEntityRegistry.java) |
| F09 | [StoragePerformanceCollector.java](../cachedb-core/src/main/java/com/cachedb/core/queue/StoragePerformanceCollector.java) |
| F10 | [AdminConsistencyInspector.java](../cachedb-starter/src/main/java/com/cachedb/starter/AdminConsistencyInspector.java) |
| F11 | [CacheDatabase.java](../cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabase.java) |
| F12 | [CacheDatabaseAdminHttpServer.java](../cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabaseAdminHttpServer.java) |
| F13 | [CacheDatabaseSchemaAdmin.java](../cachedb-starter/src/main/java/com/cachedb/starter/CacheDatabaseSchemaAdmin.java) |
| F14 | [CacheDistributedJobExecutor.java](../cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/CacheDistributedJobExecutor.java) |
| F15 | [JacksonJsonArrayCodec.java](../cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/snapshot/JacksonJsonArrayCodec.java) |
| F16 | [SnapshotJobs.java](../cachedb-spring-boot-starter/src/main/java/com/cachedb/spring/boot/snapshot/SnapshotJobs.java) |

## Exhaustive inventory

### Redis

| ID | Class/member | Site; monitor | Classification; decision | Reason, including synchronous edges |
| --- | --- | --- | --- | --- |
| R01 | RedisNearCacheCoordinator.register | F01:L42; this | BLOCKING; LOCK P1 | L57-58 constructs/starts a platform thread: native lifecycle and inherited-thread-local `childValue` callbacks can block. `listen` itself runs on the new thread, not under this monitor. |
| R02 | RedisNearCacheCoordinator.refresh | F01:L74; this | BLOCKING; LOCK P1 | L84 `ThreadPoolExecutor.execute` reaches queue `offer`/executor locks and thread creation; those may wait. AbortPolicy does not run `task` inline. |
| R03 | RedisNearCacheCoordinator.close | F01:L149; this | PURE-MEMORY; KEEP | Captures executor, sets closed/connected only. Clear, shutdownNow, awaitTermination(2 s), unsubscribe and interrupt are L153-162, outside the monitor. |
| R04 | RedisNearCacheCoordinator.Store.lookup | F01:L184; segment | PURE-MEMORY; KEEP | String-key lookup, expiry removal, bounded frequency counters and version comparison only. |
| R05 | RedisNearCacheCoordinator.Store.getDecoded | F01:L197; segment | PURE-MEMORY; KEEP | Compares payload strings, expiry/counters; returns decoded reference without invoking a codec. |
| R06 | RedisNearCacheCoordinator.Store.put (version overload) | F01:L220; segment | PURE-MEMORY; KEEP | Epoch/version checks, bounded eviction, frequency maintenance and map insertion; no decoded-object callbacks. |
| R07 | RedisNearCacheCoordinator.Store.put (post-check) | F01:L235; segment | PURE-MEMORY; KEEP | Epoch mismatch removes a String-keyed entry only. Legacy epoch-based fills retain their global invalidation guard; per-ID stamps are documented in [point-read configuration](redis-point-read-configuration.md). |
| R08 | RedisNearCacheCoordinator.Store.invalidate | F01:L239; segment | PURE-MEMORY; KEEP | Epoch increment precedes the block; the block only removes an entry and adjusts bytes. |
| R09 | RedisNearCacheCoordinator.Store.clear | F01:L243; each segment | PURE-MEMORY; KEEP | Clears bounded entries/sketch and resets counters, one segment at a time. |
| R10 | RedisWatchRegistry.register | F02:L30; registry this | BLOCKING; LOCK P2 | `new Watch` L35 initializes `UUID.randomUUID` at L74; SecureRandom/provider initialization/entropy can block. Listener is stored, not invoked. Conditional on provider/initialization. |
| R11 | RedisWatchRegistry.Dispatcher.submit | F02:L90; dispatcher this | BLOCKING; LOCK P2 | `ArrayBlockingQueue.offer` L93 acquires a ReentrantLock, despite not waiting for capacity. Thread construction/start L95 is also lifecycle work. Callback is not run inline. |
| R12 | RedisWatchRegistry.Dispatcher.run, finally | F02:L105; dispatcher this | PURE-MEMORY; KEEP | Only decrements queuedBytes. `queue.take`, `task.action.run` and error logging are L102-104 outside it. Migrate with R11's lock family. |
| R13 | RedisEntityRepository.VerifiedOwners.get | F03:L115; owners this | BLOCKING; LOCK P3 | `String.valueOf(Object)` invokes an arbitrary ID's `toString` inside the monitor; built-in String IDs are memory-only, public generic IDs are not proven so. |
| R14 | RedisEntityRepository.VerifiedOwners.put | F03:L116; owners this | BLOCKING; LOCK P3 | Same extensible conversion at L117; subsequent LRU/byte accounting is bounded memory work (16384 entries/4 MiB). |
| R15 | RedisEntityRepository.registerWatcher | F03:L139; repository this | BLOCKING; LOCK P3 | `metadata.tenantFieldName`, `activeTenant -> TenantAccess.active`, nested R16 and R10. Metadata is a public SPI; no loader/Redis call is directly made here. |
| R16 | RedisEntityRepository.registerWatcher, inner | F03:L143; shared watchState | BLOCKING; LOCK P3 | `tenantQuery -> TenantAccess.scoped` invokes metadata methods; registry.register reaches UUID creation. This guard is shared across repository views; preserve that identity. |
| R17 | RedisProducerGuard.runtimeProfileEvents | F04:L223; this | PURE-MEMORY; KEEP | Copies at most 64 immutable event records. |
| R18 | RedisProducerGuard.manualRuntimeProfileOverride | F04:L227; this | PURE-MEMORY; KEEP | AtomicReference.get only. |
| R19 | RedisProducerGuard.setManualRuntimeProfile | F04:L231; this | BLOCKING; LOCK P4 | `applyRuntimeProfileOverride` L448 -> `recordRuntimeProfileEvent` L548 -> Redis XADD L570 on a switch with diagnostics enabled. |
| R20 | RedisProducerGuard.clearManualRuntimeProfileOverride | F04:L237; this | BLOCKING; LOCK P4 | Calls R22 reentrantly; a resulting profile switch can publish diagnostics through XADD. |
| R21 | RedisProducerGuard.activeRuntimeProfileProperties | F04:L244; this | PURE-MEMORY; KEEP | `sampleSafely()` L243 performs Redis I/O BEFORE monitor acquisition. Block builds strings from config/snapshot only. Do not misclassify by enclosing method. |
| R22 | RedisProducerGuard.updateRuntimeProfile | F04:L377; this | BLOCKING; LOCK P4 | Both normal transition L434 and manual override L393 reach the same diagnostics XADD; sampling outside the lock does not remove this transitive I/O. |
| R23 | RedisProjectionRepository.ProjectionQueryCache.get | F05:L693; query-cache this | PURE-MEMORY; KEEP | Private String-keyed map/expiry pruning. Dormant read path: hotQueryCacheKey L291 always returns null. |
| R24 | RedisProjectionRepository.ProjectionQueryCache.put | F05:L699; query-cache this | PURE-MEMORY; KEEP | Private list copy/LRU (512 entries), no element codec/hash invocation. Currently uncalled: cacheHotQuery L279 is a no-op. Re-audit list implementations before re-enabling. |
| R25 | RedisProjectionRepository.ProjectionQueryCache.clear | F05:L711; query-cache this | PURE-MEMORY; KEEP | Memory-only invalidator attached at L85-86; no warmup loader is invoked. |
| R26 | RedisQueryIndexManager.checkCompositeSignature | F06:L64; this | BLOCKING; LOCK P5 | GET L68, optional EVAL L74, metadata SPI and warning logger L88 inside the monitor. Constructor and five-second refresh paths reach it. |
| R27 | RedisQueryIndexManager.rebuildFromEntityStore | F06:L232; this | BLOCKING; LOCK P5 | Redis rebuild-slot/degraded checks, SCAN/EXISTS/GET/SET/DEL L235-281, codec.fromRedisValue L272 and reindexEntity -> pipeline.sync. 100000 scan-iteration cap does not make I/O memory-only. |

### JDBC and core

| ID | Class/member | Site; monitor | Classification; decision | Reason, including synchronous edges |
| --- | --- | --- | --- | --- |
| J01 | AbstractJdbcWriteBehindFlusher.compactIndependentWrites | F07:L207; reorderLoggedEntities | BLOCKING; LOCK P6 | System.Logger INFO L212-214 may synchronously write a console/file/network sink. The surrounding JDBC transaction is not itself inside this block. |
| C01 | DefaultEntityRegistry.register (5 args) | F08:L34; this | BLOCKING; LOCK P7 | EntityRegistry.super.register delegates to the seven-argument override C02 while retaining the outer monitor. Loader objects are passed, not executed. |
| C02 | DefaultEntityRegistry.register (7 args) | F08:L45; this | BLOCKING; LOCK P7 | Metadata entityName/compressionThresholdBytes and PayloadEntityCodec.wrap -> constructor schemaVersion/payloadUpcaster/upcaster.targetVersion are extensible synchronous calls. Generated implementations are normally memory-only; SPI does not enforce it. |
| C03 | DefaultEntityRegistry.registerProjection | F08:L99; this | BLOCKING; LOCK P7 | Calls metadata.entityName and projection.name. Both are user implementations, not necessarily simple getters. Projection business mapper is not called. |
| C04 | DefaultEntityRegistry.find | F08:L153; this | PURE-MEMORY; KEEP | String-keyed map/Optional only; no metadata/loader callback. |
| C05 | DefaultEntityRegistry.findProjection | F08:L158; this | PURE-MEMORY; KEEP | Two String-keyed lookups; no projection invocation. |
| C06 | DefaultEntityRegistry.projections | F08:L167; this | PURE-MEMORY; KEEP | Copies internal binding references; no binding callback. Per-entity projection cardinality is not explicitly capped: separate memory/latency review required. |
| C07 | DefaultEntityRegistry.all | F08:L176; this | PURE-MEMORY; KEEP | Copies entity binding references; registered entity limit enforced in register. |
| C08 | StoragePerformanceCollector.LatencyAccumulator.record | F09:L214; samples | PURE-MEMORY; KEEP | Bounded ArrayDeque<Long> insert/prune, SAMPLE_LIMIT=256; atomic metrics updated outside. |
| C09 | StoragePerformanceCollector.LatencyAccumulator.snapshot | F09:L228; samples | PURE-MEMORY; KEEP | Copies <=256 Long samples; sorting/percentiles occur after monitor exit. |
| C10 | StoragePerformanceCollector.LatencyAccumulator.clear | F09:L249; samples | PURE-MEMORY; KEEP | Clears the bounded deque only. |

### Starter and Spring Boot

| ID | Class/member | Site; monitor | Classification; decision | Reason, including synchronous edges |
| --- | --- | --- | --- | --- |
| S01 | AdminConsistencyInspector.scanKeyCountIncrementally | F10:L139; progress | BLOCKING; LOCK P8 | jedis.scan L142 inside bounded step loop; cursor/count/completion must remain serialized per progress object. |
| S02 | AdminConsistencyInspector.IncrementalKeyScan.completedCount | F10:L173; progress this | PURE-MEMORY; KEEP | Returns long only; same object as S01, so migrate the lock together. |
| S03 | CacheDatabase.onDurableEntityChange | F11:L98; this | PURE-MEMORY; KEEP | Validates capacity <=64 and CopyOnWriteArrayList.add; returns removal closure, does not execute it. publishDurableChange L106 invokes listeners elsewhere, outside this monitor. Listener implementations can perform I/O and need separate review, not a false claim that all listeners are pure. |
| S04 | CacheDatabaseAdminHttpServer.ensureBackgroundExecutor | F12:L1127; this | PURE-MEMORY; KEEP | boundedExecutor L1356 only constructs executor/queue/AbortPolicy. No prestart, submit or thread-factory call here; submit L1108 occurs after return. |
| S05 | CacheDatabaseSchemaAdmin.migrationHistory | F13:L231; migrationHistory | PURE-MEMORY; KEEP | Copies owned synchronized ArrayList; SQL L192-210 is outside. History has no retention cap: KEEP is not a scalability approval of its unbounded copy. |
| B01 | CacheDistributedJobExecutor.localExecutor | F14:L171; this | PURE-MEMORY; KEEP | Lazy executor/queue construction, no task submission/start under this monitor. |
| B02 | CacheDistributedJobExecutor.heartbeatExecutor | F14:L181; this | PURE-MEMORY; KEEP | Constructs scheduled executor, no scheduling under the monitor. |
| B03 | CacheDistributedJobExecutor.close, capture | F14:L309; this | PURE-MEMORY; KEEP | Captures executor references; shutdown calls L313-315 are after exit. |
| B04 | JacksonJsonArrayCodec.prepared | F15:L28; codec this | BLOCKING; LOCK P9 | mapper.readerForListOf L30 can eagerly resolve/contextualize custom deserializers and invoke user code; not guaranteed memory-only. Actual decodeArray/readValue and OutputStream encoder writes are elsewhere. |
| B05 | SnapshotJobs.withDatabase | F16:L79; jobs this | PURE-MEMORY; KEEP | Plan/record access, actual CacheDatabase-owned DefaultEntityRegistry.find and S03 registration only. The lambda L90-114 is stored, not run. Its codec.fromColumns/rootIdExtractor callbacks may block when later invoked; do not label those implementations pure. |
| B06 | SnapshotJobs.heartbeat | F16:L262; jobs this | PURE-MEMORY; KEEP | Lazy scheduler construction only; heartbeat scheduling/eval at L408-426 runs after return. |
| B07 | SnapshotJobs.refresh, admission | F16:L347; job | PURE-MEMORY; KEEP | Checks closed, CAS busy, captures current worker. Redis lease SET starts at L362, after exit. |
| B08 | SnapshotJobs.refresh, finally | F16:L388; job | PURE-MEMORY; KEEP | Clears worker/busy only; cleanup Redis calls L381-385 precede it. |
| B09 | SnapshotJobs.execute, renewal lambda | F16:L430; guard | BLOCKING; LOCK P10 | RENEW eval L418 is outside, but worker.interrupt L433 can synchronously call an interruptible-channel blocker/close and acquire JDK internal locks. Conditional on worker state; not just an atomic flag. |
| B10 | SnapshotJobs.execute, finally | F16:L531; guard | PURE-MEMORY; KEEP | Sets active=false only. renewal.cancel(false) L534 is outside; migrate together with B09 to retain the no-late-interrupt handshake. |
| B11 | SnapshotJobs.start | F16:L749; jobs this | BLOCKING; LOCK P10 | scheduleWithFixedDelay L765/L780 acquires scheduled-executor queue locks and starts threads. refresh/dirtyTick callbacks run on workers, not inline. |
| B12 | SnapshotJobs.close | F16:L800; jobs this | BLOCKING; LOCK P10 | Inner B13 interrupts, future.cancel(true), scheduler/heartbeat.shutdownNow L809-811 acquire executor/queue locks and interrupt workers. Private changeRegistration is the S03 removal closure, not an arbitrary injected close implementation. Error logging L804 is another conditional sink. |
| B13 | SnapshotJobs.close, worker | F16:L806; job (outer jobs this held) | BLOCKING; LOCK P10 | job.worker.interrupt L807 can synchronously enter blocker cleanup; replacing only the outer jobs monitor leaves this risk. |
| B14 | SnapshotJobs.Job.codec | F16:L830; job this | PURE-MEMORY; KEEP | SnapshotPlan.jsonCodec L100 checks the final JsonSnapshotProjection record and returns its codec field; fallback JacksonJsonArrayCodec constructor only assigns validated references. It does not call prepared/deserialization under this monitor. |


## Lock Families

| Family | Change and preserved contract |
| --- | --- |
| P1 | Near-cache lifecycle lock; preserve lazy initialization, budget, deduplication and close races |
| P2 | Watcher registry/dispatcher locks; preserve queue, byte and watcher limits; callbacks remain outside locks |
| P3 | Verified-owner and watcher registration locks; preserve tenant checks and shared repository-view state |
| P4 | Runtime-profile lock; preserve reentrancy, transition order and diagnostic publication |
| P5 | Query-index lifecycle lock; preserve schema signatures, degraded-state transitions and rebuild serialization |
| P6 | JDBC regrouping log lock; preserve bounded log-once bookkeeping and write ordering |
| P7 | Entity-registry lock for all shared reads/writes; preserve duplicate registration and loader selection |
| P8 | Per-scan progress lock; preserve bounded cursor advancement and completed counts |
| P9 | Lazy Jackson reader lock; preserve mapper semantics and repeated initialization checks |
| P10 | Snapshot lifecycle, job and renewal locks; preserve lease ownership, cancellation and worker handoff |

Metadata, codecs, IDs, log sinks and user callbacks can execute arbitrary code.
A getter-like method name is not proof that a call cannot block. User callbacks
must still obey their own timeout and thread-safety contracts.

## JFR Verification

Use the opt-in profile to record `jdk.VirtualThreadPinned` with stack traces and
a 20 ms threshold. Every module/fork writes its own bounded, completed recording.

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

The control creates a real monitor-pinning recording that must fail and a
reentrant-lock recording that must pass. The reader rejects corrupt, changed,
oversized, truncated or data-loss evidence. Exit 0 means no matching CacheDB
frame was observed in the supplied recordings, not that all scenarios ran.
Required-suite and skip checks establish coverage separately.

The reference focused run used HotSpot/Temurin 21.0.7: the positive control
contained one pinning event; the negative control and three repository/snapshot
recordings contained none. Provider, Cluster, async and application-specific
workloads require their own complete verification. A different JVM/vendor must
be capability-qualified; missing instrumentation is not evidence of safety.

## Runtime Tuning

Virtual threads do not eliminate CPU, memory, socket or connection-pool limits.
Keep bounded admission, Redis/JDBC deadlines and backpressure. Do not execute
blocking work inside an intrinsic monitor or use caller-runs rejection policies
that unexpectedly block request threads.

### Redis URI Shortcuts

`CacheDatabaseBootstrap.connect(uri, dataSource)` and `.redis(uri)` own their URI-created clients.
Their pools use fair waiting-thread admission while retaining Jedis 5.2 URI defaults: `maxTotal=8`,
`maxIdle=8`, `minIdle=0`, LIFO idle reuse, `maxWait=-1` (no borrow deadline), connection/read timeout 2000 ms
and blocking socket timeout 0 (infinite). This is compatibility behavior, **not a production bounded
execution contract**. Fairness does not add a deadline or substitute CacheDB's configured pool defaults.
See the [SDK URI path](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/UnifiedJedis.java)
and [plain pool defaults](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.12.0/src/main/java/org/apache/commons/pool2/impl/BaseObjectPoolConfig.java).

TLS, credentials, database and query protocol retain the SDK URI semantics. An omitted URI port
remains -1; it is **not** silently replaced with 6379. Supply an explicit port.
A background-URI construction failure closes an already-created owned foreground client.

For production, select finite pool/connect/read/blocking timeouts explicitly through
`RedisConnectionConfig`, size the pool for the workload, and own the clients outside Bootstrap.
The following values are an explicit example, not universal sizing defaults:

```java
var connections = RedisConnectionConfig.builder().uri("redis://redis-service:6379")
        .poolMaxTotal(32).poolMaxIdle(32).poolMinIdle(0)
        .poolMaxWaitMillis(2000).connectionTimeoutMillis(2000)
        .readTimeoutMillis(2000).blockingReadTimeoutMillis(5000).build();
try (var redis = connections.createClient();
        var database = CacheDatabaseBootstrap.using(redis, dataSource).config(baseConfig).build()) {
    database.start();
    // Application work.
}
```

Bootstrap borrows the supplied client without changing its pool; it does not close that client.
Close the database before closing externally owned clients, including on startup failure.

On HotSpot Java 21, `-XX:+UseZGC -XX:+ZGenerational` is a tuning candidate for large L1 heaps, not a
measured CacheDB performance claim. Compare it with the same workload and memory
limits before changing production flags. Do not copy these collector flags into
an OpenJ9 deployment; use its own [GC policy guidance](https://eclipse.dev/openj9/docs/gc/)
and qualify instrumentation on the exact JVM build. HotSpot results are not
OpenJ9 performance or pinning evidence. See [performance methodology](performance-benchmarks.md).
