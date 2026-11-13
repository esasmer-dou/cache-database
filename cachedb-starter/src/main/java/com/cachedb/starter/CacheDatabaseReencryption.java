package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.crypto.EncryptedStorageChecks;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisNonTenantReencryptionRunner;
import com.reactor.cachedb.redis.RedisRuntimeReencryptionTransport;
import com.reactor.cachedb.starter.CacheReencryptionJob.Code;
import java.util.IdentityHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Lazy, bounded, owned service. Construction performs no I/O or provider calls. */
final class CacheDatabaseReencryption implements AutoCloseable {
    private final CacheDatabase database;
    private final IdentityHashMap<Class<?>, CacheReencryptionJob> jobs = new IdentityHashMap<>();
    private final ReentrantLock creation = new ReentrantLock();
    private final Semaphore work = new Semaphore(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RedisRuntimeReencryptionTransport transport;

    CacheDatabaseReencryption(CacheDatabase database) { this.database = database; }

    CacheReencryptionJob factory(Class<?> type) {
        validate(1, RedisNonTenantReencryptionRunner.Scope.REDIS_RESIDENT);
        if (type == null) throw failure(Code.INVALID_REQUEST);
        creation.lock();
        try {
            ensureOpen();
            var binding = resolve(type);
            var existing = jobs.get(type);
            if (existing != null) {
                if (existing.binding != binding) throw failure(Code.REGISTRATION_CHANGED);
                return existing;
            }
            if (jobs.size() >= 64) throw failure(Code.CAPACITY);
            var metadata = binding.metadata();
            if (metadata.tenantFieldName() != null || metadata.tenantPurgeEnabled()
                    || metadata.encryptedColumns().isEmpty()
                    || metadata.encryptedColumns().values().stream().anyMatch(d -> d.encryptsSource()))
                throw failure(Code.UNSUPPORTED_MAPPING);
            if (database.runtimeOptions().keyProvider() == null) throw failure(Code.UNBOUND_PROVIDER);
            if (!(database.redisOperations() instanceof JedisPooledOperations) || database.redisOperations().clustered())
                throw failure(Code.UNSUPPORTED_TRANSPORT);
            EncryptedStorageChecks.requireBound(metadata, binding.codec());
            if (transport == null) transport = new RedisRuntimeReencryptionTransport(database.redisOperations());
            CacheReencryptionJob job = create(binding);
            jobs.put(type, job);
            return job;
        } catch (CacheReencryptionJob.Failure safe) { throw safe; }
        catch (RuntimeException unsafe) { throw failure(Code.UNBOUND_PROVIDER); }
        finally { creation.unlock(); }
    }

    private <T, ID> CacheReencryptionJob create(EntityBinding<T, ID> binding) {
        // No ambient route context can certify quota freedom. Defer growth and unknown reservations.
        var runner = new RedisNonTenantReencryptionRunner<>(binding.metadata(), binding.codec(),
                database.runtimeOptions().keyProvider(), database.redisOperations(), database.redisKeyStrategy(),
                transport, RedisNonTenantReencryptionRunner.QuotaPolicy.nonGrowing(java.util.Set.of()));
        return new CacheReencryptionJob(this, binding, runner);
    }

    private EntityBinding<?, ?> resolve(Class<?> type) {
        var bindings = database.entityRegistry().all();
        int limit = Math.min(10000, database.config().resourceLimits().maxRegisteredEntities());
        if (limit < 1 || bindings.size() > limit) throw failure(Code.CAPACITY);
        EntityBinding<?, ?> selected = null;
        int examined = 0;
        for (var binding : bindings) {
            if (++examined > limit) throw failure(Code.CAPACITY);
            if (binding.metadata().entityType() != type) continue;
            if (selected != null) throw failure(Code.AMBIGUOUS_REGISTRATION);
            selected = binding;
        }
        if (selected == null) throw failure(Code.UNKNOWN_ENTITY);
        String namespace = selected.metadata().redisNamespace();
        for (var binding : bindings)
            if (binding != selected && namespace.equals(binding.metadata().redisNamespace()))
                throw failure(Code.AMBIGUOUS_REGISTRATION);
        return selected;
    }

    void validate(int maxRows, RedisNonTenantReencryptionRunner.Scope scope) {
        if (!CacheTenantContext.isAdministrative()) throw failure(Code.ADMIN_REQUIRED);
        ensureOpen();
        if (maxRows < 1 || maxRows > 10000 || scope == null) throw failure(Code.INVALID_REQUEST);
        if (scope != RedisNonTenantReencryptionRunner.Scope.REDIS_RESIDENT) throw failure(Code.SOURCE_UNSUPPORTED);
    }

    <V> V execute(CacheReencryptionJob job, Supplier<V> action) {
        validate(1, RedisNonTenantReencryptionRunner.Scope.REDIS_RESIDENT);
        if (!work.tryAcquire()) throw failure(Code.BUSY);
        try {
            ensureOpen();
            if (job.owner != this || resolve(job.binding.metadata().entityType()) != job.binding)
                throw failure(Code.REGISTRATION_CHANGED);
            V result = action.get();
            ensureOpen();
            return result;
        } catch (CacheReencryptionJob.Failure | RedisNonTenantReencryptionRunner.Failure safe) { throw safe; }
        catch (RuntimeException unsafe) { throw failure(Code.FAILURE); }
        finally { work.release(); }
    }

    private void ensureOpen() { if (closed.get()) throw failure(Code.CLOSED); }
    private static CacheReencryptionJob.Failure failure(Code code) { return new CacheReencryptionJob.Failure(code); }

    @Override public void close() {
        // Stop admission, not physical drain. An active job retains its permit until execute exits.
        closed.set(true);
        creation.lock();
        try {
            if (transport != null) transport.close();
            jobs.clear();
        } finally { creation.unlock(); }
    }
}
