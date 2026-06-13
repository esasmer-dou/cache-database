# CacheDB Project Memory

Last updated: 2026-06-13

Purpose: this file is an internal handoff note for future Codex/chat sessions.
It summarizes the product direction, completed work, validation evidence, and
remaining production gates. It is not release marketing copy.

## Repository State

- Repository path: `E:\ReactorRepository\cache-database`
- Remote: `ssh://git@ssh.github.com:443/esasmer-dou/cache-database.git`
- Main branch status at the time of this note: clean and aligned with `origin/main`
- Latest release prepared and published: `v0.1.0-beta.3`
- Latest release asset: `cache-database-0.1.0-beta.3-public-beta.zip`
- Public positioning: strong public beta, not GA yet

## Product Direction

CacheDB is a Java/Spring Boot data-layer framework that uses PostgreSQL as the
durable source of truth and Redis as a hot read/write coordination layer.

The design direction is:

- compile-time generated mapping, not reflection-heavy runtime magic
- Redis-first hot reads and writes, PostgreSQL durability behind it
- projection/read-model first for relation-heavy and global sorted screens
- explicit route contracts for expensive read shapes
- bounded hot-set memory, not "put the whole database in Redis"
- production evidence before GA claims

The product should not be described as a drop-in ORM that makes every query fast
automatically. The honest message is: CacheDB works best when hot entities,
route contracts, projections, warm plans, and cutover evidence are designed
explicitly.

## Architecture Rules Already Established

- PostgreSQL remains the durable source of truth.
- Redis holds bounded hot entities, projection windows, route indexes, stream
  state, leader leases, and operational telemetry.
- Relation-heavy first-paint screens must use summary/projection models.
- Full aggregate loading is for detail screens or controlled cold paths, not
  hot list screens.
- Global sorted/range screens need ranked projection/read-model paths.
- Multi-pod Kubernetes deployments rely on Redis for coordination.
- Consumer names must be pod/instance unique.
- Singleton operational loops should use Redis leader lease behavior.
- External PostgreSQL writes require outbox/CDC input, otherwise Redis can go
  stale.
- Database-originated outbox/CDC events must apply in cache-only mode by
  default; they must not be sent back into PostgreSQL write-behind as if they
  were new CacheDB commands.
- Production strict mode should fail fast if a projection-required route falls
  back to broad entity scanning.

## Major Work Completed

### Public Beta Hygiene and Release

- Added public repo hygiene files: license, contributing, security, code of
  conduct, support, issue templates, PR templates, and release checklist.
- Added release packaging scripts and public beta package generation.
- Published `v0.1.0-beta.1`, `v0.1.0-beta.2`, and `v0.1.0-beta.3` release
  notes over the project lifecycle.
- Current release is `0.1.0-beta.3`.

### Documentation

- Reworked English and Turkish README/onboarding docs.
- Clarified Spring Boot JDBC dependency behavior.
- Added production recipes for projection-first and route-contract usage.
- Added use-case examples for insert, read, update, delete, query, dashboard,
  reporting, projection, hot-set, and anti-pattern scenarios.
- Added Turkish documentation cleanup passes for correct Turkish semantics and
  Turkish characters.
- Added production GA criteria and go/no-go gates.

### Admin UI and Migration Planner

- Improved admin dashboard URL shape and navigation.
- Reworked admin category map into clearer operational sections.
- Refactored Migration Planner CSS/JS out of large inline Java strings.
- Split Migration Planner resource structure into more maintainable assets and
  partial-like helpers.
- Made Migration Planner more user friendly:
  - PostgreSQL schema discovery
  - table/view selection
  - route candidate suggestions
  - form auto-fill from discovered candidates
  - dry-run execution
  - warm execution
  - side-by-side comparison
  - migration report download
  - cutover action plan
  - coverage guidance
- Fixed several UX/runtime issues:
  - buttons using full page reload instead of async behavior
  - plan result shown too narrow
  - warm running too long without useful progress/error visibility
  - route candidate list showing too few choices
  - overflowing text in cards and left navigation

### Migration Planner Capabilities

