# Bounded Runtime Operations

The APIs on this page require 0.13.0 or later. Align the BOM, processor and provider
starter before recompiling generated bindings. Read the
[release notes](releases/v0.13.0.md) before changing an existing storage contract.

## Choose the Boundary

| Requirement | Use | Important limit |
|---|---|---|
| Decode read-only SQL rows | `@CacheSourceRecord` | Primitive SQL NULL fails |
| Combine tables without N+1 | `SnapshotPlan` + typed relations | One bounded source transaction |
| Page prepared responses | `SnapshotIndexSpec` + `WindowRequest` | No SQL on read |
| Update a few prepared roots | `SnapshotRootScope` + `refreshRoots` | Explicit scope for every source |
| Share unchanged catalog payloads | `withContentReuse()` | Changed data still needs new memory |
| Consume external writes in parallel | `JdbcOutboxPartition` | Stable partition per entity |
| Accept related writes together | `CacheWriteBatch` | Redis acceptance is not SQL commit |
| Share a legacy SQL sequence | `JdbcPooledSequenceIdGenerator` | Allocated but unused IDs can be lost |
| Distribute Redis memory | `JedisClusterOperations` | One atomic scope occupies one slot |

## Cache Count Policy

```java
var bounded = CachePolicy.builder().hotEntityLimit(1_000).build();
var unboundedCount = CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).build();
var noAdmission = CachePolicy.builder().hotEntityLimit(CachePolicy.DISABLED).build();
```

| Value | Result |
|---|---|
| Positive | Count limit; configure eviction and other policy bounds deliberately |
| `-1` / `UNBOUNDED` | No count cap, not unlimited physical memory |
| `0` / `DISABLED` | No entity admission from writes, warm or hydration; read window cap is zero |
| Below `-1` | Configuration error |
| Omitted | Existing 1,000-record default retained |

YAML's `hot-entity-limit` uses the same values. If an old configuration used zero
to mean unlimited, change it to `-1` before upgrading. Existing cached keys are not
retroactively deleted by changing a policy; use an explicit reconciliation/eviction.
Disabling entity admission does not disable write-behind or remove durability/version
metadata. It does not switch an explicit Redis-only route to SQL.
Projection windows and snapshot generations have their own residency contracts.
Set Redis maxmemory, use a persistence-safe eviction policy and measure index/metadata
memory as well as payload memory. Do not use `-1` to bypass capacity planning.

## Indexed and Partial Snapshots

The example assumes generated `OrderEntityCacheBinding.SOURCE` and
`CustomerEntityCacheBinding.SOURCE`, with Long IDs. Replace fields with your schema.

```java
var orders = SnapshotSource.entity("orders", OrderEntityCacheBinding.SOURCE)
        .where(SnapshotPredicate.isNull("deleted_at"));
var customers = SnapshotSource.entity("customers", CustomerEntityCacheBinding.SOURCE);
var scope = SnapshotRootScope.builder()
        .source(orders, ids -> SnapshotPredicate.inValues("order_id",
                ids.stream().map(Long::valueOf).toList()))
        .source(customers, ids -> SnapshotPredicate.in("customer_id",
                orders.where(SnapshotPredicate.inValues("order_id",
                        ids.stream().map(Long::valueOf).toList())).select("customer_id")))
        .build();

SnapshotPlan<OrderEntity, OrderSummary> plan = new SnapshotPlan<>(
        "order-timeline", orders, order -> order.orderId.toString(),
        SnapshotPlan.inputs(orders, customers), OrderSummary.class, rows -> {
            var customerById = rows.unique(customers, customer -> customer.customerId);
            return order -> List.of(OrderSummary.from(order, customerById.get(order.customerId)));
        })
        .indexed(SnapshotIndexSpec.of(order -> order.customerId.toString(),
                order -> -order.orderDate.toEpochMilli()))
        .scoped(scope)
        .withContentReuse();
```

`OrderSummary.from` is application business mapping, not framework magic. The index
is on roots (orders here); each root payload is still a JSON array. Index scores are
ascending, so a negative timestamp gives newest-first ordering. Equal scores use
lexical root ID order, not numeric ID order. Doubles cannot represent every large
integer exactly; do not encode exact financial amounts or IDs above 2^53 as ranking
scores without an explicit precision contract.

