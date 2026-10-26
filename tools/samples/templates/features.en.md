## Declarative Catalog Scenarios

These scenarios use **CacheDB 0.18.0**. Existing commerce,
support, logistics and archive routes are unchanged. Unlike a release-note link,
the classes below are actual sample sources, compiled and tested for all three
providers. Each complete Java block belongs at the path printed above it.

### What Changed in 0.15

The stock catalog now resolves its generated codec once at plan construction;
`scopedBy` declares the SQL ID column, and `plan.bind` creates a typed Redis reader.
There is no runtime field discovery, automatic SQL fallback or implicit startup import.
The archive repository uses `@CacheSeek` instead of copying OR groups. A method with
`@CacheJobHandler` becomes a generated, directly invoked worker adapter; its payload
must have a generated codec or an explicit `CacheValueCodec` bean.

### Stock Ranges and Coalesced Supplier Events

Prepare the catalog using the refresh flow below before serving reads. A stock
range is inclusive and sorted by available quantity, then textual product ID:

These examples assume section 2's supplier delivery has durably stored products
`9200001` and `9200002` in `tools`, followed by a completed catalog refresh.
In your Spring service, inject `SnapshotJobs snapshots` and
`TypedSnapshotRepository<Long, CatalogCard> catalog` as in the compiled classes.

```java
var page = catalog.findTypedPage(new SnapshotPageRequest(
        "tools", 5, 20, WindowRequest.first(25)));
// Reuse the SAME category and bounds when continuing with page.nextCursor().
// This performs bounded Redis index/payload reads. A miss never runs SQL.
```

`GET /api/catalog/categories/tools/available?minimum=5&maximum=20&limit=25`
exposes this contract. Invalid bounds return 400. The legacy category URL is unchanged.

When a committed supplier transaction changes a few products, coalesce the IDs:

```java
snapshots.markDirty(CatalogConfiguration.PLAN, List.of(9200001L, 9200002L, 9200001L));
// In a worker, after the SQL transaction has committed:
var refreshed = snapshots.flushDirty(CatalogConfiguration.PLAN);
// Optional.empty(): no pending roots. Failures requeue the reserved roots.
```

Repeated marks share a bounded pending set. A root marked DURING a refresh stays
pending for the next flush. One owner claims the batch; a crashed claim becomes
recoverable after its lease. `batch-rows` bounds pending plus reserved unique IDs;
overflow fails explicitly, without silently dropping IDs. A first full snapshot
and an explicit root scope are required. Each mark call's input is also bounded
by `batch-rows` before deduplication. Flush performs SQL work and must not run
on a GET handler. Dirty hints expire and are NOT a durable outbox or CDC guarantee;
retain periodic full reconciliation or a durable change feed for recovery.

The demo-only `POST /api/catalog/dirty` accepts `{"ids":[9200001,9200002]}` (202, added count).
`POST /api/catalog/dirty/flush` returns a 202 job and Location; poll that job until
COMPLETED or FAILED. These commands are disabled outside the opt-in demo profile.

`src/main/java/com/example/cachedb/sample/catalog/CatalogDirtyJobHandler.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogDirtyJobHandler.java}}
```

Use `client` for publisher-only API pods and `worker` for SQL preparation. The
framework supplies the publisher; no manual client executor configuration is needed.
Publisher-only construction starts no job worker/heartbeat or snapshot scheduler
threads. Redis Cluster submission uses a single atomic script only for same-slot
keys; cross-slot keys retain a checked enqueue and cleanup fallback. Redis must
still be available: publishing is not an in-process durable queue.

`withContentReuse()` hashes JSON bytes as they are spooled. Existing content is
shared rather than reloaded and resent. If it expires before publication, the
framework retries with the spool payload. This reduces work on unchanged catalogs;
it does not remove SQL preparation or authorize an unbounded in-memory snapshot.

| Real situation | Implementation | What it proves |
| --- | --- | --- |
| A storefront needs a small, predictable stock card | Generated JSON codec + streaming typed snapshot | Explicit SQL preparation, Redis-only reads, Unicode and decimal parity |
| A supplier sends a small new-product delivery twice | Atomic catalog import + stable batch ID | All-or-none Redis acceptance, replay identity and separate SQL durability |
| Stock changes in one category while another page is open | Scoped snapshot refresh | Unrelated category cursors survive; changed-category cursors must restart |
| API demand grows independently of SQL persistence | `client` and `worker` profiles + Compose | API accepts work; separate, replaceable workers persist it |
| An old queued write arrives after physical deletion | Disposable-provider integration test | Durable delete fences reject stale UPSERTs and roll back with business data |

