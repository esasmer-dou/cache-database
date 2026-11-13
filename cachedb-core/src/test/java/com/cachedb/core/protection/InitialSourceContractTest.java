package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class InitialSourceContractTest {
    record Row(Long id,String tenant,String value) {}
    private static final SourceOwnerOnboarding.IdCodec<Long> IDS=new SourceOwnerOnboarding.IdCodec<>(){
        public String encode(Long value){return value.toString();}public Long decode(String value){return Long.valueOf(value);}
    };
    private static final EntityCodec<Row> CODEC=new EntityCodec<>(){
        public String toRedisValue(Row row){return row.toString();}
        public Row fromRedisValue(String value){throw new UnsupportedOperationException();}
        public Map<String,Object> toColumns(Row row){return Map.of("id",row.id(),"tenant_id",row.tenant(),"value",row.value());}
    };
    private static final Binding BINDING=new Binding(Row.class,"initial_rows",new SourcePlan("initial_rows","tenant_id","id"),
            ProtectionKey.hash("layout"),10000,128,1048576,true);
    private record Spec(String deleted,String active,String marker,List<String> columns,Map<String,String> types,
            Map<String,EncryptedColumnDescriptor> encrypted,int schema) {}
    private static Spec base(){return new Spec("deleted_flag","0","1",List.of("id","tenant_id","value"),
            Map.of("id","java.lang.Long","tenant_id","java.lang.String","value","java.lang.String"),Map.of(),1);}
    private static EntityMetadata<Row,Long> metadata(Spec s){
        return new EntityMetadata<>(){
            public String entityName(){return "InitialRow";}public String tableName(){return "initial_rows";}
            public String redisNamespace(){return "initial_rows";}public String idColumn(){return "id";}
            public Class<Row> entityType(){return Row.class;}public Function<Row,Long> idAccessor(){return Row::id;}
            public boolean tenantPurgeEnabled(){return true;}public String tenantFieldName(){return "tenant_id";}
            public String extractTenantId(Row row){return row.tenant();}public String deletedColumn(){return s.deleted();}
            public String activeMarkerValue(){return s.active();}public String deletedMarkerValue(){return s.marker();}
            public List<String> columns(){return s.columns();}public Map<String,String> columnTypes(){return s.types();}
            public Map<String,EncryptedColumnDescriptor> encryptedColumns(){return s.encrypted();}
            public int schemaVersion(){return s.schema();}public Set<String> indexedColumns(){return Set.of("tenant_id");}
            public List<RelationDefinition> relations(){return List.of();}
        };
    }
    private static InitialSourceHotWindow<Row,Long> window(Spec spec,SourceOwnerOnboarding.IdCodec<Long> ids){
        return new InitialSourceHotWindow<>(BINDING,metadata(spec),CODEC,ids,ProtectionKey.hash("long-v1"),
                1L,100L,100,65536,8192,1048576,InitialSourceHotWindow.Profile.POINT_NO_QUOTA);
    }

    @Test void completeSelectionAndPayloadMappingAreFingerprintBoundWithUnambiguousFraming(){
        var b=base();String original=window(b,IDS).fingerprint();
        assertEquals(original,window(b,IDS).fingerprint());
        var changes=List.of(
                new Spec("other_flag",b.active(),b.marker(),b.columns(),b.types(),b.encrypted(),1),
                new Spec(b.deleted(),"false",b.marker(),b.columns(),b.types(),b.encrypted(),1),
                new Spec(b.deleted(),b.active(),"true",b.columns(),b.types(),b.encrypted(),1),
                new Spec(b.deleted(),b.active(),b.marker(),List.of("tenant_id","id","value"),b.types(),b.encrypted(),1),
                new Spec(b.deleted(),b.active(),b.marker(),b.columns(),Map.of("id","java.lang.Long","tenant_id","java.lang.String","value","java.lang.CharSequence"),b.encrypted(),1),
                new Spec(b.deleted(),b.active(),b.marker(),b.columns(),b.types(),b.encrypted(),2));
        for(var changed:changes)assertNotEquals(original,window(changed,IDS).fingerprint());
        assertNotEquals(window(new Spec(b.deleted(),"a:b","c",b.columns(),b.types(),Map.of(),1),IDS).fingerprint(),
                window(new Spec(b.deleted(),"a","b:c",b.columns(),b.types(),Map.of(),1),IDS).fingerprint());
        assertNotEquals(window(new Spec(null,"0","1",b.columns(),b.types(),Map.of(),1),IDS).fingerprint(),original);
    }
    @Test void numericCanonicalMustBeExactLongNotJustEncodeDecodeEncode(){
        SourceOwnerOnboarding.IdCodec<Long> plus=new SourceOwnerOnboarding.IdCodec<>(){
            public String encode(Long value){return "+"+value;}public Long decode(String value){return Long.valueOf(value);}
        };
        assertThrows(ProtectionException.class,()->window(base(),plus));
        SourceOwnerOnboarding.IdCodec<Long> changed=new SourceOwnerOnboarding.IdCodec<>(){
            public String encode(Long value){return value.toString();}public Long decode(String value){return Long.valueOf(value)+1;}
        };
        assertThrows(ProtectionException.class,()->window(base(),changed));
        assertEquals("42",window(base(),IDS).canonical(42L));
    }
    @Test void stringOrUuidRoundtripCannotStandInForDatabaseCollationProof(){
        @SuppressWarnings({"unchecked","rawtypes"}) EntityMetadata<Row,String> wrong=(EntityMetadata)metadata(base());
        SourceOwnerOnboarding.IdCodec<String> ids=new SourceOwnerOnboarding.IdCodec<>(){
            public String encode(String value){return value;}public String decode(String value){return value;}
        };
        assertEquals(ProtectionException.Code.MIGRATION_REQUIRED,assertThrows(ProtectionException.class,()->
                new InitialSourceHotWindow<>(BINDING,wrong,CODEC,ids,ProtectionKey.hash("strings"),"1","9",
                        9,65536,8192,1048576,InitialSourceHotWindow.Profile.POINT_NO_QUOTA)).code());
    }
    @Test void totalResidentBudgetAndPageCountAreHardLimits(){
        assertEquals(128,InitialSourceHotWindow.MAX_PAGES);
        assertThrows(ProtectionException.class,()->new InitialSourceHotWindow<>(BINDING,metadata(base()),CODEC,IDS,
                ProtectionKey.hash("long-v1"),1L,100L,100,65536,8192,65535,InitialSourceHotWindow.Profile.POINT_NO_QUOTA));
        assertThrows(ProtectionException.class,()->new InitialSourceHotWindow<>(BINDING,metadata(base()),CODEC,IDS,
                ProtectionKey.hash("long-v1"),1L,100L,100,65536,8192,67108865,InitialSourceHotWindow.Profile.POINT_NO_QUOTA));
    }
    @Test void generationAndLeaseCannotCrossTenantOrOperation(){
        var generation=new Generation(BINDING.key("a"),1);var op=UUID.randomUUID();
        var request=new InitialTenantBootstrap.Request(generation,"a",op,ProtectionKey.hash("operator"),30);
        assertThrows(ProtectionException.class,()->new InitialTenantBootstrap.Session(request,
                new Lease(BINDING.key("b"),op,UUID.randomUUID(),1,30),ProtectionKey.hash("plan"),ProtectionKey.hash("manifest")));
        assertThrows(ProtectionException.class,()->new InitialTenantBootstrap.Session(request,
                new Lease(BINDING.key("a"),UUID.randomUUID(),UUID.randomUUID(),1,30),ProtectionKey.hash("plan"),ProtectionKey.hash("manifest")));
    }
}
