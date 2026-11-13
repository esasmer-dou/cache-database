package com.reactor.cachedb.core.config;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class RedisReadCoalescingIntegrationConfigTest {
    @Test void publicBaseRecordKeepsItsTwentyOneComponentsAndJava21PatternShape() {
        assertTrue(CacheDatabaseConfig.class.isRecord());
        assertEquals(21, CacheDatabaseConfig.class.getRecordComponents().length);
        Object base = CacheDatabaseConfig.defaults();
        var role = switch (base) {
            case CacheDatabaseConfig(var writes, var flusher, var resources, var keys, var coordination,
                    var functions, var relations, var pages, var through, var shapes, var indexes,
                    var projections, var guards, var recovery, var monitoring, var reports,
                    var http, var schema, var runtimeRole, var mutations, var trace) -> runtimeRole;
            default -> throw new AssertionError("Expected unchanged base record");
        };
        assertEquals(CacheRuntimeRole.FULL, role);
    }
    @Test void immutableOrdinaryOptionsRoundTripWithoutChangingTheBaseRecord() {
        assertFalse(CacheRuntimeOptions.class.isRecord());
        assertTrue(Modifier.isFinal(CacheRuntimeOptions.class.getModifiers()));
        for (var constructor : CacheRuntimeOptions.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        var selected = RedisReadCoalescingConfig.builder().enabled(false).maxBatchKeys(31).build();
        var options = CacheRuntimeOptions.builder().redisReadCoalescing(selected).build();
        assertSame(selected, options.redisReadCoalescing());
        assertSame(selected, options.toBuilder().build().redisReadCoalescing());
        assertEquals(RedisReadCoalescingConfig.defaults(), CacheRuntimeOptions.defaults().redisReadCoalescing());
        assertThrows(NullPointerException.class, () -> options.toBuilder().redisReadCoalescing(null));
    }
    @Test void originalCanonicalDescriptorStillCompilesAndPreservesEqualsShape() {
        var c = CacheDatabaseConfig.defaults();
        var copy = new CacheDatabaseConfig(c.writeBehind(), c.writeBehindFlusherFactory(), c.resourceLimits(),
                c.keyspace(), c.runtimeCoordination(), c.redisFunctions(), c.relations(), c.pageCache(),
                c.readThrough(), c.readShapeGuardrail(), c.queryIndex(), c.projectionRefresh(), c.redisGuardrail(),
                c.deadLetterRecovery(), c.adminMonitoring(), c.adminReportJob(), c.adminHttp(), c.schemaBootstrap(),
                c.runtimeRole(), c.mutations(), c.traceContextPropagator());
        assertEquals(c, copy); assertEquals(c.hashCode(), copy.hashCode());
        assertEquals(c, c.toBuilder().build());
    }
}
