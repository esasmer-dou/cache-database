# Tuning Parameters

This document collects the runtime knobs that can now be changed without editing code.

There are two layers:

- connection/bootstrap tuning for Redis and PostgreSQL clients
- core `CacheDatabaseConfig` overrides for write-behind, guardrails, admin, indexing, schema bootstrap, and cache behavior

If a property is not set, the system falls back to the default shown below.

## Prefix Model

Global core overrides:

- `cachedb.config.*`

Scoped core overrides:

- `cachedb.demo.config.*`
- `cachedb.admin.config.*`

Scoped connection tuning:

- `cachedb.demo.redis.*`
- `cachedb.demo.postgres.*`
- `cachedb.admin.redis.*`
- `cachedb.admin.postgres.*`

Examples:

```powershell
-Dcachedb.config.writeBehind.workerThreads=8
-Dcachedb.config.redisGuardrail.usedMemoryWarnBytes=2147483648
-Dcachedb.demo.redis.pool.maxTotal=96
-Dcachedb.demo.postgres.connectTimeoutSeconds=15
```

## Redis Client Tuning

| Property | Default | What it does |
| --- | --- | --- |
| `<scope>.redis.uri` | runtime-specific URI | Redis target URI. |
| `<scope>.redis.pool.maxTotal` | `64` | Max pooled Redis connections. |
| `<scope>.redis.pool.maxIdle` | `16` | Max idle Redis connections kept hot. |
| `<scope>.redis.pool.minIdle` | `4` | Min idle Redis connections kept ready. |
| `<scope>.redis.pool.maxWaitMillis` | `5000` | How long pool callers wait before failing when exhausted. |
| `<scope>.redis.pool.blockWhenExhausted` | `true` | Whether callers wait instead of failing immediately when pool is full. |
| `<scope>.redis.pool.testOnBorrow` | `false` | Validates Redis connections when borrowed. |
| `<scope>.redis.pool.testWhileIdle` | `false` | Validates idle connections in eviction runs. Keep disabled by default to avoid background `PING` overhead and timeout noise. |
| `<scope>.redis.pool.timeBetweenEvictionRunsMillis` | `30000` | Idle connection maintenance cadence. |
| `<scope>.redis.pool.minEvictableIdleTimeMillis` | `60000` | How long an idle Redis connection can sit before eviction. |
| `<scope>.redis.pool.numTestsPerEvictionRun` | `3` | Number of idle connections checked per maintenance cycle. |
| `<scope>.redis.pool.connectionTimeoutMillis` | `2000` | Redis connect timeout for new pooled sockets. |
| `<scope>.redis.pool.readTimeoutMillis` | `5000` | Redis read timeout for regular commands. |
| `<scope>.redis.pool.blockingReadTimeoutMillis` | `15000` | Redis read timeout for blocking commands such as stream reads. Keep this above worker block timeouts to avoid false `Read timed out` failures. |

## Spring Boot Starter Redis Topology

These properties apply specifically to `cachedb-spring-boot-starter`.

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.profile` | `default` | Starter profile shortcut. Supports `default`, `development`, `production`, `benchmark`, `memory-constrained`, and `minimal-overhead`. |
| `cachedb.redis.uri` | `redis://127.0.0.1:6379` | Foreground repository Redis URI for the starter. |
| `cachedb.redis-uri` | `redis://127.0.0.1:6379` | Legacy alias for `cachedb.redis.uri`. |
| `cachedb.redis.pool.maxTotal` | `64` | Foreground repository pool max size. |
| `cachedb.redis.pool.maxIdle` | `16` | Foreground repository pool max idle size. |
| `cachedb.redis.pool.minIdle` | `4` | Foreground repository pool min idle size. |
| `cachedb.redis.pool.maxWaitMillis` | `5000` | Foreground repository pool max wait. |
| `cachedb.redis.pool.blockWhenExhausted` | `true` | Whether foreground callers wait when the pool is full. |
| `cachedb.redis.pool.testOnBorrow` | `false` | Whether foreground connections are validated on borrow. |
| `cachedb.redis.pool.testWhileIdle` | `false` | Whether foreground idle connections are validated in maintenance runs. Default is off to keep repository reads free from idle-validation `PING` noise. |
| `cachedb.redis.pool.timeBetweenEvictionRunsMillis` | `30000` | Foreground idle-eviction cadence. |
| `cachedb.redis.pool.minEvictableIdleTimeMillis` | `60000` | Foreground idle-eviction threshold. |
| `cachedb.redis.pool.numTestsPerEvictionRun` | `3` | Foreground idle connections checked per maintenance run. |
| `cachedb.redis.pool.connectionTimeoutMillis` | `2000` | Foreground Redis connect timeout. |
| `cachedb.redis.pool.readTimeoutMillis` | `5000` | Foreground Redis read timeout for non-blocking commands. |
| `cachedb.redis.pool.blockingReadTimeoutMillis` | `15000` | Foreground Redis read timeout for blocking commands. |
| `cachedb.redis.background.enabled` | `true` | Turns the background worker/admin pool on or off. |
| `cachedb.redis.background.uri` | falls back to `cachedb.redis.uri` | Background worker/admin Redis URI. |
| `cachedb.redis.background.pool.maxTotal` | `24` | Background worker/admin pool max size. |
| `cachedb.redis.background.pool.maxIdle` | `8` | Background worker/admin pool max idle size. |
| `cachedb.redis.background.pool.minIdle` | `2` | Background worker/admin pool min idle size. |
| `cachedb.redis.background.pool.maxWaitMillis` | `5000` | Background pool max wait. |
| `cachedb.redis.background.pool.blockWhenExhausted` | `true` | Whether background callers wait when the pool is full. |
| `cachedb.redis.background.pool.testOnBorrow` | `false` | Whether background connections are validated on borrow. |
| `cachedb.redis.background.pool.testWhileIdle` | `false` | Whether background idle connections are validated in maintenance runs. Default is off because worker/admin pools tolerate reconnects better than periodic validation overhead. |
| `cachedb.redis.background.pool.timeBetweenEvictionRunsMillis` | `30000` | Background idle-eviction cadence. |
| `cachedb.redis.background.pool.minEvictableIdleTimeMillis` | `60000` | Background idle-eviction threshold. |
| `cachedb.redis.background.pool.numTestsPerEvictionRun` | `3` | Background idle connections checked per maintenance run. |
| `cachedb.redis.background.pool.connectionTimeoutMillis` | `2000` | Background Redis connect timeout. |
| `cachedb.redis.background.pool.readTimeoutMillis` | `10000` | Background Redis read timeout for normal commands. |
| `cachedb.redis.background.pool.blockingReadTimeoutMillis` | `30000` | Background Redis read timeout for blocking stream operations and recovery loops. |

## Runtime Coordination

These properties control multi-pod worker identity and singleton operational loops.

### Core Overrides

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.runtimeCoordination.instanceId` | empty | Explicit runtime instance id. If empty, CacheDB resolves it from environment and falls back to a UUID. |
| `cachedb.config.runtimeCoordination.appendInstanceIdToConsumerNames` | `true` | Appends the resolved instance id to worker consumer name prefixes. Keep this on for shared consumer groups in Kubernetes. |
| `cachedb.config.runtimeCoordination.leaderLeaseEnabled` | `true` | Enables Redis leader leasing for cleanup/report/history-style singleton loops. |
| `cachedb.config.runtimeCoordination.leaderLeaseSegment` | `coordination:leader` | Redis key segment used under the main key prefix for leader lease keys. |
| `cachedb.config.runtimeCoordination.leaderLeaseTtlMillis` | `15000` | Redis lease TTL for singleton operational loops. |
| `cachedb.config.runtimeCoordination.leaderLeaseRenewIntervalMillis` | `5000` | How often the leader renews its Redis lease while it stays active. |

### Spring Boot Starter Shortcuts

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.runtime.instance-id` | empty | Starter-friendly alias for the runtime instance id. |
| `cachedb.runtime.append-instance-id-to-consumer-names` | `true` | Starter-friendly flag for pod-unique consumer names. |
| `cachedb.runtime.leader-lease-enabled` | `true` | Starter-friendly flag for Redis leader leasing on singleton ops loops. |
| `cachedb.runtime.leader-lease-segment` | `coordination:leader` | Starter-friendly Redis key segment for leader leases. |
| `cachedb.runtime.leader-lease-ttl-millis` | `15000` | Starter-friendly leader lease TTL. |
| `cachedb.runtime.leader-lease-renew-interval-millis` | `5000` | Starter-friendly leader lease renew interval. |

