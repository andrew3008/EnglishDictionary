# SpanExporter Metrics Usage Inventory

## 1. Executive Summary

- **Расположение контракта ключей:** `platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/exporter/SpanExporterMetrics.java` — `@UtilityClass` с 9 `public static final String` константами.
- **Текущие metric keys (значения строк):** `batches`, `failures`, `exported`, `dropped`, `flush_failures`, `shutdown_failures`, `last_export_duration_nanos`, `gated`, `export_enabled`.
- **Тип метрик по фактам репозитория:** это **не** зарегистрированные OpenTelemetry metric instruments и **не** Micrometer/Prometheus meters для этих ключей. Это **internal snapshot keys** в `Map<String, Long>`, формируемые `SafeSpanExporter.metricsSnapshot()` и читаемые через JMX/actuator.
- **Главный producer:** `space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter` — поля `LongAdder` / `AtomicLong` / `AtomicBoolean`, агрегация в `metricsSnapshot()` через константы `SpanExporterMetrics`.
- **Главные consumers (production):**
  - `PlatformTracingControl.getSafeExporterMetrics()` → JMX-атрибут `SafeExporterMetrics`;
  - `SamplingControlClient.getExportMetrics()` → вложенная карта `export.safeExporter` в `GET /actuator/tracing`.
- **Главная naming problem:** короткие однословные ключи (`batches`, `failures`, `exported`, `dropped`, `gated`) без домена `span`/`export`/`transport`; пересечение с другими счётчиками export pipeline (processor-level `failures` vs SafeSpanExporter `failures`); риск путаницы `dropped` с queue overflow drops; термин `gated` неочевиден без кода; `export_enabled` — boolean state, закодированный как `1L/0L` в numeric map.
- **Финальные имена в этом документе не предлагаются** — только inventory и семантика для последующего Perplexity pass.

---

## 2. Scope

### Inspected modules

| Module | Path | Role |
|--------|------|------|
| `platform-tracing-otel-javaagent-extension` | `src/main/java`, `src/test/java` | Producer, JMX bridge, unit/integration tests |
| `platform-tracing-spring-boot-autoconfigure` | `src/main/java`, `src/test/java` | Actuator/JMX client exposure |
| `docs` | `docs/**` | ADR, runbooks, dropped-span taxonomy |

### Inspected source roots

- `platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/exporter/`
- `platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/jmx/`
- `platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/factory/`
- `platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/sampling/`
- `platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/actuator/`
- `platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/` (проверка отсутствия binder для SafeSpanExporter keys)

### Searches run

Эквиваленты запрошенных `rg` (выполнены по workspace `e:\Platform_Traces`):

```text
rg "SpanExporterMetrics" .
rg "metricsSnapshot\(" .
rg "BATCHES|FAILURES|EXPORTED|DROPPED|FLUSH_FAILURES|SHUTDOWN_FAILURES|LAST_EXPORT_DURATION_NANOS|GATED|EXPORT_ENABLED" .
rg "\"batches\"|\"failures\"|\"exported\"|\"dropped\"|\"flush_failures\"|\"shutdown_failures\"|\"last_export_duration_nanos\"|\"gated\"|\"export_enabled\"" .
rg "SafeSpanExporter" platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-otel-javaagent-extension/src/test/java docs
rg "SpanExporter|exporter|exportFailures|exportedSpans|droppedSpans|gatedSpans|exportEnabled" platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-otel-javaagent-extension/src/test/java
rg "ObservableGauge|LongCounter|Counter|Gauge|Meter|meter|metric|Metric" platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-otel-javaagent-extension/src/test/java
rg "JMX|MBean|ControlMBean|snapshot|actuator|diagnostic|diagnostics" platform-tracing-otel-javaagent-extension/src/main/java platform-tracing-spring-boot-autoconfigure/src/main/java docs
```

Дополнительно: поиск `getSafeExporterMetrics`, `SafeExporterMetrics`, `PlatformTracingSafeWrapperMetricsBinder` (для отделения SafeWrapper vs SafeExporter метрик).

### Git availability

```text
Git unavailable; used rg-based repository search.
```

(`git status --short` и `git grep` → `fatal: not a git repository`)

### Path adaptations

Пути из prompt совпадают с фактической структурой репозитория; адаптаций не потребовалось. Каталог `docs/architecture/` существует.

