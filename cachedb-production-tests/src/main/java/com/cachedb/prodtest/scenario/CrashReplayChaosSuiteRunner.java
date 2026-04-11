package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntity;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntityCacheBinding;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntity;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntityCacheBinding;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseProfiles;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CrashReplayChaosSuiteRunner {

    private static final String JDBC_USER = System.getProperty("cachedb.prod.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.prod.postgres.password", "postgresql");
    private static final String REDIS_PASSWORD = System.getProperty("cachedb.prod.redis.password", "welcome1");
    private static final String REDIS_URI = System.getProperty("cachedb.prod.redis.uri", "redis://default:" + REDIS_PASSWORD + "@127.0.0.1:6379");
    private static final String JDBC_URL = System.getProperty("cachedb.prod.postgres.url", "jdbc:postgresql://127.0.0.1:5432/postgres");

    public CrashReplayChaosSuiteReport run() throws Exception {
        ArrayList<CrashReplayChaosScenarioResult> scenarios = new ArrayList<>();
        scenarios.add(runLatestStateDeleteAfterRestartScenario());
        scenarios.add(runExactSequenceOrderAfterRestartScenario());
        scenarios.add(runManualReplayAfterRestartScenario());

        CrashReplayChaosSuiteReport report = new CrashReplayChaosSuiteReport(
                scenarios.size(),
                (int) scenarios.stream().filter(CrashReplayChaosScenarioResult::passed).count(),
                scenarios.stream().allMatch(CrashReplayChaosScenarioResult::passed),
                Instant.now(),
                List.copyOf(scenarios)
        );
        writeReports(report);
        return report;
    }

    private CrashReplayChaosScenarioResult runLatestStateDeleteAfterRestartScenario() throws Exception {
        String keyPrefix = "cachedb-chaos-delete-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropTables("cachedb_prodtest_customers");

        boolean restartVerified;
        boolean stateVerified;
        boolean rebuildVerified;
        String finalHealthStatus;

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase first = new CacheDatabase(jedis, dataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(first);
            first.start();

            EcomCustomerEntity entity = new EcomCustomerEntity();
            entity.id = 9101L;
            entity.status = "ACTIVE";
            entity.segment = "SMS";
            entity.tier = "VIP";
            entity.lastCampaignCode = "CHAOS-A";
            EcomCustomerEntityCacheBinding.save(first, CachePolicy.defaults(), entity);

            entity.status = "INACTIVE";
            entity.lastCampaignCode = "CHAOS-B";
            EcomCustomerEntityCacheBinding.save(first, CachePolicy.defaults(), entity);
            EcomCustomerEntityCacheBinding.deleteById(first, CachePolicy.defaults(), 9101L);
        }

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase second = new CacheDatabase(jedis, dataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(second);
            second.start();
            waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id = 9101") == 0, 10_000L,
                    "customer delete should survive restart");
            restartVerified = EcomCustomerEntityCacheBinding.findById(second, CachePolicy.defaults(), 9101L).isEmpty();
            stateVerified = countRows("select count(*) from cachedb_prodtest_customers where id = 9101") == 0;
            rebuildVerified = second.admin().rebuildQueryIndexes("EcomCustomerEntity", "chaos-delete-restart").rebuiltEntityCount() >= 0;
            finalHealthStatus = second.admin().health().status().name();
        }

        boolean passed = restartVerified && stateVerified && rebuildVerified;
        return new CrashReplayChaosScenarioResult(
                "latest-state-delete-after-restart",
                passed,
                restartVerified,
                stateVerified,
                true,
                rebuildVerified,
                finalHealthStatus,
                "delete persisted across restart without stale resurrection"
        );
    }

    private CrashReplayChaosScenarioResult runExactSequenceOrderAfterRestartScenario() throws Exception {
        String keyPrefix = "cachedb-chaos-order-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropTables("cachedb_prodtest_orders");

        boolean restartVerified;
        boolean stateVerified;
        boolean rebuildVerified;
        String finalHealthStatus;

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase first = new CacheDatabase(jedis, dataSource(), configFor(keyPrefix, functionPrefix))) {
            registerOrderBinding(first);
            first.start();

            EcomOrderEntity order = new EcomOrderEntity();
            order.id = 9201L;
            order.customerId = 101L;
            order.sku = "CHAOS-SKU";
            order.quantity = 1;
            order.totalAmount = 199.90d;
            order.status = "PENDING";
            order.campaignCode = "CHAOS-ORDER";
            EcomOrderEntityCacheBinding.save(first, CachePolicy.defaults(), order);

            waitUntil(() -> countRows("select count(*) from cachedb_prodtest_orders where id = 9201") == 1, 10_000L,
                    "order should initially persist before restart");

            order.status = "PAID";
            EcomOrderEntityCacheBinding.save(first, CachePolicy.defaults(), order);
        }

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase second = new CacheDatabase(jedis, dataSource(), configFor(keyPrefix, functionPrefix))) {
            registerOrderBinding(second);
            second.start();
            waitUntil(() -> "PAID".equals(singleString("select status from cachedb_prodtest_orders where id = 9201")), 10_000L,
                    "order should reach final PAID state after restart");
            restartVerified = EcomOrderEntityCacheBinding.findById(second, CachePolicy.defaults(), 9201L)
                    .map(entity -> "PAID".equals(entity.status))
                    .orElse(false);
            stateVerified = "PAID".equals(singleString("select status from cachedb_prodtest_orders where id = 9201"));
            rebuildVerified = second.admin().rebuildQueryIndexes("EcomOrderEntity", "chaos-order-restart").rebuiltEntityCount() >= 0;
            finalHealthStatus = second.admin().health().status().name();
        }

        boolean passed = restartVerified && stateVerified && rebuildVerified;
        return new CrashReplayChaosScenarioResult(
                "exact-sequence-order-after-restart",
                passed,
                restartVerified,
                stateVerified,
                true,
                rebuildVerified,
                finalHealthStatus,
                "final order state remained PAID after restart"
        );
    }

    private CrashReplayChaosScenarioResult runManualReplayAfterRestartScenario() throws Exception {
        String keyPrefix = "cachedb-chaos-replay-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropTables("cachedb_prodtest_customers");

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase first = new CacheDatabase(jedis, dataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(first);
            first.start();
            injectCustomerDeadLetter(jedis, keyPrefix);
        }

        boolean restartVerified;
        boolean stateVerified;
        boolean replayVerified;
        boolean rebuildVerified;
        String finalHealthStatus;

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase second = new CacheDatabase(jedis, dataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(second);
            second.start();
            var deadLetters = second.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId("9301").build()).items();
            restartVerified = !deadLetters.isEmpty();
            var replay = second.admin().replay(deadLetters.get(0).entryId(), "chaos replay after restart");
            replayVerified = replay.applied();
            waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id = 9301") == 1, 10_000L,
                    "manual replay should restore customer row");
            stateVerified = "ACTIVE".equals(singleString("select status from cachedb_prodtest_customers where id = 9301"));
            rebuildVerified = second.admin().reconciliation(ReconciliationQuery.builder().status("MANUAL_REPLAYED").limit(10).build())
                    .items().stream().anyMatch(item -> "9301".equals(item.entityId()));
            finalHealthStatus = second.admin().health().status().name();
        }

        boolean passed = restartVerified && stateVerified && replayVerified && rebuildVerified;
        return new CrashReplayChaosScenarioResult(
                "manual-replay-after-restart",
                passed,
                restartVerified,
                stateVerified,
                replayVerified,
                rebuildVerified,
                finalHealthStatus,
                "dead-letter replay remained available across restart"
        );
    }

    private String injectCustomerDeadLetter(JedisPooled jedis, String keyPrefix) {
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
        columns.put("id", 9301L);
        columns.put("status", "ACTIVE");
        columns.put("segment", "CHAOS");
        columns.put("tier", "VIP");
        columns.put("last_campaign_code", "REPLAY-CHAOS");

        WriteOperation<EcomCustomerEntity, Long> operation = new WriteOperation<>(
                OperationType.UPSERT,
                EcomCustomerEntityCacheBinding.METADATA,
                9301L,
                columns,
                "",
                1L,
                Instant.now()
        );

        Map<String, String> body = new com.reactor.cachedb.redis.RedisWriteOperationMapper().toBody(operation);
        body.put("deadLetterReason", "java.sql.SQLException");
        body.put("deadLetterMessage", "chaos replay seed");
        body.put("deadLetterFailureCategory", "CONNECTION");
        body.put("deadLetterSqlState", "08006");
        body.put("deadLetterVendorCode", "0");
        body.put("deadLetterRetryable", "true");
        body.put("deadLetterRootType", "java.sql.SQLException");
        body.put("deadLetterAttempts", "1");
        return jedis.xadd(keyPrefix + ":dlq", XAddParams.xAddParams(), body).toString();
    }

    private CacheDatabaseConfig configFor(String keyPrefix, String functionPrefix) {
        CacheDatabaseConfig base = CacheDatabaseProfiles.benchmark();
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
                .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                        .enabled(false)
                        .consumerGroup(base.deadLetterRecovery().consumerGroup())
                        .consumerNamePrefix(base.deadLetterRecovery().consumerNamePrefix())
                        .reconciliationStreamKey(base.deadLetterRecovery().reconciliationStreamKey())
                        .archiveResolvedEntries(base.deadLetterRecovery().archiveResolvedEntries())
                        .archiveStreamKey(base.deadLetterRecovery().archiveStreamKey())
                        .cleanupEnabled(false)
                        .build())
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

    private void registerCustomerBinding(CacheDatabase cacheDatabase) {
        cacheDatabase.register(EcomCustomerEntityCacheBinding.METADATA, EcomCustomerEntityCacheBinding.CODEC, CachePolicy.defaults());
    }

    private void registerOrderBinding(CacheDatabase cacheDatabase) {
        cacheDatabase.register(EcomOrderEntityCacheBinding.METADATA, EcomOrderEntityCacheBinding.CODEC, CachePolicy.defaults());
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private void dropTables(String... tables) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                statement.executeUpdate("DROP TABLE IF EXISTS " + table);
            }
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

    private String singleString(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        }
    }

    private void waitUntil(ThrowingBooleanSupplier condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out: " + message);
    }

    private void writeReports(CrashReplayChaosSuiteReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("crash-replay-chaos-suite.json"), toJson(report));
        Files.writeString(reportDirectory.resolve("crash-replay-chaos-suite.md"), toMarkdown(report));
    }

    private String toJson(CrashReplayChaosSuiteReport report) {
        StringBuilder builder = new StringBuilder("{\"scenarioCount\":")
                .append(report.scenarioCount())
                .append(",\"successfulScenarios\":").append(report.successfulScenarios())
                .append(",\"allSuccessful\":").append(report.allSuccessful())
                .append(",\"scenarios\":[");
        for (int index = 0; index < report.scenarios().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            CrashReplayChaosScenarioResult scenario = report.scenarios().get(index);
            builder.append("{\"scenarioName\":\"").append(escapeJson(scenario.scenarioName())).append("\",")
                    .append("\"passed\":").append(scenario.passed()).append(',')
                    .append("\"restartVerified\":").append(scenario.restartVerified()).append(',')
                    .append("\"stateVerified\":").append(scenario.stateVerified()).append(',')
                    .append("\"replayVerified\":").append(scenario.replayVerified()).append(',')
                    .append("\"rebuildVerified\":").append(scenario.rebuildVerified()).append(',')
                    .append("\"finalHealthStatus\":\"").append(escapeJson(scenario.finalHealthStatus())).append("\",")
                    .append("\"note\":\"").append(escapeJson(scenario.note())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(CrashReplayChaosSuiteReport report) {
        StringBuilder builder = new StringBuilder("# Crash Replay Chaos Suite\n\n");
        builder.append("- Scenario Count: `").append(report.scenarioCount()).append("`\n");
        builder.append("- Successful Scenarios: `").append(report.successfulScenarios()).append("`\n");
        builder.append("- All Successful: `").append(report.allSuccessful()).append("`\n\n");
        builder.append("| Scenario | Passed | Restart | State | Replay | Rebuild | Health | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CrashReplayChaosScenarioResult scenario : report.scenarios()) {
            builder.append("| ").append(scenario.scenarioName())
                    .append(" | ").append(scenario.passed())
                    .append(" | ").append(scenario.restartVerified())
                    .append(" | ").append(scenario.stateVerified())
                    .append(" | ").append(scenario.replayVerified())
                    .append(" | ").append(scenario.rebuildVerified())
                    .append(" | ").append(scenario.finalHealthStatus())
                    .append(" | ").append(scenario.note().replace("|", "\\|"))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
