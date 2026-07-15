package com.reactor.cachedb.core.projection;

import java.math.BigDecimal;
import java.util.UUID;

public final class ProjectionValueCodecs {

    public static final ProjectionValueCodec<String> STRING = codec(value -> value, value -> value);
    public static final ProjectionValueCodec<Long> LONG = codec(String::valueOf, Long::valueOf);
    public static final ProjectionValueCodec<Integer> INTEGER = codec(String::valueOf, Integer::valueOf);
    public static final ProjectionValueCodec<Double> DOUBLE = codec(String::valueOf, Double::valueOf);
    public static final ProjectionValueCodec<Boolean> BOOLEAN = codec(String::valueOf, Boolean::valueOf);
    public static final ProjectionValueCodec<BigDecimal> BIG_DECIMAL = codec(BigDecimal::toPlainString, BigDecimal::new);
    public static final ProjectionValueCodec<UUID> UUID_VALUE = codec(UUID::toString, UUID::fromString);

    private ProjectionValueCodecs() {
    }

    public static <V> ProjectionValueCodec<V> codec(
            java.util.function.Function<V, String> encoder,
            java.util.function.Function<String, V> decoder
    ) {
        return new ProjectionValueCodec<>() {
            @Override
            public String encode(V value) {
                return value == null ? null : encoder.apply(value);
            }

            @Override
            public V decode(String value) {
                return value == null ? null : decoder.apply(value);
            }
        };
    }
}
