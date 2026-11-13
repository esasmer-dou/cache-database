package com.reactor.cachedb.redis;

import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;

/** Client lifecycle and topology-dependent operations; keyed commands use Jedis' shared API. */
public interface RedisClientOperations extends AutoCloseable {
    UnifiedJedis commands();

    default boolean clustered() {
        return false;
    }

    record GuardedSetMembers(boolean degraded, List<String> members) {
        public GuardedSetMembers {
            members = List.copyOf(members);
            if (degraded && !members.isEmpty())
                throw new IllegalArgumentException("Degraded candidate fetch cannot contain members");
        }
    }

    /** Empty means unsupported; a present result distinguishes degraded from healthy-empty. */
    default Optional<GuardedSetMembers> guardedSetMembers(
            String degradedKey, String membershipKey, int maxMembers) {
        return Optional.empty();
    }

    /** Finite per-node pool capacity; unknown custom clients use a conservative two. */
    default int physicalPoolCapacity() { return 2; }

    default Object serverInfo(String section, String routingKey) {
        return commands().sendCommand(redis.clients.jedis.Protocol.Command.INFO, section);
    }

    default Object memoryInfo(String routingKey) { return serverInfo("memory", routingKey); }

    default String ping(String routingKey) {
        return commands().ping();
    }

    default long delete(String... keys) {
        return commands().del(keys);
    }

    default AbstractPipeline pipeline() {
        return commands().pipelined();
    }

    default ScanResult<String> scan(String cursor, ScanParams params) {
        return commands().scan(cursor, params);
    }

    default List<String> mget(String... keys) {
        return commands().mget(keys);
    }

    /** Read-only admission before materializing payload replies, including a guarded multi-slot path. */
    default List<String> boundedMget(List<String> keys, long maxBytes) {
        if (keys.size() > 512 || maxBytes < 1 || maxBytes > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Bounded read supports 512 keys and at most 16 MiB");
        if (keys.isEmpty()) return List.of();
        long keyBytes = 0;
        for (String key : keys) {
            if (key == null || key.length() > 16_384) throw new IllegalArgumentException("Invalid bounded read key");
            keyBytes += 2L * key.length();
            if (keyBytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Bounded key byte budget exceeded");
        }
        boolean sameSlot = true;
        try { requireSameSlot(keys); } catch (IllegalArgumentException crossSlot) { sameSlot = false; }
        if (sameSlot) {
            Object raw = eval("""
                    local bytes=0
                    for _,key in ipairs(KEYS) do
                      bytes=bytes+redis.call('STRLEN',key)
                      if bytes>tonumber(ARGV[1]) then return redis.error_reply('Payload read byte budget exceeded') end
                    end
                    return redis.call('MGET',unpack(KEYS))
                    """, keys, List.of(Long.toString(maxBytes)));
            var values = new ArrayList<String>(keys.size());
            for (Object value : (List<?>) raw) values.add(value == null ? null : value.toString());
            return values;
        }
        var lengths = new ArrayList<redis.clients.jedis.Response<Long>>(keys.size());
        try (var pipeline = pipeline()) {
            for (String key : keys) lengths.add(pipeline.strlen(key));
            pipeline.sync();
        }
        long total = 0;
        for (var length : lengths) {
            total = Math.addExact(total,length.get());
            if (total > maxBytes) throw new IllegalStateException("Payload read byte budget exceeded");
        }
        var replies = new ArrayList<redis.clients.jedis.Response<Object>>(keys.size());
        try (var pipeline = pipeline()) {
            for (int i = 0; i < keys.size(); i++) replies.add(pipeline.eval("""
                    if redis.call('STRLEN',KEYS[1])>tonumber(ARGV[1]) then return redis.error_reply('Payload grew during bounded read') end
                    return redis.call('GET',KEYS[1])
                    """, List.of(keys.get(i)),List.of(Long.toString(lengths.get(i).get()))));
            pipeline.sync();
        }
        var values = new ArrayList<String>(keys.size());
        for (var reply : replies) { Object value = reply.get(); values.add(value == null ? null : value.toString()); }
        return values;
    }

    record IndexAggregateBatch(long count, List<String> values, List<Integer> nullCandidates) {
        public IndexAggregateBatch(long count, List<String> values) { this(count, values, List.of()); }
    }

    /** One read-only server call per bounded common-slot batch; numeric arithmetic stays in Java. */
    default Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, String field, boolean countOnly) {
        return aggregateIndexBatch(entities, tombstones, indexMetadata, null, null, field, countOnly);
    }

    /** Ownership, liveness and tombstones are checked in the same read-only script. */
    default Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, List<String> ownerKeys, String tenant, String field, boolean countOnly) {
        var command = indexAggregateCommand(entities, tombstones, indexMetadata, ownerKeys, tenant, field, countOnly);
        try { requireSameSlot(command.keys()); }
        catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        return parseIndexAggregateResponse(eval(command.script(), command.keys(), command.arguments()), entities.size(), countOnly);
    }

