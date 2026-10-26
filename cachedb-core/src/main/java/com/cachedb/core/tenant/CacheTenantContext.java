package com.reactor.cachedb.core.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Explicit lexical ownership; never inheritable across executor threads. */
public final class CacheTenantContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private CacheTenantContext() {}
    private record Captured(String tenant, boolean administrative) {}
    public static Optional<String> currentTenant() {
        Scope scope = CURRENT.get(); return scope == null ? Optional.empty() : Optional.ofNullable(scope.tenant);
    }
    public static boolean isAdministrative() { Scope scope = CURRENT.get(); return scope != null && scope.administrative; }
    private static String checked(String tenant) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 256) throw new IllegalArgumentException("Invalid tenant identity");
        return tenant;
    }
    public static Scope withTenant(String tenant) { return bind(new Captured(checked(tenant), false)); }
    /** Trusted maintenance entry point; never derive this permission from request data. */
    public static Scope administrative() { return bind(new Captured(null, true)); }
    private static Captured capture() { return new Captured(currentTenant().orElse(null), isAdministrative()); }
    private static Scope bind(Captured captured) {
        Scope previous = CURRENT.get();
        if (previous != null && previous.depth >= 64) throw new IllegalStateException("Tenant scope nesting exceeds 64");
        Scope scope = new Scope(captured.tenant, captured.administrative, previous); CURRENT.set(scope); return scope;
    }
    private static void restore(Scope scope) {
        scope.closed = true;
        if (scope.previous == null) CURRENT.remove(); else CURRENT.set(scope.previous);
    }
    public static void runWithTenant(String tenant, Runnable task) {
        Objects.requireNonNull(task); runCaptured(new Captured(checked(tenant), false), task);
    }
    public static <V> V callWithTenant(String tenant, Callable<V> task) throws Exception {
        Objects.requireNonNull(task);
        Scope scope = withTenant(tenant);
        try { return task.call(); }
        finally { restore(scope); }
    }
    public static Runnable wrap(Runnable task) {
        Captured captured = capture();
        Objects.requireNonNull(task);
        return () -> runCaptured(captured, task);
    }
    public static <V> Supplier<V> wrapSupplier(Supplier<V> task) {
        Captured captured = capture();
        Objects.requireNonNull(task);
        return () -> {
            Scope scope = bind(captured);
            try { return task.get(); }
            finally { restore(scope); }
        };
    }
    public static <V> Callable<V> wrapCallable(Callable<V> task) {
        Captured captured = capture();
        Objects.requireNonNull(task);
        return () -> {
            Scope scope = bind(captured);
            try { return task.call(); }
            finally { restore(scope); }
        };
    }
    private static void runCaptured(Captured captured, Runnable task) {
        Scope scope = bind(captured);
        try { task.run(); }
        finally { restore(scope); }
    }
    public static final class Scope implements AutoCloseable {
        private final String tenant;
        private final boolean administrative;
        private final Scope previous;
        private final Thread owner = Thread.currentThread();
        private final int depth;
        private boolean closed;
        private Scope(String tenant, boolean administrative, Scope previous) {
            this.tenant = tenant; this.administrative = administrative; this.previous = previous;
            depth = previous == null ? 1 : previous.depth + 1;
        }
        public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Tenant scope belongs to another thread");
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Tenant scopes must close in reverse order");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
