local owner=ARGV[1]
if redis.call('GET',KEYS[1])~=owner or redis.call('PTTL',KEYS[1])<=0 then return redis.error_reply('RETENTION_LEASE_LOST') end
local n=tonumber(ARGV[2]); local cutoff=ARGV[3]; local offset=tonumber(ARGV[4])
if n<1 or n>128 or offset<0 or offset>10000 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
local result=redis.call('ZRANGEBYSCORE',KEYS[2],'-inf',cutoff,'WITHSCORES','LIMIT',offset,n)
local bytes=0
for i=1,#result,2 do
  if #result[i]>1024 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
  bytes=bytes+#result[i]+#result[i+1]
end
if bytes>262144 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
return result
