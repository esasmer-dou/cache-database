# Concepts And Assumptions

Turkish version: [../tr/docs/kavramlar-ve-kabuller.md](../tr/docs/kavramlar-ve-kabuller.md)

This document explains the core concepts and design assumptions behind CacheDB.
Its purpose is to make clear which behavior is automatic and which behavior must
be designed explicitly.

CacheDB does not try to copy classic ORM behavior. It is built around explicit
route decisions, bounded hot data, projection/read-model discipline, and
production evidence.

## Short Glossary

| Concept | Meaning |
| --- | --- |
| Entity | The model bound to a source-database table and Redis hot entity payload |
| Repository | The API for save, find, query, and delete operations |
| Hot set | The bounded data set allowed to stay in Redis |
| Hot policy | The rule that decides whether a row may enter Redis |
| Projection | A compact read model for a screen or API |
| Ranked projection | A pre-ranked read model for global sorting or top-N screens |
| Relation | Entity relationship metadata plus explicit fetch behavior |
| FetchPlan | The explicit plan for which relations to preload |
| RelationBatchLoader | The class that loads relation data in batches |
| Route contract | A per-endpoint contract for page size, hot window, projection requirement, and limits |
| Warm | Controlled preloading of the Redis hot set |
| Dry-run warm | Warm planning without mutating Redis |
| Side-by-side comparison | Source-database baseline compared against CacheDB result and latency |
| Cutover | Moving a specific route to the CacheDB path |

## Source Database And Redis Roles

The two stores have different responsibilities.

| Layer | Responsibility |
| --- | --- |
| Source database | Durable source of truth, full history, archive, replay, reporting base |
| Redis | Hot entities, projection windows, indexes, streams, coordination, telemetry |

CacheDB is not designed to remove the durable source database. Redis exists to
serve the bounded hot path with low latency.

ANTI-PATTERN: trying to move the entire database into Redis.

BEST: decide explicitly what stays in Redis and what stays on the
source-database cold/durable path for every production route.

## Entity

An entity binds a table row to the Redis hot entity payload.

```java
@CacheEntity(table = "orders", redisNamespace = "orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("status")
    public String status;
}
```

Rules:

- Every entity must define one `@CacheId`.
- Persisted fields must not be `private` or `final`.
- Column names must be explicit.
- Keep entities small and understandable.
- Use projections instead of inflating entities for large screens.

## Repository

Repository operations include:

- `save`
- `findById`
- `findAll`
- `findPage`
- `query`
- `deleteById`

Behavior:

- Writes enter Redis first and then PostgreSQL write-behind.
- Reads use the Redis hot path first.
- Read-through or hydrated rows are admitted to Redis according to hot policy.
- Large pages and queries should be protected by guardrails.

## Hot Set

The hot set is the data allowed to stay in Redis. It must be bounded.

Real hotness is rarely a single rule:

- last 90 days
- open or pending state
- VIP customer
- tenant quota
- first 1,000 dashboard rows
- latest 50 notifications per user

Do not model hot data as only TTL.

## Hot Policy

Hot policy decides whether an entity can enter Redis.

| Policy | Use when |
| --- | --- |
| `COUNT_WINDOW` | A fixed count of newest or hottest rows should stay hot |
| `TIME_WINDOW` | Business time defines the window, such as last 7, 30, or 90 days |
| `STATE_WINDOW` | Only states such as `OPEN`, `PENDING`, or `ACTIVE` should be hot |
| `COMPOSITE` | Multiple hotness rules must be combined |
| `CUSTOM_PREDICATE` | Domain-specific admission logic is required |

Example decisions:

| Need | Approach |
| --- | --- |
| Last 90 days of orders in Redis | `TIME_WINDOW` on `order_date` |
| Only open tickets in Redis | `STATE_WINDOW` on `status=OPEN` |
| Last 90 days and `OPEN/PENDING` | `COMPOSITE` |
| One tenant must not consume Redis | Tenant quota |

TTL is different:

- TTL controls time since Redis write.
- `TIME_WINDOW` controls business time from the entity data.

Use `TIME_WINDOW` for "last 90 days of business data", not TTL.

## Relation

Relations describe entity relationships, but CacheDB does not run transparent
lazy loading.

```java
@CacheRelation(
        targetEntity = "OrderEntity",
        mappedBy = "customerId",
        kind = CacheRelation.RelationKind.ONE_TO_MANY,
        batchLoadOnly = true
)
public List<OrderEntity> orders;
```

