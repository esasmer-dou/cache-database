-- cachedb_delete comes from the explicit shared canonical resource.
local owner=ARGV[1]
if redis.call('GET',KEYS[1])~=owner or redis.call('PTTL',KEYS[1])<=0 then return redis.error_reply('RETENTION_LEASE_LOST') end
local mode=ARGV[2]; local cutoff=tonumber(ARGV[3]); local n=tonumber(ARGV[4]); local p=5
if (mode~='DELETE' and mode~='EVICT_CACHE') or n<1 or n>128 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
local function str(i) return redis.call('GET',KEYS[i]) or '' end
local function typ(i,want)
  if i==0 then return true end
  local t=redis.call('TYPE',KEYS[i]).ok
  return t=='none' or t==want
end
local rows={}; local allocatorRunId=nil
for r=1,n do
  local x={}; x.id=ARGV[p]; x.score=ARGV[p+1]; x.version=ARGV[p+2]; x.owner=ARGV[p+3]; x.hot=ARGV[p+4]; x.payload=ARGV[p+5]; p=p+6
  x.gkey=tonumber(ARGV[p]); x.akey=tonumber(ARGV[p+1]); x.counter=tonumber(ARGV[p+2]); x.gen=ARGV[p+3]; x.token=ARGV[p+4]; x.scope=ARGV[p+5]; p=p+6
  x.channel=ARGV[p]; x.message=ARGV[p+1]; p=p+2
  x.k={}
  for j=1,9 do x.k[j]=tonumber(ARGV[p]); p=p+1 end
  x.meta=tonumber(ARGV[p]); x.hotkey=tonumber(ARGV[p+1]); x.admission=tonumber(ARGV[p+2]); x.byteskey=tonumber(ARGV[p+3]); x.bytes=tonumber(ARGV[p+4]); p=p+5
  x.fields={}; local nf=tonumber(ARGV[p]); p=p+1
  for j=1,nf do x.fields[j]={ARGV[p],ARGV[p+1]}; p=p+2 end
  x.sets={}; local ns=tonumber(ARGV[p]); p=p+1
  for j=1,ns do x.sets[j]=tonumber(ARGV[p]); p=p+1 end
  x.sorted={}; local nz=tonumber(ARGV[p]); p=p+1
  for j=1,nz do x.sorted[j]=tonumber(ARGV[p]); p=p+1 end
  x.args={}; local na=tonumber(ARGV[p]); p=p+1
  for j=1,na do x.args[j]=ARGV[p]; p=p+1 end
  if not typ(x.k[1],'string') or not typ(x.k[2],'string') or not typ(x.k[3],'string')
     or not typ(x.k[4],'stream') or not typ(x.k[5],'hash') or not typ(x.k[6],'string')
     or not typ(x.k[7],'stream') or not typ(x.k[8],'hash') or not typ(x.k[9],'string')
     or not typ(x.meta,'hash') or not typ(x.hotkey,'string') or not typ(x.gkey,'hash')
     or not typ(x.byteskey,'string') or not typ(x.akey,'hash') or not typ(x.counter,'string') then return redis.error_reply('RETENTION_LAYOUT') end
  for _,k in ipairs(x.sets) do if not typ(k,'set') then return redis.error_reply('RETENTION_LAYOUT') end end
  for _,k in ipairs(x.sorted) do if not typ(k,'zset') then return redis.error_reply('RETENTION_LAYOUT') end end
  local current=redis.call('ZSCORE',KEYS[2],x.id)
  x.ok=current and tonumber(current)==tonumber(x.score) and tonumber(current)<=cutoff
    and redis.call('STRLEN',KEYS[x.k[1]])<=262144 and str(x.k[1])==x.payload
    and str(x.k[2])==x.version and str(x.k[9])==x.owner and str(x.hotkey)==x.hot
    and (x.admission==0 or redis.call('EXISTS',KEYS[x.admission])==0)
    and redis.call('HLEN',KEYS[x.meta])==#x.fields
  for _,v in ipairs(x.fields) do if redis.call('HGET',KEYS[x.meta],v[1])~=v[2] then x.ok=false end end
  if x.gkey~=0 and (redis.call('HGET',KEYS[x.gkey],'state')~='OPEN'
      or redis.call('HGET',KEYS[x.gkey],'generation')~=x.gen) then x.ok=false end
  if mode=='DELETE' then
    if (na~=21 and na~=29) or x.args[14]~=x.id or x.args[21]~=x.version
        or x.args[17]~=x.channel or x.args[18]~=x.message
        or (x.owner~='' and (x.args[19]~='cachedb:tenant:v1' or x.args[20]~=x.owner))
        or (x.gkey~=0 and (na~=29 or x.args[22]~='cachedb:generation:v1'
            or x.args[23]~='9' or x.args[24]~=x.gen or x.args[25]~=x.token or x.args[26]~=x.scope
            or x.args[27]~='cachedb:version-range:v1' or x.args[28]~='10' or x.args[29]~='11' or x.akey==0 or x.counter==0))
        or (x.gkey==0 and na~=21) then return redis.error_reply('RETENTION_LAYOUT') end
  elseif na~=0 then return redis.error_reply('RETENTION_LAYOUT') end
  rows[r]=x
