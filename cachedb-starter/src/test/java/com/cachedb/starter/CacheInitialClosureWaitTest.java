package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Scheduling/budget unit tests, not native roster, SQL or physical-drain attestation. */
class CacheInitialClosureWaitTest {
    private static TenantReadFence.Evidence evidence(TenantReadFence.State state) {
        return new TenantReadFence.Evidence(state,"",state==TenantReadFence.State.CLOSED?ProtectionKey.hash("unit-evidence"):null,
                2,state==TenantReadFence.State.CLOSED?2:1);
    }
    @Test void peerAcknowledgementCanArriveWithoutReacquiringOrReplacingTheOriginalLease() {
        var calls=new AtomicInteger();var time=new AtomicLong();var closed=evidence(TenantReadFence.State.CLOSED);
        var result=CacheInitialNativeBridge.awaitClosure(() -> calls.incrementAndGet()==3?closed:evidence(TenantReadFence.State.DRAINING),
                time::get,time::addAndGet);
        assertSame(closed,result);assertEquals(3,calls.get());assertEquals(20_000_000L,time.get());
    }
    @Test void missingAcknowledgementStopsAtFixedRoundBoundEvenWhenClockDoesNotAdvance() {
        var calls=new AtomicInteger();var pauses=new AtomicInteger();
        var failure=assertThrows(ProtectionException.class,() -> CacheInitialNativeBridge.awaitClosure(() -> {
            calls.incrementAndGet();return evidence(TenantReadFence.State.DRAINING);
        },() -> 0L,ignored -> pauses.incrementAndGet()));
        assertEquals(ProtectionException.Code.BUSY,failure.code());assertEquals(32,calls.get());assertEquals(31,pauses.get());
    }
    @Test void healthyHalfSecondPeerLoopCanAcknowledgeTheSameLeaseAfterFourHundredMillis() {
        var time=new AtomicLong();var calls=new AtomicInteger();var closed=evidence(TenantReadFence.State.CLOSED);
        var result=CacheInitialNativeBridge.awaitClosure(() -> {
            calls.incrementAndGet();
            return time.get()>=400_000_000L?closed:evidence(TenantReadFence.State.DRAINING);
        },time::get,time::addAndGet);
        assertSame(closed,result);assertTrue(time.get()>=400_000_000L);assertTrue(time.get()<5_000_000_000L);
        assertTrue(calls.get()<=32);
    }
    @Test void lateAcknowledgementStillFitsTheOriginalDeadlineAndAttemptLimit() {
        var time=new AtomicLong();var calls=new AtomicInteger();var pauses=new AtomicInteger();
        var closed=evidence(TenantReadFence.State.CLOSED);
        var result=CacheInitialNativeBridge.awaitClosure(() -> {
            calls.incrementAndGet();
            return time.get()>=4_500_000_000L?closed:evidence(TenantReadFence.State.DRAINING);
        },time::get,nanos -> {
            assertTrue(nanos>0);assertTrue(nanos<=5_000_000_000L-time.get());
            if(pauses.getAndIncrement()<2)assertEquals(10_000_000L,nanos);
            time.addAndGet(nanos);
        });
        assertSame(closed,result);assertTrue(time.get()>=4_500_000_000L);assertTrue(time.get()<5_000_000_000L);
        assertTrue(calls.get()<=32);assertTrue(pauses.get()<=31);
    }
    @Test void missingPeerUsesTheDeclaredBudgetWithoutRenewingTheLeaseOrAddingAttempts() {
        var time=new AtomicLong();var calls=new AtomicInteger();var pauses=new AtomicInteger();
        var failure=assertThrows(ProtectionException.class,() -> CacheInitialNativeBridge.awaitClosure(() -> {
            calls.incrementAndGet();return evidence(TenantReadFence.State.DRAINING);
        },time::get,nanos -> {pauses.incrementAndGet();time.addAndGet(nanos);}));
        assertEquals(ProtectionException.Code.BUSY,failure.code());assertEquals(32,calls.get());assertEquals(31,pauses.get());
        assertTrue(time.get()>=4_000_000_000L);assertTrue(time.get()<5_000_000_000L);
    }
    @Test void physicalPollDeadlineIsNotRenewedByRetriesAndAnOverdueClosedReplyIsNotAccepted() {
        var time=new AtomicLong();var calls=new AtomicInteger();
        var failure=assertThrows(ProtectionException.class,() -> CacheInitialNativeBridge.awaitClosure(() -> {
            calls.incrementAndGet();time.addAndGet(5_000_000_000L);return evidence(TenantReadFence.State.CLOSED);
        },time::get,ignored -> fail("No pause after exhausted budget")));
        assertEquals(ProtectionException.Code.BUSY,failure.code());assertEquals(1,calls.get());
    }
    @Test void oversleepDoesNotStartAnotherPhysicalPollAfterTheDeadline() {
        var time=new AtomicLong();var calls=new AtomicInteger();var pauses=new AtomicInteger();
        var failure=assertThrows(ProtectionException.class,() -> CacheInitialNativeBridge.awaitClosure(() -> {
            calls.incrementAndGet();return evidence(TenantReadFence.State.DRAINING);
        },time::get,ignored -> {pauses.incrementAndGet();time.addAndGet(5_000_000_001L);}));
        assertEquals(ProtectionException.Code.BUSY,failure.code());assertEquals(1,calls.get());assertEquals(1,pauses.get());
    }
    @Test void interruptionDuringPauseDoesNotStartAnotherPhysicalPoll() {
        var calls=new AtomicInteger();
        try {
            var failure=assertThrows(ProtectionException.class,() -> CacheInitialNativeBridge.awaitClosure(() -> {
                calls.incrementAndGet();return evidence(TenantReadFence.State.DRAINING);
            },() -> 0L,ignored -> Thread.currentThread().interrupt()));
            assertEquals(ProtectionException.Code.BUSY,failure.code());assertEquals(1,calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test void incompleteCoverageIsReturnedForFailClosedHandlingWithoutBusyRetry() {
        var incomplete=evidence(TenantReadFence.State.INCOMPLETE);
        assertSame(incomplete,CacheInitialNativeBridge.awaitClosure(() -> incomplete,() -> 0L,
                ignored -> fail("Unknown ownership must not be polled into acceptance")));
    }
    @Test void interruptedWaitStopsWithoutClearingTheInterruptOrClaimingAnAcknowledgement() {
        Thread.currentThread().interrupt();
        try {
            var failure=assertThrows(ProtectionException.class,() -> CacheInitialNativeBridge.awaitClosure(
                    () -> evidence(TenantReadFence.State.DRAINING),() -> 0L,ignored -> fail("Interrupted wait must not park")));
            assertEquals(ProtectionException.Code.BUSY,failure.code());assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
}
