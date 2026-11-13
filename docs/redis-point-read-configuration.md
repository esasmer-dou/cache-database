# Redis Point-Read Coalescing

Each CacheDatabase owns one RedisReadRuntime for its foreground client. DefaultCacheSession, repositories created with different policies and fetch-plan copies share that runtime and its bounded admission state. Repositories do not create or close their own transport. Legacy standalone repository/session constructors retain direct reads unless a lifecycle owner explicitly injects shared read state.

Only synchronous findById and findHotById transport is redirected here. Bulk/query/refresh behavior is unchanged. Entity decoding, tenant checks, hot-policy admission, source fallback and fetch-plan expansion stay on the caller after transport completion; neither application entity objects nor caller ThreadLocal state are shared through the raw coalescer.

## Configuration

Spring binds `cachedb.redis.read-coalescing.*` into a separate `CacheRuntimeOptions.redisReadCoalescing()`
bean. CacheRuntimeOptions is an immutable ordinary final class, not a record. Its builder/toBuilder()
preserve the selected options; ordered CacheRuntimeOptionsCustomizer beans run after runtime property
mapping. Existing CacheDatabaseConfigCustomizer beans remain base-config-only.

CacheDatabaseConfig keeps its original 21 record components, canonical constructors, Java 21
record-pattern arity, and equality shape. Existing CacheDatabase constructors use default runtime
options. Add an options argument to select runtime behavior without changing the base config:

```java
var options = CacheRuntimeOptions.builder()
        .redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(false).build())
        .build();
var database = new CacheDatabase(redisOperations, dataSource, baseConfig, options);
// Or configure the bootstrap without changing the base-config customizer:
var bootstrap = CacheDatabase.bootstrap(redisOperations, dataSource)
        .config(baseConfig).runtimeOptions(options)
        .customizeRuntime(builder -> builder.redisReadCoalescing(options.redisReadCoalescing()));
```

`database.runtimeOptions()` exposes the immutable selected instance; `database.config()` still
returns the stable base record. Bootstrap resolves runtime customizers before opening connections.
The runtime options are independent of development/production/base profile changes.

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

Coalescing defaults to false to preserve low-load latency. Enable it explicitly only after measuring a benefit under your concurrent workload. Disabled synchronous repository reads keep the existing direct MGET/causal-read path: no coalescer future, queue, EVAL transport or executor hop is introduced. Existing direct-client limits and timeouts still apply.

The example reduces the runtime-wide raw reply-content budget to 64 MiB; maxLiveReplyBytes defaults to 256 MiB and cannot exceed that hard limit. It must be at least maxReplyBytes, including when enabled=false. Other values use the transport defaults. maxBatchKeys counts at most 512 entity identities, each with an entity/tombstone pair. Queue count is limited to 4096; queued-key and per-batch reply budgets are each at most 16 MiB. maxInFlightBatches=0 derives max(2, physicalPoolCapacity / 2); a derived or explicit value must fit RedisReadCoalescingConfig.HARD_IN_FLIGHT. timeoutMillis is 1..300000 and maxAsyncDirect is 0..1024. Invalid settings fail configuration validation.

The same 64 MiB budget can be bound from YAML:

```yaml
cachedb:
  redis:
    read-coalescing:
      enabled: true
      max-reply-bytes: 16777216
      max-live-reply-bytes: 67108864
```

## Capacity And Memory

RedisClientOperations.physicalPoolCapacity() defaults to a conservative two for unknown custom clients. JedisPooledOperations reads the actual foreground pool's maxTotal; JedisClusterOperations returns the minimum capacity across node pools, never their sum. An empty cluster pool map uses two until a new runtime is created from discovered pools. A known Jedis pool with maxTotal <= 0 is rejected, not silently treated as bounded. Custom adapters remain responsible for finite pool/socket limits. Capacity discovery is memory-only and does not borrow a connection. The admission budget is fixed when the runtime is created; recreate the runtime after intentional pool resizing.

maxLiveReplyBytes is a separate runtime-wide admission budget for reserved plus retained raw reply content, not a JVM heap cap. A batch reserves maxReplyBytes before physical I/O; capacity remains charged while the transport/delivery retains raw content. With the YAML above, at most four full 16 MiB reservations fit even when maxInFlightBatches is larger. Queue/time limits still apply while waiting for memory capacity. Decoding, Jedis/RESP framing, String/object overhead, caller-owned values and application object graphs require additional pod memory. Tune both budgets and physical concurrency for pod memory and Redis CPU; do not merely increase pool sizes. The synchronous disabled/causal/unit-of-work direct paths retain their existing client behavior and do not claim coalescer admission limits. Physical pools remain authoritative even when a derived concurrency minimum of two exceeds a one-connection pool.

## Bypass And Correctness

The integration checks eligibility on the original caller thread after an L1 miss and before the backing data read. A positive causal minimum version or CacheUnitOfWork.active() bypasses the coalescer. Causal reads retain their version fetch/verification, including rejection of unavailable or older payloads. Unit-of-work reads are not mixed into another caller's transport batch.

L1 hits need no transport admission. Enabled point reads request Redis PTTL together with payload only when an L1 fill was eligible before the read. The TTL origin is RedisPointRead.observedAtNanos(); elapsed network, delivery, decoding and policy work is subtracted before local admission. An unrequested/expired TTL cannot be cached. Persistent/missing Redis TTL semantics are retained.

Known-ID point reads, refreshes, and each bounded bulk group capture a Store.Stamp before
backing payload I/O. Query/window reads keep the pre-I/O global Store.epoch() guard because their
result IDs are not yet known. Never capture a fill guard after receiving an old reply. Legacy
epoch-based Store.put methods retain their original global invalidation semantics.

## Metrics And Lifecycle

CacheDatabase.redisReadRuntime() and RedisEntityRepository.redisReadRuntime() expose the same borrowed transport for diagnostics/integration. Do not close it through a repository. CacheDatabase.redisReadMetrics() combines its transport snapshot with separate disabled, causal and unitOfWork bypass counters; bypassed() sums these counters and transport-overflow bypasses. The reason counters are mutually exclusive, prioritized disabled, then causal, then unit-of-work. Raw runtime.metrics() intentionally cannot observe policy decisions made before entering the transport.

Closing CacheDatabase first forecloses shared point-read admission, including the disabled and causal/unit-of-work legacy routes, then shuts down other resources. Queued reads are rejected by the transport. Running physical I/O is not declared complete early; finite pool/socket deadlines remain mandatory. Repositories and fetch-plan copies reject new point reads after their owner closes. A supplied Redis client remains borrowed; a client created by CacheDatabaseBootstrap is closed through bootstrap ownership.

Qualification includes RedisReadWiringRedisTest, RedisRepositoryReadStateTest, RedisReadCoalescingIntegrationConfigTest, RedisReadCoalescingBindingTest and RedisReadPhysicalCapacityClusterTest. Redis-backed tests require cachedb.snapshot.redis=true; cluster capacity tests require cachedb.redis.cluster=true. Tests use existing disposable endpoints and do not provision Redis.

The [recorded candidate matrix](performance-benchmarks.md#0190-candidate-evidence) demonstrates opt-in
high-concurrency benefit but fails the one-worker <=3% p50 criterion. The product default is false;
auto-enabling merging is not qualified. Final-artifact, workload and deployment qualification remain
separate from those candidate numbers and from functional tests.
