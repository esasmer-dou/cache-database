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
    local ttl = tonumber(args[2])
    local compactionPayloadTtl = tonumber(args[3])
    local compactionPendingTtl = tonumber(args[4])
    local versionKeyTtl = tonumber(args[5])
    local observationTag = args[7]
    local operationType = args[8]
    local entityName = args[9]
    local tableName = args[10]
    local namespace = args[11]
    local idColumn = args[12]
    local versionColumn = args[13]
    local deletedColumn = args[14]
    local activeMarkerValue = args[15]
    local id = args[16]
    local createdAt = args[17]
    local columnCount = tonumber(args[18])
    local version = redis.call('INCR', versionKey)

    if versionKeyTtl ~= nil and versionKeyTtl > 0 then
        redis.call('EXPIRE', versionKey, versionKeyTtl)
    end

    if ttl ~= nil and ttl > 0 then
        redis.call('SETEX', entityKey, ttl, payload)
    else
        redis.call('SET', entityKey, payload)
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

    local index = 19
    for i = 1, columnCount do
        local columnName = args[index]
        local columnValue = args[index + 1]
        table.insert(fields, 'col:' .. columnName)
        table.insert(fields, columnValue)
        index = index + 2
    end

    redis.call('XADD', streamKey, '*', unpack(fields))
    local payloadExists = redis.call('EXISTS', compactionPayloadKey)
    redis.call('HSET', compactionPayloadKey, unpack(fields))
    if payloadExists == 0 then
        redis.call('HINCRBY', compactionStatsKey, 'payloadCount', 1)
    end
    if compactionPayloadTtl ~= nil and compactionPayloadTtl > 0 then
        redis.call('EXPIRE', compactionPayloadKey, compactionPayloadTtl)
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
    if compactionPendingTtl ~= nil and compactionPendingTtl > 0 then
        redis.call('EXPIRE', compactionPendingKey, compactionPendingTtl)
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
    local observationTag = args[5]
    local entityName = args[6]
    local tableName = args[7]
    local namespace = args[8]
    local idColumn = args[9]
    local versionColumn = args[10]
    local deletedColumn = args[11]
    local deletedMarkerValue = args[12]
    local id = args[13]
    local createdAt = args[14]
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

    redis.call('XADD', streamKey, '*', unpack(fields))
    local payloadExists = redis.call('EXISTS', compactionPayloadKey)
    redis.call('HSET', compactionPayloadKey, unpack(fields))
    if payloadExists == 0 then
        redis.call('HINCRBY', compactionStatsKey, 'payloadCount', 1)
    end
    if compactionPayloadTtl ~= nil and compactionPayloadTtl > 0 then
        redis.call('EXPIRE', compactionPayloadKey, compactionPayloadTtl)
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
    if compactionPendingTtl ~= nil and compactionPendingTtl > 0 then
        redis.call('EXPIRE', compactionPendingKey, compactionPendingTtl)
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