---

## 3. Current Metric Key Contract

Источник строк: `SpanExporterMetrics.java`. Snapshot type: `Map<String, Long>` (все значения — `long`; boolean state кодируется числом).

| Constant | Current Key | Current Type | Source Method / Field | Counter/Gauge/State? | Current Meaning From Code | Rename Risk |
|---|---|---|---|---|---|---|
| `BATCHES` | `batches` | `Long` in map | `getExportBatches()` ← `exportBatches` (`LongAdder`) | **Counter** (monotonic) | Число **инициированных** вызовов `export()` при включённом export-gate; gated path не инкрементирует | **MEDIUM** — JMX/actuator/tests/docs; короткое имя |
| `FAILURES` | `failures` | `Long` | `getExportFailures()` ← `exportFailures` | **Counter** | Число **неуспешных export batch-вызовов** (throw / null result / async `ofFailure`); **не** span count | **HIGH** — коллизия с processor `export.failures` в actuator |
| `EXPORTED` | `exported` | `Long` | `getExportedSpans()` ← `exportedSpans` | **Counter** | Число span'ов в **успешно завершённых** batch'ах (async success) | **MEDIUM** — коллизия с sampling/exported semantics в других подсистемах |
| `DROPPED` | `dropped` | `Long` | `getDroppedSpans()` ← `droppedSpans` | **Counter** | Число span'ов, учтённых как потерянные из-за **неуспешного transport export**; **не** включает gated | **HIGH** — путаница с queue overflow / processor drops |
| `FLUSH_FAILURES` | `flush_failures` | `Long` | `getFlushFailures()` ← `flushFailures` | **Counter** | Число flush-вызовов, завершившихся **исключением** делегата | **MEDIUM** — null flush result не считается failure |
| `SHUTDOWN_FAILURES` | `shutdown_failures` | `Long` | `getShutdownFailures()` ← `shutdownFailures` | **Counter** | Число shutdown-вызовов с **исключением** делегата | **MEDIUM** — null shutdown result не считается failure |
| `LAST_EXPORT_DURATION_NANOS` | `last_export_duration_nanos` | `Long` | `getLastExportDurationNanos()` ← `lastExportDurationNanos` (`AtomicLong`) | **Duration gauge / last-value state** | Длительность **последнего завершённого** export attempt (success или failure), наносекунды; initial `0` | **MEDIUM** — unit suffix present; not a counter |
| `GATED` | `gated` | `Long` | `getGatedSpans()` ← `gatedSpans` | **Counter** | Число span'ов, **намеренно отброшенных** export-gate (`exportEnabled==false`); отдельно от `dropped` | **HIGH** — термин неочевиден; docs drift (см. §8) |
| `EXPORT_ENABLED` | `export_enabled` | `Long` (`1L` / `0L`) | `isExportEnabled() ? 1L : 0L` ← `exportEnabled` (`AtomicBoolean`) | **State gauge** | Runtime kill-switch: `1` = export enabled, `0` = disabled | **MEDIUM** — boolean as numeric; дублирует JMX `isExportEnabled()` |

**`EXPORT_ENABLED` encoding:** явно `1L` при `isExportEnabled()==true`, `0L` при `false` — см. `SafeSpanExporter.metricsSnapshot():174`.

---

## 4. Producer Map

Единственный код, использующий `SpanExporterMetrics` константы: `SafeSpanExporter.metricsSnapshot()`.

