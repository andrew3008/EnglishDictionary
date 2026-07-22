# PlatformTracingControl Constructor Inventory

> **СТАТУС: УСТАРЕЛО ПОСЛЕ CLEANUP (2026-06-18).** После реализации `AGGRESSIVE_FULL_BOUNDARY_CLEANUP`
> класс `PlatformTracingControl` — чистая реализация MBean: один **package-private** канонический
> конструктор (бывш. C7), конструкторы C1–C6 удалены, все `registerSafely(...)` (R1–R8) удалены.
> `PlatformTracingControl` не предоставляет registration API. `PlatformTracingJmxRegistrar` **перенесён
> в пакет `jmx`** (единственный владелец JMX-жизненного цикла), напрямую вызывает package-private
> конструктор и содержит терминальную регистрацию в `tryRegisterMBean()`. Публичного хелпера
> `PlatformTracingControlRegistration` нет. Тесты создают экземпляры через `PlatformTracingControlTestBuilder`.
> Документ ниже отражает состояние **до** cleanup и сохранён как исторический артефакт.
>
> **Тип документа:** инвентаризация (analysis-only).
>
> **Дата:** 2026-06-18
>
> **Цель:** собрать доказательства для будущего PR по сокращению публичных конструкторов и перегрузок `registerSafely(...)` класса `PlatformTracingControl`.

---

## 1. Executive Summary

**Факты по классу** (`platform-tracing-otel-javaagent-extension/src/main/java/.../jmx/PlatformTracingControl.java`):

| Метрика | Значение |
|---------|----------|
| Публичных конструкторов | **7** |
| Статических перегрузок `registerSafely(...)` | **8** |
| Канонический конструктор (полный) | **9 параметров** — `(configHolder, compositeSampler, watchdog, composite, metrics, scrubbing, validating, exportProcessorSupplier, safeExporterSupplier)` |
| Канонический `registerSafely` | **та же 9-параметровая сигнатура** — единственная перегрузка, создающая MBean через `MBeanServer.registerMBean` |
| Прямых `new PlatformTracingControl(...)` в `src/main` | **0** (создание только внутри канонического `registerSafely`) |
| Production-вызов `registerSafely` | **1 call-site** — `PlatformTracingJmxRegistrar.tryRegisterMBean()` (9-arg) |

**Выводы:**

- Production startup **не вызывает** короткие перегрузки конструктора или `registerSafely` — только полную 9-arg `registerSafely` через `PlatformTracingJmxRegistrar`.
- Все **7 конструкторов** и **7 из 8** перегрузок `registerSafely` — цепочки делегирования (convenience wrappers); дублируют друг друга по смыслу.
- **Единственный test call-site** `registerSafely` — 1-arg `(SamplerStateHolder)` в `PlatformTracingControlTest` (проверка регистрации/idempotency).
- **Test call-sites** `new PlatformTracingControl(...)` — **5 файлов**, **~30 инстанцирований**; используют **5 различных сигнатур** (1-arg, 2-arg, 3-arg, 4-arg, 6-arg, 8-arg, 9-arg).
- **6-arg** и **8-arg** конструкторы **не вызываются напрямую из тестов** — только через делегирование из более коротких конструкторов.
- Многие overload'ы выглядят **OBSOLETE_OR_COMPATIBILITY / TEST_ONLY** — исторические convenience-обёртки фаз PR-6D/7B/8B/10/14.
- **Важное production-наблюдение (не баг данного inventory):** `PlatformTracingJmxRegistrar.tryRegisterMBean()` срабатывает при первом `setConfigHolder()` в фазе `addSamplerCustomizer` — **до** регистрации span processors. Snapshot зависимостей (watchdog, composite, metrics, scrubbing, validating) фиксируется на момент регистрации; только export-поставщики ленивые (`this::getExportProcessor`, `this::getSafeExporter`). Это влияет на null-tolerant поведение production MBean для метрик процессоров.

**Пробелы доказательств:**

- Reflection/dynamic construction `PlatformTracingControl` — **не найдено** в репозитории (кроме unrelated `OtelSdkExtension.getDeclaredConstructor()`).
- Порядок OTel autoconfigure callbacks в реальном agent runtime vs unit-test `Recorder` proxy — **частично** покрыт тестами; полный production agent bootstrap не воспроизведён в данном inventory.

---

## 2. Scope

### 2.1 Primary class

| Поле | Значение |
|------|----------|
| FQCN | `space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingControl` |
| Файл | `platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/jmx/PlatformTracingControl.java` |
| Реализует | `PlatformTracingControlMBean` |
| ObjectName | `space.br1440.platform.tracing:type=Control,name=PlatformTracingControl` (константа `PlatformTracingControlMBean.OBJECT_NAME`) |
| Поля экземпляра | `configHolder`, `compositeSampler`, `watchdog`, `composite`, `metrics`, `scrubbing`, `validating`, `exportProcessorSupplier`, `safeExporterSupplier`, `invalidConfigCounter` |

