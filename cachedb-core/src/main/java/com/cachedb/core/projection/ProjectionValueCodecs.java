package com.reactor.cachedb.core.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public final class ProjectionValueCodecs {

    public static final ProjectionValueCodec<String> STRING = codec(value -> value, value -> value);
    public static final ProjectionValueCodec<Long> LONG = codec(String::valueOf, Long::valueOf);
    public static final ProjectionValueCodec<Integer> INTEGER = codec(String::valueOf, Integer::valueOf);
    public static final ProjectionValueCodec<Short> SHORT = codec(String::valueOf, Short::valueOf);
    public static final ProjectionValueCodec<Double> DOUBLE = codec(String::valueOf, Double::valueOf);
    public static final ProjectionValueCodec<Float> FLOAT = codec(String::valueOf, Float::valueOf);
    public static final ProjectionValueCodec<Boolean> BOOLEAN = codec(String::valueOf, Boolean::valueOf);
    public static final ProjectionValueCodec<BigDecimal> BIG_DECIMAL = codec(BigDecimal::toPlainString, BigDecimal::new);
    public static final ProjectionValueCodec<UUID> UUID_VALUE = codec(UUID::toString, UUID::fromString);
    public static final ProjectionValueCodec<Instant> INSTANT = codec(Instant::toString, Instant::parse);
    public static final ProjectionValueCodec<LocalDate> LOCAL_DATE = codec(LocalDate::toString, LocalDate::parse);
    public static final ProjectionValueCodec<LocalDateTime> LOCAL_DATE_TIME = codec(LocalDateTime::toString, LocalDateTime::parse);
    public static final ProjectionValueCodec<LocalTime> LOCAL_TIME = codec(LocalTime::toString, LocalTime::parse);

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
