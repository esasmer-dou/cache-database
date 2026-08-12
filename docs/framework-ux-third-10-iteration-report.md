# Framework UX: Third Ten-Iteration Engineering Report

Turkish version: [../tr/docs/framework-ux-ucuncu-10-iterasyon-raporu.md](../tr/docs/framework-ux-ucuncu-10-iterasyon-raporu.md)

This report records the third complete review, implementation, and verification
cycle over CacheDB core, the Spring Boot integration, and the standalone
PostgreSQL and SQL Server samples. The baseline is released version `0.7.1`.
The implemented changes in this engineering record are included in the `0.8.0`
release; the authoritative distribution summary remains the `v0.8.0` release
note.

## Preserved Product Boundaries

- HOT routes remain Redis-only; a miss never triggers hidden SQL I/O.
- SOURCE routes remain explicit, bounded, indexed, and time-limited.
- Growing lists use projections and keyset windows rather than full aggregates.
- Writes remain Redis-first and expose SQL durability through typed receipts.
- Repository/runtime integration remains compile-time generated without runtime reflection.
- Warm, reconciliation, and distributed jobs remain bounded and multi-pod safe.
- Operational evidence remains bounded; route, customer, and tenant names do not become metric tags.
- PostgreSQL and SQL Server samples keep the same application architecture.

## Iteration Summary

| Iteration | Outcome |
| --- | --- |
| 1 | Correct warm row accounting and enforce result invariants. |
| 2 | Publish compact, typed repository capabilities instead of failing with generic exceptions. |
| 3 | Make HOT-route population strategy an explicit compile-time contract. |
| 4 | Enforce globally safe route identity and build a pre-indexed operational inventory. |
| 5 | Replace duplicate entity/projection warm methods with one typed target. |
| 6 | Standardize an application-facing warm execution summary. |
| 7 | Make distributed job submission and handler registration share one typed definition. |
| 8 | Collapse sample warm orchestration into one validated command and one plan switch. |
| 9 | Make sample warm HTTP APIs validated, asynchronous, and discoverable. |
| 10 | Extend test tooling, bounded metrics, CI principles, and EN/TR documentation. |

## Iteration 1: Warm Accounting Is Correct By Construction

### Finding

The successful warm path passed loaded and submitted row counts to
`CacheWarmResult` in reverse order. Equal counts hid the defect, while admission
rejection or dry-run paths could report misleading evidence.

### Implementation

The constructor call was corrected. `CacheWarmResult` and `CacheWarmSummary`
now reject negative values and reject `submitted > loaded`. Duration is also
non-negative and notes are copied into immutable lists.

### Production Effect

Dashboards, job results, and migration evidence now distinguish source rows
read from rows actually admitted to Redis. Invalid evidence fails immediately
instead of silently reaching operators.

### Rejected

Clamping invalid counters was rejected because it would conceal a framework
correctness defect.

## Iteration 2: Repository Capabilities Are Typed

### Finding

Optional repository operations were discoverable only by calling them and
receiving a generic `UnsupportedOperationException`. Framework integrations
could not safely decide which optimized operations a repository supports.

### Implementation

`RepositoryCapability`, compact immutable `RepositoryCapabilities`, and
`RepositoryCapabilityUnavailableException` were added. Capability checks use a
bit mask and do not allocate collections on the read path. Redis repositories
publish one reusable static capability set.

### Production Effect

Application adapters can fail early with a precise operation name. Capability
inspection is predictable under load and does not introduce reflection or
per-request set allocation.

### Rejected

Runtime method probing and reflective interface inspection were rejected. They
would duplicate the compile-time contract and increase startup/runtime magic.

## Iteration 3: HOT-Route Population Is Explicit

### Finding

A HOT route described its query and limits but did not state how Redis would
become representative. A route could compile while relying on undocumented
operator knowledge.

### Implementation

`@HotRoute.population` now exposes `ON_DEMAND`, `DECLARED_WARM`, `WRITE_FED`,
and `EXTERNAL`. Generated `RepositoryRouteDefinition` metadata carries the
strategy. A `DECLARED_WARM` route does not compile unless a matching
`@WarmRoute(from=...)` exists. Every HOT route in both samples explicitly uses
`DECLARED_WARM`.

