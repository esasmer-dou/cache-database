package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.route.RouteCoverage;
import com.reactor.cachedb.core.route.RouteCoverageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowContractTest {

    @Test
    void reservesOneProbeRowAtTheLargestPublicWindow() {
        WindowRequest request = WindowRequest.first(WindowRequest.MAX_LIMIT);

        assertEquals(1_001, request.queryLimit());
        assertEquals(1_001, QuerySpec.builder().limit(request.queryLimit()).build().limit());
    }

    @Test
    void rejectsUnboundedOrNegativeQueryShapes() {
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.builder().limit(0).build());
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.builder().limit(QuerySpec.MAX_LIMIT + 1).build());
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.builder().offset(-1).build());
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.builder().queryTimeoutSeconds(-1).build());
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.builder().queryTimeoutSeconds(301).build());
        assertEquals(17, QuerySpec.builder().queryTimeoutSeconds(17).build().queryTimeoutSeconds());
    }

    @SuppressWarnings("deprecation")
    @Test
    void retainsTheLegacyListBasedInFilterOverload() {
        List<Object> values = List.of("OPEN", "PAID");

        QueryFilter filter = QueryFilter.in("status", values);

        assertEquals(values, filter.values());
    }

    @Test
    void cursorRoundTripsTypedStableSortValuesAndRejectsNulls() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("created_at", 42L);
        values.put("order_id", 7L);

        String cursor = WindowCursor.encode(values);

        assertEquals(Map.copyOf(values), WindowCursor.decode(cursor));
        assertThrows(IllegalArgumentException.class,
                () -> WindowCursor.encode(Map.of("created_at", new Object())));
        LinkedHashMap<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("created_at", null);
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encode(nullValue));
    }

    @Test
    void completeItemsRejectsPartialCoverageInsteadOfServingSilentPartialData() {
        RouteCoverage partial = new RouteCoverage(
                "customer-orders",
                "42",
                RouteCoverageStatus.PARTIAL,
                100,
                25,
                Instant.EPOCH,
                Instant.EPOCH,
                "warm batch stopped early"
        );
        HotWindow<String> window = new HotWindow<>(List.of("partial"), null, partial);

        HotRouteUnavailableException failure = assertThrows(
                HotRouteUnavailableException.class,
                window::completeItems
        );

        assertSame(partial, failure.coverage());
        assertEquals(RouteCoverageStatus.PARTIAL, failure.coverage().status());
    }

    @Test
    void completeItemsReturnsTheImmutableWindowWhenCoverageIsComplete() {
        RouteCoverage complete = new RouteCoverage(
                "customer-orders",
                "42",
                RouteCoverageStatus.COMPLETE,
                1,
                1,
                Instant.EPOCH,
                Instant.EPOCH,
                ""
        );
        HotWindow<String> window = new HotWindow<>(List.of("ready"), "next", complete);

        assertEquals(List.of("ready"), window.completeItems());
    }
}
