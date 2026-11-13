#!/usr/bin/env bash
set -Eeuo pipefail
set +x +v
umask 077
[[ $(id -u) == 1000 ]]
mkdir -p /depot/{home,tmp,evidence,repository,source,sdk-import,metadata,newman-cache}
export LC_ALL=C TZ=UTC MAVEN_SKIP_RC=1 TMPDIR=/depot/tmp
export MAVEN_OPTS='-Xms32m -Xmx384m -XX:ActiveProcessorCount=1'
export NODE_OPTIONS='--max-old-space-size=192'
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
ulimit -c 0; ulimit -u 256; ulimit -f 524288
exec 9>/depot/cache.lock
flock -n 9
finish(){ local rc=$?;trap - EXIT;date -u +%FT%TZ > /depot/evidence/finished.part;mv /depot/evidence/finished.part /depot/evidence/finished.utc;printf '%s\n' "$rc" > /depot/evidence/preparation.part;mv /depot/evidence/preparation.part /depot/evidence/preparation.exit;exit "$rc"; }
trap finish EXIT
for tool in node java javac mvn tar sha256sum flock timeout;do command -v "$tool" >/dev/null;done
node -e 'if(process.versions.node.split(".")[0]!=="22")throw Error("Node22 required")'
node --check /scripts/sample-artifact-depot.mjs
node --check /scripts/seal-sample-artifact-cache.mjs
bash -n /scripts/prepare-sample-artifact-cache.sh
[[ ${DEPOT_SCRIPTS_SHA256:-} =~ ^[a-f0-9]{64}$ ]]
node --input-type=module <<'JS'
import fs from 'node:fs';
import crypto from 'node:crypto';
const root = '/depot/toolchain/node/', rows = [];
for (const name of ['node', 'npm', 'npx']) {
  const p = root + 'bin/' + name, s = fs.lstatSync(p);
  const target = s.isSymbolicLink() ? fs.readlinkSync(p) : null;
  const expected = name === 'node' ? null : '../lib/node_modules/npm/bin/' + name + '-cli.js';
  const resolved = fs.realpathSync(p), regular = fs.statSync(resolved);
  if (target !== expected || !regular.isFile() || !resolved.startsWith(root)
      || (name === 'node' && !s.isFile())) throw Error('Minimal Node copy boundary');
  const h = crypto.createHash('sha256');
  for await (const b of fs.createReadStream(resolved)) h.update(b);
  rows.push({path: 'bin/' + name, target, mode: regular.mode, bytes: regular.size, sha256: h.digest('hex')});
}
fs.writeFileSync('/depot/metadata/node-copy.json', JSON.stringify(rows, null, 2) + '\n');
JS
printf '%s\n' "$DEPOT_SCRIPTS_SHA256" > /depot/evidence/tool-preflight.ok
printf 'DEPOT_TOOL_PREFLIGHT_READY\n'
# One VPN seed phase; this is infrastructure retention, never test authority.
timeout 900 bash -c 'until test -f /depot/input/seed.ready;do sleep 2;done'
node -e 'const f=require("fs"),a=JSON.parse(f.readFileSync("/depot/input/authority.json")),c=Date.parse(a.creationTimestamp),d=Date.parse(a.originalCutoff);if(a.podUid!==process.env.POD_UID||a.activeDeadlineSeconds!==3600||!Number.isFinite(c)||!Number.isFinite(d)||d-c!==3600000||c>Date.now()||d<=Date.now()||f.readFileSync("/depot/input/cutoff.utc","utf8")!==a.originalCutoff+"\n")throw Error("Authority")'
cutoff=$(cat /depot/input/cutoff.utc)
phase_end=$(( $(date -u +%s) + 600 ))
absolute_end=$(date -u -d "$cutoff" +%s)
(( phase_end < absolute_end - 180 )) || phase_end=$(( absolute_end - 180 ))
step(){
 local name=$1 seconds=$2 left rc;shift 2;left=$(( phase_end - $(date -u +%s) ));test "$left" -gt 0
 (( seconds <= left )) || seconds=$left
 date -u +%FT%TZ > "/depot/evidence/$name.started"
 set +e;timeout --signal=TERM --kill-after=3s "$seconds" "$@" > "/depot/evidence/$name.log" 2>&1;rc=$?;set -e
 printf '%s\n' "$rc" > "/depot/evidence/$name.exit";printf '%s exit=%s\n' "$name" "$rc"
 if (( rc != 0 ));then
  # These cache-only tools receive no credentials; retain a bounded first-failure tail.
  tail -c 8192 "/depot/evidence/$name.log"
  printf 'cache preparation failed: stage=%s exit=%s\n' "$name" "$rc" > /dev/termination-log
 fi
 test "$rc" -eq 0
}
(cd /depot/input && sha256sum --strict --status -c payload.sha256)
printf '%s  /depot/input/source.sha256\n' c338942da83f72ba5f0b4afcdce4fb028159d4313f19ae828226d0f12bd3cb6b | sha256sum --strict --status -c -
printf '%s  /depot/input/settings-public.xml\n%s  /depot/input/settings-offline.xml\n' eb6b61ec766f35b48d297bbac7421b98bbee2a6d235d5d88d6e147333ddd1768 183668ce4e29a3f85583130a51ad0fb3fadf8af4235cd38c8151a3a62787cd21 | sha256sum --strict --status -c -
(cd /depot/published && printf '%s  %s.tgz\n' d1f5c51f77c50fe77351a528f8f96a1113011a13e8b34bded83f5466524f4d38 d1f5c51f77c50fe77351a528f8f96a1113011a13e8b34bded83f5466524f4d38 ee4e4a2fbe82ea6ff76653e82e0f62367f21c86d4e0bd1a9b5c45fd300c48f09 ee4e4a2fbe82ea6ff76653e82e0f62367f21c86d4e0bd1a9b5c45fd300c48f09 e21e682cfe25ae5d8e81977a7fa935cd871783b9997d9d94983ee3f91e8bb2bb e21e682cfe25ae5d8e81977a7fa935cd871783b9997d9d94983ee3f91e8bb2bb | sha256sum --strict --status -c -)
tar -xzf /depot/published/d1f5c51f77c50fe77351a528f8f96a1113011a13e8b34bded83f5466524f4d38.tgz -C /depot/repository --strip-components=3
tar -xzf /depot/published/ee4e4a2fbe82ea6ff76653e82e0f62367f21c86d4e0bd1a9b5c45fd300c48f09.tgz -C /depot/source
tar -xzf /depot/published/e21e682cfe25ae5d8e81977a7fa935cd871783b9997d9d94983ee3f91e8bb2bb.tgz -C /depot/sdk-import
printf '%s  /depot/sdk-import/sdk.sha256\n' 6d4947027db8568dc73f55f9dcb48f9527f5cf3c80a50101310e38111f21cf55 | sha256sum --strict --status -c -
(cd /depot/source && sha256sum --strict --status -c /depot/input/source.sha256)
cmp /depot/input/source.sha256 /depot/sdk-import/source.sha256
(cd /depot/sdk-import/sdk && sha256sum --strict --status -c ../sdk.sha256)
test ! -e /depot/repository/com/reactor/cachedb
(cd /depot/repository && find . -type f -print0 | sort -z | xargs -0 -r sha256sum) > /depot/metadata/base-repository.sha256
cp -R /depot/sdk-import/sdk/. /depot/repository/
for artifact in org.jboss.logging:jboss-logging:3.5.3.Final com.fasterxml:classmate:1.7.0;do
 step "fetch-${artifact%%:*}" 60 mvn -B -ntp -C -f /depot/source/sample-cache-database-postgresql/pom.xml -s /depot/input/settings-public.xml -gs /depot/input/settings-public.xml -Dmaven.repo.local=/depot/repository org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get "-Dartifact=$artifact" -Dtransitive=false
