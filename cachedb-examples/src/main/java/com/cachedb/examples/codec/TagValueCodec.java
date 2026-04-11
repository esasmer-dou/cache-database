package com.reactor.cachedb.examples.codec;

import com.reactor.cachedb.core.codec.FieldValueCodec;
import com.reactor.cachedb.examples.entity.TagValue;

public final class TagValueCodec implements FieldValueCodec<TagValue> {
    @Override
    public String encode(TagValue value) {
        return value == null ? null : value.value();
    }

    @Override
    public TagValue decode(String encoded) {
        return encoded == null ? null : new TagValue(encoded);
    }
}
