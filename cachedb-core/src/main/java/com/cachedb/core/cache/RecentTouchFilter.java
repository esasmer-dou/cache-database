package com.reactor.cachedb.core.cache;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Fixed-size collision-safe approximate LRU touch filter; collisions never suppress another ID. */
public final class RecentTouchFilter {
    private final AtomicReferenceArray<Touch> slots;

    public RecentTouchFilter(int capacity) {
        if (capacity < 1 || capacity > 65_536) throw new IllegalArgumentException("Touch capacity must be 1..65536");
        slots = new AtomicReferenceArray<>(capacity);
    }

    public boolean acquire(Object id, long debounceMillis) {
        Objects.requireNonNull(id, "id");
        if (debounceMillis <= 0) return true;
        int slot = Math.floorMod(id.hashCode(), slots.length());
        long now = System.nanoTime();
        Touch previous = slots.get(slot);
        if (previous != null && previous.id.equals(id) && now - previous.atNanos < debounceMillis * 1_000_000L)
            return false;
        Touch next = new Touch(id, now);
        if (slots.compareAndSet(slot, previous, next)) return true;
        // A competing/colliding key may have replaced the slot. Recording extra work is safer than a false suppression.
        return true;
    }

    public void forget(Object id) {
        int slot = Math.floorMod(id.hashCode(), slots.length());
        Touch previous = slots.get(slot);
        if (previous != null && previous.id.equals(id)) slots.compareAndSet(slot, previous, null);
    }

    private record Touch(Object id, long atNanos) {}
}
