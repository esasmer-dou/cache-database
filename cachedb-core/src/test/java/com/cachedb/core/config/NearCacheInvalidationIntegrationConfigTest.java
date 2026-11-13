package com.reactor.cachedb.core.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NearCacheInvalidationIntegrationConfigTest {
    @Test void defaultFleetPolicyLivesOutsideTheUnchangedBaseRecord() {
        var options = CacheRuntimeOptions.defaults();
        assertEquals(NearCacheInvalidationConfig.defaults(), options.nearCacheInvalidation());
        assertEquals(NearCacheInvalidationMode.AUTO, options.nearCacheInvalidation().mode());
        assertTrue(options.nearCacheInvalidation().publishLegacyInvalidations());
        assertEquals(21, CacheDatabaseConfig.class.getRecordComponents().length);
        assertFalse(CacheRuntimeOptions.class.isRecord());
        assertEquals("CacheRuntimeOptions", options.toString());
    }
    @Test void strictOptOutAndReadCoalescingSurviveBothOptionsBuilderRoundTrips() {
        var invalidation = NearCacheInvalidationConfig.builder()
                .mode(NearCacheInvalidationMode.SERVER_TRACKING).publishLegacyInvalidations(false)
                .maxMasters(2).maxNodes(4).maxNamespaces(12).ioTimeoutMillis(600)
                .heartbeatMillis(100).healthTimeoutMillis(900).maxAttempts(4)
                .initialBackoffMillis(200).maxBackoffMillis(1000).retryCooldownMillis(2000).build();
        var reads = RedisReadCoalescingConfig.builder().enabled(false).maxBatchKeys(31).build();
        assertEquals(invalidation, invalidation.toBuilder().build());
        var options = CacheRuntimeOptions.builder().redisReadCoalescing(reads).nearCacheInvalidation(invalidation).build();
        assertSame(invalidation, options.toBuilder().build().nearCacheInvalidation());
        assertSame(reads, options.toBuilder().build().redisReadCoalescing());
        assertThrows(NullPointerException.class, () -> options.toBuilder().nearCacheInvalidation(null));
    }
    @Test void unsafeMixedFleetFallbackCannotBeConfigured() {
        for (var mode : new NearCacheInvalidationMode[]{NearCacheInvalidationMode.AUTO, NearCacheInvalidationMode.PUBSUB})
            assertThrows(IllegalArgumentException.class, () -> NearCacheInvalidationConfig.builder()
                    .mode(mode).publishLegacyInvalidations(false).build());
        var strict = NearCacheInvalidationConfig.builder().mode(NearCacheInvalidationMode.SERVER_TRACKING)
                .publishLegacyInvalidations(false).build();
        assertThrows(IllegalArgumentException.class, () -> strict.withMode(NearCacheInvalidationMode.AUTO));
        assertThrows(IllegalArgumentException.class, () -> strict.toBuilder().mode(NearCacheInvalidationMode.PUBSUB).build());
    }
    @Test void baseCanonicalConstructorAndEqualityStayIndependentOfOptions() {
        var c = CacheDatabaseConfig.defaults();
        var old21 = new CacheDatabaseConfig(c.writeBehind(), c.writeBehindFlusherFactory(), c.resourceLimits(),
                c.keyspace(), c.runtimeCoordination(), c.redisFunctions(), c.relations(), c.pageCache(),
                c.readThrough(), c.readShapeGuardrail(), c.queryIndex(), c.projectionRefresh(), c.redisGuardrail(),
                c.deadLetterRecovery(), c.adminMonitoring(), c.adminReportJob(), c.adminHttp(), c.schemaBootstrap(),
                c.runtimeRole(), c.mutations(), c.traceContextPropagator());
        CacheRuntimeOptions.builder().nearCacheInvalidation(NearCacheInvalidationConfig.builder()
                .mode(NearCacheInvalidationMode.SERVER_TRACKING).publishLegacyInvalidations(false).build()).build();
        assertEquals(c, old21); assertEquals(c.hashCode(), old21.hashCode());
    }
    @Test void hardBudgetsAreValidatedByThePublicBuilder() {
        assertThrows(IllegalArgumentException.class, () -> NearCacheInvalidationConfig.builder().maxMasters(17).build());
        assertThrows(IllegalArgumentException.class, () -> NearCacheInvalidationConfig.builder().maxNodes(65).build());
        assertThrows(IllegalArgumentException.class, () -> NearCacheInvalidationConfig.builder().maxNamespaces(257).build());
        assertThrows(IllegalArgumentException.class, () -> NearCacheInvalidationConfig.builder().maxAttempts(9).build());
        assertThrows(IllegalArgumentException.class, () -> NearCacheInvalidationConfig.builder().heartbeatMillis(300).build());
    }
}
