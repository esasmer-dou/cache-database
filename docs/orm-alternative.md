# CacheDB As An ORM Alternative

This page is the short external-facing answer to one question:

When should a team choose CacheDB instead of a traditional JPA/Hibernate-style ORM?

The answer must stay aligned with the project's first priority:

- keep production runtime overhead low
- make the library easy enough to be a serious ORM alternative

## Short Answer

Choose CacheDB when:

- Redis is part of the real runtime plan, not just an afterthought
- low-latency reads matter more than transparent relational abstraction
- the team is willing to be explicit about relation loading, projections, and hot paths
- production overhead, startup simplicity, and escape hatches matter

Stay with Hibernate/JPA when:

- your application depends heavily on relational joins as the primary read model
- you want transparent ORM behavior more than explicit runtime control
- the team expects lazy loading and entity graph behavior to stay mostly invisible
- most production bottlenecks are in SQL modeling, not read-path latency

## What CacheDB Is

CacheDB is not trying to be a drop-in clone of Hibernate.

It is a Redis-first persistence library where:

- application reads and writes go to Redis first
- PostgreSQL remains the durable persistence layer
- metadata is compile-time generated
- relation loading is explicit
- write-behind moves database durability off the foreground path

That means CacheDB should be evaluated as:

- a low-overhead ORM alternative for teams that want explicit control
- a production-oriented persistence library for Redis-first applications
- a library that keeps an escape hatch for true hotspots

## Comparison At A Glance

| Topic | CacheDB | Traditional JPA / Hibernate |
| --- | --- | --- |
| Primary read path | Redis-first | Database-first |
| Metadata model | Compile-time generated | Usually runtime reflection and ORM metadata |
| Default philosophy | Explicit control | Transparent abstraction |
| Relation loading | Explicit `FetchPlan`, loaders, projections | Often implicit lazy/eager graph behavior |
| Hotspot escape hatch | Drop to binding or direct repository | Usually stays inside ORM abstractions or custom SQL |
| Best fit | Low-latency services, read-heavy APIs, Redis-centric systems | Relational domains, SQL-centric systems, join-heavy apps |
| Runtime overhead goal | Very low | Often acceptable, but not the primary design goal |

## Where CacheDB Fits Best

CacheDB is a strong fit for:

- product services with hot read paths
- dashboard and list-heavy applications that benefit from projections
- systems that already operate Redis as a first-class production dependency
- teams that want generated ergonomics without reflection-heavy runtime behavior
- services that need clear separation between normal code and measured hotspots

## Where CacheDB Is A Worse Fit

CacheDB is a worse fit if the team wants:

- a mostly invisible ORM that hides read-model shape
- wide relational joins as the default way to build screens
- automatic graph traversal without thinking about payload size
- highly relational reporting workloads as the primary application pattern

In those cases, Hibernate/JPA may still be the more natural tool.

That is not a weakness in the message. It makes the positioning more credible.

## What Production Teams Should Expect

If a team adopts CacheDB well, production should usually look like this:

- default business code uses generated domain or binding surfaces
- hot paths use projections and explicit fetch limits
- global sorted/range read screens use projection-specific ranked fields instead of wide multi-sort entity queries
- those ranked projection fields are declared with `rankedBy(...)` so the projection repository can use a top-window fast path
- only proven hotspots drop to direct repositories
- foreground repository traffic is isolated from background worker/admin traffic

If a team adopts CacheDB badly, the failure pattern is usually this:

- it hydrates wide aggregates for list pages
- it treats Redis as magically free
- it avoids projections
- it shares Redis pools between foreground and background paths
- it drops all code to minimal repositories before measuring anything

CacheDB rewards explicitness. It does not reward pretending object graphs are free.

## Recommended Adoption Path

Use this migration path if a team is coming from JPA/Hibernate:

1. Start with `GeneratedCacheModule.using(session)...`
2. Keep CRUD and normal service endpoints on the generated surface
3. Move list screens to projections and summary/detail patterns
4. Add `withRelationLimit(...)` to preview relations
5. Move only measured hotspots to `*CacheBinding.using(session)...`
6. Use direct repositories only where profiling says wrapper reduction still matters

This path keeps onboarding smooth while preserving the low-overhead goal.

## Surface Selection

Use this as the default team rule:

| Team or workload | Recommended surface |
| --- | --- |
| Normal product service code | `GeneratedCacheModule.using(session)...` |
| Explicit hot endpoints | `*CacheBinding.using(session)...` |
| Worker, replay, recovery, infra code | direct `EntityRepository` / `ProjectionRepository` |
| Relation-heavy list or dashboard reads | projections + `withRelationLimit(...)` |

## Benchmark Honesty

The repository recipe benchmark in this repo is intentionally narrow.

It proves one useful thing:

- generated ergonomics stay in the same low-overhead band as direct repository usage

It does **not** prove:

- that CacheDB is universally faster than Hibernate in all workloads
- that Redis latency disappears
- that relation-heavy screens are cheap without read-model discipline

Use the benchmark for API-surface honesty, not marketing fiction.

## Current Evidence Inside This Repo

Latest recipe benchmark snapshot:

- `Generated entity binding`: fastest average in the current local run
- `Minimal repository`: lowest p95 in the current local run
- `JPA-style domain module`: grouped ergonomic surface with modest wrapper cost

That is the important outcome:

- the ergonomic surface is not free
- but it stays close enough to the direct repository path that most teams should not sacrifice readability prematurely

## Read This Next

- [Production Recipes](./production-recipes.md)
- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../cachedb-production-tests/README.md)
