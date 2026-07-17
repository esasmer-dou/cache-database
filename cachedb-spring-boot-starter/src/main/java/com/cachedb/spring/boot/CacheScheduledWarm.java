package com.reactor.cachedb.spring.boot;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Schedules a no-argument method that returns a bounded CacheWarmPlan.
 * Every job name is coordinated through Redis so only one application instance
 * executes a cluster-wide warm cycle at a time.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheScheduledWarm {

    String name() default "";

    String cron() default "";

    String zone() default "";

    String fixedDelayString() default "";

    String fixedRateString() default "";

    String initialDelayString() default "PT0S";

    String enabledString() default "true";

    CacheScheduledWarmMode mode() default CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS;

    /**
     * Redis lease TTL. The owner renews this lease while the warm is running.
     */
    String lockAtMostForString() default "PT5M";

    /**
     * Maximum time a pod waits for the current owner before skipping this cycle.
     */
    String lockWaitTimeoutString() default "PT30S";

    String lockRetryIntervalString() default "PT0.25S";

    /**
     * Cluster-wide minimum interval between successful executions. When empty,
     * fixed-delay/fixed-rate jobs use their schedule interval and cron jobs use one second.
     */
    String minimumIntervalString() default "";

    boolean reconcileHotSet() default false;

    String reconcileMaxRowsPerRunString() default "10000";

    String reconcileScanCountString() default "500";
}
