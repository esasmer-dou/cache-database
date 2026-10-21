package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

/** Durable SQL high-water marks, including physical deletes. Never expire this table by TTL. */
public final class JdbcWriteFence {
    public static final String TABLE = "cachedb_write_fence";
    private static final int KEY_BATCH_SIZE = 500;

    public enum Provider {
        POSTGRES,
        MSSQL,
        ORACLE
    }

    private final DataSource source;
    private final EntityRegistry registry;
    private final Provider provider;
    private final int timeout;
    private final Map<String, String> tableScopes = new ConcurrentHashMap<>();
    private final ReentrantLock initializationLock = new ReentrantLock();
    private volatile boolean initialized;
    private String initializedContext;

    public JdbcWriteFence(
            DataSource source, EntityRegistry registry, Provider provider, int timeout) {
        this.source = Objects.requireNonNull(source, "source");
        this.registry = registry;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.timeout = Math.max(1, timeout);
    }

    /** Run before borrowing the business connection; Oracle DDL must never commit business DML. */
    public void initialize() throws SQLException {
        if (initialized) return;
        initializationLock.lock();
        try {
            if (initialized) return;
            try (Connection connection = source.getConnection()) {
                try {
                    validate(connection);
                } catch (SQLException missing) {
                    if (!missingTable(missing)) throw missing;
                    if (!connection.getAutoCommit()) {
                        throw new SQLException(
                                "Provision "
                                        + TABLE
                                        + " using the CacheDB migration before writing; DDL"
                                        + " requires an autocommit connection",
                                "CDB03",
                                missing);
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(timeout);
                        statement.executeUpdate(createTableSql(provider));
                    } catch (SQLException createFailure) {
                        if (transientFailure(createFailure)) throw createFailure;
                        // A different pod may have created it. Validation, not the DDL error code,
                        // determines whether it is safe to proceed.
                        try {
                            validate(connection);
                        } catch (SQLException invalid) {
                            if (transientFailure(invalid)) throw invalid;
                            SQLException failure =
                                    new SQLException(
                                            "Cannot initialize "
                                                    + TABLE
                                                    + "; apply the CacheDB write-fence migration"
                                                    + " and grant SELECT/INSERT/UPDATE. No write"
                                                    + " was acknowledged.",
                                            "CDB03",
                                            createFailure);
                            failure.addSuppressed(missing);
                            failure.addSuppressed(invalid);
                            throw failure;
                        }
                    }
                    validate(connection);
                }
                initializedContext = context(connection);
                initialized = true;
            }
        } finally {
            initializationLock.unlock();
        }
    }

    public static String createTableSql(Provider provider) {
        String text =
                switch (provider) {
                    case POSTGRES -> "TEXT";
                    case MSSQL -> "NVARCHAR(MAX)";
                    case ORACLE -> "CLOB";
                };
        String number = provider == Provider.ORACLE ? "NUMBER(19)" : "BIGINT";
        return "CREATE TABLE "
                + TABLE
                + " (fence_key CHAR(64) NOT NULL PRIMARY KEY, identity_value "
                + text
                + " NOT NULL, entity_version "
                + number
                + ", deleted SMALLINT NOT NULL)";
    }

