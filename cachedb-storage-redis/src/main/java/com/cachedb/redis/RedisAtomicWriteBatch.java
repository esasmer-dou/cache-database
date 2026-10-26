package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.CacheAdmissionSource;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.*;

import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** A single stream envelope cannot be split across consumers or retries. */
public final class RedisAtomicWriteBatch implements CacheWriteBatch {
    private static final int MAX_ITEMS = 100;
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final String APPLY =
            """
local prior=redis.call('HGET',KEYS[2],'fingerprint')
if prior then
  if prior ~= ARGV[1] then return redis.error_reply('Batch id reused with different operations') end
  local result={redis.call('HGET',KEYS[2],'stream')}
  for i=1,tonumber(ARGV[2]) do table.insert(result,redis.call('HGET',KEYS[2],'v'..i)) end
  return result
end
local streamType=redis.call('TYPE',KEYS[1]).ok
if streamType ~= 'none' and streamType ~= 'stream' then return redis.error_reply('Invalid batch stream type') end
if tonumber(ARGV[3]) > 0 and redis.call('XLEN',KEYS[1]) >= tonumber(ARGV[3]) then
  return redis.error_reply('Write-behind batch backlog limit exceeded') end
local count=tonumber(ARGV[2])
local pos=5
local items={}
for i=1,count do
  local base=3+(i-1)*6
  local current=tonumber(redis.call('GET',KEYS[base+1]) or '0')
  local expected=tonumber(ARGV[pos+2])
  if current >= 9007199254740990 then return redis.error_reply('Entity version exhausted') end
  if expected >= 0 and expected ~= current then return redis.error_reply('Batch optimistic version conflict') end
  for j=0,2 do
    local kind=redis.call('TYPE',KEYS[base+j]).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('Invalid entity key type') end
  end
  local hot=redis.call('TYPE',KEYS[base+3]).ok
  if hot ~= 'none' and hot ~= 'zset' then return redis.error_reply('Invalid hot set type') end
  local limit=tonumber(ARGV[pos+7])
  if limit > 0 and redis.call('ZCARD',KEYS[base+3])+count-limit > 100 then
    return redis.error_reply('Hot set requires explicit reconciliation before this atomic batch') end
  table.insert(items,{base,ARGV[pos],ARGV[pos+1],ARGV[pos+3],ARGV[pos+4],ARGV[pos+5],ARGV[pos+6],ARGV[pos+7],ARGV[pos+8],ARGV[pos+9]})
  pos=pos+10
end
local views={}
local viewCount=tonumber(ARGV[pos]) pos=pos+1
for i=1,viewCount do
  local base=tonumber(ARGV[pos])
  for j=0,1 do
    local kind=redis.call('TYPE',KEYS[base+j]).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('Invalid projection key type') end
  end
  table.insert(views,{base,ARGV[pos+1],ARGV[pos+2],ARGV[pos+3],ARGV[pos+4],ARGV[pos+5]}) pos=pos+6
end
local fields={}
for i=pos,#ARGV do table.insert(fields,ARGV[i]) end
local channel=''
for i=1,#fields,2 do if fields[i] == 'nearInvalidationChannel' then channel=fields[i+1] end end
if channel ~= '' then
  for i=1,#fields,2 do
    if string.match(fields[i], '^b%d+:nearInvalidation$')
      and not redis.acl_check_cmd('PUBLISH',channel,fields[i+1]) then
      return redis.error_reply('Near-cache requires PUBLISH permission before batch acceptance')
    end
  end
end
local result={}
for i,item in ipairs(items) do
  local base=item[1]
  local version=redis.call('INCR',KEYS[base+1])
  if item[4] == 'DELETE' then
    redis.call('DEL',KEYS[base])
    redis.call('SET',KEYS[base+2],tostring(version))
    redis.call('ZREM',KEYS[base+3],item[2])
  else
    redis.call('DEL',KEYS[base+2])
    if item[6] == '1' then
      redis.call('SET',KEYS[base],item[3])
      if tonumber(item[5]) > 0 then redis.call('EXPIRE',KEYS[base],item[5]) end
      redis.call('ZADD',KEYS[base+3],ARGV[4],item[2])
      local limit=tonumber(item[8])
      if limit > 0 then
        local excess=redis.call('ZCARD',KEYS[base+3])-limit
        if excess > 0 then
          local evicted=redis.call('ZRANGE',KEYS[base+3],0,excess-1)
          for _,id in ipairs(evicted) do redis.call('DEL',item[9]..id) redis.call('ZREM',KEYS[base+3],id) end
        end
      end
    else redis.call('DEL',KEYS[base]) redis.call('ZREM',KEYS[base+3],item[2]) end
  end
  if item[10] == '1' or redis.call('EXISTS',KEYS[base+5]) == 1 then redis.call('SET',KEYS[base+4],'atomic-batch') end
  table.insert(fields,'b'..(i-1)..':version') table.insert(fields,tostring(version))
  table.insert(fields,'b'..(i-1)..':col:'..item[7]) table.insert(fields,tostring(version))
  table.insert(result,tostring(version))
  redis.call('HSET',KEYS[2],'v'..i,tostring(version))
end
for _,view in ipairs(views) do
  local base=view[1]
  if view[3] == 'DELETE' or view[5] ~= '1' then
    redis.call('DEL',KEYS[base]) redis.call('SET',KEYS[base+1],'1')
  else
    redis.call('SET',KEYS[base],view[2]) redis.call('DEL',KEYS[base+1])
    if tonumber(view[4]) > 0 then redis.call('EXPIRE',KEYS[base],view[4]) end
  end
  if view[6] == '1' or redis.call('EXISTS',KEYS[base+3]) == 1 then redis.call('SET',KEYS[base+2],'atomic-batch') end
end
local stream=redis.call('XADD',KEYS[1],'*',unpack(fields))
if channel ~= '' then
  for i=1,#fields,2 do
    if string.match(fields[i], '^b%d+:nearInvalidation$') then redis.call('PUBLISH',channel,fields[i+1]) end
  end
end
redis.call('HSET',KEYS[2],'fingerprint',ARGV[1],'stream',stream)
redis.call('EXPIRE',KEYS[2],86400)
table.insert(result,1,stream)
return result
""";

