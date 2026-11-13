import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.jdbc.InitialSqlSandbox;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;

/** Execution adapter only. Raw lane evidence is qualified by the existing verifier. */
public final class SampleRuntimeEvidence {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> PROVIDERS=List.of("postgresql","mssql","oracle");
    private static final List<String> LANES=List.of("audit-derived-delete","encrypted-notes","tenant-purge");
    private static final long MAX_INPUT_BYTES=64L*1024*1024;
    private static final long MAX_TOOL_BYTES=256L*1024*1024;
    private static final long MAX_TOOL_TREE_BYTES=512L*1024*1024;
    private static final List<Process> ACTIVE=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Properties config=new Properties();
    private final Path source,sdk,repository,work,out,sourceManifest,sdkManifest;
    private final SortedMap<String,String> sourceFiles,sdkFiles;
    private final Instant cutoff;
    private final String sourcePin,sdkPin;
    private final Set<String> fixtures=new HashSet<>();
    private final Map<String,Path> toolTargets=new HashMap<>();
    private String stage="admission";
    private String redisPassword;

    private SampleRuntimeEvidence(Path admission,String pin)throws Exception{
        need(hash(admission).equals(pin));
        need(Files.size(admission)<=65536);var seen=new HashSet<String>();
        for(String line:Files.readAllLines(admission,StandardCharsets.UTF_8)){
            if(line.isBlank()||line.startsWith("#"))continue;
            int split=line.indexOf('=');need(split>0&&!line.contains("\\")&&!line.contains("\r"));
            String name=line.substring(0,split);need(name.matches("[a-zA-Z][a-zA-Z0-9]*")&&seen.add(name));
            config.setProperty(name,line.substring(split+1));
        }
        source=path("sourceRoot");sdk=path("sdkRoot");repository=path("repository");work=path("workRoot");out=path("evidenceRoot");
        sourceManifest=path("sourceManifest");sdkManifest=path("sdkManifest");
        sourcePin=get("sourceManifestSha256");sdkPin=get("sdkManifestSha256");
        need(get("sdkSourceManifestSha256").equals(sourcePin));
        sourceFiles=manifest(sourceManifest,sourcePin);sdkFiles=manifest(sdkManifest,sdkPin);
        cutoff=Instant.parse(get("originalCutoff"));
    }
    public static void main(String[] args){
        SampleRuntimeEvidence adapter=null;
        Runtime.getRuntime().addShutdownHook(new Thread(SampleRuntimeEvidence::stopRunning));
        try{
            need(args.length==2);adapter=new SampleRuntimeEvidence(Path.of(args[0]),args[1]);adapter.execute();
        }catch(Throwable failure){
            // SQL credentials and raw server/process exception messages never reach the adapter log.
            System.err.println("Sample runtime adapter stopped: "+(adapter==null?"admission":adapter.stage)
                    +" ["+failure.getClass().getSimpleName()+"]");
            int frames=0;
            for(var frame:failure.getStackTrace())if(frame.getClassName().equals(SampleRuntimeEvidence.class.getName())&&frames++<8)
                System.err.println("  at SampleRuntimeEvidence."+frame.getMethodName()+":"+frame.getLineNumber());
            System.exit(1);
        }
    }
    private void execute()throws Exception{
        preflight();
        copyManifestTree(source,sourceFiles,out.resolve("source"));copyManifestTree(sdk,sdkFiles,out.resolve("sdk"));
        Files.copy(sourceManifest,out.resolve("source.sha256"));Files.copy(sdkManifest,out.resolve("sdk.sha256"));
        Instant started=Instant.now();var cells=new ArrayList<Map<String,Object>>();
        JsonNode lanes=JSON.readTree(source.resolve("tools/samples/v019-scenarios.json").toFile()).get("qualificationLanes");
        need(lanes!=null&&lanes.size()==3);
        for(String provider:PROVIDERS)for(int laneIndex=0;laneIndex<LANES.size();laneIndex++){
            String lane=LANES.get(laneIndex);JsonNode contract=lanes.get(laneIndex);
            need(lane.equals(contract.path("id").asText())&&contract.path("maximumSecondsPerProvider").asInt()==900);
            stage=provider+"/"+lane;
            Path sample=work.resolve(provider).resolve(lane).resolve("sample-cache-database-"+provider);
            String prefix="sample-cache-database-"+provider+"/";var members=new TreeMap<String,String>();
            sourceFiles.forEach((name,digest)->{if(name.startsWith(prefix))members.put(name.substring(prefix.length()),digest);});
            need(members.containsKey("pom.xml"));copyManifestTree(source.resolve(prefix),members,sample);
            try(var channel=FileChannel.open(sample.resolve(".sample-runtime.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()){
                need(lock!=null);
                var cell=new LinkedHashMap<String,Object>();cell.put("provider",provider);cell.put("lane",lane);
                cell.put("sourceManifestSha256",sourcePin);cell.put("sdkManifestSha256",sdkPin);
                cell.put("tests",phase(provider,lane,contract,sample,members,false));
                cell.put("http",phase(provider,lane,contract,sample,members,true));cells.add(cell);
                write(out.resolve("completed-cell-"+cells.size()+".json"),cell);
            }
        }
        var receipt=new LinkedHashMap<String,Object>();receipt.put("schema",1);receipt.put("kind","cachedb-v019-sample-runtime");
        receipt.put("source",Map.of("root","source","manifest",ref(out.resolve("source.sha256"))));
        receipt.put("sdk",Map.of("root","sdk","manifest",ref(out.resolve("sdk.sha256")),"sourceManifestSha256",sourcePin));
        receipt.put("execution",Map.of("runId",get("runId"),"namespace",get("namespace"),"runnerPodUid",get("runnerPodUid"),
                "startedAt",receiptTime(started,false),"finishedAt",receiptTime(Instant.now(),true),"cutoffAt",receiptTime(cutoff,false),
                "cpuMillicores",Integer.parseInt(get("cpuMillicores")),"memoryBytes",Long.parseLong(get("memoryBytes"))));
        receipt.put("cells",cells);Path candidate=out.resolve("candidate-receipt.json");write(candidate,receipt);
        stage="raw-evidence-verifier";
        int code=run(List.of(get("node"),source.resolve("tools/ci/check-v019-sample-coverage.mjs").toString(),
                "--root",source.toString(),"--summary",source.resolve("target/sample-runtime-coverage.md").toString(),"--runtime-evidence",candidate.toString(),
                "--runtime-evidence-sha256",hash(candidate)),Map.of(),source,out.resolve("verifier.log"),deadline(300));
        Files.writeString(out.resolve("verifier.exit"),code+"\n");need(code==0);
        preserve();Files.move(candidate,out.resolve("receipt.json"));
        Files.writeString(out.resolve("receipt.sha256"),hash(out.resolve("receipt.json"))+"  receipt.json\n");
    }
    private void preflight()throws Exception{
        need(Runtime.version().feature()==21&&System.getenv("KUBERNETES_SERVICE_HOST")!=null);
        need(get("qualification").equals("PARENT_QUALIFIED_SOURCE_SDK92_TOOLS_SETTINGS"));
        need(get("namespace").equals("cachedb-v019-validation")&&get("runId").matches("[a-zA-Z0-9_-]{8,128}"));
        need(get("runnerPodUid").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                &&get("serviceRunnerPodUid").equals(get("runnerPodUid")));
        need(get("runnerPodUid").equals(System.getenv("POD_UID"))&&get("namespace").equals(System.getenv("POD_NAMESPACE")));
        need(get("redisOwnership").equals("FRESH_EXCLUSIVE_NO_FLUSH"));
        for(String key:List.of("redisServiceUid","redisPodUid"))
            need(get(key).matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
        URI origin=URI.create(get("serviceOrigin"));
        need(origin.getScheme().equals("http")&&origin.getHost()!=null&&origin.getHost().endsWith("."+get("namespace")+".svc.cluster.local")
                &&origin.getPort()>0&&origin.getUserInfo()==null&&origin.getQuery()==null&&origin.getFragment()==null&&origin.getPath().equals("/"));
        need(origin.getPort()==Integer.parseInt(get("applicationPort")));
        need(get("redisUri").equals(redis(0)));
        need(get("redisPasswordFile").equals("/var/run/cachedb-fixture/password"));
        redisPassword=readRedisPassword(path("redisPasswordFile"));
        need(Instant.now().plusSeconds(60).isBefore(cutoff));
        long memory=Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim());
        String[] cpu=Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim().split(" ");
        need(memory==Long.parseLong(get("memoryBytes"))&&memory<=4L*1024*1024*1024
                &&Long.parseLong(cpu[0])*1000/Long.parseLong(cpu[1])==Integer.parseInt(get("cpuMillicores"))
                &&Integer.parseInt(get("cpuMillicores"))<=2000);
        need(!source.startsWith(out)&&!out.startsWith(source)&&!sdk.startsWith(out)&&!out.startsWith(sdk));
        need(!work.startsWith(out)&&!out.startsWith(work)&&!work.startsWith(source)&&!source.startsWith(work));
        var tools=manifest(path("toolsManifest"),get("toolsManifestSha256"));
        for(String key:List.of("java","javac","maven","pwsh","node","npx","settings","globalSettings")){
            Path target=path(key).toRealPath();need(tools.containsKey(target.toString().substring(1)));toolTargets.put(key,target);
        }
        need(Files.isDirectory(path("npmCache"))&&Files.isDirectory(repository));
        var expected=new TreeSet<String>();var runtime=new TreeSet<Path>();
        Element root=pom(source.resolve("pom.xml"));need(direct(root,"groupId").equals("com.reactor.cachedb")&&direct(root,"version").equals("0.19.0"));
        var modules=children(root,"modules");need(modules.size()==1);
        var paths=new ArrayList<String>();paths.add("");
        for(var module:children(modules.getFirst(),"module"))paths.add(module.getTextContent().trim());
        need(paths.size()==25&&new HashSet<>(paths).size()==paths.size());
        for(String module:paths){
            need(module.isEmpty()||module.matches("[a-z0-9-]+"));Element element=pom(source.resolve(module).resolve("pom.xml"));
            String artifact=direct(element,"artifactId"),base="com/reactor/cachedb/"+artifact+"/0.19.0/"+artifact+"-0.19.0";
            need(artifact.matches("[a-z0-9-]+"));expected.add(base+".pom");
            if(!direct(element,"packaging").equals("pom")){
                expected.add(base+".jar");runtime.add(sdk.resolve(base+".jar"));
                if(!artifact.equals("cachedb-integration-tests")){expected.add(base+"-sources.jar");expected.add(base+"-javadoc.jar");}
            }
        }
        need(expected.size()==92&&expected.equals(sdkFiles.keySet()));
        var cp=manifest(path("classpathManifest"),get("classpathManifestSha256"));need(cp.size()<=256);
        Path cpRoot=path("classpathRoot");var found=new TreeSet<Path>();long entries=0;
        for(var member:cp.entrySet()){
            Path jar=safe(cpRoot,member.getKey());need(jar.toString().endsWith(".jar")&&hash(jar).equals(member.getValue()));
            if(runtime.contains(jar)){need(found.add(jar));continue;}
            need(!jar.startsWith(sdk));
            try(var file=new JarFile(jar.toFile())){
                var names=file.entries();while(names.hasMoreElements()){
                    String name=names.nextElement().getName();need(++entries<=1000000);
                    need(!name.contains("com/reactor/cachedb/")&&!name.contains("com/cachedb/"));
                }
            }
        }
        need(runtime.equals(found));preserve();
        for(String provider:List.of("postgres","mssql","oracle")){
            String url=get(provider+"AdminUrl");String service=provider.equals("postgres")?"postgres":provider;
            String prefix=switch(provider){case "postgres"->"jdbc:postgresql://";case "mssql"->"jdbc:sqlserver://";default->"jdbc:oracle:thin:@//";};
            int port=switch(provider){case "postgres"->5432;case "mssql"->1433;default->1521;};
            need(url.startsWith(prefix+service+"."+get("namespace")+".svc.cluster.local:"+port+(provider.equals("mssql")?";":"/"))
                    &&!url.toLowerCase(Locale.ROOT).contains("password"));
        }
        // Reject an unusable PowerShell runtime before creating any SQL fixture or running sample lanes.
        need(run(List.of(get("pwsh"),"-NoLogo","-NoProfile","-NonInteractive","-Command",
                "$PSVersionTable.PSVersion.ToString()"),Map.of(),work,out.resolve("powershell-version.log"),deadline(30))==0);
        need(supportedPowerShellVersion(Files.readString(out.resolve("powershell-version.log")).trim()));
        need(run(List.of(get("npx"),"--yes","--offline","--package","newman@6.2.1","newman","--version"),Map.of(),
                work,out.resolve("newman-version.log"),deadline(30))==0);
        need(Files.readString(out.resolve("newman-version.log")).trim().equals("6.2.1"));
        // A dedicated fresh Redis is mandatory. No reset, delete, adoption or previous-run replay.
        for(int db=0;db<=12;db++)try(var redis=connectRedis(redis(db),redisPassword)){
            need(redis.ping().equals("PONG")&&redis.dbSize()==0);
        }
    }
    private static boolean supportedPowerShellVersion(String version){
        if(!version.matches("[1-9][0-9]{0,3}\\.[0-9]{1,4}\\.[0-9]{1,4}"))return false;
        String[] parts=version.split("\\.");
        int major=Integer.parseInt(parts[0]),minor=Integer.parseInt(parts[1]);
        return major>7||(major==7&&minor>=3);
    }
    private Map<String,Object> phase(String provider,String lane,JsonNode contract,Path sample,SortedMap<String,String> members,boolean http)throws Exception{
        Path dir=out.resolve(provider).resolve(lane).resolve(http?"http":"tests");Files.createDirectories(dir);
        Instant started=Instant.now(),end=deadline(890);var phase=new LinkedHashMap<String,Object>();
        phase.put("startedAt",receiptTime(started,false));snapshot(dir,"Before",phase,sample,members);
        int code=1;String nativeProvider=provider.equals("postgresql")?"postgres":provider;
        try(var fixture=InitialSqlSandbox.empty(nativeProvider)){
            var data=fixture.dataSource();String url=data.getJdbcUrl(),scope;
            try(var connection=data.getConnection()){
                scope=provider.equals("mssql")?connection.getCatalog():connection.getSchema();
                if(provider.equals("postgresql"))url+=(url.contains("?")?"&":"?")+"currentSchema="+scope;
                if(provider.equals("mssql")){
                    need(url.matches("(?is).*;databaseName=master(?:;.*)?"));url=url.replaceFirst("(?i)(;databaseName=)master(?=;|$)","$1"+scope);
                }
            }
            String fixtureId=sha((provider+"\n"+url+"\n"+scope+"\n"+data.getUsername()).getBytes(StandardCharsets.UTF_8));
            need(fixtures.add(fixtureId));phase.put("fixtureId",fixtureId);Files.writeString(dir.resolve("fixture-id.sha256"),fixtureId+"\n");
            int slot=1+PROVIDERS.indexOf(provider)*4+(lane.equals("encrypted-notes")?2:0)+(http?1:0);
            String redis=redis(lane.equals("tenant-purge")?0:slot);
            var env=new HashMap<String,String>();env.put("CACHEDB_TEST_NAMESPACE",get("namespace"));
            env.put("CACHEDB_TEST_DATASOURCE_URL",url);env.put("CACHEDB_TEST_DATASOURCE_USERNAME",data.getUsername());
            env.put("CACHEDB_TEST_DATASOURCE_PASSWORD",data.getPassword());env.put("CACHEDB_TEST_REDIS_URI",redis);
            env.put("CACHEDB_TEST_REDIS_PASSWORD",redisPassword);
            if(http)code=http(lane,sample,dir,env,url,data.getUsername(),data.getPassword(),redis,end);
            else code=run(maven(contract),env,sample,dir.resolve("maven.log"),end);
        }catch(Exception|Error failure){code=1;throw failure;}finally{
            Files.writeString(dir.resolve("run.exit"),code+"\n");phase.put("exit",ref(dir.resolve("run.exit")));
            try{if(http)collectHttp(sample,dir,lane,phase);else collectTests(sample,dir,contract,phase);}
            finally{
                try{snapshot(dir,"After",phase,sample,members);Files.writeString(dir.resolve("preservation.exit"),"0\n");}
                catch(Exception failure){Files.writeString(dir.resolve("preservation.exit"),"1\n");throw failure;}
                phase.put("preservationExit",ref(dir.resolve("preservation.exit")));phase.put("finishedAt",receiptTime(Instant.now(),true));
                write(dir.resolve("phase.json"),phase);
            }
        }
        need(code==0&&Instant.now().isBefore(end));return phase;
    }
    private List<String> maven(JsonNode lane)throws Exception{
        return List.of(get("maven"),"-o","-B","-ntp","-C","-s",get("settings"),"-gs",get("globalSettings"),
                "-Dmaven.repo.local="+repository,"-DskipTests=false","-Dmaven.test.skip=false","-Dmaven.test.failure.ignore=false",
                "-DfailIfNoTests=true","-Dsurefire.failIfNoSpecifiedTests=true","-Dfailsafe.failIfNoSpecifiedTests=true",
                "-Dsurefire.rerunFailingTestsCount=0","-Dfailsafe.rerunFailingTestsCount=0","-DforkCount=1","-DreuseForks=true",
                "-Djunit.jupiter.execution.parallel.enabled=false","-DargLine=-Xms64m -Xmx512m -XX:ActiveProcessorCount=1",
                "-P"+lane.path("profiles").asText(),"-Dtest="+lane.path("surefire").asText(),"-Dit.test="+lane.path("failsafe").asText(),"verify");
    }
    private int http(String lane,Path sample,Path dir,Map<String,String> env,String url,String user,String password,String redis,Instant end)throws Exception{
        boolean purge=lane.equals("tenant-purge");String runId=UUID.randomUUID().toString().replace("-","");
        env.put("DB_URL",url);env.put("DB_USERNAME",user);env.put("DB_PASSWORD",password);
        env.put("REDIS_URI",authenticatedRedisUri(redis,redisPassword));
        env.put("SERVER_ADDRESS","0.0.0.0");env.put("SERVER_PORT",get("applicationPort"));
        env.put("SPRING_PROFILES_ACTIVE",lane.equals("encrypted-notes")?"demo,encryption":"demo");
        for(String token:List.of("SUPPLIER","TENANT_A","TENANT_B"))env.put("SAMPLE_CONTROLS_"+token+"_TOKEN",UUID.randomUUID().toString());
        env.put("SAMPLE_ENCRYPTION_AES_KEY",key());env.put("SAMPLE_ENCRYPTION_HMAC_KEY",key());
        env.put("SAMPLE_PURGE_SQL_URL",url);env.put("SAMPLE_PURGE_SQL_USERNAME",user);env.put("SAMPLE_PURGE_SQL_PASSWORD",password);
        env.put("SAMPLE_PURGE_REDIS_URI",redis);env.put("SAMPLE_PURGE_RUN_ID",runId);
        env.put("SAMPLE_PURGE_REDIS_PASSWORD",redisPassword);
        env.put("SAMPLE_PURGE_ADMIN_TOKEN",UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
        env.put("SAMPLE_PURGE_CONFIRM","CREATE_FRESH_PURGE_DEMO");env.put("SAMPLE_PURGE_BIND_ADDRESS","0.0.0.0");env.put("SAMPLE_PURGE_HTTP_PORT",get("applicationPort"));
        if(purge)env.remove("SPRING_PROFILES_ACTIVE");
        Path jar=sample.resolve("target/"+sample.getFileName()+"-0.19.0.jar");need(Files.isRegularFile(jar));
        var command=new ArrayList<>(List.of(get("java"),"-Xms64m","-Xmx512m","-XX:ActiveProcessorCount=1","-jar",jar.toString()));
        if(purge)command.add("--spring.config.name=application-purge");
        Process app=start(command,env,sample,dir.resolve("app.log"));
        try{
            boolean ready=false;
            for(int attempt=0;attempt<120&&!ready;attempt++){
                need(app.isAlive()&&Instant.now().isBefore(end));
                try{
                    var connection=(HttpURLConnection)URI.create(get("serviceOrigin")).toURL().openConnection();
                    connection.setConnectTimeout(500);connection.setReadTimeout(500);connection.setInstanceFollowRedirects(false);
                    try{ready=connection.getResponseCode()!=503;}finally{connection.disconnect();}
                }catch(IOException unavailable){/* Bounded readiness polling, never a lane replay. */}
                if(!ready)Thread.sleep(500);limits();
            }
            need(ready);String script=purge?"tenant-purge":lane.equals("encrypted-notes")?"encryption":"audit";
            var arguments=new ArrayList<>(List.of(get("pwsh"),"-NoLogo","-NoProfile","-NonInteractive","-File",
                    sample.resolve("scripts/test-"+script+"-postman.ps1").toString(),"-BaseUrl",get("serviceOrigin"),"-ConfirmDisposableDemo"));
            if(purge){arguments.add("-RunId");arguments.add(runId);arguments.add("-RedisServiceName");arguments.add(get("redisServiceName"));}
            int result=run(arguments,env,sample,dir.resolve("postman.log"),end);need(app.isAlive());return result;
        }finally{stop(app);}
    }
    private void collectTests(Path sample,Path dir,JsonNode contract,Map<String,Object> phase)throws Exception{
        var reports=new ArrayList<Map<String,String>>();
        for(String kind:List.of("surefire-reports","failsafe-reports"))copyReports(sample.resolve("target/"+kind),dir.resolve(kind));
        for(JsonNode suite:contract.path("suites")){
            String name=suite.path("file").asText().replaceFirst("^src/[^/]+/java/","").replace('/', '.').replaceFirst("\\.java$","");
            Path file=dir.resolve(name.endsWith("IT")?"failsafe-reports":"surefire-reports").resolve("TEST-"+name+".xml");
            if(Files.isRegularFile(file))reports.add(ref(file));
        }
        phase.put("reports",reports);
    }
    private void collectHttp(Path sample,Path dir,String lane,Map<String,Object> phase)throws Exception{
        boolean purge=lane.equals("tenant-purge");Path reports=sample.resolve("target/"+(purge?"tenant-purge-postman":"v019-postman"));
        copyReports(reports,dir.resolve("postman"));String base=lane.equals("encrypted-notes")?"encryption-results":"audit-results";
        for(String suffix:purge?List.of("newman.xml"):List.of(base+".xml",base+".json")){
            try(var paths=Files.walk(dir.resolve("postman"),4)){
                var found=paths.filter(path->path.getFileName().toString().equals(suffix)).toList();need(found.size()==1);
                phase.put(suffix.endsWith(".json")?"newman":"junit",ref(found.getFirst()));
            }
        }
    }
    private void snapshot(Path dir,String suffix,Map<String,Object> phase,Path sample,SortedMap<String,String> members)throws Exception{
        preserve();verify(sample,members);
        for(String kind:List.of("source","sdk")){
            Path file=dir.resolve(kind+"-"+suffix.toLowerCase(Locale.ROOT)+".sha256");Files.copy(kind.equals("source")?sourceManifest:sdkManifest,file);
            phase.put(kind+suffix,ref(file));
        }
    }
    private void preserve()throws Exception{
        need(hash(sourceManifest).equals(sourcePin)&&hash(sdkManifest).equals(sdkPin));verify(source,sourceFiles);verify(sdk,sdkFiles);verify(repository,sdkFiles);
        var tools=manifest(path("toolsManifest"),get("toolsManifestSha256"));verifyTools(Path.of("/"),tools);
        for(var target:toolTargets.entrySet())need(path(target.getKey()).toRealPath().equals(target.getValue()));
    }
    private int run(List<String> command,Map<String,String> env,Path cwd,Path log,Instant end)throws Exception{
        Process process=start(command,env,cwd,log);
        try{
            while(!process.waitFor(250,TimeUnit.MILLISECONDS)){need(Instant.now().isBefore(end));limits();}
            limits();return process.exitValue();
        }finally{stop(process);}
    }
    private static Process start(List<String> command,Map<String,String> env,Path cwd,Path log)throws Exception{
        var builder=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // Do not inherit arbitrary build profiles, JVM agents, proxies, or Spring overrides.
        var inherited=new HashMap<>(builder.environment());builder.environment().clear();
        for(String key:List.of("PATH","HOME","TMPDIR","LC_ALL","TZ","JAVA_HOME","MAVEN_OPTS","MAVEN_SKIP_RC","NODE_OPTIONS",
                "DOTNET_GCHeapHardLimit","npm_config_offline","npm_config_cache","npm_config_audit","npm_config_fund")){
            if(inherited.containsKey(key))builder.environment().put(key,inherited.get(key));
        }
        builder.environment().putAll(env);Process process=builder.start();ACTIVE.add(process);return process;
    }
    private void limits()throws Exception{
        auditFiles(List.of(work,out));
        try(var children=ProcessHandle.current().descendants()){need(children.limit(33).count()<=32);}
    }
    private static boolean vanished(IOException failure){return failure instanceof NoSuchFileException;}
    private static void auditFiles(List<Path> roots)throws Exception{
        long[] totals={0,0};
        for(Path root:roots){
            need(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS));
            Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),24,new SimpleFileVisitor<Path>(){
                private FileVisitResult inspect(java.nio.file.attribute.BasicFileAttributes attrs){
                    need(++totals[0]<=20000&&!attrs.isSymbolicLink());
                    if(attrs.isRegularFile()){need(attrs.size()<=128L*1024*1024);totals[1]+=attrs.size();need(totals[1]<=2L*1024*1024*1024);}
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs){return inspect(attrs);}
                @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs){return inspect(attrs);}
                @Override public FileVisitResult visitFileFailed(Path file,IOException failure)throws IOException{
                    // Child processes remove temporary entries during the bounded audit. All other I/O failures stay fatal.
                    if(!vanished(failure))throw failure;return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure)throws IOException{
                    if(failure!=null&&!vanished(failure))throw failure;return FileVisitResult.CONTINUE;
                }
            });
            need(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS));
        }
    }
    private static String receiptTime(Instant value,boolean finish){
        // Millisecond receipt bounds enclose real execution; the cutoff is never rounded up.
        Instant rounded=value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return (finish&&!rounded.equals(value)?rounded.plusMillis(1):rounded).toString();
    }
    private Instant deadline(int seconds)throws Exception{Instant end=Instant.now().plusSeconds(seconds),reserved=cutoff.minusSeconds(10);need(Instant.now().isBefore(reserved));return end.isBefore(reserved)?end:reserved;}
    private static void stopRunning(){for(Process process:ACTIVE)stop(process);}
    private static void stop(Process process){
        var children=process.descendants().toList();children.reversed().forEach(ProcessHandle::destroy);process.destroy();
        try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        children.reversed().forEach(child->{if(child.isAlive())child.destroyForcibly();});if(process.isAlive())process.destroyForcibly();
        try{process.waitFor(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        ACTIVE.remove(process);
    }
    private void copyReports(Path from,Path to)throws Exception{
        Files.createDirectories(to);if(!Files.exists(from))return;
        try(var paths=Files.walk(from,8)){
            for(Path file:paths.filter(Files::isRegularFile).toList()){
                need(!Files.isSymbolicLink(file)&&Files.size(file)<=32L*1024*1024);Path target=to.resolve(from.relativize(file));
                Files.createDirectories(target.getParent());Files.copy(file,target);limits();
            }
        }
    }
    private static void copyManifestTree(Path root,SortedMap<String,String> members,Path to)throws Exception{
        long bytes=0;need(members.size()<=10000);
        for(String member:members.keySet()){
            Path from=safe(root,member);bytes+=Files.size(from);need(bytes<=512L*1024*1024);
            Path file=to.resolve(member);Files.createDirectories(file.getParent());Files.copy(from,file);
        }
    }
    private static SortedMap<String,String> manifest(Path file,String pin)throws Exception{
        need(Files.size(file)<=2097152&&hash(file).equals(pin));var result=new TreeMap<String,String>();
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
            need(line.matches("[0-9a-f]{64}  [^\\r\\n]+"));String member=line.substring(66);
            need(!member.contains("..")&&!member.contains("\\")&&!member.contains(":")&&!member.startsWith("/")&&!member.startsWith("./"));
            need(result.putIfAbsent(member,line.substring(0,64))==null&&result.size()<=10000);
        }
        need(!result.isEmpty());return result;
    }
    private static Path safe(Path root,String member)throws Exception{
        return safe(root,member,MAX_INPUT_BYTES);
    }
    private static Path safe(Path root,String member,long maximumBytes)throws Exception{
        Path file=root.resolve(member);need(file.toRealPath().equals(file.toAbsolutePath())&&Files.isRegularFile(file)&&Files.size(file)<=maximumBytes);return file;
    }
    private static void verify(Path root,SortedMap<String,String> members)throws Exception{
        for(var member:members.entrySet())need(hash(safe(root,member.getKey())).equals(member.getValue()));
    }
    private static void verifyTools(Path root,SortedMap<String,String> members)throws Exception{
        // Pinned native tools can exceed the source/SDK limit. Bound the whole tree before hashing.
        long bytes=0;
        for(String member:members.keySet()){
            bytes+=Files.size(safe(root,member,MAX_TOOL_BYTES));need(bytes<=MAX_TOOL_TREE_BYTES);
        }
        for(var member:members.entrySet())need(hash(safe(root,member.getKey(),MAX_TOOL_BYTES)).equals(member.getValue()));
    }
    private Map<String,String> ref(Path file)throws Exception{return Map.of("path",out.relativize(file).toString().replace('\\','/'),"sha256",hash(file));}
    private static String hash(Path file)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String key(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    private String redis(int database)throws Exception{
        String service=get("redisServiceName");need(service.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")&&database>=0&&database<=12);
        return "redis://"+service+"."+get("namespace")+".svc.cluster.local:6379/"+database;
    }
    private static String readRedisPassword(Path file)throws Exception{
        // Secret projections use symlinks. Bound the read itself, not a racy size probe.
        byte[] bytes;
        try(var input=Files.newInputStream(file)){bytes=input.readNBytes(1025);}
        try{
            need(bytes.length<=1024);int end=bytes.length;
            if(end>0&&bytes[end-1]=='\n'){end--;if(end>0&&bytes[end-1]=='\r')end--;}
            need(end>=16&&end<=512);
            for(int i=0;i<end;i++)need(bytes[i]>=33&&bytes[i]<=126);
            return new String(bytes,0,end,StandardCharsets.US_ASCII);
        }finally{Arrays.fill(bytes,(byte)0);}
    }
    private static URI redisEndpoint(String origin){
        URI uri=URI.create(origin);
        need("redis".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getPort()==6379
                &&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null
                &&uri.getRawPath().matches("/(?:[0-9]|1[0-2])"));
        return uri;
    }
    private static Jedis connectRedis(String origin,String password){
        URI uri=redisEndpoint(origin);need(password!=null&&!password.isEmpty());
        return new Jedis(new HostAndPort(uri.getHost(),uri.getPort()),DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(2000).socketTimeoutMillis(2000)
                .database(Integer.parseInt(uri.getPath().substring(1))).password(password).build());
    }
    private static String authenticatedRedisUri(String origin,String password){
        URI uri=redisEndpoint(origin);need(password!=null&&!password.isEmpty());
        String encoded=URLEncoder.encode(password,StandardCharsets.UTF_8).replace("+","%20");
        return "redis://default:"+encoded+"@"+uri.getRawAuthority()+uri.getRawPath();
    }
    private String get(String key)throws Exception{String value=config.getProperty(key);need(value!=null&&!value.isBlank()&&value.length()<=4096);return value;}
    private Path path(String key)throws Exception{Path value=Path.of(get(key));need(value.isAbsolute()&&value.normalize().equals(value));return value;}
    private static Element pom(Path path)throws Exception{
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }
    private static String direct(Element element,String name){
        for(var child=element.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element field&&field.getTagName().equals(name))return field.getTextContent().trim();return "";
    }
    private static List<Element> children(Element element,String name){
        var result=new ArrayList<Element>();
        for(var child=element.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element field&&field.getTagName().equals(name))result.add(field);
        return result;
    }
    private static void write(Path file,Object value)throws Exception{Files.write(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}
    private static void need(boolean condition){if(!condition)throw new IllegalStateException("Sample runtime admission/execution contract rejected");}
}