| Metric Key | Producer Code | Value Source | Update Location | Semantics |
|---|---|---|---|---|
| `batches` | `snapshot.put(SpanExporterMetrics.BATCHES, getExportBatches())` | `exportBatches.increment()` | `SafeSpanExporter.export()` после gate-check, **до** `delegate.export()` | Один increment на попытку export batch при enabled gate |
| `failures` | `...FAILURES, getExportFailures()` | `exportFailures.increment()` | (1) `recordBatchFailure()` — sync throw/null; (2) `whenComplete` async failure branch | +1 per failed **batch**, not per span |
| `exported` | `...EXPORTED, getExportedSpans()` | `exportedSpans.add(count)` | `result.whenComplete()` при `result.isSuccess()` | +`spans.size()` per successful batch (async completion) |
| `dropped` | `...DROPPED, getDroppedSpans()` | `droppedSpans.add(count)` | (1) `recordBatchFailure()`; (2) async failure branch | +`spans.size()` per failed transport batch |
| `flush_failures` | `...FLUSH_FAILURES, getFlushFailures()` | `flushFailures.increment()` | `flush()` catch block only | +1 on delegate **exception**; null result → failure code but **no** counter increment |
| `shutdown_failures` | `...SHUTDOWN_FAILURES, getShutdownFailures()` | `shutdownFailures.increment()` | `shutdown()` catch block only | +1 on delegate **exception**; null result → failure code but **no** counter increment |
| `last_export_duration_nanos` | `...LAST_EXPORT_DURATION_NANOS, getLastExportDurationNanos()` | `lastExportDurationNanos.set(...)` | (1) `whenComplete` for any completed result; (2) `recordBatchFailure()` on sync failure | Wall time from `startedAt` (after `exportBatches++`) to completion |
| `gated` | `...GATED, getGatedSpans()` | `gatedSpans.add(count)` | Early return in `export()` when `!exportEnabled.get()` | +`spans.size()` when export gate blocks delegate |
| `export_enabled` | `...EXPORT_ENABLED, isExportEnabled() ? 1L : 0L` | live read of `AtomicBoolean` | Snapshot read-time; mutated via `setExportEnabled()` / JMX | Current gate state, not cumulative |

**Обёртка / регистрация producer instance:**

- `PlatformExportProcessorFactory.captureExporter()` создаёт `SafeSpanExporter`, регистрирует в `PlatformTracingJmxRegistrar.setSafeExporter()` для late-binding JMX.

---

## 5. Runtime Semantics Reconstruction

Источник: `SafeSpanExporter.java`, подтверждение — `SafeSpanExporterTest.java`.

### 5.1 batches

- **Когда инкрементируется:** после проверки `exportEnabled`, непосредственно перед вызовом `delegate.export()` (`exportBatches.increment()` на строке 55).
- **Что считает:** число **попыток** transport export (batch calls), не успешных batch'ей.
- **Gated spans:** при `exportEnabled==false` метод возвращает success **без** increment `exportBatches` (тест `exportGateDropsBatchWithoutCallingDelegate`: `getExportBatches()==0`).
- **Null input:** `null` collection нормализуется в `List.of()`; пустой batch всё равно считается одной попыткой при enabled gate.

### 5.2 failures

- **Единица учёта:** **batch / export invocation**, не span.
- **Инкремент при:**
  - `delegate.export()` бросает (`recordBatchFailure` → `exportFailures.increment()`);
  - `delegate.export()` вернул `null` (`recordBatchFailure`);
  - async `CompletableResultCode.isSuccess()==false` в `whenComplete`.
- **Не инкрементируется** при export-gate (gated path).
- **Доказательство batch vs span:** тест `countersDistinguishBatchesFromSpans` — два failed batch по 3 span → `failures=2`, `dropped=6`.

### 5.3 exported

- **Единица учёта:** **spans**.
- **Инкремент:** только в `whenComplete` при `result.isSuccess()` → `exportedSpans.add(count)`.
- **Async:** счётчик обновляется **после** завершения `CompletableResultCode`, не синхронно при return из `export()`.
- **Gated / failures:** не растёт при gate или transport failure.

### 5.4 dropped

- **Единица учёта:** **spans** потерянные из-за **transport export failure** в `SafeSpanExporter`.
- **Инкремент:** `droppedSpans.add(count)` в `recordBatchFailure` и async failure branch — вместе с `failures += 1`.
- **Gated spans:** **не** попадают в `dropped` — идут в `gated` (`gatedSpans.add(count)`).
- **Processor queue overflow:** **не** учитывается здесь — отдельные метрики `PlatformDropOldestExportSpanProcessor` / JMX `getExportDroppedOverflowTotal()`.
- **Naming collision risk:** ключ `dropped` в snapshot ≠ processor overflow drops ≠ sampling DROP reasons.

### 5.5 flush_failures

- **Инкремент:** только при `Throwable` из `delegate.flush()` (`flushFailures.increment()` в catch).
- **Null result:** возвращается `ofFailure()`, counter **не** увеличивается (нет теста на null flush — поведение только из кода).
- **Не связан** с span counts.

### 5.6 shutdown_failures

- **Инкремент:** только при `Throwable` из `delegate.shutdown()`.
- **Null result:** `ofFailure()` без increment (аналогично flush).
- **Не связан** с span counts.

