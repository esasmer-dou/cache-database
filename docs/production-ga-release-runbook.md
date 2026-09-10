# Production GA Release Runbook

This runbook is the mandatory stable release flow.
CacheDB framework GA means the library release is safe to consume with clear
boundaries, green CI evidence, and a documented distribution channel. It does
not mean every consuming application's production topology has already been
certified.

## Non-Negotiable Rule

Do not publish or announce a framework GA release when any of these are still
missing:

- local Docker or CI outage/restart evidence for Redis coordination and SQL
  provider reconnect behavior
- self-hosted physical Data Guard evidence when Oracle support is part of the release
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
STAGING_ORACLE_URL
STAGING_ORACLE_USER
STAGING_ORACLE_PASSWORD
```

Provider staging secrets are mandatory only when the release claim includes the
corresponding managed HA topology. The local physical Data Guard lane may be
reported as representative framework evidence; it is not PostgreSQL HA, SQL
Server Always On, Oracle RAC, or customer-topology certification.

## Step-by-Step GA Flow

1. Prepare a stable version such as `0.1.0` or `1.0.0`. Do not use `beta`, `alpha`,
   `rc`, `preview`, or `SNAPSHOT` in a GA version.
2. Run the local Docker HA preflight:

   ```powershell
   pwsh ./tools/ci/run-local-docker-ha-preflight.ps1
   ```

   This starts Redis, PostgreSQL, and SQL Server containers, runs Redis
   outage/recovery evidence, and runs SQL Server restart/reconnect evidence.
3. Run the Oracle provider evidence lane with a container restart:

   ```powershell
   pwsh ./tools/ci/run-oracle-provider-evidence.ps1 -RestartOracleContainer
   ```

   This verifies the Oracle JDBC Thin path, version-guarded writes, outbox,
   migration discovery, bounded reads, delayed-network behavior, throughput,
   and reconnect after a single-instance restart. It does not claim RAC or Data
   Guard certification.
4. Run the physical Data Guard lane on a Windows self-hosted runner with the
   licensed Oracle 19c Enterprise image already present:

   ```powershell
   pwsh ./tools/ci/run-local-oracle-dataguard-evidence.ps1 `
     -MavenExecutable C:\apache-maven-3.9.9\bin\mvn.cmd
   ```

   This proves real redo transport/apply, planned broker switchover, forced
   primary loss, stale-connection rejection, Hikari recovery through a
   multi-address Oracle JDBC service-name descriptor, full provider behavior
   after both transitions, and reinstatement of the former primary with final
   no-gap/zero-lag readiness. It does not prove RAC/SCAN/FAN/FCF, zero-RPO, or a
   customer's production network and service policy.
5. If the release includes MSSQL listener/failover claims but the shared staging
   Always On environment cannot be failed over on demand, run the local listener
   preflight:

   ```powershell
   pwsh ./tools/ci/run-local-mssql-listener-failover-evidence.ps1
   ```

   This proves stale JDBC connection invalidation and new-connection recovery
   through a stable listener endpoint. It does not replace a real Always On
   topology test for replication, quorum, or managed failover policy.
6. Push the release commit to `main` and wait for `Framework Readiness` and
   `Production Evidence` to pass on that exact commit.
7. Build the official GitHub Release artifact from the intended commit:

   ```powershell
   pwsh ./tools/release/build-release-package.ps1 `
     -Version 0.1.0 `
     -PackageLabel github-release
   ```

   For stable releases, use a non-beta package label such as `github-release`.
8. Create and push the stable tag, for example `v0.1.0`.
9. If Maven Central is the selected distribution channel, run `Maven Central
   Publish` manually on the stable tag with
   `gaRelease=true`. The workflow runs the GA preflight before deploying signed
   artifacts.
10. Run `Production GA Release Readiness` for the same tag. Enable
   `requireManagedStagingHa`, `requireApplicationMigrationCoverage`, or
   `requireMavenCentralPublish` only when that release claim includes those
   optional gates.
11. Publish the GitHub release only after the readiness summary is `PASS` and
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
tests, or the framework readiness workflow passed.
