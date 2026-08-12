package com.reactor.cachedb.core.route;

/**
 * Bounded, reflection-free metadata emitted by the repository annotation processor.
 * Zero values mean that a limit does not apply to that route kind.
 */
public record RepositoryRouteDefinition(
        String methodName,
        RepositoryRouteKind kind,
        String routeName,
        String projectionName,
        int pageSize,
        int maxRows,
        int hotWindow,
        int queryTimeoutSeconds,
        long memoryBudgetBytes,
        boolean coverageScoped,
        boolean projectionsOnly,
        String detail,
        HotRoutePopulation population
) {
    public RepositoryRouteDefinition(
            String methodName,
            RepositoryRouteKind kind,
            String routeName,
            String projectionName,
            int pageSize,
            int maxRows,
            int hotWindow,
            int queryTimeoutSeconds,
            long memoryBudgetBytes,
            boolean coverageScoped,
            boolean projectionsOnly,
            String detail
    ) {
        this(methodName, kind, routeName, projectionName, pageSize, maxRows, hotWindow,
                queryTimeoutSeconds, memoryBudgetBytes, coverageScoped, projectionsOnly, detail,
                kind == RepositoryRouteKind.HOT
                        ? HotRoutePopulation.ON_DEMAND
                        : HotRoutePopulation.NOT_APPLICABLE);
    }

    public RepositoryRouteDefinition {
        methodName = requireText(methodName, "methodName");
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        routeName = requireText(routeName, "routeName");
        projectionName = normalize(projectionName);
        detail = normalize(detail);
        population = population == null
                ? (kind == RepositoryRouteKind.HOT
                ? HotRoutePopulation.ON_DEMAND
                : HotRoutePopulation.NOT_APPLICABLE)
                : population;
        if (kind != RepositoryRouteKind.HOT && population != HotRoutePopulation.NOT_APPLICABLE) {
            throw new IllegalArgumentException("population only applies to HOT routes");
        }
        if (kind == RepositoryRouteKind.HOT && population == HotRoutePopulation.NOT_APPLICABLE) {
            throw new IllegalArgumentException("HOT routes require a population strategy");
        }
        if (pageSize < 0 || maxRows < 0 || hotWindow < 0 || queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("route bounds must not be negative");
        }
        if (memoryBudgetBytes < 0L) {
            throw new IllegalArgumentException("memoryBudgetBytes must not be negative");
        }
    }

    public boolean projectionBacked() {
        return !projectionName.isEmpty();
    }

    public boolean requiresDeclaredWarm() {
        return kind == RepositoryRouteKind.HOT && population == HotRoutePopulation.DECLARED_WARM;
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
