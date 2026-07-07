# Adjacent Code Map: PlatformDropOldestExportSpanProcessor

> Every entry is evidence-based. Speculative entries are labeled [SPECULATION].

---

## Production Classes

| File | Symbol | Relationship | Evidence |
|------|--------|-------------|----------|
| `platform-tracing-otel-extension/.../factory/PlatformExportProcessorFactory.java` | `PlatformExportProcessorFactory` | **Creates** the processor. Sole production factory. Captures `SafeSpanExporter`, shuts down stock BSP, builds replacement via `builder(exporter).readBspConfigFrom(config).build()`. Also calls `jmxRegistrar.setExportProcessor(replacement)`. | Lines 96–103 |
| `platform-tracing-otel-extension/.../factory/PlatformSpanProcessorFactory.java` | `PlatformSpanProcessorFactory` | Does **NOT** create the processor directly. Creates `PlatformCompositeSpanProcessor` with enrichment/scrubbing/validation/classification delegates. The export processor is added separately via OTel SPI `addSpanProcessorCustomizer`. | Lines 49–128 |
| `platform-tracing-otel-extension/.../processor/PlatformCompositeSpanProcessor.java` | `PlatformCompositeSpanProcessor` | **Wraps** enrichment delegates (not the export processor). The export processor is added as a **separate** `addSpanProcessor` call at the SDK level, not inside the composite. Composite calls `forceFlush()` and `shutdown()` on its own delegates only. | `PlatformAutoConfigurationCustomizer` wiring |
| `platform-tracing-otel-extension/.../jmx/export/PlatformExportControl.java` | `PlatformExportControl` | **Observes** the processor via `Supplier<PlatformDropOldestExportSpanProcessor>`. Exposes all six observability getters as JMX attributes. Also controls `SafeSpanExporter.setExportEnabled()`. | Lines 45–78 |
| `platform-tracing-otel-extension/.../jmx/PlatformTracingJmxRegistrar.java` | `PlatformTracingJmxRegistrar` | Receives the processor via `setExportProcessor()`. Passes it as a supplier to `PlatformExportControl`. Batch-registers all six domain MBeans (including export domain). | `setExportProcessor` call |
| `platform-tracing-otel-extension/.../exporter/SafeSpanExporter.java` | `SafeSpanExporter` | Wrapped by `PlatformExportProcessorFactory.captureExporter()`. Passed to `PlatformDropOldestExportSpanProcessor.builder()`. Provides `setExportEnabled(boolean)` kill-switch and `metricsSnapshot()`. | `PlatformExportProcessorFactory.java:36–48` |
| `platform-tracing-otel-extension/.../configuration/spi/DropOldestExportProcessorDefaults.java` | `DropOldestExportProcessorDefaults` | Provides **default values** for all four queue/export parameters. Delegates to `OtelSdkDefaults` (package-private). | All Builder defaults |
| `platform-tracing-otel-extension/.../configuration/spi/OtelSdkDefaults.java` | `OtelSdkDefaults` | Package-private constants: `DEFAULT_BSP_MAX_QUEUE_SIZE=2048`, `DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE=512`, `DEFAULT_BSP_SCHEDULE_DELAY=5000ms`, `DEFAULT_BSP_EXPORT_TIMEOUT=5000ms`. | Used by `DropOldestExportProcessorDefaults` |
| `platform-tracing-otel-extension/.../configuration/ExtensionDefaults.java` | `ExtensionDefaults` | Provides `DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10)`. Used in Builder default for `shutdownTimeout`. | Line 49 |
| `platform-tracing-otel-extension/.../configuration/enums/QueueOverflowPolicy.java` | `QueueOverflowPolicy` | Enum with `UPSTREAM` and `DROP_OLDEST` values used by `PlatformExportProcessorFactory.isExplicitUpstream()` to decide whether to activate this processor. | `PlatformExportProcessorFactory.java:117–133` |
| `platform-tracing-otel-extension/.../configuration/QueueExtensionConfig.java` | `QueueExtensionConfig` | Config record with `overflowPolicy()` string. Passed to `PlatformExportProcessorFactory.maybeReplaceExportProcessor()`. | `PlatformExportProcessorFactory.java:55` |
| `platform-tracing-spring-boot-autoconfigure/.../actuator/DropOldestAspirationDiagnostics.java` | `DropOldestAspirationDiagnostics` | Spring-side startup diagnostic. Does NOT interact with the processor directly — reads System properties / env vars to detect policy alignment. Emits WARN if Spring `queue.policy=DROP_OLDEST` but Agent is `UPSTREAM`. | ADR-drop-oldest-export-processor-v1.md §Diagnostics |
| `platform-tracing-spring-boot-autoconfigure/.../actuator/OtelEnvHintsBuilder.java` | `OtelEnvHintsBuilder` | Reads `DROP_OLDEST` default for operator guidance. References `DropOldestExportProcessorDefaults` for queue hints. | Evidence: referenced in search results |

---

## Configuration Properties

