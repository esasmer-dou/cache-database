if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('PTTL',KEYS[1])<=0 then return redis.error_reply('RETENTION_LEASE_LOST') end
local n=tonumber(ARGV[2]); local nf=tonumber(ARGV[3]); local guarded=ARGV[4]=='1'; local bytes=0
if n<1 or n>128 or nf<1 or nf>160 or #KEYS~=1+6*n or #ARGV~=4+nf+n then return redis.error_reply('RETENTION_INPUT_LIMIT') end
for i=1,n do
  local k=2+(i-1)*6
  if redis.call('HLEN',KEYS[k+4])>nf then return redis.error_reply('RETENTION_LAYOUT') end
  for j=0,3 do
    local len=redis.call('STRLEN',KEYS[k+j])
    if (j==0 and len>262144) or (j==1 and len>20) or (j==2 and len>1024) or (j==3 and len>8192) then return redis.error_reply('RETENTION_INPUT_LIMIT') end
    bytes=bytes+len
  end
  for j=1,nf do
    local len=redis.call('HSTRLEN',KEYS[k+4],ARGV[4+j])
    if len>4096 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
    bytes=bytes+len
  end
  if guarded and (redis.call('HSTRLEN',KEYS[k+5],'generation')>19 or redis.call('HSTRLEN',KEYS[k+5],'state')>16) then
    return redis.error_reply('RETENTION_INPUT_LIMIT')
  end
  if bytes>4194304 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
end
local result={}
for i=1,n do
  local k=2+(i-1)*6
  local generation=''
  local owner=redis.call('GET',KEYS[k+2]) or ''
  local eligible=owner==ARGV[4+nf+i]
  if guarded then
    generation=redis.call('HGET',KEYS[k+5],'generation') or ''
    eligible=eligible and redis.call('HGET',KEYS[k+5],'state')=='OPEN'
      and string.match(generation,'^[1-9][0-9]*$')~=nil and #generation<=19
      and (#generation<19 or generation<='9223372036854775807')
  end
  if not eligible then result[i]=false
  else
    local row={}
    for j=0,3 do row[j+1]=redis.call('GET',KEYS[k+j]) or false end
    local values={}
    local known=0
    for j=1,nf do local v=redis.call('HGET',KEYS[k+4],ARGV[4+j]); values[j]=v or false; if v then known=known+1 end end
    if known~=redis.call('HLEN',KEYS[k+4]) then return redis.error_reply('RETENTION_LAYOUT') end
    row[5]=values; row[6]=generation
    result[i]=row
  end
end
return result
