package com.reactor.cachedb.core.observe;

import java.util.Objects;

/** Explicit propagation of one operation; closing a binding never closes that operation. */
public final class CacheObservationContext {
    private static final ThreadLocal<Attachment> CURRENT = new ThreadLocal<>();
    private static final Snapshot EMPTY = new Snapshot(NoOpCacheObservationRecorder.SCOPE, null);
    private CacheObservationContext() {}

    public static CacheObservationRecorder.Scope current() {
        Attachment current = CURRENT.get();
        return current == null ? NoOpCacheObservationRecorder.SCOPE : current.scope;
    }
    public static void tier(Tier tier) { current().tier(Objects.requireNonNull(tier, "tier")); }
    public static void outcome(Outcome outcome) { current().outcome(Objects.requireNonNull(outcome, "outcome")); }
    public static Snapshot capture() {
        Attachment current = CURRENT.get();
        return current == null ? EMPTY : new Snapshot(current.scope, current.operationOwner);
    }
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Snapshot captured = capture();
        return () -> { try (var binding = captured.restore()) { task.run(); } };
    }
    public static boolean isOwnedBy(Object operationOwner) {
        Attachment current = CURRENT.get();
        return operationOwner != null && current != null && current.operationOwner == operationOwner;
    }
    public static CacheObservationRecorder.Binding attach(CacheObservationRecorder.Scope scope) {
        return attach(scope, null);
    }
    public static CacheObservationRecorder.Binding attach(CacheObservationRecorder.Scope scope, Object operationOwner) {
        Objects.requireNonNull(scope, "scope");
        Attachment previous = CURRENT.get();
        if (previous == null && scope == NoOpCacheObservationRecorder.SCOPE && operationOwner == null)
            return NoOpCacheObservationRecorder.BINDING;
        if (previous != null && previous.depth >= 64)
            throw new IllegalStateException("Observation context nesting exceeds 64");
        CacheObservationRecorder.Binding nativeBinding;
        try { nativeBinding = Objects.requireNonNull(scope.makeCurrent(), "binding"); }
        catch (RuntimeException instrumentationFailure) { nativeBinding = NoOpCacheObservationRecorder.BINDING; }
        var attachment = new Attachment(scope, operationOwner, previous, nativeBinding);
        CURRENT.set(attachment);
        return attachment;
    }
    public static final class Snapshot {
        private final CacheObservationRecorder.Scope scope;
        private final Object operationOwner;
        private Snapshot(CacheObservationRecorder.Scope scope, Object operationOwner) {
            this.scope = scope; this.operationOwner = operationOwner;
        }
        /** An empty capture masks the worker's ambient CacheDB observation too. */
        public CacheObservationRecorder.Binding restore() { return attach(scope, operationOwner); }
    }
    private static final class Attachment implements CacheObservationRecorder.Binding {
        private final CacheObservationRecorder.Scope scope;
        private final Attachment previous;
        private final CacheObservationRecorder.Binding nativeBinding;
        private final Thread owner = Thread.currentThread();
        private final int depth;
        private boolean closed;
        private final Object operationOwner;
        Attachment(CacheObservationRecorder.Scope scope, Object operationOwner, Attachment previous, CacheObservationRecorder.Binding nativeBinding) {
            this.scope = scope; this.operationOwner = operationOwner;
            this.previous = previous; this.nativeBinding = nativeBinding;
            this.depth = previous == null ? 1 : previous.depth + 1;
        }
        @Override public void close() {
            if (closed) return;
            if (owner != Thread.currentThread() || CURRENT.get() != this)
                throw new IllegalStateException("Observation bindings must close on their owner in reverse order");
            closed = true;
            try { nativeBinding.close(); }
            catch (RuntimeException instrumentationFailure) { /* Telemetry must not change the operation result. */ }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }
}
