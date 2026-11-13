package com.example.cachedb.purge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.starter.CacheDataProtection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;

@Timeout(10)
class TenantPurgeContractTest {
    private static final String RUN = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN = "test_admin_0123456789abcdef0123456789abcdef";
    private final TenantPurgeRuntime runtime = mock(TenantPurgeRuntime.class);
    private final TenantPurgeHttp filter = new TenantPurgeHttp(runtime, new ObjectMapper(), TOKEN, RUN);

    @Test void initializationDiagnosticsExposeOnlyFixedStageAndSafeErrorCodes() {
        String sensitive = "jdbc:vendor://private-host/database?password=do-not-log";
        var failures = List.of(
                new java.sql.SQLException(sensitive, "42000", 942),
                new java.sql.SQLException(sensitive, sensitive, 17),
                new IllegalStateException(sensitive, new RuntimeException(sensitive)),
                new ProtectionException(ProtectionException.Code.INDEX_REQUIRED));
        var codes = List.of("SQL_42000_942", "SQL_UNKNOWN_17", "UNEXPECTED", "INDEX_REQUIRED");
        for (int i = 0; i < failures.size(); i++) {
            var result = TenantPurgeRuntime.initializationFailure(
                    TenantPurgeRuntime.InitializationStage.SQL_GUARD, failures.get(i));
            assertTrue(result.getMessage().contains("stage=SQL_GUARD, code=" + codes.get(i)));
            assertFalse(result.getMessage().contains(sensitive));
            assertNull(result.getCause()); assertEquals(0, result.getSuppressed().length);
        }
    }

    @Test void fixedEntityUsesGeneratedTenantEncryptedNativeMapping() {
        var metadata = TenantPurgeRowCacheBinding.METADATA;
        assertEquals("sample_purge_rows", metadata.tableName());
        assertEquals(TenantPurgeRuntime.NS, metadata.redisNamespace());
        assertTrue(metadata.tenantPurgeEnabled());
        byte[] hmacKey = new byte[32];
        Arrays.fill(hmacKey, (byte) 1);
        var material = KeyMaterial.fromBase64("purge-v1",
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(hmacKey));
        var codec = TenantPurgeRowCacheBinding.CODEC.bindEncryption(new StaticKeyProvider(
                Map.of("sample-purge", List.of(material)), Map.of("sample-purge", "purge-v1")));
        var row = new TenantPurgeRow(1L, "purge-" + RUN + "-a", "purge-demo-value-1");
        String sql = codec.toSourceColumns(row).get("secret").toString();
        String redis = codec.toRedisValue(row);
        assertNotEquals(row.secret(), sql);
        assertFalse(redis.contains(row.secret()));
        assertEquals(row, codec.fromRedisValue(redis));
        assertFalse(row.toString().contains(row.secret()));
        assertThrows(IllegalArgumentException.class, () -> new TenantPurgeRow(9L, row.tenant(), row.secret()));
        assertThrows(IllegalArgumentException.class, () -> new TenantPurgeRow(1L, "arbitrary", row.secret()));
    }

    @Test void httpRejectsAnonymousInvalidAndDuplicateAuthWithoutInvokingRuntime() throws Exception {
        var anonymous = request("GET", "/status");
        anonymous.removeHeader("Authorization");
        assertEquals(401, invoke(anonymous).getStatus());
        var invalid = request("GET", "/status");
        invalid.removeHeader("Authorization"); invalid.addHeader("Authorization", "Bearer wrong");
        var response = invoke(invalid);
        assertEquals(403, response.getStatus());
        assertFalse(response.getContentAsString().contains("wrong"));
        assertFalse(response.getContentAsString().contains(TOKEN));
        var duplicate = request("GET", "/status"); duplicate.addHeader("Authorization", "Bearer " + TOKEN);
        assertEquals(403, invoke(duplicate).getStatus());
        verifyNoInteractions(runtime);
    }