### 2.2 Adjacent classes inspected

| Класс | Роль |
|-------|------|
| `PlatformTracingControlMBean` | JMX-контракт (~40 операций) |
| `PlatformTracingJmxRegistrar` | Production orchestrator регистрации MBean |
| `PlatformAutoConfigurationCustomizer` | OTel SPI entry; владеет `PlatformTracingJmxRegistrar` |
| `PlatformSamplerFactory` | `setConfigHolder` + `setCompositeSampler` → trigger registration |
| `PlatformSpanProcessorFactory` | `setWatchdog/Metrics/Composite/Scrubbing/Validating` + `tryRegisterMBean` |
| `PlatformExportProcessorFactory` | `setExportProcessor` + `setSafeExporter` (late-binding) |
| `SamplingControlClient` | App-side JMX client (invoke by ObjectName, не использует конструкторы) |
| Test classes | `PlatformTracingControlTest`, `SamplingPolicyRuntimeUpdateJmxTest`, `ScrubbingPolicyRuntimeUpdateJmxTest`, `ValidationPolicyRuntimeUpdateJmxTest`, `ValidationStrictRuntimeGuardTest`, `PlatformAutoConfigurationCustomizerProcessorsTest` |

### 2.3 Commands / searches performed

```text
rg "new PlatformTracingControl" .                    (excluding build/)
rg "PlatformTracingControl\.registerSafely" .        (excluding build/)
rg "registerSafely\(" platform-tracing-otel-javaagent-extension/src ...
rg "PlatformTracingControl" .                        (excluding build/)
rg "PlatformTracingControlMBean" .
rg "PlatformTracingJmxRegistrar|registerMBean|ObjectName" platform-tracing-otel-javaagent-extension/src
rg "SamplerStateHolder|CompositeSampler|...|SafeSpanExporter" platform-tracing-otel-javaagent-extension/src
rg "Supplier<PlatformDropOldestExportSpanProcessor>|NULL_SUPPLIER|exportProcessorSupplier" ...
rg "PlatformTracingControl\.class|getDeclaredConstructor|Class\.forName.*PlatformTracingControl"
```

Исходные файлы прочитаны напрямую: `PlatformTracingControl.java`, `PlatformTracingJmxRegistrar.java`, `PlatformAutoConfigurationCustomizer.java`, все test call-sites, `PlatformTracingControlMBean.java`.

---

## 3. Constructor Inventory