- Discovery can inspect PostgreSQL schema and present route candidates.
- Planner can generate route decisions for relation-heavy shapes.
- Planner can generate CacheDB entity skeletons, relation loader skeletons, and
  projection support skeletons from discovered schema.
- Planner can run dry-run warm analysis without mutating Redis.
- Planner can warm Redis for selected hot windows.
- Planner can compare PostgreSQL baseline SQL against CacheDB route output.
- Planner can generate downloadable migration reports with readiness state,
  blockers, next steps, rollback notes, and cutover action plan.
- Latest product requirement: full conversion needs route coverage, not only one
  selected route. GA must require coverage for every screen/API/batch/report.

### Projection and Read-Model Performance

- Added stronger projection-first guidance for relation-heavy and global sorted
  reads.
- Added ranked/global-sorted read-model direction.
- Added route-specific fast-path expectations:
  - top-N windows
  - per-parent hot windows
  - projection-required routes
  - summary first, detail later
- Improved comparison flow so projection-required demo routes can use projection
  labels instead of falling back to entity routes.

### Multi-Pod/Kubernetes Coordination

- Added automatic instance identity behavior for safer consumer naming.
- Added Redis leader lease direction for singleton operational loops.
- Added multi-instance coordination smoke path.
- Added CI evidence lane for multi-pod/coordination regressions.
- Clarified that Redis is the real coordination center and therefore Redis HA is
  a production dependency, not an optional optimization.

### Hot-Set Policy and Memory Discipline

- Extended entity cache policy beyond simple TTL/row-count thinking.
- Added explicit hot policy concepts:
  - count window
  - time window
  - state window
  - custom predicate
  - composite hot policy
- Added route-level cache contracts:
  - page size
  - hot window
  - projection required
  - max cold read size
  - memory budget
  - tenant quota
  - strict mode
- Added read-shape guardrails so huge reads do not silently blow past the hot
  entity budget.
- Added tenant quota support with hot-row and payload-level memory accounting.
- Added Redis memory estimator and calibration output for migration warm plans.
- Added cache admission telemetry so accepted/rejected/evicted hot-set behavior
  is visible.

### Outbox/CDC

- Added core contracts:
  - `ExternalChangeEvent`
  - `ExternalChangeType`
  - `ExternalChangeSink`
  - `ExternalChangeFeedAdapter`
  - `ExternalChangeApplyMode`
  - `ExternalChangeApplyResult`
  - `ExternalChangeHydrationRepository`
- Added concrete PostgreSQL outbox adapter:
  - `PostgresOutboxExternalChangeFeedAdapter`
  - checkpoint table support
  - polling API
  - background daemon loop
  - safe identifier validation
  - basic JSON payload parsing
- Added `ExternalChangeApplyRunner`:
  - default `CACHE_ONLY` mode for database-originated events
  - optional `CACHE_AND_WRITE_BEHIND` mode for trusted command-originated events
  - generated `EntityCodec.fromColumns(...)` support for default UPSERT and
    DELETE id resolution
  - custom handler hook for partial payload semantics
  - fail-fast sink behavior so outbox checkpoint does not advance on failed
    apply
- Added Redis repository external hydration:
  - cache-only UPSERT hydration
  - cache-only DELETE tombstone/index/projection cleanup
  - stale external event guard based on Redis version/tombstone version
- Public API compatibility decision:
  - the new `ExternalChangeApply*` and `ExternalChangeHydrationRepository`
    types are an intentional public beta API expansion
  - the baseline in `tools/ci/public-api-baseline.txt` must include these
    signatures
  - this is not a breaking change for existing users

### Production Evidence and CI

- Added production evidence workflow.
- Added production scenario certification lane.
- Added Redis failover evidence lane.
- Added staging Redis HA workflow skeleton.
- Added migration coverage validation workflow support.
- Added benchmark threshold checks.
- Added public API compatibility checks.
- Added Turkish docs quality check.
- Added production evidence summaries.
- Hardened benchmark gates so structural materialization checks are authoritative
  and flaky "fastest microbenchmark" noise does not block incorrectly.