    @Test void httpRejectsRunBodyAndIdEscapesBeforePurge() throws Exception {
        var wrongRun = request("GET", "/status");
        wrongRun.removeHeader("X-Purge-Run"); wrongRun.addHeader("X-Purge-Run", "different");
        assertEquals(400, invoke(wrongRun).getStatus());
        assertEquals(404, invoke(request("GET", "/rows/8")).getStatus());
        var query = request("GET", "/status"); query.setQueryString("tenant=arbitrary");
        assertEquals(400, invoke(query).getStatus());
        for (String json : List.of(
                "{\"confirmation\":\"WRONG\",\"operationId\":\"" + UUID.randomUUID() + "\"}",
                "{\"confirmation\":\"ERASE_OWNED_ROWS\",\"operationId\":\"" + UUID.randomUUID()
                        + "\",\"tenant\":\"other\"}",
                "{\"confirmation\":\"ERASE_OWNED_ROWS\",\"confirmation\":\"ERASE_OWNED_ROWS\",\"operationId\":\""
                        + UUID.randomUUID() + "\"}")) {
            var post = request("POST", "/purge"); post.setContentType("application/json");
            post.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(400, invoke(post).getStatus());
        }
        var large = request("POST", "/purge"); large.setContentType("application/json");
        large.setContent(new byte[513]); assertEquals(413, invoke(large).getStatus());
        verifyNoInteractions(runtime);
    }

    @Test @SuppressWarnings("unchecked")
    void deliveryFlushRemainsInsideNativeCallback() throws Exception {
        AtomicBoolean delivering = new AtomicBoolean(), flushed = new AtomicBoolean();
        doAnswer(call -> {
            delivering.set(true);
            try { ((Consumer<Map<String, Object>>) call.getArgument(1)).accept(Map.of("id", 1, "present", true)); }
            finally { delivering.set(false); }
            assertTrue(flushed.get());
            return null;
        }).when(runtime).deliver(eq(1L), any());
        var response = new MockHttpServletResponse() {
            @Override public void flushBuffer() {
                assertTrue(delivering.get(), "Response must flush before native delivery custody ends");
                flushed.set(true); super.flushBuffer();
            }
        };
        filter.doFilter(request("GET", "/rows/1"), response, (r, s) -> fail("No route escape"));
        assertTrue(flushed.get()); assertFalse(delivering.get());
        assertTrue(response.getContentAsString().contains("\"present\":true"));
    }

    @Test void confirmedPurgeUsesOneExplicitOperationWithoutRetryOrRedirect() throws Exception {
        UUID operation = UUID.randomUUID();
        when(runtime.purge(operation)).thenReturn(new TenantPurgeRuntime.PurgeStatus(
                "COMPLETE", 2, "CLOSED", 1, "CLOSED", 1, 0, 0, true, true, true, 7, 2, true));
        var post = request("POST", "/purge"); post.setContentType("application/json");
        post.setContent(("{\"confirmation\":\"ERASE_OWNED_ROWS\",\"operationId\":\"" + operation + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = invoke(post);
        assertEquals(200, response.getStatus()); assertNull(response.getHeader("Location"));
        assertFalse(response.getContentAsString().contains(TOKEN));
        verify(runtime, times(1)).purge(operation); verifyNoMoreInteractions(runtime);
    }

    @Test void sdkFailureAbandonsOwnershipAndRequiresExplicitSameOperationRecovery() {
        assertPurgeRecovery(new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE), null, true);
        assertPurgeRecovery(new ProtectionException(ProtectionException.Code.LEASE_LOST), null, true);
        assertPurgeRecovery(new AssertionError("SDK failure"), null, true);
    }

    @Test void cleanupFailureIsSuppressedWithoutKeepingInvalidContinuation() {
        assertPurgeRecovery(new IllegalStateException("SDK failure"),
                new IllegalStateException("Cleanup failure"), true);
        assertPurgeRecovery(new IllegalStateException("SDK failure"), new AssertionError("Cleanup failure"), true);
        var sameFailure = new IllegalStateException("Shared failure");
        assertPurgeRecovery(sameFailure, sameFailure, true);
    }

    @Test void initialSdkFailureDoesNotInventOwnershipOrRetry() {
        assertPurgeRecovery(new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE), null, false);
    }

