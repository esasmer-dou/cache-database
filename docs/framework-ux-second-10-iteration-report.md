# Framework UX: Second Ten-Iteration Engineering Report

Turkish version: [../tr/docs/framework-ux-ikinci-10-iterasyon-raporu.md](../tr/docs/framework-ux-ikinci-10-iterasyon-raporu.md)

This report records a second complete review, implementation, and verification
cycle over CacheDB core and the standalone PostgreSQL and SQL Server samples.
The work starts from the released `0.7.1` baseline. The implemented changes in
this engineering record are included in the `0.8.0` release; the authoritative
distribution summary remains the `v0.8.0` release note.

## Non-Negotiable Product Contract

Every iteration was checked against the following boundaries:

- a hot route reads Redis only and never hides a SQL fallback;
- a source route is explicit, bounded, indexed, and time-limited;
- growing lists use projections and keyset windows instead of full aggregates;
- writes are accepted by Redis first and expose SQL durability through receipts;
- repository behavior is generated at compile time without runtime reflection;
- warm and reconciliation work is bounded and coordinated across pods;
- operational detail is observable without unbounded metric cardinality;
- PostgreSQL and SQL Server samples retain the same application contract.

## Iteration Summary

| Iteration | Result |
| --- | --- |
| 1 | Predicate groups cannot create an accidental OR query; intentional OR routes require `explicitDisjunction = true`. |
| 2 | `HotWindow` and `SourceWindow` share the `WindowSlice` cursor contract. |
| 3 | `HotLookup` exposes explicit state predicates and safe payload mapping. |
| 4 | Warm execution uses one plan-owned entity/projection decision and one explicit apply/dry-run mode. |
| 5 | Durability helpers preserve typed receipts, batch receipts, timeout, and operation context. |
| 6 | CacheDB's internal Redis client no longer overrides an application's primary Redis bean. |
| 7 | Generated repositories publish a reflection-free route catalog with compile-time Spring bean-name collision safety. |
| 8 | Route and scheduled-warm evidence is available through startup logs, Actuator, and bounded Micrometer metrics. |
| 9 | Generated source projection mapping and durable batch waiting use lower-allocation, bounded paths; both samples consume them. |
| 10 | English/Turkish guidance and CI principle checks protect the new contracts. |

## Iteration 1: Query Intent Is Compile-Time Safe

### Problem

`@CachePredicate.group` represents disjunction: predicates inside one group are
ANDed, while different groups are ORed. A second group could therefore widen a
query silently after a harmless-looking refactor.

### Change

`@CacheRouteQuery.explicitDisjunction` was added. The processor now rejects any
query with more than one predicate group unless the repository author sets the
flag explicitly. Processor compilation tests cover both the rejected implicit
OR and the accepted explicit OR.

The sample review also prevented a semantic regression. The active-order
policy is deliberately "last 90 days OR active status". Its two groups were
kept and marked explicitly. Archive keyset predicates and inactive-product
archive predicates received the same visible opt-in.

### Production Effect

Query widening can no longer pass code review as an invisible annotation
detail. An intentional OR remains supported and reviewable.

### Rejected

Changing every multi-group query to AND was rejected because it changes the
business result and would break the sample's composite admission policy.

## Iteration 2: One Cursor Window Contract

### Problem

`HotWindow` and `SourceWindow` carried the same item/cursor behavior but callers
had to handle them separately. Repeated pagination glue increased application
code without adding safety.

### Change

Both records now implement `WindowSlice<T>`. The shared API exposes `size`,
`isEmpty`, `hasNext`, and `nextRequest(limit)`. `map` keeps the original cursor;
`HotWindow.map` also keeps route coverage. `HotWindow.requireComplete` provides
a fluent coverage gate.

### Production Effect

Controllers and application services can share cursor handling without erasing
the essential distinction between Redis coverage and a durable SQL result.

### Rejected

A common untyped page result was rejected because it would hide hot-route
coverage and make an SQL result look equivalent to a prepared Redis window.

## Iteration 3: Hot Lookup States Stay Explicit

### Problem

A Redis miss, a tombstone, and policy rejection have different meanings. Code
that checks only `Optional.empty()` can incorrectly return HTTP 404 for a row
that still exists in SQL.

### Change

`HotLookup` now exposes `isNotCached`, `isTombstoned`, and
`isOutsideHotPolicy`. `map` transforms only a hit and preserves every non-hit
state. Mapping and exception factories may not return `null`.

### Production Effect

Application error mapping becomes concise without collapsing data absence,
cache availability, and admission policy into one status.

### Rejected

Automatic SQL lookup on `NOT_CACHED` was rejected. It would turn route cost,
pool use, and latency into hidden runtime behavior.

