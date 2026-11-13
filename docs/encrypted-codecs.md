# Generated Encrypted Codecs And Queued Writes

## Explicit Runtime Binding

`EntityMetadata.encryptedColumns()` is an immutable physical-column map of core
`EncryptedColumnDescriptor(keyRing, Scope, blindIndex)` values. Core never imports
annotation types. `columnTypes()` advertises String for REDIS_AND_SOURCE columns
so JDBC conversion must not parse an E1 envelope as a number/date/enum.

Generated static `CODEC` is deliberately **unbound**, not globally reconfigured.
Its encrypted operations fail with a fixed `CacheEncryptionException` before any
value is returned. Metadata remains available without keys. Bind once per runtime:

```java
EntityCodec<Order> codec = OrderCacheBinding.CODEC.bindEncryption(provider);
// Equivalent generated convenience factory:
EntityCodec<Order> otherRuntime = OrderCacheBinding.codec(otherProvider);
SourceMapping<Order> source = SourceMapping.entity(OrderCacheBinding.METADATA, codec);
```

Each call returns a new codec with final provider-backed engine and per-runtime
custom codec instances. No mutable static key, service locator, reflection,
ThreadLocal provider or thread-bound hidden setter exists. The provider's current
key must also be readable by id with matching AES/HMAC bytes at binding time.
Provider failures and custom codec runtime failures discard their causes/values.
Custom FieldValueCodec implementations must be bounded, deterministic and thread-safe.
`encryptionBound()` is false for the unbound generated codec and all legacy/custom
defaults; it is true only on an explicitly bound generated instance. PayloadEntityCodec
delegates this status. Protected metadata must not accept a default no-op binder.

The generated `source(provider)` factory is explicit. Do not keep using static
SOURCE or an unbound CODEC in source loaders/scopes after registry binding.
`PayloadEntityCodec` delegates every new hook, including rebinding, source mapping,
index literals and queue conversion; compression/schema framing stays outside crypto.

When encryptedColumns() is nonempty, bind the configured provider and require
codec.encryptionBound() before registration or write acceptance. An inherited
no-op binder is not encryption support. Source mappings must use that same bound
runtime codec; never substitute the static unbound CODEC/SOURCE.

## Separate Representations

| Hook | REDIS_ONLY protected field | REDIS_AND_SOURCE protected field |
| --- | --- | --- |
| `toColumns(entity)` | Original logical/SQL-compatible value | Original logical value |
| `fromLogicalColumns(map)` | Logical hydration for policy/PATCH assembly | Logical hydration, not SQL storage decode |
| `toSourceColumns(entity)` | Original SQL-compatible value | E1 ciphertext String |
| `fromColumns(sqlRow)` | Original SQL hydration | Requires E1 String, decrypts then typed decode |
| `toRedisValue` / `fromRedisValue` | E1 in existing length-prefixed fields | E1 in existing length-prefixed fields |
| `toIndexColumns(entity)` | HMAC token if blind; omitted otherwise | HMAC token if blind; omitted otherwise |
| `indexQueryValue(column, typedLiteral)` | Same HMAC token, EQ only | Same HMAC token, EQ only |
| `toQueuedColumns(entity[, resolvedAssignments])` | **E1 ciphertext, never plaintext** | E1 ciphertext |
| `sourceColumnsFromQueued(id, decodedQueueColumns)` | Authenticates, restores SQL-compatible column text | Authenticates, retains original E1 ciphertext |

Default methods preserve unencrypted legacy codecs and bytes. Do not substitute
`toColumns` for an index or any Redis persistence boundary. `entityColumnValue`
also remains a logical/business accessor, not a safe persisted-index extractor.
Source storage decoding is deliberately distinct from logical patch hydration.
On a protected codec, indexQueryValue accepts canonical physical column names only;
unknown names, Java aliases or case-mismatched names fail instead of returning a
possibly unprotected literal. Resolve names before calling the hook.

