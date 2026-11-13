-- Read-only preflight, then ONE all-or-none owner/zero-version mutation. Never replay after dispatch.
local count=tonumber(ARGV[5])
local fields=tonumber(ARGV[6])
if not count or count<1 or count>128 or count%1~=0 or #KEYS~=4+9*count
  or not fields or fields<1 or fields>328
  or #ARGV[2]<1 or #ARGV[2]>16 or #ARGV[3]<1 or #ARGV[3]>16 then
  return redis.error_reply('CDB_FRESH_INCOMPLETE')
end
local proof=6+fields*2
local grantFields=tonumber(ARGV[proof+6])
if not grantFields or grantFields<1 or grantFields>16 or #ARGV~=proof+6+grantFields*2 then
  return redis.error_reply('CDB_FRESH_INCOMPLETE')
end
local run=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
local replication=redis.call('INFO','REPLICATION')
if run~=ARGV[1] or not string.find(replication,'role:master\r\n',1,true)
  or redis.call('TYPE',KEYS[1]).ok~='hash' or redis.call('PTTL',KEYS[1])~=-1
  or redis.call('HSTRLEN',KEYS[1],'run_id')~=40
  or redis.call('HSTRLEN',KEYS[1],'state')~=4
  or redis.call('HSTRLEN',KEYS[1],'generation')~=#ARGV[2]
  or redis.call('HSTRLEN',KEYS[1],'epoch')~=#ARGV[3]
  or redis.call('HGET',KEYS[1],'run_id')~=run
  or redis.call('HGET',KEYS[1],'state')~='OPEN'
  or redis.call('HGET',KEYS[1],'generation')~=ARGV[2]
  or redis.call('HGET',KEYS[1],'epoch')~=ARGV[3] then
  return redis.error_reply('CDB_FRESH_GENERATION_CHANGED')
end
for i=1,grantFields do
  local field,value=ARGV[proof+5+i*2],ARGV[proof+6+i*2]
  if #field>64 or #value>64 or redis.call('HSTRLEN',KEYS[1],field)~=#value
    or redis.call('HGET',KEYS[1],field)~=value then return redis.error_reply('CDB_FRESH_GENERATION_CHANGED') end
end
if redis.call('TYPE',KEYS[2]).ok~='hash' or redis.call('PTTL',KEYS[2])~=-1
  or redis.call('HLEN',KEYS[2])~=fields+1
  or redis.call('HSTRLEN',KEYS[2],'run_id')~=40
  or redis.call('HGET',KEYS[2],'run_id')~=run then
  return redis.error_reply('CDB_FRESH_RUNTIME_CHANGED')
end
for i=1,fields do
  local field,value=ARGV[5+i*2],ARGV[6+i*2]
  if redis.call('HSTRLEN',KEYS[2],field)~=#value or redis.call('HGET',KEYS[2],field)~=value then
    return redis.error_reply('CDB_FRESH_RUNTIME_CHANGED')
  end
end
local function decimal(value)
  return value and #value<=16 and string.match(value,'^[0-9]+$')
    and (value=='0' or string.sub(value,1,1)~='0')
    and (#value<16 or value<='9007199254740991')
end
local function less(a,b) if #a~=#b then return #a<#b end return a<b end
local function bounded_decimal_field(key,field)
  local length=redis.call('HSTRLEN',key,field)
  return length>=1 and length<=16
end
if redis.call('TYPE',KEYS[3]).ok~='hash' or redis.call('PTTL',KEYS[3])~=-1
  or redis.call('TYPE',KEYS[4]).ok~='string' or redis.call('PTTL',KEYS[4])~=-1
  or redis.call('HSTRLEN',KEYS[3],'run_id')~=40
  or redis.call('HSTRLEN',KEYS[3],'generation')~=#ARGV[2]
  or redis.call('HSTRLEN',KEYS[3],'control_epoch')~=#ARGV[3]
  or redis.call('HSTRLEN',KEYS[3],'inventory')~=64
  or not bounded_decimal_field(KEYS[3],'floor') or not bounded_decimal_field(KEYS[3],'ceiling')
  or redis.call('STRLEN',KEYS[4])>16
  or redis.call('HGET',KEYS[3],'run_id')~=run
  or redis.call('HGET',KEYS[3],'generation')~=ARGV[2]
  or redis.call('HGET',KEYS[3],'control_epoch')~=ARGV[3] then
  return redis.error_reply('CDB_FRESH_VERSION_CHANGED')
end
local grantVersion={'floor','ceiling','inventory','request','reservation_epoch'}
for i,field in ipairs(grantVersion) do
  local value=ARGV[proof+i]
  if #value>64 or redis.call('HSTRLEN',KEYS[3],field)~=#value
    or redis.call('HGET',KEYS[3],field)~=value then return redis.error_reply('CDB_FRESH_VERSION_CHANGED') end
end
local current=redis.call('GET',KEYS[4])
local floor,ceiling=redis.call('HGET',KEYS[3],'floor'),redis.call('HGET',KEYS[3],'ceiling')
if not decimal(current) or not decimal(floor) or not decimal(ceiling)
  or less(current,floor) or less(ceiling,current) then
  return redis.error_reply('CDB_FRESH_VERSION_CHANGED')
end
if redis.call('EXISTS',unpack(KEYS,5,#KEYS))~=0 then return redis.error_reply('CDB_FRESH_IDENTITY_EXISTS') end
local markers={}
for i=1,count do
  markers[#markers+1]=KEYS[4+(i-1)*9+3]; markers[#markers+1]=ARGV[4]
  markers[#markers+1]=KEYS[4+(i-1)*9+2]; markers[#markers+1]='0'
end
if not redis.acl_check_cmd('MSETNX',unpack(markers)) then return redis.error_reply('CDB_FRESH_OWNER_DENIED') end
return redis.call('MSETNX',unpack(markers))
