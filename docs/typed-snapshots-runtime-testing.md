# Typed Snapshots, Runtime Roles and Consumer Tests

English | [Türkçe](../tr/docs/tipli-snapshot-roller-testler.md)

This page describes the 0.14.0 API. The published 0.13.0 package does not contain
these additions. Use aligned framework, starter and processor versions; do not mix
generated classes from different releases.

## Choose the Right Tool

| Need | API | Boundary |
|---|---|---|
| Read a prepared response as Java values | `TypedSnapshotRepository<ID, V>` | Redis only |
| Distinguish absence from an unavailable catalog | `SnapshotLookup<V>` | No automatic SQL fallback |
| Read multiple roots consistently | `lookupAll` / `findByIds` | One generation, up to 1,000 IDs |
| Prepare independent roots without retaining their source list | `SnapshotPlan.streaming` | Explicit row-local mapping |
| Separate API pods from SQL workers | `CacheRuntimeRole` | Workers must exist elsewhere |
| Test business code without containers | `InMemoryCacheDatabase` | Test double, not durability evidence |

## Declare a Streaming Inventory Catalog

The source table in this example is `inventory(stock_id, sku, available)`.
The decoder handles the JDBC number type without a provider-specific cast.

```java
import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.spring.boot.snapshot.*;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InventorySnapshots {
    public record Stock(long id, String sku, int available) {}

    @Bean
    SnapshotPlan<Stock, Stock> inventorySnapshot() {
        var source = SnapshotSource.entity("stock", new SourceMapping<Stock>(
                "inventory", List.of("stock_id", "sku", "available"), row -> new Stock(
                        ((Number) row.get("stock_id")).longValue(),
                        (String) row.get("sku"),
                        ((Number) row.get("available")).intValue())));

        return SnapshotPlan.streaming("inventory", source,
                        stock -> Long.toString(stock.id()), Stock.class, List::of)
                .indexed(SnapshotIndexSpec.of(stock -> "all", Stock::available))
                .scoped(SnapshotRootScope.builder()
                        .source(source, ids -> SnapshotPredicate.inValues("stock_id",
                                ids.stream().map(Long::valueOf).toList()))
                        .build());
    }
}
```

The index is ascending: low-stock items come first. Equal scores use lexical ID
order. For newest-first time indexes, use a negative timestamp as documented in
the [snapshot guide](snapshot-projections.md).

`streaming` promises that each root can be mapped independently. It does not pass
the complete source catalog to the mapper. Existing `new SnapshotPlan<>(...)`
factories retain their complete `SnapshotRows` behavior, even with one source.
Multi-source joins still retain bounded source rows and relationship indexes on
the heap. Streaming removes the root list, not spool metadata, JDBC driver buffers
or the result returned to the caller.

Full catalog scans are deliberate preparation work, never a side effect of a GET.
Restrict large sources with predicates. Configure source row/byte budgets, JDBC
fetch size, preparation timeout and disk space before enabling scheduled refresh.
A failed preparation leaves the previous generation active.

## Read Values and State

```java
TypedSnapshotRepository<Long, InventorySnapshots.Stock> repository =
        jobs.typedRepository("inventory", InventorySnapshots.Stock.class, Object::toString);

SnapshotLookup<InventorySnapshots.Stock> one = repository.lookup(42L);
var several = repository.lookupAll(List.of(42L, 43L));
var first = repository.findTypedPage("all", WindowRequest.first(50));
var next = first.nextRequest(50).map(window -> repository.findTypedPage("all", window));
repository.refreshRoots(List.of(42L)); // Explicit SQL command; requires the scope above.
```

Import `com.reactor.cachedb.core.repository.WindowRequest`. Inject the configured
`SnapshotJobs` bean as `jobs`. The class-checked overload catches a mismatched DTO
type when obtaining the repository. Readers are cached per plan; reads do not
create a new Jackson type descriptor.

