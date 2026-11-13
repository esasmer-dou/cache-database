# Encrypted Storage Boundaries

## Configuration

Supply a key provider explicitly. No default key is generated, loaded from a
service locator or inferred from thread state.

```java
CacheRuntimeOptions options = CacheRuntimeOptions.builder()
        .keyProvider(keyProvider)
        .build();
CacheDatabase database = new CacheDatabase(redis, dataSource, baseConfig, options);
```

The provider reference survives options.toBuilder(). The existing 21-component
CacheDatabaseConfig record and database.config() remain unchanged. Existing
constructors use default runtime options with no configured provider. Unencrypted mappings
retain their existing behavior. For encrypted metadata, registration binds the
codec before payload framing and requires encryptionBound() to be true.
An inherited no-op bindEncryption implementation is not encryption support.
An explicitly bound codec may be supplied directly. Provider failures never
enable plaintext fallback. Configuration diagnostics do not stringify providers.

In Spring Boot, an optional unambiguous CacheKeyProvider bean is applied by the
runtime-options customizer before application customizers. Ambiguous beans fail
closed; use an explicit primary provider. A user-supplied CacheRuntimeOptions bean
is authoritative and must set its own provider. No key bytes are read from
configuration properties or logged during options binding.

## Runtime-Bound Sources

Use the registered runtime codec, including source reads and snapshots:

```java
var binding = database.register(OrderCacheBinding.METADATA,
        OrderCacheBinding.CODEC, CachePolicy.defaults());
EntityCodec<Order> codec = binding.codec();
SourceMapping<Order> source = database.sourceMapping(OrderCacheBinding.SOURCE);
// An equivalent generated entry point uses the explicit session:
SourceMapping<Order> sameRuntime = OrderCacheBinding.sourceMapping(database);
```

Static CODEC and SOURCE remain unbound. Rebinding creates a new instance and does
not mutate generated static fields. An encrypted source mapping with a different
table, column layout or incompatible metadata is rejected. Direct use of an
unbound encrypted decoder fails rather than reading a legacy plaintext value.
Registered JDBC source loaders use bound codecs. SQL predicates over randomized
REDIS_AND_SOURCE columns are rejected before JDBC access; the original logical
predicate is never replaced with a blind token for source SQL. REDIS_ONLY blind EQ
uses the logical SQL value. Source reads by ID decrypt through the bound codec.
Opaque SourceSqlRepository use with encrypted mappings is rejected before opening
a connection because its filter/sort/group semantics cannot be verified safely.
No separate verified source blind-index protocol is supplied.

## Redis And SQL Representations

Logical toColumns values are for business policy and PATCH assembly only.
Redis entity payloads and all protected queued field values are encrypted in
both scopes, including compaction hashes, retries and dead-letter records.
toIndexColumns emits blind HMAC tokens or omits non-blind encrypted fields;
logical or source-column maps must not be persisted as index metadata.

Entity-returning PATCH requires a complete hot entity. An assignment-only cold
receipt command uses the explicit ID/verified-tenant queue hook; it does not
perform SQL bootstrap. Actual null stays null; literal __NULL__,
cachedb:__NULL__, ~P:N and ~P:V strings are encrypted before transport framing.
Arithmetic deltas over encrypted fields are rejected before numeric diagnostics.

A protected prepared Redis chunk and the transient protected SQL batch each have
an 8 MiB UTF-8 payload/column budget. This is not a process-wide concurrency limit;
use bounded request admission and worker concurrency as well. saveAll retains its
existing chunked, non-atomic semantics; earlier chunks may already be accepted.

The background flusher authenticates all required non-delete members and creates
immutable transient SQL copies before fence processing or business DML.
REDIS_ONLY restores original SQL column text; REDIS_AND_SOURCE retains the
authenticated ciphertext. Original queue records are never replaced by those
SQL copies, so retries, compaction, DLQ and replay retain protected values.
The runtime queue mapper uses registered encryption metadata as well as the Q1
marker. Protected DELETE errors are redacted even without encrypted columns.
Automatic and manual replay sanitize failures before capture or reconciliation;
archive copies retain protected columns but discard old error text and unknown
fields. Legacy plaintext encrypted queue columns cannot be republished and require
an explicit migration; they are not silently converted into a new valid record.

Source and provider failures cannot put decoded SQL parameters or original
exception chains into worker diagnostics, DLQ, replay reconciliation or archives. Safe direct retry classification,
validated SQLState and vendor code remain available. Disable JDBC parameter/value
logging: field encryption cannot sanitize driver logs emitted before an exception
is returned to the library.

## Schema And Ownership

REDIS_AND_SOURCE requires a JDBC text column able to hold each actual ASCII
envelope. The 65,536-byte limit is the maximum envelope size, not a minimum
column capacity. VARCHAR/NVARCHAR and their long text equivalents are checked
through JDBC metadata. Every write, including a schema-cache hit, checks its
actual envelope against both limits. A smaller verified VARCHAR2 column can hold
smaller encrypted values; raw CLOB, numeric and binary mappings remain unsupported.