Operational notes:

- shared consumer groups stay shared across pods; only consumer names become pod-unique
- the automatic instance id resolution order is `cachedb.runtime.instance-id`, `CACHE_DB_INSTANCE_ID`, `HOSTNAME`, `POD_NAME`, `COMPUTERNAME`, then a generated UUID
- leader leasing currently covers cleanup/report/history-style loops, not the main consumer-group workers
- one Redis still remains the coordination-plane dependency; use durable/HA Redis for production
- size worker threads for the whole cluster, not per pod in isolation
- for same-host local smoke, prefer explicit `cachedb.runtime.instance-id` values or the bundled `tools/ops/cluster/run-multi-instance-coordination-smoke.ps1` runner because `HOSTNAME` is usually shared across local processes

## PostgreSQL Client Tuning

| Property | Default | What it does |
| --- | --- | --- |
| `<scope>.postgres.jdbcUrl` | runtime-specific JDBC URL | PostgreSQL target JDBC URL. |
| `<scope>.postgres.user` | runtime-specific user | Database username. |
| `<scope>.postgres.password` | runtime-specific password | Database password. |
| `<scope>.postgres.connectTimeoutSeconds` | `30` | PostgreSQL connect timeout. |
| `<scope>.postgres.socketTimeoutSeconds` | `300` | PostgreSQL socket read timeout. |
| `<scope>.postgres.tcpKeepAlive` | `true` | Enables TCP keepalive on PostgreSQL connections. |
| `<scope>.postgres.rewriteBatchedInserts` | `true` | Lets PostgreSQL JDBC rewrite batched inserts into faster multi-value statements. |
| `<scope>.postgres.prepareThreshold` | `5` | Driver threshold before switching to server-prepared statements. |
| `<scope>.postgres.defaultRowFetchSize` | `0` | Default row fetch size. `0` keeps driver default behavior. |
| `<scope>.postgres.applicationName` | `cache-database` | Application name attached to PostgreSQL sessions. |
| `<scope>.postgres.additionalParameters` | empty | Extra JDBC query parameters as `key=value;key=value`. |

## Core Runtime Tuning

### Write-Behind

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.writeBehind.enabled` | `true` | Enables the write-behind pipeline. |
| `cachedb.config.writeBehind.workerThreads` | `max(1, cpu/2)` | Number of write-behind workers. |
| `cachedb.config.writeBehind.batchSize` | `128` | Base read batch size from the stream. Raised modestly to improve sustained drain rate under concurrent write traffic. |
| `cachedb.config.writeBehind.dedicatedWriteConsumerGroupEnabled` | `true` | Uses dedicated compaction consumer groups instead of the base stream. |
| `cachedb.config.writeBehind.durableCompactionEnabled` | `true` | Keeps durable Redis-side compaction state. |
| `cachedb.config.writeBehind.batchFlushEnabled` | `true` | Enables grouped flush batches. |
| `cachedb.config.writeBehind.tableAwareBatchingEnabled` | `true` | Groups flushes by table/entity type. |
| `cachedb.config.writeBehind.flushGroupParallelism` | `4` | Parallel flush groups per worker cycle. Higher default increases PostgreSQL flush overlap without changing write semantics. |
| `cachedb.config.writeBehind.flushPipelineDepth` | `4` | In-flight flush wave depth. Increased so grouped flush waves can stay busy under backlog. |
| `cachedb.config.writeBehind.coalescingEnabled` | `true` | Collapses superseded writes before flush. |
| `cachedb.config.writeBehind.maxFlushBatchSize` | `128` | Max rows per flush batch. |
| `cachedb.config.writeBehind.batchStaleCheckEnabled` | `true` | Drops stale batched writes before PostgreSQL flush. |
| `cachedb.config.writeBehind.adaptiveBacklogHighWatermark` | `250` | Backlog level that triggers the high adaptive batch profile. |
| `cachedb.config.writeBehind.adaptiveBacklogCriticalWatermark` | `750` | Backlog level that triggers the critical adaptive batch profile. Lowered so the worker scales up sooner under sustained write pressure. |
| `cachedb.config.writeBehind.adaptiveHighFlushBatchSize` | `256` | Flush batch size under high backlog. |
| `cachedb.config.writeBehind.adaptiveCriticalFlushBatchSize` | `512` | Flush batch size under critical backlog. |
| `cachedb.config.writeBehind.postgresMultiRowFlushEnabled` | `true` | Enables multi-row PostgreSQL upsert/delete statements. |
| `cachedb.config.writeBehind.postgresMultiRowStatementRowLimit` | `64` | Row cap per generated multi-row PostgreSQL statement. |
| `cachedb.config.writeBehind.postgresCopyBulkLoadEnabled` | `true` | Enables PostgreSQL `COPY`-based bulk path. |
| `cachedb.config.writeBehind.postgresCopyThreshold` | `128` | Minimum rows before switching to PostgreSQL `COPY`. |
| `cachedb.config.writeBehind.blockTimeoutMillis` | `2000` | Redis stream blocking read timeout. |
| `cachedb.config.writeBehind.idleSleepMillis` | `250` | Worker sleep when idle. |
| `cachedb.config.writeBehind.maxFlushRetries` | `3` | Flush retry count for generic failures. |
| `cachedb.config.writeBehind.retryBackoffMillis` | `1000` | Backoff between write-behind retries. |
| `cachedb.config.writeBehind.streamKey` | `cachedb:stream:write-behind` | Base write-behind stream key. |
| `cachedb.config.writeBehind.consumerGroup` | `cachedb-write-behind` | Base consumer group name. |
| `cachedb.config.writeBehind.consumerNamePrefix` | `cachedb-worker` | Base consumer name prefix. |
| `cachedb.config.writeBehind.compactionStreamKey` | `cachedb:stream:write-behind:compaction` | Dedicated compaction stream key. |
| `cachedb.config.writeBehind.compactionConsumerGroup` | `cachedb-write-behind-compaction` | Dedicated compaction consumer group. |
| `cachedb.config.writeBehind.compactionConsumerNamePrefix` | `cachedb-compaction-worker` | Dedicated compaction consumer name prefix. |
| `cachedb.config.writeBehind.compactionShardCount` | `4` | Number of compaction shards. Multiple shards reduce durable compaction stream hot-spotting under concurrent writes. |
| `cachedb.config.writeBehind.autoCreateConsumerGroup` | `true` | Auto-creates Redis stream consumer groups. |
| `cachedb.config.writeBehind.shutdownAwaitMillis` | `10000` | Graceful shutdown wait. |
| `cachedb.config.writeBehind.daemonThreads` | `true` | Runs workers as daemon threads. |
| `cachedb.config.writeBehind.recoverPendingEntries` | `true` | Claims orphaned pending entries on startup. |
| `cachedb.config.writeBehind.claimIdleMillis` | `5000` | Pending-entry idle threshold before claim. |
| `cachedb.config.writeBehind.claimBatchSize` | `100` | Max claimed pending entries per cycle. |
| `cachedb.config.writeBehind.deadLetterMaxLength` | `10000` | Redis DLQ stream trim target for write-behind failures. |
| `cachedb.config.writeBehind.deadLetterStreamKey` | `cachedb:stream:write-behind:dlq` | Write-behind DLQ stream key. |
| `cachedb.config.writeBehind.compactionMaxLength` | `10000` | Compaction stream trim target. |
| `cachedb.config.writeBehind.retryOverrides` | empty | Per-entity retry overrides. Format below. |
| `cachedb.config.writeBehind.entityFlushPolicies` | empty | Per-entity PostgreSQL flush policies. Format below. |

### Resource Limits and Default Cache Policy

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.resourceLimits.maxRegisteredEntities` | `1000` | Max entity bindings allowed in one `CacheDatabase`. |
| `cachedb.config.resourceLimits.maxColumnsPerOperation` | `256` | Max columns tracked in a single mutation. |
| `cachedb.config.resourceLimits.defaultCachePolicy.hotEntityLimit` | `1000` | Default hot entity budget. |
| `cachedb.config.resourceLimits.defaultCachePolicy.pageSize` | `100` | Default page cache size. |
| `cachedb.config.resourceLimits.defaultCachePolicy.lruEvictionEnabled` | `true` | Enables LRU-like eviction for the hot set. |
| `cachedb.config.resourceLimits.defaultCachePolicy.entityTtlSeconds` | `0` | Entity TTL. `0` means no TTL. |
| `cachedb.config.resourceLimits.defaultCachePolicy.pageTtlSeconds` | `60` | Page-cache TTL in seconds. |