```java
SnapshotRepository repository = jobs.repository("order-timeline");
jobs.refresh("order-timeline", true); // Full, transaction-consistent preparation first.
var first = repository.findPage("42", WindowRequest.first(50));
var next = first.nextRequest(50).map(request -> repository.findPage("42", request));
repository.refreshRoots(List.of("9001", "9002")); // Explicit command, never triggered by GET.
```

A full refresh stages payloads, partition indexes and metadata together before one
active-generation switch. Reads remain Redis-only. Cursors bind the plan, partition,
generation and revision. A full or partial refresh invalidates old cursors; restart
from the first window. Page size is bounded by WindowRequest (maximum 1,000),
and payload bytes by `batch-target-size` (4 MiB by default). An oversized page fails
instead of silently returning a partial answer; reduce the window or use point reads.

Partial refresh requires a successful full generation and an explicit predicate for
every input. Root IDs are Strings at the command boundary; convert them to the real
SQL type in your scope function. `shared(source)` explicitly permits an entire small
lookup source to be reread; it is not an implicit default. Incorrect scoping can
change business results, so certify source membership and full payload parity.
Absent selected roots are deleted; remaining selected roots and their index scores
are replaced in one lease/active-generation-checked operation.

Root count must fit `batch-rows`; serialized delta must fit `batch-target-size`.
Split larger requests explicitly. Unchanged roots retain their old `refreshedAt`.
Delta does not extend the entire catalog's retention or mark untouched roots fresh.
Keep scheduled full refreshes if that is required by the freshness contract.

Content reuse stores a SHA-256 payload reference per root and shares immutable JSON
between generations. Unchanged JSON is not rewritten; index/hash metadata still
overlaps. If every payload changes, old and new data must coexist for atomic full
publication. This option does not guarantee half the memory or skip SQL preparation.
Cleanup is bounded and checks active/staging references before removing shared data.

## Partitioned Outbox

```java
// The producer stores this integer with the outbox row in its SQL transaction.
var shard = new JdbcOutboxPartition(8, podShardIndex, "partition_key");
int partitionKey = shard.partitionFor("OrderEntity", orderId.toString());

var feed = JdbcOutboxExternalChangeFeedAdapter.builder(dataSource, outboxDialect)
        .adapterName("orders-feed")
        .outboxTable("cachedb_outbox")
        .checkpointTable("cachedb_outbox_adapter_checkpoint")
        .partition(shard)
        .batchSize(128)
        .build();
```

Use PostgresOutboxDialect, MssqlOutboxDialect or OracleOutboxDialect from the selected
provider. Provider wrapper builders also accept `.partition(count, index, column)`.
Create an index on `(partition_key, id)`. Assign every partition to a consumer;
duplicate pods for the same partition serialize safely on its checkpoint row.
Different partitions use different rows and can progress in parallel.
No automatic partition assignment or SQL hash translation is assumed.

The producer and consumers must agree on entity name, canonical ID text and fixed
partition count. Events for one entity must stay in one partition and use increasing,
commit-visible event IDs. Changing partition count needs a drained/certified migration;
do not run the old unpartitioned consumer alongside the new set.
Partitioned generic adapters default to READ COMMITTED with a checkpoint row lock;
an explicitly selected isolation is retained. The old unpartitioned default is retained.
Deadlock/serialization conflicts before any sink callback receive at most three
bounded retries. Once delivery starts, the adapter reports failure instead of
silently repeating side effects in the same poll call.
Sink delivery is at-least-once: a crash after Redis acceptance and before checkpoint
commit can replay events. Keep sink version checks/idempotency, finite pool timeouts,
retry backoff, adapter failure telemetry and pending/lag alerts.

## Atomic Related Writes

```java
var receipt = database.writeBatch("checkout-" + requestId)
        .put(order)
        .put(payment)
        .commit();

// Compare-and-set all members before accepting any of them.
database.writeBatch("amend-" + requestId)
        .put(updatedOrder, expectedOrderVersion)
        .put(updatedPayment, expectedPaymentVersion)
        .commit();

database.writeBatch("cancel-" + requestId)
        .delete(PaymentEntity.class, paymentId)
        .delete(OrderEntity.class, orderId)
        .commit();
```

