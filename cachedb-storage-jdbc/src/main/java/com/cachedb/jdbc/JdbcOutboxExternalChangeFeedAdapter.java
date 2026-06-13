package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.change.ExternalChangeFeedAdapter;
import com.reactor.cachedb.core.change.ExternalChangeSink;
import com.reactor.cachedb.core.change.ExternalChangeType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JdbcOutboxExternalChangeFeedAdapter implements ExternalChangeFeedAdapter {

    private final DataSource dataSource;
    private final JdbcOutboxDialect dialect;
    private final String adapterName;
    private final JdbcOutboxMapping mapping;
    private final int batchSize;
    private final long pollIntervalMillis;
    private final boolean createCheckpointTable;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<RuntimeException> lastFailure = new AtomicReference<>();
    private volatile Thread worker;

    private JdbcOutboxExternalChangeFeedAdapter(Builder builder) {
        this.dataSource = Objects.requireNonNull(builder.dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(builder.dialect, "dialect");
        this.adapterName = requireText(builder.adapterName, "adapterName");
        this.mapping = new JdbcOutboxMapping(
                builder.outboxTable,
                builder.checkpointTable,
                builder.idColumn,
                builder.entityColumn,
                builder.entityIdColumn,
                builder.typeColumn,
                builder.payloadColumn,
                builder.versionColumn,
                builder.occurredAtColumn,
                builder.sourceColumn
        );
        this.batchSize = Math.max(1, builder.batchSize);
        this.pollIntervalMillis = Math.max(50L, builder.pollIntervalMillis);
        this.createCheckpointTable = builder.createCheckpointTable;
    }

    public static Builder builder(DataSource dataSource, JdbcOutboxDialect dialect) {
        return new Builder(dataSource, dialect);
    }

    @Override
    public void start(ExternalChangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> runLoop(sink), "cachedb-" + dialect.name() + "-outbox-" + adapterName);
        worker.setDaemon(true);
        worker.start();
    }

    public int pollOnce(ExternalChangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        try {
            initialize();
            long checkpoint = readCheckpoint();
            List<OutboxRow> rows = readBatch(checkpoint);
            long lastAcceptedId = checkpoint;
            int accepted = 0;
            try {
                for (OutboxRow row : rows) {
                    sink.accept(row.event());
                    lastAcceptedId = row.id();
                    accepted++;
                }
            } finally {
                if (lastAcceptedId > checkpoint) {
                    writeCheckpoint(lastAcceptedId);
                }
            }
            lastFailure.set(null);
            return accepted;
        } catch (SQLException exception) {
            RuntimeException failure = new IllegalStateException(
                    dialect.name() + " outbox polling failed for adapter " + adapterName,
                    exception
            );
            lastFailure.set(failure);
            throw failure;
        } catch (RuntimeException exception) {
            lastFailure.set(exception);
            throw exception;
        }
    }

    public void initialize() throws SQLException {
        if (!createCheckpointTable) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(dialect.createCheckpointTableSql(mapping.checkpointTable()));
        }
    }

    public RuntimeException lastFailure() {
        return lastFailure.get();
    }

    @Override
    public void close() {
        running.set(false);
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            try {
                currentWorker.join(5_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop(ExternalChangeSink sink) {
        while (running.get()) {
            try {
                int accepted = pollOnce(sink);
                if (accepted == 0) {
                    Thread.sleep(pollIntervalMillis);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (RuntimeException exception) {
                lastFailure.set(exception);
                sleepAfterFailure();
            }
        }
    }

    private void sleepAfterFailure() {
        try {
            Thread.sleep(pollIntervalMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private long readCheckpoint() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(dialect.readCheckpointSql(mapping.checkpointTable()))) {
            statement.setString(1, adapterName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0L;
                }
                return resultSet.getLong(1);
            }
        }
    }

    private List<OutboxRow> readBatch(long checkpoint) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(dialect.readBatchSql(mapping, batchSize))) {
            dialect.bindReadBatch(statement, checkpoint, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<OutboxRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    long id = resultSet.getLong(1);
                    rows.add(new OutboxRow(id, new ExternalChangeEvent(
                            resultSet.getString(2),
                            resultSet.getObject(3),
                            parseType(resultSet.getString(4)),
                            parseFlatJsonObject(resultSet.getString(5)),
                            resultSet.getLong(6),
                            toInstant(resultSet.getTimestamp(7)),
                            resultSet.getString(8)
                    )));
                }
                return List.copyOf(rows);
            }
        }
    }

    private void writeCheckpoint(long lastEventId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(dialect.writeCheckpointSql(mapping.checkpointTable()))) {
            dialect.bindWriteCheckpoint(statement, adapterName, lastEventId);
            statement.executeUpdate();
        }
    }

    private ExternalChangeType parseType(String value) {
        if (value == null || value.isBlank()) {
            return ExternalChangeType.UPSERT;
        }
        return ExternalChangeType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static Map<String, Object> parseFlatJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        FlatJsonParser parser = new FlatJsonParser(json);
        return parser.parseObject();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private final DataSource dataSource;
        private final JdbcOutboxDialect dialect;
        private String adapterName = "cachedb-outbox";
        private String outboxTable = "cachedb_outbox";
        private String checkpointTable = "cachedb_outbox_adapter_checkpoint";
        private String idColumn = "id";
        private String entityColumn = "entity_name";
        private String entityIdColumn = "entity_id";
        private String typeColumn = "event_type";
        private String payloadColumn = "payload_json";
        private String versionColumn = "entity_version";
        private String occurredAtColumn = "occurred_at";
        private String sourceColumn = "event_source";
        private int batchSize = 100;
        private long pollIntervalMillis = 1_000L;
        private boolean createCheckpointTable = true;

        private Builder(DataSource dataSource, JdbcOutboxDialect dialect) {
            this.dataSource = dataSource;
            this.dialect = dialect;
        }

        public Builder adapterName(String adapterName) {
            this.adapterName = adapterName;
            return this;
        }

        public Builder outboxTable(String outboxTable) {
            this.outboxTable = outboxTable;
            return this;
        }

        public Builder checkpointTable(String checkpointTable) {
            this.checkpointTable = checkpointTable;
            return this;
        }

        public Builder idColumn(String idColumn) {
            this.idColumn = idColumn;
            return this;
        }

        public Builder entityColumn(String entityColumn) {
            this.entityColumn = entityColumn;
            return this;
        }

        public Builder entityIdColumn(String entityIdColumn) {
            this.entityIdColumn = entityIdColumn;
            return this;
        }

        public Builder typeColumn(String typeColumn) {
            this.typeColumn = typeColumn;
            return this;
        }

        public Builder payloadColumn(String payloadColumn) {
            this.payloadColumn = payloadColumn;
            return this;
        }

        public Builder versionColumn(String versionColumn) {
            this.versionColumn = versionColumn;
            return this;
        }

        public Builder occurredAtColumn(String occurredAtColumn) {
            this.occurredAtColumn = occurredAtColumn;
            return this;
        }

        public Builder sourceColumn(String sourceColumn) {
            this.sourceColumn = sourceColumn;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder pollIntervalMillis(long pollIntervalMillis) {
            this.pollIntervalMillis = pollIntervalMillis;
            return this;
        }

        public Builder createCheckpointTable(boolean createCheckpointTable) {
            this.createCheckpointTable = createCheckpointTable;
            return this;
        }

        public JdbcOutboxExternalChangeFeedAdapter build() {
            return new JdbcOutboxExternalChangeFeedAdapter(this);
        }
    }

    private record OutboxRow(long id, ExternalChangeEvent event) {
    }

    private static final class FlatJsonParser {
        private final String value;
        private int index;

        private FlatJsonParser(String value) {
            this.value = value.trim();
        }

        private Map<String, Object> parseObject() {
            skipWhitespace();
            if (!consume('{')) {
                return Map.of("_payload", value);
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return values;
            }
            while (index < value.length()) {
                String key = parseString();
                skipWhitespace();
                if (!consume(':')) {
                    return Map.of("_payload", value);
                }
                Object parsedValue = parseValue();
                values.put(key, parsedValue);
                skipWhitespace();
                if (consume('}')) {
                    return values;
                }
                if (!consume(',')) {
                    return Map.of("_payload", value);
                }
            }
            return Map.of("_payload", value);
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= value.length()) {
                return null;
            }
            char current = value.charAt(index);
            if (current == '"') {
                return parseString();
            }
            if (startsWith("true")) {
                index += 4;
                return true;
            }
            if (startsWith("false")) {
                index += 5;
                return false;
            }
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            return parseNumberOrToken();
        }

        private Object parseNumberOrToken() {
            int start = index;
            while (index < value.length()) {
                char current = value.charAt(index);
                if (current == ',' || current == '}' || Character.isWhitespace(current)) {
                    break;
                }
                index++;
            }
            String token = value.substring(start, index);
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                return token;
            }
        }

        private String parseString() {
            if (!consume('"')) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            while (index < value.length()) {
                char current = value.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\' && index < value.length()) {
                    char escaped = value.charAt(index++);
                    builder.append(switch (escaped) {
                        case '"', '\\', '/' -> escaped;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                } else {
                    builder.append(current);
                }
            }
            return builder.toString();
        }

        private boolean startsWith(String expected) {
            return value.regionMatches(index, expected, 0, expected.length());
        }

        private boolean consume(char expected) {
            skipWhitespace();
            if (index < value.length() && value.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
        }
    }
}
