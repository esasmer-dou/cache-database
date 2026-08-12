package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.route.RepositoryRouteCatalog;
import com.reactor.cachedb.core.route.RepositoryRouteDefinition;
import com.reactor.cachedb.core.route.RepositoryRouteKind;
import com.reactor.cachedb.core.route.HotRoutePopulation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable, bounded operational view of generated repository route catalogs. */
public final class CacheDbRouteInventory {
    private final List<RepositoryRouteCatalog> catalogs;
    private final Map<RepositoryRouteKind, Integer> counts;
    private final Map<HotRoutePopulation, Integer> hotPopulationCounts;
    private final Map<String, RouteDescriptor> routesById;
    private final Map<String, RouteDescriptor> hotRoutesByName;
    private final List<RouteDescriptor> routes;
    private final int routeCount;

    public CacheDbRouteInventory(Collection<RepositoryRouteCatalog> catalogs) {
        ArrayList<RepositoryRouteCatalog> ordered = new ArrayList<>();
        if (catalogs != null) {
            for (RepositoryRouteCatalog catalog : catalogs) {
                if (catalog == null) {
                    throw new IllegalArgumentException("catalogs must not contain null");
                }
                ordered.add(catalog);
            }
        }
        ordered.sort(Comparator.comparing(RepositoryRouteCatalog::repositoryName));
        Set<String> names = new HashSet<>(Math.max(16, ordered.size() * 2));
        EnumMap<RepositoryRouteKind, Integer> routeCounts = new EnumMap<>(RepositoryRouteKind.class);
        EnumMap<HotRoutePopulation, Integer> populationCounts = new EnumMap<>(HotRoutePopulation.class);
        LinkedHashMap<String, RouteDescriptor> indexedRoutes = new LinkedHashMap<>();
        LinkedHashMap<String, RouteDescriptor> indexedHotRoutes = new LinkedHashMap<>();
        ArrayList<RouteDescriptor> routeDescriptors = new ArrayList<>();
        for (RepositoryRouteCatalog catalog : ordered) {
            if (!names.add(catalog.repositoryName())) {
                throw new IllegalArgumentException("duplicate repository route catalog: " + catalog.repositoryName());
            }
            for (RepositoryRouteDefinition route : catalog.routes()) {
                RouteDescriptor descriptor = new RouteDescriptor(catalog.repositoryName(), catalog.entityName(), route);
                RouteDescriptor duplicateMethod = indexedRoutes.putIfAbsent(descriptor.id(), descriptor);
                if (duplicateMethod != null) {
                    throw new IllegalArgumentException("duplicate repository route id: " + descriptor.id());
                }
                routeDescriptors.add(descriptor);
                if (route.kind() == RepositoryRouteKind.HOT) {
                    RouteDescriptor duplicateHotRoute = indexedHotRoutes.putIfAbsent(route.routeName(), descriptor);
                    if (duplicateHotRoute != null) {
                        throw new IllegalArgumentException("duplicate HOT route name '" + route.routeName()
                                + "' in " + duplicateHotRoute.id() + " and " + descriptor.id()
                                + "; Redis coverage keys would collide");
                    }
                    populationCounts.merge(route.population(), 1, Integer::sum);
                    validateDeclaredWarm(catalog, route);
                }
                routeCounts.merge(route.kind(), 1, Integer::sum);
            }
        }
        this.catalogs = List.copyOf(ordered);
        this.counts = Collections.unmodifiableMap(routeCounts);
        this.hotPopulationCounts = Collections.unmodifiableMap(populationCounts);
        this.routesById = Map.copyOf(indexedRoutes);
        this.hotRoutesByName = Map.copyOf(indexedHotRoutes);
        this.routes = List.copyOf(routeDescriptors);
        this.routeCount = routes.size();
    }

    public static CacheDbRouteInventory empty() {
        return new CacheDbRouteInventory(List.of());
    }

    public List<RepositoryRouteCatalog> catalogs() {
        return catalogs;
    }

    public int repositoryCount() {
        return catalogs.size();
    }

    public int routeCount() {
        return routeCount;
    }

    public int count(RepositoryRouteKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        return counts.getOrDefault(kind, 0);
    }

    public Map<RepositoryRouteKind, Integer> counts() {
        return counts;
    }

    public Map<HotRoutePopulation, Integer> hotPopulationCounts() {
        return hotPopulationCounts;
    }

    public int hotPopulationCount(HotRoutePopulation population) {
        if (population == null || population == HotRoutePopulation.NOT_APPLICABLE) {
            throw new IllegalArgumentException("population must identify a HOT route strategy");
        }
        return hotPopulationCounts.getOrDefault(population, 0);
    }

    public Optional<RouteDescriptor> route(String repositoryName, String methodName) {
        return Optional.ofNullable(routesById.get(routeId(repositoryName, methodName)));
    }

    public Optional<RouteDescriptor> hotRoute(String routeName) {
        if (routeName == null || routeName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(hotRoutesByName.get(routeName.trim()));
    }

    public List<RouteDescriptor> routes(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        if (limit >= routeCount) {
            return routes;
        }
        return List.copyOf(routes.subList(0, limit));
    }

    public record RouteDescriptor(
            String repositoryName,
            String entityName,
            RepositoryRouteDefinition route
    ) {
        public String id() {
            return routeId(repositoryName, route.methodName());
        }
    }

    private static void validateDeclaredWarm(
            RepositoryRouteCatalog catalog,
            RepositoryRouteDefinition hotRoute
    ) {
        if (!hotRoute.requiresDeclaredWarm()) {
            return;
        }
        String expected = "from=" + hotRoute.methodName();
        boolean declared = catalog.routes(RepositoryRouteKind.WARM).stream()
                .anyMatch(route -> expected.equals(route.detail()));
        if (!declared) {
            throw new IllegalArgumentException("HOT route " + catalog.repositoryName() + '#'
                    + hotRoute.methodName() + " requires a generated warm route");
        }
    }

    private static String routeId(String repositoryName, String methodName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("repositoryName must not be blank");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        return repositoryName.trim() + '#' + methodName.trim();
    }
}
