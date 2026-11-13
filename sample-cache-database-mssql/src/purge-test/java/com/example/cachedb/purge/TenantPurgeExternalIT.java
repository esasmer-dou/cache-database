package com.example.cachedb.purge;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cachedb.sample.ExternalSampleFixtures;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@SpringBootTest(classes = TenantPurgeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=application-purge")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(360)
class TenantPurgeExternalIT {
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String TOKEN = "purge_admin_" + UUID.randomUUID().toString().replace("-", "");
    private static final String AES = randomKey(), HMAC = randomKey();
    @LocalServerPort int port;
    @Autowired TenantPurgeRuntime runtime;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry)) throw new IllegalStateException("Disposable fixtures required");
        registry.add("SAMPLE_PURGE_REDIS_URI", () -> Objects.requireNonNull(System.getenv("CACHEDB_TEST_REDIS_URI")));
        registry.add("SAMPLE_PURGE_REDIS_PASSWORD", () -> Objects.requireNonNullElse(
                System.getenv("CACHEDB_TEST_REDIS_PASSWORD"), ""));
        registry.add("SAMPLE_PURGE_RUN_ID", () -> RUN);
        registry.add("SAMPLE_PURGE_ADMIN_TOKEN", () -> TOKEN);
        registry.add("SAMPLE_PURGE_CONFIRM", () -> "CREATE_FRESH_PURGE_DEMO");
        registry.add("SAMPLE_ENCRYPTION_AES_KEY", () -> AES);
        registry.add("SAMPLE_ENCRYPTION_HMAC_KEY", () -> HMAC);
    }

    @Test void nativeInitialHttpPurgeRemovesHotAndColdAndPreservesForeignState() throws Exception {
        assertEquals(401, call("GET", "/status", null, null).statusCode());
        assertEquals(403, call("GET", "/status", null, "not_the_admin").statusCode());
        JsonNode initial = json(call("GET", "/status", null, TOKEN), 200);
        assertEquals("ACTIVE", initial.path("phase").asText());
        assertEquals(2, initial.path("actualRoots").asInt());
        assertEquals("OPEN", initial.path("sqlGate").asText());
        assertEquals("OPEN", initial.path("redisGate").asText());
        assertEquals(1, initial.path("sqlGeneration").asLong());
        assertEquals(1, initial.path("redisGeneration").asLong());
        assertEquals(7, initial.path("ownedSqlRows").asLong());
        assertEquals(2, initial.path("hotOwnedRows").asLong());
        assertTrue(initial.path("coldAbsentFromRedis").asBoolean());
        assertTrue(initial.path("foreignSqlPreserved").asBoolean());
        assertTrue(initial.path("foreignRedisPreserved").asBoolean());
        String foreignSql = runtime.foreignSqlSnapshot(), foreignRedis = runtime.foreignRedisValue();
        assertNotNull(foreignSql); assertNotNull(foreignRedis);
        for (long id = 1; id <= 8; id++) {
            String source = runtime.sourceSecret(id);
            assertNotNull(source);
            assertFalse(source.contains("purge-demo-value-"), "Source must use the real encrypted codec");
        }
        for (long id = 1; id <= 2; id++) {
            assertNotNull(runtime.redisValue(id));
            assertFalse(runtime.redisValue(id).contains("purge-demo-value-"));
            assertTrue(json(call("GET", "/rows/" + id, null, TOKEN), 200).path("present").asBoolean());
        }
        // POINT native enrollment cannot authorize an unknown cold Redis owner: no SQL read-through.
        for (long id = 3; id <= 7; id++) {
            assertNull(runtime.redisValue(id)); assertNotNull(runtime.sourceSecret(id));
        }
        assertEquals(409, call("GET", "/rows/3", null, TOKEN).statusCode());
        assertEquals(404, call("GET", "/rows/8", null, TOKEN).statusCode());
        assertEquals(400, call("POST", "/purge",
                "{\"confirmation\":\"WRONG\",\"operationId\":\"" + UUID.randomUUID() + "\"}", TOKEN).statusCode());
        assertEquals(7, runtime.ownedSqlRows(), "Invalid confirmation must not mutate source");
        UUID operation = UUID.randomUUID();
        String body = "{\"confirmation\":\"ERASE_OWNED_ROWS\",\"operationId\":\"" + operation + "\"}";
        JsonNode complete = json(call("POST", "/purge", body, TOKEN), 200);
        assertEquals("COMPLETE", complete.path("phase").asText());
        assertEquals("CLOSED", complete.path("sqlGate").asText());
        assertEquals("CLOSED", complete.path("redisGate").asText());
        assertEquals(1, complete.path("sqlGeneration").asLong());
        assertEquals(1, complete.path("redisGeneration").asLong());
        assertEquals(0, complete.path("ownedSqlRows").asLong());
        assertEquals(0, complete.path("hotOwnedRows").asLong());
        assertEquals(7, complete.path("purgedSqlRows").asLong());
        assertTrue(complete.path("securityMetadataRetained").asBoolean());
        assertTrue(runtime.ownedRedisRemoved(), "Actual owned payload, owner, version and index metadata must be absent");
        for (long id = 1; id <= 7; id++) assertNull(runtime.sourceSecret(id));
        assertEquals(foreignSql, runtime.foreignSqlSnapshot());
        assertEquals(foreignRedis, runtime.foreignRedisValue());
        assertFalse(foreignRedis.contains("purge-demo-value-"));
        assertTrue(complete.path("foreignSqlPreserved").asBoolean());
        assertTrue(complete.path("foreignRedisPreserved").asBoolean());
        var blocked = assertThrows(SQLException.class, runtime::assertSourceWriteClosed);
        assertEquals("CDBGC", blocked.getSQLState(), "A valid transaction must be rejected by the closed SQL gate");
        assertEquals(409, call("GET", "/rows/1", null, TOKEN).statusCode());
        assertEquals(404, call("POST", "/reopen", "{}", TOKEN).statusCode());
        JsonNode retained = json(call("GET", "/status", null, TOKEN), 200);
        assertEquals("CLOSED", retained.path("sqlGate").asText());
        assertEquals("CLOSED", retained.path("redisGate").asText());
        assertTrue(runtime.ownedRedisRemoved());
        assertEquals(foreignSql, runtime.foreignSqlSnapshot());
        assertEquals(foreignRedis, runtime.foreignRedisValue());
    }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + TenantPurgeHttp.ROOT + path))
                .timeout(Duration.ofSeconds(90)).header("X-Purge-Run", RUN);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), "Redacted HTTP status");
        assertFalse(response.body().contains(TOKEN)); assertFalse(response.body().contains(AES));
        assertFalse(response.body().contains(HMAC)); assertNull(response.headers().firstValue("location").orElse(null));
        return mapper.readTree(response.body());
    }
    private static String randomKey() {
        byte[] value = new byte[32]; new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }
    @AfterEach void closeHttpClient() { http.close(); }
}