end
if p~=#ARGV+1 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
-- Check the entire batch before the first mutation. Redis script errors are not a rollback.
local allocations={}
for _,x in ipairs(rows) do
  if x.ok then
    local function acl(cmd,...)
      if not redis.acl_check_cmd(cmd,...) then error('RETENTION_ACL') end
    end
    if x.channel~='' then acl('PUBLISH',x.channel,x.message) end
    acl('DEL',KEYS[x.k[1]],KEYS[x.meta],KEYS[x.hotkey])
    for _,k in ipairs(x.sets) do acl('SREM',KEYS[k],x.id) end
    for _,k in ipairs(x.sorted) do acl('ZREM',KEYS[k],x.id) end
    if x.byteskey~=0 then
      if redis.call('EXISTS',KEYS[x.byteskey])==1 and not tonumber(str(x.byteskey)) then return redis.error_reply('RETENTION_LAYOUT') end
      acl('DECRBY',KEYS[x.byteskey],tostring(x.bytes)); acl('DEL',KEYS[x.byteskey])
    end
    if mode=='DELETE' then
      if x.gkey~=0 then
        if not allocatorRunId then allocatorRunId=cachedb_version_run_id() end
        local current=cachedb_version_assert(KEYS[x.gkey],KEYS[x.akey],KEYS[x.counter],x.gen,allocatorRunId)
        if not cachedb_decimal(x.version) or cachedb_decimal_less(current,x.version) then
          cachedb_version_fail(KEYS[x.gkey],'CDB_VERSION_HIGHWATER_MISMATCH')
        end
        local needed=(allocations[x.counter] or 0)+1;allocations[x.counter]=needed
        local ceiling=redis.call('HGET',KEYS[x.akey],'ceiling')
        if tonumber(current)+needed>tonumber(ceiling) then return redis.error_reply('CDB_VERSION_RANGE_EXHAUSTED') end
        acl('INCR',KEYS[x.counter]);acl('SET',KEYS[x.k[2]],'1')
      else acl('INCR',KEYS[x.k[2]]) end
      acl('SET',KEYS[x.k[3]],'1'); acl('SETEX',KEYS[x.k[3]],'1','1')
      if x.args[5]=='1' then
        acl('HSET',KEYS[x.k[5]],'id',x.id); acl('HDEL',KEYS[x.k[5]],'traceMetadata','tenantGeneration','tenantToken','generationScope')
        acl('HINCRBY',KEYS[x.k[8]],'payloadCount','1'); acl('SETNX',KEYS[x.k[6]],'1')
        acl('SET',KEYS[x.k[6]],'1'); acl('XADD',KEYS[x.k[7]],'*','id',x.id)
      else acl('XADD',KEYS[x.k[4]],'*','id',x.id) end
    end
  end
end
local result={}
for _,x in ipairs(rows) do
  if not x.ok then result[#result+1]={0,0}
  else
    local existed=redis.call('EXISTS',KEYS[x.k[1]])
    if mode=='DELETE' then
      local dk={}; for j=1,8 do dk[j]=KEYS[x.k[j]] end
      if x.gkey~=0 then dk[#dk+1]=KEYS[x.gkey];dk[#dk+1]=KEYS[x.akey];dk[#dk+1]=KEYS[x.counter] end
      dk[#dk+1]=KEYS[x.k[9]]
      local version=cachedb_delete(dk,x.args,allocatorRunId)
      if type(version)~='number' or version<=0 then return redis.error_reply('RETENTION_CONFLICT') end
    else
      redis.call('DEL',KEYS[x.k[1]])
      if x.channel~='' then redis.call('PUBLISH',x.channel,x.message) end
    end
    for _,k in ipairs(x.sets) do redis.call('SREM',KEYS[k],x.id) end
    for _,k in ipairs(x.sorted) do redis.call('ZREM',KEYS[k],x.id) end
    redis.call('DEL',KEYS[x.meta],KEYS[x.hotkey])
    if x.byteskey~=0 and redis.call('EXISTS',KEYS[x.byteskey])==1 then
      local remain=redis.call('DECRBY',KEYS[x.byteskey],x.bytes)
      if remain<=0 then redis.call('DEL',KEYS[x.byteskey]) end
    end
    result[#result+1]={1,existed}
  end
end
return result
