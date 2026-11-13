package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.ProtectedReadFixture.*;
import com.reactor.cachedb.core.protection.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(20)
class RedisTenantReadFenceReuseTest {
    @Test void exactScopeBorrowsCapacityOneAndInnerCloseDoesNotReleaseOuterPhysicalPermit() throws Exception {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,1,List.of());var outer=fence.begin(KEY)) {
            outer.withReentrantReads(() -> {
                try(var inner=fence.begin(KEY)) {
                    assertNotSame(outer,inner);assertSame(outer.generation(),inner.generation());
                    assertEquals("value",inner.complete("value"));
                    assertEquals(1L,inner.publish(plan(inner.generation())));
                }
                outer.check();
                var outsider=new FutureTask<>(() -> assertThrows(ProtectionException.class,() -> fence.begin(KEY)).code());
                var thread=Thread.ofVirtual().start(outsider);
                try {assertEquals(ProtectionException.Code.BUSY,outsider.get(5,TimeUnit.SECONDS));}
                catch(Exception failure){throw new AssertionError(failure);}
                finally {join(thread);}
                return null;
            });
            assertEquals(ProtectionException.Code.BUSY,assertThrows(ProtectionException.class,() -> fence.begin(KEY)).code());
            state.closeGeneration();assertEquals(1,fence.closeAndDrain(LEASE,1).active());
        }
    }

    @Test void differentKeyAndDifferentThreadCannotBorrowTheOwnedScope() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,1,List.of());var outer=fence.begin(KEY)) {
            outer.withReentrantReads(() -> {
                assertEquals(ProtectionException.Code.BUSY,assertThrows(ProtectionException.class,
                        () -> fence.begin(ProtectionKey.of(NS,"different"))).code());
                var escaped=new FutureTask<>(() -> assertThrows(ProtectionException.class,outer::generation).code());
                var thread=Thread.ofVirtual().start(escaped);
                try {assertEquals(ProtectionException.Code.INVALID_REQUEST,escaped.get(5,TimeUnit.SECONDS));}
                catch(Exception failure){throw new AssertionError(failure);}
                finally {join(thread);}
                return null;
            });
        }
    }

    @Test void originalGenerationAndPhysicalCountSurviveClosureDuringNestedRead() throws Exception {
        State state=new State();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var fence=new RedisTenantReadFence(state,1,List.of())) {
            var task=new FutureTask<>(() -> {
                try(var outer=fence.begin(KEY)) {
                    return outer.withReentrantReads(() -> {
                        try(var inner=fence.begin(KEY)) {
                            assertSame(outer.generation(),inner.generation());
                            return inner.read(() -> {entered.countDown();awaitPhysical(release);return "private_value";});
                        }
                    });
                }
            });
            var thread=Thread.ofVirtual().start(task);
            try {
                await(entered);state.closeGeneration();
                var draining=fence.closeAndDrain(LEASE,1);assertFalse(draining.drained());assertEquals(1,draining.active());
                assertFalse(task.isDone());release.countDown();
                var failure=assertInstanceOf(ProtectionException.class,assertThrows(ExecutionException.class,
                        () -> task.get(5,TimeUnit.SECONDS)).getCause());
                assertNull(failure.getCause());assertFalse(failure.toString().contains("private_value"));
                assertTrue(fence.closeAndDrain(LEASE,1).drained());
            }finally{release.countDown();join(thread);}
        }
    }

    @Test void outOfOrderHandleCloseCannotAcknowledgeAnExecutingDeliveryScope() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,1,List.of());var outer=fence.begin(KEY)) {
            assertThrows(ProtectionException.class,() -> outer.withReentrantReads(() -> {
                try(var inner=fence.begin(KEY)) {
                    outer.close();state.closeGeneration();
                    assertEquals(1,fence.closeAndDrain(LEASE,1).active());
                    assertThrows(ProtectionException.class,inner::check);
                }
                assertEquals(1,fence.closeAndDrain(LEASE,1).active());
                return null;
            }));
            assertTrue(fence.closeAndDrain(LEASE,1).drained());
        }
    }

    @Test void outstandingBorrowedHandlesAreBoundedAndCleanupAllowsAnotherScope() {
        State state=new State();
        try(var fence=new RedisTenantReadFence(state,1,List.of());var outer=fence.begin(KEY)) {
            outer.withReentrantReads(() -> {
                var handles=new ArrayList<RedisTenantReadFence.Ticket>();
                try {
                    for(int i=0;i<64;i++)handles.add(fence.begin(KEY));
                    assertEquals(ProtectionException.Code.BUSY,assertThrows(ProtectionException.class,() -> fence.begin(KEY)).code());
                    handles.getFirst().close();handles.getFirst().close();
                    try(var next=fence.begin(KEY)){assertSame(outer.generation(),next.generation());}
                }finally{handles.forEach(RedisTenantReadFence.Ticket::close);}
                return null;
            });
            outer.withReentrantReads(() -> {try(var next=fence.begin(KEY)){next.check();}return null;});
        }
    }

    @Test void failedCaptureIncludingErrorReleasesPhysicalRegistration() {
        State state=new State();var injected=new AssertionError("private_capture");
        try(var fence=new RedisTenantReadFence(state,1,List.of())) {
            state.captureHook=() -> {throw injected;};
            assertSame(injected,assertThrows(AssertionError.class,() -> fence.begin(KEY)));
            state.captureHook=() -> {};
            try(var next=fence.begin(KEY)){next.check();}
        }
    }

    private static void join(Thread thread) {
        try {if(!thread.join(java.time.Duration.ofSeconds(5)))throw new AssertionError("Physical read did not exit");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Fixture interrupted");}
    }
}
