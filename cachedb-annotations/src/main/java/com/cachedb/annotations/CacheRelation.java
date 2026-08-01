package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheRelation {
    /** Prefer {@link #target()} for compile-time type safety. */
    String targetEntity() default "";
    Class<?> target() default Void.class;
    String mappedBy();
    RelationKind kind();
    boolean batchLoadOnly() default true;
    int maxRowsPerParent() default 100;
    int parentBatchSize() default 32;
    String[] orderBy() default {};

    enum RelationKind {
        ONE_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_ONE,
        MANY_TO_MANY
    }
}