### 5.7 last_export_duration_nanos

- **Что измеряет:** `System.nanoTime() - startedAt`, где `startedAt` берётся после `exportBatches.increment()`, перед `delegate.export()`.
- **Обновление:**
  - sync failure paths (`recordBatchFailure`) — сразу;
  - async paths — в `whenComplete` **до** ветвления success/failure (обновляется и при success, и при async failure).
- **Initial value:** `0` (default `AtomicLong`).
- **Не** «только last successful» — обновляется на любом **завершённом** export attempt.
- **Gated path:** не обновляется (delegate не вызывается).

### 5.8 gated

- **Семантика:** span'ы, отброшенные **export-gate** (`setExportEnabled(false)`), когда `SafeSpanExporter` возвращает `ofSuccess()` без вызова delegate (BSP не ретраит).
- **Counter:** monotonic `LongAdder` (`gatedSpans`).
- **Overlap с `dropped`:** **нет** — тест явно `getDroppedSpans()==0` при gate; gate — намеренное подавление, не transport failure.
- **Overlap с `exported`:** gated spans не считаются exported.

### 5.9 export_enabled

- **Тип:** runtime **state**, не cumulative counter.
- **Encoding:** `1L` enabled, `0L` disabled в numeric map.
- **Источник:** `AtomicBoolean exportEnabled` (default `true`).
- **Дублирование:** JMX `PlatformTracingControl.isExportEnabled()` / `setExportEnabled()` — boolean API; snapshot дублирует state numerically.
- **Интерпретация:** gauge/state metric, **не** counter.

---

## 6. Consumer Map

| Consumer | Consumed Metrics | Purpose | Production/Test/Docs | External Exposure? |
|---|---|---|---|---|
| `SafeSpanExporter.metricsSnapshot()` | all 9 keys via `SpanExporterMetrics` | Формирование snapshot map | Production (producer) | Internal |
| `PlatformTracingControl.getSafeExporterMetrics()` | full map from `metricsSnapshot()` | JMX MBean read | Production | **Yes — JMX** attribute `SafeExporterMetrics` on `PlatformTracingControlMBean` |
| `SamplingControlClient.getSafeExporterMetrics()` | JMX attribute `SafeExporterMetrics` | Чтение через `MBeanServer.getAttribute` | Production | JMX client (application CL) |
| `SamplingControlClient.getExportMetrics()` | nests map under key `safeExporter` | Actuator aggregation | Production | **Yes — HTTP** `GET /actuator/tracing` → `export.safeExporter.*` |
| `TracingActuatorEndpoint.tracing()` | via `getExportMetrics()` | Actuator read endpoint | Production | **Yes — HTTP** (when agent ready) |
| `SafeSpanExporterTest` | literal keys + getters | Unit contract tests | Test | No |
| `PlatformTracingControlTest` | literal `"exported"`, `"batches"` | JMX late-binding test | Test | No |
| `TracingActuatorEndpointTest` | mock `export` section (processor `failures`, not safeExporter keys) | Actuator shape test | Test | No |
| `ADR-safe-span-exporter-v1.md` | lists 7 keys by name | Architecture contract | Docs | Documentation only |
| `dropped-span-reasons.md` | references `failures`, `dropped`; **incorrect** `dropped` for gate | Incident taxonomy | Docs | Documentation only (partial drift) |

**Production consumers outside `SafeSpanExporter`:** **да** — JMX + Spring actuator path. **Нет** production consumer, импортирующего `SpanExporterMetrics` кроме `SafeSpanExporter`.

**OpenTelemetry / Prometheus export этих ключей:** **NOT FOUND IN REPOSITORY** as registered OTel instruments or Micrometer meters for `SpanExporterMetrics` keys. `PlatformTracingSafeWrapperMetricsBinder` polls **`SafeWrapperMetrics`**, not `SafeExporterMetrics` (ADR-safe-span-exporter-v1 явно: export metrics — JMX + actuator, not `MeterRegistry`).

**Actuator naming collision (evidence):** `SamplingControlClient.getExportMetrics()` кладёт processor counter в `export.failures` (`ExportFailuresTotal`) **и** отдельно `export.safeExporter` map, где снова есть ключ `failures` — **разная семантика**, один natural language label.

---

## 7. Test Coverage Map

