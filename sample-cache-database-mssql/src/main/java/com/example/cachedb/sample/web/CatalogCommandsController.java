package com.example.cachedb.sample.web;

import com.example.cachedb.sample.catalog.*;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class CatalogCommandsController {
    private final CacheDistributedJobExecutor jobs;
    private final com.reactor.cachedb.spring.boot.snapshot.SnapshotJobs snapshots;

    public CatalogCommandsController(CacheDistributedJobExecutor jobs,
            com.reactor.cachedb.spring.boot.snapshot.SnapshotJobs snapshots) {
        this.jobs = jobs;
        this.snapshots = snapshots;
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

    @PostMapping("/dirty")
    public ResponseEntity<java.util.Map<String, Integer>> markDirty(@RequestBody CatalogRefreshCommand command) {
        return ResponseEntity.accepted().body(java.util.Map.of("added",
                snapshots.markDirty(CatalogConfiguration.PLAN, command.ids())));
    }

    @PostMapping("/dirty/flush")
    public ResponseEntity<CacheDistributedJobSnapshot> flushDirty() {
        return accepted(jobs.submit(CatalogDirtyJobHandler.DEFINITION, new CatalogRefreshCommand(java.util.List.of())));
    }

    private ResponseEntity<CacheDistributedJobSnapshot> accepted(CacheDistributedJobSnapshot job) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/warm/jobs/" + job.jobId()))
                .body(job);
    }
}
