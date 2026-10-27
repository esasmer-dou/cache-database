package com.example.cachedb.sample.controls;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.domain.SupplierOffer;
import com.example.cachedb.sample.domain.SupplierOfferCacheBinding;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import redis.clients.jedis.JedisPooled;

/** New APIs are exercised against the same disposable Redis and real SQL provider as the old contracts. */
public final class ControlsScenarios {
    private ControlsScenarios() {}
    public static void verify(TestRestTemplate http, String base, JdbcTemplate sql) {
        transport(http);
        String path = base + "/api/examples/v018";
        assertEquals(401, call(http, path + "/offers/9300001", HttpMethod.GET, null, null).getStatusCode().value());
        var first = put(http, path, 9300001, "acme", "ACTIVE", "12.1250", 20);
        put(http, path, 9300002, "acme", "ACTIVE", "7.8750", 30);
        put(http, path, 9300003, "acme", "PAUSED", "1.0050", 40);
        String causal = first.getHeaders().getFirst("X-CacheDB-Causal");
        assertNotNull(causal);
        var headers = auth("demo-supplier"); headers.set("X-CacheDB-Causal", causal);
        var read = http.exchange(path + "/offers/9300001", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, read.getStatusCode().value());
        assertEquals(20, number(read.getBody().get("availableUnits")));
        headers.set("X-CacheDB-Causal", "sample-supplier-offers|9300001|9223372036854775807");
        assertEquals(409, http.exchange(path + "/offers/9300001", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getStatusCode().value());
        headers.set("X-CacheDB-Causal", "malformed");
        assertEquals(400, http.exchange(path + "/offers/9300001", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getStatusCode().value());

        var bulk = list(call(http, path + "/offers?ids=9300001,9300002,9300001", HttpMethod.GET, "demo-supplier", null), 200);
        assertEquals(List.of(9300001L, 9300002L, 9300001L), bulk.stream().map(row -> number(row.get("offerId"))).toList());
        assertEquals(400, call(http, path + "/offers?ids=" + String.join(",", Collections.nCopies(65, "9300001")),
                HttpMethod.GET, "demo-supplier", null).getStatusCode().value());
        var dashboard = call(http, path + "/offers/dashboard?supplier=acme", HttpMethod.GET, "demo-supplier", null);
        assertEquals(200, dashboard.getStatusCode().value());
        var json = com.google.gson.JsonParser.parseString(dashboard.getBody()).getAsJsonObject();
        assertEquals(2, json.getAsJsonObject("counts").get("ACTIVE").getAsLong());
        assertEquals(0, new BigDecimal("20.0000").compareTo(json.get("activePriceTotal").getAsBigDecimal()));
        assertEquals(0, new BigDecimal("21.0050").divide(BigDecimal.valueOf(3), java.math.MathContext.DECIMAL128)
                .compareTo(json.get("decimalAverage").getAsBigDecimal()));
        assertEquals(2, list(call(http, path + "/offers/search?supplier=acme&status=ACTIVE", HttpMethod.GET, "demo-supplier", null), 200).size());
        put(http, path, 9300001, "acme", "PAUSED", "12.1250", 20);
        assertEquals(1, list(call(http, path + "/offers/search?supplier=acme&status=ACTIVE", HttpMethod.GET, "demo-supplier", null), 200).size());

        var changes = Map.of("putId", 9300006L, "put", offer("batch", "ACTIVE", "3.5000", 9),
                "patchId", 9300002L, "units", 77,
                "secondPutId", 9300007L, "secondPut", offer("batch", "ACTIVE", "4.2500", 10),
                "secondPatchId", 9300001L, "secondUnits", 78, "deleteId", 9300003L);
        assertEquals(202, call(http, path + "/offers/changes", HttpMethod.POST, "demo-supplier", changes).getStatusCode().value());
        await(() -> sql.queryForObject("SELECT COUNT(*) FROM sample_supplier_offers WHERE offer_id=9300002 AND available_units=77", Integer.class) == 1);
        await(() -> sql.queryForObject("SELECT COUNT(*) FROM sample_supplier_offers WHERE offer_id=9300003", Integer.class) == 0);
        assertEquals(2, sql.queryForObject("SELECT COUNT(*) FROM sample_supplier_offers WHERE offer_id IN (9300006,9300007)", Integer.class));
        assertEquals(78, sql.queryForObject("SELECT available_units FROM sample_supplier_offers WHERE offer_id=9300001", Integer.class));
        assertEquals(0, new BigDecimal("7.8750").compareTo(sql.queryForObject(
                "SELECT unit_price FROM sample_supplier_offers WHERE offer_id=9300002", BigDecimal.class)));

        // SQL-only history stays cold until an explicit bounded warm job runs.
        sql.update("INSERT INTO sample_supplier_offers (offer_id,supplier,status,unit_price,available_units,entity_version) VALUES (?,?,?,?,?,?)",
                9300004L, "cold-window", "ACTIVE", new BigDecimal("2.0000"), 17, 0L);
        assertEquals(404, call(http, path + "/offers/9300004", HttpMethod.GET, "demo-supplier", null).getStatusCode().value());
        assertEquals(200, call(http, path + "/offers/9300004/source", HttpMethod.GET, "demo-supplier", null).getStatusCode().value());
        assertEquals(404, call(http, path + "/offers/9300004", HttpMethod.GET, "demo-supplier", null).getStatusCode().value());
        var warm = object(call(http, path + "/offers/warm", HttpMethod.POST, "demo-supplier",
                Map.of("supplier", "cold-window", "maxRows", 100)), 202);
        await(() -> {
            var result = object(call(http, base + "/api/warm/jobs/" + warm.get("jobId"), HttpMethod.GET, null, null), 200);
            if ("FAILED".equals(result.get("status"))) fail("Offer warm job failed: " + result);
            return "COMPLETED".equals(result.get("status"));
        });
        assertEquals(200, call(http, path + "/offers/9300004", HttpMethod.GET, "demo-supplier", null).getStatusCode().value());
        assertEquals(400, call(http, path + "/offers/warm", HttpMethod.POST, "demo-supplier",
                Map.of("supplier", "cold-window", "maxRows", 101)).getStatusCode().value());

        assertEquals(202, call(http, path + "/stock/batch", HttpMethod.POST, "demo-shop-a", List.of(
                stock(9400001, "W1", 4), stock(9400002, "W1", 8), stock(9400003, "W2", 2))).getStatusCode().value());
        assertEquals(202, call(http, path + "/stock/batch", HttpMethod.POST, "demo-shop-b", List.of(
                stock(9400004, "W1", 1000), stock(9400005, "W2", 1000))).getStatusCode().value());
        for (int units : List.of(5, 6))
            assertEquals(202, call(http, path + "/stock/9400001/units", HttpMethod.PATCH, "demo-shop-a",
                    Map.of("availableUnits", units)).getStatusCode().value());
        assertEquals(403, call(http, path + "/stock/9400001", HttpMethod.GET, "demo-shop-b", null).getStatusCode().value());
        assertEquals(403, call(http, path + "/stock/9400001/units", HttpMethod.PATCH, "demo-shop-b",
                Map.of("availableUnits", 99)).getStatusCode().value());
        assertEquals(403, call(http, path + "/stock/batch", HttpMethod.POST, "demo-shop-a", List.of(
                stock(9400001, "W1", 999), stock(9400004, "W1", 999))).getStatusCode().value());
        assertEquals(6, number(object(call(http, path + "/stock/9400001", HttpMethod.GET, "demo-shop-a", null), 200).get("availableUnits")));
        var top = object(call(http, path + "/stock/top?warehouses=W1,W2&limit=1", HttpMethod.GET, "demo-shop-a", null), 200);
        assertEquals(9400002L, number(((Map<?, ?>)((List<?>)top.get("W1")).getFirst()).get("stockId")));
        assertEquals(9400003L, number(((Map<?, ?>)((List<?>)top.get("W2")).getFirst()).get("stockId")));
        var cards = list(call(http, path + "/stock/cards?limit=1", HttpMethod.GET, "demo-shop-a", null), 200);
        assertEquals(1, cards.size());
        assertEquals(9400002L, number(cards.getFirst().get("stockId")));
        assertFalse(cards.getFirst().containsKey("tenantId"));
        assertEquals(403, call(http, path + "/offers/dashboard?supplier=acme", HttpMethod.GET, "demo-shop-a", null).getStatusCode().value());
        assertEquals(400, call(http, path + "/stock/batch", HttpMethod.POST, "demo-shop-a",
                java.util.Arrays.asList((Object) null)).getStatusCode().value());
        assertEquals(400, call(http, path + "/offers?ids=9300001,,9300002", HttpMethod.GET, "demo-supplier", null).getStatusCode().value());
        assertEquals(400, call(http, path + "/offers/9300099", HttpMethod.PUT, "demo-supplier",
                offer("acme", "ACTIVE", "1e2147483647", 1)).getStatusCode().value());
    }

    public static void nearCache(TestRestTemplate http, String base, String redisUri, CacheDatabase database) throws Exception {
        transport(http);
        String path = base + "/api/examples/v018";
        put(http, path, 9500001, "swr", "ACTIVE", "1.0000", 6);
        for (int i = 0; i < 3; i++)
            assertEquals(6, number(object(call(http, path + "/offers/9500001", HttpMethod.GET, "demo-supplier", null), 200).get("availableUnits")));
        try (var redis = new JedisPooled(redisUri)) {
            var keys = new RedisKeyStrategy(database.config().keyspace().keyPrefix());
            // Fault injection only: simulate a missed invalidation, not a supported application write path.
            redis.set(keys.entityKey("sample-supplier-offers", 9500001),
                    SupplierOfferCacheBinding.CODEC.toRedisValue(new SupplierOffer(9500001L, "swr", "ACTIVE", new BigDecimal("1.0000"), 7)));
            Thread.sleep(2200);
            assertEquals(6, number(object(call(http, path + "/offers/9500001", HttpMethod.GET, "demo-supplier", null), 200).get("availableUnits")));
            await(() -> number(object(call(http, path + "/offers/9500001", HttpMethod.GET, "demo-supplier", null), 200).get("availableUnits")) == 7);
            try (var coordinator = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis), new RedisKeyStrategy("sample-scan-" + UUID.randomUUID()))) {
                var cache = coordinator.register("bounded-test", new NearCachePolicy(64, 10, 1_048_576));
                var ids = java.util.stream.IntStream.range(0, 10000).mapToObj(i -> "key" + i)
                        .filter(id -> (id.hashCode() & Integer.MAX_VALUE) % 16 == 0).limit(40).toList();
                for (int i = 0; i < 4; i++) cache.put(ids.get(i), "v", -1, cache.epoch());
                for (int i = 0; i < 12; i++) assertEquals("v", cache.get(ids.getFirst()));
                for (int i = 4; i < ids.size(); i++) cache.put(ids.get(i), "scan", -1, cache.epoch());
                assertEquals("v", cache.get(ids.getFirst()));
                assertTrue(ids.stream().filter(id -> cache.get(id) != null).count() <= 4);
            }
        }
    }