| # | Signature | Delegates to | Defaults / nulls | Supports validation | Supports export suppliers | Production call-sites | Test call-sites | Classification |
|---|-----------|--------------|------------------|---------------------|---------------------------|----------------------|-----------------|----------------|
| C1 | `(SamplerStateHolder configHolder)` | C3 `(configHolder, null, null, null)` | `compositeSampler=null`, `watchdog=null`, `composite=null`; далее C4→C6→C7 с `metrics=null`, `scrubbing=null`, `validating=null`, `NULL_SUPPLIER`×2 | нет (`validating=null`) | `NULL_SUPPLIER` (нули export metrics) | Not found | `PlatformTracingControlTest` (×12), `SamplingPolicyRuntimeUpdateJmxTest` (×3) | **TEST_ONLY** |
| C2 | `(SamplerStateHolder, SpanWatchdogProcessor watchdog)` | C3 `(configHolder, null, watchdog, null)` | `compositeSampler=null`, `composite=null`; далее как C1 | нет | `NULL_SUPPLIER` | Not found | `PlatformTracingControlTest.stats_методы_возвращают_текущее_состояние_watchdog_а` | **TEST_ONLY** |
| C3 | `(SamplerStateHolder, SpanWatchdogProcessor, PlatformCompositeSpanProcessor composite)` | C4 `(configHolder, null, watchdog, composite)` | `compositeSampler=null`; далее C6 | нет | `NULL_SUPPLIER` | Not found | `PlatformTracingControlTest.getProcessorErrorsTotal_aggregatesPerDelegateCounts` — `(holder, null, composite)` | **TEST_ONLY** |
| C4 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | C6 `(…, null, null)` | `metrics=null`, `scrubbing=null`; далее C7 `validating=null`, `NULL_SUPPLIER` | нет | `NULL_SUPPLIER` | Not found | `SamplingPolicyRuntimeUpdateJmxTest.updateSamplingPolicy_arrays_updatesDomainAndCompositeSampler` — `(holder, sampler, null, null)` | **TEST_ONLY** |
| C5 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor, MetricsSpanProcessor metrics, ScrubbingSpanProcessor scrubbing)` | C7 `(…, null, NULL_SUPPLIER, NULL_SUPPLIER)` | `validating=null`; export suppliers = `NULL_SUPPLIER` | нет (validating=null) | `NULL_SUPPLIER` | Not found | `ScrubbingPolicyRuntimeUpdateJmxTest` (×8) — `(null, null, null, null, null, processor)` | **TEST_ONLY** |
| C6 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor, MetricsSpanProcessor, ScrubbingSpanProcessor, Supplier<PlatformDropOldestExportSpanProcessor>, Supplier<SafeSpanExporter>)` | C8 `(…, null, exportProcessorSupplier, safeExporterSupplier)` | `validating=null` | нет | да (явные suppliers) | Not found | `PlatformTracingControlTest.export_метрики_читаются_лениво_после_late_binding` — `(holder, null, null, null, null, null, procRef::get, safeRef::get)` | **TEST_ONLY** |
| C7 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor, MetricsSpanProcessor, ScrubbingSpanProcessor, ValidatingSpanProcessor validating, Supplier<PlatformDropOldestExportSpanProcessor>, Supplier<SafeSpanExporter>)` | **terminal** (прямая инициализация полей) | нет defaults | **да** | **да** | Not found (создаётся только внутри `registerSafely` C8) | `ValidationPolicyRuntimeUpdateJmxTest.controlWith`, `ValidationStrictRuntimeGuardTest.controlWith` — `(null×6, processor, ()->null, ()->null)` | **PRODUCTION_REQUIRED** (как target ctor канонического `registerSafely`); direct test use = **TEST_ONLY** |

**Примечания:**

- C5 технически 6 параметров + implicit `NULL_SUPPLIER`; тесты вызывают его напрямую с `(null, null, null, null, null, processor)`.
- C6/C7 не вызываются production-кодом напрямую; C7 — terminal constructor для MBean instance.
- `@SuppressWarnings("unchecked")` на C5/C6 и соответствующих `registerSafely` — cast `NULL_SUPPLIER` к typed `Supplier<T>`.

---

## 4. registerSafely Inventory

| # | Signature | Delegates to | Defaults / nulls | Registers MBean directly | Production call-sites | Test call-sites | Classification |
|---|-----------|--------------|------------------|--------------------------|----------------------|-----------------|----------------|
| R1 | `(SamplerStateHolder configHolder)` | R3 | `compositeSampler=null`, `watchdog=null`, `composite=null` | нет (chain) | Not found | `PlatformTracingControlTest.registerSafely_*` (×3) — 1-arg | **TEST_ONLY** |
| R2 | `(SamplerStateHolder, SpanWatchdogProcessor)` | R3 | `compositeSampler=null`, `composite=null` | нет | Not found | Not found | **UNUSED** |
| R3 | `(SamplerStateHolder, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | R4 | `compositeSampler=null` | нет | Not found | Not found | **UNUSED** |
| R4 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | R6 | `metrics=null`, `scrubbing=null` | нет | Not found | Not found | **UNUSED** |
| R5 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor, MetricsSpanProcessor, ScrubbingSpanProcessor)` | R7 | `validating=null`, `NULL_SUPPLIER`×2 | нет | Not found | Not found | **UNUSED** |
| R6 | `(…, Supplier<PlatformDropOldestExportSpanProcessor>, Supplier<SafeSpanExporter>)` — 8 params + 2 suppliers | R8 | `validating=null` | нет | Not found | Not found | **UNUSED** |
| R7 | *(same as R6 row — 8-arg with suppliers, validating=null)* | R8 | `validating=null` | нет | Not found | Not found | **OBSOLETE_OR_COMPATIBILITY** (mirror C6) |
| R8 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor, MetricsSpanProcessor, ScrubbingSpanProcessor, ValidatingSpanProcessor validating, Supplier<PlatformDropOldestExportSpanProcessor>, Supplier<SafeSpanExporter>)` | **terminal** — `new PlatformTracingControl(...)` + `MBeanServer.registerMBean` | передаёт args as-is | **да** | `PlatformTracingJmxRegistrar.tryRegisterMBean():99-104` | Not found | **PRODUCTION_REQUIRED** |

**ObjectName:** `PlatformTracingControlMBean.OBJECT_NAME` = `space.br1440.platform.tracing:type=Control,name=PlatformTracingControl`

**Поведение terminal R8:**

- `InstanceAlreadyExistsException` → return `false`, debug log
- любой другой `Throwable` → return `false`, warn log
- success → info log с boolean flags наличия каждой зависимости

---

## 5. Canonical Full Constructor Candidate

**Кандидат:** C7 (9 параметров, terminal)

