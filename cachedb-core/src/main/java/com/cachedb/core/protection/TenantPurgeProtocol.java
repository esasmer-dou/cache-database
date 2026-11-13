package com.reactor.cachedb.core.protection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Explicit pair-scoped contracts. No foreground SQL reservation or foreground guard query. */
public final class TenantPurgeProtocol {
    public static final int CHUNK = 128, MAX_ID_BYTES = 64, MAX_CURSOR = 64;
    public static final String START = "-";
    private TenantPurgeProtocol() {}

    public enum Stage { FENCING, REDIS, SOURCE, VERIFYING, COMPLETE, REOPENING, REENABLED, REQUIRES_MIGRATION }
    public record Generation(ProtectionKey key, long value) {
        public Generation { Objects.requireNonNull(key); positive(value); }
    }
    public record SourcePlan(String table, String tenantColumn, String idColumn) {
        public SourcePlan { identifier(table, true); identifier(tenantColumn, false); identifier(idColumn, false); }
        private static void identifier(String name, boolean qualified) {
            String expression = qualified ? "[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?"
                    : "[A-Za-z_][A-Za-z0-9_]{0,62}";
            if (name == null || !name.matches(expression))
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
            if (qualified && name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT).startsWith("cachedb_"))
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
    }
    public record Binding(Class<?> entityType, String scope, SourcePlan source, String layoutToken,
            int maxWorkItems, int maxKeysPerObject, int maxBatchBytes, boolean tenantPurgeEnabled) {
        public Binding {
            Objects.requireNonNull(entityType); Objects.requireNonNull(source); ProtectionKey.token(layoutToken);
            if (scope == null || !scope.matches("[A-Za-z0-9._:-]{1,128}") || maxWorkItems < 1
                    || maxWorkItems > 10_000_000 || maxKeysPerObject < 1 || maxKeysPerObject > 4096
                    || maxBatchBytes < 1024 || maxBatchBytes > 16 * 1024 * 1024)
                throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        }
        public ProtectionKey key(String tenant) { return ProtectionKey.of(scope, tenant); }
        public String token() { return ProtectionKey.hash(scope); }
        public String fingerprint() {
            return ProtectionKey.hash("v1:" + scope.length() + ":" + scope + ":" + source.table() + ":"
                    + source.tenantColumn() + ":" + source.idColumn() + ":" + layoutToken + ":"
                    + maxWorkItems + ":" + maxKeysPerObject + ":" + maxBatchBytes + ":" + tenantPurgeEnabled);
        }
    }
    public record Lease(ProtectionKey key, UUID operationId, UUID owner, long epoch, int seconds) {
        public Lease {
            Objects.requireNonNull(key); Objects.requireNonNull(operationId); Objects.requireNonNull(owner); positive(epoch);
            if (seconds < 15 || seconds > 120) invalid();
        }
    }
    /** Stable purge worklist, not a mutating Redis SCAN cursor. */
    public record Page(UUID id, String nextCursor, boolean last, List<String> ids) {
        public Page {
            Objects.requireNonNull(id);
            TenantPurgeProtocol.cursor(nextCursor);
            if (ids == null || ids.size() > CHUNK) invalid();
            ids = List.copyOf(ids);
            for (String value : ids) ProtectionKey.token(value);
            if (Set.copyOf(ids).size() != ids.size() || ids.isEmpty() && !last) invalid();
        }
        @Override public String toString() { return "PurgePage[redacted]"; }
    }
    public record Progress(long revision, UUID operationId, long closedGeneration, long newGeneration,
            Stage stage, boolean includeSource, String operatorToken, Instant startedAt, Instant completedAt,
            long redisRows, long redisKeys, long sourceRows, String inventoryToken, String cursor,
            Page pending, int pendingOffset, String bindingFingerprint) {
        public Progress {
            if (revision < 0 || closedGeneration <= 0 || newGeneration < 0 || redisRows < 0 || redisKeys < 0 || sourceRows < 0)
                invalid();
            Objects.requireNonNull(operationId); Objects.requireNonNull(stage); Objects.requireNonNull(startedAt);
            ProtectionKey.token(bindingFingerprint);
            ProtectionKey.token(operatorToken); TenantPurgeProtocol.cursor(cursor);
            if (inventoryToken != null) ProtectionKey.token(inventoryToken);
            if (!includeSource && (stage == Stage.SOURCE || stage == Stage.REOPENING || stage == Stage.REENABLED)) invalid();
            if (inventoryToken == null && stage != Stage.FENCING && stage != Stage.REQUIRES_MIGRATION || sourceRows > 0 && !includeSource) invalid();
            boolean activating = stage == Stage.REOPENING || stage == Stage.REENABLED;
            if (activating ? closedGeneration == Long.MAX_VALUE || newGeneration != closedGeneration + 1 : newGeneration != 0)
                invalid();
            if ((stage == Stage.COMPLETE || activating) && completedAt == null) invalid();
            if (pending != null && stage != Stage.REDIS && stage != Stage.REQUIRES_MIGRATION) invalid();
            if (pendingOffset < 0 || pending == null && pendingOffset != 0
                    || pending != null && pendingOffset >= pending.ids().size()) invalid();
        }
        public Progress advance(Stage next, String inventory, String nextCursor, Page page, int offset,
                long rows, long keys, long source, long activation, Instant completed) {
            return new Progress(Math.addExact(revision, 1), operationId, closedGeneration, activation, next,
                    includeSource, operatorToken, startedAt, completed, rows, keys, source, inventory, nextCursor, page, offset, bindingFingerprint);
        }
        public PurgeReceipt receipt(ProtectionKey key) {
            return new PurgeReceipt(operationId, key, closedGeneration, newGeneration, stage, redisRows,
                    redisKeys, sourceRows, includeSource, startedAt, completedAt, operatorToken,
                    pending == null ? 0 : pending.ids().size() - pendingOffset);
        }
        @Override public String toString() { return "PurgeProgress[operation=" + operationId + ",stage=" + stage + "]"; }
    }
    public record BatchResult(int rows, long removedKeys) {
        public BatchResult { if (rows < 0 || rows > CHUNK || removedKeys < 0 || removedKeys > 1_048_576) invalid(); }
    }
    public interface Store {
        void validateSource(Binding binding);
        /** Initial call durably closes SQL admission. Replays must match operationId/includeSource. */
        Lease acquire(ProtectionKey key, PurgeOptions options, boolean reenable, String bindingFingerprint);
        void renew(Lease lease);
        Progress load(Lease lease);
        Progress save(Lease lease, Progress before, Progress after);
        /** DELETE plus durable progress commit in ONE connection/transaction holding the tenant guard row. */
        Progress deleteSource(Lease lease, Binding binding, String rawTenant, int limit);
        Progress prepareReenable(Lease lease);
        void openPreparedGeneration(Lease lease);
        void release(Lease lease);
    }
    public static void cursor(String value) {
        if (!START.equals(value)) ProtectionKey.token(value);
    }
    public static void positive(long value) { if (value <= 0) invalid(); }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
}
