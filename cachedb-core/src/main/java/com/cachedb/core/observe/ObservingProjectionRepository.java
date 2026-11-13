package com.reactor.cachedb.core.observe;

import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.query.*;
import java.util.*;

public final class ObservingProjectionRepository<P, ID> implements ProjectionRepository<P, ID> {
    private final ProjectionRepository<P, ID> delegate;
    private final String entity;
    private final CacheObservationRecorder recorder;
    private ObservingProjectionRepository(ProjectionRepository<P, ID> delegate, String entity, CacheObservationRecorder recorder) {
        this.delegate = delegate; this.entity = Objects.requireNonNull(entity, "entity"); this.recorder = recorder;
    }
    public static <P, ID> ProjectionRepository<P, ID> wrap(ProjectionRepository<P, ID> delegate, String entity, CacheObservationRecorder recorder) {
        Objects.requireNonNull(delegate, "delegate"); Objects.requireNonNull(recorder, "recorder");
        if (!recorder.enabled()) return delegate;
        if (delegate instanceof ObservingProjectionRepository<?, ?> observed) {
            if (observed.recorder != recorder || !observed.entity.equals(entity))
                throw new IllegalArgumentException("Projection already has a different observation boundary");
            return delegate;
        }
        return new ObservingProjectionRepository<>(delegate, entity, recorder);
    }
    private <V> V call(CacheOperation operation, java.util.function.Supplier<V> action) {
        return CacheObservations.call(recorder, operation, entity, action);
    }
    @Override public Optional<P> findById(ID id) { return call(CacheOperation.FIND_BY_ID, () -> delegate.findById(id)); }
    @Override public List<P> findAll(Collection<ID> ids) { return call(CacheOperation.FIND_ALL, () -> delegate.findAll(ids)); }
    @Override public List<P> query(QuerySpec spec) { return call(CacheOperation.QUERY, () -> delegate.query(spec)); }
    @Override public List<P> query(QueryNode node) { return call(CacheOperation.QUERY, () -> delegate.query(node)); }
    @Override public List<P> query(QueryNode node, QuerySort... sorts) { return call(CacheOperation.QUERY, () -> delegate.query(node, sorts)); }
    @Override public List<P> query(QueryNode node, int limit, QuerySort... sorts) { return call(CacheOperation.QUERY, () -> delegate.query(node, limit, sorts)); }
}
