package com.reactor.cachedb.spring.boot;

/**
 * Handles a durable, typed job command. Every application instance must
 * register the same handler set so an abandoned job can be claimed elsewhere.
 */
public interface CacheDistributedJobHandler<A> {

    String route();

    Class<A> argumentType();

    Object execute(A arguments, CacheDistributedJobContext context);
}
