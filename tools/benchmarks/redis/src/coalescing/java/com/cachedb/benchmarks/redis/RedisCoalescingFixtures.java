package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisRepositoryReadState;
import com.reactor.cachedb.starter.CacheDatabase;
import java.time.Duration;

/** Qualification and counter reads run outside benchmark timing; only T1 APIs are used. */
final class RedisCoalescingFixtures {
    private RedisCoalescingFixtures() { }

    static RedisBenchmarkFixture create(boolean enabled) throws Exception {
        var options = CacheRuntimeOptions.builder()
                .redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(enabled).build()).build();
        var fixture = new RedisBenchmarkFixture(1000, false, false, false,
                (redis, source, config) -> {
                    if (config.readThrough().mode() != ReadThroughMode.REDIS_ONLY
                            || !config.redisGuardrail().equals(RedisGuardrailConfig.defaults()))
                        throw new IllegalStateException("Source fallback or guard configuration changed");
                    return new CacheDatabase(redis, source, config, options);
                },
                (database, operations, keys) -> {
                    throw new AssertionError("T1 near=false must not create or borrow a coordinator");
                });
        try {
            if (fixture.database.runtimeOptions() != options || fixture.database.redisReadRuntime() == null
                    || fixture.coordinator != null || fixture.store != null)
                throw new IllegalStateException("T1 options/lifecycle/near=false injection was not retained");
            var pool = fixture.redis.getPool();
            if (!pool.getFairness() || pool.getMaxTotal() != 32 || pool.getMaxIdle() != 32
                    || pool.getMinIdle() != 0 || !Duration.ofSeconds(10).equals(pool.getMaxWaitDuration())
                    || new JedisPooledOperations(fixture.redis).physicalPoolCapacity() != 32)
                throw new IllegalStateException("Both toggles require the same actual fair max32 / wait10s pool");
            try (var connection = pool.getResource()) {
                if (connection.getSoTimeout() != 10000)
                    throw new IllegalStateException("Actual ordinary socket timeout differs from 10 seconds");
            }
            return fixture;
        } catch (RuntimeException | Error failure) {
            try { fixture.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void verify(RedisBenchmarkFixture.Row row, long id) {
        if (row.id() != id || row.amount() != id || !"tenant-a".equals(row.tenant())
                || !("group-" + id % 20).equals(row.group()))
            throw new IllegalStateException("Redis-only point read returned a wrong row");
    }

    record Delta(long batches, long coalesced, long disabled, long causal, long unitOfWork, long transportBypassed) {
        static Delta between(RedisRepositoryReadState.Metrics before, RedisRepositoryReadState.Metrics after) {
            return new Delta(after.transport().batches() - before.transport().batches(),
                    after.transport().coalescedRequests() - before.transport().coalescedRequests(),
                    after.disabled() - before.disabled(), after.causal() - before.causal(),
                    after.unitOfWork() - before.unitOfWork(), after.transport().bypassed() - before.transport().bypassed());
        }
        void require(boolean enabled, long operations, boolean positiveControl) {
            if (operations < 1 || batches < 0 || coalesced < 0 || disabled < 0
                    || causal != 0 || unitOfWork != 0 || transportBypassed != 0)
                throw new IllegalStateException("Unexpected source/causal/UOW/overflow path in T1 measurement");
            if (enabled ? disabled != 0 || batches < 1 || batches + coalesced != operations
                    : disabled != operations || batches != 0 || coalesced != 0)
                throw new IllegalStateException("Configured toggle did not select the real repository transport");
            if (positiveControl && enabled && coalesced < 1)
                throw new IllegalStateException("No actual coalescing occurred in the concurrent positive control");
        }
        String fields() {
            return " batches=" + batches + " coalesced=" + coalesced + " disabled=" + disabled
                    + " causal=" + causal + " unitOfWork=" + unitOfWork + " transportBypassed=" + transportBypassed;
        }
    }
}
