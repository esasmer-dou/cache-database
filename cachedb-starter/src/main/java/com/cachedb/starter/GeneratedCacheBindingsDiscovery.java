package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
}
