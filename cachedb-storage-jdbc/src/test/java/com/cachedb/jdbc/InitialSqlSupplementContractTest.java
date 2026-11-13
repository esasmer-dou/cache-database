package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class InitialSqlSupplementContractTest {
    @Test void parameterTypesMatchDeclaredAsciiColumnsWithoutChangingOtherProviders(){
        assertEquals("CAST(? AS CHAR(64))",InitialSqlTokens.char64(JdbcTenantPurgeDialect.MSSQL));
        assertEquals("CAST(? AS VARCHAR(64))",InitialSqlTokens.varchar(JdbcTenantPurgeDialect.MSSQL,64));
        assertEquals("CAST(? AS VARCHAR(36))",InitialSqlTokens.varchar(JdbcTenantPurgeDialect.MSSQL,36));
        assertEquals("scope_token=CAST(? AS CHAR(64)) AND tenant_token=CAST(? AS CHAR(64))",
                InitialSqlTokens.pair(JdbcTenantPurgeDialect.MSSQL,true));
        assertEquals("scope_token=CAST(? AS VARCHAR(64)) AND tenant_token=CAST(? AS VARCHAR(64))",
                InitialSqlTokens.pair(JdbcTenantPurgeDialect.MSSQL,false));
        for(var dialect:List.of(JdbcTenantPurgeDialect.POSTGRES,JdbcTenantPurgeDialect.ORACLE)){
            assertEquals("?",InitialSqlTokens.char64(dialect));
            assertEquals("?",InitialSqlTokens.varchar(dialect,36));
            assertEquals("scope_token=? AND tenant_token=?",InitialSqlTokens.pair(dialect,true));
        }
        assertThrows(IllegalArgumentException.class,()->InitialSqlTokens.varchar(JdbcTenantPurgeDialect.MSSQL,1024));
    }
    @Test void stateVersionTwoPreservesVersionOneAndMakesIrreversibleCleanupDurable(){
        var key=ProtectionKey.of("fixture","a");
        var request=new Request(new Generation(key,1),"a",UUID.randomUUID(),ProtectionKey.hash("operator"),30);
        var state=InitialBootstrapState.initial(request,ProtectionKey.hash("plan"),ProtectionKey.hash("manifest"));
        byte[] v2=state.encode();assertEquals(2,ByteBuffer.wrap(v2).getInt());
        var old=Arrays.copyOf(v2,v2.length-1);ByteBuffer.wrap(old).putInt(1);
        assertFalse(InitialBootstrapState.decode(old,key).finalizing);
        assertFalse(InitialBootstrapState.decode(v2,key).finalizing);
        state.runId="a".repeat(40);state.inventory=ProtectionKey.hash("inventory");
        state.runtimeProof=ProtectionKey.hash("runtime");
        var range=new TenantVersionRange(key,1,1,1,9001,9010,UUID.randomUUID(),state.runId,state.inventory);
        state.activation=new Activation(ProtectionKey.hash("activation"),state.plan,state.manifest,state.inventory,
                state.window(),state.runtimeProof,range);
        state.phase=Phase.SQL_OPEN;state.finalizing=true;
        var retry=InitialBootstrapState.decode(state.encode(),key);
        assertTrue(retry.finalizing);assertEquals(activationValues(state.activation),activationValues(retry.activation));
        state.phase=Phase.READY;
        assertEquals(ProtectionException.Code.INVALID_PROGRESS,
                assertThrows(ProtectionException.class,()->InitialBootstrapState.decode(state.encode(),key)).code());
    }
    @Test void sharedJournalCodecKeepsWireBytesAndAll17ActivationFieldsWithoutDatabaseAuthority(){
        var key=ProtectionKey.of("fixture","a");var request=new Request(new Generation(key,1),"a",UUID.randomUUID(),ProtectionKey.hash("operator"),30);
        var state=InitialBootstrapState.initial(request,ProtectionKey.hash("plan"),ProtectionKey.hash("manifest"));
        state.runId="a".repeat(40);state.inventory=ProtectionKey.hash("inventory");state.runtimeProof=ProtectionKey.hash("native");
        state.activation=new Activation(ProtectionKey.hash("activation"),state.plan,state.manifest,state.inventory,state.window(),state.runtimeProof,
                new TenantVersionRange(key,1,3,1,9001,9064,UUID.randomUUID(),state.runId,state.inventory));state.phase=Phase.SQL_OPEN;
        byte[] original=state.encode();var frame=InitialBootstrapJournalCodec.decode(original,key);
        assertArrayEquals(original,InitialBootstrapJournalCodec.encode(frame,key));
        assertEquals(17,InitialBootstrapJournalCodec.activationFields(frame.activation()).size());
        assertEquals(InitialBootstrapJournalCodec.activationFields(state.activation),InitialBootstrapJournalCodec.activationFields(frame.activation()));
        assertFalse(frame.toString().contains("9001"));
        assertThrows(ProtectionException.class,()->InitialBootstrapJournalCodec.decode(new byte[16385],key));
        assertThrows(UnsupportedOperationException.class,()->InitialSqlCleanupPlan.stores().clear());
        assertThrows(UnsupportedOperationException.class,()->InitialSqlCleanupPlan.stores().getFirst().columns().clear());
        assertEquals(7,InitialSqlCleanupPlan.stores().size());
        assertFalse(InitialSqlCleanupPlan.stores().stream().anyMatch(s->s.table().equals("cachedb_namespace_bootstrap")
                ||s.table().equals("cachedb_tenant_allocator")||s.table().equals("cachedb_empty_activation")));
        assertTrue(InitialSqlCleanupPlan.stores().getFirst().deleteSelected(JdbcTenantPurgeDialect.MSSQL).contains("CAST(? AS VARCHAR(64))"));
    }
    private static List<Object> activationValues(com.reactor.cachedb.core.protection.InitialTenantBootstrap.Activation a){
        var r=a.range();
        return List.of(a.token(),a.planToken(),a.manifestToken(),a.inventoryToken(),a.window(),a.runtimeProof(),
                r.key(),r.generation(),r.controlEpoch(),r.reservationEpoch(),r.first(),r.last(),r.request(),r.runId(),r.inventory());
    }
}
