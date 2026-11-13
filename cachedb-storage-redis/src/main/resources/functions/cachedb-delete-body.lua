-- Shared DELETE producer. Both FCALL and bounded retention call this exact function.
local function cachedb_delete(keys, args, allocatorRunId)
    local entityKey = keys[1]
    local versionKey = keys[2]
    local tombstoneKey = keys[3]
    local streamKey = keys[4]
    local compactionPayloadKey = keys[5]
    local compactionPendingKey = keys[6]
    local compactionStreamKey = keys[7]
    local compactionStatsKey = keys[8]
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
    local traceMetadata = args[16] or ''
    local invalidationChannel = args[17] or ''
    local invalidationMessage = args[18] or ''
    local expectedVersion = tonumber(args[21] or '-1')
    local generation = nil
    local tenantToken = nil
    local generationScope = nil
    local generationMarker = args[22] or ''
    if generationMarker ~= '' then
        local generationIndex = tonumber(args[23] or '')
        generation = args[24] or ''
        tenantToken = args[25] or ''
        generationScope = args[26] or ''
        if generationMarker ~= 'cachedb:generation:v1'
                or generationIndex == nil or generationIndex % 1 ~= 0
                or generationIndex < 9 or generationIndex >= #keys
                or not string.match(generation, '^[1-9][0-9]*$') or #generation > 19
                or (#generation == 19 and generation > '9223372036854775807')
                or #tenantToken ~= 64 or not string.match(tenantToken, '^[0-9a-f]+$')
                or #generationScope ~= 64 or not string.match(generationScope, '^[0-9a-f]+$') then
            return redis.error_reply('CacheDB generation protocol invalid')
        end
        local generationKey = keys[generationIndex]
        if redis.call('HGET', generationKey, 'state') ~= 'OPEN'
                or redis.call('HGET', generationKey, 'generation') ~= generation then
            return redis.error_reply('CacheDB generation closed or changed')
        end
    end
    local currentVersion = tonumber(redis.call('GET', versionKey) or '0')
    if args[30]~=nil then
        local ri=tonumber(args[31] or '')
        if not generation or args[30]~='cachedb:runtime:v1' or not ri or ri%1~=0
            or ri<9 or ri>=#keys or ri==tonumber(args[23]) or ri==tonumber(args[28])
            or ri==tonumber(args[29]) or #args~=32 then return redis.error_reply('CDB_RUNTIME_INCOMPLETE') end
        cachedb_runtime_admit(keys[ri],args[32])
    end
    if expectedVersion ~= nil and expectedVersion >= 0 and currentVersion ~= expectedVersion then
        return -(currentVersion + 1)
    end
    if args[19] == 'cachedb:tenant:v1' then
        local tenant = args[20]
        if tenant == nil or redis.call('GET', keys[#keys]) ~= tenant then
            return redis.error_reply('CacheDB tenant access denied')
        end
    end
    if invalidationChannel ~= '' and not redis.acl_check_cmd('PUBLISH', invalidationChannel, invalidationMessage) then
        return redis.error_reply('Near-cache requires PUBLISH permission before write acceptance')
    end
    local versionText
    if generation ~= nil then
        local allocatorIndex=tonumber(args[28] or '')
        local counterIndex=tonumber(args[29] or '')
        if args[27]~='cachedb:version-range:v1' or allocatorIndex==nil or counterIndex==nil
            or allocatorIndex%1~=0 or counterIndex%1~=0 or allocatorIndex<9 or counterIndex<9
            or allocatorIndex>=#keys or counterIndex>=#keys or allocatorIndex==counterIndex then
            return redis.error_reply('CDB_VERSION_MIGRATION_REQUIRED')
        end
        versionText=cachedb_version_next(keys[tonumber(args[23])],keys[allocatorIndex],keys[counterIndex],
            generation,redis.call('GET',versionKey) or '0',allocatorRunId)
        redis.call('SET',versionKey,versionText)
    else
        redis.call('INCR',versionKey)
        versionText=redis.call('GET',versionKey)
    end
    local version=tonumber(versionText)
    if generation == nil and versionKeyTtl ~= nil and versionKeyTtl > 0 then
        redis.call('EXPIRE', versionKey, versionKeyTtl)
    end
    redis.call('DEL', entityKey)
    if tombstoneTtl ~= nil and tombstoneTtl > 0 then
        redis.call('SETEX', tombstoneKey, tombstoneTtl, versionText)
    else
        redis.call('SET', tombstoneKey, versionText)
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
        'version', versionText,
        'createdAt', createdAt,
        'col:' .. idColumn, id,
        'col:' .. versionColumn, versionText
    }
    if deletedColumn ~= nil and deletedColumn ~= '' then
        table.insert(fields, 'col:' .. deletedColumn)
        table.insert(fields, deletedMarkerValue)
    end
    if generation ~= nil then
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
        if generation == nil then
            redis.call('HDEL', compactionPayloadKey, 'tenantGeneration', 'tenantToken', 'generationScope')
        end
        redis.call('HSET', compactionPayloadKey, unpack(fields))
        if payloadExists == 0 then
            redis.call('HINCRBY', compactionStatsKey, 'payloadCount', 1)
        end
        local pendingAdded = redis.call('SETNX', compactionPendingKey, versionText)
        if pendingAdded == 1 then
            redis.call('HINCRBY', compactionStatsKey, 'pendingCount', 1)
            local notice = {'namespace', namespace, 'id', id, 'version', versionText,
                'entity', entityName, 'observationTag', observationTag}
            if generation ~= nil then
                table.insert(notice, 'tenantGeneration'); table.insert(notice, generation)
                table.insert(notice, 'tenantToken'); table.insert(notice, tenantToken)
                table.insert(notice, 'generationScope'); table.insert(notice, generationScope)
            end
            redis.call('XADD', compactionStreamKey, '*', unpack(notice))
        else
            redis.call('SET', compactionPendingKey, versionText)
        end
    else
        redis.call('XADD', streamKey, '*', unpack(fields))
    end
    if invalidationChannel ~= '' then redis.call('PUBLISH', invalidationChannel, invalidationMessage) end
    return version
end
