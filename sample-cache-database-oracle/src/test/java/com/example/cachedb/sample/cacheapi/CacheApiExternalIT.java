package com.example.cachedb.sample.cacheapi;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.ExternalSampleFixtures;
import com.example.cachedb.sample.domain.SupplierOffer;
import com.example.cachedb.sample.domain.TenantStock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sample.cache-api.enabled=true", "sample.scheduled-warm.enabled=false",
        "sample.controls.supplier-token=test-supplier",
        "sample.controls.tenant-a-token=test-a", "sample.controls.tenant-b-token=test-b",
        "cachedb.spring-cache.enabled=true",
        "cachedb.spring-cache.caches.quotes.ttl=30s",
        "cachedb.spring-cache.caches.quotes.tenant-scoped=true",
        "cachedb.spring-cache.caches.quotes.max-value-bytes=4096",
        "cachedb.spring-cache.caches.quotes.near-cache.max-entries=128",
        "cachedb.spring-cache.caches.quotes.near-cache.max-bytes=1048576",
        "cachedb.spring-cache.caches.quotes.near-cache.ttl=2s",
        "cachedb.spring-cache.caches.nulls.ttl=5s",
        "cachedb.spring-cache.caches.nulls.tenant-scoped=true",
        "cachedb.spring-cache.caches.nulls.allow-null-values=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class CacheApiExternalIT {
    @DynamicPropertySource
    static void fixtures(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry))
            throw new IllegalStateException("Dedicated external fixtures are required");
        registry.add("cachedb.spring-cache.key-prefix", () -> "sample-cache-api-" + RUN);
    }
    private static final String RUN = UUID.randomUUID().toString();
    @Autowired TestRestTemplate rest;
    @Autowired MethodCacheService cache;

    @org.junit.jupiter.api.BeforeEach
    void finiteHttpTimeouts() {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(2000); requests.setReadTimeout(5000);
        rest.getRestTemplate().setRequestFactory(requests);
    }

    @Test void actualAnnotationsHitRefreshEvictClearNullAndTenantIsolation() {
        String path = "/api/examples/v019/cache/quotes/Q-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) + "?branch=3";
        var first = get(path, "test-a", CacheApiController.Quote.class);
        assertEquals(first.value(), get(path, "test-a", CacheApiController.Quote.class).value());
        var other = get(path, "test-b", CacheApiController.Quote.class);
        assertEquals("shop-a", first.tenant()); assertEquals("shop-b", other.tenant());
        assertNotEquals(first.value(), other.value());
        var refreshed = exchange(path.replace("?branch=3", "/refresh?branch=3"), HttpMethod.POST,
                "test-a", null, CacheApiController.Quote.class);
        assertEquals(200, refreshed.getStatusCode().value());
        await(() -> get(path, "test-a", CacheApiController.Quote.class).value().equals(refreshed.getBody().value()), 3);
        assertEquals(204, exchange("/api/examples/v019/cache/quotes", HttpMethod.DELETE,
                "test-b", null, Void.class).getStatusCode().value());
        assertEquals(refreshed.getBody().value(), get(path, "test-a", CacheApiController.Quote.class).value());
        assertEquals(204, exchange(path, HttpMethod.DELETE, "test-a", null, Void.class).getStatusCode().value());
        var afterEvict = get(path, "test-a", CacheApiController.Quote.class);
        assertNotEquals(refreshed.getBody().value(), afterEvict.value());
        assertEquals(204, exchange("/api/examples/v019/cache/quotes", HttpMethod.DELETE,
                "test-a", null, Void.class).getStatusCode().value());
        assertNotEquals(afterEvict.value(), get(path, "test-a", CacheApiController.Quote.class).value());
        long nullable = cache.nullableLoads();
        assertNull(get("/api/examples/v019/cache/nullable/MISSING-ITEM", "test-a", CacheApiController.Quote.class).value());
        assertNull(get("/api/examples/v019/cache/nullable/MISSING-ITEM", "test-a", CacheApiController.Quote.class).value());
        assertEquals(nullable + 1, cache.nullableLoads());
        assertEquals(401, exchange(path, HttpMethod.GET, null, null, Map.class).getStatusCode().value());
        assertEquals(403, exchange(path, HttpMethod.GET, "test-supplier", null, Map.class).getStatusCode().value());
    }

    @Test void hundredVirtualHttpCallersShareOneMethodLoad() throws Exception {
        String path = "/api/examples/v019/cache/quotes/SINGLE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        long before = cache.sourceLoads();
        var ready = new CountDownLatch(100);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new ArrayList<Future<String>>(100);
            for (int i = 0; i < 100; i++) pending.add(workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start deadline exceeded");
                return get(path, "test-a", CacheApiController.Quote.class).value();
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            var values = new HashSet<String>();
            for (var result : pending) values.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, values.size());
            assertEquals(before + 1, cache.sourceLoads());
        } finally { start.countDown(); }
    }

    @Test void asyncReceiptsSourceDurabilityAndReactiveTenantCausalReadsRemainDistinct() {
        long id = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L);
        String offerPath = "/api/examples/v019/async/offers/" + id;
        var body = new CacheApiController.OfferInput("cache-api", "ACTIVE", new BigDecimal("12.3400"), 7);
        var accepted = exchange(offerPath, HttpMethod.PUT, "test-supplier", body, CacheApiController.Accepted.class);
        assertEquals(202, accepted.getStatusCode().value());
        assertTrue(accepted.getBody().version() > 0);
        assertFalse(accepted.getBody().sqlDurabilityConfirmed());
        String token = accepted.getHeaders().getFirst("X-CacheDB-Causal");
        assertEquals(accepted.getBody().causalToken(), token);
        assertEquals(id, getWithToken(offerPath, "test-supplier", token, SupplierOffer.class).offerId());
        await(() -> {
            var source = exchange(offerPath + "/source", HttpMethod.GET, "test-supplier", null, Map.class);
            return source.getStatusCode().value() == 200
                    && ((Number) source.getBody().get("availableUnits")).intValue() == 7;
        }, 20);
        var causalHeaders = headers("test-a"); causalHeaders.set("X-CacheDB-Causal", token);
        assertEquals(409, rest.exchange("/api/examples/v019/cache/quotes/CAUSAL", HttpMethod.GET,
                new HttpEntity<>(causalHeaders), Map.class).getStatusCode().value());

        long stockId = id + 1;
        var stockInput = Map.of("stockId", stockId, "warehouse", "API", "status", "AVAILABLE", "availableUnits", 9);
        var stockWrite = exchange("/api/examples/v018/stock/" + stockId, HttpMethod.PUT, "test-a", stockInput, Map.class);
        assertEquals(202, stockWrite.getStatusCode().value());
        String stockToken = stockWrite.getHeaders().getFirst("X-CacheDB-Causal");
        var row = getWithToken("/api/examples/v019/reactive/stock/" + stockId, "test-a", stockToken, TenantStock.class);
        assertEquals("shop-a", row.tenantId()); assertEquals(9, row.availableUnits());
        var rows = get("/api/examples/v019/reactive/stock?ids=" + stockId, "test-a", TenantStock[].class);
        assertEquals(1, rows.length); assertEquals(stockId, rows[0].stockId());
        assertEquals(403, exchange("/api/examples/v019/reactive/stock/" + stockId,
                HttpMethod.GET, "test-b", null, Map.class).getStatusCode().value());
        var missing = exchange("/api/examples/v019/async/offers/" + (id + 3), HttpMethod.GET,
                "test-supplier", null, Map.class);
        assertEquals(409, missing.getStatusCode().value());
    }

    private <T> T get(String path, String token, Class<T> type) {
        var response = exchange(path, HttpMethod.GET, token, null, type);
        assertEquals(200, response.getStatusCode().value()); return response.getBody();
    }
    private <T> T getWithToken(String path, String authorization, String token, Class<T> type) {
        var headers = headers(authorization); headers.set("X-CacheDB-Causal", token);
        var response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
        assertEquals(200, response.getStatusCode().value()); return response.getBody();
    }
    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, String token, Object body, Class<T> type) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers(token)), type);
    }
    private static HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON); return headers;
    }
    private static void await(BooleanSupplier condition, int seconds) {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        do {
            if (condition.getAsBoolean()) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        } while (System.nanoTime() < deadline);
        fail("Condition did not become true within the bounded deadline");
    }
}