| Test Class | Scenario | Metrics Covered | Behavior Protected |
|---|---|---|---|
| `SafeSpanExporterTest.successfulExportCountsExportedSpans` | success export | getters: `exported`, `failures`, `dropped`, `batches` | success accounting |
| `SafeSpanExporterTest.failedResultIncrementsBatchFailureAndSpanDrops` | async failure result | `failures`, `dropped`, `exported` | batch vs span on failure |
| `SafeSpanExporterTest.delegateExceptionIsIsolated` | thrown export | `failures`, `dropped` | exception isolation |
| `SafeSpanExporterTest.nullResultTreatedAsFailure` | null export result | `failures`, `dropped` | null guard |
| `SafeSpanExporterTest.countersDistinguishBatchesFromSpans` | 2 failed batches | `failures`, `dropped` | batch/span separation |
| `SafeSpanExporterTest.flushExceptionIsIsolated` | flush throw | `flushFailures` getter (not snapshot key assert) | flush failure counter |
| `SafeSpanExporterTest.shutdownExceptionIsIsolated` | shutdown throw | `shutdownFailures` getter | shutdown failure counter |
| `SafeSpanExporterTest.metricsSnapshotHasStableKeys` | snapshot keys | literals: `batches`, `failures`, `exported`, `dropped`, `flush_failures`, `shutdown_failures`, `last_export_duration_nanos`; value `exported=1` | partial key stability (** omits `gated`, `export_enabled` **) |
| `SafeSpanExporterTest.exportGateDropsBatchWithoutCallingDelegate` | export gate | getters `gatedSpans`, `exportBatches`, `dropped`, `failures` | gate semantics (not snapshot keys) |
| `PlatformTracingControlTest.export_метрики_возвращают_нули...` | no exporter bound | empty `getSafeExporterMetrics()` | JMX empty state |
| `PlatformTracingControlTest.export_метрики_читаются_лениво...` | late binding | `exported`, `batches` in map | JMX reads live snapshot |
| `PlatformExportProcessorFactorySafeWrapTest` | SafeSpanExporter wrap | none on metric keys | wrapping, not metrics |
| `TracingActuatorEndpointTest.readOperation_export_секция_*` | actuator export | mock processor fields only | graceful `not_ready`; **no assert on safeExporter keys** |

**Gap:** нет теста, asserting `gated` / `export_enabled` in `metricsSnapshot()`; нет integration test Prometheus scrape for these keys.

---

## 8. Documentation Mentions

| Document | Mention | Current Meaning | Is It Current? |
| -------- | ------- | --------------- | -------------- |
| `docs/decisions/ADR-safe-span-exporter-v1.md` | `batches`/`failures` (batch), `dropped`/`exported` (span), `flush_failures`, `shutdown_failures`, `last_export_duration_nanos`; `getSafeExporterMetrics()` | Matches core SafeSpanExporter semantics | **Mostly yes** — omits `gated`, `export_enabled` |
| `docs/decisions/ADR-safe-span-exporter-v1.md` | JMX + `GET /actuator/tracing` exposure; **not** Micrometer | Architecture decision | **Yes** |
| `docs/tracing/dropped-span-reasons.md` | `EXPORT_FAILURE` → keys `failures`, `dropped` | Transport failure in SafeSpanExporter | **Yes** for failure path |
| `docs/tracing/dropped-span-reasons.md` | `EXPORT_GATE_DISABLED` → key **`dropped`** | Claims gate increments `dropped` | **No — code uses `gated`, not `dropped`** |
| `docs/architecture/platform-tracing-current-codebase-inventory.md` | `SafeSpanExporter`, `SafeSpanExporterTest` | Component inventory | **Yes** (no per-key detail) |
| `docs/architecture/platform-tracing-preservation-first-migration-plan.md` | preserve `SafeSpanExporter` | Migration | **Yes** (no metric keys) |
| `docs/tracing/requirements-coverage-dossier.md` | `SafeSpanExporter` isolation | Requirements trace | **Yes** (no metric keys) |
| `PlatformTracingControlMBean.java` javadoc | lists 7 snapshot keys | JMX API doc | **Partially stale** — missing `gated`, `export_enabled` |

**`SpanExporterMetrics` class name in docs:** **NOT FOUND IN REPOSITORY** (docs refer to keys / `getSafeExporterMetrics`, not the utility class).

---

## 9. Metric Type Classification

