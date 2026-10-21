package com.reactor.cachedb.spring.boot.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Lease-checked staging, with immutable payload sharing between generations. */
final class SnapshotPublication {
    static final String STAGE =
            """
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            for i=5,#ARGV,6 do
              local content=tonumber(ARGV[i+4])
              if content > 0 then
                redis.call('SET',KEYS[content],ARGV[i+1],'NX','PX',ARGV[2])
                redis.call('PEXPIRE',KEYS[content],ARGV[2])
                redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+5])
                redis.call('SADD',KEYS[6],ARGV[i+5])
              else redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]) end
              local index=tonumber(ARGV[i+3])
              if index > 0 then
                redis.call('ZADD',KEYS[index],ARGV[i+2],ARGV[i])
                redis.call('PEXPIRE',KEYS[index],ARGV[2])
                redis.call('HSET',KEYS[3],ARGV[i],KEYS[index])
                redis.call('SADD',KEYS[4],KEYS[index])
              end
            end
            for i=2,6 do redis.call('PEXPIRE',KEYS[i],ARGV[2]) end
            redis.call('SET',KEYS[5],ARGV[3],'PX',ARGV[2])
            redis.call('SET',KEYS[7],ARGV[4],'PX',ARGV[2])
            return (#ARGV-4)/6
            """;

    record Batch(List<String> keys, List<String> arguments) {}

    static Batch batch(
            String prefix,
            String generation,
            String lock,
            String owner,
            long retention,
            boolean reuse,
            List<SnapshotSpool.Entry> entries,
            java.util.function.Function<SnapshotSpool.Entry, String> payload) {
        List<String> keys =
                new ArrayList<>(
                        List.of(
                                lock,
                                prefix + ":data:" + generation,
                                prefix + ":meta:" + generation,
                                prefix + ":keys:" + generation,
                                prefix + ":format:" + generation,
                                prefix + ":digests:" + generation,
                                prefix + ":staging"));
        List<String> args =
                new ArrayList<>(
                        List.of(
                                owner,
                                Long.toString(retention),
                                reuse ? "content" : "json",
                                generation));
        Map<String, Integer> slots = new HashMap<>();
        for (var entry : entries) {
            String json = payload.apply(entry);
            String digest = reuse ? digest(entry.id() + "\u0000" + json) : "";
            int index =
                    entry.partition() == null
                            ? 0
                            : slot(
                                    keys,
                                    slots,
                                    prefix + ":idx:" + generation + ":" + entry.partition());
            int content = reuse ? slot(keys, slots, prefix + ":content:" + digest) : 0;
            args.add(entry.id());
            args.add(json);
            args.add(Double.toString(entry.score()));
            args.add(Integer.toString(index));
            args.add(Integer.toString(content));
            args.add(digest);
        }
        return new Batch(keys, args);
    }

    static int slot(List<String> keys, Map<String, Integer> slots, String key) {
        Integer prior = slots.get(key);
        if (prior != null) return prior;
        keys.add(key);
        slots.put(key, keys.size());
        return keys.size();
    }

    static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
