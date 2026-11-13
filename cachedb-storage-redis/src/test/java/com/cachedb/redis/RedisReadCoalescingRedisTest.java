package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Isolated("ZERO-threshold JFR must not overlap J0 20 ms recordings")
class RedisReadCoalescingRedisTest {
    @Test @Timeout(90)
    void tenThousandVirtualCallersKeepTheFixed32ConnectionPoolBounded() throws Exception {
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(32);
        pool.setMaxIdle(32);
        pool.setMinIdle(0);
        pool.setBlockWhenExhausted(true);
        pool.setMaxWait(Duration.ofSeconds(30));
        String prefix = "t1-vt-" + UUID.randomUUID();
        var keys = new ArrayList<String>();
        var done = new AtomicBoolean();
        var peak = new AtomicInteger();
        try (var jedis = new JedisPooled(pool, URI.create(RedisTestEndpoint.uri()), 3000, 30_000);
                var reader = new RedisReadRuntime(new JedisPooledOperations(jedis),
                        RedisReadCoalescingConfig.builder().enabled(true).timeoutMillis(30_000).build(), 32);
                var callers = Executors.newVirtualThreadPerTaskExecutor();
                var recording = new Recording()) {
            try {
                try (var pipeline = jedis.pipelined()) {
                    for (int i = 0; i < 128; i++) {
                        String entity = prefix + ":e:" + i, tombstone = prefix + ":t:" + i;
                        keys.add(entity);
                        keys.add(tombstone);
                        pipeline.setex(entity, 120, "value-" + i);
                        if (i % 7 == 0) pipeline.setex(tombstone, 120, "deleted");
                    }
                    pipeline.sync();
                }
                startPinningRecording(recording, Duration.ofSeconds(90));
                var window = new ReadWindow();
                window.begin();
                Thread sampler = Thread.ofVirtual().start(() -> {
                    while (!done.get()) {
                        peak.accumulateAndGet(jedis.getPool().getNumActive(), Math::max);
                        try { Thread.sleep(1); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                });
                var start = new CountDownLatch(1);
                var reads = new ArrayList<Future<RedisPointRead>>(10_000);
                for (int i = 0; i < 10_000; i++) {
                    final int id = i % 128;
                    reads.add(callers.submit(() -> {
                        start.await();
                        assertTrue(Thread.currentThread().isVirtual());
                        return reader.readSync(prefix + ":e:" + id, prefix + ":t:" + id, true);
                    }));
                    window.submitted++;
                }
                start.countDown();
                for (int i = 0; i < reads.size(); i++) {
                    RedisPointRead value = reads.get(i).get(45, TimeUnit.SECONDS);
                    assertEquals("value-" + i % 128, value.payload());
                    assertEquals(i % 128 % 7 == 0 ? "deleted" : null, value.tombstone());
                    assertTrue(value.ttlMillis() > 0);
                    window.completed++;
                }
                done.set(true);
                sampler.join(2000);
                assertFalse(sampler.isAlive());
                assertTrue(peak.get() > 0 && peak.get() <= 32, "Observed active connection count: " + peak.get());
                assertEquals(32, jedis.getPool().getMaxTotal());
                assertTrue(jedis.getPool().getNumActive() + jedis.getPool().getNumIdle() <= 32);
                assertTrue(reader.metrics().coalescedRequests() > 0);
                window.end();
                window.commit();
                verifyPinningRecording(recording, Path.of("target/t1-coalescing-jfr/ten-thousand-vt.jfr"));
            } finally {
                done.set(true);
                jedis.del(keys.toArray(String[]::new));
            }
        }
    }

    @Name("com.reactor.cachedb.t1.CoalescingReadWindow")
    static final class ReadWindow extends Event {
        int submitted;
        int completed;
    }

    private static void startPinningRecording(Recording recording, Duration limit) {
        assertTrue(FlightRecorder.isAvailable());
        assertTrue(FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .anyMatch(type -> type.getName().equals("jdk.VirtualThreadPinned")));
        recording.setMaxSize(64L * 1024 * 1024);
        recording.setDuration(limit);
        recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
        recording.enable("jdk.ActiveSetting");
        recording.enable("jdk.DataLoss");
        recording.enable(ReadWindow.class).withThreshold(Duration.ZERO).withStackTrace();
        recording.start();
        assertPinningSettings(recording);
    }

    private static void assertPinningSettings(Recording recording) {
        assertEquals(RecordingState.RUNNING, recording.getState(), "Workload outlived its JFR window");
        var settings = recording.getSettings();
        assertEquals("true", settings.get("jdk.VirtualThreadPinned#enabled"));
        assertEquals("true", settings.get("jdk.VirtualThreadPinned#stackTrace"));
        assertTrue(settings.get("jdk.VirtualThreadPinned#threshold").matches("0\\s*(ns|us|ms|s)"));
    }

    private static void verifyPinningRecording(Recording recording, Path output) throws Exception {
        assertPinningSettings(recording);
        recording.stop();
        Files.createDirectories(output.getParent());
        recording.dump(output);
        assertTrue(Files.size(output) > 0 && Files.size(output) <= 64L * 1024 * 1024);
        long pinnedId = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .filter(type -> type.getName().equals("jdk.VirtualThreadPinned")).findFirst().orElseThrow().getId();
        boolean enabled = false, stacks = false, zeroThreshold = false;
        int events = 0, windows = 0;
        try (var file = new RecordingFile(output)) {
            while (file.hasMoreEvents()) {
                assertTrue(++events <= 10_000, "JFR evidence exceeded its event budget");
                var event = file.readEvent();
                String name = event.getEventType().getName();
                if (name.equals("jdk.DataLoss")) fail("JFR data loss invalidates pinning evidence");
                if (name.equals("jdk.ActiveSetting") && event.getLong("id") == pinnedId) {
                    String setting = event.getString("name"), value = event.getString("value");
                    enabled |= setting.equals("enabled") && value.equals("true");
                    stacks |= setting.equals("stackTrace") && value.equals("true");
                    zeroThreshold |= setting.equals("threshold") && value.matches("0\\s*(ns|us|ms|s)");
                }
                if (name.equals("jdk.VirtualThreadPinned")) {
                    var trace = event.getStackTrace();
                    assertNotNull(trace, "Pinning event has no stack");
                    assertFalse(trace.getFrames().isEmpty(), "Empty pinning stack invalidates evidence");
                    assertFalse(trace.isTruncated(), "Truncated pinning stack invalidates evidence");
                    assertFalse(trace.getFrames().stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().startsWith("com.reactor.cachedb.")),
                            "CacheDB carrier pinning observed in " + output);
                }
                if (name.equals("com.reactor.cachedb.t1.CoalescingReadWindow")) {
                    windows++;
                    assertEquals(10_000, event.getInt("submitted"));
                    assertEquals(10_000, event.getInt("completed"));
                    assertTrue(event.getDuration().compareTo(Duration.ZERO) > 0);
                    assertFalse(event.getStartTime().isBefore(recording.getStartTime()));
                    assertFalse(event.getEndTime().isAfter(recording.getStopTime()));
                }
            }
        }
        assertEquals(1, windows, "Missing/duplicate completed workload window");
        assertTrue(enabled && stacks && zeroThreshold, "Missing native enabled/stack/zero-threshold settings");
    }

    @Test @Timeout(60)
    void thousandDisabledAsyncReadsUseSingletonAdmissionWithAnExplicitlyUnfairBorrowedPool() throws Exception {
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(32);
        pool.setMaxIdle(32);
        pool.setMinIdle(0);
        pool.setFairness(false); // Borrowed clients need not use the SDK's new fair default.
        pool.setBlockWhenExhausted(true);
        pool.setMaxWait(Duration.ofSeconds(5));
        String prefix = "t1-disabled-unfair-" + UUID.randomUUID();
        try (var jedis = new JedisPooled(pool, URI.create(RedisTestEndpoint.uri()), 3000, 5000)) {
            String entity = prefix + ":e", tombstone = prefix + ":t";
            try (var reader = new RedisReadCoalescer(new JedisPooledOperations(jedis),
                    RedisReadCoalescingConfig.builder().enabled(false).timeoutMillis(30_000).build(), 32)) {
                try {
                    jedis.setex(entity, 60, "row");
                    var reads = new ArrayList<java.util.concurrent.CompletableFuture<RedisPointRead>>(1000);
                    for (int i = 0; i < 1000; i++) reads.add(reader.readAsync(entity, tombstone, true));
                    for (var read : reads) {
                        var value = read.get(40, TimeUnit.SECONDS);
                        assertEquals("row", value.payload());
                        assertNull(value.tombstone());
                        assertTrue(value.ttlMillis() > 0);
                    }
                    RedisReadCoalescerTest.until(() -> reader.state().asyncAdmitted() == 0 && reader.state().inFlight() == 0);
                    assertEquals(1000, reader.metrics().batches());
                    assertEquals(0, reader.metrics().coalescedRequests());
                    assertEquals(1.0, reader.metrics().averageBatchSize());
                    assertEquals(0, reader.state().asyncDirect());
                    assertTrue(jedis.getPool().getNumActive() + jedis.getPool().getNumIdle() <= 32);
                } finally { jedis.del(entity, tombstone); }
            }
        }
    }

    @Test void luaPreservesMissingPersistentTtlAndAtomicColocatedRawPairs() throws Exception {
        String prefix = "t1-raw-" + UUID.randomUUID();
        List<String> keys = List.of(prefix + ":e", prefix + ":t");
        try (var jedis = new JedisPooled(RedisTestEndpoint.uri());
                var reader = new RedisReadRuntime(new JedisPooledOperations(jedis), 32)) {
            try {
                assertEquals(-2, reader.readSync(keys.get(0), keys.get(1), true).ttlMillis());
                jedis.set(keys.get(0), "persistent");
                assertEquals(-1, reader.readSync(keys.get(0), keys.get(1), true).ttlMillis());
                assertEquals(-3, reader.readSync(keys.get(0), keys.get(1), false).ttlMillis());
                jedis.eval("redis.call('SET',KEYS[1],ARGV[1]); redis.call('SET',KEYS[2],ARGV[1]); return 1",
                        keys, List.of("0"));
                var mutation = new java.util.concurrent.CompletableFuture<Void>();
                Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 1; i <= 500; i++)
                            jedis.eval("redis.call('SET',KEYS[1],ARGV[1]); redis.call('SET',KEYS[2],ARGV[1]); return 1",
                                    keys, List.of(Integer.toString(i)));
                        mutation.complete(null);
                    } catch (Throwable failure) { mutation.completeExceptionally(failure); }
                });
                for (int i = 0; i < 500; i++) {
                    RedisPointRead value = reader.readSync(keys.get(0), keys.get(1), true);
                    assertEquals(value.payload(), value.tombstone());
                    assertEquals(-1, value.ttlMillis());
                }
                mutation.get(10, TimeUnit.SECONDS);
            } finally { jedis.del(keys.toArray(String[]::new)); }
        }
    }

    @Test void realLuaRejectsOnlyOversizedPointAndDebatchesValidLargerRows() throws Exception {
        String prefix = "t1-budget-" + UUID.randomUUID();
        List<String> keys = List.of(prefix + ":a", prefix + ":b", prefix + ":t", prefix + ":oversized");
        try (var jedis = new JedisPooled(RedisTestEndpoint.uri())) {
            var client = new JedisPooledOperations(jedis);
            try {
                jedis.setex(keys.get(0), 60, "a".repeat(9 * 1024 * 1024));
                jedis.setex(keys.get(1), 60, "b".repeat(9 * 1024 * 1024));
                jedis.setex(keys.get(3), 60, "x".repeat(16 * 1024 * 1024 + 1));
                var first = new RedisBoundedPointRead.KeyPair(keys.get(0), keys.get(2), true);
                var second = new RedisBoundedPointRead.KeyPair(keys.get(1), keys.get(2), true);
                var error = assertThrows(JedisDataException.class,
                        () -> RedisBoundedPointRead.read(client, List.of(first, second), 16L * 1024 * 1024));
                assertTrue(error.getMessage().contains("CACHEDB_READ_BUDGET"));
                assertEquals(9 * 1024 * 1024, RedisBoundedPointRead.read(client, List.of(first), 16L * 1024 * 1024)[0].payload().length());
                assertEquals(9 * 1024 * 1024, RedisBoundedPointRead.read(client, List.of(second), 16L * 1024 * 1024)[0].payload().length());
                assertThrows(JedisDataException.class, () -> RedisBoundedPointRead.read(client,
                        List.of(new RedisBoundedPointRead.KeyPair(keys.get(3), keys.get(2), false)), 16L * 1024 * 1024));
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var once = new AtomicBoolean();
                var gated = new RedisClientOperations() {
                    @Override public redis.clients.jedis.UnifiedJedis commands() {
                        return new redis.clients.jedis.UnifiedJedis(new redis.clients.jedis.executors.CommandExecutor() {
                            @Override public <T> T executeCommand(redis.clients.jedis.CommandObject<T> command) {
                                throw new AssertionError("Only bounded EVAL is permitted");
                            }
                            @Override public void close() {}
                        }) {
                            @Override public Object evalsha(byte[] sha, List<byte[]> names, List<byte[]> arguments) {
                                if (once.compareAndSet(false, true)) {
                                    entered.countDown();
                                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                                }
                                return jedis.evalsha(sha, names, arguments);
                            }
                            @Override public Object eval(byte[] script, List<byte[]> names, List<byte[]> arguments) {
                                return jedis.eval(script, names, arguments);
                            }
                        };
                    }
                    @Override public void close() {}
                };
                try (var reader = new RedisReadCoalescer(gated,
                        RedisReadCoalescingConfig.builder().enabled(true).maxInFlightBatches(1).timeoutMillis(30_000).build(), 32)) {
                    try {
                        var initial = reader.readAsync(prefix + ":initial", keys.get(2), false);
                        assertTrue(entered.await(2, TimeUnit.SECONDS));
                        var a = reader.readAsync(keys.get(0), keys.get(2), true);
                        var b = reader.readAsync(keys.get(1), keys.get(2), true);
                        var tooLarge = reader.readAsync(keys.get(3), keys.get(2), true);
                        var last = reader.readAsync(prefix + ":missing", keys.get(2), true);
                        release.countDown();
                        initial.get(5, TimeUnit.SECONDS);
                        assertEquals(9 * 1024 * 1024, a.get(10, TimeUnit.SECONDS).payload().length());
                        assertEquals(9 * 1024 * 1024, b.get(10, TimeUnit.SECONDS).payload().length());
                        assertInstanceOf(JedisDataException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> tooLarge.get(10, TimeUnit.SECONDS)).getCause());
                        assertNull(last.get(10, TimeUnit.SECONDS).payload());
                        assertEquals(4, reader.metrics().bypassed());
                    } finally { release.countDown(); }
                }
            } finally { jedis.del(keys.toArray(String[]::new)); }
        }
    }
}
