# Composite Range Indexes

A composite index combines equality predicates on a prefix with an optional sort column.
An index without `sortBy` keeps its existing equality layout and exact signature.
The equality prefix accepts 2..4 fields without `sortBy`, or 1..4 fields when a distinct
sort column completes the index. For example, `columns = {"tenantId"}, sortBy = "createdAt"`
supports a tenant timeline without requiring an artificial second equality field.

## Declare The Index

```java
@CacheEntity(table = "orders", redisNamespace = "orders",
        indexedColumns = {"tenantId", "status", "priority"})
@CacheCompositeIndex(name = "tenant_status",
        columns = {"tenantId", "status"}, sortBy = "priority")
public record Order(
        @CacheId(column = "order_id") Long id,
        @CacheTenantId @CacheColumn("tenant_id") String tenantId,
        @CacheColumn("status") String status,
        @CacheColumn("priority") Integer priority) {}
```

The processor resolves Java field names and persisted aliases in index declarations.
The sort field must be persisted, outside the equality prefix and supported by the existing
Redis score conversion. Prefix, ID and sort fields are automatically indexed. Unsupported
custom codecs and invalid declarations fail compilation.

Storage declarations accept byte/short/int/long/float/double and their wrappers, Instant,
LocalDate, LocalDateTime and OffsetDateTime. Storage support does not prove that a double
score preserves logical order.

## Ordinary Queries Preserve Logical Types

```java
QuerySpec query = QuerySpec.allOf(
        QueryFilter.eq("tenant_id", tenantId),
        QueryFilter.eq("status", "OPEN"),
        QueryFilter.gte("priority", 10),
        QueryFilter.lt("priority", 100))
    .orderBy(QuerySort.asc("priority"))
    .limitTo(50);
List<Order> orders = repository.query(query);
```

Eligible single-score or unordered Redis-only queries use the bounded sorted window.
Ties in an ordinary score-only query are unspecified, not a stable ID-pagination contract.
An explicit additional ID sort retains its logical Java comparator. Typed keyset OR branches
use only equality predicates common to every branch for candidate selection; residuals remain typed.

Long values beyond 2^53, nearby decimal values, signed floating-point zero, sub-millisecond
timestamps and offset date-times cannot safely use a Redis double as a logical comparator.
Byte, short, int and LocalDate qualify directly. Ordinary Long score-window queries may qualify
through the separate namespace/index Long53 attestation below.
Other supported storage types use complete equality-prefix candidates followed by exact
payload predicates and logical sorting. No exclusive rounded score bound is used there.
BigDecimal is not a supported `sortBy` storage type.

## Long Epoch-Millisecond Timelines

A Long sort column can accelerate ordinary single-score queries and explicitly opted-in keyset
windows after a cooperative
fenced rebuild verifies every resident live value is in [-9007199254740991, 9007199254740991].
This covers realistic createdAtEpochMillis timelines without declaring the whole Java Long domain
safe. The field must still use Long values; Double/BigDecimal conversion is not an attestation.
Instant with nanosecond precision is not eligible through this contract.

~~~java
QuerySpec latest = QuerySpec.allOf(
        QueryFilter.eq("tenant_id", tenantId),
        QueryFilter.eq("status", "OPEN"),
        QueryFilter.gt("created_at_epoch_millis", afterEpochMillis))
    .orderBy(QuerySort.desc("created_at_epoch_millis"))
    .limitTo(50);
~~~

All writers must implement this domain-validation protocol, including warm/read-through/cache
insertion paths, before enabling attestation. Older writers that only participate in migration
locking are insufficient. Validate values before payload mutation while the writer is registered.
The first unsafe Long poisons one persistent flag per index; it does not reject the business write.
Poisoning and writer release share the existing atomic Lua call. A fast reader checks the verified
flag together with lock, active-writer count, degradation, signature and index coverage in the SAME
range Lua. There is no extra attestation round trip, per-ID domain registry or expiring proof.

