package com.example.cachedb.sample.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class WarmJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WarmJobService.class);
    private static final int QUEUE_CAPACITY = 8;
    private static final int RETAINED_JOB_LIMIT = 256;

    private final ConcurrentHashMap<String, WarmJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "sample-warm-job");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    public WarmJob submit(String route, Supplier<SampleWarmBackfillService.WarmResult> operation) {
        String jobId = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        WarmJob queued = new WarmJob(jobId, route, "QUEUED", now, null, null, null, null);
        jobs.put(jobId, queued);
        insertionOrder.add(jobId);
        trimHistory();
        try {
            executor.execute(() -> run(jobId, operation));
        } catch (RejectedExecutionException exception) {
            jobs.remove(jobId);
            insertionOrder.remove(jobId);
            throw new WarmQueueFullException(
                    "Warm queue is full. Retry after an existing job completes; capacity=" + QUEUE_CAPACITY
            );
        }
        return queued;
    }

    public Optional<WarmJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private void run(String jobId, Supplier<SampleWarmBackfillService.WarmResult> operation) {
        WarmJob queued = jobs.get(jobId);
        if (queued == null) {
            return;
        }
        long startedAt = Instant.now().toEpochMilli();
        jobs.put(jobId, queued.started(startedAt));
        try {
            SampleWarmBackfillService.WarmResult result = operation.get();
            jobs.computeIfPresent(jobId, (ignored, current) -> current.completed(result, Instant.now().toEpochMilli()));
        } catch (RuntimeException exception) {
            LOGGER.error("Warm job {} failed for route {}", jobId, queued.route(), exception);
            jobs.computeIfPresent(jobId, (ignored, current) -> current.failed(
                    exception.getClass().getSimpleName(),
                    "Warm job failed; inspect server logs using jobId=" + jobId,
                    Instant.now().toEpochMilli()
            ));
        }
    }

    private void trimHistory() {
        while (jobs.size() > RETAINED_JOB_LIMIT) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            WarmJob job = jobs.get(oldest);
            if (job != null && ("QUEUED".equals(job.status()) || "RUNNING".equals(job.status()))) {
                insertionOrder.add(oldest);
                return;
            }
            jobs.remove(oldest);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record WarmJob(
            String jobId,
            String route,
            String status,
            long submittedAtEpochMillis,
            Long startedAtEpochMillis,
            Long finishedAtEpochMillis,
            SampleWarmBackfillService.WarmResult result,
            JobError error
    ) {
        WarmJob started(long startedAt) {
            return new WarmJob(jobId, route, "RUNNING", submittedAtEpochMillis, startedAt, null, null, null);
        }

        WarmJob completed(SampleWarmBackfillService.WarmResult result, long finishedAt) {
            return new WarmJob(
                    jobId,
                    route,
                    "COMPLETED",
                    submittedAtEpochMillis,
                    startedAtEpochMillis,
                    finishedAt,
                    result,
                    null
            );
        }

        WarmJob failed(String errorType, String message, long finishedAt) {
            return new WarmJob(
                    jobId,
                    route,
                    "FAILED",
                    submittedAtEpochMillis,
                    startedAtEpochMillis,
                    finishedAt,
                    null,
                    new JobError(errorType, message)
            );
        }
    }

    public record JobError(String type, String message) {
    }
}
