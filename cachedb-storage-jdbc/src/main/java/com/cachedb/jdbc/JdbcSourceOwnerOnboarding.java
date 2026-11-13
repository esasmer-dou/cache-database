package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/**
 * Administrative, one-row-per-pair ownership journal and indexed batched source reader.
 * Uses the SAME durable tenant guard, DataSource, transaction and lease epoch as purge/flushing.
 * Does not enumerate SQL history, write source rows, reserve foreground identities, or open admission.
 */
public final class JdbcSourceOwnerOnboarding<I> implements SourceOwnerOnboarding.Store<I> {
    public interface JdbcIdCodec<I> extends IdCodec<I> {
        String formatToken();
        Set<Integer> jdbcTypes();
        void bind(PreparedStatement statement, int parameter, I id, int jdbcType) throws SQLException;
        /** Must bound input before decoding. Never load an unbounded LOB/string. */
        I read(ResultSet rows, int column) throws SQLException;
    }

    public static JdbcIdCodec<String> stringIds() {
        return new JdbcIdCodec<>() {
            public String formatToken() { return ProtectionKey.hash("cachedb-source-owner:string-v1"); }
            public String encode(String id) { return SourceOwnerOnboarding.canonicalId(id); }
            public String decode(String text) { return SourceOwnerOnboarding.canonicalId(text); }
            public Set<Integer> jdbcTypes() { return Set.of(Types.VARCHAR, Types.NVARCHAR); }
            public void bind(PreparedStatement q, int parameter, String id, int jdbcType) throws SQLException {
                if (jdbcType == Types.NVARCHAR) q.setNString(parameter, id);
                else q.setObject(parameter, id, Types.VARCHAR);
            }
            public String read(ResultSet rows, int column) throws SQLException {
                try (var reader = rows.getCharacterStream(column)) {
                    if (reader == null) throw new ProtectionException(INCOMPLETE_INVENTORY);
                    char[] buffer = new char[1025]; int size = 0;
                    while (size < buffer.length) {
                        int count = reader.read(buffer, size, buffer.length - size);
                        if (count < 0) break;
                        if (count == 0) throw new ProtectionException(INCOMPLETE_INVENTORY);
                        size += count;
                    }
                    if (size > 1024) throw new ProtectionException(INCOMPLETE_INVENTORY);
                    return SourceOwnerOnboarding.canonicalId(new String(buffer, 0, size));
                } catch (IOException malformed) { throw new ProtectionException(INCOMPLETE_INVENTORY); }
            }
        };
    }

    public static JdbcIdCodec<Long> longIds() {
        return new JdbcIdCodec<>() {
            public String formatToken() { return ProtectionKey.hash("cachedb-source-owner:long-v1"); }
            public String encode(Long id) { return Objects.requireNonNull(id).toString(); }
            public Long decode(String text) {
                SourceOwnerOnboarding.canonicalId(text);
                try {
                    long value = Long.parseLong(text);
                    if (!Long.toString(value).equals(text)) throw new NumberFormatException();
                    return value;
                } catch (NumberFormatException invalid) { throw new ProtectionException(INVALID_REQUEST); }
            }
            public Set<Integer> jdbcTypes() {
                return Set.of(Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.NUMERIC, Types.DECIMAL);
            }
            public void bind(PreparedStatement q, int parameter, Long id, int jdbcType) throws SQLException { q.setLong(parameter, id); }
            public Long read(ResultSet rows, int column) throws SQLException {
                // Driver getLong() may truncate fractions or overflow NUMERIC/DECIMAL values.
                BigDecimal value = rows.getBigDecimal(column);
                if (value == null) throw new ProtectionException(INCOMPLETE_INVENTORY);
                try { return value.longValueExact(); }
                catch (ArithmeticException invalid) { throw new ProtectionException(INCOMPLETE_INVENTORY); }
            }
        };
    }

    private final JdbcTenantPurgeGuard guard;
    private final JdbcTenantPurgeDialect dialect;
    private final Plan plan;
    private final JdbcIdCodec<I> ids;
    private final int timeout;
    private volatile int idJdbcType;

    public JdbcSourceOwnerOnboarding(JdbcTenantPurgeGuard guard, Plan plan, JdbcIdCodec<I> ids,
                                    int timeoutSeconds, Set<String> reviewedPlans) {
        this.guard = Objects.requireNonNull(guard); this.dialect = guard.ownerBootstrapDialect();
        this.plan = Objects.requireNonNull(plan); this.ids = Objects.requireNonNull(ids);
        if (timeoutSeconds < 1 || timeoutSeconds > 5 || reviewedPlans == null || reviewedPlans.size() > 64)
            throw new ProtectionException(INVALID_REQUEST);
        reviewedPlans.forEach(ProtectionKey::token);
        if (ids.jdbcTypes() == null || ids.jdbcTypes().isEmpty() || ids.jdbcTypes().size() > 8)
            throw new ProtectionException(INVALID_REQUEST);
        if (!reviewedPlans.contains(plan.fingerprint()) || !plan.idFormatToken().equals(ids.formatToken()))
            throw new ProtectionException(UNWIRED_PROTOCOL);
        timeout = timeoutSeconds;
    }

