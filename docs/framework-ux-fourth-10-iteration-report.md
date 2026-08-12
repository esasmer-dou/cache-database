# Framework UX: Fourth Ten-Iteration Engineering Report

Turkish version: [../tr/docs/framework-ux-dorduncu-10-iterasyon-raporu.md](../tr/docs/framework-ux-dorduncu-10-iterasyon-raporu.md)

This report records the fourth full review, implementation, and verification
cycle over CacheDB core, compile-time generation, Spring Boot integration, test
tooling, and the PostgreSQL and SQL Server samples. The released baseline is
`0.7.1`. The implemented changes in this engineering record are included in
the `0.8.0` release; the authoritative distribution summary remains the
`v0.8.0` release note.

## Preserved Product Boundaries

- HOT routes remain Redis-only and fail explicitly when coverage is not usable.
- SOURCE routes remain explicit, bounded, indexed, and time-limited SQL reads.
- Writes remain Redis-first; SQL durability is asynchronous and observable.
- Repository implementations remain compile-time generated without runtime reflection.
- Growing lists use projections and keyset windows, not offset scans or full aggregates.
- Warm and background work remain bounded, resumable, idempotency-aware, and multi-pod capable.
- PostgreSQL and SQL Server keep one application architecture and provider-specific SQL behavior.

## Iteration Summary

| Iteration | Outcome |
| --- | --- |
| 1 | Bind every new continuation cursor to route, scope, and sort contract. |
| 2 | Preserve keyset continuation through application and HTTP layers with `CursorPage<T>`. |
| 3 | Add compile-time repository route defaults with method-level override priority. |
| 4 | Replace raw byte literals with named compile-time memory-budget constants. |
| 5 | Replace manual sample limit helpers with declarative Bean Validation. |
| 6 | Make distributed job handlers definition-first and typed. |
| 7 | Add a bounded structured checkpoint/progress contract. |
| 8 | Move durable import batching and receipt backpressure into the framework. |
| 9 | Add a dry-run/apply/coverage integration-test journey. |
| 10 | Extend regression gates, examples, bilingual docs, and real-provider evidence. |

## Iteration 1: Cursor Use Is Contract-Bound

### Finding

The cursor stored stable sort values but did not identify the route or parent
scope that produced them. A valid customer-42 token could be supplied to a
customer-43 request and be interpreted under the wrong query contract.

### Implementation

New `WindowCursor` tokens include a version and SHA-256 fingerprint of the
generated route name, normalized scope, and ordered sort fields/directions.
`KeysetPagination` receives that contract from generated repository code.
`CursorContractMismatchException` reports an explicit mismatch. Legacy tokens
remain readable so existing clients are not broken immediately.

### Production Effect

Accidental cross-route and cross-scope continuation is rejected before query
evaluation. The fingerprint is a correctness binding, not an HMAC or an
authorization boundary; API authorization must still validate the requested
tenant/customer scope.

### Rejected

Offset pagination was rejected because deep pages grow in cost and drift under
concurrent writes. A server-side cursor session was rejected because it would
add state, cleanup, and cross-pod affinity.

## Iteration 2: HTTP Keeps The Continuation Token

### Finding

Generated repositories returned `nextCursor`, but sample application services
converted windows to bare lists. Users could not copy the sample and continue
past the first keyset page.

### Implementation

`CursorPage<T>` exposes immutable `items` and optional `nextCursor`.
`HotWindow.completePage()` enforces fresh, complete Redis coverage before
conversion; `SourceWindow.page()` keeps the explicit SQL origin. `WindowRequest.of`
maps an optional HTTP `after` value without branch duplication. Pageable sample
endpoints now accept `after` and return the page contract.

### Production Effect

The transport layer no longer discards the storage-safe continuation token.
HOT routes still cannot expose a partial page as success, while SOURCE routes
do not imply that SQL rows were admitted to Redis.

### Rejected

A generic framework response envelope for every endpoint was rejected. It
would impose HTTP policy on non-web consumers and add fields unrelated to the
repository contract.

## Iteration 3: Repository Defaults Are Compile-Time Policy

### Finding

Every HOT method repeated the same population strategy, while source row and
timeout limits repeated repository-wide. Repetition obscured exceptional
routes and created drift risk.

### Implementation

`@CacheRepositoryDefaults` defines HOT population/page/window/memory/staleness/
strictness, SOURCE row/timeout, and WARM row defaults. The processor inspects
annotation mirrors to distinguish an omitted value from an explicit method
value. Method values always win. Invalid repository defaults fail compilation.

### Production Effect

Samples declare `DECLARED_WARM` once per repository while route-specific
projection, scope, window, and memory decisions remain visible. Generated
metadata contains resolved values; no runtime configuration lookup was added.

### Rejected

Global mutable defaults and environment-driven route semantics were rejected.
The same artifact must not silently compile one contract and run another.

## Iteration 4: Memory Budgets Are Readable

### Finding

Values such as `16_777_216L` were correct but difficult to review and easy to
mistype in annotations.

### Implementation

`CacheMemoryBudget.MIB_1` through `MIB_256` provide primitive compile-time
constants. Both samples now use names such as `MIB_8`, `MIB_16`, and `MIB_32`.

### Production Effect

Code review can compare route budgets quickly without mental byte conversion.
There is no runtime allocation, unit parser, or hidden rounding.

### Rejected

String values such as `"16MiB"` were rejected because annotation parsing would
move errors to processor/runtime code and weaken normal Java constant checking.

## Iteration 5: HTTP Limits Are Declarative

### Finding