| Current Key | Recommended Metric Type Category | Reason |
| ----------- | -------------------------------- | ------ |
| `batches` | Counter + Internal snapshot key | Monotonic `LongAdder`; only exposed via map/JMX/actuator |
| `failures` | Counter + Internal snapshot key | Monotonic batch failure count |
| `exported` | Counter + Internal snapshot key | Monotonic span success count |
| `dropped` | Counter + Internal snapshot key | Monotonic span loss on transport failure |
| `flush_failures` | Counter + Internal snapshot key | Monotonic flush error count |
| `shutdown_failures` | Counter + Internal snapshot key | Monotonic shutdown error count |
| `last_export_duration_nanos` | Duration gauge (last value) + Internal snapshot key | Overwritten `AtomicLong`; not monotonic |
| `gated` | Counter + Internal snapshot key | Monotonic intentional suppression count |
| `export_enabled` | State gauge + Internal snapshot key | Boolean encoded 0/1; snapshot at read time |

**Not classified as:** exported OpenTelemetry metric instruments (**no code evidence**).

---

## 10. Naming Problem Analysis

1. **`batches`** — не указывает domain (`export`, `span`, `transport`, `safe_exporter`); в enterprise dashboards неясно, BSP batch или transport batch.
2. **`failures`** — не указывает batch vs span vs flush vs shutdown; **коллизия** с actuator `export.failures` (processor `ExportFailuresTotal`).
3. **`exported`** — не указывает spans; пересекается лексически с sampling/exported trace semantics (`PlatformSamplingReasons.EXPORTED` — другой domain).
4. **`dropped`** — сильная амбигuity с queue overflow (`getExportDroppedOverflowTotal`), shutdown drops, sampling DROP; код разделяет, имя — нет.
5. **`gated`** — ops-unfriendly без runbook; кодовая семантика = export kill-switch suppression, не generic «gate».
6. **`export_enabled`** — state в map of counters; дублирует boolean JMX; numeric 0/1 без typed contract в HTTP JSON.
7. **`last_export_duration_nanos`** — единственный key с unit suffix; смешение naming styles в одном snapshot.
8. **`SpanExporterMetrics` vs exposure path** — constants live in exporter package, but external path is `SafeExporterMetrics` JMX / `safeExporter` actuator — **naming stack mismatch**.
9. **Informal brevity** — keys read like debug map entries, not enterprise metric names suitable for future Prometheus/OTel migration (ADR backlog v1.1).
10. **Documentation drift** — `dropped-span-reasons.md` mis-maps gate to `dropped`; MBean javadoc incomplete → operational risk при rename.

---

## 11. Naming Dimensions for Perplexity Scoring

Neutral dimensions for later LLM variants:

1. OpenTelemetry semantic convention alignment (`otel` export metrics naming).
2. Prometheus naming style (`_total` suffix, unit suffixes).
3. Internal snapshot key style (short map keys for JMX JSON).
4. Enterprise readability (self-describing for SRE without code).
5. Span vs batch explicitness in name.
6. Exporter / transport domain prefixing (`span_exporter`, `safe_exporter`, `transport`).
7. Units suffix convention (`_nanos` vs `_seconds` vs OTel unit metadata).
8. Counter suffix convention (`_total`, `_count`).
9. State gauge naming (`_enabled`, `_state`, `_info`).
10. Distinguishing export transport failure vs processor export failure.
11. Distinguishing flush/shutdown failures from batch export failures.
12. Avoiding ambiguity with queue overflow drops.
13. Avoiding ambiguity with export-gate suppression (`gated` alternatives).
14. Compatibility with nested actuator JSON (`export.safeExporter.*`).
15. Compatibility with flat JMX map keys.
16. Future Micrometer meter name mapping (if classloader bridge added).
17. Prefix strategy: component vs pipeline vs platform namespace.
18. Russian ops docs vs English metric identifiers.
19. Stability contract: rename keys vs add aliases.
20. Separation: snapshot keys vs exported telemetry names (dual naming layer).

---

## 12. Candidate Naming Requirements

Future names (any variant) should satisfy:

