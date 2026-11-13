package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.EmptyTenantActivation.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Retained EMPTY decision journal. All mutations share the live SQL guard row transaction. */
public final class JdbcEmptyTenantActivation {
    private static final int MAX_BYTES = 4096;
    private final JdbcTenantPurgeGuard guard;
    private final JdbcTenantVersionAllocator allocator;
    private final JdbcTenantPurgeDialect dialect;
    private final Binding binding;
    private final String manifest;
    private final int queues;

    public JdbcEmptyTenantActivation(JdbcTenantPurgeGuard guard, JdbcTenantVersionAllocator allocator,
            Binding binding, String manifest, int queues) {
        this.guard = Objects.requireNonNull(guard); this.allocator = Objects.requireNonNull(allocator);
        this.binding = Objects.requireNonNull(binding); this.manifest = ProtectionKey.token(manifest);
        dialect = guard.ownerBootstrapDialect();
        if (!binding.tenantPurgeEnabled() || !allocator.belongsTo(guard) || queues < 1 || queues > 128)
            throw new ProtectionException(UNWIRED_PROTOCOL);
        this.queues = queues;
    }

    /** Completed retries must not acquire a new control epoch and invalidate live allocator refills. */
    public Optional<Checkpoint> completed(String tenant, Request request) {
        validate(tenant, request);
        return guard.activationInspection(binding.key(tenant), state -> {
            Checkpoint cp = read(state.connection(), binding.key(tenant), request.purge().operationId());
            if (cp == null) return Optional.empty();
            match(cp, request); requireState(state, cp);
            return cp.phase() == Phase.ACTIVE ? Optional.of(cp) : Optional.empty();
        });
    }

    /** Atomically binds the retained purge certificate and changes SQL CLOSED(g) to CLOSED(g+1). */
    public Checkpoint prepare(Lease lease, String tenant, Request request) {
        validate(tenant, request); requirePair(lease, tenant, request);
        return guard.activationTransaction(lease, state -> {
            if (!state.progress().bindingFingerprint().equals(binding.fingerprint()) || !state.progress().includeSource())
                throw new ProtectionException(OPERATION_CONFLICT);
            Checkpoint cp = read(state.connection(), lease.key(), lease.operationId());
            if (cp != null) {
                match(cp, request); requireState(state, cp);
                if (cp.phase() == Phase.ACTIVE) throw new ProtectionException(OPERATION_CONFLICT);
                if (cp.attemptEpoch() == lease.epoch() && cp.attemptOwner().equals(lease.owner())) return cp;
                if (cp.attemptEpoch() >= lease.epoch()) throw new ProtectionException(LEASE_LOST);
                // Only queue receipts are replayed; the SQL_OPEN range/decision remains immutable.
                cp = new Checkpoint(cp.identity(), cp.phase(), lease.epoch(), lease.owner(), 0, cp.reservation());
                write(state.connection(), cp, false); return cp;
            }
            Progress p = state.progress();
            if (p.stage() != Stage.COMPLETE || !p.includeSource()) throw new ProtectionException(MIGRATION_REQUIRED);
            certificate(state.connection(), lease, request);
            Identity id = new Identity(lease.key(), lease.operationId(), request.activationId(), p.closedGeneration(),
                    Math.addExact(p.closedGeneration(), 1), request.purgeCertificate(), manifest,
                    p.inventoryToken(), request.purge().operatorToken());
            cp = new Checkpoint(id, Phase.PREPARED, lease.epoch(), lease.owner(), 0, null);
            guard.prepareEmptyGeneration(state, lease);
            write(state.connection(), cp, true); return cp;
        });
    }

    public Checkpoint queueAcknowledged(Lease lease, Checkpoint expected) {
        return guard.activationTransaction(lease, state -> {
            Checkpoint cp = current(state, lease, expected);
            if (cp.phase() == Phase.ACTIVE || cp.queueCursor() >= queues) throw new ProtectionException(INVALID_PROGRESS);
            Checkpoint next = new Checkpoint(cp.identity(), cp.phase(), cp.attemptEpoch(), cp.attemptOwner(),
                    cp.queueCursor() + 1, cp.reservation());
            write(state.connection(), next, false); return next;
        });
    }

