# Production Tuning Guide

Turkish version: [../tr/docs/production-tuning-rehberi.md](../tr/docs/production-tuning-rehberi.md)

This guide explains which CacheDB tuning decision solves which production
problem. Use [Tuning Parameters](tuning-parameters.md) as the full property
reference; use this document to decide what to tune.

The goals are to:

- control Redis memory growth
- keep first-paint cost bounded on relation-heavy screens
- handle write-behind backlog predictably
- run safely in multi-pod Kubernetes environments
- estimate warm-up and comparison cost during PostgreSQL migrations

## 30-Second Map

| Problem | Start with |
| --- | --- |
| Redis memory is growing | Hot policy, tenant quota, Redis `maxmemory`, projection window |
| Large list is slow | Projection, ranked projection, route contract |
| Relation loading is expensive | `withRelationLimit(...)`, batch loader, summary-first model |
| Write queue is building up | Write-behind workers, batch size, flush policy, PostgreSQL pool |
| Multi-pod loops run twice | Runtime coordination and leader lease |
| Old reads pollute Redis | `admitOnRead=false`, `TIME_WINDOW`, cold path |
| Migration warm-up takes too long | Batch size, checkpoint/resume, rate limit, route scope |
| External systems update PostgreSQL | Outbox/CDC adapter and apply runner |

## 1. Classify The Route First

Do not start by increasing knobs. First classify the route. A bad route shape
cannot be fixed permanently with larger pools.

| Route type | Recommended model |
| --- | --- |
| Single entity detail | Entity repository |
| Small bounded list | Entity page with guardrails |
| Large child list per parent | Projection window |
| Global top-N or business ranking | Ranked projection |
| Audit, archive, monthly report | Source-database cold/reporting path |
| Worker, replay, repair | Direct repository or explicit SQL |

BEST: define the route contract, then tune properties.

ANTI-PATTERN: run a large full-entity query and try to fix it by increasing
Redis pool size or CPU.

## 2. Redis Memory Model

Redis memory is not controlled by one setting. Treat it as four layers.

| Layer | Controls |
| --- | --- |
| Hot policy | Which rows may enter Redis |
| Hot entity limit | How many entities may stay hot |
| Tenant quota | Whether one tenant/customer can consume the budget |
| Redis `maxmemory` | The absolute infrastructure limit |

### Example: Last 90 Days Of Orders

Requirement:

- orders table has millions of rows
- users mostly read the last 90 days
- old orders are opened rarely

BEST:

- use `TIME_WINDOW` for `OrderEntity`
- use `order_date` as the time column
- consider `admitOnRead=false` if old read-through rows should not enter Redis
- use a projection for customer order lists
- configure Redis `maxmemory` and eviction policy at infrastructure level

Example:

```properties
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.mode=TIME_WINDOW
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.timeColumn=order_date
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.hotForSeconds=7776000
```

`7776000` seconds is roughly 90 days.

### Example: Only Open Tickets

Requirement:

- support ticket history is large
- the live queue only needs open and pending work to be fast

BEST:

```properties
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.mode=STATE_WINDOW
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.stateColumn=status
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.stateValues=OPEN,PENDING
```

When a ticket closes and no longer satisfies hot policy, cached entity and index
entries should be removed.

### Example: Tenant Memory Limit

Requirement:

- one large tenant must not consume the whole Redis budget
- every tenant needs a hot payload budget

BEST:

- use tenant quota in the route contract
- configure both hot row limit and payload memory budget
- monitor accepted, rejected, and evicted counts per tenant

Signals:

| Signal | Meaning |
| --- | --- |
| Accepted high, evicted low | Budget is likely sufficient |
| Rejected growing quickly | Hot policy is too wide or quota is too low |
| Evicted constantly increasing | Hot window may be smaller than route demand |
| Estimate and actual Redis memory diverge | Inspect memory calibration report |

## 3. Page Size And Hot Window

Page size must stay smaller than the hot window. Otherwise one request can
exceed the route's designed hot boundary.

Example:

| Value | Meaning |
| --- | --- |
| `hotWindowPerRoot=1000` | Latest 1,000 order summaries per customer in Redis |
| `firstPageSize=50` | UI first paint shows 50 rows |
| `maxColdReadSize=200` | Cold path cannot accidentally become huge |

BEST:

- keep UI page size small and stable
- make the projection window match route demand
- keep guardrails enabled for full-entity pages

ANTI-PATTERN:

- `hotWindow=1000` while the UI requests 5,000 rows in one call
- put a large list in Redis and sort it later in application memory

## 4. Relation Tuning

Relation loading gets expensive because it creates decode, allocation, network,
and object graph costs.

BEST:

- use `withRelationLimit(...)` for small detail-page previews
- use projections for list screens
- make loaders batch-oriented; do not run one query per parent
- keep `maxFetchDepth` controlled

Example:

```java
OrderEntity order = orderRepository
        .withRelationLimit("orderLines", 8)
        .findById(orderId)
        .orElseThrow();
```

ACCEPTABLE:

- first 8 order lines on an order detail page

ANTI-PATTERN:

- load all orders and all order lines for every customer in a customer list

## 5. Projection Tuning

Projection design is the main performance tool for relation-heavy screens.

Ask:

- Which columns does the first screen actually show?
- Is full entity detail needed before row open?
- Is the sort stable?
- Is the sort global or per parent?
- What is the hot window size?
- What projection refresh lag is acceptable?

Example route:

```text
GET /customers/{customerId}/orders
```

BEST model:

- `CustomerOrderSummaryProjection`
- key: `customer_id`
- sort: `order_date DESC, order_id DESC`
- window: 1,000 per customer
- page size: 50 or 100
- detail: `OrderEntity.findById(orderId)`