    private static ResponseEntity<String> put(TestRestTemplate http, String path, long id, String supplier, String status, String price, int units) {
        var response = call(http, path + "/offers/" + id, HttpMethod.PUT, "demo-supplier", offer(supplier, status, price, units));
        assertEquals(202, response.getStatusCode().value(), response.getBody()); return response;
    }
    private static Map<String, Object> offer(String supplier, String status, String price, int units) {
        return Map.of("supplier", supplier, "status", status, "unitPrice", new BigDecimal(price), "availableUnits", units);
    }
    private static Map<String, Object> stock(long id, String warehouse, int units) {
        return Map.of("stockId", id, "warehouse", warehouse, "status", "AVAILABLE", "availableUnits", units);
    }
    private static HttpHeaders auth(String token) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token); return headers;
    }
    private static ResponseEntity<String> call(TestRestTemplate http, String url, HttpMethod method, String token, Object body) {
        return http.exchange(url, method, new HttpEntity<>(body, auth(token)), String.class);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(ResponseEntity<String> response, int status) {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return new com.google.gson.Gson().fromJson(response.getBody(), Map.class);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ResponseEntity<String> response, int status) {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return new com.google.gson.Gson().fromJson(response.getBody(), List.class);
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static void transport(TestRestTemplate http) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        http.getRestTemplate().setRequestFactory(factory);
    }
    private static void await(BooleanSupplier condition) {
        long end = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(100); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new AssertionError(stop); }
        }
        fail("Bounded SQL/Redis operation did not complete");
    }
}