| State | Meaning | Application action |
|---|---|---|
| `FOUND` | Root exists in the active generation | Use `values()` |
| `STALE_WARNING` | Root exists but crossed `warn-age` | Serve or reject according to the route contract |
| `ABSENT_IN_GENERATION` | Catalog is available; selected root is absent | Apply business-level absence handling |
| `SNAPSHOT_UNAVAILABLE` | No active publication marker | Return an explicit unavailable response or invoke a separately declared source route |

An existing `[]` is a present empty result. `findValuesById` preserves this
distinction as `Optional.of(List.of())`, not `Optional.empty()`.
`refreshedAt`, `ageMillis` and `generation` are available for logging and freshness
decisions. `warn-age=PT0S` disables warnings; the existing default remains PT3M.
Warnings do not silently discard data or enforce an HTTP status. Corrupt cache
content, connection failures and deserialization errors remain explicit failures.

Multi-ID reads use one Lua operation and one generation. Duplicate input IDs
collapse to one map entry. The encoded payload budget is at most 4 MiB and can be
lowered with `batch-target-size`. Oversized results fail instead of truncating.
Point reads preserve the existing ability to return a single larger root.
Choose bounded DTOs and HTTP response budgets even for point reads.

Full publication invalidates all old generation cursors. Partial refresh changes
only the old/new partitions touched by the selected roots. A refresh in partition
A therefore does not invalidate B's cursor. Changes within B still require B to
restart pagination. Cursors carry generation, partition revision and the final
score/member pair; they do not promise a live view while their partition changes.

## Separate API and Worker Pods

```properties
# API deployment
cachedb.runtime-role=CLIENT_ONLY
```

```properties
# Worker deployment using the same entity/projection registrations and Redis config
cachedb.runtime-role=WORKER_ONLY
spring.main.web-application-type=none
spring.main.keep-alive=true
```

| Role | Redis reads / acceptance | Automatic background work |
|---|---|---|
| `FULL` (default) | Enabled | Enabled, subject to existing feature settings |
| `CLIENT_ONLY` | Enabled | SQL flush, DLQ, cleanup, history polling, scheduled warm/snapshot and job consumption disabled |
| `WORKER_ONLY` | Available to the embedding application | Worker features enabled |

`WORKER_ONLY` does not secure or hide application REST controllers. Deploy the
worker without application-facing endpoints or protect them independently.
`CLIENT_ONLY` is not an all-in-one deployment: without workers, writes accumulate
in Redis and never become SQL-durable. Monitor backlog, oldest pending age, DLQ,
projection lag and Redis memory. Configure finite Redis/SQL connection timeouts and
bounded pools on both deployments. Workers need the same metadata/codecs and
projection definitions, not only network access to the same Redis instance.

For Redis Cluster, an untagged prefix enables per-namespace slots. Entity,
projection, version, tombstone, hot-set and write stream keys remain colocated.
The worker pool polls slot-local streams rather than using cross-slot XREADGROUP.
Admin stream discovery follows the registered namespaces, including registrations
made after the admin service was first obtained. Backlog estimates include atomic
batch envelopes as well as compaction work; they are not SQL commit receipts.
An explicit `{scope}` retains the shared-slot contract for related atomic batches;
configure its streams with the same scope. Cross-slot atomic batches fail before
mutation. Do not treat different slots as one SQL/Redis distributed transaction.

Snapshot plans with an already-tagged application namespace now receive their
own combined snapshot tag. Rewarm those plans before moving traffic; their key
layout changes. Untagged snapshot namespace layouts are retained. Do not run
different key strategies against the same logical entity during a rolling change.
Function reload retries only a definite missing-function reply, once. It does not
retry ambiguous network failures or bypass version/content-drift checks.

## Test Without Redis or SQL

