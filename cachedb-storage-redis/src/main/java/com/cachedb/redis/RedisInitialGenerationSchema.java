package com.reactor.cachedb.redis;

/** Internal Lua data validation shared by INITIAL maintenance and ordinary identity purge. */
final class RedisInitialGenerationSchema {
    private RedisInitialGenerationSchema() {}

    static final String LUA = """
        local function cachedb_initial_decimal(value,maximum,zero)
          if not value or #value==0 or #value>#maximum or not string.match(value,'^%d+$')
            or #value>1 and string.sub(value,1,1)=='0' or not zero and value=='0'
            or #value==#maximum and value>maximum then return nil end
          return tonumber(value)
        end
        local function cachedb_initial_hex(value,length)
          return value and #value==length and string.match(value,'^[0-9a-f]+$')~=nil
        end
        local function cachedb_initial_uuid(value)
          return value and #value==36 and string.match(value,
            '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
            and value==string.lower(value)
        end
        local function cachedb_initial_ledger(stage)
          if redis.call('TYPE',stage).ok~='hash' then return nil end
          local length=redis.call('HLEN',stage)
          if length<6 or length>160 then return nil end
          local base={state=1,generation=1,operation=1,owner=1,epoch=1,run_id=1}
          local tokens={['i:plan']=1,['i:manifest']=1,['i:verified']=1,['i:window']=1,
            ['i:active']=1,['i:active_range']=1}
          local limits={['i:rows']='10000',['i:bytes']='67108864',['i:pages']='128',
            ['i:deadline']='9007199254740991',['i:finalize_epoch']='9007199254740991'}
          local seen,values={},{}
          local rows,bytes,pages,optional=0,0,0,0
          for _,field in ipairs(redis.call('HKEYS',stage)) do
            if base[field] then
              if redis.call('HSTRLEN',stage,field)>64 then return nil end
              seen[field]=true
            elseif field=='compaction_ack' then
              optional=optional+1
              if redis.call('HSTRLEN',stage,field)~=64
                or not cachedb_initial_hex(redis.call('HGET',stage,field),64) then return nil end
            elseif string.sub(field,1,4)=='i:p:' then
              if #field~=40 or not cachedb_initial_uuid(string.sub(field,5))
                or redis.call('HSTRLEN',stage,field)>76 then return nil end
              local receipt=redis.call('HGET',stage,field)
              local token,r,b=string.match(receipt,'^([0-9a-f]+):(%d+):(%d+)$')
              r=cachedb_initial_decimal(r,'128',true)
              b=cachedb_initial_decimal(b,'8388608',true)
              if not cachedb_initial_hex(token,64) or not r or not b then return nil end
              pages=pages+1;rows=rows+r;bytes=bytes+b
            elseif tokens[field] then
              optional=optional+1
              if redis.call('HSTRLEN',stage,field)~=64 then return nil end
              local value=redis.call('HGET',stage,field)
              if not cachedb_initial_hex(value,64) then return nil end
              values[field]=value
            elseif field=='i:operation' then
              optional=optional+1
              if redis.call('HSTRLEN',stage,field)~=36 then return nil end
              local value=redis.call('HGET',stage,field)
              if not cachedb_initial_uuid(value) then return nil end
              values[field]=value
            elseif limits[field] then
              optional=optional+1
              if redis.call('HSTRLEN',stage,field)>#limits[field] then return nil end
              local value=redis.call('HGET',stage,field)
              local number=cachedb_initial_decimal(value,limits[field],field~='i:finalize_epoch')
              if not number then return nil end
              values[field]=number
            else return nil end
          end
          if length~=6+optional+pages or pages>128 or rows>10000 or bytes>67108864 then return nil end
          for field in pairs(base) do if not seen[field] then return nil end end
          if next(values) or pages>0 then
            for _,field in ipairs({'i:operation','i:plan','i:manifest','i:rows','i:bytes','i:pages'}) do
              if values[field]==nil then return nil end
            end
            if values['i:active'] then
              if not values['i:active_range'] or not values['i:verified'] or not values['i:window']
                or values['i:deadline']==nil or pages~=0 or values['i:pages']~=0 then return nil end
            elseif values['i:active_range'] or values['i:finalize_epoch']
              or rows~=values['i:rows'] or bytes~=values['i:bytes'] or pages~=values['i:pages'] then return nil end
          end
          return rows,bytes,pages
        end
        """;
}
