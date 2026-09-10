package com.reactor.cachedb.maven;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDbDoctorMojoTest {

    @Test
    void shouldAcceptExplicitOracleProvider() {
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();

        CacheDbDoctorMojo.validateProvider(
                Set.of("cachedb-spring-boot-starter-oracle"),
                "oracle",
                errors,
                warnings
        );

        assertTrue(errors.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void shouldFailClosedWhenMultipleProvidersAreImplicit() {
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();

        CacheDbDoctorMojo.validateProvider(
                Set.of("cachedb-storage-postgres", "cachedb-storage-oracle"),
                "",
                errors,
                warnings
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Multiple SQL providers"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void shouldRejectOracleSelectionWithoutOracleArtifacts() {
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();

        CacheDbDoctorMojo.validateProvider(
                Set.of("cachedb-storage-postgres"),
                "oracle",
                errors,
                warnings
        );

        assertEquals("Oracle is configured but its CacheDB provider starter is missing", errors.get(0));
        assertTrue(warnings.isEmpty());
    }
}
