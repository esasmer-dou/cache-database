package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TenantReadFenceTest {
    @Test void zeroOrPartialAcknowledgementsCannotBecomeAClosedCertificate(){
        String token=ProtectionKey.hash("evidence");
        assertThrows(ProtectionException.class,()->new TenantReadFence.Evidence(TenantReadFence.State.CLOSED,"",token,0,0));
        assertThrows(ProtectionException.class,()->new TenantReadFence.Evidence(TenantReadFence.State.CLOSED,"",token,3,2));
        assertThrows(ProtectionException.class,()->new TenantReadFence.Evidence(TenantReadFence.State.CLOSED,"",null,1,1));
        assertEquals(TenantReadFence.State.DRAINING,new TenantReadFence.Evidence(TenantReadFence.State.DRAINING,"round-1",null,3,2).state());
        assertEquals(TenantReadFence.State.INCOMPLETE,new TenantReadFence.Evidence(TenantReadFence.State.INCOMPLETE,"",null,0,0).state());
    }
    @Test void checkpointAndManifestEvidenceAreBoundedAndOpaque(){
        assertThrows(ProtectionException.class,()->new TenantReadFence.Evidence(TenantReadFence.State.DRAINING,"x".repeat(1025),null,1,0));
        assertThrows(ProtectionException.class,()->new TenantReadFence.Evidence(TenantReadFence.State.CLOSED,"tenant@example.test",ProtectionKey.hash("p"),1,1));
        assertThrows(ProtectionException.class,()->new TenantReadFence.Evidence(TenantReadFence.State.CLOSED,"","not-a-token",1,1));
    }
}
