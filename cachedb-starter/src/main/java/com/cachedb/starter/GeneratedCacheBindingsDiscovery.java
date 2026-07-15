package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.CachePolicyCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;

public final class GeneratedCacheBindingsDiscovery {
    private GeneratedCacheBindingsDiscovery() {
    }

    public static List<GeneratedCacheBindingsRegistrar> discover(ClassLoader classLoader) {
        ServiceLoader<GeneratedCacheBindingsRegistrar> serviceLoader =
                ServiceLoader.load(GeneratedCacheBindingsRegistrar.class, classLoader);
        List<GeneratedCacheBindingsRegistrar> registrars = new ArrayList<>();
        for (GeneratedCacheBindingsRegistrar registrar : serviceLoader) {
            registrars.add(registrar);
        }
        registrars.sort(Comparator.comparing(GeneratedCacheBindingsRegistrar::packageName));
        return List.copyOf(registrars);
    }

    public static int registerDiscovered(CacheDatabase cacheDatabase, CachePolicy cachePolicy, ClassLoader classLoader) {
        List<GeneratedCacheBindingsRegistrar> registrars = discover(classLoader);
        for (GeneratedCacheBindingsRegistrar registrar : registrars) {
            registrar.register(cacheDatabase, cachePolicy);
        }
        return registrars.size();
    }

    public static int registerDiscoveredJdbcBacked(
            CacheDatabase cacheDatabase,
            CachePolicyCatalog policyCatalog,
            ClassLoader classLoader,
            boolean failOnUnknownPolicy
    ) {
        CachePolicyCatalog resolvedCatalog = policyCatalog == null ? CachePolicyCatalog.empty() : policyCatalog;
        List<GeneratedCacheBindingsRegistrar> registrars = discover(classLoader);
        validateEntityNames(registrars, resolvedCatalog, failOnUnknownPolicy);
        for (GeneratedCacheBindingsRegistrar registrar : registrars) {
            registrar.registerJdbcSources(cacheDatabase, resolvedCatalog);
        }
        for (GeneratedCacheBindingsRegistrar registrar : registrars) {
            registrar.registerDeclaredLoaders(cacheDatabase, resolvedCatalog);
        }
        return registrars.size();
    }

    private static void validateEntityNames(
            List<GeneratedCacheBindingsRegistrar> registrars,
            CachePolicyCatalog policyCatalog,
            boolean failOnUnknownPolicy
    ) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (GeneratedCacheBindingsRegistrar registrar : registrars) {
            for (String entityName : registrar.entityNames()) {
                String existingOwner = owners.putIfAbsent(entityName, registrar.packageName());
                if (existingOwner != null && !existingOwner.equals(registrar.packageName())) {
                    throw new IllegalStateException("Generated CacheDB entity name is not unique: " + entityName
                            + " is declared by " + existingOwner + " and " + registrar.packageName());
                }
            }
        }
        if (!failOnUnknownPolicy || policyCatalog.isEmpty()) {
            return;
        }
        Set<String> knownEntities = owners.keySet();
        List<String> unknownEntities = policyCatalog.entityNames().stream()
                .filter(entityName -> !knownEntities.contains(entityName))
                .sorted()
                .toList();
        if (!unknownEntities.isEmpty()) {
            throw new IllegalStateException("CacheDB policies reference unknown generated entities: " + unknownEntities);
        }
    }
}
