package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import com.reactor.cachedb.core.route.RouteCacheStrictMode;
import com.reactor.cachedb.core.route.TenantCacheQuota;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntity;
import com.reactor.cachedb.prodtest.entity.EcomOrderEntityCacheBinding;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.PostgresOutboxExternalChangeFeedAdapter;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
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
import java.util.UUID;

public final class ProductionScenarioCertificationRunner {

    private static final String JDBC_USER = ProductionTestEnvironment.postgresUser();
    private static final String JDBC_PASSWORD = ProductionTestEnvironment.postgresPassword();
    private static final String REDIS_URI = ProductionTestEnvironment.redisUri();
    private static final String JDBC_URL = ProductionTestEnvironment.postgresUrl();

    public ProductionScenarioCertificationReport run() throws Exception {
        String keyPrefix = "cachedb-prod-scenario-" + UUID.randomUUID();
        RouteScenarioOutcome routeOutcome = verifyRoutePolicyAndTenantPayloadBudget(keyPrefix);
        OutboxOutcome outboxOutcome = verifyPostgresOutboxAdapter();
        List<CertificationGateResult> gates = List.of(
                gate("strict_projection_contract", routeOutcome.strictProjectionContract(),
                        "projection route accepted and entity fallback rejected",
                        String.valueOf(routeOutcome.strictProjectionContract()),
                        "Route strict mode must fail fast if a projection-required route falls back to an entity scan."),
                gate("tenant_hot_row_eviction", routeOutcome.tenantHotRowEviction(),
                        "oldest tenant row evicted",
                        String.valueOf(routeOutcome.tenantHotRowEviction()),
                        "Per-tenant hot row quota must prevent one customer from occupying the full hot set."),
                gate("tenant_payload_accounting", routeOutcome.tenantPayloadAccounting(),
                        "payload bytes > 0",
                        String.valueOf(routeOutcome.tenantPayloadBytes()),
                        "Tenant memory budget must account for actual Redis entity payload bytes, not only tracking keys."),
                gate("outbox_polling", outboxOutcome.polledEvents() == 2,
                        "2 events",
                        String.valueOf(outboxOutcome.polledEvents()),
                        "PostgreSQL outbox adapter should poll a bounded batch into ExternalChangeEvent records."),
                gate("outbox_checkpoint", outboxOutcome.checkpointAdvanced(),
                        "checkpoint advanced",
                        String.valueOf(outboxOutcome.checkpointAdvanced()),
                        "Accepted outbox events must advance the adapter checkpoint to prevent duplicate replay.")
        );
        ProductionScenarioCertificationReport report = new ProductionScenarioCertificationReport(
                "production-scenario-certification",
                Instant.now(),
                gates,
                gates.stream().allMatch(CertificationGateResult::passed)
        );
        writeReports(report);
        return report;
    }

    private RouteScenarioOutcome verifyRoutePolicyAndTenantPayloadBudget(String keyPrefix) throws Exception {
        String functionPrefix = keyPrefix.replace('-', '_');
        CacheDatabaseConfig config = CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(false)
                        .streamKey(keyPrefix + ":stream")
                        .consumerGroup(keyPrefix + "-group")
                        .consumerNamePrefix(keyPrefix + "-worker")
                        .build())
                .keyspace(KeyspaceConfig.builder()
                        .keyPrefix(keyPrefix)
                        .entitySegment("entity")
                        .pageSegment("page")
                        .versionSegment("version")
                        .hotSetSegment("hotset")
                        .indexSegment("index")
                        .compactionSegment("compaction")
                        .build())
                .redisFunctions(RedisFunctionsConfig.builder()
                        .enabled(false)
                        .libraryName(functionPrefix)
                        .build())
                .schemaBootstrap(SchemaBootstrapConfig.builder()
                        .mode(SchemaBootstrapMode.CREATE_IF_MISSING)
                        .autoApplyOnStart(true)
                        .build())
                .build();
        RouteCacheContract contract = RouteCacheContract.builder()
                .routeName("CustomerOrdersTimeline")
                .entityName("EcomOrderEntity")
                .projectionName("order-summary")
                .pageSize(2)
                .hotWindow(2)
                .projectionRequired(true)
                .strictMode(RouteCacheStrictMode.FAIL_FAST)
                .tenantQuota(new TenantCacheQuota("customer_id", 2, 32_768L, true))
                .build();
        boolean strictProjectionContract = true;
        try {
            contract.validateResolvedRoute("projection:order-summary");
            contract.validateResolvedRoute("entity:EcomOrderEntity");
            strictProjectionContract = false;
        } catch (IllegalStateException expected) {
            strictProjectionContract = true;
        }

