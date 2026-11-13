import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { sampleRuntimeContracts, verifySampleRuntime } from './verify-v019-sample-runtime.mjs';

const START = '<!-- BEGIN CACHE API MATRIX -->', END = '<!-- END CACHE API MATRIX -->';
const MAIN = 'src/main/java/com/example/cachedb/sample/';
const TEST = 'src/test/java/com/example/cachedb/sample/';
const stable = v => Array.isArray(v) ? v.map(stable) : v && typeof v === 'object' ?
    Object.fromEntries(Object.keys(v).sort().map(k => [k,stable(v[k])])) : v;
const canonical = v => JSON.stringify(stable(v));
const escaped = v => v.replace(/[.*+?^$()|[\]\\]/g,'\\$&');
const check = (ok,message) => { if (!ok) throw new Error(message); };

export function javaMask(source) {
    let state='code', output='';
    for (let i=0;i<source.length;i++) {
        const c=source[i],n=source[i+1];
        if (state==='line') { output+=c==='\n'?'\n':' '; if(c==='\n')state='code'; }
        else if (state==='block') {
            if(c==='*'&&n==='/'){output+='  ';i++;state='code';}else output+=c==='\n'?'\n':' ';
        } else if (['string','char','text'].includes(state)) {
            if(c==='\\'){output+='  ';i++;}
            else if(state==='text'&&source.slice(i,i+3)==='"""'){output+='   ';i+=2;state='code';}
            else if((state==='string'&&c==='"')||(state==='char'&&c==="'")){output+=' ';state='code';}
            else output+=c==='\n'?'\n':' ';
        } else if(c==='/'&&n==='/'){output+='  ';i++;state='line';}
        else if(c==='/'&&n==='*'){output+='  ';i++;state='block';}
        else if(source.slice(i,i+3)==='"""'){output+='   ';i+=2;state='text';}
        else if(c==='"'||c==="'"){output+=' ';state=c==='"'?'string':'char';}
        else output+=c;
    }
    check(['code','line'].includes(state),'Unterminated Java literal/comment');
    return output;
}
function endOf(mask,start,open,close) {
    check(mask[start]===open,'Invalid delimiter start');let depth=0;
    for(let i=start;i<mask.length;i++){
        if(mask[i]===open)depth++;
        else if(mask[i]===close){depth--;if(depth===0)return i;}
    }
    throw new Error('Unterminated Java delimiter');
}
function parameterCount(parameters){
    const text=javaMask(parameters);if(!text.trim())return 0;
    let angle=0,paren=0,square=0,count=1;
    for(const c of text){
        if(c==='<')angle++;else if(c==='>')angle--;
        else if(c==='(')paren++;else if(c===')')paren--;
        else if(c==='[')square++;else if(c===']')square--;
        else if(c===','&&angle===0&&paren===0&&square===0)count++;
    }
    return count;
}
export function javaMethod(source,name,arity) {
    const mask=javaMask(source),found=[];
    const pattern=new RegExp('^[ \\t]*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|synchronized|default)\\s+)*'+
        '(?:<[^>\\n]+>\\s+)?[A-Za-z_$][\\w<>,.?\\[\\] \\t]*\\s+'+escaped(name)+'\\s*\\(','gm');
    for(const match of mask.matchAll(pattern)){
        if(['return','throw','new','if','while','switch','case'].includes(match[0].trim().split(/\s+/)[0]))continue;
        const open=mask.indexOf('(',match.index),close=endOf(mask,open,'(',')');
        let body=close+1;while(body<mask.length&&/\s/.test(mask[body]))body++;
        if(mask.slice(body).startsWith('throws ')){body=mask.indexOf('{',body);if(body<0)continue;}
        if(!['{',';'].includes(mask[body]))continue;
        const end=mask[body]==='{'?endOf(mask,body,'{','}'):body;
        const prefix=Math.max(mask.lastIndexOf('}',match.index),mask.lastIndexOf(';',match.index))+1;
        found.push({name,signature:source.slice(match.index,close+1),parameters:source.slice(open+1,close),
            prefix:source.slice(prefix,match.index),body:mask[body]==='{'?source.slice(body+1,end):'',
            maskedBody:mask[body]==='{'?mask.slice(body+1,end):''});
    }
    const selected=arity===undefined?found:found.filter(m=>parameterCount(m.parameters)===arity);
    check(selected.length===1,'Expected one Java method '+name+', found '+selected.length);return selected[0];
}
function hasCall(body,call){
    return new RegExp('(?:^|[^\\w.])'+call.split('.').map(escaped).join('\\s*\\.\\s*')+'\\s*\\(').test(body);
}
function calls(source,method,names){
    const value=javaMethod(source,method);
    for(const name of names)check(hasCall(value.maskedBody,name),'Missing executable call '+method+' -> '+name);
    check(!/\b(?:TODO|UnsupportedOperationException)\b/.test(value.maskedBody),'Unimplemented method '+method);
    return value;
}
function requests(items,depth=0){
    check(depth<=8,'Postman nesting exceeds eight');
    return items.flatMap(item=>item.request?[item]:requests(item.item||[],depth+1));
}
function scripts(item,listen){return(item.event||[]).filter(e=>e.listen===listen).flatMap(e=>e.script.exec||[]);}
function requestContract(item){return{name:item.name,request:item.request,test:scripts(item,'test'),prerequest:scripts(item,'prerequest')};}
function routeOf(source,method){
    const root=[...source.matchAll(/@RequestMapping\s*\(\s*"([^"]+)"\s*\)/g)][0];
    const map=[...method.prefix.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping\s*\(\s*"([^"]*)"\s*\)/g)];
    check(root&&map.length===1,'Expected class/method REST mapping: '+method.name);
    return{verb:map[0][1].toUpperCase(),route:root[1]+map[0][2]};
}
function routeRegex(route){
    return new RegExp('^'+route.split(/(\{[A-Za-z_][A-Za-z0-9_]*\})/g)
        .map(p=>p.startsWith('{')?'[^/]+':escaped(p)).join('')+'$');
}
function bodyFields(source,name){
    const mask=javaMask(source),match=new RegExp('\\brecord\\s+'+escaped(name)+'\\s*\\(').exec(mask);
    check(match,'Missing DTO record '+name);
    const open=mask.indexOf('(',match.index),close=endOf(mask,open,'(',')');
    let text=source.slice(open+1,close);const annotations=javaMask(text),spans=[];
    for(const m of annotations.matchAll(/@\w+(?:\.\w+)*/g)){
        let end=m.index+m[0].length;while(end<annotations.length&&/\s/.test(annotations[end]))end++;
        if(annotations[end]==='(')end=endOf(annotations,end,'(',')')+1;spans.push([m.index,end]);
    }
    for(const[start,end]of spans.reverse())text=text.slice(0,start)+' '.repeat(end-start)+text.slice(end);
    return text.split(',').map(p=>/(\w+)\s*$/.exec(p)?.[1]).filter(Boolean);
}
function yamlValues(source){
    const values={},stack=[];let listIndent=null;
    for(const line of source.split('\n')){
        const sequence=/^(\s*)-\s/.exec(line);
        if(sequence){listIndent=sequence[1].length;continue;}
        const indent=/^\s*/.exec(line)[0].length;
        if(listIndent!==null&&line.trim()&&indent>listIndent)continue;
        if(line.trim())listIndent=null;
        const m=/^(\s*)([A-Za-z][A-Za-z0-9-]*):(?:\s+(.*))?$/.exec(line);if(!m)continue;
        while(stack.length&&stack.at(-1).indent>=m[1].length)stack.pop();
        const key=[...stack.map(s=>s.key),m[2]].join('.');
        if(m[3]?.trim()){check(!(key in values),'Duplicate YAML scalar '+key);values[key]=m[3].trim();}
        else stack.push({indent:m[1].length,key:m[2]});
    }
    return values;
}
function matrixRows(source){
    const a=source.indexOf(START),b=source.indexOf(END);
    check(a>=0&&b>a&&source.indexOf(START,a+1)<0,'Missing/duplicate guide matrix');
    return source.slice(a+START.length,b).split('\n').filter(l=>/^\| [a-z][a-z0-9-]+ \|/.test(l))
        .map(l=>l.split('|').slice(1,-1).map(c=>c.trim()));
}
function testProof(source,name){
    const method=javaMethod(source,name);
    check(/@Test\b/.test(method.prefix+method.signature),'Proof method is not a JUnit @Test: '+name);
    check(!/@Disabled\b/.test(javaMask(source)),'Disabled test cannot establish coverage');
    check(/\bassert(?:Equals|True|False|Null|NotNull|Throws|InstanceOf|Same|NotEquals)\s*\(/.test(method.maskedBody),
        'Proof has no concrete JUnit assertion: '+name);
}
export function guideRow(scenario,count){
    const type=scenario.controller.split('/').at(-1).replace('.java','');
    return[scenario.id,scenario.verb+' '+scenario.route,'['+type+'.'+scenario.method+']('+MAIN+scenario.controller+')',String(count)];
}
function pendingCoverage(read,root,provider,feature,lanes,parseScript){
    check(feature.status==='SOURCE_IMPLEMENTED_RUNTIME_UNQUALIFIED'&&feature.runtimeEvidence===null,
        'Source declarations cannot certify runtime acceptance: '+feature.id);
    const proof=feature.sourceCoverage,lane=lanes.find(l=>l.id===proof?.lane);
    check(lane&&proof.scope&&proof.tests.length>0,'Missing bounded qualification/source scope: '+feature.id);
    for(const method of proof.methods)calls(read(root+'/'+method.file),method.method,method.calls);
    for(const suite of proof.tests){
        check(lane.suites.some(s=>s.file===suite.file),'Proof is outside the selected runtime roster: '+suite.file);
        for(const method of suite.methods)testProof(read(root+'/'+suite.file),method);
    }
    const postman=proof.postman,collectionPath=postman.collection.replace('{provider}',provider);
    const collection=JSON.parse(read(root+'/'+collectionPath)),items=requests(collection.item);
    check(items.length===postman.requestCount&&new Set(items.map(i=>i.name)).size===items.length,
        'Pending collection count/identity mismatch: '+feature.id);
    for(const name of postman.requests)check(items.some(i=>i.name===name),'Missing pending request '+name);
    for(const item of [collection,...items])for(const kind of ['test','prerequest'])
        parseScript(scripts(item,kind).join('\n'));
    for(const item of items)check(scripts(item,'test').some(line=>
        /pm\.response\.to\.have\.status\(\d{3}\)/.test(line)),
        'Pending request has no concrete HTTP assertion: '+item.name);
    const runner=read(root+'/'+postman.runner);
    check(runner.includes('ConfirmDisposableDemo')&&runner.includes('6.2.1'),
        'Missing explicit disposable/pinned Newman runner: '+feature.id);
    for(const guide of ['README.md','README.tr.md']){
        const text=read(root+'/'+guide),start=text.indexOf('<!-- BEGIN V019 PENDING COVERAGE -->');
        const end=text.indexOf('<!-- END V019 PENDING COVERAGE -->');
        check(start>=0&&end>start,'Missing pending coverage section: '+guide);
        const section=text.slice(start,end);
        const rows=section.split('\n').filter(line=>line.startsWith('| '+feature.id+' |'));
        const authority=guide==='README.tr.md'?'S\u00fcr\u00fcm commit CI / pakete ba\u011fl\u0131 kan\u0131t':
            'Release-commit CI / artifact-bound evidence';
        check(rows.length===1&&rows[0].split('|')[2].trim()===authority&&
            section.includes('('+postman.runner+')'),'Guide omits pending scope/runner: '+feature.id);
        for(const suite of proof.tests)check(section.includes('('+suite.file+')'),
            'Guide omits actual proof source: '+suite.file);
    }
}
function qualificationRoster(read,root,lanes){
    const expected={
        'audit-derived-delete':{'AuditContractTest':8,'AuditHttpContractTest':6,'AuditExternalIT':5,'DerivedDeleteRangeExternalIT':2},
        'encrypted-notes':{'EncryptedNoteContractTest':5,'EncryptedNoteExternalIT':2},
        'tenant-purge':{'TenantPurgeOptInTest':7,'TenantPurgeContractTest':11,'TenantPurgeExternalIT':1}
    };
    const profiles={'audit-derived-delete':'provider-integration','encrypted-notes':'encrypted-demo,provider-integration',
        'tenant-purge':'tenant-purge-demo,provider-integration'};
    check(lanes.length===3&&new Set(lanes.map(l=>l.id)).size===3,'Expected three independent qualification lanes');
    for(const lane of lanes){
        const roster=expected[lane.id];
        check(roster&&lane.profiles===profiles[lane.id]&&lane.maximumSecondsPerProvider===900,
            'Qualification lane/profile/bound changed: '+lane.id);
        const names=lane.suites.map(s=>s.file.split('/').at(-1).replace(/\.java$/,''));
        check(canonical([...names].sort())===canonical(Object.keys(roster).sort()),'Qualification suite set changed: '+lane.id);
        check(canonical(lane.surefire.split(',').sort())===canonical(names.filter(n=>n.endsWith('Test')).sort())&&
            canonical(lane.failsafe.split(',').sort())===canonical(names.filter(n=>n.endsWith('IT')).sort()),
            'Surefire/Failsafe selectors do not match the declared roster: '+lane.id);
        for(const suite of lane.suites){
            const source=javaMask(read(root+'/'+suite.file)),name=suite.file.split('/').at(-1).replace(/\.java$/,'');
            check(suite.cases===roster[name]&&[...source.matchAll(/@Test\b/g)].length===suite.cases&&
                !/@(?:Disabled|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/.test(source),
                'Expected exact non-disabled ordinary JUnit roster: '+suite.file);
        }
    }
}
export function validateSampleMatrix(read,parseScript=()=>{}){
    const manifest=JSON.parse(read('tools/samples/v019-scenarios.json'));
    check(manifest.schema===1&&manifest.scenarios.length===20,'Unsupported matrix schema/count');
    check(canonical(manifest.providers)===canonical(['postgresql','mssql','oracle']),
        'Expected the exact PostgreSQL/MSSQL/Oracle provider roster');
    const required=['spring-cache','async','reactive','range-keyset','spring-data','spring-data-bulk-delete','field-encryption','retention','tenant-purge'];
    check(canonical(manifest.requiredFeatures)===canonical(required),'Required release feature set changed');
    check(canonical([...new Set([...manifest.scenarios.map(s=>s.feature),...manifest.deferredFeatures.map(f=>f.id)])].sort())===
        canonical([...required].sort()),'Required feature has neither real scenarios nor an explicit deferred gate');
    check(canonical(Object.keys(manifest.nativeRequirements).sort())===
        canonical([...new Set(manifest.scenarios.map(s=>s.feature))].sort()),'Missing native API requirement');
    const rows=[],errors=[],deferred=[],native=new Map();
    const capture=(label,action)=>{try{action();}catch(error){errors.push(label+': '+error.message);}};
    for(const[feature,requirements]of Object.entries(manifest.nativeRequirements)){
        const problems=[];
        for(const requirement of requirements)try{
            const source=read(requirement.file),mask=javaMask(source);
            check(new RegExp('\\b(?:class|interface)\\s+'+escaped(requirement.type)+'\\b').test(mask),
                'Missing native type '+requirement.type);
            if(requirement.method)javaMethod(source,requirement.method,requirement.arity);
            if(requirement.baseTypes){
                const declaration=new RegExp('\\binterface\\s+'+escaped(requirement.type)+'[^{}]*').exec(mask)?.[0]||'';
                for(const type of requirement.baseTypes)check(new RegExp('\\b'+escaped(type)+'\\s*<').test(declaration),
                    'Missing inherited Spring Data contract '+type);
            }
        }catch(error){problems.push(error.message);}
        native.set(feature,problems);
    }
    for(const provider of manifest.providers){
        const root='sample-cache-database-'+provider;let cases=[];
        capture(root+'/collections',()=>{
            const business=JSON.parse(read(root+'/postman/cache-database-'+provider+'-sample.postman_collection.json'));
            const range=JSON.parse(read(root+'/postman/cache-database-'+provider+'-range-data.postman_collection.json'));
            const folder=business.item.filter(i=>i.name===manifest.cacheFolder);
            check(folder.length===1,'Missing unique cache folder');
            const legacy=requests(business.item.filter(i=>i.name!==manifest.cacheFolder));
            check(legacy.length===61&&requests(business.item).length===84,'Legacy 61 + cache 23 contract lost');
            check(canonical(legacy.map(requestContract))===canonical(manifest.legacy),'Legacy request/body/assertion flow changed');
            const cache=requests(folder[0].item),extra=requests(range.item);
            check(cache.length===23&&extra.length===37,'Expected 23 + 37 new requests');
            check(canonical(scripts(folder[0],'prerequest'))===canonical(manifest.cacheGate),'Cache certification gate changed');
            check(canonical(scripts(range,'prerequest'))===canonical(manifest.rangeGate),'Range certification gate changed');
            cases=[...cache,...extra];check(new Set(cases.map(c=>c.name)).size===60,'Duplicate scenario request name');
            check(canonical(cases.map(requestContract))===canonical(manifest.cases.map(c=>c.contract)),
                'Saved scenario request/body/assertion contract changed');
            for(const item of cases)for(const kind of ['test','prerequest'])parseScript(scripts(item,kind).join('\n'));
        });
        for(const scenario of manifest.scenarios){
            const before=errors.length;
            capture(root+'/'+scenario.id,()=>{
                const source=read(root+'/'+MAIN+scenario.controller),method=calls(source,scenario.method,scenario.calls);
                check(canonical(routeOf(source,method))===canonical({verb:scenario.verb,route:scenario.route}),'Controller REST mapping differs');
                if(scenario.dto){
                    const fields=bodyFields(source,scenario.dto);
                    for(const entry of manifest.cases.filter(c=>c.scenario===scenario.id&&c.status===202)){
                        const body=JSON.parse(entry.contract.request.body.raw.replace(/\{\{[^}]+\}\}/g,'1001'));
                        check(canonical(Object.keys(body).sort())===canonical(fields.sort()),'JSON body differs from actual DTO fields');
                    }
                }
                testProof(read(root+'/'+TEST+scenario.test.file),scenario.test.method);
                for(const flow of scenario.flows||[])calls(read(root+'/'+MAIN+flow.file),flow.method,flow.calls);
                const bound=manifest.cases.filter(c=>c.scenario===scenario.id);check(bound.length>0,'Scenario has no saved requests');
                for(const entry of bound){
                    const actual=cases.find(c=>c.name===entry.contract.name);check(actual,'Missing request '+entry.contract.name);
                    const raw=typeof actual.request.url==='string'?actual.request.url:actual.request.url.raw;
                    const pathname=raw.replace(/^\{\{baseUrl\}\}/,'').split('?')[0].replace(/\{\{[^}]+\}\}/g,'example');
                    check(actual.request.method===scenario.verb&&routeRegex(scenario.route).test(pathname),'Request targets a different REST method');
                    check(scripts(actual,'test').some(l=>l.includes("pm.test('HTTP "+entry.status+"'")),'Missing exact HTTP status assertion');
                }
                for(const guide of ['README.md','README.tr.md']){
                    const text=read(root+'/'+guide),table=matrixRows(text);
                    check(table.length===manifest.scenarios.length,'Guide matrix row count differs');
                    check(canonical(table.find(r=>r[0]===scenario.id))===canonical(guideRow(scenario,bound.length)),
                        'EN/TR REST/source/request-count matrix differs: '+guide);
                    if(guide.endsWith('.tr.md'))check(text.normalize('NFC')===text,'Turkish guide is not NFC');
                }
            });
            const problems=native.get(scenario.feature)||[];
            rows.push({provider,scenario:scenario.id,feature:scenario.feature,
                status:errors.length!==before?'MISMATCH':problems.length?'DEPENDENCY':'COVERED_STATIC',dependency:problems.join('; ')});
        }
        const configErrors=errors.length;
        capture(root+'/configuration',()=>{
            const base=yamlValues(read(root+'/src/main/resources/application.yml'));
            const demo={...base,...yamlValues(read(root+'/src/main/resources/application-demo.yml'))};
            check(base['cachedb.redis.read-coalescing.enabled']==='${SAMPLE_READ_COALESCING_ENABLED:false}'&&
                demo['cachedb.redis.read-coalescing.enabled']===base['cachedb.redis.read-coalescing.enabled'],
                'Merging must remain explicit opt-in, including demo');
            check(base['sample.cache-api.enabled']==='false'&&base['sample.range-data.enabled']==='false','Non-demo APIs must default false');
            for(const[key,value]of Object.entries(manifest.demoRequired))check(demo[key]===value,'Demo property differs: '+key);
            const pom=read(root+'/pom.xml');check(/<cachedb.version>0\.19\.0<\/cachedb.version>/.test(pom),'Unaligned sample version');
            for(const artifact of ['cachedb-reactor','cachedb-spring-data']){
                const deps=[...pom.matchAll(/<dependency>[\s\S]*?<\/dependency>/g)].filter(m=>m[0].includes('<artifactId>'+artifact+'</artifactId>'));
                check(deps.length===1&&/<optional>true<\/optional>/.test(deps[0][0])&&
                    deps[0][0].includes('<version>'+'${cachedb.version}'+'</version>'),'Optional dependency must use project version: '+artifact);
            }
            const guard=javaMethod(read(root+'/'+MAIN+'rangedata/RangeDataGuard.java'),'call').maskedBody;
            for(const call of ['access.tenant','CacheCausalContext.hasRequirements','CacheTenantContext.withTenant','permits.tryAcquire','permits.release'])
                check(hasCall(guard,call),'Missing executable guard '+call);
            check(guard.indexOf('hasRequirements')<guard.indexOf('operation.apply'),'Causal guard occurs after work');
            const service=read(root+'/'+MAIN+'cacheapi/MethodCacheService.java');
            check(!/\bfinal\s+class\s+MethodCacheService\b/.test(javaMask(service)),'Final CGLIB cache target');
            for(const[name,annotation]of Object.entries(manifest.cacheAnnotations))
                check(javaMethod(service,name).prefix.replace(/\s+/g,'').includes(annotation.replace(/\s+/g,'')),'Cache annotation semantics changed: '+name);
            const defaults=read(root+'/'+TEST+'SampleReadTransportDefaultsTest.java');
            for(const method of ['nonDemoKeepsNewApisAndMergingDisabled','demoDoesNotImplicitlyEnableMerging','explicitOptInOnlyChangesMerging'])testProof(defaults,method);
            const proof=read(root+'/'+TEST+'rangedata/RangeDataExternalIT.java');
            calls(proof,'nativeRepository',['database.repository','assertInstanceOf']);
            check(/@SpringBootTest\b/.test(javaMask(proof))&&!/\b(?:mock|spy)\s*\(/.test(javaMask(proof)),'Native parity requires the actual repository');
            const repo=read(root+'/'+MAIN+'rangedata/RangeDataRepository.java');
            for(const method of ['findByRunIdAndStatus','readByRunIdAndStatus','readByRunIdAndStatusOrderByRankValueAscRowIdAsc',
                'countByRunIdAndStatus','existsByRunIdAndStatus'])javaMethod(repo,method);
            const runner=read(root+'/scripts/test-v019-postman.ps1');
            check(runner.includes('-Kind Business')&&runner.includes('test-range-data-postman.ps1'),'Runner dropped 84 or range collection');
            for(const guide of ['README.md','README.tr.md']){
                const text=read(root+'/'+guide);check(text.includes('SAMPLE_READ_COALESCING_ENABLED:false')&&
                    text.includes('SAMPLE_READ_COALESCING_ENABLED=true')&&text.includes('p99'),'Missing opt-in/qualification contract');
                check(text.includes('<cachedb.version>0.19.0</cachedb.version>')&&
                    !/(?:uses CacheDB `0\.18\.1`|use \*\*CacheDB 0\.18\.1\*\*|örnek CacheDB `0\.18\.1`|senaryolar \*\*CacheDB 0\.18\.1\*\*)/.test(text),
                    'Guide and configured artifact versions differ');
            }
        });
        if(errors.length!==configErrors)for(const row of rows)if(row.provider===provider)row.status='MISMATCH';
        const rosterErrors=errors.length;
        capture(root+'/qualification-roster',()=>qualificationRoster(read,root,manifest.qualificationLanes));
        const rosterValid=errors.length===rosterErrors;
        for(const feature of manifest.deferredFeatures){
            const before=errors.length;
            capture(root+'/pending/'+feature.id,()=>pendingCoverage(read,root,provider,feature,manifest.qualificationLanes,parseScript));
            deferred.push({provider,feature:feature.id,status:'DEFERRED',
                sourceStatus:rosterValid&&errors.length===before?'COVERED_STATIC':'MISMATCH',
                runtimeStatus:feature.status,runtimeEvidence:null,dependency:feature.dependency});
        }
    }
    const structurallyComplete=errors.length===0&&[...native.values()].every(v=>!v.length);
    return{schema:1,evidence:'STATIC_ONLY_NOT_RUNTIME_ACCEPTANCE',runtimeQualified:false,structurallyComplete,rows,errors,deferred,
        unresolvedNative:[...native].filter(([,v])=>v.length).map(([feature,problems])=>({feature,problems})),
        complete:structurallyComplete&&deferred.length===0};
}
function qualificationReferences({receiptPath,receiptSha256,sourceEquivalence,executionToolingOverlay}){
    const references=[sourceEquivalence,executionToolingOverlay];
    if(references.every(value=>value===undefined))return;
    check(references.every(value=>value!==undefined)&&Boolean(receiptPath)&&/^[a-f0-9]{64}$/.test(receiptSha256),
        'Qualification requires both reference pairs and pinned runtime evidence');
    for(const reference of references){
        check(reference&&typeof reference==='object'&&!Array.isArray(reference)&&Object.keys(reference).sort().join(',')==='path,sha256'&&
            typeof reference.sha256==='string'&&/^[a-f0-9]{64}$/.test(reference.sha256),'Invalid qualification reference SHA256');
        const member=reference.path;
        check(typeof member==='string'&&member.length>0&&member.length<=1024&&!/[\\:\x00-\x1f\x7f]/.test(member)&&
            !member.startsWith('/')&&member.split('/').every(part=>part&&part!=='.'&&part!=='..'&&part===part.trim()&&!part.endsWith('.')),
            'Unsafe qualification manifest path');
    }
}
export function checkSampleCoverage({root,receiptPath,receiptSha256,sourceEquivalence,executionToolingOverlay}){
    check(Boolean(receiptPath)===Boolean(receiptSha256),'Both runtime evidence path and coordinator SHA256 are required');
    qualificationReferences({receiptPath,receiptSha256,sourceEquivalence,executionToolingOverlay});
    const sourceDigests=new Map();
    const read=relative=>{
        check(!path.isAbsolute(relative),'Matrix path must be relative');const full=path.resolve(root,relative);
        check(full.startsWith(root+path.sep),'Matrix path escapes repository');check(fs.statSync(full).size<=2*1024*1024,'Source exceeds 2 MiB');
        const bytes=fs.readFileSync(full),sha256=createHash('sha256').update(bytes).digest('hex');
        check(!sourceDigests.has(relative)||sourceDigests.get(relative)===sha256,'Source changed during structural checks');
        sourceDigests.set(relative,sha256);
        return new TextDecoder('utf-8',{fatal:true}).decode(bytes).replace(/\r\n/g,'\n');
    };
    const result=validateSampleMatrix(read,s=>new vm.Script(s));
    if(!receiptPath)return result;
    try{
        check(result.structurallyComplete,'Runtime qualification requires structurally complete source coverage');
        const contracts=sampleRuntimeContracts(read,javaMask);
        const runtime=verifySampleRuntime({repositoryRoot:root,receiptPath,receiptSha256,contracts,
            requiredSourceFiles:[...sourceDigests.keys()],requiredSourceDigests:sourceDigests,
            ...(sourceEquivalence===undefined?{}:{sourceEquivalence,executionToolingOverlay})});
        const bound=result.deferred.map(row=>{
            const cell=runtime.cells.find(cell=>cell.provider===row.provider&&cell.features.includes(row.feature));
            check(row.sourceStatus==='COVERED_STATIC'&&cell,'Missing verified feature/lane binding');
            return{...row,status:'COVERED_RUNTIME',runtimeStatus:'QUALIFIED_FINAL_RUNTIME',runtimeEvidence:{
                receiptSha256:runtime.receiptSha256,sourceManifestSha256:runtime.sourceManifestSha256,
                sdkManifestSha256:runtime.sdkManifestSha256,lane:cell.lane},dependency:null};
        });
        check(bound.length===12,'Exact four-feature/three-provider binding required');
        return{...result,evidence:'STATIC_AND_PINNED_FINAL_SAMPLE_RUNTIME',runtimeQualified:true,
            runtime,qualifiedFeatures:bound,deferred:[],complete:true,releaseAccepted:false};
    }catch(error){return{...result,runtimeQualified:false,complete:false,runtimeErrors:[error.message]};}
}
export function sampleCoverageOptions(args){
    const options={};
    for(let i=0;i<args.length;i+=2){
        check(['--root','--summary','--runtime-evidence','--runtime-evidence-sha256','--source-equivalence','--source-equivalence-sha256',
            '--execution-tooling-overlay','--execution-tooling-overlay-sha256','--private-results'].includes(args[i]),'Unsupported option: '+args[i]);
        const name=args[i].slice(2);
        check(!Object.hasOwn(options,name),'Duplicate option: '+args[i]);
        check(args[i+1]&&!args[i+1].startsWith('--'),'Expected option/value pairs');
        options[name]=args[i+1];
    }
    check(Boolean(options['runtime-evidence'])===Boolean(options['runtime-evidence-sha256']),
        'Use --runtime-evidence and --runtime-evidence-sha256 together');
    for(const name of ['source-equivalence','execution-tooling-overlay'])check(Boolean(options[name])===Boolean(options[name+'-sha256']),
        'Use both '+name+' path and SHA256');
    qualificationReferences({receiptPath:options['runtime-evidence'],receiptSha256:options['runtime-evidence-sha256'],
        sourceEquivalence:options['source-equivalence']?{path:options['source-equivalence'],sha256:options['source-equivalence-sha256']}:undefined,
        executionToolingOverlay:options['execution-tooling-overlay']?{path:options['execution-tooling-overlay'],sha256:options['execution-tooling-overlay-sha256']}:undefined});
    check(!options['source-equivalence']||Boolean(options['private-results']),'Qualification requires private results');
    return options;
}
export function sampleCoveragePrivateResults(root,full){
    check(typeof full==='string'&&path.isAbsolute(full),'Private results require an absolute path');
    const checkout=fs.realpathSync(root),parent=fs.realpathSync(path.dirname(full)),resolved=path.resolve(parent,path.basename(full));
    const member=path.relative(checkout,resolved);
    check(path.resolve(full)===resolved&&(member==='..'||member.startsWith('..'+path.sep)||path.isAbsolute(member)),
        'Private results must stay outside the checkout without aliases');
    check(!fs.existsSync(resolved),'Private results require a fresh file');
    return resolved;
}
export function sampleCoverageRuntimeIssues(errors,privateResults){
    return privateResults&&errors?.length?['Pinned runtime evidence rejected; diagnostic details retained privately.']:errors||[];
}
function main(){
    check(Number(process.versions.node.split('.')[0])>=18,'The sample matrix checker requires Node.js 18 or newer');
    const options=sampleCoverageOptions(process.argv.slice(2));
    const root=path.resolve(options.root||process.cwd());
    const privateResults=options['private-results']?sampleCoveragePrivateResults(root,options['private-results']):null;
    const result=checkSampleCoverage({root,receiptPath:options['runtime-evidence'],receiptSha256:options['runtime-evidence-sha256'],
        sourceEquivalence:options['source-equivalence']?{path:options['source-equivalence'],sha256:options['source-equivalence-sha256']}:undefined,
        executionToolingOverlay:options['execution-tooling-overlay']?{path:options['execution-tooling-overlay'],sha256:options['execution-tooling-overlay-sha256']}:undefined});
    const summary=path.resolve(root,options.summary||'target/v019-sample-coverage.md'),target=path.resolve(root,'target')+path.sep;
    check(summary.startsWith(target),'Summary must stay below target');
    const lines=['# Sample Scenario Matrix','','This report is not full release, baseline HTTP or performance acceptance.',
        'Structural consistency: '+(result.structurallyComplete?'COVERED_STATIC':'INCOMPLETE')+'. Runtime qualification: '+
            (result.runtimeQualified?'FOUR_PENDING_FEATURES_QUALIFIED':result.runtimeErrors?'REJECTED':'NOT_INGESTED')+'.',
        'Source declarations remain runtime-unqualified; only verified pinned evidence can bind final qualification.','',
        '| Provider | Scenario | Feature | Status |','| --- | --- | --- | --- |',
        ...result.rows.map(r=>'| '+[r.provider,r.scenario,r.feature,r.status].join(' | ')+' |'),
        ...[...result.deferred,...(result.qualifiedFeatures||[])].map(r=>'| '+[r.provider,'feature runtime',r.feature,r.status+' / '+r.sourceStatus].join(' | ')+' |')];
    if(result.runtime)lines.push('','Receipt SHA256: `'+result.runtime.receiptSha256+'`. Verified 141 tests and 180 independent HTTP requests.');
    const issues=[...result.errors,...sampleCoverageRuntimeIssues(result.runtimeErrors,privateResults),...result.unresolvedNative.flatMap(r=>r.problems.map(p=>r.feature+': '+p)),
        ...result.deferred.map(r=>r.provider+'/'+r.feature+': '+r.dependency)];
    if(issues.length)lines.push('','Unresolved required evidence:','',...issues.map(e=>'- '+e));
    fs.mkdirSync(path.dirname(summary),{recursive:true});fs.writeFileSync(summary,lines.join('\n')+'\n','utf8');
    fs.writeFileSync(privateResults||summary.replace(/\.md$/i,'')+'.json',JSON.stringify(result,null,2)+'\n',
        privateResults?{encoding:'utf8',flag:'wx',mode:0o600}:'utf8');
    if(!result.complete){process.stderr.write('Required sample matrix is incomplete; see '+summary+'\n');process.exitCode=1;}
    else process.stdout.write('Four pending features qualified from pinned final runtime evidence; full release acceptance remains separate.\n');
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
    try{main();}catch(error){process.stderr.write(error.message+'\n');process.exitCode=1;}
}
