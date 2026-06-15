# Production GA Release Runbook

This runbook is intentionally stricter than the public beta release flow.
CacheDB framework GA means the library release is safe to consume with clear
boundaries, green CI evidence, and a documented distribution channel. It does
not mean every consuming application's production topology has already been
certified.

## Non-Negotiable Rule

Do not publish or announce a framework GA release when any of these are still
missing:

- local Docker or CI outage/restart evidence for Redis coordination and SQL
  provider reconnect behavior
- a documented official distribution path. Current selected path: GitHub
  Release artifact
- signed Maven Central publish with source and javadoc artifacts when Maven
  Central is the selected distribution channel
- green public API compatibility and benchmark regression gates
- explicit admin exposure decision behind gateway auth or CacheDB token auth

For a consuming application, also require full route coverage and real staging
HA evidence before cutting production traffic over to CacheDB.

## Required Repository Secrets

Configure these GitHub repository secrets only for the optional gates you use.
For Maven Central:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
GPG_PRIVATE_KEY
GPG_PASSPHRASE
```

For managed staging HA evidence:

```text
STAGING_REDIS_URI
STAGING_POSTGRES_URL
STAGING_POSTGRES_USER
STAGING_POSTGRES_PASSWORD
STAGING_MSSQL_URL
STAGING_MSSQL_USER
STAGING_MSSQL_PASSWORD
```

MSSQL staging secrets are mandatory only if the release claim includes managed
SQL Server HA or Always On evidence. If not, describe MSSQL support as Docker
restart/reconnect and provider evidence, not as topology certification.

## Step-by-Step GA Flow

1. Prepare a stable version such as `0.1.0` or `1.0.0`. Do not use `beta`, `alpha`,
   `rc`, `preview`, or `SNAPSHOT` in a GA version.
2. Run the local Docker HA preflight:

   ```powershell
   pwsh ./tools/ci/run-local-docker-ha-preflight.ps1
   ```

   This starts Redis, PostgreSQL, and SQL Server containers, runs Redis
   outage/recovery evidence, and runs SQL Server restart/reconnect evidence.
3. If the release includes MSSQL listener/failover claims but the shared staging
   Always On environment cannot be failed over on demand, run the local listener
   preflight:

   ```powershell
   pwsh ./tools/ci/run-local-mssql-listener-failover-evidence.ps1
   ```

   This proves stale JDBC connection invalidation and new-connection recovery
   through a stable listener endpoint. It does not replace a real Always On
   topology test for replication, quorum, or managed failover policy.
4. Push the release commit to `main` and wait for `Public Beta Readiness` and
   `Production Evidence` to pass on that exact commit.
5. Build the official GitHub Release artifact from the intended commit:

   ```powershell
   pwsh ./tools/release/build-release-package.ps1 `
     -Version 0.1.0 `
     -PackageLabel github-release
   ```

   For stable releases, use a non-beta package label such as `github-release`.
6. Create and push the stable tag, for example `v0.1.0`.
7. If Maven Central is the selected distribution channel, run `Maven Central
   Publish` manually on the stable tag with
   `gaRelease=true`. The workflow runs the GA preflight before deploying signed
   artifacts.
8. Run `Production GA Release Readiness` for the same tag. Enable
   `requireManagedStagingHa`, `requireApplicationMigrationCoverage`, or
   `requireMavenCentralPublish` only when that release claim includes those
   optional gates.
9. Publish the GitHub release only after the readiness summary is `PASS` and
   attach the official release artifact.

## Local Preflight

After the tag exists, an operator can run:

```powershell
pwsh ./tools/ci/check-ga-release-readiness.ps1 `
  -Repository esasmer-dou/cache-database `
  -TargetRef main `
  -ReleaseTag v0.1.0
```

This command checks framework-level GA readiness. Add the optional flags below
only when the release claim requires them:

```powershell
-RequireMavenCentralPublish
-RequireManagedStagingHa
-RequireApplicationMigrationCoverage -CoverageCsvPath docs/ga-migration-coverage.csv
```

To publish signed artifacts after every non-Maven GA gate is green and Maven
Central is the selected distribution channel:

```powershell
gh workflow run maven-central-publish.yml `
  --repo esasmer-dou/cache-database `
  --ref v0.1.0 `
  -f gaRelease=true `
  -f releaseTag=v0.1.0 `
  -f targetRef=main `
  -f migrationCoverageCsvPath=docs/ga-migration-coverage.csv
```

## Production Decision

BEST: release GA only after `Production GA Release Readiness` is green and the
GitHub Release artifact is attached as the official distribution package.

ACCEPTABLE: release framework GA with Docker/CI outage evidence and clear
boundaries, while individual applications still run route-level coverage,
rollback, and staging HA evidence before cutover.

ANTI-PATTERN: rename a beta build to GA because the unit tests, local Docker
tests, or public beta readiness workflow passed.
