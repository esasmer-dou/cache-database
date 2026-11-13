package com.example.cachedb.sample.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.api.BulkDeleteFailureException;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.retention.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuditContractTest {
    private static WriteReceipt<DeleteAuditNote, String> deleted(String id, long version) {
        return new WriteReceipt<>(null, id, "sample-audit-delete", OperationType.DELETE, version, Instant.EPOCH);
    }
    @Test void derivedDeleteReturnsSelectionNotInventedReceiptsAndDoesNotRepeatMutation() {
        var h = new AuditTestHarness();
        var row = new DeleteAuditNote("audit-1", "expired", LocalDate.of(2000, 1, 1), "EXPIRED");
        when(h.data.deleteByState("EXPIRED")).thenReturn(List.of(row));
        var response = h.controller.springData("Bearer supplier-test", null);
        var result = response.getBody();
        assertEquals(202, response.getStatusCode().value()); assertEquals(List.of("audit-1"), result.selected());
        assertTrue(result.accepted().isEmpty()); assertEquals("NOT_EXPOSED_BY_SPRING_DATA", result.receiptAvailability());
        assertFalse(result.sqlDurabilityConfirmed()); assertFalse(result.retrySafe());
        verify(h.data, times(1)).deleteByState("EXPIRED");
        verify(h.delete).entityQueryCapacity(); verifyNoMoreInteractions(h.delete);
        verifyNoInteractions(h.database, h.deleteSource);
    }
    @Test void derivedPartialFailurePreservesAllCategoriesWithoutRetry() {
        var h = new AuditTestHarness();
        var receipt = deleted("audit-1", Long.MAX_VALUE);
        var failure = new BulkDeleteFailureException(BulkDeleteFailureException.Phase.MUTATION,
                List.of(receipt), List.of("audit-4"), List.of("audit-2"), List.of("audit-3"), 2);
        when(h.data.deleteByState("EXPIRED")).thenThrow(failure);
        var response = h.controller.springData("Bearer supplier-test", null);
        var result = response.getBody();
        assertEquals(409, response.getStatusCode().value()); assertEquals("MUTATION", result.phase());
        assertEquals(Long.toString(Long.MAX_VALUE), result.accepted().getFirst().version());
        assertEquals(List.of("audit-4"), result.rejected()); assertEquals(List.of("audit-2"), result.uncertain());
        assertEquals(List.of("audit-3"), result.cleanupPending()); assertEquals(2, result.notAttempted());
        assertFalse(result.sqlDurabilityConfirmed()); assertFalse(result.retrySafe());
        verify(h.data, times(1)).deleteByState("EXPIRED");
        verify(h.delete).entityQueryCapacity(); verifyNoMoreInteractions(h.delete); verifyNoInteractions(h.database);
    }
    @Test void nativeBulkExposesActualReceiptsAndBoundsBeforeIo() {
        var h = new AuditTestHarness();
        when(h.delete.bulkDelete(List.of("audit-1", "audit-2"))).thenReturn(List.of(deleted("audit-1", 41), deleted("audit-2", 42)));
        var response = h.controller.bulk("Bearer supplier-test", null, new AuditNoteController.Slots(List.of(1, 2)));
        assertEquals(202, response.getStatusCode().value());
        assertEquals(List.of("41", "42"), response.getBody().accepted().stream().map(AuditNoteController.Receipt::version).toList());
        assertEquals("ACTUAL_WRITE_RECEIPTS", response.getBody().receiptAvailability()); assertFalse(response.getBody().sqlDurabilityConfirmed());
        assertThrows(IllegalArgumentException.class, () -> h.controller.bulk("Bearer supplier-test", null, new AuditNoteController.Slots(List.of(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> h.controller.bulk("Bearer supplier-test", null, new AuditNoteController.Slots(List.of(9))));
        verify(h.delete, times(1)).bulkDelete(any()); verifyNoInteractions(h.data, h.database);
    }
    @Test void nativeCleanupFailureCannotBecomeSuccessfulOrTriggerFallback() {
        var h = new AuditTestHarness();
        when(h.delete.bulkDelete(any())).thenThrow(new BulkDeleteFailureException(BulkDeleteFailureException.Phase.CLEANUP,
                List.of(deleted("audit-1", 7)), List.of(), List.of(), List.of("audit-1"), 0));
        var result = h.controller.bulk("Bearer supplier-test", null, new AuditNoteController.Slots(List.of(1)));
        assertEquals(409, result.getStatusCode().value()); assertEquals("CLEANUP", result.getBody().phase());
        assertEquals(List.of("audit-1"), result.getBody().cleanupPending());
        verify(h.delete, times(1)).bulkDelete(any()); verifyNoMoreInteractions(h.delete); verifyNoInteractions(h.data);
    }
    @Test void sweeperUsesActualAdministrativeScopeAndRestoresCallerOnBothPaths() {
        var h = new AuditTestHarness();
        var evict = h.controller.sweep("Bearer supplier-test", null, "evict", new AuditNoteController.SweepInput(1));
        assertEquals("EVICT_CACHE", evict.action()); assertEquals(1, evict.evicted()); assertEquals(0, evict.deleteAccepted());
        var delete = h.controller.sweep("Bearer supplier-test", null, "delete", new AuditNoteController.SweepInput(8));
        assertEquals("DELETE", delete.action()); assertEquals(1, delete.deleteAccepted()); assertEquals(0, delete.evicted());
        assertTrue(delete.indexedResidentOnly()); assertFalse(delete.sourceErasureConfirmed()); assertFalse(delete.sqlDurabilityConfirmed());
        assertFalse(CacheTenantContext.isAdministrative()); assertTrue(CacheTenantContext.currentTenant().isEmpty());
        h.sweepFailure = new RetentionException(RetentionException.Code.STORAGE_FAILURE);
        assertThrows(RetentionException.class, () -> h.controller.sweep("Bearer supplier-test", null, "delete", new AuditNoteController.SweepInput(2)));
        assertFalse(CacheTenantContext.isAdministrative());
        assertThrows(IllegalArgumentException.class, () -> h.controller.sweep("Bearer supplier-test", null, "evict", new AuditNoteController.SweepInput(9)));
        assertEquals(3, h.sweeps.get());
    }
    @Test void supplierTenantAndCausalGuardsFailBeforeAnyRepositoryMutation() {
        var h = new AuditTestHarness();
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> h.controller.springData("Bearer tenant-a-test", null)).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> h.controller.springData(null, null)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> h.controller.springData("Bearer supplier-test", "required")).getStatusCode().value());
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> h.controller.springData("Bearer supplier-test", null)).getStatusCode().value());
        }
        try (var admin = CacheTenantContext.administrative()) {
            assertThrows(ResponseStatusException.class, () -> h.controller.springData("Bearer supplier-test", null));
        }
        try (var requirement = CacheCausalContext.withMinVersion("sample-audit-delete", "audit-1", 1)) {
            assertThrows(ResponseStatusException.class, () -> h.controller.springData("Bearer supplier-test", null));
        }
        try (var empty = CacheCausalContext.empty()) {
            when(h.data.deleteByState("EXPIRED")).thenReturn(List.of());
            assertEquals(202, h.controller.springData("Bearer supplier-test", null).getStatusCode().value());
        }
        verify(h.data, times(1)).deleteByState("EXPIRED");
        verify(h.delete).entityQueryCapacity(); verifyNoMoreInteractions(h.delete); verifyNoInteractions(h.evict, h.database);
    }
    @Test void auditRowsAreBoundedUnicodeAndMetadataContainsBothRealPolicies() {
        var date = LocalDate.of(2000, 1, 1);
        assertDoesNotThrow(() -> new EvictAuditNote("audit-8", "é".repeat(256), date, "EXPIRED"));
        for (String note : List.of("", "\0", "\uD800", "é".repeat(257)))
            assertThrows(IllegalArgumentException.class, () -> new EvictAuditNote("audit-1", note, date, "EXPIRED"));
        assertThrows(IllegalArgumentException.class, () -> new DeleteAuditNote("audit-9", "note", date, "ACTIVE"));
        assertEquals(RetentionPolicy.Action.EVICT_CACHE, EvictAuditNoteCacheBinding.METADATA.retentionPolicy().orElseThrow().action());
        assertEquals(RetentionPolicy.Action.DELETE, DeleteAuditNoteCacheBinding.METADATA.retentionPolicy().orElseThrow().action());
        EvictAuditNoteCacheBinding.METADATA.retentionPolicy().orElseThrow().validate(EvictAuditNoteCacheBinding.METADATA);
        DeleteAuditNoteCacheBinding.METADATA.retentionPolicy().orElseThrow().validate(DeleteAuditNoteCacheBinding.METADATA);
        assertFalse(DeleteAuditNoteCacheBinding.METADATA.tenantPurgeEnabled());
    }
    @Test void acceptedWriteTimeoutIsNotRetriedAndNeverClaimsSourceConfirmation() {
        var h = new AuditTestHarness();
        when(h.delete.saveWithReceipt(any())).thenAnswer(call -> {
            DeleteAuditNote row = call.getArgument(0);
            return new WriteReceipt<>(row, row.id(), "sample-audit-delete", OperationType.UPSERT, 10, Instant.EPOCH);
        });
        var error = assertThrows(ResponseStatusException.class, () -> h.controller.sourceRoundtrip("Bearer supplier-test", null,
                "delete", 1, new AuditNoteController.Input("accepted", true)));
        assertEquals(504, error.getStatusCode().value());
        assertEquals("10", ((AuditNoteController.WriteUnconfirmed) error).accepted().version());
        verify(h.delete, times(1)).saveWithReceipt(any()); verify(h.database, times(1)).awaitDurable(any(WriteReceipt.class), eq(Duration.ofSeconds(5)));
        verifyNoInteractions(h.deleteSource);
    }
}