REDIS_ONLY explicitly leaves SQL/replicas/CDC/backups plaintext; it does not permit
plaintext anywhere in the Redis write-behind pipeline. REDIS_AND_SOURCE requires
compatible text columns, capacity for each actual ASCII envelope (up to 65,536 bytes),
and String-valued JDBC hydration. Numeric columns, byte[] and JDBC Clob handles
are rejected rather than stringified. Java String metadata alone does not verify
the database schema. Background flush validates actual types/capacity before SQL
mutation and rechecks each supplied value even when schema metadata is cached.
The global envelope limit is not a minimum column size. Smaller VARCHAR2 columns
are valid for envelopes that fit; CLOB-getObject mappings are not automatically
compatible. Use verified String hydration or refuse activation.
Do not silently truncate or auto-migrate a column. Source predicates against
randomized REDIS_AND_SOURCE ciphertext cannot implement plaintext EQ/range/LIKE.

## Redis Queue Contract

For every UPSERT, serialize `codec.toQueuedColumns(acceptedEntity)`, not logical
columns. For a hot PATCH, reconstruct the complete accepted state with logical columns,
apply assignments, then use `codec.toQueuedColumns(acceptedEntity, assignments)`.
Normalize logical assignments -> assemble accepted entity -> encrypt queued values
-> apply transport framing. Neither logical normalization nor queue protection may
interpret literal `__NULL__`, `cachedb:__NULL__`, `~P:N` or `~P:V` as actual null.
Only a real Java null receives transport NULL framing; protected non-null literals
must already be E1 ciphertext before PatchColumnValues.encode or function mapping.
Assignments must match that state. Sparse assignments stay sparse: ID/tenant are
not added as business DML assignments. Arithmetic commands are not assignments;
encrypted deltas are compile errors and programmatic deltas must be rejected
before arithmetic/bounds diagnostics. Resolve allowed unencrypted deltas first.

For an assignment-only cold receipt command, use
`toQueuedPatchColumns(canonicalId, verifiedTenant, normalizedAssignments)`.
It encrypts supplied fields directly with explicit ID/tenant AAD; it never
constructs an entity from an absent partial row. The repository requires an
established Redis version and verified ownership. Entity-returning PATCH still
needs the full hot row. Missing state rejects without foreground SQL bootstrap.

Queue column maps add reserved, bounded metadata:

- `$cachedb:crypto:format` = `~Q1`, required for every protected queued operation.
- `$cachedb:crypto:tenant` = `~T1:<row tenant>`, required for tenant entities even
  when PATCH omits the tenant column. Its prefix avoids legacy NULL sentinel collision.
- At most 128 assignments plus two markers. Account for these extra fields
  and retain them through stream, compaction, atomic-member, retry and DLQ encoding.
- The normal transport encoders still own `__NULL__` and PATCH `~P:N`/`~P:V` framing.
  Invoke source conversion only after RedisWriteOperationMapper decodes that framing.
- The conversion requires canonical operation id, verifies any included id/tenant
  assignment, rejects unknown columns/version, removes markers, authenticates every
  protected assignment and restores REDIS_ONLY SQL values using the existing field codec.
- For REDIS_AND_SOURCE it authenticates/validates but keeps the original ciphertext;
  do not re-encrypt during retry or change nonce/key id behind the queued operation.

Flusher ordering is mandatory: decode protected Redis envelope -> validate all
members -> convert into a **new transient SQL operation** -> JdbcWriteFence ->
business DML. No fence or partial DML before all required conversions succeed.
DELETE has no protected assignments and must not be forced through a Q1 field
conversion. Atomic envelopes convert each non-delete member before any fence.

