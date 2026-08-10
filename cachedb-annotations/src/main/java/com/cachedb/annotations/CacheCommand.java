package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Documents and validates the acknowledgement contract of a repository command. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheCommand {
    Operation operation() default Operation.SAVE;

    Acknowledgement acknowledgement() default Acknowledgement.REDIS_ACCEPTED;

    int maxBatchSize() default 1_000;

    long durabilityTimeoutMillis() default 5_000L;

    String entityParameter() default "entity";

    String idParameter() default "id";

    String expectedVersionParameter() default "";

    enum Operation {
        SAVE,
        SAVE_ALL,
        DELETE_BY_ID
    }

    enum Acknowledgement {
        REDIS_ACCEPTED,
        SQL_DURABLE,
        READ_YOUR_WRITES
    }
}
