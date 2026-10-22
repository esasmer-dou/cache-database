package com.example.cachedb.sample.web;

import com.example.cachedb.sample.catalog.*;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/catalog")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class CatalogCommandsController {
    private final CacheDistributedJobExecutor jobs;

    public CatalogCommandsController(CacheDistributedJobExecutor jobs) {
        this.jobs = jobs;
    }

    @PostMapping("/refresh")
    public ResponseEntity<CacheDistributedJobSnapshot> refresh(
            @RequestBody CatalogRefreshCommand command) {
        return accepted(jobs.submit(CatalogRefreshJobHandler.DEFINITION, command));
    }

    @PostMapping("/imports")
    public ResponseEntity<CacheDistributedJobSnapshot> importProducts(
            @RequestBody CatalogImportCommand command) {
        return accepted(jobs.submit(CatalogImportJobHandler.DEFINITION, command));
    }

    private ResponseEntity<CacheDistributedJobSnapshot> accepted(CacheDistributedJobSnapshot job) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/warm/jobs/" + job.jobId()))
                .body(job);
    }
}