ANTI-PATTERN:

- read full `OrderEntity` payloads on every request and sort in memory

## 6. Write-Behind Tuning

Write-behind persists Redis writes into PostgreSQL. Increasing worker count is
not the only tuning lever.

Watch:

- stream backlog
- pending entry count
- DLQ count
- PostgreSQL flush latency
- retry count
- worker CPU
- PostgreSQL connection pool wait

First levers:

| Area | Increase or tune when |
| --- | --- |
| Worker threads | Backlog grows and CPU/PostgreSQL pool can handle more |
| Batch size | Too many tiny flushes are happening |
| Flush group parallelism | Different table groups can be written in parallel |
| Retry backoff | Transient PostgreSQL errors create retry storms |
| DLQ trim | More failed records are needed for incident analysis |

ANTI-PATTERN:

- increase worker count without checking PostgreSQL capacity
- ignore DLQ
- run write-behind without retry and timeout discipline

## 7. PostgreSQL Tuning

PostgreSQL remains the durable source of truth. Even with Redis-first reads,
PostgreSQL connection and index quality matter.

Watch:

- connection timeout
- socket timeout
- batch insert rewrite
- prepared statement threshold
- fetch size
- application name
- pool size
- table indexes

Warm-up especially needs indexes on:

- child relation column
- sort column
- primary key
- filtered status/date columns

Example:

```sql
CREATE INDEX idx_orders_customer_date
ON orders (customer_id, order_date DESC, order_id DESC);
```

## 8. Redis Client And Pool Tuning

Redis pools serve two traffic classes:

- foreground repository reads/writes
- background workers, admin, streams, recovery

BEST:

- size foreground and background pools separately
- keep foreground timeout lower and background blocking timeout higher when
  appropriate
- avoid unnecessary `PING` noise from validation settings
- do not let pool wait time exceed the route SLO

ANTI-PATTERN:

- use one huge pool for every workload
- set blocking stream read timeout lower than worker block timeout

## 9. Kubernetes And Multi-Pod

In Kubernetes, multiple app pods connect to the same Redis and PostgreSQL. That
is expected. Consumer identity and singleton loops are the important parts.

BEST:

- consumer name must be pod-unique
- consumer group should stay shared
- cleanup/report/history loops should use leader lease
- pod count, total worker capacity, and PostgreSQL pool size must be planned
  together
- Redis HA is a production dependency

Example:

```properties
cachedb.runtime.append-instance-id-to-consumer-names=true
cachedb.runtime.leader-lease-enabled=true
cachedb.runtime.leader-lease-ttl-millis=15000
```

ANTI-PATTERN:

- use the same consumer name in every pod
- let every pod run the same cleanup job at the same time
- use a single Redis instance as production coordination without HA

## 10. Migration Warm Tuning

Warm-up is one of the riskiest migration steps because it reads PostgreSQL and
fills Redis.

BEST:

- run dry-run first
- scope warm-up to one route
- start with controlled batch size
- enable checkpoint/resume
- protect PostgreSQL and Redis with rate limits
- inspect Redis memory calibration after warm-up

Warm report fields:

| Field | Meaning |
| --- | --- |
| Root rows | Number of parent rows included |
| Child rows | Fan-out volume |
| Missing root ids | Possible integrity issue |
| Duration | Warm-up cost |
| Estimated Redis memory | Budget estimate |
| Actual Redis memory | Calibration signal |

## 11. Outbox And CDC Tuning

If PostgreSQL changes outside CacheDB, outbox/CDC is required.

BEST:

- outbox events must be idempotent
- checkpoints must be durable
- duplicate events must be safe to apply
- update/delete ordering must be respected
- poison events must be visible through DLQ or an error channel

Current boundary:

- the existing adapter can read outbox events
- a production apply runner should still map events to idempotent
  upsert/delete/projection refresh behavior

## 12. Dashboard And Alarm Signals

Production monitoring should show more than latency. It should also show
admission behavior.

Watch:

- cache hit/miss
- hot policy accepted/rejected
- tenant quota rejected/evicted
- Redis used memory
- write-behind backlog
- pending claim count
- DLQ count
- projection lag
- warm progress
- side-by-side comparison parity

Alarm examples:

| Signal | Action |
| --- | --- |
| DLQ growing | Inspect PostgreSQL error type and retry policy |
| Projection lag growing | Check refresh workers and source write rate |
| Tenant rejected growing | Check hot policy, tenant quota, and route window |
| Redis memory growing quickly | Inspect key prefixes, projection windows, entity TTL/hot policy |
| CacheDB p95 worse than source-database baseline | Verify whether the route used projection or entity fallback |

## 13. Production Profiles

### Small Pilot

- 1-3 hot routes
- strict mode for projection-required routes
- admin UI only on internal network
- staging comparison required
- Redis HA plan documented

### Medium Service

- route contract inventory
- tenant quota
- projection lag metric
- write-behind DLQ alarm
- benchmark threshold CI gate
- migration coverage report

### Large Multi-Tenant System

- tenant memory budget
- route-level hot windows
- staging Redis failover test
- outbox/CDC apply runner
- canary cutover
- rollback runbook
- capacity plan and regular memory calibration

## Final Checklist

Before production:

- every hot route has a route contract
- projection-required routes do not fall back to entity scans
- page size is smaller than hot window
- tenant quota protects against single-tenant overflow
- Redis `maxmemory` and eviction policy are configured
- PostgreSQL indexes match warm/query shape
- write-behind backlog and DLQ are monitored
- outbox/CDC requirement is evaluated
- migration warm supports checkpoint/resume
- side-by-side comparison proves data parity
- admin UI is behind a trusted gateway or operations network
