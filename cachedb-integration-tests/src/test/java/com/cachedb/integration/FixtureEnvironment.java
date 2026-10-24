package com.reactor.cachedb.integration;

/** Optional disposable fixture environment, preserving existing command-line overrides. */
public final class FixtureEnvironment {
    public static String value(String property) { return value(property, null); }
    public static String value(String property, String fallback) {
        String env = System.getenv(property.toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
        return System.getProperty(property, env == null ? fallback : env);
    }
    private FixtureEnvironment() {}
}
