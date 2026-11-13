package com.reactor.cachedb.core.observe;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class RecordingCacheObservationRecorder implements CacheObservationRecorder {
    final List<RecordedScope> scopes = new CopyOnWriteArrayList<>();
    public Scope start(CacheOperation operation) {
        var scope = new RecordedScope(operation); scopes.add(scope); return scope;
    }
    RecordedScope last() { return scopes.get(scopes.size() - 1); }
    static final class RecordedScope implements Scope {
        final CacheOperation operation;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();
        volatile Tier tier;
        volatile Outcome outcome;
        volatile Throwable failure;
        String entity;
        RecordedScope(CacheOperation operation) { this.operation = operation; }
        public void entity(String value) { entity = value; }
        public void tier(Tier value) { if (!closed.get()) tier = value; }
        public void outcome(Outcome value) { if (!closed.get()) outcome = value; }
        public void outcomeIfAbsent(Outcome value) { if (!closed.get() && outcome == null) outcome = value; }
        public void error(Throwable failure) { this.failure = failure; outcome = Outcome.ERROR; }
        public void close() { if (closed.compareAndSet(false, true)) closes.incrementAndGet(); }
    }
}