| Property | Channel | Values | Default | Effect |
|----------|---------|--------|---------|--------|
| `platform.tracing.queue.overflow-policy` | OTel ConfigProperties (agent) | `UPSTREAM`, `DROP_OLDEST` | `UPSTREAM` | Activates/deactivates the processor |
| `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` | Env-var | same | unset | Same as above via env fallback |
| `otel.bsp.max.queue.size` | OTel ConfigProperties | int | 2048 | Queue capacity |
| `otel.bsp.max.export.batch.size` | OTel ConfigProperties | int | 512 | Max batch per export cycle |
| `otel.bsp.schedule.delay` | OTel ConfigProperties | Duration (ms) | 5000ms | Time between scheduled exports |
| `otel.bsp.export.timeout` | OTel ConfigProperties | Duration (ms) | 5000ms | Max time waiting for `exporter.export()` |
| `platform.tracing.diagnostics.drop-oldest-aspiration-warn` | Spring | boolean | `true` | Controls `DropOldestAspirationDiagnostics` WARN |
| `platform.tracing.diagnostics.drop-oldest-aspiration-info` | Spring | boolean | `true` | Controls `DropOldestAspirationDiagnostics` INFO |

**Note:** `shutdownTimeout` (10s) is not configurable via any property. It comes from `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` and is not read from `otel.bsp.*` keys.

---

## Tests That Cover This Processor

### Direct unit/integration tests

| Test File | Location | Coverage |
|-----------|----------|----------|
| `PlatformDropOldestExportSpanProcessorLifecycleTest.java` | `otel-extension/test/.../processor/` | forceFlush (success, drain), shutdown idempotency, droppedAfterShutdown counter, exportTimeout counter, exporter exception isolation, shutdown drains queue |
| `PlatformDropOldestExportSpanProcessorOverflowPolicyTest.java` | `otel-extension/test/.../processor/` | Drop-oldest contract (tail present, head absent), queue size getter, counter balance (`dropped + exported == TOTAL`), last span preserved |
| `PlatformDropOldestExportSpanProcessorQueueCharacterizationTest.java` | `otel-extension/test/.../processor/` | SP-05 / 5 tests: identity proof, non-blocking `onEnd`, counter exact increment, post-pressure export, shutdown after overflow |
| `PlatformDropOldestExportSpanProcessorBuilderValidationTest.java` | `otel-extension/test/.../processor/` | Null exporter NPE, zero queueSize fallback, batchSize > queueSize clamp, negative durations fallback, small valid config accepted |
| `DropOldestExportProcessorDefaultsTest.java` | `otel-extension/test/.../configuration/spi/` | Default values alignment |

### Integration / SDK-level tests

| Test File | Location | Coverage |
|-----------|----------|----------|
| `BspDropOldestNoDoubleExportTest.java` | `otel-extension/test/` | No double-export when opt-in activated via `AutoConfiguredOpenTelemetrySdk` |
| `PlatformAutoConfigurationCustomizerExportProcessorTest.java` | `otel-extension/test/` | Default-off / explicit-on / multi-exporter fallback via autoconfigure customizer |
| `SharedDefaultsAlignmentTest.java` | `spring-boot-autoconfigure/test/` | BSP queue defaults alignment between Spring properties and extension defaults |
| `DropOldestAspirationDiagnosticsTest.java` | `spring-boot-autoconfigure/test/` | Aspiration WARN matrix |

### E2E / smoke tests

| Test File | Location | Coverage |
|-----------|----------|----------|
| `BspDropOldestSafetyAgentSmokeTest.java` | `platform-tracing-e2e-tests/` | Agent opt-in smoke: safety markers, INFO log at startup, OTLP unreachable scenario |

### Performance benchmarks

| File | Location | Coverage |
|------|----------|----------|
| `QueueOfferBenchmark.java` | `platform-tracing-bench/` | JMH: `onEnd` throughput steady + saturated vs stock BSP, `@Threads(4)` contention |
| `CompositePipelineBenchmark.java` | `platform-tracing-bench/` | JMH: composite pipeline cost (does not include export processor, but validates composite overhead) |

---

## Documentation and ADR References

| Document | Path | Relevance |
|----------|------|-----------|
| `ADR-drop-oldest-export-processor-v1.md` | `docs/decisions/` | **Primary ADR**: design rationale, SPI facts, queue/worker/forceFlush/shutdown contract, counter definitions, config, builder validation, test strategy, rollback |
| `ADR-bsp-overflow-policy-finding.md` | `docs/decisions/` | Finding: stock BSP is drop-new, not drop-oldest (SDK 1.61.0 + 1.62.0 probe) |
| `dropped-span-reasons.md` | `docs/tracing/` | Taxonomy mapping processor counters to span loss categories |
| `h1-composite-pipeline-jmh-baseline.md` | `docs/tracing/` | JMH performance baseline for pipeline (context for refactoring performance constraints) |
| `ADR-performance-model.md` | `docs/decisions/` | Performance model, `GAP-PRIORITY-EVICTION` gap referenced |
| `queue-overflow-policy-usage-inventory.md` | `docs/architecture/` | Inventory of `overflow-policy` usage across the codebase |

---

## Risk Column Summary

| Symbol | Relationship | Risk to Refactoring |
|--------|-------------|---------------------|
| `PlatformExportProcessorFactory` | Creates the processor | Must not change `Builder` API used here |
| `PlatformExportControl` | Reads 6 getters | All getter signatures are public API constraints |
| `PlatformTracingJmxRegistrar` | Holds reference | MBean registration contract must be preserved |
| `SafeSpanExporter` | Used as the exporter | Shutdown delegation chain must be maintained |
| `OtelSdkDefaults` | Default values | BSP key parity (SharedDefaultsAlignmentTest) must hold |
| `ExtensionDefaults` | Shutdown timeout | 10s shutdown timeout is observable (diagnosable) |
| `DropOldestAspirationDiagnostics` | Spring-side only | No coupling to processor internals; safe to ignore |
| `QueueOfferBenchmark` | Performance baseline | Must be re-run after any structural change |
