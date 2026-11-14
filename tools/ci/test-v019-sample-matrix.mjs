import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { validateSampleMatrix, sampleCoverageOptions, sampleCoveragePrivateResults } from './check-v019-sample-coverage.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8').replace(/\r\n/g,'\n');
const privateHome=fs.mkdtempSync(path.join(os.tmpdir(),'cachedb-private-report-'));
try{
    const checkout=path.join(privateHome,'source');fs.mkdirSync(checkout);
    const summary=path.join(privateHome,'summary.md'),results=path.join(privateHome,'results.json');
    assert.equal(sampleCoverageOptions(['--private-summary',summary,'--private-results',results])['private-summary'],summary);
    assert.throws(()=>sampleCoverageOptions(['--private-summary',summary]),/requires private results/);
    assert.throws(()=>sampleCoverageOptions(['--private-summary',summary,'--private-results',results,'--summary','target/public.md']),/either public summary/);
    assert.equal(sampleCoveragePrivateResults(checkout,summary),summary);
    assert.throws(()=>sampleCoveragePrivateResults(checkout,path.join(checkout,'summary.md')),/outside the checkout/);
    fs.writeFileSync(summary,'retained original');
    assert.throws(()=>sampleCoveragePrivateResults(checkout,summary),/fresh file/);
    assert.equal(fs.readFileSync(summary,'utf8'),'retained original');
    assert.match(read('tools/ci/check-k8s-acceptance.ps1'),/'--private-summary', \(Join-Path \$PrivateResults 'features\.md'\)/);
}finally{fs.rmSync(privateHome,{recursive:true,force:true});}
function checkProducerSummary(source){
    const match=/"--summary",\s*source\.resolve\("([^"]+)"\)\.toString\(\)/.exec(source);
    assert(match,'Runtime producer must resolve its summary against the checked source tree');
    assert(path.resolve(root,match[1]).startsWith(path.resolve(root,'target')+path.sep),
        'Runtime producer summary must stay below target');
}
const producer=read('tools/k8s/SampleRuntimeEvidence.java');
const launcher=read('tools/k8s/run-sample-runtime-evidence.sh');
assert.match(launcher,/^set -euo pipefail$/m,'Logger must preserve a failed producer exit');
for (const channel of ['compile','adapter']) {
    assert(launcher.includes('2>&1 | tee -- "$out/'+channel+'.log"'),'Missing parent-visible '+channel+' log');
}
const startMarker=producer.indexOf('System.out.println("SAMPLE_CELL_START "');
const checkpoint=producer.indexOf('write(out.resolve("completed-cell-"');
const completionMarker=producer.indexOf('System.out.println("SAMPLE_CELL_COMPLETE "');
assert(startMarker>=0 && checkpoint>startMarker && completionMarker>checkpoint,
    'A completion marker must not precede a successful persisted checkpoint');
checkProducerSummary(producer);
assert.throws(()=>checkProducerSummary(producer.replace(
    'source.resolve("target/sample-runtime-coverage.md")','out.resolve("coverage.md")')));
assert.throws(()=>checkProducerSummary(producer.replace(
    'source.resolve("target/sample-runtime-coverage.md")','source.resolve("../coverage.md")')));
const baseline=validateSampleMatrix(read);
assert.deepEqual(baseline.errors,[],'Implemented scenarios must be structurally consistent');
assert.equal(baseline.complete,false,'Deferred protection/bulk-delete scenarios must not certify a release');
assert.equal(baseline.runtimeQualified,false,'Documentation authority must not qualify runtime behavior');
assert(baseline.deferred.length>0&&baseline.deferred.every(row=>
    row.runtimeStatus==='SOURCE_IMPLEMENTED_RUNTIME_UNQUALIFIED'&&row.runtimeEvidence===null),
    'Public documentation wording must not change internal source coverage declarations');
