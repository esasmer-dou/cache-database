package com.example.cachedb.sample.rangedata;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.ExternalSampleFixtures;
import com.reactor.cachedb.core.api.EntityQueryCapacity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.starter.CacheDatabase;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sample.range-data.enabled=true", "sample.scheduled-warm.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class DerivedDeleteRangeExternalIT {
    @DynamicPropertySource static void fixtures(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry))
            throw new IllegalStateException("Dedicated external provider fixtures are required");
    }
    @Autowired CacheDatabase database;
    @Autowired JdbcTemplate jdbc;

    private EntityRepository<RangeDataRow, String> rows() {
        return database.repository(RangeDataRowCacheBinding.METADATA, RangeDataRowCacheBinding.CODEC);
    }

    @Test void generatedDeleteSelectsCompletePositiveBudgetMatchesAndPreservesOtherScopes() throws Exception {
        String run = run(), other = run();
        var nativeRows = rows();
        var derived = new RangeDataRepositoryCacheDbImplementation(database);
        var selected = List.of(row("shop-a", run, "a", "OPEN"), row("shop-a", run, "b", "OPEN"));
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            assertEquals(EntityQueryCapacity.finite(32), nativeRows.entityQueryCapacity());
            var baseline = new ArrayList<>(selected);
            baseline.add(row("shop-a", run, "keep", "CLOSED"));
            baseline.add(row("shop-a", other, "keep", "OPEN"));
            assertTrue(database.awaitDurable(nativeRows.saveAll(baseline), Duration.ofSeconds(15)));
        }
        try (var tenant = CacheTenantContext.withTenant("shop-b")) {
            assertTrue(database.awaitDurable(nativeRows.saveAll(List.of(row("shop-b", run, "keep", "OPEN"))),
                    Duration.ofSeconds(15)));
        }
        assertEquals(3, sourceCount("shop-a", run));
        assertEquals(1, sourceCount("shop-a", other)); assertEquals(1, sourceCount("shop-b", run));
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            var deleted = derived.deleteByRunIdAndStatus(run, "OPEN");
            assertEquals(selected.size(), deleted.size()); assertEquals(ids(selected), ids(deleted));
            assertTrue(nativeRows.findAll(selected.stream().map(RangeDataRow::rowId).toList()).isEmpty());
            assertTrue(nativeRows.findHotById("shop-a:" + run + ":keep").isHit());
            assertTrue(nativeRows.findHotById("shop-a:" + other + ":keep").isHit());
        }
        try (var tenant = CacheTenantContext.withTenant("shop-b")) {
            assertTrue(nativeRows.findHotById("shop-b:" + run + ":keep").isHit());
        }
        eventually(() -> sourceCount("shop-a", run) == 1);
        assertEquals(1, sourceCount("shop-a", other)); assertEquals(1, sourceCount("shop-b", run));
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
    }

    @Test void exactCapacitySelectionRejectsAmbiguousCompletenessBeforeAnyDelete() throws Exception {
        String run = run();
        var nativeRows = rows();
        var derived = new RangeDataRepositoryCacheDbImplementation(database);
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            var capacity = nativeRows.entityQueryCapacity();
            assertEquals(EntityQueryCapacity.finite(32), capacity);
            var baseline = new ArrayList<RangeDataRow>(capacity.maxRows());
            for (int slot = 0; slot < capacity.maxRows(); slot++)
                baseline.add(row("shop-a", run, Integer.toString(slot, 36), "OPEN"));
            assertTrue(database.awaitDurable(nativeRows.saveAll(baseline), Duration.ofSeconds(15)));
            assertEquals(32, sourceCount("shop-a", run));
            var failure = assertThrows(IllegalStateException.class, () -> derived.deleteByRunIdAndStatus(run, "OPEN"));
            assertEquals("Derived delete reached its complete-selection capacity (32); the whole match set is unproven. No deletes were attempted; use explicit known IDs or a bounded administrative job", failure.getMessage());
            assertEquals(ids(baseline), ids(nativeRows.findAll(baseline.stream().map(RangeDataRow::rowId).toList())));
            assertEquals(32, sourceCount("shop-a", run));
        }
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
    }

    private long sourceCount(String tenant, String run) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sample_range_rows WHERE tenant_id = ? AND run_id = ?",
                Long.class, tenant, run);
    }
    private static RangeDataRow row(String tenant, String run, String slot, String status) {
        return new RangeDataRow(tenant + ":" + run + ":" + slot, tenant, run, status, 1L);
    }
    private static Set<String> ids(List<RangeDataRow> rows) {
        return Set.copyOf(rows.stream().map(RangeDataRow::rowId).toList());
    }
    private static String run() { return UUID.randomUUID().toString().replace("-", ""); }
    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do { if (condition.getAsBoolean()) return; Thread.sleep(50); } while (System.nanoTime() < end);
        assertTrue(condition.getAsBoolean(), "The bounded SQL observation was not reached");
    }
}
