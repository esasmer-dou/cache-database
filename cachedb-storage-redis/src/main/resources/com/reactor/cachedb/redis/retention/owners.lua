if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('PTTL',KEYS[1])<=0 then return redis.error_reply('RETENTION_LEASE_LOST') end
if #KEYS<2 or #KEYS>129 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
for i=2,#KEYS do
  if redis.call('STRLEN',KEYS[i])>1024 then return redis.error_reply('RETENTION_INPUT_LIMIT') end
end
local owners={}
for i=2,#KEYS do owners[i-1]=redis.call('GET',KEYS[i]) or '' end
return owners