const prefix='sample-cache-database-postgresql/';
for(const guide of ['README.md','README.tr.md']){
    const file=prefix+guide,original=read(file);
    const archived=original.replace(/^(\| (?:spring-data-bulk-delete|field-encryption|retention|tenant-purge) \|)[^|]+\|/gm,
        '$1 SOURCE_IMPLEMENTED_RUNTIME_UNQUALIFIED |');
    assert.notEqual(archived,original,'Archived guide fixture must change the authority column');
    const retained=validateSampleMatrix(p=>p===file?archived:read(p));
    assert.deepEqual(retained.errors,[],'Valid archived source documentation must remain readable');
    assert.equal(retained.complete,false,'Archived documentation is not release acceptance');
    assert.equal(retained.runtimeQualified,false,'Archived documentation is not runtime proof');
    assert(retained.deferred.every(row=>row.runtimeEvidence===null));
}
const controller=prefix+'src/main/java/com/example/cachedb/sample/cacheapi/CacheApiController.java';
const collection=prefix+'postman/cache-database-postgresql-sample.postman_collection.json';
const mutations=[
    ['unrecognized-guide-status',prefix+'README.md',s=>s.replace(
        '| tenant-purge | Release-commit CI / artifact-bound evidence |',
        '| tenant-purge | UNKNOWN_SOURCE_STATUS |')],
    ['fabricated-guide-pass',prefix+'README.md',s=>s.replace(
        '| tenant-purge | Release-commit CI / artifact-bound evidence |',
        '| tenant-purge | RUNTIME_ACCEPTED |')],
    ['wrong-turkish-authority',prefix+'README.tr.md',s=>s.replace(
        '| tenant-purge | S\u00fcr\u00fcm commit CI / pakete ba\u011fl\u0131 kan\u0131t |',
        '| tenant-purge | Release-commit CI / artifact-bound evidence |')],
    ['duplicate-guide-authority',prefix+'README.md',s=>s.replace(
        /^\| tenant-purge \|[^\n]+/m,row=>row+'\n'+row)],
    ['fabricated-source-pass','tools/samples/v019-scenarios.json',s=>{
        const data=JSON.parse(s),feature=data.deferredFeatures.find(f=>f.id==='tenant-purge');
        feature.status='RUNTIME_ACCEPTED';feature.runtimeEvidence={claimed:true};return JSON.stringify(data);}],
    ['comment-spoof',controller,s=>s.replace('String tenant = access.tenant(auth); key(sku, branch);',
        '/* access.tenant(auth) */ String tenant = "shop-a"; key(sku, branch);')],
    ['wrong-rest-path',controller,s=>s.replace('@GetMapping("/cache/quotes/{sku}")','@GetMapping("/cache/quote-broken/{sku}")')],
    ['wrong-turkish-matrix',prefix+'README.tr.md',s=>s.replace('[CacheApiController.quote]','[CacheApiController.clear]')],
    ['unqualified-default',prefix+'src/main/resources/application.yml',
        s=>s.replace('SAMPLE_READ_COALESCING_ENABLED:false','SAMPLE_READ_COALESCING_ENABLED:true')],
    ['drop-folder-07',collection,s=>{const c=JSON.parse(s);c.item=c.item.filter(i=>i.name!=='07 Method Cache and Async');return JSON.stringify(c);}],
    ['vacuous-assertion',collection,s=>{const c=JSON.parse(s);c.item.find(i=>i.name==='07 Method Cache and Async')
        .item[0].event.find(e=>e.listen==='test').script.exec=["pm.test('HTTP 204',()=>{});"];return JSON.stringify(c);}],
    ['skipped-certification',collection,s=>{const c=JSON.parse(s);c.item.find(i=>i.name==='07 Method Cache and Async')
        .event[0].script.exec=['pm.execution.skipRequest();'];return JSON.stringify(c);}]
];
for(const[name,file,change]of mutations){
    const original=read(file),modified=change(original);
    assert.notEqual(modified,original,'Mutation did not target actual source: '+name);
    const result=validateSampleMatrix(p=>p===file?modified:read(p));
    assert(result.errors.length>baseline.errors.length,'Matrix accepted broken source/guide/request: '+name);
}
const manifest='tools/samples/v019-scenarios.json',data=JSON.parse(read(manifest));
data.requiredFeatures=data.requiredFeatures.filter(f=>f!=='tenant-purge');
assert.throws(()=>validateSampleMatrix(p=>p===manifest?JSON.stringify(data):read(p)),/Required release feature set changed/);
process.stdout.write('Static matrix rejection checks passed; no live application acceptance is implied.\n');
