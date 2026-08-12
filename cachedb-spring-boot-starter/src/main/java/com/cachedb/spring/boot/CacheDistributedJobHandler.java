package com.reactor.cachedb.spring.boot;

/**
 * Handles a durable, typed job command. Every application instance must
 * register the same handler set so an abandoned job can be claimed elsewhere.
 */
public interface CacheDistributedJobHandler<A> {

    String route();

    Class<A> argumentType();

    default CacheDistributedJobDefinition<A> definition() {
        return CacheDistributedJobDefinition.of(route(), argumentType());
    }

    Object execute(A arguments, CacheDistributedJobContext context);

    /**
     * Definition-first handler contract. Implementations declare one typed
     * definition; route and argument type cannot drift from it.
     */
    interface Typed<A> extends CacheDistributedJobHandler<A> {
        @Override
        CacheDistributedJobDefinition<A> definition();

        @Override
        default String route() {
            return definition().route();
        }

        @Override
        default Class<A> argumentType() {
            return definition().argumentType();
        }
    }

    static <A> CacheDistributedJobHandler<A> of(
            CacheDistributedJobDefinition<A> definition,
            Operation<A> operation
    ) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        return new Typed<>() {
            @Override public CacheDistributedJobDefinition<A> definition() { return definition; }
            @Override public Object execute(A arguments, CacheDistributedJobContext context) {
                return operation.execute(arguments, context);
            }
        };
    }

    @FunctionalInterface
    interface Operation<A> {
        Object execute(A arguments, CacheDistributedJobContext context);
    }
}
