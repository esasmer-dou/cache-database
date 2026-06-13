# Database Provider SPI

Turkish version: [../tr/docs/veritabani-provider-spi.md](../tr/docs/veritabani-provider-spi.md)

CacheDB is still PostgreSQL-first in the public beta. The storage provider work
now has a first explicit SPI layer, so adding MSSQL is no longer a fake
"change the JDBC URL" story.

## Decision

BEST: choose the durable SQL provider explicitly and wire the matching
write-behind flusher.

ACCEPTABLE: use the default starter path for PostgreSQL while the application is
PostgreSQL-only.

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
  <version>0.1.0-beta.3</version>
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

The MSSQL flusher does not use `MERGE` by default. The safer beta path is:

1. Open one transaction.
2. Set isolation to `SERIALIZABLE`.
3. Try `UPDATE ... WITH (UPDLOCK, HOLDLOCK)` with a version guard.
4. If no row was updated, check row existence with the same lock hints.
5. If the row does not exist, insert it.
6. Commit or roll back the whole batch.

This favors correctness and idempotency over maximum bulk throughput. Bulk copy,
table-valued parameters, and MSSQL-specific outbox checkpoint SQL are separate
GA hardening items.

Large `flushBatch(...)` calls are split by `WriteBehindConfig.maxFlushBatchSize()`
so SQL Server does not hold one oversized serializable transaction for the full
Redis batch. If a later chunk fails, earlier chunks may already be committed;
the version guard keeps retry behavior idempotent.

## Provider Differences That Matter

| Area | PostgreSQL | MSSQL |
| --- | --- | --- |
| Upsert | `INSERT ... ON CONFLICT` | version-guarded update/existence/insert transaction |
| Bulk load | optional `COPY` path | not enabled in this beta SPI |
| Parameter limit | 65,535 parameters | 2,100 parameters |
| Temporary table | PostgreSQL temp table semantics | SQL Server `#temp` semantics, not wired yet |
| Failure classification | SQLSTATE-oriented | SQL Server vendor-code-oriented |
| Production status | default public beta provider | explicit beta provider, not GA |

## Current MSSQL Gate

MSSQL is usable for explicit beta testing of write-behind semantics, but it is
not production-certified yet.

Now covered by the provider evidence lane:

- real SQL Server integration lane, not only unit-level SQL recorder tests
- parameter-limit and batch-size regression tests against a live SQL Server
- MSSQL outbox/checkpoint adapter
- migration discovery, warm, and side-by-side comparison on SQL Server metadata
- multi-pod apply runner smoke test with MSSQL as durable storage

Still required before MSSQL GA:

- stale version, duplicate id, deadlock, timeout, and lock-conflict tests at
  larger concurrency, not only smoke scale
- longer SQL Server soak/restart/retry evidence
- MSSQL outbox ownership strategy for concurrent active-active pollers
- MSSQL migration warm and comparison against realistic table volumes
- dashboard/reporting labels that separate PostgreSQL and MSSQL storage metrics

Until those gates are complete, MSSQL remains an explicit beta provider.
