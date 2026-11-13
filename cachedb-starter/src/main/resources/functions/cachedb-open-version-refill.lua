-- Explicit root-only install: immutable native activation epoch is NOT the SQL reservation control.
local function less(a,b) if #a~=#b then return #a<#b end return a<b end
local function decimal(value,maximum)
  return value and #value>=1 and #value<=#maximum and string.match(value,'^[0-9]+$')
    and (value=='0' or string.sub(value,1,1)~='0') and not less(maximum,value)
end
local function exact(key,field,value)
  return redis.call('HSTRLEN',key,field)==#value and redis.call('HGET',key,field)==value
end
local function bounded(key,field,limit)
  local size=redis.call('HSTRLEN',key,field)
  if size<1 or size>limit then return nil end
  return redis.call('HGET',key,field)
end
if #KEYS~=4 or #ARGV<12 then return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED') end
local fields=tonumber(ARGV[10])
if not fields or fields<1 or fields>328 or fields%1~=0 then
  return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
end
local proof=11+fields*2
local grantFields=tonumber(ARGV[proof])
if not grantFields or grantFields<1 or grantFields>16 or grantFields%1~=0
  or #ARGV~=proof+grantFields*2 then return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED') end
for i=2,5 do
  if not decimal(ARGV[i],'9223372036854775807') or ARGV[i]=='0' then
    return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
  end
end
if #ARGV[1]~=40 or #ARGV[8]~=36 or #ARGV[9]~=64
  or not decimal(ARGV[6],'9007199254740991') or not decimal(ARGV[7],'9007199254740991')
  or not less(ARGV[6],ARGV[7]) or less(ARGV[4],ARGV[3]) then
  return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
end
local run=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
if run~=ARGV[1] or not string.find(redis.call('INFO','REPLICATION'),'role:master\r\n',1,true)
  or redis.call('TYPE',KEYS[1]).ok~='hash' or redis.call('PTTL',KEYS[1])~=-1
  or not exact(KEYS[1],'run_id',run) or not exact(KEYS[1],'state','OPEN')
  or not exact(KEYS[1],'generation',ARGV[2]) or not exact(KEYS[1],'epoch',ARGV[3]) then
  return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
end
for i=1,grantFields do
  local field,value=ARGV[proof+i*2-1],ARGV[proof+i*2]
  if #field>64 or #value>64 or not exact(KEYS[1],field,value) then
    return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
  end
end
if redis.call('TYPE',KEYS[2]).ok~='hash' or redis.call('PTTL',KEYS[2])~=-1
  or redis.call('HLEN',KEYS[2])~=fields+1 or not exact(KEYS[2],'run_id',run) then
  return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
end
for i=1,fields do
  local field,value=ARGV[9+i*2],ARGV[10+i*2]
  if #field>128 or #value>64 or not exact(KEYS[2],field,value) then
    return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
  end
end
if redis.call('TYPE',KEYS[3]).ok~='hash' or redis.call('PTTL',KEYS[3])~=-1
  or redis.call('TYPE',KEYS[4]).ok~='string' or redis.call('PTTL',KEYS[4])~=-1
  or not exact(KEYS[3],'generation',ARGV[2]) or not exact(KEYS[3],'control_epoch',ARGV[3])
  or not exact(KEYS[3],'run_id',run) or not exact(KEYS[3],'inventory',ARGV[9])
  or redis.call('STRLEN',KEYS[4])>16 then return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED') end
local previous=bounded(KEYS[3],'reservation_epoch',19)
local floor,ceiling=bounded(KEYS[3],'floor',16),bounded(KEYS[3],'ceiling',16)
local request=bounded(KEYS[3],'request',36)
local current=redis.call('GET',KEYS[4])
local controlSize=redis.call('HSTRLEN',KEYS[3],'reservation_control_epoch')
if controlSize>19 then return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED') end
local control=redis.call('HGET',KEYS[3],'reservation_control_epoch')
if not decimal(previous,'9223372036854775807') or previous=='0' or not request or #request~=36
  or not decimal(floor,'9007199254740991') or not decimal(ceiling,'9007199254740991')
  or not decimal(current,'9007199254740991') or not less(floor,ceiling)
  or less(current,floor) or less(ceiling,current)
  or (control and (not decimal(control,'9223372036854775807') or less(ARGV[4],control))) then
  return redis.error_reply('CDB_VERSION_RECOVERY_REQUIRED')
end
if less(ARGV[5],previous) then return redis.error_reply('CDB_VERSION_STALE_RANGE') end
if previous==ARGV[5] then
  if request~=ARGV[8] or floor~=ARGV[6] or ceiling~=ARGV[7]
    or (control and control~=ARGV[4]) or (not control and ARGV[4]~=ARGV[3]) then
    return redis.error_reply('CDB_VERSION_STALE_RANGE')
  end
  -- A matching retry does not SET, HSET or rewind a consumed counter.
  return 0
end
if request==ARGV[8] or less(ARGV[6],ceiling) then return redis.error_reply('CDB_VERSION_STALE_RANGE') end
local update={'HSET',KEYS[3],'generation',ARGV[2],'control_epoch',ARGV[3],
  'reservation_control_epoch',ARGV[4],'reservation_epoch',ARGV[5],'floor',ARGV[6],
  'ceiling',ARGV[7],'request',ARGV[8],'run_id',run,'inventory',ARGV[9]}
-- Redis errors do not roll back scripts. Denied writes must be detected before the first mutation.
if not redis.acl_check_cmd(unpack(update)) or not redis.acl_check_cmd('SET',KEYS[4],ARGV[6]) then
  return redis.error_reply('CDB_VERSION_REFILL_DENIED')
end
redis.call(unpack(update))
redis.call('SET',KEYS[4],ARGV[6])
return 1
