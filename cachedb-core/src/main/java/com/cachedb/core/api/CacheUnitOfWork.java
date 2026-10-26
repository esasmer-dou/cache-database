package com.reactor.cachedb.core.api;

import java.util.*;

/** Explicit deferred commands: no fabricated Redis-acceptance receipt before commit. */
public final class CacheUnitOfWork implements AutoCloseable {
    private static final ThreadLocal<CacheUnitOfWork> CURRENT = new ThreadLocal<>();
    private final Object owner;
    private final CacheWriteBatch batch;
    private boolean rollbackOnly;
    private boolean closed;
    private CacheUnitOfWork(Object owner, CacheWriteBatch batch) { this.owner = owner; this.batch = batch; }

    public static CacheUnitOfWork begin(Object owner, CacheWriteBatch batch) {
        Objects.requireNonNull(owner); Objects.requireNonNull(batch);
        if (CURRENT.get() != null) {
            CURRENT.get().rollbackOnly = true;
            throw new IllegalStateException("Nested CacheDB units of work are not supported");
        }
        var work = new CacheUnitOfWork(owner, batch); CURRENT.set(work); return work;
    }
    public static CacheUnitOfWork current() {
        var work = CURRENT.get();
        if (work == null || work.closed) throw new IllegalStateException("No active synchronous CacheDB unit of work");
        return work;
    }
    public static boolean active() { return CURRENT.get() != null; }
    public void requireOwner(Object expected) {
        if (owner != expected) { rollbackOnly = true; throw new IllegalStateException("Cross-database unit of work is not supported"); }
    }
    private void check() { if (closed || CURRENT.get() != this) throw new IllegalStateException("Unit of work is not active on this thread"); }
    public <T> CacheUnitOfWork put(T entity) { check(); try { batch.put(entity); return this; } catch (RuntimeException failure) { rollbackOnly = true; throw failure; } }
    public <T> CacheUnitOfWork put(T entity, long expectedVersion) { check(); try { batch.put(entity, expectedVersion); return this; } catch (RuntimeException failure) { rollbackOnly = true; throw failure; } }
    public <T> CacheUnitOfWork putAll(Collection<T> entities) {
        check();
        try {
            if (Objects.requireNonNull(entities).size() > 100) throw new IllegalArgumentException("Unit of work exceeds 100 operations");
            for (T entity : entities) put(entity); return this;
        } catch (RuntimeException failure) { rollbackOnly = true; throw failure; }
    }
    public <T, ID> CacheUnitOfWork patch(Class<T> type, ID id, Map<String, Object> updates) { check(); try { batch.patch(type, id, updates); return this; } catch (RuntimeException failure) { rollbackOnly = true; throw failure; } }
    public <T, ID> CacheUnitOfWork delete(Class<T> type, ID id) { check(); try { batch.delete(type, id); return this; } catch (RuntimeException failure) { rollbackOnly = true; throw failure; } }
    public void setRollbackOnly() { check(); rollbackOnly = true; }
    public CacheWriteBatchReceipt commit() {
        check(); if (rollbackOnly) throw new IllegalStateException("Unit of work was marked rollback-only");
        close();
        return batch.commit();
    }
    public static void rejectImmediateReceipt() {
        var work = CURRENT.get();
        if (work != null) {
            work.rollbackOnly = true;
            throw new IllegalStateException("A Redis acceptance receipt cannot exist before commit; use tx.put / tx.patch / tx.delete");
        }
    }
    @Override public void close() { if (!closed) { check(); closed = true; CURRENT.remove(); } }
}
