# Tenant Generation Runtime Contracts

## Entity-Owned Scope

An entity purge covers only the registered entity namespace and the requested tenant, together
with its explicitly declared entity-owned stores. It is not a tenant-wide closure across other
entities or an automatic search for personal data. General Spring Cache method values cannot be
attributed to an entity class automatically and are outside this scope unless an ownership
participant is explicitly registered and included in the required manifest. A declared required
participant that is absent or cannot verify ownership makes the result incomplete.

Mixed projections and atomic queue entries must preserve other entities and tenants. Unsupported
ownership, unknown legacy entries or incomplete bounded verification must not be reported as
successful erasure. This is not a universal PII erasure, KVKK/GDPR certification, or backup,
export, log or external-system erasure guarantee.

## Original Generation And SQL Admission

Original generations are first-class envelope fields. They are not trace metadata, ciphertext
or business columns. Public write, queue, receipt and configuration records retain their existing
component counts. Worker scopes restore previous thread state and reject conflicting stamps.

The JDBC flusher checks each protected original generation in the same business transaction,
before write-fence filtering or business DML, retaining the guard row lock through commit or
rollback. Stamped writes are checked even if a metadata wrapper reports the default opt-out flag.
Missing registration, original stamps or tenant ownership fails closed. No foreground SQL
guard lookup or identity reservation is introduced.

External-change payloads without an original verifiable generation are rejected before invoking
a custom handler. A queued old payload must never be stamped with the current generation.
Legacy producer/read/projection paths without atomic generation support are unavailable for
opted-in metadata. This quarantine is a safety boundary, not a complete purge implementation.

## Queue And Post-Commit Work

Retry and DLQ copies retain the original envelope. Their XADD checks a stream-slot-local
generation mirror in the same Redis command. Every actual stream destination must be declared,
closed and verified; an entity-slot guard alone cannot protect a cross-slot queue.

Post-commit durability markers and compaction completion/requeue check the original generation
before any mutation. A closed or superseded generation cannot recreate a marker, payload or
notice. Compaction notices carry the payload's original stamp. Missing or inconsistent payload
ownership fails closed. The final accepted version of an exhausted range remains markable;
only further allocations reject exhaustion.

The stream cleanup engine snapshots a fixed upper ID, validates the primary process/slot and
enforces a 1 MiB reply budget before bulk/array allocation. COUNT 128 is not a byte limit.
Persist the complete returned frame before consuming it. Its raw frame can be decoded with the
same input caps after restart; a process/slot change instead requires recovery.

Only wholly owned original-generation entries can be erased. Mixed-tenant atomic entries and
unknown legacy envelopes return incomplete ownership; they are not split or deleted. Foreign
entries remain untouched. Erasure never trims or deletes a whole shared stream. A bounded
request/result checkpoint makes a lost response retry return the same count; commit that count
and the pending offset together before acknowledging the Redis result. A missing entry without
a retained result is an unknown outcome, not a fabricated zero.

## Erasure Versus Retention

With includeSource=false, cleanup must not call canonical SQL DELETE or enqueue SQL deletes.
Source rows remain, the durable guard stays closed, and explicit re-enable remains
MIGRATION_REQUIRED without a separately verified source/history migration contract.
With includeSource=true, direct exact-tenant source deletion is bounded and guarded separately.
Retention DELETE intentionally uses ordinary SQL write-behind; it is not Redis-only purge.

These components do not by themselves orchestrate all producers, reads, L1, fills, projections,
declared stores, audit checkpoints or a COMPLETE/REENABLED receipt. Full registration and
verification of those paths is required before exposing an end-to-end purge operation.

Native identity participants use the same bounded participant contract. Their durable checkpoint,
phase evidence and physical-key counts commit together under the SQL guard; native Redis I/O runs
outside that transaction. A previously VERIFIED phase is re-attested, not trusted across process or
closure changes. The sealed ID substream is separate from required KEY/ENTRY cleanup work; those
items must still be routed and verified, never silently skipped.
