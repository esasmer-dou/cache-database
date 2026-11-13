package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheInitialNativeBridgeFixtureLifecycleTest {
    @Test
    void closesRuntimeResourcesBeforeOwnedSqlScope() throws Exception {
        var closed=new ArrayList<String>();

        CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(
                recording(closed,"database"),recording(closed,"wire"),
                recording(closed,"redis"),recording(closed,"sandbox"));

        assertEquals(List.of("database","wire","redis","sandbox"),closed);
    }

    @Test
    void keepsFirstFailureAndSuppressesEveryLaterCleanupFailure() {
        var closed=new ArrayList<String>();
        var databaseFailure=new Exception("database close");
        var wireFailure=new Exception("wire close");
        var redisFailure=new Exception("redis close");
        var sandboxFailure=new Exception("sandbox close");

        var failure=assertThrows(Exception.class,()->
                CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(
                        ()->{closed.add("database");throw databaseFailure;},
                        ()->{closed.add("wire");throw wireFailure;},
                        ()->{closed.add("redis");throw redisFailure;},
                        ()->{closed.add("sandbox");throw sandboxFailure;}));

        assertSame(databaseFailure,failure);
        assertArrayEquals(new Throwable[]{wireFailure,redisFailure,sandboxFailure},failure.getSuppressed());
        assertEquals(List.of("database","wire","redis","sandbox"),closed);
    }

    @Test
    void stillDisposesOwnedSqlAfterAnError() {
        var closed=new ArrayList<String>();
        var databaseFailure=new AssertionError("database close");
        var sandboxFailure=new Exception("sandbox close");

        var failure=assertThrows(AssertionError.class,()->
                CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(
                        ()->{closed.add("database");throw databaseFailure;},
                        recording(closed,"wire"),recording(closed,"redis"),
                        ()->{closed.add("sandbox");throw sandboxFailure;}));

        assertSame(databaseFailure,failure);
        assertArrayEquals(new Throwable[]{sandboxFailure},failure.getSuppressed());
        assertEquals(List.of("database","wire","redis","sandbox"),closed);
    }

    @Test
    void closesOnlyInitializedOwnersAfterPartialConstruction() throws Exception {
        var closed=new ArrayList<String>();

        CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(
                null,null,recording(closed,"redis"),recording(closed,"sandbox"));
        CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(null,null,null,null);

        assertEquals(List.of("redis","sandbox"),closed);
    }

    @Test
    void avoidsSelfSuppressionWithoutSkippingRemainingCleanup() {
        var closed=new ArrayList<String>();
        var sharedFailure=new Exception("shared close failure");

        var failure=assertThrows(Exception.class,()->
                CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(
                        ()->{throw sharedFailure;},()->{throw sharedFailure;},
                        recording(closed,"redis"),recording(closed,"sandbox")));

        assertSame(sharedFailure,failure);
        assertEquals(0,failure.getSuppressed().length);
        assertEquals(List.of("redis","sandbox"),closed);
    }

    @Test
    void tryWithResourcesRetainsBodyFailureAndAllCleanupFailures() {
        var closed=new ArrayList<String>();
        var bodyFailure=new Exception("test body");
        var databaseFailure=new Exception("database close");
        var sandboxFailure=new Exception("sandbox close");

        var failure=assertThrows(Exception.class,()->{
            try(AutoCloseable fixture=()->
                    CacheInitialNativeBridgeProvidersRedisTest.Fixture.closeOwnedResources(
                            ()->{closed.add("database");throw databaseFailure;},
                            recording(closed,"wire"),recording(closed,"redis"),
                            ()->{closed.add("sandbox");throw sandboxFailure;})) {
                throw bodyFailure;
            }
        });

        assertSame(bodyFailure,failure);
        assertArrayEquals(new Throwable[]{databaseFailure},failure.getSuppressed());
        assertArrayEquals(new Throwable[]{sandboxFailure},databaseFailure.getSuppressed());
        assertEquals(List.of("database","wire","redis","sandbox"),closed);
    }

    private static AutoCloseable recording(List<String> closed,String name) {
        return ()->{closed.add(name);};
    }
}
