-- EMPTY activation only. KEYS: guard, lease, allocator, counter (last two absent for queue mirrors).
-- Receipt and OPEN are one HSET; no cleanup, version rewind, migration or automatic activation here.
local function less(a,b) if #a~=#b then return #a<#b end return a<b end
local function fail() return redis.error_reply('PURGE_RECOVERY_REQUIRED') end
local aclCheck=redis.acl_check_cmd
local function allowed(...)
    if type(aclCheck)~='function' then return false end
    local ok,result=pcall(aclCheck,...)
    return ok and result==true
end
local command,from,to,operation,owner,epoch,lease,ttl,identity,phase,run,reservation,first,last,request,inventory,receipt=unpack(ARGV)
local queue=command=='queue-prepare' or command=='queue-open'
local observing=command=='observe'
local preparing=command=='prepare' or command=='queue-prepare'
if type(aclCheck)~='function' then return fail() end
if not observing and not preparing and command~='activate' and command~='queue-open' then return fail() end
if redis.call('TYPE',KEYS[1]).ok~='hash' then return fail() end
local leaseType=redis.call('TYPE',KEYS[2]).ok
if leaseType~='none' and leaseType~='string' then return fail() end
if not queue and (redis.call('TYPE',KEYS[3]).ok~='hash' or redis.call('TYPE',KEYS[4]).ok~='string'
    or redis.call('PTTL',KEYS[3])~=-1 or redis.call('PTTL',KEYS[4])~=-1) then return fail() end
local currentRun=cachedb_version_run_id()
for _,field in ipairs({'generation','epoch','owner','operation','run_id','e:identity','e:to','e:operation','e:receipt','e:install'}) do
    if redis.call('HSTRLEN',KEYS[1],field)>64 then return fail() end
end
local generation=redis.call('HGET',KEYS[1],'generation')
local priorEpoch=redis.call('HGET',KEYS[1],'epoch')
local state=redis.call('HGET',KEYS[1],'state')
if not generation or not string.match(generation,'^[1-9]%d*$') or #generation>19
    or not cachedb_decimal(priorEpoch) or priorEpoch=='0' or redis.call('HGET',KEYS[1],'run_id')~=currentRun
    or redis.call('PTTL',KEYS[1])~=-1 or redis.call('HGET',KEYS[1],'operation')~=operation
    or (state~='CLOSED' and state~='OPEN') then return fail() end
if not queue and phase~='PREPARED' and run~=currentRun then return fail() end
local oldIdentity=redis.call('HGET',KEYS[1],'e:identity')
local priorReceipt=redis.call('HGET',KEYS[1],'e:receipt')
local same=oldIdentity==identity and redis.call('HGET',KEYS[1],'e:operation')==operation
if observing then
    if not same or generation~=to or state~='OPEN' or priorReceipt~=receipt
        or priorEpoch~=epoch or redis.call('HGET',KEYS[1],'owner')~=owner then return fail() end
    -- The shared validator can close admission on corrupt allocator metadata.
    if not allowed('HSET',KEYS[1],'state','CLOSED') then return fail() end
    cachedb_version_assert(KEYS[1],KEYS[3],KEYS[4],to,currentRun)
    return {currentRun,epoch,owner,receipt}
end
if less(epoch,priorEpoch) or (epoch==priorEpoch and redis.call('HGET',KEYS[1],'owner')~=owner)
    then return redis.error_reply('PURGE_FENCE_LOST') end
if not same then
    -- First installation requires the actual retained purge closure, never an absent Redis key.
    if generation~=from or state~='CLOSED' or phase~='PREPARED'
        or oldIdentity and redis.call('HGET',KEYS[1],'e:to')~=from then return fail() end
    if redis.call('HEXISTS',KEYS[1],'cleanup_request')==1 then return fail() end
elseif generation~=to then return fail() end
if phase=='PREPARED' and (state~='CLOSED' or same and priorReceipt) then return fail() end
if not preparing and phase~='SQL_OPEN' then return fail() end
if queue and redis.call('HEXISTS',KEYS[1],'cleanup_request')==1 then return fail() end
if state=='OPEN' then
    if not priorReceipt or priorReceipt~=receipt or phase=='PREPARED' then return fail() end
