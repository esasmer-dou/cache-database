# Spring Cache Adapter

Opt-in synchronous Spring Cache in the existing provider-neutral starter.
Enable annotations explicitly with @EnableCaching. No entity binding or SQL
persistence is required for method values.

This adapter supports synchronous method values only. Both Cache.retrieve
overloads retain Spring's optional UnsupportedOperationException default.
Spring's CompletableFuture and reactive @Cacheable interception uses retrieve
(including sync=true); those annotated return types are unsupported and fail
before the source is invoked. Use the separate asynchronous/reactive repository
APIs without these method-cache annotations. No blocking retrieve shim is used.
See [Spring Cache](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/Cache.java)
and [annotation dispatch](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/interceptor/CacheAspectSupport.java).

## Configuration

~~~yaml
cachedb:
  spring-cache:
    enabled: true
    key-prefix: orders-prod
    max-concurrent-operations: 256
    max-in-flight-keys: 1024
    max-waiters: 4096
    load-timeout: 30s
    caches:
      prices:
        ttl: 5m
        tenant-scoped: true
        allow-null-values: false
        max-value-bytes: 1048576
        near-cache:
          max-entries: 10000
          max-bytes: 8388608
          ttl: 30s
          soft-ttl: 10s
~~~

Only declared caches exist: 1..256 names, no lazy/unbounded cache creation.
TTL is mandatory, positive whole milliseconds and at most seven days. Values
include a one-byte null/value envelope: default 1 MiB, hard maximum 16 MiB.
NearCachePolicy validates entry/byte/TTL/soft-TTL limits. Per manager, all L1
partitions together are limited to 64,000 entries / 64 MiB. Hard TTL is at most
60 seconds and cannot exceed Redis TTL; soft TTL is zero or below hard TTL.
Soft expiration follows the synchronous Redis path, never stale-while-refresh.
Only encoded strings are retained; each caller decodes separately.

Generation hints have independent hard caps: 256 scopes per cache and 4096
across the manager. Their local TTL is bounded by the L1 hard TTL (or at most
60 seconds/value TTL if L1 is disabled). LRU eviction discards the scope epoch
without scanning payload entries. Metadata overhead is also bounded; the L1 byte
estimate includes a conservative 768-byte entry allowance plus string storage.
The limits are accounting bounds, not measured JVM heap-size claims.

Sharing replicas must use compatible TTL/null/codec/key schemas. Use a new
prefix/cache name for incompatible migrations. Prefixes must be deployment/database-
specific and cannot contain Redis hash-tag braces. A cache-only application
without a DataSource must set cachedb.enabled=false and provide an explicitly
bounded RedisClientOperations bean; no second connection factory is introduced.

## Spring Wiring

cachedb.spring-cache.enabled=true enables this support. An application-provided
CacheManager makes the entire auto-configuration back off. Enable @EnableCaching
separately in the application.

Redis selection uses an existing RedisClientOperations first, then
CacheDatabase.redisOperations(), then the cacheDbJedisPooled bean. A
CacheDatabase supplies backgroundRedisOperations() for subscription; otherwise
the same RedisClientOperations is used. Resolve multiple candidates explicitly,
for example with @Primary. Clients are borrowed and are not closed by the manager.

The manager bean is named cacheDbCacheManager. The cacheDbKeyGenerator bean is
created only when no bean with that name exists. Supply a CacheDbCacheCodec<V>
bean for an explicit per-cache value codec. Metrics bind when a Micrometer
MeterRegistry exists. These beans do not replace SQL/entity operations or
application authorization.

## Annotation and Key Contract

~~~java
@EnableCaching
@Configuration(proxyBeanMethods = false)
class MethodCachingConfiguration {}

@Service
class Prices {
    @Cacheable(cacheNames = "prices", sync = true)
    public String read(String sku, int branch) {
        return queryPriceWithAnApplicationDeadline(sku, branch);
    }
    @CachePut(cacheNames = "prices")
    public String update(String sku, int branch) {
        return updatePriceAndReturnIt(sku, branch);
    }
    @CacheEvict(cacheNames = "prices", allEntries = true)
    public void clearCurrentTenant() {}
}
~~~

When enabled and no application CachingConfigurer exists, auto-configuration
installs CacheDbKeyGenerator through its own CachingConfigurer. It does
not override a user's configurer. In that case, explicitly use
@CacheConfig(keyGenerator = "cacheDbKeyGenerator"), the equivalent method
attribute, or return that bean from CachingConfigurer.keyGenerator().

Zero arguments produce native SimpleKey.EMPTY. One non-null scalar uses its
native typed identity; ordinary one-argument strings remain native. Reserved
ck1: strings are escaped into a disjoint single-string domain so they cannot
collide with generated composite strings. Multiple arguments (and single null)
use a typed, argument-counted, exact length-framed String. Method identity is
not added: equal arguments in the same cache deliberately share entries.

