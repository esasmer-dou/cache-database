package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SnapshotCodecReadinessTest {
    @Test void preparedReaderRemainsReusableAfterInitialization() throws Exception {
        var codec = new JacksonJsonArrayCodec<>(new ObjectMapper(), String.class);
        assertSame(codec, codec.prepared());
        for (int i = 0; i < 10; i++) {
            assertSame(codec, codec.prepared());
            assertEquals(List.of("one", "two"), codec.decodeArray("[\"one\",\"two\"]"));
        }
    }

    @Test void virtualCallersShareReaderWithoutUnbalancedUnlockOrDeadlock() {
        var codec = new JacksonJsonArrayCodec<>(new ObjectMapper(), String.class);
        assertTimeout(Duration.ofSeconds(10), () -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var ready = new CountDownLatch(32);
                var start = new CountDownLatch(1);
                var calls = new ArrayList<java.util.concurrent.Future<List<String>>>(32);
                for (int i = 0; i < 32; i++) calls.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Codec test did not start");
                    codec.prepared();
                    return codec.decodeArray("[\"ready\"]");
                }));
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                for (var call : calls) assertEquals(List.of("ready"), call.get(2, TimeUnit.SECONDS));
            }
        });
    }
}