elseif same and priorReceipt then return fail() end
local installing=command=='activate' and state=='CLOSED'
local floor,header,setCounter
if installing then
    local allocatorGeneration=redis.call('HGET',KEYS[3],'generation')
    local allocatorEpoch=redis.call('HGET',KEYS[3],'reservation_epoch')
    floor=string.format('%.0f',tonumber(first)-1)
    local current=redis.call('GET',KEYS[4])
    local pending=redis.call('HGET',KEYS[1],'e:install')
    if not cachedb_decimal(current) or not cachedb_decimal(floor) or less(floor,current)
        or pending and pending~=receipt then return fail() end
    -- CLOSED without a receipt has never published this generation. Never rewind a consumed counter.
    if allocatorGeneration==to then
        if allocatorEpoch~=reservation or redis.call('HGET',KEYS[3],'request')~=request
            or redis.call('HGET',KEYS[3],'floor')~=floor or redis.call('HGET',KEYS[3],'ceiling')~=last
            or redis.call('HGET',KEYS[3],'run_id')~=run or redis.call('HGET',KEYS[3],'inventory')~=inventory then return fail() end
    else
        if allocatorGeneration~=from or not allocatorEpoch or not less(allocatorEpoch,reservation)
            or redis.call('HGET',KEYS[3],'run_id')~=currentRun then return fail() end
        local ceiling=redis.call('HGET',KEYS[3],'ceiling')
        if not cachedb_decimal(ceiling) or less(floor,ceiling)
            or less(ceiling,current) and not (pending==receipt and current==floor) then return fail() end
    end
    setCounter=current~=floor
    header={'HSET',KEYS[3],'generation',to,'reservation_epoch',reservation,'floor',floor,
        'ceiling',last,'request',request,'run_id',run,'inventory',inventory,'control_epoch',epoch}
end
-- All command/key ACL and type checks precede the first write, including validator failure closure.
if not allowed('HSET',KEYS[1],'state','CLOSED')
    or not allowed('HSET',KEYS[1],'epoch',epoch,'owner',owner)
    or not allowed('SET',KEYS[2],lease,'PX',ttl) then return fail() end
if not same and (not allowed('HDEL',KEYS[1],'e:receipt','e:install')
    or not allowed('HSET',KEYS[1],'generation',to,'e:identity',identity,'e:to',to,'e:operation',operation)) then return fail() end
if state=='OPEN' and not queue and not allowed('HSET',KEYS[3],'control_epoch',epoch) then return fail() end
if not preparing and not allowed('HSET',KEYS[1],'state','OPEN','e:receipt',receipt) then return fail() end
if installing and (not allowed('HSET',KEYS[1],'e:install',receipt) or not allowed('PERSIST',KEYS[3])
    or setCounter and not allowed('SET',KEYS[4],floor) or not allowed(unpack(header))) then return fail() end
if state=='OPEN' and not queue then
    -- Check the CURRENT reservation, which may have been refilled since the first OPEN.
    cachedb_version_assert(KEYS[1],KEYS[3],KEYS[4],to,currentRun)
    redis.call('HSET',KEYS[3],'control_epoch',epoch)
end
if not same then
    redis.call('HDEL',KEYS[1],'e:receipt','e:install')
    redis.call('HSET',KEYS[1],'generation',to,'e:identity',identity,'e:to',to,'e:operation',operation)
end
redis.call('HSET',KEYS[1],'epoch',epoch,'owner',owner)
redis.call('SET',KEYS[2],lease,'PX',ttl)
if preparing then return currentRun end
if queue then
    redis.call('HSET',KEYS[1],'state','OPEN','e:receipt',receipt)
    return currentRun
end
if installing then
    -- Pending survives interruption after counter installation but before the new header is published.
    redis.call('HSET',KEYS[1],'e:install',receipt)
    redis.call('PERSIST',KEYS[3])
    if setCounter then redis.call('SET',KEYS[4],floor) end
    redis.call(unpack(header))
    cachedb_version_assert(KEYS[1],KEYS[3],KEYS[4],to,currentRun)
end
redis.call('HSET',KEYS[1],'state','OPEN','e:receipt',receipt)
return {currentRun,epoch,owner,receipt}
