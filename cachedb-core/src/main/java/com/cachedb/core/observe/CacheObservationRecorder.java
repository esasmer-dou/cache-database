package com.reactor.cachedb.core.observe;

import java.util.List;

/** Optional, non-throwing instrumentation. Implementations must not retain entity data. */
public interface CacheObservationRecorder {
    default boolean enabled() { return true; }
    Scope start(CacheOperation operation);

    interface Scope extends AutoCloseable {
        void tier(Tier tier);
        void outcome(Outcome outcome);
        default void outcomeIfAbsent(Outcome outcome) { outcome(outcome); }
        void error(Throwable failure);
        default void entity(String schemaEntity) {}
        default void route(String declaredRoute) {}
        default void databaseSystem(String system) {}
        /** Set before the first makeCurrent; origins are links, never remote parents. */
        default void originLinks(List<CacheSpanLink> links) {}
        /** A thread-owned lexical binding, distinct from the operation lifetime. */
        default Binding makeCurrent() { return NoOpCacheObservationRecorder.BINDING; }
        @Override void close();
    }
    @FunctionalInterface
    interface Binding extends AutoCloseable { @Override void close(); }
}
