# Framework UX: Fifth Ten-Iteration Engineering Report

Turkish version: [../tr/docs/framework-ux-besinci-10-iterasyon-raporu.md](../tr/docs/framework-ux-besinci-10-iterasyon-raporu.md)

This report records the fifth complete review and implementation cycle over
CacheDB core APIs, compile-time repository generation, Spring Boot operations,
test support, and the PostgreSQL and SQL Server samples. The engineering
baseline was `v0.8.0`; these additive changes are released in `v0.9.0`.

## Preserved Product Boundaries

- HOT routes remain Redis-only. There is no hidden SQL fallback.
- SOURCE routes remain explicit, bounded, keyset-paged, and time-limited SQL reads.
- Writes remain Redis-first; SQL durability is asynchronous unless the caller explicitly waits.
- Repository implementations and route metadata remain compile-time generated without runtime reflection.
- Growing lists remain projection-first and keyset-paged; offset pagination is not added.
- Coverage is a correctness contract, not a best-effort cache hint.
- PostgreSQL and SQL Server keep the same provider-neutral application surface.

## Iteration Summary

| Iteration | Implemented outcome |
| --- | --- |
| 1 | Preserve page limits across cursor continuation and add allocation-bounded page mapping. |
| 2 | Infer unambiguous query parameters at compile time. |
| 3 | Infer lookup and warm parameter roles without silent choices. |
| 4 | Allow strict HOT and bounded SOURCE routes to return `CursorPage<T>` directly. |
| 5 | Prove that every coverage scope is an equality constraint in every OR group. |
| 6 | Generate reflection-free, typed route references for every repository method. |
| 7 | Accept generated route references in warm, coverage, and test APIs. |
| 8 | Publish aggregate HOT route memory and design evidence through Actuator and Micrometer. |
| 9 | Add explicitly named, timeout-bounded single-write SQL durability helpers. |
| 10 | Move both samples to the shorter API, strengthen CI, and document the exact contract. |

## Iteration 1: Cursor Continuation Keeps Its Bound

**Finding.** A caller could build the next request from a page, but it had to
repeat the limit manually. DTO mapping also required rebuilding a page and
remembering to preserve its cursor.

**Implementation.** `WindowRequest.continueAfter`,
`WindowSlice.nextRequest(WindowRequest)`, and
`CursorPage.nextRequest(WindowRequest)` preserve the validated limit.
`CursorPage.map` uses one pre-sized list and preserves the opaque cursor.

**Production result.** Continuation code cannot accidentally change the page
shape merely because a controller repeated a different limit. Mapping adds no
stream pipeline or unbounded intermediate allocation.

**Rejected.** Adding a third `limit` field to the public `CursorPage` record was
rejected because it would change the stable JSON shape and record constructor.

## Iteration 2: Query Parameter Roles Are Inferred Safely

**Finding.** Repository methods repeated declarations such as
`windowParameter = "window"`, `limitParameter = "limit"`, and
`parameter = "customerId"` even though types and names already made the role
unambiguous.

**Implementation.** The processor now infers a same-name compatible predicate
parameter, the single `WindowRequest`, or the single unused integer limit. An
explicit value remains supported and always wins.

**Production result.** Normal routes contain less string coupling while all
type, consumption, row-bound, and predicate checks still run at compilation.

**Rejected.** Runtime method inspection and parameter-name reflection were
rejected. Multiple candidates produce a compile error instead of a heuristic
choice.

## Iteration 3: Lookup And Warm Roles Are Inferred

**Finding.** Point lookups and warm plans repeated ID, relation preview, row
limit, target, and coverage-scope names.

**Implementation.** `@CacheLookup` infers the sole ID-compatible parameter and
the sole unused integer relation limit. `@WarmRoute` infers row limit and
`CacheWarmTarget` after resolving the source route, and inherits the source HOT
coverage scope when that parameter exists.

**Production result.** A warm declaration states policy rather than wiring.
The source route remains the authority, so filter parameters cannot be mistaken
for row limits.

**Rejected.** Positional inference was rejected. Parameter order is not a safe
semantic contract, especially after refactoring.

## Iteration 4: Repository Routes Can Return Transport Pages

**Finding.** Application services repeatedly converted `HotWindow` with
`completePage()` and `SourceWindow` with `page()` even when no coverage-specific
business decision existed.

**Implementation.** Generated `@HotRoute` and `@SourceRoute` methods may now
return `CursorPage<T>`. A HOT page return is accepted only when the resolved
route is strict; generated code enforces fresh, complete coverage before
returning the page. Window return types remain available.

**Production result.** The common REST path becomes one repository call while
the advanced path can still retain full coverage evidence.

**Rejected.** Making every route return a page was rejected. Operational code
that reasons about partial, stale, or not-warmed coverage must keep
`HotWindow<T>`.

## Iteration 5: Coverage Scope Cannot Lie

**Finding.** Earlier validation proved that a scope name referenced a method
parameter, but did not prove that the query was actually restricted by that
scope in every OR branch.

**Implementation.** A scoped HOT route must use its coverage parameter in
exactly one `EQ` predicate per query group, and every group must constrain the
same entity field.

**Production result.** A customer, tenant, shipment, or order scope cannot be
marked complete for a query branch that includes another scope's rows.

**Rejected.** `GTE`, `IN`, and best-effort scope detection were rejected because
they do not define one stable coverage identity.

## Iteration 6: Generated Route References Replace Raw Names

