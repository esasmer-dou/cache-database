package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.examples.demo.entity.DemoCartEntity;
import com.reactor.cachedb.examples.demo.entity.DemoCartEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoCustomerEntity;
import com.reactor.cachedb.examples.demo.entity.DemoCustomerEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntityCacheBinding;
import com.reactor.cachedb.examples.demo.entity.DemoProductEntity;
import com.reactor.cachedb.examples.demo.entity.DemoProductEntityCacheBinding;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.starter.CacheDatabaseAdmin;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class DemoBulkSeedBootstrapper implements DemoSeedBootstrapper {

    private static final String SCAN_CURSOR_START = "0";
    private static final long INITIAL_VERSION = 1L;
    private static final String NULL_SENTINEL = "__NULL__";

    private final JedisPooled jedis;
    private final DataSource dataSource;
    private final CacheDatabaseAdmin admin;
    private final RedisKeyStrategy keyStrategy;
    private final String keyPrefix;
    private final int postgresBatchSize;
    private final int redisBatchSize;
    private final boolean useDedicatedRedisDbReset;

    public DemoBulkSeedBootstrapper(
            JedisPooled jedis,
            DataSource dataSource,
            CacheDatabaseAdmin admin,
            String keyPrefix,
            boolean useDedicatedRedisDbReset
    ) {
        this(
                jedis,
                dataSource,
                admin,
                keyPrefix,
                useDedicatedRedisDbReset,
                Integer.getInteger("cachedb.demo.seed.postgresBatchSize", 2_000),
                Integer.getInteger("cachedb.demo.seed.redisBatchSize", 4_000)
        );
    }

    public DemoBulkSeedBootstrapper(
            JedisPooled jedis,
            DataSource dataSource,
            CacheDatabaseAdmin admin,
            String keyPrefix,
            boolean useDedicatedRedisDbReset,
            int postgresBatchSize,
            int redisBatchSize
    ) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.keyStrategy = new RedisKeyStrategy(keyPrefix);
        this.postgresBatchSize = Math.max(1, postgresBatchSize);
        this.redisBatchSize = Math.max(1, redisBatchSize);
        this.useDedicatedRedisDbReset = useDedicatedRedisDbReset;
    }

    @Override
    public void seed(DemoSeedPayload payload) {
        seed(payload, null);
    }

    @Override
    public void seed(DemoSeedPayload payload, Consumer<String> stageReporter) {
        stage(stageReporter, "Hazirlik yapiliyor");
        throwIfInterrupted();
        admin.applySchemaMigrationPlan();
        clearRedisState();
        truncateDemoTables();
        stage(stageReporter, "PostgreSQL insert yapiliyor");
        batchInsertPostgres(payload.customers(), DemoCustomerEntityCacheBinding.METADATA, DemoCustomerEntityCacheBinding.CODEC);
        batchInsertPostgres(payload.products(), DemoProductEntityCacheBinding.METADATA, DemoProductEntityCacheBinding.CODEC);
        batchInsertPostgres(payload.carts(), DemoCartEntityCacheBinding.METADATA, DemoCartEntityCacheBinding.CODEC);
        batchInsertPostgres(payload.orders(), DemoOrderEntityCacheBinding.METADATA, DemoOrderEntityCacheBinding.CODEC);
        batchInsertPostgres(payload.orderLines(), DemoOrderLineEntityCacheBinding.METADATA, DemoOrderLineEntityCacheBinding.CODEC);
        stage(stageReporter, "Redis index ve cache yaziliyor");
        batchInsertRedis(payload.customers(), DemoCustomerEntityCacheBinding.METADATA, DemoCustomerEntityCacheBinding.CODEC, entity -> entity.id);
        batchInsertRedis(payload.products(), DemoProductEntityCacheBinding.METADATA, DemoProductEntityCacheBinding.CODEC, entity -> entity.id);
        batchInsertRedis(payload.carts(), DemoCartEntityCacheBinding.METADATA, DemoCartEntityCacheBinding.CODEC, entity -> entity.id);
        batchInsertRedis(payload.orders(), DemoOrderEntityCacheBinding.METADATA, DemoOrderEntityCacheBinding.CODEC, entity -> entity.id);
        batchInsertRedis(payload.orderLines(), DemoOrderLineEntityCacheBinding.METADATA, DemoOrderLineEntityCacheBinding.CODEC, entity -> entity.id);
        stage(stageReporter, "Telemetri sifirlaniyor");
        admin.resetTelemetry();
        stage(stageReporter, "Tamamlandi");
    }

    private void clearRedisState() {
        throwIfInterrupted();
        if (useDedicatedRedisDbReset) {
            jedis.sendCommand(redis.clients.jedis.Protocol.Command.FLUSHDB, "ASYNC");
            return;
        }
        deleteByPattern(keyPrefix + ":*");
    }

    private void deleteByPattern(String pattern) {
        ScanParams params = new ScanParams().match(pattern).count(1_000);
        String cursor = SCAN_CURSOR_START;
        do {
            throwIfInterrupted();
            ScanResult<String> scan = jedis.scan(cursor, params);
            cursor = scan.getCursor();
            List<String> keys = scan.getResult();
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(String[]::new));
            }
        } while (!SCAN_CURSOR_START.equals(cursor));
    }

    private void truncateDemoTables() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                    TRUNCATE TABLE
                      cachedb_demo_order_lines,
                      cachedb_demo_orders,
                      cachedb_demo_carts,
                      cachedb_demo_products,
                      cachedb_demo_customers
                    RESTART IDENTITY
                    """)) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Demo bulk seed tabloları temizlenemedi", exception);
        }
    }

    private <T> void batchInsert(
            List<T> items,
            EntityMetadata<T, Long> metadata,
            EntityCodec<T> codec,
            Function<T, Long> idAccessor
    ) {
        if (items.isEmpty()) {
            return;
        }
        batchInsertPostgres(items, metadata, codec);
        batchInsertRedis(items, metadata, codec, idAccessor);
    }

    private <T> void batchInsertPostgres(
            List<T> items,
            EntityMetadata<T, Long> metadata,
            EntityCodec<T> codec
    ) {
        List<String> columns = new ArrayList<>(metadata.columns());
        columns.add(metadata.versionColumn());
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + metadata.tableName()
                + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int batched = 0;
                for (T item : items) {
                    throwIfInterrupted();
                    Map<String, Object> values = codec.toColumns(item);
                    int parameterIndex = 1;
                    for (String column : metadata.columns()) {
                        statement.setObject(parameterIndex++, values.get(column));
                    }
                    statement.setLong(parameterIndex, INITIAL_VERSION);
                    statement.addBatch();
                    batched++;
                    if (batched >= postgresBatchSize) {
                        statement.executeBatch();
                        connection.commit();
                        batched = 0;
                    }
                }
                if (batched > 0) {
                    statement.executeBatch();
                    connection.commit();
                }
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Demo bulk seed PostgreSQL insert başarısız", exception);
        }
    }

    private <T> void batchInsertRedis(
            List<T> items,
            EntityMetadata<T, Long> metadata,
            EntityCodec<T> codec,
            Function<T, Long> idAccessor
    ) {
        Pipeline pipeline = jedis.pipelined();
        try {
            int batched = 0;
            for (T item : items) {
                throwIfInterrupted();
                Long id = idAccessor.apply(item);
                String namespace = metadata.redisNamespace();
                pipeline.set(keyStrategy.entityKey(namespace, id), codec.toRedisValue(item));
                pipeline.set(keyStrategy.versionKey(namespace, id), String.valueOf(INITIAL_VERSION));
                pipeline.del(keyStrategy.tombstoneKey(namespace, id));
                pipeline.sadd(keyStrategy.indexAllKey(namespace), String.valueOf(id));
                Map<String, Object> values = codec.toColumns(item);
                LinkedHashMap<String, String> metaValues = new LinkedHashMap<>();
                for (String column : metadata.columns()) {
                    Object value = values.get(column);
                    String serialized = serializeValue(value);
                    metaValues.put(column, serialized);
                    pipeline.sadd(keyStrategy.indexExactKey(namespace, column, encodeKeyPart(serialized)), String.valueOf(id));
                    Double score = toScore(value);
                    if (score != null) {
                        pipeline.zadd(keyStrategy.indexSortKey(namespace, column), score, String.valueOf(id));
                    }
                }
                if (!metaValues.isEmpty()) {
                    pipeline.hset(keyStrategy.indexMetaKey(namespace, id), metaValues);
                }
                batched++;
                if (batched >= redisBatchSize) {
                    pipeline.sync();
                    pipeline.close();
                    pipeline = jedis.pipelined();
                    batched = 0;
                }
            }
            if (batched > 0) {
                pipeline.sync();
            }
        } finally {
            pipeline.close();
        }
    }

    private void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Demo bulk seed interrupted");
        }
    }

    private void stage(Consumer<String> stageReporter, String value) {
        throwIfInterrupted();
        if (stageReporter != null) {
            stageReporter.accept(value);
        }
    }

    private String serializeValue(Object value) {
        if (value == null) {
            return NULL_SENTINEL;
        }
        if (value instanceof Instant instant) {
            return String.valueOf(instant.toEpochMilli());
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return String.valueOf(offsetDateTime.toInstant().toEpochMilli());
        }
        if (value instanceof LocalDateTime localDateTime) {
            return String.valueOf(localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        if (value instanceof LocalDate localDate) {
            return String.valueOf(localDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        return String.valueOf(value);
    }

    private String encodeKeyPart(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Double toScore(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0d : 0.0d;
        }
        if (value instanceof Instant instant) {
            return (double) instant.toEpochMilli();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return (double) offsetDateTime.toInstant().toEpochMilli();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return (double) localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if (value instanceof LocalDate localDate) {
            return (double) localDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        return null;
    }
}
