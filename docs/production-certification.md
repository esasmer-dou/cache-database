# Production Certification

Turkish version: [../tr/docs/production-sertifikasi.md](../tr/docs/production-sertifikasi.md)

Use this gate in every application that sends production traffic to CacheDB.
It turns route coverage, parity, memory, failover, canary, and rollback from a
checklist into a failing Maven build contract.

## Sample Validation Tooling Integrity

The sample API checks use Node.js 24.22.1 for Linux x64 and Newman 6.2.1.
These are validation tools, not CacheDB Java runtime dependencies. Their
[machine-readable pins](validation-tooling-pins.json) identify exact archive
bytes, not merely version labels:

- Node archive: `node-v24.22.1-linux-x64.tar.gz`, SHA-256 `eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c`.
- Newman archive: `newman-6.2.1.tgz`, integrity `sha512-Zq8Sr5GFF+OXs5yIbyglLMKMh1WNMjYVV0yZaSBZ+DIgQOIWcxT8QTfbrl/YUGrLyT4rjpu+yZ/Z+kozw79GEA==`.

Use a disposable tooling directory. Verify the downloaded bytes before
extracting or executing them; do not disable TLS or checksum verification.
The upstream references are the [Node checksum manifest](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt)
and [versioned Newman package metadata](https://registry.npmjs.org/newman/6.2.1).

```bash
set -eu
curl --fail --location --proto '=https' --tlsv1.2 -o node-v24.22.1-linux-x64.tar.gz https://nodejs.org/dist/v24.22.1/node-v24.22.1-linux-x64.tar.gz
printf '%s  %s\n' eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c node-v24.22.1-linux-x64.tar.gz | sha256sum --check -
tar -xzf node-v24.22.1-linux-x64.tar.gz
curl --fail --location --proto '=https' --tlsv1.2 -o newman-6.2.1.tgz https://registry.npmjs.org/newman/-/newman-6.2.1.tgz
./node-v24.22.1-linux-x64/bin/node -e 'const fs=require("node:fs"),crypto=require("node:crypto");const expected="sha512-Zq8Sr5GFF+OXs5yIbyglLMKMh1WNMjYVV0yZaSBZ+DIgQOIWcxT8QTfbrl/YUGrLyT4rjpu+yZ/Z+kozw79GEA==";const actual="sha512-"+crypto.createHash("sha512").update(fs.readFileSync("newman-6.2.1.tgz")).digest("base64");if(actual!==expected)throw new Error("Newman integrity mismatch");console.log("Newman archive integrity verified");'
```

A Newman package pin alone does not freeze its transitive dependencies. Keep
the installation's `package-lock.json`, record its SHA-256 with the evidence,
and reuse it with `npm ci --ignore-scripts --no-audit --no-fund` rather than
silently resolving a different dependency graph. Integrity verification is not
a dependency security assessment or proof that API tests passed. Provider
tests, actual HTTP assertions, zero-skip checks and the complete release
coverage gate remain separate requirements.

## 1. Add The Maven Gate

The CacheDB BOM already defines the plugin version. Add this profile to the
application POM:

```xml
<profiles>
    <profile>
        <id>production-certification</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>com.reactor.cachedb</groupId>
                    <artifactId>cachedb-maven-plugin</artifactId>
                    <version>${cachedb.version}</version>
                    <executions>
                        <execution>
                            <id>certify-cache-database</id>
                            <phase>verify</phase>
                            <goals>
                                <goal>certify</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

Run the complete gate:

```bash
mvn verify -Pproduction-certification
```

The report is written to
`target/cachedb-production-certification.md`. A missing or inconsistent item
fails the Maven build.

## 2. Create The Evidence Directory

```text
cachedb-certification/
├── certification.properties
├── route-coverage.csv
└── evidence/
    ├── redis-failover.md
    ├── sql-failover.md
    ├── rollback.md
    ├── canary.md
    ├── customer-orders-warm.md
    ├── customer-orders-parity.md
    └── customer-orders-memory.md
```

Evidence files must stay inside this directory and use the following required
header. The Maven gate rejects a missing field, a failed status, another
environment, or another application commit.

```text
status: passed
commit: 0123456789abcdef
environment: staging
owner: orders-team
generated-at: 2026-08-13T12:00:00Z
summary: Redis failover completed and the route recovered within the measured SLO.
```

Append the run URL, command, metrics, and observations after the header. Do not
add passwords, tokens, or JDBC URLs containing credentials.

## 3. Add The Manifest

```properties
application=orders-api
environment=staging
application.commit=0123456789abcdef
framework.version=0.11.0
inventory.complete=true
inventory.routeCount=1
redis.failover=passed
sql.failover=passed
rollback.drill=passed
canary.ready=passed
redis.failoverEvidence=evidence/redis-failover.md
sql.failoverEvidence=evidence/sql-failover.md
rollback.drillEvidence=evidence/rollback.md
canary.evidence=evidence/canary.md
```

`application.commit` binds every evidence file to the exact build under test.
`framework.version` must be a stable semantic version. `inventory.complete=true`
is an application-team assertion that screens, APIs, batches, workers, and
reports were inventoried. `inventory.routeCount` must equal the number of
unique rows in `route-coverage.csv`.

## 4. Add Every Production Route

Start from [the coverage template](ga-migration-coverage-template.csv). One row
represents one independently cut over route.

```csv
RouteName,RouteKind,Owner,QueryShape,CacheDbShape,WarmStatus,WarmEvidence,CompareStatus,CompareEvidence,MemoryStatus,MemoryEvidence,CutoverStatus,RollbackPlan,RollbackEvidence,Blocker
customer-order-timeline,api,orders-team,"customer filter; date desc",projection,passed,evidence/customer-orders-warm.md,matched,evidence/customer-orders-parity.md,within budget,evidence/customer-orders-memory.md,ready,"disable the CacheDB route flag and return to bounded SQL",evidence/rollback.md,none
```

Accepted route kinds are `screen`, `api`, `batch`, `worker`, and `report`.
Accepted CacheDB shapes are `generated`, `projection`, `ranked projection`,
`repository`, and `cold path`.

## 5. Interpret A Failure

| Failure | Required action |
| --- | --- |
| Route count differs | Complete the inventory or correct the manifest count. |
| Warm evidence missing | Run the exact warm route and export its coverage result. |
| Parity is not matched | Compare membership and ordering against the bounded SQL source route. |
| Memory is over budget | Reduce the hot window or payload, then warm and measure again. |
| Failover is not passed | Trigger the real staging topology failover and capture recovery evidence. |
| Rollback evidence missing | Execute the route-flag or deployment rollback drill. |
| Blocker is not `none` | Keep the route off production traffic. |

BEST: generate evidence in staging CI and commit only the immutable summary
artifacts used for the cutover decision.

ANTI-PATTERN: copy placeholder files, mark an untested topology as passed, or
assume the framework repository's Docker tests certify a customer's managed
infrastructure.