A missing, unsafe or invalid flag discards partial fast-path work and uses complete equality-prefix
candidates plus the original logical predicates/sort, subject to the existing complete-fallback
budgets. Safe writes and deletion of an unsafe row do not restore eligibility. Only a successful
exclusive rebuild with all remaining resident values lossless can publish a verified flag.
Atomic write batches continue to degrade indexes before payload mutation and require fenced rebuild.
The active declaration admits at most 32 flags, one per declared Long composite, never one per ID.
A fenced rebuild removes retired index keys.
Keep these proofs persistent; do not apply eviction/TTL or manually promote them.

Long lexical windows use version `4-redis-long-lex-id`, carrying an exact Long value plus a String
Redis member. No continuation is reconstructed from a rounded double. While the existing Long53
flag is verified, range and keyset reads use the same atomically guarded sorted-index window.
An unsafe, absent or invalid flag switches the WHOLE page to complete bounded materialization,
ordered by `Long.compare` then unsigned UTF-8 ID bytes. This fallback supports the entire Long
domain, including Long.MIN_VALUE/MAX_VALUE and adjacent values collapsed by a Redis double.
A wide explicit continuation also uses exact fallback, even after a later safe rebuild.
Range residuals use exact Long operands; Integer, Double and BigDecimal substitutes are rejected.
No new attestation flag, writer registry, promotion protocol or round trip is introduced.

For a sample-shaped timeline, declare a sorted composite on the Long field and opt in explicitly:

```java
@CacheCompositeIndex(name = "customer_status_date",
        columns = {"customerId", "status"}, sortBy = "orderDate")
// OrderEntity: Long orderId, Long customerId, String status, Long orderDate
// @CacheColumn("order_date") Long orderDate is an epoch-millisecond business value.

@HotRoute(value = "timeline", coverageScopeParameter = "customerId")
@CacheRouteQuery(redisLexicalIdTie = true, redisCompositeIndex = "customer_status_date",
    predicates = {
        @CachePredicate(field = "customerId"),
        @CachePredicate(field = "status", constants = "OPEN"),
        @CachePredicate(field = "orderDate", parameter = "minimum",
                operator = CachePredicate.Operator.GTE)
    },
    orderBy = @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC))
HotWindow<OrderEntity> timeline(Long customerId, Long minimum, WindowRequest window);
```

The index annotation belongs on OrderEntity; the route belongs on its repository. Java field names
resolve to persisted aliases, such as `order_date`. Existing sample routes do not acquire this
contract automatically. Use first/after WindowRequest calls with the SAME typed filters and scope.
Generated Long routes call the exact native Long capability, not typed Java-ID slicing.
Optional raw seek values are nullable Long plus String ID, with LT/DESC or GT/ASC on both fields.
They must be supplied together and cannot be mixed with an opaque cursor. Version 3 remains
Double-based and rejects Long routes; legacy typed cursors keep their original semantics.

## Explicit Redis Cursor Contract

Redis lexical pagination is a separate opt-in, not a reinterpretation of existing typed cursors.

```java
@CacheRepository(entity = Order.class)
public interface Orders extends CacheDbRepository<Order, Long> {
    @HotRoute(value = "open-priorities", coverageScopeParameter = "tenantId")
    @CacheRouteQuery(
        redisLexicalIdTie = true, redisCompositeIndex = "tenant_status",
        predicates = {
            @CachePredicate(field = "tenantId"),
            @CachePredicate(field = "status", constants = "OPEN")
        },
        orderBy = @CacheOrder(field = "priority", direction = CacheOrder.Direction.ASC))
    HotWindow<Order> openPriorities(String tenantId, WindowRequest window);
}
```

Use `WindowRequest.first(50)`, then pass `result.nextCursor()` to
`WindowRequest.after(token, 50)`. `HotWindow` retains coverage evidence; call
`completeItems()` when incomplete or stale coverage must fail rather than appear complete.

