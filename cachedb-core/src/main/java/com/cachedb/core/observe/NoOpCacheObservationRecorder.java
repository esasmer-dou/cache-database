package com.reactor.cachedb.core.observe;

public final class NoOpCacheObservationRecorder implements CacheObservationRecorder {
    public static final NoOpCacheObservationRecorder INSTANCE = new NoOpCacheObservationRecorder();
    public static final Binding BINDING = () -> {};
    public static final Scope SCOPE = new Scope() {
        public void tier(Tier tier) {}
        public void outcome(Outcome outcome) {}
        public void error(Throwable failure) {}
        public void close() {}
    };
    private NoOpCacheObservationRecorder() {}
    @Override public boolean enabled() { return false; }
    @Override public Scope start(CacheOperation operation) { return SCOPE; }
}