done
for provider in postgresql mssql oracle;do
 step "prefetch-$provider" 100 mvn -B -ntp -C -f "/depot/source/sample-cache-database-$provider/pom.xml" -s /depot/input/settings-public.xml -gs /depot/input/settings-public.xml -Dmaven.repo.local=/depot/repository -Pprovider-integration,encrypted-demo,tenant-purge-demo -DskipTests=true -Dmaven.test.skip=true org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline
done
for provider in postgresql mssql oracle;do
 step "offline-$provider" 30 mvn -o -B -ntp -C -f "/depot/source/sample-cache-database-$provider/pom.xml" -s /depot/input/settings-offline.xml -gs /depot/input/settings-offline.xml -Dmaven.repo.local=/depot/repository -Pprovider-integration,encrypted-demo,tenant-purge-demo -DskipTests=true -Dmaven.test.skip=true org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline
done
export npm_config_cache=/depot/newman-cache npm_config_audit=false npm_config_fund=false
step newman-prefetch 100 npx --yes --package=newman@6.2.1 newman --version
step newman-offline 20 npx --yes --offline --package=newman@6.2.1 newman --version
grep -qx '6.2.1' /depot/evidence/newman-offline.log
(cd /depot/repository && sha256sum --strict --status -c /depot/sdk-import/sdk.sha256)
(cd /depot/source && sha256sum --strict --status -c /depot/input/source.sha256)
cp /depot/sdk-import/{sdk.sha256,source.sha256} /depot/metadata/
step seal 60 node /scripts/seal-sample-artifact-cache.mjs
test "$(du -sb /depot | cut -f1)" -le 1610612736
# Fixed members exclude the Secret mount, arbitrary inputs, unpublished archive and seed archives.
step archive 90 tar -czf /depot/incoming/cache-complete.part -C /depot repository newman-cache toolchain metadata evidence input/authority.json input/cutoff.utc input/source.sha256 input/payload.sha256 input/settings-public.xml input/settings-offline.xml
test "$(stat -c %s /depot/incoming/cache-complete.part)" -le 536870912
step archive-members 20 tar -tzf /depot/incoming/cache-complete.part
cp /depot/evidence/archive-members.log /depot/archive-members.txt
members_pin=$(sha256sum /depot/archive-members.txt);members_pin=${members_pin%% *}
pin=$(sha256sum /depot/incoming/cache-complete.part);pin=${pin%% *}
chmod 400 /depot/incoming/cache-complete.part
test ! -e "/depot/published/$pin.tgz"
mv /depot/incoming/cache-complete.part "/depot/published/$pin.tgz"
printf '{"sha256":"%s","bytes":%s,"membersSha256":"%s","qualification":"CACHE_ONLY_NO_PRODUCT_TESTS"}\n' "$pin" "$(stat -c %s "/depot/published/$pin.tgz")" "$members_pin" > /depot/cache-result.part
chmod 400 /depot/cache-result.part
mv /depot/cache-result.part /depot/cache-result.json
node -e 'const f=require("fs"),c=require("crypto"),p="/depot/published/";const a=f.readdirSync(p);if(a.length!==4||a.some(n=>!/^[a-f0-9]{64}\.tgz$/.test(n)))throw Error("Publication roster");const bytes=JSON.stringify(a.sort())+"\n";f.writeFileSync("/depot/whitelist.part",bytes,{flag:"wx",mode:0o400});f.renameSync("/depot/whitelist.part","/depot/whitelist.json");const r=JSON.parse(f.readFileSync("/depot/cache-result.json"));f.writeFileSync("/depot/publication.part",JSON.stringify({podUid:process.env.POD_UID,cacheSha256:r.sha256,whitelistSha256:c.createHash("sha256").update(bytes).digest("hex")})+"\n",{flag:"wx",mode:0o400});f.renameSync("/depot/publication.part","/depot/publication.ready")'
printf 'CACHE_PUBLISHED sha256=%s\n' "$pin"