Rules:

- Relation metadata is declared on the entity.
- Relation loading is requested explicitly with `FetchPlan`.
- A relation cannot be safely preloaded without a registered loader.
- Use `withRelationLimit(...)` for previews.
- Use projections for large lists.

BEST:

```java
customerRepository
        .withRelationLimit("orders", 10)
        .findById(customerId);
```

ANTI-PATTERN:

- loading a full order history for every row in a customer list
- expecting relation field access to trigger automatic Redis/DB calls
- creating wide object graphs without relation limits

## Projection

A projection is a screen-specific or API-specific read model. It carries only
the fields needed by the route.

Example:

```text
CustomerOrderSummary
- order_id
- customer_id
- order_date
- order_amount
- currency_code
- status
```

Projection is required when:

- first paint shows many child rows
- sorting is global or business-score based
- entity payloads are large
- relation fan-out grows over time
- full details are not needed until the user opens a row

BEST: list from projection, detail from entity.

ANTI-PATTERN: full aggregate entity graph for list first paint.

## Ranked Projection

Ranked projection is the shape for top-N and globally sorted screens.

Examples:

- highest-risk customers
- highest-value orders
- tickets approaching SLA deadline
- products with the highest stock risk

Do not fetch a wide candidate set and sort it in application memory. The ranking
field should be prepared on the projection.

```text
TicketRiskProjection
- ticket_id
- tenant_id
- priority
- sla_deadline
- rank_score
```

## Route Contract

A route contract defines the runtime envelope of a screen or API.

It should answer:

- maximum page size
- hot window size
- whether projection is required
- cold read limit
- tenant memory budget
- whether production strict mode is enabled

Example decisions:

| Route | Contract |
| --- | --- |
| `/customers/{id}` | Single `CustomerEntity`, no relation |
| `/customers/{id}/orders` | Projection required, latest 1,000 summaries per customer |
| `/orders/{id}` | Single `OrderEntity`, small line preview |
| `/dashboard/risk` | Ranked projection, global top 100 |
| `/reports/monthly` | Source-database/reporting path, not Redis hot set |

## Warm And Dry-Run

Warm preloads the Redis hot set before production traffic or after deployment.

Dry-run warm does not mutate Redis. It shows SQL, row counts, root id counts,
and estimated memory impact.

BEST sequence:

1. Generate the plan.
2. Run dry-run warm.
3. Inspect SQL and row counts.
4. Run staging warm.
5. Inspect Redis memory calibration.
6. Run side-by-side comparison.
7. Attach the cutover decision to the report.

## Side-By-Side Comparison

This step compares source-database baseline output with CacheDB output.

It checks:

- same id list
- same ordering
- same page size
- correct entity/projection route label
- acceptable CacheDB p95
- cutover readiness

If data does not match, do not cut over even when latency is excellent.

## Outbox And CDC

If systems outside CacheDB mutate the source database, the Redis hot set can become
stale. Outbox or CDC is required for that architecture.

Rule:

- Writes through CacheDB enter write-behind.
- Writes outside CacheDB must be reported through outbox/CDC if Redis freshness
  matters.

BEST: write source-database changes to an outbox table or CDC stream, read them
with a CacheDB adapter, and apply them with an idempotent runner that refreshes
entities and projections.

ANTI-PATTERN: expecting Redis to stay fresh while other systems mutate
PostgreSQL silently.

## Production Assumptions

Production use assumes:

- Redis HA is a real infrastructure dependency.
- The source database remains the durable source of truth.
- Admin UI is behind a trusted network or gateway.
- Projection-required routes do not fall back to entity scans in production.
- Hot set growth is bounded by policy, quota, and Redis `maxmemory`.
- Every migration route produces warm, comparison, and rollback evidence.
- Full-system migration requires 100% route coverage.

## Decision Classification

BEST:

- choose the hot route explicitly
- use projection where projection is required
- define hot policy and route contract
- warm and compare in staging before cutover

ACCEPTABLE:

- use bounded relation previews on small detail screens
- pilot a few routes before wider rollout
- leave old archive queries on the source-database cold path

ANTI-PATTERN:

- load the whole table into Redis
- open relation-heavy screens with full entity graphs
- run large pages without route contracts
- ignore external PostgreSQL writes without outbox/CDC
- cut over without benchmark and parity evidence
