# Bounded Field Encryption

## Scope And Storage

CacheDB encrypts explicitly annotated persisted fields with the JCA engine in
`com.reactor.cachedb.core.crypto`. Use generated codecs with an explicitly bound
key provider; the annotation is not a runtime interceptor. Field encryption does
not replace authorization, retention policy, secure deletion or durable protection
against concurrent purge/write operations.

```java
import com.reactor.cachedb.annotations.CacheEncrypted;
import com.reactor.cachedb.annotations.EncryptionScope;

@CacheEncrypted(keyRing = "default", scope = EncryptionScope.REDIS_ONLY, blindIndex = true)
String contactAddress;
```

Annotations have SOURCE retention. `@CacheEncrypted` supports persisted fields and
record components, not getters/accessors. Unsupported placement fails compilation;
generated bindings do not use runtime entity reflection.

| Scope | Redis persisted representation | SQL persisted representation |
| --- | --- | --- |
| `REDIS_ONLY` (default) | E1 ciphertext | Original plaintext column value |
| `REDIS_AND_SOURCE` | E1 ciphertext | E1 ciphertext; compatible text schema required |

Both scopes protect annotated values in Redis entity payloads, queued writes,
compaction snapshots, retries and dead-letter records. REDIS_ONLY restores the
original column value only in transient SQL operations; it must not replace the
protected queued operation. REDIS_ONLY does not encrypt SQL replicas, logs, CDC,
exports or backups. REDIS_AND_SOURCE fails closed when keys or compatible source
schema are unavailable; it never falls back to plaintext.
See [encrypted codecs and queued writes](encrypted-codecs.md) for the storage,
null, indexing and source-column contracts.

## API And Protocol

Core package: `com.reactor.cachedb.core.crypto`.

```java
CacheKeyProvider keys = /* explicitly supplied, bounded provider */;
JcaFieldEncryption crypto = new JcaFieldEncryption(keys, 64);
String stored = crypto.encrypt("default", "tenant-a:orders", "42", "contact", serializedValue);
String restored = crypto.decrypt("default", "tenant-a:orders", "42", "contact", stored);
String equalityToken = crypto.blindIndex("default", "tenant-a:orders", "contact", serializedValue);
```

Use `FieldValueCodec.encode` or the generated equivalent for a canonical field
string before encryption and indexing. Reverse it only after authenticated
decryption. No hidden case folding, Unicode normalization or type coercion occurs.

- Cipher is JCA `AES/GCM/NoPadding`, 256-bit AES key, fresh 12-byte random nonce
  from `SecureRandom`, 128-bit authentication tag. AAD is supplied before `doFinal`.
- Envelope is exactly `~E1:<keyId>:<base64url(nonce || ciphertext || tag)>`.
  Base64url is unpadded and canonical; whitespace, `=`, standard Base64 `+`/`/`,
  nonzero unused tail bits, extra delimiters and future versions are rejected.
- Key-ring names and key ids are case-sensitive ASCII
  `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. They must be opaque non-PII identifiers.
  No colons, pipes, whitespace, Unicode or caller-provided secrets in names.
- AAD is the exact UTF-8 bytes of `namespace + "|" + id + "|" + column`.
  Namespace/id are each at most 1024 UTF-8 bytes; column at most 128 bytes.
  Components must be nonempty, well-formed Unicode, with no `|`, C0 controls or DEL.
  Reject delimiter-bearing components rather than joining ambiguous tuples.
- Namespace must already be a canonical, collision-free **tenant-bound** namespace
  if ids can repeat between tenants. Passing a shared namespace plus a tenant-local
  id does not provide cross-tenant cryptographic isolation. Use the same tenant
  binding on every read, write, asynchronous and source-storage path.
- Null values remain null, including blind tokens. Empty strings are encrypted and
  authenticated. Schema and row validation must enforce null/missing-field integrity;
  GCM does not authenticate an absent field.

The **64 KiB (65,536-byte) stored field limit includes the ASCII header, nonce,
ciphertext, tag and Base64 expansion**. Thus 64 KiB plaintext does not fit. The
largest plaintext is `JcaFieldEncryption.maxPlaintextBytes(keyId)` UTF-8 bytes:
49,119 bytes for a one-character key id; 49,072 bytes for a 64-character key id.
Use the smaller figure as a stable application limit across key-id rotations.
The outer entity/payload limits remain independently applicable. All input text
has an additional 64 KiB pre-encoding cap; malformed UTF-16 and authenticated
non-UTF-8 ciphertext are rejected rather than replacement-decoded. Blind-index
input has a 64 KiB UTF-8 cap and tokens are always 47 ASCII bytes.

Do not auto-detect encrypted fields from their value prefix. Unannotated fields
must retain their legacy codec unchanged, even for text beginning `~E1:`.
For an explicitly encrypted field, every non-null read must be a valid E1 envelope:
legacy plaintext, unknown keys, malformed envelopes and failed authentication
throw. There is no plaintext fallback and no automatic plaintext migration.
Encrypting an envelope-looking plaintext string encrypts it normally; `encrypt`
is not idempotent and must not be applied twice to an already mapped field.

## Key Providers And Rotation

`CacheKeyProvider.current(keyRing)` returns immutable `KeyMaterial` for every
write; `byId(keyRing, keyId)` returns exactly that version or `Optional.empty()`.
The engine never retries a lookup, scans keys, falls back to the current key on
read, or retains an unbounded provider-side key cache. `KeyMaterial` requires two
different 32-byte keys and defensively copies them. It intentionally exposes no
public raw-key accessor and redacts `toString`. KMS/Vault providers must supply
local DEK bytes; remote per-field encryption and non-exportable HSM key handles
are outside this byte-material SPI.

Dev/test only, explicit config/env loading (there are no default keys):

```java
KeyMaterial oldKey = KeyMaterial.fromBase64("key-1",
        System.getenv("CACHEDB_AES_KEY_1"), System.getenv("CACHEDB_BLIND_KEY"));
