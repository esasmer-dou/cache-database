package com.example.cachedb.sample.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class WorkerLifecycleConfigurationTest {
    @Test
    void nonWebWorkerStaysAliveWithoutStartingAnHttpListener() throws Exception {
        var sources = new YamlPropertySourceLoader().load(
                "worker", new ClassPathResource("application-worker.yml"));
        assertThat(sources).hasSize(1);
        var worker = sources.get(0);
        assertThat(worker.getProperty("spring.main.keep-alive")).isEqualTo(Boolean.TRUE);
        assertThat(worker.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(worker.getProperty("cachedb.runtime-role")).isEqualTo("WORKER_ONLY");
    }
}
