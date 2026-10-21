package com.reactor.cachedb.core.testing;

import com.reactor.cachedb.core.repository.CacheIdGenerator;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Predictable, session-local fixture IDs. Never use these as production identifiers. */
final class InMemoryIdGenerator implements CacheIdGenerator {
    private static final char[] BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final Clock clock;
    private final AtomicLong counter = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    InMemoryIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public UUID nextUuid() {
        return new UUID(0x4000L, Long.MIN_VALUE | counter.incrementAndGet());
    }

    public String nextUlid() {
        char[] value = new char[26];
        long time = clock.millis();
        if (time < 0 || time >= (1L << 48))
            throw new IllegalStateException("ULID fixture timestamp is outside 48-bit range");
        for (int i = 9; i >= 0; i--) {
            value[i] = BASE32[(int) (time & 31)];
            time >>>= 5;
        }
        long sequence = counter.incrementAndGet();
        for (int i = 25; i >= 10; i--) {
            value[i] = BASE32[(int) (sequence & 31)];
            sequence >>>= 5;
        }
        return new String(value);
    }

    public long nextSequence(String name, int allocationSize) {
        if (name == null || !validName(name) || allocationSize < 1 || allocationSize > 10000)
            throw new IllegalArgumentException("Invalid test sequence name/allocation size");
        return sequences.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static boolean validName(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '_'
                    && c != '.'
                    && c != ':'
                    && c != '-'
                    && (c < 'A' || c > 'Z')
                    && (c < 'a' || c > 'z')
                    && (c < '0' || c > '9')) return false;
        }
        return true;
    }
}