Direct Cache keys support primitive wrappers, strings, finite numbers, UUID,
bounded BigInteger/BigDecimal and SimpleKey.EMPTY. Different scalar types are
distinct. Arbitrary objects/arrays require an explicit String KeyGenerator.
Input is capped at 65,536 characters, composite arguments at 128, and numeric
input at 4096 bits. UTF-8/base64url encoding is at most 1024 characters; longer
encoded typed frames use h: plus SHA-256 of the complete typed input. Invalid
Unicode is rejected. There is no toString/hashCode identity shortcut or Java
object serialization.

**Literal SimpleKey deviation:** nonempty opaque Spring 6.1 SimpleKey exposes
no public parameter accessor. It is rejected with an actionable configurer/
KeyGenerator exception, not decoded using reflection or a lossy string key.
Ordinary multi-argument annotations work automatically via the supplied default
String generator. Existing user configurers must opt into it explicitly.
See [Spring SimpleKey](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/interceptor/SimpleKey.java).

## Zero-RTT L1 and Atomic Misses

A valid, non-soft-expired L1 hit performs **zero Redis I/O**, including no
generation/version lookup, client access, GET/PTTL/EVAL or per-hit health probe.
The generation hint and payload are local, bounded, and tied to the active
subscription session and scope epoch. Immutable admitted wire values are not
UTF-8-revalidated/copied into a byte array on each hit.

The opt-in key layout remains generation-addressed:

~~~text
<prefix>:scache:<cacheName>:{<sha256(typed-cache/tenant-scope)>}:generation
<prefix>:scache:<cacheName>:{<scope>}:state
<prefix>:scache:<cacheName>:{<scope>}:revision:<encodedKey>
<prefix>:scache:<cacheName>:{<scope>}:g<incarnation>-<generation>:<encodedKey>
~~~

A known-generation miss uses **one EVAL** that validates generation/incarnation,
obtains the per-key revision and payload, checks STRLEN/PTTL budgets and returns
one atomic snapshot. There is no separate state lookup plus payload lookup.
The single-flight owner does not repeat that Redis miss before calling the
source; its second check is local only. A cacheable source still needs its
separate conditional write after the source completes.

