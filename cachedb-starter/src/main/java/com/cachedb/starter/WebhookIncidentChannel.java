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

final class WebhookIncidentChannel extends AbstractIncidentChannel {

    private final IncidentWebhookConfig config;
    private final HttpClient httpClient;

    WebhookIncidentChannel(IncidentWebhookConfig config) {
        super("webhook");
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, config.connectTimeoutMillis())))
                .build();
    }

    @Override
    public boolean deliver(AdminIncidentRecord record) {
        if (!config.enabled() || config.endpointUrl() == null || config.endpointUrl().isBlank()) {
            markDropped();
            return false;
        }
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
                    markDelivered();
                    return true;
                }
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    markFailed(interruptedException);
                    return false;
                }
                markFailed((Exception) exception);
            }
            sleepQuietly(config.retryBackoffMillis());
        }
        return false;
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
}
