package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class AtomicWriteBatchEnvelopeCleanTest {
    @Test
    void rawStreamFieldsContainEachVersionExactlyOnceAndDoNotDegradeAbsentIndexes() {
        String prefix = "envelope-" + UUID.randomUUID();
        var fixture = new RedisAtomicWriteBatchTest();
        var keys = new RedisKeyStrategy(prefix);
        var config =
                WriteBehindConfig.builder()
                        .streamKey(prefix + ":stream")
                        .compactionStreamKey(prefix + ":compaction")
                        .build();
        var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
        registry.register(fixture.metadata(), fixture.codec(), CachePolicy.defaults(), null, null);
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            try {
                new RedisAtomicWriteBatch(
                                redis,
                                registry,
                                keys,
                                config,
                                RedisGuardrailConfig.defaults(),
                                null,
                                "clean")
                        .withQueryIndexConfig(
                                QueryIndexConfig.builder()
                                        .exactIndexEnabled(false)
                                        .rangeIndexEnabled(false)
                                        .build())
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "a"))
                        .commit();
                var response =
                        (List<?>)
                                redis.sendCommand(
                                        Protocol.Command.XRANGE,
                                        config.activeStreamKey(),
                                        "-",
                                        "+");
                var fields = (List<?>) ((List<?>) response.get(0)).get(1);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < fields.size(); i += 2)
                    names.add(
                            fields.get(i) instanceof byte[] bytes
                                    ? new String(bytes, StandardCharsets.UTF_8)
                                    : fields.get(i).toString());
                assertEquals(1, Collections.frequency(names, "b0:version"));
                assertEquals(1, Collections.frequency(names, "b0:col:entity_version"));
                assertFalse(redis.exists(keys.indexDegradedKey("rows")));
                redis.sadd(keys.indexAllKey("rows"), "1");
                new RedisAtomicWriteBatch(
                                redis,
                                registry,
                                keys,
                                config,
                                RedisGuardrailConfig.defaults(),
                                null,
                                "indexed")
                        .put(new RedisAtomicWriteBatchTest.Row(1L, "changed"))
                        .commit();
                assertTrue(redis.exists(keys.indexDegradedKey("rows")));
            } finally {
                String cursor = "0";
                do {
                    var page =
                            redis.scan(
                                    cursor,
                                    new redis.clients.jedis.params.ScanParams()
                                            .match(prefix + ":*")
                                            .count(100));
                    cursor = page.getCursor();
                    for (String key : page.getResult()) redis.unlink(key);
                } while (!cursor.equals("0"));
            }
        }
    }
}
