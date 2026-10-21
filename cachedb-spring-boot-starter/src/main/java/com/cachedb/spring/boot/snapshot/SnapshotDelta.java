package com.reactor.cachedb.spring.boot.snapshot;

import java.io.IOException;
import java.util.*;

/** An entire bounded root delta is applied with one owner/version check. */
final class SnapshotDelta {
    static final String APPLY =
            """
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('GET',KEYS[2]) ~= ARGV[2] then return 0 end
            local ttl=redis.call('PTTL',KEYS[2])
            if ttl <= 0 then return 0 end
            if (redis.call('GET',KEYS[7]) or 'json') ~= ARGV[4] then return 0 end
            local prefix=string.match(KEYS[3],'^(.*):data:')
            local changed={}
            for i=5,#ARGV,6 do
              local oldDigest=redis.call('HGET',KEYS[3],ARGV[i])
              if ARGV[4] == 'content' and oldDigest then redis.call('SREM',KEYS[9],oldDigest) end
              local oldIndex=redis.call('HGET',KEYS[4],ARGV[i])
              if oldIndex then redis.call('ZREM',oldIndex,ARGV[i]); changed[oldIndex]=true end
              if ARGV[i+1] == '' then
                redis.call('HDEL',KEYS[3],ARGV[i])
                redis.call('HDEL',KEYS[4],ARGV[i])
                redis.call('HDEL',KEYS[5],ARGV[i])
              else
                local content=tonumber(ARGV[i+4])
                if content > 0 then
                  redis.call('SET',KEYS[content],ARGV[i+1],'NX','PX',ttl)
                  redis.call('PEXPIRE',KEYS[content],ttl)
                  redis.call('HSET',KEYS[3],ARGV[i],ARGV[i+5])
                  redis.call('SADD',KEYS[9],ARGV[i+5])
                else redis.call('HSET',KEYS[3],ARGV[i],ARGV[i+1]) end
                redis.call('HSET',KEYS[5],ARGV[i],ARGV[3])
                local index=tonumber(ARGV[i+3])
                if index > 0 then
                  redis.call('ZADD',KEYS[index],ARGV[i+2],ARGV[i])
                  redis.call('PEXPIRE',KEYS[index],ttl)
                  redis.call('HSET',KEYS[4],ARGV[i],KEYS[index])
                  redis.call('SADD',KEYS[8],KEYS[index])
                  changed[KEYS[index]]=true
                end
              end
              if ARGV[4] == 'content' and oldDigest and oldDigest ~= ARGV[i+5] then
                local staging=redis.call('GET',prefix..':staging') or ''
                if redis.call('SISMEMBER',prefix..':digests:'..staging,oldDigest) == 0 then
                  redis.call('UNLINK',prefix..':content:'..oldDigest)
                end
              end
            end
            for key,_ in pairs(changed) do redis.call('HINCRBY',KEYS[10],key,1) end
            redis.call('INCR',KEYS[6])
            for i=3,10 do redis.call('PEXPIRE',KEYS[i],ttl) end
            return (#ARGV-4)/6
            """;

    static SnapshotPublication.Batch batch(
            String prefix,
            String lock,
            String owner,
            String active,
            long sourceAt,
            boolean reuse,
            List<String> ids,
            SnapshotSpool spool,
            Runnable check)
            throws IOException {
        String generation = active.substring(0, active.indexOf(':'));
        Map<String, SnapshotSpool.Entry> entries = new HashMap<>();
        Set<String> requested = new HashSet<>(ids);
        for (var entry : spool.entries()) {
            if (!requested.contains(entry.id()))
                throw new IllegalStateException(
                        "Scoped SQL returned an undeclared root: " + entry.id());
            entries.put(entry.id(), entry);
        }
        List<String> keys =
                new ArrayList<>(
                        List.of(
                                lock,
                                prefix + ":active",
                                prefix + ":data:" + generation,
                                prefix + ":meta:" + generation,
                                prefix + ":times:" + generation,
                                prefix + ":revision:" + generation,
                                prefix + ":format:" + generation,
                                prefix + ":keys:" + generation,
                                prefix + ":digests:" + generation,
                                prefix + ":partition-revisions:" + generation));
        List<String> args =
                new ArrayList<>(
                        List.of(
                                owner,
                                active,
                                Long.toString(sourceAt),
                                reuse ? "content" : "json"));
        Map<String, Integer> slots = new HashMap<>();
        for (String id : ids) {
            var entry = entries.get(id);
            if (entry == null) {
                args.addAll(List.of(id, "", "0", "0", "0", ""));
                continue;
            }
            String json = spool.read(entry, check);
            String digest = reuse ? SnapshotPublication.digest(id + "\u0000" + json) : "";
            int newIndex =
                    entry.partition() == null
                            ? 0
                            : SnapshotPublication.slot(
                                    keys,
                                    slots,
                                    prefix + ":idx:" + generation + ":" + entry.partition());
            int newContent =
                    reuse
                            ? SnapshotPublication.slot(keys, slots, prefix + ":content:" + digest)
                            : 0;
            args.addAll(
                    List.of(
                            id,
                            json,
                            Double.toString(entry.score()),
                            Integer.toString(newIndex),
                            Integer.toString(newContent),
                            digest));
        }
        return new SnapshotPublication.Batch(keys, args);
    }
}
