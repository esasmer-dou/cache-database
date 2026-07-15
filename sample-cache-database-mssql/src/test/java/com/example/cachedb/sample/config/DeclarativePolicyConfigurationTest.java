package com.example.cachedb.sample.config;

import com.reactor.cachedb.core.cache.EntityHotPolicyMode;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarativePolicyConfigurationTest {

    @Test
    void applicationYamlDeclaresEveryGeneratedEntityPolicy() throws IOException {
        CacheDbSpringProperties properties = loadProperties();

        assertEquals(CacheDbSpringProperties.RegistrationSource.JDBC, properties.getRegistration().getSource());
        assertTrue(properties.getRegistration().isFailOnUnknownEntity());
        assertEquals(9, properties.getRegistration().getEntities().size());
        assertEquals(100_000, properties.getRegistration().getEntities().get("OrderEntity").getHotEntityLimit());
        assertEquals(
                EntityHotPolicyMode.COMPOSITE,
                properties.getRegistration().getEntities().get("OrderEntity").getHotPolicy().getMode()
        );
        assertFalse(properties.getRegistration().getEntities()
                .get("AuditEventEntity").getHotPolicy().getAdmitOnRead());
    }

    private CacheDbSpringProperties loadProperties() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "sample",
                new ClassPathResource("application.yml")
        );
        sources.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment)
                .bind("cachedb", Bindable.of(CacheDbSpringProperties.class))
                .orElseThrow(() -> new IllegalStateException("cachedb properties were not bound"));
    }
}
