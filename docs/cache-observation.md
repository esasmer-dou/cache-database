# Cache Observations And Tracing

CacheDB's dependency-free observation SPI can record one logical repository call across synchronous,
async and projected views. The optional Micrometer adapter writes real ObservationRegistry events.
Native tracing requires the application's tracing handler and exporter; context metadata alone is
not a native span link.

## Native Runtime Configuration

Configure observations before publishing the runtime:

~~~java
var options = CacheRuntimeOptions.builder().observationRecorder(recorder).build();
var database = new CacheDatabase(redis, dataSource, config, options);
var repository = database.repository(metadata, codec);
~~~

Redis repositories remain concrete RedisEntityRepository instances. Their maintenance, external
hydration and explicitly declared lexical-window capabilities are not replaced by a generic proxy.
Fetch-plan views share the observation owner. Public data calls, including count, receipt/bulk APIs
and lexical windows, create one boundary; same-owner internal calls reuse it. Disabled repositories
branch before allocating a lambda or capturing an observation context.

CacheAsyncContext carries the operation owner through the existing executor and restores the
worker's previous context on success or failure. Async views retain the delegate's execution and
original future semantics; observations add no scheduler, transport enqueue or retry policy.
The no-argument async view uses the runtime-owned bounded executor. Redis point/bulk eligibility,
physical-completion accounting and cancellation remain the native delegate's decisions; observation
wrapping does not replace direct enqueue with an offloader.

The native JDBC flusher observes the real transaction boundary, including validation, durable
write-fence filtering, DML, commit/rollback and connection-state restoration. Connection acquisition
and write-fence initialization precede that boundary. One scope covers one transaction attempt,
not each row. SQL retries remain the storage provider's responsibility.

## Explicit Decoration For Custom Repositories

~~~java
var recorder = new MicrometerCacheObservationRecorder(
        registry, new CacheObservationTagPolicy(Set.of("Order"), Set.of("latest_orders")));
var observed = ObservingEntityRepository.wrap(repository, "Order", recorder);
var result = observed.findById(orderId);
~~~

The decorator delegates public overloads, versioned/receipt APIs, capabilities and fetch/projection
views without replacing the delegate's execution strategy. Async calls return the original delegate
CompletableFuture, preserving cancellation and native execution. Use one observation boundary:
do not decorate both an async delegate and the synchronous repository it invokes.
Creating views, inspecting capabilities, watcher registration and aggregateFieldValue do not create
per-row observations. The decorator adds no executor, offloader, retry, Redis/JDBC request or result copy.

NoOpCacheObservationRecorder.INSTANCE returns singleton scopes/bindings. When disabled,
ObservingEntityRepository.wrap returns the exact undecorated instance. No wrapper, lambda, tag,
observation or future is created on that repository path. This identity guarantee is not a measured
latency claim. Custom implementations should bypass optional context work when instrumentation is off.

The Spring adapter is conditional on the optional ObservationRegistry class and a registry bean;
cachedb.observation.enabled=false disables its auto-configuration. A custom recorder bean takes
precedence. Its runtime-options customizer installs the recorder into native repositories and JDBC
flushers, not a repository proxy. Configure handlers before creating the runtime; enabled state is
selected at construction. Custom factories returning a wrapped flusher must configure their native
JDBC flusher before wrapping it. Do not additionally decorate a native observed Redis repository.
No Micrometer or OTel dependency is added to core/storage.

## Tags, Outcomes And Privacy

Only low-cardinality keys are supplied: db.system, cachedb.entity, cachedb.operation, cachedb.tier,
cachedb.outcome and optional cachedb.route. No IDs, tenant IDs, payloads or URLs belong in tags.
CacheObservationTagPolicy uses fixed startup allowlists: at most 1024 schema entity names and 256
declared routes, at most 128 identifier characters each. Unknown entities become "unknown"; unknown
routes are omitted. No request-driven registration or ID hashing is performed.
db.system is redis/postgresql/mssql/oracle/other; the default transport is redis.

