-- KEYS[1..2]/ARGV[1..5] belong exclusively to RedisGenerationBarrier.closedCheckLua().
-- No enqueue, canonical DELETE, version allocation, publication or SQL operation occurs here.
local mode,layout,id,tenant=ARGV[6],ARGV[7],ARGV[8],ARGV[9]
local owner,hot=tonumber(ARGV[10]),tonumber(ARGV[11])
local function typ(i,want)
  local t=redis.call('TYPE',KEYS[i]).ok
  return t=='none' or t==want
end
if not typ(3,'string') or redis.call('STRLEN',KEYS[3])>256 then return {-1} end
local receipt=redis.call('GET',KEYS[3])
if receipt and redis.call('PTTL',KEYS[3])~=-1 then return {-1} end
if mode=='V' then
  if not receipt then return {-1} end
  local p,n=12,tonumber(ARGV[12]);p=p+1
  for i=1,n do
    if redis.call('EXISTS',KEYS[tonumber(ARGV[p])])~=0 then return {-1} end
    p=p+1
  end
  n=tonumber(ARGV[p]);p=p+1
  for i=1,n do
    local k,t=tonumber(ARGV[p]),ARGV[p+1];p=p+2
    if not typ(k,t) then return {-1} end
    if t=='set' and redis.call('SISMEMBER',KEYS[k],id)~=0 then return {-1} end
    if t=='zset' and redis.call('ZSCORE',KEYS[k],id) then return {-1} end
    if t=='list' and (redis.call('LLEN',KEYS[k])>4096 or redis.call('LPOS',KEYS[k],id)) then return {-1} end
  end
  if p~=#ARGV+1 then return {-1} end
  return receipt
end
if receipt then
  if mode=='S' then return {1,receipt} end
  return receipt
end
if not typ(owner,'string') or redis.call('STRLEN',KEYS[owner])>1024
  or redis.call('GET',KEYS[owner])~=tenant or redis.call('PTTL',KEYS[owner])~=-1 then return {-1} end
if not typ(hot,'string') or redis.call('STRLEN',KEYS[hot])>16384 then return {-1} end
local hotValue=redis.call('GET',KEYS[hot])
local p,targets=13,tonumber(ARGV[12])
local snapshots={}
local total=0
for target=1,targets do
  local meta,n,sig=tonumber(ARGV[p]),tonumber(ARGV[p+1]),tonumber(ARGV[p+2])
  local expected,lock,writers=ARGV[p+3],tonumber(ARGV[p+4]),tonumber(ARGV[p+5])
  local degraded,payload,all=tonumber(ARGV[p+6]),tonumber(ARGV[p+7]),tonumber(ARGV[p+8]);p=p+9
  if not typ(meta,'hash') or redis.call('HLEN',KEYS[meta])>n
    or redis.call('EXISTS',KEYS[lock])~=0 or not typ(writers,'hash')
    or redis.call('HLEN',KEYS[writers])~=0 or redis.call('EXISTS',KEYS[degraded])~=0
    or not typ(all,'set') then return {-1} end
  if redis.call('HLEN',KEYS[meta])==0 and (redis.call('EXISTS',KEYS[payload])~=0
    or redis.call('SISMEMBER',KEYS[all],id)~=0) then return {-1} end
  if expected~='' and (not typ(sig,'string') or redis.call('STRLEN',KEYS[sig])>#expected
    or redis.call('GET',KEYS[sig])~=expected) then return {-1} end
  local found,values=0,{}
  for field=1,n do
    local name=ARGV[p];p=p+1
    local len=redis.call('HSTRLEN',KEYS[meta],name)
    total=total+len+#name+32
    if len>8192 or total>65536 then return {-1} end
    local value=redis.call('HGET',KEYS[meta],name)
    if value then found=found+1 end
    values[field]=value or false
    if mode=='E' then
      local present,expectedValue=ARGV[p],ARGV[p+1];p=p+2
      if (present=='1' and value~=expectedValue) or (present=='0' and value) then return {-1} end
    end
  end
  -- Detect unknown field names without unbounded HKEYS/HGETALL replies.
  if redis.call('HLEN',KEYS[meta])~=found then return {-1} end
  snapshots[target]=values
