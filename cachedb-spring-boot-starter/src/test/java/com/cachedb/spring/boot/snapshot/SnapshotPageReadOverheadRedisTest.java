package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.redis.RedisClientOperations;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class SnapshotPageReadOverheadRedisTest {
    // Count only this script's format reads, without global command-stat resets or MONITOR.
    private static final String COUNTER =
            """
local formatReads=0
local original=redis
local redis={error_reply=original.error_reply,call=function(command,...)
  local key=select(1,...)
  if command == 'GET' and string.find(key,':format:',1,true) then
    formatReads=formatReads+1
  end
  return original.call(command,...)
end}
local function execute()
""";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsFormatOncePerNonemptyPageWithoutChangingPayloadOrFailureSemantics(boolean content) {
        String prefix = "snapshot-overhead-" + UUID.randomUUID() + ":{snapshot:catalog}";
        String generation = UUID.randomUUID().toString();
        String format = prefix + ":format:" + generation;
        String data = prefix + ":data:" + generation;
        String index = prefix + ":idx:" + generation + ":" + SnapshotIndexSpec.partition("customer");
        try (var client =
                new JedisPooled(
                        "redis://127.0.0.1:"
                                + Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
            try {
                client.set(prefix + ":active", generation + ":1000");
                client.zadd(index, Map.of("a", 1D, "b", 2D, "c", 3D));
                Map<String, String> payloads =
                        Map.of("a", "[\"one\"]", "b", "[\"\u0130\u011f\"]", "c", "[]");
                for (var entry : payloads.entrySet()) {
                    client.hset(data, entry.getKey(), content ? entry.getKey() : entry.getValue());
                    if (content)
                        client.set(prefix + ":content:" + entry.getKey(), entry.getValue());
                }
                if (content) client.set(format, "content");
                AtomicInteger reads = new AtomicInteger();
                var operations = mock(RedisClientOperations.class);
                when(operations.eval(anyString(), anyList(), anyList()))
                        .thenAnswer(
                                call -> {
                                    List<?> result =
                                            (List<?>)
                                                    client.eval(
                                                            COUNTER
                                                                    + call.getArgument(
                                                                            0, String.class)
                                                                    + "\n"
                                                                    + "end\n"
                                                                    + "local result=execute()\n"
                                                                    + "if result.err then return result end\n"
                                                                    + "return {result,formatReads}",
                                                            call.getArgument(1),
                                                            call.getArgument(2));
                                    reads.set(((Number) result.get(1)).intValue());
                                    return result.get(0);
                                });
                var repository = new RedisSnapshotRepository(operations, prefix, true, null);
                var first = repository.findPage("customer", WindowRequest.first(2));
                assertEquals(
                        List.of("a", "b"), first.items().stream().map(SnapshotValue::id).toList());
                assertEquals(
                        List.of(payloads.get("a"), payloads.get("b")),
                        first.items().stream().map(SnapshotValue::payload).toList());
                assertEquals(1, reads.get());
                var last = repository.findPage("customer", first.nextRequest(2).orElseThrow());
                assertEquals("c", last.items().get(0).id());
                assertEquals("[]", last.items().get(0).payload());
                assertEquals(1, reads.get());
                assertFalse(last.hasNext());
                var bounded = new RedisSnapshotRepository(operations, prefix, true, null, 3);
                assertTrue(
                        assertThrows(
                                        RuntimeException.class,
                                        () -> bounded.findPage("customer", WindowRequest.first(2)))
                                .getMessage()
                                .contains("byte budget"));
                if (content) {
                    client.del(prefix + ":content:a");
                    assertTrue(
                            assertThrows(
                                            RuntimeException.class,
                                            () ->
                                                    repository.findPage(
                                                            "customer", WindowRequest.first(2)))
                                    .getMessage()
                                    .contains("content expired"));
                }
                client.del(format);
                client.hset(format, "wrong", "type");
                assertTrue(repository.findPage("absent", WindowRequest.first(2)).items().isEmpty());
                assertEquals(0, reads.get());
                client.hdel(data, "a");
                assertTrue(
                        assertThrows(
                                        RuntimeException.class,
                                        () ->
                                                repository.findPage(
                                                        "customer", WindowRequest.first(2)))
                                .getMessage()
                                .contains("index/payload mismatch"));
            } finally {
                String cursor = "0";
                do {
                    var scan =
                            client.scan(cursor, new ScanParams().match(prefix + ":*").count(100));
                    cursor = scan.getCursor();
                    for (String key : scan.getResult()) client.unlink(key);
                } while (!cursor.equals("0"));
            }
        }
    }
}