{{PROVIDER_NOTE}}

### 1. Storefront Stock Cards Without Jackson Mapping

`CatalogCard` is a compact response model, not the full entity. The annotation
processor generates `CatalogCardJsonCodec`; never create or commit that class.
Spring MVC still uses its normal HTTP JSON stack. Only snapshot preparation and
typed snapshot decoding use the explicit generated codec.

`src/main/java/com/example/cachedb/sample/catalog/CatalogCard.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogCard.java}}
```

The plan reads only active SQL products. It prepares one root at a time instead
of keeping the full root list, partitions the sorted index by category, and
defines parameter-bound SQL for a partial refresh. Ascending available quantity
puts low-stock items first; equal quantities use the textual product ID order.

`src/main/java/com/example/cachedb/sample/catalog/CatalogConfiguration.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogConfiguration.java}}
```

The sample explicitly pins Gson **2.13.2**, because the Spring Boot parent can
otherwise select an older Gson without `Strictness`. The POM includes
`cachedb-json`, the annotation dependency and the 0.15.1 processor. Do not remove
the Gson override or mix framework versions.

With the 0.15.1 BOM and annotation processor already configured, these are the
additional runtime dependencies used by the compiled sample:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-json</artifactId>
</dependency>
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.13.2</version>
</dependency>
```

After Quick Start has initialized SQL and started the demo API:

```powershell
$base = 'http://127.0.0.1:{{PORT}}'
$job = Invoke-RestMethod "$base/api/catalog/refresh" -Method Post -ContentType 'application/json' -Body '{"ids":[]}'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 200
    $state = Invoke-RestMethod "$base/api/warm/jobs/$($job.jobId)"
} while ($state.status -notin @('COMPLETED', 'FAILED') -and [DateTime]::UtcNow -lt $deadline)
if ($state.status -ne 'COMPLETED') { throw ($state | ConvertTo-Json -Depth 10) }
$state
Invoke-RestMethod "$base/api/catalog/items/10"
Invoke-RestMethod "$base/api/catalog/items?ids=10,11"
Invoke-RestMethod "$base/api/catalog/categories/electronics?limit=10"
```

Use a category present in your source data; category matching is exact. Demo
product categories are shown by the source or product APIs. `202` means a job
was queued, not that the snapshot is ready. A completed preparation publishes
one generation atomically. Failed preparation leaves the prior generation active.

| Operation | Database/cache behavior |
| --- | --- |
| `POST /api/catalog/refresh` with `{"ids":[]}` | Explicit bounded SQL preparation; writes a new Redis generation |
| Same POST with `{"ids":[10]}` | Explicit parameter-bound SQL for those IDs; updates affected partitions |
| `GET /api/catalog/items/10` | Redis only; `200` FOUND/STALE_WARNING, `404` ABSENT_IN_GENERATION, `503` SNAPSHOT_UNAVAILABLE |
| `GET /api/catalog/items?ids=10,11` | At most 100 IDs; one-generation read; `200` with explicit per-ID states |
| Category GET | At most 100 rows; opaque cursor; an unprepared catalog can return an empty page, so use point lookup to inspect availability |

GET never prepares data or falls back to SQL. Snapshot data does **not** follow
entity/projection updates automatically. Refresh explicitly or enable the
schedule. `STALE_WARNING` is visible but remains readable; an absent product is
not proof that no durable SQL row exists outside this active-product catalog.

To update product 10 after a confirmed SQL commit, submit `{"ids":[10]}`. A
cursor for another unaffected category remains valid. A cursor for an affected
category returns `400`; restart that category's first page. A complete refresh
invalidates every older cursor.

### 2. Atomic Supplier Delivery, Safe Replay and Durability

This endpoint is a deliberately **bounded new-product import**,
not an arbitrary Excel updater. Use new IDs/SKUs owned by this flow. Expected
Redis version `0` rejects existing versioned entities before any member changes.
That is not a substitute for SQL uniqueness or recovery of lost version counters.

**This is not a database-enforced insert-only operation.** If an ID already
exists only in SQL, Redis version `0` cannot detect it, and the write-behind
UPSERT can update that SQL row. A primary-key constraint does not prevent an
UPSERT. Reserve a new ID/SKU range for this demo; do not use it to import over
an existing catalog. Production insert-only semantics require an explicit SQL
command/constraint contract, not an assumption based on a Redis miss.

`CatalogImportItem` carries a full row and the source's stable `updatedAt`.
Changing timestamps, prices, item order or identifiers changes the batch identity.
Never put `Instant.now()` into a retry of the same delivery.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportItem.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogImportItem.java}}
```

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportCommand.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogImportCommand.java}}
```

This application service is also directly testable with an in-memory session.
The two callbacks are supplied by `CatalogConfiguration`, not by HTTP clients.
The durable job worker performs the bounded wait; the HTTP request does not wait
for the three-second SQL deadline.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportService.java`:

