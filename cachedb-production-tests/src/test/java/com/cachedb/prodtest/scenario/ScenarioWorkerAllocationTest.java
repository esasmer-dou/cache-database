package com.reactor.cachedb.prodtest.scenario;

import com.reactor.cachedb.prodtest.scenario.EcommerceProductionScenarioRunner.WorkerAllocation;
import com.reactor.cachedb.prodtest.scenario.EcommerceProductionScenarioRunner.WorkerGroup;
import com.reactor.cachedb.prodtest.scenario.EcommerceProductionScenarioRunner.WorkerMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static com.reactor.cachedb.prodtest.scenario.EcommerceProductionScenarioRunner.allocateWorkers;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioWorkerAllocationTest {
    @Test
    void disabledSeparationUsesMixedWorkersForEveryProfile() {
        for (int reads : new int[]{0, 60, 100}) {
            assertSingleGroup(allocateWorkers(profile(8, reads), 100L, false, -1.0d),
                    WorkerMode.MIXED, 8, 100L);
        }
    }

    @Test
    void singleWorkerUsesMixedModeWithoutAddingASecondWorker() {
        for (boolean separated : new boolean[]{false, true}) {
            for (int reads : new int[]{0, 60, 100}) {
                assertSingleGroup(allocateWorkers(profile(1, reads), 1L, separated, -1.0d),
                        WorkerMode.MIXED, 1, 1L);
            }
        }
    }

    @Test
    void pureProfilesSelectOnlyTheirOwnPathWhenSeparated() {
        for (long tps : new long[]{1L, 100L}) {
            assertSingleGroup(allocateWorkers(profile(8, 100), tps, true, 0.5d),
                    WorkerMode.READ_ONLY, 8, tps);
            assertSingleGroup(allocateWorkers(profile(8, 0), tps, true, 0.5d),
                    WorkerMode.WRITE_ONLY, 8, tps);
        }
    }

    @Test
    void separatedPoolsConserveWorkerCountAndExactTpsRemainder() {
        var allocation = allocateWorkers(profile(10, 60), 101L, true, -1.0d);
        assertEquals(10, allocation.totalWorkers());
        assertEquals(2, allocation.groups().size());
        assertEquals(new WorkerGroup(WorkerMode.READ_ONLY, 6, 61L), allocation.groups().get(0));
        assertEquals(new WorkerGroup(WorkerMode.WRITE_ONLY, 4, 40L), allocation.groups().get(1));
    }

    @Test
    void readShareOverrideChangesAllocationWithoutCreatingExtraWorkersOrTps() {
        var allocation = allocateWorkers(profile(10, 60), 101L, true, 0.2d);
        assertEquals(new WorkerGroup(WorkerMode.READ_ONLY, 2, 20L), allocation.groups().get(0));
        assertEquals(new WorkerGroup(WorkerMode.WRITE_ONLY, 8, 81L), allocation.groups().get(1));
        for (double share : new double[]{0.0d, 1.0d, 2.0d}) {
            var small = allocateWorkers(profile(2, 60), 2L, true, share);
            assertEquals(2, small.totalWorkers());
            for (WorkerGroup group : small.groups()) {
                assertEquals(1, group.workers());
                assertEquals(1L, group.targetTps());
            }
        }
    }

    @Test
    void oneTpsMixedProfileKeepsWorkersWithoutAllocatingTwoTps() {
        var allocation = allocateWorkers(profile(8, 60), 1L, true, -1.0d);
        assertSingleGroup(allocation, WorkerMode.MIXED, 8, 1L);
        var group = allocation.groups().get(0);
        assertEquals(TimeUnit.SECONDS.toNanos(8L), group.nanosPerOperation());
        for (int worker = 0; worker < group.workers(); worker++) {
            assertEquals(TimeUnit.SECONDS.toNanos(worker), group.initialDelayNanos(worker));
        }
    }

    @Test
    void allocationAndStaggeredPacingNeverMultiplyLowTpsBudgets() {
        for (int workers : new int[]{1, 2, 3, 8, 64}) {
            for (long tps : new long[]{1L, 2L, 3L, 7L, 100L, 101L}) {
                for (int reads : new int[]{0, 1, 50, 99, 100}) {
                    for (boolean separated : new boolean[]{false, true}) {
                        var allocation = allocateWorkers(profile(workers, reads), tps, separated, -1.0d);
                        assertEquals(workers, allocation.totalWorkers());
                        assertEquals(tps, allocation.groups().stream().mapToLong(WorkerGroup::targetTps).sum());
                        for (WorkerGroup group : allocation.groups()) {
                            assertTrue(group.workers() > 0);
                            assertTrue(group.targetTps() > 0L);
                            // Ceil division must not turn fractional per-worker TPS into a one-TPS floor.
                            assertTrue(group.nanosPerOperation() >=
                                    (double) TimeUnit.SECONDS.toNanos(group.workers()) / group.targetTps());
                        }
                        for (int seconds : new int[]{1, 2, 3}) {
                            assertTrue(scheduledAttempts(allocation, TimeUnit.SECONDS.toNanos(seconds)) <= tps * seconds,
                                    "Offered slots exceeded TPS budget: " + allocation);
                        }
                    }
                }
            }
        }
    }

    @Test
    void pacingRoundsUpNonDivisibleIntervalsRatherThanExceedingBudget() {
        var group = new WorkerGroup(WorkerMode.MIXED, 2, 3L);
        assertEquals(666_666_667L, group.nanosPerOperation());
        assertEquals(333_333_334L, group.initialDelayNanos(1));
        assertThrows(IllegalArgumentException.class, () -> group.initialDelayNanos(-1));
        assertThrows(IllegalArgumentException.class, () -> group.initialDelayNanos(2));
    }

    @Test
    void invalidTpsAndNonfiniteReadShareFailBeforeWorkerSubmission() {
        for (long tps : new long[]{0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> allocateWorkers(profile(8, 60), tps, true, -1.0d));
        }
        for (double share : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> allocateWorkers(profile(8, 60), 100L, true, share));
        }
    }

    private static long scheduledAttempts(WorkerAllocation allocation, long durationNanos) {
        long attempts = 0L;
        for (WorkerGroup group : allocation.groups()) {
            for (int worker = 0; worker < group.workers(); worker++) {
                long first = group.initialDelayNanos(worker);
                if (first < durationNanos) attempts += 1L + (durationNanos - 1L - first) / group.nanosPerOperation();
            }
        }
        return attempts;
    }

    private static void assertSingleGroup(WorkerAllocation allocation, WorkerMode mode, int workers, long tps) {
        assertEquals(workers, allocation.totalWorkers());
        assertEquals(1, allocation.groups().size());
        assertEquals(new WorkerGroup(mode, workers, tps), allocation.groups().get(0));
    }

    private static EcommerceScenarioProfile profile(int workers, int reads) {
        return new EcommerceScenarioProfile("allocation-control", EcommerceScenarioKind.LOAD, "Synthetic allocation only",
                100, 1, workers, 10, 10, 2, reads, 0, 100 - reads, 0, 0, 0, 1, 10, 10, 10, 30, 30);
    }
}