All actual Redis keys are declared in KEYS and share one Cluster slot. An
unknown/stale generation hint can require a bounded resync response and retry:
a fresh scope created by that EVAL normally completes in one call; joining an
existing scope without its hint, an expired hint or concurrent clears may need
another call. At most three attempts are allowed, then deterministic contention
failure. This cold/resync exception is explicit: one
RTT is not guaranteed for every cold-generation case or construct undeclared Redis keys in Lua.
See the [Redis EVAL key contract](https://redis.io/docs/latest/commands/eval/).

clear() is one O(1) Lua call: INCR generation plus PUBLISH atomically, without a
generation prefetch, KEYS/SCAN or entry deletion. Old payloads retain their TTL.
Local/peer scope epoch replacement makes old L1 entries unreachable in O(1);
new admissions drain at most 16 stale entries and enforce the LRU byte/entry caps.
TTL forbids serving expired data; there is no background heap-cleanup thread,
so idle stale L1 storage may remain reserved until activity/transport flush. CLEAR/reset notifications carry the new
incarnation/counter and re-prime only already-known scope hints; notifications
cannot allocate an unlimited tenant catalog. invalidate() returns false because
prior population cannot be known without scanning.

Scope metadata and key revision tombstones use idle TTL = value TTL + 60 seconds.
Missing generation/state creates a fresh random incarnation, never an old
generation-zero namespace. Missing key version gets a new token. Fencing tokens
are identity tokens, not credentials; generation uses no SecureRandom device
I/O on virtual-thread request paths.

## Pub/Sub, Epochs and Failure Semantics

The channel is <prefix>:scache:invalidation with bounded sc1 KEY/CLEAR
messages. Successful writes/evicts/load publication and their KEY event execute
atomically in one Lua script. Clear/reset publishes the new generation. A key
event invalidates exactly that logical entry; a scope clear invalidates the
scope and its generation hint/epoch. Writes also invalidate local entries before
and after the script, including ambiguous failures.

A fetch ticket captures session identity, scope identity and a bounded striped
entry epoch **before** server fetch. Admission checks them together under the
same in-memory partition lock used by invalidation. A key notification, clear,
scope eviction, disconnect or reconnect therefore rejects a late old fill.
Stripe collisions only reject extra admissions; they never identify values.
No source/Redis/future/socket wait occurs under these ReentrantLocks.

On subscription loss, local values and generation hints are flushed and local
hits/admission remain disabled. Successful subscribe ACK flushes again and
opens a new session; old tickets cannot become valid after reconnect. While
disconnected, reads use bounded atomic Redis fallback without local admission.
Redis-operation failure also flushes/disables L1 and interrupts the owned
listener to require a fresh ACK. If fallback fails, the exception propagates
without invoking a source based on an unverified miss.

**Consistency boundary:** publication is not peer processing acknowledgement.
Peer visibility follows notification processing, not immediate remote write
return. Pub/sub is at-most-once: an unknown network partition cannot be detected
instantaneously. This implementation protects detected disconnect gaps by
flush/reconnect fencing; it cannot replay dropped messages, certify arbitrary
out-of-band Redis writes/eviction/failover, or promise linearizable peer reads
without synchronous validation. TTL limits stale residence if an unsupported
external mutation or undetected failure occurs. Applications requiring stronger
guarantees must disable L1. Automatic CLIENT TRACKING transport is not supplied.
These limits are intrinsic to [Redis Pub/Sub delivery](https://redis.io/docs/latest/develop/pubsub/), not hidden retries.

## Loading, Tenants, Codecs and Bounds

get(key, Callable) / @Cacheable(sync = true) use the existing bounded
SingleFlightLoader. Managers sharing the exact RedisClientOperations instance
and prefix share its JVM/classloader flight group. Different client instances
are isolated; backend identity is not guessed. Cache definitions and flight
limits must match. Explicit codecs must be the same shared bean instances,
not merely the same classes: constructor options can change wire schemas.
Default scalar codecs are compatible when cache definitions match.
JVM registry caps remain 256 groups, 1024 leases,
65,536 reserved keys and 1,048,576 reserved waiters. Last idle close releases a
group; active orphan flights are never evicted to permit duplicate source work.
There is no distributed single-flight claim.

A logical call is registered before its Redis read. This lets an already
registered caller with a delayed miss reuse a concurrent completed load rather
than invoking the source twice. The immutable wire result is retained only
until the last overlapping registration leaves; calls registered after that
completion cannot consume it. Generation/revision identities still separate
real mutations. At most one completion is retained per active logical key;
the JVM-wide completion budget is 64 MiB (256 bytes plus twice the wire and
state-key character counts per retained result). Budget exhaustion rejects a
delayed caller with an explicit retry-lookup error instead of duplicating source
work. There is no timer, completed-result cache or additional Redis recheck.
Registration is capped by max-in-flight-keys (hard 4096) and max-waiters
(hard 65,536); programmatic SingleFlightConfig.maxResultCharacters is enforced
before publication and cannot exceed 16 MiB. Character limits do not replace
the per-cache UTF-8 value-byte limit.

Flights include tenant, incarnation, generation, key and key revision. A
conditional LOAD rejects put/evict/clear races, including evict of an absent key;
unrelated keys do not fence the loader. No source is blindly retried. Owner
Callable runs on the caller's platform/virtual thread without a monitor, with
explicit context wrappers. Followers use bounded future waits and retain
interruption. load-timeout bounds followers only; the source must enforce its
own JDBC/HTTP deadline. A stuck source retains bounded permits/flight state.

tenantScoped=true requires currentTenant() for every operation, including
clear/invalidate. Administrative scope alone is not a bypass; the tenant enters
the typed scope digest, never raw hash tags. Global caches are deliberately
global and must not contain tenant-sensitive values. A nonempty entity causal
context fails explicitly before L1/Redis access. No automatic entity RYOW,
transaction rollback, SQL/source freshness or failover durability is invented.

**Annotation boundary:** @CachePut runs the source method before writing the
cache; @CacheEvict defaults to eviction after a successful method return.
Cache-level tenant/causal rejection cannot undo those source side effects.
Validate source authorization and context at method entry. beforeInvocation=true
evicts before invocation but does not make source work atomic. Conditions or
calls bypassing the proxy may skip cache access, so cache guards do not secure
such source calls. See [Spring CachePut](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/annotation/CachePut.java)
and [Spring CacheEvict](https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-context/src/main/java/org/springframework/cache/annotation/CacheEvict.java).

~~~java
try (var tenant = CacheTenantContext.withTenant("tenant-a")) {
    prices.read("SKU-42", 3);
}
~~~

Use explicit CacheTenantContext.wrap and CacheCausalContext.wrap when crossing
thread boundaries. ThreadLocal context is not propagated automatically.

The default JSON codec uses existing Jackson token APIs, not ObjectMapper
databinding/default typing/reflection/Class.forName. Finite scalar values keep
their Java types. POJOs/collections require one explicit CacheDbCacheCodec<V>
binding to the existing CacheValueCodec<V> SPI. Generated StreamingJsonCodec
implementations fit directly; applications using them must explicitly depend
on cachedb-json. Custom codecs must
be thread-safe, reflection-free, deterministic and internally allocation/depth
bounded. The adapter bounds returned UTF-8 without allocating a byte copy; Lua
bounds stored values before GET. Oversized values are rejected, not stored.

Null has its own envelope and differs from a miss. With nulls disabled, explicit
put(null) fails; a null Callable result is shared but not cached. Each caller
decodes separately, never shares a mutable decoded cache graph.

Operation admission is a no-queue Semaphore (default 256, hard 4096), including
codec work. Overflow rejects instead of blocking a burst indefinitely. Size it
against CPU/container memory/Redis capacity, not concurrent user count. TTL
bounds Redis lifetime, not bytes under unlimited new-key arrival; configure
Redis maxmemory and upstream rate/key limits. Do not swallow tenant/codec/causal/
fence exceptions with a permissive CacheErrorHandler.

Clients are borrowed, never closed. Each manager owns at most one virtual
subscription thread and one bounded one-shot shutdown helper, no executor,
refresh queue or virtual-thread pool. Disabled L1 starts subscription lazily on
first Redis operation to cache generation hints; enabled L1 starts on complete
registration. This behavior is independent of spring.threads.virtual.enabled;
the caller's thread type is unchanged. Reserve a subscription connection and finite acquisition/read/
connect timeouts in the borrowed client, including disconnect detection.
After 32 consecutive unacknowledged subscription attempts (100ms..5s backoff),
L1 stays disabled; recreate the manager operationally. Shutdown waits at most
two seconds; transport cleanup still depends on the borrowed client's finite
timeouts. No hidden heartbeat RTT is charged to the hit path.

Statistics/Micrometer expose hits, near.hits, misses, puts, evictions, clears,
failures, fenced.loads, rejected, inflight, waiters, invalidation.connected,
late.fills.rejected, notifications, disconnects and generation.scopes. Only fixed
cache names are labels; no tenant/key/value/cardinality explosion.

## Metadata Lifecycle and Tenant Erasure

For a cache with fixed Redis TTL T (at most seven days), generation and state
have an idle lease of T + 60 seconds, not persistent lifetime. The per-key
revision/tombstone uses the same lease. Every Redis READ/PUT/NX/LOAD/CHECK/EVICT
renews generation/state inside that operation's Lua evaluation; clear renews
them in its INCR/PUBLISH evaluation. Revision leases renew on key access.
A genuine local L1 hit does not renew any Redis lease or perform network I/O.

A newly created state is one 36-character incarnation String written with
SET PX, before resetting the generation counter. Generation, revisions and
payloads are also created with expiry in the same creation command. Thus an
ACL/runtime error before a later PEXPIRE cannot leave a newly created
persistent state. Lua errors are not rollback; ambiguous failures still
propagate and disable local admission. All sharing replicas must use the same
TTL/schema. State uses a String layout. Never mix incompatible metadata
layouts under one prefix; use a fresh prefix for an incompatible migration.

The last successful payload write sets lifetime at most T, while its scope
lease lasts T + 60 seconds. An admitted L1 lease is no longer than the payload's
remaining TTL and 60 seconds. A scope therefore cannot expire naturally before
its payloads and L1 leases under this contract. On loss of either scope key,
a fresh incarnation is installed BEFORE the counter resets; counter zero never
addresses an earlier incarnation. Never delete/reset generation metadata as
a tenant cleanup shortcut, and do not change TTLs in place across replicas.

Idle tenants and key revisions eventually expire, but TTL is not an absolute
Redis key/memory cap with unlimited new-tenant/key arrival. Two scope keys per
recent tenant/cache, recent revisions and unexpired old-generation payloads
consume capacity. At bounded arrival rates their residence is bounded by T +
60 seconds (payloads by T); Redis expiration is logical, not an immediate
physical memory-reclamation deadline. Configure Redis maxmemory, monitor key/
memory growth and expiry, and enforce tenant/key creation admission upstream.
The adapter's L1/hint limits do not enforce a distributed Redis tenant quota.

Entity-class tenant purge does not know arbitrary Spring Cache prefixes,
key schemas, ownership or old generations. It does NOT erase all PII stored
in generic method caches. Such caches require explicit application enrollment
in a separately coordinated purge participant; no automatic participant API
or discovery is supplied here. Authenticate the scope, fence concurrent work,
and include every declared cache/replica and retained generation in the erasure
contract. A scoped clear can provide logical invalidation when that is the
chosen retention policy, but old values remain until TTL and peer visibility
is notification-driven. Clear is not proof of immediate physical erasure or
universal tenant-wide deletion. Global caches must not contain tenant PII
unless an explicit ownership and erasure contract is implemented.
