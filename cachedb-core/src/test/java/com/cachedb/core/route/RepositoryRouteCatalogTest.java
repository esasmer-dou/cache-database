package com.reactor.cachedb.core.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryRouteCatalogTest {

    @Test
    void exposesBoundedCompileTimeRouteInventory() {
        RepositoryRouteDefinition hot = new RepositoryRouteDefinition(
                "timeline", RepositoryRouteKind.HOT, "customer-timeline", "order-summary",
                100, 1_000, 1_000, 0, 8_388_608L, true, false, "strict=true"
        );
        RepositoryRouteDefinition warm = new RepositoryRouteDefinition(
                "warmTimeline", RepositoryRouteKind.WARM, "warm-customer-timeline", "order-summary",
                0, 1_000, 1_000, 0, 0L, true, true, "from=timeline"
        );

        RepositoryRouteCatalog catalog = new RepositoryRouteCatalog(
                "OrderRepository", "OrderEntity", List.of(hot, warm)
        );

        assertEquals(1, catalog.count(RepositoryRouteKind.HOT));
        assertEquals(List.of(warm), catalog.routes(RepositoryRouteKind.WARM));
        assertTrue(hot.projectionBacked());
        RepositoryRouteRef ref = catalog.requireMethod("timeline");
        assertEquals("customer-timeline", ref.routeName());
        assertEquals(RepositoryRouteKind.HOT, ref.kind());
        assertTrue(ref.projectionBacked());
        assertTrue(catalog.findMethod("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.requireMethod("missing"));
    }

    @Test
    void rejectsDuplicateMethodMetadata() {
        RepositoryRouteDefinition route = new RepositoryRouteDefinition(
                "timeline", RepositoryRouteKind.HOT, "customer-timeline", "",
                100, 1_000, 1_000, 0, 0L, false, false, ""
        );

        assertThrows(IllegalArgumentException.class, () -> new RepositoryRouteCatalog(
                "OrderRepository", "OrderEntity", List.of(route, route)
        ));
    }
}
