package com.example.cachedb.sample.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import com.reactor.cachedb.starter.CacheDatabase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 0.14.0 auto-configures job executors on workers; this explicit client bean only publishes. */
@Configuration(proxyBeanMethods = false)
@Profile("client")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class ClientJobSubmissionConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CacheDistributedJobExecutor.class)
    CacheDistributedJobExecutor clientJobPublisher(
            CacheDatabase database,
            ObjectMapper mapper,
            CacheDbSpringProperties properties,
            ObjectProvider<CacheDistributedJobHandler<?>> handlers) {
        return new CacheDistributedJobExecutor(
                database.backgroundRedisOperations(),
                mapper,
                database.instanceId(),
                properties.getJobs(),
                handlers.orderedStream().toList(),
                false);
    }
}