For example, `VARCHAR(1024)` accepts an envelope of 1024 ASCII characters or fewer.
An envelope of 1025 characters rejects the entire flush call before durable fence
initialization, transaction changes or business DML, including later Oracle chunks.
PATCH checks only supplied encrypted fields; SQL NULL does not require envelope
space. Account for encryption/Base64 expansion rather than sizing from plaintext.
This is a background flush check: a Redis `WriteReceipt` is not confirmation that
SQL committed. Monitor flush failures and follow the configured recovery policy.
A String type in entity metadata alone does not validate SQL types or hydration.

Schema validation caches at most 4096 immutable entity mappings per flusher.
Coordinate schema changes with runtime restart/revalidation; live schema mutation
behind a verified mapping is not a supported migration path. Configure bounded
JDBC connection/network timeouts for metadata access as well as statement timeouts.

Encrypted tenant writes require an established matching Redis ownership record.
Missing or mismatched ownership is rejected without foreground SQL lookup or
ownership reservation. Arrange verified ownership provisioning before activation.
Encryption does not establish authorization, durable tenant fencing or purge
protection.

## Partial Commands

`patch(id, assignments)` needs a complete hot entity because it returns the merged
entity. With cold PATCH enabled, `patchWithReceipt` can accept assignment-only
commands while the entity payload is absent. The receipt then has a null entity.
An established Redis version and, for tenant entities, matching verified ownership
are required. Missing state fails before acceptance; encrypted commands do not
bootstrap a version, ownership or a partial entity from SQL.

Partial queue encoding binds the canonical ID and explicitly verified tenant to
the field AAD. Only assigned fields are queued. REDIS_ONLY assignments are
decrypted into an immutable transient SQL copy; REDIS_AND_SOURCE assignments keep
their validated E1 envelope. Unassigned fields are never synthesized. Literal
`cachedb:__NULL__`, `__NULL__` and `~P:N` strings are encrypted before PATCH
transport framing; a real null remains a real null after decoding.

## Source Query Safety

Typed REDIS_ONLY blind EQ filters retain the original logical SQL parameter.
HMAC tokens are for Redis candidate lookup and cursor contracts, not SQL binds
or residual predicates. Bound ID reads decrypt REDIS_AND_SOURCE values.

A REDIS_AND_SOURCE filter cannot be evaluated against randomized ciphertext by
binding a logical value or a blind token. Such filters, protected sort/group
roles and opaque SQL queries for encrypted mappings are rejected before SQL.
No source blind-index schema or migration is inferred. A separately verified
source blind-index protocol is required before enabling those operations.

## Query And Batch Limits

Only blind EQ is supported for encrypted fields. Non-EQ predicates, sorting,
facets, aggregate values, composite/partition roles and encrypted relation-query
paths are rejected. Exact-index candidate keys use the original typed EQ value
through the codec, while residual evaluation retains the original logical filter.
Precomputed logical column maps are not trusted as protected index data.
Protected fields never produce range, prefix or text-token indexes.

Blind HMAC uses the fixed entity namespace and a separate stable key. AES rotation
preserves equality tokens when that HMAC key remains unchanged. Equal values can
be linked across tenants in the same entity/ring; this is not tenant-specific
blind-key isolation. Tenant-scoped candidate/owner checks are still required.
Changing HMAC keys, rings or token protocols requires an explicit index migration.

Encrypted atomic and unit-of-work batches use domain-separated ~I1: intent HMACs
with key material independent from AES. Protected queued columns remain E1 in
both scopes. No plaintext intent or dictionary-guessable unkeyed/salted digest is
persisted. Fresh ciphertext, AES key ids, clocks and trace fields are not retry identity.

A new builder using the same batch id and business intent returns the original
stream id and versions within the existing 24-hour deduplication window.
AES rotation preserves the fingerprint when HMAC material is unchanged.
Retrying an unknown acceptance outcome never replaces the published ciphertext
with a newly generated nonce. Changed values, operation order or explicit CAS
expectations conflict.

PATCH identity covers normalized assignments, not the resolved full row or its
derived read/CAS version. Residual predicates and source SQL parameters stay
logical. Encrypted arithmetic deltas are unsupported. Tenant atomic writes require
matching pre-established Redis ownership; they never bootstrap ownership from SQL.

Generated codecs provide canonical field encoding and intent HMAC through explicit
SPI methods. Custom codecs must implement that capability and encode fields
deterministically. Missing HMAC material/capability raises INTENT_KEY_UNAVAILABLE
before acceptance/writes; failed preparation also prevents committing a staged
prefix. Unencrypted-only batches retain their existing fingerprint format.
Existing limits remain 100 operations, 2000 stream fields and a 4 MiB wire budget.
Changing HMAC material requires coordinated index and deduplication-window migration.

Legacy encrypted-field plaintext, old index memberships and old queue/DLQ data need
an explicit migration and drain/cleanup policy. Adding an annotation does not
retroactively remove or protect existing plaintext.

See [field encryption](field-encryption.md) and
[encrypted codec contracts](encrypted-codecs.md) for key budgets, wire format,
null handling and compiler restrictions.
