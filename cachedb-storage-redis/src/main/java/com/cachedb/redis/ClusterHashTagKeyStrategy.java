package com.reactor.cachedb.redis;

import java.util.Objects;

/** One atomic scope occupies one slot; use independent scopes to distribute memory. */
public final class ClusterHashTagKeyStrategy extends RedisKeyStrategy {
    private final boolean scoped;
    private java.util.function.Supplier<? extends java.util.Collection<String>> namespaces =
            java.util.List::of;

    public void registeredNamespaces(
            java.util.function.Supplier<? extends java.util.Collection<String>> namespaces) {
        this.namespaces = Objects.requireNonNull(namespaces);
    }

    public java.util.List<String> namespaces() {
        return java.util.List.copyOf(namespaces.get());
    }

    public java.util.List<String> activeStreamKeys(
            com.reactor.cachedb.core.config.WriteBehindConfig config) {
        if (scoped) return config.activeStreamKeys();
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String ns : namespaces())
            for (String base : config.activeStreamKeys()) result.add(writeStreamKey(base, ns));
        return result;
    }

    public ClusterHashTagKeyStrategy(String prefix) {
        super(Objects.requireNonNull(prefix, "prefix"));
        scoped = prefix.contains("{");
        if (scoped) validateTagged(prefix);
    }

    public ClusterHashTagKeyStrategy(String prefix, String scope) {
        this(scopedPrefix(prefix, scope));
    }

    public ClusterHashTagKeyStrategy(com.reactor.cachedb.core.config.KeyspaceConfig config) {
        super(
                config.keyPrefix(),
                config.entitySegment(),
                config.pageSegment(),
                config.versionSegment(),
                config.tombstoneSegment(),
                config.hotSetSegment(),
                config.indexSegment(),
                config.compactionSegment());
        scoped = config.keyPrefix().contains("{");
        if (scoped) validateTagged(config.keyPrefix());
    }

    public boolean namespaceSharded() {
        return !scoped;
    }

    @Override
    public String writeStreamKey(String base, String namespace) {
        return scoped
                ? base
                : streamKey(namespace) + ":" + base.replace('{', '_').replace('}', '_');
    }

    @Override
    public String compactionStreamKey(String base, String namespace, Object id, int shards) {
        return super.compactionStreamKey(writeStreamKey(base, namespace), namespace, id, shards);
    }

    @Override
    public String compactionStatsKey(String namespace) {
        return scoped ? compactionStatsKey() : namespacePrefix(namespace) + ":compaction:stats";
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
        return namespacePrefix(namespace) + ":stream:write-behind";
    }
}
