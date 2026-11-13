package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.ProtectedReadFixture.*;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RedisTenantReadFenceTest {
    @Test void captureInFlightIsRegisteredBeforeCloseAndCannotBeRelabelled() throws Exception {
        State state=new State();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        state.captureHook=()->{entered.countDown();await(release);};
        try(var fence=new RedisTenantReadFence(state,2,List.of());var worker=Executors.newSingleThreadExecutor()) {
            Future<?> read=worker.submit(()->{try(var ticket=fence.begin(KEY)){ticket.complete("never");}});
            await(entered);
            state.closeGeneration();
            assertFalse(fence.closeAndDrain(LEASE,1).drained());
            release.countDown();
            assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,()->read.get(5,TimeUnit.SECONDS)).getCause());
            assertTrue(fence.closeAndDrain(LEASE,1).drained());
        } finally {release.countDown();}
    }

    @Test void bothRuntimesMustPhysicallyDrainBeforeAcknowledgement() throws Exception {
        State state=new State();
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var a=new RedisTenantReadFence(state,2,List.of(store()));
                var b=new RedisTenantReadFence(state,2,List.of(store()));
                var workers=Executors.newFixedThreadPool(2)) {
            Callable<Object> first=()->blockedRead(a,entered,release);
            Callable<Object> second=()->blockedRead(b,entered,release);
            Future<?> left=workers.submit(first),right=workers.submit(second);
            await(entered);state.closeGeneration();
            assertFalse(a.closeAndDrain(LEASE,1).drained());
            assertFalse(b.closeAndDrain(LEASE,1).drained());
            assertNotEquals(a.runtimeId(),b.runtimeId());
            release.countDown();
            assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,()->left.get(5,TimeUnit.SECONDS)).getCause());
            assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,()->right.get(5,TimeUnit.SECONDS)).getCause());
            assertTrue(a.closeAndDrain(LEASE,1).drained());
            assertTrue(b.closeAndDrain(LEASE,1).drained());
            assertEquals(0,state.executions.get());
        } finally {release.countDown();}
    }

    private static Object blockedRead(RedisTenantReadFence fence,CountDownLatch entered,CountDownLatch release) {
        try(var ticket=fence.begin(KEY)) {
            ticket.read(()->{entered.countDown();await(release);return "sensitive_value";});
            ticket.publish(plan(ticket.generation()));
            return ticket.complete("never");
        }
    }

    @Test void callerCancellationDoesNotFabricatePhysicalCompletion() throws Exception {
        State state=new State();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var physical=new CountDownLatch(1);
        try(var fence=new RedisTenantReadFence(state,1,List.of());var worker=Executors.newSingleThreadExecutor()) {
            Future<?> future=worker.submit(()->{
                try(var ticket=fence.begin(KEY)) {
                    ticket.read(()->{entered.countDown();awaitPhysical(release);return null;});
                } finally {physical.countDown();}
            });
            await(entered);assertTrue(future.cancel(true));state.closeGeneration();
            assertFalse(fence.closeAndDrain(LEASE,1).drained());
            assertEquals(1,physical.getCount());
            release.countDown();await(physical);
            assertTrue(fence.closeAndDrain(LEASE,1).drained());
        } finally {release.countDown();}
    }

    @Test void l1FramesRejectOldGenerationsWithoutBreakingOrdinaryIdInvalidations() {
        State state=new State();var local=store();
        try(var fence=new RedisTenantReadFence(state,2,List.of(local))) {
            try(var ticket=fence.begin(KEY)) {
                fence.cache(ticket,local,"id","cipher",10000,9,local.capture("id"));
                assertEquals("cipher",fence.lookup(ticket,local,"id",0).payload());
                local.invalidate("id"); // Existing T2 invalidation identity, unchanged.
                assertNull(fence.lookup(ticket,local,"id",0));
                fence.cache(ticket,local,"id","cipher",10000,9,local.capture("id"));
                ticket.complete(null);
            }
            state.closeGeneration();state.reopen(); // Missed local ack must still never relabel old L1.
            try(var next=fence.begin(KEY)) {
                assertEquals(2,next.generation().value());
                assertNull(fence.lookup(next,local,"id",0));
                assertNull(local.get("id"));
            }
        }
    }

    @Test void invalidationBetweenPointIoAndL1FillRejectsTheOriginalStamp() {
        State state=new State();var local=store();
        try(var fence=new RedisTenantReadFence(state,2,List.of(local));var ticket=fence.begin(KEY)) {
            var original=local.capture("id");
            local.invalidate("id");
            fence.cache(ticket,local,"id","old_cipher",10000,9,original);
            assertNull(fence.lookup(ticket,local,"id",0));
        }
    }

    @Test void closeBetweenL1DecodeAndReturnRejectsTheResultAndClearsStore() {
        State state=new State();var local=store();
        try(var fence=new RedisTenantReadFence(state,2,List.of(local));var ticket=fence.begin(KEY)) {
            fence.cache(ticket,local,"id","cipher",10000,9,local.capture("id"));
            var held=fence.lookup(ticket,local,"id",0);
            assertNotNull(held);
            state.closeGeneration();
            assertFalse(fence.closeAndDrain(LEASE,1).drained());
            assertThrows(ProtectionException.class,()->ticket.complete(held.payload()));
            assertNull(local.get("id"));
        }
    }

    @Test void delayedFillAndOldPlanRejectAfterCloseAndReopen() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,2,List.of())) {
            RedisTenantReadFence.Publication old;
            try(var ticket=fence.begin(KEY)) {
                old=plan(ticket.generation());
                state.closeGeneration();
                assertThrows(ProtectionException.class,()->ticket.publish(old));
                assertThrows(ProtectionException.class,()->ticket.complete("never"));
            }
            state.reopen();
            try(var next=fence.begin(KEY)) {
                assertThrows(ProtectionException.class,()->next.publish(old));
                assertEquals(1L,next.publish(plan(next.generation())));
            }
            assertEquals(1,state.executions.get());
        }
    }

    @Test void boundedAdmissionReleasedTicketsAndLocalShutdownAreFailClosed() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,1,List.of());var ticket=fence.begin(KEY)) {
            assertEquals(ProtectionException.Code.BUSY,assertThrows(ProtectionException.class,()->fence.begin(KEY)).code());
            ticket.close();
            assertThrows(ProtectionException.class,()->ticket.complete("never"));
            try(var next=fence.begin(KEY)) {fence.close();assertThrows(ProtectionException.class,next::check);}
            assertThrows(ProtectionException.class,()->fence.begin(KEY));
        }
    }

    @Test void sourceAndTransportErrorsDiscardSensitiveCauses() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,2,List.of());var ticket=fence.begin(KEY)) {
            var readError=assertThrows(ProtectionException.class,()->ticket.read(()->{throw new IllegalStateException("sensitive_value");}));
            assertNull(readError.getCause());assertFalse(readError.toString().contains("sensitive_value"));
            state.command=plan->{throw new IllegalStateException("sensitive_value");};
            var fillError=assertThrows(ProtectionException.class,()->ticket.publish(plan(ticket.generation())));
            assertNull(fillError.getCause());assertFalse(fillError.toString().contains("sensitive_value"));
        }
    }

    @Test void ownedCallbacksPreserveOptimisticConflictsWithoutReleasingThePhysicalScope() {
        State state=new State();
        var conflict=new OptimisticWriteConflictException("ReadRow","id",0,9);
        try(var fence=new RedisTenantReadFence(state,1,List.of())) {
            try(var outer=fence.begin(KEY)) {
                assertSame(conflict,assertThrows(OptimisticWriteConflictException.class,()->outer.withReentrantReads(()->{
                    try(var inner=fence.begin(KEY)) {
                        return inner.read(()->{throw conflict;});
                    }
                })));
                assertSame(conflict,assertThrows(OptimisticWriteConflictException.class,
                        ()->outer.compute(()->{throw conflict;})));
                assertEquals(ProtectionException.Code.BUSY,assertThrows(ProtectionException.class,()->fence.begin(KEY)).code());
                outer.complete(null);
            }
            try(var next=fence.begin(KEY)) { next.complete(null); }
        }
    }

    @Test void optimisticConflictsCannotEscapeAfterTheirGenerationCloses() {
        for(boolean cpuOnly:List.of(false,true)) {
            State state=new State();
            var conflict=new OptimisticWriteConflictException("ReadRow","private_identity",0,9);
            try(var fence=new RedisTenantReadFence(state,1,List.of());var ticket=fence.begin(KEY)) {
                java.util.function.Supplier<Object> action=()->{state.closeGeneration();throw conflict;};
                var failure=assertThrows(ProtectionException.class,()->{
                    if(cpuOnly) ticket.compute(action); else ticket.read(action);
                });
                assertEquals(ProtectionException.Code.GENERATION_MISMATCH,failure.code());
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("private_identity"));
            }
        }
    }

    @Test void authorityErrorsRemainRedactedEvenIfTheyUseThePublicConflictType() {
        State state=new State();
        var conflict=new OptimisticWriteConflictException("ReadRow","private_identity",0,9);
        try(var fence=new RedisTenantReadFence(state,1,List.of())) {
            state.captureHook=()->{throw conflict;};
            var captureFailure=assertThrows(ProtectionException.class,()->fence.begin(KEY));
            assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,captureFailure.code());
            assertNull(captureFailure.getCause());
            state.captureHook=()->{};
            try(var ticket=fence.begin(KEY)) {
                state.command=plan->{throw conflict;};
                var publicationFailure=assertThrows(ProtectionException.class,()->ticket.publish(plan(ticket.generation())));
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,publicationFailure.code());
                assertNull(publicationFailure.getCause());
                assertFalse(publicationFailure.toString().contains("private_identity"));
            }
        }
    }

    @Test void unregisteredStoreCrossRuntimeTicketAndCrossThreadUseReject() throws Exception {
        State state=new State();var local=store();
        try(var a=new RedisTenantReadFence(state,2,List.of(local));var b=new RedisTenantReadFence(state,2,List.of(local));
                var ticket=a.begin(KEY);var worker=Executors.newSingleThreadExecutor()) {
            assertThrows(ProtectionException.class,()->a.lookup(ticket,store(),"id",0));
            assertThrows(ProtectionException.class,()->b.lookup(ticket,local,"id",0));
            var result=worker.submit(()->assertThrows(ProtectionException.class,ticket::generation));
            assertEquals(ProtectionException.Code.INVALID_REQUEST,result.get(5,TimeUnit.SECONDS).code());
        }
    }

    @Test void closeRequiresTheActualClosedLeaseAndPublicationIsBoundedAndRedacted() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,2,List.of())) {
            assertThrows(ProtectionException.class,()->fence.closeAndDrain(LEASE,1));
            var original=new Generation(KEY,1);
            assertThrows(ProtectionException.class,()->new RedisTenantReadFence.Publication(original,"owner","a",
                    "return 1",List.of("key"),List.of("x".repeat(1048577))));
            assertFalse(plan(original).toString().contains("owner"));
        }
    }
}
