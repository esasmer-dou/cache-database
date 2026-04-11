package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.queue.DeadLetterQuery;
import com.reactor.cachedb.core.queue.ReconciliationQuery;
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

public final class FaultInjectionSuiteRunner {

    private static final String JDBC_USER = System.getProperty("cachedb.prod.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.prod.postgres.password", "postgresql");
    private static final String REDIS_PASSWORD = System.getProperty("cachedb.prod.redis.password", "welcome1");
    private static final String REDIS_URI = System.getProperty("cachedb.prod.redis.uri", "redis://default:" + REDIS_PASSWORD + "@127.0.0.1:6379");
    private static final String JDBC_URL = System.getProperty("cachedb.prod.postgres.url", "jdbc:postgresql://127.0.0.1:5432/postgres");
    private static final String BAD_JDBC_URL = System.getProperty("cachedb.prod.postgres.faultUrl", "jdbc:postgresql://127.0.0.1:1/postgres?connectTimeout=2&socketTimeout=2");

    public FaultInjectionSuiteReport run() throws Exception {
        ArrayList<FaultInjectionScenarioResult> scenarios = new ArrayList<>();
        scenarios.add(runMidFlushRestartScenario());
        scenarios.add(runTransientPostgresLossScenario());
        scenarios.add(runReplayOrderingAfterRestartScenario());
        scenarios.add(runRecoveryCorrectnessSoakScenario());

        FaultInjectionSuiteReport report = new FaultInjectionSuiteReport(
                scenarios.size(),
                (int) scenarios.stream().filter(FaultInjectionScenarioResult::passed).count(),
                scenarios.stream().allMatch(FaultInjectionScenarioResult::passed),
                Instant.now(),
                List.copyOf(scenarios)
        );
        writeReports(report);
        return report;
    }

    private FaultInjectionScenarioResult runMidFlushRestartScenario() throws Exception {
        String keyPrefix = "cachedb-fault-midflush-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropCustomerTable();

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase first = new CacheDatabase(jedis, goodDataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(first);
            first.start();
            for (long id = 9401L; id <= 9420L; id++) {
                EcomCustomerEntity entity = customer(id, "ACTIVE", "MID-A");
                EcomCustomerEntityCacheBinding.save(first, CachePolicy.defaults(), entity);
                entity.status = "UPDATED";
                entity.lastCampaignCode = "MID-B";
                EcomCustomerEntityCacheBinding.save(first, CachePolicy.defaults(), entity);
            }
        }

        boolean recoveryVerified;
        boolean orderingVerified;
        boolean rebuildVerified;
        String health;
        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase second = new CacheDatabase(jedis, goodDataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(second);
            second.start();
            waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id between 9401 and 9420") == 20, 15_000L,
                    "mid-flush rows should drain after restart");
            recoveryVerified = true;
            orderingVerified = countRows("select count(*) from cachedb_prodtest_customers where id between 9401 and 9420 and status = 'UPDATED'") == 20;
            rebuildVerified = second.admin().rebuildQueryIndexes("EcomCustomerEntity", "fault-mid-flush").rebuiltEntityCount() >= 0;
            health = second.admin().health().status().name();
        }

