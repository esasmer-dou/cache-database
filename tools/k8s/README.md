# Isolated Kubernetes Validation

This recipe builds and tests inside a disposable namespace. It does not require
Docker-in-Docker, privileged pods, host paths, shared databases or a developer JDK.
Source synchronization is not a local Maven build.

## Safety Contract

- Choose an explicit kube context on every command; never change its global default.
- The example namespace is `cachedb-v017-validation`. All services are ClusterIP.
- Only fixture data belongs here. SQL storage is `emptyDir`, not production persistence.
- Namespace separation alone is not network isolation. A CNI must enforce NetworkPolicy
  before a policy can provide that guarantee; do not assume Flannel enforces it.
- Pod/service limits and quota are bounded. Do not change shared storage, CNI,
  ingress, databases or cluster services to run this validation.
- Do not mount an operator kubeconfig, Docker socket or service-account token into test pods.
- Generate a new fixture password in an operator-side secret named `fixture-auth`,
  key `password`. Never commit its value, pass it as a Maven `-D` argument or include
  it in evidence. Transfer it only to `/workspace/fixture-password` with mode 0600.

## Run Order

1. Inspect the target cluster and available capacity. Apply `validation/workspace.yml`
   and `validation/providers.yml` only to the dedicated namespace.
2. Wait for all four private provider services and the non-root Maven pod to be ready.
   Initialize disposable `cachedb` and `cachedb_sample` SQL Server databases with
   `ALLOW_SNAPSHOT_ISOLATION ON`; create `cachedb_sample` on PostgreSQL. Do not apply
   these setup commands to a managed/shared database. Oracle uses its fixture user.
3. Synchronize a unique source directory with `sync-workspace.ps1`. Deleted source
   files are omitted; reusing an old directory can retain stale files, so do not reuse it.
4. From that directory INSIDE the workspace pod, run:

```sh
sh tools/k8s/run-validation.sh unit
sh tools/k8s/run-validation.sh columns
sh tools/k8s/run-validation.sh providers
sh tools/k8s/run-validation.sh provider-legacy
sh tools/k8s/run-validation.sh redis
sh tools/k8s/run-validation.sh cluster
sh tools/k8s/run-validation.sh sample-postgresql
sh tools/k8s/run-validation.sh sample-mssql
sh tools/k8s/run-validation.sh sample-oracle
```

The columns lane checks bounded JDBC capability resolution, schema/DDL rejection,
4096-field generation and serialization, and full-width insert/update/delete on
all three disposable SQL providers. It requires the provider services and SQL
Server database initialization above; it is not a mock-only capability check.
Oracle extended mode is proven only if the actual driver/server reports and
accepts that limit, not by the resolver's 4096 stub test.

Sample lanes use separate standalone Redis logical databases: PostgreSQL 1/2,
SQL Server 3/4 and Oracle 5/6 (main/split-role). Never reuse another provider's
cache, job receipts or version counters. These integration contracts require
fresh disposable SQL and Redis data. For a fresh rerun, recreate the owned
provider fixtures together; do not clear only Redis while retaining SQL fences.
The runner never flushes a shared Redis or truncates a managed database.

The unit lane enables the standalone Redis and all three SQL provider contracts;
the Cluster lane additionally requires the bootstrapped six-node fixture.
After bootstrapping every fixture, `sh tools/k8s/run-validation.sh all` combines
these contracts with legacy provider tests and source/Javadoc packaging in one
reactor run. Listener/service-name failover tests still require their explicit
coordination setup; they must not be described as executed when unconfigured.
The runner reads fixture credentials from the private file and exports environment
variables, keeping passwords out of Surefire system properties. It rejects concurrent
Maven runs in the same checkout. Save evidence between lanes because Maven replaces reports.

```sh
DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 /workspace/tools/pwsh/pwsh \
  -NoProfile -File tools/k8s/collect-test-evidence.ps1 -Lane unit
```

The optional evidence collector requires an operator-provided PowerShell runtime.
It removes JVM property dumps and redacts the disposable fixture password; it never
changes test outcomes. Retain source/artifact hashes and image IDs with the results.

## Cluster and Application Checks

`validation/redis-cluster.yml` creates six bounded, ephemeral Redis nodes. Bootstrap
their slots with the Redis CLI only after all six are ready. Use the in-cluster seed
name and port, not a Docker port mapper:

```sh
mvn -B -ntp -pl cachedb-spring-boot-starter -am test \
  -Dcachedb.snapshot.redis=true \
  -Dcachedb.snapshot.redis.host=redis.cachedb-v017-validation.svc.cluster.local \
  -Dcachedb.snapshot.redis.port=6379 \
  -Dcachedb.redis.cluster=true \
  -Dcachedb.redis.cluster.host=redis-cluster-0.redis-cluster.cachedb-v017-validation.svc.cluster.local \
  -Dcachedb.redis.cluster.port=6379
```

Use the targeted Cluster contracts for a cluster lane; older standalone-only fixtures
must still point at the standalone fixture. Cluster snapshots, same-slot atomic calls,
cross-slot reads, job admission and dirty-root reservations have separate assertions.

For multi-pod API testing, build sample JARs in the workspace. `ArtifactServer.java`
serves only a curated artifact directory, never the workspace root or credential file.
Use private services and checksum-verified downloads. Test API/worker handover and
contract collections against those pods, not a workstation process.

`validation/sample-runtime.yml` runs one `CLIENT_ONLY` API and two `WORKER_ONLY`
pods. Supply a `sample-runtime` ConfigMap containing `ARTIFACT_URL`,
`ARTIFACT_SHA256`, `DB_URL`, `DB_USERNAME`, `REDIS_URI`, `SERVER_ADDRESS=0.0.0.0`,
`SERVER_PORT=8091`, `SAMPLE_WRITE_TOOLS_ENABLED=true`,
`SAMPLE_SCHEDULED_WARM_ENABLED=false`, `SPRING_SQL_INIT_MODE=never` and
`JAVA_TOOL_OPTIONS=-Xmx384m -XX:ActiveProcessorCount=2`. The fixture password is
injected separately from the namespace's Secret. Initialize a dedicated database
with that provider sample's `schema.sql` before starting the pods. Never point
these write-enabled validation pods at business databases.

Run the provider's saved catalog collection against the private API service.
`run-read-load.mjs` can then run a bounded HTTP smoke load after the collection
has published product `9100001`. It accepts only a dedicated namespace service,
limits concurrency to 64, applies a five-second request timeout and checks the
response content, not just the HTTP status. This HTTP check is separate from
the packaged framework's strict 50 ms codec gate.

These checks demonstrate framework/provider behavior on disposable fixtures. They
are not RAC/Data Guard, Always On, WAN/VPN or application-specific cutover certification.
After exporting evidence, remove only the resources belonging to this test namespace.
