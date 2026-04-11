package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.queue.AdminIncidentRecord;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.util.LinkedHashMap;

final class QueueIncidentChannel extends AbstractIncidentChannel {

    private final JedisPooled jedis;
    private final AdminMonitoringConfig config;

    QueueIncidentChannel(JedisPooled jedis, AdminMonitoringConfig config) {
        super("queue");
        this.jedis = jedis;
        this.config = config;
    }

    @Override
    public boolean deliver(AdminIncidentRecord record) {
        for (int attempt = 0; attempt <= Math.max(0, config.incidentQueue().maxRetries()); attempt++) {
            try {
                LinkedHashMap<String, String> fields = new LinkedHashMap<>();
                fields.put("entryId", record.entryId());
                fields.put("code", record.code());
                fields.put("description", record.description());
                fields.put("severity", record.severity().name());
                fields.put("source", record.source());
                fields.put("recordedAt", record.recordedAt().toString());
                fields.putAll(record.fields());
                jedis.xadd(config.incidentQueue().streamKey(), XAddParams.xAddParams(), fields);
                if (config.incidentQueue().maxLength() > 0) {
                    jedis.xtrim(config.incidentQueue().streamKey(), config.incidentQueue().maxLength(), true);
                }
                markDelivered();
                return true;
            } catch (RuntimeException exception) {
                markFailed(exception);
            }
            sleepQuietly(config.incidentQueue().retryBackoffMillis());
        }
        return false;
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
