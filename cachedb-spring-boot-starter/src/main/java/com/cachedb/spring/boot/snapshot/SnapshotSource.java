package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.query.CacheField;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A trusted, application-declared SELECT. Request parameters must never supply SQL fragments. */
public final class SnapshotSource<T> implements SnapshotInput {
    private final String name;
    private final String sql;
    private final String oracleSql;
    private final Function<Map<String, Object>, T> decoder;
    private final boolean bounded;
    private final SourceMapping<T> mapping;
    private final SnapshotPredicate predicate;

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
        this(name, sql, oracleSql, decoder, bounded, null, null);
    }

    private SnapshotSource(
            String name,
            String sql,
            String oracleSql,
            Function<Map<String, Object>, T> decoder,
            boolean bounded,
            SourceMapping<T> mapping,
            SnapshotPredicate predicate) {
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
        this.mapping = mapping;
        this.predicate = predicate;
    }

    public String name() {
        return name;
    }

    @Override
    public SnapshotSource<T> source() {
        return this;
    }

    public String sql() {
        return sql("PostgreSQL");
    }

    public Function<Map<String, Object>, T> decoder() {
        return decoder;
    }

    public boolean bounded() {
        return bounded;
    }

    String sql(String product) {
        return command(product).sql();
    }

    record Command(String sql, List<Object> parameters) {}

    Command command(String product) {
        List<Object> parameters = new java.util.ArrayList<>();
        String query = "Oracle".equals(product) ? oracleSql : sql;
        if (predicate != null) query += " WHERE " + predicate.sql(product, parameters);
        return new Command(query, List.copyOf(parameters));
    }

    public static <T> SnapshotSource<T> entity(String name, SourceMapping<T> mapping) {
        Objects.requireNonNull(mapping, "mapping");
        String table = table(mapping.table(), "PostgreSQL");
        String columns =
                mapping.columns().stream()
                        .map(SnapshotSource::quote)
                        .collect(Collectors.joining(", "));
        String oracleColumns =
                mapping.columns().stream()
                        .map(c -> column(c, "Oracle") + " AS " + quote(c))
                        .collect(Collectors.joining(", "));
        return new SnapshotSource<>(
                name,
                "SELECT " + columns + " FROM " + table,
                "SELECT " + oracleColumns + " FROM " + table(mapping.table(), "Oracle"),
                mapping.decoder(),
                true,
                mapping,
                null);
    }

    public SnapshotSource<T> where(SnapshotPredicate condition) {
        if (mapping == null)
            throw new IllegalStateException("Typed predicates require a mapped source");
        Objects.requireNonNull(condition, "condition").columns().forEach(this::checkColumn);
        return new SnapshotSource<>(
                name,
                sql,
                oracleSql,
                decoder,
                bounded,
                mapping,
                predicate == null ? condition : predicate.and(condition));
    }

    public <V> SnapshotSource<T> where(CacheField<T, V> field, V value) {
        return where(SnapshotPredicate.eq(field, value));
    }

    public SnapshotSelection select(String field) {
        return new SnapshotSelection(this, field);
    }

    public <V> SnapshotSelection select(CacheField<T, V> field) {
        return select(field.columnName());
    }

    void checkColumn(String field) {
        if (mapping == null || !mapping.columns().contains(field))
            throw new IllegalArgumentException("Undeclared source column: " + name + "." + field);
    }

    String selectSql(String field, String product, List<Object> parameters) {
        checkColumn(field);
        return "SELECT "
                + column(field, product)
                + " FROM "
                + table(mapping.table(), product)
                + (predicate == null ? "" : " WHERE " + predicate.sql(product, parameters));
    }

    private static String table(String table, String product) {
        return Arrays.stream(table.split("\\.", -1))
                .map(part -> column(part, product))
                .collect(Collectors.joining("."));
    }

    static String column(String name, String product) {
        return quote("Oracle".equals(product) ? name.toUpperCase(java.util.Locale.ROOT) : name);
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
        return new SnapshotSource<>(name, sql, oracleSql, decoder, false, mapping, predicate);
    }

    static String quote(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Unsafe snapshot identifier");
        return '"' + identifier + '"';
    }
}
