package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PurgeParticipantManifestTest {
    @Test void anExplicitlyRequiredStoreCannotBeSkippedAsComplete(){
        var manifest=new PurgeParticipantManifest("Orders",Set.of("redis-primary","projection-owned","declared-spring-values"),List.of());
        assertEquals(3,manifest.missing().size());
        assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY,
                assertThrows(ProtectionException.class,manifest::requireAll).code());
    }
    @Test void genericSpringCacheIsNotAutomaticallyAddedToEntityScope(){
        PurgeParticipant redis=new PurgeParticipant(){
            public Declaration declaration(){return new Declaration("redis-primary",ProtectionKey.hash("layout"),Set.of("Orders"),false);}
            public Result step(Request request){throw new AssertionError("Manifest construction must not execute stores");}
        };
        var manifest=new PurgeParticipantManifest("Orders",Set.of("redis-primary"),List.of(redis));
        assertTrue(manifest.missing().isEmpty());assertEquals(1,manifest.requireAll().size());
    }
    @Test void aForeignEntityCannotBeRegisteredAsTheRequestedEntityParticipant(){
        PurgeParticipant foreign=new PurgeParticipant(){
            public Declaration declaration(){return new Declaration("foreign",ProtectionKey.hash("owner"),Set.of("Invoices"),true);}
            public Result step(Request request){throw new AssertionError("Foreign participant must never execute");}
        };
        assertThrows(ProtectionException.class,()->new PurgeParticipantManifest("Orders",Set.of("foreign"),List.of(foreign)));
    }
    @Test void mixedContributionParticipantCanDeclareIncompleteWithoutDeletingForeignData(){
        PurgeParticipant mixed=new PurgeParticipant(){
            public Declaration declaration(){return new Declaration("mixed",ProtectionKey.hash("mapping"),Set.of("Orders","Invoices"),true);}
            public Result step(Request request){return new Result(State.INCOMPLETE,request.checkpoint(),0,0,null);}
        };
        var manifest=new PurgeParticipantManifest("Orders",Set.of("mixed"),List.of(mixed));
        assertTrue(manifest.missing().isEmpty());assertTrue(manifest.requireAll().get(0).declaration().mixedOwnership());
        var lease=new com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease(ProtectionKey.of("orders","a"),
                UUID.randomUUID(),UUID.randomUUID(),1,30);
        assertEquals(PurgeParticipant.State.INCOMPLETE,mixed.step(new PurgeParticipant.Request(lease,1,PurgeParticipant.Phase.VERIFY,"",128,1024)).state());
    }
}
