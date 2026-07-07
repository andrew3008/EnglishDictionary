# 02 — Contracts and Migration Boundaries

> Evidence-first. All citations are to actual repository files read on 2026-06-30.
> "Evidence missing" marks items with no repository support found.

---

## Surface Contract Table

| # | Surface | Current consumer | Contract type | Can break in pre-prod? | Required migration/approval | Tests/docs |
|---|---------|-----------------|---------------|------------------------|-----------------------------|------------|
| 1 | `builder(SpanExporter)` static factory | `PlatformExportProcessorFactory.java:96` | Java API, internal platform | YES (plan §1) | Must update factory in same PR (PR-4); no other production caller found | `PlatformDropOldestExportSpanProcessorBuilderValidationTest.java`, `PlatformAutoConfigurationCustomizerExportProcessorTest.java` |
| 2 | `Builder.readBspConfigFrom(ConfigProperties)` | `PlatformExportProcessorFactory.java:98` | Java API + config key contract | YES | Replace with `DropOldestExportProcessorConfig.readBspConfig()` or equivalent; must preserve all four otel.bsp.* key reads | `SharedDefaultsAlignmentTest.java` (asserts BSP key alignment), `PlatformDropOldestExportSpanProcessorBuilderValidationTest.java` |
| 3 | Six processor getter methods (see below) | `PlatformExportControl.java:46–78` | Java API bridging to JMX | YES (pre-prod) | `PlatformExportControl` must be updated to read from `MetricsSnapshot` instead; both changes must be in same PR (PR-4) | No getter-signature-specific tests found; behavior exercised indirectly in `LifecycleTest`, `OverflowPolicyTest` |
| 4 | JMX MBean attribute names (from `PlatformExportControlMBean`) | JMX clients (operators), monitoring tools | JMX wire contract | **UNCLEAR** — no formal ADR for these specific attributes found | If attribute names change: requires operator notification and migration docs; must be documented as new operator contract | `DomainMBeanJmxComplianceTest.java`, `PlatformTracingJmxRegistrarTest.java` |
| 5 | `PlatformExportProcessorFactory.maybeReplaceExportProcessor()` wiring | `PlatformAutoConfigurationCustomizer.java` | Internal SPI wiring | YES | Factory wiring must be updated in PR-4 with all acceptance tests passing | `PlatformAutoConfigurationCustomizerExportProcessorTest.java` (5 scenarios) |
| 6 | Config keys (`otel.bsp.*`) | `readBspConfigFrom()` via `ConfigProperties` | OTel BSP key parity contract | NO — must keep same keys | Keys must remain `otel.bsp.max.queue.size`, `otel.bsp.max.export.batch.size`, `otel.bsp.schedule.delay`, `otel.bsp.export.timeout` | `SharedDefaultsAlignmentTest.java` |
| 7 | Opt-in policy (`platform.tracing.queue.overflow-policy` / `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY`) | `ExtensionConfigReader` → `QueueExtensionConfig` → `PlatformExportProcessorFactory` | Runtime config contract | NO — must keep property name | Property name and semantics must be preserved; only valid values: `UPSTREAM`, `DROP_OLDEST` | `PlatformAutoConfigurationCustomizerExportProcessorTest.java` |
| 8 | Multi-exporter fallback | `PlatformExportProcessorFactory.java:61–68` | Factory behavior contract | YES — mechanism can change if semantics preserved | Must preserve: >1 exporter captured → stock BSP returned + WARN; test must assert `BatchSpanProcessor` type | `PlatformAutoConfigurationCustomizerExportProcessorTest.dropOldestFallsBackToStockOnMultiExporter()` — **WEAK assertion** (line ~148: non-null only) |
| 9 | Dropped span counters (`droppedSpansOverflow`, `droppedSpansAfterShutdown`) | JMX MBean, `dropped-span-reasons.md` taxonomy | Observability + SRE taxonomy | Semantics NO; field names YES (pre-prod) | Counter semantics must be preserved: overflow = queue eviction, after-shutdown = onEnd after shutdown flag; may rename fields in snapshot | `PlatformDropOldestExportSpanProcessorOverflowPolicyTest.java`, `PlatformDropOldestExportSpanProcessorLifecycleTest.java` |
| 10 | Logging policy (`logExportFailureOnce`) | SRE monitoring, log aggregation | Observable logging behavior | If changed: must document | If policy changes from one-shot to rate-limited: must appear in PR-3/4 acceptance criteria and be communicated to SRE | No test found for log throttling behavior (Evidence missing) |

---

## Surface Detail: Six Processor Getter Methods

Important finding: **the processor getter method names differ from the JMX MBean attribute method names**.

