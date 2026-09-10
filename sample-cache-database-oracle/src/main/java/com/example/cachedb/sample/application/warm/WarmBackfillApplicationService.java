package com.example.cachedb.sample.application.warm;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.service.SampleWarmJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import com.reactor.cachedb.spring.boot.CacheScheduledWarmRegistry;
import com.reactor.cachedb.spring.boot.CacheScheduledWarmSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class WarmBackfillApplicationService {

    private final CacheDistributedJobExecutor jobs;
    private final ObjectProvider<CacheScheduledWarmRegistry> schedules;

    public WarmBackfillApplicationService(
            CacheDistributedJobExecutor jobs,
            ObjectProvider<CacheScheduledWarmRegistry> schedules
    ) {
        this.jobs = jobs;
        this.schedules = schedules;
    }

    public CacheDistributedJobSnapshot submit(SampleWarmCommand command) {
        return jobs.submit(SampleWarmJobHandler.DEFINITION, command);
    }

    public CacheDistributedJobSnapshot job(String jobId) {
        return jobs.find(jobId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Warm job", jobId));
    }

    public List<CacheScheduledWarmSnapshot> schedules() {
        CacheScheduledWarmRegistry registry = schedules.getIfAvailable();
        return registry == null ? List.of() : registry.snapshots();
    }
}
