package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class CacheRuntimeOptionsBootstrapTest {
    @Test void optionsAreIndependentOfBaseProfilesAndHaveOrderedRuntimeCustomizers() {
        var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:runtime_options_preview");
        var selected = RedisReadCoalescingConfig.builder().maxBatchKeys(17).build();
        var options = CacheRuntimeOptions.builder().redisReadCoalescing(selected).build();
        var bootstrap = CacheDatabase.bootstrap(jdbc).runtimeOptions(options);
        assertSame(options, bootstrap.previewRuntimeOptions());
        bootstrap.customizeRuntime(builder -> builder.redisReadCoalescing(
                RedisReadCoalescingConfig.builder().maxBatchKeys(23).build()));
        bootstrap.customizeRuntime(builder -> builder.redisReadCoalescing(
                RedisReadCoalescingConfig.builder().enabled(false).maxBatchKeys(31).build()));
        bootstrap.production();
        assertEquals(31, bootstrap.previewRuntimeOptions().redisReadCoalescing().maxBatchKeys());
        assertFalse(bootstrap.previewRuntimeOptions().redisReadCoalescing().enabled());
        assertEquals(17, options.redisReadCoalescing().maxBatchKeys());
        assertThrows(NullPointerException.class, () -> bootstrap.runtimeOptions(null));
        assertThrows(NullPointerException.class, () -> bootstrap.customizeRuntime(null));
    }
    @Test void invalidRuntimeCustomizationFailsBeforeCreatingAnyConnection() {
        var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:runtime_options_preflight");
        var failure = assertThrows(IllegalArgumentException.class, () -> CacheDatabase.bootstrap(jdbc)
                .redis("redis://127.0.0.1:1")
                .customizeRuntime(builder -> { throw new IllegalArgumentException("runtime-preflight"); }).build());
        assertEquals("runtime-preflight", failure.getMessage());
    }
}
