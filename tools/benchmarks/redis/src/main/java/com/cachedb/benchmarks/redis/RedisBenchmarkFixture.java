package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.page.EntityOwnershipLoader;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.core.config.KeyspaceConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.RelationDefinition;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisEntityRepository;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisNearCacheCoordinator;
import com.reactor.cachedb.starter.CacheDatabase;
import java.io.PrintWriter;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

/** A private key prefix per fork; SQL is forbidden, not silently simulated. */
final class RedisBenchmarkFixture implements AutoCloseable {
    @FunctionalInterface
    interface DatabaseFactory {
        CacheDatabase create(JedisPooled redis, DataSource dataSource, CacheDatabaseConfig baseConfig);
    }
    @FunctionalInterface
    interface CoordinatorFactory {
        RedisNearCacheCoordinator create(CacheDatabase database,
                com.reactor.cachedb.redis.RedisClientOperations operations, RedisKeyStrategy keys);
    }
    record Row(Long id, String tenant, String group, long amount) {}

    static final class Metadata implements EntityMetadata<Row, Long> {
        private final boolean tenantScoped;
        Metadata(boolean tenantScoped) { this.tenantScoped = tenantScoped; }
        public String entityName() { return "BenchmarkRow"; }
        public String tableName() { return "benchmark_rows"; }
        public String redisNamespace() { return "benchmark_rows"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return Row::id; }
        public List<String> columns() { return List.of("id", "tenant", "group", "amount"); }
        public List<RelationDefinition> relations() { return List.of(); }
        public boolean deeplyImmutable() { return true; }
        public String tenantFieldName() { return tenantScoped ? "tenant" : null; }
        public String extractTenantId(Row row) { return tenantScoped ? row.tenant() : null; }
        public Map<String, String> columnTypes() {
            return Map.of("id", "java.lang.Long", "tenant", "java.lang.String",
                    "group", "java.lang.String", "amount", "java.lang.Long");
        }
    }

