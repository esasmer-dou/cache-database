package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.retention.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetentionSweeperTest {
    @Test void requiresExplicitAdminAndRegisteredBoundedTarget() {
        var calls=new AtomicInteger();
        RetentionTarget target=new RetentionTarget() {
            public Class<?> entityType(){return String.class;} public String scopeToken(){return "a".repeat(64);}
            public RetentionSweepResult sweep(int n){calls.incrementAndGet();return new RetentionSweepResult(0,0,0,0,0,true,0,Instant.EPOCH);}
        };
        var sweeper=new RetentionSweeper(List.of(target));
        assertEquals(RetentionException.Code.ADMIN_REQUIRED,assertThrows(RetentionException.class,()->sweeper.sweepOnce(String.class,128)).code());
        assertEquals(0,calls.get());
        try (var admin=CacheTenantContext.administrative()) {
            assertTrue(sweeper.sweepOnce(String.class,128).exhausted()); assertEquals(1,calls.get());
            assertThrows(IllegalArgumentException.class,()->sweeper.sweepOnce(String.class,10001));
            assertThrows(IllegalArgumentException.class,()->sweeper.sweepOnce(Integer.class,128));
        }
    }
}
