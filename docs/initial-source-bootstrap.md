# Initial Source Bootstrap

[Türkçe](../tr/docs/initial-source-bootstrap.md)

Initial bootstrap populates a declared, bounded hot window while one entity namespace and
tenant remain CLOSED, then coordinates durable SQL admission with native Redis activation.
It is administrative work, never a foreground SQL fallback or a certificate covering all tenant data.

## Supported Profile

The initial profile requires `CLIENT_ONLY` with `REDIS_ONLY` reads; other runtime roles are
not certified by this coordinator. Distributed worker/replay routes still require explicit
inventory and closure coverage.

INITIAL rejects L1-enabled roots, Redis Cluster and custom Redis operation adapters before namespace
birth consumption. Both foreground and background must use the canonical `JedisPooledOperations`
transport over the same standalone pooled client. A pooled connection to a Cluster node is not a
standalone deployment. Global defaults, registered per-entity policies and `@CacheNearPolicy`
metadata must all leave L1 disabled. Registration changes are rechecked before bootstrap and each
coordinator phase; enabling an unsupported policy later fails closed rather than consuming another
namespace grant or continuing activation.

The initial profile accepts globally unique, non-null integral `Long` IDs. The physical JDBC
type, precision, scale and exact conversion are checked. String/UUID ordering is not inferred
from Java round-trips or database collation; unsupported ordering requires migration.

The source must have unfiltered leading `(tenant,id)` and `(tenant,version)` indexes and bounded
supported column types. Versions must be positive exact integers no greater than
`9007199254740991`. NULL, zero, fractions and overflow are errors, not normalization inputs.
The complete selection and payload mapping, including soft-delete markers, is fingerprint-bound.

Limits are 10,000 selected rows, 128 rows per page, 128 persisted pages, 1 MiB per encoded row,
8 MiB per encoded page and an explicitly approved total no greater than 64 MiB.
These bound captured page bytes, not total Redis memory; native index/verification expansion
also requires a physical bound. The supported `POINT_NO_QUOTA` profile has no route/quota
reservation. Quota-enabled routes, relationships, projections and source read-through are
unsupported rather than silently bypassed.

The hot window is not an all-history Redis identity ledger. Cold source rows and caller-supplied
new IDs do not become owned merely because activation succeeded.

## Source Writer Prerequisite

Stop or independently fence every writer of the selected source scope before INITIAL, and keep
that boundary in place until activation and the application cutover finish. Include existing ORM
applications, stored procedures, imports, scheduled jobs and external CDC/outbox producers.
The SQL generation guard fences cooperating CacheDB workers; it does not intercept arbitrary
direct SQL issued by another application. An empty Redis queue or a reviewed mapping is not proof
that those writers have stopped.

Source revalidation detects changes observed while pages are checked. It cannot guarantee that an
uncontrolled writer will not commit after the final check. If source-writer closure cannot be
independently established, do not activate this route or treat its bootstrap result as cutover
approval. This coordinator does not automatically certify external-writer quiescence.

## Administrative Sequence

Install the provider's security migrations explicitly. INITIAL additionally requires
`V019_3__source_owner_onboarding.sql`, `V019_4__initial_source_bootstrap.sql` and
`V019_5__namespace_bootstrap_once.sql`. Runtime code never installs schema.

A first namespace requires a reviewed durable `JdbcNamespaceBootstrapGate` consumption
before native roster creation and before INITIAL acquisition. It binds the exact roster key,
mapping, Redis run ID and the single runtime incarnation used by producer and read fence.
SQL consumption commits before the native send. Missing/expired initialized roster, lost response
or a crash is not permission to recreate it. SQL absence alone proves nothing about a legacy deployment.

Use `CacheInitialNamespace.createBootstrap` after preparing the namespace on the actual
`CacheDatabase` root. It binds the registered metadata, storage codec, client, keys, guard and
reviewed plan. The internal factory accepts only the framework's root-owned native adapter;
applications cannot supply their own closure or activation adapter. A local CLOSED flag, caller
Boolean, process-local map or declared empty roster cannot establish closure. The required
manifest includes the concrete `JdbcInitialBootstrapPurgeParticipant` for interrupted captured pages.

