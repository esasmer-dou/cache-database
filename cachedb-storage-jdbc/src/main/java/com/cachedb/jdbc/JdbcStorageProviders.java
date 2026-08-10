package com.reactor.cachedb.jdbc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

public final class JdbcStorageProviders {
    private JdbcStorageProviders() {
    }

    public static JdbcStorageProvider require(String id) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return require(id, context == null ? JdbcStorageProviders.class.getClassLoader() : context);
    }

    public static JdbcStorageProvider require(String id, ClassLoader classLoader) {
        String normalized = normalize(id);
        return discover(classLoader).stream()
                .filter(provider -> normalize(provider.id()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new CacheDbProviderUnavailableException(
                        normalized,
                        discover(classLoader).stream().map(JdbcStorageProvider::id).toList()
                ));
    }

    public static JdbcStorageProvider requireSingle(ClassLoader classLoader) {
        List<JdbcStorageProvider> providers = discover(classLoader);
        if (providers.isEmpty()) {
            throw new CacheDbProviderUnavailableException("auto", List.of());
        }
        if (providers.size() > 1) {
            throw new CacheDbProviderAmbiguousException(providers.stream().map(JdbcStorageProvider::id).toList());
        }
        return providers.get(0);
    }

    public static List<JdbcStorageProvider> discover(ClassLoader classLoader) {
        ClassLoader loader = classLoader == null ? JdbcStorageProviders.class.getClassLoader() : classLoader;
        LinkedHashMap<String, JdbcStorageProvider> providers = new LinkedHashMap<>();
        for (JdbcStorageProvider provider : ServiceLoader.load(JdbcStorageProvider.class, loader)) {
            String id = normalize(provider.id());
            JdbcStorageProvider duplicate = providers.putIfAbsent(id, provider);
            if (duplicate != null && duplicate.getClass() != provider.getClass()) {
                throw new IllegalStateException("Multiple CacheDB JDBC providers use id '" + id + "': "
                        + duplicate.getClass().getName() + ", " + provider.getClass().getName());
            }
        }
        ArrayList<JdbcStorageProvider> result = new ArrayList<>(providers.values());
        result.sort(Comparator.comparing(provider -> normalize(provider.id())));
        return List.copyOf(result);
    }

    public static Map<String, String> validateOptions(JdbcStorageProvider provider, Map<String, String> options) {
        Map<String, String> safe = options == null ? Map.of() : Map.copyOf(options);
        for (String key : safe.keySet()) {
            if (!provider.supportedOptions().contains(key)) {
                throw new IllegalArgumentException("Unsupported option '" + key + "' for CacheDB JDBC provider "
                        + provider.id() + ". Supported options: " + provider.supportedOptions());
            }
        }
        return safe;
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("JDBC provider id must not be blank");
        }
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