        try (JedisPooled jedis = new JedisPooled(URI.create(REDIS_URI));
             CacheDatabase cacheDatabase = new CacheDatabase(jedis, dataSource(), config)) {
            cacheDatabase.register(
                    EcomOrderEntityCacheBinding.METADATA,
                    EcomOrderEntityCacheBinding.CODEC,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(2).entityTtlSeconds(300).pageTtlSeconds(60).build()
            );
            cacheDatabase.start();
            EntityRepository<EcomOrderEntity, Long> repository = cacheDatabase.repository(
                    EcomOrderEntityCacheBinding.METADATA,
                    EcomOrderEntityCacheBinding.CODEC,
                    CachePolicy.builder().hotEntityLimit(10).pageSize(2).entityTtlSeconds(300).pageTtlSeconds(60).build()
            );
            RouteCacheContext.runWithContract(contract, () -> {
                repository.save(order(50_001L, 77L, "SKU-A", 2, 31.50D, "OPEN"));
                repository.save(order(50_002L, 77L, "SKU-B", 1, 42.00D, "OPEN"));
                repository.save(order(50_003L, 77L, "SKU-C", 4, 77.00D, "OPEN"));
            });
            boolean tenantHotRowEviction = repository.findById(50_001L).isEmpty()
                    && repository.findById(50_002L).isPresent()
                    && repository.findById(50_003L).isPresent();
            RedisKeyStrategy keyStrategy = new RedisKeyStrategy(keyPrefix, "entity", "page", "version", "hotset", "index", "compaction");
            String payloadBytesKey = keyStrategy.tenantHotPayloadBytesKey(
                    EcomOrderEntityCacheBinding.METADATA.redisNamespace(),
                    "customer_id",
                    77L
            );
            long tenantPayloadBytes = parseLong(jedis.get(payloadBytesKey));
            return new RouteScenarioOutcome(strictProjectionContract, tenantHotRowEviction, tenantPayloadBytes > 0L, tenantPayloadBytes);
        }
    }

    private OutboxOutcome verifyPostgresOutboxAdapter() throws Exception {
        dropOutboxTables();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE cachedb_prod_scenario_outbox (
                        id BIGSERIAL PRIMARY KEY,
                        entity_name TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        payload_json TEXT,
                        entity_version BIGINT NOT NULL,
                        occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        event_source TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO cachedb_prod_scenario_outbox
                    (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                    VALUES
                    ('EcomOrderEntity', '50001', 'UPSERT', '{"customer_id":77,"status":"OPEN"}', 1, 'production-scenario-outbox'),
                    ('EcomOrderEntity', '50001', 'DELETE', '{"customer_id":77,"status":"DELETED"}', 2, 'production-scenario-outbox')
                    """);
        }
        PostgresOutboxExternalChangeFeedAdapter adapter = PostgresOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName("production-scenario-certification")
                .outboxTable("cachedb_prod_scenario_outbox")
                .checkpointTable("cachedb_prod_scenario_outbox_checkpoint")
                .batchSize(10)
                .pollIntervalMillis(100L)
                .build();
        ArrayList<ExternalChangeEvent> events = new ArrayList<>();
        int polled = adapter.pollOnce(events::add);
        int duplicatePoll = adapter.pollOnce(events::add);
        adapter.close();
        boolean checkpointAdvanced = countRows("SELECT last_event_id FROM cachedb_prod_scenario_outbox_checkpoint WHERE adapter_name = 'production-scenario-certification'") == 2L;
        return new OutboxOutcome(polled, duplicatePoll, checkpointAdvanced);
    }

    private EcomOrderEntity order(long id, long customerId, String sku, int quantity, double totalAmount, String status) {
        EcomOrderEntity entity = new EcomOrderEntity();
        entity.id = id;
        entity.customerId = customerId;
        entity.sku = sku;
        entity.quantity = quantity;
        entity.totalAmount = totalAmount;
        entity.status = status;
        entity.campaignCode = "CERT";
        return entity;
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private void dropOutboxTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prod_scenario_outbox_checkpoint");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_prod_scenario_outbox");
        }
    }

    private long countRows(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void writeReports(ProductionScenarioCertificationReport report) throws IOException {
        Path reportDirectory = Path.of(System.getProperty("cachedb.prod.reportDir", "target/cachedb-prodtest-reports"));
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("production-scenario-certification.json"), renderJson(report));
        Files.writeString(reportDirectory.resolve("production-scenario-certification.md"), renderMarkdown(report));
    }

    private String renderJson(ProductionScenarioCertificationReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"certificationName\":\"").append(report.certificationName()).append("\",");
        builder.append("\"recordedAt\":\"").append(report.recordedAt()).append("\",");
        builder.append("\"passed\":").append(report.passed()).append(',');
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

    private String renderMarkdown(ProductionScenarioCertificationReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Production Scenario Certification\n\n");
        builder.append("- Recorded At: ").append(report.recordedAt()).append('\n');
        builder.append("- Passed: ").append(report.passed()).append("\n\n");
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

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private record RouteScenarioOutcome(
            boolean strictProjectionContract,
            boolean tenantHotRowEviction,
            boolean tenantPayloadAccounting,
            long tenantPayloadBytes
    ) {
    }

    private record OutboxOutcome(int polledEvents, int duplicatePollEvents, boolean checkpointAdvanced) {
    }
}
