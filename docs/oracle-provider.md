# Oracle Database Provider

Turkish version: [../tr/docs/oracle-provider.md](../tr/docs/oracle-provider.md)

CacheDB supports Oracle Database 19c and newer through the JDBC Thin driver.
The provider covers bounded source reads, Redis-first write-behind, warm and
reconciliation, outbox/checkpoint polling, migration discovery and comparison,
and Redis memory estimation. It is an explicit Oracle dialect, not PostgreSQL
SQL routed through an Oracle JDBC URL.

## Support Contract

| Area | Contract |
| --- | --- |
| Database | Oracle Database 19c or newer; CI uses Oracle Database Free 23 |
| Java | Java 17 or newer; the sample and CI use Java 21 |
| Driver | `com.oracle.database.jdbc:ojdbc17`, supplied by the Oracle starter |
| JDBC URL | Thin service-name URL such as `jdbc:oracle:thin:@//host:1521/service` |
| Identifier | Generated before Redis accepts the command |
| Version | Explicit numeric version column; stale writes are rejected |
| Empty text | `REJECT` by default or explicit `NORMALIZE_TO_NULL` |
| Read limits | Bounded row count; `IN` values are split into 900-expression groups |
| Runtime DDL | Disabled for outbox/checkpoint by default |

## Spring Boot Setup

Import the CacheDB BOM and add exactly one provider starter:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-spring-boot-starter-oracle</artifactId>
</dependency>
```

The starter includes the supported `ojdbc17` runtime driver. Keep one Oracle
JDBC version on the classpath. If the platform manages another approved driver
version, exclude the transitive driver and test that exact combination in the
application's certification lane.

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//db.example.internal:1521/ORDER_SERVICE
    username: cachedb_app
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 12
      minimum-idle: 2
      connection-timeout: 3000
      validation-timeout: 1500

cachedb:
  sql:
    provider: ORACLE
    oracle:
      query-timeout-seconds: 10
      transaction-isolation: READ_COMMITTED
      duplicate-race-retries: 2
      empty-string-policy: REJECT
```

`AUTO` also works when Oracle is the only provider starter. Explicit `ORACLE`
is preferable in production configuration because a second accidental provider
then fails during startup and `cachedb:doctor` can validate the build contract.

## Plain Java Setup

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-storage-oracle</artifactId>
</dependency>
```

```java
OracleWriteBehindOptions options = OracleWriteBehindOptions.builder()
        .queryTimeoutSeconds(10)
        .transactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
        .duplicateRaceRetries(2)
        .emptyStringPolicy(OracleWriteBehindOptions.EmptyStringPolicy.REJECT)
        .build();

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, oracleDataSource)
        .writeBehindFlusherFactory(OracleWriteBehindFlusher.factory(options))
        .register(registry -> {
            // Register generated entities, projections, and routes.
        })
        .start();
```

## Entity Contract

CacheDB accepts a command in Redis before durable Oracle completion. Therefore
the durable identifier must already be known. Use an application-generated
numeric/UUID identifier or a Redis-side ID strategy. Do not depend on an Oracle
sequence, identity column, or trigger to assign the identifier after acceptance.

Every mutable table needs a numeric version column. The provider uses that
column in version-guarded `MERGE` and delete statements. A retry with the same
or older version cannot overwrite a newer durable row.

```sql
CREATE TABLE orders (
    order_id NUMBER(19) PRIMARY KEY,
    customer_id NUMBER(19) NOT NULL,
    status VARCHAR2(24) NOT NULL,
    order_amount NUMBER(19, 4) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0 NOT NULL,
    deleted VARCHAR2(16)
);

CREATE INDEX idx_orders_customer_status
    ON orders(customer_id, status, order_id);
