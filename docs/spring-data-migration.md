# Spring Data Migration

CacheDB 0.19 adds the opt-in cachedb-spring-data artifact. It uses Spring Data Commons managed by the
Spring Boot 3.3.5 BOM. Existing core/processor consumers gain no Spring Data dependency.

## Migration

Before, an application may expose Spring Data store repositories:

```java
interface Customers extends ListCrudRepository<Customer, Long>,
        ListPagingAndSortingRepository<Customer, Long> {
    Page<Customer> findByActive(Boolean active, Pageable pageable);
}
```

After, keep the persisted entity and generated CacheDB binding in the same annotation-processing source build:

```java
@CacheEntity(table = "customer", redisNamespace = "customer")
public record Customer(@CacheId(column = "id") Long id,
        @CacheColumn("active") Boolean active,
        @CacheColumn("name") String name) {}

@CacheRepository(entity = Customer.class)
public interface Customers extends CacheDbSpringDataRepository<Customer, Long> {
    Page<Customer> findByActive(Boolean active, Pageable pageable);
    Window<Customer> findFirst100ByActiveOrderByIdAsc(Boolean active, KeysetScrollPosition position);
}
```

Add cachedb-spring-data using the CacheDB BOM, retain cachedb-processor on the annotation processor path,
and import CustomersCacheDbConfiguration in the application's Spring configuration.
The generated factory injects CacheSession; a normal CacheDatabase bean implements that contract.
Register the generated entity binding with the existing CacheDB runtime as before.
Do not include these packages in JPA/other Spring Data runtime repository scans.

The concrete CustomersCacheDbImplementation extends a bounded execution adapter and implements the interface.
Generated configuration creates it directly. There is no query proxy, reflective field accessor, runtime method-name
parser, classpath repository scan or extra executor. A non-Spring application can construct it with CacheSession.
The same thread and backend preserve tenant, route and failure checks.
Causal point/findAll reads retain native version verification. Query/page/count/derived-delete and aggregate-like
reads are NOT certified by context propagation: relevant active causal requirements fail closed unless an explicit
applied-version protocol exists. A write receipt does not certify arbitrary derived queries as read-your-writes.

This is not a transparent JPA/SQL-history replacement. Query/count/delete-all follow the existing bounded CacheDB
repository contract and active data policy. Source loading, SQL durability, ordering consistency and transactions
are not strengthened by the Spring Data method names.

## Supported Query Contract

- Read prefixes: find/read/get/query with By, optional Top/First and positive N. AllBy aliases and predicate-free Top/First are supported.
- Operators: Equals, Is, IsEquals, Not/IsNot, In/IsIn, NotIn/IsNotIn, GreaterThan, GreaterThanEqual,
  LessThan, LessThanEqual, Between/IsBetween, IsNull, IsNotNull, True/IsTrue, False/IsFalse, StartingWith.
- And combines predicates. User-declared Or, Like, IgnoreCase, nested relation paths and arbitrary custom methods fail compilation.
- OrderBy accepts several persisted Java properties with Asc/Desc. Dynamic Sort uses generated Java-property-to-column mappings.
  Unknown properties, conflicting duplicate directions, IgnoreCase and explicit null-ordering requests fail, not silently downgrade.
- Returns: entity, Optional<Entity>, List<Entity>, Page<Entity>, Slice<Entity>, Window<Entity>, long/Long and boolean/Boolean.
  deleteBy returns long/Long, void or List<Entity>. Stream, raw containers, projections and unresolved generics fail compilation.
- Page/Slice requires Pageable. A List can also take Pageable. Sort and Limit are supported for bounded list reads.
  Window requires ScrollPosition or KeysetScrollPosition and supports Limit or a static Top/First size.
- Pageable cannot be combined with Sort/Limit; Top/First cannot be combined with Limit.
  Single results support Sort and Top/First1, not Pageable/Limit/TopN>1.
  Count/exists/delete do not accept paging/sorting/scrolling/limits.
- Single reads without First/Top request two rows and fail on non-uniqueness. Missing entity results are null; Optional is empty.
- Concrete repositories must be top-level with resolved entity/ID types. Generic parents/fragments and CRUD redeclarations are resolved at compile time.
  Generic derived methods and declared throws clauses are not supported.
