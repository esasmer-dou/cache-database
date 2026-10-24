package com.example.cachedb.sample.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.repository.ReportJobRepository;
import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.starter.CacheDatabase;
import java.time.Duration;
import java.util.ArrayList;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Two application instances share one disposable Redis and the provider's disposable SQL fixture.
 */
public final class SplitRoleScenarios {
    private SplitRoleScenarios() {}

    public static void verify(Class<?> application, Environment original) {
        String externalRedis = com.example.cachedb.sample.ExternalSampleFixtures.splitRedisUri();
        if (externalRedis != null) {
            verify(application, original, externalRedis);
            return;
        }
        try (var redis =
                new GenericContainer<>(
                                "redis:8.2.1-alpine3.22@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232")
                        .withExposedPorts(6379)
                        .withCommand("redis-server", "--save", "", "--appendonly", "no")) {
            redis.start();
            String redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
            verify(application, original, redisUri);
        }
    }

    private static void verify(Class<?> application, Environment original, String redisUri) {
            try (var client = start(application, original, redisUri, "client")) {
                var api = client.getBean(CacheDatabase.class);
                var reports = client.getBean(ReportJobRepository.class);
                var sql = client.getBean(JdbcTemplate.class);
                assertEquals(CacheRuntimeRole.CLIENT_ONLY, api.config().runtimeRole());
                var publisher = client.getBean(com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor.class);
                var submitted = publisher.submit(CatalogRefreshJobHandler.DEFINITION, new CatalogRefreshCommand(java.util.List.of()));
                assertEquals(com.reactor.cachedb.spring.boot.CacheDistributedJobState.QUEUED, submitted.status());
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread ->
                        thread.getName().startsWith("cachedb-job-") && thread.getName().contains(api.instanceId())));
                var accepted = reports.save(report(8_200_001));
                assertFalse(api.awaitDurable(accepted, Duration.ofMillis(100)));
                assertEquals(
                        0,
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM sample_report_jobs WHERE report_job_id = ?",
                                Integer.class,
                                8_200_001));
                try (var worker = start(application, original, redisUri, "worker")) {
                    var runtime = worker.getBean(CacheDatabase.class);
                    assertEquals(CacheRuntimeRole.WORKER_ONLY, runtime.config().runtimeRole());
                    assertNotEquals(api.instanceId(), runtime.instanceId());
                    assertTrue(api.awaitDurable(accepted, Duration.ofSeconds(20)));
                    assertEquals(
                            1,
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM sample_report_jobs WHERE report_job_id ="
                                            + " ?",
                                    Integer.class,
                                    8_200_001));
                    try (var secondWorker = start(application, original, redisUri, "worker")) {
                        assertNotEquals(
                                runtime.instanceId(),
                                secondWorker.getBean(CacheDatabase.class).instanceId());
                        worker.close();
                        var handover = reports.save(report(8_200_003));
                        assertTrue(api.awaitDurable(handover, Duration.ofSeconds(20)));
                    }
                }
                var pending = reports.save(report(8_200_002));
                assertFalse(api.awaitDurable(pending, Duration.ofMillis(100)));
                try (var replacement = start(application, original, redisUri, "worker")) {
                    assertTrue(api.awaitDurable(pending, Duration.ofSeconds(20)));
                }
            }
    }

    private static ConfigurableApplicationContext start(
            Class<?> application, Environment original, String redisUri, String role) {
        var arguments = new ArrayList<String>();
        for (String property :
                new String[] {
                    "spring.datasource.url",
                    "spring.datasource.username",
                    "spring.datasource.password"
                }) {
            arguments.add("--" + property + "=" + original.getRequiredProperty(property));
        }
        arguments.add("--cachedb.redis.uri=" + redisUri);
        arguments.add("--cachedb.runtime.instance-id=" + role + "-" + java.util.UUID.randomUUID());
        arguments.add("--spring.main.web-application-type=none");
        arguments.add("--spring.sql.init.mode=never");
        arguments.add("--cachedb.admin.enabled=false");
        arguments.add("--sample.scheduled-warm.enabled=false");
        arguments.add("--sample.demo.write-tools-enabled=true");
        return new SpringApplicationBuilder(application)
                .profiles("demo", role)
                .run(arguments.toArray(String[]::new));
    }

    private static ReportJobEntity report(long id) {
        var report = new ReportJobEntity();
        report.reportJobId = id;
        report.reportType = "SPLIT_ROLE_TEST";
        report.status = "QUEUED";
        report.requestedBy = "fixture";
        report.createdAt = 1_700_000_000L;
        report.updatedAt = report.createdAt;
        report.rowCount = 0;
        return report;
    }
}
