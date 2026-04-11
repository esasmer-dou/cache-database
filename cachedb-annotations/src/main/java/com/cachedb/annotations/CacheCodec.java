package com.reactor.cachedb.annotations;

import com.reactor.cachedb.core.codec.FieldValueCodec;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheCodec {
    Class<? extends FieldValueCodec<?>> value();
}
