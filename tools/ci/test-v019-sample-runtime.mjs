import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { javaMask, validateSampleMatrix, checkSampleCoverage, sampleCoverageOptions, sampleCoveragePrivateResults, sampleCoverageRuntimeIssues } from './check-v019-sample-coverage.mjs';
import { sampleRuntimeContracts, verifySampleRuntime, verifyExecutionToolingOverlay } from './verify-v019-sample-runtime.mjs';

const overlayOnly = process.argv.slice(2).length === 1 && process.argv[2] === '--execution-tooling-overlay';
const finalSourceOnly = process.argv.slice(2).length === 1 && process.argv[2] === '--final-source-overlay';
const originalCellsOnly = process.argv.slice(2).length === 1 && process.argv[2] === '--original-cell-controls';
assert(process.argv.length === 2 || overlayOnly || finalSourceOnly || originalCellsOnly, 'Unsupported fixture selection');

// Synthetic parser fixtures only: these bytes must never become release/runtime evidence.
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'cachedb-runtime-verifier-'));
const checkout = path.join(home, 'checkout'), evidence = path.join(home, 'evidence');
const sha = value => createHash('sha256').update(value).digest('hex');
const escaped = value => String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const ref = (name, bytes) => ({ path: name, sha256: sha(bytes) });
const inputs = new Map();
let sourceBytes = 0;
const read = name => {
    const full = path.join(repository, name), stat = fs.lstatSync(full);
    assert(stat.isFile() && !stat.isSymbolicLink() && stat.size <= 2 * 1024 ** 2, 'Bounded regular source input required');
    const bytes = fs.readFileSync(full), previous = inputs.get(name);
    assert(bytes.length <= 2 * 1024 ** 2, 'Source input grew beyond the fixture file budget');
    if (previous) assert(bytes.equals(previous), 'Source changed while collecting fixture inputs: ' + name);
    else {
        sourceBytes += bytes.length;
        assert(inputs.size < 4096 && sourceBytes <= 32 * 1024 ** 2, 'Fixture source closure budget exceeded');
        inputs.set(name, bytes);
    }
    return bytes.toString('utf8').replace(/\r\n/g, '\n');
};
let visitedSources = 0;
function retainTree(member, optional = false) {
    let root;
    try { root = fs.lstatSync(path.join(repository, member)); }
    catch (error) { if (optional && error.code === 'ENOENT') return; throw error; }
    assert(root.isDirectory() && !root.isSymbolicLink(), 'Actual source directory required: ' + member);
    let files = 0;
    function walk(name, depth) {
        assert(depth <= 24 && ++visitedSources <= 20000, 'Fixture source walk budget exceeded');
        const stat = fs.lstatSync(path.join(repository, name));
        assert(!stat.isSymbolicLink(), 'Source symlink rejected');
        if (stat.isFile()) { read(name); files++; }
        else {
            assert(stat.isDirectory(), 'Unsupported source input');
            const entries = fs.readdirSync(path.join(repository, name));
            assert(entries.length <= 10000, 'Fixture source directory budget exceeded');
            for (const entry of entries) walk(name + '/' + entry, depth + 1);
        }
    }
    walk(member, 0);
    assert(optional || files > 0, 'Actual required source tree empty: ' + member);
}
function write(root, name, bytes) {
    const full = path.join(root, name); fs.mkdirSync(path.dirname(full), { recursive: true }); fs.writeFileSync(full, bytes);
    return ref(name, bytes);
}
function entries(collection) {
    const result = [];
    function walk(node, ancestors) {
        const chain = [...ancestors, node];
        if (node.request) result.push({ node, chain });
        else for (const item of node.item) walk(item, chain);
    }
    walk(collection, []); return result;
}
const scripts = (node, kind) => (node.event || []).filter(event => event.listen === kind).map(event => event.script.exec.join('\n')).join('\n');
const assertions = entry => [...entry.chain.map(node => scripts(node, 'test')).join('\n')
    .matchAll(/\bpm\.test\(\s*(['"])([^'"\\\r\n]*)\1\s*,/g)].map(match => match[2]);
function junit(suite) {
    return '<testsuite name="' + escaped(suite.type) + '" tests="' + suite.methods.length + '" errors="0" failures="0" skipped="0">' +
        suite.methods.map(name => '<testcase classname="' + escaped(suite.type) + '" name="' + name + '"/>').join('') + '</testsuite>';
}
function httpJunit(rows) {
    return '<testsuites tests="' + rows.length + '">' + rows.map(entry => {
        const names = assertions(entry);
        return '<testsuite name="' + escaped(entry.node.name) + '" tests="' + names.length + '" failures="0" errors="0" skipped="0">' +
            names.map(name => '<testcase name="' + escaped(name) + '"/>').join('') + '</testsuite>';
    }).join('') + '</testsuites>';
}
function httpJson(contract) {
    const rows = entries(contract.collection), total = rows.reduce((n, entry) => n + assertions(entry).length, 0);
    return { collection: contract.collection, run: { failures: [], executions: rows.map(entry => ({
        item: entry.node, response: { code: Number(/pm\.response\.to\.have\.status\((\d{3})\)/.exec(scripts(entry.node, 'test'))[1]) },
        assertions: assertions(entry).map(assertion => ({ assertion, skipped: false }))
    })), stats: Object.fromEntries(['requests', 'assertions', 'prerequestScripts', 'testScripts'].map(name => [name, {
        total: name === 'assertions' ? total : rows.length, failed: 0, pending: 0
    }])) } };
}
let cases = 0;
function test(name, operation) {
    try { operation(); cases++; } catch (error) { error.message = name + ': ' + error.message; throw error; }
}
function toolingOverlayControls(finalSource = false) {
    // Synthetic manifest/authority-boundary controls, not SDK bytes or a raw runtime qualification.
    const paths = ['tools/ci/verify-v019-sample-runtime.mjs', 'tools/ci/test-v019-sample-runtime.mjs',
        'tools/ci/test-v019-sample-matrix.mjs', 'tools/k8s/SampleRuntimeEvidence.java', '.github/workflows/framework-readiness.yml',
        'tools/ci/check-v019-sample-coverage.ps1', 'tools/ci/check-v019-sample-coverage.mjs', 'tools/ci/test-v019-runtime-workflow.ps1',
        'tools/release/build-release-bundle.ps1'];
    const oldPins = ['94cd05a20a2646399ebd4bb61e78880567df33fd4f6016ad9690cba7971ec619',
        '954efdcbb6b26bb9f27920cfee20f7da753aea0fec6bf51283702e76ac9858be',
        'b53c831bccb38b07fb1342944d44d538cb620ba7e504eadec824ad2406d6150e',
        '0fdb8fb6518717dc39a644b2a60470b2fe2789cc8b1a025f02cd5caea2fb13e4',
        '25dc4c3cb30fbfb9ae70052980f5448c04123cbf598721e7a6af4edbc18a30cd',
        '8be23cadbeb23fa9fc2ad8996a68450ca0513681b015b0744f2cda56fd4d561d',
        '5da7f47788e6e338e7ec314b6d040b0b6f16a9506909323e7fd62eb280f714e4',
        '90795c4b93fa9240b928c374d9563fd6e82fc0fc5db23f0c1e14deca78e912ed',
        'c13d2c3f1ada19d2aea2a3c247af7d00b4305d805f7512ea0f863ffb7ba1b927'];
    const semantics = ['SOURCE_BINDING_GATE_AND_QUALIFICATION_CHAIN', 'SYNTHETIC_SOURCE_BINDING_NEGATIVE_CONTROLS',
        'PRODUCER_SUMMARY_PATH_NEGATIVE_CONTROLS', 'REPORT_SUMMARY_UNDER_SOURCE_TARGET_ONLY',
        'PINNED_PUBLIC_QUALIFICATION_INPUTS_PRIVATE_RESULTS', 'PAIRED_REFERENCE_WRAPPER_FORWARDING',
        'PAIRED_REFERENCE_CLI_FORWARDING_PRIVATE_CHAIN', 'PUBLIC_QUALIFICATION_PLUMBING_AND_RELEASE_ARCHIVE_NEGATIVE_CONTROLS',
        'ZIP_INCLUDES_HIDDEN_MEMBERS_NO_PAYLOAD_SELECTION_CHANGE'];
    if (finalSource) {
        semantics[4] = 'PRIVATE_ONLY_RETAINED_EVIDENCE_UPLOAD_AND_PUBLIC_ADMISSION';
        paths.push('tools/ci/import-v019-private-owned-runtime.ps1', 'tools/ci/private-v019-runtime.workflow.yml.template',
            'tools/ci/stage-v019-private-evidence.ps1', 'tools/ci/test-v019-private-owned-runtime.ps1',
            'tools/ci/test-release-module-packaging.ps1', 'tools/ci/private-v019-runtime.md',
            'cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisStandaloneExecAclTest.java',
            'cachedb-storage-redis/src/test/java/com/cachedb/redis/RedisStandaloneIndexAclTest.java');
        oldPins.push('70a2631e854e4ecc58cd835fb111f589b2a51a86b9785dd7bef333e5624f77b1',
            '6c7e012d81edebbb060644f052290dfa521e33da294fcb2aaec6ae875c1eee8f', null,
            '862be7ec1c2e22254a2947888a9f795e09fae7c7d30d5a9c249631643ba7be31',
            'a65ff14c46383e266b9915e9feded47111fee9b4fd3d5e5cbedd03800ab09eea',
            'ec2b82c54f71bf2ebb7020fcd55d6189ca37dd49903c1177624b0676fbcf5f91',
            '7779060e8ead53d259697c866e7a4e6643d47fc882d23e519f65751a3a6da38b',
            'fd6892f98afa7917872adaef635c2a910ff682325bfa95fe244e29a83233dc02');
        semantics.push('PRIVATE_OWNED_IMPORT_SEVEN_FUNCTIONS_PAIRED_REFS', 'PRIVATE_IMPORT_TEMPLATE_PAIRED_REFS',
            'PRIVATE_ONLY_PINNED_IMMUTABLE_EVIDENCE_UPLOAD', 'PRIVATE_EVIDENCE_UPLOAD_NEGATIVE_CONTROLS',
            'RELEASE_MODULE_HIDDEN_MEMBERS_NEGATIVE_CONTROLS', 'EXACT_PRIVATE_EVIDENCE_OPERATOR_DOCUMENTATION',
            'EXACT_REDIS_ACL_TEST_FIXTURE_ONLY', 'EXACT_REDIS_ACL_TEST_FIXTURE_ONLY');
    }
    const original = new Map(paths.flatMap((name, i) => oldPins[i] === null ? [] : [[name, oldPins[i]]]));
    const runtimeNames = ['pom.xml', 'cachedb-core/pom.xml', 'cachedb-core/src/main/java/Fixture.java',
        'sample-cache-database-postgresql/pom.xml', 'sample-cache-database-postgresql/src/main/resources/application.yml',
        'sample-cache-database-postgresql/scripts/fixture.ps1', 'sample-cache-database-postgresql/postman/fixture.json'];
    for (let i = runtimeNames.length; i < 1599; i++) runtimeNames.push('contract-fixture/src/main/Runtime' + i + '.java');
    for (const name of runtimeNames) original.set(name, sha('SYNTHETIC RUNTIME INPUT: ' + name));
    const docs = ['README.md', 'CHANGELOG.md', 'tr/README.md', 'docs/releases/v0.19.0.md',
        ...Array.from({ length: 12 }, (_, i) => 'docs/fixture-' + i + '.md')];
    if (finalSource) docs.push('docs/redis-read-coalescing.md', 'tr/docs/redis-read-coalescing.md', 'tr/docs/release-checklist.md');
    for (const name of docs) original.set(name, sha('SYNTHETIC ORIGINAL DOC: ' + name));
    while (original.size < 2569) original.set('contract-fixture/member-' + original.size + '.txt', sha('SYNTHETIC UNCHANGED INPUT'));
    const documented = new Map(original);
    for (const name of docs) documented.set(name, sha('SYNTHETIC CURRENT DOC: ' + name));
    // Preserve raw hashes (including line endings), unlike the structural text reader.
    const current = new Map(documented), actual = paths.map(name => { read(name); return inputs.get(name); });
    for (let i = 0; i < paths.length; i++) current.set(paths[i], sha(actual[i]));
    const after = actual[3], before = Buffer.from(after.toString('utf8').replace(
        'source.resolve("target/sample-runtime-coverage.md")', 'out.resolve("coverage.md")'));
    assert.equal(sha(before), oldPins[3], 'Producer fixture must reconstruct the exact original E producer');
    const serialize = rows => [...rows.keys()].sort().map(name => rows.get(name) + '  ' + name + '\n').join('');
    const runtimePin = sha(serialize(new Map(runtimeNames.map(name => [name, original.get(name)]))));
    const baseContext = { receiptSha256: '03ff2f520571af3ef57e554a793fc589039e0c1fec37c655d5148e4096397f13',
        sourceManifestSha256: '95bd490a15e4f17f89965667d31635c4a343a671e45ecd1b790c2766a95a6d34',
        documentationSourceManifestSha256: sha(serialize(documented)),
        sdkManifestSha256: '7e934d3f27a586104341765752454874a87eb4a04077a124a9acac092620eed2' };
    function control(name, mutation = () => {}, expected) {
        test((finalSource ? 'E-to-final-source tooling overlay: ' : 'E-to-G tooling overlay: ') + name, () => {
            const rows = new Map(current), old = new Map(original), docsOnly = new Map(documented), context = { ...baseContext };
            const files = new Map();
            const put = (name, bytes) => { files.set(name, Buffer.from(bytes)); return ref(name, bytes); };
            const proof = { schema: finalSource ? 4 : 3, kind: finalSource ? 'cachedb-v019-e-to-final-source-execution-tooling-overlay' : 'cachedb-v019-e-to-g-execution-tooling-overlay',
                receiptSha256: context.receiptSha256, originalSourceManifestSha256: context.sourceManifestSha256,
                documentationSourceManifestSha256: context.documentationSourceManifestSha256,
                currentSourceManifest: put('G-source.sha256', serialize(rows)), sdkManifestSha256: context.sdkManifestSha256,
                runtimeInputs: { members: 1599, originalSha256: runtimePin, currentSha256: runtimePin },
                declarations: { sdkRebuilt: false, originalEvidenceUnmodified: true, runtimeInputsByteEquivalent: true,
                    toolingScope: 'SOURCE_BINDING_PUBLIC_ADMISSION_RELEASE_ARCHIVE_AND_NEGATIVE_CONTROLS_ONLY', publicAdmission: 'EXPLICIT_PAIRED_REFS_PRIVATE_RESULTS_ONLY',
                    producerChange: 'REPORT_SUMMARY_UNDER_SOURCE_TARGET_ONLY', archiveChange: 'ZIP_INCLUDES_HIDDEN_MEMBERS_NO_PAYLOAD_SELECTION_CHANGE', releaseAccepted: false },
                changes: paths.map((path, i) => ({ path, before: oldPins[i], after: rows.get(path), semanticDelta: semantics[i] })) };
            if (finalSource) Object.assign(proof.declarations, {
                toolingScope: 'EXACT_PRIVATE_EVIDENCE_TOOLING_ARCHIVE_AND_NAMED_ACL_FIXTURES_ONLY',
                privateEvidenceTransport: 'IMMUTABLE_RECEIPT_AND_RESULTS_NO_WORKLOAD_REGENERATION',
                addedSource: 'EXACT_PRIVATE_EVIDENCE_STAGER_ONLY', testFixtureScope: 'TWO_NAMED_REDIS_ACL_TESTS_NOT_RUNTIME_OR_DOCUMENTATION'
            });
            const h = { rows, old, docsOnly, context, put, readProducer: () => ({ before, after }),
                seal: () => { proof.currentSourceManifest = put('G-source.sha256', serialize(rows)); } };
            mutation(proof, h);
            let reference = put('tooling-overlay.json', JSON.stringify(proof));
            if (h.reference) reference = h.reference(reference);
            const evidenceReader = { text(reference, maximum) {
                assert.deepEqual(Object.keys(reference).sort(), ['path', 'sha256']);
                assert(!reference.path.includes('..') && !reference.path.startsWith('/'), 'Unsafe evidence path');
                const bytes = files.get(reference.path); assert(bytes && bytes.length <= maximum, 'Missing/oversized pinned evidence');
                assert.equal(sha(bytes), reference.sha256, 'Evidence digest mismatch'); return bytes.toString('utf8');
            }, json(reference, maximum) { return JSON.parse(this.text(reference, maximum)); } };
            const invoke = () => verifyExecutionToolingOverlay({ evidence: evidenceReader, reference, original: old,
                documented: docsOnly, ...context, readProducer: h.readProducer });
            if (expected) assert.throws(invoke, expected);
            else {
                const result = invoke(); assert.deepEqual(result.current, rows); assert.deepEqual(result.record.changes, proof.changes);
                assert.equal(result.record.manifest.sha256, reference.sha256);
                assert.equal(result.record.declarations.releaseAccepted, false);
            }
        });
    }
    control(finalSource ? 'exact seventeen-path v4 opt-in retains documentation identity and the single declared addition' :
        'exact nine-file v3 opt-in retains the separate documentation identity');
    control('old four-file v1 proof cannot authorize public integration paths', proof => { proof.schema = 1; proof.changes.length = 4; }, /schema/);
    control('old eight-file v2 proof cannot authorize the release archive path', proof => { proof.schema = 2; proof.changes.length = 8; }, /schema/);
    control('v3 cannot omit the release archive declaration', proof => { proof.changes.length = 8; }, /exact delta/);
    control('v3 cannot omit the newly reviewed integration paths', proof => { proof.changes.length = 4; }, /exact delta/);
    for (const name of ['receiptSha256', 'originalSourceManifestSha256', 'documentationSourceManifestSha256', 'sdkManifestSha256'])
        control('rejects stale ' + name, proof => { proof[name] = '0'.repeat(64); }, /binding mismatch/);
    for (const name of ['receiptSha256', 'sourceManifestSha256', 'sdkManifestSha256'])
        control('cannot repin a different E authority: ' + name, (proof, h) => { h.context[name] = '0'.repeat(64); }, /original E .*authority/);
    for (const name of ['originalSha256', 'currentSha256', 'members'])
        control('rejects runtime identity ' + name, proof => { proof.runtimeInputs[name] = name === 'members' ? 1598 : '0'.repeat(64); }, /runtime input identity/);
    for (const name of runtimeNames.slice(0, 7)) control('rejects product/POM/config/script/Postman delta: ' + name,
        (proof, h) => { h.rows.set(name, sha('ALTERED EXECUTABLE')); h.seal(); }, /exact changed paths/);
    control('rejects added source member', (proof, h) => { h.rows.set('tools/extra.mjs', sha('ADDED')); h.seal(); }, /full source inventory/);
    control('rejects deleted source member', (proof, h) => { h.rows.delete(runtimeNames[0]); h.seal(); }, /full source inventory/);
    control('rejects outside allowlist declaration', proof => { proof.changes[0].path = 'tools/other.mjs'; }, /exact delta/);
    control('rejects duplicate declaration', proof => { proof.changes[0] = proof.changes[1]; }, /exact delta/);
    control('rejects missing declaration', proof => { proof.changes.pop(); }, /exact delta/);
    control('rejects an old current-source manifest pin', proof => { proof.currentSourceManifest.sha256 = baseContext.sourceManifestSha256; }, /digest mismatch/);
    control('rejects an undeclared current hash', (proof, h) => { h.rows.set(paths[0], sha('UNREVIEWED CHECKER')); h.seal(); }, /member digest/);
    for (const name of paths.slice(4)) control('rejects an unapproved integration hash: ' + name,
        (proof, h) => { h.rows.set(name, sha('UNREVIEWED INTEGRATION')); h.seal(); }, /member digest/);
    control('rejects a mismatched old hash', proof => { proof.changes[0].before = '0'.repeat(64); }, /member digest/);
    control('rejects producer arbitrary future edit even with a repinned declaration', (proof, h) => {
        const pin = sha('ARBITRARY PRODUCER'); h.rows.set(paths[3], pin); proof.changes[3].after = pin; h.seal();
    }, /reviewed producer\/matrix digest/);
    control('rejects future matrix edit even with a repinned declaration', (proof, h) => {
        const pin = sha('ARBITRARY MATRIX'); h.rows.set(paths[2], pin); proof.changes[2].after = pin; h.seal();
    }, /reviewed producer\/matrix digest/);
    control('rejects future archive edit even with a repinned declaration', (proof, h) => {
        const pin = sha('ARBITRARY ARCHIVE TOOL'); h.rows.set(paths[8], pin); proof.changes[8].after = pin; h.seal();
    }, /reviewed release archive digest/);
    control('rejects a mismatched old archive hash', proof => { proof.changes[8].before = '0'.repeat(64); }, /member digest/);
    control('rejects relabeling archive tooling as documentation', proof => { proof.changes[8].semanticDelta = 'DOCUMENTATION'; }, /semantic declaration/);
    control('rejects producer byte tampering', (proof, h) => { h.readProducer = () => ({ before, after: Buffer.from('TAMPER') }); }, /producer byte digest/);
    control('rejects tooling already hidden in the documentation stage', (proof, h) => { h.docsOnly.set(paths[0], h.rows.get(paths[0])); }, /exact changed paths/);
    control('rejects extra documentation drift after the documentation stage', (proof, h) => { h.rows.set(docs[0], sha('LATER DOC')); h.seal(); }, /exact changed paths/);
    control('rejects a runtime subset masquerading as a full manifest', (proof, h) => {
        for (const name of [...h.rows.keys()]) if (!runtimeNames.includes(name)) h.rows.delete(name); h.seal();
    }, /full source inventory/);
    control('rejects unsupported overlay version', proof => { proof.schema = finalSource ? 5 : 4; }, /schema/);
    control('rejects a documentation proof as tooling authority', proof => { proof.kind = 'cachedb-v019-documentation-source-equivalence'; }, /schema/);
    for (const name of ['sdkRebuilt', 'originalEvidenceUnmodified', 'runtimeInputsByteEquivalent', 'toolingScope', 'publicAdmission', 'producerChange', 'archiveChange', 'releaseAccepted'])
        control('rejects false declaration: ' + name, proof => { proof.declarations[name] = !proof.declarations[name]; }, /declaration mismatch/);
    control('rejects semantic relabeling of producer', proof => { proof.changes[3].semanticDelta = 'DOCUMENTATION'; }, /semantic declaration/);
    control('requires the independently supplied proof SHA', (proof, h) => { h.reference = reference => ({ ...reference, sha256: '0'.repeat(64) }); }, /digest mismatch/);
    control('rejects proof path traversal', (proof, h) => { h.reference = reference => ({ ...reference, path: '../tooling-overlay.json' }); }, /Unsafe evidence path/);
    if (finalSource) {
        control('v3 nine-path authority cannot grant the new scope', proof => {
            proof.schema = 3; proof.kind = 'cachedb-v019-e-to-g-execution-tooling-overlay'; proof.changes.length = 9;
            proof.declarations.toolingScope = 'SOURCE_BINDING_PUBLIC_ADMISSION_RELEASE_ARCHIVE_AND_NEGATIVE_CONTROLS_ONLY';
            for (const name of ['privateEvidenceTransport', 'addedSource', 'testFixtureScope']) delete proof.declarations[name];
        }, /full source inventory/);
        control('v4 requires the singular added stager', (proof, h) => { h.rows.delete(paths[11]); h.seal(); }, /full source inventory/);
        control('v4 cannot rename the added stager', (proof, h) => {
            h.rows.set('tools/ci/other-stager.ps1', h.rows.get(paths[11])); h.rows.delete(paths[11]); h.seal();
        }, /full source inventory/);
        control('v4 cannot disguise an existing path as an addition', proof => { proof.changes[9].before = null; }, /member digest/);
        control('v4 added-path old pin must be null', proof => { proof.changes[11].before = '0'.repeat(64); }, /member digest/);
        control('v4 added-path after pin cannot be null', proof => { proof.changes[11].after = null; }, /member digest/);
        control('v4 cannot exempt another src/test fixture', (proof, h) => {
            h.rows.set('cachedb-storage-redis/src/test/java/OtherAclTest.java', sha('UNREVIEWED FIXTURE')); h.seal();
        }, /full source inventory/);
        for (const index of [15, 16]) control('v4 named ACL fixture is not documentation: ' + paths[index],
            proof => { proof.changes[index].semanticDelta = 'DOCUMENTATION'; }, /semantic declaration/);
        for (const name of ['privateEvidenceTransport', 'addedSource', 'testFixtureScope'])
            control('v4 rejects false declaration: ' + name, proof => { proof.declarations[name] = false; }, /declaration mismatch/);
    }
}
function publicQualificationControls() {
    const pin = 'a'.repeat(64), privateResults = path.join(home, 'private-result.json');
    const base = ['--runtime-evidence', path.join(home, 'receipt.json'), '--runtime-evidence-sha256', pin, '--private-results', privateResults];
    const pairs = ['--source-equivalence', 'proof/docs.json', '--source-equivalence-sha256', pin,
        '--execution-tooling-overlay', 'proof/tooling.json', '--execution-tooling-overlay-sha256', pin];
    test('private runtime diagnostics cannot leak into the public Markdown summary', () => {
        const errors = ['SYNTHETIC PRIVATE REPORT CONTENT'];
        assert(!sampleCoverageRuntimeIssues(errors, privateResults).join('').includes(errors[0]));
        assert.deepEqual(errors, ['SYNTHETIC PRIVATE REPORT CONTENT']);
        assert.deepEqual(sampleCoverageRuntimeIssues([], privateResults), []);
    });
    test('public qualification accepts only two complete explicit reference pairs', () => {
        const options = sampleCoverageOptions([...base, ...pairs]);
        assert.equal(options['source-equivalence'], pairs[1]); assert.equal(options['execution-tooling-overlay-sha256'], pin);
    });
    test('public qualification rejects duplicate reference options', () => {
        assert.throws(() => sampleCoverageOptions([...base, ...pairs, '--source-equivalence', 'proof/other.json']), /Duplicate option/);
    });
    for (let field = 0; field < 4; field++) test('public qualification rejects missing reference field ' + field, () => {
        assert.throws(() => sampleCoverageOptions([...base, ...pairs.filter((_, index) => Math.floor(index / 2) !== field)]), /Use both/);
    });
    for (const offset of [0, 4]) test('public qualification rejects a missing qualification stage ' + offset, () => {
        assert.throws(() => sampleCoverageOptions([...base, ...pairs.slice(offset, offset + 4)]), /both reference pairs/);
    });
    for (const offset of [3, 7]) for (const bad of ['A'.repeat(64), 'g'.repeat(64), 'abc'])
        test('public qualification rejects invalid SHA at ' + offset + ': ' + bad.slice(0, 3), () => {
            const changed = [...pairs]; changed[offset] = bad;
            assert.throws(() => sampleCoverageOptions([...base, ...changed]), /SHA256/);
        });
    for (const offset of [1, 5]) for (const bad of ['../escape', '/absolute', 'C:/outside', 'a\\b', 'a//b', 'a/./b', 'a/ b', 'a/b.', 'a\u0000b'])
        test('public qualification rejects unsafe path at ' + offset + ': ' + JSON.stringify(bad), () => {
            const changed = [...pairs]; changed[offset] = bad;
            assert.throws(() => sampleCoverageOptions([...base, ...changed]), /Unsafe qualification/);
        });
    test('public qualification rejects refs without runtime authority', () => {
        assert.throws(() => sampleCoverageOptions([...base.slice(4), ...pairs]), /pinned runtime evidence/);
    });
    test('public qualification requires an explicit private result destination', () => {
        assert.throws(() => sampleCoverageOptions([...base.slice(0, 4), ...pairs]), /private results/);
    });
    test('public API rejects a partial chain before reading source', () => {
        assert.throws(() => checkSampleCoverage({ root: 'not-a-checkout', receiptPath: 'receipt.json', receiptSha256: pin,
            sourceEquivalence: { path: 'proof/docs.json', sha256: pin } }), /both reference pairs/);
    });
    test('public API rejects malformed references before reading source', () => {
        assert.throws(() => checkSampleCoverage({ root: 'not-a-checkout', receiptPath: 'receipt.json', receiptSha256: pin,
            sourceEquivalence: { path: 'proof/docs.json', sha256: pin, approved: true },
            executionToolingOverlay: { path: 'proof/tooling.json', sha256: pin } }), /reference SHA256/);
    });
    test('public API rejects unsafe reference paths before reading source', () => {
        assert.throws(() => checkSampleCoverage({ root: 'not-a-checkout', receiptPath: 'receipt.json', receiptSha256: pin,
            sourceEquivalence: { path: '../docs.json', sha256: pin },
            executionToolingOverlay: { path: 'proof/tooling.json', sha256: pin } }), /Unsafe qualification/);
    });
    fs.mkdirSync(checkout);
    test('private qualification result may stay outside checkout', () => {
        assert.equal(sampleCoveragePrivateResults(checkout, privateResults), privateResults);
    });
    test('private qualification result cannot stay inside checkout', () => {
        assert.throws(() => sampleCoveragePrivateResults(checkout, path.join(checkout, 'result.json')), /outside the checkout/);
    });
    test('private qualification result cannot use a relative path', () => {
        assert.throws(() => sampleCoveragePrivateResults(checkout, 'result.json'), /absolute path/);
    });
    test('private qualification result cannot overwrite a retained file', () => {
        fs.writeFileSync(privateResults, 'SYNTHETIC CONTROL ONLY');
        assert.throws(() => sampleCoveragePrivateResults(checkout, privateResults), /fresh file/);
    });
    if (process.platform !== 'win32') test('private qualification result cannot use a symlink alias', () => {
        const alias = path.join(home, 'checkout-alias'); fs.symlinkSync(checkout, alias, 'dir');
        assert.throws(() => sampleCoveragePrivateResults(checkout, path.join(alias, 'result.json')), /without aliases/);
    });
    fs.rmSync(checkout, { recursive: true });
}
function originalCellControls(base, options) {
    function fixture() {
        const receipt = { schema: 2, kind: 'cachedb-v019-sample-runtime-original-cells', source: structuredClone(base.source),
            sdk: structuredClone(base.sdk), origins: [], cells: [] };
        const states = [];
        for (let index = 0; index < 2; index++) {
            const oracle = index === 1, id = oracle ? 'original/oracle-tenant-purge' : 'original/feature9-child04';
            const podUid = '00000000-0000-0000-0000-00000000000' + (index + 1);
            const inventory = new Map();
            function captured(name, value) {
                const bytes = Buffer.isBuffer(value) || typeof value === 'string' ? value : JSON.stringify(value);
                const reference = write(evidence, id + '/' + name, bytes);
                inventory.set(name, { name, sha256: reference.sha256, bytes: Buffer.byteLength(bytes) }); return reference;
            }
            const external = (name, value) => write(evidence, 'authority-' + index + '/' + name, JSON.stringify(value));
            const script = captured('direct-overlay/run-feature9.sh', '# SYNTHETIC FIXTURE ONLY\n');
            const process = { schema: 'cachedb.feature9-native-process/v1', executionId: podUid, logicalExecutionId: oracle ? 'oracle-tenant-purge' : 'sample-features',
                podUid, pid: 123, executable: '/usr/bin/bash', argv: ['/workspace/direct-overlay/run-feature9.sh'], workingDirectory: '/workspace',
                startedAt: base.execution.startedAt, finishedAt: base.execution.finishedAt, exitCode: oracle ? 0 : 1,
                timedOut: false, stdoutComplete: true, stderrComplete: true };
            for (const [name, value] of [['stdout', 'SYNTHETIC native output\n'], ['stderr', ''], ['exit', process.exitCode + '\n'], ...(oracle ? [['tee', '0\n']] : [])]) {
                const member = 'evidence/feature9/' + name;
                const reference = captured(member, value);
                process[name] = { path: member, sha256: reference.sha256, bytes: Buffer.byteLength(value) };
            }
            if (oracle) process.teeExit = 0;
            const createdAt = base.execution.startedAt, cutoffAt = '2026-11-07T00:56:00Z';
            // Keep every physical envelope finite; the original schema1 fixture deliberately has a longer envelope.
            process.finishedAt = '2026-11-07T00:30:00Z';
            const admission = { namespace: base.execution.namespace, runId: 'SYNTHETIC_FIXTURE-' + podUid,
                runnerPodUid: podUid, serviceRunnerPodUid: podUid, runnerCreatedAt: createdAt, originalCutoff: cutoffAt,
                activeDeadlineSeconds: '3360', cpuMillicores: '2000', memoryBytes: '4294967296' };
            const epoch = Date.parse(createdAt) / 1000;
            const origin = { id, prefix: id, custody: null,
                pod: external('pod.json', { kind: 'Pod', metadata: { uid: podUid, namespace: admission.namespace, name: 'synthetic', creationTimestamp: createdAt }, spec: { activeDeadlineSeconds: 3360 } }),
                authority: external('authority.json', { schema: 'cachedb.feature9-authority/v1', context: 'kubernetes-admin@EmaTech',
                    namespace: admission.namespace, pod: 'synthetic', podUid, maximumRunSeconds: 3360, deadlineMaximumSeconds: 3360,
                    extensionAllowed: false, authorizedByUser: true, baselineRun: false, retriesAllowed: false, podDeadlineSeconds: 3360,
                    firstResourceEpoch: epoch, firstResourceCutoffEpoch: epoch + 3360, cutoffEpoch: epoch + 3360 }),
                preparation: external('preparation.json', { scriptSha256: script.sha256 }), scriptPinPointer: '/scriptSha256',
                admission: captured('input/authority.json', admission), process: captured('evidence/feature9/process.json', process), executedScript: script };
            const selected = base.cells.filter(cell => (cell.provider === 'oracle' && cell.lane === 'tenant-purge') === oracle);
            for (const cell of selected) {
                const ordinal = oracle ? 1 : base.cells.indexOf(cell) + 1;
                for (const phase of [cell.tests, cell.http]) {
                    const references = [phase.exit, phase.preservationExit, phase.sourceBefore, phase.sourceAfter, phase.sdkBefore, phase.sdkAfter,
                        ...(phase.reports || [phase.junit, ...(phase.newman ? [phase.newman] : [])])];
                    for (const reference of references) captured('sample-runtime-evidence/' + reference.path, fs.readFileSync(path.join(evidence, reference.path)));
                }
                const prefix = 'sample-runtime-evidence/' + cell.provider + '/' + cell.lane + '/http/';
                const log = captured(prefix + 'app.log', 'SYNTHETIC app lifecycle\n');
                const lifecycle = { schema: 'cachedb.feature9-cell-lifecycle/v1', podUid, lane: cell.lane, pid: 234,
                    postmanNativeExit: 0, aliveAfterStop: false, nativeExit: 143, stdoutBytes: Buffer.byteLength('SYNTHETIC app lifecycle\n'),
                    startedObservedAt: '2026-11-07T00:00:01.000000001Z', readyObservedAt: '2026-11-07T00:00:02Z',
                    aliveAfterRequestsObservedAt: '2026-11-07T00:00:03Z', stopRequestedAt: '2026-11-07T00:00:04Z', stoppedObservedAt: '2026-11-07T00:00:05Z' };
                receipt.cells.push({ originId: id, checkpoint: captured('sample-runtime-evidence/completed-cell-' + ordinal + '.json', cell),
                    lifecycle: { record: captured(prefix + 'app-lifecycle.json', lifecycle), log } });
            }
            if (oracle) {
                const environmentValue = '{"spring.datasource.hikari.data-source-properties[oracle.jdbc.ReadTimeout]":"15000"}';
                origin.runtimeConfiguration = { policy: external('runtime-configuration.json', {
                    schema: 'cachedb.oracle-tenant-purge-runtime-configuration/v1', scope: 'oracle/tenant-purge', phases: ['tests', 'http'],
                    environmentVariable: 'SPRING_APPLICATION_JSON', environmentValue,
                    property: 'spring.datasource.hikari.data-source-properties[oracle.jdbc.ReadTimeout]', oracleDataSourceProperty: 'oracle.jdbc.ReadTimeout',
                    readTimeoutMs: 15000, connectTimeoutMsUnchanged: 5000, guardStatementTimeoutSecondsUnchanged: 2,
                    productSourceOverlay: false, sdkRebuilt: false, effectiveConnectionValueObserved: false }) };
                for (const phase of ['tests', 'http']) origin.runtimeConfiguration[phase] = captured(
                    'sample-runtime-evidence/oracle/tenant-purge/' + phase + '/read-timeout-override.json', {
                        schema: 'cachedb.oracle-read-timeout-child-environment/v1', provider: 'oracle', lane: 'tenant-purge', phase,
                        variable: 'SPRING_APPLICATION_JSON', value: environmentValue, readTimeoutMs: 15000,
                        connectTimeoutMsUnchanged: 5000, guardStatementTimeoutSecondsUnchanged: 2, effectiveConnectionValueObserved: false });
                for (const binding of [base.source, base.sdk]) captured('sample-runtime-evidence/' + binding.manifest.path,
                    fs.readFileSync(path.join(evidence, binding.manifest.path)));
                origin.scopedReceipt = captured('sample-runtime-evidence/scoped-receipt.json', { schema: 1,
                    kind: 'cachedb-oracle-tenant-purge-scoped-runtime', source: base.source, sdk: base.sdk,
                    execution: { ...base.execution, runId: admission.runId, runnerPodUid: podUid, finishedAt: process.finishedAt, cutoffAt }, cells: selected });
            }
            function refresh() {
                origin.custody = external('custody.json', { schema: 'cachedb.feature9-original-custody/v1', status: 'ORIGINALS_CAPTURED',
                    podUid, sourceSdkTreesExcludedForExactReuse: true, cutoffEpoch: epoch + 3360, files: [...inventory.values()] });
            }
            refresh(); receipt.origins.push(origin); states.push({ origin, process, captured, refresh });
        }
        return { receipt, states };
    }
    function check(name, mutate, expected) {
        test('original cells: ' + name, () => {
            const state = fixture(); if (mutate) mutate(state);
            const pin = write(evidence, 'receipt.json', JSON.stringify(state.receipt)).sha256;
            if (expected) assert.throws(() => verifySampleRuntime(options(pin)), expected);
            else {
                const result = verifySampleRuntime(options(pin));
                assert.equal(result.schema, 2); assert.equal(result.tests, 141); assert.equal(result.requests, 180);
                assert.equal(result.cells.length, 9); assert.equal(Object.hasOwn(result, 'runId'), false);
                assert.deepEqual(result.originalExecutions.map(origin => origin.observedExit), [1, 0]);
                assert.notEqual(result.originalExecutions[0].podUid, result.originalExecutions[1].podUid);
                assert.equal(result.originalExecutions[1].runtimeConfiguration.baselineReadTimeoutMs, 5000);
                assert.equal(result.originalExecutions[1].runtimeConfiguration.readTimeoutMs, 15000);
                assert.deepEqual(result.originalExecutions[1].runtimeConfiguration.phases, ['tests', 'http']);
            }
        });
    }
    const mutateProcess = (state, action) => {
        const origin = state.states[0]; action(origin.process);
        origin.origin.process = origin.captured('evidence/feature9/process.json', origin.process); origin.refresh();
    };
    const mutateCheckpoint = (state, action) => {
        const selection = state.receipt.cells[0], original = state.states[0];
        const cell = JSON.parse(fs.readFileSync(path.join(evidence, selection.checkpoint.path), 'utf8')); action(cell, original);
        selection.checkpoint = original.captured('sample-runtime-evidence/completed-cell-1.json', cell); original.refresh();
    };
    check('preserves native1 and qualifies only eight-plus-one originals');
    check('rejects synthetic common execution', ({ receipt }) => { receipt.execution = base.execution; }, /Runtime receipt/);
    check('rejects missing origin', ({ receipt }) => receipt.origins.pop(), /two original/);
    check('rejects original native1 rewritten to zero', state => mutateProcess(state, value => { value.exitCode = 0; }), /native process/);
    check('rejects partial stdout', state => mutateProcess(state, value => { value.stdoutComplete = false; }), /native process/);
    check('rejects timeout', state => mutateProcess(state, value => { value.timedOut = true; }), /native process/);
    check('rejects process beyond original cutoff', state => mutateProcess(state, value => { value.finishedAt = '2026-11-07T01:00:00Z'; }), /ordering/);
    check('rejects physical process beyond original 2400s workload cap', state => mutateProcess(state, value => { value.finishedAt = '2026-11-07T00:40:00.001Z'; }), /ordering/);
    check('rejects a foreign origin partition', ({ receipt }) => { receipt.cells[0].originId = receipt.origins[1].id; }, /prefix|partition/);
    check('rejects original-prefix alias', ({ receipt }) => { receipt.origins[0].prefix += '-alias'; }, /prefix/);
    check('rejects a duplicate checkpoint', ({ receipt }) => { receipt.cells[1] = receipt.cells[0]; }, /Reused|Duplicate/);
    check('rejects a freshly repinned uncaptured record', ({ receipt }) => {
        receipt.origins[0].process = write(evidence, receipt.origins[0].process.path, '{}');
    }, /captured custody/);
    check('rejects freshly repinned uncaptured report', state => mutateCheckpoint(state, (cell) => {
        cell.tests.reports[0] = write(evidence, state.receipt.origins[0].prefix + '/sample-runtime-evidence/' + cell.tests.reports[0].path, '<testsuite/>');
        cell.tests.reports[0].path = cell.tests.reports[0].path.split('/sample-runtime-evidence/')[1];
    }), /captured custody/);
    check('rejects original native exit stream replaced by success', state => {
        const origin = state.states[0], member = origin.process.exit.path;
        const ref = origin.captured(member, '0\n'); origin.process.exit.sha256 = ref.sha256;
        origin.origin.process = origin.captured('evidence/feature9/process.json', origin.process); origin.refresh();
    }, /native\/tee exit/);
    check('rejects Oracle native success with tee failure', state => {
        const origin = state.states[1]; origin.process.teeExit = 1;
        origin.origin.process = origin.captured('evidence/feature9/process.json', origin.process); origin.refresh();
    }, /tee failed/);
    check('rejects changed independently captured preparation pin', state => {
        const origin = state.states[0].origin; origin.preparation = write(evidence, origin.preparation.path, JSON.stringify({ scriptSha256: '0'.repeat(64) }));
    }, /original preparation/);
    check('rejects failed preservation under genuine refreshed custody', state => mutateCheckpoint(state, (cell, origin) => {
        const ref = origin.captured('sample-runtime-evidence/' + cell.tests.preservationExit.path, '1\n'); cell.tests.preservationExit.sha256 = ref.sha256;
    }), /phase exit/);
    check('rejects phase cutoff overrun', state => mutateCheckpoint(state, cell => { cell.tests.finishedAt = '2026-11-07T00:51:00Z'; }), /timing/);
    check('rejects skipped original JUnit under refreshed custody', state => mutateCheckpoint(state, (cell, origin) => {
        const report = cell.tests.reports[0], bytes = fs.readFileSync(path.join(evidence, origin.origin.prefix, 'sample-runtime-evidence', report.path), 'utf8');
        report.sha256 = origin.captured('sample-runtime-evidence/' + report.path, bytes.replace('skipped="0"', 'skipped="1"')).sha256;
    }), /JUnit|skipped|counters/);
    check('rejects lifecycle outside original HTTP phase', state => {
        const selection = state.receipt.cells[0], origin = state.states[0];
        const value = JSON.parse(fs.readFileSync(path.join(evidence, selection.lifecycle.record.path), 'utf8'));
        value.stoppedObservedAt = '2026-11-07T00:01:01.000000001Z';
        selection.lifecycle.record = origin.captured('sample-runtime-evidence/postgresql/audit-derived-delete/http/app-lifecycle.json', value); origin.refresh();
    }, /lifecycle time/);
    check('rejects missing Oracle runtime configuration', ({ receipt }) => { delete receipt.origins[1].runtimeConfiguration; }, /Original cell origin/);
    check('rejects broader Oracle runtime configuration policy', ({ receipt }) => {
        const origin = receipt.origins[1], ref = origin.runtimeConfiguration.policy;
        const value = JSON.parse(fs.readFileSync(path.join(evidence, ref.path), 'utf8')); value.phases.push('application');
        origin.runtimeConfiguration.policy = write(evidence, ref.path, JSON.stringify(value));
    }, /Unapproved Oracle/);
    check('rejects SDK rebuild configuration claim', ({ receipt }) => {
        const ref = receipt.origins[1].runtimeConfiguration.policy;
        const value = JSON.parse(fs.readFileSync(path.join(evidence, ref.path), 'utf8')); value.sdkRebuilt = true;
        receipt.origins[1].runtimeConfiguration.policy = write(evidence, ref.path, JSON.stringify(value));
    }, /Unapproved Oracle/);
    check('rejects uncaptured Oracle configuration phase record', ({ receipt }) => {
        const ref = receipt.origins[1].runtimeConfiguration.tests;
        receipt.origins[1].runtimeConfiguration.tests = write(evidence, ref.path, '{}');
    }, /captured custody/);
    check('rejects changed captured Oracle timeout', state => {
        const original = state.states[1], ref = original.origin.runtimeConfiguration.http;
        const value = JSON.parse(fs.readFileSync(path.join(evidence, ref.path), 'utf8')); value.readTimeoutMs = 5000;
        original.origin.runtimeConfiguration.http = original.captured('sample-runtime-evidence/oracle/tenant-purge/http/read-timeout-override.json', value); original.refresh();
    }, /Original Oracle runtime configuration differs/);
    fs.writeFileSync(path.join(evidence, 'receipt.json'), JSON.stringify(base));
}

try {
    if (!originalCellsOnly) toolingOverlayControls(finalSourceOnly);
    if (!finalSourceOnly && !originalCellsOnly) publicQualificationControls();
    if (!overlayOnly && !finalSourceOnly) {
    const structural = validateSampleMatrix(read);
    assert.deepEqual(structural.errors, [], 'Actual source must satisfy the static contract before generating fixtures');
    const contracts = sampleRuntimeContracts(read, javaMask);
    const requiredSourceFiles = [...inputs.keys()];
    const rootPom = read('pom.xml');
    // Only the main reactor is published; benchmark profile modules are not SDK members.
    const reactorModules = /<modules>([\s\S]*?)<\/modules>/.exec(rootPom)[1];
    const modules = [...reactorModules.matchAll(/<module>([^<]+)<\/module>/g)].map(match => match[1]);
    assert(modules.every(module => !module.startsWith('tools/benchmarks/')), 'Benchmark profiles must not enter the SDK fixture');
    const sdk = [];
    for (const module of ['', ...modules]) {
        const pom = read(module ? module + '/pom.xml' : 'pom.xml');
        const own = pom.replace(/<parent>[\s\S]*?<\/parent>/, '');
        const artifact = /<artifactId>([^<]+)<\/artifactId>/.exec(own)[1];
        const pomOnly = /<packaging>pom<\/packaging>/.test(own);
        if (module) retainTree(module + '/src', pomOnly);
        const base = 'com/reactor/cachedb/' + artifact + '/0.19.0/' + artifact + '-0.19.0';
        sdk.push(base + '.pom');
        if (!pomOnly) {
            sdk.push(base + '.jar');
            if (artifact !== 'cachedb-integration-tests') sdk.push(base + '-sources.jar', base + '-javadoc.jar');
        }
    }
    assert.equal(sdk.length, 92, 'The final SDK fixture must contain the exact 92-coordinate/classifier roster');
    assert.equal(new Set(sdk).size, 92, 'The final SDK fixture roster must be unique');
    for (const provider of ['postgresql', 'mssql', 'oracle'])
        for (const directory of ['src', 'scripts', 'postman']) retainTree('sample-cache-database-' + provider + '/' + directory);
    for (const name of ['tools/ci/check-v019-sample-coverage.mjs', 'tools/ci/verify-v019-sample-runtime.mjs',
        'tools/ci/test-v019-sample-runtime.mjs', 'tools/ci/test-v019-sample-matrix.mjs',
        'tools/ci/check-v019-sample-coverage.ps1', 'tools/ci/v019-sample-runtime-evidence.md',
        'tools/k8s/SampleRuntimeEvidence.java', 'README.md', 'CHANGELOG.md', 'tr/README.md',
        'docs/releases/v0.19.0.md', 'tr/docs/releases/v0.19.0.md']) read(name);
    // Every source member is retained verbatim; only SDK/report bytes are synthetic parser fixtures.
    const productionMember = [...inputs.keys()].find(name => name.startsWith('cachedb-core/src/main/') && name.endsWith('.java') && !requiredSourceFiles.includes(name));
    assert(productionMember, 'An actual independently discovered production source input is required');
    for (const [name, bytes] of inputs) { write(checkout, name, bytes); write(evidence, 'source/' + name, bytes); }
    const manifest = rows => [...rows].sort(([a], [b]) => a.localeCompare(b)).map(([name, bytes]) => sha(bytes) + '  ' + name + '\n').join('');
    const sourceText = manifest(inputs), sourceRef = write(evidence, 'source.sha256', sourceText);
    const sdkBytes = new Map(sdk.map(name => [name, Buffer.from('SYNTHETIC UNIT FIXTURE: ' + name + '\n')]));
    for (const [name, bytes] of sdkBytes) write(evidence, 'sdk/' + name, bytes);
    const sdkText = manifest(sdkBytes), sdkRef = write(evidence, 'sdk.sha256', sdkText);
    const base = { schema: 1, kind: 'cachedb-v019-sample-runtime',
        source: { root: 'source', manifest: sourceRef },
        sdk: { root: 'sdk', manifest: sdkRef, sourceManifestSha256: sourceRef.sha256 },
        execution: { runId: 'SYNTHETIC_FIXTURE_ONLY', namespace: 'cachedb-synthetic-unit',
            runnerPodUid: '00000000-0000-0000-0000-000000000000',
            startedAt: '2026-11-07T00:00:00Z', finishedAt: '2026-11-07T01:00:00Z', cutoffAt: '2026-11-07T01:00:01Z',
            cpuMillicores: 2000, memoryBytes: 4294967296 }, cells: [] };
    for (const contract of contracts) {
        const cell = { provider: contract.provider, lane: contract.lane, sourceManifestSha256: sourceRef.sha256, sdkManifestSha256: sdkRef.sha256 };
        for (const purpose of ['tests', 'http']) {
            const prefix = contract.provider + '/' + contract.lane + '/' + purpose + '/';
            const phase = { fixtureId: sha(prefix), startedAt: '2026-11-07T00:00:01Z', finishedAt: '2026-11-07T00:01:01Z',
                exit: write(evidence, prefix + 'run.exit', '0\n'), preservationExit: write(evidence, prefix + 'preservation.exit', '0\n') };
            for (const name of ['sourceBefore', 'sourceAfter', 'sdkBefore', 'sdkAfter'])
                phase[name] = write(evidence, prefix + name + '.sha256', name.startsWith('source') ? sourceText : sdkText);
            if (purpose === 'tests') phase.reports = contract.suites.map(suite => write(evidence, prefix + 'TEST-' + suite.type + '.xml', junit(suite)));
            else {
                phase.junit = write(evidence, prefix + 'newman.xml', httpJunit(entries(contract.collection)));
                if (contract.lane !== 'tenant-purge') phase.newman = write(evidence, prefix + 'newman.json', JSON.stringify(httpJson(contract)));
            }
            cell[purpose] = phase;
        }
        base.cells.push(cell);
    }
    const receiptPath = path.join(evidence, 'receipt.json');
    const options = receiptSha256 => ({ repositoryRoot: checkout, receiptPath, receiptSha256, contracts, requiredSourceFiles });
    const pristine = JSON.stringify(base), pin = write(evidence, 'receipt.json', pristine).sha256;
    originalCellControls(base, options);
    if (!originalCellsOnly) {
    test('complete raw fixture qualifies exactly the intended scope', () => {
        assert(!fs.existsSync(path.join(checkout, 'cachedb-bom/src')), 'The actual POM-only module has no source tree');
        const result = verifySampleRuntime(options(pin));
        assert.equal(result.tests, 141); assert.equal(result.requests, 180); assert.equal(result.cells.length, 9);
        assert.match(result.scope, /NOT_FULL_RELEASE/);
        assert.equal(Object.hasOwn(result, 'sourceRequalification'), false);
        assert.equal(Object.hasOwn(result, 'currentSourceManifestSha256'), false);
    });
    test('checker binds four features without changing source declarations', () => {
        const result = checkSampleCoverage({ root: checkout, receiptPath, receiptSha256: pin });
        assert.deepEqual(result.runtimeErrors, undefined);
        assert.equal(result.complete, true); assert.equal(result.runtimeQualified, true); assert.equal(result.releaseAccepted, false);
        assert.equal(result.qualifiedFeatures.length, 12); assert.deepEqual(result.deferred, []);
        assert.equal(JSON.parse(fs.readFileSync(path.join(checkout, 'tools/samples/v019-scenarios.json')))
            .deferredFeatures.every(feature => feature.status === 'SOURCE_IMPLEMENTED_RUNTIME_UNQUALIFIED' && feature.runtimeEvidence === null), true);
    });
    test('no receipt preserves source-only fail-closed behavior', () => {
        const result = checkSampleCoverage({ root: checkout });
        assert.equal(result.complete, false); assert.equal(result.runtimeQualified, false);
        assert.equal(result.deferred.length, 12); assert(result.deferred.every(row => row.status === 'DEFERRED' && row.runtimeEvidence === null));
    });
    function reject(name, mutation, expected = /./) {
        test(name, () => {
            const receipt = JSON.parse(pristine), backups = new Map(), moves = [];
            function change(member, bytes) {
                const full = path.join(evidence, member);
                if (!backups.has(full)) backups.set(full, fs.existsSync(full) ? fs.readFileSync(full) : null);
                return write(evidence, member, bytes);
            }
            const replace = (reference, operation) => Object.assign(reference, change(reference.path, operation(fs.readFileSync(path.join(evidence, reference.path), 'utf8'))));
            const json = (reference, operation) => replace(reference, text => { const report = JSON.parse(text); operation(report); return JSON.stringify(report); });
            function omitTree(member, empty = false) {
                for (const root of [checkout, path.join(evidence, receipt.source.root)]) {
                    const full = path.resolve(root, member), parked = path.join(home, 'parked-' + moves.length);
                    assert(full.startsWith(root + path.sep), 'Only owned fixture trees may be moved');
                    fs.renameSync(full, parked); moves.push([full, parked]);
                    if (empty) fs.mkdirSync(full);
                }
                const text = sourceText.split('\n').filter(line => !line.slice(66).startsWith(member + '/')).join('\n');
                Object.assign(receipt.source.manifest, change(receipt.source.manifest.path, text));
                receipt.sdk.sourceManifestSha256 = receipt.source.manifest.sha256;
                for (const cell of receipt.cells) {
                    cell.sourceManifestSha256 = receipt.source.manifest.sha256;
                    for (const phase of [cell.tests, cell.http]) for (const name of ['sourceBefore', 'sourceAfter'])
                        Object.assign(phase[name], change(phase[name].path, text));
                }
            }
            try {
                mutation(receipt, { change, replace, json, omitTree });
                const pinned = change('receipt.json', JSON.stringify(receipt)).sha256;
                assert.throws(() => verifySampleRuntime(options(pinned)), expected);
            } finally {
                for (const [full, bytes] of backups) { if (bytes === null) fs.rmSync(full, { force: true }); else fs.writeFileSync(full, bytes); }
                for (const [full, parked] of moves.reverse()) {
                    if (fs.existsSync(full)) fs.rmdirSync(full);
                    fs.renameSync(parked, full);
                }
            }
        });
    }
    function requalify(name, mutation, expected) {
        test(name, () => {
            const current = new Map(inputs), backups = new Map();
            function save(root, member, bytes) {
                const full = path.join(root, member);
                if (!backups.has(full)) backups.set(full, fs.existsSync(full) ? fs.readFileSync(full) : null);
                return write(root, member, bytes);
            }
            function edit(member) {
                assert(inputs.has(member), 'Negative control requires an actual retained input: ' + member);
                const bytes = Buffer.concat([inputs.get(member), Buffer.from('\nSYNTHETIC_DOCUMENTATION_DELTA_FIXTURE\n')]);
                current.set(member, bytes); save(checkout, member, bytes);
            }
            const proof = { schema: 1, kind: 'cachedb-v019-documentation-source-equivalence', receiptSha256: pin,
                originalSourceManifestSha256: sourceRef.sha256, currentSourceManifest: null, changes: [] };
            function seal() {
                proof.currentSourceManifest = save(evidence, 'current-source.sha256', manifest(current));
                proof.changes = [...inputs].filter(([member, bytes]) => current.has(member) && sha(bytes) !== sha(current.get(member)))
                    .map(([member, bytes]) => ({ path: member, before: sha(bytes), after: sha(current.get(member)) }));
            }
            const args = options(pin), helper = { current, edit, save, seal, args, finish: () => {} };
            try {
                edit('README.md'); seal(); mutation(proof, helper);
                args.sourceEquivalence = save(evidence, 'source-equivalence.json', JSON.stringify(proof));
                helper.finish(args);
                if (expected) assert.throws(() => verifySampleRuntime(args), expected);
                else {
                    const result = verifySampleRuntime(args);
                    assert.equal(result.sourceManifestSha256, sourceRef.sha256);
                    assert.equal(result.currentSourceManifestSha256, proof.currentSourceManifest.sha256);
                    assert.deepEqual(result.sourceRequalification, { schema: 1, kind: proof.kind, manifest: args.sourceEquivalence,
                        receiptSha256: pin, originalSourceManifestSha256: sourceRef.sha256,
                        currentSourceManifest: proof.currentSourceManifest, changes: proof.changes });
                    assert.equal(result.tests, 141); assert.equal(result.requests, 180); assert.equal(result.cells.length, 9);
                    assert.match(result.scope, /NOT_FULL_RELEASE/);
                    assert.equal(fs.readFileSync(receiptPath, 'utf8'), pristine);
                    assert.equal(fs.readFileSync(path.join(evidence, sourceRef.path), 'utf8'), sourceText);
                    for (const [member, bytes] of inputs) assert(fs.readFileSync(path.join(evidence, 'source', member)).equals(bytes));
                }
            } finally {
                for (const [full, bytes] of backups) { if (bytes === null) fs.rmSync(full, { force: true }); else fs.writeFileSync(full, bytes); }
            }
        });
    }
    requalify('documentation-only equivalence retains both identities and the untouched original evidence', (proof, h) => {
        for (const member of ['CHANGELOG.md', 'tr/README.md', 'docs/releases/v0.19.0.md', 'tr/docs/releases/v0.19.0.md',
            ...['postgresql', 'mssql', 'oracle'].flatMap(provider => ['README.md', 'README.tr.md']
                .map(name => 'sample-cache-database-' + provider + '/' + name))]) h.edit(member);
        h.seal();
        h.args.requiredSourceDigests = new Map(proof.changes.map(change => [change.path, change.after]));
    });
    requalify('documentation delta never opts in automatically', (proof, h) => {
        h.finish = args => { delete args.sourceEquivalence; };
    }, /Frozen source differs from checkout/);
    requalify('stale static documentation bytes cannot use the equivalence result', (proof, h) => {
        h.args.requiredSourceDigests = new Map([['README.md', sha(inputs.get('README.md'))]]);
    }, /Static\/runtime input changed/);
    for (const [name, mutation, expected] of [
        ['receipt pin', p => { p.receiptSha256 = '0'.repeat(64); }, /receipt\/source binding/],
        ['original source pin', p => { p.originalSourceManifestSha256 = '0'.repeat(64); }, /receipt\/source binding/],
        ['unknown schema', p => { p.schema = 2; }, /schema/],
        ['unknown proof field', p => { p.allowSourceDrift = true; }, /Source equivalence/],
        ['missing change', p => { p.changes = []; }, /exact changed paths/],
        ['extra unchanged path', p => { p.changes.push({ path: 'pom.xml', before: sha(inputs.get('pom.xml')), after: sha(inputs.get('pom.xml')) }); }, /exact changed paths/],
        ['duplicate change', p => { p.changes.push(p.changes[0]); }, /exact changed paths/],
        ['incorrect old digest', p => { p.changes[0].before = '0'.repeat(64); }, /member digest/],
        ['incorrect new digest', p => { p.changes[0].after = '0'.repeat(64); }, /member digest/],
        ['unknown change field', p => { p.changes[0].approved = true; }, /Source equivalence change/],
        ['absolute change path', p => { p.changes[0].path = '/README.md'; }, /Unsafe evidence path/],
        ['excessive change list', p => { p.changes = Array(129).fill(p.changes[0]); }, /Bounded/]
    ]) requalify('documentation equivalence rejects ' + name, mutation, expected);
    requalify('documentation equivalence requires an actual delta', (proof, h) => {
        h.current.set('README.md', inputs.get('README.md')); h.save(checkout, 'README.md', inputs.get('README.md')); h.seal();
    }, /Bounded nonempty/);
    requalify('documentation equivalence rejects removed inventory members', (proof, h) => {
        h.current.delete('pom.xml'); h.seal();
    }, /full inventory/);
    requalify('documentation equivalence rejects added documentation inventory members', (proof, h) => {
        h.current.set('docs/extra.md', Buffer.from('SYNTHETIC ADDED DOCUMENT')); h.seal();
    }, /full inventory/);
    requalify('documentation equivalence rejects manifest case aliases', (proof, h) => {
        proof.currentSourceManifest = h.save(evidence, proof.currentSourceManifest.path,
            manifest(h.current) + sha(inputs.get('README.md')) + '  readme.md\n');
    }, /Duplicate/);
    const forbidden = [productionMember, 'pom.xml', modules[0] + '/pom.xml', 'tools/k8s/SampleRuntimeEvidence.java',
        'tools/ci/verify-v019-sample-runtime.mjs', 'tools/ci/test-v019-sample-runtime.mjs',
        'tools/ci/test-v019-sample-matrix.mjs', 'tools/ci/v019-sample-runtime-evidence.md'];
    for (const provider of ['postgresql', 'mssql', 'oracle']) {
        const prefix = 'sample-cache-database-' + provider;
        forbidden.push(prefix + '/pom.xml');
        for (const accepts of [name => name.startsWith(prefix + '/src/') && name.endsWith('.java'),
            name => name.startsWith(prefix + '/src/') && /\.(?:ya?ml|properties)$/.test(name),
            name => name.startsWith(prefix + '/scripts/'), name => name.startsWith(prefix + '/postman/')]) {
            const member = [...inputs.keys()].find(accepts);
            assert(member, 'Actual provider executable/configuration input required: ' + prefix); forbidden.push(member);
        }
    }
    for (const member of forbidden) requalify('repinning cannot approve executable/non-documentation change: ' + member, (proof, h) => {
        h.edit(member); h.seal();
    }, /permits documentation only/);
    requalify('valid documentation proof cannot conceal an unlisted product change', (proof, h) => {
        h.save(checkout, productionMember, Buffer.concat([inputs.get(productionMember), Buffer.from('\nUNLISTED_PRODUCT_DRIFT\n')]));
    }, /Requalified source differs from checkout/);
    for (const member of ['src/main/java/Unlisted.java', 'src/main/resources/unlisted.yml', 'scripts/unlisted.ps1', 'postman/unlisted.json'])
        requalify('valid documentation proof cannot omit a newly added executable input: ' + member, (proof, h) => {
            h.save(checkout, 'sample-cache-database-postgresql/' + member, 'SYNTHETIC UNLISTED INPUT\n');
        }, /Frozen source manifest omits required input/);
    requalify('documentation equivalence does not relax the execution overlay authority', (proof, h) => {
        const receipt = structuredClone(base);
        receipt.sdk.executionOverlay = { kind: 'SAMPLE_DIAGNOSTICS_AND_ROSTER_141', buildSourceManifest: sourceRef,
            delta: { path: 'invented-overlay.json', sha256: '0'.repeat(64) } };
        h.args.receiptSha256 = h.save(evidence, 'receipt.json', JSON.stringify(receipt)).sha256;
        proof.receiptSha256 = h.args.receiptSha256;
    }, /Unapproved execution overlay SDK\/source authority/);
    for (const member of ['receipt.json', 'source.sha256', 'current-source.sha256', 'source-equivalence.json', 'source/README.md', 'sdk/' + sdk[0]])
        requalify('documentation equivalence retains byte-integrity checks: ' + member, (proof, h) => {
            h.finish = () => h.save(evidence, member, Buffer.concat([fs.readFileSync(path.join(evidence, member)), Buffer.from('\nTAMPER\n')]));
        }, /digest mismatch/);
    requalify('documentation proof requires an explicit coordinator SHA256 pin', (proof, h) => {
        h.finish = args => { delete args.sourceEquivalence.sha256; };
    }, /File reference/);
    requalify('documentation proof rejects an incorrect coordinator SHA256 pin', (proof, h) => {
        h.finish = args => { args.sourceEquivalence.sha256 = '0'.repeat(64); };
    }, /digest mismatch/);
    requalify('documentation proof rejects traversal references', (proof, h) => {
        h.finish = args => { args.sourceEquivalence.path = '../source-equivalence.json'; };
    }, /Unsafe evidence path/);
    reject('receipt boolean cannot supply qualification', receipt => { receipt.runtimeQualified = true; }, /Runtime receipt/);
    reject('missing provider lane', receipt => { receipt.cells.pop(); }, /nine/);
    reject('duplicate provider lane', receipt => { receipt.cells[1] = receipt.cells[0]; }, /membership/);
    reject('foreign provider', receipt => { receipt.cells[0].provider = 'h2'; }, /membership/);
    reject('mixed SDK cell', receipt => { receipt.cells[0].sdkManifestSha256 = '0'.repeat(64); }, /Mixed/);
    reject('different SDK build source', receipt => { receipt.sdk.sourceManifestSha256 = '0'.repeat(64); }, /source binding/);
    reject('unknown receipt version', receipt => { receipt.schema = 2; }, /schema/);
    reject('failed process cannot qualify', (r, h) => h.replace(r.cells[0].tests.exit, () => '1\n'), /phase exit/);
    reject('failed preservation cannot qualify', (r, h) => h.replace(r.cells[0].http.preservationExit, () => '1\n'), /phase exit/);
    reject('fixture reuse across TEST and HTTP', r => { r.cells[0].http.fixtureId = r.cells[0].tests.fixtureId; }, /fixtures alias/);
    reject('phase evidence path reuse', r => { r.cells[0].tests.preservationExit = r.cells[0].tests.exit; }, /Reused phase/);
    reject('phase exceeds bound', r => { r.cells[0].tests.finishedAt = '2026-11-07T00:20:00Z'; }, /timing/);
    reject('phase exceeds cohort finish', r => { r.cells[0].http.finishedAt = '2026-11-07T01:00:01Z'; }, /timing/);
    reject('original deadline exceeded', r => { r.execution.cutoffAt = '2026-11-07T00:59:59Z'; }, /cutoff/);
    reject('calendar rollover rejected', r => { r.execution.startedAt = '2026-02-30T00:00:00Z'; }, /timestamp/);
    reject('nonpreserved SDK manifest', r => { r.cells[0].tests.sdkAfter.sha256 = '0'.repeat(64); }, /preservation/);
    reject('JUnit missing report', r => { r.cells[0].tests.reports.pop(); }, /report roster/);
    reject('JUnit extra report', r => { r.cells[0].tests.reports.push(r.cells[0].tests.reports[0]); }, /report roster/);
    reject('JUnit duplicate suite', r => { r.cells[0].tests.reports[1] = r.cells[0].tests.reports[0]; }, /Reused phase/);
    reject('JUnit count cannot hide missing testcase', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace(/<testcase[^>]*\/>/, '')), /testcase count/);
    reject('JUnit extra testcase', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace('</testsuite>', '<testcase name="extra"/></testsuite>')), /testcase count/);
    reject('JUnit wrong exact method', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace(/(<testcase[^>]*name=")[^"]+/, '$1inventedMethod')), /methods/);
    reject('JUnit wrong classname', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace(/classname="[^"]+"/, 'classname="wrong.Type"')), /class\/status/);
    reject('JUnit duplicate method', (r, h) => h.replace(r.cells[0].tests.reports[0], s => {
        const names = [...s.matchAll(/<testcase[^>]*\sname="([^"]+)"/g)].map(match => match[1]);
        return s.replace('name="' + names[1] + '"', 'name="' + names[0] + '"');
    }), /methods/);
    for (const tag of ['failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'])
        reject('JUnit ' + tag + ' rejected despite zero counters', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace('/>', '><' + tag + '/></testcase>')), /Failed\/skipped/);
    reject('JUnit declared failed count', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace('failures="0"', 'failures="1"')), /count mismatch/);
    reject('JUnit DTD prohibited', (r, h) => h.replace(r.cells[0].tests.reports[0], s => '<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/passwd">]>' + s), /XML/);
    reject('JUnit nested extra testcase', (r, h) => h.replace(r.cells[0].tests.reports[0], s => s.replace('</testsuite>', '<system-out><testcase name="extra"/></system-out></testsuite>')), /extra testcase/);
    reject('raw report byte tamper', (r, h) => { h.change(r.cells[0].tests.reports[0].path, 'tampered'); }, /digest mismatch/);
    reject('Newman missing request', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions.pop(); }), /executions/);
    reject('Newman extra request', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions.push(j.run.executions[0]); }), /executions/);
    reject('Newman duplicate request', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions[1] = j.run.executions[0]; }), /order\/response/);
    reject('Newman assertion missing', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions[0].assertions.pop(); }), /assertions/);
    reject('Newman failed assertion', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions[0].assertions[0].error = {}; }), /Failed\/skipped/);
    reject('Newman skipped assertion', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions[0].assertions[0].skipped = true; }), /Failed\/skipped/);
    reject('Newman wrong response status', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.executions[0].response.code = 500; }), /HTTP status/);
    reject('Newman wrong assertion total', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.stats.assertions.total++; }), /totals/);
    reject('Newman pending request', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.stats.requests.pending = 1; }), /pending/);
    reject('Newman failure list nonempty', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.failures.push({}); }), /executions/);
    reject('Newman altered saved collection script', (r, h) => h.json(r.cells[0].http.newman, j => {
        entries(j.collection)[0].node.event.find(event => event.listen === 'test').script.exec = ["pm.test('HTTP 200', () => {});"];
    }), /collection contract/);
    reject('Newman declared failure counter', (r, h) => h.json(r.cells[0].http.newman, j => { j.run.stats.testScripts.failed = 1; }), /counters/);
    reject('Newman JUnit assertion mismatch', (r, h) => h.replace(r.cells[0].http.junit, s => s.replace(/(<testcase name=")[^"]+/, '$1not-required')), /assertions/);
    for (const lane of ['audit-derived-delete', 'encrypted-notes', 'tenant-purge']) {
        reject('Newman JUnit root assertion total is not a request count: ' + lane, (r, h) => {
            const cell = r.cells.find(cell => cell.lane === lane);
            h.replace(cell.http.junit, s => s.replace(/^(<testsuites tests=")\d+/, '$1' + [...s.matchAll(/<testcase\b/g)].length));
        }, /XML tests count mismatch/);
    }
    reject('Newman JUnit root wrong request count', (r, h) => h.replace(r.cells[0].http.junit,
        s => s.replace(/^(<testsuites tests=")\d+/, (_, prefix) => prefix + '999')), /XML tests count mismatch/);
    for (const name of ['failures', 'errors', 'skipped'])
        reject('Newman JUnit root nonzero ' + name, (r, h) => h.replace(r.cells[0].http.junit,
            s => s.replace('<testsuites ', '<testsuites ' + name + '="1" ')), /XML .* count mismatch/);
    reject('Newman JUnit request assertion count remains exact', (r, h) => h.replace(r.cells[0].http.junit,
        s => s.replace(/(<testsuite\b[^>]*\btests=")\d+/, (_, prefix) => prefix + '999')), /XML tests count mismatch/);
    reject('Newman JUnit request order remains exact', (r, h) => h.replace(r.cells[0].http.junit, s => {
        const suites = [...s.matchAll(/<testsuite\b[\s\S]*?<\/testsuite>/g)].map(match => match[0]);
        return s.replace(suites[0] + suites[1], suites[1] + suites[0]);
    }), /request\/order\/count/);
    reject('Newman JUnit assertion failure cannot hide behind passing counters', (r, h) => h.replace(r.cells[0].http.junit,
        s => s.replace(/(<testcase\b[^>]*?)\/>/, '$1><failure/></testcase>')), /Failed\/skipped/);
    reject('purge JUnit is required and exact without invented JSON', (r, h) => {
        const cell = r.cells.find(cell => cell.lane === 'tenant-purge');
        h.replace(cell.http.junit, s => s.replace('Owned hot row delivered', 'invented purge assertion'));
    }, /assertions/);
    reject('path traversal', r => { r.cells[0].tests.exit.path = '../run.exit'; }, /Unsafe/);
    reject('absolute path', r => { r.cells[0].tests.exit.path = '/tmp/run.exit'; }, /Unsafe/);
    reject('Windows drive path', r => { r.cells[0].tests.exit.path = 'C:/run.exit'; }, /Unsafe/);
    reject('source and SDK roots overlap', r => { r.sdk.root = 'source/sdk'; }, /overlap/);
    reject('omitted production source input', (r, h) => {
        h.replace(r.source.manifest, text => text.split('\n')
            .filter(line => !line.endsWith(productionMember)).join('\n'));
        r.sdk.sourceManifestSha256 = r.source.manifest.sha256;
    }, /omits required/);
    for (const member of ['cachedb-annotations/src', ...['postgresql', 'mssql', 'oracle'].flatMap(provider =>
        ['src', 'scripts', 'postman'].map(directory => 'sample-cache-database-' + provider + '/' + directory))]) {
        reject('whole required tree omitted: ' + member, (r, h) => h.omitTree(member), /Required source tree missing:/);
        reject('whole required tree empty: ' + member, (r, h) => h.omitTree(member, true), /Required source tree empty:/);
    }
    reject('omitted SDK classifier', (r, h) => h.replace(r.sdk.manifest, text => text.replace(/^.*-sources\.jar\n/m, '')), /classifier roster/);
    reject('altered frozen source', (r, h) => { const name = [...inputs.keys()][0]; h.change('source/' + name, 'tampered'); }, /digest mismatch/);
    reject('altered SDK bytes', (r, h) => { h.change('sdk/' + sdk[0], 'tampered'); }, /digest mismatch/);
    reject('oversized raw report', (r, h) => h.replace(r.cells[0].tests.reports[0], () => ' '.repeat(16 * 1024 * 1024 + 1)), /byte budget/);
    test('coordinator pin is mandatory and checked', () => {
        assert.throws(() => verifySampleRuntime(options(undefined)), /Coordinator/);
        assert.throws(() => verifySampleRuntime(options('0'.repeat(64))), /digest mismatch/);
    });
    for (const count of [1, 3, 34]) {
        test('checkout drift reports ' + count + ' changed files without qualifying or rewriting evidence', () => {
            const members = sourceText.trimEnd().split('\n').map(line => line.slice(66)).slice(0, count);
            assert.equal(members.length, count);
            const backups = members.map(member => [member, fs.readFileSync(path.join(checkout, member))]);
            try {
                for (const member of members) fs.appendFileSync(path.join(checkout, member), '\nCHECKOUT_DRIFT_FIXTURE\n');
                const listed = members.slice(0, 32);
                const expected = 'Frozen source differs from checkout (' + count + ' files): ' + listed.join(', ') +
                    (count > listed.length ? '; ' + (count - listed.length) + ' additional paths omitted' : '');
                assert.throws(() => verifySampleRuntime(options(pin)), error => {
                    assert.equal(error.message, expected);
                    return true;
                });
                assert.equal(fs.readFileSync(receiptPath, 'utf8'), pristine);
                assert.equal(fs.readFileSync(path.join(evidence, sourceRef.path), 'utf8'), sourceText);
                for (const [member, bytes] of backups)
                    assert(fs.readFileSync(path.join(evidence, 'source', member)).equals(bytes));
            } finally {
                for (const [member, bytes] of backups) fs.writeFileSync(path.join(checkout, member), bytes);
            }
        });
    }
    test('checkout drift cannot conceal a later frozen source digest violation', () => {
        const members = sourceText.trimEnd().split('\n').map(line => line.slice(66));
        const first = path.join(checkout, members[0]);
        const last = path.join(evidence, 'source', members.at(-1));
        const before = fs.readFileSync(first), frozen = fs.readFileSync(last);
        try {
            fs.appendFileSync(first, '\nCHECKOUT_DRIFT_FIXTURE\n');
            fs.appendFileSync(last, '\nEVIDENCE_TAMPER_FIXTURE\n');
            assert.throws(() => verifySampleRuntime(options(pin)), /digest mismatch/);
        } finally {
            fs.writeFileSync(first, before);
            fs.writeFileSync(last, frozen);
        }
    });
    test('invalid receipt leaves checker deferred rather than publishing a partial PASS', () => {
        const result = checkSampleCoverage({ root: checkout, receiptPath, receiptSha256: '0'.repeat(64) });
        assert.equal(result.runtimeQualified, false); assert.equal(result.complete, false); assert.equal(result.deferred.length, 12);
        assert.equal(result.runtimeErrors.length, 1); assert.equal(result.qualifiedFeatures, undefined);
    });
    test('paired CLI options are explicit and reject unknown/duplicate switches', () => {
        assert.equal(sampleCoverageOptions(['--runtime-evidence', receiptPath, '--runtime-evidence-sha256', pin])['runtime-evidence-sha256'], pin);
        for (const args of [['--runtime-evidence', receiptPath], ['--runtime-evidence-sha256', pin], ['--runtime-qualified', 'true'],
            ['--root', checkout, '--root', checkout], ['--runtime-evidence']]) assert.throws(() => sampleCoverageOptions(args));
    });
    test('PowerShell wrapper source preserves bound-option validation and literal argument passing', () => {
        const wrapper = inputs.get('tools/ci/check-v019-sample-coverage.ps1').toString('utf8').replace(/\r\n/g, '\n');
        for (const statement of [
            "[string]$RuntimeEvidencePath = ''", "[string]$RuntimeEvidenceSha256 = ''",
            "$hasEvidencePath = $PSBoundParameters.ContainsKey('RuntimeEvidencePath')",
            "$hasEvidenceHash = $PSBoundParameters.ContainsKey('RuntimeEvidenceSha256')",
            'if ($hasEvidencePath -ne $hasEvidenceHash) {',
            'if ($hasEvidencePath -and ([string]::IsNullOrWhiteSpace($RuntimeEvidencePath) -or',
            "$RuntimeEvidenceSha256 -cnotmatch '^[a-f0-9]{64}$')) {",
            "$arguments = @($checker, '--root', $root, '--summary', $SummaryPath)",
            "if ($hasEvidencePath) {\n    $receipt = (Resolve-Path -LiteralPath $RuntimeEvidencePath).Path\n" +
                "    $arguments += @('--runtime-evidence', $receipt, '--runtime-evidence-sha256', $RuntimeEvidenceSha256)\n}",
            '& $node.Source @arguments', 'if ($LASTEXITCODE -ne 0) {'
        ]) assert(wrapper.includes(statement), 'Required PowerShell source contract missing: ' + statement);
        assert(wrapper.indexOf('if ($hasEvidencePath -ne $hasEvidenceHash)') < wrapper.indexOf('$root ='));
        assert(wrapper.indexOf("$RuntimeEvidenceSha256 -cnotmatch") < wrapper.indexOf('$root ='));
        assert.doesNotMatch(wrapper, /\b(?:Invoke-Expression|iex)\b/i);
    });
    if (process.env.CACHEDB_TEST_PWSH_CONTRACT === 'true') {
        assert(process.env.KUBERNETES_SERVICE_HOST, 'PowerShell runtime contracts require Kubernetes');
        const wrapper = path.join(checkout, 'tools/ci/check-v019-sample-coverage.ps1');
        const invoke = args => spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-NonInteractive', '-File', wrapper, ...args], {
            cwd: home, encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
            env: { ...process.env, DOTNET_GCHeapHardLimit: '0x8000000', POWERSHELL_TELEMETRY_OPTOUT: '1' }
        });
        test('PowerShell rejects an unpaired receipt before resolving paths', () => {
            const result = invoke(['-RepositoryRoot', '/missing-checkout', '-RuntimeEvidencePath', '/missing-receipt']);
            assert.ifError(result.error); assert.notEqual(result.status, 0);
            assert.match(result.stderr, /Provide RuntimeEvidencePath and RuntimeEvidenceSha256 together/);
        });
        test('PowerShell rejects an invalid receipt pin before resolving paths', () => {
            const result = invoke(['-RepositoryRoot', '/missing-checkout', '-RuntimeEvidencePath', '/missing-receipt',
                '-RuntimeEvidenceSha256', 'invalid']);
            assert.ifError(result.error); assert.notEqual(result.status, 0);
            assert.match(result.stderr, /coordinator-pinned lowercase SHA256 required/);
        });
        test('PowerShell retains fail-closed behavior without a receipt', () => {
            const summary = path.join(checkout, 'target', 'source only summary.md');
            const result = invoke(['-RepositoryRoot', checkout, '-SummaryPath', summary]);
            assert.ifError(result.error); assert.notEqual(result.status, 0);
            assert(fs.existsSync(summary), 'Source-only diagnostic summary required: ' + result.stderr);
            assert.match(result.stderr, /sample scenario matrix is incomplete/);
        });
        test('PowerShell forwards the pinned synthetic receipt and literal paths', () => {
            const summary = path.join(checkout, 'target', 'qualified fixture summary.md');
            const result = invoke(['-RepositoryRoot', checkout, '-SummaryPath', summary,
                '-RuntimeEvidencePath', receiptPath, '-RuntimeEvidenceSha256', pin]);
            assert.ifError(result.error); assert.equal(result.status, 0, result.stderr);
            assert(fs.existsSync(summary), 'Qualified fixture diagnostic summary required');
            assert.equal(checkSampleCoverage({ root: checkout, receiptPath, receiptSha256: pin }).releaseAccepted, false,
                'Tooling fixture is never release acceptance');
        });
    }
    if (process.platform !== 'win32') {
        test('symlink phase reference rejected', () => {
            const link = path.join(evidence, 'alias.exit'); fs.symlinkSync(base.cells[0].tests.exit.path, link);
            try {
                const receipt = structuredClone(base); receipt.cells[0].tests.exit.path = 'alias.exit';
                const hash = write(evidence, 'receipt.json', JSON.stringify(receipt)).sha256;
                assert.throws(() => verifySampleRuntime(options(hash)), /Symlink/);
            } finally { fs.unlinkSync(link); fs.writeFileSync(receiptPath, pristine); }
        });
        test('hardlink cannot reuse a phase report', () => {
            const original = path.join(evidence, base.cells[0].tests.exit.path), link = path.join(evidence, 'alias.exit');
            fs.linkSync(original, link);
            try {
                const receipt = structuredClone(base); receipt.cells[0].tests.preservationExit.path = 'alias.exit';
                const hash = write(evidence, 'receipt.json', JSON.stringify(receipt)).sha256;
                assert.throws(() => verifySampleRuntime(options(hash)), /Reused phase/);
            } finally { fs.unlinkSync(link); fs.writeFileSync(receiptPath, pristine); }
        });
    }
    }
    }
    process.stdout.write(cases + (originalCellsOnly ? ' isolated original-cell composite controls' : finalSourceOnly ? ' isolated E-to-final-source tooling overlay contract controls' :
        overlayOnly ? ' isolated E-to-G tooling overlay contract controls' : ' synthetic runtime ingestion checks') +
        ' passed; no live product acceptance is implied.\n');
} finally {
    assert(path.dirname(home) === os.tmpdir() && path.basename(home).startsWith('cachedb-runtime-verifier-'));
    fs.rmSync(home, { recursive: true, force: true });
}
