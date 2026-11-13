-- Standard-root namespace admission. Membership persists; expiry/death is never retirement.
local function cachedb_runtime_counts(key)
    if redis.call('TYPE',key).ok~='hash' or redis.call('PTTL',key)~=-1
        or redis.call('HGET',key,'schema')~='2'
        or redis.call('HGET',key,'run_id')~=cachedb_version_run_id() then error('CDB_RUNTIME_INCOMPLETE') end
    local function count(field,max)
        local s=redis.call('HGET',key,field)
        if not s or #s>3 or not string.match(s,'^[0-9]+$') then error('CDB_RUNTIME_INCOMPLETE') end
        local n=tonumber(s)
        if tostring(n)~=s or n>max then error('CDB_RUNTIME_INCOMPLETE') end
        return n
    end
    local enrolled,required,pairs=count('enrolled',128),count('required',128),count('pairs',64)
    if enrolled<1 or redis.call('HLEN',key)~=9+enrolled+required+pairs then error('CDB_RUNTIME_INCOMPLETE') end
    local state=redis.call('HGET',key,'state')
    if state~='COLLECTING' and state~='SEALED' and state~='REVIEW_REQUIRED' then error('CDB_RUNTIME_INCOMPLETE') end
    for _,field in ipairs({'approval','manifest','coverage'}) do
        local length=redis.call('HSTRLEN',key,field)
        if (required==0 and length~=0) or (required>0 and length~=64) then error('CDB_RUNTIME_INCOMPLETE') end
    end
    return enrolled,required,pairs,state
end
local function cachedb_runtime_admit(key,member)
    local enrolled,required,pairs,state=cachedb_runtime_counts(key)
    if state~='SEALED' or enrolled~=required or pairs<1
        or redis.call('HGET',key,'e:'..member)~='1'
        or redis.call('HGET',key,'m:'..member)~='1' then error('CDB_RUNTIME_NOT_APPROVED') end
end
