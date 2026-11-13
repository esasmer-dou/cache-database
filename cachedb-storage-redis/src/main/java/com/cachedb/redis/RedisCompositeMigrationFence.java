package com.reactor.cachedb.redis;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Opt-in maintenance protocol: all upgraded writers must cooperate; no expiring safety lease. */
final class RedisCompositeMigrationFence {
    static final int MAX_ACTIVE_WRITERS = 10_000;
    static final String LONG53_VERIFIED = "long53-v1:verified";
    static final String LONG53_UNSAFE = "long53-v1:unsafe";
    private final RedisClientOperations redis;
    private final String lock, writers, degraded, signature;
    private final List<String> identityKeys;
    private final List<String> long53Keys;
    private final ThreadLocal<Migration> migrating = new ThreadLocal<>();
    private final ThreadLocal<Writer> local = new ThreadLocal<>();
    RedisCompositeMigrationFence(RedisClientOperations redis, RedisKeyStrategy keys, String namespace) {
        this(redis, keys, namespace, List.of());
    }
    RedisCompositeMigrationFence(RedisClientOperations redis, RedisKeyStrategy keys, String namespace, List<String> long53Keys) {
        if (long53Keys.size() > 32) throw new IllegalArgumentException("At most 32 Long53 domain flags");
        this.long53Keys = List.copyOf(long53Keys);
        this.redis = redis;
        String base = keys.indexRebuildLockKey(namespace) + ":composite-migration";
        lock = base + ":lock"; writers = base + ":writers"; degraded = keys.indexDegradedKey(namespace);
        signature = keys.compositeIndexSignatureKey(namespace);
        identityKeys = List.of(lock, writers);
    }
    List<String> identityKeys() { return identityKeys; }
    boolean reserved(String key) { return lock.equals(key) || writers.equals(key) || long53Keys.contains(key); }

    void attestLong53(int unsafeMask) {
        if (long53Keys.isEmpty()) return;
        Writer writer = local.get();
        if (writer != null) { writer.unsafeMask |= unsafeMask; return; }
        Migration migration = migrating.get();
        if (migration != null) { migration.unsafeMask |= unsafeMask; return; }
        throw new IllegalStateException("Long53 attestation requires an active cooperative writer or fenced rebuild");
    }

    <V> V write(Supplier<V> action) {
        Writer current = local.get();
        if (current != null) return action.get();
        String token = UUID.randomUUID().toString();
        redis.requireSameSlot(List.of(lock, writers));
        redis.eval("""
                local lt=redis.call('TYPE',KEYS[1]).ok
                local wt=redis.call('TYPE',KEYS[2]).ok
                if (lt~='none' and lt~='string') or (wt~='none' and wt~='hash') then
                  return redis.error_reply('Invalid composite fence key type') end
                if redis.call('EXISTS',KEYS[1])==1 then return redis.error_reply('Composite migration blocks writes') end
                if redis.call('HLEN',KEYS[2])>=tonumber(ARGV[2]) then return redis.error_reply('Composite active-writer bound exceeded') end
                redis.call('HSET',KEYS[2],ARGV[1],'1'); return 1
                """, identityKeys, List.of(token, Integer.toString(MAX_ACTIVE_WRITERS)));
        var writer = new Writer();
        local.set(writer);
        Throwable original = null;
        try { return action.get(); }
        catch (RuntimeException | Error failure) {
            original = failure;
            try { redis.commands().set(degraded, "composite-writer-failed"); }
            catch (RuntimeException markFailure) { failure.addSuppressed(markFailure); }
            throw failure;
        } finally {
            local.remove();
            try {
                if (writer.unsafeMask == 0) {
                    redis.eval("return redis.call('HDEL',KEYS[1],ARGV[1])", List.of(writers), List.of(token));
                } else {
                    var keys = new java.util.ArrayList<String>(); keys.add(writers);
                    for (int i = 0; i < long53Keys.size(); i++)
                        if ((writer.unsafeMask & (1 << i)) != 0) keys.add(long53Keys.get(i));
                    // The active writer remains visible until poisoning and release complete atomically.
                    redis.eval("""
                            if redis.call('TYPE',KEYS[1]).ok~='hash' or redis.call('HEXISTS',KEYS[1],ARGV[1])~=1 then
                              return redis.error_reply('Composite writer ownership lost') end
                            for i=2,#KEYS do
                              local t=redis.call('TYPE',KEYS[i]).ok
                              if t~='none' and t~='string' then return redis.error_reply('Invalid Long53 domain flag type') end
                            end
                            for i=2,#KEYS do redis.call('SET',KEYS[i],ARGV[2]) end
                            return redis.call('HDEL',KEYS[1],ARGV[1])
                            """, keys, List.of(token, LONG53_UNSAFE));
                }
            }
            catch (RuntimeException releaseFailure) {
                if (original != null) original.addSuppressed(releaseFailure); else throw releaseFailure;
            }
        }
    }
    private static final class Writer { int unsafeMask; }