Create one `InitialTenantBootstrap.Request` with the original generation, tenant, operation UUID,
operator token and bounded lease. Reuse it unchanged for bounded `step(request)` calls.
Each call completes its physical I/O before releasing admission.

The coordinator durably captures a ciphertext page before native hydration. The same encrypted
payload, random nonces and keyed row proof survive retry/restart. It never stores protected plaintext
or unsalted hashes of protected plaintext. Fresh source comparisons use generated column access
in memory, not re-encrypted byte equality or logs.

Version inventory includes bounded native owner/namespace/declared-queue discovery, retained
durable fences (including deletes) and indexed source extrema including cold/soft-deleted rows.
Unknown ownership, mixed legacy queues, undrained accepted history or unprovable external routes
remain incomplete. Accepted events retain their original generation.

Every acknowledged page is freshly verified against current owner, payload, source version,
canonical indexes and L1 state. TTL coverage must remain valid through native OPEN; a previous
page ACK or summary digest does not establish current residency. The retained allocator reserves
a bounded range above the verified history. SQL OPEN and its activation journal commit together;
Redis activates conditionally afterward. If Redis already opened the exact committed activation,
recovery acknowledges it without closing Redis or resetting a consumed range.

After the exact committed native activation is observed OPEN, `SQL_OPEN` may continue while bounded
journal cleanup runs. Continue the same request until `ACTIVE`. Each continuation removes at most
one indexed temporary row without fetching its payload; captured pages are bounded to 8 MiB and
raw frames to 1 MiB. Captured pages, scan locators, worklists and facade/call checkpoints are cleaned
under their original INITIAL operation UUID, including when a later purge uses a different UUID.
The purge participant durably anchors that original UUID before deletion and independently verifies
cleanup. Unattributed older operations remain INCOMPLETE; their data is not silently deleted.

Once cleanup has started, a native CLOSED state requires explicit recovery rather than hydration
from partially removed pages. Namespace grants, generation guards, allocator highwater and
version/write fences remain retained security metadata.

Finalization is available only through the root-owned coordinator. A JDBC session and an activation
DTO do not authorize `ACTIVE` or cleanup. Each cleanup chunk obtains a fresh physical observation
of the exact original Redis OPEN activation and consumes it once in that same root's validated SQL
transaction. The pure journal codec and cleanup descriptors do not execute finalization.

In `SQL_OPEN`, `prepareActivation` returns the original committed activation unchanged, including
its original version range, or fails with `RECOVERY_REQUIRED`. A replacement administrative lease
never reserves another range. An already consumed version remains consumed.

The native observation fences delayed older close commands using the current administrative SQL
lease epoch; it does not change the activation, allocator or generation. A cooperating close that
needs a newer SQL lease must wait for the current SQL guard transaction. The exact live lease is
validated under that row lock before cleanup and again before commit; failure rolls back the chunk.
This ordering is not an atomic transaction across SQL and Redis or a hard total I/O deadline.
Raw Redis administration, fabricated native lease DTOs and external SQL writers are not certified
by this protocol; they require independent operational fencing.

`close()` stops new coordinator calls. A previously admitted synchronous step may finish and commit
its current bounded chunk; close neither cancels physical I/O nor acknowledges a distributed drain.
Keep the underlying SQL/Redis resources available until that call physically exits. Resume the
original request through a new valid coordinator after an interrupted or ambiguous result.

## Failure And Scope

Use bounded retries with the same request only for transient failures. INCOMPLETE,
MIGRATION_REQUIRED or unknown activation requires explicit investigation, never deletion/reset
of security metadata or automatic fresh-namespace inference. No automatic repair of an ambiguous
namespace birth is provided.

INITIAL is not a purge re-enable path. Existing purge history is rejected, including Redis-only
purges that intentionally leave source rows and remain closed. Unregistered external stores,
generic Spring Cache values, backups and historical exports are outside this entity-owned scope.
No universal erasure or legal certification is implied.
