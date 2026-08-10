# Framework UX: Ten-Iteration Engineering Report

Turkish version: [../tr/docs/framework-ux-10-iterasyon-raporu.md](../tr/docs/framework-ux-10-iterasyon-raporu.md)

This report records ten consecutive review, implementation, and verification
iterations over the CacheDB core and PostgreSQL/MSSQL samples. The goal was to
reduce application code without hiding Redis coverage, SQL durability, memory,
or multi-pod behavior.

## Decision Boundary

The work preserves these non-negotiable rules:

- hot routes are Redis-only and never fall back to SQL invisibly;
- archive reads use explicit, bounded source routes;
- list screens use bounded projections and keyset windows;
- writes are accepted by Redis first and expose SQL durability through receipts;
- generated code replaces runtime reflection and dynamic repository proxies;
- warm jobs are bounded, observable, retryable, and coordinated across pods;
- incomplete coverage, missing update state, and invalid configuration fail explicitly.

## Iteration Evidence Matrix

| Iteration | Primary evidence |
| --- | --- |
| 1 | Public surface, generated implementations, provider starters, samples, and committed `0.6.0` API baseline were traced together. |
| 2 | Core window tests and live sample HTTP tests prove that an unwarmed list fails instead of returning a misleading empty result. |
| 3 | Processor compilation tests verify bounded first-page generation while pageable routes retain keyset cursors. |
| 4 | Repository contract tests prove that optimistic updates do not issue a hidden SQL read or accept missing hot state. |
| 5 | Processor tests compile generic repository fragments and reject invalid route declarations with `[CacheDB]` diagnostics. |
| 6 | Spring property tests reject invalid pools, timeouts, leases, queues, credentials, and MSSQL settings during startup. |
| 7 | Test-kit tests cover complete coverage, tombstone, outside-policy, controlled warm, and bounded durability waiting. |
| 8 | CI inspected 120 sample Java files, 62 provider-neutral files, and the shared PostgreSQL/MSSQL integration contract. |
| 9 | CI inspected 783 Java files for reflection and generated-code allocation rules; generated scheduled tasks call typed methods directly. |
| 10 | Documentation, API, packaging, benchmark, Postman, provider, and clean-reactor gates all passed in the final verification cycle. |

## Iteration 1: Architecture And API Baseline

The repository surface, generated bindings, processor model, Spring Boot
auto-configuration, test kit, and both samples were traced end to end. The
review separated application-facing APIs from low-level compatibility APIs and
confirmed that provider selection, Redis-first writes, source reads, and warm
execution remain explicit.

Rejected: replacing route contracts with generic method-name parsing or a
transparent ORM fallback. That would make cost and data-source selection
unreviewable.

## Iteration 2: Complete Hot-Result Contract

`HotWindow.completeItems()` now returns rows only when route coverage is
complete and fresh. Otherwise it throws `HotRouteUnavailableException` with
the original coverage evidence. A mapper overload lets an application convert
that evidence to its own exception without losing the reason.

The samples use this method for hot endpoints and map incomplete coverage to
HTTP 503. Source/archive routes continue to return their bounded SQL result.

Risk removed: an incomplete Redis window can no longer look like a valid empty
or short business result when the safe API is used.

## Iteration 3: First-Page And Keyset Read Shapes

Top-N and first-page routes now declare an `int limit` through
`@CacheRouteQuery(limitParameter = "limit")`. Generated code converts it to a
bounded first-window request. Timeline and archive routes retain explicit
`WindowRequest` parameters because they expose keyset pagination.

Risk removed: simple endpoint code no longer constructs pagination objects,
while pageable routes do not lose cursor semantics or fall back to offset scans.

## Iteration 4: Safe Optimistic Updates

`CacheDbRepository.updateHot` centralizes the read-version-transform-save flow.
It requires a complete entity result from the update function and throws
`HotUpdateUnavailableException` if no current Redis version exists. It never
loads SQL and silently merges a partial command.

Sample services use this contract and map missing hot update state to HTTP 409.
The unsupported `idempotent` annotation flag was removed because a flag without
a deduplication store and stable command key would create a false guarantee.

Risk removed: lost updates, partial-entity corruption, and fictional retry
idempotency.

## Iteration 5: Extensible Compile-Time Repositories

Repository entity and ID types are resolved through substituted generic
supertypes. Teams can now define typed base repository fragments with default
convenience methods and still receive generated implementations. Default,
static, and private methods are not treated as unsupported abstract routes.

Processor diagnostics use a consistent `[CacheDB]` prefix and invalid fields,
parameters, SQL, limits, and signatures still fail compilation.

Risk removed: users no longer copy repository boilerplate or lose compile-time
validation when introducing a shared interface hierarchy.

## Iteration 6: Fail-Fast Spring Configuration

`CacheDbSpringProperties` validates Redis pool bounds and timeouts, leader-lease
renewal, scheduled-warm executors, distributed-job queues/retries, admin
security inputs, and MSSQL timeout settings during startup.

