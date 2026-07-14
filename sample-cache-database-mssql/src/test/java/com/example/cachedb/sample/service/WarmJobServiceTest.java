package com.example.cachedb.sample.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmJobServiceTest {

    private final WarmJobService service = new WarmJobService();

    @AfterEach
    void close() {
        service.shutdown();
    }

    @Test
    void rejectsWorkWhenTheBoundedQueueIsFull() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        service.submit("blocking", () -> {
            started.countDown();
            await(release);
            return result();
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        for (int index = 0; index < 8; index++) {
            service.submit("queued-" + index, () -> result());
        }

        WarmQueueFullException exception = assertThrows(
                WarmQueueFullException.class,
                () -> service.submit("rejected", this::result)
        );
        assertEquals("Warm queue is full. Retry after an existing job completes; capacity=8", exception.getMessage());
        release.countDown();
    }

    private SampleWarmBackfillService.WarmResult result() {
        return new SampleWarmBackfillService.WarmResult(
                "test",
                "test",
                1,
                1,
                1,
                1,
                false,
                false,
                "test",
                List.of()
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }
}