## Latest Release: 0.1.0-beta.3

Key additions:

- production scenario certification lane
- concrete PostgreSQL outbox adapter
- route-level cache contracts
- tenant quota and payload-level memory budget accounting
- composite hot policy support
- migration warm checkpoint/resume
- Redis memory calibration output
- cache admission telemetry

Key fixes:

- repeated writes of the same entity no longer double-count tenant payload bytes
- warm batch hydration now carries tenant columns and payload estimates
- release bundle copies the release note for the selected version
- PostgreSQL outbox/CDC events can now be applied to Redis hot state through
  `ExternalChangeApplyRunner` without re-enqueuing write-behind

Upgrade note:

- Maven coordinates remain under `com.reactor.cachedb`
- Use version `0.1.0-beta.3`
- If PostgreSQL can be changed outside CacheDB, configure outbox/CDC before
  relying on Redis hot-set freshness
- Use `ExternalChangeApplyRunner` in `CACHE_ONLY` mode for database-originated
  outbox/CDC events
- For relation-heavy/global sorted screens, use route contracts and
  projection-required strict mode

## Current Release Confidence State

- GitHub `Production Evidence` and `Public Beta Readiness` workflows were green
  on `main` at the last verification.
- GitHub release `v0.1.0-beta.3` exists with the public beta package asset.
- Maven Central publishing is not complete until repository secrets for Central
  credentials and GPG signing are configured.
- Staging Redis HA evidence is not complete until staging Redis/PostgreSQL
  secrets are configured and an operator-triggered failover window is executed.

## Provider SPI / MSSQL Direction

- Current public beta remains PostgreSQL-first.
- MSSQL must not be added by changing only the JDBC URL.
- The accepted direction is a provider SPI:
  - shared JDBC storage contracts
  - PostgreSQL dialect module
  - MSSQL dialect module
  - provider selection by explicit configuration
  - MSSQL-specific idempotency, retry, parameter-limit, and metadata tests
- See `docs/database-provider-spi.md` and
  `tr/docs/veritabani-provider-spi.md`.

## Validation Already Run

The following validations were run successfully during the latest work cycle:

```powershell
mvn.cmd -q -DskipTests -pl cachedb-starter,cachedb-storage-redis,cachedb-production-tests,cachedb-integration-tests -am test-compile
```

```powershell
mvn.cmd -q -pl cachedb-integration-tests -Dtest=CacheDatabaseIntegrationTest#routeTenantQuotaShouldRejectPayloadsOverMemoryBudget+routeTenantQuotaShouldTrackPayloadBytesWithoutDoubleCountingUpdates+routeTenantQuotaShouldEvictOldestTenantHotEntity test
```

```powershell
mvn.cmd -q -pl cachedb-integration-tests -Dtest=PostgresOutboxExternalChangeFeedAdapterTest test
```

```powershell
pwsh tools\ci\run-production-scenario-certification.ps1 -MavenExecutable mvn.cmd -RedisUri redis://default:welcome1@127.0.0.1:56379 -PostgresUrl jdbc:postgresql://127.0.0.1:5432/postgres
```

```powershell
pwsh tools\ci\run-production-evidence.ps1 -MavenExecutable mvn.cmd -RedisUri redis://default:welcome1@127.0.0.1:56379 -PostgresUrl jdbc:postgresql://127.0.0.1:5432/postgres
```

```powershell
pwsh tools\ci\check-benchmark-thresholds.ps1
pwsh tools\ci\check-public-api-compatibility.ps1
pwsh tools\ci\check-tr-docs.ps1
git diff --check
mvn.cmd -q -DskipTests package
pwsh tools\release\build-public-beta-package.ps1 -Version 0.1.0-beta.3 -SkipBuild
```

Known caveat:

- One broad Maven test command timed out at five minutes. Completed Surefire
  reports did not show failures, but it should not be counted as a full green
  gate. Prefer targeted tests plus CI evidence until a full local run is
  repeated with enough timeout.

## Local Test Environment Used Recently

