-- Explicit canonical UPSERT/PATCH body shared by native producers.
local function cachedb_upsert(keys, args)
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
    local trailerIndex = 21 + (columnCount * 2)
    local expectedVersion = tonumber(args[trailerIndex] or '-1')
    local dependencyNamespace = args[trailerIndex + 1] or ''
    local dependencyId = args[trailerIndex + 2] or ''
    local dependencyVersion = tonumber(args[trailerIndex + 3] or '0')
    local traceMetadata = args[trailerIndex + 4] or ''
    local invalidationChannel = args[trailerIndex + 5] or ''
    local invalidationMessage = args[trailerIndex + 6] or ''
    if invalidationChannel ~= '' and not redis.acl_check_cmd('PUBLISH', invalidationChannel, invalidationMessage) then
        return redis.error_reply('Near-cache requires PUBLISH permission before write acceptance')
    end
    local generation, tenantToken, generationScope
    local gi, ai, ci
    if args[trailerIndex + 9] ~= nil and args[trailerIndex + 9] ~= '' then
        gi = tonumber(args[trailerIndex + 10])
        generation = args[trailerIndex + 11]
        tenantToken = args[trailerIndex + 12]
        generationScope = args[trailerIndex + 13]
        ai = tonumber(args[trailerIndex + 15])
        ci = tonumber(args[trailerIndex + 16])
        if args[trailerIndex + 9] ~= 'cachedb:generation:v1'
            or args[trailerIndex + 14] ~= 'cachedb:version-range:v1'
            or not gi or not ai or not ci or gi % 1 ~= 0 or ai % 1 ~= 0 or ci % 1 ~= 0
            or gi < 9 or ai < 9 or ci < 9 or gi >= #keys or ai >= #keys or ci >= #keys
            or gi == ai or gi == ci or ai == ci
            or not generation or not string.match(generation, '^[1-9][0-9]*$') or #generation > 19
            or (#generation == 19 and generation > '9223372036854775807')
            or not tenantToken or #tenantToken ~= 64 or not string.match(tenantToken, '^[0-9a-f]+$')
            or not generationScope or #generationScope ~= 64 or not string.match(generationScope, '^[0-9a-f]+$')
            or args[trailerIndex + 7] ~= 'cachedb:tenant:v1' then
            return redis.error_reply('CacheDB generation protocol invalid')
        end
        if redis.call('HGET', keys[gi], 'state') ~= 'OPEN'
            or redis.call('HGET', keys[gi], 'generation') ~= generation then
            return redis.error_reply('CacheDB generation closed or changed')
        end
    end
    local currentVersion = tonumber(redis.call('GET', versionKey) or '0')
    if args[trailerIndex+17]~=nil then
        local ri=tonumber(args[trailerIndex+18] or '')
        if not generation or args[trailerIndex+17]~='cachedb:runtime:v1' or not ri
            or ri%1~=0 or ri<9 or ri>=#keys or ri==gi or ri==ai or ri==ci
            or #args~=trailerIndex+19 then return redis.error_reply('CDB_RUNTIME_INCOMPLETE') end
        cachedb_runtime_admit(keys[ri],args[trailerIndex+19])
    end
    if expectedVersion ~= nil and expectedVersion >= 0 and currentVersion ~= expectedVersion then
        return -(currentVersion + 1)
    end
    if operationType == 'PATCH' and redis.call('EXISTS', tombstoneKey) == 1 then
        return redis.error_reply('PATCH cannot recreate a deleted entity')
    end
    if args[trailerIndex + 7] == 'cachedb:tenant:v1' then
        local tenant = args[trailerIndex + 8]
        if tenant == nil or redis.call('GET', keys[#keys]) ~= tenant then
            return redis.error_reply('CacheDB tenant access denied')
        end
    end
    local versionText
    if generation then
        versionText = cachedb_version_next(keys[gi], keys[ai], keys[ci], generation,
            redis.call('GET', versionKey) or '0')
        redis.call('SET', versionKey, versionText)
    else
        redis.call('INCR', versionKey)
        versionText = redis.call('GET', versionKey)
    end
    local version = tonumber(versionText)

    if operationType == 'PATCH' and keys[9] ~= nil then
        redis.call('SET', keys[9], 'patch')
    end

    if not generation and versionKeyTtl ~= nil and versionKeyTtl > 0 then
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
        'version', versionText,
        'createdAt', createdAt
    }

    table.insert(fields, 'col:' .. versionColumn)
    table.insert(fields, versionText)

    if operationType ~= 'PATCH' and deletedColumn ~= nil and deletedColumn ~= '' then
        table.insert(fields, 'col:' .. deletedColumn)
        table.insert(fields, activeMarkerValue)
    end

    if dependencyNamespace ~= '' and dependencyId ~= '' and dependencyVersion > 0 then
        table.insert(fields, 'dependencyNamespace')
        table.insert(fields, dependencyNamespace)
        table.insert(fields, 'dependencyId')
        table.insert(fields, dependencyId)
        table.insert(fields, 'dependencyVersion')
        table.insert(fields, args[trailerIndex + 3])
    end

    local index = 21
    for i = 1, columnCount do
        local columnName = args[index]
        local columnValue = args[index + 1]
        table.insert(fields, 'col:' .. columnName)
        table.insert(fields, columnValue)
        index = index + 2
    end

    if generation then
        table.insert(fields, 'tenantGeneration'); table.insert(fields, generation)
        table.insert(fields, 'tenantToken'); table.insert(fields, tenantToken)
        table.insert(fields, 'generationScope'); table.insert(fields, generationScope)
    end

    if traceMetadata ~= '' then
        table.insert(fields, 'traceMetadata')
        table.insert(fields, traceMetadata)
    end

    if durableCompaction then
        local payloadExists = redis.call('EXISTS', compactionPayloadKey)
        if traceMetadata == '' then redis.call('HDEL', compactionPayloadKey, 'traceMetadata') end
        if not generation then
            redis.call('HDEL', compactionPayloadKey, 'tenantGeneration', 'tenantToken', 'generationScope')
        end
        redis.call('HSET', compactionPayloadKey, unpack(fields))
        if payloadExists == 0 then
            redis.call('HINCRBY', compactionStatsKey, 'payloadCount', 1)
        end
        local pendingAdded = redis.call('SETNX', compactionPendingKey, versionText)
        if pendingAdded == 1 then
            redis.call('HINCRBY', compactionStatsKey, 'pendingCount', 1)
            local notification = {'namespace', namespace, 'id', id, 'version', versionText,
                'entity', entityName, 'observationTag', observationTag}
            if generation then
                table.insert(notification, 'tenantGeneration'); table.insert(notification, generation)
                table.insert(notification, 'tenantToken'); table.insert(notification, tenantToken)
                table.insert(notification, 'generationScope'); table.insert(notification, generationScope)
            end
            redis.call('XADD', compactionStreamKey, '*', unpack(notification))
        else
            redis.call('SET', compactionPendingKey, versionText)
        end
    else
        redis.call('XADD', streamKey, '*', unpack(fields))
    end
    if invalidationChannel ~= '' then redis.call('PUBLISH', invalidationChannel, invalidationMessage) end
    return version
end
