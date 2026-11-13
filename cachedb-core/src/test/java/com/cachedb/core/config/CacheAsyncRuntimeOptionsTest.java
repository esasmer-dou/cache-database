package com.reactor.cachedb.core.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class CacheAsyncRuntimeOptionsTest {
    @Test void asyncOptionsRoundTripWithoutDiscardingOtherRuntimeSettings() {
        Executor executor = task -> { throw new AssertionError("Configuration must not execute work"); };
        var read = RedisReadCoalescingConfig.builder().enabled(false).build();
        var options = CacheRuntimeOptions.builder().redisReadCoalescing(read)
                .asyncMaxConcurrent(4321).asyncExecutor(executor).build();
        var copy = options.toBuilder().build();
        assertSame(read, copy.redisReadCoalescing());
        assertSame(executor, copy.asyncExecutor());
        assertEquals(4321, copy.asyncMaxConcurrent());
        assertEquals("CacheRuntimeOptions", options.toString(), "Never expose executor/provider internals");
    }

    @Test void defaultsAndNullableExecutorDoNotAllocateOrOwnAnApplicationExecutor() {
        var options = CacheRuntimeOptions.defaults();
        assertEquals(1024, options.asyncMaxConcurrent());
        assertNull(options.asyncExecutor());
        Executor executor = task -> { throw new AssertionError("No configuration-time dispatch"); };
        var selected = options.toBuilder().asyncExecutor(executor).build();
        assertSame(executor, selected.asyncExecutor());
        assertNull(selected.toBuilder().asyncExecutor(null).build().asyncExecutor());
    }

    @Test void asyncAdmissionBoundsFailBeforeClientConstruction() {
        assertThrows(IllegalArgumentException.class, () -> CacheRuntimeOptions.builder().asyncMaxConcurrent(0));
        assertThrows(IllegalArgumentException.class, () -> CacheRuntimeOptions.builder().asyncMaxConcurrent(65537));
        assertEquals(65536, CacheRuntimeOptions.builder().asyncMaxConcurrent(65536).build().asyncMaxConcurrent());
    }
}