```java
public PlatformTracingControl(
    SamplerStateHolder configHolder,
    CompositeSampler compositeSampler,
    SpanWatchdogProcessor watchdog,
    PlatformCompositeSpanProcessor composite,
    MetricsSpanProcessor metrics,
    ScrubbingSpanProcessor scrubbing,
    ValidatingSpanProcessor validating,
    Supplier<PlatformDropOldestExportSpanProcessor> exportProcessorSupplier,
    Supplier<SafeSpanExporter> safeExporterSupplier)
```

**Почему канонический:**

- Единственный конструктор без делегирования — прямое присвоение всех 9 полей.
- Именно его вызывает terminal `registerSafely` R8.
- Покрывает все dependency groups MBean: sampling, sampler counters, watchdog, composite errors, span limits metrics, scrubbing policy+metrics, validation policy, export pipeline (lazy), export gate.

**Production должен использовать только его?**

- **Логически да** — через `registerSafely` R8 / `PlatformTracingJmxRegistrar`; прямой `new` в production **не найден**.
- Короткие конструкторы C1–C6 — **pure convenience wrappers** для тестов и исторической поэтапной сборки (фазы PR-6..14).

**Рекомендация deletion:** не давать в этом inventory — evidence overwhelming для C1–C6 как REMOVE_CANDIDATE, но test rewrite required.

---

## 6. Canonical registerSafely Candidate

**Кандидат:** R8 (9 параметров, terminal)

**Production должен использовать только R8?**

- **Да** — единственный production call-site уже использует R8.
- R1–R7 — convenience chain; R1 единственный с test usage; R2–R7 — **UNUSED** в текущем репозитории.

**Дублирование с конструкторами:**

- Цепочки R1–R7 **зеркально повторяют** C1–C6 + вызывают C7 через `new PlatformTracingControl(...)`.
- R1 + C1..C6 можно рассматривать как paired obsolete API surface.

---

## 7. Field Initialization Matrix

| Field | Type | Full constructor parameter | Default in short constructors | Used by methods | Null behavior |
|-------|------|---------------------------|-------------------------------|-----------------|---------------|
| `configHolder` | `SamplerStateHolder` | param 1 | always passed (may be null in scrubbing/validation tests) | все sampling getters/setters, `updateSamplingPolicy*`, `getSamplingConfigVersion/Source`, `getInvalidConfigCount` (partial) | getters: empty/-1/"unknown"; mutators: `IllegalStateException("SamplerStateHolder is not registered")` |
| `compositeSampler` | `CompositeSampler` | param 2 | `null` in C1–C3 | `getSamplerDecisionCount(s)`, `getSamplerDecisionCounts`, `resetSamplerCounters` | returns `0` / empty map; reset no-op |
| `watchdog` | `SpanWatchdogProcessor` | param 3 | `null` in C1, C4–C7 when omitted | `getForcedSpanCloses`, `getForcedTraceCloses`, `getActiveSpanCount`, `getActiveTraceCount` | returns `0` |
| `composite` | `PlatformCompositeSpanProcessor` | param 4 | `null` in C1–C2, C4+ when omitted | `getProcessorErrorsTotal`, `getProcessorErrorsByName` | returns `0` / empty map |
| `metrics` | `MetricsSpanProcessor` | param 5 | `null` in C1–C4 | `getDroppedAttributesTotal`, `getDroppedEventsTotal`, `getDroppedLinksTotal` | returns `0` |
| `scrubbing` | `ScrubbingSpanProcessor` | param 6 | `null` except C5+ | `getScrubbingMetrics`, `isScrubbingEnabled`, `updateScrubbingPolicy*`, `getScrubbingConfigVersion/Source` | getters: empty/false/-1/"unknown"; mutators: `IllegalStateException("ScrubbingSpanProcessor is not registered")` |
| `validating` | `ValidatingSpanProcessor` | param 7 | `null` in C1–C6 | `isValidationEnabled/Strict/StrictRuntimeAllowed`, `updateValidationPolicy*`, `getValidationConfigVersion/Source` | getters: `false`/`-1`/`"unknown"`; mutators: `IllegalStateException("ValidatingSpanProcessor is not registered")` |
| `exportProcessorSupplier` | `Supplier<PlatformDropOldestExportSpanProcessor>` | param 8 | `NULL_SUPPLIER` in C5; explicit in C6/C7 | `getExportDroppedOverflowTotal`, `getExportDroppedAfterShutdownTotal`, `getExportFailuresTotal`, `getExportTimeoutsTotal`, `getExportQueueCapacity/Size` | supplier never null in C7; `NULL_SUPPLIER.get()` → null → metrics return `0` |
| `safeExporterSupplier` | `Supplier<SafeSpanExporter>` | param 9 | `NULL_SUPPLIER` in C5; explicit in C6/C7 | `isExportEnabled`, `setExportEnabled`, `getSafeExporterMetrics` | `setExportEnabled`: ISE if exporter null; `isExportEnabled`: false; metrics: empty map |
| `invalidConfigCounter` | `LongAdder` | *(internal)* | always new instance | `getInvalidConfigCount` | N/A |

