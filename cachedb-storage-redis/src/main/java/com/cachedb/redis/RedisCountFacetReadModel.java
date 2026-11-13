package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.readmodel.CountFacetRead;
import com.reactor.cachedb.core.readmodel.CountFacetReadModel;
import com.reactor.cachedb.core.readmodel.CountFacetDefinition;
import com.reactor.cachedb.core.readmodel.CountFacetRefresh;
import com.reactor.cachedb.core.readmodel.CountFacetSnapshot;
import com.reactor.cachedb.core.readmodel.CountFacetValues;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicit, bounded aggregate snapshots. Reads never execute the source or refresh implicitly.
 * Source aggregation is caller-owned and is not made transactionally atomic by this API.
 */
public final class RedisCountFacetReadModel implements CountFacetReadModel {
    static final int MAX_VALUES_BYTES = 4 * 1024 * 1024;
    private static final String CLOCK = """
            local function clock()
                local t=redis.call('TIME')
                return string.format('%.0f',tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000))
            end
            """;
    private static final String LEASE_GUARD = """
            local leaseType=redis.call('TYPE',KEYS[2]).ok
            if leaseType~='none' and (leaseType~='string' or redis.call('STRLEN',KEYS[2])>128) then
                return redis.error_reply('CACHEDB_READ_MODEL_INVALID_LEASE')
            end
            """;
    private static final String ACQUIRE = CLOCK + LEASE_GUARD + """
            local now=clock()
            if not redis.call('SET',KEYS[2],ARGV[1],'NX','PX',ARGV[2]) then return {'BUSY'} end
            return {'ACQUIRED',now}
            """;
    private static final String RELEASE = LEASE_GUARD + """
            if redis.call('GET',KEYS[2])==ARGV[1] then return redis.call('DEL',KEYS[2]) end
            return 0
            """;
    private static final String PUBLISH = CLOCK + LEASE_GUARD + """
            if redis.call('GET',KEYS[2])~=ARGV[1] then return {'LEASE_LOST'} end
            local snapshotType=redis.call('TYPE',KEYS[1]).ok
            local sequenceType=redis.call('TYPE',KEYS[3]).ok
            if snapshotType~='none' and (snapshotType~='hash' or redis.call('HLEN',KEYS[1])~=6) then
                return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SNAPSHOT')
            end
            if snapshotType=='hash' then
                for _,name in ipairs({'schema','generation','from','through','published','values'}) do
                    local size=redis.call('HSTRLEN',KEYS[1],name)
                    if size==0 or size>(name=='values' and 4194304 or 19) then
                        return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SNAPSHOT')
                    end
                end
            end
            if sequenceType~='none' then
                if sequenceType~='string' or redis.call('STRLEN',KEYS[3])>19
                    or redis.call('PTTL',KEYS[3])~=-1 then
                    return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SEQUENCE')
                end
                local prior=redis.call('GET',KEYS[3])
                if not string.match(prior,'^[1-9]%d*$') or
                    (#prior==19 and prior>='9223372036854775807') then
                    return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SEQUENCE')
                end
            elseif snapshotType~='none' then
                return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SEQUENCE')
            end
            local now=clock()
            if tonumber(now)<tonumber(ARGV[2]) then
                return redis.error_reply('CACHEDB_READ_MODEL_CLOCK_REVERSED')
            end
            -- Scripts do not roll back command errors: reject denied writes before modifying state.
            if not redis.acl_check_cmd('INCR',KEYS[3])
                or not redis.acl_check_cmd('HSET',KEYS[1],'schema','1','generation','1',
                    'from',ARGV[2],'through',now,'published',now,'values',ARGV[3])
                or not redis.acl_check_cmd('PEXPIRE',KEYS[1],ARGV[4])
                or not redis.acl_check_cmd('DEL',KEYS[2]) then
                return redis.error_reply('NOPERM CACHEDB_READ_MODEL_PUBLICATION_DENIED')
            end
            redis.call('INCR',KEYS[3])
            local generation=redis.call('GET',KEYS[3])
            redis.call('HSET',KEYS[1],'schema','1','generation',generation,
                'from',ARGV[2],'through',now,'published',now,'values',ARGV[3])
            redis.call('PEXPIRE',KEYS[1],ARGV[4])
            redis.call('DEL',KEYS[2])
            return {'PUBLISHED',generation,now}
            """;
    private static final String READ = CLOCK + """
            local now=clock()
            local kind=redis.call('TYPE',KEYS[1]).ok
            if kind=='none' then return {'MISSING',now} end
            if kind~='hash' or redis.call('HLEN',KEYS[1])~=6 then
                return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SNAPSHOT')
            end
            local fields={'schema','generation','from','through','published','values'}
            for i,name in ipairs(fields) do
                local n=redis.call('HSTRLEN',KEYS[1],name)
                local limit=i==6 and 4194304 or 19
                if n==0 or n>limit then
                    return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SNAPSHOT')
                end
            end
            local values=redis.call('HMGET',KEYS[1],unpack(fields))
            if redis.call('TYPE',KEYS[3]).ok~='string' or redis.call('STRLEN',KEYS[3])>19
                or redis.call('PTTL',KEYS[3])~=-1 then
                return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SEQUENCE')
            end
            local generation=redis.call('GET',KEYS[3])
            if not string.match(generation,'^[1-9]%d*$')
                or (#generation==19 and generation>'9223372036854775807') or #generation<#values[2]
                or (#generation==#values[2] and generation<values[2]) then
                return redis.error_reply('CACHEDB_READ_MODEL_INVALID_SEQUENCE')
            end
            return {'PRESENT',now,unpack(values)}
            """;

    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final String namespace;
    private final boolean tenantScoped;
    private final CountFacetDefinition definition;

