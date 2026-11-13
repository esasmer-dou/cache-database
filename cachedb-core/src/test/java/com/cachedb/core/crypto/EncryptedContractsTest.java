package com.reactor.cachedb.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.EntityCodec;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EncryptedContractsTest {
    @Test void legacyDefaultsKeepIdentityAndNullColumnSemantics() {
        EntityCodec<String> codec=new EntityCodec<>() {
            public String toRedisValue(String value){return value;}
            public String fromRedisValue(String value){return value;}
            public Map<String,Object> toColumns(String value){return Collections.singletonMap("value",value);}
            public String fromColumns(Map<String,Object> columns){return (String)columns.get("value");}
        };
        assertSame(codec,codec.bindEncryption(null));
        assertFalse(codec.encryptionBound());
        assertNull(codec.toQueuedColumns(null).get("value"));
        assertEquals(codec.toColumns("x"),codec.toSourceColumns("x"));
        assertEquals(codec.toColumns("x"),codec.toIndexColumns("x"));
        assertEquals("x",codec.fromLogicalColumns(codec.toColumns("x")));
        assertEquals("x",codec.indexQueryValue("value","x"));
        Map<String,Object> partial=new LinkedHashMap<>();partial.put("value",null);
        assertEquals(partial,codec.toQueuedColumns("unused",partial));
        Map<String,String> queued=new LinkedHashMap<>();queued.put("value",null);
        assertEquals(queued,codec.sourceColumnsFromQueued("1",queued));
    }

    @Test void redactionDropsAllSqlMessagesCausesAndNextExceptionsButKeepsSafeRetryMetadata() {
        SQLException original=new SQLTransientException("PII_SQL_SECRET","40001",17,new RuntimeException("PII_CAUSE"));
        original.setNextException(new SQLException("PII_NEXT"));
        original.addSuppressed(new RuntimeException("PII_SUPPRESSED"));
        SQLException safe=CacheFieldRedactor.redactSqlFailure(original);
        assertInstanceOf(SQLTransientException.class,safe);
        assertEquals("40001",safe.getSQLState());assertEquals(17,safe.getErrorCode());
        assertEquals("CacheDB protected SQL operation failed",safe.getMessage());
        assertNull(safe.getCause());assertNull(safe.getNextException());assertEquals(0,safe.getSuppressed().length);
        assertInstanceOf(SQLTimeoutException.class,CacheFieldRedactor.redactSqlFailure(new SQLTimeoutException("secret","HYT00",1)));
        assertInstanceOf(SQLRecoverableException.class,CacheFieldRedactor.redactSqlFailure(new SQLRecoverableException("secret","08001",1)));
        assertEquals("HY000",CacheFieldRedactor.redactSqlFailure(new SQLException("secret","PII_BAD_STATE",1)).getSQLState());
    }

    @Test void coreDescriptorAndQueueTenantProtocolAreBoundedAndAnnotationIndependent() {
        var descriptor=new EncryptedColumnDescriptor("default",EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE,true);
        assertTrue(descriptor.encryptsSource());assertTrue(descriptor.blindIndex());
        assertEquals("EncryptedColumnDescriptor[redacted]",descriptor.toString());
        assertThrows(CacheEncryptionException.class,()->new EncryptedColumnDescriptor("bad|ring",descriptor.scope(),false));
        assertThrows(CacheEncryptionException.class,()->new EncryptedColumnDescriptor("default",null,false));
        assertEquals("__NULL__",EncryptedCodecSupport.queuedTenant("~T1:__NULL__"));
        assertThrows(CacheEncryptionException.class,()->EncryptedCodecSupport.queuedTenant("legacy"));
        assertThrows(CacheEncryptionException.class,()->EncryptedCodecSupport.tenantNamespace("rows","x".repeat(513)));
        assertNotEquals(EncryptedCodecSupport.tenantNamespace("rows","a|b"),EncryptedCodecSupport.tenantNamespace("rows","a:b"));
    }
}
