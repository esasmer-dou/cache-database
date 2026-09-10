# Declarative Snapshot Projections

Use SnapshotPlan when a REST response combines several tables and must remain available during source outages.
The application declares source mappings, business rules and settings.
CacheDB owns JDBC iteration, disk spooling, batching, leases and safe publication.

Available since CacheDB 0.11.0. The source runner supports PostgreSQL, SQL Server and Oracle.
H2 is used for isolated tests. Unknown database products fail before source iteration.

## Database Prerequisites

| Provider | One refresh transaction | Prerequisite |
|---|---|---|
| PostgreSQL | Read-only REPEATABLE READ | SELECT rights on declared sources |
| SQL Server | SNAPSHOT isolation | DBA enables ALLOW_SNAPSHOT_ISOLATION for the source database |
| Oracle | SET TRANSACTION READ ONLY | SELECT rights; sufficient undo retention |

CacheDB does not run ALTER DATABASE or grant permissions. SQL Server READ_COMMITTED_SNAPSHOT
alone is not the required transaction-wide isolation. A disabled prerequisite fails the warm
without replacing the last successful catalog. Borrowed connections are rolled back and their
isolation, read-only and auto-commit settings restored before returning to the pool.
Use a dedicated SELECT-only database account, especially on SQL Server where JDBC readOnly
is not an authorization boundary. Keep native predicates provider-specific; generated entity
columns use quoted identifiers. For Oracle, the entity factory follows unquoted uppercase
object names and preserves codec column aliases. For deliberately mixed-case quoted Oracle
objects use an explicit SnapshotSource SELECT. LOB and structured source values are rejected; explicitly
cast a bounded scalar value or keep that route in a separate SQL adapter.

