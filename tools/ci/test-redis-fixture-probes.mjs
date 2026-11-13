import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = new URL('../../', import.meta.url);
const fixture = fs.readFileSync(new URL('tools/k8s/validation/sample-redis.yml', root), 'utf8');
const readiness = /\n      readinessProbe:\n([\s\S]*?)(?=\n      volumeMounts:)/.exec(fixture.replace(/\r\n/g, '\n'))?.[1];
assert(readiness, 'Redis fixture readiness probe must be present');
assert.match(readiness, /tcpSocket: \{port: redis\}/);
assert.doesNotMatch(readiness, /\bexec:|\btimeout\s+\d+\s+redis-cli/);
assert.match(readiness, /timeoutSeconds: 3/);
const authentication = fs.readFileSync(new URL('tools/k8s/SampleRedisAuthenticationCheck.java', root), 'utf8');
assert.match(authentication, /"PONG"\.equals\(client\.ping\(\)\)/);
const runner = fs.readFileSync(new URL('tools/k8s/run-validation.sh', root), 'utf8');
assert.equal((runner.match(/RedisServicePreflight\.java/g) || []).length, 2,
    'Main and split-role Service endpoints must retain their functional preflight');
const preflight = fs.readFileSync(new URL('tools/k8s/RedisServicePreflight.java', root), 'utf8');
assert.match(preflight, /command\(input, output, "PONG", "PING"\)/);
console.log('Redis fixture probes: fork-free readiness and authenticated functional admission preserved');
