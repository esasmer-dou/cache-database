package com.reactor.cachedb.core.crypto;

/** Core storage/query contract; intentionally independent of SOURCE-retained annotations. */
public record EncryptedColumnDescriptor(String keyRing, Scope scope, boolean blindIndex) {
    public enum Scope { REDIS_ONLY, REDIS_AND_SOURCE }

    public EncryptedColumnDescriptor {
        CryptoSupport.name(keyRing);
        if (scope == null) throw new CacheEncryptionException(CacheEncryptionException.Code.INVALID_CONFIGURATION);
    }

    public boolean encryptsSource() { return scope == Scope.REDIS_AND_SOURCE; }
    @Override public String toString() { return "EncryptedColumnDescriptor[redacted]"; }
}