1. Self-explanatory without reading `SafeSpanExporter` source.
2. Explicit **span** vs **batch** where unit differs.
3. Explicit **export transport** domain to separate from processor queue metrics.
4. Preserve or explicitly decide **external compatibility** for JMX attribute map keys and actuator JSON.
5. Distinguish **counters** from **state gauges** (avoid storing boolean state in counter-like names).
6. Keep **duration units** explicit or document OTel unit mapping.
7. Clarify **gate/suppressed** semantics without ambiguous `gated` alone.
8. Work in `Map<String, Long>` snapshot (no boolean type today).
9. Suitable if later exposed as OTel/Prometheus metrics (low cardinality, stable labels).
10. Avoid breaking tests unexpectedly (`SafeSpanExporterTest`, `PlatformTracingControlTest`).
11. Resolve actuator collision between processor `failures` and safe exporter failures.
12. Align docs (`dropped-span-reasons`, MBean javadoc) with chosen gate metric name.
13. Consider dual-layer: stable external ID + display name (if rename deferred).
14. Do not conflate with `PlatformDropOldestExportSpanProcessor` counters.
15. English identifiers even if docs remain Russian.

---

## 13. Rename Impact Surface

| Affected Area | Expected Change | Risk |
| ------------- | --------------- | ---- |
| `SpanExporterMetrics.java` | constant string values | **HIGH** — single source of truth |
| `SafeSpanExporter.metricsSnapshot()` | uses constants (auto if constants change) | **HIGH** |
| `SafeSpanExporterTest.metricsSnapshotHasStableKeys` | literal key assertions | **HIGH** |
| `PlatformTracingControlTest` | literal `"batches"`, `"exported"` | **HIGH** |
| `PlatformTracingControlMBean` javadoc | key list | **MEDIUM** |
| `SamplingControlClient` / actuator JSON | nested map keys under `safeExporter` | **HIGH** — HTTP contract |
| JMX clients / scripts polling `SafeExporterMetrics` | map keys | **HIGH** — ops automation |
| `docs/decisions/ADR-safe-span-exporter-v1.md` | metric name list | **MEDIUM** |
| `docs/tracing/dropped-span-reasons.md` | taxonomy mapping | **MEDIUM** (already partially wrong for gate) |
| Prometheus / OTel | no current meters for these keys | **LOW today**; **HIGH if v1.1 bridge added** |
| External dashboards | **NOT FOUND IN REPOSITORY** | **UNKNOWN** — cannot confirm production dashboards |

**External exposure evidence:** **Yes** — JMX `SafeExporterMetrics` + actuator `export.safeExporter` map (when agent MBean ready). **No** evidence of Prometheus metric names equal to these snapshot keys.

**Backwards compatibility:** renaming strings breaks JMX/actuator consumers unless alias layer introduced; boolean `export_enabled` and numeric encoding likely need explicit migration note.

---

## 14. Questions for Perplexity Naming Pass

1. How should exporter **batch attempt** counters be named in OTel/Prometheus style?
2. Should `batches` become `export_batches_total`, `span_export_batches_total`, or `span_exporter_batches_total`?
3. Should `failures` explicitly include `batch` or `transport` qualifier?
4. How to disambiguate SafeSpanExporter `failures` from processor `ExportFailuresTotal` in a unified naming scheme?
5. Should `exported` be renamed to `exported_spans_total` or similar?
6. Should `dropped` be renamed to avoid confusion with queue overflow drops (`export_transport_dropped_spans_total`)?
7. Is `gated` acceptable enterprise terminology, or prefer `suppressed`, `blocked`, `gate_discarded`, `export_disabled_discarded`?
8. Should export-gate span counter be separate from transport failure `dropped` (code already separates — what name pair best conveys this)?
9. Should boolean state use dedicated suffix `_enabled` as gauge, not counter?
10. Is encoding state as `1/0` in `Map<String, Long>` acceptable long-term, or should actuator expose boolean?
11. Should duration use `_nanos`, `_seconds`, or OTel `unit=` metadata only?
12. Should names include `safe_span_exporter` component prefix?
13. If snapshot keys differ from future Prometheus names, should both coexist (alias map)?
14. Should internal snapshot keys remain short while exported metrics use long names?
15. How do OTel Java SDK self-metrics name export failures (for alignment reference)?
16. Should `flush_failures` / `shutdown_failures` share prefix with batch export failures or lifecycle namespace?
17. Should `last_export_duration_nanos` be a gauge named `export_last_duration` with unit attribute?
18. Does nested actuator path `export.safeExporter.<key>` affect optimal key naming ( redundancy with parent key)?
19. What naming convention does ADR-processor-errors-metric suggest for related platform.tracing.* metrics?
20. Should rename preserve exact strings for JMX v1 and introduce v2 attribute — compatibility strategy?
21. How to document gate metric in `dropped-span-reasons.md` after rename (`gated` vs `dropped`)?
22. Are there industry precedents (Google, Microsoft, CNCF) for export kill-switch counters?
23. Should Perplexity variants score **actuator JSON ergonomics** separately from **Prometheus ergonomics**?
24. Minimum breaking-change policy if external ops already parse `SafeExporterMetrics`?
25. Should constants class remain `SpanExporterMetrics` or align name with `SafeSpanExporter` / `SafeExporterMetrics` JMX naming?

