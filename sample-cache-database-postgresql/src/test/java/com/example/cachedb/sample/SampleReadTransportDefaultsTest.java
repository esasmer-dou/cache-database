package com.example.cachedb.sample;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;

class SampleReadTransportDefaultsTest {
    private PropertySourcesPropertyResolver configuration(boolean demo, String optIn) throws Exception {
        var sources = new MutablePropertySources();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("application", new ClassPathResource("application.yml"))) sources.addLast(source);
        if (demo) for (var source : loader.load("demo", new ClassPathResource("application-demo.yml"))) sources.addFirst(source);
        if (optIn != null) sources.addFirst(new MapPropertySource("explicit-opt-in",
                Map.of("SAMPLE_READ_COALESCING_ENABLED", optIn)));
        return new PropertySourcesPropertyResolver(sources);
    }
    @Test void nonDemoKeepsNewApisAndMergingDisabled() throws Exception {
        var properties = configuration(false, null);
        assertFalse(properties.getRequiredProperty("cachedb.redis.read-coalescing.enabled", Boolean.class));
        assertFalse(properties.getRequiredProperty("sample.cache-api.enabled", Boolean.class));
        assertFalse(properties.getRequiredProperty("sample.range-data.enabled", Boolean.class));
    }
    @Test void demoDoesNotImplicitlyEnableMerging() throws Exception {
        assertFalse(configuration(true, null)
                .getRequiredProperty("cachedb.redis.read-coalescing.enabled", Boolean.class));
    }
    @Test void explicitOptInOnlyChangesMerging() throws Exception {
        var properties = configuration(false, "true");
        assertTrue(properties.getRequiredProperty("cachedb.redis.read-coalescing.enabled", Boolean.class));
        assertFalse(properties.getRequiredProperty("sample.cache-api.enabled", Boolean.class));
        assertFalse(properties.getRequiredProperty("sample.range-data.enabled", Boolean.class));
    }
}
