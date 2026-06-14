# Production GA Criteria

CacheDB can be public beta before every item here is green. It must not be
announced as production GA until every gate below is green in CI and in a
staging environment that resembles production traffic, Redis topology, and
durable SQL volume.

## Go/No-Go Gates

| Gate | GA requirement | Evidence |
| --- | --- | --- |
| Redis HA and failover | Multi-pod coordination, consumer identity, leader lease, pending claim, and post-outage recovery must pass. | `Production Evidence / redis-failover-evidence` workflow artifact. |
| Staging Redis HA | The same coordination path must pass against the real staging Redis/source-database topology while a managed Redis failover is triggered externally. | `Production GA Staging Evidence / staging-redis-ha` workflow artifact. |
| Admin HTTP exposure | Admin HTTP must be explicitly enabled and must be protected by gateway auth or CacheDB token auth. It must not be exposed directly to the public internet. | `cachedb.admin.http-enabled=true` is explicit; gateway route or `cachedb.admin.auth-enabled=true` is documented in deployment config. |
| TLS boundary | Application-level TLS is not required when the service is behind a managed gateway or reverse proxy. | Gateway/proxy owns TLS termination, request authentication, and network exposure policy. |
| Migration coverage | Every production screen, API, batch, worker, and report route must have an explicit CacheDB shape, warm decision, comparison result, owner, cutover state, and rollback plan. | `Production GA Staging Evidence / migration-coverage` validates `docs/ga-migration-coverage.csv` or the supplied CSV path. |
| Public API compatibility | Public API signatures must be compared against the committed baseline. | `tools/ci/check-public-api-compatibility.ps1` passes. |
| Maven Central release | Release artifacts must be source/javadoc attached and signed before publication. | `Maven Central Publish` workflow succeeds with Central and GPG secrets. |
| Benchmark regressions | Benchmark JSON reports must be checked by a CI threshold gate, not only uploaded as artifacts. | `tools/ci/check-benchmark-thresholds.ps1` passes in `Production Evidence`. |
| Relation-heavy reads | Summary-first, preview/detail, and projection-first recipes must remain faster and lower-materialization than full aggregate first-paint. | Relation read-shape benchmark and migration side-by-side reports pass. |
| Global sorted/range reads | Ranked projection top-window path must remain cheaper than wide candidate scan. | Ranked projection benchmark passes. |
| Write durability | Write-behind retry, claim, DLQ, poison visibility, and selected SQL provider durability must be verified. | Integration tests and multi-instance coordination evidence pass. |
| MSSQL provider readiness | MSSQL must remain explicit beta until live provider evidence, checkpoint locking, high-volume replay/load, migration SQL, multi-pod apply smoke, and longer SQL Server soak/failover tests are green. | `Production Evidence / mssql-provider-evidence` workflow artifact plus `Production GA Staging Evidence / staging-mssql-ha` artifact. |

## Classification

BEST: Keep CacheDB as `public beta` until all gates are green on every release
candidate and the release workflow has published a signed artifact to Maven
Central.

ACCEPTABLE: Use CacheDB in controlled pilots when Redis HA, migration parity,
admin exposure, and rollback are verified for that application's routes.

ANTI-PATTERN: Announce GA while admin HTTP is implicitly exposed, route coverage
is partial, benchmark thresholds are advisory only, or release artifacts are not
signed and reproducible.

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
- Before GA, run `Production GA Staging Evidence` with real staging secrets and
  trigger managed Redis failover during the wait window.
- Before MSSQL GA, run `Production GA Staging Evidence` with
  `STAGING_MSSQL_URL`, `STAGING_MSSQL_USER`, and `STAGING_MSSQL_PASSWORD`, then
  trigger a managed SQL Server HA or Always On failover during the MSSQL wait
  window.
- Before GA, commit a full route coverage CSV using
  [docs/ga-migration-coverage-template.csv](docs/ga-migration-coverage-template.csv)
  as the schema and make the coverage workflow pass.
