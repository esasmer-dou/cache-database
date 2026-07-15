package com.example.cachedb.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresqlSampleIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    ).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cachedb_sample")
            .withUsername("cachedb")
            .withPassword("cachedb");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:8.2.1-alpine3.22@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232"
    )
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory", "256mb",
                    "--maxmemory-policy", "noeviction");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("cachedb.redis.uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("cachedb.admin.enabled", () -> false);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void productionContractsWorkAgainstRealPostgresqlAndRedis() {
        jdbcTemplate.update(
                "INSERT INTO sample_customers (customer_id, tax_number, customer_type, segment, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                777L, "SQL-ONLY-777", "RETAIL", "STANDARD", "ACTIVE", 1L, 1L
        );
        ResponseEntity<Map> coldSqlCustomer = rest.getForEntity(url("/api/customers/777?orderPreview=1"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, coldSqlCustomer.getStatusCode());

        ResponseEntity<Map> seed = rest.postForEntity(
                url("/api/demo/seed?customers=2&ordersPerCustomer=2&linesPerOrder=2"),
                null,
                Map.class
        );
        assertEquals(HttpStatus.OK, seed.getStatusCode());

        ResponseEntity<List> activeCustomers = rest.getForEntity(url("/api/customers/active?limit=10"), List.class);
        assertEquals(HttpStatus.OK, activeCustomers.getStatusCode());
        assertEquals(2, activeCustomers.getBody().size());

        ResponseEntity<Map> customerDetail = rest.getForEntity(
                url("/api/customers/1?orderPreview=2"),
                Map.class
        );
        assertEquals(HttpStatus.OK, customerDetail.getStatusCode());
        assertEquals(2, ((List<?>) customerDetail.getBody().get("orders")).size());

        ResponseEntity<Map> orderDetail = rest.getForEntity(
                url("/api/orders/10001?linePreview=2"),
                Map.class
        );
        assertEquals(HttpStatus.OK, orderDetail.getStatusCode());
        assertEquals(2, ((List<?>) orderDetail.getBody().get("lines")).size());

        ResponseEntity<List> policyProfiles = rest.getForEntity(url("/api/tuning/profiles"), List.class);
        assertEquals(HttpStatus.OK, policyProfiles.getStatusCode());
        Map<?, ?> commercePolicy = (Map<?, ?>) policyProfiles.getBody().stream()
                .filter(profile -> "commerceTimeline".equals(((Map<?, ?>) profile).get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(100_000, ((Number) commercePolicy.get("hotEntityLimit")).intValue());

        ResponseEntity<Map> oversizedRoute = rest.getForEntity(url("/api/customers/active?limit=101"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, oversizedRoute.getStatusCode());

        ResponseEntity<Map> invalidCustomer = postJson(
                "/api/customers",
                Map.of("customerId", 900L, "customerType", "RETAIL")
        );
        assertEquals(HttpStatus.BAD_REQUEST, invalidCustomer.getStatusCode());

        ResponseEntity<Map> missingParentOrder = postJson(
                "/api/orders",
                Map.of(
                        "orderId", 900L,
                        "customerId", 999_999L,
                        "orderAmount", "125.4500",
                        "currencyCode", "USD"
                )
        );
        assertEquals(HttpStatus.CONFLICT, missingParentOrder.getStatusCode());
        assertEquals("1", missingParentOrder.getHeaders().getFirst("Retry-After"));

        ResponseEntity<Map> acceptedOrder = postJson(
                "/api/orders",
                Map.of(
                        "orderId", 901L,
                        "customerId", 1L,
                        "orderAmount", "125.4500",
                        "currencyCode", "USD"
                )
        );
        assertEquals(HttpStatus.ACCEPTED, acceptedOrder.getStatusCode());
        assertEquals("WRITE_BEHIND_ACCEPTED", acceptedOrder.getBody().get("status"));
        await(Duration.ofSeconds(15), () -> rowCount("sample_orders", "order_id", 901L) == 1);
        BigDecimal durableAmount = jdbcTemplate.queryForObject(
                "SELECT order_amount FROM sample_orders WHERE order_id = ?",
                BigDecimal.class,
                901L
        );
        assertEquals(0, new BigDecimal("125.4500").compareTo(durableAmount));

        ResponseEntity<List> firstArchivePage = rest.getForEntity(
                url("/api/orders/archive?customerId=1&limit=1"),
                List.class
        );
        assertEquals(HttpStatus.OK, firstArchivePage.getStatusCode());
        Map firstArchiveRow = (Map) firstArchivePage.getBody().getFirst();
        ResponseEntity<List> secondArchivePage = rest.getForEntity(
                url("/api/orders/archive?customerId=1&beforeOrderDate=" + firstArchiveRow.get("orderDate")
                        + "&beforeOrderId=" + firstArchiveRow.get("orderId") + "&limit=1"),
                List.class
        );
        assertEquals(HttpStatus.OK, secondArchivePage.getStatusCode());
        assertNotEquals(firstArchiveRow.get("orderId"), ((Map) secondArchivePage.getBody().getFirst()).get("orderId"));

        ResponseEntity<Map> deleteParentWithChildren = rest.exchange(
                url("/api/orders/10001"),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Map.class
        );
        assertEquals(HttpStatus.CONFLICT, deleteParentWithChildren.getStatusCode());
        assertEquals(1, rowCount("sample_orders", "order_id", 10_001L));

        ResponseEntity<Map> deleteLeafOrder = rest.exchange(
                url("/api/orders/901"),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Map.class
        );
        assertEquals(HttpStatus.ACCEPTED, deleteLeafOrder.getStatusCode());
        await(Duration.ofSeconds(15), () -> rowCount("sample_orders", "order_id", 901L) == 0);

        ResponseEntity<Map> readiness = rest.getForEntity(url("/api/health/ready"), Map.class);
        assertEquals(HttpStatus.OK, readiness.getStatusCode());
        assertEquals(Boolean.TRUE, readiness.getBody().get("ready"));
        assertNotNull(readiness.getBody().get("database"));

        ResponseEntity<Map> warmAccepted = rest.postForEntity(
                url("/api/warm/orders/customer/1?limit=2&projectionOnly=true"),
                null,
                Map.class
        );
        assertEquals(HttpStatus.ACCEPTED, warmAccepted.getStatusCode());
        String jobId = String.valueOf(warmAccepted.getBody().get("jobId"));
        await(Duration.ofSeconds(15), () -> {
            ResponseEntity<Map> job = rest.getForEntity(url("/api/warm/jobs/" + jobId), Map.class);
            return job.getStatusCode().is2xxSuccessful()
                    && List.of("COMPLETED", "FAILED").contains(job.getBody().get("status"));
        });
        ResponseEntity<Map> warmJob = rest.getForEntity(url("/api/warm/jobs/" + jobId), Map.class);
        assertEquals("COMPLETED", warmJob.getBody().get("status"));
    }

    private ResponseEntity<Map> postJson(String path, Map<String, ?> body) {
        RequestEntity<Map<String, ?>> request = RequestEntity.post(URI.create(url(path)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        return rest.exchange(request, Map.class);
    }

    private int rowCount(String table, String idColumn, long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?",
                Integer.class,
                id
        );
        return count == null ? 0 : count;
    }

    private void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for asynchronous write", exception);
            }
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous condition");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
