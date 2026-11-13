# Server-Assisted Near-Cache Invalidation

Near-cache invalidation has three modes. It is asynchronous cache coherence, not a linearizable read-after-write guarantee. Redis reads, hard TTL, causal/version checks, tenant checks and payload limits remain authoritative.

| Configured mode | Usable invalidation channel |
| --- | --- |
| `PUBSUB` | The existing application invalidation channel; raw writers without PUBLISH are invisible. |
| `SERVER_TRACKING` | RESP2 BCAST tracking on every master; L1 is disabled until all subscriptions and control heartbeats are healthy. No automatic PUBSUB fallback. |
| `AUTO` (default) | Try complete server tracking; otherwise use healthy PUBSUB with one warning per coordinator. Partial tracking is never admitted. Healthy PUBSUB is retained until an actual connection, health, namespace, or topology change requires requalification. |

`invalidationMode()` reports the last successfully negotiated channel, initially PUBSUB. Always read `connected()` as well: a mode alone is not evidence of a usable L1. `trackingReady()` and `trackingReadySupplier()` require healthy, complete server tracking, not just a configured SERVER_TRACKING/AUTO value.

## Configuration And Write Compatibility

`NearCacheInvalidationConfig` is a separate immutable configuration record; it does not change `NearCachePolicy`'s constructor or record shape.

```java
import com.reactor.cachedb.core.config.NearCacheInvalidationConfig;
import com.reactor.cachedb.core.config.NearCacheInvalidationMode;
import com.reactor.cachedb.redis.RedisNearCacheCoordinator;

var settings = NearCacheInvalidationConfig.defaults()
        .withMode(NearCacheInvalidationMode.AUTO);
var coordinator = new RedisNearCacheCoordinator(redisOperations, keyStrategy, settings);
```

The two-argument coordinator constructor uses these defaults. Configuration adapters must map `cachedb.near-cache.invalidation-mode` to `mode` and `cachedb.near-cache.publish-legacy-invalidations` to `publishLegacyInvalidations`. The record itself does not bind Spring properties.

Legacy publication defaults to true for mixed fleets. **The configuration constructor rejects legacy=false with AUTO or PUBSUB.** Legacy opt-out is valid only with explicit SERVER_TRACKING and an all-consumer upgrade contract: every L1 consumer of the keyspace must use SERVER_TRACKING without a PUBSUB fallback. Merely supporting tracking or being healthy at rollout time is insufficient.

| Configuration | Result |
| --- | --- |
| AUTO + legacy=true | Allowed; tracking failure may use healthy PUBSUB. |
| AUTO + legacy=false | Rejected at configuration construction. |
| PUBSUB + legacy=false | Rejected at configuration construction. |
| SERVER_TRACKING + legacy=false | Allowed only under the all-consumer contract; tracking failure keeps L1 disabled, never PUBSUB. |

If one pod falls back to PUBSUB while tracking-active peers omit PUBLISH, that pod can retain stale L1 data. A writer's own tracking-ready check cannot establish another pod's invalidation readiness. This is why AUTO opt-out is rejected rather than relying only on a mapper supplier.

The explicit opt-out settings are:

```properties
cachedb.near-cache.invalidation-mode=SERVER_TRACKING
cachedb.near-cache.publish-legacy-invalidations=false
```

Both settings must reach the same validated NearCacheInvalidationConfig passed to the three-argument coordinator constructor. Do not bind legacy=false only in the mapper while leaving the coordinator at AUTO defaults.

Write adapters must consult `coordinator::publishLegacyInvalidations` at argument-construction time. It is false only when the validated flag is false AND complete tracking is currently healthy. Tracking failure forces this supplier true while L1 remains disabled. Setting the record alone does not remove PUBLISH from an existing write mapper. Do not remove positional wire arguments or shift tenant marker offsets when omitting invalidation arguments.

