package com.reactor.cachedb.spring.boot.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.trace.TraceMetadataCodec;
import io.micrometer.observation.ObservationRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class CacheObservationRedisContractTest {
    @Test void boundedStreamMetadataRoundTripFeedsOneBatchContextWithOriginsNotParents() {
        String stream = "t5-contract-" + UUID.randomUUID();
        String traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-00";
        var metadata = CacheTraceparent.withTraceparent(Map.of("correlationId", "preserved"), traceparent);
        String encoded = TraceMetadataCodec.encode(metadata);
        try (var redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1")
                + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
            try {
                redis.eval("return redis.call('XADD',KEYS[1],'MAXLEN','=',64,'*','traceMetadata',ARGV[1],'traceparent',ARGV[2])",
                        List.of(stream), List.of(encoded, traceparent));
                var entries = (List<?>) redis.eval("return redis.call('XRANGE',KEYS[1],'-','+')", List.of(stream), List.of());
                assertEquals(1, entries.size());
                var fields = (List<?>) ((List<?>) entries.get(0)).get(1);
                var stored = new HashMap<String, String>();
                for (int i = 0; i < fields.size(); i += 2) stored.put(fields.get(i).toString(), fields.get(i + 1).toString());
                assertEquals(metadata, TraceMetadataCodec.decode(stored.get("traceMetadata")));
                assertEquals("preserved", TraceMetadataCodec.decode(stored.get("traceMetadata")).get("correlationId"));
                var registry = ObservationRegistry.create();
                var sink = new MicrometerCacheObservationRecorderTest.Sink();
                registry.observationConfig().observationHandler(sink);
                var recorder = MicrometerCacheObservationRecorderTest.recorder(registry);
                var scope = recorder.start(CacheOperation.FLUSH_BATCH);
                scope.originLinks(CacheTraceparent.originLinks(List.of(stored.get("traceparent"))));
                try (var binding = CacheObservationContext.attach(scope)) { scope.tier(Tier.SOURCE); }
                finally { scope.close(); }
                assertEquals(1, sink.started.size()); assertEquals(1, sink.stopped.size());
                assertEquals(traceparent, sink.originsAtStart.get(0).get(0).traceparent());
                assertNull(sink.last().getParentObservation());
                assertNull(registry.getCurrentObservation());
                // Stream/context contract only; native span export is tested independently.
            } finally { redis.del(stream); }
        }
    }
}
