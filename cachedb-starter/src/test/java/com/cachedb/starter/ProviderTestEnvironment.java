package com.reactor.cachedb.starter;

/** Keeps explicit JVM overrides while allowing private in-cluster fixture credentials. */
final class ProviderTestEnvironment {
    private ProviderTestEnvironment() {}

    static String property(String key, String fallback) {
        return System.getProperty(key, System.getenv().getOrDefault(
                key.replace('.', '_').toUpperCase(java.util.Locale.ROOT), fallback));
    }
}
