package com.example.cachedb.sample.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.reactor.cachedb.core.api.BulkDeleteFailureException;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.retention.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Timeout(15)
class AuditHttpContractTest {
    @Test void springHttpPreservesSuccessSelectionAndPartialAcceptanceContracts() throws Exception {
        var h = new AuditTestHarness();
        var mvc = MockMvcBuilders.standaloneSetup(h.controller).setControllerAdvice(new AuditErrors())
                .addFilters(new AuditRequestFilter(h.access)).build();
        when(h.data.deleteByState("EXPIRED")).thenReturn(List.of(new DeleteAuditNote("audit-1", "note", LocalDate.of(2000, 1, 1), "EXPIRED")));
        mvc.perform(post("/api/examples/v019/audit-notes/delete/spring-data").header("Authorization", "Bearer supplier-test"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selected[0]").value("audit-1"))
                .andExpect(jsonPath("$.accepted").isEmpty()).andExpect(jsonPath("$.receiptAvailability").value("NOT_EXPOSED_BY_SPRING_DATA"))
                .andExpect(jsonPath("$.sqlDurabilityConfirmed").value(false));
        var receipt = new WriteReceipt<DeleteAuditNote, String>(null, "audit-1", "sample-audit-delete", OperationType.DELETE, 17, Instant.EPOCH);
        when(h.data.deleteByState("EXPIRED")).thenThrow(new BulkDeleteFailureException(BulkDeleteFailureException.Phase.MUTATION,
                List.of(receipt), List.of("audit-3"), List.of("audit-2"), List.of("audit-1"), 1));
        mvc.perform(post("/api/examples/v019/audit-notes/delete/spring-data").header("Authorization", "Bearer supplier-test"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.phase").value("MUTATION"))
                .andExpect(jsonPath("$.accepted[0].version").value("17")).andExpect(jsonPath("$.uncertain[0]").value("audit-2"))
                .andExpect(jsonPath("$.rejected[0]").value("audit-3")).andExpect(jsonPath("$.cleanupPending[0]").value("audit-1"))
                .andExpect(jsonPath("$.notAttempted").value(1)).andExpect(jsonPath("$.retrySafe").value(false));
        verify(h.data, times(2)).deleteByState("EXPIRED");
        verify(h.delete, times(2)).entityQueryCapacity(); verifyNoMoreInteractions(h.delete); verifyNoInteractions(h.database);
    }
    @Test void zeroQueryBudgetIsDeterministic409BeforeGeneratedSelectionOrMutation() throws Exception {
        var h = new AuditTestHarness();
        when(h.delete.entityQueryCapacity()).thenReturn(com.reactor.cachedb.core.api.EntityQueryCapacity.finite(0));
        var mvc = MockMvcBuilders.standaloneSetup(h.controller).setControllerAdvice(new AuditErrors())
                .addFilters(new AuditRequestFilter(h.access)).build();
        mvc.perform(post("/api/examples/v019/audit-notes/delete/spring-data").header("Authorization", "Bearer supplier-test"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("AUDIT_DERIVED_DELETE_ZERO_QUERY_BUDGET"))
                .andExpect(jsonPath("$.sqlDurabilityConfirmed").value(false)).andExpect(jsonPath("$.retrySafe").value(false))
                .andExpect(jsonPath("$.outcomeUnknown").doesNotExist());
        verify(h.delete).entityQueryCapacity(); verifyNoMoreInteractions(h.delete);
        verifyNoInteractions(h.data, h.database, h.deleteSource, h.evict, h.evictSource);
    }
    @Test void retentionErrorNeverBecomesZeroDeleteSuccessOrSqlConfirmation() throws Exception {
        var h = new AuditTestHarness();
        var mvc = MockMvcBuilders.standaloneSetup(h.controller).setControllerAdvice(new AuditErrors())
                .addFilters(new AuditRequestFilter(h.access)).build();
        h.sweepFailure = new RetentionException(RetentionException.Code.STORAGE_FAILURE);
        mvc.perform(post("/api/examples/v019/audit-notes/delete/sweep").header("Authorization", "Bearer supplier-test")
                        .contentType("application/json").content("{\"maxRows\":1}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.outcomeUnknown").value(true))
                .andExpect(jsonPath("$.sourceErasureConfirmed").value(false)).andExpect(jsonPath("$.retrySafe").value(false));
        h.sweepFailure = new RetentionException(RetentionException.Code.BUSY);
        mvc.perform(post("/api/examples/v019/audit-notes/evict/sweep").header("Authorization", "Bearer supplier-test")
                        .contentType("application/json").content("{\"maxRows\":1}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        assertFalse(com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative());
    }
    @Test void filterRejectsTenantAndCausalBeforeReadingBodyAndBoundsChunkedInput() throws Exception {
        var h = new AuditTestHarness(); var filter = new AuditRequestFilter(h.access);
        var denied = new MockHttpServletRequest("POST", "/api/examples/v019/audit-notes/delete/bulk");
        denied.addHeader("Authorization", "Bearer tenant-a-test");
        var unread = new HttpServletRequestWrapper(denied) {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("Unauthorized body was read"); }
        };
        var response = new MockHttpServletResponse();
        filter.doFilter(unread, response, (request, result) -> fail("Tenant request admitted")); assertEquals(403, response.getStatus());
        denied.removeHeader("Authorization"); denied.addHeader("Authorization", "Bearer supplier-test"); denied.addHeader("X-CacheDB-Causal", "required");
        response = new MockHttpServletResponse();
        filter.doFilter(unread, response, (request, result) -> fail("Causal request admitted")); assertEquals(409, response.getStatus());
        var large = new MockHttpServletRequest("POST", "/api/examples/v019/audit-notes/delete/bulk");
        large.addHeader("Authorization", "Bearer supplier-test"); large.setContent(new byte[2049]);
        var chunked = new HttpServletRequestWrapper(large) { @Override public long getContentLengthLong() { return -1; } };
        response = new MockHttpServletResponse();
        filter.doFilter(chunked, response, (request, result) -> fail("Oversized chunked body admitted")); assertEquals(413, response.getStatus());
        verifyNoInteractions(h.delete, h.data);
    }
    @Test void maxRowsSlotsAndPolicyAreRejectedBeforeStorage() throws Exception {
        var h = new AuditTestHarness();
        var mvc = MockMvcBuilders.standaloneSetup(h.controller).setControllerAdvice(new AuditErrors())
                .addFilters(new AuditRequestFilter(h.access)).build();
        mvc.perform(post("/api/examples/v019/audit-notes/delete/sweep").header("Authorization", "Bearer supplier-test")
                .contentType("application/json").content("{\"maxRows\":9}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/examples/v019/audit-notes/delete/bulk").header("Authorization", "Bearer supplier-test")
                .contentType("application/json").content("{\"slots\":[1,1]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/examples/v019/audit-notes/evict/9").header("Authorization", "Bearer supplier-test")
                .contentType("application/json").content("{\"note\":\"note\",\"expired\":true}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/examples/v019/audit-notes/purge/1").header("Authorization", "Bearer supplier-test"))
                .andExpect(status().isBadRequest());
        assertEquals(0, h.sweeps.get()); verifyNoInteractions(h.delete, h.evict, h.data, h.database);
    }
    @Test void eightPhysicalRequestsHoldCapacityUntilTheirChainsExit() throws Exception {
        var h = new AuditTestHarness(); var filter = new AuditRequestFilter(h.access);
        var entered = new CountDownLatch(8); var release = new CountDownLatch(1);
        var pool = Executors.newVirtualThreadPerTaskExecutor(); var work = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 8; i++) work.add(pool.submit(() -> {
                var request = new MockHttpServletRequest("GET", "/api/examples/v019/audit-notes/evict/1");
                request.addHeader("Authorization", "Bearer supplier-test"); var response = new MockHttpServletResponse();
                try {
                    filter.doFilter(request, response, (r, s) -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture release timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    });
                    assertEquals(200, response.getStatus());
                } catch (Exception failure) { throw new AssertionError(failure); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var request = new MockHttpServletRequest("GET", "/api/examples/v019/audit-notes/evict/1");
            request.addHeader("Authorization", "Bearer supplier-test"); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (r, s) -> fail("Ninth request admitted"));
            assertEquals(429, response.getStatus()); assertEquals("1", response.getHeader("Retry-After"));
        } finally {
            release.countDown();
            try { for (var task : work) task.get(5, TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); }
        }
        var request = new MockHttpServletRequest("GET", "/api/examples/v019/audit-notes/evict/1");
        request.addHeader("Authorization", "Bearer supplier-test");
        var response = new MockHttpServletResponse(); var admitted = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(request, response, (r, s) -> admitted.set(true)); assertTrue(admitted.get());
    }
}
