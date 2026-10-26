package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.*;
import java.util.function.Function;

/** Bounded pending/in-flight reservations prevent lost updates and make crashed claims recoverable. */
final class SnapshotDirtyRoots {
    long pendingCount() { return redis.commands().scard(keys.get(0)); }
    private static final String MARK = """
            local cap=tonumber(ARGV[2])
            if redis.call('SCARD',KEYS[1]) > cap or redis.call('SCARD',KEYS[2]) > cap then return -2 end
            local count=#redis.call('SUNION',KEYS[1],KEYS[2])
            for i=3,#ARGV do
              if redis.call('SISMEMBER',KEYS[1],ARGV[i]) == 0 and redis.call('SISMEMBER',KEYS[2],ARGV[i]) == 0 then count=count+1 end
            end
            if count > cap then return -1 end
            local added=redis.call('SADD',KEYS[1],ARGV[3])
            redis.call('PEXPIRE',KEYS[1],ARGV[1])
            for i=4,#ARGV,500 do added=added+redis.call('SADD',KEYS[1],unpack(ARGV,i,math.min(i+499,#ARGV))) end
            return added
            """;
    private static final String CLAIM = """
            if redis.call('EXISTS',KEYS[3]) == 1 then return {'BUSY'} end
            local cap=tonumber(ARGV[4])
            if redis.call('SCARD',KEYS[1]) > cap or redis.call('SCARD',KEYS[2]) > cap then return {'CORRUPT'} end
            local ids=redis.call('SUNION',KEYS[1],KEYS[2])
            if #ids > cap then return {'CORRUPT'} end
            if #ids == 0 then return {} end
            redis.call('SADD',KEYS[2],ids[1])
            redis.call('PEXPIRE',KEYS[2],ARGV[2])
            for i=2,#ids,500 do redis.call('SADD',KEYS[2],unpack(ids,i,math.min(i+499,#ids))) end
            redis.call('DEL',KEYS[1])
            redis.call('PEXPIRE',KEYS[2],ARGV[2])
            redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[3])
            table.insert(ids,1,'CLAIMED')
            return ids
            """;
    private static final String FINISH = """
            if redis.call('GET',KEYS[3]) ~= ARGV[1] then return 0 end
            if ARGV[3] == 'retry' then
              local cap=tonumber(ARGV[4])
              if redis.call('SCARD',KEYS[1]) > cap or redis.call('SCARD',KEYS[2]) > cap then return -1 end
              local ids=redis.call('SUNION',KEYS[1],KEYS[2])
              if #ids > cap then return -1 end
              if #ids > 0 then
                redis.call('SADD',KEYS[1],ids[1]); redis.call('PEXPIRE',KEYS[1],ARGV[2])
                for i=2,#ids,500 do redis.call('SADD',KEYS[1],unpack(ids,i,math.min(i+499,#ids))) end
              end
            end
            redis.call('DEL',KEYS[2],KEYS[3])
            return 1
            """;
    private final RedisClientOperations redis;
    private final List<String> keys;
    private final int capacity;
    private final long ttl;
    private final long lease;

    SnapshotDirtyRoots(RedisClientOperations redis, String prefix, SnapshotSettings settings) {
        this.redis = redis;
        String base = prefix.contains("{") ? prefix : prefix + "{snapshot-dirty:" + SnapshotPublication.digest(prefix).substring(0, 32) + "}";
        keys = List.of(base + ":dirty", base + ":dirty-inflight", base + ":dirty-claim");
        capacity = settings.batchRows();
        lease = Math.addExact(settings.timeout().toMillis(), settings.leaseDuration().toMillis());
        ttl = Math.max(Math.multiplyExact(settings.retention().toMillis(), 2), Math.multiplyExact(lease, 2));
    }

    int mark(Collection<?> requested) {
        Objects.requireNonNull(requested, "rootIds");
        if (requested.size() > capacity) throw new IllegalArgumentException("Dirty-root request exceeds batchRows");
        Set<String> ids = new LinkedHashSet<>();
        long bytes = 0;
        for (Object value : requested) {
            if (value == null) throw new IllegalArgumentException("Null dirty root ID");
            String id = value.toString();
            if (id.isBlank() || id.length() > 512 || id.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid dirty root ID");
            bytes += com.reactor.cachedb.core.codec.Utf8Length.of(id);
            if (bytes > 4L * 1024 * 1024) throw new IllegalArgumentException("Dirty-root ID byte limit exceeded");
            ids.add(id);
        }
        if (ids.isEmpty()) return 0;
        List<String> args = new ArrayList<>(List.of(Long.toString(ttl), Integer.toString(capacity)));
        args.addAll(ids);
        long added = ((Number) redis.eval(MARK, keys.subList(0, 2), args)).longValue();
        if (added < 0) throw new IllegalStateException("Dirty-root capacity exceeded or corrupt reservations");
        return Math.toIntExact(added);
    }

    Optional<SnapshotManifest> flush(String name, Function<Collection<String>, SnapshotRefreshResult> refresh) {
        String owner = UUID.randomUUID().toString();
        List<?> result = (List<?>) redis.eval(CLAIM, keys, List.of(owner, Long.toString(ttl), Long.toString(lease), Integer.toString(capacity)));
        if (result.isEmpty()) return Optional.empty();
        if (!"CLAIMED".equals(result.get(0))) throw new IllegalStateException("Dirty-root claim " + result.get(0));
        List<String> ids = result.subList(1, result.size()).stream().map(String::valueOf).toList();
        try {
            SnapshotRefreshResult completed = refresh.apply(ids);
            if (!"COMPLETED".equals(completed.status())) throw new IllegalStateException("Dirty-root refresh " + completed.status());
            finish(owner, false);
            return Optional.of(new SnapshotManifest(name, ids.size(), completed));
        } catch (RuntimeException | Error failure) {
            try { finish(owner, true); } catch (RuntimeException retryFailure) { failure.addSuppressed(retryFailure); }
            throw failure;
        }
    }

    private void finish(String owner, boolean retry) {
        Object result = redis.eval(FINISH, keys, List.of(owner, Long.toString(ttl), retry ? "retry" : "ack", Integer.toString(capacity)));
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("Dirty-root claim ownership lost");
    }
}
