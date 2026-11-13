package com.reactor.cachedb.core.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RedisReadCoalescingConfigTest {
    @Test void defaultsAreBoundedAndResolveFromTheActualPool() {
        var config = RedisReadCoalescingConfig.defaults();
        assertFalse(config.enabled());
        assertTrue(RedisReadCoalescingConfig.builder().enabled(true).build().enabled());
        assertEquals(512, config.maxBatchKeys());
        assertEquals(4096, config.maxQueuedRequests());
        assertEquals(16L * 1024 * 1024, config.maxQueuedBytes());
        assertEquals(16L * 1024 * 1024, config.maxReplyBytes());
        assertEquals(16, config.resolveMaxInFlight(32));
        assertEquals(2, config.resolveMaxInFlight(1));
        assertEquals(64, config.resolveMaxInFlight(128));
        assertThrows(IllegalArgumentException.class, () -> config.resolveMaxInFlight(2050));
        assertEquals(3, RedisReadCoalescingConfig.builder().maxInFlightBatches(3).build().resolveMaxInFlight(32));
    }

    @Test void hardCapsCannotBeRaisedOrDisabledThroughTheBuilder() {
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxBatchKeys(513).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxBatchKeys(0).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxQueuedRequests(4097).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxInFlightBatches(1025).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxInFlightBatches(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxQueuedBytes(16L * 1024 * 1024 + 1).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxReplyBytes(16L * 1024 * 1024 + 1).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().maxAsyncDirect(1025).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.builder().timeoutMillis(0).build());
        assertThrows(IllegalArgumentException.class, () -> RedisReadCoalescingConfig.defaults().resolveMaxInFlight(0));
        assertEquals(0, RedisReadCoalescingConfig.builder().maxQueuedRequests(0).maxAsyncDirect(0).build().maxQueuedRequests());
    }
}
