# Explicit Tenant Runtime Enrollment

Runtime enrollment binds actual `CacheDatabase` producers, reads and local caches to an
explicit deployment approval. It is not application-wide PII discovery or source-owner
onboarding. The supported initial profile is `CLIENT_ONLY` with `REDIS_ONLY` reads,
canonical functions, no entity relations, no foreground source hydration and no registered projections.
Unsupported profiles fail closed. Existing unprotected read paths are unchanged.

## Startup And Approval

Every standard `CacheDatabase.configureTenantProducer` (also the session path) durably
enrolls a fresh runtime incarnation in an EXISTING authorized namespace BEFORE returning the producer. `prepareTenantProtection`
uses that same registration path; it is not a separate roster authority. The producer and
its actual read/L1 fence share one UUID. Obtaining a raw protected repository without a
producer does not enable reads or writes. Configuring a producer without preparing or
sealing it does not enable them either.

Ordinary configuration NEVER creates a missing registry. Loss, expiration or deletion of an
initialized registry requires explicit recovery; Redis absence is not evidence of a fresh
namespace. A new tenant or a new process cannot reset the namespace's retained birth record.

The namespace registry is a persistent schema-2 hash with at most 128 enrolled incarnations.
Before initial sealing it is `COLLECTING`: ordinary protected reads and writes are blocked.
The atomic seal compares the approved set against the COMPLETE enrolled set, not just
the locally supplied member names, while checking every declared CLOSED pair. Omitting
an actual enrolled root fails without publishing a seal. Unknown schema, extra fields,
unexpected expiration or a changed Redis run ID fail closed.

Register every protected entity before preparing enrollment, and prepare before obtaining
any repository. For a new namespace, complete the first-only initialization below before
preparation. Preparation configures all producers first, then materializes their actual
repositories and read/L1 participants. A preparation failure requires discarding that root;
it is not safe to reopen partially prepared configuration.

```java
CacheTenantRuntimeEnrollment enrollment;
try (var admin = CacheTenantContext.administrative()) {
    enrollment = database.prepareTenantProtection(32);
}
var descriptor = enrollment.descriptor("Customer");
```

Build each real `RedisTenantPurgePlan` with this enrollment's `barrier`, `readFence`,
`pointProfileTarget` and `controlParticipant`. Include `runtime-control-roster` in the
participant manifest. The point-profile target describes the actual managers; its empty
page catalogue is NOT proof that historical pages or other stores do not exist. Historical
or additional projection/page stores require a separate reviewed adapter/profile.

An administrator must approve the exact plan fingerprint, coverage token, actual local
read-fence UUIDs and complete finite tenant-pair catalogue. Use `ApprovedRoster` and
`sealClosed(plan, approval, closedPairs)` on every actual runtime. All listed pairs must
hold current CLOSED Redis maintenance leases; their validation and sealing of the existing roster occur
atomically using the existing generation barrier. This operation does not validate or
open SQL admission, create owner markers, reserve versions or certify source migration.

A new root/pod incarnation registered after `SEALED` is retained as unapproved and atomically
moves the namespace to `REVIEW_REQUIRED`. It cannot use ordinary raw reads/writes. Existing
members also lose normal admission, and the previous closure evidence is no longer valid.
Admission is checked in the actual Redis capture, publication and mutation command, including
canonical protected FCALL. A previously captured ticket cannot bypass a later registry change.
Read/L1 completion revalidates admission; a preflight-only check is not the contract.

Enrollment and approval entries have no TTL and are never removed on process/root close,
missing ACK, timeout or failed preparation. The approved set/catalogue cannot be rewritten.
**Retirement, reapproval, rolling restart and elastic membership are NOT supported by this
initial API.** A late join therefore requires a separately verified CLOSED recovery/migration;
there is no in-place recovery command here. Do not deploy this fixed-roster profile where
automatic replacement must remain available, and do not delete/edit registry keys to restore
service. A missing/dead member remains evidence requiring resolution, not a quorum reduction.

Complete enrollment covers standard protected roots running this admission protocol, not
old binaries, arbitrary Redis clients, custom adapters or unrelated application caches.
Mixed-version/legacy protected writers must be stopped and fenced before initial activation;
an application-supplied approval callback cannot certify their absence. Registry enrollment
does not create a source-owner certificate or prove full historical data-store inventory.

## First Namespace Initialization

First initialization is an explicit administrative operation on the actual root. Install
the tenant/INITIAL metadata schema in the actual provider first. Provision the real SQL
guard CLOSED before starting INITIAL; its generation must be pristine: epoch zero, no
operation and no earlier INITIAL or purge journal. A reviewed namespace decision must
establish freshness externally. Neither an empty Redis database nor absent SQL rows can
make that decision for the application.

