package com.reactor.cachedb.core.repository;

import java.util.Optional;
import java.util.function.Function;

/** Redis-only point lookup. NOT_CACHED never means the durable row is absent. */
public record HotLookup<T>(HotLookupStatus status, T value) {
    public HotLookup {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == HotLookupStatus.HIT && value == null) {
            throw new IllegalArgumentException("HIT requires a value");
        }
        if (status != HotLookupStatus.HIT && value != null) {
            throw new IllegalArgumentException(status + " must not carry a value");
        }
    }

    public static <T> HotLookup<T> hit(T value) {
        return new HotLookup<>(HotLookupStatus.HIT, value);
    }

    public static <T> HotLookup<T> notCached() {
        return new HotLookup<>(HotLookupStatus.NOT_CACHED, null);
    }

    public static <T> HotLookup<T> tombstoned() {
        return new HotLookup<>(HotLookupStatus.TOMBSTONED, null);
    }

    public static <T> HotLookup<T> outsidePolicy() {
        return new HotLookup<>(HotLookupStatus.OUTSIDE_HOT_POLICY, null);
    }

    public Optional<T> optional() {
        return Optional.ofNullable(value);
    }

    public boolean isHit() {
        return status == HotLookupStatus.HIT;
    }

    public boolean isNotCached() {
        return status == HotLookupStatus.NOT_CACHED;
    }

    public boolean isTombstoned() {
        return status == HotLookupStatus.TOMBSTONED;
    }

    public boolean isOutsideHotPolicy() {
        return status == HotLookupStatus.OUTSIDE_HOT_POLICY;
    }

    /** Maps a hit while preserving every non-hit status exactly. */
    public <R> HotLookup<R> map(Function<? super T, ? extends R> mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (!isHit()) {
            return new HotLookup<>(status, null);
        }
        R mapped = mapper.apply(value);
        if (mapped == null) {
            throw new IllegalArgumentException("mapper must not return null for a hot hit");
        }
        return HotLookup.hit(mapped);
    }

    public <X extends Throwable> T orElseThrow(
            Function<HotLookupStatus, ? extends X> exceptionFactory
    ) throws X {
        if (isHit()) {
            return value;
        }
        if (exceptionFactory == null) {
            throw new IllegalArgumentException("exceptionFactory must not be null");
        }
        X failure = exceptionFactory.apply(status);
        if (failure == null) {
            throw new IllegalArgumentException("exceptionFactory must not return null");
        }
        throw failure;
    }
}
