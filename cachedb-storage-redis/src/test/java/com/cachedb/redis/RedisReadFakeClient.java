package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.CommandObjects;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.executors.CommandExecutor;
import redis.clients.jedis.util.JedisClusterCRC16;

final class RedisReadFakeClient implements RedisClientOperations {
    record Eval(List<String> keys, List<String> args, Response<Object> response) {}
    final Map<String, byte[]> values = new ConcurrentHashMap<>();
    final Map<Integer, RuntimeException> failures = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger commandEvals = new AtomicInteger();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final AtomicInteger internalMgets = new AtomicInteger();
    final List<Thread> threads = new CopyOnWriteArrayList<>();
    final List<List<Eval>> executions = new CopyOnWriteArrayList<>();
    final boolean cluster;
    volatile CountDownLatch gate = new CountDownLatch(0);
    volatile CountDownLatch started = new CountDownLatch(0);
    volatile Object malformed;
    volatile java.util.function.IntConsumer beforeReply = ignored -> {};
    volatile InheritableThreadLocal<String> context;
    final List<String> inherited = new CopyOnWriteArrayList<>();

    RedisReadFakeClient() { this(false); }
    RedisReadFakeClient(boolean cluster) { this.cluster = cluster; }
    void hold(int expected) { gate = new CountDownLatch(1); started = new CountDownLatch(expected); }
    void release() { gate.countDown(); }
    void put(String key, String value) { values.put(key, value.getBytes(StandardCharsets.UTF_8)); }
    @Override public boolean clustered() { return cluster; }
    @Override public UnifiedJedis commands() {
        return new UnifiedJedis(new CommandExecutor() {
            @Override public <T> T executeCommand(CommandObject<T> command) {
                throw new AssertionError("Expected the public binary eval overload");
            }
            @Override public void close() {}
        }) {
            @Override public Object evalsha(byte[] sha, List<byte[]> keys, List<byte[]> args) {
                return eval(RedisBoundedPointRead.SCRIPT.getBytes(StandardCharsets.UTF_8), keys, args);
            }
            @Override public Object eval(byte[] script, List<byte[]> keys, List<byte[]> args) {
                commandEvals.incrementAndGet();
                try (var pipeline = RedisReadFakeClient.this.pipeline()) {
                    var response = pipeline.eval(script, keys, args);
                    pipeline.sync();
                    return response.get();
                }
            }
        };
    }
    @Override public void close() {}

    @Override public AbstractPipeline pipeline() {
        return new AbstractPipeline(new CommandObjects()) {
            final List<Eval> evals = new ArrayList<>();
            @Override protected <T> Response<T> appendCommand(CommandObject<T> command) {
                throw new AssertionError("Unexpected unbounded command");
            }
            @Override public Response<Object> eval(byte[] script, List<byte[]> keys, List<byte[]> args) {
                assertArrayEquals(RedisBoundedPointRead.SCRIPT.getBytes(StandardCharsets.UTF_8), script);
                List<String> names = keys.stream().map(k -> new String(k, StandardCharsets.UTF_8)).toList();
                if (cluster) for (String key : names)
                    assertEquals(JedisClusterCRC16.getSlot(names.getFirst()), JedisClusterCRC16.getSlot(key));
                var response = new Response<Object>(BuilderFactory.RAW_OBJECT);
                evals.add(new Eval(names, args.stream().map(a -> new String(a, StandardCharsets.UTF_8)).toList(), response));
                return response;
            }
            @Override public void sync() {
                int call = calls.incrementAndGet();
                int count = active.incrementAndGet();
                peak.accumulateAndGet(count, Math::max);
                threads.add(Thread.currentThread());
                if (context != null) inherited.add(String.valueOf(context.get()));
                executions.add(List.copyOf(evals));
                started.countDown();
                try {
                    assertTrue(gate.await(10, TimeUnit.SECONDS), "Fake transport was not released");
                    beforeReply.accept(call);
                    if (failures.containsKey(call)) throw failures.get(call);
                    for (Eval eval : evals) {
                        if (malformed != null) { eval.response().set(malformed); continue; }
                        assertTrue(eval.keys().size() <= 1024);
                        long bytes = 0;
                        for (String key : eval.keys()) {
                            byte[] value = values.get(key);
                            bytes += value == null ? 0 : value.length;
                        }
                        if (bytes > Long.parseLong(eval.args().getFirst())) {
                            eval.response().set(new JedisDataException("CACHEDB_READ_BUDGET"));
                            continue;
                        }
                        internalMgets.incrementAndGet();
                        var payloads = new ArrayList<Object>();
                        var ttls = new ArrayList<Long>();
                        for (int i = 0; i < eval.keys().size(); i++) {
                            byte[] value = values.get(eval.keys().get(i));
                            payloads.add(value);
                            ttls.add(eval.args().get(i + 1).equals("1") ? (value == null ? -2L : -1L) : -3L);
                        }
                        eval.response().set(List.of(payloads, ttls));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    active.decrementAndGet();
                }
            }
            @Override public void close() {}
        };
    }
}
