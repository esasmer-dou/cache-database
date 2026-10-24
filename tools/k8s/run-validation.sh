#!/bin/sh
set -eu

# Run inside the dedicated workspace pod, never on the developer workstation.
test -d /workspace/builds || { echo 'Kubernetes validation workspace required' >&2; exit 2; }
mkdir -p target
# Two Maven processes must never recompile or replace the same test/class output.
exec 9>target/kubernetes-validation.lock
flock -n 9 || { echo 'Validation already running in this checkout; use a separate source directory' >&2; exit 2; }
date -u '+%Y-%m-%dT%H:%M:%SZ' > target/kubernetes-validation-start.txt
namespace=${CACHEDB_TEST_NAMESPACE:-cachedb-v015-validation}
case "$namespace" in cachedb-*) ;; *) echo 'Dedicated namespace required' >&2; exit 2;; esac
export CACHEDB_TEST_NAMESPACE="$namespace"
password=$(tr -d '\r\n' < /workspace/fixture-password)
export CACHEDB_SNAPSHOT_POSTGRES_URL="jdbc:postgresql://postgres.$namespace.svc.cluster.local:5432/cachedb"
export CACHEDB_SNAPSHOT_POSTGRES_USER=cachedb CACHEDB_SNAPSHOT_POSTGRES_PASSWORD="$password"
export CACHEDB_SNAPSHOT_MSSQL_URL="jdbc:sqlserver://mssql.$namespace.svc.cluster.local:1433;databaseName=cachedb;encrypt=false;trustServerCertificate=true"
export CACHEDB_SNAPSHOT_MSSQL_USER=sa CACHEDB_SNAPSHOT_MSSQL_PASSWORD="$password"
export CACHEDB_SNAPSHOT_ORACLE_URL="jdbc:oracle:thin:@//oracle.$namespace.svc.cluster.local:1521/FREEPDB1"
export CACHEDB_SNAPSHOT_ORACLE_USER=cachedb CACHEDB_SNAPSHOT_ORACLE_PASSWORD="$password"
redis="redis.$namespace.svc.cluster.local"
export CACHEDB_TEST_REDIS_URI="redis://$redis:6379/7"
export CACHEDB_TEST_SPLIT_REDIS_URI="redis://$redis:6379/8"
export CACHEDB_IT_POSTGRES_URL="$CACHEDB_SNAPSHOT_POSTGRES_URL" CACHEDB_IT_POSTGRES_USER=cachedb CACHEDB_IT_POSTGRES_PASSWORD="$password"
export CACHEDB_IT_MSSQL_URL="$CACHEDB_SNAPSHOT_MSSQL_URL" CACHEDB_IT_MSSQL_USER=sa CACHEDB_IT_MSSQL_PASSWORD="$password"
export CACHEDB_IT_ORACLE_URL="$CACHEDB_SNAPSHOT_ORACLE_URL" CACHEDB_IT_ORACLE_USER=cachedb CACHEDB_IT_ORACLE_PASSWORD="$password"
export CACHEDB_IT_REDIS_URI="redis://$redis:6379/10"