    private void validate(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                prepare(
                        connection,
                        "SELECT fence_key, identity_value, entity_version, deleted FROM "
                                + TABLE
                                + " WHERE 1=0")) {
            try (ResultSet ignored = statement.executeQuery()) {
                /* Validate before any business DML. */
            }
        }
        var metadata = connection.getMetaData();
        String table =
                metadata.storesUpperCaseIdentifiers()
                        ? TABLE.toUpperCase(java.util.Locale.ROOT)
                        : TABLE;
        int columns = 0;
        boolean key = false;
        try (ResultSet keys =
                metadata.getPrimaryKeys(connection.getCatalog(), connection.getSchema(), table)) {
            while (keys.next()) {
                columns++;
                key = "fence_key".equalsIgnoreCase(keys.getString("COLUMN_NAME"));
            }
        }
        if (columns != 1 || !key)
            throw new SQLException("Write-fence table must have PRIMARY KEY (fence_key)", "CDB03");
    }

    /**
     * Claims and locks all identities in a transaction, then returns accepted writes in input
     * order. Metadata updates and the caller's DML must be committed or rolled back on this SAME
     * connection. Seed/lock/update work is bounded and batched, not one network query per entity.
     */
    public List<QueuedWriteOperation> filter(
            Connection connection, List<QueuedWriteOperation> operations) throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("Write fence requires the business transaction", "CDB03");
        if (!initialized || !Objects.equals(initializedContext, context(connection)))
            throw new SQLException(
                    "Initialize the write fence for this datasource's fixed catalog/schema before"
                            + " writing",
                    "CDB03");
        if (operations.isEmpty()) return operations;
        TreeMap<String, Entry> entries = new TreeMap<>();
        ArrayList<Entry> ordered = new ArrayList<>(operations.size());
        MessageDigest digest = sha256();
        for (QueuedWriteOperation operation : operations) {
            if (operation.atomicBatch())
                throw new SQLException("Flatten the atomic envelope before fencing", "CDB03");
            String identity = identity(connection, operation);
            String key =
                    HexFormat.of()
                            .formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
            Entry entry = entries.computeIfAbsent(key, ignored -> new Entry(key, identity));
            if (!entry.identity.equals(identity)) throw collision();
            ordered.add(entry);
        }
        ArrayList<Entry> sorted = new ArrayList<>(entries.values());
        for (int start = 0; start < sorted.size(); start += KEY_BATCH_SIZE) {
            List<Entry> chunk =
                    sorted.subList(start, Math.min(start + KEY_BATCH_SIZE, sorted.size()));
            seed(connection, chunk);
            lock(connection, chunk);
        }
        ArrayList<QueuedWriteOperation> accepted = new ArrayList<>(operations.size());
        for (int i = 0; i < operations.size(); i++) {
            QueuedWriteOperation operation = operations.get(i);
            Entry entry = ordered.get(i);
            boolean delete = operation.type() == OperationType.DELETE;
            if (entry.version == null
                    || operation.version() > entry.version
                    || (operation.version() == entry.version && delete && !entry.deleted)) {
                accepted.add(operation);
                entry.version = operation.version();
                entry.deleted = delete;
                entry.changed = true;
            }
        }
        try (PreparedStatement update =
                prepare(
                        connection,
                        "UPDATE " + TABLE + " SET entity_version=?, deleted=? WHERE fence_key=?")) {
            int count = 0;
            for (Entry entry : sorted) {
                if (!entry.changed) continue;
                update.setLong(1, entry.version);
                update.setInt(2, entry.deleted ? 1 : 0);
                update.setString(3, entry.key);
                update.addBatch();
                if (++count % KEY_BATCH_SIZE == 0) {
                    verifyUpdates(update.executeBatch(), KEY_BATCH_SIZE);
                    update.clearBatch();
                }
            }
            if (count % KEY_BATCH_SIZE != 0)
                verifyUpdates(update.executeBatch(), count % KEY_BATCH_SIZE);
        }
        return accepted;
    }

    private void seed(Connection connection, List<Entry> entries) throws SQLException {
        if (provider == Provider.ORACLE) {
            // Catch only a duplicate seed, not a business-table constraint violation.
            String item =
                    "BEGIN INSERT INTO "
                            + TABLE
                            + " (fence_key,identity_value,entity_version,deleted) VALUES"
                            + " (?,?,NULL,0); EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL; END;";
            String sql = "BEGIN " + item.repeat(entries.size()) + " END;";
            try (PreparedStatement statement = prepare(connection, sql)) {
                int index = 1;
                for (Entry entry : entries) {
                    statement.setString(index++, entry.key);
                    statement.setString(index++, entry.identity);
                }
                statement.executeUpdate();
            }
            return;
        }
        String values = String.join(",", java.util.Collections.nCopies(entries.size(), "(?,?)"));
        String sql =
                provider == Provider.POSTGRES
                        ? "INSERT INTO "
                                + TABLE
                                + " (fence_key,identity_value,entity_version,deleted) SELECT"
                                + " v.k,v.i,NULL,0 FROM (VALUES "
                                + values
                                + ") v(k,i) ORDER BY v.k ON CONFLICT (fence_key) DO NOTHING"
                        : "MERGE "
                                + TABLE
                                + " WITH (HOLDLOCK) AS t USING (VALUES "
                                + values
                                + ") AS v(k,i) ON t.fence_key=v.k WHEN NOT MATCHED THEN INSERT"
                                + " (fence_key,identity_value,entity_version,deleted) VALUES"
                                + " (v.k,v.i,NULL,0);";
        try (PreparedStatement statement = prepare(connection, sql)) {
            int index = 1;
            for (Entry entry : entries) {
                statement.setString(index++, entry.key);
                statement.setString(index++, entry.identity);
            }
            statement.executeUpdate();
        }
    }

    private void lock(Connection connection, List<Entry> entries) throws SQLException {
        String markers = String.join(",", java.util.Collections.nCopies(entries.size(), "?"));
        String sql =
                "SELECT fence_key,identity_value,entity_version,deleted FROM "
                        + TABLE
                        + (provider == Provider.MSSQL ? " WITH (UPDLOCK,HOLDLOCK)" : "")
                        + " WHERE fence_key IN ("
                        + markers
                        + ") ORDER BY fence_key"
                        + (provider == Provider.MSSQL ? "" : " FOR UPDATE");
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry entry : entries) byKey.put(entry.key, entry);
        try (PreparedStatement statement = prepare(connection, sql)) {
            for (int i = 0; i < entries.size(); i++) statement.setString(i + 1, entries.get(i).key);
            int found = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Entry entry = byKey.get(result.getString(1));
                    if (entry == null || !entry.identity.equals(result.getString(2)))
                        throw collision();
                    long version = result.getLong(3);
                    entry.version = result.wasNull() ? null : version;
                    entry.deleted = result.getInt(4) != 0;
                    found++;
                }
            }
            if (found != entries.size())
                throw new SQLException("Incomplete write-fence lock set", "CDB03");
        }
    }

    private String identity(Connection connection, QueuedWriteOperation operation)
            throws SQLException {
        String input = operation.tableName();
        String scope = tableScopes.get(input);
        if (scope == null) {
            scope = resolveTable(connection, operation.tableName());
            tableScopes.putIfAbsent(input, scope);
        }
        String type = JdbcWriteBehindSupport.columnType(registry, operation, operation.idColumn());
        Object value =
                JdbcWriteBehindSupport.convertValue(
                        operation.id(), type == null ? "java.lang.String" : type);
        if (value == null) throw new SQLException("Null write identity", "CDB03");
        String id =
                value instanceof BigDecimal decimal
                        ? decimal.stripTrailingZeros().toPlainString()
                        : value.toString();
        // Length-prefix each part; identifiers/IDs may themselves contain delimiters.
        return scope.length() + ":" + scope + id.length() + ":" + id;
    }

    private String resolveTable(Connection connection, String table) throws SQLException {
        String sql =
                switch (provider) {
                    case POSTGRES ->
                            "SELECT n.nspname,c.relname FROM pg_catalog.pg_class c JOIN"
                                    + " pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE"
                                    + " c.oid=pg_catalog.to_regclass(?)";
                    case MSSQL ->
                            "SELECT"
                                + " OBJECT_SCHEMA_NAME(OBJECT_ID(?,'U')),OBJECT_NAME(OBJECT_ID(?,'U'))";
                    case ORACLE ->
                            "SELECT owner,table_name FROM all_tables WHERE owner=? AND"
                                    + " table_name=?";
                };
        try (PreparedStatement statement = prepare(connection, sql)) {
            if (provider == Provider.ORACLE) {
                String[] parts = table.split("\\.", -1);
                if (parts.length > 2)
                    throw new SQLException(
                            "Use owner.table for Oracle write-fence identity", "CDB03");
                statement.setString(
                        1, parts.length == 2 ? oracleIdentifier(parts[0]) : connection.getSchema());
                statement.setString(2, oracleIdentifier(parts[parts.length - 1]));
            } else {
                statement.setString(1, table);
                if (provider == Provider.MSSQL) statement.setString(2, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString(1) == null || result.getString(2) == null)
                    throw new SQLException("Cannot resolve physical write table " + table, "CDB03");
                String schema = result.getString(1);
                return schema.length() + ":" + schema + result.getString(2);
            }
        }
    }

    private static String oracleIdentifier(String value) {
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1).replace("\"\"", "\"")
                : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String context(Connection connection) throws SQLException {
        return Objects.toString(connection.getCatalog(), "")
                + '\u001f'
                + Objects.toString(connection.getSchema(), "");
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout);
        return statement;
    }

    private static void verifyUpdates(int[] outcomes, int expected) throws SQLException {
        if (outcomes == null || outcomes.length != expected)
            throw new SQLException(
                    "Write-fence batch returned an incomplete result count", "CDB03");
        for (int outcome : outcomes)
            if (outcome != 1 && outcome != Statement.SUCCESS_NO_INFO)
                throw new SQLException("Write-fence update lost a locked identity", "CDB03");
    }

    private static SQLException collision() {
        return new SQLException(
                "Write-fence identity collision or corrupt metadata; refusing the write", "CDB03");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean missingTable(SQLException failure) {
        for (SQLException current = failure;
                current != null;
                current = current.getNextException()) {
            if ("42P01".equals(current.getSQLState())
                    || "42S02".equals(current.getSQLState())
                    || "42S04".equals(current.getSQLState())
                    || current.getErrorCode() == 208
                    || current.getErrorCode() == 942) return true;
        }
        return false;
    }

    private static boolean transientFailure(SQLException failure) {
        String state = failure.getSQLState();
        return failure instanceof java.sql.SQLTransientException
                || failure instanceof java.sql.SQLRecoverableException
                || state != null
                        && (state.startsWith("08")
                                || state.startsWith("40")
                                || state.equals("55P03")
                                || state.equals("57014"))
                || failure.getErrorCode() == 1205
                || failure.getErrorCode() == 1222;
    }

    private static final class Entry {
        final String key;
        final String identity;
        Long version;
        boolean deleted;
        boolean changed;

        Entry(String key, String identity) {
            this.key = key;
            this.identity = identity;
        }
    }
}
