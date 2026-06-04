package com.reactor.cachedb.starter;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

final class MigrationRedisMemoryEstimator {

    private static final int SAMPLE_ROW_LIMIT = 512;
    private static final long DEFAULT_ROOT_ROW_BYTES = 384L;
    private static final long DEFAULT_CHILD_ROW_BYTES = 640L;
    private static final double ENTITY_SERIALIZATION_MULTIPLIER = 1.70d;
    private static final double PROJECTION_SUMMARY_RATIO = 0.45d;
    private static final long REDIS_STRING_AND_KEY_OVERHEAD_BYTES = 160L;
    private static final long HOTSET_ENTRY_BYTES = 72L;
    private static final long SORTED_INDEX_ENTRY_BYTES = 104L;
    private static final long PAGE_REFERENCE_BYTES = 44L;
    private static final long PAGE_KEY_OVERHEAD_BYTES = 128L;
    private static final long MIN_STREAM_ALLOWANCE_BYTES = 8L * 1024L * 1024L;
    private static final double SAFETY_HEADROOM_RATIO = 0.35d;
    private static final double MAXMEMORY_HEADROOM_RATIO = 0.25d;

    private final DataSource dataSource;
    private final MigrationSchemaDiscovery schemaDiscovery;

    MigrationRedisMemoryEstimator(DataSource dataSource, MigrationSchemaDiscovery schemaDiscovery) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.schemaDiscovery = Objects.requireNonNull(schemaDiscovery, "schemaDiscovery");
    }

    Result estimate(MigrationPlanner.Result plan) {
        Objects.requireNonNull(plan, "plan");
        MigrationPlanner.Request request = plan.request();
        ArrayList<String> assumptions = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        assumptions.add("This is a sizing estimate, not Redis MEMORY USAGE output. Validate it after staging warm.");
        assumptions.add("PostgreSQL row sampling is capped at " + SAMPLE_ROW_LIMIT + " rows per selected table to avoid a full payload scan.");
        assumptions.add("Redis object overhead, key names, indexes, projection payloads, page cache, streams, allocator fragmentation, and safety headroom are modeled separately.");

        MigrationSchemaDiscovery.Result discoveryResult = safeDiscover(warnings);
        MigrationSchemaDiscovery.TableInfo rootTable = resolveTable(discoveryResult, request.rootTableOrEntity());
        MigrationSchemaDiscovery.TableInfo childTable = resolveTable(discoveryResult, request.childTableOrEntity());
        if (rootTable == null) {
            warnings.add("Root table could not be resolved from discovery; root row count and payload size use planner inputs and conservative defaults.");
        }
        if (childTable == null) {
            warnings.add("Child table could not be resolved from discovery; child row count and payload size use planner inputs and conservative defaults.");
        }

        long rootRowCount = Math.max(0L, request.rootRowCount());
        long childRowCount = Math.max(0L, request.childRowCount());
        long rootAverageBytes = DEFAULT_ROOT_ROW_BYTES;
        long childAverageBytes = DEFAULT_CHILD_ROW_BYTES;
        boolean rootAverageMeasured = false;
        boolean childAverageMeasured = false;
        boolean rootCountMeasured = rootRowCount > 0L;
        boolean childCountMeasured = childRowCount > 0L;
        String source = "PLANNER_INPUT";

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            DatabaseKind databaseKind = databaseKind(productName);
            connection.setReadOnly(true);
            if (rootTable != null) {
                RowEstimate estimate = estimateTable(connection, databaseKind, rootTable, rootRowCount, DEFAULT_ROOT_ROW_BYTES, warnings);
                rootRowCount = estimate.rowCount();
                rootAverageBytes = estimate.averageRowBytes();
                rootAverageMeasured = estimate.averageMeasured();
                rootCountMeasured = estimate.rowCountMeasured();
            }
            if (childTable != null) {
                RowEstimate estimate = estimateTable(connection, databaseKind, childTable, childRowCount, DEFAULT_CHILD_ROW_BYTES, warnings);
                childRowCount = estimate.rowCount();
                childAverageBytes = estimate.averageRowBytes();
                childAverageMeasured = estimate.averageMeasured();
                childCountMeasured = estimate.rowCountMeasured();
            }
            if (rootAverageMeasured || childAverageMeasured) {
                source = databaseKind == DatabaseKind.POSTGRESQL ? "POSTGRESQL_SAMPLE" : "JDBC_SAMPLE";
            }
        } catch (SQLException exception) {
            warnings.add("Could not sample PostgreSQL rows for Redis memory estimate: " + exception.getMessage());
        }

        long childHotRows = estimateChildHotRows(plan, rootRowCount, childRowCount);
        long rootHotRows = estimateRootHotRows(request, rootRowCount, childHotRows);
        long childEntityHotRows = plan.fullEntityHotWindowRecommended() ? childHotRows : 0L;
        long rootEntityAverageRedisBytes = estimateEntityRedisBytes(rootAverageBytes);
        long childEntityAverageRedisBytes = estimateEntityRedisBytes(childAverageBytes);
        long projectionAverageRedisBytes = estimateProjectionRedisBytes(childAverageBytes);

        ArrayList<Component> components = new ArrayList<>();
        addComponent(
                components,
                "ROOT_ENTITY",
                request.rootTableOrEntity(),
                rootHotRows,
                rootEntityAverageRedisBytes,
                multiplySaturated(rootHotRows, rootEntityAverageRedisBytes),
                "Root entity payload kept hot for route ownership, direct lookup, and relation anchor reads."
        );
        if (plan.projectionRequired()) {
            addComponent(
                    components,
                    "CHILD_PROJECTION",
                    plan.summaryProjectionName(),
                    childHotRows,
                    projectionAverageRedisBytes,
                    multiplySaturated(childHotRows, projectionAverageRedisBytes),
                    "Summary projection payload for the hot list first paint."
            );
        } else {
            childEntityHotRows = childHotRows;
        }
        addComponent(
                components,
                "CHILD_ENTITY",
                request.childTableOrEntity(),
                childEntityHotRows,
                childEntityAverageRedisBytes,
                multiplySaturated(childEntityHotRows, childEntityAverageRedisBytes),
                plan.projectionRequired()
                        ? "Optional child entity payload for hot detail lookups when the planner still recommends it."
                        : "Child entity payload for direct entity reads because the route does not require projection yet."
        );

        long indexEntries = saturatingAdd(rootHotRows, childHotRows);
        long indexBytes = saturatingAdd(
                multiplySaturated(rootHotRows, HOTSET_ENTRY_BYTES),
                multiplySaturated(childHotRows, HOTSET_ENTRY_BYTES + SORTED_INDEX_ENTRY_BYTES)
        );
        addComponent(
                components,
                "INDEX_AND_HOTSET",
                request.relationColumn() + " + " + request.sortColumn(),
                indexEntries,
                indexEntries == 0L ? 0L : Math.max(1L, indexBytes / indexEntries),
                indexBytes,
                "Redis hot-set membership and sorted-index entries used to serve the bounded window without scanning payload keys."
        );

        long pageRows = estimatePageReferenceRows(request, childHotRows, rootHotRows);
        long pageBytes = saturatingAdd(
                multiplySaturated(pageRows, PAGE_REFERENCE_BYTES),
                multiplySaturated(Math.max(1L, Math.min(rootHotRows, 100_000L)), PAGE_KEY_OVERHEAD_BYTES)
        );
        addComponent(
                components,
                "PAGE_CACHE",
                request.workloadName(),
                pageRows,
                PAGE_REFERENCE_BYTES,
                pageBytes,
                "Allowance for cached first-page result references and page keys after the route is exercised."
        );

        long streamAllowance = Math.max(
                MIN_STREAM_ALLOWANCE_BYTES,
                multiplySaturated(Math.max(1L, Math.min(saturatingAdd(rootHotRows, childHotRows), 500_000L)), 24L)
        );
        addComponent(
                components,
                "STREAM_OPS",
                "write-behind/projection/admin",
                1L,
                streamAllowance,
                streamAllowance,
                "Operational allowance for short-lived streams, projection refresh state, admin telemetry, and queue metadata."
        );

        long subtotal = components.stream()
                .mapToLong(Component::estimatedBytes)
                .reduce(0L, MigrationRedisMemoryEstimator::saturatingAdd);
        long headroomBytes = ceilMultiply(subtotal, SAFETY_HEADROOM_RATIO);
        addComponent(
                components,
                "HEADROOM",
                "allocator/fragmentation/safety",
                1L,
                headroomBytes,
                headroomBytes,
                "Safety margin for Redis allocator fragmentation, key-name variance, codec variance, and short burst growth."
        );

        long estimatedTotal = saturatingAdd(subtotal, headroomBytes);
        long recommendedMaxmemory = saturatingAdd(estimatedTotal, ceilMultiply(estimatedTotal, MAXMEMORY_HEADROOM_RATIO));
        if (rootRowCount == 0L && rootHotRows == 0L) {
            warnings.add("Root row count is zero or unknown; Redis estimate may understate root entity memory.");
        }
        if (childRowCount == 0L && childHotRows == 0L) {
            warnings.add("Child row count is zero or unknown; Redis estimate may understate projection/index memory.");
        }
        if (plan.boundedRedisWindowRequired() && request.fullHistoryMustStayHot()) {
            warnings.add("The route asks for full history hot, but the planner recommends a bounded Redis window. Size Redis for the bounded plan unless the product explicitly accepts unbounded growth.");
        }
        if (!rootAverageMeasured || !childAverageMeasured) {
            warnings.add("At least one table could not be row-sampled; default row-byte assumptions were used.");
        }

        String confidence = confidence(rootAverageMeasured, childAverageMeasured, rootCountMeasured, childCountMeasured);
        return new Result(
                source,
                confidence,
                rootTable == null ? request.rootTableOrEntity() : rootTable.qualifiedTableName(),
                childTable == null ? request.childTableOrEntity() : childTable.qualifiedTableName(),
                rootRowCount,
                childRowCount,
                rootHotRows,
                childHotRows,
                childEntityHotRows,
                rootAverageBytes,
                childAverageBytes,
                rootAverageMeasured,
                childAverageMeasured,
                subtotal,
                headroomBytes,
                estimatedTotal,
                recommendedMaxmemory,
                List.copyOf(components),
                List.copyOf(assumptions),
                List.copyOf(warnings),
                Instant.now()
        );
    }

    private MigrationSchemaDiscovery.Result safeDiscover(ArrayList<String> warnings) {
        try {
            return schemaDiscovery.discover();
        } catch (IllegalStateException exception) {
            warnings.add("Schema discovery was not available for Redis memory estimate: " + exception.getMessage());
            return null;
        }
    }

    private RowEstimate estimateTable(
            Connection connection,
            DatabaseKind databaseKind,
            MigrationSchemaDiscovery.TableInfo table,
            long plannerRowCount,
            long defaultAverageBytes,
            ArrayList<String> warnings
    ) {
        long rowCount = Math.max(0L, plannerRowCount);
        boolean rowCountMeasured = rowCount > 0L;
        Optional<Long> databaseRowCount = estimateRowCount(connection, databaseKind, table, warnings);
        if (databaseRowCount.isPresent() && databaseRowCount.get() >= 0L) {
            rowCount = databaseRowCount.get();
            rowCountMeasured = true;
        }
        Optional<Long> sampledAverage = estimateAverageRowBytes(connection, databaseKind, table, warnings);
        if (sampledAverage.isPresent() && sampledAverage.get() > 0L) {
            return new RowEstimate(rowCount, sampledAverage.get(), true, rowCountMeasured);
        }
        return new RowEstimate(rowCount, defaultAverageBytes, false, rowCountMeasured);
    }

    private Optional<Long> estimateRowCount(
            Connection connection,
            DatabaseKind databaseKind,
            MigrationSchemaDiscovery.TableInfo table,
            ArrayList<String> warnings
    ) {
        if ("VIEW".equalsIgnoreCase(table.objectType())) {
            warnings.add("Row count estimate for view " + table.qualifiedTableName() + " is not read from pg_class; planner input is used.");
            return Optional.empty();
        }
        if (databaseKind == DatabaseKind.POSTGRESQL) {
            String schema = table.schemaName() == null || table.schemaName().isBlank() ? "public" : table.schemaName();
            String sql = """
                    SELECT COALESCE(GREATEST(c.reltuples, 0), 0)::bigint AS estimated_rows
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, schema);
                statement.setString(2, table.tableName());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(Math.max(0L, resultSet.getLong(1)));
                    }
                }
            } catch (SQLException exception) {
                warnings.add("Could not read pg_class.reltuples for " + table.qualifiedTableName() + ": " + exception.getMessage());
            }
            return Optional.empty();
        }
        if (databaseKind == DatabaseKind.H2) {
            String sql = "SELECT COUNT(*) FROM " + quotedTableName(table);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    return Optional.of(Math.max(0L, resultSet.getLong(1)));
                }
            } catch (SQLException exception) {
                warnings.add("Could not count rows for " + table.qualifiedTableName() + ": " + exception.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<Long> estimateAverageRowBytes(
            Connection connection,
            DatabaseKind databaseKind,
            MigrationSchemaDiscovery.TableInfo table,
            ArrayList<String> warnings
    ) {
        if (databaseKind == DatabaseKind.POSTGRESQL) {
            String sql = "SELECT COALESCE(AVG(pg_column_size(source)), 0) FROM (SELECT * FROM "
                    + quotedTableName(table)
                    + " LIMIT ?) source";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, SAMPLE_ROW_LIMIT);
                statement.setFetchSize(SAMPLE_ROW_LIMIT);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(Math.max(0L, Math.round(resultSet.getDouble(1))));
                    }
                }
            } catch (SQLException exception) {
                warnings.add("Could not sample PostgreSQL row size for " + table.qualifiedTableName() + ": " + exception.getMessage());
            }
            return Optional.empty();
        }
        String sql = "SELECT * FROM " + quotedTableName(table);
        long rowCount = 0L;
        long totalBytes = 0L;
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(SAMPLE_ROW_LIMIT);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (resultSet.next()) {
                    rowCount++;
                    for (int column = 1; column <= columnCount; column++) {
                        totalBytes = saturatingAdd(totalBytes, estimateJdbcValueBytes(resultSet.getObject(column)));
                    }
                }
            }
        } catch (SQLException exception) {
            warnings.add("Could not sample row size for " + table.qualifiedTableName() + ": " + exception.getMessage());
        }
        if (rowCount == 0L) {
            return Optional.empty();
        }
        return Optional.of(Math.max(1L, totalBytes / rowCount));
    }

    private long estimateJdbcValueBytes(Object value) {
        if (value == null) {
            return 1L;
        }
        if (value instanceof byte[] bytes) {
            return 24L + bytes.length;
        }
        if (value instanceof CharSequence sequence) {
            return 40L + sequence.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        if (value instanceof Integer || value instanceof Float) {
            return 16L;
        }
        if (value instanceof Long || value instanceof Double || value instanceof Timestamp || value instanceof java.sql.Date) {
            return 24L;
        }
        if (value instanceof BigDecimal decimal) {
            return 32L + decimal.precision();
        }
        if (value instanceof Boolean) {
            return 8L;
        }
        return 32L + value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private long estimateChildHotRows(MigrationPlanner.Result plan, long rootRowCount, long childRowCount) {
        MigrationPlanner.Request request = plan.request();
        long normalizedChildRows = Math.max(0L, childRowCount);
        if (normalizedChildRows == 0L) {
            return 0L;
        }
        if (request.fullHistoryMustStayHot() && !plan.boundedRedisWindowRequired()) {
            return normalizedChildRows;
        }
        if (plan.rankedProjectionRequired()) {
            return Math.min(normalizedChildRows, Math.max(request.firstPageSize(), plan.recommendedHotWindowPerRoot()));
        }
        long effectiveRootRows = Math.max(rootRowCount, request.rootRowCount());
        if (effectiveRootRows <= 0L && request.typicalChildrenPerRoot() > 0L) {
            effectiveRootRows = Math.max(1L, normalizedChildRows / Math.max(1L, request.typicalChildrenPerRoot()));
        }
        if (effectiveRootRows <= 0L) {
            return Math.min(normalizedChildRows, plan.recommendedHotWindowPerRoot());
        }
        return Math.min(normalizedChildRows, multiplySaturated(effectiveRootRows, plan.recommendedHotWindowPerRoot()));
    }

    private long estimateRootHotRows(MigrationPlanner.Request request, long rootRowCount, long childHotRows) {
        long normalizedRootRows = Math.max(0L, rootRowCount);
        if (normalizedRootRows > 0L) {
            return normalizedRootRows;
        }
        if (request.typicalChildrenPerRoot() <= 0L) {
            return childHotRows > 0L ? 1L : 0L;
        }
        return Math.max(0L, childHotRows / Math.max(1L, request.typicalChildrenPerRoot()));
    }

    private long estimateEntityRedisBytes(long postgresRowBytes) {
        long payloadBytes = ceilMultiply(Math.max(1L, postgresRowBytes), ENTITY_SERIALIZATION_MULTIPLIER);
        return saturatingAdd(payloadBytes, REDIS_STRING_AND_KEY_OVERHEAD_BYTES);
    }

    private long estimateProjectionRedisBytes(long postgresRowBytes) {
        long projectedBytes = Math.max(96L, ceilMultiply(Math.max(1L, postgresRowBytes), PROJECTION_SUMMARY_RATIO));
        return saturatingAdd(ceilMultiply(projectedBytes, ENTITY_SERIALIZATION_MULTIPLIER), REDIS_STRING_AND_KEY_OVERHEAD_BYTES);
    }

    private long estimatePageReferenceRows(MigrationPlanner.Request request, long childHotRows, long rootHotRows) {
        if (childHotRows <= 0L || rootHotRows <= 0L) {
            return 0L;
        }
        long firstPageCoverage = multiplySaturated(rootHotRows, Math.max(1L, request.firstPageSize()));
        return Math.min(childHotRows, firstPageCoverage);
    }

    private void addComponent(
            ArrayList<Component> components,
            String code,
            String surface,
            long rowCount,
            long averageBytes,
            long estimatedBytes,
            String description
    ) {
        if (estimatedBytes <= 0L && !"CHILD_ENTITY".equals(code)) {
            return;
        }
        components.add(new Component(
                code,
                surface == null ? "" : surface,
                Math.max(0L, rowCount),
                Math.max(0L, averageBytes),
                Math.max(0L, estimatedBytes),
                description == null ? "" : description
        ));
    }

    private MigrationSchemaDiscovery.TableInfo resolveTable(MigrationSchemaDiscovery.Result discoveryResult, String surface) {
        if (discoveryResult == null || surface == null || surface.isBlank()) {
            return null;
        }
        String normalized = normalize(surface);
        for (MigrationSchemaDiscovery.TableInfo table : discoveryResult.tables()) {
            if (normalize(table.qualifiedTableName()).equals(normalized)
                    || normalize(table.tableName()).equals(normalized)
                    || normalize(table.registeredEntityName()).equals(normalized)) {
                return table;
            }
        }
        return null;
    }

    private String quotedTableName(MigrationSchemaDiscovery.TableInfo table) {
        if (table.schemaName() == null || table.schemaName().isBlank() || "public".equalsIgnoreCase(table.schemaName())) {
            return quoteIdentifier(table.tableName());
        }
        return quoteIdentifier(table.schemaName()) + "." + quoteIdentifier(table.tableName());
    }

    private String quoteIdentifier(String identifier) {
        String safe = identifier == null ? "" : identifier.trim();
        if (safe.isBlank()) {
            throw new IllegalArgumentException("Blank SQL identifier");
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private DatabaseKind databaseKind(String productName) {
        String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("postgresql")) {
            return DatabaseKind.POSTGRESQL;
        }
        if (normalized.contains("h2")) {
            return DatabaseKind.H2;
        }
        return DatabaseKind.OTHER;
    }

    private String confidence(
            boolean rootAverageMeasured,
            boolean childAverageMeasured,
            boolean rootCountMeasured,
            boolean childCountMeasured
    ) {
        if (rootAverageMeasured && childAverageMeasured && rootCountMeasured && childCountMeasured) {
            return "HIGH";
        }
        if ((rootAverageMeasured || childAverageMeasured) && (rootCountMeasured || childCountMeasured)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static long ceilMultiply(long value, double multiplier) {
        if (value <= 0L || multiplier <= 0d) {
            return 0L;
        }
        double result = Math.ceil(value * multiplier);
        if (result >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) result;
    }

    private static long multiplySaturated(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatingAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            return Long.MAX_VALUE;
        }
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record Result(
            String source,
            String confidence,
            String rootTable,
            String childTable,
            long estimatedRootRows,
            long estimatedChildRows,
            long rootHotRows,
            long childHotRows,
            long childEntityHotRows,
            long rootAveragePostgresRowBytes,
            long childAveragePostgresRowBytes,
            boolean rootAverageMeasured,
            boolean childAverageMeasured,
            long subtotalBytes,
            long headroomBytes,
            long estimatedTotalBytes,
            long recommendedMaxmemoryBytes,
            List<Component> components,
            List<String> assumptions,
            List<String> warnings,
            Instant estimatedAt
    ) {
    }

    record Component(
            String code,
            String surface,
            long rowCount,
            long averageBytes,
            long estimatedBytes,
            String description
    ) {
    }

    private record RowEstimate(
            long rowCount,
            long averageRowBytes,
            boolean averageMeasured,
            boolean rowCountMeasured
    ) {
    }

    private enum DatabaseKind {
        POSTGRESQL,
        H2,
        OTHER
    }
}