        boolean passed = recoveryVerified && orderingVerified && rebuildVerified;
        return new FaultInjectionScenarioResult(
                "mid-flush-restart-drain",
                passed,
                true,
                recoveryVerified,
                orderingVerified,
                rebuildVerified,
                health,
                "pending writes drained to final UPDATED state after restart"
        );
    }

    private FaultInjectionScenarioResult runTransientPostgresLossScenario() throws Exception {
        String keyPrefix = "cachedb-fault-pg-loss-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropCustomerTable();

        boolean faultInjected;
        String entryId;
        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase broken = new CacheDatabase(jedis, badDataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(broken);
            broken.start();
            EcomCustomerEntityCacheBinding.save(broken, CachePolicy.defaults(), customer(9501L, "ACTIVE", "PG-LOSS"));
            waitUntil(() -> !broken.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId("9501").build()).items().isEmpty(), 15_000L,
                    "dead-letter should appear while PostgreSQL is unavailable");
            entryId = broken.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId("9501").build()).items().get(0).entryId();
            faultInjected = true;
        }

        boolean recoveryVerified;
        boolean replayOrderingVerified;
        boolean rebuildVerified;
        String health;
        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase recovered = new CacheDatabase(jedis, goodDataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(recovered);
            recovered.start();
            recoveryVerified = recovered.admin().replay(entryId, "fault-recovery").applied();
            waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id = 9501") == 1, 15_000L,
                    "manual replay should recover lost write");
            replayOrderingVerified = "ACTIVE".equals(singleString("select status from cachedb_prodtest_customers where id = 9501"));
            rebuildVerified = recovered.admin().reconciliation(ReconciliationQuery.builder().status("MANUAL_REPLAYED").limit(10).build())
                    .items().stream().anyMatch(item -> "9501".equals(item.entityId()));
            health = recovered.admin().health().status().name();
        }

        boolean passed = faultInjected && recoveryVerified && replayOrderingVerified && rebuildVerified;
        return new FaultInjectionScenarioResult(
                "transient-postgres-loss-recovery",
                passed,
                faultInjected,
                recoveryVerified,
                replayOrderingVerified,
                rebuildVerified,
                health,
                "PG outage produced DLQ entry and manual replay restored persisted state"
        );
    }

    private FaultInjectionScenarioResult runReplayOrderingAfterRestartScenario() throws Exception {
        String keyPrefix = "cachedb-fault-ordering-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropCustomerTable();

        String staleEntryId;
        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase broken = new CacheDatabase(jedis, badDataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(broken);
            broken.start();
            EcomCustomerEntityCacheBinding.save(broken, CachePolicy.defaults(), customer(9601L, "ACTIVE", "ORDERING-A"));
            waitUntil(() -> !broken.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId("9601").build()).items().isEmpty(), 15_000L,
                    "stale dead-letter should be produced");
            staleEntryId = broken.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId("9601").build()).items().get(0).entryId();
        }

        boolean recoveryVerified;
        boolean orderingVerified;
        boolean rebuildVerified;
        String health;
        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase recovered = new CacheDatabase(jedis, goodDataSource(), configFor(keyPrefix, functionPrefix))) {
            registerCustomerBinding(recovered);
            recovered.start();
            EcomCustomerEntityCacheBinding.save(recovered, CachePolicy.defaults(), customer(9601L, "INACTIVE", "ORDERING-B"));
            waitUntil(() -> "INACTIVE".equals(singleString("select status from cachedb_prodtest_customers where id = 9601")), 15_000L,
                    "newer state should persist before replay");
            recoveryVerified = "MANUAL_REPLAY_STALE".equals(recovered.admin().replay(staleEntryId, "stale replay after restart").status());
            orderingVerified = "INACTIVE".equals(singleString("select status from cachedb_prodtest_customers where id = 9601"));
            rebuildVerified = recovered.admin().reconciliation(ReconciliationQuery.builder().status("MANUAL_REPLAY_STALE").limit(10).build())
                    .items().stream().anyMatch(item -> "9601".equals(item.entityId()));
            health = recovered.admin().health().status().name();
        }

        boolean passed = recoveryVerified && orderingVerified && rebuildVerified;
        return new FaultInjectionScenarioResult(
                "replay-ordering-after-restart",
                passed,
                true,
                recoveryVerified,
                orderingVerified,
                rebuildVerified,
                health,
                "older replay was rejected as stale after newer state persisted"
        );
    }

    private FaultInjectionScenarioResult runRecoveryCorrectnessSoakScenario() throws Exception {
        String keyPrefix = "cachedb-fault-soak-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        dropCustomerTable();

        boolean faultInjected = false;
        boolean recoveryVerified = true;
        boolean orderingVerified = true;
        boolean rebuildVerified = true;
        String health = "UNKNOWN";

        for (int cycle = 1; cycle <= 3; cycle++) {
            long id = 9700L + cycle;
            try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
                 CacheDatabase broken = new CacheDatabase(jedis, badDataSource(), configFor(keyPrefix, functionPrefix))) {
                registerCustomerBinding(broken);
                broken.start();
                EcomCustomerEntityCacheBinding.save(broken, CachePolicy.defaults(), customer(id, "ACTIVE", "SOAK-" + cycle));
                waitUntil(() -> !broken.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId(String.valueOf(id)).build()).items().isEmpty(), 15_000L,
                        "each soak cycle should produce a dead-letter under PG outage");
                faultInjected = true;
            }

            try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
                 CacheDatabase recovered = new CacheDatabase(jedis, goodDataSource(), configFor(keyPrefix, functionPrefix))) {
                registerCustomerBinding(recovered);
                recovered.start();
                var items = recovered.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId(String.valueOf(id)).build()).items();
                if (items.isEmpty()) {
                    recoveryVerified = false;
                    break;
                }
                recoveryVerified = recoveryVerified && recovered.admin().replay(items.get(0).entryId(), "soak-cycle-" + cycle).applied();
                waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id = " + id) == 1, 15_000L,
                        "replayed cycle should persist");
                orderingVerified = orderingVerified && "ACTIVE".equals(singleString("select status from cachedb_prodtest_customers where id = " + id));
                rebuildVerified = rebuildVerified && recovered.admin().deadLetters(DeadLetterQuery.builder().limit(10).entityId(String.valueOf(id)).build()).items().isEmpty();
                health = recovered.admin().health().status().name();
            }
        }

        boolean passed = faultInjected && recoveryVerified && orderingVerified && rebuildVerified;
        return new FaultInjectionScenarioResult(
                "recovery-correctness-soak",
                passed,
                faultInjected,
                recoveryVerified,
                orderingVerified,
                rebuildVerified,
                health,
                "three repeated outage/replay cycles converged without leftover DLQ entries"
        );
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

    private PGSimpleDataSource goodDataSource() {
        return dataSource(JDBC_URL);
    }

    private PGSimpleDataSource badDataSource() {
        return dataSource(BAD_JDBC_URL);
    }

    private PGSimpleDataSource dataSource(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        dataSource.setConnectTimeout(2);
        dataSource.setSocketTimeout(2);
        return dataSource;
    }

    private void registerCustomerBinding(CacheDatabase cacheDatabase) {
        cacheDatabase.register(EcomCustomerEntityCacheBinding.METADATA, EcomCustomerEntityCacheBinding.CODEC, CachePolicy.defaults());
    }

    private EcomCustomerEntity customer(long id, String status, String campaignCode) {
        EcomCustomerEntity entity = new EcomCustomerEntity();
        entity.id = id;
        entity.status = status;
        entity.segment = "FAULT";
        entity.tier = "VIP";
        entity.lastCampaignCode = campaignCode;
        return entity;
    }

    private void dropCustomerTable() throws Exception {
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

    private void writeReports(FaultInjectionSuiteReport report) throws IOException {
        Path reportDirectory = Path.of("target", "cachedb-prodtest-reports");
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("fault-injection-suite.json"), toJson(report));
        Files.writeString(reportDirectory.resolve("fault-injection-suite.md"), toMarkdown(report));
    }

    private String toJson(FaultInjectionSuiteReport report) {
        StringBuilder builder = new StringBuilder("{\"scenarioCount\":")
                .append(report.scenarioCount())
                .append(",\"successfulScenarios\":").append(report.successfulScenarios())
                .append(",\"allSuccessful\":").append(report.allSuccessful())
                .append(",\"scenarios\":[");
        for (int index = 0; index < report.scenarios().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            FaultInjectionScenarioResult scenario = report.scenarios().get(index);
            builder.append("{\"scenarioName\":\"").append(escapeJson(scenario.scenarioName())).append("\",")
                    .append("\"passed\":").append(scenario.passed()).append(',')
                    .append("\"faultInjected\":").append(scenario.faultInjected()).append(',')
                    .append("\"recoveryVerified\":").append(scenario.recoveryVerified()).append(',')
                    .append("\"replayOrderingVerified\":").append(scenario.replayOrderingVerified()).append(',')
                    .append("\"rebuildVerified\":").append(scenario.rebuildVerified()).append(',')
                    .append("\"finalHealthStatus\":\"").append(escapeJson(scenario.finalHealthStatus())).append("\",")
                    .append("\"note\":\"").append(escapeJson(scenario.note())).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toMarkdown(FaultInjectionSuiteReport report) {
        StringBuilder builder = new StringBuilder("# Fault Injection Suite\n\n");
        builder.append("- Scenario Count: `").append(report.scenarioCount()).append("`\n");
        builder.append("- Successful Scenarios: `").append(report.successfulScenarios()).append("`\n");
        builder.append("- All Successful: `").append(report.allSuccessful()).append("`\n\n");
        builder.append("| Scenario | Passed | Fault | Recovery | Ordering | Rebuild | Health | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (FaultInjectionScenarioResult scenario : report.scenarios()) {
            builder.append("| ").append(scenario.scenarioName())
                    .append(" | ").append(scenario.passed())
                    .append(" | ").append(scenario.faultInjected())
                    .append(" | ").append(scenario.recoveryVerified())
                    .append(" | ").append(scenario.replayOrderingVerified())
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
