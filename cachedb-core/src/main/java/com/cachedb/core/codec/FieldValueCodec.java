package com.reactor.cachedb.core.codec;

public interface FieldValueCodec<T> {
    String encode(T value);
    T decode(String encoded);

    default Object toColumnValue(T value) {
        return value == null ? null : encode(value);
    }
}
