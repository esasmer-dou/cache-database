package com.reactor.cachedb.spring.boot.observe;

import java.util.*;

/** Startup-declared schema/route allowlists, not an on-demand cache of request values. */
public final class CacheObservationTagPolicy {
    private final Set<String> entities;
    private final Set<String> routes;
    public CacheObservationTagPolicy(Set<String> entities, Set<String> routes) {
        this.entities = checked(entities, 1024); this.routes = checked(routes, 256);
    }
    public static CacheObservationTagPolicy defaults() { return new CacheObservationTagPolicy(Set.of(), Set.of()); }
    private static Set<String> checked(Set<String> values, int maximum) {
        Objects.requireNonNull(values, "declared names");
        if (values.size() > maximum) throw new IllegalArgumentException("Observation name allowlist exceeds " + maximum);
        var copy = new LinkedHashSet<String>();
        int examined = 0;
        for (String name : values) {
            if (++examined > maximum || name == null || name.length() > 128
                    || !name.matches("[A-Za-z_][A-Za-z0-9_.:#-]{0,127}"))
                throw new IllegalArgumentException("Observation names must be bounded declared identifiers");
            copy.add(name);
        }
        return Set.copyOf(copy);
    }
    public String entity(String name) { return name != null && name.length() <= 128 && entities.contains(name) ? name : "unknown"; }
    public String route(String name) { return name != null && name.length() <= 128 && routes.contains(name) ? name : null; }
    static String databaseSystem(String name) {
        if (name == null || name.length() > 32) return "other";
        return switch (name) {
            case "redis", "postgresql", "mssql", "oracle" -> name;
            default -> "other";
        };
    }
}
