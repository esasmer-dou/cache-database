package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.route.RepositoryRouteCatalog;
import com.reactor.cachedb.core.route.RepositoryRouteDefinition;
import com.reactor.cachedb.core.route.RepositoryRouteKind;
import com.reactor.cachedb.core.route.HotRoutePopulation;
import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheDbRouteInventoryTest {

    @Test
    void aggregatesGeneratedCatalogsWithoutUnboundedRouteTags() {
        RepositoryRouteCatalog orders = catalog(
                "sample.OrderRepository",
                route("timeline", RepositoryRouteKind.HOT),
                route("archive", RepositoryRouteKind.SOURCE),
                route("warmTimeline", RepositoryRouteKind.WARM)
        );
        RepositoryRouteCatalog products = catalog(
                "sample.ProductRepository",
                route("active", RepositoryRouteKind.HOT)
        );

        CacheDbRouteInventory inventory = new CacheDbRouteInventory(List.of(products, orders));

        assertEquals(2, inventory.repositoryCount());
        assertEquals(4, inventory.routeCount());
        assertEquals(2, inventory.count(RepositoryRouteKind.HOT));
        assertEquals("sample.OrderRepository", inventory.catalogs().get(0).repositoryName());
        assertEquals(2, inventory.routes(2).size());
    }

    @Test
    void rejectsDuplicateQualifiedRepositoryCatalogs() {
        RepositoryRouteCatalog catalog = catalog("sample.OrderRepository", route("timeline", RepositoryRouteKind.HOT));

        assertThrows(IllegalArgumentException.class, () -> new CacheDbRouteInventory(List.of(catalog, catalog)));
        assertTrue(new CacheScheduledWarmRegistry().snapshots(Integer.MAX_VALUE).isEmpty());
    }

    @Test
    void rejectsHotRouteNamesThatWouldCollideInCoverageStorage() {
        RepositoryRouteCatalog orders = catalog(
                "sample.OrderRepository",
                route("timeline", RepositoryRouteKind.HOT)
        );
        RepositoryRouteCatalog shipments = catalog(
                "sample.ShipmentRepository",
                route("timeline", RepositoryRouteKind.HOT)
        );

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CacheDbRouteInventory(List.of(orders, shipments))
        );

        assertTrue(failure.getMessage().contains("coverage keys would collide"));
    }

    @Test
    void indexesRoutesAndValidatesDeclaredWarmPopulation() {
        RepositoryRouteDefinition hot = new RepositoryRouteDefinition(
                "timeline", RepositoryRouteKind.HOT, "customer-timeline", "OrderSummary",
                100, 1_000, 1_000, 0, 1_024L, true, false, "strict=true",
                HotRoutePopulation.DECLARED_WARM
        );
        RepositoryRouteDefinition warm = new RepositoryRouteDefinition(
                "warmTimeline", RepositoryRouteKind.WARM, "warm-customer-timeline", "OrderSummary",
                0, 1_000, 1_000, 0, 0L, true, true, "from=timeline"
        );
        CacheDbRouteInventory inventory = new CacheDbRouteInventory(List.of(catalog(
                "sample.OrderRepository", hot, warm
        )));

        assertEquals("customer-timeline", inventory.route("sample.OrderRepository", "timeline")
                .orElseThrow().route().routeName());
        assertEquals("sample.OrderRepository#timeline", inventory.hotRoute("customer-timeline")
                .orElseThrow().id());
        assertEquals(1, inventory.hotPopulationCounts().get(HotRoutePopulation.DECLARED_WARM));

        assertThrows(IllegalArgumentException.class, () -> new CacheDbRouteInventory(List.of(catalog(
                "sample.BrokenRepository", hot
        ))));
    }

    @Test
    void publishesBoundedRouteAndScheduledWarmEvidence() {
        CacheDbRouteInventory inventory = new CacheDbRouteInventory(List.of(catalog(
                "sample.OrderRepository",
                route("timeline", RepositoryRouteKind.HOT),
                route("warmTimeline", RepositoryRouteKind.WARM)
        )));
        CacheScheduledWarmRegistry warmRegistry = new CacheScheduledWarmRegistry();
        warmRegistry.registered("orders-running", "pod-a");
        warmRegistry.running("orders-running", "pod-a", Instant.EPOCH);
        warmRegistry.registered("orders-failed", "pod-a");
        warmRegistry.failed("orders-failed", "pod-a", Instant.EPOCH, CacheScheduledWarmState.FAILED,
                new IllegalStateException("test"));
        warmRegistry.registered("orders-skipped", "pod-a");
        warmRegistry.skipped("orders-skipped", "pod-a", CacheScheduledWarmState.SKIPPED_LOCK_TIMEOUT, "lease busy");
        CacheDatabase database = mock(CacheDatabase.class, RETURNS_DEEP_STUBS);
        when(database.instanceId()).thenReturn("pod-a");
        when(database.redisGuardrailSnapshot().pressureLevel()).thenReturn("NORMAL");

        Map<String, Object> snapshot = new CacheDatabaseEndpoint(
                database,
                new CacheDbProviderInfo("postgres", "dialect", List.of("postgres")),
                inventory,
                warmRegistry
        ).snapshot();

        assertEquals(1, snapshot.get("declaredRepositories"));
        assertEquals(2, snapshot.get("declaredRoutes"));
        assertEquals(1, snapshot.get("scheduledWarmRunning"));
        assertEquals(1L, snapshot.get("scheduledWarmFailures"));
        assertEquals(1L, snapshot.get("scheduledWarmSkipped"));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new CacheDatabaseMetrics(database, inventory, warmRegistry).bindTo(meters);
        assertEquals(2.0d, meters.get("cachedb.routes.declared").gauge().value());
        assertEquals(1.0d, meters.get("cachedb.routes.hot.population")
                .tag("strategy", "on_demand").gauge().value());
        assertEquals(0.0d, meters.get("cachedb.routes.hot.population")
                .tag("strategy", "declared_warm").gauge().value());
        assertEquals(1.0d, meters.get("cachedb.scheduled.warm.running").gauge().value());
        assertEquals(1.0d, meters.get("cachedb.scheduled.warm.failures").functionCounter().count());
    }

    @Test
    void boundsOperationalDetailsWithoutTruncatingAggregateEvidence() {
        ArrayList<RepositoryRouteDefinition> routes = new ArrayList<>();
        for (int index = 0; index < 260; index++) {
            routes.add(route("route" + index, RepositoryRouteKind.HOT));
        }
        CacheDbRouteInventory inventory = new CacheDbRouteInventory(List.of(new RepositoryRouteCatalog(
                "sample.LargeRepository",
                "sample.Entity",
                routes
        )));
        CacheScheduledWarmRegistry warmRegistry = new CacheScheduledWarmRegistry();
        for (int index = 0; index < 105; index++) {
            warmRegistry.registered("warm-" + index, "pod-a");
        }
        CacheDatabase database = mock(CacheDatabase.class, RETURNS_DEEP_STUBS);
        when(database.instanceId()).thenReturn("pod-a");
        when(database.redisGuardrailSnapshot().pressureLevel()).thenReturn("NORMAL");

        Map<String, Object> snapshot = new CacheDatabaseEndpoint(
                database,
                new CacheDbProviderInfo("postgres", "dialect", List.of("postgres")),
                inventory,
                warmRegistry
        ).snapshot();

        assertEquals(260, snapshot.get("declaredRoutes"));
        assertEquals(CacheDatabaseEndpoint.MAX_ROUTE_DETAILS, ((List<?>) snapshot.get("routeDetails")).size());
        assertTrue((Boolean) snapshot.get("routeDetailsTruncated"));
        assertEquals(CacheDatabaseEndpoint.MAX_SCHEDULED_WARM_DETAILS,
                ((List<?>) snapshot.get("scheduledWarmJobs")).size());
        assertTrue((Boolean) snapshot.get("scheduledWarmJobsTruncated"));
        assertEquals(105, warmRegistry.size());
        assertThrows(IllegalArgumentException.class, () -> warmRegistry.snapshots(0));
        assertEquals(0, snapshot.get("scheduledWarmRunning"));
    }

    private RepositoryRouteCatalog catalog(String repository, RepositoryRouteDefinition... routes) {
        return new RepositoryRouteCatalog(repository, "sample.Entity", List.of(routes));
    }

    private RepositoryRouteDefinition route(String method, RepositoryRouteKind kind) {
        return new RepositoryRouteDefinition(
                method, kind, method, "", 0, 100, 0, 0, 0L,
                false, false, ""
        );
    }
}
