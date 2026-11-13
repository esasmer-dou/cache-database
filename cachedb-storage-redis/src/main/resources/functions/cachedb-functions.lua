#!lua name=__LIBRARY_NAME__

__CACHEDB_VERSION_ALLOCATOR__

__CACHEDB_UPSERT_BODY__

redis.register_function('__UPSERT_FUNCTION__', cachedb_upsert)

__CACHEDB_DELETE_BODY__

local function cachedb_delete_one(keys, args)
    return cachedb_delete(keys, args)
end

redis.register_function('__DELETE_FUNCTION__', cachedb_delete_one)

-- Independent row outcomes. Redis does not roll back an earlier row when a later row fails.
redis.register_function('__DELETE_FUNCTION___bulk', function(keys, args)
    local n = tonumber(args[1])
    if not n or n < 1 or n > 128 or #keys > 16384 or #args > 65536 then
        return redis.error_reply('Invalid bounded delete request')
    end
    local bytes = 0
    for _,v in ipairs(keys) do bytes = bytes + #v end
    for _,v in ipairs(args) do bytes = bytes + #v end
    if bytes > 16777216 then return redis.error_reply('Bounded delete request exceeds 16 MiB') end
    local rows, p, k, commandCount = {}, 2, 1, 0
    local allowed = {DEL=true, SREM=true, ZREM=true, SADD=true, SET=true, CLAMP=true}
    for i=1,n do
        local base, ac, cc = tonumber(args[p]), tonumber(args[p+1]), tonumber(args[p+2])
        p=p+3
        if not base or not ac or not ((base==8 or base==9) and ac==21 or base==12 and ac==29 or base==13 and ac==32)
            or not cc or cc<0 or cc>8192 then
            return redis.error_reply('Invalid bounded delete row')
        end
        commandCount=commandCount+cc
        if commandCount>8192 then return redis.error_reply('Too many delete maintenance commands') end
        local row={keys={}, args={}, commands={}}
        for j=1,base do
            if not keys[k] then return redis.error_reply('Missing bounded delete key') end
            row.keys[j]=keys[k]; k=k+1
        end
        for j=1,ac do
            if not args[p] then return redis.error_reply('Missing bounded delete argument') end
            row.args[j]=args[p]; p=p+1
        end
        row.hotKey=keys[k]; k=k+1
        row.hotExpected=args[p]; p=p+1
        if not row.hotKey or not row.hotExpected then return redis.error_reply('Missing hot-owner guard') end
        for j=1,cc do
            local op, arity=args[p],tonumber(args[p+1]); p=p+2
            if not allowed[op] or not arity or arity<2 or arity>4 or not keys[k] then
                return redis.error_reply('Invalid delete maintenance command')
            end
            local c={op,keys[k]}; k=k+1
            for x=3,arity do
                if not args[p] then return redis.error_reply('Missing maintenance operand') end
                c[x]=args[p]; p=p+1
            end
            if op=='CLAMP' and (arity~=3 or not tonumber(c[3]) or tonumber(c[3])<0) then
                return redis.error_reply('Invalid tenant byte decrement')
            end
            row.commands[j]=c
        end
        rows[i]=row
    end
    if k~=#keys+1 or p~=#args+1 then return redis.error_reply('Trailing bounded delete data') end
    local output, stopped={},false
    for i,row in ipairs(rows) do
        if stopped then output[i]={'N','0','N'}
        else
            local ok,value=pcall(cachedb_delete_one,row.keys,row.args)
            if not ok then output[i]={'U','0','N'}; stopped=true
            elseif type(value)=='table' and value.err then output[i]={'R','0','N'}; stopped=true
            elseif type(value)~='number' or value<=0 then output[i]={'R','0','N'}; stopped=true
            else
                local hot = redis.pcall('GET',row.hotKey)
                local clean = (type(hot)~='table') and (hot or '')==row.hotExpected
                if clean then
                    for _,c in ipairs(row.commands) do
                        local result
                        if c[1]=='CLAMP' then
                            result=redis.pcall('DECRBY',c[2],c[3])
                            if type(result)=='number' and result<=0 then result=redis.pcall('DEL',c[2]) end
                        else result=redis.pcall(unpack(c)) end
                        if type(result)=='table' and result.err then clean=false; break end
                    end
                end
                output[i]={'A',tostring(value),clean and 'C' or 'F'}
                if not clean then stopped=true end
            end
        end
    end
    return output
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
