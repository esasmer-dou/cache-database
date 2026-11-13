package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in field encryption for generated bindings, not runtime reflection.
 * Encrypted fields permit no query use except EQ through an explicitly enabled blind index.
 * Merely adding this annotation does not encrypt data without processor/storage integration.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.SOURCE)
public @interface CacheEncrypted {
    String keyRing() default "default";
    EncryptionScope scope() default EncryptionScope.REDIS_ONLY;
    boolean blindIndex() default false;
}
