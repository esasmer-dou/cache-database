# Outbox and CDC Apply Runner

Turkish version: [../tr/docs/outbox-cdc-apply-runner.md](../tr/docs/outbox-cdc-apply-runner.md)

Use this page when PostgreSQL can be changed outside CacheDB and Redis must stay
fresh.

Examples:

- an existing ORM still writes to PostgreSQL during migration
- a back-office batch job updates rows directly
- Debezium or an outbox table publishes changes from another service
- a repair job replays rows after an incident

## The Problem

CacheDB can keep Redis and PostgreSQL aligned when writes go through CacheDB.
If another system writes directly to PostgreSQL, Redis will not automatically
know about that change unless you feed the change back into CacheDB.

The safe production pattern is:

1. Capture the database change in an outbox table or CDC stream.
2. Convert it into an `ExternalChangeEvent`.
3. Apply it with `ExternalChangeApplyRunner`.
4. Let the runner update Redis hot entities, indexes, tombstones, and
   projections without writing the same event back to PostgreSQL.

## Default Mode: Cache Only

BEST default:

```java
ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
        .builder(cacheDatabase.session(), cacheDatabase.entityRegistry())
        .mode(ExternalChangeApplyMode.CACHE_ONLY)
        .build();

PostgresOutboxExternalChangeFeedAdapter adapter =
        PostgresOutboxExternalChangeFeedAdapter
                .builder(dataSource)
                .adapterName("orders-hotset")
                .outboxTable("cachedb_outbox")
                .batchSize(200)
                .pollIntervalMillis(500)
                .build();

adapter.start(runner);
```

For Microsoft SQL Server, wire the provider-specific adapter from
`cachedb-storage-mssql`:

```java
MssqlOutboxExternalChangeFeedAdapter adapter =
        MssqlOutboxExternalChangeFeedAdapter
                .builder(mssqlDataSource)
                .adapterName("orders-hotset")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(200)
                .pollIntervalMillis(500)
                .build();

adapter.start(runner);
```

`CACHE_ONLY` means:

- UPSERT hydrates Redis from the external event payload.
- DELETE writes a Redis tombstone and removes hot/index/projection state.
- The write-behind queue is not used.
- PostgreSQL is not written again.

This prevents a feedback loop where a PostgreSQL event is written back to
PostgreSQL as if it were a new CacheDB command.

## Event Shape

Default UPSERT handling requires a full row payload that the registered
`EntityCodec.fromColumns(...)` can hydrate.

Example:

```java
new ExternalChangeEvent(
        "OrderEntity",
        "10042",
        ExternalChangeType.UPSERT,
        Map.of(
                "order_id", 10042L,
                "customer_id", 501L,
                "order_date", orderDate,
                "order_amount", amount,
                "currency_code", "TRY",
                "status", "PAID"
        ),
        18L,
        Instant.now(),
        "postgres-outbox"
);
```

DELETE can carry only the id if the codec can resolve the typed id from the id
column:

```java
new ExternalChangeEvent(
        "OrderEntity",
        "10042",
        ExternalChangeType.DELETE,
        Map.of(),
        19L,
        Instant.now(),
        "postgres-outbox"
);
```

## Partial Payloads

ANTI-PATTERN:

- receive `{ "status": "PAID" }`
- merge it into a Redis entity that may not be present
- silently invent a full entity from partial data

For partial events, register an explicit handler:

```java
ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
        .builder(cacheDatabase.session(), cacheDatabase.entityRegistry())
        .handler("OrderEntity", event -> {
            // Resolve the command intentionally: load full row, reject partial,
            // or route to a domain-specific projection update.
            return ExternalChangeApplyResult.ignored(
                    event,
                    event.id(),
                    ExternalChangeApplyMode.CACHE_ONLY,
                    "Partial order patch is handled by the order command route"
            );
        })
        .build();
```

BEST: partial update semantics must be owned by an explicit command or handler.

## Version And Idempotency

The Redis repository checks the existing version/tombstone version before
applying an external event.

Behavior:

- if Redis has a newer version, the external event is skipped
- if the same event is replayed, applying it is safe
- if the event has no positive version, CacheDB applies it without stale-event
  protection

BEST: outbox and CDC events should carry a monotonically increasing per-entity
version.

ACCEPTABLE: event id order is stable and only one apply runner owns the entity
stream.

ANTI-PATTERN: unordered external events with no version field.

## Checkpoint Semantics

`PostgresOutboxExternalChangeFeedAdapter` advances its checkpoint only after
`ExternalChangeSink.accept(...)` returns successfully.

`MssqlOutboxExternalChangeFeedAdapter` follows the same rule and stores the
checkpoint with a SQL Server lock-guarded update-then-insert statement instead
of `MERGE`.

For JDBC outbox adapters, `pollOnce(...)` reads, applies, and advances the
checkpoint in one database transaction. Pollers that use the same `adapterName`
share the same checkpoint row. The checkpoint row is locked before reading the
next batch, so two Kubernetes pods do not apply the same range concurrently.
The MSSQL adapter also serializes first-start checkpoint table creation with a
short SQL Server application lock, and treats a concurrent duplicate checkpoint
row insert as a successful ownership handoff. For controlled production
environments, pre-creating the outbox and checkpoint tables through normal
schema migration is still the cleaner option.

This is a safety model, not a throughput model. One `adapterName` is one
logical consumer. If a route needs active-active outbox throughput, partition
the outbox stream and give every partition its own explicit adapter name and
checkpoint.

If the apply runner fails:

- the adapter does not accept the event
- the checkpoint does not advance
- the next poll can retry the same event

This is intentional. Do not hide apply failures just to keep the outbox moving.

## Production Checklist

- Register every entity that can appear in the outbox.
- Use generated bindings or implement `EntityCodec.fromColumns(...)`.
- Prefer `CACHE_ONLY` for database-originated events.
- Use `CACHE_AND_WRITE_BEHIND` only for trusted command events that should go
  through normal CacheDB writes.
- Include entity id, event type, version, occurred-at, source, and full row
  payload for UPSERT events.
- Add metrics for accepted, ignored, failed, and retried events.
- Keep the outbox adapter name stable; changing it creates a new checkpoint.
- In Kubernetes, reuse the same `adapterName` only when you want one serialized
  logical consumer across pods.
- Use partitioned adapter names only when the outbox stream is explicitly split
  and every partition has its own ownership boundary.
