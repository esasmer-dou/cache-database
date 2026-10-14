package com.reactor.cachedb.spring.boot.snapshot;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** One ascending score index per partition; equal scores use Redis member order. */
public record SnapshotIndexSpec<R>(Function<R, String> partitionBy, ToDoubleFunction<R> scoreBy) {
    public SnapshotIndexSpec {
        Objects.requireNonNull(partitionBy, "partitionBy");
        Objects.requireNonNull(scoreBy, "scoreBy");
    }

    public static <R> SnapshotIndexSpec<R> of(
            Function<R, String> partitionBy, ToDoubleFunction<R> scoreBy) {
        return new SnapshotIndexSpec<>(partitionBy, scoreBy);
    }

    static String partition(String value) {
        if (value == null || value.isBlank() || value.length() > 512)
            throw new IllegalArgumentException("Snapshot partition must contain 1..512 characters");
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
