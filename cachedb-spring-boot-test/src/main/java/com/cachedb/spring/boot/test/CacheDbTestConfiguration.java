package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class CacheDbTestConfiguration {
    @Bean
    public CacheDbTestProbe cacheDbTestProbe(CacheDatabase cacheDatabase) {
        return new CacheDbTestProbe(cacheDatabase);
    }
}