    private record Item(
            EntityBinding<Object, Object> binding,
            Object id,
            String payload,
            Map<String, String> columns,
            Map<String, Object> admissionColumns,
            Map<String, String> projections,
            OperationType type,
            long expected) {}

    private final RedisClientOperations redis;
    private final EntityRegistry registry;
    private final RedisKeyStrategy keys;
    private final WriteBehindConfig config;
    private final RedisGuardrailConfig guardrail;
    private final RedisProducerGuard producer;
    private final String batchId;
    private final List<Item> items = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private boolean committed;
    private int maxColumns = 256;
    private boolean queryIndexesEnabled = true;
    private com.reactor.cachedb.core.trace.TraceContextPropagator trace = com.reactor.cachedb.core.trace.TraceContexts.threadLocal();

    public RedisAtomicWriteBatch withTraceContextPropagator(com.reactor.cachedb.core.trace.TraceContextPropagator value) {
        trace = Objects.requireNonNull(value); return this;
    }

    public RedisAtomicWriteBatch withQueryIndexConfig(QueryIndexConfig config) {
        if (!items.isEmpty()) throw new IllegalStateException("Configure indexes before adding operations");
        Objects.requireNonNull(config);
        queryIndexesEnabled = config.exactIndexEnabled() || config.rangeIndexEnabled() || config.prefixIndexEnabled() || config.textIndexEnabled();
        return this;
    }

    public RedisAtomicWriteBatch withMaxColumnsPerOperation(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Column limit must be positive");
        if (!items.isEmpty())
            throw new IllegalStateException("Configure limits before adding operations");
        maxColumns = limit;
        return this;
    }

    public RedisAtomicWriteBatch(
            JedisPooled redis,
            EntityRegistry registry,
            RedisKeyStrategy keys,
            WriteBehindConfig config,
            RedisGuardrailConfig guardrail,
            RedisProducerGuard producer,
            String batchId) {
        this(JedisPooledOperations.of(redis), registry, keys, config, guardrail, producer, batchId);
    }