See [Microsoft isolation guidance](https://learn.microsoft.com/en-us/sql/connect/jdbc/understanding-isolation-levels)
and [Oracle read consistency](https://docs.oracle.com/en/database/oracle/oracle-database/21/adfns/sql-processing-for-application-developers.html).

## Declare a Plan

```java
@Bean
SnapshotPlan<CustomerEntity, OrderSummary> customerOrders() {
    var customers = SnapshotSource.entity("customers",
            CustomerEntityCacheBinding.METADATA, CustomerEntityCacheBinding.CODEC, "");
    var orders = SnapshotSource.entity("orders",
            OrderEntityCacheBinding.METADATA, OrderEntityCacheBinding.CODEC, "status = 'OPEN'");
    return new SnapshotPlan<>("customer-orders", customers, row -> row.id.toString(),
            List.of(customers, orders), OrderSummary.class, rows -> {
                var byCustomer = rows.group(orders, row -> row.customerId,
                        OrderSummary::fromEntity, Comparator.comparing(OrderSummary::date).reversed());
                return customer -> byCustomer.getOrDefault(customer.id, List.of());
            });
}
```

The entity fields and DTO factory above are illustrative: use your actual model.
Generated bindings provide column decoding. Relationship tables without single-column IDs can use typed SnapshotSource<Link> SELECT declarations.
SQL and predicates must be trusted, static application definitions, never HTTP input.
Mapping is pure business logic. Build relationship lookups once. Do not make JDBC, Redis or external calls in mapping.

```properties
cachedb.snapshots.jobs.customer-orders.enabled=true
cachedb.snapshots.jobs.customer-orders.interval=PT1M
cachedb.snapshots.jobs.customer-orders.warn-age=PT3M
cachedb.snapshots.jobs.customer-orders.max-age=PT30M
cachedb.snapshots.jobs.customer-orders.retention=PT1H
cachedb.snapshots.jobs.customer-orders.batch-rows=256
cachedb.snapshots.jobs.customer-orders.batch-target-size=4MB
```

Spring discovers the bean and starts an immediate attempt, followed by fixed-delay attempts.
A shared completion marker prevents other pods repeating a recently completed scheduled job.
This is not a cron guarantee. Job duration and pod schedules affect the actual refresh interval.
Unknown options and settings for undeclared plans fail startup.

## Read and Refresh

```java
SnapshotRepository repository = jobs.repository("customer-orders");
Optional<SnapshotValue> result = repository.findById(customerId);
SnapshotRefreshResult refreshed = jobs.refresh("customer-orders", true);
```

Inject SnapshotOperations as jobs. True requests a manual attempt.
The repository reads a completed generation only. It never queries SQL or starts a warm.
Each root holds a JSON array serialized using the application's ObjectMapper.
Absence differs from a prepared empty array.
Apply authorization and freshness checks at the application boundary using SnapshotValue.refreshedAt().
warn-age and max-age are validated consumer settings; this low-level repository does not automatically return HTTP errors.

enabled=false disables scheduling, not authorized manual execution.
A concurrent attempt returns BUSY rather than queuing. A scheduled attempt within the interval returns NOT_DUE.
Secure manual endpoints and use bounded execution with finite HTTP timeouts.

This is ID-addressed snapshot storage, not arbitrary ProjectionRepository.query or implicit SQL fallback.
plan.map(name, type, mapper) reuses business rules in another plan. Separate plans read separate SQL snapshots and publish independently.

For a dedicated SELECT-only application, call SnapshotReadOnlyProfile.configure(builder, keyPrefix) from a CacheDatabaseConfigCustomizer.
Disable automatic entity registration when entities are source-only metadata.
The profile disables write-behind, schema mutation, fallback hydration and indexing.
Do not apply it to an application needing CacheDB writes. Use a SQL account without DML/DDL rights.

## Publication Contract

1. Acquire a renewable Redis lease with a unique owner.
2. Read all sources on one provider-specific, transaction-consistent connection.
3. Prepare all root responses in one private temporary disk file.
4. Stage byte/row-bounded batches in a new Redis generation hash.
5. Check ownership and root count; atomically change the active-generation pointer.
6. Unlink the previous generation and close the temporary file.

Every stage and final publication checks ownership inside Redis Lua.
A paused former owner cannot overwrite its successor.
Failures before commit preserve the previous catalog. Deleted roots disappear after successful publication.
An uncertain commit reply is reported as an error, but cleanup never deletes the active generation.
Readers retry once if cleanup races their read. Separate reads can observe different generations.

These checks assume the same authoritative Redis primary.
They do not prevent asynchronous Redis failover data loss or split-brain writes.
Use a dedicated allocation and consider noeviction: arbitrary eviction makes a complete catalog unavailable.
Keys use a common hash tag, but auto-configuration uses JedisPooled, not a Redis Cluster client.

## Resource Settings

All settings are under cachedb.snapshots.jobs.<plan>.

| Setting | Default | Purpose |
|---|---|---|
| preparation-timeout / timeout | PT90S / PT110S | Preparation / total work deadline |
| lease-duration | PT2M | Renewed every third of the duration |
| batch-rows / fetch-rows | 256 / 256 | Redis batch roots / JDBC fetch hint |
| batch-target-size | 4MB | Soft byte target; a larger root goes alone |
| max-source-rows | 300000 | Total decoded rows |
| max-rows-per-source | 100000 | Per-source decoded rows |
| max-source-size | 64MB | Source value UTF-8 estimate, not heap |
| payload-warning-size / catalog-warning-size | 1MB / 64MB | Log only; zero disables |
| spool-directory | JVM temporary directory | Existing, private, disk-backed directory |

withoutPerSourceLimit() disables only the per-source cap; global budgets remain.
Overflow fails the whole preparation, never silently truncates.
JDBC statements use at most 30 seconds each. Configure finite pool acquisition, connection and socket timeouts as well.
Preparation must finish before timeout. interval + timeout < max-age; retention > max-age + timeout.
Reads use the foreground Redis pool; publication and lease work use the background pool.

Source rows and indexes occupy heap. JSON is disk-spooled, but one batch and one large response still occupy heap.
Redis temporarily holds old and new catalogs. Budget both plus overhead, other data and failed-generation TTL cleanup.
Use disk-backed Kubernetes emptyDir with ephemeral-storage limits; never memory-backed spool storage.
Restrict directory permissions. Logs report job, rows, JSON bytes and duration; measure Redis memory separately.

## Test and Upgrade

```shell
mvn -pl cachedb-spring-boot-starter -am "-Dtest=SnapshotContractTest,SnapshotJobsRedisTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcachedb.snapshot.redis=true" test
```

Default test Redis: 127.0.0.1:16383; override cachedb.snapshot.redis.port.
Tests use random namespaces and H2, never FLUSHDB or the application database.
Provider integration tests verify concurrent mutation consistency, publication, deletion, failed warm preservation and pooled connection reset on PostgreSQL, SQL Server and Oracle. The snapshot-provider-evidence CI lane requires all three engines; it does not silently skip unavailable providers.
Use a new namespace when migrating from per-entity projection storage.
Warm and compare before switching traffic; retain the old namespace and JAR for rollback.
