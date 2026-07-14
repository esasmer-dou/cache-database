#!lua name=__LIBRARY_NAME__

redis.register_function('__UPSERT_FUNCTION__', function(keys, args)
    local entityKey = keys[1]
    local versionKey = keys[2]
    local tombstoneKey = keys[3]
    local streamKey = keys[4]
    local compactionPayloadKey = keys[5]
    local compactionPendingKey = keys[6]
    local compactionStreamKey = keys[7]
    local compactionStatsKey = keys[8]
    local payload = args[1]
    local cacheEntity = args[2] == '1'
    local ttl = tonumber(args[3])
    local compactionPayloadTtl = tonumber(args[4])
    local compactionPendingTtl = tonumber(args[5])
    local versionKeyTtl = tonumber(args[6])
    local durableCompaction = args[8] == '1'
    local observationTag = args[9]
    local operationType = args[10]
    local entityName = args[11]
    local tableName = args[12]
    local namespace = args[13]
    local idColumn = args[14]
    local versionColumn = args[15]
    local deletedColumn = args[16]
    local activeMarkerValue = args[17]
    local id = args[18]
    local createdAt = args[19]
    local columnCount = tonumber(args[20])
    local version = redis.call('INCR', versionKey)

    if versionKeyTtl ~= nil and versionKeyTtl > 0 then
        redis.call('EXPIRE', versionKey, versionKeyTtl)
    end

    if cacheEntity then
        if ttl ~= nil and ttl > 0 then
            redis.call('SETEX', entityKey, ttl, payload)
        else
            redis.call('SET', entityKey, payload)
        end
    else
        redis.call('DEL', entityKey)
    end
    redis.call('DEL', tombstoneKey)

    local fields = {
        'type', operationType,
        'entity', entityName,
        'table', tableName,
        'namespace', namespace,
        'observationTag', observationTag,
        'idColumn', idColumn,
        'versionColumn', versionColumn,
        'deletedColumn', deletedColumn,
        'id', id,
        'version', tostring(version),
        'createdAt', createdAt
    }

    table.insert(fields, 'col:' .. versionColumn)
    table.insert(fields, tostring(version))

    if deletedColumn ~= nil and deletedColumn ~= '' then
        table.insert(fields, 'col:' .. deletedColumn)
        table.insert(fields, activeMarkerValue)
    end

    local index = 21
    for i = 1, columnCount do
        local columnName = args[index]
        local columnValue = args[index + 1]
        table.insert(fields, 'col:' .. columnName)
        table.insert(fields, columnValue)
        index = index + 2
    end

    if durableCompaction then
        local payloadExists = redis.call('EXISTS', compactionPayloadKey)
        redis.call('HSET', compactionPayloadKey, unpack(fields))
        if payloadExists == 0 then
            redis.call('HINCRBY', compactionStatsKey, 'payloadCount', 1)
        end
        local pendingAdded = redis.call('SETNX', compactionPendingKey, tostring(version))
        if pendingAdded == 1 then
            redis.call('HINCRBY', compactionStatsKey, 'pendingCount', 1)
            redis.call('XADD', compactionStreamKey, '*',
                'namespace', namespace,
                'id', id,
                'version', tostring(version),
                'entity', entityName,
                'observationTag', observationTag)
        else
            redis.call('SET', compactionPendingKey, tostring(version))
        end
    else
        redis.call('XADD', streamKey, '*', unpack(fields))
    end
    return version
end)

