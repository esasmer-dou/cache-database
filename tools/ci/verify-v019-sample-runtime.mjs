import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

const need = (ok, message) => { if (!ok) throw new Error(message); };
const digest = value => createHash('sha256').update(value).digest('hex');
const hex = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const providers = ['postgresql', 'mssql', 'oracle'];
const lanes = {
    'audit-derived-delete': { tests: 21, requests: 42, stem: 'audit', features: ['spring-data-bulk-delete', 'retention'],
        suites: { AuditContractTest: 8, AuditHttpContractTest: 6, AuditExternalIT: 5, DerivedDeleteRangeExternalIT: 2 } },
    'encrypted-notes': { tests: 7, requests: 10, stem: 'encryption', features: ['field-encryption'],
        suites: { EncryptedNoteContractTest: 5, EncryptedNoteExternalIT: 2 } },
    'tenant-purge': { tests: 19, requests: 8, stem: 'tenant-purge', features: ['tenant-purge'],
        suites: { TenantPurgeOptInTest: 7, TenantPurgeContractTest: 11, TenantPurgeExternalIT: 1 } }
};
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function sameMembers(actual, expected, label) {
    need(Array.isArray(actual) && new Set(actual).size === actual.length &&
        equal([...actual].sort(), [...expected].sort()), label + ': exact unique membership required');
}
function keys(value, expected, label) {
    need(value && typeof value === 'object' && !Array.isArray(value), label + ': object required');
    sameMembers(Object.keys(value), expected, label);
}
function relative(value) {
    need(typeof value === 'string' && value.length > 0 && value.length <= 1024 &&
        !/[\\:\x00-\x1f\x7f]/.test(value) && !value.startsWith('/') &&
        value.split('/').every(part => part && part !== '.' && part !== '..' && part === part.trim() && !part.endsWith('.')),
        'Unsafe evidence path');
    return value;
}
function safeFile(root, member) {
    let full = root;
    for (const part of relative(member).split('/')) {
        full = path.join(full, part);
        need(!fs.lstatSync(full).isSymbolicLink(), 'Symlink evidence rejected');
    }
    need(fs.statSync(full).isFile(), 'Regular evidence file required');
    return full;
}
function fileHash(full, maximum) {
    const fd = fs.openSync(full, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
        const before = fs.fstatSync(fd);
        need(before.isFile() && before.size <= maximum, 'Evidence file exceeds byte budget');
        const hash = createHash('sha256'), buffer = Buffer.allocUnsafe(65536);
        let count = 0, size;
        while ((size = fs.readSync(fd, buffer, 0, buffer.length, null)) !== 0) {
            count += size; need(count <= maximum, 'Evidence file grew beyond byte budget');
            hash.update(buffer.subarray(0, size));
        }
        const after = fs.fstatSync(fd);
        need(count === before.size && before.size === after.size && before.mtimeMs === after.mtimeMs &&
            before.ctimeMs === after.ctimeMs, 'Evidence changed during inspection');
        return { sha256: hash.digest('hex'), size: count, inode: before.dev + ':' + before.ino };
    } finally { fs.closeSync(fd); }
}
class Evidence {
    constructor(root) { this.root = root; this.files = new Map(); this.claims = new Set(); this.inodes = new Set(); this.bytes = 0; }
    file(ref, maximum = 64 * 1024 * 1024, exclusive = false) {
        keys(ref, ['path', 'sha256'], 'File reference'); need(hex(ref.sha256), 'SHA256 required');
        const full = safeFile(this.root, ref.path), current = fileHash(full, maximum);
        need(current.sha256 === ref.sha256, 'Evidence digest mismatch: ' + ref.path);
        const old = this.files.get(ref.path);
        need(!old || old.sha256 === ref.sha256, 'Conflicting file reference');
        if (!old) { this.bytes += current.size; need(this.bytes <= 2 * 1024 ** 3 && this.files.size < 20000,
            'Evidence aggregate budget exceeded'); this.files.set(ref.path, { ...ref, maximum }); }
        if (exclusive) {
            need(!this.claims.has(ref.path.toLowerCase()) && !this.inodes.has(current.inode), 'Reused phase evidence');
            this.claims.add(ref.path.toLowerCase()); this.inodes.add(current.inode);
        }
        return full;
    }
    text(ref, maximum, exclusive = false) {
        const full = this.file(ref, maximum, exclusive), bytes = fs.readFileSync(full);
        need(bytes.length <= maximum && digest(bytes) === ref.sha256, 'Evidence changed before decoding');
        return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    }
    json(ref, maximum, exclusive = false) { return JSON.parse(this.text(ref, maximum, exclusive)); }
    unchanged() { for (const ref of this.files.values()) this.file({ path: ref.path, sha256: ref.sha256 }, ref.maximum); }
}
function manifest(text) {
    const rows = new Map(), aliases = new Set();
    for (const line of text.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n')) {
        const match = /^([a-f0-9]{64}) [ *](.+)$/.exec(line);
        need(match, 'Malformed SHA256 manifest');
        const name = relative(match[2].replace(/^\.\//, ''));
        need(!aliases.has(name.toLowerCase()) && rows.size < 10000, 'Duplicate/oversized manifest');
        aliases.add(name.toLowerCase()); rows.set(name, match[1]);
    }
    need(rows.size > 0, 'Empty manifest'); return rows;
}

// Deliberately limited report/POM XML: no DTD, entities, namespaces on element names, or external resolution.
function xml(text) {
    need(!/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(text), 'Invalid XML control character');
    const document = { name: '#document', attrs: {}, children: [], text: '' }, stack = [document];
    const tokens = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?xml\s[^?]*\?>|<\/?[A-Za-z_][\w.-]*(?:[^<>"']|"[^"]*"|'[^']*')*>|[^<]+/gy;
    let index = 0, nodes = 0, declared = false, token;
    const decode = value => value.replace(/&(?:([^;]*);)?/g, (_, entity) => {
        const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
        if (Object.hasOwn(named, entity)) return named[entity];
        need(entity && /^#(?:[0-9]+|x[0-9a-fA-F]+)$/.test(entity), 'Unsupported XML entity');
        const number = entity[1] === 'x' ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
        need(number === 9 || number === 10 || number === 13 || number >= 32 && number <= 0x10ffff &&
            !(number >= 0xd800 && number <= 0xdfff) && number !== 0xfffe && number !== 0xffff, 'Invalid XML entity');
        return String.fromCodePoint(number);
    });
    while ((token = tokens.exec(text))) {
        need(token.index === index, 'Malformed XML'); index = tokens.lastIndex;
        const value = token[0], parent = stack.at(-1);
        if (value.startsWith('<!--')) { need(!value.slice(4, -3).includes('--'), 'Malformed XML comment'); continue; }
        if (value.startsWith('<?')) { need(!declared && stack.length === 1 && !document.children.length, 'Misplaced XML declaration'); declared = true; continue; }
        if (value.startsWith('<![CDATA[')) { need(stack.length > 1, 'Misplaced CDATA'); parent.text += value.slice(9, -3); continue; }
        if (!value.startsWith('<')) {
            need(stack.length > 1 || !value.trim(), 'Text outside XML root'); parent.text += decode(value); continue;
        }
        if (value.startsWith('</')) {
            need(/^<\/[\w.-]+\s*>$/.test(value) && stack.length > 1 &&
                parent.name === value.slice(2, -1).trim(), 'Mismatched XML closing tag'); stack.pop(); continue;
        }
        const match = /^<([\w.-]+)([\s\S]*?)(\/?)>$/.exec(value), attrs = Object.create(null);
        need(match && ++nodes <= 20000 && stack.length <= 24, 'XML node/depth budget exceeded');
        let tail = match[2], attr;
        while (tail.length) {
            if (!tail.trim()) break;
            attr = /^\s+([\w:.-]+)\s*=\s*(?:"([^"<]*)"|'([^'<]*)')/.exec(tail);
            need(attr && !Object.hasOwn(attrs, attr[1]) && Object.keys(attrs).length < 128, 'Invalid/duplicate XML attribute');
            attrs[attr[1]] = decode(attr[2] ?? attr[3]); tail = tail.slice(attr[0].length);
        }
        need(!/^(?:failure|error|skipped|flakyFailure|flakyError|rerunFailure|rerunError)$/.test(match[1]),
            'Failed/skipped/flaky/rerun XML evidence');
        const node = { name: match[1], attrs, children: [], text: '' }; parent.children.push(node);
        if (!match[3]) stack.push(node);
    }
    need(index === text.length && stack.length === 1 && document.children.length === 1, 'Malformed/unsafe XML');
    return document.children[0];
}
const children = (node, name) => node.children.filter(child => child.name === name);
function counter(node, name, expected, required = true) {
    const value = node.attrs[name];
    need(!required && value === undefined || value === String(expected), 'XML ' + name + ' count mismatch');
}
function passing(node, count, required = true) {
    counter(node, 'tests', count);
    for (const name of ['failures', 'errors', 'skipped']) counter(node, name, 0, required);
    for (const name of ['disabled', 'flakyFailures', 'flakyErrors', 'rerunFailures', 'rerunErrors']) counter(node, name, 0, false);
}
function scripts(item, kind) {
    return (item.event || []).filter(event => event.listen === kind).map(event => {
        const code = event.script?.exec; need(Array.isArray(code) && code.every(line => typeof line === 'string'), 'Invalid collection script');
        return code.join('\n').replace(/\r\n/g, '\n');
    }).join('\n');
}
function requests(collection) {
    const result = [];
    function walk(node, ancestors, depth) {
        need(depth < 9, 'Collection depth exceeded');
        const chain = [...ancestors, node];
        if (node.request) { need(result.length < 256, 'Collection request budget exceeded'); result.push({ node, chain }); }
        else { need(Array.isArray(node.item), 'Invalid collection node'); for (const item of node.item) walk(item, chain, depth + 1); }
    }
    walk(collection, [], 0); sameMembers(result.map(entry => entry.node.name), result.map(entry => entry.node.name), 'Request names');
    return result;
}
function titles(entry) {
    const code = entry.chain.map(node => scripts(node, 'test')).join('\n');
    const found = [...code.matchAll(/\bpm\.test\(\s*(['"])([^'"\\\r\n]*)\1\s*,/g)].map(match => match[2]);
    need(found.length > 0 && found.length === [...code.matchAll(/\bpm\.test\s*\(/g)].length,
        'Only bounded literal assertion titles are supported');
    sameMembers(found, found, 'Assertion titles'); return found;
}
function url(request) {
    if (typeof request.url === 'string') return request.url;
    const value = request.url;
    need(value && Object.keys(value).every(key => ['raw', 'host', 'path', 'query', 'variable'].includes(key)) &&
        Array.isArray(value.host) && value.host.length === 1 && /^\{\{(?:baseUrl|peerBaseUrl)\}\}$/.test(value.host[0]) &&
        Array.isArray(value.path) && (!value.variable || value.variable.length === 0), 'Unsupported Newman URL');
    const query = (value.query || []).map(item => {
        need(typeof item.key === 'string' && typeof item.value === 'string' && !item.disabled &&
            !/[&=#?]/.test(item.key) && !/[&#]/.test(item.value), 'Invalid Newman query');
        return item.key + '=' + item.value;
    });
    need(value.path.every(part => typeof part === 'string' && !/[\/#?]/.test(part)), 'Invalid Newman path');
    const raw = value.host[0] + '/' + value.path.join('/') + (query.length ? '?' + query.join('&') : '');
    need(value.raw === undefined || value.raw === raw, 'Conflicting Newman raw URL'); return raw;
}
function requestContract(entry) {
    const node = entry.node;
    return [node.name, node.request.method, url(node.request), node.request.body?.raw ?? '',
        entry.chain.map(parent => [scripts(parent, 'prerequest'), scripts(parent, 'test')])];
}

export function sampleRuntimeContracts(read, maskJava) {
    const matrix = JSON.parse(read('tools/samples/v019-scenarios.json')), result = [];
    sameMembers(matrix.providers, providers, 'Providers'); sameMembers(matrix.qualificationLanes.map(lane => lane.id), Object.keys(lanes), 'Lanes');
    for (const provider of providers) for (const lane of matrix.qualificationLanes) {
        const expected = lanes[lane.id], prefix = 'sample-cache-database-' + provider + '/';
        sameMembers(lane.suites.map(suite => path.posix.basename(suite.file, '.java')), Object.keys(expected.suites), 'Suites');
        const suites = lane.suites.map(suite => {
            const source = maskJava(read(prefix + suite.file)), type = /\bpackage\s+([\w.]+)\s*;/.exec(source)?.[1];
            const methods = [...source.matchAll(/@Test\s+(?:@SuppressWarnings\(\s*\)\s+)?(?:public\s+)?void\s+(\w+)\s*\(/g)].map(match => match[1]);
            const name = path.posix.basename(suite.file, '.java');
            need(type && methods.length === expected.suites[name] && suite.cases === methods.length &&
                !/@(?:Disabled|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/.test(source), 'Unsupported actual JUnit roster');
            sameMembers(methods, methods, 'Methods'); return { type: type + '.' + name, methods };
        });
        const collection = JSON.parse(read(prefix + 'postman/cache-database-' + provider + '-' + expected.stem + '.postman_collection.json'));
        const entries = requests(collection); need(entries.length === expected.requests, 'Collection request count mismatch');
        for (const entry of entries) titles(entry);
        result.push({ ...expected, provider, lane: lane.id, suites, collection });
    }
    return result;
}
function junit(text, suite) {
    const root = xml(text); need(root.name === 'testsuite' && root.attrs.name === suite.type, 'Wrong JUnit suite');
    const cases = children(root, 'testcase'); passing(root, suite.methods.length);
    need(cases.length === suite.methods.length, 'JUnit testcase count mismatch');
    reportShape(root, new Set(cases));
    for (const entry of cases) need(entry.attrs.classname === suite.type &&
        (entry.attrs.status === undefined || entry.attrs.status === 'passed'), 'JUnit testcase class/status mismatch');
    sameMembers(cases.map(entry => entry.attrs.name), suite.methods, 'JUnit methods');
}
function reportShape(root, cases) {
    function walk(node) {
        need(node === root || node.name !== 'testcase' || cases.has(node), 'Nested/extra testcase rejected');
        need(node === root || !['testsuite', 'testsuites'].includes(node.name), 'Nested/extra suite rejected');
        for (const child of node.children) walk(child);
    }
    walk(root);
}
function newmanJunit(text, entries) {
    const root = xml(text); need(root.name === 'testsuites', 'Newman testsuites root required');
    const suites = children(root, 'testsuite'); need(suites.length === entries.length, 'Newman suite count mismatch');
    let total = 0;
    for (let i = 0; i < entries.length; i++) {
        const suite = suites[i], expected = titles(entries[i]), cases = children(suite, 'testcase');
        reportShape(suite, new Set(cases));
        need(suite.attrs.name === entries[i].node.name && cases.length === expected.length, 'Newman request/order/count mismatch');
        passing(suite, expected.length, false); sameMembers(cases.map(entry => entry.attrs.name), expected, 'Newman JUnit assertions');
        for (const entry of cases) need(entry.attrs.status === undefined || entry.attrs.status === 'passed', 'Nonpassing Newman assertion');
        total += cases.length;
    }
    need(root.children.every(node => node.name === 'testsuite'), 'Unexpected Newman report element');
    // Newman counts requests at the root and assertions within each request suite.
    counter(root, 'tests', suites.length, false);
    for (const name of ['failures', 'errors', 'skipped']) counter(root, name, 0, false);
    return total;
}
function newman(report, contract) {
    const expected = requests(contract.collection), actual = requests(report.collection);
    need(equal(actual.map(requestContract), expected.map(requestContract)), 'Executed Newman collection contract differs');
    const run = report.run;
    need(run && Array.isArray(run.failures) && run.failures.length === 0 && !run.error &&
        Array.isArray(run.executions) && run.executions.length === expected.length, 'Missing/failed/extra Newman executions');
    let assertions = 0;
    for (let i = 0; i < expected.length; i++) {
        const execution = run.executions[i], names = titles(expected[i]);
        need(execution.item?.name === expected[i].node.name && execution.response &&
            Array.isArray(execution.assertions), 'Newman request order/response mismatch');
        const status = /pm\.response\.to\.have\.status\((\d{3})\)/.exec(scripts(expected[i].node, 'test'))?.[1];
        need(status && execution.response.code === Number(status), 'Newman actual HTTP status mismatch');
        sameMembers(execution.assertions.map(assertion => assertion.assertion), names, 'Newman assertions');
        for (const assertion of execution.assertions) need(!assertion.skipped && !assertion.error, 'Failed/skipped Newman assertion');
        assertions += names.length;
    }
    for (const name of ['requests', 'assertions', 'prerequestScripts', 'testScripts']) {
        const stats = run.stats?.[name];
        need(stats && stats.failed === 0 && Number.isSafeInteger(stats.total) && stats.total >= 0 &&
            ['pending', 'skipped'].every(key => stats[key] === undefined || stats[key] === 0), 'Missing/failed/pending Newman counters');
    }
    need(run.stats.requests.total === expected.length && run.stats.assertions.total === assertions &&
        (!run.stats.assertions.pending) && (!run.stats.requests.pending), 'Newman declared/actual totals mismatch');
    return assertions;
}
function instant(value) {
    need(typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(value), 'UTC timestamp required');
    const result = Date.parse(value);
    need(Number.isFinite(result) && new Date(result).toISOString().replace('.000Z', 'Z') === value.replace('.000Z', 'Z'), 'Invalid UTC timestamp');
    return result;
}
function requireTree(checkout, member, required, optional = false) {
    let root;
    try { root = fs.lstatSync(path.join(checkout, member)); }
    catch (error) {
        if (error.code !== 'ENOENT') throw error;
        need(optional, 'Required source tree missing: ' + member);
        return;
    }
    need(!root.isSymbolicLink() && root.isDirectory(), 'Source tree must be a non-symlink directory: ' + member);
    let visited = 0, files = 0;
    function walk(name, depth) {
        need(depth <= 24 && required.size < 10000 && ++visited <= 20000, 'Source closure budget exceeded');
        const full = path.join(checkout, name), stat = fs.lstatSync(full);
        need(!stat.isSymbolicLink(), 'Symlink source input rejected');
        if (stat.isFile()) { required.add(name); files++; }
        else {
            need(stat.isDirectory(), 'Unsupported source input');
            const entries = fs.readdirSync(full); need(entries.length <= 10000, 'Source directory budget exceeded');
            for (const entry of entries) walk(name + '/' + entry, depth + 1);
        }
    }
    walk(member, 0);
    need(optional || files > 0, 'Required source tree empty: ' + member);
}

/** The coordinator-pinned receipt is the provenance trust anchor, never an asserted PASS bit. */
function verifyExecutionOverlay(evidence, receipt, source) {
    const buildPin = '4c714758f699ef767dc3bec5c53a9c9f910e0ea624fa900fa0e5a37c0d67e5c1';
    const sdkPin = '3607c566c5b38790487e69fa8eeffd9ec43709f0ffcc49bec74458f4a8be0fd3';
    const proof = receipt.sdk.executionOverlay;
    keys(proof, ['kind', 'buildSourceManifest', 'delta'], 'Execution overlay proof');
    need(proof.kind === 'SAMPLE_DIAGNOSTICS_AND_ROSTER_141' &&
        receipt.sdk.sourceManifestSha256 === buildPin && proof.buildSourceManifest.sha256 === buildPin &&
        receipt.sdk.manifest.sha256 === sdkPin, 'Unapproved execution overlay SDK/source authority');
    const original = manifest(evidence.text(proof.buildSourceManifest, 2 * 1024 * 1024, true));
    const delta = evidence.json(proof.delta, 64 * 1024, true);
    keys(delta, ['sdkBuildSourceManifestSha256', 'qualificationSourceManifestSha256', 'sdkRebuilt',
        'frameworkMainAndPomsUnchanged', 'changes'], 'Execution overlay delta');
    need(delta.sdkBuildSourceManifestSha256 === buildPin &&
        delta.qualificationSourceManifestSha256 === receipt.source.manifest.sha256 &&
        delta.sdkRebuilt === false && delta.frameworkMainAndPomsUnchanged === true &&
        original.size === 2520 && source.size === 2520, 'Execution overlay binding mismatch');
    sameMembers([...source.keys()], [...original.keys()], 'Execution overlay full source inventory');
    const allowed = providers.flatMap(provider => [
        `sample-cache-database-${provider}/src/purge/java/com/example/cachedb/purge/TenantPurgeRuntime.java`,
        `sample-cache-database-${provider}/src/purge-test/java/com/example/cachedb/purge/TenantPurgeContractTest.java`
    ]).concat(['tools/samples/v019-scenarios.json', 'tools/ci/verify-v019-sample-runtime.mjs',
        'tools/ci/check-v019-sample-coverage.mjs']);
    const changed = [...source.keys()].filter(member => source.get(member) !== original.get(member));
    sameMembers(changed, allowed, 'Execution overlay changed paths');
    need(Array.isArray(delta.changes), 'Execution overlay changes required');
    sameMembers(delta.changes.map(change => change.path), allowed, 'Execution overlay exact delta');
    for (const change of delta.changes) {
        keys(change, ['path', 'before', 'after', 'kind'], 'Execution overlay change');
        need(change.before === original.get(change.path) && change.after === source.get(change.path) &&
            change.kind === (change.path.startsWith('sample-') ? 'SAMPLE_DIAGNOSTICS' : 'ROSTER_141'),
            'Execution overlay member mismatch');
    }
}

// A separately pinned documentation delta never replaces the original receipt or SDK provenance.
function verifySourceEquivalence(evidence, reference, original, receiptSha256, sourceManifestSha256) {
    const proof = evidence.json(reference, 64 * 1024, true);
    keys(proof, ['schema', 'kind', 'receiptSha256', 'originalSourceManifestSha256', 'currentSourceManifest', 'changes'],
        'Source equivalence');
    need(proof.schema === 1 && proof.kind === 'cachedb-v019-documentation-source-equivalence', 'Unsupported source equivalence schema');
    need(proof.receiptSha256 === receiptSha256 && proof.originalSourceManifestSha256 === sourceManifestSha256,
        'Source equivalence receipt/source binding mismatch');
    const current = manifest(evidence.text(proof.currentSourceManifest, 2 * 1024 * 1024, true));
    sameMembers([...current.keys()], [...original.keys()], 'Source equivalence full inventory');
    const changed = [...original.keys()].filter(member => original.get(member) !== current.get(member));
    need(changed.length > 0 && Array.isArray(proof.changes) && proof.changes.length <= 128, 'Bounded nonempty documentation delta required');
    sameMembers(proof.changes.map(change => relative(change.path)), changed, 'Source equivalence exact changed paths');
    const changes = proof.changes.map(change => {
        keys(change, ['path', 'before', 'after'], 'Source equivalence change');
        const member = change.path;
        const documentation = ['README.md', 'CHANGELOG.md', 'tr/README.md'].includes(member) ||
            /^(?:docs|tr\/docs)\/.+\.md$/.test(member) ||
            providers.some(provider => member === `sample-cache-database-${provider}/README.md` ||
                member === `sample-cache-database-${provider}/README.tr.md`);
        need(documentation && !member.split('/').some(part => ['src', 'scripts', 'postman'].includes(part)),
            'Source equivalence permits documentation only: ' + member);
        need(change.before === original.get(member) && change.after === current.get(member), 'Source equivalence member digest mismatch');
        return { path: member, before: change.before, after: change.after };
    });
    return { current, record: { schema: 1, kind: proof.kind, manifest: { ...reference }, receiptSha256,
        originalSourceManifestSha256: sourceManifestSha256, currentSourceManifest: { ...proof.currentSourceManifest }, changes } };
}

const executionToolingMembers = new Map([
    ['tools/ci/verify-v019-sample-runtime.mjs', ['94cd05a20a2646399ebd4bb61e78880567df33fd4f6016ad9690cba7971ec619', 'SOURCE_BINDING_GATE_AND_QUALIFICATION_CHAIN']],
    ['tools/ci/test-v019-sample-runtime.mjs', ['954efdcbb6b26bb9f27920cfee20f7da753aea0fec6bf51283702e76ac9858be', 'SYNTHETIC_SOURCE_BINDING_NEGATIVE_CONTROLS']],
    ['tools/ci/test-v019-sample-matrix.mjs', ['b53c831bccb38b07fb1342944d44d538cb620ba7e504eadec824ad2406d6150e', 'PRODUCER_SUMMARY_PATH_NEGATIVE_CONTROLS']],
    ['tools/k8s/SampleRuntimeEvidence.java', ['0fdb8fb6518717dc39a644b2a60470b2fe2789cc8b1a025f02cd5caea2fb13e4', 'REPORT_SUMMARY_UNDER_SOURCE_TARGET_ONLY']],
    ['.github/workflows/framework-readiness.yml', ['25dc4c3cb30fbfb9ae70052980f5448c04123cbf598721e7a6af4edbc18a30cd', 'PINNED_PUBLIC_QUALIFICATION_INPUTS_PRIVATE_RESULTS']],
    ['tools/ci/check-v019-sample-coverage.ps1', ['8be23cadbeb23fa9fc2ad8996a68450ca0513681b015b0744f2cda56fd4d561d', 'PAIRED_REFERENCE_WRAPPER_FORWARDING']],
    ['tools/ci/check-v019-sample-coverage.mjs', ['5da7f47788e6e338e7ec314b6d040b0b6f16a9506909323e7fd62eb280f714e4', 'PAIRED_REFERENCE_CLI_FORWARDING_PRIVATE_CHAIN']],
    ['tools/ci/test-v019-runtime-workflow.ps1', ['90795c4b93fa9240b928c374d9563fd6e82fc0fc5db23f0c1e14deca78e912ed', 'PUBLIC_QUALIFICATION_PLUMBING_AND_RELEASE_ARCHIVE_NEGATIVE_CONTROLS']],
    ['tools/release/build-release-bundle.ps1', ['c13d2c3f1ada19d2aea2a3c247af7d00b4305d805f7512ea0f863ffb7ba1b927', 'ZIP_INCLUDES_HIDDEN_MEMBERS_NO_PAYLOAD_SELECTION_CHANGE']]
]);
const privateEvidenceStager = 'tools/ci/stage-v019-private-evidence.ps1';
const finalSourceToolingMembers = new Map([
    ...executionToolingMembers,
    ['.github/workflows/framework-readiness.yml', ['25dc4c3cb30fbfb9ae70052980f5448c04123cbf598721e7a6af4edbc18a30cd', 'PRIVATE_ONLY_RETAINED_EVIDENCE_UPLOAD_AND_PUBLIC_ADMISSION']],
    ['tools/ci/import-v019-private-owned-runtime.ps1', ['70a2631e854e4ecc58cd835fb111f589b2a51a86b9785dd7bef333e5624f77b1', 'PRIVATE_OWNED_IMPORT_SEVEN_FUNCTIONS_PAIRED_REFS']],
    ['tools/ci/private-v019-runtime.workflow.yml.template', ['6c7e012d81edebbb060644f052290dfa521e33da294fcb2aaec6ae875c1eee8f', 'PRIVATE_IMPORT_TEMPLATE_PAIRED_REFS']],
    [privateEvidenceStager, [null, 'PRIVATE_ONLY_PINNED_IMMUTABLE_EVIDENCE_UPLOAD']],
    ['tools/ci/test-v019-private-owned-runtime.ps1', ['862be7ec1c2e22254a2947888a9f795e09fae7c7d30d5a9c249631643ba7be31', 'PRIVATE_EVIDENCE_UPLOAD_NEGATIVE_CONTROLS']],
    ['tools/ci/test-release-module-packaging.ps1', ['a65ff14c46383e266b9915e9feded47111fee9b4fd3d5e5cbedd03800ab09eea', 'RELEASE_MODULE_HIDDEN_MEMBERS_NEGATIVE_CONTROLS']],
    ['tools/ci/private-v019-runtime.md', ['ec2b82c54f71bf2ebb7020fcd55d6189ca37dd49903c1177624b0676fbcf5f91', 'EXACT_PRIVATE_EVIDENCE_OPERATOR_DOCUMENTATION']],
    ['cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisStandaloneExecAclTest.java', ['7779060e8ead53d259697c866e7a4e6643d47fc882d23e519f65751a3a6da38b', 'EXACT_REDIS_ACL_TEST_FIXTURE_ONLY']],
    ['cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisStandaloneIndexAclTest.java', ['fd6892f98afa7917872adaef635c2a910ff682325bfa95fe244e29a83233dc02', 'EXACT_REDIS_ACL_TEST_FIXTURE_ONLY']]
]);
const reportSummaryBefore = 'out.resolve("coverage.md")';
const reportSummaryAfter = 'source.resolve("target/sample-runtime-coverage.md")';
const toolingQualificationProducerPin = '00e618511f80a1541291dfaf6e2f9bbbd32b4878498e4fe2f7d4749305374fb0';
function runtimeInputIdentity(source) {
    const names = [...source.keys()].filter(name => name === 'pom.xml' || name.endsWith('/pom.xml') ||
        /(?:^|\/)src\/main\//.test(name) || providers.some(provider =>
            name.startsWith('sample-cache-database-' + provider + '/') && /^(?:src|scripts|postman)\//.test(name.slice(('sample-cache-database-' + provider + '/').length)))).sort();
    return { members: names.length, sha256: digest(names.map(name => source.get(name) + '  ' + name + '\n').join('')) };
}

// The caller-pinned overlay is a separate authority from documentation equivalence.
// Exported for isolated contract controls; only verifySampleRuntime qualifies raw runtime evidence.
export function verifyExecutionToolingOverlay({ evidence, reference, original, documented, receiptSha256,
    sourceManifestSha256, documentationSourceManifestSha256, sdkManifestSha256, readProducer }) {
    need(receiptSha256 === '03ff2f520571af3ef57e554a793fc589039e0c1fec37c655d5148e4096397f13' &&
        sourceManifestSha256 === '95bd490a15e4f17f89965667d31635c4a343a671e45ecd1b790c2766a95a6d34' &&
        sdkManifestSha256 === '7e934d3f27a586104341765752454874a87eb4a04077a124a9acac092620eed2',
        'Execution tooling overlay requires original E receipt/source/SDK authority');
    const proof = evidence.json(reference, 64 * 1024, true);
    keys(proof, ['schema', 'kind', 'receiptSha256', 'originalSourceManifestSha256', 'documentationSourceManifestSha256',
        'currentSourceManifest', 'sdkManifestSha256', 'runtimeInputs', 'declarations', 'changes'], 'Execution tooling overlay');
    const finalSource = proof.schema === 4 && proof.kind === 'cachedb-v019-e-to-final-source-execution-tooling-overlay';
    need(finalSource || proof.schema === 3 && proof.kind === 'cachedb-v019-e-to-g-execution-tooling-overlay', 'Unsupported execution tooling overlay schema');
    need(proof.receiptSha256 === receiptSha256 && proof.originalSourceManifestSha256 === sourceManifestSha256 &&
        proof.documentationSourceManifestSha256 === documentationSourceManifestSha256 &&
        proof.sdkManifestSha256 === sdkManifestSha256, 'Execution tooling overlay receipt/source/SDK binding mismatch');
    const declarations = { sdkRebuilt: false, originalEvidenceUnmodified: true, runtimeInputsByteEquivalent: true,
        toolingScope: 'SOURCE_BINDING_PUBLIC_ADMISSION_RELEASE_ARCHIVE_AND_NEGATIVE_CONTROLS_ONLY', publicAdmission: 'EXPLICIT_PAIRED_REFS_PRIVATE_RESULTS_ONLY',
        producerChange: 'REPORT_SUMMARY_UNDER_SOURCE_TARGET_ONLY', archiveChange: 'ZIP_INCLUDES_HIDDEN_MEMBERS_NO_PAYLOAD_SELECTION_CHANGE', releaseAccepted: false };
    if (finalSource) Object.assign(declarations, {
        toolingScope: 'EXACT_PRIVATE_EVIDENCE_TOOLING_ARCHIVE_AND_NAMED_ACL_FIXTURES_ONLY',
        privateEvidenceTransport: 'IMMUTABLE_RECEIPT_AND_RESULTS_NO_WORKLOAD_REGENERATION',
        addedSource: 'EXACT_PRIVATE_EVIDENCE_STAGER_ONLY', testFixtureScope: 'TWO_NAMED_REDIS_ACL_TESTS_NOT_RUNTIME_OR_DOCUMENTATION'
    });
    keys(proof.declarations, Object.keys(declarations), 'Execution tooling declarations');
    need(Object.entries(declarations).every(([name, value]) => proof.declarations[name] === value), 'Execution tooling declaration mismatch');
    const current = manifest(evidence.text(proof.currentSourceManifest, 2 * 1024 * 1024, true));
    sameMembers([...documented.keys()], [...original.keys()], 'Execution tooling documentation inventory');
    sameMembers([...current.keys()], [...original.keys(), ...(finalSource ? [privateEvidenceStager] : [])], 'Execution tooling full source inventory');
    need(original.size === 2569 && (!finalSource || !original.has(privateEvidenceStager)), 'Execution tooling exact E source roster required');
    const members = finalSource ? finalSourceToolingMembers : executionToolingMembers;
    const allowed = [...members.keys()];
    sameMembers([...current.keys()].filter(name => current.get(name) !== documented.get(name)), allowed, 'Execution tooling exact changed paths');
    need(Array.isArray(proof.changes), 'Execution tooling changes required');
    sameMembers(proof.changes.map(change => relative(change.path)), allowed, 'Execution tooling exact delta');
    for (const change of proof.changes) {
        keys(change, ['path', 'before', 'after', 'semanticDelta'], 'Execution tooling change');
        const [before, semanticDelta] = members.get(change.path);
        need(change.before === before && change.before === (original.get(change.path) ?? null) && change.before === (documented.get(change.path) ?? null) &&
            change.after === current.get(change.path) && hex(change.after) && change.after !== before && change.semanticDelta === semanticDelta,
            'Execution tooling member digest/semantic declaration mismatch: ' + change.path);
    }
    need(current.get('tools/ci/test-v019-sample-matrix.mjs') === 'e71c3eb524a92bd05d92f223fb132aab2e7a02222adcd783499391df9d209ea6' &&
        current.get('tools/k8s/SampleRuntimeEvidence.java') === '00e618511f80a1541291dfaf6e2f9bbbd32b4878498e4fe2f7d4749305374fb0',
        'Execution tooling reviewed producer/matrix digest mismatch');
    need(current.get('tools/release/build-release-bundle.ps1') === '1b9974fa4f16605bfbac6536815db21ee2721a2dc2a4034c8c3dd7932b69f3b2',
        'Execution tooling reviewed release archive digest mismatch');
    const oldInputs = runtimeInputIdentity(original), newInputs = runtimeInputIdentity(current);
    keys(proof.runtimeInputs, ['members', 'originalSha256', 'currentSha256'], 'Execution tooling runtime inputs');
    need(oldInputs.members === 1599 && newInputs.members === 1599 && proof.runtimeInputs.members === 1599 &&
        oldInputs.sha256 === newInputs.sha256 && proof.runtimeInputs.originalSha256 === oldInputs.sha256 &&
        proof.runtimeInputs.currentSha256 === newInputs.sha256, 'Execution tooling runtime input identity mismatch');
    const producer = 'tools/k8s/SampleRuntimeEvidence.java', bytes = readProducer();
    need(digest(bytes.before) === original.get(producer) && digest(bytes.after) === current.get(producer), 'Execution tooling producer byte digest mismatch');
    const before = new TextDecoder('utf-8', { fatal: true }).decode(bytes.before);
    const after = new TextDecoder('utf-8', { fatal: true }).decode(bytes.after);
    need(before.split(reportSummaryBefore).length === 2 && before.replace(reportSummaryBefore, reportSummaryAfter) === after,
        'Execution tooling producer semantic delta is not the single reviewed report-directory fix');
    return { current, record: { ...proof, manifest: { ...reference } } };
}

// sourceEquivalence is a coordinator-pinned { path, sha256 } reference relative to the receipt directory.
// It qualifies only the current checkout; all original SDK/phase/source evidence remains bound to the receipt.
export function verifySampleRuntime({ repositoryRoot, receiptPath, receiptSha256, contracts, requiredSourceFiles = [],
    requiredSourceDigests = new Map(), sourceEquivalence, executionToolingOverlay }) {
    need(hex(receiptSha256), 'Coordinator-pinned receipt SHA256 required');
    const absolute = path.resolve(receiptPath), root = path.dirname(absolute);
    need(fs.realpathSync(root) === root && !fs.lstatSync(absolute).isSymbolicLink(), 'Canonical non-symlink evidence root required');
    const evidence = new Evidence(root), receiptRef = { path: path.basename(absolute), sha256: receiptSha256 };
    const receipt = evidence.json(receiptRef, 1024 * 1024);
    keys(receipt, ['schema', 'kind', 'source', 'sdk', 'execution', 'cells'], 'Runtime receipt');
    need(receipt.schema === 1 && receipt.kind === 'cachedb-v019-sample-runtime', 'Unsupported runtime receipt schema');
    keys(receipt.source, ['root', 'manifest'], 'Source binding');
    const overlay = Object.hasOwn(receipt.sdk, 'executionOverlay');
    keys(receipt.sdk, ['root', 'manifest', 'sourceManifestSha256', ...(overlay ? ['executionOverlay'] : [])], 'SDK binding');
    const sourceRoot = relative(receipt.source.root), sdkRoot = relative(receipt.sdk.root);
    const sourceAlias = sourceRoot.toLowerCase(), sdkAlias = sdkRoot.toLowerCase();
    need(sourceAlias !== sdkAlias && !sourceAlias.startsWith(sdkAlias + '/') && !sdkAlias.startsWith(sourceAlias + '/'), 'Source/SDK roots overlap');
    const source = manifest(evidence.text(receipt.source.manifest, 2 * 1024 * 1024, true));
    const sdk = manifest(evidence.text(receipt.sdk.manifest, 2 * 1024 * 1024, true));
    if (overlay) verifyExecutionOverlay(evidence, receipt, source);
    else need(receipt.sdk.sourceManifestSha256 === receipt.source.manifest.sha256, 'SDK build source binding mismatch');
    const equivalence = sourceEquivalence === undefined ? null : verifySourceEquivalence(evidence, sourceEquivalence, source,
        receiptSha256, receipt.source.manifest.sha256);
    const checkout = fs.realpathSync(repositoryRoot);
    let toolingQualification = null;
    if (executionToolingOverlay !== undefined) {
        need(!overlay && receiptSha256 === '03ff2f520571af3ef57e554a793fc589039e0c1fec37c655d5148e4096397f13' &&
            receipt.source.manifest.sha256 === '95bd490a15e4f17f89965667d31635c4a343a671e45ecd1b790c2766a95a6d34' &&
            receipt.sdk.manifest.sha256 === '7e934d3f27a586104341765752454874a87eb4a04077a124a9acac092620eed2' && sdk.size === 92,
            'Execution tooling overlay requires the unchanged original E receipt/source/SDK authority');
        need(equivalence, 'Execution tooling overlay requires an explicit documentation qualification stage');
        toolingQualification = verifyExecutionToolingOverlay({ evidence, reference: executionToolingOverlay, original: source,
            documented: equivalence.current, receiptSha256, sourceManifestSha256: receipt.source.manifest.sha256,
            documentationSourceManifestSha256: equivalence.record.currentSourceManifest.sha256,
            sdkManifestSha256: receipt.sdk.manifest.sha256, readProducer: () => {
                const member = 'tools/k8s/SampleRuntimeEvidence.java';
                const before = evidence.text({ path: sourceRoot + '/' + member, sha256: source.get(member) }, 2 * 1024 * 1024);
                const full = safeFile(checkout, member);
                need(fileHash(full, 2 * 1024 * 1024).sha256 === toolingQualificationProducerPin, 'Execution tooling current producer digest mismatch');
                const after = fs.readFileSync(full); need(after.length <= 2 * 1024 * 1024, 'Execution tooling producer byte budget exceeded');
                return { before: Buffer.from(before), after };
            } });
    }
    const currentSource = toolingQualification ? toolingQualification.current : equivalence ? equivalence.current : source;
    const sourceDifferences = [];
    let changedSourceFiles = 0;
    for (const [member, sha256] of source) {
        evidence.file({ path: sourceRoot + '/' + member, sha256 });
    }
    for (const [member, sha256] of currentSource) {
        if (fileHash(safeFile(checkout, member), 64 * 1024 * 1024).sha256 !== sha256) {
            changedSourceFiles++;
            if (sourceDifferences.length < 32) sourceDifferences.push(member);
        }
    }
    need(changedSourceFiles === 0, (equivalence ? 'Requalified source' : 'Frozen source') + ' differs from checkout (' + changedSourceFiles + ' files): ' +
        sourceDifferences.join(', ') + (changedSourceFiles > sourceDifferences.length
            ? '; ' + (changedSourceFiles - sourceDifferences.length) + ' additional paths omitted' : ''));
    const required = new Set(['pom.xml', 'tools/ci/check-v019-sample-coverage.mjs',
        'tools/ci/check-v019-sample-coverage.ps1', 'tools/ci/verify-v019-sample-runtime.mjs', 'tools/ci/test-v019-sample-runtime.mjs',
        'tools/ci/test-v019-sample-matrix.mjs', ...requiredSourceFiles]);
    if (toolingQualification) requireTree(checkout, 'src/main', required, true);
    for (const [member, sha256] of requiredSourceDigests) need(currentSource.get(member) === sha256, 'Static/runtime input changed: ' + member);
    const rootPom = xml(fs.readFileSync(safeFile(checkout, 'pom.xml'), 'utf8'));
    need(children(rootPom, 'groupId')[0]?.text.trim() === 'com.reactor.cachedb' &&
        children(rootPom, 'version')[0]?.text.trim() === '0.19.0', 'Unexpected reactor version/group');
    const modules = children(rootPom, 'modules'); need(modules.length === 1, 'Exact reactor modules required');
    const moduleNames = children(modules[0], 'module').map(node => relative(node.text.trim()));
    sameMembers(moduleNames, moduleNames, 'Reactor modules');
    const sdkMembers = [];
    for (const module of ['', ...moduleNames]) {
        const member = module ? module + '/pom.xml' : 'pom.xml'; required.add(member);
        const pom = xml(fs.readFileSync(safeFile(checkout, member), 'utf8'));
        const artifacts = children(pom, 'artifactId'), packaging = children(pom, 'packaging');
        need(pom.name === 'project' && artifacts.length === 1 && packaging.length <= 1 &&
            /^[a-z0-9-]+$/.test(artifacts[0].text.trim()), 'Invalid reactor coordinate');
        const pomOnly = packaging[0]?.text.trim() === 'pom';
        if (module) requireTree(checkout, module + '/src', required, pomOnly);
        const artifact = artifacts[0].text.trim(), base = 'com/reactor/cachedb/' + artifact + '/0.19.0/' + artifact + '-0.19.0';
        sdkMembers.push(base + '.pom');
        if (!pomOnly) {
            sdkMembers.push(base + '.jar');
            if (artifact !== 'cachedb-integration-tests') sdkMembers.push(base + '-sources.jar', base + '-javadoc.jar');
        }
    }
    for (const provider of providers) {
        const prefix = 'sample-cache-database-' + provider;
        required.add(prefix + '/pom.xml');
        for (const directory of ['src', 'scripts', 'postman']) requireTree(checkout, prefix + '/' + directory, required);
    }
    for (const member of required) need(source.has(member), 'Frozen source manifest omits required input: ' + member);
    sameMembers([...sdk.keys()], sdkMembers, 'Final SDK coordinate/classifier roster');
    for (const [member, sha256] of sdk) evidence.file({ path: sdkRoot + '/' + member, sha256 });
    const run = receipt.execution;
    keys(run, ['runId', 'namespace', 'runnerPodUid', 'startedAt', 'finishedAt', 'cutoffAt', 'cpuMillicores', 'memoryBytes'], 'Execution binding');
    need(typeof run.runId === 'string' && /^[a-zA-Z0-9_-]{8,128}$/.test(run.runId) &&
        /^cachedb-[a-z0-9-]+$/.test(run.namespace) && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(run.runnerPodUid) &&
        Number.isSafeInteger(run.cpuMillicores) && run.cpuMillicores > 0 &&
        Number.isSafeInteger(run.memoryBytes) && run.memoryBytes > 0, 'Invalid execution identity/resources');
    const start = instant(run.startedAt), finish = instant(run.finishedAt), cutoff = instant(run.cutoffAt);
    need(start <= finish && finish <= cutoff, 'Execution exceeded its original cutoff');
    need(Array.isArray(contracts) && contracts.length === 9 && Array.isArray(receipt.cells) && receipt.cells.length === 9, 'Exact nine provider/lane cells required');
    const expectedCells = providers.flatMap(provider => Object.keys(lanes).map(lane => provider + '/' + lane));
    sameMembers(contracts.map(cell => cell.provider + '/' + cell.lane), expectedCells, 'Source contracts');
    sameMembers(receipt.cells.map(cell => cell.provider + '/' + cell.lane), expectedCells, 'Runtime cells');
    const fixtures = new Set(), qualified = [];
    for (const cell of receipt.cells) {
        keys(cell, ['provider', 'lane', 'sourceManifestSha256', 'sdkManifestSha256', 'tests', 'http'], 'Runtime cell');
        need(cell.sourceManifestSha256 === receipt.source.manifest.sha256 && cell.sdkManifestSha256 === receipt.sdk.manifest.sha256,
            'Mixed source/SDK cell binding');
        const contract = contracts.find(entry => entry.provider === cell.provider && entry.lane === cell.lane);
        for (const [purpose, phase] of [['tests', cell.tests], ['http', cell.http]]) {
            const fields = ['fixtureId', 'startedAt', 'finishedAt', 'exit', 'preservationExit', 'sourceBefore', 'sourceAfter', 'sdkBefore', 'sdkAfter'];
            keys(phase, [...fields, ...(purpose === 'tests' ? ['reports'] : cell.lane === 'tenant-purge' ? ['junit'] : ['junit', 'newman'])], 'Phase');
            const first = instant(phase.startedAt), last = instant(phase.finishedAt);
            need(first >= start && last >= first && last <= finish && last - first <= 900000, 'Phase timing/bound mismatch');
            need(hex(phase.fixtureId) && !fixtures.has(phase.fixtureId), 'TEST/HTTP/provider fixtures alias'); fixtures.add(phase.fixtureId);
            for (const name of ['exit', 'preservationExit']) need(/^0\r?\n?$/.test(evidence.text(phase[name], 16, true)), 'Failed/missing phase exit');
            for (const name of ['sourceBefore', 'sourceAfter', 'sdkBefore', 'sdkAfter']) {
                const expected = name.startsWith('source') ? receipt.source.manifest.sha256 : receipt.sdk.manifest.sha256;
                need(phase[name]?.sha256 === expected, 'Phase source/SDK preservation mismatch'); evidence.file(phase[name], 2 * 1024 * 1024, true);
            }
        }
        need(Array.isArray(cell.tests.reports) && cell.tests.reports.length === contract.suites.length, 'Exact JUnit report roster required');
        const observedSuites = [];
        for (const ref of cell.tests.reports) {
            const text = evidence.text(ref, 16 * 1024 * 1024, true), name = xml(text).attrs.name;
            const suite = contract.suites.find(entry => entry.type === name); need(suite, 'Unexpected JUnit report');
            observedSuites.push(name); junit(text, suite);
        }
        sameMembers(observedSuites, contract.suites.map(suite => suite.type), 'JUnit reports');
        const entries = requests(contract.collection), assertions = newmanJunit(evidence.text(cell.http.junit, 16 * 1024 * 1024, true), entries);
        if (cell.lane !== 'tenant-purge') need(newman(evidence.json(cell.http.newman, 32 * 1024 * 1024, true), contract) === assertions,
            'Newman JSON/JUnit assertion totals differ');
        else need(assertions === 36, 'Exact purge 36-assertion roster required');
        qualified.push({ provider: cell.provider, lane: cell.lane, features: contract.features, tests: contract.tests, requests: contract.requests, assertions });
    }
    evidence.unchanged();
    // Also fence the live checkout for the complete inspection, not just its first read.
    for (const [member, sha256] of currentSource) need(fileHash(safeFile(checkout, member), 64 * 1024 * 1024).sha256 === sha256, 'Checkout changed during verification');
    return { schema: 1, evidence: 'PINNED_FINAL_SAMPLE_RUNTIME', receiptSha256, sourceManifestSha256: receipt.source.manifest.sha256,
        ...(equivalence ? { currentSourceManifestSha256: (toolingQualification || equivalence).record.currentSourceManifest.sha256,
            sourceRequalification: equivalence.record } : {}),
        ...(toolingQualification ? { sourceQualificationChain: { schema: 1, kind: 'cachedb-v019-source-qualification-chain',
            stages: [equivalence.record, toolingQualification.record] } } : {}),
        sdkManifestSha256: receipt.sdk.manifest.sha256, runId: run.runId, tests: 141, requests: 180, cells: qualified,
        scope: 'FOUR_PENDING_FEATURES_ONLY_NOT_FULL_RELEASE_OR_BASELINE_HTTP_ACCEPTANCE' };
}