### Keyspace, Functions, Relations, Page Cache

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.keyspace.keyPrefix` | `cachedb` | Global Redis key prefix. |
| `cachedb.config.keyspace.entitySegment` | `entity` | Entity-key segment. |
| `cachedb.config.keyspace.pageSegment` | `page` | Page-cache segment. |
| `cachedb.config.keyspace.versionSegment` | `version` | Version-key segment. |
| `cachedb.config.keyspace.tombstoneSegment` | `tombstone` | Tombstone-key segment. |
| `cachedb.config.keyspace.hotSetSegment` | `hotset` | Hot-set segment. |
| `cachedb.config.keyspace.indexSegment` | `index` | Query-index segment. |
| `cachedb.config.keyspace.compactionSegment` | `compaction` | Compaction segment. |
| `cachedb.config.redisFunctions.enabled` | `true` | Enables Redis Functions path. |
| `cachedb.config.redisFunctions.autoLoadLibrary` | `true` | Loads Redis Functions on startup. |
| `cachedb.config.redisFunctions.replaceLibraryOnLoad` | `true` | Replaces the Redis Function library when loading. |
| `cachedb.config.redisFunctions.strictLoading` | `true` | Fails fast if function loading cannot complete cleanly. |
| `cachedb.config.redisFunctions.libraryName` | `cachedb` | Redis Function library name. |
| `cachedb.config.redisFunctions.upsertFunctionName` | `entity_upsert` | Upsert function entrypoint. |
| `cachedb.config.redisFunctions.deleteFunctionName` | `entity_delete` | Delete function entrypoint. |
| `cachedb.config.redisFunctions.compactionCompleteFunctionName` | `compaction_complete` | Compaction-complete function entrypoint. |
| `cachedb.config.redisFunctions.templateResourcePath` | `/functions/cachedb-functions.lua` | Redis Function source template path. |
| `cachedb.config.redisFunctions.sourceOverride` | empty | Full source override for the library. |
| `cachedb.config.relations.batchSize` | `250` | Default relation batch loader size. |
| `cachedb.config.relations.maxFetchDepth` | `3` | Max relation fetch depth. |
| `cachedb.config.relations.failOnMissingPreloader` | `false` | Fails when fetch plans require missing relation preloaders. |
| `cachedb.config.pageCache.readThroughEnabled` | `true` | Enables page-cache read-through. |
| `cachedb.config.pageCache.failOnMissingPageLoader` | `false` | Fails when page read-through has no loader. |
| `cachedb.config.pageCache.evictionBatchSize` | `100` | Page-cache eviction work chunk size. |

### Query Index

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.queryIndex.exactIndexEnabled` | `true` | Enables exact-match indexes. |
| `cachedb.config.queryIndex.rangeIndexEnabled` | `true` | Enables range indexes. |
| `cachedb.config.queryIndex.prefixIndexEnabled` | `true` | Enables prefix indexes. |
| `cachedb.config.queryIndex.textIndexEnabled` | `true` | Enables text indexes. |
| `cachedb.config.queryIndex.plannerStatisticsEnabled` | `true` | Enables planner statistics collection. |
| `cachedb.config.queryIndex.plannerStatisticsPersisted` | `true` | Persists planner statistics in Redis. |
| `cachedb.config.queryIndex.plannerStatisticsTtlMillis` | `60000` | Planner statistics TTL. |
| `cachedb.config.queryIndex.plannerStatisticsSampleSize` | `32` | Planner statistics sample size. |
| `cachedb.config.queryIndex.learnedStatisticsEnabled` | `true` | Enables learned planner weighting. |
| `cachedb.config.queryIndex.learnedStatisticsWeight` | `0.35` | Weight given to learned planner statistics. |
| `cachedb.config.queryIndex.cacheWarmingEnabled` | `true` | Enables index and planner warming. |
| `cachedb.config.queryIndex.rangeHistogramBuckets` | `8` | Bucket count for range histograms. |
| `cachedb.config.queryIndex.prefixMaxLength` | `12` | Max indexed prefix length. |
| `cachedb.config.queryIndex.textTokenMinLength` | `2` | Minimum indexed text token length. |
| `cachedb.config.queryIndex.textTokenMaxLength` | `32` | Maximum indexed text token length. |
| `cachedb.config.queryIndex.textMaxTokensPerValue` | `16` | Max indexed tokens per field value. |

### Projection Refresh

