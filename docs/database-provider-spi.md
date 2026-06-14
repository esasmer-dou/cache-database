# Database Provider SPI

Turkish version: [../tr/docs/veritabani-provider-spi.md](../tr/docs/veritabani-provider-spi.md)

CacheDB uses PostgreSQL as the default durable SQL provider and has an explicit
storage-provider SPI. MSSQL is available as an explicitly selected provider
with its own SQL Server evidence lane, so this is no longer a fake "change the
JDBC URL" story.

## Decision

BEST: choose the durable SQL provider explicitly and wire the matching
write-behind flusher.

ACCEPTABLE: use the default starter path when the application intentionally
uses the default PostgreSQL provider.

ANTI-PATTERN: point a PostgreSQL flusher at an MSSQL JDBC URL and assume the
same SQL, retry rules, and parameter limits are safe.

## Current Module Shape

```text
cachedb-storage-jdbc
- JdbcDatabaseDialect
- JdbcWriteBehindSupport
- SqlFailureClassifierSupport
- JdbcOutboxExternalChangeFeedAdapter
- shared value conversion, row-limit, and batch partition helpers

cachedb-storage-postgres
- PostgresDatabaseDialect
- PostgresWriteBehindFlusher
- PostgresFailureClassifier
- PostgresOutboxDialect
- PostgreSQL ON CONFLICT and optional COPY path

cachedb-storage-mssql
- MssqlDatabaseDialect
- MssqlWriteBehindFlusher
- MssqlFailureClassifier
- MssqlOutboxExternalChangeFeedAdapter
- SQL Server update/existence/insert write path
```

`cachedb-starter` keeps PostgreSQL as the default flusher for backward
compatibility. Non-PostgreSQL users must provide a `WriteBehindFlusherFactory`
explicitly.

## PostgreSQL Usage

PostgreSQL users do not need extra provider wiring when using the starter:

```java
CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, postgresDataSource)
        .register(db -> {
            // register generated entities and projections
        })
        .start();
```

The default flusher remains `PostgresWriteBehindFlusher`.

## MSSQL Usage

Add the MSSQL storage module and a Microsoft SQL Server JDBC driver. CacheDB does
not force a driver version transitively; the application owns the `DataSource`.

```xml
<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-storage-mssql</artifactId>
  <version>0.1.0</version>
</dependency>

<dependency>
  <groupId>com.microsoft.sqlserver</groupId>
  <artifactId>mssql-jdbc</artifactId>
  <version><!-- choose the version approved by your platform --></version>
</dependency>
```

Wire the flusher explicitly:

```java
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, mssqlDataSource)
        .writeBehindFlusherFactory(MssqlWriteBehindFlusher::new)
        .register(db -> {
            // register generated entities and projections
        })
        .start();
```

This is intentionally explicit. It prevents an application from silently running
PostgreSQL SQL against SQL Server.

For database-originated events, use the MSSQL outbox adapter explicitly:

```java
import com.reactor.cachedb.mssql.MssqlOutboxExternalChangeFeedAdapter;

MssqlOutboxExternalChangeFeedAdapter adapter =
        MssqlOutboxExternalChangeFeedAdapter
                .builder(mssqlDataSource)
                .adapterName("orders-hotset")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(200)
                .build();

adapter.start(externalChangeApplyRunner);
```

## MSSQL Write Semantics

The MSSQL flusher does not use `MERGE` by default. The safer provider path is:

1. Open one transaction.
2. Set isolation to `SERIALIZABLE`.
3. Try `UPDATE ... WITH (UPDLOCK, HOLDLOCK)` with a version guard.
4. If no row was updated, check row existence with the same lock hints.
5. If the row does not exist, insert it.
6. Commit or roll back the whole batch.

This favors correctness and idempotency over maximum bulk throughput. Bulk copy
and table-valued parameters are separate GA hardening items.

Large `flushBatch(...)` calls are split by `WriteBehindConfig.maxFlushBatchSize()`
so SQL Server does not hold one oversized serializable transaction for the full
Redis batch. If a later chunk fails, earlier chunks may already be committed;
the version guard keeps retry behavior idempotent.

## Provider Differences That Matter

| Area | PostgreSQL | MSSQL |
| --- | --- | --- |
| Upsert | `INSERT ... ON CONFLICT` | version-guarded update/existence/insert transaction |
| Bulk load | optional `COPY` path | intentionally not enabled by default; use route-specific batches first |
| Parameter limit | 65,535 parameters | 2,100 parameters |
| Temporary table | PostgreSQL temp table semantics | SQL Server `#temp` semantics, not wired yet |
| Failure classification | SQLSTATE-oriented | SQL Server vendor-code-oriented |
| Production status | default provider path | explicit provider with SQL Server CI evidence; HA topology still environment-specific |

## Current MSSQL Gate

MSSQL is usable as an explicit provider for write-behind, outbox, migration
planner, and multi-pod apply-runner behavior. The provider-level claim is now
CI-backed for SQL Server correctness and regression coverage. The remaining
boundary is not "does the provider work"; it is whether the consuming
application has certified its own SQL Server topology, data volume, route
inventory, and rollback plan.

Now covered by the provider evidence lane:

- real SQL Server integration lane, not only unit-level SQL recorder tests
- parameter-limit and batch-size regression tests against a live SQL Server
- high-volume write-behind load with stale version and delete checks
- concurrent same-id write races with duplicate-id and stale-version pressure
- retryable timeout, deadlock, and lock-conflict failure classification
- live SQL Server lock-timeout classification under a blocked row
- MSSQL outbox/checkpoint adapter
- migration discovery, warm, and side-by-side comparison on SQL Server metadata
  with representative windowed table volume
- multi-pod apply runner smoke test with MSSQL as durable storage
- lock-guarded checkpoint table bootstrap during concurrent pod startup
- duplicate-key safe checkpoint row bootstrap during concurrent polling
- concurrent same-`adapterName` polling protected by checkpoint row locks
- provider-tagged durable SQL write performance breakdowns (`mssql:*`)
- single-node SQL Server container restart/reconnect regression

Still required before claiming a specific MSSQL production topology is ready:

- run the provider evidence lane against the application's approved SQL Server
  version, JDBC driver, connection pool, schema size, and indexes
- run a longer SQL Server soak/retry test under production-sized data and real
  traffic mix
- prove SQL Server HA or Always On failover in staging if that topology is part
  of the application's availability claim
- design partitioned outbox ownership if a route needs active-active polling
  throughput; same `adapterName` is intentionally serialized for safety
- run Migration Planner warm and comparison for every production route in the
  application's migration inventory