For an explicit Jackson-free typed snapshot path, see
[generated JSON codecs](#generated-json-codecs-without-jackson) below.

Given a generated binding for your `OrderEntity`:

```java
var memory = new com.reactor.cachedb.core.testing.InMemoryCacheDatabase();
var orders = OrderEntityCacheBinding.repository(memory);
var accepted = orders.saveWithReceipt(order);

assert orders.findById(accepted.id()).isPresent();
assert !memory.isDurable(accepted);
memory.markDurable(accepted); // Test fixture acknowledgement, not an actual SQL commit.
assert memory.awaitDurable(accepted, java.time.Duration.ofMillis(25));

var batch = memory.writeBatch("checkout-123").put(order).put(payment).commit();
```

Register the generated bindings for both entity types before creating the batch.
Generated `@CacheRepository` implementations also accept `CacheSession`; for an
interface named `Orders`, construct `new OrdersCacheDbImplementation(memory)`.
Source routes fail explicitly unless supplied by a source-capable session.
Hot route coverage is not fabricated: provide the intended `RouteCoverage`
fixture through `putCoverage` when testing coverage-gated routes.

The kit supports four-state hot lookups, codec copies, TTL/policy checks, count
eviction, versioned reads, CAS, bounded queries, paging, projections and atomic
batch rollback. It uses one policy per entity type per test session. Relations
are not silently hydrated; unsupported relation plans fail explicitly.
`InMemorySnapshotRepository` supplies prepared JSON fixtures, typed views,
partition windows, expiry and explicit refresh callbacks.

Generated batch IDs are session-local fixture IDs; snapshot generations are unique
within the test JVM. Neither uses cryptographic randomness. Do not persist these
identifiers or use them as production IDs. TTL expiry is checked before decoding
an expired entity. Query sorting extracts columns once per candidate rather than
building maps/comparators for every comparison; the kit remains an in-memory scan,
not a production index or query-cost simulator.
Count-only admission and unfiltered, unsorted queries do not extract unused columns.

These tests do not establish SQL constraints, source parity, Lua correctness,
failover, replay ordering, memory capacity or throughput. Keep real Redis and
provider integration tests for those guarantees. Test runtime depends on the JVM
and machine; a short unit test is not a production latency benchmark.

### Measure Full-Method Setup Separately

Run the focused contracts repeatedly on your normal test JDK:

```powershell
# JAVA_HOME must point to your test JDK; Maven must be on PATH.
./tools/ci/check-inmemory-latency.ps1 -Runs 5 -ReportOnly
```

CI uses this reporting mode for the original contracts: correctness remains
mandatory, while complete test-method timings remain visible. The strict release
latency target is the Jackson-free generated-codec/snapshot probe below, including
its first use. Do not interpret full JUnit method timings as repository-call latency.

Without `-ReportOnly`, the optional test-method limit is strictly less than 50 ms
**per test**, across every recorded
run, not a class average or a p95 that hides slower samples. The command fails on
functional failures, missing/stale reports, skipped contracts or an exceeded
budget. It saves each run and `summary.json` under `target/inmemory-unit-latency/`.
`-ReportOnly` still records a failed budget but does not fail the command for it;
functional failures always fail. No intentional prewarming is performed. Test
execution times include initialization occurring inside the test, but exclude
Maven startup, JVM process launch and compilation. This is not a promise that a
fresh JVM plus Jackson type discovery finishes within 50 ms on every machine.

Treat this as a test-method budget, not a repository latency measurement. A call
such as `typedRepository(new ObjectMapper(), Summary.class)` also measures the
caller's mapper construction; callbacks, codecs and first-use assertions can add
their own setup costs. Attribute these phases separately when investigating a
failure, but retain the complete test result. For latency-sensitive record
snapshots, explicitly choose the generated-codec path below and verify payload
parity before switching. CacheDB never silently replaces a supplied mapper or its
customizations to meet a timing target.

Byte-limit checks use exact UTF-8 lengths without materializing a temporary byte
array. The in-memory fixture stores the length with each immutable payload and
replaces it on update or refresh; existing window budgets and large single-root
read behavior remain unchanged. This length is the encoded payload size, not a
complete Redis memory estimate. Keys, indexes and allocator overhead still apply.
JDBC preparation shares one column-name index per result set; row values remain
independent and may be retained by decoders.

For a separate cold/warmed diagnostic, the optional `inmemory-benchmarks` Maven
profile builds a JMH tool without adding JMH to any library artifact:

```powershell
./tools/ci/check-inmemory-jmh.ps1
```

It measures complete entity, atomic-batch, 100-row paging and typed-snapshot
fixtures with correctness checks. Each invocation creates a new fixture; warmed
snapshot tests reuse the mapper, as an application would. Cold mode includes
mapper construction and type discovery. Five cold forks have no warmup; three
warmed forks each have five warmup and five measurement iterations. JSON results
and GC diagnostics are saved under `target/inmemory-latency/`. The default gate
requires all observed cold and warmed samples to be below 50 ms. Explicitly using
`-Acceptance Warmed` checks only warmed samples, not the full cold target.

Do not treat unsupported-VM results as acceptance evidence. The JMH command fails
if JMH reports an unsupported VM, including the tested OpenJ9 0.43.0 runtime.
You may supply `-BenchmarkJavaHome <supported-JDK>` for a separate diagnostic,
without changing the JDK used to build or run normal unit tests. Results from a
different VM do not certify your target VM. Keep the strict JUnit report as the
target-JDK evidence; a passing warmed benchmark does not close a failing cold gate.

`CacheSession.awaitDurable` supports ordinary and atomic batch receipts. It polls
with monotonic time, bounded 5/10/25 ms waits and interrupt preservation. Timeout
returns false; it does not cancel or resubmit the accepted write. Configure Redis
socket timeouts as well: a blocked I/O call is not interrupted by the polling deadline.
Backoff/deadline tests use an internal monotonic-clock seam instead of sleeping;
the public API still uses real monotonic time and retains the same waits.

## Generated JSON Codecs Without Jackson

Use this opt-in path when record DTOs need predictable JSON mapping without
runtime databinding/type discovery. Existing ObjectMapper overloads remain unchanged.
This does not remove Jackson from every CacheDB or Spring feature.

### 1. Add the Optional Module

With the aligned CacheDB BOM already imported, add:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-json</artifactId>
</dependency>
```

Keep `cachedb-annotations` on the compile classpath and `cachedb-processor` in
your compiler's annotation processor paths, all at 0.14.0. The optional module
uses Gson's strict streaming reader, not Gson object mapping. Generated codecs
write through a bounded CacheDB token writer, avoiding Gson writer initialization
on this path. Custom `StreamingJsonCodec` implementations retain their existing
Gson writer unless they explicitly implement `GeneratedJsonWriter`. The module
is not added to applications that retain the Jackson path.

The CacheDB BOM aligns Gson to the tested 2.13.2 version. If another BOM or parent
overrides it, check `mvn dependency:tree -Dincludes=com.google.code.gson:gson` and
pin this direct dependency; older releases lack the required streaming controls:

```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.13.2</version>
</dependency>
```

### 2. Declare the Response Record

Place this in `example/CatalogSummary.java`:

```java
package example;

import com.reactor.cachedb.annotations.CacheJsonRecord;

@CacheJsonRecord
public record CatalogSummary(String title) {}
```

The compiler generates `example.CatalogSummaryJsonCodec`, including `INSTANCE`.
Do not write or commit that generated class. Public nested records are also
supported; `Outer.Summary` generates `Outer_SummaryJsonCodec` in the same package.

### 3. Select the Codec on the Plan

For an existing `products(product_id, title)` source table:

```java
package example;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.spring.boot.snapshot.*;
import java.util.List;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class CatalogSnapshots {
    public record Product(long id, String title) {}

    @Bean
    SnapshotPlan<Product, CatalogSummary> catalog() {
        var source = SnapshotSource.entity("products", new SourceMapping<Product>(
                "products", List.of("product_id", "title"), row -> new Product(
                        ((Number) row.get("product_id")).longValue(),
                        (String) row.get("title"))));
        return SnapshotPlan.streaming("catalog", source,
                        product -> Long.toString(product.id()), CatalogSummary.class,
                        product -> List.of(new CatalogSummary(product.title())))
                .withJsonCodec(CatalogSummaryJsonCodec.INSTANCE);
    }
}
```

If every plan declares a codec, Spring snapshot auto-configuration does not
resolve the ObjectMapper provider. Mixed applications retain the configured mapper
for legacy plans. Manual bootstrapping can use `SnapshotJobs.jacksonFree(...)`;
that factory rejects any plan without a codec before starting workers.

Warm and refresh behavior is unchanged: the framework reads the declared SQL
source, prepares bounded payloads and atomically publishes a generation. Typed
reads decode that prepared JSON; they never fetch missing data from SQL.
`SnapshotPlan.map(...)` changes the value contract and deliberately drops the
old codec; select the new DTO's codec on the returned plan.

An infrastructure-free consumer test uses the same generated codec:

```java
var raw = new com.reactor.cachedb.spring.boot.snapshot.InMemorySnapshotRepository();
var catalog = raw.typedRepository(example.CatalogSummaryJsonCodec.INSTANCE);
raw.putSnapshot("42", "[{\"title\":\"Available stock\"}]");
assert catalog.lookup("42").values().get(0).title().equals("Available stock");
```

### Supported Mapping and Limits

| Type | JSON representation |
|---|---|
| Primitives, wrappers, String, BigInteger, BigDecimal | Exact scalar values; finite floating-point numbers |
| UUID, supported java.time values, enums | Text; enum constant name |
| Annotated records, List, Map with String keys | Nested objects, arrays and objects |

Supported time values are Instant, LocalDate, LocalTime, LocalDateTime,
OffsetDateTime and ZonedDateTime. Collections may contain null values; the
top-level snapshot array and its record elements may not be null.
Missing primitive components default to zero/false; duplicate fields use the last
value. Unknown fields fail unless `@CacheJsonRecord(ignoreUnknown = true)` is set.

Defaults per document/container: depth 64, 100,000 elements/entries, numeric token
length and absolute exponent at most 1,024, and 16,777,216 UTF-16 characters per
root document. These are parsing/encoding bounds, not a Redis memory quota.
Prefer route-specific tighter limits for large or untrusted payloads:

```java
var codec = new example.CatalogSummaryJsonCodec(
        new com.reactor.cachedb.json.JsonCodecLimits(16, 1000, 128, 1_048_576));
// Select this instance with plan.withJsonCodec(codec).
```

No runtime reflection, registry, classpath scan or silent databinding fallback is
used. Unsupported types, generic records and Jackson/Gson mapping annotations
fail compilation. The generated contract uses record component names and strict
JSON types, not custom naming strategies, serializers or permissive coercions.
Keep the legacy mapper for such contracts, or supply an explicit `JsonArrayCodec`.
Before switching an existing plan, verify old/new payload value parity, numeric
precision, null handling and ordering with representative production DTOs. JSON
whitespace/escaping need not be byte-identical; rewarm when switching encoders
instead of assuming content hashes remain identical.

### Reproduce First-Use Measurements

```powershell
./tools/ci/check-jackson-free-latency.ps1 -Runs 5 -PackagedArtifacts
```

`-PackagedArtifacts` runs the built module JARs rather than loose class directories;
the report records their SHA-256 hashes. The strict CI gate uses this mode.

The tool runs the actual generated example without Jackson JARs in its runtime
classpath. Five fresh JVMs per scenario record decode, roundtrip and full in-memory
snapshot contracts. The first invocation is always retained; all 20 subsequent
invocations are also reported, with no discarded warmup. All samples must be
strictly below 50 ms. Compilation and JVM process launch are excluded, but all
scenario initialization is included. `-ReportOnly` records failed latency without
changing the result; functional failures always fail. Reports remain under
`target/jackson-free-latency/`. This diagnostic is not a production throughput or
allocation benchmark and does not certify other scenarios or JVMs.