    private void assertPurgeRecovery(Throwable failure, Throwable cleanup, boolean retainedOwnership) {
        var priorCleanup = new IllegalStateException("SDK cleanup failure");
        failure.addSuppressed(priorCleanup);
        var sdk = mock(CacheDataProtection.class);
        var target = mock(TenantPurgeRuntime.class);
        ReflectionTestUtils.setField(target, "ready", true);
        ReflectionTestUtils.setField(target, "tenant", "purge-" + RUN + "-a");
        ReflectionTestUtils.setField(target, "prefix", "sample-purge-" + RUN);
        ReflectionTestUtils.setField(target, "controlFailure", new AtomicReference<Throwable>());
        ReflectionTestUtils.setField(target, "facade", sdk);
        doCallRealMethod().when(target).purge(any(UUID.class));
        var closed = new TenantPurgeRuntime.PurgeStatus(
                "COMPLETE", 2, "CLOSED", 1, "CLOSED", 1, 0, 0, true, true, true, 7, 2, true);
        when(target.status()).thenReturn(closed);
        UUID operation = UUID.randomUUID();
        var key = ProtectionKey.of(TenantPurgeRuntime.NS, target.tenant);
        var continuation = new CacheDataProtection.Continuation(TenantPurgeRow.class,
                new Lease(key, operation, UUID.randomUUID(), 1, 120));
        var running = purgeResult(operation, key, CacheDataProtection.Status.RUNNING, continuation);
        var complete = purgeResult(operation, key, CacheDataProtection.Status.COMPLETE, null);
        var invocation = when(sdk.purgeTenant(eq(TenantPurgeRow.class), eq(target.tenant),
                any(PurgeOptions.class), nullable(CacheDataProtection.Continuation.class)));
        if (retainedOwnership) invocation = invocation.thenReturn(running);
        invocation.thenThrow(failure).thenReturn(complete);
        doAnswer(call -> {
            assertTrue(CacheTenantContext.isAdministrative());
            if (cleanup != null) throw cleanup;
            return null;
        }).when(sdk).abandon(continuation);

        assertSame(failure, assertThrows(failure.getClass(), () -> target.purge(operation)));
        assertFalse(CacheTenantContext.isAdministrative());
        assertNull(ReflectionTestUtils.getField(target, "continuation"));
        assertEquals(operation, ReflectionTestUtils.getField(target, "operation"));
        assertSame(retainedOwnership ? running : null, ReflectionTestUtils.getField(target, "last"));
        assertArrayEquals(cleanup == null || cleanup == failure
                        ? new Throwable[] { priorCleanup } : new Throwable[] { priorCleanup, cleanup },
                failure.getSuppressed());
        var calls = inOrder(sdk);
        calls.verify(sdk).purgeTenant(eq(TenantPurgeRow.class), eq(target.tenant), any(PurgeOptions.class), isNull());
        if (retainedOwnership) {
            calls.verify(sdk).purgeTenant(eq(TenantPurgeRow.class), eq(target.tenant),
                    any(PurgeOptions.class), same(continuation));
            calls.verify(sdk).abandon(continuation);
        }
        calls.verifyNoMoreInteractions();
        assertThrows(IllegalArgumentException.class, () -> target.purge(UUID.randomUUID()));
        calls.verifyNoMoreInteractions();

        assertSame(closed, target.purge(operation));
        calls.verify(sdk).purgeTenant(eq(TenantPurgeRow.class), eq(target.tenant), any(PurgeOptions.class), isNull());
        assertSame(complete, ReflectionTestUtils.getField(target, "last"));
        assertNull(ReflectionTestUtils.getField(target, "continuation"));
        assertSame(closed, target.purge(operation));
        calls.verifyNoMoreInteractions();
        var options = ArgumentCaptor.forClass(PurgeOptions.class);
        verify(sdk, times(retainedOwnership ? 3 : 2)).purgeTenant(eq(TenantPurgeRow.class), eq(target.tenant),
                options.capture(), nullable(CacheDataProtection.Continuation.class));
        for (var value : options.getAllValues()) {
            assertEquals(operation, value.operationId());
            assertEquals(target.tenant, value.confirmation());
            assertTrue(value.includeSource());
            assertEquals(PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA, value.scope());
        }
        verifyNoMoreInteractions(sdk);
    }