    record IndexReadKeys(List<String> entities, List<String> tombstones, List<String> indexMetadata, List<String> owners) {}
    record IndexReadCommand(String script, List<String> keys, List<String> arguments) {}

    /** Explicit opt-in: existing adapters keep their tenant-scoped COUNT(*) phase contract. */
    default boolean supportsUnownedIndexCounts() { return false; }

    /** Optional COUNT(*) phase; opted-in null tenant requires null owners. Each window retains its snapshot boundary. */
    default java.util.OptionalLong aggregateIndexCounts(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant) {
        return java.util.OptionalLong.empty();
    }

    /** Optional bounded facet phase; absence means retry the original sequential windows in full. */
    default Optional<List<FacetIndexBatch>> facetIndexBatches(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant, String groupField, String valueField) {
        return Optional.empty();
    }

    record FacetIndexCounts(Map<String, Long> counts, List<Integer> nullCandidates) {
        public FacetIndexCounts { counts = Map.copyOf(counts); nullCandidates = List.copyOf(nullCandidates); }
    }

    /** Optional COUNT(*) histogram; sentinel positions still require live payload disambiguation. */
    default Optional<List<FacetIndexCounts>> facetIndexCounts(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant, String groupField) {
        return Optional.empty();
    }

    enum FacetPhaseDecline { UNSUPPORTED, EVAL_COMMAND_DENIED, SOFT_BYTE_BUDGET }

    record FacetPhaseResult<T>(Optional<List<T>> batches, FacetPhaseDecline decline) {
        public FacetPhaseResult {
            Objects.requireNonNull(batches, "batches");
            if (batches.isPresent() == (decline != null))
                throw new IllegalArgumentException("Facet phase requires either batches or a decline reason");
            batches = batches.map(List::copyOf);
        }
        public static <T> FacetPhaseResult<T> available(List<T> batches) {
            return new FacetPhaseResult<>(Optional.of(batches), null);
        }
        public static <T> FacetPhaseResult<T> declined(FacetPhaseDecline reason) {
            return new FacetPhaseResult<>(Optional.empty(), Objects.requireNonNull(reason, "reason"));
        }
        public boolean skipRowsPhase() {
            return decline == FacetPhaseDecline.EVAL_COMMAND_DENIED || decline == FacetPhaseDecline.SOFT_BYTE_BUDGET;
        }
    }

    /** Legacy adapters keep their Optional override and original ROWS fallback behavior. */
    default FacetPhaseResult<FacetIndexCounts> facetIndexCountsPhase(int candidates,
            java.util.function.IntFunction<IndexReadKeys> windows, String tenant, String groupField) {
        var batches = facetIndexCounts(candidates, windows, tenant, groupField);
        return batches.isPresent() ? FacetPhaseResult.available(batches.get())
                : FacetPhaseResult.declined(FacetPhaseDecline.UNSUPPORTED);
    }