```java
{{CODE:src/main/java/com/example/cachedb/sample/catalog/CatalogImportService.java}}
```

```powershell
$body = @'
{"batchId":"supplier-delivery-001","items":[{"productId":9200001,"sku":"SUP-9200001","name":"İğne çelik","category":"tools","unitPrice":12.3456,"stock":8,"reserved":2,"updatedAt":1700000000},{"productId":9200002,"sku":"SUP-9200002","name":"Tool B","category":"tools","unitPrice":15.0000,"stock":12,"reserved":0,"updatedAt":1700000000}]}
'@
$job = Invoke-RestMethod "$base/api/catalog/imports" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 200
    $state = Invoke-RestMethod "$base/api/warm/jobs/$($job.jobId)"
} while ($state.status -notin @('COMPLETED', 'FAILED') -and [DateTime]::UtcNow -lt $deadline)
if ($state.status -ne 'COMPLETED') { throw ($state | ConvertTo-Json -Depth 10) }
$state.result
# Submit the identical $body again to verify the original receipt is returned.
```

| Result | Meaning / action |
| --- | --- |
| `202` | Job submission accepted; no SQL commit claim |
| COMPLETED + `result.durable=true` | The atomic batch's SQL durability was observed |
| COMPLETED + `result.outcome=ACCEPTED`, `result.durable=false` | Redis accepted the batch, but SQL was not confirmed in 3 seconds; inspect backlog/DLQ and retry only the identical delivery |
| COMPLETED + `result.outcome=REJECTED`, `Batch id reused` | Same ID with different contents; no new batch application; deterministic rejection is not retried |
| COMPLETED + `result.outcome=REJECTED`, optimistic version conflict | A member was not a new version-zero entity; Redis changes none of the batch members |
| `400` / `413` | Invalid contract / body larger than 64 KiB; command not queued |

`COMPLETED` describes the job lifecycle; inspect `outcome` and `durable` before
treating a delivery as accepted and persisted. Unexpected connection failures
still follow the framework's bounded retry path and can finish as FAILED.

At most **25 rows**, **64 KiB HTTP body**, NUMERIC(19,4)-compatible prices and
nonnegative stock/reservations are accepted. SQL uniqueness/constraint failures
still need operational handling. Redis acceptance and later SQL transaction are
**not one distributed transaction**: accepted Redis values can precede or outlive
a failed SQL flush. Identical retry is idempotent; it does not repair a rejected
SQL row or make the batch receipt an audit-log retention policy.

Price bounds also reject extreme scientific exponents before queueing; ordinary
scientific notation that fits NUMERIC(19,4) remains valid.

Distinct batch IDs retain Redis coordination/receipt metadata. It is not a
TTL-managed entity window. Budget and monitor this growth; do not erase receipts
while promising replay protection for old deliveries. A production retention
policy needs an explicit replay horizon and a drained recovery/migration plan.

### 3. Separate API and Worker Containers

Build `mvn clean verify -Pprovider-integration`. Complete Quick Start once so
SQL exists, then stop the local Java process to free the API port. Keep the
same Redis and SQL data; do not reset version counters.

```powershell
docker compose -f docker-compose.yml -f docker-compose.roles.yml up -d --scale worker=2
docker compose -f docker-compose.yml -f docker-compose.roles.yml logs worker
```

The same JAR runs with `demo,client` on the API and `demo,worker` on workers.
The worker profile sets `web-application-type=none`; it exposes no REST listener.
It also sets `spring.main.keep-alive=true`: background pools use daemon threads,
so a non-web JVM must be kept alive explicitly by its application lifecycle.
All instances share metadata, JSON codecs, Redis, SQL and job definitions.
Auto-configuration also supplies a publisher-only job executor for
`CLIENT_ONLY`. It accepts registered commands without consuming them or starting
idle worker/heartbeat threads. No extra configuration class is required.

