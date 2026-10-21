# Durable Write Fences

[Türkçe](../tr/docs/kalici-yazma-surumleri.md)

## Why A Separate Table Is Needed

A business row's version cannot protect a physical delete: deleting the row also
deletes its version. An older UPSERT already in flight can then insert that row
again. A Redis version check before SQL, compaction, or a JVM lock cannot close
this race across workers or pods.

Starting with 0.14.0, PostgreSQL, SQL Server and Oracle flushers use
`cachedb_write_fence`. Business tables and public flusher constructors are
unchanged. The metadata records the last applied version and whether that version
was a delete. It is durable SQL data, not a Redis cache.

| Delivery | SQL result |
| --- | --- |
| UPSERT v1, DELETE v3, late UPSERT v2 | Row stays deleted. |
| DELETE v3 on an absent row, UPSERT v2 | Row stays absent. |
| DELETE v3, UPSERT v4 | The newer write may recreate the row. |
| UPSERT v3, DELETE v3, UPSERT v3 | Delete wins an equal-version tie. |
| Business constraint failure | Both business DML and metadata roll back. |

## Provision Before Deployment

Use the script for your provider from `cachedb-storage-jdbc`:

- [PostgreSQL migration](../cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/postgres/V014__durable_write_fence.sql).
- [SQL Server migration](../cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/mssql/V014__durable_write_fence.sql).
- [Oracle migration](../cachedb-storage-jdbc/src/main/resources/com/reactor/cachedb/jdbc/migrations/oracle/V014__durable_write_fence.sql).

Apply it once in the datasource's default schema. Give workers SELECT, INSERT
and UPDATE permissions on it. Keep the datasource catalog and default schema
fixed; use a separate datasource/flusher for another catalog or default schema.
All workers writing the same physical business tables must share this one metadata
table/schema and the same monotonic entity-version/keyspace contract. Separate
per-pod or per-user copies of the metadata table do not coordinate those writers.
The runtime validates the columns and primary key. If the table is missing, it
attempts creation once before borrowing the business transaction connection.
Restricted production users should use the explicit migration instead. A failed
initialization throws SQL state `CDB03`; the queue write is not acknowledged.
Transient connection/query failures retain their original SQL retry classification
and are not treated as a request to create metadata tables.
DDL never runs inside the business transaction, including Oracle's implicit DDL
commit path.

## Upgrade Safely

1. Pause write acceptance. Drain and reconcile pending writes using your existing
   operational runbook, then stop all old workers before changing versions.
2. Apply the metadata migration and verify permissions.
3. Start only upgraded workers; do not mix old workers that bypass the fence with
   upgraded workers for the same tables.
4. Verify insert, update, delete, replay and restart behavior in staging before
   accepting traffic again.

The migration cannot reconstruct deletes that happened before it existed, or
repair historical resurrection. Reconcile that data and old pending/retry/poison
queues explicitly. Do not replay historical, unfenced deletes/UPSERTs blindly.

## Transaction And Performance Contract

For each transaction, identities are sorted and claimed in chunks of at most 500.
Seed, locked read and metadata update use bounded SQL/prepared batches. Accepted
writes retain their input order and existing provider bulk paths. The metadata
and business changes commit on the same connection; a crash before commit rolls
both back. Replaying an acknowledged version cannot override a newer delete.

This adds SQL metadata work on the asynchronous durable path. It adds no SQL call
to an ordinary Redis read. Query timeouts remain enabled; SQL Server also retains
its lock timeout and isolation options. Deadlocks/transport failures remain
failures for the existing worker retry/poison mechanism, not successful writes.

The key covers the resolved physical schema/table and canonical entity ID; a
bounded hash index is used for locking and the full identity is verified to fail
closed on a collision/corrupt metadata. As with Redis entity keys, every SQL primary
key must have exactly one canonical application ID representation. Do not use
case/accent/trailing-space variants of a string ID as distinct Redis identities
when the SQL primary-key collation treats them as equal. Prefer numeric IDs or
canonical UUID strings. Write targets must be physical tables, not synonyms.

## Retention And Recovery

Do not truncate this table, apply TTL cleanup, or delete tombstones when business
rows disappear. Its row count grows with distinct written identities; include its
table/index size in SQL capacity planning. It is not bounded by Redis hot-set size.
Back up and restore it consistently with business tables. Restoring only one side
can suppress a legitimate write or reopen old replay risk.

Redis version keys must also remain non-expiring. After loss/restoration of Redis,
recover monotonically increasing entity versions, including SQL tombstones, before
opening writers. The SQL fence prevents stale persistence; it does not magically
make a lost Redis version counter safe to reset, or coordinate arbitrary external
SQL writers. Keep the existing CDC/outbox and recovery requirements.

If external SQL writes can race with CacheDB write-behind for the same identity,
an after-commit CDC/outbox event alone cannot fence that SQL transaction. Use one
write owner, or explicitly coordinate both writers with the same version authority
and the same transactional SQL fence. CDC/outbox remains suitable for refreshing
a read-only CacheDB route; it is not automatic multi-writer conflict resolution.
