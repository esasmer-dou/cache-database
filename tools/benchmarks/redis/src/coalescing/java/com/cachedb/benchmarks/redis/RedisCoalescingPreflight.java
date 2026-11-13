package com.reactor.cachedb.benchmarks.redis;

import java.util.ArrayList;
import java.util.concurrent.*;

/** A fixed real-Redis 1000-worker parity/liveness control, not JMH performance evidence. */
public final class RedisCoalescingPreflight {
    private RedisCoalescingPreflight() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("true") && !args[0].equals("false"))
            throw new IllegalArgumentException("Pass exactly false or true");
        boolean enabled = Boolean.parseBoolean(args[0]);
        boolean declaredDefault = com.reactor.cachedb.core.config.RedisReadCoalescingConfig.defaults().enabled();
        boolean builderDefault = com.reactor.cachedb.core.config.RedisReadCoalescingConfig.builder().build().enabled();
        boolean runtimeDefault = com.reactor.cachedb.core.config.CacheRuntimeOptions.defaults().redisReadCoalescing().enabled();
        if (declaredDefault != builderDefault || declaredDefault != runtimeDefault)
            throw new IllegalStateException("Config, builder and runtime option defaults disagree");
        System.out.println("T1_DEFAULT coalescing=" + declaredDefault
                + " configBuilder=" + builderDefault + " runtimeOptions=" + runtimeDefault);
        try (var fixture = RedisCoalescingFixtures.create(enabled)) {
            var before = fixture.database.redisReadMetrics();
            var ready = new CountDownLatch(1000);
            var start = new CountDownLatch(1);
            var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                    .inheritInheritableThreadLocals(false).name("t1-preflight-", 0).factory());
            var futures = new ArrayList<Future<Long>>(1000);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            try {
                for (int worker = 0; worker < 1000; worker++) {
                    final int index = worker;
                    futures.add(executor.submit(() -> {
                        if (!Thread.currentThread().isVirtual()) throw new IllegalStateException("Not a virtual worker");
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Preflight start timed out");
                        long checksum = 0;
                        for (int round = 0; round < 16; round++) {
                            if (until - System.nanoTime() <= 0) throw new TimeoutException("Preflight deadline elapsed");
                            long id = (index + round) % 1000 + 1L;
                            var row = fixture.repository.findById(id).orElseThrow();
                            RedisCoalescingFixtures.verify(row, id);
                            checksum += row.id();
                        }
                        return checksum;
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Not all 1000 workers started");
                start.countDown();
                long checksum = 0;
                for (var future : futures) {
                    long remaining = until - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("Preflight exceeded its shared deadline");
                    checksum += future.get(remaining, TimeUnit.NANOSECONDS);
                }
                if (checksum != 8008000L) throw new IllegalStateException("Off/on data parity failed");
                var delta = RedisCoalescingFixtures.Delta.between(before, fixture.database.redisReadMetrics());
                delta.require(enabled, 16000, true);
                System.out.println("T1_PREFLIGHT coalescing=" + enabled
                        + " workers=1000 min=16 total=16000 checksum=" + checksum + delta.fields()
                        + " fairness=true maxTotal=32 maxWaitMillis=10000 socketMillis=10000"
                        + " near=false source=REDIS_ONLY guard=UNCHANGED");
            } finally {
                start.countDown();
                for (var future : futures) if (!future.isDone()) future.cancel(true);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Preflight retained worker threads after cancellation");
            }
        }
    }
}