end
if mode=='S' then
  if p~=#ARGV+1 then return {-1} end
  local result={0,hotValue or false}
  for i=1,targets do result[#result+1]=snapshots[i] end
  return result
end
if mode~='E' then return {-1} end
local hotPresent,expectedHot=ARGV[p],ARGV[p+1];p=p+2
if (hotPresent=='1' and hotValue~=expectedHot) or (hotPresent=='0' and hotValue) then return {-1} end
local tenantSet,bytesKey,admission,charge=tonumber(ARGV[p]),tonumber(ARGV[p+1]),tonumber(ARGV[p+2]),tonumber(ARGV[p+3]);p=p+4
local remaining=0
if tenantSet~=0 then
  if not typ(tenantSet,'zset') or not typ(bytesKey,'string') or redis.call('EXISTS',KEYS[admission])~=0
    or redis.call('STRLEN',KEYS[bytesKey])>16 then return {-1} end
  local raw=redis.call('GET',KEYS[bytesKey]) or '0'
  if not string.match(raw,'^%d+$') or (#raw>1 and string.sub(raw,1,1)=='0') then return {-1} end
  local current=tonumber(raw)
  if current>9007199254740991 or charge>current or charge<0 or charge~=math.floor(charge) then return {-1} end
  if not hotValue and redis.call('ZSCORE',KEYS[tenantSet],id) then return {-1} end
  remaining=current-charge
elseif hotValue then return {-1} end
local count=tonumber(ARGV[p]);p=p+1
if count>2048 then return {-1} end
local actions,exists={},{}
for i=1,count do
  local k,t,kind=tonumber(ARGV[p]),ARGV[p+1],ARGV[p+2];p=p+3
  if k<=3 or not typ(k,t) or (t=='list' and redis.call('LLEN',KEYS[k])>4096) then return {-1} end
  if kind~='D' and kind~='M' and kind~='C' then return {-1} end
  if kind=='C' and k~=bytesKey then return {-1} end
  actions[i]={k,t,kind}
  exists[i]=redis.call('EXISTS',KEYS[k])
end
if p~=#ARGV+1 then return {-1} end
-- ACL errors after DEL would not roll back Lua writes. Preflight ALL write permissions first.
if not redis.acl_check_cmd then return {-1} end
if not redis.acl_check_cmd('SET',KEYS[3],layout..'|'..ARGV[1]..'|2048|999999') then return {-1} end
for i=1,count do
  local k,t,kind=unpack(actions[i])
  if kind=='D' then
    if not redis.acl_check_cmd('DEL',KEYS[k]) then return {-1} end
  elseif kind=='C' and charge>0 then
    if remaining==0 then
      if not redis.acl_check_cmd('DEL',KEYS[k]) then return {-1} end
    elseif not redis.acl_check_cmd('SET',KEYS[k],string.format('%.0f',remaining)) then return {-1} end
  elseif kind=='M' then
    local command=t=='set' and 'SREM' or (t=='zset' and 'ZREM' or 'LREM')
    if t=='list' then
      if not redis.acl_check_cmd(command,KEYS[k],0,id) then return {-1} end
    elseif not redis.acl_check_cmd(command,KEYS[k],id) then return {-1} end
  end
end
-- All schema, snapshots, ownership, budgets and command types are checked BEFORE the first write.
-- EVAL is one atomic namespace-slot operation; journal survives owner/key deletion and lease takeover.
local members=0
for i=1,count do
  local k,t,kind=unpack(actions[i])
  if kind=='D' then redis.call('DEL',KEYS[k])
  elseif kind=='C' then
    if charge>0 then
      if remaining==0 then redis.call('DEL',KEYS[k])
      else redis.call('SET',KEYS[k],string.format('%.0f',remaining)) end
    end
  elseif t=='set' then members=members+redis.call('SREM',KEYS[k],id)
  elseif t=='zset' then members=members+redis.call('ZREM',KEYS[k],id)
  elseif t=='list' then members=members+redis.call('LREM',KEYS[k],0,id)
  end
end
local removed=0
for i=1,count do
  if exists[i]==1 and redis.call('EXISTS',KEYS[actions[i][1]])==0 then removed=removed+1 end
end
receipt=layout..'|'..ARGV[1]..'|'..removed..'|'..members
redis.call('SET',KEYS[3],receipt)
return receipt
