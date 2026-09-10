package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.spring.boot.CacheDatabaseSpringBootAutoConfiguration;
import com.reactor.cachedb.starter.CacheDatabase;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import redis.clients.jedis.JedisPooled;

import java.time.Clock;
import java.util.*;

import javax.sql.DataSource;

/**
 * Defining a SnapshotPlan bean is sufficient; applications never implement storage or scheduling.
 */
@AutoConfiguration(after = CacheDatabaseSpringBootAutoConfiguration.class)
@ConditionalOnBean({SnapshotPlan.class, CacheDatabase.class})
@EnableConfigurationProperties(SnapshotProperties.class)
public class CacheSnapshotAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    SnapshotJobs snapshotJobs(
            DataSource source,
            CacheDatabase database,
            @Qualifier("cacheDbBackgroundJedisPooled") JedisPooled redis,
            @Qualifier("cacheDbJedisPooled") JedisPooled readRedis,
            ObjectMapper mapper,
            SnapshotProperties properties,
            List<SnapshotPlan<?, ?>> plans) {
        Map<String, SnapshotSettings> settings = properties.jobs();
        var names = plans.stream().map(SnapshotPlan::name).toList();
        for (String name : settings.keySet())
            if (!names.contains(name))
                throw new IllegalArgumentException(
                        "Snapshot configuration references an unknown plan: " + name);
        return new SnapshotJobs(
                source,
                redis,
                readRedis,
                mapper,
                Clock.systemUTC(),
                database.config().keyspace().keyPrefix(),
                plans,
                settings);
    }
}
