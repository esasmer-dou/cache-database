package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisNoScriptException;
import redis.clients.jedis.util.JedisClusterCRC16;

/** No post-MGET admission: every slot must pass STRLEN before any values are materialized. */
final class RedisBoundedPointRead {
    static final String SCRIPT = """
            if #KEYS<1 or #KEYS>1024 or #ARGV~=#KEYS+1 then
              return redis.error_reply('CACHEDB_READ_SHAPE')
            end
            local budget=tonumber(ARGV[1])
            if not budget or budget<0 or budget>16777216 then
              return redis.error_reply('CACHEDB_READ_BUDGET')
            end
            local bytes=0
            for _,key in ipairs(KEYS) do
              bytes=bytes+redis.call('STRLEN',key)
              if bytes>budget then return redis.error_reply('CACHEDB_READ_BUDGET') end
            end
            local values=redis.call('MGET',unpack(KEYS))
            local ttls={}
            for i,key in ipairs(KEYS) do
              if ARGV[i+1]=='1' then ttls[i]=redis.call('PTTL',key)
              elseif ARGV[i+1]=='0' then ttls[i]=-3
              else return redis.error_reply('CACHEDB_READ_SHAPE') end
            end
            return {values,ttls}
            """;
    private static final byte[] SCRIPT_BYTES = SCRIPT.getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCRIPT_SHA = "f62a82f4c8bf727352de66640d7ffd7c9d918676"
            .getBytes(StandardCharsets.US_ASCII);
    record KeyPair(String entity, String tombstone, boolean needTtl) {}
    record Result(RedisPointRead[] reads, long contentBytes) {}
    private record Position(int request, boolean entity, boolean needTtl) {}
    private static final class Group {
        final List<byte[]> keys = new ArrayList<>();
        final List<Position> positions = new ArrayList<>();
    }

    static RedisPointRead[] read(RedisClientOperations client, List<KeyPair> requests, long maxBytes) {
        return readMeasured(client, requests, maxBytes).reads();
    }

    static Result readMeasured(RedisClientOperations client, List<KeyPair> requests, long maxBytes) {
        if (requests.isEmpty() || requests.size() > 512 || maxBytes < 1 || maxBytes > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid bounded point-read batch");
        var groups = new LinkedHashMap<Integer, Group>();
        for (int i = 0; i < requests.size(); i++) {
            KeyPair request = requests.get(i);
            add(groups, client.clustered(), request.entity(), new Position(i, true, request.needTtl()));
            add(groups, client.clustered(), request.tombstone(), new Position(i, false, false));
        }
        long observed = System.nanoTime(); // Conservative TTL origin, before every network/decoding cost.
        var payloads = new String[requests.size()];
        var tombstones = new String[requests.size()];
        var ttls = new long[requests.size()];
        Arrays.fill(ttls, RedisPointRead.TTL_NOT_REQUESTED);
        long totalBytes = 0;
        // Sequential slot reads avoid static quotas and never materialize N independent 16 MiB replies.
        for (Group group : groups.values()) {
            long remaining = maxBytes - totalBytes;
            var arguments = new ArrayList<byte[]>(group.keys.size() + 1);
            arguments.add(Long.toString(remaining).getBytes(StandardCharsets.US_ASCII));
            for (Position position : group.positions)
                arguments.add((position.needTtl() ? "1" : "0").getBytes(StandardCharsets.US_ASCII));
            {
                Object raw = fetch(client, group.keys, arguments);
                if (!(raw instanceof List<?> pair) || pair.size() != 2
                        || !(pair.get(0) instanceof List<?> values)
                        || !(pair.get(1) instanceof List<?> expiries)
                        || values.size() != group.keys.size() || expiries.size() != group.keys.size())
                    throw malformed();
                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);
                    if (value != null && !(value instanceof byte[])) throw malformed();
                    byte[] bytes = (byte[]) value;
                    if (bytes != null) {
                        totalBytes = Math.addExact(totalBytes, bytes.length);
                        if (totalBytes > maxBytes) throw malformed();
                    }
                    if (!(expiries.get(i) instanceof Long ttl)) throw malformed();
                    Position position = group.positions.get(i);
                    if (position.needTtl() ? ttl < -2 : ttl != RedisPointRead.TTL_NOT_REQUESTED)
                        throw malformed();
                    String text = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
                    if (position.entity()) {
                        payloads[position.request()] = text;
                        ttls[position.request()] = ttl;
                    } else tombstones[position.request()] = text;
                }
            }
        }
        var results = new RedisPointRead[requests.size()];
        for (int i = 0; i < results.length; i++)
            results[i] = new RedisPointRead(payloads[i], tombstones[i], ttls[i], observed);
        return new Result(results, totalBytes);
    }

    private static Object fetch(RedisClientOperations client, List<byte[]> keys, List<byte[]> arguments) {
        // Binary script execution retains Jedis Cluster MOVED/ASK handling without a SCRIPT LOAD round trip.
        try {
            var commands = client.commands();
            try { return commands.evalsha(SCRIPT_SHA, keys, arguments); }
            catch (JedisNoScriptException missing) {
                // The slot owner may have restarted or flushed its script cache.
            }
            catch (JedisAccessControlException denied) {
                if (!evalshaCommandDenied(denied)) throw denied;
            }
            return commands.eval(SCRIPT_BYTES, keys, arguments);
        }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(client, failure); throw failure; }
    }

    private static boolean evalshaCommandDenied(JedisAccessControlException denied) {
        if (denied.getCause() != null || denied.getSuppressed().length != 0) return false;
        String message = denied.getMessage();
        if (message == null) return false;
        String suffix = " has no permissions to run the 'evalsha' command";
        return message.equals("NOPERM this user" + suffix)
                || message.startsWith("NOPERM User ")
                && message.length() > "NOPERM User ".length() + suffix.length()
                && message.endsWith(suffix);
    }

    private static void add(LinkedHashMap<Integer, Group> groups, boolean clustered, String key, Position position) {
        Group group = groups.computeIfAbsent(clustered ? JedisClusterCRC16.getSlot(key) : 0, ignored -> new Group());
        group.keys.add(key.getBytes(StandardCharsets.UTF_8));
        group.positions.add(position);
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("Malformed or oversized bounded Redis point-read reply");
    }

    private RedisBoundedPointRead() {}
}