    static IndexReadCommand indexAggregateCommand(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, List<String> ownerKeys, String tenant, String field, boolean countOnly) {
        if (field != null && field.length() > 4096) throw new IllegalArgumentException("Aggregate field exceeds 4096 characters");
        boolean hasField = field != null && !field.isBlank() && !field.equals("*");
        if (!countOnly && !hasField) throw new IllegalArgumentException("Numeric aggregation requires a field");
        var keys = indexBatchKeys(entities, tombstones, indexMetadata, ownerKeys, tenant, hasField);
        int size = entities.size();
        return new IndexReadCommand("""
                local n=tonumber(ARGV[3]); local count=0; local values={}; local bytes=0; local nulls={}
                local owned=ARGV[4]=='1'; local ownerOffset=2*n
                if ARGV[1]~='' then ownerOffset=3*n end
                if ARGV[2]=='1' and ARGV[1]=='' then
                  local tenant=ARGV[5]; local tenantLength=#tenant
                  -- Atomic batch-wide liveness proof avoids two Lua command dispatches per row.
                  local live=redis.call('EXISTS',unpack(KEYS,1,n))==n and redis.call('EXISTS',unpack(KEYS,n+1,2*n))==0
                  if live then
                    if not owned then return {n,values,nulls} end
                  end
                  for i=1,n do
                    local allowed=live or (redis.call('EXISTS',KEYS[i])==1 and redis.call('EXISTS',KEYS[n+i])==0)
                    if allowed and owned then
                      local key=KEYS[ownerOffset+i]
                      -- The inclusive end reads one extra byte, so a longer tenant prefix cannot match.
                      local observed=redis.pcall('GETRANGE',key,0,tenantLength)
                      allowed=observed==tenant
                      if not allowed and type(observed)=='table' and observed.err then
                        if not redis.acl_check_cmd or redis.acl_check_cmd('GETRANGE',key,0,tenantLength) then
                          return observed
                        end
                        allowed=redis.call('STRLEN',key)==tenantLength and redis.call('GET',key)==tenant
                      end
                    end
                    if allowed then count=count+1 end
                  end
                  return {count,values,nulls}
                end
                for i=1,n do
                  local allowed=redis.call('EXISTS',KEYS[i])==1 and redis.call('EXISTS',KEYS[n+i])==0
                  if allowed and owned then
                    local key=KEYS[ownerOffset+i]
                    allowed=redis.call('STRLEN',key)==#ARGV[5] and redis.call('GET',key)==ARGV[5]
                  end
                  if allowed then
                    local value=false
                    if ARGV[1]~='' then
                      local key=KEYS[2*n+i]; local length=redis.call('HSTRLEN',key,ARGV[1])
                      if length==0 and redis.call('HEXISTS',key,ARGV[1])==0 then
                        return redis.error_reply('Incomplete aggregate index metadata; rebuild indexes')
                      end
                      bytes=bytes+length
                      if bytes>16777216 then return redis.error_reply('Aggregate byte budget exceeded') end
                      value=redis.call('HGET',key,ARGV[1])
                    end
                    if ARGV[2]=='0' or ARGV[1]=='' or value~='__NULL__' then
                      count=count+1
                      if ARGV[2]=='0' and value~='__NULL__' then
                        table.insert(values,value)
                      end
                    end
                    if ARGV[2]=='1' and ARGV[1]~='' and value=='__NULL__' then table.insert(nulls,i-1) end
                  end
                end
                return {count,values,nulls}
                """, keys, List.of(hasField ? field : "", countOnly ? "1" : "0", Integer.toString(size),
                        ownerKeys == null ? "0" : "1", tenant == null ? "" : tenant));
    }

    static Optional<IndexAggregateBatch> parseIndexAggregateResponse(Object response, int size, boolean countOnly) {
        if (!(response instanceof List<?> result) || result.size() != 3
                || !(result.get(0) instanceof Number total) || total.longValue() < 0 || total.longValue() > size
                || total.longValue() != total.doubleValue()
                || !(result.get(1) instanceof List<?> rawValues) || rawValues.size() > total.longValue()
                || !(result.get(2) instanceof List<?> rawNulls) || rawNulls.size() > size)
            throw new IllegalStateException("Invalid aggregate batch response");
        var values = new ArrayList<String>(rawValues.size());
        long bytes = 0;
        for (Object value : rawValues) {
            if (!(value instanceof String text)) throw new IllegalStateException("Invalid aggregate batch value");
            bytes += 2L * text.length();
            if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Aggregate byte budget exceeded");
            values.add(text);
        }
        var nulls = new ArrayList<Integer>(rawNulls.size());
        int previous = -1;
        for (Object index : rawNulls) {
            int position = indexBatchPosition(index, size, previous);
            nulls.add(position); previous = position;
        }
        if (countOnly && !values.isEmpty() || !countOnly && !nulls.isEmpty()
                || total.longValue() + nulls.size() > size)
            throw new IllegalStateException("Invalid aggregate batch counts");
        return Optional.of(new IndexAggregateBatch(total.longValue(), List.copyOf(values), List.copyOf(nulls)));
    }

    record FacetIndexRow(int candidate, String groupRaw, String valueRaw) {}
    record FacetIndexBatch(List<FacetIndexRow> rows) {
        public FacetIndexBatch { rows = List.copyOf(rows); }
    }

    /** Compact raw pairs plus original positions; null valueRaw means no value field was requested. */
    default Optional<FacetIndexBatch> facetIndexBatch(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, List<String> ownerKeys, String tenant, String groupField, String valueField) {
        var command = facetIndexCommand(entities, tombstones, indexMetadata, ownerKeys, tenant, groupField, valueField, 0);
        try { requireSameSlot(command.keys()); }
        catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        return parseFacetIndexResponse(eval(command.script(), command.keys(), command.arguments()),
                entities.size(), valueField, 16L * 1024 * 1024);
    }

    static IndexReadCommand facetIndexCommand(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, List<String> ownerKeys, String tenant, String groupField, String valueField,
            long phaseBudget) {
        return facetIndexCommand(entities, tombstones, indexMetadata, ownerKeys, tenant, groupField, valueField, phaseBudget, false);
    }