Register every writable entity and projection first. IDs must be assigned before
put; the batch does not guess missing IDs or merge partial entities. Supply complete
entities. A batch has at most 100 operations, a 4 MiB encoded command limit, bounded
stream fields and the configured per-operation column limit. Duplicate entity/ID
pairs and invalid version preconditions fail before mutation. Batch IDs are stable
idempotency keys for retries; identical retries reuse the receipt for 24 hours.
Do not reuse an ID for different operations or assume deduplication after expiry.

Redis applies payload/version/tombstone changes and supported registered projection
payloads in one operation, then enqueues one indivisible envelope. Query indexes are
marked degraded instead of trusting stale membership; rebuild explicitly or use the
configured index recovery/strict-route behavior. Bounded tenant admission contracts
currently reject atomic batches rather than silently bypassing tenant quotas.

The PostgreSQL, SQL Server and Oracle flushers commit the envelope in one SQL
transaction, preserving operation order. A SQL failure rolls back the entire group;
retry/dead-letter recovery must retain the envelope, never split it into rows.
Parent inserts must precede dependent inserts; child deletes must precede parent
deletes when SQL constraints require it.

The receipt means Redis acceptance, not SQL durability. Redis and SQL do not share
a distributed transaction. Other SQL readers can lag until write-behind commits.
Use durability tracking before triggering effects requiring confirmed SQL storage.
`database.isDurable(receipt)` checks every member's accepted version without SQL.
Redis scripts are isolated, not rollback transactions for OOM/server faults. Use
noeviction, finite command budgets, memory headroom and durable Redis configuration.

## Shared SQL Sequence

```java
var ids = new JdbcPooledSequenceIdGenerator(dataSource, "order_id_seq", 128, 30);
Long orderId = ids.nextId(); // SQL only when the local allocation buffer is empty.
```

The generator fetches actual NEXTVAL/NEXT VALUE FOR values in one bounded statement.
It does not infer a contiguous range or change an existing sequence's increment.
This is safe when legacy applications and multiple pods share that sequence.
IDs returned from the local buffer require no SQL round trip. The refill is blocking
JDBC: use an appropriate command executor, finite pool/driver timeout and metrics.
Values must fit Long, allocation is 1..1,000 and statement timeout is 1..300 seconds.
Process crashes can leave gaps; sequences are not gap-free invoice numbers.

## Redis Cluster

```java
String scope = ClusterHashTagKeyStrategy.scopedPrefix("cachedb", "checkout");
var writeBehind = WriteBehindConfig.builder()
        .streamKey(scope + ":stream")
        .compactionStreamKey(scope + ":compaction")
        .deadLetterStreamKey(scope + ":dlq")
        .build();
var config = CacheDatabaseConfig.builder()
        .keyspace(KeyspaceConfig.builder().keyPrefix(scope).build())
        .writeBehind(writeBehind)
        .build();
var clientConfig = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(3_000).socketTimeoutMillis(5_000).build();
var redis = new JedisClusterOperations(new JedisCluster(
        Set.of(new HostAndPort("redis-cluster", 6379)), clientConfig));
var database = new CacheDatabase(redis, dataSource, config);
```

Register bindings and start/close the database using your application lifecycle.
Close the separately owned Redis adapter when the application shuts down.
For Spring, define RedisClientOperations and CacheDatabase beans explicitly;
the default pooled client backs off. Registration remains an explicit application
bootstrap responsibility for a custom database bean. Snapshot and scheduled warm
use the database's operation adapters. Set distributed-job/operational prefixes to
the same tag where their scripts require it; do not leave independently configured
operational stream prefixes unreviewed.

The full CacheDatabase requires a tagged coordination scope and matching write
streams; startup rejects mismatched slots. Related entities in one atomic batch
must share that scope. Separate tenant/domain scopes can occupy different slots;
one very large single scope is not automatically split across nodes.
Low-level ClusterHashTagKeyStrategy("cachedb") uses `{entityNamespace}` and preserves
the owner tag for projection namespaces. Its streamKey(namespace) must be used with
the corresponding independently configured consumer. There is no cross-slot ACID.

The adapter supports primary-node bounded SCAN, slot-grouped MGET/DELETE, Cluster
pipelines and function loading across primaries. Memory guardrails inspect the scope's
own primary rather than averaging away an overloaded shard. Observe every shard
separately. Reshard/failover/replication durability and customer staging acceptance
remain deployment responsibilities; a three-primary local test is not HA certification.