These properties tune the durable Redis Stream-backed projection refresh worker used by `EntityProjection.asyncRefresh()`.

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.projectionRefresh.enabled` | `true` | Enables the projection refresh stream and worker. |
| `cachedb.config.projectionRefresh.streamKey` | `cachedb:stream:projection-refresh` | Redis stream key that stores projection refresh events. |
| `cachedb.config.projectionRefresh.consumerGroup` | `cachedb-projection-refresh` | Redis consumer group used by projection refresh workers. |
| `cachedb.config.projectionRefresh.consumerNamePrefix` | `projection-refresh` | Prefix used when worker consumer names are generated. |
| `cachedb.config.projectionRefresh.batchSize` | `100` | Max number of refresh events read in one worker batch. |
| `cachedb.config.projectionRefresh.blockTimeoutMillis` | `1000` | Redis stream blocking read timeout. |
| `cachedb.config.projectionRefresh.idleSleepMillis` | `250` | Worker sleep interval when no refresh work is available. |
| `cachedb.config.projectionRefresh.autoCreateConsumerGroup` | `true` | Auto-creates the Redis consumer group when missing. |
| `cachedb.config.projectionRefresh.recoverPendingEntries` | `true` | Attempts to recover stale pending projection events. |
| `cachedb.config.projectionRefresh.claimIdleMillis` | `30000` | Idle threshold before a pending projection event is claimed. |
| `cachedb.config.projectionRefresh.claimBatchSize` | `100` | Max number of pending projection events claimed in one pass. |
| `cachedb.config.projectionRefresh.maxStreamLength` | `100000` | Approximate Redis stream trim target for projection refresh events. |
| `cachedb.config.projectionRefresh.deadLetterEnabled` | `true` | Enables the projection refresh poison/dead-letter stream. |
| `cachedb.config.projectionRefresh.deadLetterStreamKey` | `cachedb:stream:projection-refresh-dlq` | Redis stream key that stores poisoned projection refresh events. |
| `cachedb.config.projectionRefresh.deadLetterMaxLength` | `25000` | Approximate trim target for the projection refresh dead-letter stream. |
| `cachedb.config.projectionRefresh.maxAttempts` | `3` | Max processing attempts before a projection refresh event is moved to dead-letter. |
| `cachedb.config.projectionRefresh.deadLetterWarnThreshold` | `1` | Warn threshold used by admin incidents/services for projection refresh dead-letter backlog. |
| `cachedb.config.projectionRefresh.deadLetterCriticalThreshold` | `25` | Critical threshold used by admin incidents/services for projection refresh dead-letter backlog. |
| `cachedb.config.projectionRefresh.shutdownAwaitMillis` | `5000` | Graceful shutdown wait for the projection refresh worker. |
| `cachedb.config.projectionRefresh.daemonThreads` | `true` | Runs the projection refresh worker on daemon threads. |

Operational notes:

- async projection refresh is now durable at the Redis Stream level
- refresh events survive process restarts and can be consumed by multiple application nodes
- the model remains eventually consistent by design
- poisoned projection refresh events are moved to a dedicated Redis Stream dead-letter queue
- replay is available through the admin API and the bundled ops tooling
- this is not yet a full projection platform with poison-queue handling, replay tooling, or dedicated admin telemetry
- projection refresh consumer names are now pod-unique by default when runtime coordination suffixing is enabled

### Redis Guardrails

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.redisGuardrail.enabled` | `true` | Enables Redis guardrails. |
| `cachedb.config.redisGuardrail.producerBackpressureEnabled` | `true` | Lets producers slow down under pressure. |
| `cachedb.config.redisGuardrail.usedMemoryWarnBytes` | `0` | Redis memory warning threshold. `0` disables it. |
| `cachedb.config.redisGuardrail.usedMemoryCriticalBytes` | `0` | Redis memory critical threshold. `0` disables it. |
| `cachedb.config.redisGuardrail.writeBehindBacklogWarnThreshold` | `250` | Warning threshold for pending write-behind work. |
| `cachedb.config.redisGuardrail.writeBehindBacklogCriticalThreshold` | `750` | Critical threshold for pending write-behind work. |
| `cachedb.config.redisGuardrail.compactionPendingWarnThreshold` | `1000` | Warning threshold for compaction pending keys. |
| `cachedb.config.redisGuardrail.compactionPendingCriticalThreshold` | `5000` | Critical threshold for compaction pending keys. |
| `cachedb.config.redisGuardrail.writeBehindBacklogHardLimit` | `0` | Hard cap for write-behind backlog. `0` disables it. |
| `cachedb.config.redisGuardrail.compactionPendingHardLimit` | `0` | Hard cap for compaction pending count. `0` disables it. |
| `cachedb.config.redisGuardrail.compactionPayloadHardLimit` | `0` | Hard cap for compaction payload count. `0` disables it. |
| `cachedb.config.redisGuardrail.rejectWritesOnHardLimit` | `false` | Rejects writes instead of only degrading when a hard limit is exceeded. |
| `cachedb.config.redisGuardrail.shedPageCacheWritesOnHardLimit` | `true` | Stops page-cache writes under hard-limit pressure. |
| `cachedb.config.redisGuardrail.shedReadThroughCacheOnHardLimit` | `true` | Stops read-through cache fills under hard-limit pressure. |
| `cachedb.config.redisGuardrail.shedHotSetTrackingOnHardLimit` | `true` | Stops hot-set tracking under hard-limit pressure. |
| `cachedb.config.redisGuardrail.shedQueryIndexWritesOnHardLimit` | `true` | Stops query-index writes under hard-limit pressure. |
| `cachedb.config.redisGuardrail.shedQueryIndexReadsOnHardLimit` | `true` | Stops query-index reads under hard-limit pressure. |
| `cachedb.config.redisGuardrail.shedPlannerLearningOnHardLimit` | `true` | Stops planner learning under hard-limit pressure. |
| `cachedb.config.redisGuardrail.highSleepMillis` | `2` | Producer sleep for warning/high pressure. |
| `cachedb.config.redisGuardrail.criticalSleepMillis` | `5` | Producer sleep for critical pressure. |
| `cachedb.config.redisGuardrail.sampleIntervalMillis` | `500` | Guardrail sampling cadence. |
| `cachedb.config.redisGuardrail.automaticRuntimeProfileSwitchingEnabled` | `true` | Enables automatic runtime profile switching. |
| `cachedb.config.redisGuardrail.warnSamplesToBalanced` | `3` | Samples needed to escalate `STANDARD -> BALANCED`. |
| `cachedb.config.redisGuardrail.criticalSamplesToAggressive` | `2` | Samples needed to escalate to `AGGRESSIVE`. |
| `cachedb.config.redisGuardrail.warnSamplesToDeescalateAggressive` | `4` | Samples needed to de-escalate from `AGGRESSIVE` to `BALANCED`. |
| `cachedb.config.redisGuardrail.normalSamplesToStandard` | `5` | Samples needed to return to `STANDARD`. |
| `cachedb.config.redisGuardrail.compactionPayloadTtlSeconds` | `3600` | TTL for compaction payload keys. |
| `cachedb.config.redisGuardrail.compactionPendingTtlSeconds` | `3600` | TTL for compaction pending keys. |
| `cachedb.config.redisGuardrail.versionKeyTtlSeconds` | `86400` | TTL for version keys. |
| `cachedb.config.redisGuardrail.tombstoneTtlSeconds` | `86400` | TTL for tombstones. |
| `cachedb.config.redisGuardrail.autoRecoverDegradedIndexesEnabled` | `true` | Auto-rebuilds degraded indexes when pressure falls. |
| `cachedb.config.redisGuardrail.degradedIndexRebuildCooldownMillis` | `30000` | Cooldown before another degraded-index rebuild. |
| `cachedb.config.redisGuardrail.entityPolicies` | empty | Namespace-specific hard-limit shedding policies. Format below. |
| `cachedb.config.redisGuardrail.queryPolicies` | empty | Query-class shedding policies. Format below. |