**Методы без полей экземпляра (shared singletons):**

| Method group | Dependency |
|--------------|------------|
| Propagation gate | `PlatformPropagationGate.shared()` |
| Config reload diagnostics | `ConfigReloadDiagnostics.shared()` |
| Platform log level | `PlatformLogControl.shared()` |
| Safe wrapper metrics | `TracingDiagnostics.shared()` |

---

## 8. Dependency-to-MBean Method Matrix

| Dependency | MBean methods requiring it | Null behavior | Production necessity | Test-only usage |
|------------|---------------------------|---------------|---------------------|-------------------|
| `configHolder` | `isSamplerEnabled`, `setSamplerEnabled`, `get/setRouteRatios`, `get/setSamplingRatio`, `get/setDropPathPrefixes`, `get/setForceRecordValues`, `updateSamplingPolicy` (both overloads), `getSamplingConfigVersion`, `getSamplingConfigLastUpdatedSource` | mutators throw ISE; getters tolerant | **Required** — единственный hard gate в `tryRegisterMBean` (`configHolder == null` → no register) | C1 tests; scrubbing tests pass `null` configHolder |
| `compositeSampler` | `getSamplerDecisionCount`, `getSamplerDecisionCounts`, `resetSamplerCounters` | zero/empty/no-op | **Possibly required** for ops counters; may be null at early registration (см. §9) | C4 test with sampler |
| `watchdog` | `getForcedSpanCloses/TraceCloses`, `getActiveSpanCount/TraceCount` | zero | **Possibly required** for leak diagnostics; likely null at early MBean registration | C2 test |
| `composite` | `getProcessorErrorsTotal`, `getProcessorErrorsByName` | zero/empty | **Possibly required**; likely null at early registration | C3 test |
| `metrics` | `getDroppedAttributes/Events/LinksTotal` | zero | Optional metrics | Not found in direct tests |
| `scrubbing` | `getScrubbingMetrics`, `isScrubbingEnabled`, `updateScrubbingPolicy*`, scrubbing config version/source | mutators throw ISE | **Required** for scrubbing policy JMX path | C5 tests (8×), `configHolder=null` |
| `validating` | `isValidation*`, `updateValidationPolicy*`, validation config version/source | mutators throw ISE | **Required** for validation policy JMX path | C7 tests via `controlWith()` |
| `exportProcessorSupplier` | export queue/drop/failure/timeout metrics | lazy null → zero | **Required** (lazy) — production passes `this::getExportProcessor` | C6 late-binding test |
| `safeExporterSupplier` | `isExportEnabled`, `setExportEnabled`, `getSafeExporterMetrics` | lazy null; set throws ISE | **Required** (lazy) — production passes `this::getSafeExporter` | C6 late-binding test |
| *(none — singletons)* | propagation, config reload, log level, safe wrapper metrics | always available | Always active regardless of constructor | Partially covered in `PlatformTracingControlTest` |

### Method groups classification

| Group | Requires | Null-tolerant reads? | Mutations require non-null? |
|-------|----------|---------------------|----------------------------|
| Sampling runtime mutation | `configHolder` | n/a | **yes** |
| Sampler counters | `compositeSampler` | **yes** (0) | reset no-op |
| Watchdog metrics | `watchdog` | **yes** (0) | n/a |
| Composite processor error metrics | `composite` | **yes** (0/empty) | n/a |
| Span limits drop metrics | `metrics` | **yes** (0) | n/a |
| Scrubbing metrics + policy update | `scrubbing` | reads tolerant | **yes** for update |
| Validation metrics + policy update | `validating` | reads tolerant | **yes** for update |
| Export gate | `safeExporterSupplier` → live exporter | `isExportEnabled` → false | **yes** for `setExportEnabled` |
| Propagation gate | singleton | n/a | never null |
| Config reload diagnostics | singleton | n/a | n/a |
| Log level control | singleton | n/a | n/a |
| Export pipeline metrics | `exportProcessorSupplier` | **yes** (0) | n/a |
| Safe exporter metrics | `safeExporterSupplier` | **yes** (empty) | n/a |
| Safe wrapper metrics | singleton | n/a | n/a |

---

## 9. Production Call Graph