| Setting | Default | Valid range / meaning |
| --- | --- | --- |
| maxMasters | 16 | 1..16 target masters |
| maxNodes | 64 | maxMasters..64 known node pools, including replicas |
| maxNamespaces | 256 | 1..256 enabled namespaces |
| ioTimeoutMillis | 500 | 50..2000; socket reads after connection creation |
| heartbeatMillis | 100 | 25..1000; at most half ioTimeoutMillis |
| healthTimeoutMillis | 1000 | ioTimeoutMillis + heartbeatMillis .. 10000; L1 admission lease |
| maxAttempts | 5 | 1..8 attempts per retry burst |
| initialBackoffMillis | 100 | 100..1000 |
| maxBackoffMillis | 5000 | initialBackoffMillis..5000 |
| retryCooldownMillis | 30000 | 1000..60000; cooldown between failed retry bursts, not a healthy fallback lifetime |

Retries continue in bounded bursts, not as an unbounded work queue. A healthy AUTO fallback is not disconnected by a periodic tracking retry. After an ACL-only capability change with no disconnect or topology event, call `topologyChanged()` or restart the runtime to requalify tracking; this avoids periodic L1 clears and extra probe sockets. A namespace registration or explicit topology notification changes the generation. Registering a namespace clears existing L1 entries and requires complete requalification. Excess configured tracking-prefix budgets cause AUTO to use PUBSUB; the coordinator's separate 256-store / 64000-entry / 64-MiB allocation limits still reject registrations beyond their own bounds.

## Connections And Protocol

Each master owns a fresh subscription connection S and a separate fresh control connection C. The adapters expose the pool's existing factory through `RedisTrackingConnectionProvider`; `RedisTrackingNode.openDedicated(int)` calls `pool.getFactory().makeObject()`, never `getResource()` or `borrowObject()`. Closing these connections destroys the factory objects instead of returning them to the shared pool.

This preserves the configured credentials, TLS socket factory, hostname verification, endpoint mapping and selected database. RESP3 and Jedis' own CacheConnection are rejected; this implementation owns RESP2 tracking independently. A custom Redis adapter must implement the dedicated-connection provider SPI, otherwise L1 remains unavailable rather than reserving an unknown shared connection.

For each master the setup is:

```text
S: CLIENT ID
S: SUBSCRIBE __redis__:invalidate
C: CLIENT ID
C: CLIENT TRACKING ON REDIRECT <S-id> BCAST
     PREFIX <entityKeyPrefix(namespace1)> PREFIX <entityKeyPrefix(namespace2)> ...
```

S and C must have different client IDs. All enabled namespaces are registered on every master, even if one namespace currently belongs to only one slot. There is no GET-per-key tracking registration and no NOLOOP option.

The subscription loop uses finite-timeout RESP2 reads from the initial SUBSCRIBE acknowledgement onward. It does not call `JedisPubSubBase.proceed` or `Connection.setTimeoutInfinite`: in Jedis 5.2 the latter uses the configured blocking socket timeout, which need not be infinite. Each S heartbeat writes a unique PING and processes invalidations until its matching PONG, then pauses for `heartbeatMillis`. No application write is needed to keep an idle subscription alive; invalidations arriving after PONG wait for the next bounded heartbeat round. It follows the Jedis 5.2 payload semantics: a list is flattened into individual key invalidations; a null payload clears every L1 store. Unknown entity prefixes are ignored. Malformed protocol input fails the session and disables admission; no invalidation is silently dropped from an application queue.

`RedisKeyStrategy.entityKeyPrefix(String)` delegates to the actual `entityKey(namespace, "")`, preserving cluster hash tags and custom layouts. `entityKeyPrefixes(Collection<String>)` compiles immutable namespace-to-prefix mappings once per generation. `parseEntityKey(String, Map<String,String>)` reuses them without rebuilding all prefix strings per event. IDs containing colons remain intact. Custom strategies must use stable, non-overlapping entity prefixes and should keep parsing memory-only; slow callback I/O can cause heartbeat failure.

## Failure And Topology Safety

Every heartbeat sends a unique PING on S and validates its matching PONG. C independently checks PING and CLIENT ID. ROLE and, for clusters, CLUSTER NODES are checked at approximately one-second intervals. S and C have independent health deadlines, and readiness uses the earlier deadline. A successful C heartbeat alone cannot extend readiness when S is blackholed. An expired lease cannot be revived by a late PONG or a delayed supervisor; that generation must be flushed and replaced.

