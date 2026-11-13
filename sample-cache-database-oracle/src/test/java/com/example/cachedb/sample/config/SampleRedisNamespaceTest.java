package com.example.cachedb.sample.config;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SampleRedisNamespaceTest {
    @Test void absentOverridePreservesExistingNamespace() {
        var builder = CacheDatabaseConfig.builder().keyspace(KeyspaceConfig.builder().keyPrefix("existing").build());
        new SampleCacheDbTuningConfig().sampleRedisNamespace(new MockEnvironment())
                .customize(builder, new CacheDbSpringProperties());
        assertEquals("existing", builder.build().keyspace().keyPrefix());
    }
    @Test void explicitBoundedNamespaceDoesNotChangeReadGuards() {
        var builder = CacheDatabaseConfig.builder();
        var before = builder.build();
        new SampleCacheDbTuningConfig().sampleRedisNamespace(new MockEnvironment()
                .withProperty("SAMPLE_REDIS_PREFIX", "sample21-pg-normal"))
                .customize(builder, new CacheDbSpringProperties());
        assertEquals("sample21-pg-normal", builder.build().keyspace().keyPrefix());
        assertEquals(before.readShapeGuardrail(), builder.build().readShapeGuardrail());
    }
    @Test void explicitNamespaceAlsoScopesWriteJournalCompactionAndBatchReplay() {
        var builder = CacheDatabaseConfig.builder();
        new SampleCacheDbTuningConfig().sampleCacheDbTuning(new MockEnvironment()
                .withProperty("SAMPLE_REDIS_PREFIX", "sample21-pg-normal"))
                .customize(builder, new CacheDbSpringProperties());
        var config = builder.build();
        assertEquals("sample21-pg-normal:stream:write-behind", config.writeBehind().streamKey());
        assertEquals("sample21-pg-normal:stream:write-behind:compaction", config.writeBehind().compactionStreamKey());
        assertEquals("sample21-pg-normal:stream:write-behind:dlq", config.writeBehind().deadLetterStreamKey());
        assertEquals(10, config.readShapeGuardrail().hotSetHeadroom());
        assertEquals(250, config.readShapeGuardrail().maxEntityQueryLimit());
    }
    @Test void malformedNamespacesFailBeforeRuntimeCreation() {
        for (String invalid : java.util.List.of("", "{shared}", "x".repeat(101), "with space"))
            assertThrows(IllegalArgumentException.class, () -> new SampleCacheDbTuningConfig().sampleRedisNamespace(
                    new MockEnvironment().withProperty("SAMPLE_REDIS_PREFIX", invalid)));
    }
}
