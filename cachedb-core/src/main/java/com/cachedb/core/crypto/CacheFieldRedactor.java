package com.reactor.cachedb.core.crypto;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded column-map redaction. Never invokes a protected value's toString(). */
public final class CacheFieldRedactor {
    private CacheFieldRedactor() {}

    public static Map<String, Object> redactColumns(EntityMetadata<?, ?> metadata, Map<String, ?> columns) {
        if (metadata == null || columns == null) throw EncryptedCodecSupport.invalidValue();
        if (columns.size() > LengthPrefixedPayloadCodec.MAX_FIELDS + 2) {
            throw new CacheEncryptionException(CacheEncryptionException.Code.FIELD_LIMIT);
        }
        Map<String, EncryptedColumnDescriptor> encrypted = metadata.encryptedColumns();
        if (encrypted == null || encrypted.size() > EncryptedCodecSupport.MAX_ENCRYPTED_COLUMNS) throw EncryptedCodecSupport.invalidValue();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (var entry : columns.entrySet()) {
            String column = entry.getKey();
            boolean protectedColumn = column != null && column.startsWith("$cachedb:crypto:");
            if (column != null && !protectedColumn) for (String encryptedColumn : encrypted.keySet())
                if (encryptedColumn.equalsIgnoreCase(column)) { protectedColumn = true; break; }
            safe.put(column, protectedColumn ? "[REDACTED]" : entry.getValue());
        }
        return Collections.unmodifiableMap(safe);
    }

    /** Preserve safe direct SQL retry metadata, not driver text, next exceptions or causes. */
    public static java.sql.SQLException redactSqlFailure(java.sql.SQLException failure) {
        if (failure == null) throw EncryptedCodecSupport.invalidValue();
        String state = failure.getSQLState();
        boolean valid = state != null && state.length() == 5;
        if (valid) for (int i = 0; i < state.length(); i++) {
            char c = state.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) { valid = false; break; }
        }
        if (!valid) state = "HY000";
        int vendor = failure.getErrorCode();
        String message = "CacheDB protected SQL operation failed";
        if (failure instanceof java.sql.SQLTimeoutException) return new java.sql.SQLTimeoutException(message, state, vendor);
        if (failure instanceof java.sql.SQLTransientException) return new java.sql.SQLTransientException(message, state, vendor);
        if (failure instanceof java.sql.SQLRecoverableException) return new java.sql.SQLRecoverableException(message, state, vendor);
        return new java.sql.SQLException(message, state, vendor);
    }
}
