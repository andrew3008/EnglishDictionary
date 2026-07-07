# 04 — Builder, Config, Factory Wiring Evidence

> Evidence-first. All citations are to actual repository files read on 2026-06-30.
> "Evidence missing" marks claims without repository support.

---

## Who Calls `PlatformDropOldestExportSpanProcessor.builder(...)`

**Sole production caller:** `PlatformExportProcessorFactory.java:96–99`

```java
PlatformDropOldestExportSpanProcessor replacement = PlatformDropOldestExportSpanProcessor
        .builder(exporter)           // line 96
        .readBspConfigFrom(config)   // line 98
        .build();                    // line 99
```

No other production file calls `builder(...)`. Confirmed by reading all files in the `factory/`, `processor/`, `configuration/`, `jmx/` directories.

---

## Who Calls `Builder.readBspConfigFrom(...)`

**Sole production caller:** `PlatformExportProcessorFactory.java:98` (same call chain as above).

No direct test calls `readBspConfigFrom()` independently. It is exercised indirectly via `PlatformAutoConfigurationCustomizerExportProcessorTest` (which exercises the full autoconfigure path including factory) and `SharedDefaultsAlignmentTest` (which verifies BSP key default alignment).

---

## Config Keys and Defaults

Source: `PlatformDropOldestExportSpanProcessor.java` `Builder.readBspConfigFrom()` (lines 467–491), `DropOldestExportProcessorDefaults.java`, `OtelSdkDefaults.java`, `ExtensionDefaults.java`.

| Config key | Builder field | Default source | Default value | Read from BSP config? |
|-----------|--------------|----------------|---------------|----------------------|
| `otel.bsp.max.queue.size` | `maxQueueSize` | `OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE` | `2048` | YES (line 470) |
| `otel.bsp.max.export.batch.size` | `maxExportBatchSize` | `OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE` | `512` | YES (line 475) |
| `otel.bsp.schedule.delay` | `scheduleDelay` | `OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY` | `Duration.ofMillis(5000)` | YES (line 480) |
| `otel.bsp.export.timeout` | `exportTimeout` | `OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT` | `Duration.ofMillis(5000)` | YES (line 485) |
| *(none — not from BSP config)* | `shutdownTimeout` | `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` | `Duration.ofSeconds(10)` | NO |

**`shutdownTimeout` is NOT configurable via any OTel property.** `01-current-behavior.md`: "shutdownTimeout is NOT read from BSP config. It uses `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT = 10s`."

---

## Validation Policy: WARN + Fallback vs Fail-Fast

Source: `Builder.applyValidationWithSafeFallback()` lines 498–534.

| Condition | Policy | Fallback value |
|-----------|--------|---------------|
| `maxQueueSize <= 0` | WARN + fallback | `DropOldestExportProcessorDefaults.defaultMaxQueueSize()` = 2048 |
| `maxExportBatchSize <= 0` | WARN + fallback | `DropOldestExportProcessorDefaults.defaultMaxExportBatchSize()` = 512 |
| `maxExportBatchSize > maxQueueSize` | WARN + clamp | `maxQueueSize` |
| `scheduleDelay == null \|\| <= 0` | WARN + fallback | `DropOldestExportProcessorDefaults.defaultScheduleDelay()` = 5s |
| `exportTimeout == null \|\| <= 0` | WARN + fallback | `DropOldestExportProcessorDefaults.defaultExportTimeout()` = 5s |
| `shutdownTimeout == null \|\| <= 0` | WARN + fallback | `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` = 10s |
| `exporter == null` | **FAIL-FAST** (`NullPointerException`) | (no fallback — line 439) |

**Only null exporter causes fail-fast.** All other invalid values produce a WARN log and fallback to safe defaults. This is the "production-safe" policy: agent extension must not crash the JVM on config typo.

---

## Tests Asserting Builder/Factory Behavior

| Test class | What it asserts | Location |
|------------|----------------|----------|
| `PlatformDropOldestExportSpanProcessorBuilderValidationTest.java` | Null exporter → NPE; zero/negative values → fallback; batchSize > queueSize → clamp; valid small config accepted | `otel-extension/test/.../processor/` |
| `PlatformAutoConfigurationCustomizerExportProcessorTest.java` | Default → DROP_OLDEST; explicit DROP_OLDEST; explicit UPSTREAM; unknown policy → DROP_OLDEST; non-BatchSpanProcessor passthrough | `otel-extension/test/` |
| `BspDropOldestNoDoubleExportTest.java` | No double export when opt-in activated via `AutoConfiguredOpenTelemetrySdk` | `otel-extension/test/` |
| `SharedDefaultsAlignmentTest.java` | `queue.max-size`, `export-batch-size`, `export-timeout` align between Spring TracingProperties and DropOldestExportProcessorDefaults | `spring-boot-autoconfigure/test/` |
| `DropOldestExportProcessorDefaultsTest.java` | Default value assertions | `otel-extension/test/.../configuration/spi/` |

---