### Dead-Letter Recovery

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.deadLetterRecovery.enabled` | `true` | Enables DLQ recovery worker. |
| `cachedb.config.deadLetterRecovery.workerThreads` | `1` | DLQ worker thread count. |
| `cachedb.config.deadLetterRecovery.blockTimeoutMillis` | `2000` | DLQ stream block timeout. |
| `cachedb.config.deadLetterRecovery.idleSleepMillis` | `250` | DLQ worker idle sleep. |
| `cachedb.config.deadLetterRecovery.consumerGroup` | `cachedb-write-behind-dlq` | DLQ consumer group. |
| `cachedb.config.deadLetterRecovery.consumerNamePrefix` | `cachedb-dlq-worker` | DLQ consumer name prefix. |
| `cachedb.config.deadLetterRecovery.autoCreateConsumerGroup` | `true` | Auto-creates DLQ consumer group. |
| `cachedb.config.deadLetterRecovery.shutdownAwaitMillis` | `10000` | Graceful shutdown wait for DLQ worker. |
| `cachedb.config.deadLetterRecovery.daemonThreads` | `true` | Runs DLQ worker as daemon. |
| `cachedb.config.deadLetterRecovery.claimIdleMillis` | `5000` | Pending-entry idle threshold before DLQ claim. |
| `cachedb.config.deadLetterRecovery.claimBatchSize` | `100` | Claimed DLQ entries per cycle. |
| `cachedb.config.deadLetterRecovery.maxReplayRetries` | `3` | DLQ replay retry count. |
| `cachedb.config.deadLetterRecovery.replayBackoffMillis` | `1000` | DLQ replay backoff. |
| `cachedb.config.deadLetterRecovery.reconciliationStreamKey` | `cachedb:stream:write-behind:reconciliation` | Reconciliation stream key. |
| `cachedb.config.deadLetterRecovery.archiveResolvedEntries` | `true` | Archives resolved DLQ entries. |
| `cachedb.config.deadLetterRecovery.archiveStreamKey` | `cachedb:stream:write-behind:archive` | Archive stream key. |
| `cachedb.config.deadLetterRecovery.cleanupEnabled` | `true` | Enables retention cleanup. |
| `cachedb.config.deadLetterRecovery.cleanupIntervalMillis` | `60000` | Retention cleanup cadence. |
| `cachedb.config.deadLetterRecovery.cleanupBatchSize` | `250` | Cleanup work chunk size. |
| `cachedb.config.deadLetterRecovery.cleanupEnabled` | `true` | Enables the retention cleanup loop. In multi-pod mode this loop is now leader-leased so only one node performs the cleanup work at a time. |
| `cachedb.config.deadLetterRecovery.deadLetterRetentionMillis` | `0` | DLQ retention. `0` means keep indefinitely. |
| `cachedb.config.deadLetterRecovery.reconciliationRetentionMillis` | `604800000` | Reconciliation retention. |
| `cachedb.config.deadLetterRecovery.archiveRetentionMillis` | `2592000000` | Archive retention. |
| `cachedb.config.deadLetterRecovery.deadLetterMaxLength` | `10000` | DLQ stream max length. |
| `cachedb.config.deadLetterRecovery.reconciliationMaxLength` | `10000` | Reconciliation stream max length. |
| `cachedb.config.deadLetterRecovery.archiveMaxLength` | `10000` | Archive stream max length. |
| `cachedb.config.deadLetterRecovery.retryOverrides` | empty | Per-entity replay retry overrides. Format below. |

### Admin Monitoring, Reporting, HTTP, and Schema Bootstrap

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.config.adminMonitoring.writeBehindWarnThreshold` | `250` | Warning incident threshold for write-behind backlog. Aligned with the default Redis guardrail warning level to avoid false degraded signals during short bursts. |
| `cachedb.config.adminMonitoring.writeBehindCriticalThreshold` | `750` | Critical incident threshold for write-behind backlog. Aligned with the default Redis guardrail critical level. |
| `cachedb.config.adminMonitoring.deadLetterWarnThreshold` | `10` | Warning incident threshold for DLQ size. |
| `cachedb.config.adminMonitoring.deadLetterCriticalThreshold` | `100` | Critical incident threshold for DLQ size. |
| `cachedb.config.adminMonitoring.recoveryFailedWarnThreshold` | `10` | Warning threshold for recovery failures. |
| `cachedb.config.adminMonitoring.recoveryFailedCriticalThreshold` | `100` | Critical threshold for recovery failures. |
| `cachedb.config.adminMonitoring.recentErrorWindowMillis` | `60000` | Window used for “recent worker error” incidents. |
| `cachedb.config.adminMonitoring.historySampleIntervalMillis` | `5000` | Server-side monitoring sample cadence. |
| `cachedb.config.adminMonitoring.historyMinSampleIntervalMillis` | `1000` | Lower floor for monitoring sample cadence after overrides. |
| `cachedb.config.adminMonitoring.historyMaxSamples` | `720` | Max monitoring history samples retained in the Redis monitoring-history stream. |
| `cachedb.config.adminMonitoring.historyMinSamples` | `32` | Lower floor for monitoring-history retention. |
| `cachedb.config.adminMonitoring.alertRouteHistoryMinSamples` | `64` | Lower floor for alert-route history samples. |
| `cachedb.config.adminMonitoring.alertRouteHistorySampleMultiplier` | `4` | Multiplier used to expand alert-route history relative to monitoring history. |
| `cachedb.config.adminMonitoring.telemetryTtlSeconds` | `86400` | Default TTL for Redis-backed admin telemetry keys. Out of the box, telemetry expires after 1 day unless overridden. |
| `cachedb.config.adminMonitoring.monitoringHistoryStreamKey` | `cachedb:stream:admin:monitoring-history` | Redis stream key for monitoring history samples. |
| `cachedb.config.adminMonitoring.alertRouteHistoryStreamKey` | `cachedb:stream:admin:alert-route-history` | Redis stream key for alert-route history samples. |
| `cachedb.config.adminMonitoring.performanceHistoryStreamKey` | `cachedb:stream:admin:performance-history` | Redis stream key for performance history samples. |
| `cachedb.config.adminMonitoring.performanceSnapshotKey` | `cachedb:hash:admin:performance` | Redis hash key for the current storage-performance snapshot and scenario breakdowns. |
| `cachedb.config.adminMonitoring.incidentTtlSeconds` | `86400` | TTL for incident stream entries and their cooldown keys. |
| `cachedb.config.adminMonitoring.incidentStreamKey` | `cachedb:stream:admin:incidents` | Incident stream key. |
| `cachedb.config.adminMonitoring.incidentMaxLength` | `2000` | Incident stream trim target. |
| `cachedb.config.adminMonitoring.incidentCooldownMillis` | `30000` | Cooldown before re-emitting the same incident. |
| `cachedb.config.adminMonitoring.incidentDeliveryQueueFloor` | `64` | Minimum in-memory queue size for incident delivery workers. |
| `cachedb.config.adminMonitoring.incidentDeliveryPollTimeoutMillis` | `500` | Delivery worker poll wait when the incident queue is empty. |
| `cachedb.config.adminMonitoring.incidentWebhook.*` | see code defaults | Webhook incident delivery tuning. |
| `cachedb.config.adminMonitoring.incidentQueue.*` | see code defaults | Redis queue incident delivery tuning. |
| `cachedb.config.adminMonitoring.incidentEmail.*` | see code defaults | SMTP incident delivery tuning. |
| `cachedb.config.adminMonitoring.incidentDeliveryDlq.*` | see code defaults | Incident-delivery DLQ and replay tuning. |
| `cachedb.config.adminReportJob.enabled` | `false` | Enables persisted admin report generation. |
| `cachedb.config.adminReportJob.intervalMillis` | `300000` | Report job cadence. |
| `cachedb.config.adminReportJob.outputDirectory` | `build/reports/cachedb-admin` | Report output directory. |
| `cachedb.config.adminReportJob.format` | `JSON` | Export format for report job output. |
| `cachedb.config.adminReportJob.queryLimit` | `500` | Max records pulled per report section. |
| `cachedb.config.adminReportJob.writeDeadLetters` | `true` | Includes dead-letter export. |
| `cachedb.config.adminReportJob.writeReconciliation` | `true` | Includes reconciliation export. |
| `cachedb.config.adminReportJob.writeArchive` | `true` | Includes archive export. |
| `cachedb.config.adminReportJob.writeIncidents` | `true` | Includes incident export. |
| `cachedb.config.adminReportJob.writeDiagnostics` | `true` | Includes diagnostics export. |
| `cachedb.config.adminReportJob.includeTimestampInFileName` | `true` | Adds timestamps to report file names. |
| `cachedb.config.adminReportJob.maxRetainedFilesPerReport` | `10` | Max retained files per report type. |
| `cachedb.config.adminReportJob.fileRetentionMillis` | `604800000` | Admin report retention. |
| `cachedb.config.adminReportJob.persistDiagnostics` | `true` | Persists diagnostics to Redis stream. |
| `cachedb.config.adminReportJob.diagnosticsStreamKey` | `cachedb:stream:admin:diagnostics` | Diagnostics stream key. |
| `cachedb.config.adminReportJob.diagnosticsMaxLength` | `2000` | Diagnostics stream trim target. |
| `cachedb.config.adminReportJob.diagnosticsTtlSeconds` | `86400` | TTL for diagnostics stream entries. |
| `cachedb.config.adminHttp.enabled` | `false` | Enables admin HTTP server. |
| `cachedb.config.adminHttp.host` | `127.0.0.1` | Admin HTTP bind host. |
| `cachedb.config.adminHttp.port` | `0` | Admin HTTP port. `0` means caller chooses explicitly. |
| `cachedb.config.adminHttp.backlog` | `64` | HTTP socket backlog. |
| `cachedb.config.adminHttp.workerThreads` | `2` | HTTP worker thread count. |
| `cachedb.config.adminHttp.dashboardEnabled` | `true` | Serves the HTML dashboard. |
| `cachedb.config.adminHttp.corsEnabled` | `false` | Enables permissive CORS headers. |
| `cachedb.config.adminHttp.dashboardTitle` | `CacheDB Admin` | Dashboard title text. |
| `cachedb.config.schemaBootstrap.mode` | `DISABLED` | Schema bootstrap mode. |
| `cachedb.config.schemaBootstrap.autoApplyOnStart` | `false` | Applies schema bootstrap on startup. |
| `cachedb.config.schemaBootstrap.includeVersionColumn` | `true` | Includes version column in generated DDL. |
| `cachedb.config.schemaBootstrap.includeDeletedColumn` | `true` | Includes deleted column in generated DDL. |
| `cachedb.config.schemaBootstrap.schemaName` | empty | Target PostgreSQL schema name. |

## Structured Override Formats

`retryOverrides`:

```text
entityName,operationType,maxRetries,backoffMillis|entityName,*,maxRetries,backoffMillis
```

`entityFlushPolicies`:

```text
entityName,operationType,stateCompactionEnabled,preferCopy,preferMultiRow,maxBatchSize,statementRowLimit,copyThreshold,persistenceSemantics
```

`redisGuardrail.entityPolicies`:

```text
namespace,shedPageCacheWrites,shedReadThroughCache,shedHotSetTracking,shedQueryIndexWrites,shedQueryIndexReads,shedPlannerLearning,autoRebuildIndexes
```

`redisGuardrail.queryPolicies`:

```text
namespace,queryClass,shedReads,shedLearning
```

`incidentEmail.toAddresses` and `incidentEmail.pinnedServerCertificateSha256`:

```text
value1,value2,value3
```

`postgres.additionalParameters`:

```text
key=value;key=value
```

## Where the Other Tunables Live

- Benchmark- and certification-specific load knobs stay documented in [cachedb-production-tests/README.md](/E:/ReactorRepository/cache-database/cachedb-production-tests/README.md).
- Demo-specific URLs, ports, and profile controls stay documented in [cachedb-examples/README.md](/E:/ReactorRepository/cache-database/cachedb-examples/README.md).

## Demo Runtime Tuning