## Iteration 4: A Warm Plan Owns Its Admission Shape

### Problem

Callers selected a generated warm plan and then repeated the same decision with
another `projectionOnly` boolean when executing it. Those two values could
disagree.

### Change

`CacheWarmExecutionMode` contains only `APPLY` and `DRY_RUN`.
`CacheDatabase.executeWarm(plan, mode)` derives entity versus projection
admission from the plan itself and returns `CacheWarmExecution`, which retains
the exact plan, mode, result, route, and scope. The test probe exposes the same
entry point.

Both samples now use this single execution path. Their HTTP choice still
selects which generated plan to create, but execution no longer repeats the
plan's projection decision.

### Production Effect

Dry-run remains non-mutating, apply remains explicit, and a projection-only
plan cannot accidentally be executed as a full-entity warm.

### Rejected

Automatic startup import was rejected. Existing SQL data must still be warmed
through a bounded, observable, operator-controlled workflow.

## Iteration 5: Durability Failures Preserve Evidence

### Problem

Boolean durability checks forced every application to recreate timeout errors.
Batch code could lose receipt identity and the operation that timed out.

### Change

Single-receipt and batch `awaitDurableOrThrow` helpers validate their timeout,
return the original typed object on success, and throw explicit exceptions on
failure. Batch failures preserve every receipt plus an operation label such as
`sample seed/orders`.

Generated SQL-durable batch commands now use one `awaitAll` operation instead
of waiting for each receipt with a separate full timeout.

### Production Effect

Failure handling has enough evidence for retry, dead-letter investigation, and
support diagnostics. Batch timeout remains bounded by one command deadline.

### Rejected

Pretending `REDIS_ACCEPTED` means SQL commit was rejected. Receipt durability
remains an explicit state transition.

## Iteration 6: Spring Infrastructure Does Not Hijack User Beans

### Problem

The starter marked its internal `cacheDbJedisPooled` bean as `@Primary`. An
application injecting `JedisPooled` for unrelated work could therefore receive
CacheDB's client unexpectedly.

### Change

The primary marker was removed. CacheDB internals already use stable bean names
and qualifiers for foreground and background clients. A starter test and CI
rule prevent reintroduction.

### Production Effect

Applications keep ownership of their primary Redis bean while CacheDB still
uses its dedicated, explicitly qualified pools.

### Rejected

Selecting Redis clients by type and "first bean wins" was rejected as
unpredictable in multi-client Spring applications.

## Iteration 7: Generated Route Catalogs

### Problem

Repository interfaces were compile-time safe, but operations could not list the
declared hot, source, source-SQL, warm, lookup, and command surfaces without
scanning annotations at runtime.

### Change

The processor emits an immutable `RepositoryRouteCatalog` for every generated
repository. Each `RepositoryRouteDefinition` includes the method, route kind,
route name, projection, page/window/row bounds, timeout, memory budget,
coverage scope, projection-only flag, and concise detail. Qualified repository
and entity names avoid cross-package collisions.

Spring configurations expose catalogs as beans. Non-Spring users can access
the generated implementation's static `routeCatalog()` method. No classpath
scan, dynamic proxy, or reflection was introduced.

Repositories with the same short name in different packages could otherwise
collide on Spring's default bean name. `@CacheRepository.springBeanName` provides
an explicit escape hatch, and the processor rejects duplicate default or custom
bean names within one compilation. Route-catalog bean names are package-qualified.
The default name of an existing non-conflicting repository remains unchanged,
preserving source compatibility.

### Production Effect

The declared route topology is now machine-readable and can be compared with
coverage, warm schedules, runbooks, and deployment policy.

### Rejected

Runtime annotation scanning was rejected because it increases startup work and
creates two competing interpretations of the compile-time contract.

## Iteration 8: Bounded Operational Evidence

### Problem

The Actuator endpoint showed queue, projection, and Redis pressure but not the
application's declared repository topology or scheduled-warm state. Per-route
metrics would solve discovery but create unsafe cardinality at scale.

### Change

`CacheDbRouteInventory` aggregates generated catalogs without reflection. The
startup line reports repository, total route, hot-route, and warm-route counts.
The `cachedb` Actuator endpoint returns route-kind counts, at most 250 route
details, and at most 100 scheduled-warm details with explicit truncation flags.
Scheduled-warm details are selected with a 100-entry bounded algorithm instead
of first copying and sorting the complete registry, preventing an avoidable
unbounded list allocation during an Actuator scrape.

Micrometer adds aggregate gauges/counters for declared repositories, declared
routes, running warm jobs, warm failures, and skipped warm executions. No
route-name or tenant tag is emitted.

Meter state remains strongly reachable for the lifetime of the registry. A
repeat test exposed and then closed the risk of a gauge becoming `NaN` when the
temporary `MeterBinder` instance was collected early.