    /** No Redis callback here. Reservation, OPEN and SQL_OPEN checkpoint commit together. */
    public Checkpoint openSql(Lease lease, String tenant, Checkpoint expected, String runId, int rangeSize) {
        if (!binding.key(tenant).equals(lease.key())) throw new ProtectionException(CONFIRMATION_REQUIRED);
        return guard.activationTransaction(lease, state -> {
            Checkpoint cp = current(state, lease, expected);
            if (cp.phase() != Phase.PREPARED || cp.queueCursor() != queues) throw new ProtectionException(INVALID_PROGRESS);
            try (var q = statement(state.connection(), dialect.sourceProbeSql(binding.source()))) {
                dialect.bindTenant(q, tenant, 1);
                try (var rows = q.executeQuery()) {
                    if (rows.next()) throw new ProtectionException(RECOVERY_REQUIRED);
                }
            }
            TenantVersionRange range = allocator.reserveEmpty(state.connection(), lease, binding, cp.identity(), rangeSize, runId);
            Checkpoint next = new Checkpoint(cp.identity(), Phase.SQL_OPEN, cp.attemptEpoch(), cp.attemptOwner(), 0, range);
            guard.openEmptyGeneration(state, lease); write(state.connection(), next, false); return next;
        });
    }

    public Checkpoint complete(Lease lease, Checkpoint expected, Receipt observed) {
        return guard.activationTransaction(lease, state -> {
            Checkpoint cp = current(state, lease, expected);
            if (cp.phase() != Phase.SQL_OPEN || cp.queueCursor() != queues) throw new ProtectionException(INVALID_PROGRESS);
            observed.require(cp);
            Checkpoint next = new Checkpoint(cp.identity(), Phase.ACTIVE, cp.attemptEpoch(), cp.attemptOwner(), queues, cp.reservation());
            guard.completeEmptyGeneration(state, lease); write(state.connection(), next, false); return next;
        });
    }