Disconnect, malformed input, missed heartbeat, role change, topology disagreement or generation replacement disables admission and clears all stores before readiness can be restored. Reconnection creates new S/C IDs. A deadline check on the L1 admission path also clears stores when the health lease has expired, even if the supervisor is delayed. Existing stale values may be observable during the bounded detection window; this is not synchronous invalidation.

Cluster qualification compares the complete advertised master set with the discovered master identities and requires consistent role/slot/configuration-epoch views. The known-pool map is also checked for changes. Slot migration annotations or incomplete consensus prevent tracking admission. On a handled MOVED or slot-cache refresh, the Redis integration must call `coordinator.topologyChanged()`; do not wait for the periodic check when the event is known.

The adapter does not invent unauthenticated node connections from CLUSTER NODES addresses. If the existing Jedis pool map lacks an advertised master, tracking stays unavailable until Jedis discovers a pool with the correct configuration. Discovery may conservatively fail if an existing node pool is unreachable.

At the hard master limit, a runtime uses at most 32 retained tracking sockets, one temporary node-probe socket, one supervisor virtual thread and 32 reader/controller virtual threads. PUBSUB uses two dedicated sockets and two workers plus the supervisor. A failed generation is retired before another opens sockets. There is no worker or queue per invalidated key. Default heartbeat load is approximately 30 commands/second/master plus two topology commands/second/master in clusters; multiply this by runtime count when sizing Redis CPU/network budgets. Namespace prefixes total at most 1 MiB UTF-8; topology text is limited to 128 KiB; a decoded invalidation list to 64000 keys. Identity limits are 4096 characters for an ID and 8192 for the complete parsed key.

The supervisor owns each C socket during discovery. A memory-only handoff publishes and starts its existing C worker atomically with session cancellation; that worker performs CLIENT ID, creates S, obtains S's ID and enables tracking. The C worker owns S until an equivalent handoff starts the reader. Each handoff rechecks closure, cancellation and generation. Stop only signals cancellation and interrupts the published owners; it never calls Jedis close/disconnect or flush from another thread. A late factory/command result is destroyed by its current owner without opening the next socket or starting a reader/heartbeat. No timeout helper, detached continuation or reconnect task is left to resume after close.

Close forecloses readiness and new handoffs before cancellation, then joins the supervisor and every published S/C owner using one shared two-second budget. Successful close means those owners have exited and completed factory destruction. Interrupted callers still wait within that same budget and retain their interrupt status. If an owner violates the budget, close throws and records `DEDICATED_CONNECTION_STOP_TIMEOUT`; it does not report successful cleanup or admit a replacement generation. The original factory's connect/auth/TLS timeouts remain in force during `makeObject()`; the invalidation I/O timeout applies after creation. Configure finite original timeouts. The supported factory and credential/TLS/mapper/destruction callbacks must honor virtual-thread interruption and deadlines; arbitrary uninterruptible custom/native callbacks are unsupported. Java cannot safely force such a callback to release its private socket, and offloading it to a timeout thread would hide rather than fix the leak.

A dedicated-connection cleanup failure disables this coordinator permanently; replace it after correcting the cause. Jedis decodes RESP allocations before application size checks; these checks are not a hostile-RESP memory firewall. Use trusted Redis endpoints, server output-buffer limits and bounded cluster size.

## Late-Fill Protection

`Store.Stamp(long clearEpoch, long segmentEpoch)` separates whole-store clears from per-segment invalidations. Capture the stamp for the exact ID before the backing Redis read, then pass it to a new put overload:

```java
var stamp = store.capture(id);
var reply = readRedisOutsideAnyStoreLock(id);
if (coordinator.connected()) {
    store.put(id, reply.payload(), reply.decoded(), reply.ttlMillis(), stamp, reply.version());
}
```

For multi-ID reads using Stamp overloads, capture one stamp per ID before that ID's backing read; one ID's segment stamp cannot represent the entire batch. If a query/window discovers IDs only in the backing response, retain the legacy global epoch captured before that read and use the legacy put overload. Capturing per-ID stamps after the response is unsafe. The global API is a conservative safe option for such reads. Check readiness before and after an L1 hit as well. A miss must still honor actual Redis TTL, tombstones, tenant visibility, causal minimum version and payload admission budgets.