    static final class Codec implements EntityCodec<Row> {
        public String toRedisValue(Row row) {
            return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id().toString(), "tenant", row.tenant(),
                    "group", row.group(), "amount", Long.toString(row.amount())));
        }
        public Row fromRedisValue(String value) {
            var columns = LengthPrefixedPayloadCodec.decode(value);
            return new Row(Long.valueOf(columns.get("id")), columns.get("tenant"),
                    columns.get("group"), Long.parseLong(columns.get("amount")));
        }
        public Map<String, Object> toColumns(Row row) {
            return Map.of("id", row.id(), "tenant", row.tenant(), "group", row.group(), "amount", row.amount());
        }
        public boolean directColumnAccess() { return true; }
        public Object entityColumnValue(Row row, String column) {
            return switch (column) {
                case "id" -> row.id();
                case "tenant" -> row.tenant();
                case "group" -> row.group();
                case "amount" -> row.amount();
                default -> throw new IllegalArgumentException("Unknown benchmark column");
            };
        }
    }

    static final class NewRowsOnly implements EntityByIdLoader<Row, Long>, EntityOwnershipLoader<Long> {
        public Optional<Row> load(Long id) { return Optional.empty(); }
        public Map<Long, String> loadTenantOwners(Collection<Long> ids) { return Map.of(); }
    }

    final String prefix = "cachedb-bench-" + UUID.randomUUID();
    final String stream = prefix + ":writes";
    final JedisPooled redis;
    final CacheDatabase database;
    final EntityRepository<Row, Long> repository;
    final RedisNearCacheCoordinator coordinator;
    final RedisNearCacheCoordinator.Store store;
    private final ScheduledExecutorService streamMaintenance;
    private final boolean ownsCoordinator;

    RedisBenchmarkFixture(int rows, boolean tenantScoped, boolean near, boolean appendWrites) throws Exception {
        this(rows, tenantScoped, near, appendWrites, CacheDatabase::new,
                (database, operations, keys) -> new RedisNearCacheCoordinator(operations, keys), true);
    }

    /** Injected coordinators are borrowed from the injected database, never closed separately. */
    RedisBenchmarkFixture(int rows, boolean tenantScoped, boolean near, boolean appendWrites,
            DatabaseFactory databaseFactory, CoordinatorFactory coordinatorFactory) throws Exception {
        this(rows, tenantScoped, near, appendWrites, databaseFactory, coordinatorFactory, false);
    }

    private RedisBenchmarkFixture(int rows, boolean tenantScoped, boolean near, boolean appendWrites,
            DatabaseFactory databaseFactory, CoordinatorFactory coordinatorFactory, boolean ownsCoordinator) throws Exception {
        Objects.requireNonNull(databaseFactory, "databaseFactory");
        Objects.requireNonNull(coordinatorFactory, "coordinatorFactory");
        this.ownsCoordinator = ownsCoordinator;
        String endpoint = System.getProperty("cachedb.bench.redis");
        if (endpoint == null || endpoint.isBlank())
            throw new IllegalArgumentException("Set -Dcachedb.bench.redis=host:port (dedicated Redis fixture only)");
        String uri = endpoint.contains("://") ? endpoint : "redis://" + endpoint;
        redis = fairClient(uri);
        var metadata = new Metadata(tenantScoped);
        var codec = new Codec();
        var nearPolicy = near ? new NearCachePolicy(512, 60, 4L * 1024 * 1024) : NearCachePolicy.disabled();
        var policy = CachePolicy.builder().hotEntityLimit(20000).lruEvictionEnabled(false)
                .nearCachePolicy(nearPolicy).build();
        database = databaseFactory.create(redis, new ForbiddenDataSource(), CacheDatabaseConfig.builder()
                .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                .writeBehind(WriteBehindConfig.builder().streamKey(stream)
                        .compactionStreamKey(prefix + ":compaction").deadLetterStreamKey(prefix + ":dlq")
                        .consumerGroup(prefix).durableCompactionEnabled(!appendWrites).build()).build());
        var source = new NewRowsOnly();
        database.register(metadata, codec, policy, null, null, source, null);
        repository = database.repository(metadata, codec, policy);
        coordinator = near ? Objects.requireNonNull(coordinatorFactory.create(
                database, JedisPooledOperations.of(redis), new RedisKeyStrategy(prefix)), "coordinator") : null;
        if (near) {
            ((RedisEntityRepository<Row, Long>) repository).withNearCache(coordinator);
            store = coordinator.register(metadata.redisNamespace(), nearPolicy);
        } else store = null;
        database.start();
        try (var scope = tenantScoped ? CacheTenantContext.withTenant("tenant-a") : null) {
            for (long start = 1; start <= rows; start += 128) {
                var batch = new ArrayList<Row>(128);
                for (long id = start; id < start + 128 && id <= rows; id++)
                    batch.add(new Row(id, "tenant-a", "group-" + id % 20, id));
                if (repository.saveAll(batch).size() != batch.size())
                    throw new IllegalStateException("Benchmark seed batch was not fully accepted");
            }
        }
        if (near) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!coordinator.connected() && System.nanoTime() < deadline) Thread.sleep(10);
            if (!coordinator.connected()) throw new IllegalStateException("L1 invalidation subscription did not become ready");
            // Drain write invalidations before admitting the measured L1 fixture.
            Thread.sleep(100);
            for (long id = 1; id <= Math.min(rows, 100); id++) repository.findById(id).orElseThrow();
            if (store.lookup("1", 0) == null) throw new IllegalStateException("L1 fixture was not admitted");
        }
        streamMaintenance = appendWrites ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "benchmark-stream-maintenance");
            thread.setDaemon(true); return thread;
        }) : null;
        if (streamMaintenance != null) streamMaintenance.scheduleWithFixedDelay(
                () -> redis.xtrim(stream, 8192, true), 100, 100, TimeUnit.MILLISECONDS);
    }

    private static JedisPooled fairClient(String value) {
        URI uri = URI.create(value);
        if (uri.getUserInfo() != null || uri.getHost() == null)
            throw new IllegalArgumentException("Use a dedicated benchmark endpoint without credentials");
        var pool = new GenericObjectPoolConfig<redis.clients.jedis.Connection>();
        pool.setMaxTotal(32); pool.setMaxIdle(32); pool.setMinIdle(0);
        pool.setFairness(true); pool.setMaxWait(java.time.Duration.ofSeconds(10));
        pool.setTimeBetweenEvictionRuns(java.time.Duration.ofSeconds(30));
        pool.setMinEvictableIdleDuration(java.time.Duration.ofSeconds(60));
        pool.setNumTestsPerEvictionRun(3);
        var client = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(5000).socketTimeoutMillis(10000).blockingSocketTimeoutMillis(15000)
                .ssl("rediss".equalsIgnoreCase(uri.getScheme()));
        if (uri.getPath() != null && !uri.getPath().isBlank() && !uri.getPath().equals("/"))
            client.database(Integer.parseInt(uri.getPath().substring(1)));
        return new JedisPooled(pool, new HostAndPort(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379), client.build());
    }

    @Override public void close() {
        if (streamMaintenance != null) {
            streamMaintenance.shutdownNow();
            try { if (!streamMaintenance.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Benchmark stream maintenance did not stop"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
        if (ownsCoordinator && coordinator != null) coordinator.close();
        database.close();
        String cursor = ScanParams.SCAN_POINTER_START;
        int iterations = 0;
        do {
            var scanned = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(500));
            if (!scanned.getResult().isEmpty()) redis.unlink(scanned.getResult().toArray(String[]::new));
            cursor = scanned.getCursor();
            if (++iterations > 20000) throw new IllegalStateException("Benchmark cleanup scan limit exceeded");
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        redis.close();
    }

    private static final class ForbiddenDataSource implements DataSource {
        public Connection getConnection() throws SQLException { throw new SQLException("Redis-only benchmark accessed JDBC"); }
        public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter writer) { }
        public void setLoginTimeout(int seconds) { }
        public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("No JDBC wrapper"); }
        public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
