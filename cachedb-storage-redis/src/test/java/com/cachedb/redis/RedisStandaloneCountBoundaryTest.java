package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisAccessControlException;

class RedisStandaloneCountBoundaryTest {
    @Test void originalGenericAndBinaryCountsRespectNativeCapWithEvalDenied() {
        for (boolean binary : List.of(false, true)) for (int rows : List.of(10_000, 10_001)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                if (rows == 10_000) {
                    for (int start = 0; start < rows; start += 512)
                        pool.connection.reply(nativeReply(Math.min(512, rows - start)));
                } else {
                    pool.connection.reply(Collections.nCopies(20, new JedisAccessControlException(
                            "NOPERM this user has no permissions to run the 'eval' command")));
                }
                var ids = IntStream.range(0, rows).mapToObj(Integer::toString).toList();
                var result = binary ? RedisStandaloneIndexSnapshot.indexCounts(pool,
                        new RedisBinaryCountKeys(new RedisKeyStrategy(), "count-boundary", ids, "a"), "a")
                        : RedisStandaloneIndexSnapshot.indexCounts(pool, rows, start -> {
                            var window = ids.subList(start, Math.min(start + 512, rows));
                            return new RedisClientOperations.IndexReadKeys(
                                    window.stream().map(id -> "entity:" + id).toList(),
                                    window.stream().map(id -> "tomb:" + id).toList(),
                                    Collections.nCopies(window.size(), null),
                                    window.stream().map(id -> "owner:" + id).toList());
                        }, "a");
                if (rows == 10_000) assertEquals(rows, result.orElseThrow());
                else assertTrue(result.isEmpty());
                var names = pool.connection.names();
                assertEquals(rows == 10_000 ? 20 : 0, Collections.frequency(names, "MULTI"));
                assertEquals(rows == 10_000 ? 20 : 0, Collections.frequency(names, "EXEC"));
                assertEquals(rows == 10_000 ? rows : 0, Collections.frequency(names, "GETRANGE"));
                assertEquals(rows == 10_000 ? 0 : 20, Collections.frequency(names, "EVAL"));
                assertEquals(1, pool.borrows);
                assertEquals(rows == 10_000 ? 1 : 0, pool.healthyReturns);
                assertEquals(rows == 10_000 ? 0 : 1, pool.brokenReturns);
                assertTrue(pool.connection.pending.isEmpty());
                assertTrue(pool.fallback.phases.isEmpty(), "No whole-phase native borrow above the cap");
            }
        }
    }

    private static List<Object> nativeReply(int size) {
        var values = new ArrayList<Object>();
        values.add((long) size); values.add(0L);
        for (int i = 0; i < size; i++) values.add("a".getBytes(StandardCharsets.UTF_8));
        var reply = new ArrayList<Object>();
        reply.add("OK".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < values.size(); i++) reply.add("QUEUED".getBytes(StandardCharsets.UTF_8));
        reply.add(values);
        return reply;
    }
}
