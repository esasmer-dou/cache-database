package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.CachePolicyCatalog;
import com.reactor.cachedb.core.cache.EntityHotPolicy;
import com.reactor.cachedb.core.cache.EntityHotPolicyMode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CachePolicyCatalogFactory {

    private CachePolicyCatalogFactory() {
    }

    static void addConfiguredPolicies(
            CachePolicyCatalog.Builder catalog,
            CacheDbSpringProperties.RegistrationProperties registration,
            CachePolicy fallback
    ) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(fallback, "fallback");
        for (Map.Entry<String, CacheDbSpringProperties.EntityPolicyProperties> entry
                : registration.getEntities().entrySet()) {
            catalog.policy(entry.getKey(), toPolicy(entry.getKey(), entry.getValue(), fallback));
        }
    }

    private static CachePolicy toPolicy(
            String entityName,
            CacheDbSpringProperties.EntityPolicyProperties properties,
            CachePolicy fallback
    ) {
        CacheDbSpringProperties.EntityPolicyProperties safe = properties == null
                ? new CacheDbSpringProperties.EntityPolicyProperties()
                : properties;
        int hotEntityLimit = valueOrDefault(safe.getHotEntityLimit(), fallback.hotEntityLimit());
        int pageSize = valueOrDefault(safe.getPageSize(), fallback.pageSize());
        long entityTtlSeconds = valueOrDefault(safe.getEntityTtlSeconds(), fallback.entityTtlSeconds());
        long pageTtlSeconds = valueOrDefault(safe.getPageTtlSeconds(), fallback.pageTtlSeconds());
        if (hotEntityLimit < 1 || pageSize < 1 || entityTtlSeconds < 0L || pageTtlSeconds < 0L) {
            throw new IllegalArgumentException("Invalid CacheDB policy limits for entity " + entityName);
        }
        EntityHotPolicy hotPolicy = safe.getHotPolicy() == null
                ? fallback.hotPolicy()
                : toHotPolicy(entityName, safe.getHotPolicy(), fallback.hotPolicy());
        return CachePolicy.builder()
                .hotEntityLimit(hotEntityLimit)
                .pageSize(pageSize)
                .lruEvictionEnabled(valueOrDefault(safe.getLruEvictionEnabled(), fallback.lruEvictionEnabled()))
                .entityTtlSeconds(entityTtlSeconds)
                .pageTtlSeconds(pageTtlSeconds)
                .hotPolicy(hotPolicy)
                .build();
    }

    private static EntityHotPolicy toHotPolicy(
            String entityName,
            CacheDbSpringProperties.HotPolicyProperties properties,
            EntityHotPolicy fallback
    ) {
        EntityHotPolicy safeFallback = fallback == null ? EntityHotPolicy.countWindow() : fallback;
        EntityHotPolicyMode mode = valueOrDefault(properties.getMode(), safeFallback.mode());
        List<EntityHotPolicy> children = properties.getChildren().isEmpty()
                ? safeFallback.children()
                : properties.getChildren().stream()
                .map(child -> toHotPolicy(entityName, child, EntityHotPolicy.countWindow()))
                .toList();
        EntityHotPolicy policy = EntityHotPolicy.builder()
                .mode(mode)
                .timeColumn(valueOrDefault(properties.getTimeColumn(), safeFallback.timeColumn()))
                .hotForSeconds(valueOrDefault(properties.getHotForSeconds(), safeFallback.hotForSeconds()))
                .stateColumn(valueOrDefault(properties.getStateColumn(), safeFallback.stateColumn()))
                .stateValues(properties.getStateValues().isEmpty() ? safeFallback.stateValues() : properties.getStateValues())
                .admitOnWrite(valueOrDefault(properties.getAdmitOnWrite(), safeFallback.admitOnWrite()))
                .admitOnRead(valueOrDefault(properties.getAdmitOnRead(), safeFallback.admitOnRead()))
                .admitOnWarm(valueOrDefault(properties.getAdmitOnWarm(), safeFallback.admitOnWarm()))
                .evictWhenRejected(valueOrDefault(properties.getEvictWhenRejected(), safeFallback.evictWhenRejected()))
                .customPredicate(safeFallback.customPredicate())
                .compositeOperator(valueOrDefault(properties.getCompositeOperator(), safeFallback.compositeOperator()))
                .children(children)
                .build();
        validateHotPolicy(entityName, policy);
        return policy;
    }

    private static void validateHotPolicy(String entityName, EntityHotPolicy policy) {
        switch (policy.mode()) {
            case TIME_WINDOW -> {
                if (policy.timeColumn() == null || policy.hotForSeconds() < 1L) {
                    throw invalidHotPolicy(entityName, "TIME_WINDOW requires time-column and hot-for-seconds > 0");
                }
            }
            case STATE_WINDOW -> {
                if (policy.stateColumn() == null || policy.stateValues().isEmpty()) {
                    throw invalidHotPolicy(entityName, "STATE_WINDOW requires state-column and state-values");
                }
            }
            case CUSTOM_PREDICATE -> {
                if (policy.customPredicate() == null) {
                    throw invalidHotPolicy(entityName, "CUSTOM_PREDICATE must be supplied by a Java catalog customizer");
                }
            }
            case COMPOSITE -> {
                if (policy.children().isEmpty()) {
                    throw invalidHotPolicy(entityName, "COMPOSITE requires at least one child policy");
                }
                policy.children().forEach(child -> validateHotPolicy(entityName, child));
            }
            case COUNT_WINDOW -> {
                // No additional fields are required.
            }
        }
    }

    private static IllegalArgumentException invalidHotPolicy(String entityName, String message) {
        return new IllegalArgumentException("Invalid CacheDB hot policy for entity " + entityName + ": " + message);
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long valueOrDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static boolean valueOrDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static <T> T valueOrDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
