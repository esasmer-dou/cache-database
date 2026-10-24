import { performance } from 'node:perf_hooks';
import { writeFile } from 'node:fs/promises';

const [baseUrl, output, requestCount = '1000', parallelism = '16'] = process.argv.slice(2);
const target = new URL(baseUrl);
if (!/^sample-api\.cachedb-[a-z0-9-]+\.svc\.cluster\.local$/.test(target.hostname)) {
  throw new Error('Only the dedicated Kubernetes sample service is allowed');
}
const count = Number(requestCount);
const concurrency = Number(parallelism);
if (!Number.isInteger(count) || count < 1 || count > 10000
    || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > 64) {
  throw new Error('Expected 1..10000 requests and 1..64 concurrent requests');
}
const durations = new Float64Array(count);
let next = 0;
let failures = 0;
const failureSamples = [];
const started = performance.now();
await Promise.all(Array.from({ length: concurrency }, async () => {
  for (;;) {
    const index = next++;
    if (index >= count) return;
    const at = performance.now();
    try {
      const response = await fetch(`${baseUrl}/api/catalog/items/9100001`, {
        signal: AbortSignal.timeout(5000),
      });
      const body = await response.json();
      if (response.status !== 200 || body.status !== 'FOUND'
          || body.values?.[0]?.productId !== 9100001) {
        failures++;
        if (failureSamples.length < 10) failureSamples.push({
          index, httpStatus: response.status, snapshotStatus: body.status,
          productId: body.values?.[0]?.productId,
        });
      }
    } catch (error) {
      failures++;
      if (failureSamples.length < 10) failureSamples.push({ index, error: error.name });
    }
    durations[index] = performance.now() - at;
  }
}));
const elapsedMillis = performance.now() - started;
durations.sort();
const percentile = (p) => durations[Math.max(0, Math.ceil(count * p) - 1)];
const result = {
  scope: 'Bounded HTTP smoke load, not a capacity certification or the framework 50 ms gate',
  target: target.origin, count, concurrency, failures, failureSamples, elapsedMillis,
  requestsPerSecond: count * 1000 / elapsedMillis,
  p50Millis: percentile(0.5), p95Millis: percentile(0.95), p99Millis: percentile(0.99),
  maximumMillis: durations[count - 1],
};
await writeFile(output, JSON.stringify(result, null, 2));
console.log(JSON.stringify(result));
if (failures) process.exitCode = 1;
