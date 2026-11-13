# Upgrading To CacheDB 0.19.0 - UNRELEASED

[Türkçe](../tr/docs/upgrading-to-0.19.md) | [Draft release notes](releases/v0.19.0.md)

**UNRELEASED migration draft. Bounded F3 candidate acceptance is complete; final-artifact binding,
general acceptance and private evidence import remain PENDING. This is not approval to migrate
production or evidence that 0.19.0 packages are published.**
Do not activate a feature simply because its classes compile. The final matching artifacts,
provider/topology evidence and feature-specific migration contract must be approved first.

On November 10, 2026, candidate `long-acceptance-20261110e` passed F3 188/188 and the declared
four-feature sample scope: nine PostgreSQL/MSSQL/Oracle cells, 141 Java tests and 381 HTTP assertions
(zero failures/skips). See the [candidate hashes and qualification boundary](releases/v0.19.0.md#bounded-candidate-acceptance).
This is not baseline HTTP, general feature acceptance or application-specific existing-data onboarding.
Only raw COUNT/FACET's mandatory 2x throughput target (`rawT4target`) was waived by explicit user
acceptance on November 9, 2026: PASS BY ACCEPTANCE EXCEPTION, not a numeric pass. Historical failures
are preserved; correctness/isolation/bounds, final-artifact regressions and all other performance gates remain required.

The native-thread fixture defect was corrected by replacing orphaning BusyBox timeout readiness
watchdogs with fork-free TCP readiness, not by raising NPROC (256/256). Authenticated Service PING
remains mandatory; TCP readiness alone is not Redis acceptance. E's original outside-`target` report
failure remains recorded; independent qualification accepted the immutable evidence without replay.

## 1. Upgrade The Java Toolchain

The minimum for 0.19.0 is Java 21, including library bytecode and generated application bindings.
Building from source requires Maven 3.9+. Update the compiler/toolchain, IDE, CI, container base
image and deployed JVM together; verify the JVM inside the actual container, not only the build job.
Applications remaining on Java 17 must remain on 0.18.x. Do not relabel old JARs or rewrite historical
release notes to imply they required Java 21.

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
</properties>
```

Re-run annotation processing and remove stale generated build output as part of the application's
normal clean build. Configure processing explicitly; do not depend on runtime reflection or a compiler's
implicit processor discovery policy. `ojdbc17` is the Oracle driver's artifact name, not an instruction
to keep the CacheDB runtime on Java 17. No preview flags are needed.

CacheDB-owned async, refresh and invalidation executors have their own bounded lifecycles.
`spring.threads.virtual.enabled` controls Spring behavior; it does not remove CacheDB admission limits
or switch off those owned virtual threads. A virtual thread is not an unlimited connection or CPU budget.
Keep JVM-vendor-specific flags separate: HotSpot ZGC options are not portable OpenJ9 settings, and
no collector improvement is established by this release draft.

## 2. Align Artifacts And Runtime Options

After release approval, align the BOM, annotations, processor, chosen provider starter, workers and
optional modules to the same approved version. Keep only the selected SQL provider and JDBC driver;
there is no silent fallback to another provider. Optional `cachedb-reactor` and `cachedb-spring-data`
do not become core dependencies. Their examples do not assert current package availability.

Existing 0.18.1 quick-start coordinates and download references remain valid as examples of that
line, not as proof of 0.19.0 availability. Verify the actual release manifest and checksums before
changing dependency coordinates. Do not invent a future download link or reuse an older candidate's JAR.

New options belong to the immutable `CacheRuntimeOptions`, with `builder()` / `toBuilder()` and the
`CacheDatabase(..., baseConfig, options)` overload or the Spring runtime-options integration.
`CacheDatabaseConfig` keeps its existing record shape; do not append components, introduce static
sidecar maps, or replace the existing configured trace propagator.

## 3. Review Defaults Before Opting In

| Feature | Default / activation | Required review |
| --- | --- | --- |
| Point-read merging | `cachedb.redis.read-coalescing.enabled=false` | Explicit enabled/disabled measurements; raw async FIFO is still bounded when merging is off. Review per-runtime reply and queue memory. |
| L1 invalidation | L1 opt-in; AUTO mode and legacy publication true | Dedicated connections, ACLs, all-master readiness, topology recovery and every consumer's publication dependency. |
| Async / Reactor | Explicit API use; runtime-owned gate, default concurrency 1024 | Size against pool/pod limits, preserve caller context, reject inline borrowed executors, close owned resources. Reactor publishers start work at subscription. |
| Observations | Core no-op; Spring adapter conditional on registry/configuration | Configure bounded handlers/exporters and allowlisted low-cardinality tags. `cachedb.observation.enabled=false` disables auto-configuration. |
| Sorted composites | Explicit `sortBy` declaration and generated contracts | Upgrade all writers, rebuild, verify completeness/score domain; keep exact bounded fallback and typed residuals. |
| Spring Data | Optional module and generated repository | Advertise/forward effective query capacity, use Pageable/Limit, and qualify bulk ownership/partial-failure behavior. |
| Spring Cache | `cachedb.spring-cache.enabled=true`, `@EnableCaching`, declared caches | Finite TTL/value/L1/load budgets, tenant scope, explicit value codecs; synchronous annotation results only. |
| Encryption / retention / tenant purge | Explicit annotations and runtime/schema registration | Keys and compatible data first; review source scope, all participants and onboarding. Do not turn on protection over legacy data without approved migration. |

Read the [point-read](redis-read-coalescing.md), [native async](redis-native-async.md),
[tracking configuration](near-cache-tracking-configuration.md), [observation](cache-observation.md),
[Spring Data](spring-data-migration.md) and [Spring Cache](spring-cache.md) guides for exact bounds.

## 4. Preserve Libraries, Keys And Index Proofs

Use the 0.19.0 side-by-side Redis Function names as a set: `cachedb_v019`, `entity_upsert_v019`,
`entity_delete_v019` and `compaction_complete_v019`; the bulk-delete entry derives from the delete name.
Keep old libraries/names/markers while old or rollback callers exist. Explicit custom names require
their own disjoint library and entry points; changing only a version string does not isolate them.
Never FUNCTION FLUSH, disable content-drift checks, rewind versions, or replace legacy library content
to force startup. See [Function upgrades](redis-functions-upgrade.md).

Regenerate composite metadata, upgrade every participating writer/fill path, then perform the fenced
rebuild. An index signature is not proof that an old writer respects a new Long domain contract.
Do not manually set verification flags or infer exact Long ordering from a rounded Redis double.
Unsafe/missing proofs require the complete bounded exact fallback or an explicit failure. Atomic
batches and partial schema transitions do not authorize a partial fast-path result.
See [composite range migration](composite-range-indexes.md).

## 5. Treat Query Capacity As A Contract

`EntityRepository.entityQueryCapacity()` distinguishes UNKNOWN, UNRESTRICTED and FINITE(maxRows),
including FINITE(0). It describes the published effective row budget, not permission, an atomic
snapshot, index completeness or SQL history. The actual query rechecks authoritative guards, so a
policy change can reject a query after capacity inspection. Do not retry by raising its limit or
disabling its guard.

Custom/decorating repositories must forward the contract or retain UNKNOWN. Explicit bounded legacy
requests still use backend validation; new unpaged/complete-delete selection fails closed on UNKNOWN.
Known InMemory capacity is not a Redis performance/admission simulation. An active zero is not
unrestricted and cannot be fixed by chunking: with hot limit 8 and headroom 10, no positive entity
query is legal. Use already trusted known IDs for bounded bulk deletion, or an independently approved
administrative workflow. A query result alone never bootstraps persistent ownership through SQL.

Derived deletion selects at most min(capacity, 10,001) rows and validates the whole selected ID/tenant
set before mutation. At an exactly full selection it cannot prove the whole match set and rejects
before deleting, even if the actual count might equal the limit. Unpaged List/Page selection is capped
at min(capacity, 100,000) and likewise fails at the cap with Pageable/Limit guidance; no silent truncation.
Explicit finite requests include Slice/Window lookahead in preflight. Native bulk mutation uses chunks
of at most 128 IDs and real per-row receipts, not an atomic all-or-nothing transaction. Preserve accepted,
rejected, uncertain and cleanup-pending outcomes; never retry the entire uncertain call blindly.

No delete-while-paging, broad owner SQL scan or N+1 owner bootstrap is an upgrade workaround.
Concurrent writes are not frozen by selection. Qualify nontransactional behavior with the actual provider.

## 6. Do Not Open Protected Admission Before Qualification

Production onboarding of existing data into tenant-generation protection remains pending in this draft.
An annotation, a configured producer or a successful component test does not open a verified generation.
Do not copy test fixtures that seed owner markers or generation/range state into production.

Choose the correct onboarding workflow before changing admission:

| Workflow | Scope | Required before opening admission |
| --- | --- | --- |
| First protected namespace | The bounded INITIAL `POINT_NO_QUOTA` profile, not relationships, projections or arbitrary history. | Qualify the initial fill, interrupted-page recovery, uncertain SQL activation and purge against the selected provider. Independently stop or fence external source writers. |
| Ownership preparation for existing IDs | An explicitly reviewed, closed worklist; not an automatic scan of all SQL history. | Complete the ownership inventory. Preparation alone does not activate a generation or open admission. |
| Explicit EMPTY reopening | The candidate-qualified standalone `POINT_NO_QUOTA` profile with canonical `Long` IDs and an exclusive ID domain. | Apply the explicit activation, fresh non-overlapping ID admission and separate OPEN version refill sequence; preserve generation-1 replay fences. Candidate acceptance is not application cutover approval. |
| Legacy namespace migration or restore | A separate migration with retained versions, tombstones, generations and encryption keys. | Approve and verify the migration and rollback procedure for the application. Neither INITIAL nor bounded EMPTY acceptance authorizes arbitrary legacy reopening or restore. |

See [initial source bootstrap](initial-source-bootstrap.md) for supported types, indexes, limits,
source-writer closure and the administrative sequence. Do not disable an existing security fence to
make onboarding pass; keep the affected protected namespace closed until its requirements are met.

For encryption, supply an approved `CacheKeyProvider` before registration, retain required read keys
through rotation, and plan bounded data migration/reindexing separately. Annotated legacy plaintext is
rejected, not auto-upgraded. REDIS_ONLY still leaves plaintext SQL; REDIS_AND_SOURCE needs compatible
text columns and approved source codecs/loaders. Equality blind indexes leak equality relationships;
range, sorting, arbitrary raw SQL and unsupported source behavior remain rejected. Do not bypass
encrypted cursor/filter contracts with unkeyed hashes or treat a cursor as authorization.

Protection requires the selected provider's `V019__tenant_protection.sql`, `V019_1__purge_facade.sql`
and `V019_2__purge_list_scan_kind.sql` in order through the application's migration system; there is
no runtime DDL. INITIAL also requires `V019_3__source_owner_onboarding.sql`,
`V019_4__initial_source_bootstrap.sql` and `V019_5__namespace_bootstrap_once.sql`, in that order.
Migrations alone do not supply ownership inventory, original-generation stamping,
safe version-range recovery or distributed read/fill closure. Guard closure must fence actual JDBC
business transactions; all writers, replay sources, read paths and declared stores must participate.
Never stamp an old payload with the current generation, reset a retained highwater, or accept a local
closed flag/Pub/Sub acknowledgement as complete distributed closure.

Purge is entity-namespace-and-tenant scoped. Missing ownership/participants and unverifiable mixed
legacy entries fail closed. Redis-only erasure must not issue/enqueue SQL DELETE and cannot silently
readmit retained source history; explicit reopening requires separately verified migration. Source
erasure is bounded and explicit; completion still leaves admission closed. Retained security fences,
backups, redo/WAL, exports and unrelated Spring Cache values are not automatically erased.
Retention is resident-index maintenance, not cold-history purge: EVICT_CACHE preserves SQL, while
DELETE uses ordinary write-behind and its durability contract.

Follow [encryption](field-encryption.md), [retention](retention.md),
[producer lifecycle](tenant-producer-lifecycle.md) and [entity-scoped protection](tenant-data-protection.md).
They do not constitute legal compliance certification or an approved legacy-data onboarding procedure.

## 7. Qualify Failure, Memory And Rollback

- Retain fair, finite connection-pool admission, socket/connect/pool deadlines and application-level
  backpressure. Borrowed clients are not automatically retuned; virtual threads do not create capacity.
- Point-read live raw-content reservation defaults to 256 MiB per runtime, not 256 MiB total heap.
  Queues, decoded objects, RESP buffers, L1 and application-retained futures add overhead. Budget for
  the whole pod and number of runtimes; do not add their independent content limits as a heap guarantee.
- Cancellation or delivery timeout does not forcibly stop physical Redis/JDBC I/O or roll back accepted
  writes. Do not release underlying work credits early. Keep callbacks short and close runtimes before
  their pools; borrowed executors remain caller-owned.
- Propagate tenant/admin, causal, configured trace and route context explicitly. Do not move a live unit
  of work to another thread. Async unit-of-work use and unsupported causal non-point reads fail closed.
- Compare the exact final candidate and preserved baseline under matching fair-pool, JVM, CPU/memory,
  dataset and topology controls. Retain all failures; startup errors are not numeric measurements.
  The raw T4 2x target alone is accepted by explicit exception, not achieved. Functional tests or older
  candidate scores cannot close the remaining performance or final-artifact regression gates.
- Require the declared release/provider/Cluster/sample coverage, required-suite anti-skip checks and
  supported JFR pinning evidence; qualify application restore/replay wherever claimed. The bounded F3
  candidate result is not universal restore/HA proof. An unavailable JFR event source is not zero pinning.
- Roll back only to a qualified artifact/key/library/data combination. Preserve versions, tombstones,
  SQL write fences, keys and generation/highwater metadata. Once a new encrypted/generation protocol is
  active, keeping the old Function library is not sufficient evidence that old binaries may resume writes.

Keep [release approval](release-checklist.md), artifact checksums and both language versions aligned.
Until all required gates close, retain the UNRELEASED label and do not announce production readiness.
