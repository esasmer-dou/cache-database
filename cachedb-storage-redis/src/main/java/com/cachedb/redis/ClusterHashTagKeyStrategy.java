package com.reactor.cachedb.redis;

import java.util.Objects;

/** One atomic scope occupies one slot; use independent scopes to distribute memory. */
public final class ClusterHashTagKeyStrategy extends RedisKeyStrategy {
    private final boolean scoped;

    public ClusterHashTagKeyStrategy(String prefix) {
        super(Objects.requireNonNull(prefix, "prefix"));
        scoped = prefix.contains("{");
        if (scoped) validateTagged(prefix);
    }

    public ClusterHashTagKeyStrategy(String prefix, String scope) {
        this(scopedPrefix(prefix, scope));
    }

    public static String scopedPrefix(String prefix, String scope) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Invalid cluster key prefix");
        return prefix + ":" + tag(scope);
    }

    public static void validateTagged(String key) {
        int begin = key == null ? -1 : key.indexOf('{');
        int end = begin < 0 ? -1 : key.indexOf('}', begin);
        if (begin < 0 || end <= begin + 1)
            throw new IllegalArgumentException(
                    "Redis Cluster requires an explicit nonempty hash tag");
        tag(key.substring(begin + 1, end));
    }

    private static String tag(String scope) {
        if (scope == null || !scope.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Invalid cluster atomic scope");
        return "{" + scope + "}";
    }

    @Override
    protected String namespacePart(String namespace) {
        // Projection namespaces share their owning entity's slot.
        if (scoped) return namespace;
        int projection = namespace.indexOf(":projection:");
        return tag(projection < 0 ? namespace : namespace.substring(0, projection))
                + (projection < 0 ? "" : namespace.substring(projection));
    }

    public String streamKey(String namespace) {
        return entityKey(namespace, "").replace(":entity:", ":stream:write-behind");
    }
}
