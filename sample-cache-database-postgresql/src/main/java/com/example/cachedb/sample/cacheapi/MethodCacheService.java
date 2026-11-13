package com.example.cachedb.sample.cacheapi;

import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;

@CacheConfig(cacheManager = "cacheDbCacheManager", keyGenerator = "cacheDbKeyGenerator")
public class MethodCacheService {
    private final AtomicLong loads = new AtomicLong(), nullableLoads = new AtomicLong();

    @Cacheable(cacheNames = "quotes", sync = true)
    public String quote(String sku, int branch) { return calculate(sku, branch); }

    @CachePut(cacheNames = "quotes")
    public String refresh(String sku, int branch) { return calculate(sku, branch); }

    @CacheEvict(cacheNames = "quotes", beforeInvocation = true)
    public void evict(String sku, int branch) {}

    @CacheEvict(cacheNames = "quotes", allEntries = true, beforeInvocation = true)
    public void clear() {}

    @Cacheable(cacheNames = "nulls", sync = true)
    public String nullable(String sku) {
        tenant();
        nullableLoads.incrementAndGet();
        return sku.startsWith("MISSING-") ? null : sku;
    }

    private String calculate(String sku, int branch) {
        String tenant = tenant();
        loads.incrementAndGet();
        var random = java.util.concurrent.ThreadLocalRandom.current();
        return tenant + "/" + sku + "/" + branch + "/" + new UUID(random.nextLong(), random.nextLong());
    }
    private static String tenant() {
        return CacheTenantContext.currentTenant().orElseThrow(() -> new CacheTenantAccessDeniedException("quotes"));
    }
    public long sourceLoads() { return loads.get(); }
    public long nullableLoads() { return nullableLoads.get(); }
}
