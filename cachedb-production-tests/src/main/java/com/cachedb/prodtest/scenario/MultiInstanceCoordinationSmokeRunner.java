package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.config.IncidentDeliveryDlqConfig;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.ProjectionRefreshConfig;
import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.config.RuntimeCoordinationConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapConfig;
import com.reactor.cachedb.core.config.SchemaBootstrapMode;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntity;
import com.reactor.cachedb.prodtest.entity.EcomCustomerEntityCacheBinding;
import com.reactor.cachedb.redis.RedisWriteOperationMapper;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseProfiles;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamConsumersInfo;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MultiInstanceCoordinationSmokeRunner {

    private static final String JDBC_USER = System.getProperty("cachedb.prod.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.prod.postgres.password", "postgresql");
    private static final String REDIS_PASSWORD = System.getProperty("cachedb.prod.redis.password", "welcome1");
    private static final String REDIS_URI = System.getProperty("cachedb.prod.redis.uri", "redis://default:" + REDIS_PASSWORD + "@127.0.0.1:6379");
    private static final String JDBC_URL = System.getProperty("cachedb.prod.postgres.url", "jdbc:postgresql://127.0.0.1:5432/postgres");

    public MultiInstanceCoordinationSmokeReport run() throws Exception {
        ConsumerAndLeaderOutcome consumerAndLeader = verifyConsumerIdentityAndLeaderFailover();
        ClaimFailoverOutcome claimFailover = verifyWriteBehindClaimFailover();
        MultiInstanceCoordinationSmokeReport report = new MultiInstanceCoordinationSmokeReport(
                Instant.now(),
                consumerAndLeader.uniqueConsumerNamesVerified(),
                consumerAndLeader.observedConsumerNames(),
                consumerAndLeader.leaderLeaseFailoverVerified(),
                consumerAndLeader.leaderLeaseKey(),
                consumerAndLeader.initialLeaderInstanceId(),
                consumerAndLeader.failoverLeaderInstanceId(),
                consumerAndLeader.historySamplesBeforeFailover(),
                consumerAndLeader.historySamplesAfterFailover(),
                claimFailover.writeBehindClaimFailoverVerified(),
                claimFailover.pendingOwnerConsumerName(),
                claimFailover.claimantClaimedCount(),
                claimFailover.persistedAfterClaim(),
                claimFailover.pendingDrainedAfterClaim(),
                consumerAndLeader.uniqueConsumerNamesVerified()
                        && consumerAndLeader.leaderLeaseFailoverVerified()
                        && claimFailover.writeBehindClaimFailoverVerified()
        );
        writeReports(report);
        return report;
    }

    private ConsumerAndLeaderOutcome verifyConsumerIdentityAndLeaderFailover() throws Exception {
        String keyPrefix = "cachedb-multi-node-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        String streamKey = keyPrefix + ":stream";
        String consumerGroup = keyPrefix + "-group";
        String leaderLeaseKey = keyPrefix + ":coordination:leader:monitoring-history";
        String monitoringHistoryStreamKey = keyPrefix + ":stream:admin:monitoring-history";
        dropCustomerTable();

        try (InstanceHandle nodeA = startNode(keyPrefix, functionPrefix, "pod-a", true);
             InstanceHandle nodeB = startNode(keyPrefix, functionPrefix, "pod-b", true);
             InstanceHandle nodeC = startNode(keyPrefix, functionPrefix, "pod-c", true);
             JedisPooled probeJedis = jedis()) {
            for (long id = 10_001L; id <= 10_006L; id++) {
                saveCustomer(nodeA.database(), id, "ACTIVE", "LEADER-" + id);
            }
            waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id between 10001 and 10006") == 6,
                    Duration.ofSeconds(15),
                    "initial multi-instance writes should persist");

            List<String> consumerNames = waitForConsumers(probeJedis, streamKey, consumerGroup, 3, Duration.ofSeconds(10));
            boolean uniqueConsumerNamesVerified = consumerNames.stream().distinct().count() >= 3
                    && consumerNames.stream().anyMatch(name -> name.contains("pod-a"))
                    && consumerNames.stream().anyMatch(name -> name.contains("pod-b"))
                    && consumerNames.stream().anyMatch(name -> name.contains("pod-c"));

            String initialLeader = waitForValue(probeJedis, leaderLeaseKey, Duration.ofSeconds(10));
            waitUntil(() -> probeJedis.xlen(monitoringHistoryStreamKey) > 0L,
                    Duration.ofSeconds(10),
                    "monitoring leader should produce at least one history sample");
            long historySamplesBeforeFailover = probeJedis.xlen(monitoringHistoryStreamKey);

            switch (initialLeader) {
                case "pod-a" -> nodeA.close();
                case "pod-b" -> nodeB.close();
                case "pod-c" -> nodeC.close();
                default -> throw new IllegalStateException("Unexpected leader instance id: " + initialLeader);
            }

            String failoverLeader = waitUntilValueChanges(probeJedis, leaderLeaseKey, initialLeader, Duration.ofSeconds(10));
            waitUntil(() -> probeJedis.xlen(monitoringHistoryStreamKey) > historySamplesBeforeFailover,
                    Duration.ofSeconds(10),
                    "monitoring history should continue after leader failover");
            long historySamplesAfterFailover = probeJedis.xlen(monitoringHistoryStreamKey);
            boolean leaderLeaseFailoverVerified = !Objects.equals(initialLeader, failoverLeader)
                    && List.of("pod-a", "pod-b", "pod-c").contains(failoverLeader);

            return new ConsumerAndLeaderOutcome(
                    uniqueConsumerNamesVerified,
                    consumerNames,
                    leaderLeaseFailoverVerified,
                    leaderLeaseKey,
                    initialLeader,
                    failoverLeader,
                    historySamplesBeforeFailover,
                    historySamplesAfterFailover
            );
        }
    }

    private ClaimFailoverOutcome verifyWriteBehindClaimFailover() throws Exception {
        String keyPrefix = "cachedb-claim-node-" + UUID.randomUUID();
        String functionPrefix = keyPrefix.replace('-', '_');
        String streamKey = keyPrefix + ":stream";
        String consumerGroup = keyPrefix + "-group";
        dropCustomerTable();

        try (JedisPooled probeJedis = jedis()) {
            ensureConsumerGroup(probeJedis, streamKey, consumerGroup);
            enqueuePendingOwnerEntry(probeJedis, streamKey, consumerGroup, "cachedb-worker-owner-node-0");
            String pendingOwnerConsumerName = waitForPendingConsumer(
                    probeJedis,
                    streamKey,
                    consumerGroup,
                    "owner-node",
                    Duration.ofSeconds(5)
            );
            // Let the pending entry age past claimIdleMillis so the claimant's first recovery
            // sweep can claim it immediately instead of waiting for the next retry window.
            Thread.sleep(1_100L);

            try (InstanceHandle claimant = startNode(keyPrefix, functionPrefix, "claimant-node", false)) {
                waitUntil(() -> claimant.database().workerSnapshot().claimedCount() > 0L,
                        Duration.ofSeconds(15),
                        "claimant node should claim abandoned write-behind work");
                waitUntil(() -> countRows("select count(*) from cachedb_prodtest_customers where id = 20001") == 1,
                        Duration.ofSeconds(15),
                        "claimed write should persist to PostgreSQL");
                waitUntil(() -> probeJedis.xpending(streamKey, consumerGroup).getTotal() == 0L,
                        Duration.ofSeconds(15),
                        "pending write-behind entry should be drained after claim");

                boolean persistedAfterClaim = countRows("select count(*) from cachedb_prodtest_customers where id = 20001") == 1;
                long claimantClaimedCount = claimant.database().workerSnapshot().claimedCount();
                boolean pendingDrained = probeJedis.xpending(streamKey, consumerGroup).getTotal() == 0L;
                return new ClaimFailoverOutcome(
                        claimantClaimedCount > 0L && persistedAfterClaim && pendingDrained,
                        pendingOwnerConsumerName,
                        claimantClaimedCount,
                        persistedAfterClaim,
                        pendingDrained
                );
            }
        }
    }

    private InstanceHandle startNode(String keyPrefix, String functionPrefix, String instanceId, boolean monitoringEnabled) throws Exception {
        return startNode(keyPrefix, functionPrefix, instanceId, monitoringEnabled, baseDataSource());
    }

    private InstanceHandle startNode(
            String keyPrefix,
            String functionPrefix,
            String instanceId,
            boolean monitoringEnabled,
            DataSource dataSource
    ) throws Exception {
        JedisPooled foreground = jedis();
        JedisPooled background = jedis();
        CacheDatabase database = new CacheDatabase(
                foreground,
                background,
                dataSource,
                configFor(keyPrefix, functionPrefix, instanceId, monitoringEnabled)
        );
        EcomCustomerEntityCacheBinding.register(
                database,
                CachePolicy.defaults(),
                new NoOpRelationBatchLoader<>(),
                new NoOpEntityPageLoader<>()
        );
        database.start();
        return new InstanceHandle(foreground, background, database);
    }

    private CacheDatabaseConfig configFor(String keyPrefix, String functionPrefix, String instanceId, boolean monitoringEnabled) {
        CacheDatabaseConfig base = CacheDatabaseProfiles.benchmark();
        return CacheDatabaseConfig.builder()
                .writeBehind(WriteBehindConfig.builder()
                        .enabled(true)
                        .workerThreads(1)
                        .batchSize(1)
                        .dedicatedWriteConsumerGroupEnabled(false)
                        .durableCompactionEnabled(false)
                        .batchFlushEnabled(false)
                        .tableAwareBatchingEnabled(false)
                        .flushGroupParallelism(1)
                        .flushPipelineDepth(1)
                        .coalescingEnabled(false)
                        .batchStaleCheckEnabled(false)
                        .postgresMultiRowFlushEnabled(false)
                        .postgresCopyBulkLoadEnabled(false)
                        .blockTimeoutMillis(100L)
                        .idleSleepMillis(50L)
                        .maxFlushRetries(1)
                        .retryBackoffMillis(100L)
                        .streamKey(keyPrefix + ":stream")
                        .consumerGroup(keyPrefix + "-group")
                        .consumerNamePrefix("cachedb-worker")
                        .compactionStreamKey(keyPrefix + ":stream:compaction")
                        .compactionConsumerGroup(keyPrefix + "-compaction-group")
                        .compactionConsumerNamePrefix("cachedb-compaction-worker")
                        .compactionShardCount(1)
                        .autoCreateConsumerGroup(true)
                        .shutdownAwaitMillis(1_000L)
                        .daemonThreads(true)
                        .recoverPendingEntries(true)
                        .claimIdleMillis(750L)
                        .claimBatchSize(16)
                        .deadLetterStreamKey(keyPrefix + ":dlq")
                        .deadLetterMaxLength(256L)
                        .compactionMaxLength(256L)
                        .build())
                .resourceLimits(base.resourceLimits())
                .relations(base.relations())
                .pageCache(base.pageCache())
                .queryIndex(base.queryIndex())
                .projectionRefresh(ProjectionRefreshConfig.builder()
                        .enabled(false)
                        .streamKey(keyPrefix + ":projection-stream")
                        .consumerGroup(keyPrefix + "-projection-group")
                        .consumerNamePrefix("cachedb-projection-refresh")
                        .deadLetterEnabled(false)
                        .deadLetterStreamKey(keyPrefix + ":projection-dlq")
                        .build())
                .deadLetterRecovery(DeadLetterRecoveryConfig.builder()
                        .enabled(false)
                        .consumerGroup(keyPrefix + "-dlq-group")
                        .consumerNamePrefix("cachedb-dlq-worker")
                        .reconciliationStreamKey(keyPrefix + ":reconciliation")
                        .archiveResolvedEntries(true)
                        .archiveStreamKey(keyPrefix + ":archive")
                        .cleanupEnabled(false)
                        .build())
                .adminMonitoring(AdminMonitoringConfig.builder()
                        .enabled(monitoringEnabled)
                        .historySampleIntervalMillis(250L)
                        .historyMinSampleIntervalMillis(250L)
                        .historyMaxSamples(64)
                        .historyMinSamples(8)
                        .alertRouteHistoryMinSamples(8)
                        .alertRouteHistorySampleMultiplier(2)
                        .telemetryTtlSeconds(300L)
                        .monitoringHistoryStreamKey(keyPrefix + ":stream:admin:monitoring-history")
                        .alertRouteHistoryStreamKey(keyPrefix + ":stream:admin:alert-route-history")
                        .performanceHistoryStreamKey(keyPrefix + ":stream:admin:performance-history")
                        .performanceSnapshotKey(keyPrefix + ":hash:admin:performance")
                        .incidentStreamKey(keyPrefix + ":stream:admin:incidents")
                        .incidentCooldownMillis(1_000L)
                        .incidentDeliveryDlq(IncidentDeliveryDlqConfig.builder()
                                .streamKey(keyPrefix + ":stream:admin:incidents:dlq")
                                .recoveryStreamKey(keyPrefix + ":stream:admin:incidents:recovery")
                                .consumerGroup(keyPrefix + "-incident-dlq-group")
                                .consumerNamePrefix("cachedb-incident-delivery-dlq")
                                .workerThreads(1)
                                .batchSize(8)
                                .blockTimeoutMillis(100L)
                                .idleSleepMillis(50L)
                                .claimIdleMillis(750L)
                                .claimBatchSize(16)
                                .autoCreateConsumerGroup(true)
                                .claimAbandonedEntries(true)
                                .build())
                        .build())
                .adminReportJob(base.adminReportJob())
                .adminHttp(AdminHttpConfig.builder().enabled(false).dashboardEnabled(false).build())
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
                .runtimeCoordination(RuntimeCoordinationConfig.builder()
                        .instanceId(instanceId)
                        .appendInstanceIdToConsumerNames(true)
                        .leaderLeaseEnabled(true)
                        .leaderLeaseSegment("coordination:leader")
                        .leaderLeaseTtlMillis(1_500L)
                        .leaderLeaseRenewIntervalMillis(400L)
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

    private void ensureConsumerGroup(JedisPooled jedis, String streamKey, String consumerGroup) {
        try {
            jedis.xgroupCreate(streamKey, consumerGroup, StreamEntryID.LAST_ENTRY, true);
        } catch (RuntimeException ignored) {
            // BUSYGROUP is fine for repeat runs.
        }
    }

    private void enqueuePendingOwnerEntry(
            JedisPooled jedis,
            String streamKey,
            String consumerGroup,
            String ownerConsumerName
    ) {
        QueuedWriteOperation operation = new QueuedWriteOperation(
                OperationType.UPSERT,
                "EcomCustomerEntity",
                "cachedb_prodtest_customers",
                "prodtest_customers",
                null,
                "id",
                "entity_version",
                "",
                "20001",
                Map.of(
                        "id", "20001",
                        "status", "ACTIVE",
                        "segment", "SMOKE",
                        "tier", "GOLD",
                        "last_campaign_code", "CLAIM-PRIMARY",
                        "entity_version", "1"
                ),
                1L,
                Instant.now()
        );
        Map<String, String> body = new RedisWriteOperationMapper().toBody(operation);
        jedis.xadd(streamKey, XAddParams.xAddParams(), body);
        jedis.xreadGroup(
                consumerGroup,
                ownerConsumerName,
                new XReadGroupParams().count(1),
                Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY)
        );
    }

    private void saveCustomer(CacheDatabase database, long id, String status, String campaignCode) {
        EcomCustomerEntity entity = new EcomCustomerEntity();
        entity.id = id;
        entity.status = status;
        entity.segment = "SMOKE";
        entity.tier = "GOLD";
        entity.lastCampaignCode = campaignCode;
        EcomCustomerEntityCacheBinding.save(database, CachePolicy.defaults(), entity);
    }

    private PGSimpleDataSource baseDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private JedisPooled jedis() {
        return new JedisPooled(URI.create(REDIS_URI));
    }

    private List<String> waitForConsumers(JedisPooled jedis, String streamKey, String consumerGroup, int minimumCount, Duration timeout) throws Exception {
        final List<String>[] holder = new List[]{List.of()};
        waitUntil(() -> {
            try {
                List<String> names = jedis.xinfoConsumers(streamKey, consumerGroup).stream()
                        .map(StreamConsumersInfo::getName)
                        .sorted()
                        .toList();
                holder[0] = names;
                return names.size() >= minimumCount;
            } catch (RuntimeException exception) {
                return false;
            }
        }, timeout, "expected at least " + minimumCount + " write-behind consumers");
        return holder[0];
    }

    private String waitForPendingConsumer(JedisPooled jedis, String streamKey, String consumerGroup, String consumerFragment, Duration timeout) throws Exception {
        final String[] holder = new String[1];
        waitUntil(() -> {
            try {
                for (StreamConsumersInfo consumer : jedis.xinfoConsumers(streamKey, consumerGroup)) {
                    if (consumer.getName().contains(consumerFragment) && consumer.getPending() > 0L) {
                        holder[0] = consumer.getName();
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException exception) {
                return false;
            }
        }, timeout, "owner node should own at least one pending stream entry");
        return holder[0];
    }

    private String waitForValue(JedisPooled jedis, String key, Duration timeout) throws Exception {
        final String[] holder = new String[1];
        waitUntil(() -> {
            holder[0] = jedis.get(key);
            return holder[0] != null && !holder[0].isBlank();
        }, timeout, "expected Redis key " + key + " to be populated");
        return holder[0];
    }

    private String waitUntilValueChanges(JedisPooled jedis, String key, String previousValue, Duration timeout) throws Exception {
        final String[] holder = new String[1];
        waitUntil(() -> {
            holder[0] = jedis.get(key);
            return holder[0] != null && !holder[0].isBlank() && !Objects.equals(previousValue, holder[0]);
        }, timeout, "expected Redis key " + key + " to change leadership owner");
        return holder[0];
    }

    private void writeReports(MultiInstanceCoordinationSmokeReport report) throws IOException {
        Path reportDirectory = Path.of(System.getProperty("cachedb.prod.reportDir", "target/cachedb-prodtest-reports"));
        Files.createDirectories(reportDirectory);
        Files.writeString(reportDirectory.resolve("multi-instance-coordination-smoke.json"), renderJson(report));
        Files.writeString(reportDirectory.resolve("multi-instance-coordination-smoke.md"), renderMarkdown(report));
    }

    private String renderJson(MultiInstanceCoordinationSmokeReport report) {
        return "{\"recordedAt\":\"" + report.recordedAt()
                + "\",\"uniqueConsumerNamesVerified\":" + report.uniqueConsumerNamesVerified()
                + ",\"observedConsumerNames\":" + toJsonArray(report.observedConsumerNames())
                + ",\"leaderLeaseFailoverVerified\":" + report.leaderLeaseFailoverVerified()
                + ",\"leaderLeaseKey\":\"" + jsonEscape(report.leaderLeaseKey()) + "\""
                + ",\"initialLeaderInstanceId\":\"" + jsonEscape(report.initialLeaderInstanceId()) + "\""
                + ",\"failoverLeaderInstanceId\":\"" + jsonEscape(report.failoverLeaderInstanceId()) + "\""
                + ",\"historySamplesBeforeFailover\":" + report.historySamplesBeforeFailover()
                + ",\"historySamplesAfterFailover\":" + report.historySamplesAfterFailover()
                + ",\"writeBehindClaimFailoverVerified\":" + report.writeBehindClaimFailoverVerified()
                + ",\"pendingOwnerConsumerName\":\"" + jsonEscape(report.pendingOwnerConsumerName()) + "\""
                + ",\"claimantClaimedCount\":" + report.claimantClaimedCount()
                + ",\"persistedAfterClaim\":" + report.persistedAfterClaim()
                + ",\"pendingDrainedAfterClaim\":" + report.pendingDrainedAfterClaim()
                + ",\"allSuccessful\":" + report.allSuccessful()
                + "}";
    }

    private String renderMarkdown(MultiInstanceCoordinationSmokeReport report) {
        StringBuilder builder = new StringBuilder("# Multi-Instance Coordination Smoke\n\n");
        builder.append("- Recorded At: `").append(report.recordedAt()).append("`\n");
        builder.append("- All Successful: `").append(report.allSuccessful()).append("`\n");
        builder.append("- Unique Consumer Names: `").append(report.uniqueConsumerNamesVerified()).append("`\n");
        builder.append("- Leader Lease Failover: `").append(report.leaderLeaseFailoverVerified()).append("`\n");
        builder.append("- Write-Behind Claim Failover: `").append(report.writeBehindClaimFailoverVerified()).append("`\n\n");
        builder.append("## Observed Consumers\n\n");
        for (String consumerName : report.observedConsumerNames()) {
            builder.append("- `").append(consumerName).append("`\n");
        }
        builder.append("\n## Leader Lease\n\n");
        builder.append("- Lease Key: `").append(report.leaderLeaseKey()).append("`\n");
        builder.append("- Initial Leader: `").append(report.initialLeaderInstanceId()).append("`\n");
        builder.append("- Failover Leader: `").append(report.failoverLeaderInstanceId()).append("`\n");
        builder.append("- History Samples Before Failover: `").append(report.historySamplesBeforeFailover()).append("`\n");
        builder.append("- History Samples After Failover: `").append(report.historySamplesAfterFailover()).append("`\n\n");
        builder.append("## Claim Failover\n\n");
        builder.append("- Pending Owner Consumer: `").append(report.pendingOwnerConsumerName()).append("`\n");
        builder.append("- Claimant Claimed Count: `").append(report.claimantClaimedCount()).append("`\n");
        builder.append("- Persisted After Claim: `").append(report.persistedAfterClaim()).append("`\n");
        builder.append("- Pending Drained After Claim: `").append(report.pendingDrainedAfterClaim()).append("`\n");
        return builder.toString();
    }

    private String toJsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(jsonEscape(values.get(index))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private void waitUntil(CheckedBooleanSupplier supplier, Duration timeout, String failureDetails) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (supplier.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Condition was not met within timeout. " + failureDetails);
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private record ConsumerAndLeaderOutcome(
            boolean uniqueConsumerNamesVerified,
            List<String> observedConsumerNames,
            boolean leaderLeaseFailoverVerified,
            String leaderLeaseKey,
            String initialLeaderInstanceId,
            String failoverLeaderInstanceId,
            long historySamplesBeforeFailover,
            long historySamplesAfterFailover
    ) {
    }

    private record ClaimFailoverOutcome(
            boolean writeBehindClaimFailoverVerified,
            String pendingOwnerConsumerName,
            long claimantClaimedCount,
            boolean persistedAfterClaim,
            boolean pendingDrainedAfterClaim
    ) {
    }

    private static final class InstanceHandle implements AutoCloseable {
        private final JedisPooled foregroundJedis;
        private final JedisPooled backgroundJedis;
        private final CacheDatabase database;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private InstanceHandle(JedisPooled foregroundJedis, JedisPooled backgroundJedis, CacheDatabase database) {
            this.foregroundJedis = foregroundJedis;
            this.backgroundJedis = backgroundJedis;
            this.database = database;
        }

        private CacheDatabase database() {
            return database;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                database.close();
            } finally {
                try {
                    foregroundJedis.close();
                } finally {
                    backgroundJedis.close();
                }
            }
        }
    }

}