Risk removed: impossible pool sizes, lease renewal after expiry, empty auth
tokens, and invalid worker/queue values no longer fail under production load.

## Iteration 7: Declarative Test Support

`CacheDbAssertions` covers complete routes, tombstones, and outside-policy
lookups. `CacheDbTestProbe.warmAndRequireCoverage` combines controlled warm with
coverage proof. Typed durability helpers return the original receipt so tests
can continue without casts or repeated bookkeeping.

Risk removed: integration tests can no longer declare success after warming
rows without proving the route scope clients will read.

## Iteration 8: Sample Layer Boundaries

PostgreSQL and MSSQL samples use the same repository contracts, application
services, error mapping, and generated-code boundaries. Controllers do not
import repositories or CacheDB internals; business services do not bootstrap
the runtime or reference generated implementation classes.

`check-sample-framework-usage.ps1` and provider parity checks make these rules
CI failures. The architecture gate also requires every sample `HotWindow`
method to have a bounded matching `@WarmRoute`; a readable route without an
operational population path is rejected.

Risk removed: examples can no longer teach users to bypass the public framework
surface or implement provider-specific business logic accidentally.

## Iteration 9: Allocation And Reflection Discipline

Generated repositories reuse static route sort lists and route contracts.
Bulk saves reuse the caller collection when IDs do not need generation and use
one pre-sized list when they do. Stream pipelines and raw receipt casts were
removed from generated write paths.

`@CacheScheduledWarm` is now source-retained. Its processor validates the
method and generates a typed Spring task adapter with a direct method call.
Runtime annotation scans, dynamic scheduling proxies, and `Method.invoke` are
not used. Redis lease and reconciliation semantics remain unchanged.

`check-framework-principles.ps1` rejects runtime reflection and missing
generated-code allocation safeguards in CI.

Risk removed: startup scanning, reflective invocation, repeated route metadata
allocation, and avoidable bulk-command garbage.

## Iteration 10: Documentation And Release Evidence

English and Turkish guides now show `completeItems()`, distinguish top-N limits
from keyset pagination, explain the no-SQL-merge update rule, and document the
compile-time scheduled-warm adapter. Removed API members are no longer shown in
copy-paste examples.

Documentation, public API, sample architecture, provider parity, release
artifacts, and framework principles are separate CI gates. This prevents a
passing unit suite from hiding a broken onboarding or architecture contract.
The sample quick starts and Postman collections follow the same ordered flow:
seed durable data, submit the exact route warm, poll the distributed job to
`COMPLETED`, and only then call the coverage-enforced hot endpoint.

## Deliberately Not Implemented

| Rejected change | Reason |
| --- | --- |
| Automatic SQL fallback from a hot route | Hides latency, load, and incomplete coverage |
| Runtime repository proxies or query-name parsing | Violates compile-time validation and predictable allocation goals |
| Transparent lazy relations | Reintroduces N+1 and unbounded aggregate loading |
| Automatic SQL merge for a missing partial update | Can overwrite fields and breaks explicit command ownership |
| Unbounded warm or full-table read | Violates memory, pool, backpressure, and Kubernetes limits |
| Annotation-only idempotency claim | Cannot guarantee deduplication without a stable key and durable state |

## Production Outcome

Application code is smaller, but infrastructure behavior is more explicit, not
more magical. The recommended route lifecycle is still: define a bounded route,
warm it, prove coverage and parity, expose it with `completeItems()`, monitor it,
and keep archive access as an explicit source route.

The changes improve framework usability without changing CacheDB into a general
purpose ORM or pretending that Redis contains the full durable database.

## Final Verification Evidence

| Gate | Result |
| --- | --- |
| Clean reactor | 20 projects, 283 tests, 0 failures, 0 errors, 3 explicitly topology-gated skips |
| Core integration | 90 tests against live Redis 8 and PostgreSQL, including MSSQL outbox multi-pod coverage |
| Production smoke | 27 tests covering crash replay, fault injection, coordination, certification, soak, recovery, and benchmark shapes |
| Standalone PostgreSQL sample | CacheDB doctor passed; 8 unit tests and 1 live PostgreSQL 16 + Redis 8 integration test passed |
| Standalone MSSQL sample | CacheDB doctor passed; 8 unit tests and 1 live SQL Server 2022 + Redis 8 integration test passed |
| Performance | Repository, relation-shape, and ranked-projection benchmark thresholds passed |
| Packaging | Binary, source, and javadoc jars validated for 16 public modules; BOM validated |
| Postman | 59 requests per provider, 15 required warm routes, warm-before-hot order, job completion assertion, and provider parity passed |
| Public API | 507 signature lines added versus committed `0.6.0`; no published method or constructor removed |
| Static architecture | 120 sample Java files and 783 framework Java files passed boundary, reflection, and allocation checks |

The first clean reactor attempt failed loudly because Redis and PostgreSQL were
not listening on the configured test ports. After dedicated Redis 8,
PostgreSQL 16, and SQL Server 2022 test containers were started, the same clean
command passed. No product defect was hidden by skipping provider-dependent
tests.
