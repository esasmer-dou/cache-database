import com.reactor.cachedb.redis.BoundedRedisWire;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Path;
import java.util.Arrays;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

/** Real network authentication checks only; no sample/SQL/performance qualification. */
public final class SampleRedisAuthenticationCheck {
    private static int checks;
    public static void main(String[] args)throws Exception{
        if((args.length!=1&&args.length!=2)||args.length==2&&!args[1].equals("sample-helper")
                ||System.getenv("KUBERNETES_SERVICE_HOST")==null)
            throw new IllegalStateException("Kubernetes fixture required");
        String origin=args[0];URI endpoint=URI.create(origin);
        if(!origin.matches("redis://closure531-redis\\.cachedb-v019-validation\\.svc\\.cluster\\.local:6379/0"))
            throw new IllegalStateException("Fresh dedicated endpoint required");
        String password=(String)call("readRedisPassword",new Class<?>[]{Path.class},
                Path.of("/var/run/cachedb-fixture/password"));
        if(args.length==2){
            Class<?> helper=Class.forName("com.example.cachedb.sample.SampleRedisAuth");
            String value=(String)helper.getMethod("withPassword",String.class,String.class).invoke(null,origin,password);
            try(var client=new Jedis(URI.create(value),2000)){
                check("PONG".equals(client.ping()),"sample helper encoded URI authentication");
            }
            var config=(redis.clients.jedis.DefaultJedisClientConfig)helper.getMethod("purgeClientConfig",String.class)
                    .invoke(null,password);
            try(var client=new Jedis(new redis.clients.jedis.HostAndPort(endpoint.getHost(),endpoint.getPort()),config)){
                check("PONG".equals(client.ping()),"sample helper purge client authentication");
            }
            System.out.println(checks+" live sample helper authentication checks passed; no sample lane acceptance");
            return;
        }
        for(int db:new int[]{0,12}){
            try(var client=(Jedis)call("connectRedis",new Class<?>[]{String.class,String.class},
                    origin.substring(0,origin.length()-1)+db,password)){
                check("PONG".equals(client.ping())&&client.dbSize()==0,"authenticated client and empty fixture");
            }
        }
        String authenticated=(String)call("authenticatedRedisUri",new Class<?>[]{String.class,String.class},origin,password);
        try(var client=new Jedis(URI.create(authenticated),2000)){
            check("PONG".equals(client.ping()),"in-memory authenticated URI");
        }
        char[] chars=password.toCharArray();
        try(var wire=new BoundedRedisWire(new InetSocketAddress(endpoint.getHost(),endpoint.getPort()),
                null,null,null,chars,0,2000,2000,1);var session=wire.connect()){
            check("PONG".equals(session.call(BoundedRedisWire.Limits.scan(),"PING").value()),
                    "bounded transport authentication");
        }finally{Arrays.fill(chars,'\0');}
        rejectAuthentication(()->new Jedis(URI.create(origin),2000),"NOAUTH");
        rejectAuthentication(()->(Jedis)call("connectRedis",new Class<?>[]{String.class,String.class},
                origin,"invalid-fixture-password-531"),"WRONGPASS");
        System.out.println(checks+" live Redis authentication checks passed; no sample or release acceptance");
    }
    private static Object call(String name,Class<?>[] signature,Object... args)throws Exception{
        Method method=SampleRuntimeEvidence.class.getDeclaredMethod(name,signature);method.setAccessible(true);
        try{return method.invoke(null,args);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception exception)throw exception;
            throw new IllegalStateException("Fixture authentication check failed");
        }
    }
    @FunctionalInterface private interface Client {Jedis open()throws Exception;}
    private static void rejectAuthentication(Client factory,String code)throws Exception{
        try(var client=factory.open()){
            client.ping();throw new IllegalStateException("Unauthorized connection accepted");
        }catch(JedisDataException denied){
            check(denied.getMessage()!=null&&denied.getMessage().startsWith(code),"explicit authentication rejection");
        }
    }
    private static void check(boolean condition,String message){
        if(!condition)throw new IllegalStateException(message);checks++;
    }
}
