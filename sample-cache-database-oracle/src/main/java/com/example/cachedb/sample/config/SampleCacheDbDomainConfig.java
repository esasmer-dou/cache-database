package com.example.cachedb.sample.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class SampleCacheDbDomainConfig {

    @Bean
    Clock sampleClock() {
        return Clock.systemUTC();
    }
}