KeyMaterial newKey = KeyMaterial.fromBase64("key-2",
        System.getenv("CACHEDB_AES_KEY_2"), System.getenv("CACHEDB_BLIND_KEY"));
CacheKeyProvider dev = new StaticKeyProvider(
        Map.of("default", List.of(oldKey, newKey)), Map.of("default", "key-2"));
```

Imports omitted: `java.util.List`, `java.util.Map`, and `core.crypto` types above.
Config keys use canonical padded **standard** Base64: exactly 44 characters for
32 decoded bytes. This differs deliberately from envelope Base64url. Supply
independent random secrets through approved secret tooling; never passwords,
checked-in keys or a derived copy of the AES key. Environment strings and Java
Strings cannot be reliably zeroized. `StaticKeyProvider` is an immutable dev/test
helper, not a production secret lifecycle solution.

It permits at most 16 rings, 32 versions/ring, 256 total keys. Invalid current
ids, duplicates, AES aliases (also across rings), using any AES key as any blind
key, and different HMAC keys among versions of the same ring are rejected at
construction. Config collections are copied. Lookup is in-memory O(1).

Production KMS/Vault adapter sketch (not a complete secret manager):

```java
final class VaultBackedKeys implements CacheKeyProvider {
    private final AtomicReference<CacheKeyProvider> active;
    VaultBackedKeys(CacheKeyProvider validatedBoundedSnapshot) {
        active = new AtomicReference<>(Objects.requireNonNull(validatedBoundedSnapshot));
    }
    public KeyMaterial current(String ring) { return active.get().current(ring); }
    public Optional<KeyMaterial> byId(String ring, String id) {
        return active.get().byId(ring, id);
    }
    void publish(CacheKeyProvider validatedBoundedSnapshot) {
        active.set(Objects.requireNonNull(validatedBoundedSnapshot));
    }
}
```

Imports: `java.util.Optional`, `java.util.Objects`, `java.util.concurrent.atomic.AtomicReference`.
The application implements the validated immutable snapshot with the same hard
limits and separation rules. A single bounded refresh task fetches wrapped DEKs,
unwraps them through the approved KMS/Vault SDK, validates the full snapshot,
then atomically publishes it. No secret-manager dependency is added to core.
Use bounded remote timeouts, bounded jittered retries only on the control plane,
expiry/readiness policy and redacted failure counters. A stalled provider is not
interrupted by this engine; it still holds a crypto permit, so request-time
remote lookup is an anti-pattern. Do not keep expired or revoked material
indefinitely merely to avoid errors. Never change bytes behind an existing id.

Rotation procedure: publish old+new versions to all readers, switch current to
new for writes, keep old versions readable, then run a bounded, resumable
re-encryption job. Remove old keys only after checking all Redis/SQL
stores selected by scope, write-behind/DLQ/replay data and backup recovery policy.
A random 96-bit nonce is probabilistically unique, not a global durable counter.
Enforce cryptoperiod and the applicable GCM per-key invocation budget across all
pods/restarts (conservative ceiling: 2^32 encryptions/key), with operational
headroom. The engine does not enforce a cluster-global key-use counter.

## Blind Index

Token is `~B1:` plus unpadded Base64url of the full HMAC-SHA256 digest under the
separate HMAC key. MAC input is the UTF-8 bytes of
`CacheDB:blind-index:v1|<ring>|<namespace>|<column>|<canonical field value>`.
Row id is deliberately absent for equality lookup. The value is the last
component and may contain pipes; all earlier components are strict as above.
AES key id is absent so an AES rotation with a stable HMAC key preserves tokens.
A blind-key rotation requires an explicit token/index migration and query plan;
merely changing it makes old equality memberships unreachable.

Blind tokens leak equality/frequency; they are sensitive, not anonymized data.
Only EQ is supported. Range, ordering, facet, aggregate, composite, partition and
tenant/id uses of encrypted columns are rejected, including plaintext index shortcuts. For equality lookup,
generate the same token for the literal and persist only the token in index meta.

## Errors And Resource Limits

Engine exceptions contain only a fixed `CacheEncryptionException.Code`; no
plaintext, ciphertext, key bytes, ids, context values or provider/JCA cause.
Codes distinguish malformed input, unknown key, bad authentication, invalid
configuration, provider/crypto failure, field limit and saturation. Count by code
and operation only; never use data, ids, tenant names or key ids as metric tags.
The engine does not add logging/metrics dependencies or emit log messages.
Do not blindly retry invalid input, unknown keys or authentication failures.

Concurrency is bounded per engine: 64 active operations by default, configurable
1..256. `tryAcquire` rejects immediately with SATURATED, without queueing,
sleeping or retrying; callers must apply bounded admission/backpressure. Share
one engine per configured protection boundary instead of constructing one per
field/request to bypass the bound. Cipher/Mac instances are per operation, not
shared or cached per virtual thread. There is no executor, ThreadLocal or
monitor-held I/O. Temporary plaintext byte arrays are cleared in finally blocks;
Java Strings and JCA provider-internal buffers cannot be guaranteed erased.

Protocol references: [JDK 21 Cipher](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html),
[GCMParameterSpec](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html),
[NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final).