```

Foreign keys remain database integrity constraints. `@CacheRelation` describes
how CacheDB loads and assembles related models; it does not create or replace an
Oracle foreign key.

## Schema Bootstrap

CacheDB resolves a provider-specific schema dialect before validation or DDL.
Oracle identifiers are matched with Oracle metadata casing, and generated DDL
uses `NUMBER`, `VARCHAR2`, and Oracle timestamp types. Development
`CREATE_IF_MISSING` can create simple scalar entity tables. Production should
normally use migrations plus `VALIDATE_ONLY`.

Configured schema bootstrap is fail-fast. If a table or required column is
missing, the database product is unsupported, or Oracle rejects the DDL,
application startup stops with `SchemaBootstrapException`. Do not use generic
schema bootstrap to model partitions, advanced indexes, LOBs, virtual columns,
triggers, PL/SQL, or tablespace/storage clauses; keep those in reviewed Oracle
migrations.

## Supported Value Shapes

| Java value | Recommended Oracle column |
| --- | --- |
| `int` / `Integer` | `NUMBER(10)` |
| `long` / `Long` | `NUMBER(19)` |
| `BigInteger` | `NUMBER(38, 0)` |
| `boolean` / `Boolean` | `NUMBER(1)` |
| `BigDecimal` | `NUMBER(precision, scale)` |
| `double` / `Double` | `BINARY_DOUBLE` |
| `float` / `Float` | `BINARY_FLOAT` |
| `String` | bounded `VARCHAR2` |
| `Instant` / `OffsetDateTime` | `TIMESTAMP WITH TIME ZONE` |
| `LocalDateTime` | `TIMESTAMP` |
| `LocalDate` | `DATE` |

The generic ORM surface intentionally excludes PL/SQL procedure calls, Oracle
UDT/OBJECT, `ARRAY`, `STRUCT`, `XMLTYPE`, `SDO_GEOMETRY`, LOB streaming, and
vendor-specific bulk APIs. Keep those operations in an explicit JDBC adapter or
source command with bounded timeouts and measured allocation behavior.

## Empty String Rule

Oracle stores an empty character value as `NULL`. Silent conversion can break a
domain that distinguishes "missing" from "present but empty". The default
provider policy rejects empty Java strings before SQL execution:

```yaml
cachedb.sql.oracle.empty-string-policy: REJECT
```

Choose `NORMALIZE_TO_NULL` only when the database and API contract explicitly
treat those states as identical. The choice applies to durable writes; input
validation should still reject accidental empty values at the API boundary.

## Write and Read Behavior

The write-behind worker groups operations by SQL shape and executes prepared
statement batches inside bounded transactions. Upserts use a version-guarded
single-row `MERGE`; concurrent first inserts recover from `ORA-00001` by reading
the durable version and retrying only when safe. Deletes carry the same version
guard. Retryable availability, timeout, serialization, deadlock, and lock
errors are classified separately from constraint, data, schema, permission, and
stale-write failures.

Source reads use Oracle `OFFSET ... FETCH NEXT`, deterministic ID tie-breakers,
JDBC query timeout, fetch-size and max-row guards. Oracle limits one `IN` list
to 1,000 expressions, so CacheDB uses 900-value groups joined with `OR`. This
prevents `ORA-01795`; it does not make an unbounded query acceptable.

## Outbox and External Changes

If another application writes the same Oracle tables, publish those changes
through an outbox/CDC feed or accept a measured reconciliation delay. Periodic
warm alone is not an event delivery mechanism.

Provision the checkpoint table through a migration:

```sql
CREATE TABLE cachedb_outbox_adapter_checkpoint (
    adapter_name VARCHAR2(200) PRIMARY KEY,
    last_event_id NUMBER(19) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

```java
OracleOutboxExternalChangeFeedAdapter adapter =
        OracleOutboxExternalChangeFeedAdapter.builder(dataSource)
                .adapterName("orders-active-set")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(200)
                .createCheckpointTable(false)
                .build();

adapter.start(externalChangeApplyRunner);
```

Pollers sharing one `adapterName` serialize on the checkpoint row. That gives a
safe multi-pod ownership model, not active-active throughput. Use distinct,
non-overlapping partitions only after defining an explicit ownership contract.

## Pool and Batch Tuning

Calculate the SQL session budget before setting Hikari values:

```text
total application sessions = replicas * maximumPoolSize
required database budget    = application sessions
                            + migration/operations reserve
                            + failover reconnect headroom
```

Start with small write transactions, measure redo generation and row-lock time,
then increase `maxFlushBatchSize` only while p95 durability latency and database
load remain within budget. Bound warm and archive workers separately so they do
not consume all sessions needed by write-behind.

JDBC statement timeout does not replace network-level connect/read timeouts.
Set those in the Oracle JDBC URL/DataSource and pool according to the platform's
failover policy. Keep Kubernetes readiness sensitive to durable backlog and
connection recovery; liveness must not restart a healthy process merely because
Oracle is temporarily unavailable.

## Migration Planner

Oracle schema discovery is scoped to the connected user's current schema and
filters Oracle system schemas. The planner can discover tables/views and foreign
keys, generate route candidates, run bounded warm/dry-run, compare source and
CacheDB membership/order, and estimate Redis memory from Oracle statistics with
a bounded sampling fallback.

Statistics can be stale. Compare the estimate with actual Redis `MEMORY USAGE`
after staging warm before approving the memory budget.

## Evidence and HA Boundary

Run the single-instance provider lane against Docker:

```powershell
pwsh ./tools/ci/run-oracle-provider-evidence.ps1 -RestartOracleContainer
```

The lane covers live write/read correctness, stale versions, concurrent insert
races, 1,201-value query chunking, outbox/checkpoint polling, multi-pod
checkpoint ownership, migration warm/compare, delayed-network behavior,
throughput threshold, and container restart/reconnect.

### Local physical Data Guard lane

The repository also contains a destructive two-instance Oracle 19c Enterprise
physical Data Guard lane. It requires:

- Docker Desktop with at least 14 GiB memory, 6 CPUs and about 30 GiB free Docker disk space
- Java 21 and Maven
- a locally preloaded, licensed `oracle/database:19.3.0-ee` image
- the public `haproxy:2.9` image

The runner never downloads or accepts an Oracle license. Prepare the Oracle
image according to Oracle's terms, then run:

The default `19.3.0-ee` image proves the framework contract against the Oracle
19c baseline; it is not evidence for a current Release Update (RU). For release
certification, pass the organization's licensed, approved, RU-patched image via
`-OracleImage` and retain the reported image ID with the evidence.

```powershell
pwsh ./tools/ci/run-local-oracle-dataguard-evidence.ps1 `
  -MavenExecutable C:\apache-maven-3.9.9\bin\mvn.cmd
```

The runner creates a primary and physical standby, enables force logging,
standby redo logs, flashback and Data Guard Broker, verifies that Broker reports
no missing redo gap, and then executes all of these gates:

1. planned broker switchover
2. stale JDBC connection rejection and Hikari connection-pool recovery through
   a two-address Oracle JDBC descriptor that retains one service name
3. full Oracle provider evidence on the new primary
4. broker switchback
5. forced primary-container loss and immediate broker failover
6. a second Hikari recovery and full provider-evidence pass
7. reinstatement of the former primary, followed by `No Gap`, zero reported
   apply/transport lag, and switchover/failover readiness checks

Reports are written to
`target/cachedb-local-oracle-dataguard-reports/`. The separate `Oracle Data
Guard Evidence` workflow can run the same command manually on a Windows
self-hosted runner labelled `oracle-dataguard`.

`-UseExistingTopology` is intentionally restricted to containers and a network
labelled `com.reactor.cachedb.owner=oracle-dg-evidence`; the unplanned phase
kills the primary container and must never target an arbitrary database.

This is real physical Data Guard redo transport, apply and role transition. The
Hikari test uses an Oracle JDBC descriptor containing both listener addresses
and one stable service name. It therefore proves stale-connection rejection and
connect-time address failover without a proxy. The full provider suite uses
HAProxy separately after Broker confirms the new primary because delayed-network
tests need one controllable upstream. Neither mechanism is Oracle Clusterware
service relocation, FAN/ONS, FCF, or Application Continuity.

| Classification | Meaning |
| --- | --- |
| BEST | Repeat this evidence in the consuming application's actual staging RAC or Data Guard topology, with the production connect descriptor, pool, network path, timeouts and workload. |
| ACCEPTABLE | Use the local physical Data Guard lane as framework-level evidence before the real staging gate. |
| ANTI-PATTERN | Claim RAC, SCAN, FAN/ONS, Application Continuity or a production RTO/RPO from two single-instance Data Guard containers and a local connect descriptor. |

Oracle's RAC container setup requires a prepared Linux host, cluster networking,
storage and substantially more memory than this Docker Desktop lane. See the
[Oracle RAC container prerequisites](https://github.com/oracle/docker-images/blob/main/OracleDatabase/RAC/OracleRealApplicationClusters/docs/developers/README.md).
Fast Connection Failover also requires Oracle HA event delivery and an Oracle
pool such as UCP; Hikari reconnect evidence is not FCF. See
[Oracle UCP Fast Connection Failover](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjucp/fast-connection-failover.html).

An in-flight transaction on the old primary is expected to fail. Recovery means
the stale connection is rejected, a new connection reaches the new primary, and
the application retries an idempotent command. It does not mean JDBC silently
continues the old transaction.

The local lane uses Data Guard `MaxPerformance` with asynchronous redo transport.
Observed marker parity and a final `No Gap` state are correctness evidence for
that run; they are not a zero-RPO guarantee. A production RPO must be derived
from the deployed protection mode, transport policy, network and failure model.

## Production Checklist

- [ ] Exactly one Oracle provider starter and one approved JDBC driver version are present.
- [ ] Every mutable entity has an application-generated ID and numeric version column.
- [ ] Empty-string policy is explicit and covered by API/SQL tests.
- [ ] Route predicates and deterministic sort suffixes have matching indexes.
- [ ] Warm/source limits, query timeouts, Redis memory budgets, and coverage are measured.
- [ ] Total Hikari sessions across all pods fit the Oracle service budget.
- [ ] The physical Data Guard evidence lane passes for the exact release candidate.
- [ ] Failover evidence restores redundancy; the former primary is reinstated and Broker reports no gap before the run is accepted.
- [ ] Hikari reconnect or UCP/FCF is chosen explicitly; the two models are not mixed in claims.
- [ ] Outbox/checkpoint DDL is managed by migrations; external writers are covered.
- [ ] Production uses reviewed Oracle migrations and fail-fast schema validation.
- [ ] Actual staging proves service relocation, parity, delayed-network behavior, reconnect, canary, idempotent retry, and rollback.
- [ ] Vendor-specific PL/SQL/UDT/LOB operations remain explicit and separately tested.