### Production Effect

Code review can answer how each Redis-only route is populated. Missing warm
contracts become build failures rather than empty production screens.

### Rejected

Automatic startup import was rejected. It would create unbounded database load,
Redis memory growth, and Kubernetes startup coupling.

## Iteration 4: Route Identity Is Operationally Safe

### Finding

Generated catalogs were local to repositories. Global HOT route-name collisions
could overlap Redis coverage keys, and repeated endpoint reads rebuilt route
descriptors.

### Implementation

`CacheDbRouteInventory` now indexes routes by qualified
`repository#method`, indexes HOT routes by global route name, rejects duplicate
HOT names, and validates declared-warm metadata again at startup. Ordered route
descriptors are created once and reused by bounded Actuator reads. Population
counts are precomputed in an enum map.

### Production Effect

Ambiguous coverage cannot enter service. Inventory lookup is constant-time,
startup validation is deterministic, and Actuator reads avoid rebuilding the
complete descriptor graph.

### Rejected

Silently prefixing duplicate route names was rejected because it would change
existing Redis keys and hide a deployment contract error.

## Iteration 5: One Typed Warm Target

### Finding

Samples needed separate generated methods for projection-only and
entity-plus-projection warm. The duplicated methods could drift in query,
scope, or row limits.

### Implementation

`@WarmRoute.targetParameter` and `CacheWarmTarget` were added. The annotation
processor validates the parameter type, rejects conflicts with static
`projectionsOnly=true`, and permits runtime target selection only for
projection-backed routes. PostgreSQL and SQL Server repositories now expose one
method such as `warmCustomerTimeline(customerId, maxRows, target)`.

### Production Effect

The route query, scope, and limits have one generated definition. Applications
choose payload shape with an enum rather than method-name conventions or string
flags.

### Rejected

A general-purpose options map was rejected because invalid combinations would
move from compilation to runtime.

## Iteration 6: Warm Results Have One Application Contract

### Finding

Callers reconstructed operation names, route names, scope, target, mode, and
row counters from `CacheWarmPlan` and `CacheWarmResult` independently.

### Implementation

`CacheWarmSummary` now provides one immutable result with operation, plan,
route, entity, scope, requested/read/submitted rows, duration, target, mode, and
bounded notes. `CacheWarmExecution.summary(...)` creates it without duplicating
application mapping logic.

### Production Effect

REST jobs, logs, tests, and admin consumers share one result vocabulary.
Dry-run, projection-only, and fully-submitted states are explicit.

### Rejected

Returning an untyped `Map<String,Object>` from the framework was rejected. It
would weaken compatibility and move field errors to runtime.

## Iteration 7: Distributed Jobs Share One Typed Definition

### Finding

Job producers submitted a string route and arbitrary object while handlers
declared route and argument type separately. Producer/consumer drift could be
detected only after serialization or claim.

### Implementation

`CacheDistributedJobDefinition<A>` binds the cluster-stable route to its
argument class. Typed submit validates payloads before Redis enqueue. Handler
registration and deserialization use the same definition, and mismatched
definitions fail fast. A small factory supports concise handlers without
dynamic proxies.

### Production Effect

All pods register the same explicit contract. Invalid work does not enter the
durable queue, while abandoned jobs remain claimable by another pod with the
same handler set.

### Rejected

Embedding Java class names as queue routes was rejected because refactors would
break cluster compatibility and rolling upgrades.

## Iteration 8: Sample Warm Orchestration Is Thin

### Finding

Both samples repeated many service fields, one wrapper method per route, target
branching, and handler dispatch logic. Users had to penetrate framework details
to add a route.

### Implementation

Each sample now has one validated, JSON-serializable `SampleWarmCommand`, one
route enum, one `SampleWarmBackfillService.execute`, and one generated-plan
switch. `SampleRepositories` groups repository dependencies. The job handler
only checkpoints, delegates, and returns `CacheWarmSummary`.

### Production Effect

Adding a sample route requires one command factory and one plan mapping. The
service never builds ad-hoc SQL, opens Redis clients, or hides source fallback.
PostgreSQL and SQL Server implementations remain structurally equivalent.

### Rejected

