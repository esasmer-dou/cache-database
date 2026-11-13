package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RedisTrackingHeartbeatLeaseTest {
    @Test void firstBoundedRoundMayEstablishHealth() {
        assertTrue(RedisTrackingCoordinator.canRenewHealth(0, 100, 150, 100));
    }
    @Test void expiredPreviousLeaseCannotBeRevivedByAFastRound() {
        assertFalse(RedisTrackingCoordinator.canRenewHealth(150, 140, 150, 100));
        assertFalse(RedisTrackingCoordinator.canRenewHealth(149, 140, 150, 100));
    }
    @Test void overBudgetOrBackwardsRoundCannotRenewHealth() {
        assertFalse(RedisTrackingCoordinator.canRenewHealth(400, 100, 200, 100));
        assertFalse(RedisTrackingCoordinator.canRenewHealth(400, 200, 199, 100));
    }
    @Test void nanoTimeWrapRetainsRelativeDeadlineSemantics() {
        long started = Long.MAX_VALUE - 10, now = Long.MIN_VALUE + 10;
        assertTrue(RedisTrackingCoordinator.canRenewHealth(Long.MIN_VALUE + 100, started, now, 100));
        assertFalse(RedisTrackingCoordinator.canRenewHealth(Long.MAX_VALUE - 1, started, now, 100));
    }
}