- At most 256 persisted properties and 256 derived methods, 32 predicates/operands, 15 static sort properties plus the ID tie-breaker,
  16 total sort fields and a 1,024-character method name are admitted.
- Derived predicates and sort properties must be supported scalars: strings, booleans, boxed numeric types, BigDecimal/BigInteger,
  UUID, supported java.time values or enums. Persisted complex fields can still be saved, but cannot become derived predicates.
  Enum operands are generated as name() to match the existing codec's stored representation.
  Custom-codec/encrypted properties are not derivable here; use their separately approved typed APIs instead.
  IN operands are typed Iterable values; Java arrays and raw/mismatched collections fail compilation.

NOT IN lowers to AND of NE filters because the core has no native NOT IN operator; Between lowers to GTE/LTE.
IN is capped at 512 operands; NotIn deliberately allows only 32 exclusions to bound its core planner/materialization cost.
Use a positive indexed partition predicate and a conservative runtime candidate limit for exclusion-heavy reads.
An unlimited or 512-value NOT IN expansion is not a production recommendation. Core planning still needs workload validation.

StartingWith requires an explicit source contract:

```java
@CacheRepository(entity = Customer.class)
@CacheDbSpringDataOptions(prefixIndexedColumns = {"name"})
public interface Customers extends CacheDbSpringDataRepository<Customer, Long> {
    List<Customer> findByNameStartingWith(String prefix);
}
```

The property must be an indexed String, and runtime queryIndex.prefixIndexEnabled must be true.
Generated Spring configuration checks CacheDatabase's actual configuration; manual/custom CacheSession construction must
supply an explicit true prefix-index assertion. The plain CacheSession constructor fails for repositories needing prefixes.
Enable and rebuild the existing prefix indexes before using this contract on old data.
The annotation and enablement check cannot prove Redis index completeness: no independent prefix-readiness SPI exists.

## Bounds And Semantics

CRUD methods delegate to EntityRepository and preserve generated-ID allocation using the existing CacheSession id generator.
Only the exact generated entity runtime type is accepted for persistence; entity subclasses/proxies are rejected before mutation.
saveAll validates and materializes at most 1,000 rows before calling the backend bulk-save API; it does not issue N individual saves.
findAllById admits at most 10,000 IDs and uses batches of 512. Iterable inputs and null members are checked before writes.
Delete inputs and deleteBy/deleteAll results are capped at 10,000. Selection uses at most
min(effective entity query capacity, 10,001) rows. An exactly full selection is ambiguous and aborts
before any delete, even when the actual match count might equal that capacity. All selected IDs and
tenants are validated before calling bulkDelete; deletion never advances through a shrinking index.

EntityRepository.entityQueryCapacity() reports UNKNOWN, UNRESTRICTED, or FINITE(maxRows), including
FINITE(0). Redis reports the current effective read-shape/policy budget without I/O; the native guards
still apply. This is not authorization, a query snapshot, or proof of source/index completeness.
Legacy/custom implementations default to UNKNOWN: explicit Pageable/Limit requests retain backend
validation, but unpaged List/Page and derived-delete selection fail closed until the implementation
advertises a known capacity. The infrastructure-free InMemory repository reports UNRESTRICTED;
this does not simulate Redis admission or transport restrictions. Delegating views must forward the contract.

