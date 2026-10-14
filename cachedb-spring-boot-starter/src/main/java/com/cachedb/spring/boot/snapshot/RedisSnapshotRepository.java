package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;

import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Pointer and payload reads share one Redis operation, without JDBC. */
final class RedisSnapshotRepository implements SnapshotRepository {
    private static final String POINT =
            """
            local active=redis.call('GET',KEYS[1])
            if not active then return {} end
            local gen,at=string.match(active,'^([^:]+):(%d+)$')
            if not gen then return redis.error_reply('Invalid snapshot marker') end
            local value=redis.call('HGET',ARGV[1]..':data:'..gen,ARGV[2])
            if not value then return {} end
            if redis.call('GET',ARGV[1]..':format:'..gen) == 'content' then
              value=redis.call('GET',ARGV[1]..':content:'..value)
              if not value then return redis.error_reply('Snapshot content expired') end
            end
            at=redis.call('HGET',ARGV[1]..':times:'..gen,ARGV[2]) or at
            return {gen,at,value}
            """;
    private static final String PAGE =
            """
local active=redis.call('GET',KEYS[1])
if not active then return {} end
local gen,at=string.match(active,'^([^:]+):(%d+)$')
if not gen then return redis.error_reply('Invalid snapshot marker') end
local revision=redis.call('GET',ARGV[1]..':revision:'..gen) or '0'
local key=ARGV[1]..':idx:'..gen..':'..ARGV[2]
local start=0
if ARGV[3] ~= '' then
  if ARGV[3] ~= gen or ARGV[4] ~= revision then
    return redis.error_reply('Snapshot cursor expired; restart pagination') end
  local rank=redis.call('ZRANK',key,ARGV[5])
  if not rank then return redis.error_reply('Snapshot cursor member missing') end
  start=rank+1
end
local ids=redis.call('ZRANGE',key,start,start+tonumber(ARGV[6]))
local result={gen,at,revision}
  local bytes=0
  for i,id in ipairs(ids) do
    if i > tonumber(ARGV[6]) then
      table.insert(result,id)
      table.insert(result,at)
      table.insert(result,'')
    else
  local value=redis.call('HGET',ARGV[1]..':data:'..gen,id)
  if not value then return redis.error_reply('Snapshot index/payload mismatch') end
  if redis.call('GET',ARGV[1]..':format:'..gen) == 'content' then
    value=redis.call('GET',ARGV[1]..':content:'..value)
    if not value then return redis.error_reply('Snapshot content expired') end
  end
  table.insert(result,id)
    bytes=bytes+string.len(value)
    if bytes > tonumber(ARGV[7]) then return redis.error_reply('Snapshot page byte budget exceeded; use a smaller window') end
  table.insert(result,redis.call('HGET',ARGV[1]..':times:'..gen,id) or at)
  table.insert(result,value)
    end
end
return result
""";
    private final RedisClientOperations redis;
    private final String prefix;
    private final boolean indexed;
    private final long maxPageBytes;
    private final Function<Collection<String>, SnapshotRefreshResult> refresh;

    RedisSnapshotRepository(JedisPooled redis, String prefix) {
        this(JedisPooledOperations.of(redis), prefix);
    }

    RedisSnapshotRepository(RedisClientOperations redis, String prefix) {
        this(redis, prefix, false, null);
    }

    RedisSnapshotRepository(
            JedisPooled redis,
            String prefix,
            boolean indexed,
            Function<Collection<String>, SnapshotRefreshResult> refresh) {
        this(JedisPooledOperations.of(redis), prefix, indexed, refresh);
    }

    RedisSnapshotRepository(
            RedisClientOperations redis,
            String prefix,
            boolean indexed,
            Function<Collection<String>, SnapshotRefreshResult> refresh) {
        this(redis, prefix, indexed, refresh, 4L * 1024 * 1024);
    }

    RedisSnapshotRepository(
            RedisClientOperations redis,
            String prefix,
            boolean indexed,
            Function<Collection<String>, SnapshotRefreshResult> refresh,
            long maxPageBytes) {
        this.redis = redis;
        this.prefix = prefix;
        this.indexed = indexed;
        this.refresh = refresh;
        this.maxPageBytes = maxPageBytes;
    }

    public Optional<SnapshotValue> findById(String id) {
        Objects.requireNonNull(id, "id");
        var result = (List<?>) redis.eval(POINT, List.of(prefix + ":active"), List.of(prefix, id));
        if (!result.isEmpty()
                && (result.size() != 3 || !((String) result.get(0)).matches("[0-9a-f-]{36}")))
            throw new IllegalArgumentException("Invalid snapshot generation marker");
        return result.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        new SnapshotValue(
                                id,
                                (String) result.get(2),
                                Long.parseLong((String) result.get(1)),
                                (String) result.get(0)));
    }

    public SnapshotRefreshResult refreshRoots(Collection<String> ids) {
        if (refresh == null) return SnapshotRepository.super.refreshRoots(ids);
        return refresh.apply(ids);
    }

    public CursorPage<SnapshotValue> findPage(String partitionKey, WindowRequest window) {
        if (!indexed) return SnapshotRepository.super.findPage(partitionKey, window);
        Objects.requireNonNull(window, "window");
        String partition = SnapshotIndexSpec.partition(partitionKey);
        String gen = "", revision = "", last = "";
        if (window.after() != null) {
            if (window.after().length() > 8192)
                throw new IllegalArgumentException("Invalid snapshot cursor");
            String[] cursor =
                    new String(
                                    Base64.getUrlDecoder().decode(window.after()),
                                    StandardCharsets.UTF_8)
                            .split("\\n", -1);
            if (cursor.length != 5 || !cursor[0].equals(prefix) || !cursor[1].equals(partition))
                throw new IllegalArgumentException(
                        "Snapshot cursor belongs to another plan or partition");
            gen = cursor[2];
            revision = cursor[3];
            last = cursor[4];
        }
        var result =
                (List<?>)
                        redis.eval(
                                PAGE,
                                List.of(prefix + ":active"),
                                List.of(
                                        prefix,
                                        partition,
                                        gen,
                                        revision,
                                        last,
                                        Integer.toString(window.limit()),
                                        Long.toString(maxPageBytes)));
        if (result.isEmpty()) return new CursorPage<>(List.of(), null);
        gen = (String) result.get(0);
        revision = (String) result.get(2);
        List<SnapshotValue> items = new ArrayList<>();
        int count = (result.size() - 3) / 3;
        for (int i = 0; i < Math.min(window.limit(), count); i++) {
            int p = 3 + 3 * i;
            items.add(
                    new SnapshotValue(
                            (String) result.get(p),
                            (String) result.get(p + 2),
                            Long.parseLong((String) result.get(p + 1)),
                            gen));
        }
        String next =
                count > window.limit()
                        ? Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        (prefix
                                                        + "\n"
                                                        + partition
                                                        + "\n"
                                                        + gen
                                                        + "\n"
                                                        + revision
                                                        + "\n"
                                                        + items.get(items.size() - 1).id())
                                                .getBytes(StandardCharsets.UTF_8))
                        : null;
        return new CursorPage<>(items, next);
    }
}