```text
io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider
  PlatformAutoConfigurationCustomizer.customize()
    │
    ├─ addPropertiesCustomizer → ExtensionConfig bootstrap
    │
    ├─ addSamplerCustomizer
    │    PlatformSamplerFactory.buildSampler(existing, extensionConfig.sampling())
    │      └─ registerForJmx(composite)
    │           ├─ PlatformTracingJmxRegistrar.setConfigHolder(holder)     ← triggers tryRegisterMBean (1st)
    │           └─ PlatformTracingJmxRegistrar.setCompositeSampler(composite)
    │
    ├─ addTracerProviderCustomizer
    │    PlatformSpanProcessorFactory.registerSpanProcessors(builder, extensionConfig, config)
    │      ├─ setScrubbing / setValidating / setWatchdog / setMetrics / setComposite
    │      └─ tryRegisterMBean()  ← no-op if already registered
    │
    ├─ addSpanExporterCustomizer
    │    PlatformExportProcessorFactory.captureExporter(exporter)
    │      └─ setSafeExporter(safe)  ← late-binding via supplier
    │
    └─ addSpanProcessorCustomizer
         PlatformExportProcessorFactory.maybeReplaceExportProcessor(...)
           └─ setExportProcessor(replacement)  ← late-binding via supplier

PlatformTracingJmxRegistrar.tryRegisterMBean()
  [gate: configHolder != null; mbeanRegistered == false]
  PlatformTracingControl.registerSafely(          ← R8 (9-arg CANONICAL)
      configHolder, compositeSampler, watchdog, composite,
      metrics, scrubbing, validating,
      this::getExportProcessor,                   ← lazy Supplier
      this::getSafeExporter                       ← lazy Supplier
  )
    new PlatformTracingControl(...)               ← C7 (9-arg CANONICAL)
    MBeanServer.registerMBean(instance, OBJECT_NAME)

App-side (NOT constructor/registerSafely):
  SamplingControlClient → MBeanServer.invoke(OBJECT_NAME, ...)
  RuntimeSamplingControlSmokeMain → JMX invoke by ObjectName string
  TracingActuatorEndpoint → indirect via SamplingControlClient pattern
```

**Evidence files:**

- `PlatformAutoConfigurationCustomizer.java:26-91`
- `PlatformSamplerFactory.java:37-40`
- `PlatformSpanProcessorFactory.java:74-114`
- `PlatformExportProcessorFactory.java:46,101`
- `PlatformTracingJmxRegistrar.java:84-107`

**Timing note:** первый успешный `tryRegisterMBean` происходит в `setConfigHolder` **до** `setCompositeSampler` в том же `registerForJmx` — snapshot `compositeSampler` на момент register может быть `null`. Flag `mbeanRegistered=true` блокирует повторную регистрацию после установки остальных компонентов. Export metrics работают через lazy suppliers; processor/scrubbing/validation refs могут оставаться null в live MBean instance.

---

## 10. Test Call Graph

```text
Direct new PlatformTracingControl(...):
  PlatformTracingControlTest
    C1 (holder)              → sampling CRUD, null-tolerant stats, registerSafely idempotency, export zeros
    C2 (holder, watchdog)    → watchdog stats
    C3 (holder, null, composite) → processor error aggregation
    C6 (holder, null×4, suppliers) → late-binding export metrics

  SamplingPolicyRuntimeUpdateJmxTest
    C4 (holder, sampler, null, null) → atomic sampling policy + CompositeSampler integration
    C1 (holder)              → invalid/concurrent sampling updates

  ScrubbingPolicyRuntimeUpdateJmxTest
    C5 (null×5, processor)   → scrubbing policy JMX bridge (8 tests)

  ValidationPolicyRuntimeUpdateJmxTest
    C7 via controlWith()     → validation policy JMX bridge

  ValidationStrictRuntimeGuardTest
    C7 via controlWith()     → strict runtime guard via JMX path

PlatformTracingControl.registerSafely(...):
  PlatformTracingControlTest
    R1 (holder only)         → MBean registration + duplicate registration

Indirect (MBeanServer, no constructor):
  PlatformAutoConfigurationCustomizerProcessorsTest
    → full customize + samplerCustomizer → asserts MBean registered, get/set SamplingRatio

  SamplingControlClientTest / SamplingControlClientWireContractTest
    → stub MBean, NOT PlatformTracingControl

  platform-tracing-e2e-tests/RuntimeSamplingControlSmokeMain
    → invoke production ObjectName in agent JVM (integration, no direct ctor)
```

---

## 11. Docs References

