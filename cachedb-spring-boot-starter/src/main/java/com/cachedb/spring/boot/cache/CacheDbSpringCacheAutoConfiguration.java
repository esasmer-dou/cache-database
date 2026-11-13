package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.spring.boot.CacheDatabaseSpringBootAutoConfiguration;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@AutoConfiguration(after = CacheDatabaseSpringBootAutoConfiguration.class, before = CacheAutoConfiguration.class)
@ConditionalOnClass({CacheManager.class, RedisClientOperations.class})
@ConditionalOnProperty(prefix = "cachedb.spring-cache", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(CacheManager.class)
@EnableConfigurationProperties(CacheDbSpringCacheProperties.class)
public class CacheDbSpringCacheAutoConfiguration {
    @Bean(destroyMethod = "close")
    CacheDbCacheManager cacheDbCacheManager(CacheDbSpringCacheProperties properties,
            ObjectProvider<RedisClientOperations> operations, ObjectProvider<CacheDatabase> databases,
            @Qualifier("cacheDbJedisPooled") ObjectProvider<JedisPooled> pooled,
            ObjectProvider<CacheDbCacheCodec<?>> codecs) {
        RedisClientOperations redis = operations.getIfAvailable();
        CacheDatabase database = databases.getIfAvailable();
        if (redis == null && database != null) redis = database.redisOperations();
        if (redis == null) {
            JedisPooled jedis = pooled.getIfAvailable();
            if (jedis != null) redis = JedisPooledOperations.of(jedis);
        }
        if (redis == null)
            throw new IllegalStateException("Spring Cache requires an existing RedisClientOperations, CacheDatabase or cacheDbJedisPooled bean");
        RedisClientOperations invalidation = database == null ? redis : database.backgroundRedisOperations();
        String prefix = properties.keyPrefix() != null ? properties.keyPrefix()
                : database == null ? "cachedb" : database.config().keyspace().keyPrefix();
        List<CacheDbCacheCodec<?>> schemas = codecs.stream().limit(257).toList();
        return new CacheDbCacheManager(redis, invalidation, prefix, properties.configurations(), schemas,
                properties.maxConcurrentOperations(), new SingleFlightConfig(true, properties.maxInFlightKeys(),
                        properties.maxWaiters(), properties.loadTimeout().toMillis(),
                        CacheDbCacheConfiguration.MAX_VALUE_BYTES));
    }

    @Bean(name = "cacheDbKeyGenerator")
    @ConditionalOnMissingBean(name = "cacheDbKeyGenerator")
    KeyGenerator cacheDbKeyGenerator() { return new CacheDbKeyGenerator(); }

    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    CachingConfigurer cacheDbCachingConfigurer(CacheDbCacheManager manager,
            @Qualifier("cacheDbKeyGenerator") KeyGenerator generator) {
        return new CachingConfigurer() {
            @Override public CacheManager cacheManager() { return manager; }
            @Override public KeyGenerator keyGenerator() { return generator; }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        @ConditionalOnMissingBean(CacheDbCacheMetrics.class)
        CacheDbCacheMetrics cacheDbCacheMetrics(CacheDbCacheManager manager) { return new CacheDbCacheMetrics(manager); }
    }
}