    public RedisAtomicWriteBatch(
            RedisClientOperations redis,
            EntityRegistry registry,
            RedisKeyStrategy keys,
            WriteBehindConfig config,
            RedisGuardrailConfig guardrail,
            RedisProducerGuard producer,
            String batchId) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid batch id");
        this.redis = redis;
        this.registry = registry;
        this.keys = keys;
        this.config = config;
        this.guardrail = guardrail;
        this.producer = producer;
        this.batchId = batchId;
    }

    @SuppressWarnings("unchecked")
    private EntityBinding<Object, Object> binding(Class<?> type) {
        for (var binding : registry.all())
            if (binding.metadata().entityType() == type) {
                if (binding.metadata().tenantFieldName() != null)
                    throw new IllegalStateException("Tenant entities require ownership-checked repository writes; atomic batch tenant ownership is not yet supported");
                return (EntityBinding<Object, Object>) binding;
            }
        throw new IllegalArgumentException("Unregistered entity type: " + type.getName());
    }

    public <T> CacheWriteBatch put(T entity) {
        return put(entity, -1);
    }

    public <T> CacheWriteBatch put(T entity, long expectedVersion) {
        Objects.requireNonNull(entity, "entity");
        if (expectedVersion < -1) throw new IllegalArgumentException("Invalid expected version");
        var binding = binding(entity.getClass());
        Object id =
                Objects.requireNonNull(
                        binding.metadata().idAccessor().apply(entity), "Entity ID is required");
        var columns = new LinkedHashMap<String, String>();
        var admission = new LinkedHashMap<>(binding.codec().toColumns(entity));
        admission.forEach((k, v) -> columns.put(k, v == null ? null : v.toString()));
        add(
                new Item(
                        binding,
                        id,
                        binding.codec().toRedisValue(entity),
                        columns,
                        admission,
                        projectionPayloads(binding, entity, id),
                        OperationType.UPSERT,
                        expectedVersion));
        return this;
    }

    public <T, ID> CacheWriteBatch delete(Class<T> type, ID id) {
        var binding = binding(type);
        Objects.requireNonNull(id, "id");
        var projections = new LinkedHashMap<String, String>();
        for (var view : registry.projections(binding.metadata().entityName()))
            projections.put(view.projection().name(), "");
        add(
                new Item(
                        binding,
                        id,
                        "",
                        new LinkedHashMap<>(),
                        Map.of(),
                        projections,
                        OperationType.DELETE,
                        -1));
        return this;
    }

    public <T, ID> CacheWriteBatch patch(Class<T> type, ID id, Map<String, Object> updates) {
        var binding = binding(type); var metadata = binding.metadata();
        Objects.requireNonNull(id, "id");
        if (updates == null || updates.isEmpty() || updates.size() > maxColumns)
            throw new IllegalArgumentException("Atomic PATCH requires a bounded nonempty column set");
        for (String column : updates.keySet()) if (!metadata.columns().contains(column)
                || column.equals(metadata.idColumn()) || column.equals(metadata.versionColumn())
                || column.equals(metadata.deletedColumn())) throw new IllegalArgumentException("Invalid PATCH column: " + column);
        var delta = binding.codec().normalizePatchColumns(updates);
        var values = redis.mget(keys.entityKey(metadata.redisNamespace(), id),
                keys.versionKey(metadata.redisNamespace(), id), keys.tombstoneKey(metadata.redisNamespace(), id));
        if (values.get(0) == null || values.get(1) == null || values.get(2) != null)
            throw new IllegalStateException("Atomic PATCH requires a complete hot, versioned, nondeleted entity");
        var full = new LinkedHashMap<>(binding.codec().toColumns(binding.codec().fromRedisValue(values.get(0))));
        full.putAll(delta);
        Object entity = binding.codec().fromColumns(full);
        var columns = new LinkedHashMap<String, String>();
        delta.forEach((column, value) -> columns.put(column, value == null ? null : value.toString()));
        add(new Item(binding, id, binding.codec().toRedisValue(entity), columns, full,
                projectionPayloads(binding, entity, id), OperationType.PATCH, Long.parseLong(values.get(1))));
        return this;
    }

    private void add(Item item) {
        var route = com.reactor.cachedb.core.route.RouteCacheContext.currentContract();
        if (route != null && route.tenantQuota().bounded())
            throw new IllegalStateException(
                    "Atomic batches cannot bypass a bounded tenant admission contract");
        if (item.columns.size() + 2 > maxColumns)
            throw new IllegalArgumentException("Atomic write exceeds the configured column limit");
        if (committed) throw new IllegalStateException("Batch already committed");
        if (items.size() >= MAX_ITEMS)
            throw new IllegalArgumentException("Atomic batch exceeds 100 operations");
        String identity = keys.entityKey(item.binding.metadata().redisNamespace(), item.id);
        if (!identities.add(identity))
            throw new IllegalArgumentException("Duplicate entity in atomic batch");
        items.add(item);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> projectionPayloads(
            EntityBinding<Object, Object> binding, Object entity, Object id) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var view : registry.projections(binding.metadata().entityName())) {
            var projection =
                    (com.reactor.cachedb.core.projection.EntityProjection<Object, Object, Object>)
                            view.projection();
            Object value =
                    Objects.requireNonNull(
                            projection.projector().apply(entity), "Null batch projection");
            if (!id.equals(projection.idAccessor().apply(value)))
                throw new IllegalArgumentException("Batch projection must retain entity identity");
            result.put(projection.name(), projection.codec().toRedisValue(value));
        }
        return result;
    }

    public CacheWriteBatchReceipt commit() {
        var route = com.reactor.cachedb.core.route.RouteCacheContext.currentContract();
        if (route != null && route.tenantQuota().bounded())
            throw new IllegalStateException("Atomic batches cannot bypass a bounded tenant admission contract");
        if (committed || items.isEmpty())
            throw new IllegalStateException("Empty or committed batch");
        if (!config.enabled())
            throw new IllegalStateException("Atomic batch requires write-behind");
        if (producer != null) producer.applyBackpressure();
        List<String> redisKeys = new ArrayList<>();
        String batchStream = keys.writeStreamKey(config.activeStreamKey(), items.get(0).binding.metadata().redisNamespace());
        redisKeys.add(batchStream);
        redisKeys.add(batchStream + ":batch:" + batchId);
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "",
                                Integer.toString(items.size()),
                                Long.toString(
                                        guardrail.enabled() && guardrail.rejectWritesOnHardLimit()
                                                ? guardrail.writeBehindBacklogHardLimit()
                                                : 0),
                                Long.toString(System.currentTimeMillis())));
        var mapper = new RedisWriteOperationMapper();
        var traceMetadata = com.reactor.cachedb.core.trace.TraceContexts.boundedCopy(trace.capture());
        Map<String, String> fields = new LinkedHashMap<>();
        if (!traceMetadata.isEmpty()) fields.put("traceMetadata", com.reactor.cachedb.core.trace.TraceMetadataCodec.encode(traceMetadata));
        fields.put("type", "UPSERT");
        fields.put("entity", "__atomic_batch");
        fields.put("table", "__atomic_batch");
        fields.put("namespace", "__atomic_batch");
        fields.put("id", batchId);
        fields.put("version", "1");
        fields.put("idColumn", "id");
        fields.put("versionColumn", "entity_version");
        fields.put("createdAt", Instant.now().toString());
        fields.put("transactionGroupId", batchId);
        fields.put("batchCount", Integer.toString(items.size()));
        List<String> viewArguments = new ArrayList<>();
        int viewCount = 0;
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var metadata = item.binding.metadata();
            String ns = metadata.redisNamespace();
            redisKeys.addAll(
                    List.of(
                            keys.entityKey(ns, item.id),
                            keys.versionKey(ns, item.id),
                            keys.tombstoneKey(ns, item.id),
                            keys.hotSetKey(ns),
                            keys.indexDegradedKey(ns), keys.indexAllKey(ns)));
            var policy =
                    producer == null
                            ? item.binding.cachePolicy()
                            : producer.effectiveCachePolicy(item.binding.cachePolicy());
            if (!policy.nearCachePolicy().enabled() && metadata.nearCachePolicy().enabled())
                policy = policy.withNearCachePolicy(metadata.nearCachePolicy());
            if (policy.nearCachePolicy().enabled()) {
                fields.put("nearInvalidationChannel", keys.nearInvalidationChannel());
                fields.put("b" + i + ":nearInvalidation", RedisNearCacheCoordinator.message(ns, item.id));
            }
            boolean admit =
                    item.type != OperationType.DELETE
                            && policy.shouldAdmit(
                                    item.admissionColumns, CacheAdmissionSource.WRITE);
            String payloadKey = keys.entityKey(ns, item.id);
            arguments.addAll(
                    List.of(
                            item.id.toString(),
                            item.payload,
                            Long.toString(item.expected),
                            item.type.name(),
                            Long.toString(policy.entityTtlSeconds()),
                            admit ? "1" : "0",
                            metadata.versionColumn(),
                            Integer.toString(policy.hotEntityLimit()),
                            payloadKey.substring(
                                    0, payloadKey.length() - item.id.toString().length()),
                            queryIndexesEnabled || !metadata.partitionedSortIndexes().isEmpty() ? "1" : "0"));
            for (var view : item.projections.entrySet()) {
                String viewNamespace = ns + ":projection:" + view.getKey();
                int base = redisKeys.size() + 1;
                redisKeys.addAll(
                        List.of(
                                keys.entityKey(viewNamespace, item.id),
                                keys.tombstoneKey(viewNamespace, item.id),
                                keys.indexDegradedKey(viewNamespace), keys.indexAllKey(viewNamespace)));
                viewArguments.addAll(
                        List.of(
                                Integer.toString(base),
                                view.getValue(),
                                item.type.name(),
                                Long.toString(policy.entityTtlSeconds()),
                                admit ? "1" : "0", queryIndexesEnabled ? "1" : "0"));
                viewCount++;
            }
            var columns = new LinkedHashMap<>(item.columns);
            if (metadata.deletedColumn() != null && item.type != OperationType.PATCH)
                columns.put(
                        metadata.deletedColumn(),
                        item.type == OperationType.DELETE
                                ? metadata.deletedMarkerValue()
                                : metadata.activeMarkerValue());
            columns.put(metadata.idColumn(), item.id.toString());
            var operation =
                    new QueuedWriteOperation(
                                    item.type,
                                    metadata.entityName(),
                                    metadata.tableName(),
                                    ns,
                                    "",
                                    metadata.idColumn(),
                                    metadata.versionColumn(),
                                    metadata.deletedColumn(),
                                    item.id.toString(),
                                    columns,
                                    0,
                                    Instant.now(),
                                    item.type == OperationType.PATCH ? ns : "",
                                    item.type == OperationType.PATCH ? item.id.toString() : "",
                                    item.type == OperationType.PATCH ? item.expected : 0,
                                    "", List.of(), traceMetadata)
                            .inTransaction(batchId);
            for (var field : mapper.toBody(operation).entrySet())
                fields.put("b" + i + ":" + field.getKey(), field.getValue());
            fields.remove("b" + i + ":version");
            fields.remove("b" + i + ":col:" + metadata.versionColumn());
        }
        // Projection keys follow all fixed entity key blocks.
        List<String> fixedKeys = new ArrayList<>(List.of(redisKeys.get(0), redisKeys.get(1)));
        List<String> projectionKeys = new ArrayList<>();
        int cursor = 2;
        for (var item : items) {
            fixedKeys.addAll(redisKeys.subList(cursor, cursor + 6));
            cursor += 6;
            int length = item.projections.size() * 4;
            projectionKeys.addAll(redisKeys.subList(cursor, cursor + length));
            cursor += length;
        }
        fixedKeys.addAll(projectionKeys);
        redisKeys = fixedKeys;
        for (int i = 0; i < viewArguments.size(); i += 6)
            viewArguments.set(i, Integer.toString(3 + items.size() * 6 + (i / 6) * 4));
        arguments.add(Integer.toString(viewCount));
        arguments.addAll(viewArguments);
        if (fields.size() > 2000)
            throw new IllegalArgumentException("Atomic batch exceeds 2000 stream fields");
        int streamFieldsStart = arguments.size();
        for (var field : fields.entrySet()) {
            arguments.add(field.getKey());
            arguments.add(field.getValue());
        }
        long bytes = 0;
        for (String argument : arguments) bytes += argument.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Atomic batch exceeds 4 MiB");
        arguments.set(0, fingerprint(redisKeys, arguments, streamFieldsStart));
        if (redis.clustered()) {
            try { redis.requireSameSlot(redisKeys); }
            catch (IllegalArgumentException crossSlot) {
                throw new IllegalArgumentException("Atomic write batch across different Redis Cluster slots requires a shared hash-tag scope", crossSlot);
            }
        }
        var reply = (List<?>) redis.eval(APPLY, redisKeys, arguments);
        List<Long> versions = new ArrayList<>();
        for (int i = 1; i < reply.size(); i++)
            versions.add(Long.parseLong(reply.get(i).toString()));
        committed = true;
        List<WriteDependency> dependencies = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            dependencies.add(
                    new WriteDependency(
                            item.binding.metadata().redisNamespace(),
                            item.id.toString(),
                            versions.get(i)));
        }
        return new CacheWriteBatchReceipt(batchId, reply.get(0).toString(), versions, dependencies);
    }

    private static String fingerprint(List<String> keys, List<String> args, int streamFieldsStart) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String key : keys) {
                hash.update(key.getBytes(StandardCharsets.UTF_8));
                hash.update((byte) 0);
            }
            for (int i = 1; i < args.size(); i++) {
                if (i >= streamFieldsStart && (i - streamFieldsStart) % 2 == 0
                        && (args.get(i).equals("traceMetadata") || args.get(i).endsWith(":traceMetadata")
                            || args.get(i).equals("nearInvalidationChannel") || args.get(i).endsWith(":nearInvalidation"))) { i++; continue; }
                if (i == 3) continue; // Acceptance clock is not part of the retry identity.
                if (i > 0 && args.get(i - 1).endsWith("createdAt")) continue;
                hash.update(args.get(i).getBytes(StandardCharsets.UTF_8));
                hash.update((byte) 0);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
