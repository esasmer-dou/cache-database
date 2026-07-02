package com.reactor.cachedb.core.guardrail;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.RouteCacheStrictMode;

public final class ReadShapeGuardrails {

    private ReadShapeGuardrails() {
    }

    public static void validatePageRequest(
            String surfaceName,
            PageWindow pageWindow,
            CachePolicy cachePolicy,
            ReadShapeGuardrailConfig config
    ) {
        if (!enabled(config) || !config.rejectPageRequestOverLimit() || pageWindow == null) {
            return;
        }
        Limit limit = pageRequestLimit(cachePolicy, config);
        if (limit.active() && pageWindow.pageSize() > limit.rows()) {
            throw oversized(
                    "PageWindow",
                    surfaceName,
                    pageWindow.pageSize(),
                    limit.rows(),
                    "Use a smaller page, a bounded projection window, or a ProjectionRepository read-model for large lists."
            );
        }
    }

    public static void validateLoadedPage(
            String surfaceName,
            int loadedRowCount,
            CachePolicy cachePolicy,
            ReadShapeGuardrailConfig config
    ) {
        if (!enabled(config) || !config.rejectLoadedPageOverLimit()) {
            return;
        }
        Limit limit = loadedPageLimit(cachePolicy, config);
        if (limit.active() && loadedRowCount > limit.rows()) {
            throw oversized(
                    "Read-through page load",
                    surfaceName,
                    loadedRowCount,
                    limit.rows(),
                    "The page loader returned more rows than the Redis hot window can safely keep. Use a projection/read-model window."
            );
        }
    }

    public static boolean shouldCacheLoadedPage(
            int loadedRowCount,
            CachePolicy cachePolicy,
            ReadShapeGuardrailConfig config
    ) {
        if (!enabled(config) || !config.skipLoadedPageCacheOverLimit()) {
            return true;
        }
        Limit limit = loadedPageLimit(cachePolicy, config);
        return !limit.active() || loadedRowCount <= limit.rows();
    }

    public static void validateEntityQuery(
            String surfaceName,
            QuerySpec querySpec,
            CachePolicy cachePolicy,
            ReadShapeGuardrailConfig config
    ) {
        if (!enabled(config) || !config.rejectEntityQueryOverLimit() || querySpec == null) {
            return;
        }
        Limit limit = entityQueryLimit(cachePolicy, config);
        if (limit.active() && querySpec.limit() > limit.rows()) {
            throw oversized(
                    "Entity query",
                    surfaceName,
                    querySpec.limit(),
                    limit.rows(),
                    "Large full-entity lists must use summary projections/read-models instead of broad entity hydration."
            );
        }
    }

    public static void validateProjectionQuery(
            String surfaceName,
            QuerySpec querySpec,
            ReadShapeGuardrailConfig config
    ) {
        if (!enabled(config) || !config.rejectProjectionQueryOverLimit() || querySpec == null) {
            return;
        }
        Limit limit = projectionQueryLimit(config);
        if (limit.active() && querySpec.limit() > limit.rows()) {
            throw oversized(
                    "Projection query",
                    surfaceName,
                    querySpec.limit(),
                    limit.rows(),
                    "Use an explicit bounded projection window or split the result into smaller windows."
            );
        }
    }

    public static void validateRouteContract(RouteCacheContract contract, String resolvedRouteLabel) {
        if (contract == null) {
            return;
        }
        contract.validateResolvedRoute(resolvedRouteLabel);
    }

    public static void validateRouteReadSize(RouteCacheContract contract, String shape, int requestedRows) {
        if (contract == null
                || contract.strictMode() != RouteCacheStrictMode.FAIL_FAST
                || requestedRows <= 0
                || contract.allowsColdReadSize(requestedRows)) {
            return;
        }
        throw new IllegalArgumentException(
                safeSurface(shape) + " for route " + safeSurface(contract.routeName())
                        + " requested " + requestedRows
                        + " rows but maxColdReadSize is " + contract.maxColdReadSize()
                        + ". Use a projection/read-model route, lower the page size, or widen the explicit route contract."
        );
    }

    public static int effectivePageRequestLimit(CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        if (!enabled(config)) {
            return 0;
        }
        Limit limit = pageRequestLimit(cachePolicy, config);
        return limit.active() ? limit.rows() : 0;
    }

    public static int effectiveLoadedPageLimit(CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        if (!enabled(config)) {
            return 0;
        }
        Limit limit = loadedPageLimit(cachePolicy, config);
        return limit.active() ? limit.rows() : 0;
    }

    public static int effectiveEntityQueryLimit(CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        if (!enabled(config)) {
            return 0;
        }
        Limit limit = entityQueryLimit(cachePolicy, config);
        return limit.active() ? limit.rows() : 0;
    }

    private static boolean enabled(ReadShapeGuardrailConfig config) {
        return config != null && config.enabled();
    }

    private static int configuredPageSize(CachePolicy cachePolicy) {
        return cachePolicy == null ? 0 : Math.max(0, cachePolicy.pageSize());
    }

    private static Limit pageRequestLimit(CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        Limit limit = Limit.unbounded();
        limit = capIfPositive(limit, config.maxPageRequestSize());
        limit = capIfPositive(limit, configuredPageSize(cachePolicy));
        return capWithHotWindow(limit, cachePolicy, config);
    }

    private static Limit loadedPageLimit(CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        return capIfPositive(pageRequestLimit(cachePolicy, config), config.maxLoadedPageSize());
    }

    private static Limit entityQueryLimit(CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        Limit limit = Limit.unbounded();
        limit = capIfPositive(limit, config.maxEntityQueryLimit());
        limit = capIfPositive(limit, configuredPageSize(cachePolicy));
        return capWithHotWindow(limit, cachePolicy, config);
    }

    private static Limit projectionQueryLimit(ReadShapeGuardrailConfig config) {
        return capIfPositive(Limit.unbounded(), config.maxProjectionQueryLimit());
    }

    private static Limit capIfPositive(Limit limit, int candidate) {
        return candidate > 0 ? limit.cap(candidate) : limit;
    }

    private static Limit capWithHotWindow(Limit limit, CachePolicy cachePolicy, ReadShapeGuardrailConfig config) {
        if (cachePolicy == null || cachePolicy.hotEntityLimit() <= 0) {
            return limit;
        }
        int headroom = Math.max(0, config.hotSetHeadroom());
        return limit.cap(Math.max(0, cachePolicy.hotEntityLimit() - headroom));
    }

    private record Limit(int rows, boolean active) {
        static Limit unbounded() {
            return new Limit(0, false);
        }

        Limit cap(int candidate) {
            if (!active) {
                return new Limit(candidate, true);
            }
            return new Limit(Math.min(rows, candidate), true);
        }
    }

    private static IllegalArgumentException oversized(
            String shape,
            String surfaceName,
            int requested,
            int limit,
            String action
    ) {
        return new IllegalArgumentException(
                shape + " for " + safeSurface(surfaceName) + " requested " + requested
                        + " rows but the configured safe limit is " + limit + ". " + action
        );
    }

    private static String safeSurface(String surfaceName) {
        return surfaceName == null || surfaceName.isBlank() ? "unknown surface" : surfaceName;
    }
}
