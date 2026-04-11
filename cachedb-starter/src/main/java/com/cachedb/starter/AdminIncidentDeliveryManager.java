package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.IncidentDeliveryDlqConfig;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;
import com.reactor.cachedb.core.queue.IncidentChannelSnapshot;
import com.reactor.cachedb.core.queue.IncidentDeliverySnapshot;
import com.reactor.cachedb.core.queue.IncidentDeliveryRecoverySnapshot;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AdminIncidentDeliveryManager implements AutoCloseable {

    private final boolean enabled;
    private final AdminMonitoringConfig config;
    private final JedisPooled jedis;
    private final BlockingQueue<AdminIncidentRecord> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong enqueuedCount = new AtomicLong();
    private final AtomicLong dequeuedCount = new AtomicLong();
    private final AtomicLong droppedBeforeDeliveryCount = new AtomicLong();
    private final AtomicLong lastEnqueuedAtEpochMillis = new AtomicLong();
    private final List<IncidentChannel> channels;
    private final Map<String, IncidentChannel> channelsByName;
    private final IncidentDeliveryDlqConfig deliveryDlqConfig;
    private final int workerThreads;
    private final AdminIncidentDeliveryRecoveryWorker recoveryWorker;
    private ExecutorService executor;

    public AdminIncidentDeliveryManager(AdminMonitoringConfig config, JedisPooled jedis) {
        this(true, config, jedis);
    }

    private AdminIncidentDeliveryManager(boolean enabled, AdminMonitoringConfig config, JedisPooled jedis) {
        this.enabled = enabled;
        this.config = config;
        this.jedis = jedis;
        int queueCapacity = Math.max(
                8,
                Math.max(config.incidentWebhook().queueCapacity(), config.incidentDeliveryQueueFloor())
        );
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.workerThreads = Math.max(1, config.incidentWebhook().workerThreads());
        ArrayList<IncidentChannel> builtChannels = enabled ? buildChannels(config, jedis) : new ArrayList<>();
        this.channels = List.copyOf(builtChannels);
        this.channelsByName = builtChannels.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        channel -> channel.snapshot().channelName(),
                        channel -> channel
                ));
        this.deliveryDlqConfig = config.incidentDeliveryDlq();
        this.recoveryWorker = enabled && deliveryDlqConfig.enabled() && !channels.isEmpty()
                ? new AdminIncidentDeliveryRecoveryWorker(jedis, this, deliveryDlqConfig)
                : null;
    }

    static AdminIncidentDeliveryManager disabled(AdminMonitoringConfig config) {
        return new AdminIncidentDeliveryManager(false, config, null);
    }

    public void start() {
        if (!enabled || channels.isEmpty() || !running.compareAndSet(false, true)) {
            return;
        }
        if (recoveryWorker != null) {
            recoveryWorker.start();
        }
        executor = Executors.newFixedThreadPool(workerThreads, new DeliveryThreadFactory());
        for (int index = 0; index < workerThreads; index++) {
            executor.submit(this::runLoop);
        }
    }

    public void enqueue(AdminIncidentRecord record) {
        if (!enabled || channels.isEmpty() || record == null) {
            return;
        }
        enqueuedCount.incrementAndGet();
        lastEnqueuedAtEpochMillis.set(System.currentTimeMillis());
        if (!queue.offer(record)) {
            queue.poll();
            droppedBeforeDeliveryCount.incrementAndGet();
            queue.offer(record);
        }
    }

    public IncidentDeliverySnapshot snapshot() {
        if (!enabled) {
            return new IncidentDeliverySnapshot(
                    0L,
                    0L,
                    0L,
                    0L,
                    List.of(),
                    new IncidentDeliveryRecoverySnapshot(0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null)
            );
        }
        return new IncidentDeliverySnapshot(
                enqueuedCount.get(),
                dequeuedCount.get(),
                droppedBeforeDeliveryCount.get(),
                lastEnqueuedAtEpochMillis.get(),
                channels.stream().map(IncidentChannel::snapshot).toList(),
                recoveryWorker == null
                        ? new IncidentDeliveryRecoverySnapshot(0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null)
                        : recoveryWorker.snapshot()
        );
    }

    boolean replay(String channelName, AdminIncidentRecord record) {
        IncidentChannel channel = channelsByName.get(channelName);
        if (channel == null) {
            return false;
        }
        return deliverToChannel(channel, record, false);
    }

    private ArrayList<IncidentChannel> buildChannels(AdminMonitoringConfig config, JedisPooled jedis) {
        ArrayList<IncidentChannel> built = new ArrayList<>();
        if (config.incidentWebhook().enabled()) {
            built.add(new WebhookIncidentChannel(config.incidentWebhook()));
        }
        if (config.incidentQueue().enabled()) {
            built.add(new QueueIncidentChannel(jedis, config));
        }
        if (config.incidentEmail().enabled()) {
            built.add(new SmtpIncidentChannel(config));
        }
        return built;
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                AdminIncidentRecord record = queue.poll(
                        Math.max(1L, config.incidentDeliveryPollTimeoutMillis()),
                        TimeUnit.MILLISECONDS
                );
                if (record == null) {
                    continue;
                }
                dequeuedCount.incrementAndGet();
                for (IncidentChannel channel : channels) {
                    deliverToChannel(channel, record, true);
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean deliverToChannel(IncidentChannel channel, AdminIncidentRecord record, boolean deadLetterOnFailure) {
        boolean delivered = channel.deliver(record);
        if (!delivered && deadLetterOnFailure) {
            deadLetter(channel.snapshot(), record, 1);
        }
        return delivered;
    }

    void deadLetter(IncidentChannelSnapshot snapshot, AdminIncidentRecord record, int attempts) {
        if (!deliveryDlqConfig.enabled()) {
            return;
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("channel", snapshot.channelName());
        fields.put("attempts", String.valueOf(Math.max(1, attempts)));
        fields.put("entryId", record.entryId());
        fields.put("code", record.code());
        fields.put("description", record.description());
        fields.put("severity", record.severity().name());
        fields.put("source", record.source());
        fields.put("recordedAt", record.recordedAt().toString());
        fields.put("lastErrorType", nullSafe(snapshot.lastErrorType()));
        fields.put("lastErrorMessage", nullSafe(snapshot.lastErrorMessage()));
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            fields.put("field." + entry.getKey(), nullSafe(entry.getValue()));
        }
        jedis.xadd(deliveryDlqConfig.streamKey(), XAddParams.xAddParams(), fields);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (recoveryWorker != null) {
            recoveryWorker.close();
        }
        for (IncidentChannel channel : channels) {
            channel.close();
        }
    }

    private static final class DeliveryThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cachedb-incident-delivery");
            thread.setDaemon(true);
            return thread;
        }
    }
}
