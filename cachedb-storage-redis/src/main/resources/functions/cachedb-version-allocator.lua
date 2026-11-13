-- Shared bounded tenant allocator. No SQL and no Lua floating-point version serialization.
local function cachedb_version_run_id()
    local info=redis.call('INFO','SERVER')
    local id=string.match(info,'run_id:([0-9a-f]+)')
    if not id or #id~=40 then error('CDB_VERSION_RECOVERY_REQUIRED') end
    return id
end
local function cachedb_decimal(value)
    return value and #value<=16 and string.match(value,'^[0-9]+$')
        and (value=='0' or string.sub(value,1,1)~='0')
        and (#value<16 or value<='9007199254740991')
end
local function cachedb_decimal_less(a,b)
    if #a~=#b then return #a<#b end
    return a<b
end
local function cachedb_version_fail(generationKey,reason)
    redis.call('HSET',generationKey,'state','CLOSED')
    error(reason)
end
local function cachedb_version_assert(generationKey,allocatorKey,counterKey,generation,runId)
    if redis.call('HGET',generationKey,'generation')~=generation
        or redis.call('HGET',allocatorKey,'generation')~=generation
        or redis.call('HGET',allocatorKey,'control_epoch')~=redis.call('HGET',generationKey,'epoch')
        or redis.call('HGET',allocatorKey,'run_id')~=runId
        or redis.call('HSTRLEN',allocatorKey,'inventory')~=64
        or redis.call('PTTL',allocatorKey)~=-1 or redis.call('PTTL',counterKey)~=-1 then
        cachedb_version_fail(generationKey,'CDB_VERSION_RECOVERY_REQUIRED')
    end
    local current=redis.call('GET',counterKey)
    local floor=redis.call('HGET',allocatorKey,'floor')
    local ceiling=redis.call('HGET',allocatorKey,'ceiling')
    if not cachedb_decimal(current) or not cachedb_decimal(floor) or not cachedb_decimal(ceiling)
        or cachedb_decimal_less(current,floor) or cachedb_decimal_less(ceiling,current) then
        cachedb_version_fail(generationKey,'CDB_VERSION_RECOVERY_REQUIRED')
    end
    return current
end
local function cachedb_version_next(generationKey,allocatorKey,counterKey,generation,minimum,runId)
    if redis.call('HGET',generationKey,'state')~='OPEN'
        or redis.call('HGET',generationKey,'generation')~=generation then error('TENANT_GENERATION_REJECTED') end
    local current=cachedb_version_assert(generationKey,allocatorKey,counterKey,generation,runId or cachedb_version_run_id())
    if not cachedb_decimal(minimum) or cachedb_decimal_less(current,minimum) then
        cachedb_version_fail(generationKey,'CDB_VERSION_HIGHWATER_MISMATCH')
    end
    local ceiling=redis.call('HGET',allocatorKey,'ceiling')
    if not cachedb_decimal_less(current,ceiling) then error('CDB_VERSION_RANGE_EXHAUSTED') end
    redis.call('INCR',counterKey)
    return redis.call('GET',counterKey)
end
