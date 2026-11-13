import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { javaMask, javaMethod } from './check-v019-sample-coverage.mjs';

const base='src/encryption/java/com/example/cachedb/sample/encryption/';
const test='src/encryption-test/java/com/example/cachedb/sample/encryption/';
const requireProof=(condition,message)=>{if(!condition)throw new Error(message);};
const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const same=(a,b)=>JSON.stringify(stable(a))===JSON.stringify(stable(b));
const call=(body,name)=>new RegExp('\\b'+name.replaceAll('.','\\.')+'\\s*\\(').test(body);
export function validateEncryption(read,parse=s=>new vm.Script(s)){
 const manifest=JSON.parse(read('tools/samples/v019-encryption-scenarios.json')),rows=[];
 requireProof(manifest.schema===1&&manifest.scenarios.length===3&&manifest.cases.length===10,'Encryption contract schema/count');
 for(const provider of ['postgresql','mssql','oracle']){
  const root='sample-cache-database-'+provider;
  const controller=read(root+'/'+base+'EncryptedNoteController.java');
  requireProof(controller.includes('@Profile("demo & encryption")')&&controller.includes('@RequestMapping("/api/examples/v019/encryption/notes")'),'Actual encryption route/profile missing');
  for(const scenario of manifest.scenarios){
   const method=javaMethod(controller,scenario.method);
   requireProof(method.prefix.includes('@'+({PUT:'Put',GET:'Get',POST:'Post'}[scenario.verb])+'Mapping("'+scenario.mapping+'")'),'Wrong REST mapping '+scenario.id);
   for(const name of scenario.calls)requireProof(call(method.maskedBody,name),'Missing executable call '+name);
   const relevant=manifest.cases.filter(c=>c.request.method===scenario.verb);
   requireProof(relevant.length===scenario.count,'Scenario request count differs '+scenario.id);
   for(const guide of ['README.md','README.tr.md']){
    const text=read(root+'/'+guide);
    const label='| '+scenario.id+' | '+scenario.verb+' /api/examples/v019/encryption/notes'+scenario.mapping+' |';
    requireProof(text.includes(label)&&text.includes('[EncryptedNoteController.'+scenario.method+']('+base+'EncryptedNoteController.java) | '+scenario.count+' |'),'Guide/source/request matrix differs');
    requireProof(text.includes('-Pencrypted-demo clean spring-boot:run')&&text.includes('demo,encryption')&&text.includes('SAMPLE_ENCRYPTION_HMAC_KEY'),'Incomplete startup guide');
    if(guide==='README.tr.md')requireProof(text.normalize('NFC')===text,'Turkish NFC mismatch');
   }
   rows.push({provider,scenario:scenario.id,status:'COVERED_STATIC'});
  }
  requireProof(/\bpublic\s+record\s+Input\s*\(\s*String\s+note\s*\)/.test(javaMask(controller)),'JSON body does not match actual DTO');
  const guard=javaMethod(controller,'verify').maskedBody;
  for(const name of ['access.supplier','CacheCausalContext.hasRequirements','CacheTenantContext.currentTenant'])
   requireProof(call(guard,name),'Missing identity/context guard '+name);
  const entity=read(root+'/'+base+'EncryptedNote.java');
  requireProof(entity.includes('@CacheEncrypted(keyRing = "sample-notes", scope = EncryptionScope.REDIS_ONLY)')&&entity.includes('indexedColumns = {}'),'Wrong field encryption contract');
  const config=javaMethod(read(root+'/'+base+'EncryptionConfiguration.java'),'demoNoteKeys').maskedBody;
  for(const name of ['environment.getProperty','KeyMaterial.fromBase64'])requireProof(call(config,name),'Missing actual key-provider binding '+name);
  requireProof(/\bnew\s+StaticKeyProvider\s*\(/.test(config),'No actual StaticKeyProvider construction');
  const filter=javaMethod(read(root+'/'+base+'EncryptedNoteRequestFilter.java'),'doFilterInternal').maskedBody;
  for(const name of ['EncryptedNoteController.verify','admission.tryAcquire','admission.release'])requireProof(call(filter,name),'Missing bounded request guard '+name);
  requireProof(filter.indexOf('verify')<filter.indexOf('getInputStream'),'Body read before verified identity');
  const proof=read(root+'/'+test+'EncryptedNoteExternalIT.java');
  requireProof(javaMask(proof).includes('@SpringBootTest')&&!/@(?:MockBean|MockitoBean)/.test(javaMask(proof)),'At-rest proof must use the real application');
  for(const name of ['actualRedisCiphertextAndProviderSqlPlaintextRoundTripThroughTheBoundRuntime','actualHttpUsesSupplierIdentityAndExplicitBoundedSourceConfirmation']){
   const method=javaMethod(proof,name);
   requireProof((method.prefix+method.signature).includes('@Test')&&/\bassert(?:Equals|True|False|NotNull)\s*\(/.test(method.maskedBody),'Missing concrete external assertion '+name);
  }
  const atRest=javaMethod(proof,'actualRedisCiphertextAndProviderSqlPlaintextRoundTripThroughTheBoundRuntime').maskedBody;
  for(const name of ['notes.saveDurably','redis.get','codec.fromRedisValue','jdbc.queryForObject'])requireProof(call(atRest,name),'Missing actual Redis/SQL proof '+name);
  const pom=read(root+'/pom.xml'),profile=/<profile>\s*<id>encrypted-demo<\/id>([\s\S]*?)<\/profile>/.exec(pom)?.[1];
  requireProof(profile&&profile.includes('<goal>add-source</goal>')&&profile.includes('<source>src/encryption/java</source>')&&
   profile.includes('<goal>add-test-source</goal>')&&profile.includes('<source>src/encryption-test/java</source>')&&
   profile.includes('<directory>src/encryption/resources</directory>'),'Encrypted metadata must be isolated to an explicit build profile');
  const collection=JSON.parse(read(root+'/postman/cache-database-'+provider+'-encryption.postman_collection.json'));
  requireProof(same(collection.item,manifest.cases)&&same(collection.event,manifest.gate),'Saved request/body/assertion contract differs');
  requireProof(!JSON.stringify(collection.event).includes('skipRequest')&&JSON.stringify(collection.event).includes('throw new Error'),'Skipped encryption certification is not allowed');
  for(const item of collection.item){
   const scripts=item.event.filter(e=>e.listen==='test').flatMap(e=>e.script.exec);
   parse(scripts.join('\n'));
   requireProof(scripts.some(s=>/pm\.test\('HTTP \d{3}'.*pm\.response\.to\.have\.status\(\d{3}\)/.test(s)),'Missing executed exact HTTP assertion');
   if(item.request.body)requireProof(same(Object.keys(JSON.parse(item.request.body.raw)),['note']),'Unexpected request-body fields');
  }
  const count=items=>items.reduce((n,i)=>n+(i.request?1:count(i.item||[])),0);
  requireProof(count(JSON.parse(read(root+'/postman/cache-database-'+provider+'-sample.postman_collection.json')).item)===84,'Business 61+23 contract lost');
  requireProof(count(JSON.parse(read(root+'/postman/cache-database-'+provider+'-range-data.postman_collection.json')).item)===37,'Range/data 37 contract lost');
  const runner=read(root+'/scripts/test-encryption-postman.ps1'),validator=read(root+'/scripts/assert-postman-execution.ps1');
  requireProof(runner.includes('encryptionApiEnabled=true')&&runner.includes('encryptionApiCertification=true')&&runner.includes('-Kind Encryption'),'Runner does not require the feature gate');
  requireProof(validator.includes('Encryption=10')&&validator.includes('$assertion.skipped')&&validator.includes('$report.run.failures'),'Runner cannot accept skipped or failed evidence');
 }
 return {evidence:manifest.evidence,rows,deferred:manifest.deferred,runtimeQualified:false};
}
function main(){
 const root=path.resolve(process.argv[2]||process.cwd());
 const result=validateEncryption(p=>fs.readFileSync(path.join(root,p),'utf8').replace(/\r\n/g,'\n'));
 process.stdout.write(JSON.stringify(result,null,2)+'\n');
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main();
