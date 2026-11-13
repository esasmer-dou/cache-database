package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.*;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/**
 * Concrete INITIAL-purpose SQL orchestration. No SQL foreground call, automatic DDL,
 * owner ledger, normalization of source versions, fake purge progress or codec/provider rebinding.
 */
public final class JdbcInitialTenantBootstrap<T, ID> {
    private final JdbcTenantPurgeGuard guard;
    private final JdbcTenantVersionAllocator allocator;
    private final InitialSourceHotWindow<T, ID> window;
    private final JdbcInitialSourcePages<T, ID> source;
    private final int timeout;
    private final String manifest;

    public JdbcInitialTenantBootstrap(JdbcTenantPurgeGuard guard, JdbcTenantVersionAllocator allocator,
            InitialSourceHotWindow<T, ID> window, JdbcSourceOwnerOnboarding.JdbcIdCodec<ID> ids,
            String manifestToken, int timeoutSeconds, Set<String> reviewedWindows) {
        this.guard = Objects.requireNonNull(guard); this.allocator = Objects.requireNonNull(allocator);
        this.window = Objects.requireNonNull(window); manifest = ProtectionKey.token(manifestToken);
        if (!allocator.belongsTo(guard) || timeoutSeconds < 1 || timeoutSeconds > 5
                || reviewedWindows == null || reviewedWindows.size() > 64
                || !reviewedWindows.contains(window.fingerprint()) || !ids.formatToken().equals(window.idFormatToken()))
            fail(UNWIRED_PROTOCOL);
        reviewedWindows.forEach(ProtectionKey::token); timeout = timeoutSeconds;
        source = new JdbcInitialSourcePages<>(guard, window, ids, timeout);
    }
    public boolean belongsTo(JdbcTenantPurgeGuard expected) { return guard == expected; }
    public InitialSourceHotWindow<T, ID> window() { return window; }
    public Session acquire(Request request) {
        admin();
        if (!window.binding().key(request.tenant()).equals(request.generation().key())) fail(CONFIRMATION_REQUIRED);
        guard.validateSource(window.binding());
        var lease = guard.acquireInitialBootstrap(request.generation(), window.binding(), request.operationId(),
                request.leaseSeconds(), c -> {
                    source.validate(c);
                    InitialBootstrapState state = read(c, request);
                    if (state == null) {
                        state = InitialBootstrapState.initial(request, window.fingerprint(), manifest);
                        insert(c, request, state);
                    }
                    return state.phase;
                });
        return new Session(request, lease, window.fingerprint(), manifest);
    }
    public void renew(Session session) {
        guard.inInitialBootstrapTransaction(session, c -> { readRequired(c, session); return null; });
        guard.renew(session.lease());
    }
    public void release(Session session) { guard.release(session.lease()); }
    public Progress progress(Session session) {
        return guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session);
            long high = s.runId == null ? 0 : allocator.initialStatusIn(c, session).highwater();
            return s.progress(high);
        });
    }
    /** Actual store-backed reader. Pending pages are never exposed as acknowledged. */
    public AcknowledgedPages<T, ID> acknowledgedPages() {
        return new AcknowledgedPages<>() {
            public String planToken() { return window.fingerprint(); }
            public String manifestToken() { return manifest; }
            public AcknowledgedPage<T, ID> read(Session session, int pageCursor, int maxBytes) {
                if (pageCursor < 0 || pageCursor >= InitialSourceHotWindow.MAX_PAGES
                        || maxBytes < 1 || maxBytes > window.pageBytes()) fail(INVALID_REQUEST);
                return guard.inInitialBootstrapTransaction(session, c -> {
                    var s = readRequired(c, session);
                    if (s.phase != Phase.VERIFY_WINDOW && s.phase != Phase.READY && s.phase != Phase.SQL_OPEN
                            || s.finalizing || pageCursor >= s.pages || !applied(c, session, pageCursor)) fail(INVALID_PROGRESS);
                    int size; boolean verified;
                    try (var q = prepare(c, "SELECT payload_bytes,is_verified FROM cachedb_initial_page"
                            + " WHERE "+pairWhere()+" AND operation_id="+ascii(36)+" AND page_no=?")) {
                        pageKey(q, session, pageCursor);
                        try (var r = q.executeQuery()) {
                            if (!r.next()) fail(INVALID_PROGRESS);
                            size = r.getInt(1); int flag = r.getInt(2);
                            if (size < 1 || size > maxBytes || flag != 0 && flag != 1) fail(INVALID_PROGRESS);
                            verified = flag == 1;
                        }
                    }
                    var captured = page(c, session, pageCursor);
                    source.revalidate(c, session, captured);
                    return new AcknowledgedPage<>(pageCursor, pageCursor + 1 == s.pages ? -1 : pageCursor + 1,
                            captured, verified, size, s.window());
                });
            }
        };
    }
    public Phase phase(Session session) { return guard.inInitialBootstrapTransaction(session, c -> readRequired(c, session).phase); }
    /** Cleanup has physically begun: a CLOSED native runtime now requires explicit recovery. */
    public boolean finalizing(Session session) {
        return guard.inInitialBootstrapTransaction(session, c -> readRequired(c, session).finalizing);
    }
    public WindowProof windowProof(Session session) {
        return guard.inInitialBootstrapTransaction(session, c -> readRequired(c, session).window());
    }
    public void closed(Session session, Closure closure) {
        allocator.beginInitialInventory(session, window.binding());
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.CLOSURE);
            bindClosure(s, closure); s.phase = Phase.NATIVE_INVENTORY; save(c, session, s); return null;
        });
    }
    public void checkClosure(Session session, Closure closure) {
        guard.inInitialBootstrapTransaction(session, c -> { var s = readRequired(c, session); bindClosure(s, closure); return null; });
    }
    /** Checks the ACTUAL durable raw-frame journals; no SPI-provided complete/max Boolean. */
    public void nativeInventoryComplete(Session session) {
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.NATIVE_INVENTORY);
            int total = 0, owners = 0, namespaces = 0;
            try (var q = prepare(c, "SELECT scan_kind,is_complete,is_failed,pending_id,consumed_total,inventory_total"
                    + " FROM cachedb_purge_scan WHERE "+InitialSqlTokens.pair(guard.ownerBootstrapDialect(), true)+" AND operation_id="+ascii(36)+"")) {
                key(q, 1, session); q.setString(3, session.request().operationId().toString()); q.setFetchSize(128);
                try (var r = q.executeQuery()) {
                    while (r.next()) {
                        if (++total > 10000 || r.getInt(2) != 1 || r.getInt(3) != 0 || r.getString(4) != null
                                || r.getLong(5) != r.getLong(6)) fail(INCOMPLETE_INVENTORY);
                        if ("OWNER".equals(r.getString(1))) owners++;
                        if ("NAMESPACE".equals(r.getString(1))) namespaces++;
                    }
                }
            }
            if (owners == 0 || namespaces == 0) fail(INCOMPLETE_INVENTORY);
            s.phase = Phase.DURABLE_VERSIONS; save(c, session, s); return null;
        });
    }
    public void scanDurableVersions(Session session) {
        var status = allocator.scanInitialDurableFences(session, 128);
        if (status.durableComplete()) guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.DURABLE_VERSIONS);
            s.phase = Phase.SOURCE_VERSIONS; save(c, session, s); return null;
        });
    }
    /** All-source exact MIN/MAX and allocator seal commit with phase progression in ONE transaction. */
    public void sourceInventory(Session session) {
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.SOURCE_VERSIONS);
            long[] facts = source.sourceExtremes(c, session);
            allocator.observeInitialSourceMaximum(c, session, facts[0]);
            var seal = allocator.sealInitialIn(c, session, window.binding(), window.metadata().versionColumn());
            s.inventory = seal.inventory(); s.probes = facts[1]; s.phase = Phase.HOT_WINDOW;
            save(c, session, s); return null;
        });
    }
    /** A complete bounded ciphertext page commits BEFORE the first owner/payload mutation. */
    public Page<T, ID> nextHotPage(Session session) {
        return guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.HOT_WINDOW);
            if (s.pages > 0 && !applied(c, session, s.pages - 1)) {
                var pending = page(c, session, s.pages - 1); source.revalidate(c, session, pending); return pending;
            }
            var next = source.capture(c, session, s.cursor, s.warmed);
            byte[] encoded = InitialBootstrapPageCodec.encode(window, next);
            if (s.pages >= InitialSourceHotWindow.MAX_PAGES || s.residentBytes > window.residentBytes() - encoded.length) fail(INCOMPLETE_INVENTORY);
            try (var q = prepare(c, "INSERT INTO cachedb_initial_page(scope_token,tenant_token,operation_id,page_no,"
                    + "payload,payload_bytes,is_applied,is_verified) VALUES ("+ascii(64)+","+ascii(64)+","+ascii(36)+",?,?,?,0,0)")) {
                key(q, 1, session); q.setString(3, session.request().operationId().toString()); q.setInt(4, s.pages);
                q.setBytes(5, encoded); q.setInt(6, encoded.length); one(q.executeUpdate());
            }
            s.pages++; s.residentBytes += encoded.length; save(c, session, s); return next;
        });
    }
    public void acknowledgeHotPage(Session session, Page<T, ID> page) {
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.HOT_WINDOW);
            var saved = page(c, session, s.pages - 1);
            if (!saved.id().equals(page.id()) || applied(c, session, s.pages - 1)) fail(OPERATION_CONFLICT);
            updatePage(c, session, s.pages - 1, "is_applied");
            for (var row : saved.rows()) {
                s.windowDigest = ProtectionKey.hash(s.windowDigest + ":" + row.proof().digest());
                s.cursor = row.proof().canonicalId();
            }
            s.warmed = Math.addExact(s.warmed, saved.rows().size());
            if (s.warmed > window.maxRows()) fail(INVALID_PROGRESS);
            if (saved.last()) s.phase = Phase.VERIFY_WINDOW;
            save(c, session, s); return null;
        });
    }
    /** Every persisted selected page is reread and source-revalidated, never just a summary count. */
    public Optional<Page<T, ID>> nextVerificationPage(Session session) {
        return guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.VERIFY_WINDOW);
            if (s.verifiedPages == s.pages) return Optional.empty();
            if (!applied(c, session, s.verifiedPages)) fail(INVALID_PROGRESS);
            var page = page(c, session, s.verifiedPages); source.revalidate(c, session, page); return Optional.of(page);
        });
    }
    public void acknowledgeVerificationPage(Session session, Page<T, ID> page) {
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.VERIFY_WINDOW);
            if (!page(c, session, s.verifiedPages).id().equals(page.id())) fail(OPERATION_CONFLICT);
            updatePage(c, session, s.verifiedPages, "is_verified");
            s.verifiedPages++; save(c, session, s); return null;
        });
    }
    public void verified(Session session, String nativeProof) {
        ProtectionKey.token(nativeProof);
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.VERIFY_WINDOW);
            if (s.pages == 0 || s.pages != s.verifiedPages) fail(INVALID_PROGRESS);
            s.runtimeProof = nativeProof; s.phase = Phase.READY; save(c, session, s); return null;
        });
    }
    public Activation activation(Session session) {
        return guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session);
            if (s.activation == null) fail(MIGRATION_REQUIRED); return s.activation;
        });
    }
    /** Reserve after actual warm verification, not once per warm page/renewed lease. */
    public Activation prepareActivation(Session session, Closure closure, String freshNativeProof, int rangeSize) {
        ProtectionKey.token(freshNativeProof);
        return guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session);
            if (s.phase == Phase.SQL_OPEN) {
                if (s.activation == null || s.activation.range().last()-s.activation.range().first()+1 != rangeSize)
                    fail(RECOVERY_REQUIRED);
                return s.activation;
            }
            if (s.phase != Phase.READY || s.finalizing) fail(INVALID_PROGRESS);
            bindClosure(s, closure);
            if (s.pages == 0 || s.pages != s.verifiedPages || s.inventory == null) fail(INCOMPLETE_INVENTORY);
            if (s.activation != null && s.activation.range().controlEpoch() == session.lease().epoch())
                return s.activation;
            var range = allocator.reserveInitialIn(c, session, rangeSize, UUID.randomUUID(), s.runId);
            String token = ProtectionKey.hash("initial-activation-v1:" + s.plan + ":" + s.manifest + ":" + s.operation
                    + ":" + s.generation + ":" + s.windowDigest + ":" + freshNativeProof + ":" + range.request());
            s.activation = new Activation(token, s.plan, s.manifest, s.inventory, s.window(), freshNativeProof, range);
            s.runtimeProof = freshNativeProof; save(c, session, s); return s.activation;
        });
    }
    /** SQL OPEN and journal phase SQL_OPEN commit together after exact retained range verification. */
    public void openSql(Session session, Activation activation) {
        guard.openInitialActivation(session, activation, c -> {
            var s = readRequired(c, session); requirePhase(s, Phase.READY);
            if (s.activation == null || !s.activation.token().equals(activation.token())) fail(OPERATION_CONFLICT);
            s.phase = Phase.SQL_OPEN; save(c, session, s); return null;
        });
    }
    public void incomplete(Session session) {
        guard.inInitialBootstrapTransaction(session, c -> {
            var s = readRequired(c, session);
            if (s.phase != Phase.SQL_OPEN && s.phase != Phase.ACTIVE) { s.phase = Phase.INCOMPLETE; save(c, session, s); }
            return null;
        });
    }

    private boolean applied(Connection c, Session session, int page) throws SQLException {
        try (var q = prepare(c, "SELECT is_applied FROM cachedb_initial_page WHERE "+pairWhere()+" AND operation_id="+ascii(36)+" AND page_no=?")) {
            pageKey(q, session, page); try (var r = q.executeQuery()) {
                if (!r.next()) fail(INVALID_PROGRESS); int value = r.getInt(1);
                if (value != 0 && value != 1) fail(INVALID_PROGRESS); return value == 1;
            }
        }
    }
    private Page<T, ID> page(Connection c, Session session, int number) throws SQLException {
        int bytes;
        try (var q = prepare(c, "SELECT payload_bytes FROM cachedb_initial_page WHERE "+pairWhere()+" AND operation_id="+ascii(36)+" AND page_no=?")) {
            pageKey(q, session, number); try (var r = q.executeQuery()) {
                if (!r.next()) fail(INVALID_PROGRESS); bytes = r.getInt(1);
                if (bytes < 1 || bytes > window.pageBytes()) fail(INVALID_PROGRESS);
            }
        }
        try (var q = prepare(c, "SELECT payload FROM cachedb_initial_page WHERE "+pairWhere()+" AND operation_id="+ascii(36)+" AND page_no=?")) {
            pageKey(q, session, number); q.setFetchSize(1);
            try (var r = q.executeQuery()) {
                if (!r.next()) fail(INVALID_PROGRESS);
                return InitialBootstrapPageCodec.decode(window, session, payload(r, 1, bytes));
            }
        }
    }
    private void updatePage(Connection c, Session session, int number, String column) throws SQLException {
        if (!"is_applied".equals(column) && !"is_verified".equals(column)) fail(INVALID_REQUEST);
        try (var q = prepare(c, "UPDATE cachedb_initial_page SET " + column + "=1 WHERE "+pairWhere()+""
                + " AND operation_id="+ascii(36)+" AND page_no=? AND " + column + "=0")) {
            pageKey(q, session, number); one(q.executeUpdate());
        }
    }
    private InitialBootstrapState readRequired(Connection c, Session session) throws SQLException {
        var value = read(c, session.request()); if (value == null) fail(INVALID_PROGRESS); return value;
    }
    private InitialBootstrapState read(Connection c, Request request) throws SQLException {
        int bytes;
        try (var q = prepare(c, "SELECT payload_bytes FROM cachedb_initial_bootstrap WHERE "+pairWhere()+"")) {
            pair(q, 1, request); try (var r = q.executeQuery()) {
                if (!r.next()) return null; bytes = r.getInt(1);
                if (bytes < 1 || bytes > InitialBootstrapState.MAX_BYTES) fail(INVALID_PROGRESS);
            }
        }
        try (var q = prepare(c, "SELECT operation_id,generation,plan_token,manifest_token,phase_name,activation_token,payload"
                + " FROM cachedb_initial_bootstrap WHERE "+pairWhere()+"")) {
            pair(q, 1, request); q.setFetchSize(1);
            try (var r = q.executeQuery()) {
                if (!r.next()) fail(INVALID_PROGRESS);
                var s = InitialBootstrapState.decode(payload(r, 7, bytes), request.generation().key());
                if (!s.operation.equals(request.operationId()) || s.generation != request.generation().value()
                        || !s.plan.equals(window.fingerprint()) || !s.manifest.equals(manifest) || !s.operator.equals(request.operatorToken())
                        || !s.operation.toString().equals(r.getString(1)) || s.generation != r.getLong(2)
                        || !s.plan.equals(r.getString(3)) || !s.manifest.equals(r.getString(4)) || !s.phase.name().equals(r.getString(5))
                        || !Objects.equals(s.activation == null ? null : s.activation.token(), r.getString(6))) fail(OPERATION_CONFLICT);
                return s;
            }
        }
    }
    private void insert(Connection c, Request request, InitialBootstrapState s) throws SQLException {
        byte[] payload = s.encode();
        try (var q = prepare(c, "INSERT INTO cachedb_initial_bootstrap(scope_token,tenant_token,operation_id,generation,plan_token,"
                + "manifest_token,phase_name,revision,payload,payload_bytes) VALUES ("+ascii(64)+","+ascii(64)+","+ascii(36)+",?,"+ascii(64)+","+ascii(64)+","+ascii(32)+",0,?,?)")) {
            pair(q, 1, request); q.setString(3, s.operation.toString()); q.setLong(4, s.generation);
            q.setString(5, s.plan); q.setString(6, s.manifest); q.setString(7, s.phase.name());
            q.setBytes(8, payload); q.setInt(9, payload.length); one(q.executeUpdate());
        }
    }
    private void save(Connection c, Session session, InitialBootstrapState s) throws SQLException {
        long before = s.revision; s.revision = Math.addExact(before, 1); byte[] payload = s.encode();
        try (var q = prepare(c, "UPDATE cachedb_initial_bootstrap SET phase_name="+ascii(32)+",activation_token="+ascii(64)+",revision=?,payload=?,payload_bytes=?"
                + " WHERE "+pairWhere()+" AND operation_id="+ascii(36)+" AND revision=?")) {
            q.setString(1, s.phase.name()); q.setString(2, s.activation == null ? null : s.activation.token());
            q.setLong(3, s.revision); q.setBytes(4, payload); q.setInt(5, payload.length);
            key(q, 6, session); q.setString(8, session.request().operationId().toString()); q.setLong(9, before); one(q.executeUpdate());
        }
    }
    private static void bindClosure(InitialBootstrapState s, Closure closure) {
        if (s.runId != null && !s.runId.equals(closure.runId()) || s.roster != null && !s.roster.equals(closure.rosterToken()))
            fail(RECOVERY_REQUIRED);
        s.runId = closure.runId(); s.roster = closure.rosterToken();
    }
    private String ascii(int width) { return InitialSqlTokens.varchar(guard.ownerBootstrapDialect(), width); }
    private String pairWhere() { return InitialSqlTokens.pair(guard.ownerBootstrapDialect(), false); }
    private PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var q = c.prepareStatement(sql); try { q.setQueryTimeout(timeout); return q; } catch (SQLException e) { q.close(); throw e; }
    }
    private static byte[] payload(ResultSet rows, int column, int bytes) throws SQLException {
        try (var in = rows.getBinaryStream(column)) {
            if (in == null) throw new ProtectionException(INVALID_PROGRESS);
            byte[] value = in.readNBytes(bytes + 1); if (value.length != bytes) fail(INVALID_PROGRESS); return value;
        } catch (IOException malformed) { throw new ProtectionException(INVALID_PROGRESS); }
    }
    private static void pageKey(PreparedStatement q, Session session, int page) throws SQLException {
        key(q, 1, session); q.setString(3, session.request().operationId().toString()); q.setInt(4, page);
    }
    private static void key(PreparedStatement q, int p, Session session) throws SQLException { pair(q, p, session.request()); }
    private static void pair(PreparedStatement q, int p, Request request) throws SQLException {
        q.setString(p, request.generation().key().scopeToken()); q.setString(p + 1, request.generation().key().tenantToken());
    }
    private static void requirePhase(InitialBootstrapState s, Phase phase) { if (s.phase != phase) fail(INVALID_PROGRESS); }
    private static void admin() { if (!CacheTenantContext.isAdministrative()) fail(ADMIN_REQUIRED); }
    private static void one(int n) { if (n != 1) fail(OPERATION_CONFLICT); }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}
