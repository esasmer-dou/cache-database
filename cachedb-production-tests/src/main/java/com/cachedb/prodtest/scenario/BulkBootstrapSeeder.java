package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

public final class BulkBootstrapSeeder {
    private static final long INITIAL_VERSION = Long.parseLong(System.getProperty("cachedb.prod.seed.initialVersion", "1"));

    private final int postgresBatchSize;
    private final int redisBatchSize;

    public BulkBootstrapSeeder() {
        this(
                Integer.getInteger("cachedb.prod.seed.postgresBatchSize", 1_000),
                Integer.getInteger("cachedb.prod.seed.redisBatchSize", 2_000)
        );
    }

    public BulkBootstrapSeeder(int postgresBatchSize, int redisBatchSize) {
        this.postgresBatchSize = Math.max(1, postgresBatchSize);
        this.redisBatchSize = Math.max(1, redisBatchSize);
    }

    public <T> void seedRange(
            Connection connection,
            JedisPooled jedis,
            RedisKeyStrategy keyStrategy,
            EntityMetadata<T, Long> metadata,
            EntityCodec<T> codec,
            long startIdInclusive,
            long endIdInclusive,
            LongFunction<T> entityFactory
    ) throws SQLException {
        batchInsertPostgres(connection, metadata, codec, startIdInclusive, endIdInclusive, entityFactory);
        batchInsertRedis(jedis, keyStrategy, metadata, codec, startIdInclusive, endIdInclusive, entityFactory);
    }

    private <T> void batchInsertPostgres(
            Connection connection,
            EntityMetadata<T, Long> metadata,
            EntityCodec<T> codec,
            long startIdInclusive,
            long endIdInclusive,
            LongFunction<T> entityFactory
    ) throws SQLException {
        List<String> columns = new ArrayList<>(metadata.columns());
        columns.add(metadata.versionColumn());
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + metadata.tableName()
                + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int batched = 0;
            for (long id = startIdInclusive; id <= endIdInclusive; id++) {
                T entity = entityFactory.apply(id);
                Map<String, Object> columnsMap = codec.toColumns(entity);
                int parameterIndex = 1;
                for (String column : metadata.columns()) {
                    statement.setObject(parameterIndex++, columnsMap.get(column));
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
    }

    private <T> void batchInsertRedis(
            JedisPooled jedis,
            RedisKeyStrategy keyStrategy,
            EntityMetadata<T, Long> metadata,
            EntityCodec<T> codec,
            long startIdInclusive,
            long endIdInclusive,
            LongFunction<T> entityFactory
    ) {
        int batched = 0;
        Pipeline pipeline = jedis.pipelined();
        try {
            for (long id = startIdInclusive; id <= endIdInclusive; id++) {
                T entity = entityFactory.apply(id);
                pipeline.set(keyStrategy.entityKey(metadata.redisNamespace(), id), codec.toRedisValue(entity));
                pipeline.set(keyStrategy.versionKey(metadata.redisNamespace(), id), String.valueOf(INITIAL_VERSION));
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
}
