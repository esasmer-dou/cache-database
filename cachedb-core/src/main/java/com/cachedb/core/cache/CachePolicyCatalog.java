package com.reactor.cachedb.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable entity-to-policy registry used by generated startup registration.
 */
public final class CachePolicyCatalog {

    private final Map<String, CachePolicy> policies;

    private CachePolicyCatalog(Map<String, CachePolicy> policies) {
        this.policies = Map.copyOf(policies);
    }

    public static CachePolicyCatalog empty() {
        return new CachePolicyCatalog(Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<CachePolicy> find(String entityName) {
        return Optional.ofNullable(policies.get(normalizeEntityName(entityName)));
    }

    public CachePolicy resolve(String entityName, CachePolicy fallback) {
        return find(entityName).orElseGet(() -> Objects.requireNonNull(fallback, "fallback"));
    }

    public Set<String> entityNames() {
        return policies.keySet();
    }

    public boolean isEmpty() {
        return policies.isEmpty();
    }

    private static String normalizeEntityName(String entityName) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName must not be blank");
        }
        return entityName.trim();
    }

    public static final class Builder {
        private final LinkedHashMap<String, CachePolicy> policies = new LinkedHashMap<>();

        public Builder policy(String entityName, CachePolicy cachePolicy) {
            policies.put(normalizeEntityName(entityName), Objects.requireNonNull(cachePolicy, "cachePolicy"));
            return this;
        }

        public Builder policies(CachePolicyCatalog catalog) {
            if (catalog != null) {
                policies.putAll(catalog.policies);
            }
            return this;
        }

        public CachePolicyCatalog build() {
            return new CachePolicyCatalog(policies);
        }
    }
}
