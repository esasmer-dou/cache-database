# Production GA Release Runbook

This runbook is intentionally stricter than the public beta release flow.
CacheDB is GA only when every route, provider, staging topology, and signed
artifact gate is green.

## Non-Negotiable Rule

Do not publish or announce a production GA release when any of these are still
missing:

- full migration coverage for every production screen, API, batch, worker, and
  report route
- staging Redis HA evidence against the real Redis and source-database topology
- staging MSSQL HA evidence if MSSQL is part of the GA claim
- signed Maven Central publish with source and javadoc artifacts
- green public API compatibility and benchmark regression gates
- explicit admin exposure decision behind gateway auth or CacheDB token auth

## Required Repository Secrets

Configure these GitHub repository secrets before running the GA workflows:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
GPG_PRIVATE_KEY
GPG_PASSPHRASE
STAGING_REDIS_URI
STAGING_POSTGRES_URL
STAGING_POSTGRES_USER
STAGING_POSTGRES_PASSWORD
STAGING_MSSQL_URL
STAGING_MSSQL_USER
STAGING_MSSQL_PASSWORD
```

MSSQL secrets are mandatory if the production release claim includes MSSQL.
If a release is PostgreSQL-only, keep MSSQL out of the GA announcement and the
published support matrix.

## Step-by-Step GA Flow

1. Prepare a stable version such as `1.0.0`. Do not use `beta`, `alpha`,
   `rc`, `preview`, or `SNAPSHOT` in a GA version.
2. Fill `docs/ga-migration-coverage.csv` from
   [ga-migration-coverage-template.csv](ga-migration-coverage-template.csv).
   Every production route must have owner, query shape, CacheDB shape, warm
   status, comparison status, cutover status, and rollback plan.
3. Push the release commit to `main` and wait for `Public Beta Readiness` and
   `Production Evidence` to pass on that exact commit.
4. Run `Production GA Staging Evidence` from GitHub Actions with
   `docs/ga-migration-coverage.csv`. During the wait windows, trigger the
   managed Redis failover and the SQL Server HA or Always On failover if MSSQL
   is in scope.
5. Create and push the stable tag, for example `v1.0.0`.
6. Run `Maven Central Publish` manually on the stable tag with
   `gaRelease=true`. The workflow runs the GA preflight before deploying signed
   artifacts.
7. Run `Production GA Release Readiness` for the same tag and coverage CSV.
8. Publish the GitHub release only after the readiness summary is `PASS`.

## Local Preflight

After the tag exists, an operator can run:

```powershell
pwsh ./tools/ci/check-ga-release-readiness.ps1 `
  -Repository esasmer-dou/cache-database `
  -TargetRef main `
  -ReleaseTag v1.0.0 `
  -CoverageCsvPath docs/ga-migration-coverage.csv `
  -CheckGitHubSecrets
```

This command is expected to fail until repository secrets, staging evidence,
Maven Central publish, and full migration coverage are present.

To publish signed artifacts after every non-Maven GA gate is green:

```powershell
gh workflow run maven-central-publish.yml `
  --repo esasmer-dou/cache-database `
  --ref v1.0.0 `
  -f gaRelease=true `
  -f releaseTag=v1.0.0 `
  -f targetRef=main `
  -f migrationCoverageCsvPath=docs/ga-migration-coverage.csv
```

## Production Decision

BEST: release GA only after `Production GA Release Readiness` is green.

ACCEPTABLE: continue shipping public beta releases while selected production
pilots run with route-level coverage, rollback, and staging evidence.

ANTI-PATTERN: rename a beta build to GA because the unit tests, local Docker
tests, or public beta readiness workflow passed.
