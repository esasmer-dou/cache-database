# Migration Planner

The Migration Planner is the admin UI flow for teams that already have
PostgreSQL tables and an existing ORM route, but want to evaluate a Redis-first
CacheDB path without guessing.

It does not perform a blind production cutover. It helps you discover, plan,
warm, compare, and report one route at a time.

## When To Use It

Use the planner when:

- you already have PostgreSQL tables
- the current route is served by JPA, Hibernate, MyBatis, JDBC, or another ORM/data layer
- a list/detail route is getting expensive as child rows grow
- you need to know whether a route should use entity reads, projection reads, or ranked projection reads
- you want staging evidence before changing production traffic

Do not use it as a production mutation button. It is a migration rehearsal and
decision surface.

## Where It Lives

On the same admin host and port:

- Spring Boot: `/cachedb-admin/migration-planner`
- Native admin server: `/migration-planner`

Spring Boot example:

```text
http://127.0.0.1:8090/cachedb-admin/migration-planner
```

## Recommended UI Flow

### 1. Discover PostgreSQL Schema

Click the schema discovery action first.

Expected result:

- user tables appear
- primary keys appear
- foreign key relationships appear
- route candidates are listed
- suggested root/child pairs can be applied to the form

If discovery fails, check that the Spring `DataSource` points to the database
you expect and that the application user can read metadata from
`information_schema`.

### 2. Choose A Route Candidate

Pick one route candidate from the suggested list.

Examples:

- `customers -> orders` for customer timeline screens
- `orders -> order_lines` for order detail previews
- `products -> inventory_events` for stock history
- `accounts -> transactions` for financial timeline screens

Click `Apply to form`.

Expected result:

- root table/entity is filled
- child table/entity is filled
- primary key columns are filled when discovery knows them
- relation column is filled from the foreign key
- sort candidates are suggested
- row count and fan-out hints are prefilled when available

### 3. Adjust Route Behavior

Discovery can read schema metadata, but it cannot fully know product behavior.
Review these fields manually:

- first page size
- hot window per root
- typical children per root
- maximum children per root
- whether archive history must stay available
- whether detail lookup is hot
- whether the route is list-heavy
- whether the current ORM uses eager loading
- whether side-by-side comparison is required

Rule of thumb:

- list screen: prefer summary projection
- detail screen: full entity can be loaded on demand
- global sorted screen: prefer ranked projection
- high fan-out child table: keep only a bounded hot window in Redis

### 4. Generate Plan

Click `Generate plan`.

Expected result:

- recommended CacheDB surface
- Redis placement decision
- PostgreSQL placement decision
- projection requirement
- ranked projection requirement
- hot-window size
- warm-up steps
- staging comparison checklist
- sample child SQL
- sample root SQL

If no plan appears, the page should now show a server-side error instead of
silently leaving the result area empty.

### 5. Generate Scaffold

Use scaffold generation when you want a starting point for Java code.

Expected result:

- root `@CacheEntity` skeleton
- child `@CacheEntity` skeleton
- hot-list named query
- optional relation loader skeleton
- optional projection support skeleton
- generated binding usage snippet

This is a starting point, not a production-ready domain model. Review column
types, naming, nullability, and index assumptions before committing the code.

### 6. Run Dry-Run Warm

Click dry-run before mutating Redis.

Expected result:

- PostgreSQL child rows are counted
- referenced root rows are counted
- generated warm SQL is visible
- Redis is not changed
- missing root IDs or unexpected row counts are shown

Use this step to validate the query shape and row counts.

### 7. Run Staging Warm

Run real warm only in staging or a safe test environment.

Expected result:

- selected child hot window is read from PostgreSQL
- Redis entity surfaces are hydrated directly
- registered projections are refreshed inline
- optional referenced root rows are warmed
- warm statistics show root rows, child rows, skipped rows, and duration

If the warm step says `No registered CacheDB entity found`, the selected route
has not been registered in the running application. Generate or wire the entity
binding first, rebuild the app, and run the planner again.

### 8. Run Side-By-Side Comparison

Click comparison after warm.

Expected result:

- baseline PostgreSQL latency
- CacheDB route latency
- route label such as `entity:...` or `projection:...`
- first-page ID parity for sampled roots
- readiness assessment
- blockers and next steps

Do not cut over if:

- matched samples are not exact
- CacheDB route falls back to entity when planner requires projection
- ordering differs
- p95 latency is materially worse than baseline
- warm set does not represent the production hot window

### 9. Download Migration Report

Download the report after comparison.

The report should include:

- route summary
- selected design
- warm results
- comparison results
- readiness status
- cutover action plan
- blockers
- rollback notes

## Full-System Migration Coverage

The planner models one hot route at a time. That is intentional. A safe
full-system migration needs route inventory, not one large automatic conversion.

For 100% coverage:

1. list every production screen, API, batch, worker, and report route
2. map each route to its root table, child table, sort, filter, and page size
3. classify each route as generated CRUD, projection, ranked projection, direct repository, or PostgreSQL cold path
4. run planner flow for every route with Redis-first hot-path intent
5. keep a coverage table with owner, readiness, blockers, and rollback plan
6. do not call the migration complete until every route has an explicit decision

Recommended coverage columns:

| Column | Meaning |
| --- | --- |
| Route name | Human-readable screen/API/job name |
| Root table | Main entity/table |
| Child table | Optional child/fan-out table |
| Query shape | filter, sort, page, range, threshold |
| CacheDB shape | generated, projection, ranked projection, repository, cold path |
| Warm status | not started, dry-run ok, warm ok |
| Compare status | not run, matched, mismatch |
| Cutover status | blocked, ready, canary, live |
| Rollback plan | exact fallback path |

## Interactive Demo Bootstrap

The Spring Boot demo includes a one-click PostgreSQL migration dataset for the
planner.

From `/cachedb-admin/migration-planner` you can:

1. create a demo customer/order schema
2. seed customer and order history
3. create PK/FK constraints and supporting indexes
4. create inspection views
5. refresh discovery and continue with scaffold, warm, and compare

Prepared demo objects:

- `cachedb_migration_demo_customers`
- `cachedb_migration_demo_orders`
- `cachedb_migration_demo_customer_order_timeline_v`
- `cachedb_migration_demo_customer_metrics_v`
- `cachedb_migration_demo_ranked_orders_v`

## Current Scope

The planner does:

- discover PostgreSQL schema metadata
- suggest root/child route candidates
- generate a migration plan
- generate Java scaffold
- run dry-run warm
- run staging warm into Redis
- refresh registered projections inline during warm
- run side-by-side PostgreSQL vs CacheDB comparison
- produce migration assessment and report content

The planner does not yet:

- mutate PostgreSQL
- import existing ORM source classes automatically
- guarantee full-system coverage without a route inventory
- perform one-click production cutover

That boundary is deliberate. The planner is meant to make architecture decisions
visible before traffic is moved.