These properties control the simple load demo without editing code.

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.demo.cache.hotEntityLimit` | `100` | Demo entity hot-set limit. |
| `cachedb.demo.cache.pageSize` | `20` | Demo repository page size. |
| `cachedb.demo.cache.entityTtlSeconds` | `600` | Demo entity TTL. |
| `cachedb.demo.cache.pageTtlSeconds` | `120` | Demo page TTL. |
| `cachedb.demo.bindHost` | `0.0.0.0` | Demo and admin HTTP bind host used by the example main. |
| `cachedb.demo.publicHost` | `127.0.0.1` | Public host rendered into demo/admin URLs. |
| `cachedb.demo.admin.port` | `8080` | Admin dashboard port used by the demo main. |
| `cachedb.demo.admin.workerThreads` | `2` | Admin HTTP worker threads for the demo main. |
| `cachedb.demo.ui.port` | `8090` | Demo load UI port. |
| `cachedb.demo.keyPrefix` | `cachedb-demo` | Redis key prefix for the demo namespace. |
| `cachedb.demo.functionPrefix` | `demo_<uuid>` | Redis Function library/function name prefix for the demo process. |
| `cachedb.demo.seed.customers` | `48` | Number of demo customers seeded. |
| `cachedb.demo.seed.products` | `36` | Number of demo products seeded. |
| `cachedb.demo.seed.carts` | `32` | Number of demo carts seeded. |
| `cachedb.demo.seed.orders` | `32` | Number of demo orders seeded. |
| `cachedb.demo.view.pageSize` | `12` | Row count shown in each demo table. |
| `cachedb.demo.view.countPageSize` | `500` | Repository page size used by the snapshot counters on the demo screen. |
| `cachedb.demo.view.readerPageSize` | `10` | Page size used by reader threads during paging reads. |
| `cachedb.demo.view.readerPageWindowVariants` | `3` | Number of page windows reader threads rotate through. |
| `cachedb.demo.stop.awaitTerminationMillis` | `5000` | How long the demo waits for workers to stop cleanly. |
| `cachedb.demo.error.backoffMillis` | `50` | Backoff after a demo worker error. |
| `cachedb.demo.ui.workerThreads` | `2` | HTTP worker threads serving the demo UI. |
| `cachedb.demo.ui.autoRefreshMillis` | `3000` | Browser auto-refresh interval for the demo UI. `0` disables timer-driven refresh. |
| `cachedb.demo.load.low.readers` | `4` | Reader threads used by the LOW profile. |
| `cachedb.demo.load.low.writers` | `2` | Writer threads used by the LOW profile. |
| `cachedb.demo.load.low.readerPauseMillis` | `18` | Reader pause between LOW profile cycles. |
| `cachedb.demo.load.low.writerPauseMillis` | `28` | Writer pause between LOW profile cycles. |
| `cachedb.demo.load.medium.readers` | `8` | Reader threads used by the MEDIUM profile. |
| `cachedb.demo.load.medium.writers` | `4` | Writer threads used by the MEDIUM profile. |
| `cachedb.demo.load.medium.readerPauseMillis` | `8` | Reader pause between MEDIUM profile cycles. |
| `cachedb.demo.load.medium.writerPauseMillis` | `14` | Writer pause between MEDIUM profile cycles. |
| `cachedb.demo.load.high.readers` | `16` | Reader threads used by the HIGH profile. |
| `cachedb.demo.load.high.writers` | `8` | Writer threads used by the HIGH profile. |
| `cachedb.demo.load.high.readerPauseMillis` | `2` | Reader pause between HIGH profile cycles. |
| `cachedb.demo.load.high.writerPauseMillis` | `5` | Writer pause between HIGH profile cycles. |

## Admin Dashboard UI Tuning

These properties let you change the built-in HTTP dashboard text and style without editing Java code.

The admin dashboard also exposes the currently effective runtime values at:

- `/api/tuning`
- `/api/tuning/export?format=json|markdown`
- `/api/tuning/flags`
- `Current Effective Tuning` section on `/dashboard`

This view combines:

- core effective values derived from the active `CacheDatabaseConfig`
- explicit JVM/system-property overrides whose keys start with `cachedb.`

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.admin.ui.bootstrapCssUrl` | Bootstrap 5.3.3 CDN URL | CSS URL used by the admin dashboard. |
| `cachedb.admin.ui.themeCss` | built-in dashboard CSS | Full CSS block injected into the admin dashboard page. |
| `cachedb.admin.ui.navbarSubtitle` | `Simple HTTP admin dashboard with Bootstrap + AJAX` | Subtitle shown in the top navbar. |
| `cachedb.admin.ui.loadingText` | `Loading…` | Placeholder text shown before AJAX sections populate. |
| `cachedb.admin.ui.resetToolsTitle` | `Admin Reset Tools` | Title of the reset tools card. |
| `cachedb.admin.ui.resetTelemetryLabel` | `Reset Telemetry History` | Button/section label for telemetry reset. |
| `cachedb.admin.ui.resetTelemetryDescription` | built-in reset description | Summary text shown in the reset tools card. |
| `cachedb.admin.ui.resetTelemetryExplainTitle` | `Reset Telemetry History ne yapar?` | Title of the inline reset explanation. |
| `cachedb.admin.ui.resetTelemetryExplainBody` | built-in reset explanation | Explanation shown under the live refresh card. |
| `cachedb.admin.ui.liveRefreshTitle` | `Live Refresh` | Title of the refresh-control card. |
| `cachedb.admin.ui.refreshNowLabel` | `Refresh Now` | Manual refresh button label. |
| `cachedb.admin.ui.toggleRefreshLabel` | `Pause` | Pause/resume button label. |
| `cachedb.admin.ui.refreshOptions` | `0:Paused,5000:5 seconds,10000:10 seconds,30000:30 seconds,60000:60 seconds` | Refresh dropdown options as `millis:label`. |
| `cachedb.admin.ui.defaultRefreshMillis` | `5000` | Default selected auto-refresh interval. |
| `cachedb.admin.ui.autoRefreshLabel` | `Auto Refresh` | Label shown above the auto-refresh dropdown. |
| `cachedb.admin.ui.lastUpdatedLabel` | `Last Updated` | Label shown above the last-updated field. |
| `cachedb.admin.ui.lastUpdatedNever` | `never` | Initial last-updated placeholder before the first AJAX refresh. |
| `cachedb.admin.ui.resetTelemetryNotRunYet` | `No telemetry reset has been run yet.` | Initial status text shown before the first telemetry reset. |
| `cachedb.admin.ui.resetTelemetryInProgress` | `Resetting admin telemetry...` | Status message shown while telemetry reset is running. |
| `cachedb.admin.ui.resetTelemetryResultPrefix` | `Cleared: diagnostics ` | Prefix for the successful reset summary. |
| `cachedb.admin.ui.resetTelemetryIncidentsSegment` | `, incidents ` | Middle segment for cleared incident count. |
| `cachedb.admin.ui.resetTelemetryHistorySegment` | `, history ` | Middle segment for cleared monitoring-history count. |
| `cachedb.admin.ui.resetTelemetryRouteHistorySegment` | `, route history ` | Middle segment for cleared alert-route-history count. |
| `cachedb.admin.ui.resetTelemetryErrorPrefix` | `Reset failed: ` | Prefix for telemetry reset errors. |
| `cachedb.admin.ui.resumeLabel` | `Resume` | Resume label for the auto-refresh toggle button. |
| `cachedb.admin.ui.pauseLabel` | `Pause` | Pause label for the auto-refresh toggle button. |
| `cachedb.admin.ui.howToReadTitle` | `How To Read This Dashboard` | How-to-read section title. |
| `cachedb.admin.ui.howToRead.step1Title` | `1. First look` | First operator walkthrough step title. |
| `cachedb.admin.ui.howToRead.step1Body` | built-in help text | First operator walkthrough step body. |
| `cachedb.admin.ui.howToRead.step2Title` | `2. Where is the problem?` | Second operator walkthrough step title. |
| `cachedb.admin.ui.howToRead.step2Body` | built-in help text | Second operator walkthrough step body. |
| `cachedb.admin.ui.howToRead.step3Title` | `3. What should I do next?` | Third operator walkthrough step title. |
| `cachedb.admin.ui.howToRead.step3Body` | built-in help text | Third operator walkthrough step body. |
| `cachedb.admin.ui.sectionGuideTitle` | `Section Guide` | Section-guide title. |
| `cachedb.admin.ui.sectionGuide.liveTrendsTitle` | `Live Trends` | Section-guide title for the live-trends card. |
| `cachedb.admin.ui.sectionGuide.liveTrendsBody` | built-in guide text | Section-guide body for live trends. |
| `cachedb.admin.ui.sectionGuide.triageTitle` | `Triage` | Section-guide title for triage. |
| `cachedb.admin.ui.sectionGuide.triageBody` | built-in guide text | Section-guide body for triage. |
| `cachedb.admin.ui.sectionGuide.topSignalsTitle` | `Top Failing Signals` | Section-guide title for top failing signals. |
| `cachedb.admin.ui.sectionGuide.topSignalsBody` | built-in guide text | Section-guide body for top failing signals. |
| `cachedb.admin.ui.sectionGuide.routingTitle` | `Alert Routing / Runbooks` | Section-guide title for routing/runbooks. |
| `cachedb.admin.ui.sectionGuide.routingBody` | built-in guide text | Section-guide body for routing/runbooks. |
| `cachedb.admin.ui.liveTrendsTitle` | `Live Trends` | Live trend section title. |
| `cachedb.admin.ui.liveTrends.backlogLabel` | `Write-behind backlog` | Label above the backlog sparkline. |
| `cachedb.admin.ui.liveTrends.redisMemoryLabel` | `Redis memory` | Label above the memory sparkline. |
| `cachedb.admin.ui.liveTrends.deadLetterLabel` | `Dead-letter backlog` | Label above the DLQ sparkline. |
| `cachedb.admin.ui.alertRouteTrendsTitle` | `Alert Route Trends` | Alert-route trend section title. |
| `cachedb.admin.ui.alertRouteTrends.deliveredLabel` | `Channel delivered count` | Label above the route-delivered trend chart. |
| `cachedb.admin.ui.alertRouteTrends.failedLabel` | `Channel failed count` | Label above the route-failed trend chart. |
| `cachedb.admin.ui.incidentSeverityTrendsTitle` | `Incident Severity Trends` | Incident severity trend title. |
| `cachedb.admin.ui.topFailingSignalsTitle` | `Top Failing Signals` | Top failing signals title. |
| `cachedb.admin.ui.failingSignals.activeRecentPrefix` | `active ` | Prefix shown before active failing-signal count. |
| `cachedb.admin.ui.failingSignals.activeRecentSeparator` | ` / recent ` | Separator between active and recent failing-signal counts. |
| `cachedb.admin.ui.failingSignals.lastSeenPrefix` | `last seen ` | Prefix shown before failing-signal last-seen timestamps. |
| `cachedb.admin.ui.triageTitle` | `Triage` | Triage card title. |
| `cachedb.admin.ui.triage.primaryBottleneckPrefix` | `Primary bottleneck: ` | Prefix shown before the current bottleneck value. |
| `cachedb.admin.ui.serviceStatusTitle` | `Service Status` | Service-status card title. |
| `cachedb.admin.ui.healthTitle` | `Health` | Health card title. |
| `cachedb.admin.ui.incidentsTitle` | `Incidents` | Incidents card title. |
| `cachedb.admin.ui.deploymentTitle` | `Deployment` | Deployment section title. |
| `cachedb.admin.ui.schemaStatusTitle` | `Schema Status` | Schema-status section title. |
| `cachedb.admin.ui.schemaHistoryTitle` | `Schema History` | Schema-history section title. |
| `cachedb.admin.ui.starterProfilesTitle` | `Starter Profiles` | Starter-profiles section title. |
| `cachedb.admin.ui.apiRegistryTitle` | `API Registry` | API-registry section title. |
| `cachedb.admin.ui.currentEffectiveTuningTitle` | `Current Effective Tuning` | Effective-tuning section title. |
| `cachedb.admin.ui.tuning.capturedAtLabel` | `Captured at` | Label above the tuning snapshot timestamp. |
| `cachedb.admin.ui.tuning.overrideCountLabel` | `Explicit overrides` | Label above the explicit-override count. |
| `cachedb.admin.ui.tuning.entryCountLabel` | `Visible entries` | Label above the rendered tuning-entry count. |
| `cachedb.admin.ui.tuning.exportJsonLabel` | `Export JSON` | Button label for JSON tuning export. |
| `cachedb.admin.ui.tuning.exportMarkdownLabel` | `Export Markdown` | Button label for Markdown tuning export. |
| `cachedb.admin.ui.tuning.copyFlagsLabel` | `Copy Startup Flags` | Button label for copying effective tuning as `-D...` flags. |
| `cachedb.admin.ui.tuning.exportStatusIdle` | `Choose an export action.` | Initial helper text for the tuning export area. |
| `cachedb.admin.ui.tuning.exportLoading` | `Loading export...` | Status text while an export is loading. |
| `cachedb.admin.ui.tuning.exportJsonSuccess` | `JSON export loaded below.` | Status text after a JSON export loads. |
| `cachedb.admin.ui.tuning.exportMarkdownSuccess` | `Markdown export loaded below.` | Status text after a Markdown export loads. |
| `cachedb.admin.ui.tuning.copyFlagsSuccess` | `Startup flags copied to clipboard.` | Status text after startup flags are copied. |
| `cachedb.admin.ui.tuning.exportErrorPrefix` | `Export failed: ` | Prefix for tuning export errors. |
| `cachedb.admin.ui.certificationTitle` | `Certification` | Certification section title. |
| `cachedb.admin.ui.alertRoutingTitle` | `Alert Routing` | Alert-routing section title. |
| `cachedb.admin.ui.runbooksTitle` | `Runbooks` | Runbooks section title. |
| `cachedb.admin.ui.alertRouteHistoryTitle` | `Alert Route History` | Alert-route history section title. |
| `cachedb.admin.ui.schemaDdlTitle` | `Schema DDL` | Schema DDL section title. |
| `cachedb.admin.ui.runtimeProfileChurnTitle` | `Runtime Profile Churn` | Runtime profile churn section title. |
| `cachedb.admin.ui.explainTitle` | `Explain` | Explain section title. |
| `cachedb.admin.ui.explain.entityLabel` | `Entity` | Label for the explain entity field. |
| `cachedb.admin.ui.explain.filterLabel` | `Filter` | Label for the explain filter field. |
| `cachedb.admin.ui.explain.sortLabel` | `Sort` | Label for the explain sort field. |
| `cachedb.admin.ui.explain.limitLabel` | `Limit` | Label for the explain limit field. |
| `cachedb.admin.ui.explain.includeLabel` | `Include` | Label for the explain include field. |
| `cachedb.admin.ui.runExplainLabel` | `Run Explain` | Explain action button label. |
| `cachedb.admin.ui.metric.writeBehindTitle` | `Write-behind` | Top metric card label for write-behind backlog. |
| `cachedb.admin.ui.metric.deadLetterTitle` | `Dead-letter` | Top metric card label for DLQ backlog. |
| `cachedb.admin.ui.metric.diagnosticsTitle` | `Diagnostics` | Top metric card label for diagnostics length. |
| `cachedb.admin.ui.metric.incidentsTitle` | `Incidents` | Top metric card label for incident count. |
| `cachedb.admin.ui.metric.learnedStatsTitle` | `Learned Stats` | Top metric card label for planner statistics. |
| `cachedb.admin.ui.metric.redisMemoryTitle` | `Redis Memory` | Top metric card label for Redis memory. |
| `cachedb.admin.ui.metric.compactionPendingTitle` | `Compaction Pending` | Top metric card label for compaction pending count. |
| `cachedb.admin.ui.metric.runtimeProfileTitle` | `Runtime Profile` | Top metric card label for runtime profile state. |
| `cachedb.admin.ui.metric.alertDeliveredTitle` | `Alert Delivered` | Top metric card label for delivered alerts. |
| `cachedb.admin.ui.metric.alertFailedTitle` | `Alert Failed` | Top metric card label for failed alerts. |
| `cachedb.admin.ui.metric.alertDroppedTitle` | `Alert Dropped` | Top metric card label for dropped alerts. |
| `cachedb.admin.ui.metric.criticalSignalsTitle` | `Critical Signals` | Top metric card label for critical signals. |
| `cachedb.admin.ui.metric.warningSignalsTitle` | `Warning Signals` | Top metric card label for warning signals. |
| `cachedb.admin.ui.empty.highSignalFailures` | `No high-signal failures detected.` | Empty-state text for the top-failing-signals section. |
| `cachedb.admin.ui.empty.triageEvidence` | `No triage evidence` | Empty-state text for the triage evidence table. |
| `cachedb.admin.ui.empty.services` | `No services` | Empty-state text for service status. |
| `cachedb.admin.ui.empty.activeIssues` | `No active issues` | Empty-state text for health issues. |
| `cachedb.admin.ui.empty.activeIncidents` | `No active incidents` | Empty-state text for incidents. |
| `cachedb.admin.ui.empty.profileSwitches` | `No profile switches` | Empty-state text for runtime-profile churn. |
| `cachedb.admin.ui.empty.deploymentData` | `No deployment data` | Empty-state text for deployment rows. |
| `cachedb.admin.ui.empty.schemaData` | `No schema data` | Empty-state text for schema status. |
| `cachedb.admin.ui.empty.migrationHistory` | `No migration history` | Empty-state text for schema history. |
| `cachedb.admin.ui.empty.starterProfiles` | `No starter profiles` | Empty-state text for starter profiles. |
| `cachedb.admin.ui.empty.registeredEntities` | `No registered entities` | Empty-state text for the registry. |
| `cachedb.admin.ui.empty.tuning` | `No tuning data` | Empty-state text for the effective-tuning table. |
| `cachedb.admin.ui.empty.certificationReports` | `No certification reports` | Empty-state text for certification artifacts. |
| `cachedb.admin.ui.empty.alertRoutes` | `No alert routes` | Empty-state text for alert routing. |
| `cachedb.admin.ui.empty.routeHistory` | `No route history` | Empty-state text for alert-route history. |
| `cachedb.admin.ui.empty.runbooks` | `No runbooks` | Empty-state text for runbooks. |
| `cachedb.admin.ui.deployment.autoApplyLabel` | `auto-apply` | Deployment row label when schema auto-apply is enabled. |
| `cachedb.admin.ui.deployment.manualLabel` | `manual` | Deployment row label when schema apply is manual. |
| `cachedb.admin.ui.deployment.writeBehindOnLabel` | `write-behind on` | Deployment row label when write-behind is enabled. |
| `cachedb.admin.ui.deployment.writeBehindOffLabel` | `write-behind off` | Deployment row label when write-behind is disabled. |
| `cachedb.admin.ui.deployment.workersSuffix` | ` workers` | Suffix appended to write-behind worker count. |
| `cachedb.admin.ui.deployment.durableCompactionLabel` | `durable compaction` | Deployment row label when durable compaction is enabled. |
| `cachedb.admin.ui.deployment.noCompactionLabel` | `no compaction` | Deployment row label when durable compaction is disabled. |
| `cachedb.admin.ui.deployment.activeStreamsSuffix` | ` active streams` | Suffix appended to active stream count. |
| `cachedb.admin.ui.deployment.guardrailsOnLabel` | `guardrails on` | Deployment row label when guardrails are enabled. |
| `cachedb.admin.ui.deployment.guardrailsOffLabel` | `guardrails off` | Deployment row label when guardrails are disabled. |
| `cachedb.admin.ui.deployment.autoProfileSwitchLabel` | `auto profile switch` | Deployment row label when automatic profile switching is enabled. |
| `cachedb.admin.ui.deployment.manualProfileLabel` | `manual profile` | Deployment row label when automatic profile switching is disabled. |
| `cachedb.admin.ui.deployment.keyPrefixLabel` | `key prefix` | Deployment row label for the active key prefix. |
| `cachedb.admin.ui.schema.migrationStepsLabel` | `migration steps` | Schema-status row label for migration steps. |
| `cachedb.admin.ui.schema.createTableStepsLabel` | `create table steps` | Schema-status row label for create-table steps. |
| `cachedb.admin.ui.schema.addColumnStepsLabel` | `add column steps` | Schema-status row label for add-column steps. |
| `cachedb.admin.ui.schema.ddlEntitiesLabel` | `ddl entities` | Schema-status row label for generated DDL entities. |
| `cachedb.admin.ui.schema.stepsPrefix` | `steps=` | Prefix used in schema-history step summaries. |
| `cachedb.admin.ui.profiles.guardrailsEnabledLabel` | `guardrails` | Starter-profile row label when guardrails are enabled. |
| `cachedb.admin.ui.profiles.guardrailsDisabledLabel` | `no guardrails` | Starter-profile row label when guardrails are disabled. |
| `cachedb.admin.ui.registry.columnsPrefix` | `cols=` | Prefix used for registry column count summaries. |
| `cachedb.admin.ui.registry.hotPrefix` | `hot=` | Prefix used for registry hot-entity summaries. |
| `cachedb.admin.ui.registry.pagePrefix` | `page=` | Prefix used for registry page-size summaries. |
| `cachedb.admin.ui.alertRouting.deliveredLabel` | `delivered` | Status fallback label when a route has delivered alerts. |
| `cachedb.admin.ui.alertRouting.idleLabel` | `idle` | Status fallback label when a route has no recent activity. |
| `cachedb.admin.ui.chart.trend.backlogColor` | `#9c3f2b` | Stroke color for the backlog trend sparkline. |
| `cachedb.admin.ui.chart.trend.memoryColor` | `#2563eb` | Stroke color for the memory trend sparkline. |
| `cachedb.admin.ui.chart.trend.deadLetterColor` | `#dc2626` | Stroke color for the DLQ trend sparkline. |
| `cachedb.admin.ui.chart.backgroundColor` | `#fbf7ef` | Shared SVG background color for built-in charts. |
| `cachedb.admin.ui.chart.axisColor` | `#c9baa2` | Shared axis/guide line color for built-in charts. |
| `cachedb.admin.ui.chart.mutedTextColor` | `#7b8794` | Muted annotation color used inside built-in charts. |
| `cachedb.admin.ui.chart.route.webhookColor` | `#9c3f2b` | Route trend color for `webhook`. |
| `cachedb.admin.ui.chart.route.queueColor` | `#2563eb` | Route trend color for `queue`. |
| `cachedb.admin.ui.chart.route.smtpColor` | `#0f766e` | Route trend color for `smtp`. |
| `cachedb.admin.ui.chart.route.deliveryDlqColor` | `#dc2626` | Route trend color for `delivery-dlq`. |
| `cachedb.admin.ui.chart.route.fallbackColor` | `#1d2525` | Fallback route trend color for unknown channels. |
| `cachedb.admin.ui.chart.severity.criticalColor` | `#dc2626` | Incident severity trend color for `critical`. |
| `cachedb.admin.ui.chart.severity.warningColor` | `#d97706` | Incident severity trend color for `warning`. |
| `cachedb.admin.ui.chart.severity.infoColor` | `#2563eb` | Incident severity trend color for `info`. |
| `cachedb.admin.ui.chart.churn.lineColor` | `#9c3f2b` | Runtime-profile churn line color. |
| `cachedb.admin.ui.chart.churn.dotColor` | `#1d2525` | Runtime-profile churn point color. |
| `cachedb.admin.ui.chart.churn.axisTextColor` | `#6d5e49` | Runtime-profile churn axis-label color. |
| `cachedb.admin.ui.chart.profile.aggressiveLabel` | `AGGRESSIVE` | Label shown for the aggressive profile axis line. |
| `cachedb.admin.ui.chart.profile.balancedLabel` | `BALANCED` | Label shown for the balanced profile axis line. |
| `cachedb.admin.ui.chart.profile.standardLabel` | `STANDARD` | Label shown for the standard profile axis line. |

## Benchmark Catalog Tuning

These properties let you replace the built-in production-test scenario catalogs.

| Property | Default | What it does |
| --- | --- | --- |
| `cachedb.prod.catalog.scenarios` | built-in base scenario catalog | Replaces the `ScenarioCatalog` list. |
| `cachedb.prod.catalog.fullScaleScenarios` | built-in 50k scenario catalog | Replaces the `FullScaleBenchmarkCatalog` list. |
| `cachedb.prod.catalog.representativeScenarioNames` | `campaign-push-spike-50k,weekend-browse-storm-50k,write-behind-backpressure-50k` | Replaces the representative benchmark scenario selection. |

Catalog format for `cachedb.prod.catalog.scenarios` and `cachedb.prod.catalog.fullScaleScenarios`:

```text
name;kind;description;targetTps;durationSeconds;workerThreads;customerCount;productCount;hotProductSetSize;browsePercent;productLookupPercent;cartWritePercent;inventoryReservePercent;checkoutPercent;customerTouchPercent;writeBehindWorkerThreads;writeBehindBatchSize;hotEntityLimit;pageSize;entityTtlSeconds;pageTtlSeconds
```

Multiple scenarios are separated with `|`.
