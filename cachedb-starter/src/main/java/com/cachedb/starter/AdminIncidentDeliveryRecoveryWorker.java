package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.IncidentDeliveryDlqConfig;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;
import com.reactor.cachedb.core.queue.AdminIncidentSeverity;
import com.reactor.cachedb.core.queue.IncidentChannelSnapshot;
import com.reactor.cachedb.core.queue.IncidentDeliveryRecoverySnapshot;
import com.reactor.cachedb.core.queue.WorkerErrorCapture;
import com.reactor.cachedb.core.queue.WorkerErrorDetails;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

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

final class AdminIncidentDeliveryRecoveryWorker implements AutoCloseable {

    private final JedisPooled jedis;
    private final AdminIncidentDeliveryManager deliveryManager;
    private final IncidentDeliveryDlqConfig config;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong replayedCount = new AtomicLong();
    private final AtomicLong failedReplayCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();
    private final AtomicLong claimedCount = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorOrigin = new AtomicReference<>();
    private final AtomicReference<String> lastErrorStackTrace = new AtomicReference<>();

    AdminIncidentDeliveryRecoveryWorker(
            JedisPooled jedis,
            AdminIncidentDeliveryManager deliveryManager,
            IncidentDeliveryDlqConfig config
    ) {
        this.jedis = jedis;
        this.deliveryManager = deliveryManager;
        this.config = config;
        this.executorService = Executors.newFixedThreadPool(
                Math.max(1, config.workerThreads()),
                threadFactory(config)
        );
    }

    void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        ensureConsumerGroup();
        for (int index = 0; index < Math.max(1, config.workerThreads()); index++) {
            String consumerName = config.consumerNamePrefix() + "-" + index;
            executorService.submit(() -> runLoop(consumerName));
        }
    }

    IncidentDeliveryRecoverySnapshot snapshot() {
        return new IncidentDeliveryRecoverySnapshot(
                replayedCount.get(),
                failedReplayCount.get(),
                deadLetterCount.get(),
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

    private void ensureConsumerGroup() {
        if (!config.autoCreateConsumerGroup()) {
            return;
        }
        try {
            jedis.xgroupCreate(config.streamKey(), config.consumerGroup(), StreamEntryID.LAST_ENTRY, true);
        } catch (JedisDataException exception) {
            if (!exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private void runLoop(String consumerName) {
        XReadGroupParams params = new XReadGroupParams()
                .count(Math.max(1, config.batchSize()))
                .block((int) Math.max(1L, config.blockTimeoutMillis()));
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<Entry<String, List<StreamEntry>>> responses = config.claimAbandonedEntries()
                        ? claimAbandonedEntries(consumerName)
                        : List.of();
                if (!hasEntries(responses)) {
                    responses = jedis.xreadGroup(
                            config.consumerGroup(),
                            consumerName,
                            params,
                            Map.of(config.streamKey(), StreamEntryID.UNRECEIVED_ENTRY)
                    );
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
                captureError(exception);
            }
        }
    }

    private List<Entry<String, List<StreamEntry>>> claimAbandonedEntries(String consumerName) {
        Entry<StreamEntryID, List<StreamEntry>> claimed = jedis.xautoclaim(
                config.streamKey(),
                config.consumerGroup(),
                consumerName,
                config.claimIdleMillis(),
                new StreamEntryID("0-0"),
                new XAutoClaimParams().count(Math.max(1, config.claimBatchSize()))
        );
        List<Entry<String, List<StreamEntry>>> responses = List.of(Map.entry(
                config.streamKey(),
                claimed == null ? List.of() : claimed.getValue()
        ));
        if (hasEntries(responses)) {
            claimedCount.addAndGet(responses.get(0).getValue().size());
        }
        return responses;
    }

    private void processEntry(StreamEntry entry) {
        Map<String, String> fields = entry.getFields();
        String channel = fields.getOrDefault("channel", "");
        int attempts = parseInt(fields.get("attempts"), 1);
        AdminIncidentRecord record = toIncidentRecord(fields);
        boolean delivered = deliveryManager.replay(channel, record);
        if (delivered) {
            publishRecovery("REPLAYED", channel, record, attempts, fields);
            ackAndDelete(entry.getID());
            replayedCount.incrementAndGet();
            return;
        }
        if (attempts >= Math.max(1, config.maxReplayAttempts())) {
            publishRecovery("FAILED", channel, record, attempts, fields);
            ackAndDelete(entry.getID());
            failedReplayCount.incrementAndGet();
            return;
        }

        IncidentChannelSnapshot failedSnapshot = new IncidentChannelSnapshot(
                channel,
                0L,
                0L,
                0L,
                0L,
                System.currentTimeMillis(),
                fields.getOrDefault("lastErrorType", ""),
                fields.getOrDefault("lastErrorMessage", "")
        );
        deliveryManager.deadLetter(failedSnapshot, record, attempts + 1);
        ackAndDelete(entry.getID());
        deadLetterCount.incrementAndGet();
    }

    private void publishRecovery(
            String status,
            String channel,
            AdminIncidentRecord record,
            int attempts,
            Map<String, String> fields
    ) {
        LinkedHashMap<String, String> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("channel", channel);
        payload.put("attempts", String.valueOf(attempts));
        payload.put("entryId", record.entryId());
        payload.put("code", record.code());
        payload.put("description", record.description());
        payload.put("severity", record.severity().name());
        payload.put("source", record.source());
        payload.put("recordedAt", record.recordedAt().toString());
        payload.put("recoveredAt", Instant.now().toString());
        payload.put("lastErrorType", fields.getOrDefault("lastErrorType", ""));
        payload.put("lastErrorMessage", fields.getOrDefault("lastErrorMessage", ""));
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            payload.put("field." + entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        jedis.xadd(config.recoveryStreamKey(), XAddParams.xAddParams(), payload);
    }

    private AdminIncidentRecord toIncidentRecord(Map<String, String> fields) {
        LinkedHashMap<String, String> recordFields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey().startsWith("field.")) {
                recordFields.put(entry.getKey().substring("field.".length()), entry.getValue());
            }
        }
        return new AdminIncidentRecord(
                fields.getOrDefault("entryId", ""),
                fields.getOrDefault("code", ""),
                fields.getOrDefault("description", ""),
                AdminIncidentSeverity.valueOf(fields.getOrDefault("severity", AdminIncidentSeverity.WARNING.name())),
                fields.getOrDefault("source", ""),
                Instant.parse(fields.getOrDefault("recordedAt", Instant.now().toString())),
                Map.copyOf(recordFields)
        );
    }

    private void ackAndDelete(StreamEntryID id) {
        jedis.xack(config.streamKey(), config.consumerGroup(), id);
        jedis.xdel(config.streamKey(), id);
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

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
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

    @Override
    public void close() {
        running.set(false);
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private ThreadFactory threadFactory(IncidentDeliveryDlqConfig config) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, config.consumerNamePrefix() + "-recovery-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