Tier is the deepest ACTUALLY reported L1/REDIS/SOURCE tier; unknown tier is omitted. Duration covers
the whole call, not exclusive time in that storage tier. Explicit tombstone outcomes survive generic
result classification; errors remain sticky. "hit" is not a SQL durability/commit guarantee.
Error exports use a constant exception without the original message, cause or stack. Original
business exceptions still propagate unchanged to callers. Application handlers/filters must not
reintroduce sensitive exception details. instrumentationFailures() reports adapter failures.

Useful dashboard dimensions are operation, entity, tier and outcome. Compare whole-call duration
distributions for L1/REDIS/SOURCE labels; do not add those durations as exclusive segment timings.
Alert on error-rate and instrumentation-failure changes. Exporters must be bounded/nonblocking;
avoid per-ID series and unbounded asynchronous queues.

## Async Scope Ownership

A Scope is an operation lifetime; Binding is thread-owned and lexical. Close each binding on the
thread that opened it. Close the operation on completion, error or cancellation. Closing is
idempotent and late signals are ignored. Async callbacks preserve the original future identity;
a dependent observation-completion callback need not finish before another waiter sees that future.

Explicit propagation is available for custom workers:

~~~java
var snapshot = CacheObservationContext.capture();
executor.execute(() -> {
    try (var binding = snapshot.restore()) {
        work();
    }
});
~~~

A captured empty core context masks a worker's ambient core observation. Bindings restore the
previous context even on errors; cross-thread or out-of-order closure is rejected. Nested context
depth is bounded to 64. This SPI does not replace application tenant/causal/route propagation or
native SDK propagation. Capture per request, not once for every waiter in a coalesced batch.

## W3C Metadata And Flush Links

CacheTraceparent accepts a bounded W3C version-00 subset: exactly 55 lowercase-hex characters,
nonzero trace/span IDs, and a protocol upper bound of 128. Unknown versions are rejected, not cut.
withTraceparent(existing, header) preserves all other traceMetadata entries and the codec format.
The existing eight-entry/512-character metadata limits remain. The explicit withTraceparent helper
throws on overflow. Optional runtime enrichment instead omits the new header, increments
invalidHeaders(), and preserves every existing field when there is no capacity.

ObservingTraceContextPropagator(delegate, actualNativeHeaderSupplier) can enrich the configured
propagator using an actual active W3C header. It preserves other entries, rejects invalid headers,
counts invalidHeaders() and never invents IDs or sampling flags. Configure an explicit typed
CacheTraceparentSupplier through CacheRuntimeOptions.observationTraceparentSupplier or as a Spring
bean. The isolated FlushObservationConfiguration supplies an actual current native OTel header,
including its original flag bits. No MDC-derived sampled flag is manufactured.
In authoritative native mode, a missing/failed supplier does not accept a newly MDC-derived header;
explicit existing TraceContexts metadata and all other fields are preserved.

A native writer boundary captures the supplied header before constructing WriteOperation, including
when correlation metadata is already present. Async writers capture it before executor submission.
The base configuration's trace propagator and public record shape remain unchanged. Without both
an enabled recorder and an explicit supplier, no enrichment wrapper is installed.

Automatic JDBC origin collection is disabled unless that explicit supplier is configured. This also
prevents legacy MDC-derived headers from silently becoming native links. Custom factories can
explicitly enable validated-origin consumption with withObservationOriginLinksEnabled(true);
they are responsible for ensuring their producers stored real native headers. The native flusher
examines at most 10000 operation origins and sets at most 32 unique links before first attachment.

CacheTraceparent.originLinks examines at most 10000 headers, ignores invalid legacy values,
deduplicates trace/span pairs and returns at most 32 immutable links. Supply them before the
first binding, so handlers see them during onStart:

~~~java
var scope = CacheObservations.start(recorder, CacheOperation.FLUSH_BATCH, "Order");
try {
    scope.originLinks(CacheTraceparent.originLinks(batchTraceparents));
    scope.databaseSystem("postgresql");
    try (var binding = CacheObservationContext.attach(scope)) {
        scope.tier(Tier.SOURCE);
        flushBatch();
    }
} catch (RuntimeException | Error failure) {
    scope.error(failure);
    throw failure;
} finally {
    scope.close();
}
~~~

