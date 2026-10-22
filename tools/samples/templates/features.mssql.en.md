**SQL Server prerequisite:** the local Compose `sql-init` service creates
`cachedb_sample` and enables `ALLOW_SNAPSHOT_ISOLATION` in this disposable database.
The default JDBC URL now targets that database rather than `tempdb`. If an older
demo was stored in `tempdb`, it is not migrated automatically; reseed the disposable
demo or use an explicitly chosen `DB_URL`. The integration container initializes
the same setting in its own fixture. In production, choose a durable application
database and have the DBA approve snapshot isolation before preparing snapshots.
Application startup never changes database-wide isolation settings.
