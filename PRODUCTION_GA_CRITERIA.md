# Production GA Criteria

CacheDB can be public beta before every item here is green. For a framework
release, GA means the library can be consumed safely with documented boundaries,
green CI evidence, and a supported distribution channel. A consuming
application still needs its own route coverage, staging HA, rollback, and
cutover evidence before using CacheDB on production traffic.

## Go/No-Go Gates

| Gate | GA requirement | Evidence |
| --- | --- | --- |
| Redis HA and failover | Multi-pod coordination, consumer identity, leader lease, pending claim, and post-outage recovery must pass. | `Production Evidence / redis-failover-evidence` workflow artifact. |
| Local Docker HA preflight | Docker Desktop or CI must prove Redis outage/recovery, multi-instance coordination, SQL Server restart/reconnect, and MSSQL listener backend-switch behavior from clean containers. | `tools/ci/run-local-docker-ha-preflight.ps1` and `tools/ci/run-local-mssql-listener-failover-evidence.ps1` summaries or equivalent `Production Evidence` artifacts. |
| Managed staging Redis HA | Required only for a consuming application's production cutover or when the release claim explicitly certifies a managed Redis topology. | `Production GA Staging Evidence / staging-redis-ha` workflow artifact. |
| Admin HTTP exposure | Admin HTTP must be explicitly enabled and must be protected by gateway auth or CacheDB token auth. It must not be exposed directly to the public internet. | `cachedb.admin.http-enabled=true` is explicit; gateway route or `cachedb.admin.auth-enabled=true` is documented in deployment config. |
| TLS boundary | Application-level TLS is not required when the service is behind a managed gateway or reverse proxy. | Gateway/proxy owns TLS termination, request authentication, and network exposure policy. |
| Migration coverage tooling | The repo must ship a route coverage schema and validator. Full route coverage is required for each consuming application's cutover, not for the generic library release. | `tools/ci/validate-migration-coverage-report.ps1` and `docs/ga-migration-coverage-template.csv`. |
| Application migration coverage | Required when an application wants to cut production routes over to CacheDB. Every screen, API, batch, worker, and report route must have a shape, owner, warm status, compare result, rollback plan, and cutover state. | `Production GA Staging Evidence / migration-coverage` validates the supplied application CSV path. |
| Public API compatibility | Public API signatures must be compared against the committed baseline. | `tools/ci/check-public-api-compatibility.ps1` passes. |
| Official distribution | The release must have a documented official consumption path. Current selected channel: GitHub Release artifact. Maven Central remains optional and can be added later. | Release notes and distribution docs identify the supported channel. |
| Maven Central release | Required only when Maven Central is the selected official distribution channel. Artifacts must be source/javadoc attached and signed before publication. | `Maven Central Publish` workflow succeeds with Central and GPG secrets. |
| GA release readiness | Stable release tag, public evidence, production evidence, local HA tooling, and selected optional gates must be checked together. | `Production GA Release Readiness` workflow succeeds for the release tag. |
| Benchmark regressions | Benchmark JSON reports must be checked by a CI threshold gate, not only uploaded as artifacts. | `tools/ci/check-benchmark-thresholds.ps1` passes in `Production Evidence`. |
| Relation-heavy reads | Summary-first, preview/detail, and projection-first recipes must remain faster and lower-materialization than full aggregate first-paint. | Relation read-shape benchmark and migration side-by-side reports pass. |
| Global sorted/range reads | Ranked projection top-window path must remain cheaper than wide candidate scan. | Ranked projection benchmark passes. |
| Write durability | Write-behind retry, claim, DLQ, poison visibility, and selected SQL provider durability must be verified. | Integration tests and multi-instance coordination evidence pass. |
| MSSQL provider readiness | MSSQL can be documented as a supported provider only for the evidence level that has passed. Docker restart/reconnect, listener backend-switch, and high-volume replay are library evidence; SQL Server HA or Always On remains an application/topology cutover gate. | `Production Evidence / mssql-provider-evidence` workflow artifact; local listener-failover summary; add `Production GA Staging Evidence / staging-mssql-ha` for managed HA claims. |

## Classification

BEST: Keep CacheDB as `public beta` until framework-level gates are green on
the release candidate and the official GitHub Release artifact is documented,
built from the intended commit, and attached to the release. Add Maven Central
later when the goal is the lowest-friction public Java dependency.

ACCEPTABLE: Use CacheDB in controlled pilots when Redis HA, migration parity,
admin exposure, and rollback are verified for that application's routes.

ANTI-PATTERN: Announce GA while admin HTTP is implicitly exposed, benchmark
thresholds are advisory only, Docker outage/restart evidence is missing, or the
selected distribution channel is unclear.

## Required Production Defaults

- Spring Boot admin HTTP is not published unless `cachedb.admin.http-enabled=true`
  is set explicitly.
- If CacheDB token auth is used, set `cachedb.admin.auth-enabled=true` and pass
  the token with `Authorization: Bearer <token>` or the configured header.
- If gateway auth is used, terminate TLS and authentication at the gateway and
  keep `/cachedb-admin/**` reachable only from the intended operations network.
- Keep Redis HA outside the application process. CacheDB handles consumer,
  leader, retry, and claim behavior; it does not replace Redis high availability.
- Treat every relation-heavy or global sorted screen as projection/read-model
  work, not as a full aggregate first-paint read.
- Before framework GA, run local Docker or CI outage/restart evidence. Use
  `tools/ci/run-local-docker-ha-preflight.ps1` when Docker Desktop is available.
- Before a consuming application cuts over production traffic, run `Production
  GA Staging Evidence` with real staging secrets and trigger managed Redis
  failover during the wait window.
- Before a consuming application claims MSSQL HA readiness, run `Production GA
  Staging Evidence` with `STAGING_MSSQL_URL`, `STAGING_MSSQL_USER`, and
  `STAGING_MSSQL_PASSWORD`, then trigger a managed SQL Server HA or Always On
  failover during the MSSQL wait window.
- Before application cutover, commit or supply a full route coverage CSV using
  [docs/ga-migration-coverage-template.csv](docs/ga-migration-coverage-template.csv)
  as the schema and make the coverage workflow pass.
- Before GA, run
  [docs/production-ga-release-runbook.md](docs/production-ga-release-runbook.md)
  and do not publish or announce the release unless `Production GA Release
  Readiness` is green for the stable tag.