Never mutate or replace the original protected QueuedWriteOperation retained by
the worker. Retry, compaction, DLQ, replay and audit must keep its ciphertext.
A SQLException thrown after decryption can contain SQL values: redact/wrap that
failure before the worker persists diagnostics, preserving only safe category,
SQLState/vendor code and retry policy. Blindly copying `exception.getMessage()`
or nested/suppressed causes into DLQ remains a plaintext leak.
Use `CacheFieldRedactor.redactSqlFailure(SQLException)` at this boundary: it keeps
valid five-character SQLState/vendor code and direct transient/recoverable/timeout
classification but discards messages, cause, next-exception and suppressed chains.
If retry classification depends on a wrapped root, classify bounded safe metadata
first; do not retain the original throwable merely to preserve that classification.

## AAD, Equality And NULL

AAD remains engine E1 `namespace|id|column`. For tenant entities, generated codecs
derive namespace from the row itself as `base + ":tenant:" + base64url(UTF8(tenant))`.
Tenant is nonempty, well-formed UTF-8, <=512 bytes; the combined namespace must fit
the engine's 1024-byte budget. There is no ambient tenant context lookup. Id uses
the existing scalar serialization, must be non-null/canonical and obey engine
delimiter/size limits. Validate tenant authorization, owner markers and durable
tenant fencing independently; a queue marker is not authority.

Blind tokens deliberately use the fixed entity namespace, not tenant-qualified
AAD: the two-argument query hook has no tenant parameter or hidden ThreadLocal.
Thus identical values in the same entity/ring can have equal tokens across tenants.
This leaks equality/frequency and does not provide cryptographic tenant-specific
blind-key isolation. Scope exact-index candidates and owner checks by tenant on
every path. Do not claim otherwise or hash a token twice.
Tenant-specific tokens require a query protocol with an explicit tenant argument.
AES rotation keeps the separate HMAC key stable. HMAC/ring/protocol changes need
explicit index migration, not a silent fallback to plaintext or an unbounded key scan.

Nullable protected fields preserve actual null without invoking a custom codec
on null; empty, literal JSON `null`, `__NULL__`, `~P:N`, and E1-looking text remain
distinct. Non-null custom fields reuse existing encode/decode/toColumnValue
functions; JSON data is encrypted as a whole canonical codec string. Existing
outer length-prefix format is unchanged. Duplicate known fields are rejected on
protected payload reads; unknown fields retain legacy ignore behavior.
Encrypted reads never accept legacy plaintext. Old entity values, queues, DLQ
and plaintext index memberships require an explicit migration/drain/purge plan.
A new annotation is not an in-place data migration or proof old plaintext is gone.

## Compile-Time Restrictions

`@CacheEncrypted` targets **FIELD and RECORD_COMPONENT only**, not METHOD. A
getter/accessor-only annotation is a Java compilation error, even before processing.
Record annotations propagate to backing fields, not accessors. The generated codec
protects every annotated persisted component. Annotate the record component itself.

Supported: existing scalar/enum persisted fields, records, mutable persisted-field
beans and explicitly coded non-generic named custom field types. At most 32 encrypted
and 128 total columns per protected entity; unencrypted wide entities are unchanged.
Generic/array/type-variable encrypted fields, custom ID/tenant codecs, method-only
annotations, transient/static targets, opaque named-query/projection factories,
projections of encrypted sources, and opaque @SourceSql routes are rejected.
Partial @CacheEncrypted inside a plain @CacheJsonRecord is not supported: encrypt
the complete persisted custom JSON field instead. No annotation is silently ignored.

ID, tenant, version and soft-delete columns cannot be encrypted. Non-blind protected
columns cannot be indexed, including through default wildcard indexes: declare
explicit indexedColumns excluding them. blindIndex=true auto-registers only the
equality membership. Even blind fields cannot be composite prefix/sort, partition
key/sort, relation mappedBy/order, facet group/value, aggregate value including
COUNT(field), order/seek, or non-EQ predicate. COUNT(*) with an allowed blind EQ
filter is permitted. Delta is forbidden. Runtime programmatic query/delta APIs
also require metadata validation; processors cannot inspect arbitrary Java bodies.
