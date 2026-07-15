package com.example.cachedb.sample.config;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SampleCacheDbDomainConfig {

    @Bean
    GeneratedCacheModule.Scope sampleDomain(CacheDatabase cacheDatabase) {
        return GeneratedCacheModule.using(cacheDatabase);
    }
}
