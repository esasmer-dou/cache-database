package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.HardLimitEntityPolicy;
import com.reactor.cachedb.core.config.HardLimitQueryPolicy;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.query.HardLimitQueryClass;
import com.reactor.cachedb.core.queue.RedisGuardrailSnapshot;
import com.reactor.cachedb.core.queue.RedisRuntimeProfileEvent;
import com.reactor.cachedb.core.queue.RedisRuntimeProfileSnapshot;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisProducerGuard {

    private final JedisPooled jedis;
    private final RedisGuardrailConfig config;
    private final WriteBehindConfig writeBehindConfig;
    private final RedisKeyStrategy keyStrategy;
    private final String diagnosticsStreamKey;
    private final long diagnosticsMaxLength;
    private final AtomicLong producerHighPressureDelayCount = new AtomicLong();
    private final AtomicLong producerCriticalPressureDelayCount = new AtomicLong();
    private final AtomicBoolean samplingInProgress = new AtomicBoolean(false);
    private final AtomicLong nextSampleAllowedAtEpochMillis = new AtomicLong();
    private final AtomicReference<RedisGuardrailSnapshot> lastSnapshot = new AtomicReference<>(
            new RedisGuardrailSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "NORMAL")
    );
    private final AtomicReference<RedisRuntimeProfileSnapshot> runtimeProfileSnapshot = new AtomicReference<>(
            new RedisRuntimeProfileSnapshot("STANDARD", "NORMAL", 0L, 0L, 0L, 0L, 0L)
    );
    private final AtomicReference<String> manualRuntimeProfileOverride = new AtomicReference<>();
    private final ArrayDeque<RedisRuntimeProfileEvent> recentRuntimeProfileEvents = new ArrayDeque<>();

    public RedisProducerGuard(
            JedisPooled jedis,
            RedisGuardrailConfig config,
            WriteBehindConfig writeBehindConfig,
            RedisKeyStrategy keyStrategy,
            String diagnosticsStreamKey,
            long diagnosticsMaxLength
    ) {
        this.jedis = jedis;
        this.config = config;
        this.writeBehindConfig = writeBehindConfig;
        this.keyStrategy = keyStrategy;
        this.diagnosticsStreamKey = diagnosticsStreamKey;
        this.diagnosticsMaxLength = diagnosticsMaxLength;
    }

    public void applyBackpressure() {
        if (!config.enabled() || !config.producerBackpressureEnabled()) {
            return;
        }
        RedisGuardrailSnapshot snapshot = sampleSafely();
        if ("CRITICAL".equals(snapshot.pressureLevel())) {
            producerCriticalPressureDelayCount.incrementAndGet();
            sleepQuietly(effectiveCriticalSleepMillis());
            return;
        }
        if ("WARN".equals(snapshot.pressureLevel())) {
            producerHighPressureDelayCount.incrementAndGet();
            sleepQuietly(effectiveHighSleepMillis());
        }
    }

    public RedisGuardrailSnapshot snapshot() {
        return sampleSafely();
    }

    public RedisRuntimeProfileSnapshot runtimeProfileSnapshot() {
        sampleSafely();
        return runtimeProfileSnapshot.get();
    }

    public CachePolicy effectiveCachePolicy(CachePolicy basePolicy) {
        RuntimeProfile profile = activeRuntimeProfile();
        return profile.apply(basePolicy);
    }

    public boolean shouldShedPageCacheWrites() {
        return config.shedPageCacheWritesOnHardLimit() && isHardLimitActive();
    }

    public boolean shouldShedPageCacheWrites(String namespace) {
        if (!isHardLimitActive()) {
            return false;
        }
        HardLimitEntityPolicy policy = entityPolicy(namespace);
        return policy != null ? policy.shedPageCacheWrites() : config.shedPageCacheWritesOnHardLimit();
    }

    public boolean shouldShedReadThroughCache() {
        return config.shedReadThroughCacheOnHardLimit() && isHardLimitActive();
    }

    public boolean shouldShedReadThroughCache(String namespace) {
        if (!isHardLimitActive()) {
            return false;
        }
        HardLimitEntityPolicy policy = entityPolicy(namespace);
        return policy != null ? policy.shedReadThroughCache() : config.shedReadThroughCacheOnHardLimit();
    }

    public boolean shouldShedHotSetTracking() {
        return config.shedHotSetTrackingOnHardLimit() && isHardLimitActive();
    }

    public boolean shouldShedHotSetTracking(String namespace) {
        if (!isHardLimitActive()) {
            return false;
        }
        HardLimitEntityPolicy policy = entityPolicy(namespace);
        return policy != null ? policy.shedHotSetTracking() : config.shedHotSetTrackingOnHardLimit();
    }

    public boolean shouldShedQueryIndexWrites() {
        return config.shedQueryIndexWritesOnHardLimit() && isHardLimitActive();
    }

    public boolean shouldShedQueryIndexWrites(String namespace) {
        if (!isHardLimitActive()) {
            return false;
        }
        HardLimitEntityPolicy policy = entityPolicy(namespace);
        return policy != null ? policy.shedQueryIndexWrites() : config.shedQueryIndexWritesOnHardLimit();
    }

    public boolean shouldShedQueryIndexReads() {
        return config.shedQueryIndexReadsOnHardLimit() && isHardLimitActive();
    }

    public boolean shouldShedQueryIndexReads(String namespace, java.util.Collection<HardLimitQueryClass> queryClasses) {
        if (!isHardLimitActive()) {
            return false;
        }
        for (HardLimitQueryClass queryClass : queryClasses) {
            HardLimitQueryPolicy queryPolicy = queryPolicy(namespace, queryClass);
            if (queryPolicy != null) {
                return queryPolicy.shedReads();
            }
        }
        HardLimitEntityPolicy entityPolicy = entityPolicy(namespace);
        if (entityPolicy != null) {
            return entityPolicy.shedQueryIndexReads();
        }
        return config.shedQueryIndexReadsOnHardLimit();
    }

    public boolean shouldShedPlannerLearning() {
        return config.shedPlannerLearningOnHardLimit() && isHardLimitActive();
    }

    public boolean shouldShedPlannerLearning(String namespace, java.util.Collection<HardLimitQueryClass> queryClasses) {
        if (!isHardLimitActive()) {
            return false;
        }
        for (HardLimitQueryClass queryClass : queryClasses) {
            HardLimitQueryPolicy queryPolicy = queryPolicy(namespace, queryClass);
            if (queryPolicy != null) {
                return queryPolicy.shedLearning();
            }
        }
        HardLimitEntityPolicy entityPolicy = entityPolicy(namespace);
        if (entityPolicy != null) {
            return entityPolicy.shedPlannerLearning();
        }
        return config.shedPlannerLearningOnHardLimit();
    }

    public boolean shouldAutoRecoverDegradedIndexes(String namespace) {
        HardLimitEntityPolicy policy = entityPolicy(namespace);
        if (policy != null) {
            return policy.autoRebuildIndexes();
        }
        return config.autoRecoverDegradedIndexesEnabled();
    }

    public synchronized List<RedisRuntimeProfileEvent> runtimeProfileEvents() {
        return List.copyOf(recentRuntimeProfileEvents);
    }

    public synchronized String manualRuntimeProfileOverride() {
        return manualRuntimeProfileOverride.get();
    }

    public synchronized void setManualRuntimeProfile(String profileName) {
        RuntimeProfile selected = RuntimeProfile.parse(profileName);
        manualRuntimeProfileOverride.set(selected.name());
        applyRuntimeProfileOverride(selected, "MANUAL_OVERRIDE");
    }

    public synchronized void clearManualRuntimeProfileOverride() {
        manualRuntimeProfileOverride.set(null);
        updateRuntimeProfile(lastSnapshot.get());
    }

    public synchronized Map<String, String> activeRuntimeProfileProperties() {
        RuntimeProfile profile = activeRuntimeProfile();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("profile", profile.name());
        values.put("mode", manualRuntimeProfileOverride.get() == null ? "AUTO" : "MANUAL");
        values.put("automaticSwitchingEnabled", String.valueOf(config.automaticRuntimeProfileSwitchingEnabled()));
        values.put("hotEntityLimitFactor", String.valueOf(profile.hotEntityLimitFactor));
        values.put("pageSizeFactor", String.valueOf(profile.pageSizeFactor));
        values.put("entityTtlCapSeconds", String.valueOf(profile.entityTtlCapSeconds));
        values.put("pageTtlCapSeconds", String.valueOf(profile.pageTtlCapSeconds));
        values.put("highSleepMillisBonus", String.valueOf(profile.highSleepMillisBonus));
        values.put("criticalSleepMillisBonus", String.valueOf(profile.criticalSleepMillisBonus));
        values.put("effectiveHighSleepMillis", String.valueOf(profile.adjustHighSleep(config.highSleepMillis())));
        values.put("effectiveCriticalSleepMillis", String.valueOf(profile.adjustCriticalSleep(config.criticalSleepMillis())));
        return values;
    }

    private RedisGuardrailSnapshot sampleNow(long now) {
        Object memoryInfoResponse = jedis.sendCommand(redis.clients.jedis.Protocol.Command.INFO, "memory");
        String memoryInfo = memoryInfoResponse instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(memoryInfoResponse);
        Map<String, String> memoryStats = parseInfo(memoryInfo);
        long usedMemory = parseLong(memoryStats.get("used_memory"));
        long usedMemoryPeak = parseLong(memoryStats.get("used_memory_peak"));
        long maxMemory = parseLong(memoryStats.get("maxmemory"));
        Map<String, String> compactionStats = jedis.hgetAll(keyStrategy.compactionStatsKey());
        long pendingCount = parseLong(compactionStats.get("pendingCount"));
        long payloadCount = parseLong(compactionStats.get("payloadCount"));
        long hardRejectedWriteCount = parseLong(compactionStats.get("hardRejectedWriteCount"));
        long backlog = RedisBacklogEstimator.estimateWriteBehindBacklog(jedis, keyStrategy, writeBehindConfig);

        String pressureLevel = "NORMAL";
        if (config.usedMemoryCriticalBytes() > 0 && usedMemory >= config.usedMemoryCriticalBytes()) {
            pressureLevel = "CRITICAL";
        } else if (config.writeBehindBacklogCriticalThreshold() > 0 && backlog >= config.writeBehindBacklogCriticalThreshold()) {
            pressureLevel = "CRITICAL";
        } else if (config.compactionPendingCriticalThreshold() > 0 && pendingCount >= config.compactionPendingCriticalThreshold()) {
            pressureLevel = "CRITICAL";
        } else if (config.usedMemoryWarnBytes() > 0 && usedMemory >= config.usedMemoryWarnBytes()) {
            pressureLevel = "WARN";
        } else if (config.writeBehindBacklogWarnThreshold() > 0 && backlog >= config.writeBehindBacklogWarnThreshold()) {
            pressureLevel = "WARN";
        } else if (config.compactionPendingWarnThreshold() > 0 && pendingCount >= config.compactionPendingWarnThreshold()) {
            pressureLevel = "WARN";
        }

        RedisGuardrailSnapshot snapshot = new RedisGuardrailSnapshot(
                usedMemory,
                usedMemoryPeak,
                maxMemory,
                backlog,
                pendingCount,
                payloadCount,
                hardRejectedWriteCount,
                producerHighPressureDelayCount.get(),
                producerCriticalPressureDelayCount.get(),
                now,
                pressureLevel
        );
        updateRuntimeProfile(snapshot);
        lastSnapshot.set(snapshot);
        return snapshot;
    }

    private RedisGuardrailSnapshot sampleSafely() {
        long now = System.currentTimeMillis();
        RedisGuardrailSnapshot current = lastSnapshot.get();
        long minSampleIntervalMillis = Math.max(1L, config.sampleIntervalMillis());
        long nextAllowedAt = nextSampleAllowedAtEpochMillis.get();
        if (nextAllowedAt > now) {
            return current;
        }
        if (!samplingInProgress.compareAndSet(false, true)) {
            return current;
        }
        try {
            RedisGuardrailSnapshot snapshot = sampleNow(now);
            nextSampleAllowedAtEpochMillis.set(now + minSampleIntervalMillis);
            return snapshot;
        } catch (RuntimeException ignored) {
            long failureCooldownMillis = Math.max(minSampleIntervalMillis, 1_000L);
            nextSampleAllowedAtEpochMillis.set(now + failureCooldownMillis);
            return current;
        } finally {
            samplingInProgress.set(false);
        }
    }

    private synchronized void updateRuntimeProfile(RedisGuardrailSnapshot snapshot) {
        RedisRuntimeProfileSnapshot current = runtimeProfileSnapshot.get();
        long normalSamples = "NORMAL".equals(snapshot.pressureLevel()) ? current.normalPressureSamples() + 1L : 0L;
        long warnSamples = "WARN".equals(snapshot.pressureLevel()) ? current.warnPressureSamples() + 1L : 0L;
        long criticalSamples = "CRITICAL".equals(snapshot.pressureLevel()) ? current.criticalPressureSamples() + 1L : 0L;
        RuntimeProfile activeProfile = RuntimeProfile.parse(current.activeProfile());
        String manualOverride = manualRuntimeProfileOverride.get();

        if (manualOverride != null && !manualOverride.isBlank()) {
            applyRuntimeProfileOverride(RuntimeProfile.parse(manualOverride), snapshot.pressureLevel());
            return;
        }

        if (config.automaticRuntimeProfileSwitchingEnabled()) {
            if ("CRITICAL".equals(snapshot.pressureLevel())
                    && criticalSamples >= Math.max(1, config.criticalSamplesToAggressive())) {
                activeProfile = RuntimeProfile.AGGRESSIVE;
            } else if ("WARN".equals(snapshot.pressureLevel())
                    && warnSamples >= Math.max(1, config.warnSamplesToBalanced())) {
                activeProfile = current.activeProfile().equals("AGGRESSIVE")
                        ? RuntimeProfile.AGGRESSIVE
                        : RuntimeProfile.BALANCED;
            } else if ("WARN".equals(snapshot.pressureLevel())
                    && current.activeProfile().equals("AGGRESSIVE")
                    && warnSamples >= Math.max(1, config.warnSamplesToDeescalateAggressive())) {
                activeProfile = RuntimeProfile.BALANCED;
            } else if ("NORMAL".equals(snapshot.pressureLevel())
                    && normalSamples >= Math.max(1, config.normalSamplesToStandard())) {
                activeProfile = RuntimeProfile.STANDARD;
            }
        }

        long switchCount = current.switchCount();
        long lastSwitchedAtEpochMillis = current.lastSwitchedAtEpochMillis();
        String previousProfile = current.activeProfile();
        if (!activeProfile.name().equals(current.activeProfile())) {
            switchCount++;
            lastSwitchedAtEpochMillis = snapshot.lastSampleAtEpochMillis();
            RedisRuntimeProfileEvent event = new RedisRuntimeProfileEvent(
                    previousProfile,
                    activeProfile.name(),
                    snapshot.pressureLevel(),
                    Instant.ofEpochMilli(snapshot.lastSampleAtEpochMillis()),
                    switchCount,
                    snapshot.usedMemoryBytes(),
                    snapshot.writeBehindBacklog(),
                    snapshot.compactionPendingCount()
            );
            recordRuntimeProfileEvent(event);
        }

        runtimeProfileSnapshot.set(new RedisRuntimeProfileSnapshot(
                activeProfile.name(),
                snapshot.pressureLevel(),
                switchCount,
                lastSwitchedAtEpochMillis,
                normalSamples,
                warnSamples,
                criticalSamples
        ));
    }

    private void applyRuntimeProfileOverride(RuntimeProfile selectedProfile, String pressureLevel) {
        RedisRuntimeProfileSnapshot current = runtimeProfileSnapshot.get();
        long switchCount = current.switchCount();
        long lastSwitchedAtEpochMillis = current.lastSwitchedAtEpochMillis();
        String previousProfile = current.activeProfile();
        long sampledAt = System.currentTimeMillis();
        if (!selectedProfile.name().equals(previousProfile)) {
            switchCount++;
            lastSwitchedAtEpochMillis = sampledAt;
            RedisGuardrailSnapshot snapshot = lastSnapshot.get();
            recordRuntimeProfileEvent(new RedisRuntimeProfileEvent(
                    previousProfile,
                    selectedProfile.name(),
                    pressureLevel,
                    Instant.ofEpochMilli(sampledAt),
                    switchCount,
                    snapshot.usedMemoryBytes(),
                    snapshot.writeBehindBacklog(),
                    snapshot.compactionPendingCount()
            ));
        }
        runtimeProfileSnapshot.set(new RedisRuntimeProfileSnapshot(
                selectedProfile.name(),
                pressureLevel,
                switchCount,
                lastSwitchedAtEpochMillis,
                current.normalPressureSamples(),
                current.warnPressureSamples(),
                current.criticalPressureSamples()
        ));
    }

    private RuntimeProfile activeRuntimeProfile() {
        return RuntimeProfile.parse(runtimeProfileSnapshot().activeProfile());
    }

    private boolean isHardLimitActive() {
        RedisGuardrailSnapshot snapshot = sampleSafely();
        if (config.writeBehindBacklogHardLimit() > 0
                && snapshot.writeBehindBacklog() >= config.writeBehindBacklogHardLimit()) {
            return true;
        }
        if (config.compactionPendingHardLimit() > 0
                && snapshot.compactionPendingCount() >= config.compactionPendingHardLimit()) {
            return true;
        }
        return config.compactionPayloadHardLimit() > 0
                && snapshot.compactionPayloadCount() >= config.compactionPayloadHardLimit();
    }

    private HardLimitEntityPolicy entityPolicy(String namespace) {
        for (HardLimitEntityPolicy policy : config.entityPolicies()) {
            if (policy.matches(namespace)) {
                return policy;
            }
        }
        return null;
    }

    private HardLimitQueryPolicy queryPolicy(String namespace, HardLimitQueryClass queryClass) {
        for (HardLimitQueryPolicy policy : config.queryPolicies()) {
            if (policy.matches(namespace, queryClass)) {
                return policy;
            }
        }
        return null;
    }

    private void recordRuntimeProfileEvent(RedisRuntimeProfileEvent event) {
        if (recentRuntimeProfileEvents.size() >= 64) {
            recentRuntimeProfileEvents.removeFirst();
        }
        recentRuntimeProfileEvents.addLast(event);
        if (diagnosticsStreamKey == null || diagnosticsStreamKey.isBlank()) {
            return;
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("source", "redis-runtime-profile");
        fields.put("note", event.fromProfile() + "->" + event.toProfile());
        fields.put("status", healthStatusForPressure(event.pressureLevel()));
        fields.put("recordedAt", event.switchedAt().toString());
        fields.put("eventType", "RUNTIME_PROFILE_SWITCH");
        fields.put("fromProfile", event.fromProfile());
        fields.put("toProfile", event.toProfile());
        fields.put("pressureLevel", event.pressureLevel());
        fields.put("switchCount", String.valueOf(event.switchCount()));
        fields.put("usedMemoryBytes", String.valueOf(event.usedMemoryBytes()));
        fields.put("writeBehindBacklog", String.valueOf(event.writeBehindBacklog()));
        fields.put("compactionPendingCount", String.valueOf(event.compactionPendingCount()));
        try {
            jedis.xadd(diagnosticsStreamKey, XAddParams.xAddParams(), fields);
            if (diagnosticsMaxLength > 0) {
                jedis.xtrim(diagnosticsStreamKey, diagnosticsMaxLength, true);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics should not block producer-path switching.
        }
    }

    private String healthStatusForPressure(String pressureLevel) {
        return switch (pressureLevel) {
            case "CRITICAL" -> "DOWN";
            case "WARN" -> "DEGRADED";
            default -> "UP";
        };
    }

    private long effectiveHighSleepMillis() {
        return activeRuntimeProfile().adjustHighSleep(config.highSleepMillis());
    }

    private long effectiveCriticalSleepMillis() {
        return activeRuntimeProfile().adjustCriticalSleep(config.criticalSleepMillis());
    }

    private Map<String, String> parseInfo(String info) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (info == null || info.isBlank()) {
            return values;
        }
        for (String line : info.split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private void sleepQuietly(long sleepMillis) {
        if (sleepMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private enum RuntimeProfile {
        STANDARD(1.0d, 1.0d, Integer.MAX_VALUE, Integer.MAX_VALUE, 0L, 0L),
        BALANCED(0.75d, 0.85d, 1_800, 600, 1L, 3L),
        AGGRESSIVE(0.55d, 0.70d, 900, 300, 3L, 6L);

        private final double hotEntityLimitFactor;
        private final double pageSizeFactor;
        private final int entityTtlCapSeconds;
        private final int pageTtlCapSeconds;
        private final long highSleepMillisBonus;
        private final long criticalSleepMillisBonus;

        RuntimeProfile(
                double hotEntityLimitFactor,
                double pageSizeFactor,
                int entityTtlCapSeconds,
                int pageTtlCapSeconds,
                long highSleepMillisBonus,
                long criticalSleepMillisBonus
        ) {
            this.hotEntityLimitFactor = hotEntityLimitFactor;
            this.pageSizeFactor = pageSizeFactor;
            this.entityTtlCapSeconds = entityTtlCapSeconds;
            this.pageTtlCapSeconds = pageTtlCapSeconds;
            this.highSleepMillisBonus = highSleepMillisBonus;
            this.criticalSleepMillisBonus = criticalSleepMillisBonus;
        }

        private CachePolicy apply(CachePolicy basePolicy) {
            return CachePolicy.builder()
                    .hotEntityLimit(Math.max(16, (int) Math.round(basePolicy.hotEntityLimit() * hotEntityLimitFactor)))
                    .pageSize(Math.max(10, (int) Math.round(basePolicy.pageSize() * pageSizeFactor)))
                    .lruEvictionEnabled(basePolicy.lruEvictionEnabled())
                    .entityTtlSeconds(basePolicy.entityTtlSeconds() <= 0L
                            ? 0L
                            : Math.min(basePolicy.entityTtlSeconds(), entityTtlCapSeconds))
                    .pageTtlSeconds(basePolicy.pageTtlSeconds() <= 0L
                            ? 0L
                            : Math.min(basePolicy.pageTtlSeconds(), pageTtlCapSeconds))
                    .build();
        }

        private long adjustHighSleep(long baseSleepMillis) {
            return baseSleepMillis + highSleepMillisBonus;
        }

        private long adjustCriticalSleep(long baseSleepMillis) {
            return baseSleepMillis + criticalSleepMillisBonus;
        }

        private static RuntimeProfile parse(String value) {
            return switch (value.toUpperCase()) {
                case "BALANCED" -> BALANCED;
                case "AGGRESSIVE" -> AGGRESSIVE;
                default -> STANDARD;
            };
        }
    }
}