- Redis container: `cachedb-it-redis`
- Redis URI: `redis://default:welcome1@127.0.0.1:56379`
- PostgreSQL container: `postgresql-test`
- PostgreSQL URL: `jdbc:postgresql://127.0.0.1:5432/postgres`
- PostgreSQL user: `postgres`
- PostgreSQL password: `postgresql`

These details are local development defaults only. Do not treat them as
production secrets.

## Current Product Classification

BEST:

- Public beta for controlled pilots.
- Production pilot only when route coverage, Redis HA, admin exposure, rollback,
  and side-by-side comparison are validated for that application.

ACCEPTABLE:

- Use CacheDB for selected hot routes where PostgreSQL remains durable and Redis
  hot windows are bounded.
- Use migration planner to evaluate a subset of routes before a wider rollout.

ANTI-PATTERN:

- Announce GA without signed Maven Central release, staging Redis HA evidence,
  route coverage, and benchmark regression gates.
- Treat CacheDB as a magical ORM replacement that automatically optimizes every
  existing dynamic query.
- Let projection-required routes fall back to full entity scans in production.
- Expose admin HTTP directly to the public internet.
- Depend on Redis freshness while PostgreSQL is mutated by external systems
  without outbox/CDC.

## GA Gates Still Open

The main remaining blockers for production GA are:

- verify remote GitHub Actions for `v0.1.0-beta.3`
- signed Maven Central publish pipeline
- real staging Redis HA/failover validation
- full migration route coverage report for every screen/API/batch/report
- outbox adapter apply runner
- stricter production fail-fast behavior for projection-required routes
- broader side-by-side comparison evidence across real schemas
- final admin exposure defaults verified behind gateway/auth

TLS note:

- Application-level TLS is not required if the service is behind a managed
  gateway or reverse proxy. The gateway must own TLS termination,
  authentication, and network exposure policy.

## Recommended Next Step

Do not start another feature before closing the release confidence loop.

Recommended order:

1. Check remote GitHub Actions for `main` and `v0.1.0-beta.3`.
2. Fix any CI failures until production evidence is green remotely.
3. Complete signed Maven Central publishing.
4. Run staging Redis HA/failover evidence against real infrastructure.
5. Add Migration Planner full coverage report enforcement for a real migration
   inventory.
6. Implement outbox-to-CacheDB apply runner with idempotent upsert/delete,
   retry, checkpoint, and dead-letter visibility.

## Files Future Sessions Should Inspect First

- `README.md`
- `tr/README.md`
- `CHANGELOG.md`
- `PRODUCTION_GA_CRITERIA.md`
- `docs/releases/v0.1.0-beta.3.md`
- `docs/production-recipes.md`
- `docs/use-case-examples.md`
- `docs/production-test-report.md`
- `.github/workflows/production-evidence.yml`
- `.github/workflows/production-ga-staging-evidence.yml`
- `tools/ci/run-production-evidence.ps1`
- `tools/ci/run-production-scenario-certification.ps1`
- `tools/ci/validate-migration-coverage-report.ps1`
- `cachedb-core/src/main/java/com/cachedb/core/cache`
- `cachedb-core/src/main/java/com/cachedb/core/route`
- `cachedb-core/src/main/java/com/cachedb/core/change`
- `cachedb-starter/src/main/java/com/cachedb/starter/PostgresOutboxExternalChangeFeedAdapter.java`
- `cachedb-production-tests/src/main/java/com/cachedb/prodtest/scenario`

## Communication Notes for Future Chats

- The user wants direct, production-grade analysis.
- Do not blindly agree; classify options as BEST, ACCEPTABLE, or ANTI-PATTERN.
- Analyze bottlenecks, single points of failure, memory behavior, Kubernetes
  scaling, and failure handling before coding.
- Prefer explicit mechanisms over hidden magic.
- For CPU-heavy or serialization-heavy paths, consider Rust/JNI only when the
  Java path is proven to be the bottleneck.
- For frontend/admin UI work, use AJAX/async behavior and avoid full page
  reloads.
- For Turkish docs, write natural Turkish, not literal English translation.
