package com.reactor.cachedb.spring.boot;

import java.util.Objects;

/** Typed, cluster-stable route contract for one durable distributed job command. */
public record CacheDistributedJobDefinition<A>(String route, Class<A> argumentType) {
    public CacheDistributedJobDefinition {
        if (route == null || !route.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("route must match [A-Za-z0-9][A-Za-z0-9._:-]*");
        }
        route = route.trim();
        argumentType = Objects.requireNonNull(argumentType, "argumentType");
    }

    public static <A> CacheDistributedJobDefinition<A> of(String route, Class<A> argumentType) {
        return new CacheDistributedJobDefinition<>(route, argumentType);
    }

    public A requireArguments(Object arguments) {
        if (!argumentType.isInstance(arguments)) {
            String actual = arguments == null ? "null" : arguments.getClass().getName();
            throw new IllegalArgumentException("Distributed job route=" + route + " requires "
                    + argumentType.getName() + " but received " + actual);
        }
        return argumentType.cast(arguments);
    }
}
