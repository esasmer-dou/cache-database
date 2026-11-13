package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** One bounded atomic fetch, with bounded generation-hint resync; every Redis key is declared. */
final class CacheDbCacheStore {
    record Scope(String cacheName, String hash, String stem) {
        String generationKey() { return stem + ":generation"; }
        String stateKey() { return stem + ":state"; }
        String revisionKey(String key) { return stem + ":revision:" + key; }
    }
    record Generation(String incarnation, String counter) {
        String valueKey(Scope scope, String key) { return scope.stem + ":g" + incarnation + "-" + counter + ":" + key; }
    }
    record State(Generation generation, String revision) {
        String nearKey(Scope scope, String key) { return generation.valueKey(scope, key) + ":r" + revision; }
    }
    record Result(int status, Generation hint, State state, String payload, long ttlMillis, boolean removed) {}

    private static final String EXECUTE = """
            local mode=ARGV[8]
            if not (mode == 'READ' or mode == 'PUT' or mode == 'NX' or mode == 'EVICT'
                or mode == 'LOAD' or mode == 'CHECK') then return redis.error_reply('Invalid Spring Cache operation') end
            -- Install the expiring incarnation first: a later command error cannot preserve an old generation identity.
            local fresh=false
            if redis.call('EXISTS',KEYS[1]) == 0 or redis.call('EXISTS',KEYS[2]) == 0 then
              redis.call('SET',KEYS[2],ARGV[3],'PX',ARGV[5])
              redis.call('SET',KEYS[1],'0','PX',ARGV[5])
              fresh=true
            end
            if redis.call('STRLEN',KEYS[1]) > 15 or redis.call('STRLEN',KEYS[2]) ~= 36 then
              return redis.error_reply('Invalid Spring Cache generation metadata')
            end
            local incarnation=redis.call('GET',KEYS[2])
            local generation=redis.call('GET',KEYS[1])
            redis.call('PEXPIRE',KEYS[1],ARGV[5])
            redis.call('PEXPIRE',KEYS[2],ARGV[5])
            if fresh then redis.call('PUBLISH',ARGV[11],ARGV[13] .. incarnation .. ',' .. generation) end
            if incarnation ~= ARGV[1] or generation ~= ARGV[2] then return {0,incarnation,generation} end
            if redis.call('EXISTS',KEYS[3]) == 0 then redis.call('SET',KEYS[3],ARGV[4],'PX',ARGV[5]) end
            if redis.call('STRLEN',KEYS[3]) ~= 36 then return redis.error_reply('Invalid Spring Cache key version') end
            local revision=redis.call('GET',KEYS[3])
            redis.call('PEXPIRE',KEYS[3],ARGV[5])
            local function value()
              if redis.call('STRLEN',KEYS[4]) > tonumber(ARGV[7]) then
                return redis.error_reply('Spring Cache value byte limit exceeded')
              end
              local payload=redis.call('GET',KEYS[4])
              local ttl=redis.call('PTTL',KEYS[4])
              if payload and (ttl < 0 or ttl > tonumber(ARGV[6])) then
                return redis.error_reply('Spring Cache value TTL is invalid')
              end
              return {2,incarnation,generation,revision,payload,ttl,0}
            end
            if mode == 'READ' then return value() end
            if (mode == 'LOAD' or mode == 'CHECK') and revision ~= ARGV[10] then
              return {3,incarnation,generation,revision,false,-2,0}
            end
            if mode == 'CHECK' then return {1,incarnation,generation,revision,false,-2,0} end
            if mode == 'NX' and redis.call('EXISTS',KEYS[4]) == 1 then return value() end
            redis.call('SET',KEYS[3],ARGV[4],'PX',ARGV[5])
            local removed=0
            if mode == 'EVICT' then removed=redis.call('DEL',KEYS[4])
            else redis.call('SET',KEYS[4],ARGV[9],'PX',ARGV[6]) end
            redis.call('PUBLISH',ARGV[11],ARGV[12])
            return {1,incarnation,generation,ARGV[4],false,-2,removed}
            """;
    private static final String CLEAR = """
            -- Never reset the counter before the new expiring incarnation is installed.
            if redis.call('EXISTS',KEYS[1]) == 0 or redis.call('EXISTS',KEYS[2]) == 0 then
              redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[1])
              redis.call('SET',KEYS[1],'0','PX',ARGV[1])
            end
            if redis.call('STRLEN',KEYS[1]) >= 15 or redis.call('STRLEN',KEYS[2]) ~= 36 then
              return redis.error_reply('Invalid/exhausted Spring Cache generation')
            end
            redis.call('INCR',KEYS[1])
            redis.call('PEXPIRE',KEYS[1],ARGV[1])
            redis.call('PEXPIRE',KEYS[2],ARGV[1])
            redis.call('PUBLISH',ARGV[3],ARGV[4] .. redis.call('GET',KEYS[2]) .. ',' .. redis.call('GET',KEYS[1]))
            return 1
            """;