    @Override public Session acquire(Request request) {
        admin();
        if (!request.plan().equals(plan)) throw new ProtectionException(OPERATION_CONFLICT);
        // Reuses the reviewed leading tenant index, provider encoding and NVARCHAR/VARCHAR validation.
        guard.validateSource(plan.binding());
        var lease = guard.acquireOwnerBootstrap(request.generation(), plan.binding(), request.operationId(),
                request.leaseSeconds(), c -> {
                    validateIdentityIndexAndVersion(c);
                    Checkpoint current = read(c, request);
                    if (current == null) write(c, request, Checkpoint.initial(request), true);
                    return null;
                });
        return new Session(request, lease);
    }

    @Override public void renew(Session session) {
        // Unlike purge progress, this journal has an independent purpose, but owns the real guard lease.
        guard.inOwnerBootstrapTransaction(session.lease(), session.request().generation(), c -> {
            readRequired(c, session.request()); return null;
        });
        guard.renew(session.lease());
    }
    @Override public Checkpoint load(Session session) {
        return guard.inOwnerBootstrapTransaction(session.lease(), session.request().generation(),
                c -> readRequired(c, session.request()));
    }
    @Override public Checkpoint save(Session session, Checkpoint before, Checkpoint after) {
        return guard.inOwnerBootstrapTransaction(session.lease(), session.request().generation(), c -> {
            Checkpoint current = readRequired(c, session.request());
            if (!current.equals(before)) throw new ProtectionException(OPERATION_CONFLICT);
            transition(before, after);
            if (after.examined() > plan.binding().maxWorkItems()) throw new ProtectionException(INCOMPLETE_INVENTORY);
            if (after.pending() != null) {
                int bytes = 0;
                for (String value : after.pending().ids()) {
                    checkedId(value);
                    bytes = Math.addExact(bytes, value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                }
                if (bytes > plan.binding().maxBatchBytes()
                        || Math.addExact(after.examined(), after.pending().ids().size()) > plan.binding().maxWorkItems())
                    throw new ProtectionException(INCOMPLETE_INVENTORY);
            }
            write(c, session.request(), after, false);
            return after;
        });
    }

    @Override public List<OwnerProof<I>> resolve(Session session, Checkpoint pending) {
        return guard.inOwnerBootstrapTransaction(session.lease(), session.request().generation(), c -> {
            Checkpoint current = readRequired(c, session.request());
            if (!current.equals(pending) || current.state() != State.RUNNING || current.pending() == null)
                throw new ProtectionException(INVALID_PROGRESS);
            var canonical = current.pending().ids();
            if (canonical.isEmpty()) return List.of();
            var input = new ArrayList<I>(canonical.size());
            var positions = new HashMap<String, Integer>();
            for (int i = 0; i < canonical.size(); i++) {
                input.add(checkedId(canonical.get(i))); positions.put(canonical.get(i), i);
            }
            var versions = new long[canonical.size()];
            var matches = new int[canonical.size()];
            var exact = new boolean[canonical.size()];
            try (var q = prepare(c, JdbcSourceOwnerSql.read(dialect, plan, canonical.size()))) {
                dialect.bindTenant(q, session.request().tenant(), 1);
                int parameter = dialect.tenantParameterCount() + 1;
                for (I id : input) ids.bind(q, parameter++, id, idJdbcType);
                q.setMaxRows(129); q.setFetchSize(129);
                try (var rows = q.executeQuery()) {
                    int count = 0;
                    while (rows.next()) {
                        if (++count > 128) throw new ProtectionException(INCOMPLETE_INVENTORY);
                        I actualId = ids.read(rows, 1);
                        String actualCanonical = SourceOwnerOnboarding.canonicalId(ids.encode(actualId));
                        Integer index = positions.get(actualCanonical);
                        // SQL collation may match an ID spelling not present in the requested canonical page.
                        if (index == null) continue;
                        String actualTenant = readTenant(rows, 2);
                        BigDecimal rawVersion = rows.getBigDecimal(3);
                        long version = -1;
                        if (rawVersion != null) {
                            try { version = rawVersion.longValueExact(); }
                            catch (ArithmeticException invalid) { /* Unknown proof; never round a version. */ }
                        }
                        matches[index]++;
                        exact[index] = exactTenantAndId(session.request().tenant(), actualTenant,
                                canonical.get(index), actualCanonical)
                                && version >= 0 && version <= SourceOwnerOnboarding.MAX_VERSION;
                        versions[index] = exact[index] ? version : 0;
                    }
                }
            }
            var proofs = new ArrayList<OwnerProof<I>>(canonical.size());
            for (int i = 0; i < canonical.size(); i++) {
                boolean owned = matches[i] == 1 && exact[i];
                proofs.add(new OwnerProof<>(input.get(i), canonical.get(i),
                        owned ? Ownership.SOURCE_OWNED : Ownership.UNKNOWN, owned ? versions[i] : 0));
            }
            return List.copyOf(proofs);
        });
    }

    @Override public void release(Session session) { guard.release(session.lease()); }

    /** Defense in depth even though SQL uses the existing exact-byte tenant predicate. */
    static boolean exactTenantAndId(String expectedTenant, String actualTenant, String expectedId, String actualId) {
        return expectedTenant.equals(actualTenant) && expectedId.equals(actualId);
    }
    private I checkedId(String canonical) {
        SourceOwnerOnboarding.canonicalId(canonical);
        I value = ids.decode(canonical);
        if (value == null || !canonical.equals(ids.encode(value))) throw new ProtectionException(INVALID_REQUEST);
        return value;
    }
    private static String readTenant(ResultSet rows, int column) throws SQLException {
        try (var reader = rows.getCharacterStream(column)) {
            if (reader == null) return null;
            char[] data = new char[257]; int size = 0;
            while (size < data.length) {
                int count = reader.read(data, size, data.length - size);
                if (count < 0) break;
                if (count == 0) return null;
                size += count;
            }
            return size > 256 ? null : new String(data, 0, size);
        } catch (IOException malformed) { throw new ProtectionException(INCOMPLETE_INVENTORY); }
    }

    int validateInitialSource(Connection c) throws SQLException {
        validateIdentityIndexAndVersion(c); return idJdbcType;
    }

    private void validateIdentityIndexAndVersion(Connection c) throws SQLException {
        var meta = c.getMetaData(); var source = plan.binding().source();
        String[] parts = source.table().split("\\.", -1);
        String table = parts[parts.length - 1], schema = parts.length == 2 ? parts[0] : c.getSchema();
        if (meta.storesUpperCaseIdentifiers()) {
            table = table.toUpperCase(Locale.ROOT); if (schema != null) schema = schema.toUpperCase(Locale.ROOT);
        } else if (meta.storesLowerCaseIdentifiers()) {
            table = table.toLowerCase(Locale.ROOT); if (schema != null) schema = schema.toLowerCase(Locale.ROOT);
        }
        boolean version = false, id = false;
        int identityType = 0;
        try (var columns = meta.getColumns(c.getCatalog(), schema, table, null)) {
            int count = 0;
            while (columns.next()) {
                if (++count > 4096) throw new ProtectionException(INVALID_REQUEST);
                int type = columns.getInt("DATA_TYPE"), precision = columns.getInt("COLUMN_SIZE");
                if (source.idColumn().equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    identityType = type;
                    id = ids.jdbcTypes().contains(type) && columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls
                            && (type == Types.BIGINT || type == Types.INTEGER || type == Types.SMALLINT
                            || (type == Types.NUMERIC || type == Types.DECIMAL) && precision > 0 && precision <= 19
                            && columns.getInt("DECIMAL_DIGITS") == 0
                            || (type == Types.VARCHAR || type == Types.NVARCHAR) && precision > 0 && precision <= 1024);
                }
                if (!plan.versionColumn().equalsIgnoreCase(columns.getString("COLUMN_NAME"))) continue;
                version = (type == Types.BIGINT || type == Types.INTEGER || type == Types.SMALLINT
                        || (type == Types.NUMERIC || type == Types.DECIMAL) && precision > 0 && precision <= 19
                        && columns.getInt("DECIMAL_DIGITS") == 0);
            }
        }
        if (!id || !version) throw new ProtectionException(INVALID_REQUEST);
        // The native Redis identity is namespace-wide. A tenant-only/composite unique index is insufficient.
        var indexes = new HashMap<String, List<String>>();
        try (var rows = meta.getIndexInfo(c.getCatalog(), schema, table, true, false)) {
            int count = 0;
            while (rows.next()) {
                if (++count > 4096) throw new ProtectionException(INDEX_REQUIRED);
                String name = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME");
                if (name == null || rows.getBoolean("NON_UNIQUE") || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic)
                    continue;
                var columns = indexes.computeIfAbsent(name, ignored -> new ArrayList<>());
                columns.add(column == null ? "" : column);
                // A filtered unique index does not prove namespace-wide identity uniqueness.
                String filter = rows.getString("FILTER_CONDITION");
                if (filter != null && !filter.isBlank()) columns.add("");
            }
        }
        if (indexes.values().stream().noneMatch(columns -> columns.size() == 1
                && columns.get(0).equalsIgnoreCase(source.idColumn()))) throw new ProtectionException(INDEX_REQUIRED);
        idJdbcType = identityType;
    }

    private Checkpoint readRequired(Connection c, Request request) throws SQLException {
        Checkpoint value = read(c, request);
        if (value == null) throw new ProtectionException(INVALID_PROGRESS);
        return value;
    }
    private Checkpoint read(Connection c, Request request) throws SQLException {
        try (var q = prepare(c, "SELECT payload,payload_bytes FROM cachedb_owner_onboarding WHERE "+InitialSqlTokens.pair(guard.ownerBootstrapDialect(),false)+"")) {
            key(q, 1, request);
            try (var rows = q.executeQuery()) {
                if (!rows.next()) return null;
                long length = rows.getLong(2);
                if (length < 1 || length > SourceOwnerCheckpointCodec.MAX_BYTES) throw new ProtectionException(INVALID_PROGRESS);
                byte[] payload;
                try (var stream = rows.getBinaryStream(1)) {
                    if (stream == null) throw new ProtectionException(INVALID_PROGRESS);
                    payload = stream.readNBytes((int) length + 1);
                } catch (IOException malformed) { throw new ProtectionException(INVALID_PROGRESS); }
                if (payload.length != length) throw new ProtectionException(INVALID_PROGRESS);
                Checkpoint value = SourceOwnerCheckpointCodec.decode(payload);
                if (!value.operationId().equals(request.operationId()) || value.generation() != request.generation().value()
                        || !value.planToken().equals(request.plan().fingerprint())
                        || !value.operatorToken().equals(request.operatorToken()))
                    throw new ProtectionException(OPERATION_CONFLICT);
                return value;
            }
        }
    }
    private void write(Connection c, Request request, Checkpoint value, boolean insert) throws SQLException {
        byte[] payload = SourceOwnerCheckpointCodec.encode(value);
        String sql = insert ? "INSERT INTO cachedb_owner_onboarding(payload,payload_bytes,scope_token,tenant_token) VALUES (?,?,"+InitialSqlTokens.varchar(guard.ownerBootstrapDialect(),64)+","+InitialSqlTokens.varchar(guard.ownerBootstrapDialect(),64)+")"
                : "UPDATE cachedb_owner_onboarding SET payload=?,payload_bytes=? WHERE "+InitialSqlTokens.pair(guard.ownerBootstrapDialect(),false)+"";
        try (var q = prepare(c, sql)) {
            q.setBytes(1, payload); q.setInt(2, payload.length); key(q, 3, request);
            if (q.executeUpdate() != 1) throw new ProtectionException(INVALID_PROGRESS);
        }
    }
    private static void transition(Checkpoint before, Checkpoint after) {
        Checkpoint expected;
        if (before.state() == State.FENCING) expected = before.fenced(after.inventoryToken());
        else if (before.state() == State.RUNNING && before.pending() != null) {
            long owned = after.owned() - before.owned(), absent = after.absent() - before.absent(),
                    unknown = after.unknown() - before.unknown();
            if (owned < 0 || owned > 128 || absent < 0 || absent > 128 || unknown < 0 || unknown > 128)
                throw new ProtectionException(INVALID_PROGRESS);
            expected = before.acknowledged(new Installed((int) owned, (int) absent, (int) unknown));
        } else if (before.state() == State.RUNNING && before.last())
            expected = after.state() == State.WARMED ? before.verified() : before.incomplete();
        else if (before.state() == State.RUNNING) expected = before.staged(after.pending());
        else throw new ProtectionException(INVALID_PROGRESS);
        if (!expected.equals(after)) throw new ProtectionException(INVALID_PROGRESS);
    }
    private PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var q = c.prepareStatement(sql);
        try { q.setQueryTimeout(timeout); return q; }
        catch (SQLException failure) { q.close(); throw failure; }
    }
    private static void key(PreparedStatement q, int index, Request request) throws SQLException {
        q.setString(index, request.generation().key().scopeToken()); q.setString(index + 1, request.generation().key().tenantToken());
    }
    private static void admin() {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ADMIN_REQUIRED);
    }
}
