import { performance } from 'node:perf_hooks';
import { writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

export function summarizeReadLoad(durations, succeeded, elapsedMillis) {
  if (durations.length !== succeeded.length || !Number.isFinite(elapsedMillis) || elapsedMillis <= 0) {
    throw new Error('Measurement population and positive elapsed time are required');
  }
  const all = [];
  const success = [];
  const failed = [];
  for (let i = 0; i < durations.length; i++) {
    if (!Number.isFinite(durations[i]) || durations[i] < 0) throw new Error('Invalid latency');
    all.push(durations[i]);
    (succeeded[i] ? success : failed).push(durations[i]);
  }
  const population = (values) => {
    values.sort((a, b) => a - b);
    const percentile = (p) => values.length ? values[Math.ceil(values.length * p) - 1] : null;
    return { samples: values.length, p50Millis: percentile(0.5), p95Millis: percentile(0.95),
      p99Millis: percentile(0.99), maximumMillis: values.length ? values.at(-1) : null };
  };
  const allLatency = population(all);
  return {
    // The old field remains attempted throughput, not successful throughput.
    requestsPerSecond: durations.length * 1000 / elapsedMillis,
    attemptedRequestsPerSecond: durations.length * 1000 / elapsedMillis,
    successfulRequestsPerSecond: success.length * 1000 / elapsedMillis,
    successfulRequests: success.length, failures: failed.length,
    p50Millis: allLatency.p50Millis, p95Millis: allLatency.p95Millis,
    p99Millis: allLatency.p99Millis, maximumMillis: allLatency.maximumMillis,
    allLatency, successfulLatency: population(success), failedLatency: population(failed),
  };
}

export async function runReadLoad(baseUrl, count, concurrency, request = fetch) {
  const target = new URL(baseUrl);
  if (!/^sample-api\.cachedb-[a-z0-9-]+\.svc\.cluster\.local$/.test(target.hostname)
      || !['http:', 'https:'].includes(target.protocol) || target.username || target.password
      || target.pathname !== '/' || target.search || target.hash) {
    throw new Error('Only the dedicated Kubernetes sample service origin is allowed');
  }
  if (!Number.isInteger(count) || count < 1 || count > 10000
      || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > 64) {
    throw new Error('Expected 1..10000 requests and 1..64 concurrent requests');
  }
  const durations = new Float64Array(count);
  const succeeded = new Uint8Array(count);
  let next = 0;
  const failureSamples = [];
  const started = performance.now();
  await Promise.all(Array.from({ length: Math.min(concurrency, count) }, async () => {
    for (;;) {
      const index = next++;
      if (index >= count) return;
      const at = performance.now();
      try {
        const response = await request(`${target.origin}/api/catalog/items/9100001`, {
          signal: AbortSignal.timeout(5000), redirect: 'error',
        });
        const body = await response.json();
        if (response.status === 200 && body.status === 'FOUND'
            && body.values?.[0]?.productId === 9100001) {
          succeeded[index] = 1;
        } else if (failureSamples.length < 10) {
          failureSamples.push({ index, httpStatus: response.status, snapshotStatus: body.status,
            productId: body.values?.[0]?.productId });
        }
      } catch (error) {
        if (failureSamples.length < 10) failureSamples.push({ index, error: error.name });
      }
      durations[index] = performance.now() - at;
    }
  }));
  const elapsedMillis = performance.now() - started;
  return {
    measurementVersion: 2,
    scope: 'Bounded closed-loop HTTP smoke, not capacity certification or the framework 50 ms gate',
    latencyScope: 'Client fetch + response JSON decoding + result validation; all-attempt legacy percentiles',
    rateScope: 'Wall-clock attempts and validated successes; requestsPerSecond is the legacy attempt alias',
    target: target.origin, count, concurrency, failureSamples, elapsedMillis,
    ...summarizeReadLoad(durations, succeeded, elapsedMillis),
    rawSamples: Array.from(durations, (durationMillis, index) => ({ index, durationMillis,
      success: succeeded[index] === 1 })),
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [baseUrl, output, requestCount = '1000', parallelism = '16'] = process.argv.slice(2);
  const result = await runReadLoad(baseUrl, Number(requestCount), Number(parallelism));
  await writeFile(output, JSON.stringify(result, null, 2));
  const { rawSamples, ...summary } = result;
  console.log(JSON.stringify(summary));
  if (result.failures) process.exitCode = 1;
}