This contract orders by score, then by unsigned UTF-8 ID bytes. Numeric IDs are string members:
ASC ties are `1,10,2`, not numeric order. DESC reverses both score and ID.
Only a named sorted composite with all prefix EQ predicates, indexed supported predicates,
and a byte/short/int/LocalDate score (v3), or an exact Long score (v4), can declare this route. Unsupported typed
operators, unindexed predicates, source routes and projection routes fail compilation.

For v3, optional raw `@CacheSeek` parameters must be nullable Double score plus String ID, in score/ID
order, using GT with ASC or LT with DESC. They cannot be combined with an opaque cursor.
An ordinary typed-ID parameter is not interchangeable with the Redis member.

Versions `3-redis-score-lex-id` and `4-redis-long-lex-id` bind route, scope, active tenant,
namespace/key prefix, index signature, predicates and direction. V4 additionally binds exact Long
ordering. Typed v2, lexical v3 and Long v4 reject each other; changed contracts require a fresh first
page. No existing cursor wire format is rewritten. V4 rejects duplicate fields, wrong score/ID types
and malformed UTF-8. Tokens are bounded to 8192 characters, IDs to 4096 UTF-8 bytes.
Equality literals on protected fields (`@CacheEncrypted(blindIndex = true)`) pass through their configured keyed blind-index transformation BEFORE
contract fingerprinting; raw protected literals are not hashed or included in the token.
That keyed literal protection is NOT token authentication. Tokens are not authorization credentials;
tenant access and stored ownership are independently enforced.

## Supported Ordering Matrix

| Sort field | Ordinary logical query | Indexed lexical keyset/range | Ineligible lexical execution |
| --- | --- | --- | --- |
| byte / short / int / LocalDate | Exact logical order | V3, healthy fenced complete index | Complete bounded lossless materialization |
| Long epoch millis or other Long | Exact Long order | V4, same verified Long53 marker, safe continuation | Complete bounded exact Long/UTF-8-ID order, full Long domain |
| float / double | Logical comparator, including signed zero | Not supported by explicit lexical routes | Precise compile-time rejection; use typed logical query/keyset |
| Instant / LocalDateTime / OffsetDateTime | Logical comparator including precision/offset semantics | Not supported by explicit lexical routes, even if values happen to be millisecond-aligned | Precise compile-time rejection; use typed logical query/keyset |
| BigDecimal | Typed logical query where otherwise supported | Not a supported sorted-composite declaration | Explicit declaration/route rejection |
| null sort values | Existing logical null handling | No complete sorted coverage or lexical continuation | Explicit cursor failure, never a partial page |

The matrix does not infer units from a Long field name. Epoch milliseconds are supported because
their numeric values satisfy Long53, not because timestamps are specially rounded or truncated.

The cursor is the last RETURNED (score, ID), not the last examined row or a live rank.
A deleted anchor still works. Inserts after the cursor can appear. Inserts before it are not
revisited. An insert racing with a page is not guaranteed visible even if after its incoming
cursor: it may already precede the newly returned cursor. End-of-page is not a subscription;
later inserts do not retroactively create a continuation token. Updates, expiry and deletion
can change subsequent pages: this is not a snapshot
and does not guarantee no gaps or duplicates for rows whose sort position changes.

## Bounds And Fallback

The fast path requires VERIFIED, healthy, complete score coverage and a compatible query.
Each single-slot window Lua call atomically checks no migration lock/active registered writers,
no degradation, the expected signature and SET/ZSET cardinality before reading its ZSET window.
This is not an atomic snapshot spanning later payload reads or subsequent refill rounds.
It checks payload predicates, tombstones, tenant ownership and hot policy after index reads.
Limits are four total rounds, 10000 examined IDs and 16 MiB of ID/payload data. A range Lua reply
contains at most 2500 IDs, with at most 64 tie-boundary probes. Payload reads use 128-ID
subbatches, at most 384 keys. Existing Redis connection/socket timeouts still apply.
Query deadlines are checked between bounded operations and before returning.

