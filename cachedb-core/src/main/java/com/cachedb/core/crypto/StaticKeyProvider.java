package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INVALID_CONFIGURATION;
import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.UNKNOWN_KEY;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded immutable dev/test provider. No env discovery, network calls, refreshes or default keys. */
public final class StaticKeyProvider implements CacheKeyProvider {
    public static final int MAX_RINGS = 16;
    public static final int MAX_KEYS_PER_RING = 32;
    public static final int MAX_KEYS = 256;

    private final Map<String, Map<String, KeyMaterial>> rings;
    private final Map<String, KeyMaterial> current;

    public StaticKeyProvider(Map<String, List<KeyMaterial>> keyRings, Map<String, String> currentKeyIds) {
        if (keyRings == null || currentKeyIds == null || keyRings.isEmpty()
                || keyRings.size() > MAX_RINGS || currentKeyIds.size() != keyRings.size()
                || !currentKeyIds.keySet().equals(keyRings.keySet())) {
            throw invalid();
        }
        Map<String, Map<String, KeyMaterial>> ringCopy = new HashMap<>();
        Map<String, KeyMaterial> currentCopy = new HashMap<>();
        List<KeyMaterial> allKeys = new ArrayList<>();
        for (var entry : keyRings.entrySet()) {
            CryptoSupport.name(entry.getKey());
            List<KeyMaterial> keys = entry.getValue();
            if (keys == null || keys.isEmpty() || keys.size() > MAX_KEYS_PER_RING
                    || allKeys.size() + keys.size() > MAX_KEYS) throw invalid();
            Map<String, KeyMaterial> byId = new HashMap<>();
            KeyMaterial first = null;
            for (KeyMaterial key : keys) {
                if (key == null || byId.putIfAbsent(key.keyId(), key) != null) throw invalid();
                if (first == null) first = key;
                else if (!first.sameBlindIndexKey(key)) throw invalid();
                for (KeyMaterial prior : allKeys) {
                    if (key.sameEncryptionKey(prior) || key.encryptionMatchesBlindIndex(prior)
                            || prior.encryptionMatchesBlindIndex(key)) throw invalid();
                }
                allKeys.add(key);
            }
            String currentId = currentKeyIds.get(entry.getKey());
            CryptoSupport.name(currentId);
            KeyMaterial active = byId.get(currentId);
            if (active == null) throw invalid();
            ringCopy.put(entry.getKey(), Map.copyOf(byId));
            currentCopy.put(entry.getKey(), active);
        }
        rings = Map.copyOf(ringCopy);
        current = Map.copyOf(currentCopy);
    }

    @Override public KeyMaterial current(String keyRing) {
        CryptoSupport.name(keyRing);
        KeyMaterial key = current.get(keyRing);
        if (key == null) throw new CacheEncryptionException(UNKNOWN_KEY);
        return key;
    }

    @Override public Optional<KeyMaterial> byId(String keyRing, String keyId) {
        CryptoSupport.name(keyRing);
        CryptoSupport.name(keyId);
        Map<String, KeyMaterial> keys = rings.get(keyRing);
        return keys == null ? Optional.empty() : Optional.ofNullable(keys.get(keyId));
    }

    private static CacheEncryptionException invalid() { return new CacheEncryptionException(INVALID_CONFIGURATION); }
    @Override public String toString() { return "StaticKeyProvider[redacted]"; }
}