| Doc | Section / context | Constructor/registerSafely mentioned | Current status |
|-----|-------------------|--------------------------------------|----------------|
| `docs/architecture/platform-tracing-current-codebase-inventory.md` | JMX control plane inventory | class reference, partial Map ops | **active** |
| `docs/architecture/platform-tracing-preservation-first-migration-plan.md` | PR-2/PR-3 preservation | «не изменять PlatformTracingControl»; test commands | **active** (migration context) |
| `docs/architecture/ADR-jmx-wire-map-contract.md` | Wire vs primitive JMX | `PlatformTracingControlTest` command | **active** |
| `docs/architecture/span-exporter-metrics-usage-inventory.md` | Export metrics JMX path | `getSafeExporterMetrics`, `isExportEnabled` | **active** |
| `docs/decisions/ADR-safe-span-exporter-v1.md` | SafeSpanExporter JMX | `PlatformTracingControlTest` | **active** |
| `docs/tracing/dropped-span-reasons.md` | MBean ObjectName | OBJECT_NAME only | **active** |
| `docs/architecture/Components_v1.puml` | UML | `registerSafely(sampler, watchdog?)` — **устаревшая сигнатура** | **stale** |
| `docs/architecture/platform-tracing-classes.puml` | UML | `{static} +registerSafely(...): void` — без детализации overloads | **stale** |
| `docs/architecture/jmx-spike-production-purge-test-fixtures-plan.md` | Removed spike | historical `TracingControlWireSpike.registerSafely()` | **historical** |
| `docs/architecture_target/architecture_detailed.puml` | Target arch | `PlatformTracingControl` component | **active** |

---

## 12. Constructor Classification Summary

| Constructor | Classification | Evidence | Keep pressure | Future action candidate |
|-------------|---------------|----------|---------------|------------------------|
| C1 `(SamplerStateHolder)` | TEST_ONLY | 15 test call-sites; 0 production | WEAK_KEEP | REMOVE_AFTER_TEST_REWRITE |
| C2 `(SamplerStateHolder, SpanWatchdogProcessor)` | TEST_ONLY | 1 test; 0 production | REMOVE_CANDIDATE | REMOVE_AFTER_TEST_REWRITE |
| C3 `(SamplerStateHolder, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | TEST_ONLY | 1 test; 0 production | REMOVE_CANDIDATE | REMOVE_AFTER_TEST_REWRITE |
| C4 `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | TEST_ONLY | 1 test; 0 production | REMOVE_CANDIDATE | REMOVE_AFTER_TEST_REWRITE |
| C5 `(…, MetricsSpanProcessor, ScrubbingSpanProcessor)` | TEST_ONLY | 8 scrubbing tests; 0 production | WEAK_KEEP | REMOVE_AFTER_TEST_REWRITE |
| C6 `(…, Supplier×2)` validating=null | TEST_ONLY | 1 late-binding test; 0 production | WEAK_KEEP | REMOVE_AFTER_TEST_REWRITE |
| C7 full 9-arg | PRODUCTION_REQUIRED (via R8) + TEST_ONLY (direct) | R8 terminal; 2 validation test helpers | STRONG_KEEP | KEEP |

---

## 13. registerSafely Classification Summary

| Overload | Classification | Evidence | Keep pressure | Future action candidate |
|----------|---------------|----------|---------------|------------------------|
| R1 `(SamplerStateHolder)` | TEST_ONLY | 3 test calls; 0 production | WEAK_KEEP | REMOVE_AFTER_TEST_REWRITE |
| R2 `(SamplerStateHolder, SpanWatchdogProcessor)` | UNUSED | Not found in repository search | REMOVE_CANDIDATE | REMOVE_NOW_CANDIDATE |
| R3 `(SamplerStateHolder, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | UNUSED | Not found | REMOVE_CANDIDATE | REMOVE_NOW_CANDIDATE |
| R4 `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | UNUSED | Not found | REMOVE_CANDIDATE | REMOVE_NOW_CANDIDATE |
| R5 `(…, MetricsSpanProcessor, ScrubbingSpanProcessor)` | UNUSED | Not found | REMOVE_CANDIDATE | REMOVE_NOW_CANDIDATE |
| R6/R7 8-arg with suppliers, validating=null | UNUSED | Not found | REMOVE_CANDIDATE | REMOVE_NOW_CANDIDATE |
| R8 full 9-arg | PRODUCTION_REQUIRED | `PlatformTracingJmxRegistrar:99-104` | STRONG_KEEP | KEEP |

---

## 14. Risk Matrix

| Risk | Severity | Evidence | Mitigation |
|------|----------|----------|------------|
| Compile breakage in tests after removing short constructors | **Medium** | ~30 test instantiations across 5 files | Introduce test factory/builder before deletion |
| Test loss of null-tolerant partial wiring | **Medium** | C1/C5 tests rely on null deps for isolated domain tests | Test factory with explicit `null` slots or `@Nullable` builder |
| JMX registration behavior change | **High** if R8 modified | Single production path | Keep R8 signature stable; any change requires registrar update |
| MBean operation behavior change | **High** | Null-tolerant reads documented in MBean interface | Do not change null semantics without ADR |
| Loss of late-binding export metrics tests | **Low** | 1 test uses C6 with AtomicReference suppliers | Preserve via full constructor + suppliers in test helper |
| Spring/OTel SPI startup breakage | **High** if R8 broken | `PlatformAutoConfigurationCustomizer` wiring | Integration test `PlatformAutoConfigurationCustomizerProcessorsTest` |
| Reflection/dynamic construction | **Low** | Not found in repo search | Grep guard in CI if needed |
| Documentation drift | **Low** | Stale UML in `Components_v1.puml` | Update diagrams with cleanup PR |
| Production early MBean registration with null processor refs | **Medium** (existing) | `setConfigHolder` triggers register before span processors | **Separate PR** — not constructor cleanup; consider defer registration or re-register pattern |

