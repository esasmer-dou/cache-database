package com.reactor.cachedb.spring.boot.observe;

import com.reactor.cachedb.core.observe.*;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/** Optional adapter using only the starter's existing Micrometer Observation dependency. */
public final class MicrometerCacheObservationRecorder implements CacheObservationRecorder {
    private static final RuntimeException SANITIZED_FAILURE =
            new ObservationFailure();
    private final ObservationRegistry registry;
    private final CacheObservationTagPolicy tags;
    private final LongAdder instrumentationFailures = new LongAdder();

    public MicrometerCacheObservationRecorder(ObservationRegistry registry) {
        this(registry, CacheObservationTagPolicy.defaults());
    }
    public MicrometerCacheObservationRecorder(ObservationRegistry registry, CacheObservationTagPolicy tags) {
        this.registry = Objects.requireNonNull(registry, "registry"); this.tags = Objects.requireNonNull(tags, "tags");
    }
    @Override public boolean enabled() { return !registry.isNoop(); }
    public long instrumentationFailures() { return instrumentationFailures.sum(); }
    @Override public Scope start(CacheOperation operation) {
        return enabled() ? new MicrometerScope(Objects.requireNonNull(operation, "operation")) : NoOpCacheObservationRecorder.SCOPE;
    }
    private static final class ObservationFailure extends RuntimeException {
        ObservationFailure() { super("CacheDB operation failed", null, false, false); }
    }
    private final class MicrometerScope implements Scope {
        private final ReentrantLock lock = new ReentrantLock();
        private final CacheMicrometerObservationContext context;
        private Observation observation;
        private boolean started, closed;
        private Outcome outcome;
        private Tier deepestTier;

        MicrometerScope(CacheOperation operation) {
            context = new CacheMicrometerObservationContext(operation);
            tag("db.system", "redis"); tag("cachedb.entity", "unknown"); tag("cachedb.operation", operation.tag());
        }
        private void tag(String key, String value) { context.addLowCardinalityKeyValue(KeyValue.of(key, value)); }
        @Override public void entity(String entity) {
            lock.lock(); try { if (!closed && !started) tag("cachedb.entity", tags.entity(entity)); } finally { lock.unlock(); }
        }
        @Override public void route(String route) {
            lock.lock();
            try {
                String allowed = tags.route(route);
                if (!closed && !started && allowed != null) tag("cachedb.route", allowed);
            } finally { lock.unlock(); }
        }
        @Override public void databaseSystem(String system) {
            lock.lock(); try { if (!closed) tag("db.system", CacheObservationTagPolicy.databaseSystem(system)); } finally { lock.unlock(); }
        }
        @Override public void tier(Tier tier) {
            Objects.requireNonNull(tier, "tier");
            lock.lock();
            try {
                if (!closed && (deepestTier == null || tier.ordinal() > deepestTier.ordinal())) {
                    deepestTier = tier; tag("cachedb.tier", tier.name());
                }
            } finally { lock.unlock(); }
        }
        @Override public void outcome(Outcome value) {
            Objects.requireNonNull(value, "outcome");
            lock.lock(); try { if (!closed && outcome != Outcome.ERROR) { outcome = value; tag("cachedb.outcome", value.tag()); } }
            finally { lock.unlock(); }
        }
        @Override public void outcomeIfAbsent(Outcome value) {
            Objects.requireNonNull(value, "outcome");
            lock.lock(); try { if (!closed && outcome == null) { outcome = value; tag("cachedb.outcome", value.tag()); } }
            finally { lock.unlock(); }
        }
        @Override public void error(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            lock.lock();
            try {
                if (closed || outcome == Outcome.ERROR) return;
                outcome = Outcome.ERROR; tag("cachedb.outcome", Outcome.ERROR.tag());
                // Native handlers must not export ID/payload-bearing exception messages or causes.
                if (started) observation.error(SANITIZED_FAILURE); else context.setError(SANITIZED_FAILURE);
            } catch (RuntimeException instrumentationFailure) { instrumentationFailures.increment(); }
            finally { lock.unlock(); }
        }
        @Override public void originLinks(List<CacheSpanLink> links) {
            Objects.requireNonNull(links, "links");
            if (links.size() > CacheTraceparent.MAX_LINKS) throw new IllegalArgumentException("At most 32 origin links");
            var bounded = new ArrayList<CacheSpanLink>(Math.min(links.size(), CacheTraceparent.MAX_LINKS));
            for (CacheSpanLink link : links) {
                if (bounded.size() == CacheTraceparent.MAX_LINKS) throw new IllegalArgumentException("At most 32 origin links");
                bounded.add(Objects.requireNonNull(link, "link"));
            }
            lock.lock();
            try {
                if (closed || started) throw new IllegalStateException("Set origins before binding the operation");
                if (context.operation() != CacheOperation.FLUSH_BATCH && !links.isEmpty())
                    throw new IllegalArgumentException("Origin links belong to a flush-batch observation");
                context.originLinks(bounded);
            } finally { lock.unlock(); }
        }
        private void ensureStarted() {
            if (started || closed) return;
            observation = Observation.createNotStarted("cachedb.operation", () -> context, registry);
            if (context.operation() == CacheOperation.FLUSH_BATCH) observation.parentObservation(null);
            started = true;
            try {
                observation.start();
                if (context.getError() != null) observation.error(SANITIZED_FAILURE);
            } catch (RuntimeException failure) {
                closed = true;
                try { observation.stop(); } catch (RuntimeException ignored) { instrumentationFailures.increment(); }
                throw failure;
            }
        }
        @Override public Binding makeCurrent() {
            lock.lock();
            Observation.Scope previous = registry.getCurrentObservationScope();
            try {
                if (closed) return NoOpCacheObservationRecorder.BINDING;
                ensureStarted();
                Observation.Scope nativeScope = observation.openScope();
                Thread owner = Thread.currentThread();
                return new Binding() {
                    private boolean released;
                    @Override public void close() {
                        if (released) return;
                        if (owner != Thread.currentThread()) throw new IllegalStateException("Observation binding owner mismatch");
                        released = true;
                        try { nativeScope.close(); }
                        catch (RuntimeException instrumentationFailure) {
                            instrumentationFailures.increment();
                            registry.setCurrentObservationScope(previous);
                        }
                    }
                };
            } catch (RuntimeException instrumentationFailure) {
                instrumentationFailures.increment();
                registry.setCurrentObservationScope(previous);
                boolean stop = !closed && observation != null;
                closed = true;
                if (stop) {
                    try { observation.stop(); } catch (RuntimeException ignored) { instrumentationFailures.increment(); }
                }
                return NoOpCacheObservationRecorder.BINDING;
            } finally { lock.unlock(); }
        }
        @Override public void close() {
            lock.lock();
            try {
                if (closed) return;
                if (outcome == null) { outcome = Outcome.HIT; tag("cachedb.outcome", Outcome.HIT.tag()); }
                try { ensureStarted(); } finally { closed = true; }
                observation.stop();
            } catch (RuntimeException instrumentationFailure) { instrumentationFailures.increment(); }
            finally { lock.unlock(); }
        }
    }
}