case "${1:-unit}" in
  all) mvn -B -ntp -Poss-release -Dgpg.skip=true install \
    -Dcachedb.snapshot.redis=true -Dcachedb.snapshot.providers=true \
    -Dcachedb.snapshot.redis.host="$redis" -Dcachedb.snapshot.redis.port=6379 \
    -Dcachedb.test.redis.uri="redis://$redis:6379/11" \
    -Dcachedb.redis.cluster=true -Dcachedb.redis.cluster.host="redis-cluster-0.redis-cluster.$namespace.svc.cluster.local" \
    -Dcachedb.redis.cluster.port=6379 -Dcachedb.it.mssql.required=true -Dcachedb.it.oracle.required=true ;;
  unit) mvn -B -ntp install -Dcachedb.snapshot.redis=true -Dcachedb.snapshot.providers=true \
    -Dcachedb.snapshot.redis.host="$redis" -Dcachedb.snapshot.redis.port=6379 \
    -Dcachedb.test.redis.uri="redis://$redis:6379/11" ;;
  providers) mvn -B -ntp -pl cachedb-spring-boot-starter -am test \
    -Dcachedb.snapshot.providers=true -Dcachedb.snapshot.redis=true \
    -Dcachedb.snapshot.redis.host="$redis" -Dcachedb.snapshot.redis.port=6379 \
    '-Dtest=WriteFenceProvidersIntegrationTest,SnapshotProvidersIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false ;;
  provider-legacy) mvn -B -ntp -pl cachedb-starter,cachedb-storage-mssql -am test \
    -Dcachedb.it.mssql.required=true -Dcachedb.it.oracle.required=true \
    '-Dtest=MssqlWriteBehindFlusherSqlServerTest,MssqlWriteBehindFlusherLoadSqlServerTest,MssqlOutboxExternalChangeFeedAdapterSqlServerTest,MssqlMigrationPlannerSqlServerTest,OracleProviderIntegrationTest,OracleHighLatencyIntegrationTest,OracleMigrationPlannerTest' \
    -Dsurefire.failIfNoSpecifiedTests=false ;;
  release-smoke) mvn -B -ntp -pl cachedb-spring-boot-starter,cachedb-production-tests -am test \
    -Dcachedb.snapshot.redis=true -Dcachedb.snapshot.redis.host="$redis" -Dcachedb.snapshot.redis.port=6379 \
    -Dcachedb.redis.cluster=true -Dcachedb.redis.cluster.host="redis-cluster-0.redis-cluster.$namespace.svc.cluster.local" \
    -Dcachedb.redis.cluster.port=6379 -Dcachedb.it.oracle.required=true \
    '-Dtest=CacheDistributedJobExecutorRedisTest,CacheDistributedJobExecutorTest,OracleProviderIntegrationTest,InMemorySnapshotRepositoryTest,SnapshotScoreRangeTest,SnapshotDirtyRootsRedisTest,LengthPrefixedPayloadCodecTest,MultiInstanceCoordinationSmokeTest,CacheRepositoryProcessorTest,JacksonFreeSnapshotProcessTest' \
    -Dsurefire.failIfNoSpecifiedTests=false ;;
  redis) mvn -B -ntp -pl cachedb-spring-boot-starter -am test \
    -Dcachedb.snapshot.redis=true -Dcachedb.snapshot.redis.host="$redis" -Dcachedb.snapshot.redis.port=6379 \
    -Dcachedb.test.redis.uri="redis://$redis:6379/11" ;;
  cluster) mvn -B -ntp -pl cachedb-spring-boot-starter -am test \
    -Dcachedb.snapshot.redis=true -Dcachedb.snapshot.redis.host="$redis" -Dcachedb.snapshot.redis.port=6379 \
    -Dcachedb.test.redis.uri="redis://$redis:6379/11" \
    -Dcachedb.redis.cluster=true -Dcachedb.redis.cluster.host="redis-cluster-0.redis-cluster.$namespace.svc.cluster.local" \
    -Dcachedb.redis.cluster.port=6379 ;;
  sample-*)
    provider=${1#sample-}
    case "$provider" in
      postgresql) export CACHEDB_TEST_DATASOURCE_URL="jdbc:postgresql://postgres.$namespace.svc.cluster.local:5432/cachedb_sample" CACHEDB_TEST_DATASOURCE_USERNAME=cachedb ;;
      mssql) export CACHEDB_TEST_DATASOURCE_URL="jdbc:sqlserver://mssql.$namespace.svc.cluster.local:1433;databaseName=cachedb_sample;encrypt=false;trustServerCertificate=true" CACHEDB_TEST_DATASOURCE_USERNAME=sa ;;
      oracle) export CACHEDB_TEST_DATASOURCE_URL="$CACHEDB_SNAPSHOT_ORACLE_URL" CACHEDB_TEST_DATASOURCE_USERNAME=cachedb ;;
      *) echo 'Unsupported sample provider' >&2; exit 2 ;;
    esac
    export CACHEDB_TEST_DATASOURCE_PASSWORD="$password"
    mvn -B -ntp -f "sample-cache-database-$provider/pom.xml" -Pprovider-integration verify ;;
  *) echo 'Expected all, unit, redis, cluster, providers, provider-legacy, release-smoke, or sample-{postgresql,mssql,oracle}' >&2; exit 2 ;;
esac