    public RedisCountFacetReadModel(RedisClientOperations redis, RedisKeyStrategy keys,
            EntityMetadata<?, ?> source, CountFacetDefinition definition) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(source, "source");
        // Identity-preserving purge participants cannot attest removal of aggregate group names.
        if (source.tenantPurgeEnabled()) {
            throw new UnsupportedOperationException(
                    "COUNT/FACET snapshots require a dedicated purge participant; protected sources are not supported");
        }
        namespace = Objects.requireNonNull(source.redisNamespace(), "source namespace");
        if (namespace.isBlank() || namespace.length() > 128) {
            throw new IllegalArgumentException("Read-model source namespace must contain 1..128 characters");
        }
        requireWellFormed(namespace, "Source namespace");
        tenantScoped = source.tenantFieldName() != null && !source.tenantFieldName().isBlank();
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    @Override
    public CountFacetDefinition definition() {
        return definition;
    }

    @Override
    public CountFacetRead inspect() {
        var reply = reply(redis.eval(READ, scopeKeys(scope()), List.of()));
        Instant checkedAt = instant(reply, 1);
        if ("MISSING".equals(reply.get(0)) && reply.size() == 2) {
            return new CountFacetRead(CountFacetRead.Status.MISSING, checkedAt, Optional.empty());
        }
        if (reply.size() != 8 || !"PRESENT".equals(reply.get(0)) || !"1".equals(reply.get(2))) {
            throw malformed();
        }
        var snapshot = new CountFacetSnapshot(number(reply.get(3)), instant(reply, 4),
                instant(reply, 5), instant(reply, 6), decode(string(reply.get(7))));
        if (checkedAt.isBefore(snapshot.publishedAt())) throw malformed();
        boolean fresh = java.time.Duration.between(snapshot.observedFrom(), checkedAt)
                .compareTo(definition.maxAge()) < 0;
        return new CountFacetRead(fresh ? CountFacetRead.Status.FRESH : CountFacetRead.Status.STALE,
                checkedAt, Optional.of(snapshot));
    }

