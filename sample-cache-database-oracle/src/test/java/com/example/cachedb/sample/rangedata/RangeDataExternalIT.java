package com.example.cachedb.sample.rangedata;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.ExternalSampleFixtures;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisEntityRepository;
import com.reactor.cachedb.starter.CacheDatabase;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sample.range-data.enabled=true", "sample.scheduled-warm.enabled=false",
        "sample.controls.supplier-token=test-supplier",
        "sample.controls.tenant-a-token=test-a", "sample.controls.tenant-b-token=test-b"})
@org.springframework.test.annotation.DirtiesContext(classMode =
        org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class RangeDataExternalIT {
    @DynamicPropertySource static void fixtures(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry))
            throw new IllegalStateException("Dedicated external fixtures are required");
    }
    @Autowired CacheDatabase database;
    @Autowired RangeRoutes routes;
    @Autowired RangeDataRepository data;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void finiteHttpTimeouts() {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(2000); requests.setReadTimeout(10000);
        rest.getRestTemplate().setRequestFactory(requests);
    }
    private EntityRepository<RangeDataRow, String> nativeRepository() {
        var repository = database.repository(RangeDataRowCacheBinding.METADATA, RangeDataRowCacheBinding.CODEC);
        assertInstanceOf(RedisEntityRepository.class, repository);
        return repository;
    }

    @Test void generatedLongRangeMatchesNativeRowsAcrossTiesUnsafeLongsAndBothDirections() {
        var nativeRows = nativeRepository();
        try (var administrative = CacheTenantContext.administrative()) {
            ((RedisEntityRepository<?, ?>) nativeRows).rebuildCompositeIndexesFenced(false, "bounded demo parity");
        }
        String run = run(); var expected = seed(run);
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            var baseline = nativeRows.query(query(run).orderBy(QuerySort.asc("rank_value"), QuerySort.asc("row_id")).limitTo(32));
            assertEquals(ids(expected), ids(baseline));
            var actual = new ArrayList<RangeDataRow>(); String cursor = null;
            for (int page = 0; page < 8; page++) {
                var window = routes.ascending("shop-a", run, Long.MIN_VALUE, Long.MAX_VALUE, WindowRequest.of(2, cursor));
                actual.addAll(window.items()); cursor = window.nextCursor(); if (cursor == null) break;
            }
            assertNull(cursor, "Pagination exceeded fixture bound"); assertEquals(ids(expected), ids(actual));
            var descending = routes.descending("shop-a", run, Long.MIN_VALUE, Long.MAX_VALUE, WindowRequest.first(32));
            var reversed = new ArrayList<>(expected); Collections.reverse(reversed);
            assertEquals(ids(reversed), ids(descending.items()));
            var seek = routes.seek("shop-a", run, Long.MIN_VALUE, Long.MAX_VALUE, WindowRequest.first(32),
                    9007199254740992L, "shop-a:" + run + ":b");
            assertEquals(List.of("shop-a:" + run + ":a0", "shop-a:" + run + ":d"), ids(seek.items()));
        }
    }

    @Test void springDataCrudPageSliceWindowAndSortMatchActualNativeRepository() {
        String run = run(); var expected = seed(run);
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            var order = Sort.by(Sort.Order.asc("rankValue"), Sort.Order.asc("rowId"));
            var page = data.findByRunIdAndStatus(run, "OPEN", PageRequest.of(0, 2, order));
            assertEquals(5, page.getTotalElements()); assertTrue(page.hasNext());
            assertEquals(ids(expected.subList(0, 2)), ids(page.getContent()));
            var slice = data.readByRunIdAndStatus(run, "OPEN", PageRequest.of(1, 2, order));
            assertEquals(ids(expected.subList(2, 4)), ids(slice.getContent())); assertTrue(slice.hasNext());
            var rows = new ArrayList<RangeDataRow>(); ScrollPosition position = ScrollPosition.keyset();
            for (int index = 0; index < 8; index++) {
                var window = data.readByRunIdAndStatusOrderByRankValueAscRowIdAsc(run, "OPEN", position, Limit.of(2));
                rows.addAll(window.getContent());
                if (!window.hasNext()) break;
                position = window.positionAt(window.size() - 1);
                assertInstanceOf(KeysetScrollPosition.class, position);
                assertEquals(Set.of("$cachedb:cursor"), ((KeysetScrollPosition) position).getKeys().keySet());
            }
            assertEquals(ids(expected), ids(rows));
            assertEquals(ids(nativeRepository().query(query(run).orderBy(QuerySort.asc("rank_value"),
                    QuerySort.asc("row_id")).limitTo(32))), ids(rows));
            assertEquals(5, data.countByRunIdAndStatus(run, "OPEN")); assertTrue(data.existsByRunIdAndStatus(run, "OPEN"));
            assertEquals(expected.getFirst(), data.findById(expected.getFirst().rowId()).orElseThrow());
            data.deleteById(expected.get(2).rowId());
            assertFalse(data.findById(expected.get(2).rowId()).isPresent());
            assertEquals(4, data.countByRunIdAndStatus(run, "OPEN"));
        }
    }

    @Test void cursorContractsTenantOwnershipCausalSafetyAndDeletedAnchorRemainObservable() {
        String run = run(); seed(run); String cursor; ScrollPosition typedPosition;
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            var first = routes.ascending("shop-a", run, Long.MIN_VALUE, Long.MAX_VALUE, WindowRequest.first(2));
            cursor = first.nextCursor(); assertNotNull(cursor);
            var typed = data.readByRunIdAndStatusOrderByRankValueAscRowIdAsc(run, "OPEN", ScrollPosition.keyset(), Limit.of(2));
            typedPosition = typed.positionAt(1);
            data.deleteById(first.items().getLast().rowId());
            var rest = routes.ascending("shop-a", run, Long.MIN_VALUE, Long.MAX_VALUE, WindowRequest.after(cursor, 32));
            assertEquals(List.of("shop-a:" + run + ":b", "shop-a:" + run + ":a0", "shop-a:" + run + ":d"), ids(rest.items()));
            assertThrows(IllegalArgumentException.class, () -> routes.descending("shop-a", run, Long.MIN_VALUE,
                    Long.MAX_VALUE, WindowRequest.after(cursor, 2)));
            assertThrows(IllegalArgumentException.class, () -> routes.ascending("shop-a", run, 0L,
                    Long.MAX_VALUE, WindowRequest.after(cursor, 2)));
            try (var causal = CacheCausalContext.withMinVersion("sample-range-rows", "shop-a:" + run + ":missing", Long.MAX_VALUE)) {
                assertThrows(com.reactor.cachedb.core.exception.CacheCausalReadUnsupportedException.class, () -> data.countByRunIdAndStatus(run, "OPEN"));
                assertThrows(IllegalStateException.class, () -> routes.ascending("shop-a", run, Long.MIN_VALUE,
                        Long.MAX_VALUE, WindowRequest.first(2)));
            }
        }
        try (var tenant = CacheTenantContext.withTenant("shop-b")) {
            assertThrows(IllegalArgumentException.class, () -> routes.ascending("shop-b", run, Long.MIN_VALUE,
                    Long.MAX_VALUE, WindowRequest.after(cursor, 2)));
            assertThrows(IllegalArgumentException.class, () -> data.readByRunIdAndStatusOrderByRankValueAscRowIdAsc(
                    run, "OPEN", typedPosition, Limit.of(2)));
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> data.save(new RangeDataRow("shop-a:" + run + ":z",
                    "shop-a", run, "OPEN", 1L)));
        }
    }

    @Test void httpUsesVerifiedTenantExactLongStringsBoundsAndRealProviderWriteBehind() {
        String run = run(); String path = "/api/examples/v019/range-data/" + run;
        var accepted = exchange(path + "/rows/a", HttpMethod.PUT, "test-a",
                new RangeDataController.Input("9007199254740993", "OPEN"), RangeDataController.Saved.class, "");
        assertEquals(202, accepted.getStatusCode().value());
        assertFalse(accepted.getBody().sqlDurabilityConfirmed()); assertEquals("shop-a", accepted.getBody().row().tenant());
        assertEquals("9007199254740993", accepted.getBody().row().rank());
        assertNull(accepted.getHeaders().getFirst("X-CacheDB-Causal"));
        assertEquals(404, exchange(path + "/rows/a", HttpMethod.GET, "test-b", null, Map.class, "").getStatusCode().value());
        assertEquals(401, exchange(path + "/native", HttpMethod.GET, null, null, Map.class, "").getStatusCode().value());
        assertEquals(403, exchange(path + "/native", HttpMethod.GET, "test-supplier", null, Map.class, "").getStatusCode().value());
        assertEquals(409, exchange(path + "/native", HttpMethod.GET, "test-a", null, Map.class, "required").getStatusCode().value());
        assertEquals(400, exchange(path + "/native?limit=33", HttpMethod.GET, "test-a", null, Map.class, "").getStatusCode().value());
        assertEquals(400, exchange(path + "/data/page?page=129", HttpMethod.GET, "test-a", null, Map.class, "").getStatusCode().value());
        assertEquals(413, exchange(path + "/rows/a", HttpMethod.PUT, "test-a",
                "x".repeat(2049), Map.class, "").getStatusCode().value());
        await(() -> {
            var rows = jdbc.queryForList("SELECT rank_value FROM sample_range_rows WHERE row_id = ?",
                    Long.class, "shop-a:" + run + ":a");
            return rows.size() == 1 && rows.getFirst() == 9007199254740993L;
        }, 30);
    }

    private List<RangeDataRow> seed(String run) {
        var rows = List.of(row("shop-a", run, "zero", Long.MIN_VALUE),
                row("shop-a", run, "a", 9007199254740992L), row("shop-a", run, "b", 9007199254740992L),
                row("shop-a", run, "a0", 9007199254740993L), row("shop-a", run, "d", Long.MAX_VALUE));
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            for (var row : rows) data.save(row);
            data.save(new RangeDataRow("shop-a:" + run + ":closed", "shop-a", run, "CLOSED", 0L));
        }
        try (var tenant = CacheTenantContext.withTenant("shop-b")) { data.save(row("shop-b", run, "a", 7L)); }
        return rows;
    }
    private static RangeDataRow row(String tenant, String run, String slot, long rank) {
        return new RangeDataRow(tenant + ":" + run + ":" + slot, tenant, run, "OPEN", rank);
    }
    private static QuerySpec query(String run) {
        return QuerySpec.allOf(QueryFilter.eq("run_id", run), QueryFilter.eq("status", "OPEN")).withQueryTimeoutSeconds(3);
    }
    private static String run() { return UUID.randomUUID().toString().replace("-", ""); }
    private static List<String> ids(List<RangeDataRow> rows) { return rows.stream().map(RangeDataRow::rowId).toList(); }
    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, String token, Object body, Class<T> type, String causal) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        if (!causal.isEmpty()) headers.set("X-CacheDB-Causal", causal);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
    private static void await(BooleanSupplier condition, int seconds) {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        do {
            if (condition.getAsBoolean()) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        } while (System.nanoTime() < deadline);
        fail("Provider did not persist the exact value within the bounded deadline");
    }
}