redis.register_function('__DELETE_FUNCTION__', function(keys, args)
    local entityKey = keys[1]
    local versionKey = keys[2]
    local tombstoneKey = keys[3]
    local streamKey = keys[4]
    local compactionPayloadKey = keys[5]
    local compactionPendingKey = keys[6]
    local compactionStreamKey = keys[7]
    local compactionStatsKey = keys[8]
    local compactionPayloadTtl = tonumber(args[1])
    local compactionPendingTtl = tonumber(args[2])
    local versionKeyTtl = tonumber(args[3])
    local tombstoneTtl = tonumber(args[4])
    local durableCompaction = args[5] == '1'
    local observationTag = args[6]
    local entityName = args[7]
    local tableName = args[8]
    local namespace = args[9]
    local idColumn = args[10]
    local versionColumn = args[11]
    local deletedColumn = args[12]
    local deletedMarkerValue = args[13]
    local id = args[14]
    local createdAt = args[15]
    local version = redis.call('INCR', versionKey)

    if versionKeyTtl ~= nil and versionKeyTtl > 0 then
        redis.call('EXPIRE', versionKey, versionKeyTtl)
    end

    redis.call('DEL', entityKey)
    if tombstoneTtl ~= nil and tombstoneTtl > 0 then
        redis.call('SETEX', tombstoneKey, tombstoneTtl, tostring(version))
    else
        redis.call('SET', tombstoneKey, tostring(version))
    end

    local fields = {
        'type', 'DELETE',
        'entity', entityName,
        'table', tableName,
        'namespace', namespace,
        'observationTag', observationTag,
        'idColumn', idColumn,
        'versionColumn', versionColumn,
        'deletedColumn', deletedColumn,
        'id', id,
        'version', tostring(version),
        'createdAt', createdAt,
        'col:' .. idColumn, id,
        'col:' .. versionColumn, tostring(version)
    }

    if deletedColumn ~= nil and deletedColumn ~= '' then
        table.insert(fields, 'col:' .. deletedColumn)
        table.insert(fields, deletedMarkerValue)
    end

    if durableCompaction then
        local payloadExists = redis.call('EXISTS', compactionPayloadKey)
        redis.call('HSET', compactionPayloadKey, unpack(fields))
        if payloadExists == 0 then
            redis.call('HINCRBY', compactionStatsKey, 'payloadCount', 1)
        end
        local pendingAdded = redis.call('SETNX', compactionPendingKey, tostring(version))
        if pendingAdded == 1 then
            redis.call('HINCRBY', compactionStatsKey, 'pendingCount', 1)
            redis.call('XADD', compactionStreamKey, '*',
                'namespace', namespace,
                'id', id,
                'version', tostring(version),
                'entity', entityName,
                'observationTag', observationTag)
        else
            redis.call('SET', compactionPendingKey, tostring(version))
        end
    else
        redis.call('XADD', streamKey, '*', unpack(fields))
    end
    return version
end)

redis.register_function('__COMPACTION_COMPLETE_FUNCTION__', function(keys, args)
    local compactionPendingKey = keys[1]
    local compactionPayloadKey = keys[2]
    local compactionStreamKey = keys[3]
    local compactionStatsKey = keys[4]
    local namespace = args[1]
    local id = args[2]
    local flushedVersion = tonumber(args[3])
    local pendingVersion = tonumber(redis.call('GET', compactionPendingKey))

    if pendingVersion == nil then
        local payloadRemoved = redis.call('DEL', compactionPayloadKey)
        if payloadRemoved > 0 then
            redis.call('HINCRBY', compactionStatsKey, 'payloadCount', -payloadRemoved)
        end
        return 'CLEARED'
    end

    if pendingVersion <= flushedVersion then
        local pendingRemoved = redis.call('DEL', compactionPendingKey)
        local payloadRemoved = redis.call('DEL', compactionPayloadKey)
        if pendingRemoved > 0 then
            redis.call('HINCRBY', compactionStatsKey, 'pendingCount', -pendingRemoved)
        end
        if payloadRemoved > 0 then
            redis.call('HINCRBY', compactionStatsKey, 'payloadCount', -payloadRemoved)
        end
        return 'CLEARED'
    end

    redis.call('XADD', compactionStreamKey, '*',
        'namespace', namespace,
        'id', id,
        'version', tostring(pendingVersion))
    return 'REQUEUED'
end)
