import fs from 'node:fs';
import http from 'node:http';
import crypto from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

// The separate preparer writes; this read-only container stays diagnosable on failure.
const root = '/depot/published';
// The immutable Pod deadline also bounds waiting before the admitted authority arrives.
let deadline = Date.now() + 3600000, stopped = false;
process.once('SIGTERM', () => {stopped = true;});
function small(p, limit = 4096) {
  const s = fs.lstatSync(p);
  if (!s.isFile() || s.size > limit) throw Error('Invalid depot metadata');
  return fs.readFileSync(p);
}
async function waitForPublication() {
  let bound = false, rejected = false;
  while (!stopped && Date.now() < deadline) {
    if (!rejected) {
      let stage = 'authority';
      try {
        if (!bound && fs.existsSync('/depot/input/seed.ready')) {
          const a = JSON.parse(small('/depot/input/authority.json'));
          const created = Date.parse(a.creationTimestamp), cutoff = Date.parse(a.originalCutoff);
          if (a.podUid !== process.env.POD_UID || !Number.isSafeInteger(a.activeDeadlineSeconds)
              || a.activeDeadlineSeconds <= 0 || a.activeDeadlineSeconds > 3600
              || !Number.isFinite(created) || !Number.isFinite(cutoff)
              || cutoff - created !== a.activeDeadlineSeconds * 1000
              || created > Date.now() || cutoff <= Date.now()) throw Error('Invalid authority');
          deadline = cutoff; bound = true;
        }
        stage = 'preparation';
        if (fs.existsSync('/depot/evidence/preparation.exit')
            && small('/depot/evidence/preparation.exit').toString().trim() !== '0') throw Error('Preparation failed');
        if (bound && fs.existsSync('/depot/publication.ready')) {
          stage = 'publication';
          const ready = JSON.parse(small('/depot/publication.ready'));
          const bytes = small('/depot/whitelist.json');
          const result = JSON.parse(small('/depot/cache-result.json'));
          const list = JSON.parse(bytes);
          if (ready.podUid !== process.env.POD_UID || !/^[a-f0-9]{64}$/.test(ready.cacheSha256)
              || crypto.createHash('sha256').update(bytes).digest('hex') !== ready.whitelistSha256
              || result.sha256 !== ready.cacheSha256 || result.qualification !== 'CACHE_ONLY_NO_PRODUCT_TESTS'
              || !Array.isArray(list) || list.length !== 4 || new Set(list).size !== 4
              || !list.includes(ready.cacheSha256 + '.tgz')
              || list.some(p => !/^[a-f0-9]{64}\.tgz$/.test(p))) throw Error('Invalid publication');
          stage = 'credential';
          const credentialRoot = fs.realpathSync('/var/run/depot-auth');
          const tokenPath = fs.realpathSync('/var/run/depot-auth/token');
          if (!tokenPath.startsWith(credentialRoot + '/')) throw Error('Invalid credential path');
          const token = small(tokenPath, 64);
          if (!/^[a-f0-9]{64}$/.test(token.toString('ascii'))) throw Error('Invalid credential');
          return {allowed: new Set(list.map(p => '/' + p)), expected: Buffer.concat([Buffer.from('Bearer '), token])};
        }
      } catch {
        rejected = true;
        console.log('DEPOT_BLOCKED_READ_ONLY_DIAGNOSTICS stage=' + stage);
      }
    }
    await delay(Math.max(1, Math.min(1000, deadline - Date.now())));
  }
  return null;
}
const publication = await waitForPublication();
if (publication && !stopped) {
const {allowed, expected} = publication;
let active = 0;
const server = http.createServer((req, res) => {
  const supplied = Buffer.from(req.headers.authorization ?? '');
  if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) {
    res.writeHead(401, {'Connection': 'close'}).end(); return;
  }
  if (Date.now() >= deadline || req.method !== 'GET' || !allowed.has(req.url)) {
    res.writeHead(404, {'Connection': 'close'}).end(); return;
  }
  if (active >= 2) {res.writeHead(503, {'Connection': 'close'}).end(); return;}
  let fd;
  try {
    fd = fs.openSync(root + req.url, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    const stat = fs.fstatSync(fd);
    if (!stat.isFile() || stat.size <= 0 || stat.size > 536870912) throw Error('Artifact bounds');
    active++;
    let done = false;
    const end = () => {if (!done) {done = true; active--;}};
    const stream = fs.createReadStream('', {fd, autoClose: true, highWaterMark: 65536});
    fd = undefined;
    const timer = setTimeout(() => {stream.destroy(); res.destroy();}, Math.min(720000, deadline - Date.now()));
    timer.unref();
    res.on('close', () => {clearTimeout(timer); stream.destroy(); end();});
    stream.on('error', () => {res.destroy(); end();});
    res.writeHead(200, {'Content-Type': 'application/gzip', 'Content-Length': stat.size,
      'Cache-Control': 'private, immutable', 'Connection': 'close', 'X-Content-Type-Options': 'nosniff'});
    stream.pipe(res);
  } catch {
    if (fd !== undefined) fs.closeSync(fd);
    res.writeHead(404, {'Connection': 'close'}).end();
  }
});
server.maxConnections = 8;
server.maxHeadersCount = 16;
server.headersTimeout = 5000;
server.requestTimeout = 10000;
server.keepAliveTimeout = 1000;
server.on('clientError', (_e, socket) => socket.destroy());
server.listen(8080, '0.0.0.0', () => console.log('DEPOT_READY_READ_ONLY'));
const stop = () => {server.close(); setTimeout(() => process.exit(0), 1000).unref();};
process.on('SIGTERM', stop);
setTimeout(stop, deadline - Date.now()).unref();
}