    private Checkpoint current(JdbcTenantPurgeGuard.ActivationState state, Lease lease, Checkpoint expected) throws SQLException {
        expected.requireLease(lease);
        Checkpoint cp = read(state.connection(), lease.key(), lease.operationId());
        if (cp == null || !Arrays.equals(encode(cp), encode(expected))) throw new ProtectionException(OPERATION_CONFLICT);
        cp.requireLease(lease); requireState(state, cp); return cp;
    }
    private void requireState(JdbcTenantPurgeGuard.ActivationState state, Checkpoint cp) {
        Progress p = state.progress(); Identity id = cp.identity();
        if (p == null || cp.queueCursor() > queues || !p.operationId().equals(id.operationId()) || !p.includeSource()
                || !p.bindingFingerprint().equals(binding.fingerprint()) || !Objects.equals(p.inventoryToken(), id.inventoryToken())
                || p.closedGeneration() != id.fromGeneration() || p.newGeneration() != id.toGeneration()
                || state.generation() != id.toGeneration()
                || !state.gate().equals(cp.phase() == Phase.PREPARED ? "CLOSED" : "OPEN")
                || p.stage() != (cp.phase() == Phase.ACTIVE ? Stage.REENABLED : Stage.REOPENING))
            throw new ProtectionException(OPERATION_CONFLICT);
    }
    private void certificate(Connection c, Lease lease, Request request) throws SQLException {
        try (var q = statement(c, "SELECT cursor_value,payload,is_done,evidence_token FROM cachedb_purge_facade WHERE "
                + pair() + " AND operation_id=" + varchar(36) + " AND checkpoint_id='control'")) {
            key(q, 1, lease.key()); q.setString(3, lease.operationId().toString());
            try (var r = q.executeQuery()) {
                if (!r.next() || !"COMPLETE".equals(r.getString(1)) || r.getInt(3) != 1
                        || !request.purgeCertificate().equals(r.getString(4))) throw new ProtectionException(RECOVERY_REQUIRED);
                try (var in = r.getBinaryStream(2)) {
                    if (in == null || !Arrays.equals(in.readNBytes(65), manifest.getBytes(StandardCharsets.US_ASCII)))
                        throw new ProtectionException(OPERATION_CONFLICT);
                } catch (IOException invalid) { throw new ProtectionException(INVALID_PROGRESS); }
            }
        }
    }
    private void validate(String tenant, Request request) {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ADMIN_REQUIRED);
        Objects.requireNonNull(request); ProtectionKey.checkTenant(tenant);
        if (!tenant.equals(request.purge().confirmation())) throw new ProtectionException(CONFIRMATION_REQUIRED);
        if (!manifest.equals(request.manifestToken())) throw new ProtectionException(OPERATION_CONFLICT);
    }
    private void requirePair(Lease lease, String tenant, Request request) {
        if (!lease.key().equals(binding.key(tenant)) || !lease.operationId().equals(request.purge().operationId()))
            throw new ProtectionException(OPERATION_CONFLICT);
        if (lease.epoch() > TenantVersionRange.MAX_VERSION) throw new ProtectionException(VERSION_LIMIT);
    }
    private static void match(Checkpoint cp, Request request) {
        Identity id = cp.identity();
        if (!id.activationId().equals(request.activationId()) || !id.operationId().equals(request.purge().operationId())
                || !id.purgeCertificate().equals(request.purgeCertificate()) || !id.manifestToken().equals(request.manifestToken())
                || !id.operatorToken().equals(request.purge().operatorToken())) throw new ProtectionException(OPERATION_CONFLICT);
    }
    static boolean managed(Connection c, Lease lease, int timeout, JdbcTenantPurgeDialect dialect) throws SQLException {
        return managed(c, lease.key(), lease.operationId(), timeout, dialect);
    }
    static boolean managed(Connection c, ProtectionKey key, UUID operation, int timeout, JdbcTenantPurgeDialect dialect) throws SQLException {
        String pair = dialect == JdbcTenantPurgeDialect.MSSQL ? "scope_token=CAST(? AS CHAR(64)) AND tenant_token=CAST(? AS CHAR(64)) AND operation_id=CAST(? AS VARCHAR(36))"
                : "scope_token=? AND tenant_token=? AND operation_id=?";
        try (var q = c.prepareStatement("SELECT activation_id FROM cachedb_empty_activation WHERE " + pair)) {
            q.setQueryTimeout(timeout); key(q, 1, key); q.setString(3, operation.toString());
            try (var rows = q.executeQuery()) { return rows.next(); }
        }
    }
    private Checkpoint read(Connection c, ProtectionKey key, UUID operation) throws SQLException {
        try (var q = statement(c, "SELECT activation_id,payload FROM cachedb_empty_activation WHERE " + pair() + " AND operation_id=" + varchar(36))) {
            key(q, 1, key); q.setString(3, operation.toString());
            try (var r = q.executeQuery()) {
                if (!r.next()) return null;
                try (var in = r.getBinaryStream(2)) {
                    if (in == null) throw new ProtectionException(INVALID_PROGRESS);
                    Checkpoint cp = decode(in.readNBytes(MAX_BYTES + 1));
                    if (!cp.identity().key().equals(key) || !cp.identity().operationId().equals(operation)
                            || !cp.identity().activationId().toString().equals(r.getString(1))) throw new ProtectionException(INVALID_PROGRESS);
                    return cp;
                } catch (IOException invalid) { throw new ProtectionException(INVALID_PROGRESS); }
            }
        }
    }
    private void write(Connection c, Checkpoint cp, boolean insert) throws SQLException {
        byte[] payload = encode(cp); Identity id = cp.identity();
        String sql = insert ? "INSERT INTO cachedb_empty_activation(payload,scope_token,tenant_token,operation_id,activation_id) VALUES (?,"
                + fixed() + "," + fixed() + "," + varchar(36) + "," + varchar(36) + ")"
                : "UPDATE cachedb_empty_activation SET payload=? WHERE " + pair() + " AND operation_id=" + varchar(36) + " AND activation_id=" + varchar(36);
        try (var q = statement(c, sql)) {
            q.setBinaryStream(1, new ByteArrayInputStream(payload), payload.length); key(q, 2, id.key());
            q.setString(4, id.operationId().toString()); q.setString(5, id.activationId().toString());
            if (q.executeUpdate() != 1) throw new ProtectionException(INVALID_PROGRESS);
        }
    }
    private PreparedStatement statement(Connection c, String sql) throws SQLException {
        var q = c.prepareStatement(sql);
        try { q.setQueryTimeout(5); return q; }
        catch (SQLException failure) { q.close(); throw failure; }
    }
    private String varchar(int width) { return dialect == JdbcTenantPurgeDialect.MSSQL ? "CAST(? AS VARCHAR(" + width + "))" : "?"; }
    private String fixed() { return dialect == JdbcTenantPurgeDialect.MSSQL ? "CAST(? AS CHAR(64))" : "?"; }
    private String pair() { return "scope_token=" + fixed() + " AND tenant_token=" + fixed(); }
    private static void key(PreparedStatement q, int i, ProtectionKey key) throws SQLException {
        q.setString(i, key.scopeToken()); q.setString(i + 1, key.tenantToken());
    }
    private static byte[] encode(Checkpoint cp) {
        try {
            var bytes = new ByteArrayOutputStream(1024); var out = new DataOutputStream(bytes); Identity id = cp.identity();
            out.writeInt(1); out.writeUTF(id.key().scopeToken()); out.writeUTF(id.key().tenantToken());
            out.writeUTF(id.operationId().toString()); out.writeUTF(id.activationId().toString());
            out.writeLong(id.fromGeneration()); out.writeLong(id.toGeneration()); out.writeUTF(id.purgeCertificate());
            out.writeUTF(id.manifestToken()); out.writeUTF(id.inventoryToken()); out.writeUTF(id.operatorToken());
            out.writeUTF(cp.phase().name()); out.writeLong(cp.attemptEpoch()); out.writeUTF(cp.attemptOwner().toString()); out.writeInt(cp.queueCursor());
            TenantVersionRange r = cp.reservation(); out.writeBoolean(r != null);
            if (r != null) {
                out.writeLong(r.controlEpoch()); out.writeLong(r.reservationEpoch()); out.writeLong(r.first()); out.writeLong(r.last());
                out.writeUTF(r.request().toString()); out.writeUTF(r.runId());
            }
            out.flush(); if (bytes.size() > MAX_BYTES) throw new ProtectionException(INVALID_PROGRESS);
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new ProtectionException(INVALID_PROGRESS); }
    }
    static Checkpoint decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ProtectionException(INVALID_PROGRESS);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException();
            Identity id = new Identity(new ProtectionKey(in.readUTF(), in.readUTF()), UUID.fromString(in.readUTF()),
                    UUID.fromString(in.readUTF()), in.readLong(), in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
            Phase phase = Phase.valueOf(in.readUTF()); long epoch = in.readLong(); UUID owner = UUID.fromString(in.readUTF()); int cursor = in.readInt();
            TenantVersionRange range = in.readBoolean() ? new TenantVersionRange(id.key(), id.toGeneration(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), UUID.fromString(in.readUTF()), in.readUTF(), id.inventoryToken()) : null;
            Checkpoint cp = new Checkpoint(id, phase, epoch, owner, cursor, range);
            if (in.available() != 0) throw new IOException(); return cp;
        } catch (IOException | IllegalArgumentException invalid) { throw new ProtectionException(INVALID_PROGRESS); }
    }
}
