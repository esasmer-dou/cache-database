package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.codec.Utf8Length;

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
        List<T> rows = new ArrayList<>(Math.min(settings.fetchRows(), settings.maxRowsPerSource()));
        consume(connection, table, settings, budget, check, rows::add);
        return List.copyOf(rows);
    }

    @SuppressWarnings(
            "unchecked") // narrow preserves the decoder and row type of its declared source.
    static <R> void stream(
            DataSource source,
            SnapshotPlan<R, ?> plan,
            SnapshotSettings settings,
            Runnable check,
            List<String> ids,
            java.util.function.Consumer<R> consumer) {
        SnapshotSource<R> selected =
                ids.isEmpty()
                        ? plan.roots()
                        : (SnapshotSource<R>) plan.rootScope().narrow(plan.roots(), ids);
        try (Connection connection = source.getConnection();
                SnapshotTransaction transaction = SnapshotTransaction.begin(connection)) {
            consume(connection, selected, settings, new Budget(settings), check, consumer);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Snapshot source read failed (SQLState=" + failure.getSQLState() + ")",
                    failure);
        }
    }

    private static <T> void consume(
            Connection connection,
            SnapshotSource<T> table,
            SnapshotSettings settings,
            Budget budget,
            Runnable check,
            java.util.function.Consumer<T> consumer)
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
            int rowCount = 0;
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                String[] columns = new String[metadata.getColumnCount()];
                for (int i = 0; i < columns.length; i++)
                    columns[i] = metadata.getColumnLabel(i + 1);
                int[] types = new int[columns.length];
                for (int i = 0; i < types.length; i++) types[i] = metadata.getColumnType(i + 1);
                RowLayout layout = new RowLayout(columns);
                while (result.next()) {
                    if ((rowCount & 255) == 0) check.run();
                    if (++budget.rows > settings.maxSourceRows()
                            || (table.bounded() && rowCount >= settings.maxRowsPerSource()))
                        throw new IllegalStateException(
                                "Snapshot source row budget exceeded: " + table.name());
                    Object[] values = new Object[columns.length];
                    for (int i = 0; i < columns.length; i++) {
                        Object value =
                                switch (types[i]) {
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
                        values[i] = value;
                    }
                    if (budget.bytes - budget.checkedBytes >= 65536) {
                        check.run();
                        budget.checkedBytes = budget.bytes;
                    }
                    consumer.accept(
                            Objects.requireNonNull(
                                    table.decoder().apply(new Row(layout, values)),
                                    "Null source row"));
                    rowCount++;
                }
            }
            check.run();
        }
    }

    /** One shared index per result set, rather than a column scan per decoded field. */
    private static final class RowLayout {
        final String[] columns;
        final Map<String, Integer> positions = new HashMap<>();

        RowLayout(String[] columns) {
            this.columns = columns;
            for (int i = 0; i < columns.length; i++) positions.putIfAbsent(columns[i], i);
        }
    }

    /** Immutable row; decoders may safely retain it. No per-column map nodes. */
    private static final class Row extends AbstractMap<String, Object> {
        private final RowLayout layout;
        private final Object[] values;

        Row(RowLayout layout, Object[] values) {
            this.layout = layout;
            this.values = values;
        }

        @Override
        public Object get(Object key) {
            Integer position = key instanceof String ? layout.positions.get(key) : null;
            return position == null ? null : values[position];
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String && layout.positions.containsKey(key);
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                public int size() {
                    return layout.columns.length;
                }

                public Iterator<Map.Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        int at;

                        public boolean hasNext() {
                            return at < layout.columns.length;
                        }

                        public Map.Entry<String, Object> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            int i = at++;
                            return new AbstractMap.SimpleImmutableEntry<>(
                                    layout.columns[i], values[i]);
                        }
                    };
                }
            };
        }
    }

    private static final class Budget {
        private final long maxBytes;
        private long bytes;
        private int rows;
        private long checkedBytes;

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
            if (!(value instanceof CharSequence)) {
                long scalar =
                        value instanceof java.math.BigDecimal decimal
                                ? (long) decimal.precision() + 32
                                : value instanceof java.math.BigInteger integer
                                        ? (long) integer.bitLength() * 30103 / 100000 + 2
                                        : value instanceof java.util.UUID
                                                ? 36
                                                : value instanceof Number
                                                        ? 24
                                                        : value instanceof Boolean
                                                                ? 5
                                                                : value
                                                                                        instanceof
                                                                                        java.time
                                                                                                .temporal
                                                                                                .TemporalAccessor
                                                                                || value
                                                                                        instanceof
                                                                                        java.util
                                                                                                .Date
                                                                        ? 40
                                                                        : -1;
                if (scalar >= 0) {
                    bytes += scalar;
                    checkLimit();
                    return;
                }
                // Preserve vendor scalar support; only unknown types use the allocating fallback.
            }
            String text = value.toString();
            bytes += Utf8Length.of(text);
            checkLimit();
        }

        private void checkLimit() {
            if (bytes > maxBytes)
                throw new IllegalStateException("Snapshot source value byte budget exceeded");
        }
    }
}
