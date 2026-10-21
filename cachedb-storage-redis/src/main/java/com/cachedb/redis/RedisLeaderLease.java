package com.reactor.cachedb.redis;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

public final class RedisLeaderLease implements AutoCloseable {

    private static final String RENEW_SCRIPT =
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;

    private static final String RELEASE_SCRIPT =
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedisClientOperations jedis;
    private final boolean enabled;
    private final String leaseKey;
    private final String ownerId;
    private final long ttlMillis;
    private final long renewIntervalMillis;
    private boolean leader;
    private long lastLeaseRefreshAt;

    private RedisLeaderLease(
            JedisPooled jedis,
            boolean enabled,
            String leaseKey,
            String ownerId,
            long ttlMillis,
            long renewIntervalMillis) {
        this(
                JedisPooledOperations.of(jedis),
                enabled,
                leaseKey,
                ownerId,
                ttlMillis,
                renewIntervalMillis);
    }

    private RedisLeaderLease(
            RedisClientOperations jedis,
            boolean enabled,
            String leaseKey,
            String ownerId,
            long ttlMillis,
            long renewIntervalMillis) {
        this.jedis = jedis;
        this.enabled = enabled;
        this.leaseKey = leaseKey;
        this.ownerId = ownerId;
        this.ttlMillis = ttlMillis;
        this.renewIntervalMillis = renewIntervalMillis;
    }

    public static RedisLeaderLease disabled() {
        return new RedisLeaderLease((RedisClientOperations) null, false, "", "", 0L, 0L);
    }

    public static RedisLeaderLease create(
            JedisPooled jedis,
            String leaseKey,
            String ownerId,
            long ttlMillis,
            long renewIntervalMillis) {
        return create(
                JedisPooledOperations.of(jedis), leaseKey, ownerId, ttlMillis, renewIntervalMillis);
    }

    public static RedisLeaderLease create(
            RedisClientOperations jedis,
            String leaseKey,
            String ownerId,
            long ttlMillis,
            long renewIntervalMillis) {
        boolean enabled =
                jedis != null
                        && leaseKey != null
                        && !leaseKey.isBlank()
                        && ownerId != null
                        && !ownerId.isBlank()
                        && ttlMillis > 0L;
        long safeRenewInterval =
                renewIntervalMillis > 0L
                        ? Math.min(renewIntervalMillis, Math.max(250L, ttlMillis / 2))
                        : Math.max(250L, ttlMillis / 2);
        return new RedisLeaderLease(
                jedis,
                enabled,
                leaseKey == null ? "" : leaseKey,
                ownerId == null ? "" : ownerId,
                ttlMillis,
                safeRenewInterval);
    }

    private final java.util.concurrent.locks.ReentrantLock lock =
            new java.util.concurrent.locks.ReentrantLock();

    public boolean tryAcquireOrRenewLeadership() {
        lock.lock();
        try {
            return acquireOrRenewLeadership();
        } finally {
            lock.unlock();
        }
    }

    private boolean acquireOrRenewLeadership() {
        if (!enabled) {
            return true;
        }
        try {
            long now = System.currentTimeMillis();
            if (leader && (now - lastLeaseRefreshAt) < renewIntervalMillis) {
                return true;
            }
            if (leader && renew(now)) {
                return true;
            }
            return acquire(now);
        } catch (RuntimeException ignored) {
            leader = false;
            lastLeaseRefreshAt = 0L;
            return false;
        }
    }

    public boolean isLeader() {
        lock.lock();
        try {
            return !enabled || leader;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            release();
        } finally {
            lock.unlock();
        }
    }

    private void release() {
        if (!enabled || !leader) {
            return;
        }
        try {
            jedis.eval(RELEASE_SCRIPT, 1, leaseKey, ownerId);
        } catch (RuntimeException ignored) {
            // Best-effort lease cleanup should not surface during shutdown.
        } finally {
            leader = false;
            lastLeaseRefreshAt = 0L;
        }
    }

    private boolean renew(long now) {
        Object result = jedis.eval(RENEW_SCRIPT, 1, leaseKey, ownerId, String.valueOf(ttlMillis));
        long renewed = result instanceof Number number ? number.longValue() : 0L;
        if (renewed > 0L) {
            lastLeaseRefreshAt = now;
            return true;
        }
        leader = false;
        lastLeaseRefreshAt = 0L;
        return false;
    }

    private boolean acquire(long now) {
        String response =
                jedis.commands().set(leaseKey, ownerId, SetParams.setParams().nx().px(ttlMillis));
        leader = "OK".equalsIgnoreCase(response);
        if (leader) {
            lastLeaseRefreshAt = now;
        }
        return leader;
    }
}
