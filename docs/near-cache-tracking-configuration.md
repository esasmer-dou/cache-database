# Near-cache Tracking Configuration

Near-cache storage remains opt-in through `NearCachePolicy`. Invalidation settings are runtime-wide
and shared by every repository, foreground writer, background writer, and atomic write batch.

## Configuration

```java
var invalidation = NearCacheInvalidationConfig.builder()
        .mode(NearCacheInvalidationMode.AUTO)
        .publishLegacyInvalidations(true)
        .maxMasters(16).maxNodes(64).maxNamespaces(256)
        .ioTimeoutMillis(500).heartbeatMillis(100).healthTimeoutMillis(1000)
        .maxAttempts(5).initialBackoffMillis(100).maxBackoffMillis(5000)
        .retryCooldownMillis(30000)
        .build();
var options = CacheRuntimeOptions.builder().nearCacheInvalidation(invalidation).build();
var database = new CacheDatabase(redisOperations, dataSource, baseConfig, options);
```

The same values bind under `cachedb.near-cache` in Spring Boot:

```yaml
cachedb:
  near-cache:
    invalidation-mode: AUTO
    publish-legacy-invalidations: true
    max-masters: 16
    max-nodes: 64
    max-namespaces: 256
    io-timeout-millis: 500
    heartbeat-millis: 100
    health-timeout-millis: 1000
    max-attempts: 5
    initial-backoff-millis: 100
    max-backoff-millis: 5000
    retry-cooldown-millis: 30000
```

Invalid combinations fail before a connection is created. Maximum masters/nodes/namespaces are
16/64/256; attempts are at most 8. Socket timeouts are 50..2000 ms; heartbeat is 25..1000 ms and
at most half the socket timeout. Health timeout is at least socket timeout plus heartbeat and at
most 10000 ms. Initial backoff is 100..1000 ms; maximum backoff is no smaller and at most 5000 ms.
Retry cooldown is 1000..60000 ms. Bounds apply even in PUBSUB mode.

Runtime feature options use the separate immutable ordinary CacheRuntimeOptions class.
CacheDatabaseConfig remains the original 21-component record; its deconstruction and equality shape
are unchanged. Bootstrap supports runtimeOptions(options) and customizeRuntime(builder -> ...).
Spring CacheRuntimeOptionsCustomizer beans run after runtime property binding; existing
CacheDatabaseConfigCustomizer beans still configure only the base record.

## Mixed-fleet Safety

The default `AUTO + publish-legacy-invalidations=true` supports a mixture of tracking-capable and
PUBSUB-fallback consumers. Every writer continues to publish legacy invalidations. A writer's local
tracking readiness alone says nothing about another pod's invalidation mode.

`publish-legacy-invalidations=false` is accepted **only** with explicit `SERVER_TRACKING`.
Before using it, upgrade **all L1 consumers of the shared keyspace** to SERVER_TRACKING without
PUBSUB fallback, or disable their L1. This is an explicit deployment contract, not fleet discovery.
AUTO+false and PUBSUB+false are rejected, including builder round trips and property binding.

In strict mode a tracking failure disables and flushes L1; it never selects PUBSUB fallback.
Publication is suppressed only while every required master is tracking-ready. Connection loss,
health expiry, a known MOVED/ASK, or a topology notification immediately restores legacy publication
and invalidates local admission. A fresh, fully qualified generation is required to admit L1 again.

## Read Consistency

Known-ID point reads, refreshes, and bulk reads capture a `Store.Stamp(clearEpoch, segmentEpoch)`
before backing payload I/O. Bulk stamps are bounded to the existing 512-ID batch. Invalidations in
the same segment or any clear reject a late fill; another segment does not unnecessarily reject it.
Valid synchronous L1 hits allocate no new stamp.

Query/window reads cannot know result IDs before I/O. They retain the pre-I/O global epoch guard.
The old Store epoch/put methods remain available with their original global semantics. Tenant,
causal-version, atomic-unit-of-work, tombstone, hard-TTL, and hot-admission rules are unchanged.
Application codecs, predicates, listeners, and custom clients may perform I/O and still need review.

## Observability

Shared point-read runtime meters use the `cachedb.redis.read.coalescing.` prefix:
FunctionCounters `batches`, `coalesced.requests`, `bypassed`, `bypass.disabled`,
`bypass.causal`, `bypass.unitofwork`, and Gauge `average.batch.size`.
Total bypass includes raw transport bypasses and the exclusive disabled/causal/UOW policy reasons.
These meters are runtime-global, with no ID, tenant, repository, or instance UUID tags. They read
the same live shared state across repository/fetch-plan copies and require no storage call.

`CacheDatabase.nearCacheInvalidationMode()` returns the last negotiated mode, not health.
`nearCacheTrackingReadySupplier()` is live; `publishLegacyNearInvalidations()` returns the effective
publication decision. `nearCacheCoordinator()` is borrowed: CacheDatabase owns its lifecycle.

Micrometer exposes memory-only `cachedb.nearcache.ready`, `tracking.ready`, `legacy.publish`,
`invalidation.mode{mode}`, and counters `invalidations`, `flushes`, `reconnects`, all with the same
`cachedb.nearcache.` prefix. Mode labels are the three enum values; keys, IDs, tenants, and nodes are
never labels. A ready=0 gauge is authoritative even when the last negotiated mode still says
SERVER_TRACKING. Scrapes issue no Redis or SQL command.

Custom Redis clients must implement `RedisTrackingConnectionProvider` to provide bounded dedicated
connection factories; unsupported clients keep L1 disabled. Optional `RedisTopologyChangeSource`
notifications must be bounded and memory-only. A callback flushes L1; the supervisor, not the caller,
performs reconnect/control I/O. Surfaced MOVED/ASK errors retain their original exception and are not
replayed by the notification hook. The point-read transport retains Jedis' configured MOVED/ASK
routing and slot-map renewal. Internally recovered redirects are also covered by periodic
role/topology checks. No additional probe or hidden command is added to a normal point read.

See [server-assisted invalidation](server-assisted-invalidation.md) for dedicated connections,
ACLs, all-master qualification, and reconnect behavior.
