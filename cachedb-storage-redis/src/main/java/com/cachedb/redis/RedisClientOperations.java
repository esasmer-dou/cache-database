package com.reactor.cachedb.redis;

import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;

/** Client lifecycle and topology-dependent operations; keyed commands use Jedis' shared API. */
public interface RedisClientOperations extends AutoCloseable {
    UnifiedJedis commands();

    default boolean clustered() {
        return false;
    }

    default Object serverInfo(String section, String routingKey) {
        return commands().sendCommand(redis.clients.jedis.Protocol.Command.INFO, section);
    }

    default Object memoryInfo(String routingKey) { return serverInfo("memory", routingKey); }

    default String ping(String routingKey) {
        return commands().ping();
    }

    default long delete(String... keys) {
        return commands().del(keys);
    }

    default AbstractPipeline pipeline() {
        return commands().pipelined();
    }

    default ScanResult<String> scan(String cursor, ScanParams params) {
        return commands().scan(cursor, params);
    }

    default List<String> mget(String... keys) {
        return commands().mget(keys);
    }

    /** Read-only admission before materializing payload replies, including a guarded multi-slot path. */
    default List<String> boundedMget(List<String> keys, long maxBytes) {
        if (keys.size() > 512 || maxBytes < 1 || maxBytes > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Bounded read supports 512 keys and at most 16 MiB");
        if (keys.isEmpty()) return List.of();
        long keyBytes = 0;
        for (String key : keys) {
            if (key == null || key.length() > 16_384) throw new IllegalArgumentException("Invalid bounded read key");
            keyBytes += 2L * key.length();
            if (keyBytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Bounded key byte budget exceeded");
        }
        boolean sameSlot = true;
        try { requireSameSlot(keys); } catch (IllegalArgumentException crossSlot) { sameSlot = false; }
        if (sameSlot) {
            Object raw = eval("""
                    local bytes=0
                    for _,key in ipairs(KEYS) do
                      bytes=bytes+redis.call('STRLEN',key)
                      if bytes>tonumber(ARGV[1]) then return redis.error_reply('Payload read byte budget exceeded') end
                    end
                    return redis.call('MGET',unpack(KEYS))
                    """, keys, List.of(Long.toString(maxBytes)));
            var values = new ArrayList<String>(keys.size());
            for (Object value : (List<?>) raw) values.add(value == null ? null : value.toString());
            return values;
        }
        var lengths = new ArrayList<redis.clients.jedis.Response<Long>>(keys.size());
        try (var pipeline = pipeline()) {
            for (String key : keys) lengths.add(pipeline.strlen(key));
            pipeline.sync();
        }
        long total = 0;
        for (var length : lengths) {
            total = Math.addExact(total,length.get());
            if (total > maxBytes) throw new IllegalStateException("Payload read byte budget exceeded");
        }
        var replies = new ArrayList<redis.clients.jedis.Response<Object>>(keys.size());
        try (var pipeline = pipeline()) {
            for (int i = 0; i < keys.size(); i++) replies.add(pipeline.eval("""
                    if redis.call('STRLEN',KEYS[1])>tonumber(ARGV[1]) then return redis.error_reply('Payload grew during bounded read') end
                    return redis.call('GET',KEYS[1])
                    """, List.of(keys.get(i)),List.of(Long.toString(lengths.get(i).get()))));
            pipeline.sync();
        }
        var values = new ArrayList<String>(keys.size());
        for (var reply : replies) { Object value = reply.get(); values.add(value == null ? null : value.toString()); }
        return values;
    }

    record IndexAggregateBatch(long count, List<String> values, List<Integer> nullCandidates) {
        public IndexAggregateBatch(long count, List<String> values) { this(count, values, List.of()); }
    }

    /** One read-only server call per bounded common-slot batch; numeric arithmetic stays in Java. */
    default Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, String field, boolean countOnly) {
        int size = entities.size();
        if (size < 1 || size > 512 || tombstones.size() != size || indexMetadata.size() != size)
            throw new IllegalArgumentException("Index aggregation requires 1..512 aligned identities");
        if (field != null && field.length() > 4096) throw new IllegalArgumentException("Aggregate field exceeds 4096 characters");
        boolean hasField = field != null && !field.isBlank() && !field.equals("*");
        if (!countOnly && !hasField) throw new IllegalArgumentException("Numeric aggregation requires a field");
        var keys = new ArrayList<String>(size * 3);
        keys.addAll(entities); keys.addAll(tombstones); if (hasField) keys.addAll(indexMetadata);
        long keyBytes = 0;
        for (String key : keys) {
            if (key == null || key.length() > 16_384) throw new IllegalArgumentException("Invalid aggregate key");
            keyBytes += 2L * key.length();
            if (keyBytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Aggregate key byte budget exceeded");
        }
        if (clustered()) try { requireSameSlot(keys); }
        catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        Object response = eval("""
                local n=tonumber(ARGV[3]); local count=0; local values={}; local bytes=0; local nulls={}
                for i=1,n do
                  if redis.call('EXISTS',KEYS[i])==1 and redis.call('EXISTS',KEYS[n+i])==0 then
                    local value=false
                    if ARGV[1]~='' then
                      value=redis.call('HGET',KEYS[2*n+i],ARGV[1])
                      if not value then return redis.error_reply('Incomplete aggregate index metadata; rebuild indexes') end
                      bytes=bytes+#value
                      if bytes>16777216 then return redis.error_reply('Aggregate byte budget exceeded') end
                    end
                    if ARGV[2]=='0' or ARGV[1]=='' or value~='__NULL__' then
                      count=count+1
                      if ARGV[2]=='0' and value~='__NULL__' then
                        table.insert(values,value)
                      end
                    end
                    if ARGV[2]=='1' and ARGV[1]~='' and value=='__NULL__' then table.insert(nulls,i-1) end
                  end
                end
                return {count,values,nulls}
                """, keys, List.of(hasField ? field : "", countOnly ? "1" : "0",Integer.toString(size)));
        var result = (List<?>) response;
        var values = new ArrayList<String>();
        for (Object value : (List<?>) result.get(1)) values.add(value.toString());
        var nulls = new ArrayList<Integer>();
        for (Object index : (List<?>) result.get(2)) nulls.add(((Number) index).intValue());
        return Optional.of(new IndexAggregateBatch(((Number) result.get(0)).longValue(), List.copyOf(values), List.copyOf(nulls)));
    }

    /** Empty means unsupported topology/shape, not an empty result. Both prefixes include the trailing colon. */
    default Optional<List<String>> zrangeWithPayloads(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return Optional.empty();
    }

    record OrderedPayload(String payload, long ttlMillis) {}

    /** Unknown TTL (0) deliberately prevents local caching for legacy/custom adapters. */
    default Optional<List<OrderedPayload>> orderedPayloadWindow(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return zrangeWithPayloads(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit)
                .map(values -> values.stream().map(value -> new OrderedPayload(value, 0)).toList());
    }

    default Optional<List<String>> fetchOrderedPayloads(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloadWindow(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit)
                .map(values -> values.stream().map(OrderedPayload::payload).toList());
    }

    default Optional<List<OrderedPayload>> fetchOrderedPayloadWindow(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        if (offset < 0 || offset > 10_000 || limit < 1 || limit > 1000) return Optional.empty();
        if (clustered()) {
            int start = entityPrefix.indexOf('{'), end = entityPrefix.indexOf('}', start + 1);
            if (start < 0 || end <= start + 1) return Optional.empty();
            String tag = entityPrefix.substring(start, end + 1);
            if (!indexKey.contains(tag) || !tombstonePrefix.contains(tag)) return Optional.empty();
            try { requireSameSlot(List.of(indexKey, tombstonePrefix, entityPrefix)); }
            catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        }
        Object raw = eval("""
                local kind=redis.call('TYPE',KEYS[1]).ok
                if kind=='none' then return false end
                if kind~='zset' and kind~='list' then return redis.error_reply('Invalid ordered index type') end
                local offset=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); local result={}; local bytes=0
                if kind=='list' and redis.call('LLEN',KEYS[1])>limit then return false end
                local rank=offset; local scanned=0; local budget=math.min(10000,limit*16+128)
                while #result<limit and scanned<budget do
                  local finish=rank+math.min(127,budget-scanned-1)
                  local ids
                  if kind=='list' then ids=redis.call('LRANGE',KEYS[1],rank,finish)
                  elseif ARGV[3]=='1' then ids=redis.call('ZREVRANGE',KEYS[1],rank,finish)
                  else ids=redis.call('ZRANGE',KEYS[1],rank,finish) end
                  if #ids==0 then return result end
                  for _,id in ipairs(ids) do
                    scanned=scanned+1
                    if redis.call('EXISTS',KEYS[2]..id)==0 then
                      local payload=redis.call('GET',KEYS[3]..id)
                      if payload then
                        bytes=bytes+#payload
                        if bytes>16777216 then return redis.error_reply('Ordered payload byte budget exceeded') end
                        table.insert(result,{payload,redis.call('PTTL',KEYS[3]..id)})
                        if #result==limit then return result end
                      elseif kind=='list' then return false end
                    elseif kind=='list' then return false end
                  end
                  rank=finish+1
                end
                if #result<limit and scanned>=budget then return redis.error_reply('Ordered payload scan budget exceeded') end
                return result
                """, List.of(indexKey, tombstonePrefix, entityPrefix),
                List.of(String.valueOf(offset), String.valueOf(limit), reverse ? "1" : "0"));
        if (raw == null || Boolean.FALSE.equals(raw)) return Optional.empty();
        if (!(raw instanceof List<?> values)) throw new IllegalStateException("Invalid ordered payload response");
        var result = new ArrayList<OrderedPayload>(values.size());
        for (Object value : values) {
            if (!(value instanceof List<?> pair) || pair.size() != 2 || !(pair.get(1) instanceof Number ttl))
                throw new IllegalStateException("Invalid ordered payload/TTL response");
            result.add(new OrderedPayload(String.valueOf(pair.get(0)), ttl.longValue()));
        }
        return Optional.of(List.copyOf(result));
    }

    /** One server call on a common slot; two bounded pipeline phases across slots. */
    default Map<String, Set<String>> smembersBatch(Collection<String> keys, int maxMembers) {
        if (keys.size() > 4096 || maxMembers < 1) throw new IllegalArgumentException("Invalid set batch budget");
        var result = new LinkedHashMap<String, Set<String>>();
        var distinct = new ArrayList<>(new LinkedHashSet<>(keys));
        if (distinct.isEmpty()) return result;
        boolean sameSlot = true;
        try { requireSameSlot(distinct); } catch (IllegalArgumentException crossSlot) { sameSlot = false; }
        if (sameSlot) {
            Object raw = eval("local n=0; for _,k in ipairs(KEYS) do n=n+redis.call('SCARD',k); "
                    + "if n>tonumber(ARGV[1]) then return redis.error_reply('CacheDB materialized membership budget exceeded') end end; "
                    + "local result={}; for _,k in ipairs(KEYS) do table.insert(result,redis.call('SMEMBERS',k)) end; return result",
                    distinct, List.of(String.valueOf(maxMembers)));
            var sets = (List<?>) raw;
            if (sets.size() != distinct.size()) throw new IllegalStateException("Invalid set batch response");
            for (int i = 0; i < sets.size(); i++) {
                var members = new LinkedHashSet<String>();
                for (Object value : (List<?>) sets.get(i)) members.add(String.valueOf(value));
                result.put(distinct.get(i), members);
            }
            return result;
        }
        var cardinalities = new LinkedHashMap<String, redis.clients.jedis.Response<Long>>();
        try (var pipeline = pipeline()) {
            for (String key : distinct) cardinalities.put(key, pipeline.scard(key));
            pipeline.sync();
        }
        long total = 0;
        for (var value : cardinalities.values()) {
            total += value.get();
            if (total > maxMembers) throw new IllegalStateException("CacheDB materialized membership budget exceeded");
        }
        var responses = new LinkedHashMap<String, redis.clients.jedis.Response<Object>>();
        String script = "local n=redis.call('SCARD',KEYS[1]); if n>tonumber(ARGV[1]) then "
                + "return redis.error_reply('CacheDB candidate budget exceeded') end; return redis.call('SMEMBERS',KEYS[1])";
        try (var pipeline = pipeline()) {
            for (String key : distinct)
                responses.put(key, pipeline.eval(script, List.of(key), List.of(String.valueOf(cardinalities.get(key).get()))));
            pipeline.sync();
            for (var entry : responses.entrySet()) {
                var members = new LinkedHashSet<String>();
                for (Object value : (List<?>) entry.getValue().get()) members.add(String.valueOf(value));
                result.put(entry.getKey(), members);
            }
        }
        return result;
    }

    default Map<String, Set<String>> smembersBatch(Collection<String> keys) {
        return smembersBatch(keys, 100_000);
    }

    default Set<String> probeMembers(String key, Collection<String> candidates) {
        if (candidates.isEmpty()) return Set.of();
        if (candidates.size() > 128) throw new IllegalArgumentException("Membership probe exceeds 128 candidates");
        String[] ids = candidates.toArray(String[]::new);
        List<Boolean> included;
        try { included = commands().smismember(key, ids); }
        catch (redis.clients.jedis.exceptions.JedisDataException unsupported) {
            if (!unsupported.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unknown command")) throw unsupported;
            var responses = new ArrayList<redis.clients.jedis.Response<Boolean>>(ids.length);
            try (var pipeline = pipeline()) {
                for (String id : ids) responses.add(pipeline.sismember(key, id));
                pipeline.sync();
                included = responses.stream().map(redis.clients.jedis.Response::get).toList();
            }
        }
        var result = new LinkedHashSet<String>();
        for (int i = 0; i < ids.length; i++) if (Boolean.TRUE.equals(included.get(i))) result.add(ids[i]);
        return result;
    }

    default Optional<Set<String>> intersectSets(List<String> keys, int maxMembers) {
        if (keys.size() < 2 || keys.size() > 64) return Optional.empty();
        try { requireSameSlot(keys); } catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        Object raw = eval("local m=tonumber(ARGV[1]); local smallest=m+1; "
                + "for _,k in ipairs(KEYS) do local n=redis.call('SCARD',k); if n==0 then return {} end; "
                + "smallest=math.min(smallest,n) end; if smallest>m then "
                + "return redis.error_reply('CacheDB candidate budget exceeded') end; return redis.call('SINTER',unpack(KEYS))",
                keys, List.of(String.valueOf(maxMembers)));
        var result = new LinkedHashSet<String>();
        for (Object value : (List<?>) raw) result.add(String.valueOf(value));
        return Optional.of(result);
    }

    default Object eval(String script, List<String> keys, List<String> arguments) {
        requireSameSlot(keys);
        return commands().eval(script, keys, arguments);
    }

    default Object eval(String script, int keyCount, String... arguments) {
        requireSameSlot(Arrays.asList(arguments).subList(0, keyCount));
        return commands().eval(script, keyCount, arguments);
    }

    default Object fcall(String function, List<String> keys, List<String> arguments) {
        requireSameSlot(keys);
        return commands().fcall(function, keys, arguments);
    }

    default void requireSameSlot(Collection<String> keys) {
        if (!clustered() || keys.isEmpty()) return;
        int slot = -1;
        for (String key : keys) {
            int current = redis.clients.jedis.util.JedisClusterCRC16.getSlot(key);
            if (slot >= 0 && slot != current)
                throw new IllegalArgumentException(
                        "Atomic Redis keys must share one Cluster hash slot");
            slot = current;
        }
    }

    default String functionLoad(String code) {
        return commands().functionLoad(code);
    }

    default String functionLoadReplace(String code) {
        return commands().functionLoadReplace(code);
    }

    default JedisPooled pooledClient() {
        throw new UnsupportedOperationException("Redis client uses Cluster topology");
    }

    @Override
    default void close() {
        commands().close();
    }
}
