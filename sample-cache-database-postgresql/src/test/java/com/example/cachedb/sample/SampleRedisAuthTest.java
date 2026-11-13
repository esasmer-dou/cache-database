package com.example.cachedb.sample;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class SampleRedisAuthTest {
    private static final String MAIN = "redis://private-redis.cachedb-auth-test.svc.cluster.local:6379/0";

    /** Direct contract probe: no Spring context, JUnit engine, Maven or native fixture allocation. */
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("No arguments accepted");
        var tests = new SampleRedisAuthTest();
        tests.absentOrEmptyPasswordPreservesNoAuthCompatibility();
        tests.reservedAndUnicodePasswordUsesUtf8PercentEncodingExactlyOnce();
        tests.splitEndpointKeepsItsTransportPortAndDatabase();
        tests.inputCredentialsAreRejectedWithOrWithoutSeparatePassword();
        tests.malformedEndpointErrorsDoNotRetainInputOrCause();
        tests.invalidPasswordErrorsDoNotRetainPasswordOrCause();
        tests.maximumPasswordRemainsBoundedAndRoundTrips();
        tests.purgeClientUsesExactUnencodedPasswordForDefaultUser();
        System.out.println("AUTH helper contract cases completed: 8; no native qualification performed");
    }

    @Test void absentOrEmptyPasswordPreservesNoAuthCompatibility() {
        for (String password : new String[] {null, ""}) {
            assertEquals(MAIN, SampleRedisAuth.withPassword(MAIN, password));
            var config = SampleRedisAuth.purgeClientConfig(password);
            assertNull(config.getUser());
            assertNull(config.getPassword());
            assertEquals(0, config.getDatabase());
            assertEquals(2000, config.getConnectionTimeoutMillis());
            assertEquals(2000, config.getSocketTimeoutMillis());
        }
    }

    @Test void reservedAndUnicodePasswordUsesUtf8PercentEncodingExactlyOnce() {
        String password = "p +:@/%?#&=\u00e7\uD83D\uDE80";
        URI uri = URI.create(SampleRedisAuth.withPassword(MAIN, password));
        assertEquals(":p%20%2B%3A%40%2F%25%3F%23%26%3D%C3%A7%F0%9F%9A%80", uri.getRawUserInfo());
        assertEquals(password, URLDecoder.decode(uri.getRawUserInfo().substring(1), StandardCharsets.UTF_8));
        assertEquals(URI.create(MAIN).getHost(), uri.getHost());
        assertEquals("/0", uri.getRawPath());
    }

    @Test void splitEndpointKeepsItsTransportPortAndDatabase() {
        String split = "rediss://private-split.cachedb-auth-test.svc.cluster.local:6380/12";
        URI uri = URI.create(SampleRedisAuth.withPassword(split, "%2B +"));
        assertEquals("rediss", uri.getScheme());
        assertEquals("private-split.cachedb-auth-test.svc.cluster.local", uri.getHost());
        assertEquals(6380, uri.getPort());
        assertEquals("/12", uri.getRawPath());
        assertEquals(":%252B%20%2B", uri.getRawUserInfo());
    }

    @Test void inputCredentialsAreRejectedWithOrWithoutSeparatePassword() {
        for (String password : new String[] {null, "", "synthetic-password"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SampleRedisAuth.withPassword(MAIN.replace("://", "://:synthetic-password@"), password));
            assertEquals("Invalid credential-free Redis fixture URI", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void malformedEndpointErrorsDoNotRetainInputOrCause() {
        for (String value : new String[] {null, "redis://:synthetic%zz@invalid/0", "http://invalid/0",
                MAIN + "?synthetic-password", MAIN + "#synthetic-password", "redis://invalid:0/0"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SampleRedisAuth.withPassword(value, "synthetic-password"));
            assertEquals("Invalid credential-free Redis fixture URI", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void invalidPasswordErrorsDoNotRetainPasswordOrCause() {
        for (String password : new String[] {"synthetic-password".repeat(300), "\uD800"}) {
            for (Runnable call : new Runnable[] {() -> SampleRedisAuth.withPassword(MAIN, password),
                    () -> SampleRedisAuth.purgeClientConfig(password)}) {
                var failure = assertThrows(IllegalArgumentException.class, call::run);
                assertEquals("Invalid bounded Redis fixture password", failure.getMessage());
                assertNull(failure.getCause());
            }
        }
    }

    @Test void maximumPasswordRemainsBoundedAndRoundTrips() {
        String password = "x".repeat(4096);
        URI uri = URI.create(SampleRedisAuth.withPassword(MAIN, password));
        assertEquals(password, URLDecoder.decode(uri.getRawUserInfo().substring(1), StandardCharsets.UTF_8));
        assertEquals(password, SampleRedisAuth.purgeClientConfig(password).getPassword());
    }

    @Test void purgeClientUsesExactUnencodedPasswordForDefaultUser() {
        String password = " p+:@/%\u00e7 ";
        var config = SampleRedisAuth.purgeClientConfig(password);
        assertNull(config.getUser());
        assertEquals(password, config.getPassword());
        assertEquals(0, config.getDatabase());
        assertEquals(2000, config.getConnectionTimeoutMillis());
        assertEquals(2000, config.getSocketTimeoutMillis());
    }
}