---

## 15. Proposed Cleanup Questions For Later LLM Review

1. Должен ли production оставить **только** полный 9-arg конструктор (package-private) + factory/registrar?
2. Следует ли сделать C1–C6 **package-private** вместо public?
3. Должны ли тесты инстанцировать через **test factory** (`PlatformTracingControlTestSupport.builder()`) вместо production convenience constructors?
4. Следует ли **схлопнуть** R1–R7 `registerSafely` в один public R8, оставив регистрацию только через `PlatformTracingJmxRegistrar`?
5. Стоит ли заменить 9 параметров конструктора на **record/context object** (`PlatformTracingControlContext`)?
6. Должны ли late-binding export suppliers стать частью dedicated **registration context** в registrar?
7. Следует ли **полностью перенести** JMX registration в `PlatformTracingJmxRegistrar` (без public `registerSafely` на MBean impl)?
8. Должен ли `PlatformTracingControl` иметь **вообще public constructors**, или один package-private canonical ctor?
9. Нужно ли исправить **early registration timing** (registrar registers on first `setConfigHolder`) отдельно от constructor cleanup?
10. Допустимо ли удалить R2–R7 **немедленно** (UNUSED) без test rewrite?

---

## 16. Evidence Gaps

| Gap | Status |
|-----|--------|
| Reflection/instantiation `PlatformTracingControl` outside repo | **Not found** — Unknown requires follow-up only if external consumers exist |
| Real OTel agent JVM bootstrap order vs unit-test `Recorder` | Partially covered; full agent E2E for all MBean metric groups — **not verified in this inventory** |
| Whether production MBean actually has null processor refs at runtime | Inferred from call order analysis; **not confirmed by runtime JMX dump** |
| External downstream libraries calling public constructors | **Not found in monorepo** — Unknown for published artifact consumers |
| `Components_v1.puml` accuracy vs current 9-arg API | **Stale** — diagram shows old 2-param `registerSafely` |

---

## 17. Recommended Inputs For Perplexity / Cursor Next Step

```text
Repository facts:
- PlatformTracingControl: 7 public constructors, 8 registerSafely overloads, 1 terminal of each (9-arg).
- Production never calls new PlatformTracingControl directly.
- Production calls only registerSafely 9-arg via PlatformTracingJmxRegistrar.tryRegisterMBean().
- PlatformAutoConfigurationCustomizer owns PlatformTracingJmxRegistrar; wires via sampler/span/export factories.
- Early MBean registration on setConfigHolder may snapshot null processor refs; export uses lazy suppliers.
- jmx.spike production purge completed; PlatformTracingControl is the real production MBean.

Constructor facts:
- C7 (9-arg) is terminal/canonical.
- C1–C6 are delegation chains with NULL_SUPPLIER defaults for export.
- Test usage: C1 (15×), C2 (1×), C3 (1×), C4 (1×), C5 (8×), C6 (1×), C7 (2× via helper).

registerSafely facts:
- R8 (9-arg) is terminal; registers MBean at PlatformTracingControlMBean.OBJECT_NAME.
- R1 used in 3 tests only.
- R2–R7: zero call-sites in repository.

Production call-sites:
- PlatformTracingJmxRegistrar.java:99-104 (registerSafely R8)

Test call-sites:
- PlatformTracingControlTest (constructors + R1)
- SamplingPolicyRuntimeUpdateJmxTest (C1, C4)
- ScrubbingPolicyRuntimeUpdateJmxTest (C5 ×8)
- ValidationPolicyRuntimeUpdateJmxTest (C7 helper)
- ValidationStrictRuntimeGuardTest (C7 helper)
- PlatformAutoConfigurationCustomizerProcessorsTest (indirect MBean via bootstrap)

Open questions:
- Fix early registration timing separately?
- Remove UNUSED R2–R7 immediately?
- Introduce test factory before removing C1–C6?
- Package-private canonical constructor?
- Group dependencies into context record?
```

---

## 18. Final Status

```text
Inventory status: COMPLETED
Code changes performed: NO
Ready for constructor cleanup architecture decision: YES
```
