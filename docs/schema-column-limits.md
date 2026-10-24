# Database-Aware Column Limits

Turkish: [Kolon ve alan sınırları](../tr/docs/sema-kolon-sinirlari.md)

Availability: 0.15.1 and later. The published
0.15.0 artifacts retain their original 1024-field codec/processor ceiling.

## Two Different Limits

CacheDB bounds serialized fields independently from SQL table columns. Increasing
the first limit does not increase the capacity of a database or make a wide table
a good design.

| Layer | Limit | Behavior |
| --- | --- | --- |
| Entity payload, projection schema, Redis index mutation, admin export | 4096 fields | Reject larger input; payload-size and other budgets still apply |
| Oracle, default | 1000 table columns | Use a positive JDBC capability value when available |
| SQL Server, standard tables | 1024 table columns | Sparse/wide-table DDL is not generated automatically |
| PostgreSQL | 1600 table columns | Row size and dropped column slots can impose stricter constraints |
| H2, ANSI/custom dialect fallback | 1024 table columns | Custom dialects may declare their own bounded default |

For a connected operation, `DatabaseMetaData.getMaxColumnsInTable()` supplies the
active limit. A positive value wins, capped at 4096. Zero, a negative value or
`SQLFeatureNotSupportedException` uses the dialect default. Other SQL errors
propagate; a broken connection is not treated as an unknown capability.

CacheDB does not issue an additional catalog query to resolve this limit, and
resolves it once per schema operation or write transaction, not per row. The JDBC
driver controls whether its metadata method uses cached data or network traffic.
This is a capability guard, not a catalog scan proving that every proposed DDL
statement will succeed.

## What Counts?

Count physical columns, including the ID and any additional version or soft-delete
columns. A version/deleted column already present in the mapping counts once.
Migration planning also includes existing, unmapped table columns before proposing
an `ADD COLUMN` operation.

| Example | Total | Result with default capability |
| --- | --- | --- |
| Oracle: 998 mapped columns including ID, plus version and deleted | 1000 | Within the count budget |
| Oracle: 999 mapped columns including ID, plus version and deleted | 1001 | Rejected before business DDL |
| PostgreSQL: 1199 mapped columns including ID, plus version | 1200 | Within the count budget |
| PostgreSQL: 1600 mapped columns plus a separate version | 1601 | Rejected |
| Redis-only projection: 2000 fields | 2000 | Codec accepts it; this is not permission to create a 2000-column PostgreSQL table |

SQL statement parameter limits are separate. An incompatible single-row statement
fails rather than silently reducing its batch size to one. Batches that do fit
retain the provider's existing batching strategy and parameter budget.

## Validate Before Deployment

Use your registered entity metadata and the application DataSource. Validation
does not create business tables:

```java
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.starter.CacheDatabaseSchemaAdmin;

var schema = new CacheDatabaseSchemaAdmin(
        dataSource, entityRegistry, SchemaBootstrapConfig.defaults());
var result = schema.validate();
if (!result.success()) {
    throw new IllegalStateException("Schema validation failed: " + result.issues());
}
```

To inspect the resolved capability explicitly:

```java
import com.reactor.cachedb.jdbc.JdbcSchemaDialects;

try (var connection = dataSource.getConnection()) {
    var dialect = JdbcSchemaDialects.resolve(connection);
    int limit = dialect.resolveMaxColumnsInTable(connection.getMetaData());
    System.out.println(dialect.name() + " table column limit: " + limit);
}
```

The matching CacheDB provider must be on the classpath. This metadata check does
not silently enable a missing provider. DDL export and migration planning reject
an oversized definition with `IllegalArgumentException`. Bootstrap reports it as
a schema issue. Write-behind rejects oversized shapes before business SQL and
transactional fence updates. Initial fence-table setup may still occur first.
Invalid queued writes remain errors to investigate; do not repeatedly retry an
unchanged schema violation as though it were a transient connection failure.

## Wide Entities and Oracle Extended Mode

Mutable `@CacheEntity` classes can declare up to 4096 persisted fields. The
processor emits a note above 1000 fields because SQL providers have different
limits. Large generated codecs and metamodels use bounded helper methods to stay
within the JVM's per-method bytecode limit. Ordinary small entities keep their
existing direct code path; no runtime reflection is introduced.

Java records have an additional JVM constructor limit: 255 parameter slots,
including the receiver; primitive `long` and `double` use two slots. Use a mutable
class for wide entities. Raising a payload limit cannot remove this JVM rule.

Oracle's extended limit requires an appropriately configured database and a
compatible driver that advertises it. CacheDB does **not** enable `MAX_COLUMNS`,
change database parameters, or infer extended mode from a version number. When a
driver reports 1000 or reports an unknown value, CacheDB stays conservative. A
stub returning 4096 proves the resolver branch, not an extended Oracle deployment.

Discovery preserves read-only views up to the 4096-field payload ceiling and
warns when a view exceeds the table capability. Views and SQL result sets need
not share the physical-table limit. Direct table DDL remains constrained.

## Upgrade and Design Guidance

1. Align core, provider and annotation-processor versions; regenerate bindings.
2. Upgrade every reader/worker before publishing payloads above 1024 fields.
   Older 0.15.0 readers still reject those payloads. Existing smaller payloads
   keep their format, null handling and last-value-wins decoding behavior.
3. Validate the schema, row width and statement parameter budget against the
   actual driver/database combination.
4. Prefer narrow screen-specific projections for lists and dashboards. Supporting
   a wide legacy schema is not a recommendation to load every column on first paint.

The 4096-field cap does not override Redis memory policies, query budgets,
projection windows, write batch limits or payload byte limits. This change does
not add automatic SQL reads, warm-up or periodic synchronization.

References: [PostgreSQL limits](https://www.postgresql.org/docs/17/limits.html),
[SQL Server capacity](https://learn.microsoft.com/en-us/sql/sql-server/maximum-capacity-specifications-for-sql-server),
[Oracle MAX_COLUMNS](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/MAX_COLUMNS.html),
[JVM method descriptors](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.3.3).