## Required Migration Steps if Config-First Builder Replaces Old Builder

1. **Create `DropOldestExportProcessorConfig`** (PR-1) as package-private immutable record/class containing all five parameters (maxQueueSize, maxExportBatchSize, scheduleDelay, exportTimeout, shutdownTimeout).
2. **Embed config reading in config class** — `DropOldestExportProcessorConfig.readBspConfig(ConfigProperties)` static factory must read the same four `otel.bsp.*` keys, preserving WARN+fallback validation.
3. **Preserve null-exporter fail-fast** — the new builder or config must retain NPE on null exporter as the only fail-fast case.
4. **Update `PlatformExportProcessorFactory.java:96–99`** — replace builder chain with new config-first API in PR-4.
5. **Update line 109 caller** — `replacement.getQueueCapacity()` must still return a valid int; either the new facade provides this or factory reads from config directly.
6. **Update `PlatformExportControl`** — `PlatformTracingJmxRegistrar.setExportProcessor()` API must be compatible with new facade (see file 03).
7. **All unit tests for validation must pass** — `PlatformDropOldestExportSpanProcessorBuilderValidationTest` test scenarios must be replicated for new config class.

---

## PR-4 Mandatory Test Gates

| Gate | Test | Current status |
|------|------|----------------|
| 1. Opt-in activation test | `PlatformAutoConfigurationCustomizerExportProcessorTest.explicitDropOldestReplacesBspWithPlatformProcessor()` | EXISTS (green) |
| 2. Default UPSTREAM passthrough test | `PlatformAutoConfigurationCustomizerExportProcessorTest.explicitUpstreamLeavesStockBspIntact()` | EXISTS (green) |
| 3. Multi-exporter fallback test | `PlatformAutoConfigurationCustomizerExportProcessorTest.dropOldestFallsBackToStockOnMultiExporter()` + new test asserting `BatchSpanProcessor` type | EXISTS but **weak assertion**; new test required |
| 4. No double export test | `BspDropOldestNoDoubleExportTest.everySpanExportedExactlyOnceUnderOptIn()` | EXISTS (green) |
| 5. Config defaults test | `SharedDefaultsAlignmentTest.queueDefaults_aligned_with_agentExtension()` | EXISTS (green) |
| 6. Invalid config fallback test | `PlatformDropOldestExportSpanProcessorBuilderValidationTest` (all scenarios) | EXISTS (green) |
| 7. **NEW** — multi-exporter explicit fallback | New test: two exporters registered BEFORE platform customizer → assert `BatchSpanProcessor` returned | **DOES NOT EXIST** |
| 8. **NEW** — factory compiles and processor built from config | Integration test: new builder/config produces working processor | **DOES NOT EXIST** |

---

## Rollback Gate: What Must Be Verified for "Rollback by Setting UPSTREAM" to Work After PR-4

**Corrected rollback procedure** (see `00-refresh-executive-summary.md`, Critical Factual Error):
- Rollback is NOT "unset env-var" — default is now DROP_OLDEST
- Rollback IS: set `platform.tracing.queue.overflow-policy=UPSTREAM` explicitly

**After PR-4, verify the following rollback gates before merging:**

| Gate | Verification method | Risk if not verified |
|------|--------------------|--------------------|
| `isExplicitUpstream()` still returns `true` for UPSTREAM value | `PlatformAutoConfigurationCustomizerExportProcessorTest.explicitUpstreamLeavesStockBspIntact()` must pass after PR-4 factory changes | DROP_OLDEST may activate even when operator explicitly requested UPSTREAM |
| Stock BSP is returned (not the new processor) when UPSTREAM | Same test asserts `instanceOf(BatchSpanProcessor.class)` | Operator cannot roll back to stock BSP semantics |
| Factory does not call `builder()` when UPSTREAM | Code review: `isExplicitUpstream()` → early return before any builder call | Resource leak if old processor is built and immediately abandoned |
| No JMX registration when UPSTREAM | Verify `jmxRegistrar.setExportProcessor()` is NOT called on UPSTREAM path | Stale JMX MBean may remain from previous activation |
| New processor's worker thread does not start when UPSTREAM | Confirm processor constructor is not called | Background thread leak if processor is instantiated and discarded |

---

## Factory Code: `PlatformExportProcessorFactory.java` — Additional Detail

Line 34–51: `captureExporter()` — wraps exporter in `SafeSpanExporter` if not already; counts exporters; only first exporter is captured.

Line 61: `if (exporterCount.get() > 1)` — multi-exporter guard (falls back to stock BSP).

Line 79: `if (!(processor instanceof BatchSpanProcessor))` — non-BSP guard (passthrough without replacing).

Line 88: `processor.shutdown().join(5, TimeUnit.SECONDS)` — stock BSP explicitly shut down before replacement. After PR-4, this pattern must be preserved. The new replacement processor must also be returned for the OTel SDK to use.

**No change to this method's signature is needed in PR-4** — only the `builder(...)` call chain at lines 96–99 changes.
