package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.EntityMetadata;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Background-flush schema validation only; no foreground ownership/source query. */
final class EncryptedJdbcSchemaGuard {
    private static final int MAX_MAPPINGS = 4096;
    private record CheckedSchema(String table, Map<String, Long> capacities) {}
    private final Map<EntityMetadata<?, ?>, CheckedSchema> verified = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private static String escaped(String value, String escape) {
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    boolean verifyCached(EntityMetadata<?, ?> metadata, Map<String, String> values) {
        CheckedSchema schema;
        lock.lock();
        try { schema = verified.get(metadata); }
        finally { lock.unlock(); }
        if (schema == null) {
            return metadata.encryptedColumns().values().stream().noneMatch(EncryptedColumnDescriptor::encryptsSource);
        }
        verifyValues(metadata, schema, values);
        return true;
    }

    void verify(DatabaseMetaData database, EntityMetadata<?, ?> metadata, Map<String, String> values) throws SQLException {
        if (verifyCached(metadata, values)) return;
        if (metadata.encryptedColumns().values().stream().noneMatch(EncryptedColumnDescriptor::encryptsSource)) return;
        lock.lock();
        try {
            if (verified.containsKey(metadata)) {
                verifyValues(metadata, verified.get(metadata), values);
                return;
            }
            if (verified.size() >= MAX_MAPPINGS) throw EncryptedCodecSupport.unbound();
        } finally { lock.unlock(); }
        // JDBC metadata access is never performed while holding the local cache lock.
        var required = new HashMap<String, String>();
        metadata.encryptedColumns().forEach((column, descriptor) -> {
            if (descriptor.encryptsSource() && required.put(column.toLowerCase(Locale.ROOT), column) != null)
                throw EncryptedCodecSupport.unbound();
        });
        String mappedTable = metadata.tableName();
        String[] names = mappedTable.split("\\.");
        if (names.length < 1 || names.length > 3) throw EncryptedCodecSupport.unbound();
        String table = names[names.length - 1], schema = names.length > 1 ? names[names.length - 2] : null;
        String catalog = names.length == 3 ? names[0] : null;
        String escape = database.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) throw EncryptedCodecSupport.unbound();
        String lookupTable = database.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT)
                : database.storesLowerCaseIdentifiers() ? table.toLowerCase(Locale.ROOT) : table;
        String lookupSchema = schema == null ? null : database.storesUpperCaseIdentifiers() ? schema.toUpperCase(Locale.ROOT)
                : database.storesLowerCaseIdentifiers() ? schema.toLowerCase(Locale.ROOT) : schema;
        String pattern = escaped(lookupTable, escape);
        String schemaPattern = lookupSchema == null ? null : escaped(lookupSchema, escape);
        var observed = new HashSet<String>();
        var capacities = new HashMap<String, Long>();
        try (ResultSet columns = database.getColumns(catalog, schemaPattern, pattern, "%")) {
            int visited = 0;
            while (columns.next()) {
                if (++visited > 4096) throw EncryptedCodecSupport.unbound();
                if (!table.equalsIgnoreCase(columns.getString("TABLE_NAME"))) continue;
                String column = columns.getString("COLUMN_NAME");
                if (column == null || !required.containsKey(column.toLowerCase(Locale.ROOT))) continue;
                if (!observed.add(column.toLowerCase(Locale.ROOT))) throw EncryptedCodecSupport.unbound();
                int type = columns.getInt("DATA_TYPE");
                if (type != Types.VARCHAR && type != Types.NVARCHAR && type != Types.LONGVARCHAR && type != Types.LONGNVARCHAR)
                    throw EncryptedCodecSupport.unbound();
                long capacity = columns.getLong("COLUMN_SIZE");
                if (columns.wasNull() || capacity < 1) throw EncryptedCodecSupport.unbound();
                capacities.put(required.get(column.toLowerCase(Locale.ROOT)), capacity);
            }
        }
        if (!observed.equals(required.keySet())) throw EncryptedCodecSupport.unbound();
        var immutable = new CheckedSchema(mappedTable, Map.copyOf(capacities));
        lock.lock();
        try {
            var previous = verified.get(metadata);
            if (previous != null && !previous.equals(immutable)) throw EncryptedCodecSupport.unbound();
            if (previous == null && verified.size() >= MAX_MAPPINGS) throw EncryptedCodecSupport.unbound();
            verifyValues(metadata, immutable, values);
            verified.putIfAbsent(metadata, immutable);
        } finally { lock.unlock(); }
    }

    private static void verifyValues(EntityMetadata<?, ?> metadata, CheckedSchema schema,
            Map<String, String> values) {
        if (!Objects.equals(schema.table(), metadata.tableName())) throw EncryptedCodecSupport.unbound();
        var capacities = schema.capacities();
        if (values == null || values.size() > EncryptedCodecSupport.MAX_ENTITY_COLUMNS + 2)
            throw EncryptedCodecSupport.invalidValue();
        int expected = 0;
        for (var entry : metadata.encryptedColumns().entrySet()) {
            if (!entry.getValue().encryptsSource()) continue;
            expected++;
            Long capacity = capacities.get(entry.getKey());
            if (capacity == null) throw EncryptedCodecSupport.unbound();
            for (String column : values.keySet())
                if (!entry.getKey().equals(column) && entry.getKey().equalsIgnoreCase(column))
                    throw EncryptedCodecSupport.invalidValue();
            String value = values.get(entry.getKey());
            if (value == null) continue; // Absent PATCH fields and SQL NULL are not envelopes.
            if (value.length() > JcaFieldEncryption.MAX_FIELD_BYTES || value.length() > capacity)
                throw new CacheEncryptionException(CacheEncryptionException.Code.FIELD_LIMIT);
            if (!value.startsWith("~E1:")) throw EncryptedCodecSupport.invalidValue();
            // Authenticated envelopes are ASCII: character, UTF-8 byte and UTF-16 unit counts agree.
            for (int i = 0; i < value.length(); i++)
                if (value.charAt(i) < 0x21 || value.charAt(i) > 0x7e) throw EncryptedCodecSupport.invalidValue();
        }
        if (expected != capacities.size()) throw EncryptedCodecSupport.unbound();
    }
}
