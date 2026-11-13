package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.ExternalSampleFixtures;
import com.reactor.cachedb.core.config.NearCacheInvalidationMode;
import com.reactor.cachedb.starter.CacheDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sample.scheduled-warm.enabled=false", "cachedb.near-cache.invalidation-mode=PUBSUB"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class PubsubSoftRefreshExternalIT {
    @DynamicPropertySource static void fixtures(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry))
            throw new IllegalStateException("Dedicated external fixtures are required");
    }
    @Autowired TestRestTemplate rest;
    @Autowired CacheDatabase database;
    @Autowired Environment environment;
    @LocalServerPort int port;

    @Test void missedProtocolNotificationServesStaleThenRefreshesBeforeHardExpiry() throws Exception {
        ControlsScenarios.nearCache(rest, "http://127.0.0.1:" + port,
                environment.getRequiredProperty("cachedb.redis.uri"), database, NearCacheInvalidationMode.PUBSUB);
    }
}
