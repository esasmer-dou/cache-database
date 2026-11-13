package com.reactor.cachedb.core.crypto;

import java.util.Optional;

/**
 * Thread-safe key snapshots. Writes use current; reads resolve exactly the envelope id.
 * Implementations must bound key count, lookup latency and refresh work, and must not log inputs.
 * Prefer a bounded, preloaded KMS/Vault-backed local snapshot over request-time remote calls.
 * Key ids are immutable: never change bytes behind an existing id or alias encryption keys.
 * All versions in a ring must share a separate, stable HMAC key until an explicit index migration.
 */
public interface CacheKeyProvider {
    KeyMaterial current(String keyRing);
    Optional<KeyMaterial> byId(String keyRing, String keyId);
}
