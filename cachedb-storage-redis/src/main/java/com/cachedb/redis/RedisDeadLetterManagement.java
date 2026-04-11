package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.queue.DeadLetterActionResult;
import com.reactor.cachedb.core.queue.DeadLetterArchiveRecord;
import com.reactor.cachedb.core.queue.DeadLetterEntry;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.DeadLetterManagement;
import com.reactor.cachedb.core.queue.DeadLetterReplayPreview;
import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
import com.reactor.cachedb.core.queue.ReconciliationRecord;
import com.reactor.cachedb.core.queue.StreamPage;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.core.queue.WriteBehindFailureRecord;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RedisDeadLetterManagement implements DeadLetterManagement {

    private final JedisPooled jedis;
    private final WriteBehindFlusher flusher;
    private final DeadLetterRecoveryConfig config;
    private final RedisWriteOperationMapper mapper;
    private final RedisKeyStrategy keyStrategy;
    private final String deadLetterStreamKey;

    public RedisDeadLetterManagement(
            JedisPooled jedis,
            WriteBehindFlusher flusher,
            DeadLetterRecoveryConfig config,
            RedisWriteOperationMapper mapper,
            RedisKeyStrategy keyStrategy,
            String deadLetterStreamKey
    ) {
        this.jedis = jedis;
        this.flusher = flusher;
        this.config = config;
        this.mapper = mapper;
        this.keyStrategy = keyStrategy;
        this.deadLetterStreamKey = deadLetterStreamKey;
    }

    @Override
    public StreamPage<DeadLetterEntry> queryDeadLetters(DeadLetterQuery query) {
        return scanDescending(
                deadLetterStreamKey,
                query.cursor(),
                query.limit(),
                this::toDeadLetterEntry,
                entry -> matchesDeadLetter(entry, query)
        );
    }

    @Override
    public StreamPage<ReconciliationRecord> queryReconciliation(ReconciliationQuery query) {
        return scanDescending(
                config.reconciliationStreamKey(),
                query.cursor(),
                query.limit(),
                this::toReconciliationRecord,
                entry -> matchesReconciliation(entry, query)
        );
    }

    @Override
    public StreamPage<DeadLetterArchiveRecord> queryArchive(ReconciliationQuery query) {
        return scanDescending(
                config.archiveStreamKey(),
                query.cursor(),
                query.limit(),
                this::toArchiveRecord,
                entry -> matchesArchive(entry, query)
        );
    }

    @Override
    public Optional<DeadLetterEntry> getDeadLetter(String entryId) {
        StreamEntryID streamEntryID = new StreamEntryID(entryId);
        return jedis.xrange(deadLetterStreamKey, streamEntryID, streamEntryID, 1).stream()
                .findFirst()
                .map(this::toDeadLetterEntry);
    }

    @Override
    public DeadLetterReplayPreview dryRunReplay(String entryId) {
        Optional<DeadLetterEntry> candidate = getDeadLetter(entryId);
        if (candidate.isEmpty()) {
            return new DeadLetterReplayPreview(entryId, false, false, false, "NOT_FOUND", "Dead-letter entry not found");
        }

        DeadLetterEntry deadLetterEntry = candidate.get();
        if (isStale(deadLetterEntry.operation())) {
            return new DeadLetterReplayPreview(
                    entryId,
                    true,
                    false,
                    true,
                    "MANUAL_REPLAY_STALE",
                    "Current Redis version is newer than dead-letter version"
            );
        }

        return new DeadLetterReplayPreview(entryId, true, true, false, "MANUAL_REPLAYED", "Replay is eligible");
    }

    @Override
    public DeadLetterActionResult replay(String entryId, String note) {
        DeadLetterEntry deadLetterEntry = requireEntry(entryId);
        QueuedWriteOperation operation = deadLetterEntry.operation();
        WriteBehindFailureRecord failureRecord = toFailureRecord(deadLetterEntry);

        if (isStale(operation)) {
            publishReconciliation("MANUAL_REPLAY_STALE", failureRecord, null, 0, note);
            resolveDeadLetter(deadLetterEntry, "MANUAL_REPLAY_STALE", note);
            return new DeadLetterActionResult(entryId, "replay", true, "MANUAL_REPLAY_STALE");
        }

        try {
            flusher.flush(operation);
            publishReconciliation("MANUAL_REPLAYED", failureRecord, null, 1, note);
            resolveDeadLetter(deadLetterEntry, "MANUAL_REPLAYED", note);
            return new DeadLetterActionResult(entryId, "replay", true, "MANUAL_REPLAYED");
        } catch (SQLException | RuntimeException exception) {
            publishReconciliation("MANUAL_REPLAY_FAILED", failureRecord, exception, 1, note);
            return new DeadLetterActionResult(entryId, "replay", false, "MANUAL_REPLAY_FAILED");
        }
    }

    @Override
    public DeadLetterActionResult skip(String entryId, String note) {
        DeadLetterEntry deadLetterEntry = requireEntry(entryId);
        publishReconciliation("MANUAL_SKIPPED", toFailureRecord(deadLetterEntry), null, 0, note);
        resolveDeadLetter(deadLetterEntry, "MANUAL_SKIPPED", note);
        return new DeadLetterActionResult(entryId, "skip", true, "MANUAL_SKIPPED");
    }

    @Override
    public DeadLetterActionResult close(String entryId, String note) {
        DeadLetterEntry deadLetterEntry = requireEntry(entryId);
        publishReconciliation("MANUAL_CLOSED", toFailureRecord(deadLetterEntry), null, 0, note);
        resolveDeadLetter(deadLetterEntry, "MANUAL_CLOSED", note);
        return new DeadLetterActionResult(entryId, "close", true, "MANUAL_CLOSED");
    }

    private DeadLetterEntry requireEntry(String entryId) {
        return getDeadLetter(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Dead-letter entry not found: " + entryId));
    }

    private void resolveDeadLetter(DeadLetterEntry entry, String status, String note) {
        StreamEntryID streamEntryID = new StreamEntryID(entry.entryId());
        RedisRecoveryStreams.archiveResolved(jedis, config, entry.fields(), entry.entryId(), status, note);
        jedis.xack(deadLetterStreamKey, config.consumerGroup(), streamEntryID);
        jedis.xdel(deadLetterStreamKey, streamEntryID);
    }

    private boolean isStale(QueuedWriteOperation operation) {
        String currentVersion = jedis.get(keyStrategy.versionKey(operation.redisNamespace(), operation.id()));
        if (currentVersion == null || currentVersion.isBlank()) {
            return false;
        }
        return Long.parseLong(currentVersion) > operation.version();
    }

    private DeadLetterEntry toDeadLetterEntry(StreamEntry entry) {
        QueuedWriteOperation operation = mapper.fromBody(entry.getFields());
        Map<String, String> fields = Map.copyOf(entry.getFields());
        return new DeadLetterEntry(
                entry.getID().toString(),
                operation,
                fields.getOrDefault("deadLetterReason", ""),
                fields.getOrDefault("deadLetterMessage", ""),
                WriteFailureDetails.fromFields(
                        fields.get("deadLetterFailureCategory"),
                        fields.get("deadLetterSqlState"),
                        fields.get("deadLetterVendorCode"),
                        fields.get("deadLetterRetryable"),
                        fields.getOrDefault("deadLetterRootType", fields.getOrDefault("deadLetterReason", "")),
                        fields.getOrDefault("deadLetterMessage", "")
                ),
                parseInt(fields.get("deadLetterAttempts")),
                parseInstant(fields.get("createdAt")),
                fields
        );
    }

    private ReconciliationRecord toReconciliationRecord(StreamEntry entry) {
        Map<String, String> fields = Map.copyOf(entry.getFields());
        return new ReconciliationRecord(
                entry.getID().toString(),
                fields.getOrDefault("status", ""),
                fields.getOrDefault("entity", ""),
                fields.getOrDefault("operationType", ""),
                fields.getOrDefault("id", ""),
                parseLong(fields.get("version")),
                parseInstant(fields.get("reconciledAt")),
                WriteFailureDetails.fromFields(
                        fields.get("replayFailureCategory"),
                        fields.get("replaySqlState"),
                        fields.get("replayVendorCode"),
                        fields.get("replayRetryable"),
                        fields.getOrDefault("replayRootType", fields.getOrDefault("replayErrorType", "")),
                        fields.getOrDefault("replayErrorMessage", "")
                ),
                fields
        );
    }

    private WriteBehindFailureRecord toFailureRecord(DeadLetterEntry entry) {
        return new WriteBehindFailureRecord(
                entry.operation(),
                entry.errorType(),
                entry.errorMessage(),
                entry.failureDetails(),
                Instant.now(),
                entry.deadLetterAttempts(),
                entry.fields()
        );
    }

    private void publishReconciliation(
            String status,
            WriteBehindFailureRecord failureRecord,
            Exception replayException,
            int replayAttempts,
            String note
    ) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("status", status);
        fields.put("entity", failureRecord.operation().entityName());
        fields.put("operationType", failureRecord.operation().type().name());
        fields.put("id", failureRecord.operation().id());
        fields.put("version", String.valueOf(failureRecord.operation().version()));
        fields.put("deadLetterErrorType", failureRecord.errorType());
        fields.put("deadLetterErrorMessage", failureRecord.errorMessage());
        fields.put("deadLetterFailureCategory", failureRecord.failureDetails().category().name());
        fields.put("deadLetterSqlState", failureRecord.failureDetails().sqlState());
        fields.put("deadLetterRetryable", String.valueOf(failureRecord.failureDetails().retryable()));
        fields.put("deadLetterVendorCode", String.valueOf(failureRecord.failureDetails().vendorCode()));
        fields.put("deadLetterRootType", failureRecord.failureDetails().errorType());
        fields.put("deadLetterAttempts", String.valueOf(failureRecord.attempts()));
        fields.put("replayAttempts", String.valueOf(replayAttempts));
        fields.put("reconciledAt", Instant.now().toString());
        fields.put("note", note == null ? "" : note);
        if (replayException != null) {
            WriteFailureDetails replayFailure = FailureClassifyingFlusher.classify(flusher, replayException);
            fields.put("replayErrorType", replayException.getClass().getName());
            fields.put("replayErrorMessage", replayException.getMessage() == null ? "" : replayException.getMessage());
            fields.put("replayFailureCategory", replayFailure.category().name());
            fields.put("replaySqlState", replayFailure.sqlState());
            fields.put("replayRetryable", String.valueOf(replayFailure.retryable()));
            fields.put("replayVendorCode", String.valueOf(replayFailure.vendorCode()));
            fields.put("replayRootType", replayFailure.errorType());
        }
        RedisRecoveryStreams.publishReconciliation(jedis, config, fields);
    }

    private <T> StreamPage<T> scanDescending(
            String streamKey,
            String cursor,
            int limit,
            java.util.function.Function<StreamEntry, T> mapperFunction,
            java.util.function.Predicate<T> predicate
    ) {
        String end = cursor == null || cursor.isBlank() ? "+" : "(" + cursor;
        int batchSize = Math.max(limit * 3, limit + 1);
        List<StreamEntry> entries = jedis.xrevrange(streamKey, end, "-", batchSize);
        List<T> items = new ArrayList<>(limit);
        String nextCursor = null;
        boolean hasMore = false;
        for (StreamEntry entry : entries) {
            T mapped = mapperFunction.apply(entry);
            if (!predicate.test(mapped)) {
                continue;
            }
            if (items.size() == limit) {
                hasMore = true;
                break;
            }
            items.add(mapped);
            nextCursor = entryIdOf(mapped);
        }
        if (!hasMore) {
            nextCursor = null;
        }
        return new StreamPage<>(List.copyOf(items), nextCursor, hasMore);
    }

    private String entryIdOf(Object value) {
        if (value instanceof DeadLetterEntry entry) {
            return entry.entryId();
        }
        if (value instanceof ReconciliationRecord record) {
            return record.entryId();
        }
        if (value instanceof DeadLetterArchiveRecord archiveRecord) {
            return archiveRecord.entryId();
        }
        return null;
    }

    private boolean matchesDeadLetter(DeadLetterEntry entry, DeadLetterQuery query) {
        return matches(query.entityName(), entry.operation().entityName())
                && matches(query.operationType(), entry.operation().type().name())
                && matches(query.entityId(), entry.operation().id())
                && matches(query.errorType(), entry.errorType());
    }

    private boolean matchesReconciliation(ReconciliationRecord record, ReconciliationQuery query) {
        return matches(query.status(), record.status())
                && matches(query.entityName(), record.entityName())
                && matches(query.operationType(), record.operationType())
                && matches(query.entityId(), record.entityId());
    }

    private boolean matchesArchive(DeadLetterArchiveRecord record, ReconciliationQuery query) {
        return matches(query.status(), record.status())
                && matches(query.entityName(), record.entityName())
                && matches(query.operationType(), record.operationType())
                && matches(query.entityId(), record.entityId());
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private DeadLetterArchiveRecord toArchiveRecord(StreamEntry entry) {
        Map<String, String> fields = Map.copyOf(entry.getFields());
        return new DeadLetterArchiveRecord(
                entry.getID().toString(),
                fields.getOrDefault("sourceEntryId", ""),
                fields.getOrDefault("status", ""),
                fields.getOrDefault("entity", fields.getOrDefault("deadLetterEntity", "")),
                fields.getOrDefault("operationType", fields.getOrDefault("type", "")),
                fields.getOrDefault("id", ""),
                parseLong(fields.get("version")),
                parseInstant(fields.get("archivedAt")),
                fields
        );
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        return Instant.parse(value);
    }
}