### Production Effect

SRE teams can prove that route definitions and scheduled jobs are present while
keeping scrape size and time-series cardinality bounded.

### Rejected

One metric series per route, scope, customer, or tenant was rejected. Those
dimensions belong in bounded logs, traces, or drill-down endpoints.

## Iteration 9: Lower-Allocation Generated And Sample Paths

### Problem

Generated SQL projection routes used stream/map/filter/toList pipelines.
Generated SQL-durable batch commands waited receipt by receipt. Samples repeated
warm execution branching and custom durability error handling.

### Change

Generated projection mapping now uses one pre-sized `ArrayList` and one cached
projector function. Generated local names use a framework-specific prefix to
reduce collisions with repository parameters. Durable batch commands use one
bounded batch helper.

The PostgreSQL and SQL Server samples use `executeWarm` and contextual batch
durability helpers. The two provider samples retain equivalent application
code and compile independently against installed framework artifacts.

### Production Effect

Source projection reads create fewer short-lived objects, batch durability does
less polling/bookkeeping, and sample code teaches the public abstraction rather
than reproducing framework decisions.

### Rejected

JNI/Rust was not introduced. This path is I/O-bound and the measured problem
was avoidable Java allocation and repeated waiting, not CPU-heavy serialization.

## Iteration 10: Documentation, CI, And Verification

### Problem

New safety contracts are incomplete if examples still teach hidden OR logic,
duplicated warm decisions, or unbounded operational metrics.

### Change

English and Turkish repository guides now explain explicit OR groups, common
window handling, plan-owned warm execution, receipt-preserving durability, and
route inventory. Both sample READMEs show the same operational contract.

`check-framework-principles.ps1` now fails CI when implicit OR protection,
route catalog generation, repository bean-name collision safety, pre-sized
source projection mapping, bounded batch durability, or Spring bean isolation
is removed.

Previous public auto-configuration factory signatures remain available as
deprecated compatibility overloads. Adding route-inventory dependencies therefore
does not introduce an avoidable `NoSuchMethodError` for already compiled clients.

### Verification Evidence

The final source state was verified with Semeru/OpenJ9 JDK 21 and real Redis,
PostgreSQL, and SQL Server containers on Docker Desktop:

- after a clean build, a full 20-module reactor rerun on the final source:
  `298` tests, `297` passed, no failure or error; one
  SQL Server test requiring a dedicated listener topology was conditionally skipped;
- standalone PostgreSQL sample: `9/9`, including its Testcontainers integration;
- standalone SQL Server sample: `9/9`, including real SQL Server and Redis;
- the conditionally skipped SQL Server listener test passed in its isolated Docker
  lane: after the stable JDBC endpoint moved from the
  primary to the secondary backend, the stale connection failed as expected, a
  new connection reached a different backend, and provider evidence passed again;
- public API compatibility passed;
- framework-principle, Turkish-doc, README, sample-boundary, provider-parity,
  Markdown-link, and both 59-request Postman collection gates passed;
- `git diff --check` passed in all three working trees.

The full reactor, two standalone samples, and the separately executed listener
test therefore provide `316` successful test scenarios. This total does not
double-count a passing test: it replaces the reactor's conditional listener skip
with the dedicated evidence lane result.

The Docker listener lane is not an SQL Server Always On replication or quorum
certification. It proves the bounded contract relevant to this library: JDBC pool
recovery and successful provider work after a backend switch behind a stable
listener endpoint.

## Deliberately Unchanged

| Boundary | Reason |
| --- | --- |
| No invisible SQL fallback | A cache miss cannot decide database cost or business availability safely. |
| No automatic full-database warm | It would violate Redis memory, database pool, and Kubernetes resource limits. |
| No lazy relation loading | It recreates N+1 and unbounded aggregate hydration. |
| No runtime repository proxy | Compile-time code remains easier to review, benchmark, and operate. |
| No per-route metric tags | Route/scope cardinality can grow without a safe bound. |
| No forced internal `@Primary` Redis client | The application owns its dependency-injection defaults. |

## Production Interpretation

This cycle improves safety and ergonomics; it does not turn CacheDB into a
transparent cache or a general-purpose ORM. The production workflow remains:

1. declare a bounded hot or source route;
2. make every OR predicate group explicit;
3. choose entity or projection admission in the warm plan;
4. dry-run, apply, and prove route coverage;
5. expose hot data only after coverage validation;
6. monitor aggregate route and warm evidence;
7. keep archive access and rollback paths explicit.

The route catalog proves what the application declared. It does not prove that
Redis currently contains complete data. Coverage, parity, latency, memory, and
durability evidence are still separate production gates.
