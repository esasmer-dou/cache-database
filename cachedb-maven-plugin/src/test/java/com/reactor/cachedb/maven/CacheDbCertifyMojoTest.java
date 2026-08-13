package com.reactor.cachedb.maven;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheDbCertifyMojoTest {

    @Test
    void shouldUseMavenInjectableFileParameters() throws Exception {
        assertEquals(File.class, CacheDbCertifyMojo.class.getDeclaredField("evidenceDirectory").getType());
        assertEquals(File.class, CacheDbCertifyMojo.class.getDeclaredField("coverageFile").getType());
        assertEquals(File.class, CacheDbCertifyMojo.class.getDeclaredField("reportFile").getType());
    }
}
