package com.reactor.cachedb.starter;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PostgresOutboxExternalChangeFeedAdapter implements ExternalChangeFeedAdapter {

    private final DataSource dataSource;
    private final String adapterName;
    private final String outboxTable;
    private final String checkpointTable;
    private final String idColumn;
    private final String entityColumn;
    private final String entityIdColumn;
    private final String typeColumn;
    private final String payloadColumn;
    private final String versionColumn;
    private final String occurredAtColumn;
    private final String sourceColumn;
    private final int batchSize;
    private final long pollIntervalMillis;
    private final boolean createCheckpointTable;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<RuntimeException> lastFailure = new AtomicReference<>();
    private volatile Thread worker;

    private PostgresOutboxExternalChangeFeedAdapter(Builder builder) {
        this.dataSource = Objects.requireNonNull(builder.dataSource, "dataSource");
        this.adapterName = requireText(builder.adapterName, "adapterName");
        this.outboxTable = requireIdentifier(builder.outboxTable, "outboxTable", true);
        this.checkpointTable = requireIdentifier(builder.checkpointTable, "checkpointTable", true);
        this.idColumn = requireIdentifier(builder.idColumn, "idColumn", false);
        this.entityColumn = requireIdentifier(builder.entityColumn, "entityColumn", false);
        this.entityIdColumn = requireIdentifier(builder.entityIdColumn, "entityIdColumn", false);
        this.typeColumn = requireIdentifier(builder.typeColumn, "typeColumn", false);
        this.payloadColumn = requireIdentifier(builder.payloadColumn, "payloadColumn", false);
        this.versionColumn = requireIdentifier(builder.versionColumn, "versionColumn", false);
        this.occurredAtColumn = requireIdentifier(builder.occurredAtColumn, "occurredAtColumn", false);
        this.sourceColumn = requireIdentifier(builder.sourceColumn, "sourceColumn", false);
        this.batchSize = Math.max(1, builder.batchSize);
        this.pollIntervalMillis = Math.max(50L, builder.pollIntervalMillis);
        this.createCheckpointTable = builder.createCheckpointTable;
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    @Override
    public void start(ExternalChangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> runLoop(sink), "cachedb-postgres-outbox-" + adapterName);
        worker.setDaemon(true);
        worker.start();
    }

    public int pollOnce(ExternalChangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        try {
            initialize();
            long checkpoint = readCheckpoint();
            PollBatch batch = readBatch(checkpoint);
            long lastAcceptedId = checkpoint;
            int accepted = 0;
            try {
                for (OutboxRow row : batch.rows()) {
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
            RuntimeException failure = new IllegalStateException("PostgreSQL outbox polling failed for adapter " + adapterName, exception);
            lastFailure.set(failure);
            throw failure;
        }
    }

    public void initialize() throws SQLException {
        if (!createCheckpointTable) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + checkpointTable + " ("
                    + "adapter_name TEXT PRIMARY KEY,"
                    + "last_event_id BIGINT NOT NULL,"
                    + "updated_at TIMESTAMPTZ NOT NULL"
                    + ")");
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
        String sql = "SELECT last_event_id FROM " + checkpointTable + " WHERE adapter_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adapterName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0L;
                }
                return resultSet.getLong(1);
            }
        }
    }

    private PollBatch readBatch(long checkpoint) throws SQLException {
        String sql = "SELECT "
                + idColumn + ", "
                + entityColumn + ", "
                + entityIdColumn + ", "
                + typeColumn + ", "
                + payloadColumn + ", "
                + versionColumn + ", "
                + occurredAtColumn + ", "
                + sourceColumn
                + " FROM " + outboxTable
                + " WHERE " + idColumn + " > ?"
                + " ORDER BY " + idColumn + " ASC"
                + " LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, checkpoint);
            statement.setInt(2, batchSize);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.ArrayList<OutboxRow> rows = new java.util.ArrayList<>();
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
                return new PollBatch(java.util.List.copyOf(rows));
            }
        }
    }

    private void writeCheckpoint(long lastEventId) throws SQLException {
        String sql = "INSERT INTO " + checkpointTable
                + " (adapter_name, last_event_id, updated_at) VALUES (?, ?, now())"
                + " ON CONFLICT (adapter_name) DO UPDATE SET"
                + " last_event_id = EXCLUDED.last_event_id,"
                + " updated_at = EXCLUDED.updated_at";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adapterName);
            statement.setLong(2, lastEventId);
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

    private static String requireIdentifier(String value, String name, boolean allowQualified) {
        String normalized = requireText(value, name);
        String pattern = allowQualified
                ? "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"
                : "[A-Za-z_][A-Za-z0-9_]*";
        if (!normalized.matches(pattern)) {
            throw new IllegalArgumentException(name + " contains an unsafe SQL identifier: " + normalized);
        }
        return normalized;
    }

    public static final class Builder {
        private final DataSource dataSource;
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

        private Builder(DataSource dataSource) {
            this.dataSource = dataSource;
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

        public PostgresOutboxExternalChangeFeedAdapter build() {
            return new PostgresOutboxExternalChangeFeedAdapter(this);
        }
    }

    private record PollBatch(java.util.List<OutboxRow> rows) {
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
