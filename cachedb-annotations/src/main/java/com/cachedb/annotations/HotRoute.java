package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an explicitly Redis-only read route. No SQL fallback is generated. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface HotRoute {
    String value();

    Class<?> projection() default Void.class;

    int pageSize() default 100;

    int hotWindow() default 1_000;

    long memoryBudgetBytes() default 0L;

    /** Parameter used to isolate coverage, for example tenantId or customerId. */
    String coverageScopeParameter() default "";

    long maxStalenessSeconds() default 300L;

    boolean strict() default true;

    /** Declares how this Redis-only route receives its representative data set. */
    Population population() default Population.ON_DEMAND;

    enum Population {
        /** The application decides explicitly; retained for source compatibility. */
        ON_DEMAND,
        /** At least one @WarmRoute must reference this method. */
        DECLARED_WARM,
        /** Normal CacheDB writes or an outbox/CDC apply runner feed the route. */
        WRITE_FED,
        /** An operator-owned external process establishes route coverage. */
        EXTERNAL
    }
}