    Migration exclusive() {
        if (local.get() != null || migrating.get() != null) throw new IllegalStateException("Cannot rebuild inside an active composite mutation");
        String token = "composite-migration:" + UUID.randomUUID();
        redis.requireSameSlot(List.of(lock, writers, degraded, signature));
        redis.eval("""
                for i=1,4 do
                  local t=redis.call('TYPE',KEYS[i]).ok
                  local expected=i==2 and 'hash' or 'string'
                  if t~='none' and t~=expected then return redis.error_reply('Invalid composite fence key type') end
                end
                if redis.call('EXISTS',KEYS[1])==1 or redis.call('HLEN',KEYS[2])~=0 then
                  return redis.error_reply('Composite migration requires no active writers') end
                redis.call('SET',KEYS[1],ARGV[1])
                redis.call('SET',KEYS[3],ARGV[1])
                redis.call('SET',KEYS[4],ARGV[1]); return 1
                """, List.of(lock, writers, degraded, signature), List.of(token));
        var migration = new Migration(token); migrating.set(migration); return migration;
    }

    final class Migration implements AutoCloseable {
        private final String token;
        private final Thread owner = Thread.currentThread();
        private boolean closed, published;
        private int unsafeMask;
        Migration(String token) { this.token = token; }
        void publish(String signatureKey, String signature) {
            if (closed || owner != Thread.currentThread()) throw new IllegalStateException("Migration owner mismatch");
            var keys = new java.util.ArrayList<>(List.of(lock, writers, degraded, signatureKey));
            keys.addAll(long53Keys);
            var args = new java.util.ArrayList<>(List.of(token, signature));
            for (int i = 0; i < long53Keys.size(); i++)
                args.add((unsafeMask & (1 << i)) == 0 ? LONG53_VERIFIED : LONG53_UNSAFE);
            redis.requireSameSlot(keys);
            Object result = redis.eval("""
                    local t=redis.call('TYPE',KEYS[4]).ok
                    if t~='none' and t~='string' then return redis.error_reply('Invalid composite signature type') end
                    if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('HLEN',KEYS[2])~=0
                      or redis.call('GET',KEYS[3])~=ARGV[1] then
                      return redis.error_reply('Composite migration lost exclusivity or was invalidated') end
                    for i=5,#KEYS do
                      local t=redis.call('TYPE',KEYS[i]).ok
                      if t~='none' and t~='string' then return redis.error_reply('Invalid Long53 domain flag type') end
                    end
                    redis.call('SET',KEYS[4],ARGV[2])
                    for i=5,#KEYS do redis.call('SET',KEYS[i],ARGV[i-2]) end
                    redis.call('DEL',KEYS[3]); return 1
                    """, keys, args);
            published = ((Number) result).longValue() == 1;
            if (!published) throw new IllegalStateException("Composite migration publication was not confirmed");
        }
        public void close() {
            if (closed) return;
            if (owner != Thread.currentThread()) throw new IllegalStateException("Migration owner mismatch");
            closed = true;
            migrating.remove();
            // Crashed coordinators/writers leave persistent blockers; recovery requires offline maintenance.
            redis.eval("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",
                    List.of(lock), List.of(token));
        }
    }
}
