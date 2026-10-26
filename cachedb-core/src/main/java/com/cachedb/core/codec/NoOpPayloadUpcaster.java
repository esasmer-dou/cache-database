package com.reactor.cachedb.core.codec;

public final class NoOpPayloadUpcaster implements CachePayloadUpcaster {
    public static final NoOpPayloadUpcaster INSTANCE = new NoOpPayloadUpcaster();
    public NoOpPayloadUpcaster() {}
    public int targetVersion() { return 1; }
    public String upcast(String rawPayload, int fromVersion, int toVersion) {
        if (fromVersion != toVersion) throw new IllegalArgumentException("A schema upcaster is required");
        return rawPayload;
    }
}
