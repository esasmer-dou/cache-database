package com.example.cachedb.sample.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** The same REST contract runs unchanged against all three real SQL providers. */
public final class CatalogScenarios {
    private CatalogScenarios() {}

    public static void verify(TestRestTemplate http, String base, JdbcTemplate sql) {
        var rows =
                List.of(
                        row(9_000_001, "feature-a"),
                        row(9_000_002, "feature-a"),
                        row(9_000_003, "feature-b"),
                        row(9_000_004, "feature-b"));
        var command = Map.of("batchId", "feature-import", "items", rows);
        var imported = complete(http, base, post(http, base + "/api/catalog/imports", command));
        assertEquals("ACCEPTED", ((Map<?, ?>) imported.get("result")).get("outcome"));
        // A bounded runtime wait may legitimately return pending on a cold SQL provider.
        await(
                () ->
                        sql.queryForObject(
                                        "SELECT COUNT(*) FROM sample_products WHERE product_id"
                                                + " BETWEEN 9000001 AND 9000004",
                                        Integer.class)
                                == 4);
        var receipt = ((Map<?, ?>) imported.get("result")).get("receipt");
        var replay = complete(http, base, post(http, base + "/api/catalog/imports", command));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) replay.get("result")).get("durable"));
        assertEquals(receipt, ((Map<?, ?>) replay.get("result")).get("receipt"));
        assertEquals(
                4,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM sample_products WHERE product_id BETWEEN 9000001 AND"
                                + " 9000004",
                        Integer.class));

        var invalid =
                post(
                        http,
                        base + "/api/catalog/imports",
                        Map.of("batchId", "empty", "items", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        var extremePrice =
                post(
                        http,
                        base + "/api/catalog/imports",
                        """
                        {"batchId":"extreme-price","items":[{"productId":9000099,"sku":"INVALID",
                        "name":"Invalid price","category":"feature-a","unitPrice":1e2147483647,
                        "stock":1,"reserved":0,"updatedAt":1700000000}]}
                        """);
        assertEquals(HttpStatus.BAD_REQUEST, extremePrice.getStatusCode());
        var changed =
                terminal(
                        http,
                        base,
                        post(
                                http,
                                base + "/api/catalog/imports",
                                Map.of(
                                        "batchId",
                                        "feature-import",
                                        "items",
                                        List.of(row(9_000_005, "feature-a")))));
        assertEquals("COMPLETED", changed.get("status"));
        assertEquals("REJECTED", ((Map<?, ?>) changed.get("result")).get("outcome"));
        assertEquals(
                0,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM sample_products WHERE product_id = 9000005",
                        Integer.class));

        complete(http, base, post(http, base + "/api/catalog/refresh", Map.of("ids", List.of())));
        var one = http.getForEntity(base + "/api/catalog/items/9000001", Map.class);
        assertEquals(HttpStatus.OK, one.getStatusCode());
        assertEquals("FOUND", one.getBody().get("status"));
        var card = (Map<?, ?>) ((List<?>) one.getBody().get("values")).getFirst();
        assertEquals("\u0130\u011fne \u00e7elik", card.get("name"));
        assertEquals(6, ((Number) card.get("available")).intValue());
        assertFalse(one.getBody().containsKey("rawPayload"));
        assertEquals(
                HttpStatus.NOT_FOUND,
                http.getForEntity(base + "/api/catalog/items/99009900", Map.class).getStatusCode());
        var all = http.getForObject(base + "/api/catalog/items?ids=9000001,9000003", Map.class);
        assertEquals(
                ((Map<?, ?>) all.get("9000001")).get("generation"),
                ((Map<?, ?>) all.get("9000003")).get("generation"));

        var page = http.getForObject(base + "/api/catalog/categories/feature-a?limit=1", Map.class);
        var cursor = (String) page.get("nextCursor");
        assertNotNull(cursor);
        sql.update(
                "UPDATE sample_products SET stock_quantity = 11 WHERE product_id = ?", 9_000_003);
        complete(
                http,
                base,
                post(http, base + "/api/catalog/refresh", Map.of("ids", List.of(9_000_003))));
        assertEquals(
                HttpStatus.OK,
                http.getForEntity(
                                base + "/api/catalog/categories/feature-a?limit=1&after=" + cursor,
                                Map.class)
                        .getStatusCode());
        sql.update(
                "UPDATE sample_products SET stock_quantity = 12 WHERE product_id = ?", 9_000_001);
        complete(
                http,
                base,
                post(http, base + "/api/catalog/refresh", Map.of("ids", List.of(9_000_001))));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                http.getForEntity(
                                base + "/api/catalog/categories/feature-a?limit=1&after=" + cursor,
                                Map.class)
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                http.getForEntity(base + "/api/catalog/categories/feature-a?limit=101", Map.class)
                        .getStatusCode());

        var range = http.getForObject(base + "/api/catalog/categories/feature-a/available?minimum=10&maximum=10&limit=10", Map.class);
        assertEquals(1, ((List<?>) range.get("items")).size());
        assertEquals(HttpStatus.BAD_REQUEST, http.getForEntity(
                base + "/api/catalog/categories/feature-a/available?minimum=10&maximum=1", Map.class).getStatusCode());
        sql.update("UPDATE sample_products SET stock_quantity = 14 WHERE product_id = ?", 9_000_001);
        var marked = post(http, base + "/api/catalog/dirty", Map.of("ids", List.of(9_000_001,9_000_001)));
        assertEquals(HttpStatus.ACCEPTED, marked.getStatusCode());
        assertEquals(1, ((Number) marked.getBody().get("added")).intValue());
        assertEquals(0, ((Number) post(http, base + "/api/catalog/dirty", Map.of("ids", List.of(9_000_001)))
                .getBody().get("added")).intValue());
        complete(http, base, post(http, base + "/api/catalog/dirty/flush", Map.of()));
        var updated = http.getForObject(base + "/api/catalog/items/9000001", Map.class);
        assertEquals(12, ((Number) ((Map<?, ?>) ((List<?>) updated.get("values")).getFirst()).get("available")).intValue());
    }

    private static Map<String, Object> row(long id, String category) {
        return Map.of(
                "productId",
                id,
                "sku",
                "FEATURE-" + id,
                "name",
                "\u0130\u011fne \u00e7elik",
                "category",
                category,
                "unitPrice",
                "12.3456",
                "stock",
                8,
                "reserved",
                2,
                "updatedAt",
                1_700_000_000L);
    }

    private static ResponseEntity<Map> post(TestRestTemplate http, String uri, Object body) {
        return http.exchange(
                RequestEntity.post(URI.create(uri))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                Map.class);
    }

    private static Map complete(TestRestTemplate http, String base, ResponseEntity<Map> response) {
        var result = terminal(http, base, response);
        assertEquals("COMPLETED", result.get("status"), result.toString());
        return result;
    }

    private static Map terminal(TestRestTemplate http, String base, ResponseEntity<Map> response) {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.toString());
        assertNotNull(response.getHeaders().getLocation());
        String path = response.getHeaders().getLocation().getPath();
        await(
                () ->
                        List.of("COMPLETED", "FAILED")
                                .contains(http.getForObject(base + path, Map.class).get("status")));
        return http.getForObject(base + path, Map.class);
    }

    private static void await(BooleanSupplier ready) {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Catalog job did not finish within 30 seconds");
            try {
                Thread.sleep(100);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }
}