Sample controllers repeated `ApiLimits.requireInRange` calls. The helper added
imperative noise and did not make endpoint constraints visible in signatures.

### Implementation

Controllers use `@Validated`, `@Min`, `@Max`, `@Positive`, `@Size`, and existing
request-body validation. Cursor input is capped at 8 KiB. `ApiLimits` was
removed, and the real provider tests retain oversized-input HTTP evidence.

### Production Effect

The endpoint contract is readable where parameters are declared and Spring
maps violations through the existing error surface. Query bounds remain
enforced again inside core APIs, so HTTP validation is not the only defense.

### Rejected

Silently clamping limits was rejected because callers would believe they
received the requested shape and could make incorrect pagination decisions.

## Iteration 6: Distributed Jobs Have One Definition

### Finding

Class-based handlers repeated route and argument type methods even after
`CacheDistributedJobDefinition<A>` had become the producer contract.

### Implementation

`CacheDistributedJobHandler.Typed<A>` derives `route()` and `argumentType()`
from one required `definition()`. Existing handlers remain source compatible.
Sample seed and warm jobs implement the typed contract, and producers submit
through the same definition.

### Production Effect

Producer, handler registration, deserialization, and every pod use one route/
payload source of truth. Rolling deployments still require compatible handler
sets, but local string/type drift is removed.

### Rejected

Runtime classpath scanning and proxy-created handlers were rejected because
they would add startup magic and violate the reflection-free integration model.

## Iteration 7: Checkpoints Are Structured And Bounded

### Finding

The checkpoint API accepted arbitrary maps. Samples could create inconsistent
field names or oversized operational payloads in Redis.

### Implementation

`CacheDistributedJobProgress` validates phase, positive attempt, optional
0-100 percent, 512-character message, and at most 16 bounded attributes.
`CacheDistributedJobContext` has a typed overload, and sample handlers use it.
The original object overload remains for compatibility and domain-specific
resume state.

### Production Effect

Normal job progress has stable semantics and bounded serialization size.
Domain resume checkpoints can still be richer, but must remain explicitly
owned and compatible across pods/releases.

### Rejected

Removing the object checkpoint API immediately was rejected as an unnecessary
breaking change for existing resumable jobs.

## Iteration 8: Durable Batch Backpressure Is Framework Code

### Finding

`SampleSeedService` contained its own buffering, receipt accumulation,
backpressure threshold, and durability wait implementation. Users could copy
an example-specific helper instead of a supported framework surface.

### Implementation

`CacheDurableBatchWriter<T, ID>` batches repository `saveAll` calls, requires
one receipt per input, caps pending receipts, waits for SQL durability, and
returns `CacheDurableBatchResult`. `CacheDatabase.durableBatchWriter(...)`
creates it with an explicit operation label, batch size, pending limit, and
timeout. The duplicate sample inner class was removed.

### Production Effect

Large imports apply bounded memory and write-behind backpressure consistently.
`finish()` is the durability boundary; a timeout remains an unknown SQL outcome,
not proof that replay is safe.

### Rejected

Unbounded receipt accumulation and per-row SQL durability polling were rejected
for memory, Redis round-trip, and database throughput reasons.

## Iteration 9: Warm Tests Produce One Journey Evidence Object

### Finding

Tests could dry-run, apply, and inspect coverage separately, but it was easy to
skip one step or accidentally use different plans/scopes.

### Implementation

`CacheDbTestProbe.dryRunApplyAndRequireCoverage` executes the same plan in
`DRY_RUN` and `APPLY`, asserts zero dry-run submissions, requires complete fresh
coverage, and returns `CacheDbWarmRouteEvidence`. Both provider integration
tests exercise a generated projection warm plan through this method.

### Production Effect

Teams can prove plan safety and route readiness with one repeatable test
contract. The evidence deliberately does not claim data parity, latency,
memory fit, failover, or long-duration stability.

### Rejected

Automatically treating coverage as full cutover readiness was rejected because
coverage says nothing about baseline parity or SLO compliance.

## Iteration 10: Regression Gates And Documentation Follow The API

### Finding

CI assumed every HOT method repeated `population=DECLARED_WARM`, and sample docs
still showed raw bytes, list-only cursor use, and local durability batching.

### Implementation

The framework-principles gate now understands repository defaults and checks
cursor binding, named memory constants, typed handlers, structured progress,
declarative input validation, and framework batch backpressure. EN/TR core and
sample docs now show copy-paste examples for the resolved APIs.

### Production Effect

Future simplification cannot silently remove the safety contracts introduced
in this cycle. PostgreSQL and SQL Server examples describe the same application
model and are verified against real provider containers.

### Rejected

Documentation-only declarations without executable CI checks were rejected.
The repository must keep code, generated output, examples, and tests aligned.

## Verification Evidence

- Integrated core/processor/starter/Spring/testkit Maven test selection: passed.
- Full reactor install with current sources: passed.
- PostgreSQL sample unit tests and real PostgreSQL + Redis provider lane: passed.
- SQL Server sample unit tests and real SQL Server + Redis provider lane: passed.
- Final full-reactor, public-API, framework-principles, and documentation gates are recorded in the completion report for this working tree.

## Final Assessment

This cycle improves framework usability without weakening explicit storage
semantics. The main gain is not fewer characters by itself: repeated policy is
centralized only where compile-time resolution remains deterministic, while
route scope, projection, bounds, durability, and coverage stay explicit.

The deliberately unimplemented ideas are hidden SQL fallback, automatic full
database startup warm, offset pagination, runtime repository proxies, unbounded
job checkpoints, unbounded batch receipts, and high-cardinality route metrics.