Use `database.prepareInitialNamespace(metadata, nativeBinding, guard, ids, firstId,
lastId, maxRows, pageBytes, rowBytes, residentBytes, maxInFlight)` before any repository.
The binding must use the root's canonical native layout; the guard must belong to that
root's actual DataSource. Encrypted metadata requires the actual configured key provider
and registered runtime-bound codec. The initial profile accepts canonical Long IDs only,
with no composite/partitioned indexes, relations, projections or route quota reservations.

The returned helper's `window()` and `descriptor()` are inspectable plan identities, not
creation authority. Explicitly review the descriptor's `namespaceFingerprint()` and pass
it to `JdbcNamespaceBootstrapGate` using the SAME actual guard. Then invoke
`namespace.bootstrap(gate, generation)` BEFORE INITIAL acquisition. The root validates
the actual resources, canonical roster, mapping and Redis run ID before SQL consumption.
SQL commits a retained one-use namespace record before the private native initializer
creates the registry. That creation and the actual producer/read fence share one UUID.
An ambiguous commit, lost Redis reply or partial startup requires recovery; do not delete
the retained record, retry creation under a new tenant, or edit the roster to reopen it.

Next prepare every actual runtime and approve the complete enrolled set. Build the real
plan using its exact runtime-control participant, `enrollment.initialSourceParticipant`
for this helper's window, and the matching `JdbcInitialBootstrapPurgeParticipant` named
`initial-source-journal`. Include all actual stores and queues in the reviewed manifest.
The native participant is named `initial-native-window`. Neither participant substitutes
for historical inventory or source ownership.

Create the concrete coordinator with `namespace.createBootstrap(enrollment, plan,
approval, rangeSize, reviewedWindowFingerprints)`. Invoke its bounded `step(request)`
reusing the same operation. `namespace.pendingClosure()` exposes only the REAL lease
installed by that coordinator, so peers can explicitly seal the same CLOSED approval and
poll their own controls. `BUSY` means retry the bounded phase after physical delivery
drains; a dead or missing runtime never becomes an implicit acknowledgement.

INITIAL captures the selected source payload once, installs encrypted Redis values and
blind indexes while CLOSED, freshly verifies every committed page, and installs the
fenced version range. SQL OPEN commits before conditional Redis OPEN. No business source
DML, owner-only marker write, write-behind enqueue or foreground SQL fallback is part of
native hydration. A lost activation reply must recover the exact committed activation;
an already OPEN range is not reinstalled or rewound. Unsupported profiles and incomplete
inventory remain closed. Administrative time budgets do not override physical transport
timeouts or establish a hard wall-clock deadline for a custom connection factory.

## Bounded Control Polling

Invoke `pollControl(maxRequests, maxMillis)` from each runtime's existing administrative
loop, including client-only deployments. The catalogue is limited to 64 pairs across the
root, with 1..64 requests and 1..5000 milliseconds per invocation. Polling is round-robin
over declared pairs, not Redis SCAN or discovery of arbitrary tenant data. The existing
per-pair closure journal remains the request/lease authority.

```java
try (var admin = CacheTenantContext.administrative()) {
    var progress = enrollment.pollControl(8, 1000);
    // locallyAcknowledged is NOT distributed closure.
}
```

No background thread, new Redis endpoint, new connection pool, SQL query or automatic
retry is created. A caller may retry the bounded poll under the same administrative
operation. Time budgets are checked between physical commands, not hard socket-abort
deadlines. Configure bounded timeouts and pool waits on the actual runtime transport.
Observe the returned examined/local-ACK/pending/incomplete counters without raw tenant
labels. Back off on pending or unavailable transport; retain missing-member evidence.

Missing or stale acknowledgements never establish drain. Known pending membership remains
DRAINING, and `requireClosed` rejects it with `INCOMPLETE_INVENTORY`. Producer uncertainty,
unknown ownership/coverage and malformed rosters cannot produce a CLOSED certificate.
Fresh evidence binds the exact operation, generation, lease owner/epoch, roster, manifest
and Redis run IDs. Every destructive facade step still needs live pre/post validation.

## Owned Delivery Boundary

Use the explicit synchronous delivery scope for routes covered by this approval:

```java
enrollment.deliver(CustomerCacheBinding.METADATA, tenant,
        repository -> repository.findById(id),
        result -> boundedResponseWriter.write(result));
```

The consumer must finish actual owned delivery before returning. Do not launch asynchronous
work, return a response writer/stage, or retain a result for later delivery from this callback.
Such routes require their own physical completion integration and are outside this profile.
In-flight tickets remain held through the executing consumer, even if a caller abandons
its wait. Admission close does not force-cancel work or claim it has drained. The underlying
source/response transport must enforce its own finite I/O and response-size budgets.

Generation checks on raw repository methods do not certify later application response
serialization, network delivery or third-party caches. The approved boundary covers only
the declared roots/routes. It cannot revoke copies already delivered before closure.

`controlStoreKey` exposes the exact retained security-store descriptor for administrative
inventory tooling. The control participant validates this shared store but removes no keys
or foreign contributions. Source bootstrap, full historical inventory, arbitrary Spring
Cache ownership and end-to-end source deletion remain separate contracts.
