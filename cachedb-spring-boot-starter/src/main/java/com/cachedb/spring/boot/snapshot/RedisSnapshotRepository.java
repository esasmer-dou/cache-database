package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import redis.clients.jedis.JedisPooled;

/** Pointer and payload reads share one Redis operation, without JDBC. */
final class RedisSnapshotRepository implements SnapshotRepository {
    private static final String MULTI_POINT =
            """
local active=redis.call('GET',KEYS[1])
if not active then return {} end
local gen,at=string.match(active,'^([^:]+):(%d+)$')
if not gen then return redis.error_reply('Invalid snapshot marker') end
local content=redis.call('GET',ARGV[1]..':format:'..gen) == 'content'
local result={gen,at}
local bytes=0
for i=3,#ARGV do
  local value=redis.call('HGET',ARGV[1]..':data:'..gen,ARGV[i])
  local state='ABSENT_IN_GENERATION'
  if value then
    if content then value=redis.call('GET',ARGV[1]..':content:'..value) end
    if not value then return redis.error_reply('Snapshot content expired') end
    state='FOUND'
    bytes=bytes+string.len(value)
    if bytes > tonumber(ARGV[2]) then return redis.error_reply('Snapshot page byte budget exceeded; use fewer IDs') end
  end
  table.insert(result,ARGV[i])
  table.insert(result,state)
  table.insert(result,redis.call('HGET',ARGV[1]..':times:'..gen,ARGV[i]) or at)
  table.insert(result,value or '')
end
return result
""";
    private static final String PAGE =
            """
local active=redis.call('GET',KEYS[1])
if not active then return {} end
local gen,at=string.match(active,'^([^:]+):(%d+)$')
if not gen then return redis.error_reply('Invalid snapshot marker') end
local key=ARGV[1]..':idx:'..gen..':'..ARGV[2]
local revision=redis.call('HGET',ARGV[1]..':partition-revisions:'..gen,key) or '0'
if ARGV[9] == '+inf' or ARGV[10] == '-inf' then return {gen,at,revision} end
local start=0
if ARGV[9] and ARGV[9] ~= '-inf' then start=redis.call('ZCOUNT',key,'-inf','('..ARGV[9]) end
if ARGV[3] ~= '' then
  if ARGV[3] ~= gen or ARGV[4] ~= revision then
    return redis.error_reply('Snapshot cursor expired; restart pagination') end
  local rank=redis.call('ZRANK',key,ARGV[5])
  if not rank then return redis.error_reply('Snapshot cursor member missing') end
  if ARGV[8] ~= '' and tonumber(redis.call('ZSCORE',key,ARGV[5])) ~= tonumber(ARGV[8]) then
    return redis.error_reply('Snapshot cursor score changed') end
  start=rank+1
end
local ids=redis.call('ZRANGE',key,start,start+tonumber(ARGV[6]))
local result={gen,at,revision}
local content=nil
  local bytes=0
  for i,id in ipairs(ids) do
    local rowScore=redis.call('ZSCORE',key,id)
    if ARGV[10] and ARGV[10] ~= '+inf' and tonumber(rowScore) > tonumber(ARGV[10]) then break end
    if i > tonumber(ARGV[6]) then
      table.insert(result,id)
      table.insert(result,at)
      table.insert(result,'')
      table.insert(result,'0')
    else
  local value=redis.call('HGET',ARGV[1]..':data:'..gen,id)
  if not value then return redis.error_reply('Snapshot index/payload mismatch') end
  if content == nil then content=redis.call('GET',ARGV[1]..':format:'..gen) == 'content' end
  if content then
    value=redis.call('GET',ARGV[1]..':content:'..value)
    if not value then return redis.error_reply('Snapshot content expired') end
  end
  table.insert(result,id)
    bytes=bytes+string.len(value)
    if bytes > tonumber(ARGV[7]) then return redis.error_reply('Snapshot page byte budget exceeded; use a smaller window') end
  table.insert(result,redis.call('HGET',ARGV[1]..':times:'..gen,id) or at)
  table.insert(result,value)
  table.insert(result,rowScore)
    end
end
return result
""";
    private final RedisClientOperations redis;
    private final String prefix;
    private final boolean indexed;
    private final long maxPageBytes;
    private final java.time.Clock clock;
    private final long warnAgeMillis;
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
        this(
                redis,
                prefix,
                indexed,
                refresh,
                maxPageBytes,
                java.time.Clock.systemUTC(),
                java.time.Duration.ZERO);
    }

    RedisSnapshotRepository(
            RedisClientOperations redis,
            String prefix,
            boolean indexed,
            Function<Collection<String>, SnapshotRefreshResult> refresh,
            long maxPageBytes,
            java.time.Clock clock,
            java.time.Duration warnAge) {
        this.redis = redis;
        this.prefix = prefix;
        this.indexed = indexed;
        this.refresh = refresh;
        if (maxPageBytes < 1)
            throw new IllegalArgumentException("Snapshot byte budget must be positive");
        this.maxPageBytes = Math.min(maxPageBytes, 4L * 1024 * 1024);
        this.clock = clock;
        this.warnAgeMillis = warnAge.toMillis();
    }

    public Optional<SnapshotValue> findById(String id) {
        SnapshotLookup<String> value = lookupRaw(id);
        return value.isPresent()
                ? Optional.of(
                        new SnapshotValue(
                                id, value.rawPayload(), value.refreshedAt(), value.generation()))
                : Optional.empty();
    }

    @Override
    public SnapshotLookup<String> lookupRaw(String id) {
        // 0.13 point reads can return a single large root; the 4 MiB cap is for windows/multi-ID
        // reads.
        return lookupRawAll(List.of(id), Integer.MAX_VALUE - 8L).get(id);
    }

    @Override
    public Map<String, SnapshotLookup<String>> lookupRawAll(Collection<String> ids) {
        return lookupRawAll(ids, maxPageBytes);
    }

    private Map<String, SnapshotLookup<String>> lookupRawAll(
            Collection<String> ids, long byteBudget) {
        Objects.requireNonNull(ids, "ids");
        if (ids.size() > 1000) throw new IllegalArgumentException("At most 1000 snapshot IDs");
        if (ids.isEmpty()) return Map.of();
        List<String> args = new ArrayList<>(List.of(prefix, Long.toString(byteBudget)));
        for (String id : new LinkedHashSet<>(ids)) {
            if (id == null || id.isBlank() || id.length() > 512 || id.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid snapshot root id");
            args.add(id);
        }
        List<?> reply = (List<?>) redis.eval(MULTI_POINT, List.of(prefix + ":active"), args);
        Map<String, SnapshotLookup<String>> result = new LinkedHashMap<>();
        if (reply.isEmpty()) {
            for (int i = 2; i < args.size(); i++)
                result.put(
                        args.get(i),
                        new SnapshotLookup<>(
                                args.get(i),
                                SnapshotStatus.SNAPSHOT_UNAVAILABLE,
                                List.of(),
                                null,
                                0,
                                0,
                                null));
        } else {
            String generation = (String) reply.get(0);
            if (!validGeneration(generation) || (reply.size() - 2) % 4 != 0)
                throw new IllegalArgumentException("Invalid snapshot generation marker");
            for (int i = 2; i < reply.size(); i += 4) {
                String id = (String) reply.get(i);
                long at = Long.parseLong((String) reply.get(i + 2));
                result.put(
                        id,
                        "FOUND".equals(reply.get(i + 1))
                                ? describe(
                                        new SnapshotValue(
                                                id, (String) reply.get(i + 3), at, generation))
                                : new SnapshotLookup<>(
                                        id,
                                        SnapshotStatus.ABSENT_IN_GENERATION,
                                        List.of(),
                                        null,
                                        at,
                                        age(at),
                                        generation));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean validGeneration(String value) {
        if (value.length() != 36) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '-' && (c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    private long age(long at) {
        return Math.max(0, clock.millis() - at);
    }

    @Override
    public SnapshotLookup<String> describe(SnapshotValue value) {
        long age = age(value.refreshedAt());
        return new SnapshotLookup<>(
                value.id(),
                warnAgeMillis > 0 && age >= warnAgeMillis
                        ? SnapshotStatus.STALE_WARNING
                        : SnapshotStatus.FOUND,
                List.of(value.payload()),
                value.payload(),
                value.refreshedAt(),
                age,
                value.generation());
    }

    public SnapshotRefreshResult refreshRoots(Collection<String> ids) {
        if (refresh == null) return SnapshotRepository.super.refreshRoots(ids);
        return refresh.apply(ids);
    }

    public CursorPage<SnapshotValue> findPage(String partitionKey, WindowRequest window) {
        return findPage(new SnapshotPageRequest(partitionKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, window));
    }

    public CursorPage<SnapshotValue> findPage(SnapshotPageRequest request) {
        String partitionKey = request.partitionKey();
        WindowRequest window = request.window();
        if (!indexed) return SnapshotRepository.super.findPage(partitionKey, window);
        Objects.requireNonNull(window, "window");
        String partition = SnapshotIndexSpec.partition(partitionKey);
        String gen = "", revision = "", last = "", score = "";
        if (window.after() != null) {
            if (window.after().length() > 8192)
                throw new IllegalArgumentException("Invalid snapshot cursor");
            String[] cursor =
                    new String(
                                    Base64.getUrlDecoder().decode(window.after()),
                                    StandardCharsets.UTF_8)
                            .split("\\n", -1);
            if ((cursor.length != 5 && cursor.length != 6 && cursor.length != 8)
                    || !cursor[0].equals(prefix)
                    || !cursor[1].equals(partition))
                throw new IllegalArgumentException(
                        "Snapshot cursor belongs to another plan or partition");
            if (request.unbounded() ? cursor.length == 8 : cursor.length != 8
                    || !cursor[6].equals(request.redisMinimum()) || !cursor[7].equals(request.redisMaximum()))
                throw new IllegalArgumentException("Snapshot cursor belongs to another score range");
            gen = cursor[2];
            revision = cursor[3];
            last = cursor[4];
            if (cursor.length >= 6) score = cursor[5];
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
                                        Long.toString(maxPageBytes),
                                        score, request.redisMinimum(), request.redisMaximum()));
        if (result.isEmpty()) return new CursorPage<>(List.of(), null);
        gen = (String) result.get(0);
        revision = (String) result.get(2);
        List<SnapshotValue> items = new ArrayList<>();
        int count = (result.size() - 3) / 4;
        for (int i = 0; i < Math.min(window.limit(), count); i++) {
            int p = 3 + 4 * i;
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
                                                        + items.get(items.size() - 1).id()
                                                        + "\n"
                                                        + result.get(
                                                                3 + 4 * (items.size() - 1) + 3)
                                                        + (request.unbounded() ? "" : "\n" + request.redisMinimum() + "\n" + request.redisMaximum()))
                                                .getBytes(StandardCharsets.UTF_8))
                        : null;
        return new CursorPage<>(items, next);
    }
}
