package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.redis.RedisNonTenantReencryptionRunner;
import java.util.UUID;

/**
 * Database-owned bound maintenance handle. Calls after database closure are rejected.
 * Closure is not a physical-I/O drain/abort guarantee: an already-sent command can still commit.
 * Row/frame/reply bounds and per-read SO timeout are not a hard total-call deadline.
 */
public final class CacheReencryptionJob {
    public enum Code {
        ADMIN_REQUIRED, CLOSED, UNKNOWN_ENTITY, AMBIGUOUS_REGISTRATION, REGISTRATION_CHANGED,
        UNSUPPORTED_MAPPING, UNSUPPORTED_TRANSPORT, INVALID_REQUEST, CAPACITY,
        SOURCE_UNSUPPORTED, BUSY, UNBOUND_PROVIDER, FAILURE
    }
    public static final class Failure extends RuntimeException {
        private final Code code;
        Failure(Code code) { super(code.name(), null, false, false); this.code = code; }
        public Code code() { return code; }
    }

    final CacheDatabaseReencryption owner;
    final EntityBinding<?, ?> binding;
    final RedisNonTenantReencryptionRunner<?, ?> runner;

    CacheReencryptionJob(CacheDatabaseReencryption owner, EntityBinding<?, ?> binding,
            RedisNonTenantReencryptionRunner<?, ?> runner) {
        this.owner = owner; this.binding = binding; this.runner = runner;
    }
    public RedisNonTenantReencryptionRunner.Step reencrypt(int maxRows) {
        return reencrypt(maxRows, RedisNonTenantReencryptionRunner.Scope.REDIS_RESIDENT);
    }
    public RedisNonTenantReencryptionRunner.Step reencrypt(int maxRows, RedisNonTenantReencryptionRunner.Scope scope) {
        owner.validate(maxRows, scope);
        return owner.execute(this, () -> runner.reencrypt(binding.metadata().entityType(), maxRows, scope));
    }
    public RedisNonTenantReencryptionRunner.Step resume(UUID passId, int maxRows) {
        owner.validate(maxRows, RedisNonTenantReencryptionRunner.Scope.REDIS_RESIDENT);
        if (passId == null) throw new Failure(Code.INVALID_REQUEST);
        return owner.execute(this, () -> runner.resume(binding.metadata().entityType(), passId, maxRows));
    }
    public void startNewPass() {
        owner.validate(1, RedisNonTenantReencryptionRunner.Scope.REDIS_RESIDENT);
        owner.execute(this, () -> { runner.startNewPass(binding.metadata().entityType()); return null; });
    }
    @Override public String toString() { return "CacheReencryptionJob[redacted]"; }
}
