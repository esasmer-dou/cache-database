package com.example.cachedb.sample;

import java.util.Objects;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Explicit disposable Kubernetes fixture support; never discovers shared or production databases. */
public final class ExternalSampleFixtures {
    public static boolean configure(DynamicPropertyRegistry registry) {
        String namespace = System.getenv("CACHEDB_TEST_NAMESPACE");
        if (namespace == null) return false;
        if (!namespace.matches("cachedb-[a-z0-9-]+")) throw new IllegalStateException("Dedicated CacheDB test namespace required");
        String url = required("CACHEDB_TEST_DATASOURCE_URL");
        String redis = required("CACHEDB_TEST_REDIS_URI");
        if (!url.contains("." + namespace + ".svc.cluster.local") || !redis.contains("." + namespace + ".svc.cluster.local"))
            throw new IllegalStateException("External fixtures must use services inside the dedicated test namespace");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("CACHEDB_TEST_DATASOURCE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("CACHEDB_TEST_DATASOURCE_PASSWORD"));
        registry.add("cachedb.redis.uri", () -> redis);
        registry.add("cachedb.admin.enabled", () -> false);
        return true;
    }
    public static String splitRedisUri() {
        if (System.getenv("CACHEDB_TEST_NAMESPACE") == null) return null;
        String value = required("CACHEDB_TEST_SPLIT_REDIS_URI");
        if (!value.contains("." + required("CACHEDB_TEST_NAMESPACE") + ".svc.cluster.local"))
            throw new IllegalStateException("Split-role Redis must stay in the dedicated test namespace");
        return value;
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), "Missing test fixture variable " + name); }
    private ExternalSampleFixtures() {}
}
