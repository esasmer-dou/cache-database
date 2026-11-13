import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';

const root = '/depot', meta = root + '/metadata';
const need = (v, m) => {if (!v) throw Error(m);};
const hash = async p => {const h = crypto.createHash('sha256'); for await (const b of fs.createReadStream(p)) h.update(b); return h.digest('hex');};
need(await hash(root + '/input/source.sha256') === 'c338942da83f72ba5f0b4afcdce4fb028159d4313f19ae828226d0f12bd3cb6b', 'Original source manifest');
need(await hash(root + '/metadata/sdk.sha256') === '6d4947027db8568dc73f55f9dcb48f9527f5cf3c80a50101310e38111f21cf55', 'Original SDK92 manifest');
const lines = [], links = [];
let count = 0, total = 0;
async function walk(dir) {
  for (const e of fs.readdirSync(dir, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name))) {
    const p = dir + '/' + e.name, rel = path.relative(root, p);
    need(!/[\r\n\\]/.test(rel) && ++count <= 30000, 'Cache path/count bound');
    if (e.isDirectory()) await walk(p);
    else if (e.isFile()) {
      const size = fs.statSync(p).size; total += size;
      need(size <= 268435456 && total <= 1342177280, 'Cache size bound');
      lines.push(await hash(p) + '  ' + rel + '\n');
    } else if (e.isSymbolicLink()) {
      const base = rel.startsWith('toolchain/') ? root + '/toolchain/'
        : rel.startsWith('newman-cache/') ? root + '/newman-cache/' : null;
      need(base !== null, 'Symlinks allowed only in toolchain or newman-cache');
      const target = fs.readlinkSync(p);
      need(!path.isAbsolute(target), 'Relocatable relative symlink only');
      const resolved = fs.realpathSync(p);
      need(resolved.startsWith(base) && fs.statSync(resolved).isFile(), 'Internal regular symlink only');
      links.push({path: rel, target, resolvedSha256: await hash(resolved)});
    } else throw Error('Nonregular cache entry');
  }
}
for (const tree of ['repository', 'newman-cache', 'toolchain']) await walk(root + '/' + tree);
fs.writeFileSync(meta + '/cache.sha256', lines.sort().join(''));
fs.writeFileSync(meta + '/symlinks.json', JSON.stringify(links, null, 2) + '\n');
const sdk = fs.readFileSync(meta + '/sdk.sha256', 'utf8').trimEnd().split('\n');
need(sdk.length === 92, 'SDK92 roster');
const main = sdk.map(l => l.slice(66)).filter(p => p.endsWith('.jar') && !p.endsWith('-sources.jar') && !p.endsWith('-javadoc.jar'));
need(main.length === 23, 'SDK main jar roster');
const deps = [
  ['com.fasterxml.jackson.core','jackson-databind','2.17.2'], ['com.fasterxml.jackson.core','jackson-core','2.17.2'], ['com.fasterxml.jackson.core','jackson-annotations','2.17.2'],
  ['redis.clients','jedis','5.2.0'], ['org.apache.commons','commons-pool2','2.12.0'], ['com.google.code.gson','gson','2.13.2'], ['com.zaxxer','HikariCP','5.1.0'],
  ['org.slf4j','slf4j-api','2.0.16'], ['org.slf4j','slf4j-simple','2.0.13'], ['org.postgresql','postgresql','42.7.4'], ['com.microsoft.sqlserver','mssql-jdbc','12.8.1.jre11'],
  ['com.oracle.database.jdbc','ojdbc17','23.26.1.0.0'], ['org.junit.jupiter','junit-jupiter-api','5.10.2'], ['org.junit.platform','junit-platform-commons','1.10.2'],
  ['org.opentest4j','opentest4j','1.3.0'], ['org.apiguardian','apiguardian-api','1.1.2']
];
const cp = main.concat(deps.map(([g,a,v]) => `${g.replaceAll('.','/')}/${a}/${v}/${a}-${v}.jar`)).map(p => 'repository/' + p);
const cpLines = []; for (const p of cp) cpLines.push(await hash(root + '/' + p) + '  ' + p + '\n');
fs.writeFileSync(meta + '/native-classpath.paths', cp.join('\n') + '\n');
fs.writeFileSync(meta + '/native-classpath.sha256', cpLines.join(''));
const tools = {};
for (const n of ['java','javac','mvn','node','npx']) {
  const p = fs.realpathSync(execFileSync('which', [n], {encoding: 'utf8'}).trim());
  tools[n] = {path: p, sha256: await hash(p)};
}
tools.javaMavenImage = 'maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e';
tools.nodeImage = 'node:22-bookworm-slim@sha256:3ed748ce9dea62d2f0f0205cdbb6823106d9f973a6f555c402fe2575f8d6e1ae';
tools.requiredLaterPowerShellImage = 'mcr.microsoft.com/dotnet/sdk:8.0-bookworm-slim@sha256:ff39a765f04619c8d146796fc09b38499ae5984f4a2a4c3a0a57d0022855daee';
fs.writeFileSync(meta + '/tool-targets.json', JSON.stringify(tools, null, 2) + '\n');
const scriptNames = ['prepare-sample-artifact-cache.sh', 'sample-artifact-depot.mjs', 'seal-sample-artifact-cache.mjs'];
need(await hash('/scripts/scripts.sha256') === process.env.DEPOT_SCRIPTS_SHA256, 'Admitted producer script manifest');
const scriptLines = []; const mountedLines = [];
for (const p of scriptNames) {
  const digest = await hash('/scripts/' + p);
  scriptLines.push(digest + '  scripts/' + p + '\n');
  mountedLines.push(digest + '  ' + p + '\n');
}
need(fs.readFileSync('/scripts/scripts.sha256', 'utf8') === mountedLines.join(''), 'Exact producer script roster');
fs.writeFileSync(meta + '/producer-tools.sha256', scriptLines.join(''));
fs.writeFileSync(meta + '/summary.json', JSON.stringify({qualification: 'CACHE_ONLY_NO_PRODUCT_TESTS', files: lines.length, symlinks: links.length, bytes: total,
  newman: '6.2.1', sdkFiles: 92, nativeClasspathEntries: cp.length, productRebuilt: false, originMetadataRetained: true, mavenStrictChecksums: true,
  javaMavenBinaries: 'Restore from the pinned image; Node/npm targets are included.'}, null, 2) + '\n');
const metadata = []; for (const p of fs.readdirSync(meta).filter(p => p !== 'metadata.sha256').sort()) metadata.push(await hash(meta + '/' + p) + '  metadata/' + p + '\n');
fs.writeFileSync(meta + '/metadata.sha256', metadata.join(''));
console.log(JSON.stringify({files: lines.length, bytes: total, classpath: cp.length}));
