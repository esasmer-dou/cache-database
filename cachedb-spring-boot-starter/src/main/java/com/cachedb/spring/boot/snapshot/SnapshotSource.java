package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A trusted, application-declared SELECT. Request parameters must never supply SQL fragments. */
public final class SnapshotSource<T> {
    private final String name;
    private final String sql;
    private final String oracleSql;
    private final Function<Map<String, Object>, T> decoder;
    private final boolean bounded;

    public SnapshotSource(String name, String sql, Function<Map<String, Object>, T> decoder) {
        this(name, sql, decoder, true);
    }

    public SnapshotSource(
            String name, String sql, Function<Map<String, Object>, T> decoder, boolean bounded) {
        this(name, sql, sql, decoder, bounded);
    }

    private SnapshotSource(
            String name,
            String sql,
            String oracleSql,
            Function<Map<String, Object>, T> decoder,
            boolean bounded) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]*"))
            throw new IllegalArgumentException("Invalid snapshot source name");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(decoder, "decoder");
        if (!sql.stripLeading().regionMatches(true, 0, "SELECT ", 0, 7) || sql.contains(";"))
            throw new IllegalArgumentException("Snapshot sources require one trusted SELECT");
        this.name = name;
        this.sql = sql;
        this.oracleSql = oracleSql;
        this.decoder = decoder;
        this.bounded = bounded;
    }

    public String name() {
        return name;
    }

    public String sql() {
        return sql;
    }

    public Function<Map<String, Object>, T> decoder() {
        return decoder;
    }

    public boolean bounded() {
        return bounded;
    }

    String sql(String product) {
        return "Oracle".equals(product) ? oracleSql : sql;
    }

    public static <T, ID> SnapshotSource<T> entity(
            String name, EntityMetadata<T, ID> metadata, EntityCodec<T> codec, String predicate) {
        String columns =
                metadata.columns().stream()
                        .map(SnapshotSource::quote)
                        .collect(Collectors.joining(", "));
        String table =
                Arrays.stream(metadata.tableName().split("\\."))
                        .map(SnapshotSource::quote)
                        .collect(Collectors.joining("."));
        String oracleColumns =
                metadata.columns().stream()
                        .map(
                                column ->
                                        quote(column.toUpperCase(java.util.Locale.ROOT))
                                                + " AS "
                                                + quote(column))
                        .collect(Collectors.joining(", "));
        String oracleTable =
                Arrays.stream(metadata.tableName().split("\\."))
                        .map(part -> quote(part.toUpperCase(java.util.Locale.ROOT)))
                        .collect(Collectors.joining("."));
        String suffix = predicate == null || predicate.isBlank() ? "" : " WHERE " + predicate;
        return new SnapshotSource<>(
                name,
                "SELECT " + columns + " FROM " + table + suffix,
                "SELECT " + oracleColumns + " FROM " + oracleTable + suffix,
                codec::fromColumns,
                true);
    }

    /** The total source row and byte budgets still apply; only the per-source cap is disabled. */
    public SnapshotSource<T> withoutPerSourceLimit() {
        return new SnapshotSource<>(name, sql, oracleSql, decoder, false);
    }

    private static String quote(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Unsafe snapshot identifier");
        return '"' + identifier + '"';
    }
}