Each application container is limited to **2 CPU / 768 MiB** in this local recipe.
These are demonstration limits, not a certified capacity recommendation.

With workers stopped, API submissions can still be accepted, but work and SQL
writes remain pending. Start a worker again; do not submit a different batch ID
as a recovery strategy. Do not stop workers during a production upgrade without
draining pending work. Redis here is a disposable local fixture, not HA storage.

For periodic stock preparation, set `SAMPLE_CATALOG_SCHEDULE_ENABLED=true` on
worker containers. The framework coordinates preparation with a renewable Redis
lease; another worker cannot publish concurrently under the same lease. Default
interval is 5 minutes, not a claim of instant external-SQL synchronization.

| Setting | Sample value | Why |
| --- | --- | --- |
| source row/byte limits | 10,000 / 16 MB | Reject an unexpectedly broad source rather than exhaust JVM memory |
| fetch / preparation batch | 128 / 100 | Bound driver and preparation work |
| page/batch byte target | 1 MB | Keep reads bounded; not a total Redis memory quota |
| warning / maximum age | 10 / 30 minutes | Separate warning from expiry |
| preparation / total snapshot timeout | 20 / 30 seconds | Bound work and leave time to publish |
| lease duration | 45 seconds | Renewable ownership across pods |

### 4. Reproduce the Safety Checks

```powershell
mvn clean verify -Pprovider-integration
```

`CatalogFeatureTest` uses generated repository constructors with
`InMemoryCacheDatabase`, generated JSON codecs and `InMemorySnapshotRepository`.
It checks replay, conflict rollback, Unicode/money, states and partition cursors
without Docker. `markDurable` in a fixture is **test evidence only**, not SQL commit.

`src/test/java/com/example/cachedb/sample/catalog/CatalogFeatureTest.java`:

```java
{{CODE:src/test/java/com/example/cachedb/sample/catalog/CatalogFeatureTest.java}}
```

The real-provider integration suite also runs `CatalogScenarios`,
`WriteFenceScenarios` and `SplitRoleScenarios`. It checks REST + real Redis/SQL,
physical DELETE followed by older UPSERT after writer recreation, atomic SQL/fence
rollback, and pending client writes persisted by an independently started worker
and its replacement. Replay injection exists **only in disposable tests**, not in
a production HTTP endpoint. Keep the durable `cachedb_write_fence` table and follow
the provider migration/drained-upgrade guide.

Import `postman/cache-database-{{PROVIDER}}-catalog.postman_collection.json`.
Run all **23 requests** in order; no demo seed is required after schema setup.
They assert import durability (retrying only the identical delivery if the
initial bounded wait returns pending), identical replay, changed-payload rejection,
snapshot publication, Unicode/decimal data, single-generation reads, pagination
and invalid-input responses. The existing 59-request business collection remains
separate. Neither collection asserts a 50 ms REST SLA.

```powershell
npx --yes newman@6.2.1 run postman/cache-database-{{PROVIDER}}-catalog.postman_collection.json --env-var baseUrl=$base
```

The integration tests additionally verify unrelated-category cursor continuity;
the Postman flow does not mutate SQL directly. Redis Cluster namespace sharding
remains a framework infrastructure capability: the local Compose recipe uses
standalone Redis and does not claim a Cluster/HA topology test. All JSON/role
changes are explicit opt-ins, not transparent ORM fallback.

For an isolated command-line run, install Node.js/npm and PowerShell 7, build the
JAR, then run `./scripts/test-catalog-postman.ps1`. It starts disposable provider
fixtures and the JAR on a free local port, executes the saved collection with
Newman, exports JSON/JUnit reports under `target/catalog-postman`, and removes
only its own test project and volumes. Never point this script at production.
Pass `-SplitRoles` to bootstrap the local schema, stop the bootstrap JVM, and run
the same collection against one API container plus **two worker containers**.
The sample CI uses this mode; container logs are included in the test evidence.

The separate 59-request business collection automatically polls the saved job
status request after each accepted background command, then resumes its original
sequence. Polling stops after 30 seconds or 150 attempts. Every business response
has an HTTP assertion; a failed job stops the flow instead of racing the next
read. For the HTTP load recipe, explicitly refresh the catalog immediately before
the run: after `warn-age`, `STALE_WARNING` is an intentional freshness signal,
not a missing row or a reason to fetch SQL implicitly.
