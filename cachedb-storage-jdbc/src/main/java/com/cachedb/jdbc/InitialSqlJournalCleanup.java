package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.jdbc.InitialSqlCleanupPlan.Store;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Fixed-store, exact-pair/operation cleanup. Caller holds the real durable guard lock. */
final class InitialSqlJournalCleanup {
    private static final List<Store> STORES = InitialSqlCleanupPlan.stores();
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout;
    InitialSqlJournalCleanup(JdbcTenantPurgeDialect dialect, int timeout) {
        this.dialect = Objects.requireNonNull(dialect);
        if (timeout < 1 || timeout > 5) throw new ProtectionException(INVALID_REQUEST);
        this.timeout = timeout;
    }
    /** Deletes <=1 LOB row (<=8MiB page or <=1MiB frame), without fetching the LOB. */
    boolean removeOne(Connection c, ProtectionKey key, UUID operation) throws SQLException {
        for (Store store : STORES) {
            List<Object> row = first(c, key, operation, store);
            if (row == null) continue;
            try (var q = prepare(c, store.deleteSelected(dialect))) {
                bind(q, key, operation);
                for (int i = 0; i < store.columns().size(); i++) {
                    if (store.columns().get(i).width() == 0) q.setInt(4 + i, (Integer) row.get(i));
                    else q.setString(4 + i, (String) row.get(i));
                }
                if (q.executeUpdate() != 1) fail(OPERATION_CONFLICT);
            }
            return true;
        }
        return false;
    }
    boolean empty(Connection c, ProtectionKey key, UUID operation) throws SQLException {
        for (Store store : STORES) if (first(c, key, operation, store) != null) return false;
        return true;
    }
    /** Unknown operations are never attributed to INITIAL or silently certified away. */
    boolean hasUnknownOperation(Connection c, ProtectionKey key, UUID initial, UUID current) throws SQLException {
        for (Store store : STORES) {
            String after = null;
            for (int seen = 0; seen < 3; seen++) {
                String found;
                try (var q = prepare(c, store.selectOperation(dialect, after != null))) {
                    pair(q, key); if (after != null) q.setString(3, after);
                    try (var r = q.executeQuery()) { found = r.next() ? r.getString(1) : null; }
                }
                if (found == null) break;
                UUID operation;
                try { operation = UUID.fromString(found); }
                catch (IllegalArgumentException bad) { fail(INVALID_PROGRESS); return true; }
                if (!operation.toString().equals(found)) fail(INVALID_PROGRESS);
                if (!operation.equals(initial) && !operation.equals(current)) return true;
                after = found; // Keyset order is the database's, not a Java collation assumption.
            }
        }
        return false;
    }
    private List<Object> first(Connection c, ProtectionKey key, UUID operation, Store store) throws SQLException {
        try (var q = prepare(c, store.selectFirst(dialect))) {
            bind(q, key, operation);
            try (var r = q.executeQuery()) {
                if (!r.next()) return null;
                var values = new ArrayList<Object>(store.columns().size());
                for (int i = 0; i < store.columns().size(); i++) {
                    if (store.columns().get(i).width() == 0) {
                        int page = r.getInt(i + 1);
                        if (r.wasNull() || page < 0 || page >= InitialSourceHotWindow.MAX_PAGES) fail(INVALID_PROGRESS);
                        values.add(page);
                    } else {
                        String value = r.getString(i + 1);
                        if (value == null || value.isEmpty() || value.length() > Math.abs(store.columns().get(i).width())
                                || !value.matches("[A-Za-z0-9._-]+")) fail(INVALID_PROGRESS);
                        values.add(value);
                    }
                }
                return values;
            }
        }
    }
    private PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var q = c.prepareStatement(sql);
        try { q.setQueryTimeout(timeout); return q; }
        catch (SQLException failure) { q.close(); throw failure; }
    }
    private static void pair(PreparedStatement q, ProtectionKey key) throws SQLException {
        q.setString(1, key.scopeToken()); q.setString(2, key.tenantToken());
    }
    private static void bind(PreparedStatement q, ProtectionKey key, UUID operation) throws SQLException {
        pair(q, key); q.setString(3, operation.toString());
    }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}