CacheMicrometerObservationContext.operation()/originLinks() are handler inputs.
cachedb.origin.links is context data, not a metric tag. Clearing a Micrometer observation's parent
does not by itself prevent inheritance of an ambient native SDK parent.
DefaultTracingObservationHandler does not automatically consume this custom list.

## Runnable Consumer OTel Bridge

The isolated example in tools/benchmarks/observation-otel owns its tracing/SDK dependencies.
It changes no existing CacheDB module dependency and is not part of the default reactor.
LinkedFlushObservationHandler extends the default Micrometer tracing handler: ordinary observations
retain default behavior; FLUSH_BATCH uses setNoParent and addLink before starting ONE native span
through the real OtelTracer. The handler uses public Micrometer/OTel APIs, preserves all W3C flag
bits for links, and never restores an origin as a parent.

Register it INSTEAD OF an independent default tracing handler. For a manually configured registry:

~~~java
registry.observationConfig()
        .observationHandler(new LinkedFlushObservationHandler(otelTracer));
~~~

For Spring Boot 3.3, expose the replacement as a DefaultTracingObservationHandler subtype bean;
the default handler's missing-bean condition then backs off:

~~~java
@Bean
@Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
LinkedFlushObservationHandler defaultTracingObservationHandler(io.micrometer.tracing.Tracer tracer) {
    if (!(tracer instanceof io.micrometer.tracing.otel.bridge.OtelTracer otel)) {
        throw new IllegalStateException("The flush-link example requires the OTel backend");
    }
    return new LinkedFlushObservationHandler(otel);
}
~~~

Use application-owned micrometer-tracing-bridge-otel and an OTel SDK/exporter compatible with your
dependency platform. The isolated example pins Micrometer Tracing 1.3.6 and OTel 1.38.0.
Do not register another independent native span creator for the same observation. Metrics handlers
can coexist. A tracing sampler may drop the flush span; links do not force export or sampling.
No tracestate is reconstructed because the stored contract contains traceparent only.

The isolated acceptance test uses a real SdkTracerProvider, SimpleSpanProcessor and
InMemorySpanExporter. It asserts actual SpanData links/flags, an invalid parent ID, one flush span,
SQL-child parenting, ordinary handler fallback and async scope restoration/error/cancellation.
The native writer/header tests also verify unsampled flags and retained correlation fields.
The optional NativeWriterFlushAcceptanceTest uses the real JDBC transaction hook, real PostgreSQL
DML and real SDK SpanData links; enable it with cachedb.observation.postgres=true and supply
cachedb.observation.jdbc.url plus optional user/password system properties. It creates and removes
a unique test schema, so use a dedicated test database. This does not certify a deployed exporter
or every application-owned flusher factory.

With the corresponding CacheDB artifacts available in the Maven repository:

~~~shell
mvn -f tools/benchmarks/observation-otel/pom.xml -Dcachedb.version=0.19.0 test
~~~

Keep exporter queues, retries and timeouts bounded in the application. A single batch observation
covers the selected flush attempt; a successful observation is not proof of a committed transaction.
Native trace continuity also requires the application's write and flush boundaries to pass the
validated metadata; merely registering this handler does not add storage instrumentation.

API references: [Micrometer links](https://github.com/micrometer-metrics/tracing/blob/v1.3.6/micrometer-tracing/src/main/java/io/micrometer/tracing/Span.java),
[OTel bridge](https://github.com/micrometer-metrics/tracing/blob/v1.3.6/micrometer-tracing-bridges/micrometer-tracing-bridge-otel/src/main/java/io/micrometer/tracing/otel/bridge/OtelSpanBuilder.java),
[OTel span builder](https://github.com/open-telemetry/opentelemetry-java/blob/v1.38.0/api/all/src/main/java/io/opentelemetry/api/trace/SpanBuilder.java).
