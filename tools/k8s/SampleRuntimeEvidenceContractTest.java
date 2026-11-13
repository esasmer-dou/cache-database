import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Tooling contract checks only; never creates SQL/Redis fixtures or qualifies a release. */
public final class SampleRuntimeEvidenceContractTest {
    private static final Class<?> ADAPTER;
    private static int checks;
    static {
        try { ADAPTER=Class.forName("SampleRuntimeEvidence"); }
        catch (ClassNotFoundException missing) { throw new ExceptionInInitializerError(missing); }
    }

    public static void main(String[] args)throws Exception {
        if(args.length!=1||System.getenv("KUBERNETES_SERVICE_HOST")==null)
            throw new IllegalStateException("Dedicated Kubernetes test directory required");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.startsWith(Path.of("/workspace"))||Files.exists(root))
            throw new IllegalStateException("Fresh owned test directory required");
        Files.createDirectories(root);
        for(String version:List.of("7.3.0","7.4.21","7.10.0","8.0.0"))
            check((boolean)call("supportedPowerShellVersion",null,new Class<?>[]{String.class},version),"supported PowerShell "+version);
        for(String version:List.of("7.2.99","6.9.0","invalid","7.4.21-preview.1","7.10000.0","10000.0.0"))
            check(!(boolean)call("supportedPowerShellVersion",null,new Class<?>[]{String.class},version),"unsupported PowerShell "+version);
        for(String[] boundary:List.of(
                new String[]{"2026-11-08T12:00:00Z","2026-11-08T12:00:00Z","2026-11-08T12:00:00Z"},
                new String[]{"2026-11-08T12:00:00.123Z","2026-11-08T12:00:00.123Z","2026-11-08T12:00:00.123Z"},
                new String[]{"2026-11-08T12:00:00.000000001Z","2026-11-08T12:00:00Z","2026-11-08T12:00:00.001Z"},
                new String[]{"2026-11-08T12:00:00.123456Z","2026-11-08T12:00:00.123Z","2026-11-08T12:00:00.124Z"},
                new String[]{"2026-11-08T12:00:00.999999999Z","2026-11-08T12:00:00.999Z","2026-11-08T12:00:01Z"})){
            var actual=java.time.Instant.parse(boundary[0]);
            String start=(String)call("receiptTime",null,new Class<?>[]{java.time.Instant.class,boolean.class},actual,false);
            String finish=(String)call("receiptTime",null,new Class<?>[]{java.time.Instant.class,boolean.class},actual,true);
            check(start.equals(boundary[1])&&finish.equals(boundary[2]),"canonical conservative receipt timestamps");
            check(!java.time.Instant.parse(start).isAfter(actual)&&!java.time.Instant.parse(finish).isBefore(actual),
                    "receipt rounding cannot understate duration or extend cutoff");
            check(start.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{3})?Z")
                    &&finish.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{3})?Z"),"strict verifier timestamp shape");
        }
        String digest="a".repeat(64);
        Path manifest=root.resolve("source.sha256");
        Files.writeString(manifest,digest+"  src/Main.java\n");
        SortedMap<?,?> parsed=(SortedMap<?,?>)call("manifest",null,new Class<?>[]{Path.class,String.class},manifest,sha(manifest));
        check(parsed.size()==1&&digest.equals(parsed.get("src/Main.java")),"valid manifest");
        reject(()->call("manifest",null,new Class<?>[]{Path.class,String.class},manifest,"b".repeat(64)),"manifest pin mismatch");
        List<String> invalid=List.of("",digest+"  ../foreign\n",digest+"  ./relative\n",digest+"  /absolute\n",
                digest+"  x\\foreign\n",digest+"  file:foreign\n",digest.toUpperCase(Locale.ROOT)+"  path\n",
                digest+"  same\n"+digest+"  same\n");
        for(int i=0;i<invalid.size();i++){
            Path file=root.resolve("invalid-"+i+".sha256");Files.writeString(file,invalid.get(i));
            reject(()->call("manifest",null,new Class<?>[]{Path.class,String.class},file,sha(file)),"invalid manifest "+i);
        }
        Path content=root.resolve("regular.txt");Files.writeString(content,"owned");
        Path auditRoot=Files.createDirectory(root.resolve("audit-tree"));Files.writeString(auditRoot.resolve("regular"),"owned");
        call("auditFiles",null,new Class<?>[]{List.class},List.of(auditRoot));checks++;
        check((boolean)call("vanished",null,new Class<?>[]{java.io.IOException.class},new NoSuchFileException("temporary")),"only disappeared audit entries are transient");
        check(!(boolean)call("vanished",null,new Class<?>[]{java.io.IOException.class},new AccessDeniedException("private")),"audit permission errors remain fatal");
        Files.createSymbolicLink(auditRoot.resolve("alias"),content);
        reject(()->call("auditFiles",null,new Class<?>[]{List.class},List.of(auditRoot)),"audit symlink remains forbidden");
        Files.delete(auditRoot.resolve("alias"));
        try(var file=new java.io.RandomAccessFile(auditRoot.resolve("large").toFile(),"rw")){file.setLength(128L*1024*1024+1);}
        reject(()->call("auditFiles",null,new Class<?>[]{List.class},List.of(auditRoot)),"audit file size bound remains enforced");
        reject(()->call("auditFiles",null,new Class<?>[]{List.class},List.of(root.resolve("absent-audit-root"))),"missing audit root remains fatal");
        check(call("safe",null,new Class<?>[]{Path.class,String.class},root,"regular.txt").equals(content),"regular input");
        Files.createSymbolicLink(root.resolve("alias.txt"),content);
        reject(()->call("safe",null,new Class<?>[]{Path.class,String.class},root,"alias.txt"),"symlink input");
        Path large=root.resolve("oversized.bin");
        try(var file=new java.io.RandomAccessFile(large.toFile(),"rw")){file.setLength(64L*1024*1024+1);}
        reject(()->call("safe",null,new Class<?>[]{Path.class,String.class},root,"oversized.bin"),"oversized input");
        var tools=new TreeMap<String,String>();tools.put("oversized.bin",sha(large));
        call("verifyTools",null,new Class<?>[]{Path.class,SortedMap.class},root,tools);checks++;
        reject(()->call("verify",null,new Class<?>[]{Path.class,SortedMap.class},root,tools),
                "large tool allowance cannot admit oversized source or SDK input");
        var wrongToolPin=new TreeMap<>(tools);wrongToolPin.put("oversized.bin","b".repeat(64));
        reject(()->call("verifyTools",null,new Class<?>[]{Path.class,SortedMap.class},root,wrongToolPin),"large tool pin mismatch");
        var aliasTool=new TreeMap<String,String>();aliasTool.put("alias.txt",sha(content));
        reject(()->call("verifyTools",null,new Class<?>[]{Path.class,SortedMap.class},root,aliasTool),"tool symlink");
        Path oversizedTool=root.resolve("oversized-tool.bin");
        try(var file=new java.io.RandomAccessFile(oversizedTool.toFile(),"rw")){file.setLength(256L*1024*1024+1);}
        var oversizedTools=new TreeMap<String,String>();oversizedTools.put("oversized-tool.bin",digest);
        reject(()->call("verifyTools",null,new Class<?>[]{Path.class,SortedMap.class},root,oversizedTools),"oversized native tool");
        var oversizedToolTree=new TreeMap<String,String>();
        for(int i=0;i<3;i++){
            String name="tool-tree-"+i+".bin";
            try(var file=new java.io.RandomAccessFile(root.resolve(name).toFile(),"rw")){file.setLength(192L*1024*1024);}
            oversizedToolTree.put(name,digest);
        }
        String treePin=sha(root.resolve("tool-tree-0.bin"));oversizedToolTree.replaceAll((name,pin)->treePin);
        reject(()->call("verifyTools",null,new Class<?>[]{Path.class,SortedMap.class},root,oversizedToolTree),"tool tree total bound before hashing");
        Path pom=root.resolve("pom.xml");
        Files.writeString(pom,"<project><artifactId>test-artifact</artifactId></project>");
        Object element=call("pom",null,new Class<?>[]{Path.class},pom);
        check(call("direct",null,new Class<?>[]{org.w3c.dom.Element.class,String.class},element,"artifactId").equals("test-artifact"),"direct POM field");
        Files.writeString(pom,"<!DOCTYPE project [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><project>&xxe;</project>");
        reject(()->call("pom",null,new Class<?>[]{Path.class},pom),"external XML entity");

        Path sdkManifest=root.resolve("sdk.sha256");Files.writeString(sdkManifest,digest+"  sdk.jar\n");
        Path admission=root.resolve("admission.properties");
        String properties="sourceRoot="+root.resolve("source")+"\n"
                +"sdkRoot="+root.resolve("sdk")+"\nrepository="+root.resolve("repository")+"\n"
                +"workRoot="+root.resolve("work")+"\nevidenceRoot="+root.resolve("evidence")+"\n"
                +"sourceManifest="+manifest+"\nsdkManifest="+sdkManifest+"\n"
                +"sourceManifestSha256="+sha(manifest)+"\nsdkManifestSha256="+sha(sdkManifest)+"\n"
                +"sdkSourceManifestSha256="+sha(manifest)+"\noriginalCutoff=2026-11-08T23:59:00Z\n"
                +"namespace=cachedb-v019-validation\nredisServiceName=s530-private-redis\n"
                +"maven=/usr/bin/mvn\nsettings="+root.resolve("settings.xml")+"\nglobalSettings="+root.resolve("global.xml")+"\n";
        Files.writeString(admission,properties);
        var constructor=ADAPTER.getDeclaredConstructor(Path.class,String.class);constructor.setAccessible(true);
        Object adapter=construct(constructor,admission,sha(admission));checks++;
        for(int database:List.of(0,1,12)){
            check(call("redis",adapter,new Class<?>[]{int.class},database).equals(
                    "redis://s530-private-redis.cachedb-v019-validation.svc.cluster.local:6379/"+database),
                    "admitted Redis service and database "+database);
        }
        for(int database:List.of(-1,13,Integer.MAX_VALUE))
            reject(()->call("redis",adapter,new Class<?>[]{int.class},database),"out of range Redis database");
        for(String name:List.of("","Redis","-redis","redis-","redis.private","redis/other",
                "redis:6379","redis_user","r".repeat(64),"redis@host","redis*")){
            Files.writeString(admission,properties.replace("redisServiceName=s530-private-redis\n",
                    "redisServiceName="+name+"\n"));
            Object invalidAdapter=construct(constructor,admission,sha(admission));
            reject(()->call("redis",invalidAdapter,new Class<?>[]{int.class},0),"invalid Redis service name");
        }
        Files.writeString(admission,properties.replace("redisServiceName=s530-private-redis\n",
                "redisServiceName="+"r".repeat(63)+"\n"));
        Object longestAdapter=construct(constructor,admission,sha(admission));
        check(call("redis",longestAdapter,new Class<?>[]{int.class},12).equals(
                "redis://"+"r".repeat(63)+".cachedb-v019-validation.svc.cluster.local:6379/12"),
                "longest valid Redis service label");
        Files.writeString(admission,properties);
        Path passwordFile=root.resolve("redis-password");
        String syntheticPassword="contract+/%@:?=0123456789";
        for(String suffix:List.of("","\n","\r\n")){
            Files.writeString(passwordFile,syntheticPassword+suffix);
            check(call("readRedisPassword",null,new Class<?>[]{Path.class},passwordFile).equals(syntheticPassword),
                    "bounded mounted password with supported line ending");
        }
        Files.createSymbolicLink(root.resolve("password-projection"),passwordFile);
        check(call("readRedisPassword",null,new Class<?>[]{Path.class},root.resolve("password-projection")).equals(syntheticPassword),
                "Kubernetes Secret projection symlink");
        for(String invalidPassword:List.of("","x".repeat(15),"x".repeat(513),"x".repeat(1025),
                syntheticPassword+"\r",syntheticPassword+"\n\n",syntheticPassword+"\u0000",
                syntheticPassword+" ",syntheticPassword+"\u00fc")){
            Files.writeString(passwordFile,invalidPassword);
            reject(()->call("readRedisPassword",null,new Class<?>[]{Path.class},passwordFile),"invalid mounted password");
        }
        String origin="redis://s530-private-redis.cachedb-v019-validation.svc.cluster.local:6379/12";
        String authenticated=(String)call("authenticatedRedisUri",null,new Class<?>[]{String.class,String.class},origin,syntheticPassword);
        var uri=java.net.URI.create(authenticated);
        check(uri.getUserInfo().equals("default:"+syntheticPassword)&&uri.getPath().equals("/12")
                &&uri.getHost().equals(java.net.URI.create(origin).getHost()),"password URI encoding preserves credentials and endpoint");
        for(String invalidOrigin:List.of(origin.replace("redis://","rediss://"),origin.replace(":6379",":6380"),
                origin+"?override=true",origin+"#fragment",origin.replace("/12","/13"),
                origin.replace("redis://","redis://user:password@"))){
            reject(()->call("authenticatedRedisUri",null,new Class<?>[]{String.class,String.class},invalidOrigin,syntheticPassword),
                    "reject credentialed or out-of-contract endpoint");
        }
        var lane=new ObjectMapper().readTree("{\"profiles\":\"provider-integration,audit-demo\",\"surefire\":\"AuditTest\",\"failsafe\":\"AuditIT\"}");
        @SuppressWarnings("unchecked") var command=(List<String>)call("maven",adapter,new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class},lane);
        check(command.contains("-o")&&command.contains("-C")&&command.getLast().equals("verify"),"offline strict verify");
        check(command.contains("-DskipTests=false")&&command.contains("-Dmaven.test.skip=false")
                &&command.contains("-Dmaven.test.failure.ignore=false"),"cannot skip or ignore tests");
        check(command.contains("-Dsurefire.rerunFailingTestsCount=0")&&command.contains("-Dfailsafe.rerunFailingTestsCount=0"),"no retry laundering");
        check(command.contains("-Dtest=AuditTest")&&command.contains("-Dit.test=AuditIT")
                &&command.contains("-Pprovider-integration,audit-demo"),"declared lane selectors");
        check(command.stream().noneMatch(value->value.equals("install")||value.equals("deploy")||value.equals("-am")),"no reactor rebuild or install");
        Files.writeString(admission,properties+"sourceRoot="+root.resolve("other")+"\n");
        reject(()->construct(constructor,admission,sha(admission)),"duplicate admission property");

        Path childLog=root.resolve("child.log");
        Process child=(Process)call("start",null,new Class<?>[]{List.class,Map.class,Path.class,Path.class},
                List.of("/usr/bin/env"),Map.of("CACHEDB_ADAPTER_CONTRACT_MARKER","owned"),root,childLog);
        check(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)&&child.exitValue()==0,"bounded child execution");
        String env=Files.readString(childLog);
        check(env.lines().anyMatch(line->line.equals("CACHEDB_ADAPTER_CONTRACT_MARKER=owned")),"explicit child environment");
        check(env.lines().noneMatch(line->line.startsWith("JAVA_TOOL_OPTIONS=")||line.startsWith("JDK_JAVA_OPTIONS=")
                ||line.startsWith("SPRING_PROFILES_ACTIVE=")||line.startsWith("CACHEDB_CONTRACT_INHERITED_SECRET=")),"ambient overrides excluded");
        call("stop",null,new Class<?>[]{Process.class},child);
        Process sleeper=(Process)call("start",null,new Class<?>[]{List.class,Map.class,Path.class,Path.class},
                List.of("/bin/sleep","30"),Map.of(),root,root.resolve("sleep.log"));
        call("stop",null,new Class<?>[]{Process.class},sleeper);
        check(!sleeper.isAlive(),"child cleanup");
        System.out.println(checks+" sample adapter contract checks passed; not live sample or release acceptance");
    }

    private static Object construct(Constructor<?> constructor,Object... args)throws Exception{
        try{return constructor.newInstance(args);}catch(InvocationTargetException failure){throw cause(failure);}
    }
    private static Object call(String name,Object target,Class<?>[] signature,Object... args)throws Exception{
        var method=ADAPTER.getDeclaredMethod(name,signature);method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw cause(failure);}
    }
    private static Exception cause(InvocationTargetException failure){
        if(failure.getCause() instanceof Error fatal)throw fatal;
        return (Exception)failure.getCause();
    }
    private static String sha(Path path)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        return HexFormat.of().formatHex(digest.digest());
    }
    @FunctionalInterface private interface Attempt {void run()throws Exception;}
    private static void reject(Attempt attempt,String message)throws Exception{
        try{attempt.run();}catch(IllegalStateException|org.xml.sax.SAXParseException expected){checks++;return;}
        throw new AssertionError(message+" was accepted");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
