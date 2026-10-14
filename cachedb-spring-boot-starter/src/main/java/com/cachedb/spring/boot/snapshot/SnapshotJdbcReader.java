package com.reactor.cachedb.spring.boot.snapshot;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

/** Shared JDBC mechanics; all declarations are read on the same SELECT-only snapshot. */
final class SnapshotJdbcReader {
    static SnapshotRows read(
            DataSource source, SnapshotPlan<?, ?> plan, SnapshotSettings settings, Runnable check) {
        return read(source, plan, settings, check, List.of());
    }

    static SnapshotRows read(
            DataSource source,
            SnapshotPlan<?, ?> plan,
            SnapshotSettings settings,
            Runnable check,
            List<String> rootIds) {
        Map<SnapshotSource<?>, List<?>> tables = new LinkedHashMap<>();
        Budget budget = new Budget(settings);
        try (Connection connection = source.getConnection()) {
            try (SnapshotTransaction transaction = SnapshotTransaction.begin(connection)) {
                for (var table : plan.sources()) {
                    SnapshotSource<?> selected =
                            rootIds.isEmpty() ? table : plan.rootScope().narrow(table, rootIds);
                    tables.put(table, read(connection, selected, settings, budget, check));
                }
            }
            return new SnapshotRows(tables);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Snapshot source read failed (SQLState=" + failure.getSQLState() + ")",
                    failure);
        }
    }

    private static <T> List<T> read(
            Connection connection,
            SnapshotSource<T> table,
            SnapshotSettings settings,
            Budget budget,
            Runnable check)
            throws SQLException {
        check.run();
        String product = connection.getMetaData().getDatabaseProductName();
        var command = table.command(product);
        if ("Microsoft SQL Server".equals(product) && command.parameters().size() > 2100)
            throw new IllegalArgumentException(
                    "SQL Server snapshot parameter limit exceeded (2100)");
        try (PreparedStatement statement = connection.prepareStatement(command.sql())) {
            for (int i = 0; i < command.parameters().size(); i++) {
                Object value = command.parameters().get(i);
                if ("Oracle".equals(product) && value instanceof Boolean flag) value = flag ? 1 : 0;
                if (value instanceof java.time.Instant instant)
                    value = java.sql.Timestamp.from(instant);
                if (value instanceof java.math.BigInteger integer)
                    value = new java.math.BigDecimal(integer);
                if (value instanceof java.util.UUID && !"PostgreSQL".equals(product))
                    value = value.toString();
                statement.setObject(i + 1, value);
            }
            statement.setFetchSize(settings.fetchRows());
            statement.setQueryTimeout(
                    (int) Math.max(1, Math.min(30, settings.preparationTimeout().toSeconds())));
            statement.setMaxRows(
                    Math.min(
                                    settings.maxSourceRows(),
                                    table.bounded()
                                            ? settings.maxRowsPerSource()
                                            : settings.maxSourceRows())
                            + 1);
            List<T> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                String[] columns = new String[metadata.getColumnCount()];
                for (int i = 0; i < columns.length; i++)
                    columns[i] = metadata.getColumnLabel(i + 1);
                while (result.next()) {
                    check.run();
                    if (++budget.rows > settings.maxSourceRows()
                            || (table.bounded() && rows.size() >= settings.maxRowsPerSource()))
                        throw new IllegalStateException(
                                "Snapshot source row budget exceeded: " + table.name());
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columns.length; i++) {
                        Object value =
                                switch (metadata.getColumnType(i + 1)) {
                                    // JDBC and vendor type codes use the same standard Java-time
                                    // decoder.
                                    case Types.TIMESTAMP_WITH_TIMEZONE, -101, -102, -155 ->
                                            result.getObject(i + 1, java.time.OffsetDateTime.class);
                                    case Types.TIMESTAMP -> result.getTimestamp(i + 1);
                                    case Types.TIME -> result.getTime(i + 1);
                                    default -> result.getObject(i + 1);
                                };
                        if (value instanceof Blob
                                || value instanceof Clob
                                || value instanceof SQLXML
                                || value instanceof java.sql.Array
                                || value instanceof Struct)
                            throw new IllegalArgumentException(
                                    "Snapshot sources require scalar columns; cast or omit"
                                            + " LOB/structured column: "
                                            + columns[i]);
                        budget.add(value);
                        row.put(columns[i], value);
                    }
                    rows.add(Objects.requireNonNull(table.decoder().apply(row), "Null source row"));
                }
            }
            return List.copyOf(rows);
        }
    }

    private static final class Budget {
        private final long maxBytes;
        private long bytes;
        private int rows;

        Budget(SnapshotSettings settings) {
            maxBytes = settings.maxSourceSize().toBytes();
        }

        void add(Object value) {
            if (value == null) return;
            if (value instanceof byte[] binary) {
                bytes += binary.length;
                checkLimit();
                return;
            }
            String text = value.toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 128) bytes++;
                else if (c < 2048) bytes += 2;
                else if (Character.isHighSurrogate(c)
                        && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                    bytes += 4;
                    i++;
                } else bytes += Character.isSurrogate(c) ? 1 : 3;
            }
            checkLimit();
        }

        private void checkLimit() {
            if (bytes > maxBytes)
                throw new IllegalStateException("Snapshot source value byte budget exceeded");
        }
    }
}