    private CacheDataProtection.Result purgeResult(UUID operation, ProtectionKey key,
            CacheDataProtection.Status status, CacheDataProtection.Continuation continuation) {
        var receipt = new PurgeReceipt(operation, key, 1, 0,
                status == CacheDataProtection.Status.COMPLETE ? Stage.COMPLETE : Stage.REDIS,
                0, 0, 0, true, java.time.Instant.EPOCH,
                status == CacheDataProtection.Status.COMPLETE ? java.time.Instant.EPOCH : null,
                ProtectionKey.hash("sample-admin:sample-purge-" + RUN), 0);
        return new CacheDataProtection.Result(status, receipt, 0, null, continuation);
    }

    @Test void postmanHasEightRequestsOneMutationAndNoRetryOrRedirect() throws Exception {
        var mapper = new ObjectMapper();
        var collection = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
                "postman", "cache-database-mssql-tenant-purge.postman_collection.json")));
        var expected = List.of("01 Anonymous Status", "02 Invalid Bearer Status", "03 Native INITIAL Status", "04 Owned Hot Row", "05 Owned Cold Row Fails Closed", "06 Purge Owned Tenant Including Source", "07 Retained CLOSED Status", "08 Closed Delivery Rejected");
        assertEquals(8, collection.path("item").size());
        int mutations = 0, index = 0;
        for (var item : collection.path("item")) {
            assertEquals(expected.get(index++), item.path("name").asText());
            var request = item.path("request");
            if ("POST".equals(request.path("method").asText())) {
                mutations++;
                assertEquals("{{baseUrl}}" + TenantPurgeHttp.ROOT + "/purge", request.path("url").asText());
            }
        }
        assertEquals(1, mutations);
        String json = collection.toString();
        for (String forbidden : List.of("setNextRequest", "sendRequest", "console.", "while (", "for ("))
            assertFalse(json.contains(forbidden));
        String runner = java.nio.file.Files.readString(java.nio.file.Path.of("scripts", "test-tenant-purge-postman.ps1"));
        assertTrue(runner.contains("'--ignore-redirects'"));
        assertTrue(runner.contains("'--timeout-request','90000'"));
        assertTrue(runner.contains("'--timeout-script','25000'"));
        assertFalse(runner.contains("--iteration-count"));
        assertFalse(runner.contains("--export-environment"));
    }

    @Test void schemaRequiresCanonicalF3MigrationsAndTenantIndexes() throws Exception {
        String config;
        try (var input = getClass().getResourceAsStream("/application-purge.yml")) {
            assertNotNull(input); config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String migration : List.of("V014__durable_write_fence.sql", "V019__tenant_protection.sql",
                "V019_1__purge_facade.sql", "V019_2__purge_list_scan_kind.sql",
                "V019_3__source_owner_onboarding.sql", "V019_4__initial_source_bootstrap.sql",
                "V019_5__namespace_bootstrap_once.sql")) {
            String resource = "com/reactor/cachedb/jdbc/migrations/mssql/" + migration;
            assertTrue(config.contains("classpath:" + resource));
            assertNotNull(getClass().getClassLoader().getResource(resource), "Current F3 migration must be in the SDK");
        }
        try (var input = getClass().getResourceAsStream("/schema-purge.sql")) {
            assertNotNull(input);
            String ddl = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains("CREATE TABLE sample_purge_rows"));
            assertTrue(ddl.contains("sample_purge_rows(tenant_id,id)"));
            assertTrue(ddl.contains("sample_purge_rows(tenant_id,entity_version)"));
            assertFalse(ddl.contains("DROP ")); assertFalse(ddl.contains("TRUNCATE "));
        }
    }

    private MockHttpServletRequest request(String method, String suffix) {
        var request = new MockHttpServletRequest(method, TenantPurgeHttp.ROOT + suffix);
        request.addHeader("Authorization", "Bearer " + TOKEN); request.addHeader("X-Purge-Run", RUN);
        return request;
    }
    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> fail("No route escape"));
        return response;
    }
}
