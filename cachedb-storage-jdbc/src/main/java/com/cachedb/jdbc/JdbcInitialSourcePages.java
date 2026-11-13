package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Real indexed SQL pages on the guard-owned connection; generated bound-codec decoding only. */
final class JdbcInitialSourcePages<T, ID> {
    private record Column(String name, int type, int size) {}
    private final InitialSourceHotWindow<T, ID> window;
    private final JdbcSourceOwnerOnboarding.JdbcIdCodec<ID> ids;
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout;
    private final JdbcSourceOwnerOnboarding<ID> inspector;
    private List<Column> columns = List.of();
    private int idType, rawRowBytes;

    JdbcInitialSourcePages(JdbcTenantPurgeGuard guard, InitialSourceHotWindow<T, ID> window,
            JdbcSourceOwnerOnboarding.JdbcIdCodec<ID> ids, int timeout) {
        this.window = window; this.ids = ids; dialect = guard.ownerBootstrapDialect(); this.timeout = timeout;
        if (window.metadata().deletedColumn() != null)
            new TenantPurgeProtocol.SourcePlan(window.metadata().tableName(),window.metadata().deletedColumn(),window.metadata().idColumn());
        var plan = new SourceOwnerOnboarding.Plan(window.binding(), window.metadata().versionColumn(), window.idFormatToken());
        inspector = new JdbcSourceOwnerOnboarding<>(guard, plan, ids, timeout, Set.of(plan.fingerprint()));
    }
    void validate(Connection c) throws SQLException {
        idType = inspector.validateInitialSource(c);
        if (!ids.formatToken().equals(JdbcSourceOwnerOnboarding.longIds().formatToken())) fail(MIGRATION_REQUIRED);
        var names = new LinkedHashSet<>(window.metadata().columns());
        names.add(window.metadata().idColumn()); names.add(window.metadata().tenantFieldName());
        names.add(window.metadata().versionColumn());
        if (window.metadata().deletedColumn() != null) names.add(window.metadata().deletedColumn());
        var byName = new HashMap<String, Column>();
        var meta = c.getMetaData(); var source = window.binding().source();
        String[] parts = source.table().split("\\.", -1);
        String table = parts[parts.length - 1], schema = parts.length == 2 ? parts[0] : c.getSchema();
        if (meta.storesUpperCaseIdentifiers()) {
            table = table.toUpperCase(Locale.ROOT); if (schema != null) schema = schema.toUpperCase(Locale.ROOT);
        } else if (meta.storesLowerCaseIdentifiers()) {
            table = table.toLowerCase(Locale.ROOT); if (schema != null) schema = schema.toLowerCase(Locale.ROOT);
        }
        boolean strictVersion = false;
        try (var r = meta.getColumns(c.getCatalog(), schema, table, null)) {
            int n = 0;
            while (r.next()) {
                if (++n > 4096) fail(INVALID_REQUEST);
                String name = r.getString("COLUMN_NAME");
                int type = r.getInt("DATA_TYPE"), size = r.getInt("COLUMN_SIZE"), scale = r.getInt("DECIMAL_DIGITS");
                if (name.equalsIgnoreCase(window.metadata().versionColumn())) {
                    strictVersion = r.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls
                            && (type == Types.BIGINT || type == Types.INTEGER || type == Types.SMALLINT
                            || (type == Types.DECIMAL || type == Types.NUMERIC) && scale == 0 && size > 0 && size <= 19);
                }
                byName.put(name.toLowerCase(Locale.ROOT), new Column(name, type, size));
            }
        }
        if (!strictVersion) fail(MIGRATION_REQUIRED);
        var selected = new ArrayList<Column>(); long raw = 0;
        for (String name : names) {
            Column col = byName.get(name.toLowerCase(Locale.ROOT));
            if (col == null) fail(INVALID_REQUEST);
            long bytes = switch (col.type()) {
                case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> {
                    if (col.size() < 1 || col.size() > window.rowBytes() / 4) fail(INVALID_REQUEST);
                    yield 4L * col.size();
                }
                case Types.BINARY, Types.VARBINARY -> {
                    if (col.size() < 1 || col.size() > window.rowBytes()) fail(INVALID_REQUEST);
                    yield col.size();
                }
                case Types.NUMERIC, Types.DECIMAL -> {
                    if (col.size() < 1 || col.size() > 38) fail(INVALID_REQUEST); yield 128;
                }
                case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIT, Types.BOOLEAN,
                        Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DATE, Types.TIME, Types.TIMESTAMP,
                        Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> 128;
                default -> throw new ProtectionException(UNWIRED_PROTOCOL); // No unbounded LOB/MAX-value materialization.
            };
            raw = Math.addExact(raw, bytes + 64);
            selected.add(new Column(name, col.type(), col.size()));
        }
        if (raw > window.rowBytes() || raw > window.pageBytes()) fail(INVALID_REQUEST);
        columns = List.copyOf(selected); rawRowBytes = (int) Math.max(1, raw);
        requireLeadingIndexes(c, schema, table);
    }
    private void requireLeadingIndexes(Connection c, String schema, String table) throws SQLException {
        var indexes = new HashMap<String, Map<Integer, String>>(); var filtered = new HashSet<String>(); int n = 0;
        try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), schema, table, false, false)) {
            while (r.next()) {
                if (++n > 4096) fail(INDEX_REQUIRED);
                String name = r.getString("INDEX_NAME"), col = r.getString("COLUMN_NAME");
                if (name != null && r.getString("FILTER_CONDITION") != null) { filtered.add(name); continue; }
                int ordinal = r.getInt("ORDINAL_POSITION");
                if (name != null && col != null && ordinal > 0 && ordinal <= 2)
                    indexes.computeIfAbsent(name, k -> new HashMap<>()).put(ordinal, col);
            }
        }
        filtered.forEach(indexes::remove);
        for (String second : List.of(window.metadata().idColumn(), window.metadata().versionColumn())) {
            if (indexes.values().stream().noneMatch(x -> window.metadata().tenantFieldName().equalsIgnoreCase(x.get(1))
                    && second.equalsIgnoreCase(x.get(2)))) fail(INDEX_REQUIRED);
        }
    }

    /** Exactly two leading-index endpoint probes cover ALL cold/soft-deleted rows for this exact tenant. */
    long[] sourceExtremes(Connection c, Session session) throws SQLException {
        long min = 0, max = 0, probes = 0;
        for (boolean descending : List.of(false, true)) {
            String sql = limited(q(window.metadata().tenantFieldName()) + "," + q(window.metadata().versionColumn()),
                    dialect.tenantPredicate(window.binding().source()),
                    q(window.metadata().versionColumn()) + (descending ? " DESC" : " ASC"), 1);
            try (var s = prepare(c, sql)) {
                dialect.bindTenant(s, session.request().tenant(), 1);
                try (var rows = s.executeQuery()) {
                    if (rows.next()) {
                        if (!session.request().tenant().equals(rows.getString(1))) fail(MIGRATION_REQUIRED);
                        long value = strictVersion(rows.getBigDecimal(2)); probes++;
                        if (descending) max = value; else min = value;
                    }
                }
            }
        }
        if (probes != 0 && probes != 2 || probes > 0 && min > max) fail(MIGRATION_REQUIRED);
        return new long[]{max, probes};
    }

    Page<T, ID> capture(Connection c, Session session, String cursor, long warmed) throws SQLException {
        int remaining = Math.toIntExact(window.maxRows() - warmed);
        if (remaining < 1) fail(INVALID_PROGRESS);
        int limit = Math.min(128, Math.min(remaining, Math.max(1, window.pageBytes() / rawRowBytes)));
        String where = dialect.tenantPredicate(window.binding().source()) + " AND " + q(window.metadata().idColumn())
                + ">=? AND " + q(window.metadata().idColumn()) + "<=?"
                + (cursor == null ? "" : " AND " + q(window.metadata().idColumn()) + ">?") + activeSql();
        var entities = new ArrayList<T>(); var versions = new ArrayList<Long>();
        try (var s = prepare(c, limited(selectList(), where, q(window.metadata().idColumn()) + " ASC", limit))) {
            dialect.bindTenant(s, session.request().tenant(), 1);
            int p = dialect.tenantParameterCount() + 1;
            ids.bind(s, p++, window.ids().decode(window.firstCanonical()), idType);
            ids.bind(s, p++, window.ids().decode(window.lastCanonical()), idType);
            if (cursor != null) ids.bind(s, p++, window.ids().decode(cursor), idType);
            bindActive(s, p); s.setFetchSize(limit); s.setMaxRows(limit);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    if (entities.size() == limit) fail(INVALID_PROGRESS);
                    var values = values(rows);
                    T entity = decode(values, session);
                    entities.add(entity); versions.add(strictVersion(number(values.get(window.metadata().versionColumn()))));
                }
            }
        }
        var refs = new LinkedHashMap<ID, Long>();
        for (int i = 0; i < entities.size(); i++) {
            ID id = window.metadata().idAccessor().apply(entities.get(i));
            if (refs.put(id, versions.get(i)) != null) fail(MIGRATION_REQUIRED);
        }
        JdbcWriteFence.requireInitialRows(c, JdbcWriteFence.Provider.valueOf(dialect.name()),
                window.binding().source().table(), refs, timeout);
        var captured = new ArrayList<Row<T, ID>>(); int bytes = 32;
        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i); ID id = window.metadata().idAccessor().apply(entity);
            String canonical = window.canonical(id), payload = window.codec().toRedisValue(entity);
            int size = Math.toIntExact(com.reactor.cachedb.core.codec.Utf8Length.of(payload));
            if (size < 1 || size > window.rowBytes()) fail(INVALID_REQUEST);
            bytes = Math.addExact(bytes, size + Math.toIntExact(com.reactor.cachedb.core.codec.Utf8Length.of(canonical)) + 84);
            if (bytes > window.pageBytes()) fail(INVALID_REQUEST);
            String token = InitialBootstrapProof.row(window, session, canonical, versions.get(i), payload);
            captured.add(new Row<>(id, entity, new RowRef(canonical, versions.get(i), token), payload, size));
        }
        return new Page<>(UUID.randomUUID(), captured, captured.size() < limit || captured.size() == remaining);
    }

    /** Reuse captured ciphertext/nonces; compare fresh source through generated logical column access. */
    void revalidate(Connection c, Session session, Page<T, ID> saved) throws SQLException {
        if (saved.rows().isEmpty()) return;
        var wanted = new LinkedHashMap<String, Row<T, ID>>();
        for (var row : saved.rows()) {
            if (!row.proof().digest().equals(InitialBootstrapProof.row(window, session,
                    row.proof().canonicalId(), row.proof().sourceVersion(), row.redisPayload()))) fail(MIGRATION_REQUIRED);
            if (wanted.put(row.proof().canonicalId(), row) != null) fail(INVALID_PROGRESS);
        }
        String where = dialect.tenantPredicate(window.binding().source()) + " AND " + q(window.metadata().idColumn())
                + " IN (" + String.join(",", Collections.nCopies(wanted.size(), "?")) + ")" + activeSql();
        var found = new HashSet<String>(); var fence = new LinkedHashMap<ID, Long>();
        try (var s = prepare(c, limited(selectList(), where, q(window.metadata().idColumn()), saved.rows().size() + 1))) {
            dialect.bindTenant(s, session.request().tenant(), 1);
            int p = dialect.tenantParameterCount() + 1;
            for (var row : wanted.values()) ids.bind(s, p++, row.id(), idType);
            bindActive(s, p); s.setFetchSize(Math.min(128, wanted.size())); s.setMaxRows(wanted.size() + 1);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    var values = values(rows); T fresh = decode(values, session);
                    ID id = window.metadata().idAccessor().apply(fresh); String canonical = window.canonical(id);
                    Row<T, ID> expected = wanted.get(canonical);
                    long version = strictVersion(number(values.get(window.metadata().versionColumn())));
                    if (expected == null || !found.add(canonical) || version != expected.proof().sourceVersion()) fail(MIGRATION_REQUIRED);
                    T captured = window.codec().fromRedisValue(expected.redisPayload());
                    InitialBootstrapProof.sameLogicalSource(window, fresh, captured);
                    fence.put(id, version);
                }
            }
        }
        if (found.size() != wanted.size()) fail(MIGRATION_REQUIRED);
        JdbcWriteFence.requireInitialRows(c, JdbcWriteFence.Provider.valueOf(dialect.name()),
                window.binding().source().table(), fence, timeout);
    }
    private T decode(Map<String, Object> values, Session session) {
        if (!session.request().tenant().equals(values.get(window.metadata().tenantFieldName()))) fail(MIGRATION_REQUIRED);
        T entity = window.codec().fromColumns(values);
        if (!session.request().tenant().equals(window.metadata().extractTenantId(entity))) fail(MIGRATION_REQUIRED);
        String canonical = window.canonical(window.metadata().idAccessor().apply(entity));
        Object rawId = values.get(window.metadata().idColumn());
        ID exact;
        try {
            if (!(window.metadata().idAccessor().apply(entity) instanceof Long)) fail(MIGRATION_REQUIRED);
            exact = window.ids().decode(Long.toString(number(rawId).longValueExact()));
        } catch (ArithmeticException invalid) { throw new ProtectionException(MIGRATION_REQUIRED); }
        if (!canonical.equals(window.canonical(exact))) fail(MIGRATION_REQUIRED);
        return entity;
    }
    private LinkedHashMap<String, Object> values(ResultSet rows) throws SQLException {
        var result = new LinkedHashMap<String, Object>(); int p = 1;
        for (var col : columns) {
            Object value = switch (col.type()) {
                case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> boundedText(rows, p, col.size());
                case Types.NUMERIC, Types.DECIMAL -> rows.getBigDecimal(p);
                case Types.BINARY, Types.VARBINARY -> boundedBinary(rows, p, col.size());
                default -> rows.getObject(p);
            };
            result.put(col.name(), value); p++;
        }
        return result;
    }
    private static byte[] boundedBinary(ResultSet rows, int column, int maxBytes) throws SQLException {
        try (var in = rows.getBinaryStream(column)) {
            if (in == null) return null;
            byte[] value = in.readNBytes(maxBytes + 1);
            if (value.length > maxBytes) fail(INVALID_REQUEST);
            return value;
        } catch (IOException malformed) { throw new ProtectionException(INVALID_REQUEST); }
    }
    private static String boundedText(ResultSet rows, int column, int maxChars) throws SQLException {
        try (var in = rows.getCharacterStream(column)) {
            if (in == null) return null;
            var out = new StringBuilder(Math.min(maxChars, 256)); var buffer = new char[Math.min(maxChars + 1, 4096)];
            for (int n; (n = in.read(buffer)) >= 0;) {
                if (n == 0 || out.length() > maxChars - n) fail(INVALID_REQUEST);
                out.append(buffer, 0, n);
            }
            return out.toString();
        } catch (IOException malformed) { throw new ProtectionException(INVALID_REQUEST); }
    }
    private String selectList() { return columns.stream().map(x -> q(x.name())).collect(java.util.stream.Collectors.joining(",")); }
    private String activeSql() {
        return window.metadata().deletedColumn() == null ? "" : " AND " + q(window.metadata().deletedColumn()) + "=?";
    }
    private void bindActive(PreparedStatement s, int parameter) throws SQLException {
        if (window.metadata().deletedColumn() == null) return;
        Column col = columns.stream().filter(x -> x.name().equals(window.metadata().deletedColumn())).findFirst().orElseThrow();
        String marker = window.metadata().activeMarkerValue();
        switch (col.type()) {
            case Types.BIT, Types.BOOLEAN -> {
                if (!"true".equals(marker) && !"false".equals(marker)) fail(INVALID_REQUEST);
                s.setBoolean(parameter, Boolean.parseBoolean(marker));
            }
            case Types.NUMERIC, Types.DECIMAL, Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT ->
                    s.setBigDecimal(parameter, new BigDecimal(marker));
            case Types.NVARCHAR, Types.NCHAR -> s.setNString(parameter, marker);
            case Types.VARCHAR, Types.CHAR -> s.setObject(parameter, marker, Types.VARCHAR);
            default -> throw new ProtectionException(UNWIRED_PROTOCOL);
        }
    }
    private String q(String value) { return JdbcSourceOwnerSql.quote(dialect, value); }
    private String limited(String cols, String where, String order, int n) {
        return "SELECT " + (dialect == JdbcTenantPurgeDialect.MSSQL ? "TOP (" + n + ") " : "") + cols
                + " FROM " + q(window.metadata().tableName()) + " WHERE " + where + " ORDER BY " + order
                + (dialect == JdbcTenantPurgeDialect.POSTGRES ? " LIMIT " + n
                : dialect == JdbcTenantPurgeDialect.ORACLE ? " FETCH FIRST " + n + " ROWS ONLY" : "");
    }
    private PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql); try { s.setQueryTimeout(timeout); return s; }
        catch (SQLException e) { s.close(); throw e; }
    }
    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Long n) return BigDecimal.valueOf(n);
        if (value instanceof Integer n) return BigDecimal.valueOf(n);
        if (value instanceof Short n) return BigDecimal.valueOf(n);
        throw new ProtectionException(MIGRATION_REQUIRED);
    }
    private static long strictVersion(BigDecimal value) {
        if (value == null) fail(MIGRATION_REQUIRED);
        try { long n = value.longValueExact(); InitialTenantBootstrap.positiveVersion(n); return n; }
        catch (ArithmeticException invalid) { throw new ProtectionException(MIGRATION_REQUIRED); }
    }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}
