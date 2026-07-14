package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.page.VersionedEntitySourceLoader;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryGroup;
import com.reactor.cachedb.core.query.QueryNode;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySortDirection;
import com.reactor.cachedb.core.query.QuerySpec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

public final class JdbcEntitySourceLoader<T, ID>
        implements VersionedEntitySourceLoader<T, ID> {

    private static final String IDENTIFIER_SEGMENT = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String IDENTIFIER = IDENTIFIER_SEGMENT;
    private static final String QUALIFIED_IDENTIFIER = IDENTIFIER_SEGMENT + "(\\." + IDENTIFIER_SEGMENT + ")*";

    private final DataSource dataSource;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final int maxRows;
    private final int queryTimeoutSeconds;
    private final String tableName;
    private final List<String> selectColumns;
    private final Set<String> allowedColumns;

    public JdbcEntitySourceLoader(
            DataSource dataSource,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            int maxRows
    ) {
        this(dataSource, metadata, codec, maxRows, 30);
    }

    public JdbcEntitySourceLoader(
            DataSource dataSource,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            int maxRows,
            int queryTimeoutSeconds
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.maxRows = Math.max(1, maxRows);
        if (queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be greater than zero");
        }
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.tableName = requireQualifiedIdentifier(metadata.tableName(), "tableName");
        this.selectColumns = validateColumns(metadata.columns());
        this.allowedColumns = new HashSet<>(selectColumns);
        this.allowedColumns.add(requireIdentifier(metadata.idColumn(), "idColumn"));
        if (metadata.versionColumn() != null && !metadata.versionColumn().isBlank()) {
            this.allowedColumns.add(requireIdentifier(metadata.versionColumn(), "versionColumn"));
        }
        if (metadata.deletedColumn() != null && !metadata.deletedColumn().isBlank()) {
            this.allowedColumns.add(requireIdentifier(metadata.deletedColumn(), "deletedColumn"));
        }
    }

    @Override
    public Optional<VersionedEntity<T>> loadVersionedById(ID id) {
        Objects.requireNonNull(id, "id");
        ArrayList<Object> parameters = new ArrayList<>();
        parameters.add(id);
        String sql = "SELECT " + selectList()
                + " FROM " + tableName
                + " WHERE " + requireAllowedColumn(metadata.idColumn()) + " = ?"
                + activePredicate(parameters)
                + " ORDER BY " + requireAllowedColumn(metadata.idColumn()) + " ASC"
                + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        parameters.add(0);
        parameters.add(1);
        List<VersionedEntity<T>> rows = executeVersioned(sql, parameters, 1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<VersionedEntity<T>> loadVersionedPage(PageWindow pageWindow) {
        PageWindow normalized = pageWindow == null ? new PageWindow(0, Math.min(maxRows, 100)) : pageWindow;
        int pageSize = boundedLimit(normalized.pageSize());
        ArrayList<Object> parameters = new ArrayList<>();
        String sql = "SELECT " + selectList()
                + " FROM " + tableName
                + " WHERE 1 = 1"
                + activePredicate(parameters)
                + " ORDER BY " + requireAllowedColumn(metadata.idColumn()) + " ASC"
                + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        parameters.add(Math.max(0, normalized.offset()));
        parameters.add(pageSize);
        return executeVersioned(sql, parameters, pageSize);
    }

    @Override
    public List<VersionedEntity<T>> loadVersionedQuery(QuerySpec querySpec) {
        QuerySpec normalized = querySpec == null ? QuerySpec.builder().limit(Math.min(maxRows, 100)).build() : querySpec;
        int limit = boundedLimit(normalized.limit());
        ArrayList<Object> parameters = new ArrayList<>();
        String sql = "SELECT " + selectList()
                + " FROM " + tableName
                + " WHERE " + renderNode(normalized.rootGroup(), parameters)
                + activePredicate(parameters)
                + renderOrderBy(normalized.sorts())
                + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        parameters.add(Math.max(0, normalized.offset()));
        parameters.add(limit);
        return executeVersioned(sql, parameters, limit);
    }

    private List<VersionedEntity<T>> executeVersioned(String sql, List<Object> parameters, int expectedLimit) {
        int boundedExpectedLimit = boundedLimit(expectedLimit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setFetchSize(Math.min(boundedExpectedLimit, maxRows));
            statement.setMaxRows(boundedExpectedLimit);
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<VersionedEntity<T>> rows = new ArrayList<>(Math.min(boundedExpectedLimit, 128));
                while (resultSet.next()) {
                    if (rows.size() >= boundedExpectedLimit) {
                        throw new IllegalStateException("JDBC source loader exceeded maxRows=" + boundedExpectedLimit
                                + " for " + metadata.entityName());
                    }
                    LinkedHashMap<String, Object> columns = rowColumns(resultSet);
                    rows.add(new VersionedEntity<>(codec.fromColumns(columns), versionFrom(columns)));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("JDBC source load failed for " + metadata.entityName() + ": "
                    + exception.getMessage(), exception);
        }
    }

    private long versionFrom(Map<String, Object> columns) {
        String versionColumn = metadata.versionColumn();
        if (versionColumn == null || versionColumn.isBlank()) {
            throw new IllegalStateException("JDBC source loader requires a version column for " + metadata.entityName());
        }
        Object value = columns.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(versionColumn))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Report a single domain-specific error below.
            }
        }
        throw new IllegalStateException(
                "JDBC source row has no positive numeric version in column " + versionColumn
                        + " for " + metadata.entityName()
        );
    }

    private LinkedHashMap<String, Object> rowColumns(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(selectColumns.size());
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String label = metaData.getColumnLabel(index);
            if (label == null || label.isBlank()) {
                label = metaData.getColumnName(index);
            }
            row.put(label, resultSet.getObject(index));
        }
        return row;
    }

    private String renderNode(QueryNode node, List<Object> parameters) {
        if (node instanceof QueryFilter filter) {
            return renderFilter(filter, parameters);
        }
        QueryGroup group = (QueryGroup) node;
        if (group.nodes().isEmpty()) {
            return "1 = 1";
        }
        StringJoiner joiner = new StringJoiner(
                group.operator().name().equals("OR") ? " OR " : " AND ",
                "(",
                ")"
        );
        for (QueryNode child : group.nodes()) {
            joiner.add(renderNode(child, parameters));
        }
        return joiner.toString();
    }

    private String renderFilter(QueryFilter filter, List<Object> parameters) {
        String column = requireAllowedColumn(filter.column());
        return switch (filter.operator()) {
            case EQ -> bind(parameters, filter.value(), column + " = ?");
            case NE -> bind(parameters, filter.value(), column + " <> ?");
            case GT -> bind(parameters, filter.value(), column + " > ?");
            case GTE -> bind(parameters, filter.value(), column + " >= ?");
            case LT -> bind(parameters, filter.value(), column + " < ?");
            case LTE -> bind(parameters, filter.value(), column + " <= ?");
            case CONTAINS -> bind(parameters, "%" + String.valueOf(filter.value()) + "%", column + " LIKE ?");
            case STARTS_WITH -> bind(parameters, String.valueOf(filter.value()) + "%", column + " LIKE ?");
            case IN -> renderInFilter(column, filter.values(), parameters);
        };
    }

    private String renderInFilter(String column, List<Object> values, List<Object> parameters) {
        if (values == null || values.isEmpty()) {
            return "1 = 0";
        }
        if (values.size() > maxRows) {
            throw new IllegalArgumentException("IN filter for " + metadata.entityName()
                    + " has " + values.size() + " values but maxRows=" + maxRows);
        }
        StringJoiner placeholders = new StringJoiner(", ", "(", ")");
        for (Object value : values) {
            parameters.add(value);
            placeholders.add("?");
        }
        return column + " IN " + placeholders;
    }

    private String bind(List<Object> parameters, Object value, String sql) {
        parameters.add(value);
        return sql;
    }

    String renderOrderBy(List<QuerySort> sorts) {
        String idColumn = requireAllowedColumn(metadata.idColumn());
        if (sorts == null || sorts.isEmpty()) {
            return " ORDER BY " + idColumn + " ASC";
        }
        StringJoiner joiner = new StringJoiner(", ", " ORDER BY ", "");
        HashSet<String> explicitSortColumns = new HashSet<>(sorts.size());
        for (QuerySort sort : sorts) {
            String column = requireAllowedColumn(sort.column());
            String direction = sort.direction() == QuerySortDirection.DESC ? "DESC" : "ASC";
            joiner.add(column + " " + direction);
            explicitSortColumns.add(column.toLowerCase(Locale.ROOT));
        }
        if (!explicitSortColumns.contains(idColumn.toLowerCase(Locale.ROOT))) {
            joiner.add(idColumn + " ASC");
        }
        return joiner.toString();
    }

    private String activePredicate(List<Object> parameters) {
        String deletedColumn = metadata.deletedColumn();
        if (deletedColumn == null || deletedColumn.isBlank()) {
            return "";
        }
        parameters.add(metadata.deletedMarkerValue());
        return " AND (" + requireAllowedColumn(deletedColumn) + " IS NULL OR "
                + requireAllowedColumn(deletedColumn) + " <> ?)";
    }

    private int boundedLimit(int requestedLimit) {
        int normalized = requestedLimit <= 0 ? maxRows : requestedLimit;
        if (normalized > maxRows) {
            throw new IllegalArgumentException("JDBC source load for " + metadata.entityName()
                    + " requested " + normalized + " rows but maxRows=" + maxRows);
        }
        return Math.max(1, normalized);
    }

    private String selectList() {
        return String.join(", ", selectColumns);
    }

    private List<String> validateColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Entity " + metadata.entityName() + " must expose at least one JDBC source column");
        }
        ArrayList<String> validated = new ArrayList<>(columns.size() + 1);
        for (String column : columns) {
            validated.add(requireIdentifier(column, "column"));
        }
        String versionColumn = requireIdentifier(metadata.versionColumn(), "versionColumn");
        if (validated.stream().noneMatch(column -> column.equalsIgnoreCase(versionColumn))) {
            validated.add(versionColumn);
        }
        return List.copyOf(validated);
    }

    private String requireAllowedColumn(String column) {
        String normalized = requireIdentifier(column, "queryColumn");
        if (!allowedColumns.contains(normalized)) {
            throw new IllegalArgumentException("Column " + normalized + " is not part of "
                    + metadata.entityName() + " metadata");
        }
        return normalized;
    }

    private static String requireIdentifier(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.matches(IDENTIFIER)) {
            return normalized;
        }
        throw new IllegalArgumentException(label + " must be a safe SQL identifier: " + value);
    }

    private static String requireQualifiedIdentifier(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.matches(QUALIFIED_IDENTIFIER)) {
            return normalized;
        }
        throw new IllegalArgumentException(label + " must be a safe SQL identifier: " + value);
    }
}