---

## 15. Evidence Appendix

### 15.1 `SpanExporterMetrics` usages

```text
platform-tracing-otel-javaagent-extension/.../exporter/SpanExporterMetrics.java  (definition)
platform-tracing-otel-javaagent-extension/.../exporter/SafeSpanExporter.java:166-174  (only consumer of constants)
```

### 15.2 `metricsSnapshot()` usages

```text
SafeSpanExporter.java:164          — producer
PlatformTracingControl.java:711    — return exporter.metricsSnapshot()
SafeSpanExporterTest.java:136      — test
```

### 15.3 Current key string usages (source of truth)

All key literals defined only in `SpanExporterMetrics.java:8-16`.

Test literals (duplicate strings, not importing `SpanExporterMetrics`):

```text
SafeSpanExporterTest.java:138-140     — 7 keys in containsKeys
PlatformTracingControlTest.java:259-260 — "exported", "batches"
```

### 15.4 Tests found

```text
SafeSpanExporterTest.java           — primary metrics semantics tests
PlatformTracingControlTest.java     — JMX getSafeExporterMetrics late binding
PlatformExportProcessorFactorySafeWrapTest.java — wrap only
TracingActuatorEndpointTest.java    — export section mock, no safeExporter key asserts
```

### 15.5 Docs found (metric-key relevant)

```text
docs/decisions/ADR-safe-span-exporter-v1.md
docs/tracing/dropped-span-reasons.md
PlatformTracingControlMBean.java javadoc (in-source doc)
```

General `SafeSpanExporter` mentions (without per-key detail): multiple architecture/migration/requirements docs — see §8.

### 15.6 External exposure findings

| Channel | Evidence | Keys exposed |
|---------|----------|--------------|
| JMX | `PlatformTracingControl.getSafeExporterMetrics()`; client reads attribute `SafeExporterMetrics` | all 9 keys as map |
| Actuator HTTP | `TracingActuatorEndpoint` → `SamplingControlClient.getExportMetrics()` → `export.safeExporter` | all 9 keys nested |
| Prometheus/Micrometer | `PlatformTracingSafeWrapperMetricsBinder` uses `SafeWrapperMetrics` only | **NOT** SpanExporterMetrics keys |
| OpenTelemetry SDK Meter | no matches in otel-extension main | **NOT FOUND** |

### 15.7 Related but distinct counters (naming collision context)

```text
PlatformDropOldestExportSpanProcessor — droppedSpansOverflow, exportFailures (processor-level)
SamplingControlClient export.put("failures", ExportFailuresTotal) — processor, top-level actuator key
PlatformTracingControl.getExportFailuresTotal() — processor export failures
```

---

## 16. Non-Findings / Unknowns

| Item | Status |
|------|--------|
| OpenTelemetry metric instrument registration for these keys | **NOT FOUND IN REPOSITORY** |
| Micrometer `MeterBinder` for `SafeExporterMetrics` / SpanExporter snapshot keys | **NOT FOUND IN REPOSITORY** |
| Production Grafana/dashboard JSON referencing `batches`/`gated`/etc. | **NOT FOUND IN REPOSITORY** |
| Docs mentioning class name `SpanExporterMetrics` | **NOT FOUND IN REPOSITORY** |
| Tests asserting `export_enabled` / `gated` snapshot keys | **NOT FOUND IN REPOSITORY** |
| Git history / blame for metric key introduction | **Git unavailable** |
| External API consumers outside repo (ops scripts) | **UNKNOWN** |

---

## 17. Final Status

```text
Inventory status: COMPLETED
No refactoring performed.
Ready for Perplexity 6-variant SpanExporter metrics naming pass.
```
