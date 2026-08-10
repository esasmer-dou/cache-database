package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.repository.CacheIdGenerator;
import redis.clients.jedis.JedisPooled;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RedisCacheIdGenerator implements CacheIdGenerator {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final String SAFE_NAME = "[A-Za-z0-9_.:-]+";

    private final JedisPooled jedis;
    private final String keyPrefix;
    private final ConcurrentHashMap<String, SequenceBlock> sequences = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Object ulidLock = new Object();
    private final byte[] lastRandom = new byte[10];
    private long lastTimestamp = -1L;

    public RedisCacheIdGenerator(JedisPooled jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        if (keyPrefix == null || !keyPrefix.matches(SAFE_NAME)) {
            throw new IllegalArgumentException("Invalid CacheDB id key prefix: " + keyPrefix);
        }
        this.keyPrefix = keyPrefix;
    }

    @Override
    public UUID nextUuid() {
        return UUID.randomUUID();
    }

    @Override
    public String nextUlid() {
        synchronized (ulidLock) {
            long timestamp = System.currentTimeMillis();
            if (timestamp > lastTimestamp) {
                random.nextBytes(lastRandom);
                lastTimestamp = timestamp;
            } else {
                timestamp = lastTimestamp;
                incrementRandom();
            }
            return encodeUlid(timestamp, lastRandom);
        }
    }

    @Override
    public long nextSequence(String sequenceName, int allocationSize) {
        if (sequenceName == null || !sequenceName.matches(SAFE_NAME)) {
            throw new IllegalArgumentException("Invalid CacheDB sequence name: " + sequenceName);
        }
        if (allocationSize <= 0 || allocationSize > 10_000) {
            throw new IllegalArgumentException("allocationSize must be between 1 and 10000");
        }
        return sequences.computeIfAbsent(sequenceName, ignored -> new SequenceBlock())
                .next(jedis, keyPrefix + ":" + sequenceName, allocationSize);
    }

    private void incrementRandom() {
        for (int index = lastRandom.length - 1; index >= 0; index--) {
            lastRandom[index]++;
            if (lastRandom[index] != 0) {
                return;
            }
        }
        do {
            Thread.onSpinWait();
        } while (System.currentTimeMillis() <= lastTimestamp);
        lastTimestamp = System.currentTimeMillis();
        random.nextBytes(lastRandom);
    }

    private static String encodeUlid(long timestamp, byte[] randomness) {
        char[] output = new char[26];
        long value = timestamp;
        for (int index = 9; index >= 0; index--) {
            output[index] = CROCKFORD[(int) (value & 31L)];
            value >>>= 5;
        }
        int buffer = 0;
        int bits = 0;
        int outputIndex = 10;
        for (byte randomByte : randomness) {
            buffer = (buffer << 8) | (randomByte & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                output[outputIndex++] = CROCKFORD[(buffer >>> bits) & 31];
            }
        }
        return new String(output);
    }

    private static final class SequenceBlock {
        private final AtomicLong next = new AtomicLong(1L);
        private volatile long end;

        long next(JedisPooled jedis, String key, int allocationSize) {
            while (true) {
                long candidate = next.getAndIncrement();
                if (candidate <= end) {
                    return candidate;
                }
                synchronized (this) {
                    if (next.get() - 1L <= end) {
                        continue;
                    }
                    long allocatedEnd = jedis.incrBy(key, allocationSize);
                    long allocatedStart = allocatedEnd - allocationSize + 1L;
                    next.set(allocatedStart + 1L);
                    end = allocatedEnd;
                    return allocatedStart;
                }
            }
        }
    }
}