    private final RedisClientOperations redis;
    private final CacheDbCacheConfiguration config;
    private final CacheDbCacheInvalidationCoordinator invalidations;
    private final String metadataTtl;
    CacheDbCacheStore(RedisClientOperations redis, CacheDbCacheConfiguration config,
            CacheDbCacheInvalidationCoordinator invalidations) {
        this.redis = redis; this.config = config; this.invalidations = invalidations;
        metadataTtl = Long.toString(config.ttl().toMillis() + 60000);
    }

    Result execute(Scope scope, String key, Generation candidate, State expected, String mode, String payload) {
        boolean unknown = candidate == null;
        if (unknown) candidate = new Generation(freshToken(), "0");
        List<String> keys = List.of(scope.generationKey(), scope.stateKey(), scope.revisionKey(key), candidate.valueKey(scope, key));
        // On metadata loss, never recreate a cached old incarnation (even if its counter was zero).
        String newIncarnation = unknown ? candidate.incarnation : freshToken();
        Object raw = eval(EXECUTE, keys, List.of(candidate.incarnation, candidate.counter,
                newIncarnation, freshToken(), metadataTtl, Long.toString(config.ttl().toMillis()),
                Integer.toString(config.maxValueBytes()), mode, payload, expected == null ? "" : expected.revision,
                invalidations.channel(), CacheDbCacheInvalidationCoordinator.message("K", scope.cacheName, scope.hash, key),
                CacheDbCacheInvalidationCoordinator.message("C", scope.cacheName, scope.hash, "")));
        List<?> result = list(raw);
        int status = ((Number) result.get(0)).intValue();
        if (result.size() < 3) throw new IllegalStateException("Invalid Spring Cache Redis reply");
        Generation generation = new Generation(string(result.get(1)), string(result.get(2)));
        if (!token(generation.incarnation) || !counter(generation.counter))
            throw new IllegalStateException("Invalid Spring Cache generation");
        if (status == 0 && result.size() == 3) return new Result(0, generation, null, null, 0, false);
        if (!(status == 1 || status == 2 || status == 3) || result.size() != 7)
            throw new IllegalStateException("Invalid Spring Cache Redis operation status");
        String revision = string(result.get(3));
        if (!token(revision)) throw new IllegalStateException("Invalid Spring Cache key revision");
        return new Result(status, generation, new State(generation, revision),
                result.get(4) == null ? null : string(result.get(4)),
                ((Number) result.get(5)).longValue(), ((Number) result.get(6)).longValue() == 1);
    }

    void clear(Scope scope) {
        Object result = eval(CLEAR, List.of(scope.generationKey(), scope.stateKey()),
                List.of(metadataTtl, freshToken(), invalidations.channel(),
                        CacheDbCacheInvalidationCoordinator.message("C", scope.cacheName, scope.hash, "")));
        if (!(result instanceof Number number) || number.intValue() != 1)
            throw new IllegalStateException("Invalid Spring Cache clear reply");
    }
    private Object eval(String script, List<String> keys, List<String> arguments) {
        try { return redis.eval(script, keys, arguments); }
        catch (RuntimeException failure) { invalidations.redisFailure(); throw failure; }
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> result) || result.isEmpty())
            throw new IllegalStateException("Invalid Spring Cache Redis reply");
        return result;
    }
    private static String freshToken() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }
    private static boolean token(String value) {
        if (value.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char ch = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (ch != '-') return false;
            } else if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f')) return false;
        }
        return true;
    }
    private static boolean counter(String value) {
        if (value.isEmpty() || value.length() > 15) return false;
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }
    private static String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalStateException("Invalid Spring Cache Redis string");
        return text;
    }
}