Invalidating any ID increments its segment counter under that segment's memory-only lock. A late put using an older segment stamp is rejected. An unrelated segment does not reject the put. A whole-store clear advances clearEpoch so every old stamp is rejected. The existing `epoch()` and all `put(..., long observedEpoch[, long version])` overloads remain available with their original global-epoch semantics for binary compatibility. Stamp counters do not replace existing version/TTL guards.

No Redis control I/O runs under Store monitors, the lifecycle lock or the readiness gate. From creation through discovery, registration, steady state and destruction, every socket has exactly one I/O owner at a time. S has one reader after its handoff; C's worker owns all registration/heartbeat command reads, writes and close after its handoff. This deliberately avoids claiming general Jedis Connection thread safety: `sendCommand` may attempt an input read on a write error. No S read/write concurrency, socket I/O lock or intrinsic monitor is required.

## Operations And Qualification

Expose `invalidationsReceived()`, `flushAllEvents()`, `trackingReconnects()`, actual mode and readiness separately. A mode gauge may map PUBSUB=0 and SERVER_TRACKING=1. `invalidationFailure()` retains a bounded failure category; AUTO preserves its qualification failure while PUBSUB is healthy. `trackingClientIds()` returns diagnostic node -> [S-id,C-id] snapshots only while tracking is healthy; never turn these IDs into metric labels. A reconnect counter records successful tracking installations after the first one.

Redis ACLs must permit the required CLIENT ID / TRACKING, SUBSCRIBE to the invalidation channel, PING, ROLE and cluster inspection commands, plus the existing application channel in PUBSUB mode. CLIENT KILL is a fault-test privilege, not a production runtime requirement. Tracking is database-agnostic: identical key prefixes in different logical databases may cause safe extra invalidations, not cross-database payload reads.

Qualification suites are `RedisNearCacheStampTest`, `RedisServerTrackingTest`, `RedisServerTrackingClusterTest`, `RedisTrackingBlackholeTest`, `RedisTrackingIdleHeartbeatTest`, `RedisTrackingHeartbeatLeaseTest`, `RedisTrackingStartupCancellationTest`, and `RedisTrackingStartupSocketCancellationTest`. Standalone and blackhole suites are enabled by `cachedb.snapshot.redis=true`; the cluster suite uses `cachedb.redis.cluster=true`. Standalone fixtures use the existing Redis test endpoint or `cachedb.tracking.redis.uri` for authentication/TLS/nonzero-database qualification. Blackhole fixtures require a disposable plaintext Redis endpoint and keep real TCP sockets open while discarding packets in either S or C separately. Cluster tests require at least three real masters and cover every master. The idle suite holds real S/C sockets for 32 seconds in SERVER_TRACKING, PUBSUB and injected tracking-denied AUTO fallback, with no business writes. It asserts no readiness gaps, flushes, socket replacement, pool borrowing or concurrent same-socket Jedis I/O. The lease suite covers deterministic expiry and nanoTime wrap. The startup suite pauses factory creation, ROLE, C/S CLIENT ID, SUBSCRIBE and TRACKING acknowledgements, including successful late replies after cancellation. It asserts owner-only destruction, no later socket/reader/heartbeat, no surviving owner and bounded interrupted-caller cleanup. The loopback RESP suite withholds a real AUTH or TRACKING reply and checks peer EOF plus completed factory destruction after close; it needs no Redis service and is not Redis/TLS/Cluster acceptance. These suites do not replace the independent S/C blackhole tests.

Acceptance also requires actual repository call-site integration, auth/TLS/database matrices, role/failover/MOVED fault injection, and a 1000-write CacheDB run with legacy publication disabled. Check the difference in INFO commandstats publish calls over that isolated run; direct raw-writer tests alone do not establish zero-PUBLISH write-path behavior. Retain mixed-fleet compatibility until this qualification is complete.

Protocol references: [Redis client-side caching](https://redis.io/docs/latest/develop/reference/client-side-caching/), [Jedis 5.2 subscription decoding](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/JedisPubSubBase.java), [Jedis 5.2 connection timeout and I/O](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/Connection.java), [Jedis 5.2 connection factory](https://github.com/redis/jedis/blob/v5.2.0/src/main/java/redis/clients/jedis/ConnectionFactory.java), [Java 21 socket interruption](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html).