An annotation-driven runtime dispatcher over arbitrary service methods was
rejected because it would reintroduce reflection and obscure control flow.

## Iteration 9: HTTP Is Validated And Asynchronous

### Finding

Controller methods contained repeated imperative limit checks, and accepted job
responses did not provide a standard resource location.

### Implementation

Bean Validation now owns numeric, identifier, text, and score bounds.
Controllers submit typed commands only. Every accepted warm request returns
`202 Accepted`, the job snapshot, and `Location: /api/warm/jobs/{jobId}`. Heavy
JDBC/Redis work remains off the request thread.

### Production Effect

Invalid requests fail before queue admission. Clients have a standard polling
target, request threads remain bounded, and multi-pod job execution is retained.

### Rejected

Waiting synchronously for warm completion was rejected because it would consume
HTTP threads, amplify gateway timeouts, and make large jobs retry-prone.

## Iteration 10: Diagnostics And Regression Gates

### Finding

Tests could assert coverage but not the generated population contract. Metrics
showed route totals but not how routes are fed. Several README examples still
referenced removed duplicate warm methods.

### Implementation

`CacheDbTestProbe` now exposes the generated inventory and can require a HOT
route with a specific population strategy. Actuator includes population counts.
Micrometer adds `cachedb.routes.hot.population{strategy=...}` with four bounded
strategy values, never route/customer/tenant tags. Sample integration tests
check declared warm routes and accepted-job `Location` headers.

The framework-principle gate now protects typed warm targets, explicit
population, typed sample jobs, and removal of obsolete warm APIs. English and
Turkish core/sample guides use the exact current generated method signatures.

### Production Effect

Builds now catch documentation drift and incomplete population contracts.
Operators can compare route strategy distribution with coverage and scheduled
work without creating unbounded metric cardinality.

### Rejected

Per-route Micrometer tags were rejected. Route and scope drill-down belongs in
bounded Actuator details, logs, or traces.

## Verification Evidence

The final source state passed the following gates on Java 21 OpenJ9 with the
Docker-backed Redis and PostgreSQL test services:

- the complete Maven reactor: `305` tests, `0` failures, `0` errors, `3` skipped
- the production evidence module: `27` tests, `0` failures, `0` errors
- PostgreSQL sample: `10` tests, `0` failures, `0` errors
- MSSQL sample: `10` tests, `0` failures, `0` errors
- public API compatibility: no removed signatures
- framework principles: `800` runtime Java files checked
- sample framework boundaries: `122` Java files checked
- provider parity: `64` provider-neutral Java files checked
- Postman parity: `59` requests validated for each provider
- Turkish documentation, README quality, Markdown links, and `git diff --check`:
  passed

The first complete-reactor attempt exposed one nondeterministic crash/replay
assertion window. The product replay path passed in isolation; the test budget
was then made configurable, monotonic, and wide enough to cover the configured
pending-claim plus blocked-read cycle. The final complete-reactor run passed.

## Deliberately Not Implemented

| Proposal | Decision |
| --- | --- |
| Hidden SQL fallback on Redis miss | Rejected: cost and availability would become unpredictable. |
| Automatic full-database startup warm | Rejected: unbounded SQL, Redis, and pod-start pressure. |
| Runtime repository proxy or annotation scan | Rejected: conflicts with compile-time generation and no-reflection policy. |
| Per-route/customer/tenant metric tags | Rejected: unsafe cardinality. |
| Synchronous warm HTTP calls | Rejected: blocks request threads and fails poorly across gateways. |
| Rust/JNI for orchestration | Rejected: these paths are I/O/control-plane bound; Java allocation was already bounded. |

## Production Interpretation

The developer experience is now closer to established Java frameworks in the
areas that matter for this product: compile-time validation, typed repositories,
starter auto-configuration, Actuator evidence, test support, and concise sample
application code. It intentionally does not copy ORM behavior that conflicts
with Redis-first correctness.

The operational sequence remains explicit:

1. declare a bounded HOT or SOURCE route;
2. declare how every HOT route is populated;
3. generate a typed warm plan where required;
4. dry-run and submit a durable background job;
5. prove coverage, parity, latency, memory, and durability separately;
6. expose Redis-only traffic only after those gates pass.
