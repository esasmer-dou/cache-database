package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.IncidentWebhookConfig;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AdminIncidentWebhookNotifier implements AutoCloseable {

    private final IncidentWebhookConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<AdminIncidentRecord> queue;
    private final HttpClient httpClient;
    private ExecutorService executor;

    public AdminIncidentWebhookNotifier(IncidentWebhookConfig config) {
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.queueCapacity()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, config.connectTimeoutMillis())))
                .build();
    }

    public void start() {
        if (!isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newFixedThreadPool(Math.max(1, config.workerThreads()), new WebhookThreadFactory());
        for (int index = 0; index < Math.max(1, config.workerThreads()); index++) {
            executor.submit(this::runLoop);
        }
    }

    public void enqueue(AdminIncidentRecord record) {
        if (!isEnabled() || record == null) {
            return;
        }
        if (!queue.offer(record)) {
            queue.poll();
            queue.offer(record);
        }
    }

    private boolean isEnabled() {
        return config.enabled() && config.endpointUrl() != null && !config.endpointUrl().isBlank();
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                AdminIncidentRecord record = queue.poll(500, TimeUnit.MILLISECONDS);
                if (record != null) {
                    deliver(record);
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deliver(AdminIncidentRecord record) {
        String payload = toJson(record);
        for (int attempt = 0; attempt <= Math.max(0, config.maxRetries()); attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(config.endpointUrl()))
                        .timeout(Duration.ofMillis(Math.max(1, config.requestTimeoutMillis())))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                if (config.headerName() != null && !config.headerName().isBlank()) {
                    requestBuilder.header(config.headerName(), Objects.requireNonNullElse(config.headerValue(), ""));
                }
                HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            sleepQuietly(config.retryBackoffMillis());
        }
    }

    private String toJson(AdminIncidentRecord record) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"eventType\":\"ADMIN_INCIDENT\",");
        builder.append("\"entryId\":\"").append(escapeJson(record.entryId())).append("\",");
        builder.append("\"code\":\"").append(escapeJson(record.code())).append("\",");
        builder.append("\"description\":\"").append(escapeJson(record.description())).append("\",");
        builder.append("\"severity\":\"").append(record.severity().name()).append("\",");
        builder.append("\"source\":\"").append(escapeJson(record.source())).append("\",");
        builder.append("\"recordedAt\":\"").append(record.recordedAt()).append("\",");
        builder.append("\"fields\":").append(renderFields(record.fields())).append("}");
        return builder.toString();
    }

    private String renderFields(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(Objects.requireNonNullElse(entry.getValue(), ""))).append("\"");
        }
        builder.append('}');
        return builder.toString();
    }

    private String escapeJson(String value) {
        return Objects.requireNonNullElse(value, "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
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
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static final class WebhookThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cachedb-incident-webhook");
            thread.setDaemon(true);
            return thread;
        }
    }
}
