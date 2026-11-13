# Existing-Identity Ownership Preparation

Protected namespaces reject a mutation when an identity has no verified owner. A
missing owner is not evidence that an ID is new: it may already belong to another
tenant in cold SQL history.

`CacheDatabase.prepareSourceOwnerOnboarding` provides an administrative,
restartable preparation workflow for a bounded candidate worklist, including its
existing Redis artifacts and observed absences. It does not inventory all SQL
history, allocate caller IDs, create cache artifacts, delete source rows, or open
admission. A WARMED checkpoint covers only that worklist.

## Preconditions

Use a trusted administrative entry point, not an HTTP parameter granting authority.
Provision the real namespace/tenant generation CLOSED. Register the entity and
prepare its actual runtime enrollment before publishing repositories. All declared
producers, readers, fills and queue/replay routes must actually close and drain.
Unknown or unstamped external routes must remain unsupported/incomplete. An empty
roster is not closure evidence.

`CacheDatabase.prepareSourceOwnerOnboarding` supplies an actual-root native
`ClosedRuntime` adapter; applications do not implement a replacement callback.
The supported point profile requires **both** runtime role `CLIENT_ONLY` and read
mode `REDIS_ONLY`, on standalone Redis. Foreground and background operations must
use the same actual `JedisPooled` client. L1 must be disabled in default, entity and
annotation policies. The adapter binds the root's real JDBC guard, enrolled
roster, generation barrier, read fence, queue routes and canonical Redis layout.
Extra external-store participants, relations, projections, composite indexes and
additional protected-store participants are unsupported. Only the library's
String/Long identity codecs are used; a matching format token does not authorize
custom JDBC decoding callbacks.

The source table needs a reviewed leading tenant index, a global single-column
unique non-null ID, and an integral version column. Tenant storage is NVARCHAR on
SQL Server; PostgreSQL requires UTF8 and Oracle AL32UTF8 with VARCHAR tenant storage.
The existing guard validates these tenant requirements. The reader reuses its
exact-byte predicate and also compares the returned tenant and canonical ID with
exact Java equality. Case and trailing spaces are not normalized.

## Administrative Calls

Install V019_3__source_owner_onboarding.sql for the selected JDBC provider explicitly,
after V019, V019_1 and V019_2. There is no automatic runtime DDL. Review the source
binding and ownership-plan fingerprints before constructing the JDBC reader.

In this Long-ID example, the application has already configured `database`,
`metadata`, `enrollment`, `nativePlan`, `approvedRoster`, `existingCandidates`,
`reviewedOwnershipPlanFingerprints`, `generation`, `tenant`, `operationId` and
`operatorToken` through its trusted administrative configuration. The metadata,
enrollment and native plan must belong to that same registered database root.
The reviewed fingerprint set must authorize the exact ownership plan; do not
approve a newly computed fingerprint merely because a request supplied it.
The approved roster names all actual enrolled roots, not caller-attested closure.
Persist the operation ID before the first step and retain the same request and
worklist when resuming.

~~~java
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.jdbc.JdbcSourceOwnerOnboarding;
import com.reactor.cachedb.starter.CacheSourceOwnerOnboarding;

// Inside the application's administrative method, using the configured inputs above.
CacheSourceOwnerOnboarding<Long> bootstrap;
SourceOwnerOnboarding.Request request;
try (var administrative = CacheTenantContext.administrative()) {
    var ids = JdbcSourceOwnerOnboarding.longIds();
    bootstrap = database.prepareSourceOwnerOnboarding(
            metadata, enrollment, nativePlan, approvedRoster, ids,
            existingCandidates, reviewedOwnershipPlanFingerprints);
    request = new SourceOwnerOnboarding.Request(
            bootstrap.plan(), generation, tenant, operationId, operatorToken, 30);
}
~~~

The factory validates configuration; it does not complete the preparation.
`bootstrap.plan()` is the request plan. `bootstrap.worklistToken()` identifies
candidate configuration, **not** a completed runtime inventory seal.

While a step drains, every enrolled root must seal the **same physical lease**
exposed by `bootstrap.pendingClosure()`. Its administrative control loop calls
`enrollment.sealClosed(nativePlan, approvedRoster, List.of(closedPair))`, using
that root's own enrollment and native plan, then services
`enrollment.pollControl(1, 100)`. For roots in separate processes, deliver the
actual closed pair through a trusted administrative channel. Do not manufacture
another lease or treat roster approval as a drain acknowledgement. Keep these
peer loops running concurrently with the initiating step; a later step can
acquire a new lease and require fresh acknowledgements.