An unpaged result selects at most min(capacity, 100,000) rows and is returned only when fewer rows
are found. At the cap it fails with advice to use Pageable or Limit; it never silently truncates.
Explicit bounded reads are checked against a known finite capacity before query/count I/O, including
Slice/Window lookahead. UNKNOWN does not bypass backend checks. A zero capacity rejects all positive
entity queries; it cannot be repaired by chunking. Use trusted known IDs with bulkDelete/deleteAllById,
or a separately authorized bounded administrative job. Unrestricted means no row-policy restriction,
not removal of QuerySpec, transport, memory, tenant, causal or encryption limits. Selection and mutation
remain separate: concurrent writes are not frozen, and the complete operation is not transactional.
Bulk deletion delegates to the additive EntityRepository.bulkDelete capability, in native chunks of at most 128 IDs.
It is NOT CacheDatabase.writeBatch: each row is accepted independently and there is no whole-call rollback.
The complete input/count limit is checked before mutation; tenant owner markers are checked in bounded batches
before the first delete and rechecked atomically with version/tombstone/write-behind acceptance. No foreground SQL
ownership bootstrap is performed, including protected tenants and cold IDs. Missing markers fail closed; provision
ownership through the application's trusted bootstrap/write protocol rather than trusting query-returned entities.
The native path reuses the regular delete body, index-removal planner, L1 invalidation and watcher contracts.
Synchronous projection cleanup checks the exact parent receipt version and tombstone atomically with removal;
a newer parent version supersedes old cleanup and its new projection is not erased.
Bulk deletion completes bounded synchronous projection cleanup even for an asynchronous refresh binding.
It emits no per-ID projection queue events. Include projection fanout in the caller latency budget.
Async refresh workers atomically publish payload and canonical index changes only while the parent version,
tombstone and tenant ownership still match their snapshot. A stale worker cannot resurrect a deleted row or erase
a newer projection; it leaves the original event pending for a later recovery pass rather than relabelling the result.
A legacy DELETE event reconciles current state too, preserving later recreations. Normal single-delete enqueueing is unchanged.
Foreground warming or forced publication for an async binding queues identity-only refresh requests in chunks of 128;
it never directly publishes a possibly stale projection. The worker re-reads current parent state. Upgrade all projection
workers before using this behavior. Existing query-cache consistency and non-causal projection-read contracts are unchanged.
Purge-protected mappings remain blocked by TenantProtectionGate; no generation is inferred from the active tenant.
Registered non-purge mappings whose encrypted fields are all REDIS_ONLY support identity-only bulk DELETE with a bound
codec, Q1 identity validation, canonical typed IDs, distinct ID/version roles and atomic tenant ownership checks. No encrypted field value is accepted.
The existing identity/version DELETE queue contract is reused; mappings that also encrypt source columns remain unsupported.
Missing ownership markers or unknown/zero Redis versions fail closed, without source queries or ownership bootstrap.
Index and hot-set maintenance targets must belong to the registered mapping. With watchers, the previous encrypted payload
is decoded and its ID/tenant verified before mutation; missing prior payload rejects that chunk before mutation.
The key provider is validated when binding. Without watchers DELETE does not read or re-encrypt protected fields, so it
requires no fresh per-delete AES/HMAC operation. Point deletion is unchanged. It requires the built-in atomic function library, runtime read state, and an explicit tenant scope for tenant
mappings. Custom function source overrides must implement a separate bulk capability; they are not silently interpreted.
Native admission is limited to two active bulk deletes per runtime. Snapshot and command framing each have a 16 MiB
bound, at most 8192 maintenance commands per chunk, with the existing finite read deadline and client timeouts.
Cluster atomic keys require the configured shared hash-tag scope. Unsupported cross-slot layouts fail before mutation.
Legacy/custom repositories without BULK_DELETE retain a safe singleton delete; a multi-ID call fails before mutation.
BulkDeleteFailureException reports accepted Redis receipts, definitely rejected IDs, uncertain IDs, cleanup-pending IDs,
and the number not attempted. A network failure may have accepted an unknown subset. Never blindly retry uncertain IDs:
reconcile their version/durability first. Confirmed acceptance does not claim SQL durability or successful later cleanup.
Bulk saves likewise inherit backend partial-failure semantics. Retrying a save with a null generated ID can allocate a new identity.
Use stable/preallocated IDs and the existing receipt/version APIs for explicit recovery; this adapter adds no idempotency key protocol.

Page size is at most QuerySpec.MAX_LIMIT (100,000); offsets above 10,000 fail before I/O and recommend Window.
Page totals call count(QuerySpec) and respect static Top/First bounds. Count and rows are separate observations, not a snapshot.
Slice uses one lookahead row without a count round trip; its maximum explicit size is 99,999.
Unpaged Lists fail closed on an exactly full 100,000-row safety window instead of silently returning a possibly truncated result.
An explicit Top/Limit is an intentional result bound. Lower backend materialization/admission limits remain authoritative.

