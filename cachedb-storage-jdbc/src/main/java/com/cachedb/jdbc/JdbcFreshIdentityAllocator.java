package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.FreshIdentityRange.Request;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.Session;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/**
 * Bounded administrative SQL reservation only. Lock order: tenant control, then namespace allocator.
 * One retained row/namespace, independent of tenant version ranges. No SQL per entity, Redis calls,
 * background retry, lazy DDL, native grant installation, owner creation or ordinary-save bypass.
 * Metadata and its durable backup must survive every tenant purge and allocator deployment rollback.
 * Loss/rollback of this SQL ledger requires recovery of its highwater, NEVER reprovisioning a used
 * interval. The explicit exclusivity attestation is an operator prerequisite, not an external proof.
 */
public final class JdbcFreshIdentityAllocator {
    public record Status(long highwater, long reservationEpoch) {}
    private record Row(String binding, String authority, long first, long last, String exclusive,
            long high, long epoch, String request, String tenant, Long generation, Long control,
            Long allocatedFirst, Integer count) {}
    private final JdbcTenantPurgeGuard guard;
    private final JdbcTenantPurgeDialect dialect;
    private final int timeout;

    public JdbcFreshIdentityAllocator(JdbcTenantPurgeGuard guard, JdbcTenantPurgeDialect dialect,
            int timeoutSeconds) {
        this.guard = Objects.requireNonNull(guard);
        this.dialect = Objects.requireNonNull(dialect);
        if (timeoutSeconds < 1 || timeoutSeconds > 5 || guard.ownerBootstrapDialect() != dialect)
            throw new ProtectionException(INVALID_REQUEST);
        timeout = timeoutSeconds;
    }

    /**
     * Explicit one-time domain provision under a real CLOSED maintenance lease. Existing domains
     * must match exactly; neither bounds nor authority can be replaced, even after purge/exhaustion.
     * Exclusivity is the administrator's prerequisite, not a claim verified against source data.
     */
    public Status provisionClosed(Lease lease, long generation, FreshIdentityDomain domain) {
        requireScope(lease.key(), domain);
        return guard.inClosedVersionTransaction(lease, generation, domain.bindingFingerprint(),
                (c, control) -> provision(c, domain));
    }

    /** Provision during a real, still-CLOSED INITIAL session; never creates purge progress. */
    public Status provisionInitial(Session session, FreshIdentityDomain domain) {
        Objects.requireNonNull(session);
        requireScope(session.lease().key(), domain);
        return guard.inInitialIdentityTransaction(session, domain.bindingFingerprint(),
                (c, control) -> provision(c, domain));
    }

    private Status provision(Connection c, FreshIdentityDomain domain) throws SQLException {
        try (var q = prepare(c, JdbcFreshIdentityDialect.provision(dialect))) {
            q.setString(1, domain.scopeToken()); q.setString(2, domain.bindingFingerprint());
            q.setString(3, domain.authorityId().toString()); q.setLong(4, domain.first());
            q.setLong(5, domain.last()); q.setString(6, domain.exclusiveReservationToken());
            q.setLong(7, domain.first() - 1);
            if (dialect == JdbcTenantPurgeDialect.MSSQL) q.setString(8, domain.scopeToken());
            q.executeUpdate();
        }
        return status(read(c, domain));
    }

    /** Reads the namespace CAS position without granting any allocation or producer authority. */
    public Status statusClosed(Lease lease, long generation, FreshIdentityDomain domain) {
        requireScope(lease.key(), domain);
        return guard.inClosedVersionTransaction(lease, generation, domain.bindingFingerprint(),
                (c, control) -> status(read(c, domain)));
    }

    /** Administrative status read serialized with closure of the supplied tenant generation. */
    public Status statusOpen(Generation generation, FreshIdentityDomain domain) {
        requireScope(generation.key(), domain);
        return guard.inOpenVersionTransaction(generation, domain.bindingFingerprint(),
                (c, control) -> status(read(c, domain)));
    }

    /** Exact latest retries may replay only under the original tenant generation AND control epoch. */
    public FreshIdentityRange reserveClosed(Lease lease, Request request) {
        Objects.requireNonNull(request);
        if (!lease.key().equals(request.generation().key())) throw new ProtectionException(INVALID_REQUEST);
        return guard.inClosedVersionTransaction(lease, request.generation().value(), request.domain().bindingFingerprint(),
                (c, control) -> reserve(c, request, control, true));
    }

    /** Admin-only refill on an OPEN SQL generation; not foreground write acceptance. */
    public FreshIdentityRange reserveOpen(Request request) {
        Objects.requireNonNull(request);
        return guard.inOpenVersionTransaction(request.generation(), request.domain().bindingFingerprint(),
                (c, control) -> reserve(c, request, control, true));
    }

