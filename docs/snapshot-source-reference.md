# Snapshot Sources and Relationships

Use CacheDB 0.12.0 or later. This API describes a bounded, transaction-consistent
read job. It is not lazy ORM loading, CDC, arbitrary SQL translation or a query-result cache.
Start with the [snapshot guide](snapshot-projections.md).

## 1. Map Rows and Scope

An existing `@CacheEntity` generates `EntityCacheBinding.SOURCE`.
It combines table metadata and direct column decoding without a Spring dependency.
Source-only entities need no CRUD registration or separate entity cache.

```java
var orders = SnapshotSource.entity("orders", OrderEntityCacheBinding.SOURCE)
        .where(OrderEntityFields.status, "OPEN");
var items = SnapshotRelation.strings("items", "order_items", "order_id", "product_id")
        .where(SnapshotPredicate.in("order_id", orders.select(OrderEntityFields.id)));
var sources = SnapshotPlan.inputs(orders, items);
```

Replace the illustrative entity and column names with your schema.
The predicate becomes a prepared parameter. The nested selection remains a SQL subquery.
It does not read IDs into Java, then submit a second ID-list query.
The plan reads each declared source once on the same consistent connection.
JDBC fetches still require network round trips.

| Declaration | Meaning | Incorrect use |
|---|---|---|
| Source name | Unique name inside a plan | Duplicate names fail |
| SOURCE | Table, projected columns, decoder | Wrong mapping reads wrong data |
| where | Explicit scope | Missing rows can produce incomplete responses |
| select | One column for a subquery | Undeclared columns fail |
| inputs | Sources exposed to mapping | Undeclared source access fails |
| withoutPerSourceLimit | Remove only one table's row cap | Total row/byte/deadline budgets remain |

Repeated `where` calls use AND; combine alternatives with `or`.
`eq(field, null)` means IS NULL. Supported predicates are equality, IN subquery,
AND and OR. They do not parse arbitrary business expressions.
Mapped columns are validated. Generated fields help with types and names but do
not prove cross-table relationship semantics. Review source coverage and indexes.
Keep tables, columns and scope in trusted application definitions.
Mutable objects, raw SQL values and undeclared columns fail early.
For provider-specific SQL, retain an explicit trusted SELECT source.
Typed predicates cannot be appended to raw SELECT sources.

## 2. Read a Record Without an Entity ID

Write this public, top-level record in its own named-package source file:

```java
@CacheSourceRecord(table = "order_items")
public record OrderItemRow(
        @CacheColumn("order_id") String orderId,
        @CacheColumn("product_id") String productId,
        Integer quantity) {}
```

Import the annotations from `com.reactor.cachedb.annotations`.
The normal annotation processor generates `OrderItemRowSourceBinding.SOURCE`
and `OrderItemRowFields` during the same compilation. Do not hand-edit generated files.
Use `SnapshotSource.entity("items", OrderItemRowSourceBinding.SOURCE)`.
The factory name is shared; this record does not become a writable entity.

Supported components: String, UUID, int/Integer, long/Long, double/Double,
boolean/Boolean, BigDecimal, LocalDate, LocalDateTime and Instant.
Component names are literal column names unless overridden by CacheColumn.
Nested/generic records, unsupported fields, unsafe identifiers and duplicate columns
are compile errors. Conversion uses generated constructor calls, not reflection.
Missing columns fail. Boxed nulls remain null; numeric primitive nulls fail.
Primitive boolean treats null as false. Integral overflow and non-0/1 numeric booleans fail.
Use boxed types when absence differs from zero or false.

## 3. Prepare Lookups Once

```java
var productsByOrder = rows.lists(items);
var ordersByProduct = rows.membership(items.reverse());
var products = productsByOrder.get(orderId);
boolean inAny = ordersByProduct.containsAny(productId, requiredOrderIds);
boolean inAll = ordersByProduct.containsAll(productId, requiredOrderIds);
```

Here `items` is the two-column relation from section 1. Its direction is
order_id to product_id. The String relation helper reads those two columns only.
`reverse()` reuses the same source and does not query SQL again.
For other key types or additional columns use a record source and the generic
`rows.lists(source, key, value, comparator)` or `rows.membership(source, key, value)`.

| Helper | Contract |
|---|---|
| unique | One value per key; duplicates fail |
| lists(relation) | Lexically sorted targets; duplicates preserved |
| generic lists | Explicit comparator; duplicates preserved |
| membership | Deduplicated sets for existence checks only |
| Missing key | Empty list/set behavior |
| containsAll with empty input | true, including a missing owner |
| containsAny with empty input | false |
| Null relationship endpoint | Fails instead of silently dropping a row |

Use lists for JSON and sets for membership. Replacing a payload list with a set
changes duplicates and order. Two independent existence predicates must stay
independent; requiring one child to satisfy both can change business results.

For per-root DTOs, use `SnapshotLists.distribute(values, dto -> dto.rootIds())`.
Each DTO is assigned once per distinct root; the DTO's own relationship lists
are not changed. Input order is retained, so sort before distributing.
This avoids rescanning all DTOs for every root. It does not eliminate the memory
cost of genuine many-to-many assignments.

All lookups are per-refresh values, not global caches. Build them once in the
plan's mapping factory. No JDBC, Redis or HTTP calls belong in a root mapper.
Business eligibility and DTO assembly remain application code.

## 4. Upgrade Without Changing Results

1. Keep the existing JSON fixture or reference SQL.
2. Recompile with 0.12.0; replace metadata/codec boilerplate with SOURCE.
3. Replace each raw predicate separately and compare selected IDs.
4. Declare relationship direction and test absent, duplicate and reversed links.
5. Compare full payloads, including nulls, ordering, empty arrays and deletions.
6. Verify one connection, a fixed number of source queries and no SQL on GET.
7. Test timeout, lease loss, disk failure and source-budget failure.
8. Warm a separate namespace before changing storage layout or response semantics.

The publication, retry, lease and freshness contracts are unchanged. Failed
preparation must not replace a successful catalog. New helpers add no implicit
fallback, auto-warm on reads, unbounded retry or background synchronization.
Configure finite connection/socket timeouts and observe job duration, rows and bytes.
Keep source indexes and total heap, disk and Redis overlap budgets explicit.