Window reuses KeysetPagination and WindowCursor, with a stable ID tie-breaker and 1..1,000 rows plus one lookahead.
Forward and backward positions are supported. Offset positions are rejected.
Use Window.positionAt(index) for continuation: arbitrary caller-assembled Spring key maps are not accepted.
Positions are bound to repository/method, tenant/admin scope, sort order and a bounded typed contract of
the actual predicate tree and argument values. Unencrypted mappings retain their SHA-256 contract format.
Encrypted mappings use the runtime-bound codec's domain-separated HMAC-SHA256 capability with key material
independent from AES. No intermediate SHA-only digest of logical operands is exposed or included in a position.
The keyed contract also binds entity/table/namespace, schema/column types and encryption descriptors.
Referenced protected EQ literals contribute their canonical blind tokens, binding each referenced ring's HMAC material.
Missing binding or fingerprint capability rejects initial and continuation Windows before backend query I/O.
Changing a filter, including a Boolean or ID value on the same method,
rejects an old position before backend query I/O. Type tags and length prefixes prevent ambiguous text concatenation;
unsupported operand objects are rejected without invoking a generic Object.toString.
Fingerprinting preserves predicate and IN-value order and decimal scale. Equivalent predicates expressed differently
may conservatively reject a cursor; keep the same typed filter arguments throughout a scroll.
AES rotation preserves protected positions while HMAC material is unchanged; an HMAC-key change requires
a coordinated cursor/index migration. Protected contract admission is bounded by 16,384 framed parts and a
conservative 4 MiB content budget, in addition to ordinary query limits. Malformed protected UTF-16 is rejected.
Only a local contract representation is signed: logical residual/source filters are not rewritten.
This does not enable currently unsupported encrypted derived predicates or protected sort roles.

Positions are query-contract tokens, not authenticated bearer credentials: tenant authorization remains mandatory.
Null sort values and values not reversibly supported by WindowCursor fail deterministically.
The reversible set includes String, boxed primitive numeric values, BigDecimal, Boolean, Instant, LocalDate,
LocalDateTime, OffsetDateTime and UUID; raw enum/BigInteger cursor values are rejected unless the codec already exposes a supported representation.
Tokens retain the existing 8,192-character/16-value limits; the accumulated position content is capped at 16 MiB.

Operands have at most 4,096 characters, core numeric precision/scale limits, 4,096 structured nodes and a conservative
16 MiB aggregate operand-content budget. Non-finite numeric values are rejected. No silent truncation or swallowed backend error is added.
Queries request the existing 30-second provider query timeout; Redis socket/connection timeouts remain authoritative.
This is not a 30-second end-to-end deadline. Bulk delete separately checks the runtime's finite operation deadline. No extra retry loop, thread pool, shared cache or unbounded queue is introduced.
Observe underlying CacheDB latency, failures, candidate cardinality and pool pressure; apply caller concurrency/backpressure as for direct APIs.

## Build And Acceptance

Source-retained CacheDB annotations must be available to the processor: a separate precompiled entity JAR without its source
contracts is not sufficient for deriving these methods. Generated bindings and entities must be rebuilt together as required by the existing generator.

Source builds require cachedb-spring-data in the reactor and BOM, aligned parent versions,
and all required suites registered in the full-environment CI gate.
SpringDataRepositoryTest, SpringDataProcessorContractTest, SpringDataQueryBudgetTest,
SpringDataQueryFingerprintTest and SpringDataExecutionLimitsTest cover
CRUD, every required operator, inheritance, compile failures, paging, cursor isolation and admission bounds.
SpringDataRedisIntegrationTest is gated with cachedb.snapshot.redis=true and the existing host/port properties
(default 127.0.0.1:16383); it uses real Redis plus an isolated H2 ownership-source fixture and generated Spring beans.
Its cleanup is prefix-scoped and bounded; it never flushes shared Redis or deletes the shared function library.

Release acceptance requires Java 21 compilation, all required suites, unchanged regression tests and full-reactor/runtime validation.
Passing generator text assertions alone is not runtime or Redis acceptance.

The API mapping was checked against the official
[Spring Data Commons 3.3.5 Window API](https://github.com/spring-projects/spring-data-commons/blob/3.3.5/src/main/java/org/springframework/data/domain/Window.java)
and [ScrollPosition API](https://github.com/spring-projects/spring-data-commons/blob/3.3.5/src/main/java/org/springframework/data/domain/ScrollPosition.java).
