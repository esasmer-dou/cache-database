package com.example.cachedb.sample.audit;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.ExternalSampleFixtures;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.repository.HotLookupStatus;
import com.reactor.cachedb.starter.CacheDatabase;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sample.audit-api.enabled=true", "sample.scheduled-warm.enabled=false",
        "sample.controls.supplier-token=supplier-test", "sample.controls.tenant-a-token=tenant-a-test",
        "sample.controls.tenant-b-token=tenant-b-test"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class AuditExternalIT {
    @DynamicPropertySource static void fixtures(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry)) throw new IllegalStateException("Dedicated provider fixtures required");
    }
    @Autowired AuditStores stores;
    @Autowired CacheDatabase database;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @BeforeEach void resetOnlyTheSixteenFixedDisposableAuditRows() {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(2000); requests.setReadTimeout(35000); rest.getRestTemplate().setRequestFactory(requests);
        var evict = new ArrayList<EvictAuditNote>(); var delete = new ArrayList<DeleteAuditNote>();
        for (int slot = 1; slot <= 8; slot++) {
            evict.add(new EvictAuditNote(AuditNoteRules.id(slot), "baseline", LocalDate.now(ZoneOffset.UTC), "ACTIVE"));
            delete.add(new DeleteAuditNote(AuditNoteRules.id(slot), "baseline", LocalDate.now(ZoneOffset.UTC), "ACTIVE"));
        }
        var receipts = new ArrayList<WriteReceipt<?, ?>>();
        receipts.addAll(stores.evict().saveAll(evict)); receipts.addAll(stores.delete().saveAll(delete));
        assertEquals(16, receipts.size()); assertTrue(database.awaitDurable(receipts, Duration.ofSeconds(15)));
    }
    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON); return headers;
    }
    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return rest.exchange("/api/examples/v019/audit-notes/" + path, HttpMethod.POST,
                new HttpEntity<>(body, headers("supplier-test")), type);
    }
    private long sqlCount(String table, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE note_id = ?", Long.class, id);
    }
    private static void eventually(BooleanSupplier observed) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do { if (observed.getAsBoolean()) return; Thread.sleep(50); } while (System.nanoTime() < deadline);
        assertTrue(observed.getAsBoolean(), "Bounded source observation was not reached");
    }
    private void expiredDelete(int... slots) {
        var rows = new ArrayList<DeleteAuditNote>();
        for (int slot : slots) rows.add(new DeleteAuditNote(AuditNoteRules.id(slot), "expired-ğışöüİ",
                LocalDate.now(ZoneOffset.UTC).minusDays(2), "EXPIRED"));
        assertTrue(database.awaitDurable(stores.delete().saveAll(rows), Duration.ofSeconds(10)));
    }
    @Test void derivedDeleteRejectsZeroQueryBudgetWithoutMutatingAnyRows() throws Exception {
        expiredDelete(1, 2);
        var policy = database.registeredPolicy(DeleteAuditNoteCacheBinding.METADATA.entityName()).orElseThrow();
        var guard = database.config().readShapeGuardrail();
        assertTrue(guard.enabled()); assertTrue(guard.rejectEntityQueryOverLimit());
        assertEquals(10, guard.hotSetHeadroom()); assertEquals(250, guard.maxEntityQueryLimit());
        assertEquals(8, policy.hotEntityLimit()); assertEquals(8, policy.pageSize());
        assertEquals(com.reactor.cachedb.core.api.EntityQueryCapacity.finite(0), stores.delete().entityQueryCapacity());
        var rejected = assertThrows(IllegalStateException.class, () -> stores.data().deleteByState("EXPIRED"));
        assertEquals("Derived delete has zero effective entity query capacity; no query or delete was attempted. Use explicit known IDs for deletion or an authorized bounded administrative job",
                rejected.getMessage());
        var response = post("delete/spring-data", null, Map.class);
        assertEquals(409, response.getStatusCode().value());
        assertEquals("AUDIT_DERIVED_DELETE_ZERO_QUERY_BUDGET", response.getBody().get("error"));
        assertEquals(false, response.getBody().get("sqlDurabilityConfirmed"));
        assertEquals(false, response.getBody().get("retrySafe"));
        for (String id : List.of("audit-1", "audit-2", "audit-3")) {
            assertTrue(stores.delete().findHotById(id).isHit());
            assertEquals(1, sqlCount("sample_audit_delete", id));
        }
        assertEquals("EXPIRED", stores.delete().findHotById("audit-1").value().state());
        assertEquals("ACTIVE", stores.delete().findHotById("audit-3").value().state());
    }
    @Test void actualBulkHttpReceiptsConfirmOnlyRedisUntilMatchingSqlMarkersAreAwaited() throws Exception {
        expiredDelete(4, 5);
        var response = post("delete/bulk", new AuditNoteController.Slots(List.of(4, 5)), AuditNoteController.DeleteOutcome.class);
        assertEquals(202, response.getStatusCode().value()); var result = response.getBody();
        assertEquals("ACTUAL_WRITE_RECEIPTS", result.receiptAvailability()); assertEquals(2, result.accepted().size());
        assertFalse(result.sqlDurabilityConfirmed()); assertFalse(result.retrySafe());
        var receipts = result.accepted().stream().map(receipt -> {
            assertEquals("DELETE", receipt.operation());
            return new WriteReceipt<DeleteAuditNote, String>(null, receipt.id(), "sample-audit-delete",
                    OperationType.DELETE, Long.parseLong(receipt.version()), receipt.acceptedAt());
        }).toList();
        assertTrue(database.awaitDurable(receipts, Duration.ofSeconds(10)));
        assertEquals(0, sqlCount("sample_audit_delete", "audit-4")); assertEquals(0, sqlCount("sample_audit_delete", "audit-5"));
        assertTrue(stores.delete().findHotById("audit-7").isHit());
    }
    @Test void evictSweepsOnlyIndexedExpiredPayloadAndRetainsDurableSql() {
        var row = new EvictAuditNote("audit-1", "evict-ğışöüİ", LocalDate.now(ZoneOffset.UTC).minusDays(2), "EXPIRED");
        var receipt = stores.evict().saveWithReceipt(row); assertTrue(database.awaitDurable(receipt, Duration.ofSeconds(10)));
        assertTrue(stores.evict().findHotById(row.id()).isHit());
        var response = post("evict/sweep", new AuditNoteController.SweepInput(8), AuditNoteController.SweepOutcome.class);
        assertEquals(200, response.getStatusCode().value()); var result = response.getBody();
        assertEquals("EVICT_CACHE", result.action()); assertEquals(1, result.examined()); assertEquals(1, result.evicted());
        assertEquals(1, result.indexCleaned()); assertEquals(0, result.deleteAccepted()); assertEquals(0, result.skipped());
        assertTrue(result.indexedResidentOnly()); assertFalse(result.sqlDurabilityConfirmed()); assertFalse(result.sourceErasureConfirmed());
        assertFalse(stores.evict().findHotById(row.id()).isHit()); assertEquals(row, stores.evictSource().findById(row.id()).orElseThrow());
        assertEquals(1, sqlCount("sample_audit_evict", row.id())); assertTrue(stores.evict().findHotById("audit-2").isHit());
        var source = rest.exchange("/api/examples/v019/audit-notes/evict/1/source", HttpMethod.GET,
                new HttpEntity<>(headers("supplier-test")), AuditNoteController.Note.class);
        assertEquals(200, source.getStatusCode().value()); assertEquals(row.note(), source.getBody().note());
        assertEquals("SOURCE_OBSERVED", source.getBody().origin()); assertFalse(source.getBody().sqlDurabilityConfirmed());
    }
    @Test void deleteSweepEnqueuesNormalWriteBehindWithoutClaimingSourceErasure() throws Exception {
        expiredDelete(6);
        var response = post("delete/sweep", new AuditNoteController.SweepInput(8), AuditNoteController.SweepOutcome.class);
        assertEquals(200, response.getStatusCode().value()); var result = response.getBody();
        assertEquals("DELETE", result.action()); assertEquals(1, result.examined()); assertEquals(1, result.deleteAccepted());
        assertEquals(0, result.evicted()); assertEquals(1, result.indexCleaned()); assertEquals(0, result.skipped());
        assertFalse(result.sourceErasureConfirmed()); assertFalse(result.sqlDurabilityConfirmed()); assertTrue(result.indexedResidentOnly());
        assertFalse(stores.delete().findHotById("audit-6").isHit()); assertTrue(stores.delete().findHotById("audit-7").isHit());
        eventually(() -> sqlCount("sample_audit_delete", "audit-6") == 0);
        assertEquals(1, sqlCount("sample_audit_delete", "audit-7"));
    }
    @Test void actualHttpRejectsTenantIdentityCausalRequirementsAndOutOfBoundsSweep() {
        var tenant = rest.exchange("/api/examples/v019/audit-notes/evict/1/source", HttpMethod.GET,
                new HttpEntity<>(headers("tenant-a-test")), String.class);
        assertEquals(403, tenant.getStatusCode().value());
        var causalHeaders = headers("supplier-test"); causalHeaders.set("X-CacheDB-Causal", "required");
        assertEquals(409, rest.exchange("/api/examples/v019/audit-notes/delete/spring-data", HttpMethod.POST,
                new HttpEntity<>(causalHeaders), String.class).getStatusCode().value());
        assertEquals(400, post("delete/sweep", new AuditNoteController.SweepInput(9), String.class).getStatusCode().value());
        assertEquals(1, sqlCount("sample_audit_delete", "audit-1")); assertTrue(stores.delete().findHotById("audit-1").isHit());
    }
}
