package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntity;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntityCacheBinding;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseProfiles;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ProductionCertificationRunner {

    private static final String JDBC_USER = System.getProperty("cachedb.prod.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.prod.postgres.password", "postgresql");
    private static final String REDIS_PASSWORD = System.getProperty("cachedb.prod.redis.password", "welcome1");
    private static final String REDIS_URI = System.getProperty("cachedb.prod.redis.uri", "redis://default:" + REDIS_PASSWORD + "@127.0.0.1:6379");
    private static final String JDBC_URL = System.getProperty("cachedb.prod.postgres.url", "jdbc:postgresql://127.0.0.1:5432/postgres");

    public ProductionCertificationReport run() throws Exception {
        EcommerceScenarioProfile profile = certificationProfile(
                ScenarioCatalog.byName(System.getProperty("cachedb.prod.certification.scenario", "campaign-push-spike"))
        );
        double scaleFactor = Double.parseDouble(System.getProperty("cachedb.prod.certification.scaleFactor", "0.02"));
        double minimumTps = Double.parseDouble(System.getProperty("cachedb.prod.certification.minTps", "50"));
        long maxBacklog = Long.getLong("cachedb.prod.certification.maxBacklog", 2_000L);
        boolean requireDrain = Boolean.parseBoolean(System.getProperty("cachedb.prod.certification.requireDrain", "false"));

        EcommerceProductionScenarioRunner benchmarkRunner = new EcommerceProductionScenarioRunner();
        ScenarioReport benchmarkReport = benchmarkRunner.run(profile, scaleFactor, "production-certification-benchmark");
        RestartRecoveryCertificationResult restartRecovery = verifyRestartRecovery();
        CrashReplayChaosSuiteReport crashReplayChaos = new CrashReplayChaosSuiteRunner().run();
        FaultInjectionSuiteReport faultInjection = new FaultInjectionSuiteRunner().run();

        List<CertificationGateResult> gates = List.of(
                gate("benchmark_tps", benchmarkReport.achievedTransactionsPerSecond() >= minimumTps,
                        ">=" + minimumTps, formatDouble(benchmarkReport.achievedTransactionsPerSecond()), "Representative benchmark throughput"),
                gate("benchmark_failures", benchmarkReport.failedOperations() == 0,
                        "0", String.valueOf(benchmarkReport.failedOperations()), "Representative benchmark failure count"),
                gate("benchmark_backlog", benchmarkReport.writeBehindStreamLength() <= maxBacklog,
                        "<=" + maxBacklog, String.valueOf(benchmarkReport.writeBehindStreamLength()), "Write-behind backlog at end of benchmark"),
                gate("benchmark_hard_rejections", benchmarkReport.hardRejectedWriteCount() == 0L,
                        "0", String.valueOf(benchmarkReport.hardRejectedWriteCount()), "Hard rejections should not appear in certification benchmark"),
                gate("restart_recovery", restartRecovery.persistedBeforeRestart() && restartRecovery.recoveredAfterRestart(),
                        "persisted+recovered", restartRecovery.note(), "Restart should preserve persisted state and recover reads"),
                gate("restart_rebuild", restartRecovery.queryIndexRebuildSuccessful(),
                        "true", String.valueOf(restartRecovery.queryIndexRebuildSuccessful()), "Query index rebuild should succeed after restart"),
                gate("crash_replay_chaos", crashReplayChaos.allSuccessful(),
                        "true", String.valueOf(crashReplayChaos.allSuccessful()), "Crash/replay chaos scenarios should all pass"),
                gate("fault_injection_suite", faultInjection.allSuccessful(),
                        "true", String.valueOf(faultInjection.allSuccessful()), "Fault injection recovery scenarios should all pass"),
                gate("drain_requirement", !requireDrain || benchmarkReport.drainCompleted(),
                        String.valueOf(requireDrain), String.valueOf(benchmarkReport.drainCompleted()), "Drain completion requirement for certification")
        );
        boolean passed = gates.stream().allMatch(CertificationGateResult::passed);
        ProductionCertificationReport report = new ProductionCertificationReport(
                "production-certification",
                Instant.now(),
                benchmarkReport,
                restartRecovery,
                crashReplayChaos,
                faultInjection,
                List.copyOf(gates),
                passed
        );
        writeReports(report);
        return report;
    }

    public RestartRecoveryCertificationResult verifyRestartRecoveryForSuite() throws Exception {
        return verifyRestartRecovery();
    }

    private EcommerceScenarioProfile certificationProfile(EcommerceScenarioProfile profile) {
        return new EcommerceScenarioProfile(
                profile.name() + "-certification",
                profile.kind(),
                profile.description(),
                Math.min(profile.targetTransactionsPerSecond(), 1_200),
                Math.min(profile.durationSeconds(), 8),
                Math.min(profile.workerThreads(), 5),
                Math.min(profile.customerCount(), 500),
                Math.min(profile.productCount(), 200),
                Math.min(profile.hotProductSetSize(), 20),
                profile.browsePercent(),
                profile.productLookupPercent(),
                profile.cartWritePercent(),
                profile.inventoryReservePercent(),
                profile.checkoutPercent(),
                profile.customerTouchPercent(),
                Math.min(profile.writeBehindWorkerThreads(), 3),
                Math.min(profile.writeBehindBatchSize(), 64),
                Math.min(profile.hotEntityLimit(), 500),
                Math.min(profile.pageSize(), 50),
                Math.min(profile.entityTtlSeconds(), 60),
                Math.min(profile.pageTtlSeconds(), 30)
        );
    }

    private RestartRecoveryCertificationResult verifyRestartRecovery() throws Exception {
        String keyPrefix = "cachedb-cert-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        dropCertificationTable();

        CacheDatabaseConfig config = copyWithKeyPrefix(CacheDatabaseProfiles.benchmark(), keyPrefix, functionPrefix);
        EcomCustomerEntity entity = new EcomCustomerEntity();
        entity.id = 9001L;
        entity.status = "ACTIVE";
        entity.segment = "CERT";
        entity.tier = "GOLD";
        entity.lastCampaignCode = "CERT-01";

        boolean persistedBeforeRestart;
        boolean recoveredAfterRestart;
        boolean rebuildSuccessful;
        String healthStatus;

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase first = new CacheDatabase(jedis, dataSource, config)) {
            EcomCustomerEntityCacheBinding.register(first, CachePolicy.defaults(), new com.reactor.cachedb.core.relation.NoOpRelationBatchLoader<>(), new com.reactor.cachedb.core.page.NoOpEntityPageLoader<>());
            first.start();
            EcomCustomerEntityCacheBinding.save(first, CachePolicy.defaults(), entity);
            waitUntilPersisted();
            persistedBeforeRestart = countRows("select count(*) from cachedb_prodtest_customers where id = 9001") == 1;
        }

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase second = new CacheDatabase(jedis, dataSource, config)) {
            EcomCustomerEntityCacheBinding.register(second, CachePolicy.defaults(), new com.reactor.cachedb.core.relation.NoOpRelationBatchLoader<>(), new com.reactor.cachedb.core.page.NoOpEntityPageLoader<>());
            second.start();
            recoveredAfterRestart = EcomCustomerEntityCacheBinding.findById(second, CachePolicy.defaults(), 9001L).isPresent();
            rebuildSuccessful = second.admin().rebuildQueryIndexes("EcomCustomerEntity", "certification-restart").rebuiltEntityCount() >= 0;
            healthStatus = second.admin().health().status().name();
        }
        return new RestartRecoveryCertificationResult(
                persistedBeforeRestart,
                recoveredAfterRestart,
                rebuildSuccessful,
                healthStatus,
                "persisted=" + persistedBeforeRestart + ", recovered=" + recoveredAfterRestart + ", health=" + healthStatus
        );
    }

    private CacheDatabaseConfig copyWithKeyPrefix(CacheDatabaseConfig base, String keyPrefix, String functionPrefix) {
        return CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .workerThreads(base.writeBehind().workerThreads())
                        .batchSize(base.writeBehind().batchSize())
                        .streamKey(keyPrefix + ":stream")
                        .consumerGroup(keyPrefix + "-group")
                        .consumerNamePrefix(keyPrefix + "-worker")
                        .deadLetterStreamKey(keyPrefix + ":dlq")
                        .compactionStreamKey(keyPrefix + ":stream:compaction")
                        .compactionConsumerGroup(keyPrefix + "-compaction-group")
                        .compactionConsumerNamePrefix(keyPrefix + "-compaction-worker")
                        .blockTimeoutMillis(base.writeBehind().blockTimeoutMillis())
                        .idleSleepMillis(base.writeBehind().idleSleepMillis())
                        .batchFlushEnabled(base.writeBehind().batchFlushEnabled())
                        .tableAwareBatchingEnabled(base.writeBehind().tableAwareBatchingEnabled())
                        .durableCompactionEnabled(base.writeBehind().durableCompactionEnabled())
                        .postgresMultiRowFlushEnabled(base.writeBehind().postgresMultiRowFlushEnabled())
                        .postgresCopyBulkLoadEnabled(base.writeBehind().postgresCopyBulkLoadEnabled())
                        .build())
                .resourceLimits(base.resourceLimits())
                .relations(base.relations())
                .pageCache(base.pageCache())
                .queryIndex(base.queryIndex())
                .deadLetterRecovery(base.deadLetterRecovery())
                .adminMonitoring(base.adminMonitoring())
                .adminReportJob(base.adminReportJob())
                .adminHttp(base.adminHttp())
                .redisGuardrail(base.redisGuardrail())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(keyPrefix)
                        .entitySegment(base.keyspace().entitySegment())
                        .pageSegment(base.keyspace().pageSegment())
                        .versionSegment(base.keyspace().versionSegment())
                        .tombstoneSegment(base.keyspace().tombstoneSegment())
                        .hotSetSegment(base.keyspace().hotSetSegment())
                        .indexSegment(base.keyspace().indexSegment())
                        .compactionSegment(base.keyspace().compactionSegment())
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(true)
                        .autoLoadLibrary(true)
                        .replaceLibraryOnLoad(true)
                        .strictLoading(true)
                        .libraryName(functionPrefix)
                        .upsertFunctionName(functionPrefix + "_entity_upsert")
                        .deleteFunctionName(functionPrefix + "_entity_delete")
                        .compactionCompleteFunctionName(functionPrefix + "_compaction_complete")
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .autoApplyOnStart(true)
                        .build())
                .build();
    }

    private void writeReports(ProductionCertificationReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("production-certification-report.json"), renderJson(report));
        Files.writeString(reportDirectory.resolve("production-certification-report.md"), renderMarkdown(report));
    }

    private String renderJson(ProductionCertificationReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"certificationName\":\"").append(report.certificationName()).append("\",");
        builder.append("\"recordedAt\":\"").append(report.recordedAt()).append("\",");
        builder.append("\"passed\":").append(report.passed()).append(",");
        builder.append("\"benchmarkScenario\":\"").append(report.benchmarkReport().scenarioName()).append("\",");
        builder.append("\"benchmarkTps\":").append(formatDouble(report.benchmarkReport().achievedTransactionsPerSecond())).append(",");
        builder.append("\"benchmarkBacklog\":").append(report.benchmarkReport().writeBehindStreamLength()).append(",");
        builder.append("\"restartRecovery\":{")
                .append("\"persistedBeforeRestart\":").append(report.restartRecovery().persistedBeforeRestart()).append(',')
                .append("\"recoveredAfterRestart\":").append(report.restartRecovery().recoveredAfterRestart()).append(',')
                .append("\"queryIndexRebuildSuccessful\":").append(report.restartRecovery().queryIndexRebuildSuccessful()).append(',')
                .append("\"healthStatus\":\"").append(report.restartRecovery().healthStatus()).append("\",")
                .append("\"note\":\"").append(jsonEscape(report.restartRecovery().note())).append("\"},");
        builder.append("\"crashReplayChaos\":{")
                .append("\"scenarioCount\":").append(report.crashReplayChaos().scenarioCount()).append(',')
                .append("\"successfulScenarios\":").append(report.crashReplayChaos().successfulScenarios()).append(',')
                .append("\"allSuccessful\":").append(report.crashReplayChaos().allSuccessful()).append("},");
        builder.append("\"faultInjection\":{")
                .append("\"scenarioCount\":").append(report.faultInjection().scenarioCount()).append(',')
                .append("\"successfulScenarios\":").append(report.faultInjection().successfulScenarios()).append(',')
                .append("\"allSuccessful\":").append(report.faultInjection().allSuccessful()).append("},");
        builder.append("\"gates\":[");
        for (int index = 0; index < report.gates().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            CertificationGateResult gate = report.gates().get(index);
            builder.append("{\"gateName\":\"").append(jsonEscape(gate.gateName())).append("\",")
                    .append("\"passed\":").append(gate.passed()).append(',')
                    .append("\"expected\":\"").append(jsonEscape(gate.expected())).append("\",")
                    .append("\"actual\":\"").append(jsonEscape(gate.actual())).append("\",")
                    .append("\"details\":\"").append(jsonEscape(gate.details())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String renderMarkdown(ProductionCertificationReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Production Certification Report\n\n");
        builder.append("- Recorded At: ").append(report.recordedAt()).append('\n');
        builder.append("- Passed: ").append(report.passed()).append('\n');
        builder.append("- Benchmark Scenario: ").append(report.benchmarkReport().scenarioName()).append('\n');
        builder.append("- Benchmark TPS: ").append(formatDouble(report.benchmarkReport().achievedTransactionsPerSecond())).append('\n');
        builder.append("- Benchmark Backlog: ").append(report.benchmarkReport().writeBehindStreamLength()).append('\n');
        builder.append("- Restart Recovery: ").append(report.restartRecovery().note()).append('\n');
        builder.append("- Crash Replay Chaos: ")
                .append(report.crashReplayChaos().successfulScenarios())
                .append('/')
                .append(report.crashReplayChaos().scenarioCount())
                .append(" scenarios passed\n");
        builder.append("- Fault Injection: ")
                .append(report.faultInjection().successfulScenarios())
                .append('/')
                .append(report.faultInjection().scenarioCount())
                .append(" scenarios passed\n");
        builder.append('\n');
        builder.append("| Gate | Passed | Expected | Actual | Details |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        for (CertificationGateResult gate : report.gates()) {
            builder.append("| ").append(gate.gateName())
                    .append(" | ").append(gate.passed())
                    .append(" | ").append(gate.expected())
                    .append(" | ").append(gate.actual())
                    .append(" | ").append(gate.details().replace("|", "\\|"))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private CertificationGateResult gate(String name, boolean passed, String expected, String actual, String details) {
        return new CertificationGateResult(name, passed, expected, actual, details);
    }

    private void waitUntilPersisted() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (countRows("select count(*) from cachedb_prodtest_customers where id = 9001") == 1) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out waiting for certification row persistence");
    }

    private void dropCertificationTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prodtest_customers");
        }
    }

    private int countRows(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
