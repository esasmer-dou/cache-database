package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.config.IncidentEmailConfig;
import com.reactor.cachedb.core.config.IncidentDeliveryDlqConfig;
import com.reactor.cachedb.core.config.AdminReportJobConfig;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminMonitoringOverheadTest {

    @Test
    void disabledSupportBundleShouldNotSpawnBackgroundThreads() {
        AdminMonitoringConfig monitoringConfig = AdminMonitoringConfig.builder()
                .enabled(false)
                .build();
        AdminReportJobConfig reportConfig = AdminReportJobConfig.builder()
                .enabled(true)
                .build();

        Set<String> before = cachedbThreadNames();
        MonitoringHistoryBuffer monitoringHistoryBuffer = MonitoringHistoryBuffer.disabled(monitoringConfig);
        AlertRouteHistoryBuffer alertRouteHistoryBuffer = AlertRouteHistoryBuffer.disabled(monitoringConfig);
        PerformanceHistoryBuffer performanceHistoryBuffer = PerformanceHistoryBuffer.disabled(monitoringConfig);
        AdminReportJobWorker adminReportJobWorker = AdminReportJobWorker.disabled(reportConfig);
        AdminIncidentDeliveryManager adminIncidentDeliveryManager = AdminIncidentDeliveryManager.disabled(monitoringConfig);
        try {
            monitoringHistoryBuffer.start();
            alertRouteHistoryBuffer.start();
            performanceHistoryBuffer.start();
            adminReportJobWorker.start();
            adminIncidentDeliveryManager.start();
            Set<String> after = cachedbThreadNames();
            System.out.println("admin-monitoring-benchmark disabledThreadDelta=" + (after.size() - before.size())
                    + " before=" + before.size()
                    + " after=" + after.size());
            assertEquals(before, after, "Disabled admin monitoring should not create background threads");
        } finally {
            adminIncidentDeliveryManager.close();
            adminReportJobWorker.close();
            performanceHistoryBuffer.close();
            alertRouteHistoryBuffer.close();
            monitoringHistoryBuffer.close();
        }
    }

    @Test
    void noOpPerformanceCollectorShouldDiscardMeasurements() {
        StoragePerformanceCollector collector = StoragePerformanceCollector.noop();

        collector.recordRedisRead("bulk-customer-read", 10_000);
        collector.recordRedisWrite("order-write-burst", 20_000);
        collector.recordPostgresRead("postgres-top-orders", 30_000);
        collector.recordPostgresWrite("postgres-order-flush", 40_000);

        assertFalse(collector.enabled());
        assertEquals(0L, collector.snapshot().redisRead().operationCount());
        assertEquals(0L, collector.snapshot().redisWrite().operationCount());
        assertEquals(0L, collector.snapshot().postgresRead().operationCount());
        assertEquals(0L, collector.snapshot().postgresWrite().operationCount());
        assertTrue(collector.snapshot().redisReadBreakdown().isEmpty());
        assertTrue(collector.snapshot().postgresWriteBreakdown().isEmpty());
    }

    @Test
    void enabledIncidentDeliveryStillSpawnsThreadsWhenExplicitlyTurnedOn() throws InterruptedException {
        AdminMonitoringConfig monitoringConfig = AdminMonitoringConfig.builder()
                .enabled(true)
                .incidentEmail(IncidentEmailConfig.builder()
                        .enabled(true)
                        .addToAddress("ops@example.local")
                        .build())
                .incidentDeliveryDlq(IncidentDeliveryDlqConfig.builder()
                        .enabled(false)
                        .build())
                .build();
        Set<String> before = cachedbThreadNames();
        AdminIncidentDeliveryManager manager = new AdminIncidentDeliveryManager(monitoringConfig, null);
        try {
            manager.start();
            Thread.sleep(150L);
            Set<String> after = cachedbThreadNames();
            System.out.println("admin-monitoring-benchmark enabledIncidentThreadDelta=" + (after.size() - before.size())
                    + " before=" + before.size()
                    + " after=" + after.size());
            assertTrue(after.stream().anyMatch(name -> name.startsWith("cachedb-incident-delivery")));
            assertTrue(after.size() >= before.size());
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldShowNoOpCollectorAsLowerHeapFootprint() {
        long baseline = usedMemory();
        Object[] noopCollectors = new Object[10_000];
        for (int index = 0; index < noopCollectors.length; index++) {
            noopCollectors[index] = StoragePerformanceCollector.noop();
        }
        long noopFootprint = Math.max(0L, usedMemory() - baseline);

        noopCollectors = null;
        forceGc();

        baseline = usedMemory();
        Object[] activeCollectors = new Object[10_000];
        for (int index = 0; index < activeCollectors.length; index++) {
            activeCollectors[index] = new StoragePerformanceCollector();
        }
        long activeFootprint = Math.max(0L, usedMemory() - baseline);

        System.out.println("admin-monitoring-benchmark noopCollectorBytes=" + noopFootprint
                + " activeCollectorBytes=" + activeFootprint
                + " activeMinusNoopBytes=" + Math.max(0L, activeFootprint - noopFootprint));
        assertTrue(activeFootprint > noopFootprint, "Active collector should retain more heap than the no-op collector");
    }

    private Set<String> cachedbThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("cachedb-"))
                .collect(Collectors.toSet());
    }

    private long usedMemory() {
        forceGc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void forceGc() {
        System.gc();
        System.runFinalization();
        try {
            Thread.sleep(50L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
