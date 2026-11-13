import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runReadLoad, summarizeReadLoad } from './run-read-load.mjs';

test('attempt rates never pass failures off as successful requests', () => {
  const metrics = summarizeReadLoad([10, 100, 20], [1, 0, 1], 1000);
  assert.equal(metrics.requestsPerSecond, 3);
  assert.equal(metrics.attemptedRequestsPerSecond, 3);
  assert.equal(metrics.successfulRequestsPerSecond, 2);
  assert.equal(metrics.successfulLatency.p99Millis, 20);
  assert.equal(metrics.failedLatency.p99Millis, 100);
  assert.equal(metrics.p99Millis, 100);
});

test('all errors yield zero success throughput and no success percentile', () => {
  const metrics = summarizeReadLoad([5, 6], [0, 0], 2000);
  assert.equal(metrics.successfulRequestsPerSecond, 0);
  assert.equal(metrics.successfulLatency.samples, 0);
  assert.equal(metrics.successfulLatency.p99Millis, null);
});

test('reject invalid measurement inputs', () => {
  for (const elapsed of [0, -1, Infinity, NaN])
    assert.throws(() => summarizeReadLoad([1], [1], elapsed));
  assert.throws(() => summarizeReadLoad([NaN], [1], 10));
  assert.throws(() => summarizeReadLoad([1], [], 10));
});

test('validate bodies and record failures without sorting away request identity', async () => {
  let next = 0;
  const result = await runReadLoad('http://sample-api.cachedb-measurement.svc.cluster.local', 12, 3,
    async (_url, options) => {
      assert.equal(options.redirect, 'error');
      assert.ok(options.signal instanceof AbortSignal);
      const index = next++;
      if (index % 3 === 0) throw new TypeError('connection failure');
      return { status: 200, json: async () => ({ status: 'FOUND',
        values: [{ productId: index % 3 === 1 ? 9100001 : 999 }] }) };
    });
  assert.equal(result.failures, 8);
  assert.equal(result.successfulRequests, 4);
  assert.equal(result.rawSamples.length, 12);
  assert.deepEqual(result.rawSamples.map(x => x.index), Array.from({ length: 12 }, (_, i) => i));
  assert.equal(result.rawSamples.filter(x => x.success).length, 4);
});

test('allow only the dedicated service origin and bounded request settings', async () => {
  for (const url of ['http://localhost', 'https://example.com',
    'http://sample-api.cachedb-measurement.svc.cluster.local/extra',
    'http://user:password@sample-api.cachedb-measurement.svc.cluster.local'])
    await assert.rejects(runReadLoad(url, 1, 1));
  await assert.rejects(runReadLoad('http://sample-api.cachedb-measurement.svc.cluster.local', 10001, 1));
});
