package com.reactor.cachedb.examples;

import com.reactor.cachedb.examples.entity.UserEntity;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;

public final class ExampleBindings {

    private ExampleBindings() {
    }

    public static void register(CacheDatabase cacheDatabase) {
        GeneratedCacheBindingsDiscovery.registerDiscovered(
                cacheDatabase,
                cacheDatabase.config().resourceLimits().defaultCachePolicy(),
                ExampleBindings.class.getClassLoader()
        );
    }

    public static UserEntity newUser(long id, String username, String status) {
        UserEntity entity = new UserEntity();
        entity.id = id;
        entity.username = username;
        entity.status = status;
        return entity;
    }
}