| Processor getter (Java API) | MBean method (JMX wire) | MBean value expression |
|-----------------------------|------------------------|------------------------|
| `getDroppedSpansOverflow()` | `getExportDroppedOverflowTotal()` | `proc.getDroppedSpansOverflow()` |
| `getDroppedSpansAfterShutdown()` | `getExportDroppedAfterShutdownTotal()` | `proc.getDroppedSpansAfterShutdown()` |
| `getExportFailures()` | `getExportFailuresTotal()` | `proc.getExportFailures()` |
| `getExportTimeouts()` | `getExportTimeoutsTotal()` | `proc.getExportTimeouts()` |
| `getQueueCapacity()` | `getExportQueueCapacity()` | `proc.getQueueCapacity()` |
| `getQueueSize()` | `getExportQueueSize()` | `proc.getQueueSize()` |

Source: `PlatformExportControl.java:45–78`, `PlatformExportControlMBean.java`.

**Breaking the processor getter methods** (Java API) is a pre-production decision. The fix requires updating `PlatformExportControl.java` to read from `MetricsSnapshot` instead of calling processor getter methods directly.

**Breaking the JMX MBean attribute names** (wire contract) affects JMX clients. The `ADR-jmx-wire-map-contract.md` does NOT cover these attributes — that ADR is about a Map-based wire protocol for cross-classloader control (sampler/scrubbing), not export processor observability. There is no formal ADR for the six export-counter JMX MBean attributes.

---

## Surface Detail: Factory Wiring (Surface #5)

`PlatformExportProcessorFactory.maybeReplaceExportProcessor()` does:
1. `isExplicitUpstream(queueConfig)` — if UPSTREAM, passthrough
2. `exporterCount.get() > 1` — if multi-exporter, WARN + passthrough
3. `capturedExporter.get() == null` — WARN + passthrough
4. `!(processor instanceof BatchSpanProcessor)` — WARN + passthrough
5. `processor.shutdown().join(5, TimeUnit.SECONDS)` — closes stock BSP
6. `PlatformDropOldestExportSpanProcessor.builder(exporter).readBspConfigFrom(config).build()`
7. `jmxRegistrar.setExportProcessor(replacement)`
8. `replacement.getQueueCapacity()` (line 109) — reads getter immediately after construction

Line 109 (`replacement.getQueueCapacity()`) means factory calls a processor getter immediately — this getter must still return a valid value from the new config-first design.

---

## Surface Detail: Opt-in and Rollback (Surface #7)

**Current default**: `ExtensionDefaults.java:48` — `DEFAULT_QUEUE_OVERFLOW_POLICY = "DROP_OLDEST"`.

**ADR-v1 claim** (docs/decisions/ADR-drop-oldest-export-processor-v1.md): "v1.x default = Stock BSP (upstream)" — **OUTDATED**.

**Test confirms current behavior**: `PlatformAutoConfigurationCustomizerExportProcessorTest.defaultIsPlatformDropOldestWhenOverflowPolicyNotSet()` — unset property → DROP_OLDEST processor activated.

**Rollback**: NOT "unset env-var." Correct rollback = set `platform.tracing.queue.overflow-policy=UPSTREAM` (or env `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`).

---

## Surface Detail: Multi-Exporter Fallback (Surface #8)

`PlatformExportProcessorFactory.java:61`: `if (exporterCount.get() > 1)` — counter incremented by `captureExporter()`.

`PlatformAutoConfigurationCustomizerExportProcessorTest.dropOldestFallsBackToStockOnMultiExporter()`: test comment (line ~141–144): "ВНИМАНИЕ: ограничение теста: для проверки сценария multi-exporter нужно зарегистрировать ДВА exporter'а ПЕРЕД платформенным customizer'ом" — the test does NOT fully assert multi-exporter fallback returns `BatchSpanProcessor`; assertion at line ~148 is `isNotInstanceOf(PlatformDropOldestExportSpanProcessor.class)` without asserting it IS a `BatchSpanProcessor`.

`dropOldestWithTwoExportersBeforePlatformFallsBack()` test (lines ~158–193): assertion is only `assertThat(finalProcessor).isNotNull()` — this is the weakest possible assertion.

**Required PR-4 test gate**: explicit multi-exporter scenario with two exporters registered BEFORE platform customizer, asserting `instanceOf(BatchSpanProcessor.class)`.

---

## Required Migration Steps Summary

**If config-first builder replaces old Builder (PR-4):**
1. Create `DropOldestExportProcessorConfig` with all five current builder parameters
2. New builder must read same four `otel.bsp.*` keys from `ConfigProperties`
3. Preserve WARN+fallback validation policy; null exporter = fail-fast NPE
4. Update `PlatformExportProcessorFactory.java:96–99` to use new builder API
5. Update `PlatformExportProcessorFactory.java:109` (`getQueueCapacity()` call) to read from config or snapshot
6. `PlatformExportControl` must be updated to read from `MetricsSnapshot` (not processor getters)
7. All 5 `PlatformAutoConfigurationCustomizerExportProcessorTest` tests must pass green
8. `SharedDefaultsAlignmentTest` must pass green (BSP key parity)
9. `BspDropOldestNoDoubleExportTest` must pass green (no double export)
10. Explicit multi-exporter fallback test must assert `BatchSpanProcessor` return type