**Finding.** Runtime catalogs were generated, but application and test code
still used strings such as `"customer-order-timeline"`.

**Implementation.** Every repository now gets a companion such as
`OrderRepositoryCacheDbRoutes`. Its methods return immutable
`RepositoryRouteRef` values resolved from the static generated catalog.

**Production result.** Route renames become compilation changes. No classpath
scan, proxy, or reflective lookup was introduced.

**Rejected.** An enum was rejected because repository methods may span route
kinds and generated metadata carries more information than an enum name.

## Iteration 7: Operational APIs Are Route-Reference Aware

**Finding.** A typed route reference has little value if warm plans, coverage
checks, and tests immediately convert it back to a string.

**Implementation.** `CacheWarmPlan.Builder`, `CacheDatabase`, and
`CacheDbTestProbe` accept `RepositoryRouteRef`. Kind checks prevent a WARM,
SOURCE, or COMMAND reference from being used as HOT coverage. Projection warm
configuration can derive the generated projection name from the route.

**Production result.** Build-time metadata now reaches staging evidence without
losing identity or kind safety.

**Rejected.** A global mutable route registry was rejected. Generated static
catalogs and the existing Spring inventory remain the only authorities.

## Iteration 8: Route Inventory Becomes Capacity Evidence

**Finding.** Actuator exposed route counts but did not summarize how many HOT
routes were projection-backed, scoped, or missing a declared memory budget.

**Implementation.** `HotRouteAssessment` aggregates HOT route count,
projection/entity split, scoped count, budgeted/unbudgeted count, and saturated
sum of declared memory budgets. Actuator, startup logs, and bounded-cardinality
Micrometer gauges expose the assessment.

**Production result.** Operators can see design debt before load arrives. Route
names are deliberately not metric tags, preventing unbounded cardinality.

**Rejected.** Treating the sum as actual Redis consumption was rejected. It is
declared capacity evidence; real Redis memory and admission metrics remain the
runtime truth.

## Iteration 9: SQL Durability Is Shorter But Still Explicit

**Finding.** A command that genuinely needs SQL durability required two calls,
while careless helper naming could make synchronous durability look like the
default write model.

**Implementation.** `saveDurably`, optimistic `saveDurably`,
`saveAfterDurably`, `deleteDurably`, and `updateHotDurably` require an explicit
positive timeout and return the original typed receipt.

**Production result.** Correct single-command durability code is concise while
normal writes remain Redis-first and asynchronous.

**Rejected.** Per-row durable helpers for bulk import were rejected. Bulk work
must continue through `CacheDurableBatchWriter` to preserve batching and
backpressure.

## Iteration 10: Samples And Gates Use The Product Surface

**Finding.** A framework feature is not complete if the public examples retain
the old repetitive style or CI stops recognizing the newer return type.

**Implementation.** Both provider samples now use inferred parameter roles,
direct `CursorPage<T>` routes, generated route references in integration tests,
and no application-level page conversion. The sample framework-usage gate now
recognizes both `HotWindow` and `CursorPage` HOT methods and rejects redundant
role bindings. English and Turkish guides show the exact compiled form.

**Production result.** PostgreSQL and SQL Server still present one application
architecture. The examples are shorter without moving data-path decisions into
hidden runtime behavior.

**Rejected.** Generated controllers, automatic SQL fallback, offset paging, and
runtime repository scanning remain rejected. They reduce visible code by
hiding decisions that must stay reviewable in production.

## Compatibility And Release State

- `v0.9.0` is the immutable release containing this engineering cycle.
- The repository and both sample releases use the same `0.9.0` package line.
- Existing explicit annotation attributes remain source compatible.
- Existing `HotWindow<T>` and `SourceWindow<T>` route signatures remain valid.
- `CursorPage<T>` keeps its two-field transport shape: `items` and `nextCursor`.
- The new API adds no runtime reflection or hidden source read.

## Verification

The cycle was verified with IBM Semeru OpenJ9 Java 21.0.2 and Maven 3.9.9:

- The full Maven reactor completed with `318` tests, `0` failures, `0` errors,
  and `12` existing environment/profile skips. Redis and PostgreSQL were
  supplied through the repository's local integration containers and explicit
  test connection properties.
- The first reactor attempt intentionally remained visible in the evidence: it
  failed because no service was listening on the default `6379` and `5432`
  ports. After the official integration containers reported `PONG` and
  `accepting connections`, the same reactor scope passed in `870.2 s`.
- `mvn -DskipTests install` installed the complete `0.9.0` artifact
  set into the local Maven repository before either sample was built.
- The PostgreSQL sample passed its Testcontainers Redis 8.2.1 + PostgreSQL 16
  provider integration profile in `94.2 s`.
- The SQL Server sample passed its Testcontainers Redis 8.2.1 + SQL Server 2022
  provider integration profile in `67.3 s`.
- Public API comparison showed additions only. The intentional development
  baseline was regenerated, then the compatibility check passed deterministically.
- Framework principles passed across `807` Java files; declarative sample
  boundaries passed across `120` Java files; provider parity passed across `63`
  neutral files and the shared integration contract.
- English/Turkish README quality, Turkish language quality, Markdown links,
  and both `59`-request Postman collections passed their repository gates.
- Generated sample sources contain typed `*CacheDbRoutes` companions and place
  strict HOT/SOURCE page completion inside generated implementations rather
  than application services.

No runtime reflection, automatic SQL fallback, offset pagination, generated
controllers, or row-by-row durable bulk helper was introduced.
