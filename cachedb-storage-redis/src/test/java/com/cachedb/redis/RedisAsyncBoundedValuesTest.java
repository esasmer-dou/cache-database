package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisAsyncBoundedValuesTest {
    @Test void binaryMetadataPositionsAndMissingValuesArePreservedWithoutRequestingTtl() {
        var client = new RedisReadFakeClient();
        client.put("a", "41"); client.put("c", "43");
        assertEquals(Arrays.asList("41", null, "43"), RedisAsyncBoundedValues.read(client, List.of("a", "b", "c"), 4096));
        assertEquals(List.of("4096", "0", "0", "0"), client.executions.getFirst().getFirst().args());
    }

    @Test void splitSlotMetadataUsesRemainingCumulativeBudgetAndKeepsInputOrder() {
        var client = new RedisReadFakeClient(true);
        var keys = new ArrayList<String>(); var expected = new ArrayList<String>();
        for (int i = 0; i < 64; i++) {
            String key = "{native-version-" + i + "}:v";
            keys.add(key); expected.add(Integer.toString(i)); client.put(key, Integer.toString(i));
        }
        assertEquals(expected, RedisAsyncBoundedValues.read(client, keys, 4096));
        long previous = 4096;
        for (var batch : client.executions) {
            assertEquals(1, batch.size());
            long remaining = Long.parseLong(batch.getFirst().args().getFirst());
            assertTrue(remaining <= previous);
            previous = remaining;
        }
        assertTrue(client.calls.get() > 1);
    }

    @Test void metadataBudgetIsCheckedByLuaBeforeMgetMaterializesAnOversizedValue() {
        var client = new RedisReadFakeClient();
        client.put("version", "x".repeat(4097));
        assertThrows(JedisDataException.class, () -> RedisAsyncBoundedValues.read(client, List.of("version"), 4096));
        assertEquals(0, client.internalMgets.get());
    }

    @Test void malformedBinaryMetadataAndUnexpectedTtlAreRejected() {
        var client = new RedisReadFakeClient();
        client.malformed = List.of(List.of("not binary"), List.of(-3L));
        assertThrows(IllegalStateException.class, () -> RedisAsyncBoundedValues.read(client, List.of("v"), 4096));
        client.malformed = List.of(List.of(new byte[0]), List.of(-1L));
        assertThrows(IllegalStateException.class, () -> RedisAsyncBoundedValues.read(client, List.of("v"), 4096));
    }
}