    static IndexReadCommand facetIndexCommand(List<String> entities, List<String> tombstones,
            List<String> indexMetadata, List<String> ownerKeys, String tenant, String groupField, String valueField,
            long phaseBudget, boolean compactCounts) {
        if (compactCounts && valueField != null) throw new IllegalArgumentException("Compact facets require COUNT(*)");
        requireIndexBatchField(groupField);
        if (valueField != null) requireIndexBatchField(valueField);
        if (phaseBudget < 0 || phaseBudget > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid facet phase budget");
        var keys = indexBatchKeys(entities, tombstones, indexMetadata, ownerKeys, tenant, true);
        var arguments = new ArrayList<>(List.of(groupField, valueField == null ? "" : valueField,
                Integer.toString(entities.size()), ownerKeys == null ? "0" : "1", tenant == null ? "" : tenant));
        if (phaseBudget != 0) arguments.add(Long.toString(phaseBudget));
        if (compactCounts) {
            if (phaseBudget == 0) throw new IllegalArgumentException("Compact facets require a bounded phase");
            arguments.add("1");
        }
        return new IndexReadCommand(RedisFacetIndexScripts.script(compactCounts), keys, arguments);
    }

    static FacetIndexCounts parseFacetIndexCounts(Object response, int size, long maxBytes) {
        if (!(response instanceof List<?> result) || result.size() != 2
                || !(result.get(0) instanceof List<?> pairs) || pairs.size() > Math.min(size, 256)
                || !(result.get(1) instanceof List<?> rawNulls) || rawNulls.size() > size)
            throw new IllegalStateException("Invalid facet count response");
        var counts = new LinkedHashMap<String, Long>();
        long total = 0, bytes = 0;
        for (Object entry : pairs) {
            if (!(entry instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof String group)
                    || group.equals("__NULL__") || group.length() > 4096 || !(pair.get(1) instanceof Long count)
                    || count < 1 || count > size || counts.putIfAbsent(group, count) != null)
                throw new IllegalStateException("Invalid facet count group");
            total += count;
            bytes += count * (2L * group.length() + 16);
            if (total + rawNulls.size() > size || bytes > maxBytes)
                throw new IllegalStateException("Facet count budget exceeded");
        }
        var nulls = new ArrayList<Integer>(rawNulls.size());
        int previous = -1;
        for (Object index : rawNulls) {
            previous = indexBatchPosition(index, size, previous);
            nulls.add(previous);
        }
        return new FacetIndexCounts(counts, nulls);
    }

    static Optional<FacetIndexBatch> parseFacetIndexResponse(Object response, int size, String valueField, long maxBytes) {
        if (!(response instanceof List<?> result) || result.size() != 2
                || !(result.get(0) instanceof List<?> positions) || positions.size() > size
                || !(result.get(1) instanceof List<?> pairs) || positions.size() != pairs.size())
            throw new IllegalStateException("Invalid facet batch response");
        var rows = new ArrayList<FacetIndexRow>(positions.size());
        int previous = -1; long bytes = 0;
        for (int i = 0; i < positions.size(); i++) {
            int position = indexBatchPosition(positions.get(i), size, previous); previous = position;
            if (!(pairs.get(i) instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof String group)
                    || valueField != null && !(pair.get(1) instanceof String)
                    || valueField == null && pair.get(1) != null && !Boolean.FALSE.equals(pair.get(1)))
                throw new IllegalStateException("Invalid facet batch pair");
            String value = valueField == null ? null : (String) pair.get(1);
            bytes += 2L * group.length() + (value == null ? 0 : 2L * value.length());
            if (bytes > maxBytes) throw new IllegalStateException("Facet byte budget exceeded");
            rows.add(new FacetIndexRow(position, group, value));
        }
        return Optional.of(new FacetIndexBatch(rows));
    }

    /** Sentinel re-read: admission and payload fetch share atomic owner/live/tombstone checks. */
    default Optional<List<String>> liveIndexPayloadBatch(List<String> entities, List<String> tombstones,
            List<String> ownerKeys, String tenant) {
        var keys = indexBatchKeys(entities, tombstones, entities, ownerKeys, tenant, false);
        try { requireSameSlot(keys); }
        catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        Object response = eval("""
                local n=tonumber(ARGV[1]); local bytes=0; local result={}
                for i=1,n do
                  local allowed=redis.call('EXISTS',KEYS[i])==1 and redis.call('EXISTS',KEYS[n+i])==0
                  if allowed and ARGV[2]=='1' then
                    local key=KEYS[2*n+i]
                    allowed=redis.call('STRLEN',key)==#ARGV[3] and redis.call('GET',key)==ARGV[3]
                  end
                  local value=false
                  if allowed then
                    bytes=bytes+redis.call('STRLEN',KEYS[i])
                    if bytes>16777216 then return redis.error_reply('Payload read byte budget exceeded') end
                    value=redis.call('GET',KEYS[i])
                  end
                  table.insert(result,value)
                end
                return result
                """, keys, List.of(Integer.toString(entities.size()), ownerKeys == null ? "0" : "1",
                        tenant == null ? "" : tenant));
        if (!(response instanceof List<?> result) || result.size() != entities.size())
            throw new IllegalStateException("Invalid live payload batch response");
        var payloads = new ArrayList<String>(result.size()); long bytes = 0;
        for (Object value : result) {
            if (value != null && !Boolean.FALSE.equals(value) && !(value instanceof String))
                throw new IllegalStateException("Invalid live payload batch value");
            String payload = value instanceof String text ? text : null;
            bytes += payload == null ? 0 : 2L * payload.length();
            if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Payload read byte budget exceeded");
            payloads.add(payload);
        }
        return Optional.of(Collections.unmodifiableList(payloads));
    }

    private static void requireIndexBatchField(String field) {
        if (field == null || field.isBlank() || field.length() > 4096)
            throw new IllegalArgumentException("Index batch field requires 1..4096 characters");
    }

    private static int indexBatchPosition(Object value, int size, int previous) {
        if (!(value instanceof Number number) || number.longValue() <= previous || number.longValue() >= size
                || number.longValue() != number.doubleValue())
            throw new IllegalStateException("Invalid index batch candidate position");
        return number.intValue();
    }

    private static List<String> indexBatchKeys(List<String> entities, List<String> tombstones,
            List<String> metadata, List<String> owners, String tenant, boolean includeMetadata) {
        Objects.requireNonNull(entities, "entities"); Objects.requireNonNull(tombstones, "tombstones");
        Objects.requireNonNull(metadata, "indexMetadata");
        int size = entities.size();
        if (size < 1 || size > 512 || tombstones.size() != size || metadata.size() != size
                || owners != null && owners.size() != size)
            throw new IllegalArgumentException("Index batch requires 1..512 aligned identities");
        if ((owners == null) != (tenant == null) || tenant != null && (tenant.isBlank() || tenant.length() > 4096))
            throw new IllegalArgumentException("Owner keys require a tenant of 1..4096 characters");
        var keys = new ArrayList<String>(size * (2 + (includeMetadata ? 1 : 0) + (owners == null ? 0 : 1)));
        keys.addAll(entities); keys.addAll(tombstones);
        if (includeMetadata) keys.addAll(metadata);
        if (owners != null) keys.addAll(owners);
        // Three bytes per UTF-16 unit bounds UTF-8 without allocating an encoded copy.
        long bytes = 3L * (8192 + (tenant == null ? 0 : tenant.length()));
        for (String key : keys) {
            if (key == null || key.length() > 16_384) throw new IllegalArgumentException("Invalid index batch key");
            bytes += 3L * key.length();
            if (bytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Index batch key byte budget exceeded");
        }
        return keys;
    }

    /** Empty means unsupported topology/shape, not an empty result. Both prefixes include the trailing colon. */
    default Optional<List<String>> zrangeWithPayloads(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return Optional.empty();
    }

    record OrderedPayload(String payload, long ttlMillis) {}

    /** Unknown TTL (0) deliberately prevents local caching for legacy/custom adapters. */
    default Optional<List<OrderedPayload>> orderedPayloadWindow(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return zrangeWithPayloads(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit)
                .map(values -> values.stream().map(value -> new OrderedPayload(value, 0)).toList());
    }

    default Optional<List<String>> fetchOrderedPayloads(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        return fetchOrderedPayloadWindow(indexKey, tombstonePrefix, entityPrefix, reverse, offset, limit)
                .map(values -> values.stream().map(OrderedPayload::payload).toList());
    }

    default Optional<List<OrderedPayload>> fetchOrderedPayloadWindow(String indexKey, String tombstonePrefix,
            String entityPrefix, boolean reverse, int offset, int limit) {
        if (offset < 0 || offset > 10_000 || limit < 1 || limit > 1000) return Optional.empty();
        if (clustered()) {
            int start = entityPrefix.indexOf('{'), end = entityPrefix.indexOf('}', start + 1);
            if (start < 0 || end <= start + 1) return Optional.empty();
            String tag = entityPrefix.substring(start, end + 1);
            if (!indexKey.contains(tag) || !tombstonePrefix.contains(tag)) return Optional.empty();
            try { requireSameSlot(List.of(indexKey, tombstonePrefix, entityPrefix)); }
            catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        }
        Object raw = eval("""
                local kind=redis.call('TYPE',KEYS[1]).ok
                if kind=='none' then return false end
                if kind~='zset' and kind~='list' then return redis.error_reply('Invalid ordered index type') end
                local offset=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); local result={}; local bytes=0
                if kind=='list' and redis.call('LLEN',KEYS[1])>limit then return false end
                local rank=offset; local scanned=0; local budget=math.min(10000,limit*16+128)
                while #result<limit and scanned<budget do
                  local finish=rank+math.min(127,budget-scanned-1)
                  local ids
                  if kind=='list' then ids=redis.call('LRANGE',KEYS[1],rank,finish)
                  elseif ARGV[3]=='1' then ids=redis.call('ZREVRANGE',KEYS[1],rank,finish)
                  else ids=redis.call('ZRANGE',KEYS[1],rank,finish) end
                  if #ids==0 then return result end
                  for _,id in ipairs(ids) do
                    scanned=scanned+1
                    if redis.call('EXISTS',KEYS[2]..id)==0 then
                      local payload=redis.call('GET',KEYS[3]..id)
                      if payload then
                        bytes=bytes+#payload
                        if bytes>16777216 then return redis.error_reply('Ordered payload byte budget exceeded') end
                        table.insert(result,{payload,redis.call('PTTL',KEYS[3]..id)})
                        if #result==limit then return result end
                      elseif kind=='list' then return false end
                    elseif kind=='list' then return false end
                  end
                  rank=finish+1
                end
                if #result<limit and scanned>=budget then return redis.error_reply('Ordered payload scan budget exceeded') end
                return result
                """, List.of(indexKey, tombstonePrefix, entityPrefix),
                List.of(String.valueOf(offset), String.valueOf(limit), reverse ? "1" : "0"));
        if (raw == null || Boolean.FALSE.equals(raw)) return Optional.empty();
        if (!(raw instanceof List<?> values)) throw new IllegalStateException("Invalid ordered payload response");
        var result = new ArrayList<OrderedPayload>(values.size());
        for (Object value : values) {
            if (!(value instanceof List<?> pair) || pair.size() != 2 || !(pair.get(1) instanceof Number ttl))
                throw new IllegalStateException("Invalid ordered payload/TTL response");
            result.add(new OrderedPayload(String.valueOf(pair.get(0)), ttl.longValue()));
        }
        return Optional.of(List.copyOf(result));
    }

    /** One server call on a common slot; two bounded pipeline phases across slots. */
    default Map<String, Set<String>> smembersBatch(Collection<String> keys, int maxMembers) {
        if (keys.size() > 4096 || maxMembers < 1) throw new IllegalArgumentException("Invalid set batch budget");
        var result = new LinkedHashMap<String, Set<String>>();
        var distinct = new ArrayList<>(new LinkedHashSet<>(keys));
        if (distinct.isEmpty()) return result;
        boolean sameSlot = true;
        try { requireSameSlot(distinct); } catch (IllegalArgumentException crossSlot) { sameSlot = false; }
        if (sameSlot) {
            Object raw = eval("local n=0; for _,k in ipairs(KEYS) do n=n+redis.call('SCARD',k); "
                    + "if n>tonumber(ARGV[1]) then return redis.error_reply('CacheDB materialized membership budget exceeded') end end; "
                    + "local result={}; for _,k in ipairs(KEYS) do table.insert(result,redis.call('SMEMBERS',k)) end; return result",
                    distinct, List.of(String.valueOf(maxMembers)));
            var sets = (List<?>) raw;
            if (sets.size() != distinct.size()) throw new IllegalStateException("Invalid set batch response");
            for (int i = 0; i < sets.size(); i++) {
                var members = new LinkedHashSet<String>();
                for (Object value : (List<?>) sets.get(i)) members.add(String.valueOf(value));
                result.put(distinct.get(i), members);
            }
            return result;
        }
        var cardinalities = new LinkedHashMap<String, redis.clients.jedis.Response<Long>>();
        try (var pipeline = pipeline()) {
            for (String key : distinct) cardinalities.put(key, pipeline.scard(key));
            pipeline.sync();
        }
        long total = 0;
        for (var value : cardinalities.values()) {
            total += value.get();
            if (total > maxMembers) throw new IllegalStateException("CacheDB materialized membership budget exceeded");
        }
        var responses = new LinkedHashMap<String, redis.clients.jedis.Response<Object>>();
        String script = "local n=redis.call('SCARD',KEYS[1]); if n>tonumber(ARGV[1]) then "
                + "return redis.error_reply('CacheDB candidate budget exceeded') end; return redis.call('SMEMBERS',KEYS[1])";
        try (var pipeline = pipeline()) {
            for (String key : distinct)
                responses.put(key, pipeline.eval(script, List.of(key), List.of(String.valueOf(cardinalities.get(key).get()))));
            pipeline.sync();
            for (var entry : responses.entrySet()) {
                var members = new LinkedHashSet<String>();
                for (Object value : (List<?>) entry.getValue().get()) members.add(String.valueOf(value));
                result.put(entry.getKey(), members);
            }
        }
        return result;
    }

    default Map<String, Set<String>> smembersBatch(Collection<String> keys) {
        return smembersBatch(keys, 100_000);
    }

    default Set<String> probeMembers(String key, Collection<String> candidates) {
        if (candidates.isEmpty()) return Set.of();
        if (candidates.size() > 128) throw new IllegalArgumentException("Membership probe exceeds 128 candidates");
        String[] ids = candidates.toArray(String[]::new);
        List<Boolean> included;
        try { included = commands().smismember(key, ids); }
        catch (redis.clients.jedis.exceptions.JedisDataException unsupported) {
            if (!unsupported.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unknown command")) throw unsupported;
            var responses = new ArrayList<redis.clients.jedis.Response<Boolean>>(ids.length);
            try (var pipeline = pipeline()) {
                for (String id : ids) responses.add(pipeline.sismember(key, id));
                pipeline.sync();
                included = responses.stream().map(redis.clients.jedis.Response::get).toList();
            }
        }
        var result = new LinkedHashSet<String>();
        for (int i = 0; i < ids.length; i++) if (Boolean.TRUE.equals(included.get(i))) result.add(ids[i]);
        return result;
    }

    default Optional<Set<String>> intersectSets(List<String> keys, int maxMembers) {
        if (keys.size() < 2 || keys.size() > 64) return Optional.empty();
        try { requireSameSlot(keys); } catch (IllegalArgumentException crossSlot) { return Optional.empty(); }
        Object raw = eval("local m=tonumber(ARGV[1]); local smallest=m+1; "
                + "for _,k in ipairs(KEYS) do local n=redis.call('SCARD',k); if n==0 then return {} end; "
                + "smallest=math.min(smallest,n) end; if smallest>m then "
                + "return redis.error_reply('CacheDB candidate budget exceeded') end; return redis.call('SINTER',unpack(KEYS))",
                keys, List.of(String.valueOf(maxMembers)));
        var result = new LinkedHashSet<String>();
        for (Object value : (List<?>) raw) result.add(String.valueOf(value));
        return Optional.of(result);
    }

    default Object eval(String script, List<String> keys, List<String> arguments) {
        requireSameSlot(keys);
        try { return commands().eval(script, keys, arguments); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    default Object eval(String script, int keyCount, String... arguments) {
        requireSameSlot(Arrays.asList(arguments).subList(0, keyCount));
        try { return commands().eval(script, keyCount, arguments); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    default Object fcall(String function, List<String> keys, List<String> arguments) {
        requireSameSlot(keys);
        try { return commands().fcall(function, keys, arguments); }
        catch (RuntimeException failure) { RedisTopologyChangeSource.failure(this, failure); throw failure; }
    }

    default void requireSameSlot(Collection<String> keys) {
        if (!clustered() || keys.isEmpty()) return;
        int slot = -1;
        for (String key : keys) {
            int current = redis.clients.jedis.util.JedisClusterCRC16.getSlot(key);
            if (slot >= 0 && slot != current)
                throw new IllegalArgumentException(
                        "Atomic Redis keys must share one Cluster hash slot");
            slot = current;
        }
    }

    default String functionLoad(String code) {
        return commands().functionLoad(code);
    }

    default String functionLoadReplace(String code) {
        return commands().functionLoadReplace(code);
    }

    default JedisPooled pooledClient() {
        throw new UnsupportedOperationException("Redis client uses Cluster topology");
    }

    @Override
    default void close() {
        commands().close();
    }
}

/** Canonical facet scripts, assembled once without adding interface constants or per-request copies. */
final class RedisFacetIndexScripts {
    private static final String PREFIX = """
            local redis=redis; local call=redis.call; local pcall=redis.pcall; local aclCheck=redis.acl_check_cmd; local unpack=unpack
            local errorReply=redis.error_reply; local insert=table.insert
            local n=tonumber(ARGV[3]); local groupField=ARGV[1]; local valueField=ARGV[2]
            local owned=ARGV[4]=='1'; local tenant=ARGV[5]; local tenantLength=#tenant
            local tombOffset=n; local metadataOffset=2*n; local ownerOffset=3*n
            local bytes=0; local budget=ARGV[6] and tonumber(ARGV[6]); local exceeded=false
            if ARGV[6] and (not budget or budget<1 or budget>16777216 or budget~=math.floor(budget)) then
              return errorReply('Invalid facet phase budget')
            end
            local allLive=false
            if aclCheck then
              -- Probe the exact vectors first; permission is not a cached command capability.
              -- Only denial of this exact command/key slice restores the original row error order.
              -- An error from a permitted vector must propagate without row fallback.
              local entities=pcall('EXISTS',unpack(KEYS,1,n))
              if type(entities)=='table' and entities.err then
                if aclCheck('EXISTS',unpack(KEYS,1,n)) then return entities end
              elseif entities==n then
                local tombstones=pcall('EXISTS',unpack(KEYS,n+1,2*n))
                if type(tombstones)=='table' and tombstones.err then
                  if aclCheck('EXISTS',unpack(KEYS,n+1,2*n)) then return tombstones end
                else allLive=tombstones==0 end
              end
            end
            local ownerValues=false; local ownerPositions=false
            if allLive and owned then
              local qualified=false
              for i=1,n do
                local key=KEYS[ownerOffset+i]
                if redis.call('STRLEN',key)==tenantLength then
                  if qualified then
                    qualified[#qualified+1]=key; ownerPositions[i]=#qualified
                  end
                elseif not qualified then
                  -- Preserve the checked prefix without repeating STRLEN or allocating on the all-match path.
                  qualified={}; ownerPositions={}
                  for j=1,i-1 do qualified[j]=KEYS[ownerOffset+j]; ownerPositions[j]=j end
                end
              end
              -- Length checks preserve WRONGTYPE and avoid materializing oversized owners.
              local ownerCount=qualified and #qualified or n
              if ownerCount>0 then
                local observed=false
                if redis.acl_check_cmd then
                  if qualified then observed=redis.pcall('MGET',unpack(qualified))
                  else observed=pcall('MGET',unpack(KEYS,ownerOffset+1,ownerOffset+n)) end
                  if observed.err then
                    if qualified then
                      if redis.acl_check_cmd('MGET',unpack(qualified)) then return observed end
                    elseif aclCheck('MGET',unpack(KEYS,ownerOffset+1,ownerOffset+n)) then return observed end
                    observed=false
                  end
                end
                if observed then ownerValues=observed
                else
                  ownerValues=qualified or {}
                  for i=1,ownerCount do
                    local key=qualified and qualified[i] or KEYS[ownerOffset+i]
                    ownerValues[i]=redis.call('GET',key)
                  end
                end
              else ownerValues={} end
            end
            """;

    private static final String ADMITTED_GROUP = """
            for i=1,n do
              local allowed=allLive or call('EXISTS',KEYS[i])==1 and call('EXISTS',KEYS[tombOffset+i])==0
              if allowed and owned then
                if allLive then
                  local position=i
                  if ownerPositions then position=ownerPositions[i] end
                  allowed=position and ownerValues[position]==tenant
                else
                  local key=KEYS[ownerOffset+i]
                  allowed=call('STRLEN',key)==tenantLength and call('GET',key)==tenant
                end
              end
              if allowed then
                local key=KEYS[metadataOffset+i]; local gl=call('HSTRLEN',key,groupField)
                if gl==0 and call('HEXISTS',key,groupField)==0 then
                  return errorReply('Incomplete facet index metadata; rebuild indexes')
                end
            """;

    private static final String COMPACT = PREFIX + """
            local pairs={}; local counts={}; local nulls={}; local groups=0
            """ + ADMITTED_GROUP + """
                bytes=bytes+gl
                if bytes>16777216 then return errorReply('Facet byte budget exceeded') end
                if budget and bytes>budget and not exceeded then
                  exceeded=true; pairs={}; counts=false; nulls=false
                end
                local group=call('HGET',key,groupField)
                if not exceeded then
                  if group=='__NULL__' then insert(nulls,i-1)
                  else
                    local current=counts[group]
                    if not current then
                      groups=groups+1
                      if groups>256 then return redis.error_reply('Facet group limit exceeded: max 256') end
                      current=0
                    end
                    counts[group]=current+1
                  end
                end
              end
            end
            if exceeded then return {'cachedb-facet-phase-budget-v1',n,budget} end
            for group,count in next,counts do insert(pairs,{group,count}) end
            return {pairs,nulls}
            """;

    private static final String ROWS = PREFIX + """
            local pairs={}; local positions={}
            """ + ADMITTED_GROUP + """
                local vl=0
                if valueField~='' then
                  vl=call('HSTRLEN',key,valueField)
                  if vl==0 and call('HEXISTS',key,valueField)==0 then
                    return errorReply('Incomplete facet index metadata; rebuild indexes')
                  end
                end
                bytes=bytes+gl+vl
                if bytes>16777216 then return errorReply('Facet byte budget exceeded') end
                if budget and bytes>budget and not exceeded then exceeded=true; positions={}; pairs={} end
                local group=redis.call('HGET',key,groupField); local value=false
                if valueField~='' then value=call('HGET',key,valueField) end
                if not exceeded then insert(positions,i-1); insert(pairs,{group,value}) end
              end
            end
            if exceeded then return {'cachedb-facet-phase-budget-v1',n,budget} end
            return {positions,pairs}
            """;

    static String script(boolean compact) { return compact ? COMPACT : ROWS; }

    private RedisFacetIndexScripts() {}
}
