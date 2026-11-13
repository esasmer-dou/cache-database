package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.tenant.TenantAccess;
import java.util.*;

/** Identity-only DELETE admission; no source lookup, encrypted assignment or new generation. */
final class RedisEncryptedBulkDeleteChecks {
    private RedisEncryptedBulkDeleteChecks(){}
    private static void requireProducerScope(EntityMetadata<?,?> metadata){
        if(!metadata.tenantPurgeEnabled())return;
        String tenant=TenantAccess.active(metadata);
        if(tenant==null)throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        RedisTenantProducer.current(metadata.redisNamespace(),tenant);
    }
    static void requireScope(EntityMetadata<?,?> metadata){
        requireProducerScope(metadata);
        if(!EncryptedStorageChecks.protectedEntity(metadata))return;
        EncryptedStorageChecks.validateMetadata(metadata);
        String id=metadata.idColumn(),version=metadata.versionColumn(),tenant=metadata.tenantFieldName(),deleted=metadata.deletedColumn();
        String idType=metadata.columnTypes().get(id);
        if(id==null||id.isBlank()||version==null||version.isBlank()||id.equals(version)||id.equals(deleted)
                ||Objects.equals(version,deleted)||tenant!=null&&(tenant.equals(id)||tenant.equals(version)||tenant.equals(deleted))
                ||!metadata.columns().contains(id)||tenant!=null&&!metadata.columns().contains(tenant)
                ||new HashSet<>(metadata.columns()).size()!=metadata.columns().size()
                ||idType==null||!Set.of("java.lang.String","java.lang.Long","long","java.lang.Integer","int","java.lang.Short","short",
                        "java.lang.Byte","byte","java.util.UUID").contains(idType))
            throw EncryptedCodecSupport.unbound();
        // Protected DELETE uses the original-generation frame and carries no encrypted field assignments.
        if(!metadata.tenantPurgeEnabled()&&metadata.encryptedColumns().values().stream().anyMatch(EncryptedColumnDescriptor::encryptsSource))
            throw new CacheEncryptionException(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION);
    }
    static void requireMapping(EntityMetadata<?,?> metadata,EntityCodec<?> codec,EntityRegistry registry){
        requireScope(metadata);
        if(!EncryptedStorageChecks.protectedEntity(metadata))return;
        EncryptedStorageChecks.requireBound(metadata,codec);
        if(registry==null)throw EncryptedCodecSupport.unbound();
        var binding=registry.find(metadata.entityName()).orElseThrow(EncryptedCodecSupport::unbound);
        // A live producer ticket must not authorize a caller to change the registered purge contract.
        if(metadata.tenantPurgeEnabled()!=binding.metadata().tenantPurgeEnabled())
            throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        requireScope(binding.metadata());
        EncryptedStorageChecks.sameMapping(metadata,binding.metadata());
        EncryptedStorageChecks.requireBound(binding.metadata(),binding.codec());
        var actual=binding.metadata();
        if(!Objects.equals(metadata.entityName(),actual.entityName())
                || !Objects.equals(metadata.columnTypes(),actual.columnTypes())
                || !Objects.equals(metadata.indexedColumns(),actual.indexedColumns())
                || !Objects.equals(metadata.compositeExactIndexes(),actual.compositeExactIndexes())
                || !Objects.equals(metadata.compositeSortColumns(),actual.compositeSortColumns())
                || !Objects.equals(metadata.partitionedSortIndexes(),actual.partitionedSortIndexes())
                || metadata.schemaVersion()!=actual.schemaVersion()
                || !Objects.equals(metadata.activeMarkerValue(),actual.activeMarkerValue())
                || !Objects.equals(metadata.deletedMarkerValue(),actual.deletedMarkerValue()))
            throw EncryptedCodecSupport.unbound();
    }
    static void requireIdentityOnly(WriteOperation<?,?> operation,long expectedVersion){
        requireScope(operation.metadata());
        if(!EncryptedStorageChecks.protectedEntity(operation.metadata()))return;
        if(operation.type()!=OperationType.DELETE||expectedVersion<1
                || operation.redisPayload()==null||!operation.redisPayload().isEmpty()
                || operation.columns().size()!=1
                || !Objects.equals(operation.columns().get(operation.metadata().idColumn()),operation.id()))
            throw EncryptedCodecSupport.invalidValue();
    }
    static void probeIds(EntityMetadata<?,?> metadata,EntityCodec<?> codec,List<?> ids,String tenant,long deadline){
        requireScope(metadata);
        if(metadata.tenantPurgeEnabled()&&!Objects.equals(tenant,TenantAccess.active(metadata)))
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        if(!EncryptedStorageChecks.protectedEntity(metadata))return;
        int checked=0;
        for(Object id:ids){
            if((checked++&127)==0){
                RedisBulkDeleteExecutor.checkDeadline(deadline);
                requireProducerScope(metadata);
            }
            String text=String.valueOf(id); // copyIds already admitted only canonical scalar ID types.
            var probe=new LinkedHashMap<String,String>();
            probe.put(EncryptedCodecSupport.QUEUE_FORMAT,EncryptedCodecSupport.QUEUE_VERSION);
            if(metadata.tenantFieldName()!=null){
                if(tenant==null)throw EncryptedCodecSupport.invalidValue();
                probe.put(EncryptedCodecSupport.QUEUE_TENANT,"~T1:"+tenant);
            }
            probe.put(metadata.idColumn(),text);
            try{
                var decoded=codec.sourceColumnsFromQueued(text,Collections.unmodifiableMap(probe));
                if(decoded==null||decoded.size()!=1||!text.equals(decoded.get(metadata.idColumn())))
                    throw EncryptedCodecSupport.invalidValue();
            }catch(CacheEncryptionException safe){throw new CacheEncryptionException(safe.code());}
            catch(RuntimeException unsafe){throw EncryptedCodecSupport.invalidValue();}
        }
        requireProducerScope(metadata);
    }
    static <T,ID> void validateSnapshot(EntityMetadata<T,ID> metadata,EntityCodec<T> codec,
            RedisKeyStrategy keys,ID id,String tenant,RedisBulkDeleteExecutor.State state,boolean watched){
        if(!EncryptedStorageChecks.protectedEntity(metadata))return;
        try{
            if(state.version()<1)throw EncryptedCodecSupport.invalidValue();
            EncryptedStorageChecks.validateIndexColumns(metadata,state.meta());
            RedisCompositeIndexes.validateSortMeta(state.meta());
            for(String column:state.meta().keySet()){
                if(metadata.columns().contains(column))continue;
                if(column.startsWith(RedisCompositeIndexes.MARKER)
                        &&metadata.compositeExactIndexes().containsKey(column.substring(RedisCompositeIndexes.MARKER.length())))continue;
                if(column.startsWith(RedisCompositeIndexes.SORT_MARKER)
                        &&metadata.compositeSortColumns().containsKey(column.substring(RedisCompositeIndexes.SORT_MARKER.length())))continue;
                throw EncryptedCodecSupport.invalidValue();
            }
            String hot=state.hotOwner();
            if(hot!=null&&!hot.isBlank()){
                if(metadata.tenantFieldName()==null)throw EncryptedCodecSupport.invalidValue();
                var parts=hot.split("\\n",-1);
                if(parts.length!=1&&parts.length!=3
                        ||!keys.tenantHotSetKey(metadata.redisNamespace(),metadata.tenantFieldName(),tenant).equals(parts[0]))
                    throw EncryptedCodecSupport.invalidValue();
                if(parts.length==3){
                    long bytes=Long.parseLong(parts[2]);
                    if(bytes<0||!Long.toString(bytes).equals(parts[2])
                            ||!keys.tenantHotPayloadBytesKey(metadata.redisNamespace(),metadata.tenantFieldName(),tenant).equals(parts[1]))
                        throw EncryptedCodecSupport.invalidValue();
                }
            }
            if(watched){
                if(state.payload()==null)throw EncryptedCodecSupport.invalidValue();
                T entity=codec.fromRedisValue(state.payload());
                if(!String.valueOf(id).equals(String.valueOf(metadata.idAccessor().apply(entity))))
                    throw EncryptedCodecSupport.invalidValue();
                com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata,entity);
            }
        }catch(CacheEncryptionException safe){throw new CacheEncryptionException(safe.code());}
        catch(RuntimeException unsafe){throw EncryptedCodecSupport.invalidValue();}
    }
    static void validateIndexCommands(EntityMetadata<?,?> metadata,RedisKeyStrategy keys,List<List<String>> commands){
        if(!EncryptedStorageChecks.protectedEntity(metadata))return;
        validateOwnedIndexCommands(metadata,keys,commands);
    }
    static void validateOwnedIndexCommands(EntityMetadata<?,?> metadata,RedisKeyStrategy keys,List<List<String>> commands){
        String pattern=keys.indexPattern(metadata.redisNamespace()),prefix=pattern.substring(0,pattern.length()-1);
        for(var command:commands)if(command.size()<2||!command.get(1).startsWith(prefix))
            throw EncryptedCodecSupport.invalidValue();
    }
}