    /**
     * One-shot administrative OPEN reservation: only a newly committed CAS returns a range.
     * The retained same-UUID receipt always conflicts, including a rebased expected epoch.
     * An uncertain commit may consume the interval without returning it; never reconstruct native
     * admission authority using reserveOpen's replay. This method itself grants no native authority.
     */
    public FreshIdentityRange reserveOnceOpen(Request request) {
        Objects.requireNonNull(request);
        return guard.inOpenVersionTransaction(request.generation(), request.domain().bindingFingerprint(),
                (c, control) -> reserve(c, request, control, false));
    }

    private FreshIdentityRange reserve(Connection c, Request request, long control, boolean replayLatest) throws SQLException {
        if (control < 1 || control > FreshIdentityRange.MAX_EPOCH) throw new ProtectionException(INVALID_REQUEST);
        Row row = read(c, request.domain());
        if (request.requestId().toString().equals(row.request)) {
            if (!replayLatest || row.epoch != request.expectedReservationEpoch() + 1 || row.count != request.count()
                    || !request.generation().key().tenantToken().equals(row.tenant)
                    || request.generation().value() != row.generation || control != row.control)
                throw new ProtectionException(RESERVATION_CONFLICT);
            return new FreshIdentityRange(request, control, row.epoch, row.allocatedFirst, row.high);
        }
        if (row.epoch != request.expectedReservationEpoch()) throw new ProtectionException(RESERVATION_CONFLICT);
        if (row.high > request.domain().last() - request.count()) throw new ProtectionException(VERSION_LIMIT);
        long first = row.high + 1, last = row.high + request.count(), next = row.epoch + 1;
        try (var q = prepare(c, JdbcFreshIdentityDialect.update(dialect))) {
            q.setLong(1, last); q.setLong(2, next); q.setString(3, request.requestId().toString());
            q.setString(4, request.generation().key().tenantToken()); q.setLong(5, request.generation().value());
            q.setLong(6, control); q.setLong(7, first); q.setInt(8, request.count());
            q.setString(9, request.domain().scopeToken()); q.setLong(10, row.epoch);
            if (q.executeUpdate() != 1) throw new ProtectionException(RESERVATION_CONFLICT);
        }
        return new FreshIdentityRange(request, control, next, first, last);
    }

    private Row read(Connection c, FreshIdentityDomain domain) throws SQLException {
        try (var q = prepare(c, JdbcFreshIdentityDialect.lock(dialect))) {
            q.setString(1, domain.scopeToken());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new ProtectionException(MIGRATION_REQUIRED);
                Row row = new Row(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4),
                        r.getString(5), r.getLong(6), r.getLong(7), r.getString(8), r.getString(9),
                        nullableLong(r, 10), nullableLong(r, 11), nullableLong(r, 12), nullableInteger(r, 13));
                if (!domain.bindingFingerprint().equals(row.binding)
                        || !domain.authorityId().toString().equals(row.authority) || domain.first() != row.first
                        || domain.last() != row.last || !domain.exclusiveReservationToken().equals(row.exclusive))
                    throw new ProtectionException(OPERATION_CONFLICT);
                if (row.high < row.first - 1 || row.high > row.last || row.epoch < 0 || row.epoch > FreshIdentityRange.MAX_EPOCH
                        || row.epoch == 0 && (row.high != row.first - 1 || row.request != null || row.tenant != null
                            || row.generation != null || row.control != null || row.allocatedFirst != null || row.count != null)
                        || row.epoch > 0 && (!canonicalRequest(row.request) || row.tenant == null
                            || !row.tenant.matches("[0-9a-f]{64}") || row.control == null || row.generation == null
                            || row.allocatedFirst == null || row.count == null || row.control < 1
                            || row.control > FreshIdentityRange.MAX_EPOCH || row.generation > FreshIdentityRange.MAX_EPOCH
                            || row.generation < 1 || row.count < 1 || row.count > FreshIdentityRange.MAX_RANGE
                            || row.allocatedFirst < row.first || row.high - row.allocatedFirst != row.count - 1L))
                    throw new ProtectionException(RECOVERY_REQUIRED);
                return row;
            }
        }
    }

    private static Long nullableLong(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }
    private static Integer nullableInteger(ResultSet rows, int column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
    private static boolean canonicalRequest(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private PreparedStatement prepare(Connection c, String sql) throws SQLException {
        var q = c.prepareStatement(sql);
        try { q.setQueryTimeout(timeout); return q; }
        catch (SQLException failure) { q.close(); throw failure; }
    }
    private static Status status(Row row) { return new Status(row.high, row.epoch); }
    private static void requireScope(ProtectionKey key, FreshIdentityDomain domain) {
        Objects.requireNonNull(domain);
        if (!key.scopeToken().equals(domain.scopeToken())) throw new ProtectionException(INVALID_REQUEST);
    }
}