Exhausted or ineligible fast work is discarded, never returned as a partial success.
Fallback completely materializes equality-prefix candidates, or a permitted bounded payload
scan while indexes are degraded. It retains 10000/configured candidate and 16 MiB bounds;
capacity failures throw instead of truncating. This is bounded candidate materialization, not
an O(log N + page size) guarantee. Strict routes still prohibit full scans.
A Redis/codec error is not silently converted into a source retry.

Missing indexed payloads retain divergence handling and configured read-through behavior.
A lexical source fallback requires complete bounded materialization before Redis-order sorting;
reaching the source capacity without completeness proof fails. It excludes tombstones and
established foreign owners. SQL ordering is not assumed equivalent to Redis lexical order.
Fetch plans remain batch-loaded. Redis admission controls cache rows, not source visibility.

Null sort values keep equality membership without a score; they disable complete score-window
coverage. An explicit cursor cannot order null or non-finite scores. Ordinary queries retain
logical null handling. For this opt-in feature, composite materialization/cursors reject minimum-version requirements
for the queried entity namespace because they cannot certify an absent required row. Requirements
for another namespace alone do not reject it. Use version-certified point/findAll reads rather
than weaker SQL; this is an explicit feature restriction, not support for causal sorted queries.
Equal SET/ZSET cardinalities alone are not proof of identical membership or correct scores.

## Mutation And Rebuild

The SET retains its existing tuple encoding; the ZSET is its key plus `:sort`.
The signature includes `name=prefix1,prefix2>sortColumn` only when sorted.
Metadata uses `$cachedb:cidx-sort:<name>` with `<zsetKey>|<score>`, split at the last delimiter.
Validation bounds remain 4096 metadata fields, 32 sort markers, 128-character safe names,
8192 characters per marker and finite scores. Tuple/score changes, deletes and rebuild remove
old ZSET members. Missing metadata degrades indexes. Entity mutation plus Java reindexing
is not an atomic entity/index transaction.

Sorted indexes, including a new namespace, require an explicit cooperative fenced rebuild:
`RedisEntityRepository.rebuildCompositeIndexesFenced(false, "schema-change")`.
Ordinary automatic rebuild does not promote sorted indexes. Upgrade every writer to the
cooperative protocol, stop incompatible/direct writers and schedule maintenance before using it.
Redis Functions invoked through the repository participate through its outer writer scope.
Unsupported atomic batch paths invalidate publication by marking indexes degraded.

The fence prevents a migration from starting with registered writers and prevents new
cooperative writers during migration. Plain recency ZADD/metadata touches do not register a writer
or allocate a writer token. Actual cache insertion, payload/index changes and eviction do;
a read that really evicts therefore enters the mutation fence only for its destructive work. The verified signature is published only if the lock,
writer registry and degradation marker remain valid. Fence keys have no safety-expiring lease:
crashed/lost-reply writers or coordinators may require offline recovery with every writer stopped.
Do not delete blockers or force unlock on a timer. A later failed mutation re-degrades indexes.

## Persistent Writer Bound And Recovery

The writer HASH has a hard limit of 10000 total entries, live writers and unknown-outcome/orphan
entries together. The atomic registration script rejects another writer before its mutation body
when full. A lost registration/release reply may leave a persistent blocker; migration fails
while ANY writer entry remains. No automatic TTL, age-based orphan deletion or forced unlock is safe.

Offline recovery requires stopping/isolating every writer and draining in-flight commands first.
Retain bounded forensic evidence of the migration lock and at most 10000 writer entries. Mark the
namespace degraded and invalidate its signature BEFORE an operator clears the persistent migration
lock/writer registry. Then run one explicit fenced rebuild and verify its publication before
restoring traffic. Failed recovery remains degraded. UUID writer tokens alone do not prove a
process has stopped; absence of recent activity is not sufficient.

Observe bounded fallbacks, degraded state, rebuild failures, deadlines and capacity errors.
Measure opted-in write/touch overhead and query p99 with representative concurrency before rollout.