    @Override
    public CountFacetRefresh refresh(Supplier<CountFacetValues> source) {
        Objects.requireNonNull(source, "source");
        String scope = scope();
        List<String> routed = scopeKeys(scope);
        String token = UUID.randomUUID().toString();
        var acquired = reply(redis.eval(ACQUIRE, routed,
                List.of(token, Long.toString(definition.refreshLease().toMillis()))));
        if (acquired.size() == 1 && "BUSY".equals(acquired.getFirst())) {
            return new CountFacetRefresh(CountFacetRefresh.Status.BUSY, Optional.empty());
        }
        if (acquired.size() != 2 || !"ACQUIRED".equals(acquired.getFirst())) throw malformed();
        Instant from = instant(acquired, 1);
        Throwable failure = null;
        boolean release = true;
        try {
            CountFacetValues values = Objects.requireNonNull(source.get(), "source values");
            if (!scope.equals(scope())) {
                throw new IllegalStateException("Tenant context changed during read-model refresh");
            }
            String payload = encode(values);
            var published = reply(redis.eval(PUBLISH, routed,
                    List.of(token, Long.toString(from.toEpochMilli()), payload,
                            Long.toString(definition.retention().toMillis()))));
            if (published.size() == 1 && "LEASE_LOST".equals(published.getFirst())) {
                release = false;
                return new CountFacetRefresh(CountFacetRefresh.Status.LEASE_LOST, Optional.empty());
            }
            if (published.size() != 3 || !"PUBLISHED".equals(published.getFirst())) throw malformed();
            release = false;
            Instant through = instant(published, 2);
            var snapshot = new CountFacetSnapshot(number(published.get(1)), from, through, through, values);
            return new CountFacetRefresh(CountFacetRefresh.Status.PUBLISHED, Optional.of(snapshot));
        } catch (RuntimeException | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            if (release) {
                try {
                    redis.eval(RELEASE, routed, List.of(token));
                } catch (RuntimeException | Error releaseFailure) {
                    if (failure != null) failure.addSuppressed(releaseFailure);
                    else throw releaseFailure;
                }
            }
        }
    }

    private String scope() {
        Optional<String> tenant = CacheTenantContext.currentTenant();
        if (tenantScoped) {
            String id = tenant.orElseThrow(
                    () -> new IllegalStateException("A tenant context is required for this read model"));
            requireWellFormed(id, "Tenant id");
            return "tenant:" + id;
        }
        if (tenant.isPresent()) {
            throw new IllegalStateException("A global read model cannot be used inside a tenant context");
        }
        return "global";
    }

    private static void requireWellFormed(String value, String label) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException(label + " contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(label + " contains an unpaired surrogate");
            }
        }
    }

    private List<String> scopeKeys(String scope) {
        String base = keys.countFacetReadModelKey(namespace, definition.name(), scope);
        return List.of(base, base + ":lease", base + ":sequence");
    }

    private static String encode(CountFacetValues values) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("count", Long.toString(values.count()));
        values.groups().forEach((key, count) -> fields.put("group:" + key, Long.toString(count)));
        String encoded = LengthPrefixedPayloadCodec.encode(fields);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_VALUES_BYTES) {
            throw new IllegalArgumentException("Read-model values exceed the bounded payload limit");
        }
        return encoded;
    }

    private static CountFacetValues decode(String payload) {
        var cursor = LengthPrefixedPayloadCodec.cursor(payload);
        var groups = new LinkedHashMap<String, Long>();
        Long count = null;
        int fields = 0;
        while (cursor.next()) {
            if (++fields > 257) throw malformed();
            String name = cursor.name();
            if ("count".equals(name)) {
                if (count != null) throw malformed();
                count = number(cursor.value());
            } else if (name != null && name.startsWith("group:")) {
                String group = name.substring(6);
                if (groups.putIfAbsent(group, number(cursor.value())) != null) throw malformed();
            } else throw malformed();
        }
        if (count == null) throw malformed();
        return new CountFacetValues(count, groups);
    }

    private static List<?> reply(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 1 || list.size() > 8) throw malformed();
        return list;
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw malformed();
        return text;
    }

    private static long number(Object value) {
        String text = string(value);
        if (text.isEmpty() || text.length() > 19 || text.length() > 1 && text.charAt(0) == '0') throw malformed();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') throw malformed();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw malformed();
        }
    }

    private static Instant instant(List<?> fields, int index) {
        if (index >= fields.size()) throw malformed();
        return Instant.ofEpochMilli(number(fields.get(index)));
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("Invalid COUNT/FACET read-model snapshot or Redis response");
    }
}