Run one bounded step per scheduled administrative invocation; do not busy-loop:

~~~java
try (var administrative = CacheTenantContext.administrative()) {
    var checkpoint = bootstrap.step(request);
    // FENCING/RUNNING: retain the request and schedule another bounded step.
    // WARMED: this candidate worklist is prepared; admission stays CLOSED.
    // INCOMPLETE: investigate unresolved ownership; admission stays CLOSED.
}
~~~

On timeout or failure, retain the operation/request and investigate the cause
before another bounded attempt. Do not discard the journal, change the worklist,
or create a replacement operation to bypass missing closure or a changed seal.

Candidate input is at most 4096 distinct canonical IDs, 1 MiB total ID bytes and
128 pages, each at most 128 IDs / 128 KiB (also respecting the plan's lower limits).
It is only a configured candidate worklist, never proof of complete namespace,
SQL-history, external-store or replay inventory. No SCAN, wildcard inventory or
background executor is hidden in this factory.

During FENCING the native command seals actual per-candidate artifact existence,
types, bounded payload/metadata fingerprints, versions, tombstones, owners and
absolute expiries under the operation/generation and actual Redis run identity.
Each page is limited to at most 1 MiB of inspected values (or the plan's lower byte
limit) and 128 known metadata field names. Length checks precede value reads;
unknown fields, nonempty queue history and per-ID compaction history fail closed.
An interrupted multi-page seal retains its completed pages. Retry compares them;
it cannot overwrite changed artifacts or recreate a missing committed page seal.

Each step persists at most 128 canonical IDs before the first Redis installation.
One indexed source query returns at most 129 rows, with the extra row treated as
overflow. Input IDs, journal bytes, query timeouts and concurrent control calls are
bounded. The Redis bridge must bound its transport input before parsing; SCAN COUNT
is not a hard reply limit. Repeating a committed page is idempotent. A failed
installation leaves the pending page durable, without advancing its cursor.

Only exact, unambiguous source ownership can authorize installation for an
already-existing Redis identity. The bridge atomically rechecks generation,
distributed closure, lease ownership, existing artifacts and foreign-owner conflicts.
The native owner write also compares the FENCING artifact seal in that same atomic
command. Idempotent page receipts bind the SQL ownership/version proofs and exact
post-install state; retries never repair a deleted owner or silently recapture a
changed payload, version, tombstone or expiry. A missing seal is incomplete evidence,
not permission to build a replacement certificate during installation.
Missing source rows, unknown history or unknown ownership do not authorize a claim.
No payload, version, TTL or tombstone may be invented or rewritten by this step.
An identity without Redis artifacts remains absent; this API does not create it.

## Limits And Recovery

The SQL guard remains CLOSED after WARMED or INCOMPLETE. Initial activation still
requires independent replay-history, durable highwater/range and distributed
visibility verification; WARMED is not that certificate. This preparation refuses
OPEN tenants and any existing purge history. It cannot reopen a Redis-only purge.

There is no foreground SQL identity reservation. Cold SQL rows are not copied into
a growing Redis owner history. Unknown caller IDs remain rejected unless explicitly
admitted through the separate [bounded fresh-identity preparation](tenant-protection-components.md#prepare-a-bounded-new-record-range)
profile. That administrative API requires an exclusive numeric domain and never reuses
purged IDs; this onboarding API itself does not authorize inserts. Do not treat either
API as a general insert/bootstrap-all shortcut.

The journal retains one bounded row per namespace/tenant pair, including at most one
pending ID batch and pseudonymous operator/progress metadata. Completed pages drop
their IDs from the checkpoint. Retained security metadata, backups, other entities,
generic Spring Cache values and undeclared external stores are not erased or
certified by this API. No universal PII-erasure or regulatory certification is implied.
Terminal checkpoints and their retained inventory token are historical worklist
results, not fresh roster/drain/activation certificates. Unknown ownership still
ends INCOMPLETE without an owner claim, and every outcome leaves admission CLOSED.

## Qualification Status

The native adapter is implemented for the profile above. Fresh compilation and
focused qualification against actual Redis/JDBC runtimes remain pending. The API
and example do not establish complete inventory, INITIAL activation, automatic
OPEN, or production/release acceptance.
