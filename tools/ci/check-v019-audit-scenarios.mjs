import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { javaMask, javaMethod } from './check-v019-sample-coverage.mjs';

const MAIN='src/main/java/com/example/cachedb/sample/audit/';
const TEST='src/test/java/com/example/cachedb/sample/audit/';
const demand=(ok,message)=>{if(!ok)throw new Error(message);};
const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const equal=(a,b)=>JSON.stringify(stable(a))===JSON.stringify(stable(b));
const escape=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
function calls(method,names){
 for(const name of names)demand(new RegExp('\\b'+name.split('.').map(escape).join('\\s*\\.\\s*')+'\\s*(?:<[^;{}()]*>)?\\s*\\(').test(method.maskedBody),'Missing executable call '+method.name+' -> '+name);

}
function route(source,method){
 const root=source.match(/@RequestMapping\s*\(\s*"([^"]+)"\s*\)/);
 const local=method.prefix.match(/@(Get|Post|Put|Delete)Mapping\s*\(\s*"([^"]+)"\s*\)/);
 demand(root&&local,'Missing concrete controller mapping '+method.name);
 return {verb:local[1].toUpperCase(),path:root[1]+local[2]};
}
function routeMatches(template,actual){
 const expression=template.split(/(\{[^}]+\})/).map(s=>s.startsWith('{')?'[^/]+':escape(s)).join('');
 return new RegExp('^'+expression+'$').test(actual);
}
function scripts(item,listen){return(item.event||[]).filter(e=>e.listen===listen).flatMap(e=>e.script.exec||[]);}
function all(items){return items.flatMap(i=>i.request?[i]:all(i.item||[]));}
export function validateAudit(read,parse=s=>new vm.Script(s)){
 const manifest=JSON.parse(read('tools/samples/v019-audit-scenarios.json')),rows=[];
 demand(manifest.schema===1&&manifest.cases.length===42&&manifest.scenarios.length===6,'Audit manifest schema/count differs');
 demand(equal(manifest.providers,['postgresql','mssql','oracle'])&&manifest.runtimeQualified===false,'Static evidence must not imply runtime acceptance');
 demand(equal(manifest.limits,{slotsPerPolicy:8,bodyBytes:2048,activeRequests:8,maxSweepRows:8,commandKeys:512,commandBytes:65536}),'Audit limits differ');
 for(const requirement of manifest.native)calls(javaMethod(read(requirement.file),requirement.method),requirement.calls);
 for(const provider of manifest.providers){
  const root='sample-cache-database-'+provider,controller=read(root+'/'+MAIN+'AuditNoteController.java');
  const unit=read(root+'/'+TEST+'AuditContractTest.java'),http=read(root+'/'+TEST+'AuditHttpContractTest.java'),external=read(root+'/'+TEST+'AuditExternalIT.java');
  demand(controller.includes('@Profile("demo")')&&controller.includes('@ConditionalOnProperty(name = "sample.audit-api.enabled", havingValue = "true")'),'Controller feature/profile isolation differs');
  for(const scenario of manifest.scenarios){
   calls(javaMethod(controller,scenario.method),scenario.calls);
   for(const [method,names]of scenario.extra)calls(javaMethod(controller,method),names);
   const unitMethod=javaMethod(unit,scenario.test),externalMethod=javaMethod(external,scenario.external);
   demand((unitMethod.prefix+unitMethod.signature).includes('@Test')&&(externalMethod.prefix+externalMethod.signature).includes('@Test'),'Scenario must link actual unit and provider test methods');
   demand(/\bassert(?:Equals|True|False|Throws)\s*\(/.test(unitMethod.maskedBody)&&/\bassert(?:Equals|True|False)\s*\(/.test(externalMethod.maskedBody),'Scenario tests have no executable assertions');
   for(const file of ['README.md','README.tr.md']){
    const guide=read(root+'/'+file),matrix=guide.split('<!-- BEGIN AUDIT API MATRIX -->')[1]?.split('<!-- END AUDIT API MATRIX -->')[0];
    demand(matrix&&matrix.includes('AuditNoteController.'+scenario.method)||scenario.id==='supplier-tenant-causal'&&matrix?.includes('AuditNoteRules.verify'),'Guide lacks source-linked scenario '+scenario.id);
    demand(matrix.includes(scenario.request),'Guide lacks matching saved request '+scenario.id);
    demand(guide.includes('auditApiEnabled=true')&&guide.includes('auditApiCertification=true')&&guide.includes('spring-boot.run.profiles=demo'),'Guide startup/gates missing');
    if(file==='README.tr.md')demand(guide===guide.normalize('NFC')&&/[ğışöüİ]/.test(matrix),'Turkish guide must retain native Unicode');
   }
   rows.push({provider,scenario:scenario.id,status:'COVERED_STATIC'});
  }
  const iface=javaMethod(read(root+'/'+MAIN+'DeleteAuditNotes.java'),'deleteByState');
  demand(/List<DeleteAuditNote>/.test(iface.signature)&&/String state/.test(iface.parameters),'Derived method must use actual List<Entity> API, not fabricated receipt return');
  const capacityCheck=javaMethod(controller,'springData');
  calls(capacityCheck,['entityQueryCapacity','kind','maxRows','deleteByState']);
  demand(capacityCheck.body.indexOf('entityQueryCapacity')<capacityCheck.body.indexOf('deleteByState')
      &&capacityCheck.body.includes('Kind.FINITE')&&capacityCheck.body.includes('maxRows() == 0')
      &&capacityCheck.body.includes('AUDIT_DERIVED_DELETE_ZERO_QUERY_BUDGET'),'Zero-budget denial must precede generated selection');
  const positive=manifest.positiveDerivedDelete;
  demand(positive&&positive.method==='deleteByRunIdAndStatus','Positive derived-delete contract missing');
  const positiveRepository=read(root+'/'+positive.repository),positiveSource=read(root+'/'+positive.providerTest);
  const derived=javaMethod(positiveRepository,positive.method);
  demand(/List<RangeDataRow>/.test(derived.signature)&&/String runId, String status/.test(derived.parameters)
      &&positiveRepository.includes('@CacheRepository(entity = RangeDataRow.class, springBean = false)'),'Positive selection must use actual generated RangeDataRow repository');
  for(const [name,required]of [
      [positive.success,['deleteByRunIdAndStatus','withTenant','awaitDurable','sourceCount','assertEquals','findHotById']],
      [positive.capacityRejection,['deleteByRunIdAndStatus','assertThrows','sourceCount','findAll','assertEquals']]]){
   const proof=javaMethod(positiveSource,name);
   demand((proof.prefix+proof.signature).includes('@Test'),'Positive derived-delete provider proof must be a test');
   calls(proof,required);
  }
  calls(javaMethod(positiveSource,'sourceCount'),['queryForObject']);
  for(const guide of ['README.md','README.tr.md'])demand(read(root+'/'+guide).includes(positive.providerTest),'Guide must link positive provider proof');
  calls(javaMethod(controller,'partial'),['failure.accepted','failure.rejected','failure.uncertain','failure.cleanupPending','failure.notAttempted']);
  const deleteShape=controller.match(/public record DeleteOutcome\(([^)]*)\)/s);
  demand(deleteShape&&['selected','accepted','rejected','uncertain','cleanupPending','notAttempted','receiptAvailability','sqlDurabilityConfirmed','retrySafe'].every(field=>new RegExp('\\b'+field+'\\b').test(deleteShape[1])),'Partial-outcome DTO lost fields');
  const config=read(root+'/'+MAIN+'AuditConfiguration.java'),factory=javaMethod(config,'target');
  calls(factory,['RedisRetentionAdapter','RedisFunctionArgsMapper','RedisProducerGuard','runtimeCodec','cachePolicy','register','withNearInvalidationPublisher','publishLegacyNearInvalidations'].filter(name=>name!=='publishLegacyNearInvalidations'));
  demand(factory.body.includes('database::publishLegacyNearInvalidations')&&factory.body.includes('512, 65536, 15000, false'),'Actual publisher/budgets/ordinary generation contract missing');
  demand(!/new\s+(?:Jedis|Thread|Scheduled)/.test(factory.maskedBody),'Adapter must borrow native ownership, not create clients/workers');
  calls(javaMethod(read(root+'/'+MAIN+'AuditNoteRules.java'),'verify'),['access.supplier','CacheCausalContext.hasRequirements','CacheTenantContext.currentTenant']);
  const filter=javaMethod(read(root+'/'+MAIN+'AuditRequestFilter.java'),'doFilterInternal');
  calls(filter,['AuditNoteRules.verify','tryAcquire','readNBytes','doFilter','release']);
  demand(filter.body.indexOf('AuditNoteRules.verify')<filter.body.indexOf('readNBytes')&&filter.body.includes('2049'),'Authorize before bounded chunked-body read');
  for(const [type,action]of [['EvictAuditNote','EVICT_CACHE'],['DeleteAuditNote','DELETE']]){
   const entity=read(root+'/'+MAIN+type+'.java');
   demand(entity.includes('@CacheRetention(field = "createdOn", days = 1, action = RetentionAction.'+action+')'),'Actual retention annotation differs');
   demand(entity.includes('LocalDate createdOn')&&!entity.includes('@CacheTenantId')&&!entity.includes('@CacheEncrypted'),'Audit entity must remain ordinary/global');
   const demo=read(root+'/src/main/resources/application-demo.yml');
   const block=demo.split('      '+type+':')[1]?.split(/\n      [A-Z]/)[0];
   demand(block&&block.includes('hot-entity-limit: 8')&&block.includes('page-size: 8')&&block.includes('entity-ttl-seconds: 1800'),'Unbounded audit hot-set configuration');
  }
  demand(/audit-api:\n\s+enabled: false/.test(read(root+'/src/main/resources/application.yml'))&&/audit-api:\n\s+enabled: true/.test(read(root+'/src/main/resources/application-demo.yml')),'Production/demo gate isolation differs');
  demand(read(root+'/src/main/resources/application-demo.yml').includes('classpath:schema-audit.sql')&&read(root+'/src/encryption/resources/application-encryption.yml').includes('optional:classpath*:schema-audit.sql'),'Demo schema union missing');
  demand((unit.match(/@Test\b/g)||[]).length===8&&(http.match(/@Test\b/g)||[]).length===6&&(external.match(/@Test\b/g)||[]).length===5,'Authored test-definition counts differ');
  calls(javaMethod(external,'sqlCount'),['queryForObject']);
  calls(javaMethod(external,'actualBulkHttpReceiptsConfirmOnlyRedisUntilMatchingSqlMarkersAreAwaited'),['awaitDurable','sqlCount']);
  const collection=JSON.parse(read(root+'/postman/cache-database-'+provider+'-audit.postman_collection.json'));
  const requests=all(collection.item);
  demand(requests.length===42&&new Set(requests.map(r=>r.name)).size===42,'Audit saved requests must be unique and complete');
  const gate=scripts(collection,'prerequest').join('\n');
  demand(gate.includes("auditApiEnabled")&&gate.includes("auditApiCertification")&&gate.includes('throw new Error')&&!gate.includes('skipRequest'),'Audit certification gate must fail, not skip');
  parse(gate);
  for(let i=0;i<requests.length;i++){
   const item=requests[i],expected=manifest.cases[i],method=javaMethod(controller,expected.controllerMethod),mapping=route(controller,method);
   demand(item.name===expected.name&&item.request.method===expected.method&&item.request.url==='{{baseUrl}}/api/examples/v019/audit-notes/'+expected.path,'Saved request changed '+expected.id);
   demand(mapping.verb===expected.method&&routeMatches(mapping.path,'/api/examples/v019/audit-notes/'+expected.path),'Saved request has no actual controller route '+expected.id);
   demand(equal(item.request.body?JSON.parse(item.request.body.raw):undefined,expected.body),'Body differs '+expected.id);
   const test=scripts(item,'test');
   demand(test[0]==="pm.test('HTTP "+expected.status+"', function () { pm.response.to.have.status("+expected.status+"); });",'Exact executed HTTP assertion missing '+expected.id);
   for(const assertion of expected.assertions||[])demand(test.includes(assertion),'Semantic assertion missing '+expected.id);
   demand(!test.join('\n').includes('skipRequest'),'Skipped acceptance is forbidden');parse(test.join('\n'));
  }
  for(const [suffix,count]of [['sample',84],['range-data',37],['encryption',10]])demand(all(JSON.parse(read(root+'/postman/cache-database-'+provider+'-'+suffix+'.postman_collection.json')).item).length===count,'Existing collection lost requests '+suffix);
  const runner=read(root+'/scripts/test-audit-postman.ps1'),validator=read(root+'/scripts/assert-postman-execution.ps1');
  demand(runner.includes('auditApiEnabled=true')&&runner.includes('auditApiCertification=true')&&runner.includes('-Kind Audit')&&runner.includes('if ($exitCode -ne 0)'),'Runner must reject disabled and failed execution');
  demand(validator.includes('Audit=42')&&validator.includes('$assertion.skipped')&&validator.includes('$report.run.failures'),'Evidence validator must reject skipped/failed/missing execution');
 }
 return{evidence:manifest.evidence,rows,deferred:manifest.deferred,runtimeQualified:false};
}
function main(){
 const root=path.resolve(process.argv[2]||process.cwd());
 const result=validateAudit(p=>fs.readFileSync(path.join(root,p),'utf8').replace(/\r\n/g,'\n'));
 process.stdout.write(JSON.stringify(result,null,2)+'\n');
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main();
