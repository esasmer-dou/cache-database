package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.WriteRetryPolicy;
import com.reactor.cachedb.core.queue.DeadLetterRecoverySnapshot;
import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.WorkerErrorCapture;
import com.reactor.cachedb.core.queue.WorkerErrorDetails;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.core.queue.WriteBehindFailureRecord;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisDeadLetterRecoveryWorker implements AutoCloseable {

    private final JedisPooled jedis;
    private final WriteBehindFlusher flusher;
    private final DeadLetterRecoveryConfig config;
    private final WriteBehindConfigAdapter configAdapter;
    private final RedisWriteOperationMapper mapper;
    private final RedisKeyStrategy keyStrategy;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong replayedCount = new AtomicLong();
    private final AtomicLong staleSkippedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong claimedCount = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorOrigin = new AtomicReference<>();
    private final AtomicReference<String> lastErrorStackTrace = new AtomicReference<>();

    public RedisDeadLetterRecoveryWorker(
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
        this.configAdapter = new WriteBehindConfigAdapter(deadLetterStreamKey);
        this.executorService = Executors.newFixedThreadPool(config.workerThreads(), threadFactory(config));
    }

    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        ensureConsumerGroup();
        for (int index = 0; index < config.workerThreads(); index++) {
            String consumerName = config.consumerNamePrefix() + "-" + index;
            executorService.submit(() -> runLoop(consumerName));
        }
    }

    private void ensureConsumerGroup() {
        if (!config.autoCreateConsumerGroup()) {
            return;
        }
        try {
            jedis.xgroupCreate(configAdapter.deadLetterStreamKey(), config.consumerGroup(), StreamEntryID.LAST_ENTRY, true);
        } catch (JedisDataException exception) {
            if (!exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private void runLoop(String consumerName) {
        XReadGroupParams params = new XReadGroupParams()
                .count(config.claimBatchSize())
                .block((int) config.blockTimeoutMillis());
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<Entry<String, List<StreamEntry>>> responses = claimAbandonedEntries(consumerName);
                if (!hasEntries(responses)) {
                    responses = readNewEntries(consumerName, params);
                }

                if (!hasEntries(responses)) {
                    sleepQuietly(config.idleSleepMillis());
                    continue;
                }

                for (Entry<String, List<StreamEntry>> response : responses) {
                    for (StreamEntry entry : response.getValue()) {
                        processEntry(entry);
                    }
                }
            } catch (RuntimeException exception) {
                if (isTransientRedisTimeout(exception)) {
                    sleepQuietly(config.replayBackoffMillis());
                    continue;
                }
                captureError(exception);
                sleepQuietly(config.replayBackoffMillis());
            }
        }
    }

    private List<Entry<String, List<StreamEntry>>> claimAbandonedEntries(String consumerName) {
        Entry<StreamEntryID, List<StreamEntry>> claimed;
        try {
            claimed = jedis.xautoclaim(
                    configAdapter.deadLetterStreamKey(),
                    config.consumerGroup(),
                    consumerName,
                    config.claimIdleMillis(),
                    new StreamEntryID("0-0"),
                    new XAutoClaimParams().count(config.claimBatchSize())
            );
        } catch (JedisDataException exception) {
            if (isMissingConsumerGroup(exception)) {
                ensureConsumerGroup();
                return List.of();
            }
            throw exception;
        }
        List<Entry<String, List<StreamEntry>>> responses = List.of(Map.entry(
                configAdapter.deadLetterStreamKey(),
                claimed == null ? List.of() : claimed.getValue()
        ));
        if (hasEntries(responses)) {
            claimedCount.addAndGet(responses.get(0).getValue().size());
        }
        return responses;
    }

    private List<Entry<String, List<StreamEntry>>> readNewEntries(String consumerName, XReadGroupParams params) {
        try {
            return jedis.xreadGroup(
                    config.consumerGroup(),
                    consumerName,
                    params,
                    Map.of(configAdapter.deadLetterStreamKey(), StreamEntryID.UNRECEIVED_ENTRY)
            );
        } catch (JedisDataException exception) {
            if (isMissingConsumerGroup(exception)) {
                ensureConsumerGroup();
                return List.of();
            }
            throw exception;
        }
    }

    private void processEntry(StreamEntry entry) {
        QueuedWriteOperation operation = mapper.fromBody(entry.getFields());
        WriteBehindFailureRecord failureRecord = toFailureRecord(entry.getFields(), operation);

        if (isStale(operation)) {
            publishReconciliation("STALE_SKIPPED", failureRecord, null, 0);
            resolveDeadLetter(entry, "STALE_SKIPPED", "");
            staleSkippedCount.incrementAndGet();
            return;
        }

        WriteRetryPolicy retryPolicy = RedisRetryPolicyResolver.resolve(
                config.maxReplayRetries(),
                config.replayBackoffMillis(),
                config.retryOverrides(),
                operation
        );

        int attempts = 0;
        while (attempts <= retryPolicy.maxRetries()) {
            try {
                flusher.flush(operation);
                publishReconciliation("REPLAYED", failureRecord, null, attempts);
                resolveDeadLetter(entry, "REPLAYED", "");
                replayedCount.incrementAndGet();
                return;
            } catch (SQLException | RuntimeException exception) {
                captureError(exception);
                attempts++;
                if (attempts > retryPolicy.maxRetries()) {
                    publishReconciliation("FAILED", failureRecord, exception, attempts);
                    jedis.xack(configAdapter.deadLetterStreamKey(), config.consumerGroup(), entry.getID());
                    failedCount.incrementAndGet();
                    return;
                }
                sleepQuietly(retryPolicy.backoffMillis());
            }
        }
    }

    private WriteBehindFailureRecord toFailureRecord(Map<String, String> fields, QueuedWriteOperation operation) {
        return new WriteBehindFailureRecord(
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
                Instant.now(),
                parseInt(fields.get("deadLetterAttempts")),
                Map.copyOf(fields)
        );
    }

    private void publishReconciliation(
            String status,
            WriteBehindFailureRecord failureRecord,
            Exception replayException,
            int replayAttempts
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

    private void resolveDeadLetter(StreamEntry entry, String status, String note) {
        RedisRecoveryStreams.archiveResolved(jedis, config, entry.getFields(), entry.getID().toString(), status, note);
        jedis.xack(configAdapter.deadLetterStreamKey(), config.consumerGroup(), entry.getID());
        jedis.xdel(configAdapter.deadLetterStreamKey(), entry.getID());
    }

    private boolean isStale(QueuedWriteOperation operation) {
        String currentVersion = jedis.get(keyStrategy.versionKey(operation.redisNamespace(), operation.id()));
        if (currentVersion == null || currentVersion.isBlank()) {
            return false;
        }
        return Long.parseLong(currentVersion) > operation.version();
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private boolean hasEntries(List<Entry<String, List<StreamEntry>>> responses) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }
        for (Entry<String, List<StreamEntry>> response : responses) {
            if (response.getValue() != null && !response.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isMissingConsumerGroup(JedisDataException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("NOGROUP");
    }

    private boolean isTransientRedisTimeout(RuntimeException exception) {
        return RedisTransientConnectionFailures.isTransient(exception);
    }

    private void captureError(Exception exception) {
        WorkerErrorDetails details = WorkerErrorCapture.capture(exception);
        lastErrorAtEpochMillis.set(System.currentTimeMillis());
        lastErrorType.set(details.errorType());
        lastErrorMessage.set(details.errorMessage());
        lastErrorRootType.set(details.rootErrorType());
        lastErrorRootMessage.set(details.rootErrorMessage());
        lastErrorOrigin.set(details.origin());
        lastErrorStackTrace.set(details.stackTrace());
    }

    private void sleepQuietly(long sleepMillis) {
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private ThreadFactory threadFactory(DeadLetterRecoveryConfig config) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, config.consumerNamePrefix() + "-thread-" + counter.incrementAndGet());
            thread.setDaemon(config.daemonThreads());
            return thread;
        };
    }

    @Override
    public void close() {
        running.set(false);
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(config.shutdownAwaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public DeadLetterRecoverySnapshot snapshot() {
        return new DeadLetterRecoverySnapshot(
                replayedCount.get(),
                staleSkippedCount.get(),
                failedCount.get(),
                claimedCount.get(),
                lastErrorAtEpochMillis.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                lastErrorRootType.get(),
                lastErrorRootMessage.get(),
                lastErrorOrigin.get(),
                lastErrorStackTrace.get()
        );
    }

    private record WriteBehindConfigAdapter(String deadLetterStreamKey) {
    }
}
