# Database Provider SPI Direction

Turkish version: [../tr/docs/veritabani-provider-spi.md](../tr/docs/veritabani-provider-spi.md)

This page defines how CacheDB should grow from PostgreSQL-only durability to
multiple SQL databases such as Microsoft SQL Server.

It is intentionally strict: adding MSSQL support must not turn the codebase into
a large set of `if (postgres) ... else if (mssql) ...` branches.

## Decision

BEST: introduce a JDBC provider SPI with database-specific dialect modules.

ACCEPTABLE: keep PostgreSQL as the only GA durability provider until the SPI and
MSSQL test lane are complete.

ANTI-PATTERN: claim plug-and-play MSSQL support by only changing the JDBC URL.

## Why A Provider SPI Is Required

PostgreSQL and MSSQL differ in production-critical behavior:

| Area | PostgreSQL | MSSQL |
| --- | --- | --- |
| Upsert | `INSERT ... ON CONFLICT` | `MERGE` or update-then-insert transaction |
| Bulk load | `COPY` | table-valued parameters, `BULK INSERT`, or batched statements |
| Temporary table | `CREATE TEMP TABLE ... ON COMMIT DROP` | `#temp` tables scoped to connection |
| Pagination | `LIMIT/OFFSET` | `OFFSET ... FETCH` or `TOP` |
| Timestamp type | `TIMESTAMPTZ` | `datetimeoffset` or `datetime2` |
| Identifier quoting | `"name"` | `[name]` |
| Failure classification | SQLSTATE-oriented | SQL Server error-code oriented |

These are not cosmetic differences. They affect correctness, idempotency,
retry behavior, and write latency.

## Proposed Module Shape

Target module split:

```text
cachedb-storage-jdbc
- JdbcWriteBehindFlusher
- JdbcDatabaseDialect
- JdbcFailureClassifier
- JdbcOutboxFeedAdapter
- common value binding and statement batching

cachedb-storage-postgres
- PostgresDatabaseDialect
- PostgresFailureClassifier
- optional COPY optimization

cachedb-storage-mssql
- MssqlDatabaseDialect
- MssqlFailureClassifier
- MSSQL-specific upsert and batch strategy
```

`cachedb-starter` should depend on provider-agnostic contracts and select the
provider from configuration.

## Required SPI Surface

The first SPI version should cover:

- identifier quoting and validation
- single-row upsert SQL
- multi-row upsert SQL or supported fallback
- delete with version guard
- checkpoint table DDL
- outbox checkpoint upsert
- pagination clause
- temporary table strategy
- failure classification
- batch capability flags
- connection/session initialization if needed

## Configuration Goal

Target user experience:

```properties
cachedb.database.provider=postgres
cachedb.database.jdbcUrl=jdbc:postgresql://localhost:5432/app
```

or:

```properties
cachedb.database.provider=mssql
cachedb.database.jdbcUrl=jdbc:sqlserver://localhost:1433;databaseName=app
```

The provider must be explicit. Auto-detecting from URL can be useful as a
convenience, but production configuration should be clear.

## MSSQL GA Gate

MSSQL should not be marked production-ready until these pass:

- write-behind upsert/delete idempotency tests
- duplicate id in one batch test
- stale version event test
- outbox checkpoint retry test
- deadlock/retry classification test
- batch-size and parameter-count limit test
- migration discovery and warm test on MSSQL metadata
- side-by-side comparison test against MSSQL baseline SQL
- Kubernetes multi-pod apply runner smoke test

## Current Boundary

Current public beta behavior remains PostgreSQL-first.

The immediate completed step is external outbox/CDC apply support for keeping
Redis fresh when PostgreSQL changes outside CacheDB. MSSQL support should be the
next storage-provider workstream, implemented through the SPI above rather than
by patching PostgreSQL classes in place.
