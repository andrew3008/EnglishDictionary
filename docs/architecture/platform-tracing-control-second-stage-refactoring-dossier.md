# PlatformTracingControl Second-Stage Refactoring Dossier

> **СТАТУС: IMPLEMENTED — Phase 2 SPLIT_DOMAIN_MBEANS (2026-06-18).**
> Pre-production JMX control-plane reset выполнен по плану `split_domain_jmx_reset`.
> Выбран **агрессивный путь** (не Option A/H из анализа ниже): полное удаление монолита,
> six focused Standard MBeans (Approach A), batch registration с rollback,
> `PlatformTracingJmxClient`, `RuntimeConfigApplier` A+ diagnostics.
> Старый JMX-контракт (`type=Control,name=PlatformTracingControl`) **намеренно сломан**.
> Актуальная архитектура: [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md).
>
> **Тип документа:** аналитическое досье + вход для LLM-моделей (analysis/planning-only).
> **Код Phase 2 реализован.** Ниже — исходный анализ, предшествовавший решению.
> **Дата снимка анализа:** 2026-06-18.
> **Контекст анализа:** после Phase 1 (`WINNER_INTERNAL_DELEGATES`) — делегаты вынесены, но фасад остаётся большим, а operation-классы оказались **public** в подпакете `jmx.operations`.

---

## 1. Executive Summary

**Почему нужен second-stage refactoring**

Phase 1 перенёс доменную логику из `PlatformTracingControl` (~669 строк монолита) в шесть operation-делегатов, но **не решил проблему читаемости фасада**:

- `PlatformTracingControl` по-прежнему содержит **56 `@Override`-методов** (почти полная поверхность `PlatformTracingControlMBean`) в **354 строках** — преимущественно однострочные делегирования и секционные комментарии.
- Operation-делегаты перемещены в подпакет `space.br1440.platform.tracing.otel.extension.jmx.operations` и объявлены **`public final class`** с **`public` методами** — это **расширяет production API surface**, хотя Phase 1 планировал **package-private** internal delegates.
- Документация Phase 1 (`platform-tracing-control-refactoring-dossier.md`, UML, `runtime-policy-control-architecture.md`) **противоречит коду**: там указано «package-private delegates в пакете `jmx`», фактически — **public delegates в `jmx.operations`**.

**Достаточно ли Phase 1**

Нет, для целей архитекторов:

- **Поведение** декомпозировано — да.
- **Инкапсуляция** — **нет** (6 public production-классов без внешних call-site, но доступных любому модулю на classpath).
- **Читаемость фасада** — **частично** (логика ушла, но MBean-поверхность осталась целиком в одном файле).

**Public operation delegates — concern**

Подтверждено grep-ом: **единственный importer** `jmx.operations.*` — `PlatformTracingControl`. Тесты и внешние клиенты **не используют** operation-классы напрямую. Public visibility **не требуется** для тестов или `SamplingControlClient`. Она **требуется Java-правилами видимости пакетов**, потому что `jmx` и `jmx.operations` — **разные пакеты**: `PlatformTracingControl` не может вызывать package-private типы из подпакета.

**MBean contract**

Должен остаться неизменным: `PlatformTracingControlMBean`, `OBJECT_NAME`, имена/сигнатуры операций и атрибутов. Внешний клиент `SamplingControlClient` работает через string-based JMX (`MBeanServer.invoke` / `getAttribute`), **не компилируется** против otel-extension.

**Назначение dossier**

Подготовить **model-ready** факты и опции для выбора second-stage refactoring в Perplexity (Claude Deep Research, Gemini adversarial audit, Kimi feasibility, GPT decision memo).

---

## 2. Current Architecture Snapshot

### 2.1 PlatformTracingControl

| Метрика | Значение (факт) |
|---|---|
| Файл | `platform-tracing-otel-javaagent-extension/src/main/java/.../jmx/PlatformTracingControl.java` |
| Строк | **354** |
| Модификатор | `public final class` |
| Интерфейс | `implements PlatformTracingControlMBean` |
| `@Override` методов | **56** (grep) |
| Конструктор | **package-private**, 9 аргументов (без изменений с boundary cleanup) |
| Поля | `invalidConfigCounter` (shared `LongAdder`) + 6 delegate fields |
| Доменная логика в фасаде | только `getInvalidConfigCount()` → `invalidConfigCounter.sum()`; остальное — делегирование |

**Конструктор (package-private, unchanged):**

```java
PlatformTracingControl(SamplerStateHolder configHolder,
                       CompositeSampler compositeSampler,
                       SpanWatchdogProcessor watchdog,
                       PlatformCompositeSpanProcessor composite,
                       MetricsSpanProcessor metrics,
                       ScrubbingSpanProcessor scrubbing,
                       ValidatingSpanProcessor validating,
                       Supplier<PlatformDropOldestExportSpanProcessor> exportProcessorSupplier,
                       Supplier<SafeSpanExporter> safeExporterSupplier)
```

**Delegate fields:**

```java
private final SamplingControlOperations sampling;
private final ScrubbingControlOperations scrubbingOps;
private final ValidationControlOperations validation;
private final ExportControlOperations export;
private final ProcessorMetricsOperations processorMetrics;
private final DiagnosticsControlOperations diagnostics;
```

**Инстанцирование delegates:** только в конструкторе `PlatformTracingControl` (строки 38–43).

### 2.2 Operation delegates (`jmx.operations`)

| Класс | Строк | Class visibility | Constructor | Methods | Lombok |
|---|---:|---|---|---|---|
| `SamplingControlOperations` | 311 | **public final** | `@RequiredArgsConstructor` → **public** | **public** | `@RequiredArgsConstructor` |
| `ScrubbingControlOperations` | 77 | **public final** | public (Lombok) | **public** | `@RequiredArgsConstructor` |
| `ValidationControlOperations` | 58 | **public final** | public (Lombok) | **public** | `@RequiredArgsConstructor` |
| `ExportControlOperations` | 74 | **public final** | public (Lombok) | **public** | `@RequiredArgsConstructor` |
| `ProcessorMetricsOperations` | 65 | **public final** | public (Lombok) | **public** | `@RequiredArgsConstructor` |
| `DiagnosticsControlOperations` | 60 | **public final** | public (Lombok) | **public** | `@RequiredArgsConstructor` |
| `JmxConfigReloadRecorder` | 12 | **package-private final** | n/a (`@UtilityClass`) | **package-private static** | `@UtilityClass` |

**Суммарно operation-код:** ~657 строк в подпакете + 354 строки фасада ≈ **1011 строк** (vs ~669 до Phase 1).

### 2.3 Package layout

```
space.br1440.platform.tracing.otel.extension.jmx
├── PlatformTracingControl.java          (public, MBean impl)
├── PlatformTracingControlMBean.java    (public interface)
├── PlatformTracingJmxRegistrar.java    (public, registration owner)
└── operations/
    ├── SamplingControlOperations.java   (public)
    ├── ScrubbingControlOperations.java  (public)
    ├── ValidationControlOperations.java (public)
    ├── ExportControlOperations.java     (public)
    ├── ProcessorMetricsOperations.java  (public)
    ├── DiagnosticsControlOperations.java(public)
    └── JmxConfigReloadRecorder.java     (package-private)
```

**Кто импортирует `jmx.operations`:**

| Consumer | Import |
|---|---|
| `PlatformTracingControl` | `import space.br1440.platform.tracing.otel.extension.jmx.operations.*;` |
| *(другие)* | **нет** (grep по репозиторию) |

**Кто создаёт delegates (`new *Operations`):**

| Call-site | Location |
|---|---|
| `PlatformTracingControl` constructor | единственный (6× `new ...`) |

### 2.4 Registration ownership

- **`PlatformTracingJmxRegistrar`** (`jmx` package): единственный production call-site `new PlatformTracingControl(...)` + `MBeanServer.registerMBean`.
- Early-registration timing: **не менялся** (registrar регистрирует при первом `configHolder != null`; export suppliers — late-binding). **Вне скоупа** second stage.

### 2.5 Tests (current)

| Test class | Package | Creates control via | Uses operations directly? |
|---|---|---|---|
| `PlatformTracingControlTest` | `...jmx` | `PlatformTracingControlTestBuilder` | **нет** |
| `SamplingPolicyRuntimeUpdateJmxTest` | `...sampler` | TestBuilder | **нет** |
| `ScrubbingPolicyRuntimeUpdateJmxTest` | `...scrubbing` | TestBuilder | **нет** |
| `ValidationPolicyRuntimeUpdateJmxTest` | `...processor` | TestBuilder | **нет** |
| `ValidationStrictRuntimeGuardTest` | `...processor` | TestBuilder | **нет** |
| `PlatformAutoConfigurationCustomizerProcessorsTest` | `...extension` | MBean / registrar | **нет** |
| `PlatformSpiAutoconfigureIntegrationTest` | `...extension` | MBean contract | **нет** |

**`PlatformTracingControlTestBuilder`:** public, пакет `jmx` — доступ к package-private конструктору `PlatformTracingControl`. **Не** импортирует `jmx.operations`.

---

## 3. Usage Inventory

### 3.1 Production usage

| Artifact | Role | References operations? |
|---|---|---|
| `PlatformTracingControl` | Standard MBean impl, thin facade | **да** (instantiate + delegate) |
| `PlatformTracingJmxRegistrar` | Creates + registers MBean | **нет** (только `PlatformTracingControl`) |
| `PlatformAutoConfigurationCustomizer` | Calls `PlatformTracingJmxRegistrar.tryRegisterMBean()` | **нет** |
| `SamplingControlClient` (spring-boot-autoconfigure) | String-based JMX client | **нет** (другой module/classloader) |
| `RuntimeConfigApplier` | Spring → JMX via client | **нет** |

**Production `new PlatformTracingControl(` call-sites (grep `src/main`):**

1. `PlatformTracingJmxRegistrar.java:139`

### 3.2 Test usage

- Все JMX/policy тесты работают через **`PlatformTracingControl` MBean API** или **`PlatformTracingControlTestBuilder`**.
- **Ноль** test call-sites на `*ControlOperations` (grep `src/test`).
- `SamplingControlClientTest` использует mock `DynamicMBean`, не otel-extension classes.

### 3.3 External JMX usage

**`SamplingControlClient`** (`platform-tracing-spring-boot-autoconfigure`):

- `OBJECT_NAME` = `"space.br1440.platform.tracing:type=Control,name=PlatformTracingControl"` (дублирует константу MBean).
- **Не зависит** от `platform-tracing-otel-javaagent-extension` (javadoc, строки 25–26).
- Вызовы: `server.invoke(objectName, "<operationName>", ...)` и `server.getAttribute(objectName, "<AttributeName>")`.

**Примеры string-based операций/атрибутов (production client):**

| Kind | Names used by client |
|---|---|
| invoke | `getSamplingRatio`, `setSamplingRatio`, `setDropPathPrefixes`, `setForceRecordValues`, `setSamplerEnabled`, `updateSamplingPolicy` (7-arg), `updateScrubbingPolicy` (3-arg), `updateValidationPolicy` (3-arg), `setExportEnabled`, `setPropagationEnabled`, `setPlatformLogLevel`, `getSamplerDecisionCount` |
| getAttribute | `ProcessorErrorsTotal`, `ProcessorErrorsByName`, `SamplerEnabled`, `SamplerDecisionCounts`, `SamplingConfigVersion`, `InvalidConfigCount`, `SamplingConfigLastUpdatedSource`, `DropPathPrefixes`, `ForceRecordValues`, `RouteRatios`, `ScrubbingEnabled`, `ScrubbingConfigVersion`, `ScrubbingConfigLastUpdatedSource`, `ValidationEnabled`, `ValidationStrict`, `ValidationStrictRuntimeAllowed`, `ValidationConfigVersion`, `ValidationConfigLastUpdatedSource`, `ScrubbingMetrics`, `ConfigReloadMetrics`, `ConfigAuditTrail`, `SafeWrapperMetrics`, `ExportQueueCapacity`, `ExportQueueSize`, `ExportDroppedOverflowTotal`, ... `SafeExporterMetrics` |

**Вывод:** внутренняя структура (`jmx` vs `jmx.operations`, public vs package-private delegates) **не видна** внешним JMX-клиентам, пока MBean method/attribute names и behavior неизменны.

### 3.4 Docs and architecture rules

**Документы с упоминанием delegates (выборка):**

| Document | Claim vs reality |
|---|---|
| `platform-tracing-control-refactoring-dossier.md` | IMPLEMENTED banner: «package-private … в `jmx`» → **противоречит коду** (`public` в `jmx.operations`) |
| `platform-tracing-classes.puml` | `<<package-private>>` на operation classes → **устарело** |
| `Components_v1.puml` | `<<package-private delegates>>` → **устарело** |
| `runtime-policy-control-architecture.md` | «package-private JMX operation classes» → **устарело** |

**ArchUnit (`SafeBoundaryArchTest`):**

- `jmx_registrar_resides_in_jmx_package` — **да**, registrar в `jmx`.
- `no_public_registration_helper_in_production` — **да**, helper удалён.
- **Нет правила** «operation delegates must be package-private» или «no public classes in `jmx.operations`».
- **Нет правила** на подпакет `jmx.operations`.

**`ProductionControlPlaneNotMigratedArchTest`** (autoconfigure): проверяет `SamplingControlClient` primitive methods — **не** otel-extension delegates.

---

## 4. Public Delegate Visibility Analysis

| Class | Package | Visibility | Direct call-sites | Used outside PlatformTracingControl? | Why public? | Can be package-private? | Notes |
|---|---|---|---|---|---|---|---|
| `SamplingControlOperations` | `jmx.operations` | **public** class, **public** ctor (Lombok), **public** methods | `PlatformTracingControl:38` | **нет** | Parent package `jmx` ≠ `jmx.operations`; cross-package access requires public API | **да**, if moved to `jmx` package (same package as facade) | 311 lines, largest delegate |
| `ScrubbingControlOperations` | `jmx.operations` | **public** | `PlatformTracingControl:39` | **нет** | Same Java package rule | **да**, if in `jmx` | |
| `ValidationControlOperations` | `jmx.operations` | **public** | `PlatformTracingControl:40` | **нет** | Same | **да**, if in `jmx` | |
| `ExportControlOperations` | `jmx.operations` | **public** | `PlatformTracingControl:41` | **нет** | Same | **да**, if in `jmx` | Late-binding preserved |
| `ProcessorMetricsOperations` | `jmx.operations` | **public** | `PlatformTracingControl:42` | **нет** | Same | **да**, if in `jmx` | Read-only |
| `DiagnosticsControlOperations` | `jmx.operations` | **public** | `PlatformTracingControl:43` | **нет** | Same | **да**, if in `jmx` | Uses static singletons |
| `JmxConfigReloadRecorder` | `jmx.operations` | **package-private** | Only inside `jmx.operations` delegates | **нет** (external) | Same-package callers only | Already package-private | Correct encapsulation **within** subpackage |

### Root cause (evidence-based)

**Java package-private visibility is per package name, not directory tree.**

- `space.br1440.platform.tracing.otel.extension.jmx` and `space.br1440.platform.tracing.otel.extension.jmx.operations` are **different packages**.
- `PlatformTracingControl` (in `jmx`) **cannot** access package-private types in `jmx.operations`.
- Therefore delegates were made **`public final class`** with **`public` methods** (and Lombok `@RequiredArgsConstructor` → **public constructor**).

**Tests did NOT force public visibility** — no test imports or instantiates `*Operations`.

**Production did NOT require public visibility for external consumers** — only for cross-package call from `PlatformTracingControl`.

**Hypothesis (UNKNOWN without git blame):** subpackage `jmx.operations` was introduced for «organization» or IDE structure, inadvertently breaking Phase 1 encapsulation goal. **Fact:** current code state matches subpackage → public pattern.

---

## 5. Why PlatformTracingControl Is Still Hard To Read

### Unavoidable reasons (facts)

1. **`PlatformTracingControlMBean` defines ~56 operations** in one interface (376 строк). Standard MBean registration requires a concrete implementation class exposing **all** operations/attributes JMX clients expect.
2. **External contract is flat** — one ObjectName, one MBean; clients (`SamplingControlClient`) address a single surface by string names.
3. **Java has no multi-interface MBean composition** at registration time without changing registration model (e.g. `DynamicMBean`, split ObjectNames — out of scope / risky).

### Accidental reasons (facts + inference)

1. **56 one-line `@Override` methods** remain in `PlatformTracingControl` after extraction — file shrank (~669→354) but **still lists entire MBean surface**.
2. **Section comments** (`// -- Sampling policy control --`) substitute for structural abstraction — readability gain limited.
3. **Delegates placed in subpackage `jmx.operations`**, forcing **public** types — expands API surface without functional benefit.
4. **Documentation claims package-private** — misleads reviewers into thinking encapsulation goal was met.

### Solvable reasons (judgement, evidence-backed)

1. **Move delegates to `jmx` package** → restore package-private visibility (Option A); low risk, no behavior change.
2. **Introduce single aggregate** (`PlatformTracingControlOperations` / control plane) → facade holds **one field**, aggregate owns six domains (Option E/H); reduces field noise, may allow grouping MBean forwarding.
3. **Nested package-private section classes** inside `PlatformTracingControl` or package-private holder (Option D) — eliminates public subpackage entirely.
4. **Grouped internal interfaces** (Option B) — decouple facade from concrete delegate classes; may improve test seam **if** needed (currently tests use MBean API only).

### Risky-to-solve reasons

1. **Eliminating 56 forwarding methods entirely** without codegen, split MBean, or `DynamicMBean` — likely **impossible** while keeping current Standard MBean + single interface contract.
2. **Split MBean / multiple ObjectNames** (Option G) — breaks `SamplingControlClient` and deployment tooling; high external cost.
3. **Early-registration timing** — separate concern; fixing it during readability refactor increases blast radius.

---

## 6. Package Boundary Findings

### Why are operation delegates in `jmx.operations`?

**Fact:** subdirectory `jmx/operations/` maps to Java package `...jmx.operations`.

**UNKNOWN:** explicit design decision record for subpackage introduction (no ADR found in grep). **Inference:** likely organizational separation of «MBean surface» vs «operation impl».

### Is the subpackage beneficial?

| Pro | Con |
|---|---|
| Visual separation in IDE/file tree | **Forces public** delegate classes |
| Smaller `jmx` directory listing | **7 extra public types** on module API surface |
| | **Contradicts Phase 1 goal** (internal, non-public) |
| | **No external consumer** benefits from separate package |

**Assessment:** subpackage is **structurally convenient** but **architecturally costly** given Java visibility rules.

### Does the subpackage force public visibility?

**Yes — proven.** Only alternative while keeping subpackage:

- public classes (current state), or
- reflection (forbidden by project rules), or
- move factory/assembly into `jmx.operations` (would break registrar’s direct `new PlatformTracingControl` in `jmx` unless registrar also moves).

### Would moving delegates into `jmx` allow package-private?

**Yes.** Same pattern as `PlatformTracingControl` constructor + `PlatformTracingJmxRegistrar`:

- `PlatformTracingControl` — package-private ctor, accessed from same package registrar and test builder.
- Delegates as `final class` (no modifier) in `jmx` → accessible from `PlatformTracingControl`, **not** from `sampler`, `processor`, etc.

### Would moving them back increase package clutter?

**Fact:** `jmx` would gain 7 files (6 delegates + recorder) → **~13 Java files** in one package (control, MBean, registrar, builder tests in test tree).

**Trade-off:** package clutter vs **correct encapsulation**. Phase 1 dossier already anticipated delegates living in `jmx` package.

### Package-private tests in same package?

**Yes:** `PlatformTracingControlTest` and `PlatformTracingControlTestBuilder` live in `...jmx` test source — can access package-private `PlatformTracingControl` ctor. They **do not need** access to delegates directly today.

### ArchUnit impact

- Moving delegates `jmx.operations` → `jmx`: **no existing rule breakage** (no rule references `operations` subpackage).
- **Opportunity:** add ArchUnit rule `no public classes named *Operations in jmx` (future PR, not this dossier).

---

## 7. MBean Contract Constraints

### Must remain stable

| Item | Value / rule |
|---|---|
| `PlatformTracingControlMBean` | Interface unchanged (names, signatures, return types) |
| `OBJECT_NAME` | `"space.br1440.platform.tracing:type=Control,name=PlatformTracingControl"` |
| Standard MBean attribute names | Derived from JavaBean conventions on impl methods (e.g. `getSamplingRatio` → `SamplingRatio`) |
| Operation names | e.g. `updateSamplingPolicy`, `setExportEnabled`, … |
| Null-tolerant reads, mutation exceptions | Behavior invariants from Phase 1 |
| `invalidConfigCounter` semantics | Shared counter |
| Export supplier late-binding | Suppliers resolved per-method, not in ctor |
| Early-registration timing | Unchanged in second stage |

### Can change internally (safe if MBean behavior identical)

- Delegate package (`jmx` vs `jmx.operations`)
- Delegate visibility (public → package-private)
- Number of internal classes (6 → 1 aggregate + 6 nested)
- Forwarding structure (manual methods vs grouped helpers **inside** impl, not on MBean interface)
- Logger class names on delegates (already differ from pre-Phase-1)

### Standard MBean constraints

- `PlatformTracingControl` must remain the **registered object** implementing `PlatformTracingControlMBean` (or subclass that StandardMBean wraps — current code registers impl directly).
- **All interface methods** must be implemented on the registered class **unless** using inheritance/delegation patterns that still expose methods on the registered instance.
- **Cannot** remove forwarding methods from `PlatformTracingControl` without:
  - changing the registered class hierarchy (abstract base — Option C), or
  - codegen (Option F), or
  - non-Standard MBean model (risky).

### External clients

- **`SamplingControlClient`**: primary cross-classloader consumer; string names only.
- **Actuator / RuntimeConfigApplier**: indirect via client.
- **Ops / JConsole**: human JMX explorers — same ObjectName.

---

## 8. Candidate Refactoring Options For LLM Review

### Option A — Move operation delegates from `jmx.operations` to `jmx` package

**Concept:** Keep six delegates + recorder; change package to `...jmx`; make classes **package-private** (`final class X`).

**Target shape:** Same delegation; `PlatformTracingControl` imports removed (same package).

**Changes:** Move/rename package; drop `public` from classes/methods/constructors; update docs/UML.

**Remains:** 56 forwarding methods in facade; MBean contract.

**Benefits:** Restores Phase 1 encapsulation intent; **minimal diff**; no behavior change; ArchUnit-friendly rule possible.

**Risks:** Larger flat `jmx` package; IDE navigation slightly busier.

**Stop conditions:** Any test starts importing delegates from other packages (none today).

---

### Option B — Grouped interface delegates

**Concept:** Package-private interfaces `SamplingControlView`, `ExportControlView`, … implemented by current delegates; facade depends on interfaces.

**Target shape:** `PlatformTracingControl(SamplingControlView sampling, ...)` or factory in same package.

**Changes:** +6 interfaces; wiring/factory in `jmx`.

**Remains:** MBean surface on facade.

**Benefits:** Test seams; clearer dependency direction.

**Risks:** **More types** without reducing facade line count; may look «enterprisey» for zero external consumers of interfaces.

**Stop conditions:** Public interfaces leak outside `jmx`.

---

### Option C — Abstract base MBean facade

**Concept:** `abstract class PlatformTracingControlBase implements PlatformTracingControlMBean` with grouped default forwarding sections; concrete `PlatformTracingControl` extends base.

**Changes:** Class hierarchy + registration still uses concrete type.

**Benefits:** Could group methods in base by domain.

**Risks:** **Java single inheritance** — locks out other bases; Standard MBean + inheritance can confuse; **does not reduce total method count**, only moves it; registrar/tests target concrete class.

**Stop conditions:** Registration or ctor visibility breaks.

---

### Option D — Composition with nested static section classes

**Concept:** `private static final class SamplingSection` inside `PlatformTracingControl` (or package-private `PlatformTracingControlSections` holder).

**Changes:** Inline or move delegate logic to nested types.

**Benefits:** **Strong encapsulation** — no public subpackage; all internal.

**Risks:** Large outer file if nested inline; or new holder file still needs visibility planning.

**Stop conditions:** File size returns toward pre-Phase-1 monolith if inlined carelessly.

---

### Option E — MBean adapter + domain control plane

**Concept:** Introduce package-private `PlatformTracingControlPlane` / `PlatformTracingControlService` owning all domains; MBean facade delegates **one object**.

**Target shape:**

```java
public final class PlatformTracingControl implements PlatformTracingControlMBean {
    private final PlatformTracingControlPlane plane;
    // 56 methods → plane.sampling().getRatio() still needs 56 forwards OR plane exposes MBean-shaped API
}
```

**Changes:** New aggregate; migrate ctor wiring.

**Benefits:** Single dependency in facade field list; clearer «adapter vs domain» split.

**Risks:** Unless `plane` mirrors MBean 1:1, **still 56 forwards**; aggregate API design overhead.

**Stop conditions:** Aggregate becomes second public API.

---

### Option F — Generated or annotation-driven forwarding

**Concept:** Annotation processor / codegen emits `@Override` methods from `PlatformTracingControlMBean`.

**Benefits:** Removes manual forwarding boilerplate.

**Risks:** **No existing tooling** in repo (grep: no codegen for MBean); build complexity; architect skepticism («cosmetic codegen»).

**Recommendation:** **Likely reject** unless team already wants annotation processor infrastructure.

---

### Option G — Split MBean into multiple ObjectNames

**Concept:** Separate MBeans per domain (sampling, scrubbing, export, …).

**Changes:** New ObjectNames, client updates, docs, ops runbooks.

**Benefits:** Smaller per-class surface.

**Risks:** **Breaking JMX contract**; `SamplingControlClient` rewrite; multi-MBean registration ordering; **explicitly risky/future-only**.

**Stop conditions:** Any production client still assumes single ObjectName without migration plan.

---

### Option H — Hybrid (recommended direction for review)

**Concept:** **(A)** move delegates to `jmx` as package-private **+** **(E-lite)** introduce package-private aggregate `PlatformTracingControlOperations` that owns six domain delegates and exposes domain-grouped methods; `PlatformTracingControl` holds **one field** `operations` but **still implements 56 MBean methods** (forwards to `operations.sampling()...` or flat delegate methods on aggregate).

**Changes:** Package move + one new internal class; optional slimmer facade field block.

**Benefits:** Fixes encapsulation **and** reduces facade field noise; non-cosmetic boundary cleanup.

**Risks:** Medium implementation size; must not create public aggregate.

**Stop conditions:** Public `PlatformTracingControlOperations`; weakened tests.

---

### Option I (evidence-suggested) — Package-private factory in `jmx`

**Concept:** `PlatformTracingControlFactory` (package-private) assembles control + delegates; keeps `PlatformTracingControl` ctor minimal.

**Benefits:** Moves wiring noise out of MBean class.

**Risks:** Extra type; **does not remove 56 `@Override` methods** from MBean class unless combined with other options.

---

## 9. Preliminary Option Scoring

*Preliminary, not final decision. Scale 1–10 (higher = better).*

| Option | Readability | Encapsulation | Behavior Safety | JMX Safety | Implementation Risk | Architecture Value | Overall |
|---|---:|---:|---:|---:|---:|---:|---:|
| A — move to `jmx`, package-private | 5 | **9** | 9 | 10 | **9** | 7 | **8** |
| B — grouped interfaces | 6 | 7 | 8 | 10 | 7 | 6 | 6 |
| C — abstract base | 6 | 6 | 7 | 9 | 5 | 4 | 5 |
| D — nested sections | 7 | **9** | 8 | 10 | 6 | 7 | 7 |
| E — control plane aggregate | 7 | 8 | 8 | 10 | 6 | **8** | 7 |
| F — codegen | 8 | 7 | 7 | 9 | 3 | 5 | 5 |
| G — split MBean | 8 | 6 | 6 | **3** | 4 | 6 | 4 |
| H — hybrid A+E | **8** | **9** | 9 | 10 | 7 | **9** | **9** |
| I — package-private factory | 5 | 8 | 9 | 10 | 8 | 6 | 7 |

**Preliminary best candidates:** **H** (hybrid), then **A** (quick encapsulation fix), then **D** (if team prefers no extra top-level classes).

---

## 10. Recommended Questions For Perplexity Models

1. Is **public `jmx.operations`** acceptable as a long-term production API surface given **zero external call-sites**, or is it an encapsulation defect mandated only by subpackage choice?
2. Should operation delegates **move back to `jmx` package** as package-private (Option A) before any further abstraction?
3. Should a **single aggregate** `PlatformTracingControlOperations` (Option H) be introduced so the MBean facade depends on one internal object?
4. Will **56 one-line `@Override` methods** remain acceptable to architects if logic is extracted but MBean contract is fixed — or is codegen/split MBean required for «non-cosmetic» approval?
5. Do **grouped interfaces** (Option B) add value when tests already use MBean API / TestBuilder only?
6. Is **nested class composition** (Option D) cleaner than flat package-private delegates in `jmx`?
7. Should **split MBean** (Option G) be rejected outright for this product phase (not in production, but client/tooling coupling exists)?
8. What **ArchUnit rules** should accompany second stage (e.g. forbid `public *Operations` in otel-extension)?
9. How to **sync stale docs/UML** that still claim package-private delegates?
10. What target will architects accept as **non-cosmetic**: encapsulation fix only (A), or structural control-plane layer (E/H)?

---

## 11. Claude / Deep Research Input Block

```text
PROJECT: OpenTelemetry Java Agent extension — PlatformTracingControl second-stage refactor (analysis only).

CURRENT FACTS:
- PlatformTracingControl: 354 lines, 56 @Override methods, public final, implements PlatformTracingControlMBean unchanged.
- Phase 1 extracted logic to 6 delegates in package jmx.operations (~657 LOC total).
- ALL 6 delegate classes are public final with public methods; Lombok @RequiredArgsConstructor → public constructors.
- ONLY consumer of jmx.operations is PlatformTracingControl (import operations.*; new X in ctor lines 38-43).
- NO test references *Operations classes; tests use PlatformTracingControlTestBuilder + MBean API.
- PlatformTracingJmxRegistrar (jmx) sole production instantiator of PlatformTracingControl.
- SamplingControlClient (Spring module) uses string JMX invoke/getAttribute; does NOT compile against otel-extension.
- OBJECT_NAME fixed: space.br1440.platform.tracing:type=Control,name=PlatformTracingControl.

PROBLEM:
- Facade still lists full MBean surface (56 one-line delegates).
- Subpackage jmx.operations forced public delegates (Java cross-package visibility); contradicts Phase 1 encapsulation goal and stale docs claiming package-private.

CONSTRAINTS:
- Do NOT change PlatformTracingControlMBean, OBJECT_NAME, operation/attribute names/signatures.
- Do NOT split MBean without explicit migration (client coupling).
- Do NOT fix early-registration timing in this PR scope.
- Backward compat not required for internal code, but JMX contract is sacred.
- No reflection; no new public helper APIs without architect approval.

CANDIDATE OPTIONS:
A: Move delegates to jmx package, package-private.
H: A + package-private aggregate PlatformTracingControlOperations.
D: Nested package-private section classes.
E: Control plane aggregate; facade delegates to one object.
G: Split MBean (risky, likely reject).

NON-GOALS: cosmetic rename only; weakening tests; public operation surface retention without justification.

RISKS: docs/UML drift; ArchUnit gaps; architect rejection if 56 forwards remain without encapsulation fix.

VALIDATION: same targeted JMX tests as Phase 1; grep no public *Operations; pr4ArchitectureFitnessVerify; optional new ArchUnit rule.

ASK: Recommend second-stage target architecture with evidence-weighted trade-offs (encapsulation vs readability vs implementation cost).
```

---

## 12. Gemini / Adversarial Audit Input Block

```text
ADVERSARIAL AUDIT TARGET: PlatformTracingControl post-Phase-1 delegate extraction.

ATTACK SURFACE:
- 6 public production classes (*ControlOperations) in jmx.operations with ZERO external call-sites — are they accidental public API?
- Cross-package visibility: jmx vs jmx.operations REQUIRES public types for PlatformTracingControl to compile.
- JmxConfigReloadRecorder correctly package-private WITHIN jmx.operations — proves team knows how to hide internals when same package.

FIND CONTRADICTIONS:
- platform-tracing-control-refactoring-dossier.md IMPLEMENTED banner says package-private delegates in jmx — CODE says public in jmx.operations.
- UML (platform-tracing-classes.puml, Components_v1.puml) marks delegates package-private — FALSE vs repo.
- runtime-policy-control-architecture.md claims package-private operation classes — FALSE.

CHALLENGE OPTIONS:
- Option A (move to jmx): does package clutter (~13 files) re-introduce maintainability issues?
- Option H (aggregate): does PlatformTracingControlOperations become a god-object?
- Option G (split MBean): enumerate ALL string JMX names SamplingControlClient depends on — estimate migration blast radius.
- Option F (codegen): reject as YAGNI?

HARD RULES TO VERIFY IN ANY PROPOSAL:
- MBean contract frozen; no weakened tests; no public registration helpers; invalidConfigCounter shared; export late-binding preserved.

DELIVERABLE: Pass/fail on whether Phase 1 should be considered "complete", list blocking defects, rank options by adversarial robustness.
```

---

## 13. Kimi / Feasibility Audit Input Block

```text
FEASIBILITY: PlatformTracingControl second-stage (Java, Gradle multi-module, ArchUnit).

EFFORT ESTIMATES (S/M/L):
- Option A (move 7 files jmx.operations→jmx, drop public): S — mechanical, low test churn.
- Option H (A + aggregate class, rewire ctor, 56 forwards stay): M — one new class, refactor ctor/facade fields.
- Option D (nested classes): M-L — large file or split nested types, careful merge conflicts.
- Option B (interfaces): M — +6 interfaces, factory wiring.
- Option G (split MBean): XL — SamplingControlClient + docs + ops — REJECT short-term.

DEPENDENCIES:
- PlatformTracingControlTestBuilder in test jmx package — works with package-private control ctor; no delegate access needed.
- SafeBoundaryArchTest — no change required for Option A/H.
- No Gradle/module dependency changes expected.

ROLLBACK: Option A is easily revertible (package move only).

TEST PLAN (must pass unchanged assertions):
- PlatformTracingControlTest, *PolicyRuntimeUpdateJmxTest, ValidationStrictRuntimeGuardTest,
  PlatformAutoConfigurationCustomizerProcessorsTest, PlatformSpiAutoconfigureIntegrationTest
- pr4ArchitectureFitnessVerify

GREP GATES:
- rg "public .*Operations" jmx → expect zero after Option A/H
- rg "jmx\.operations" → expect zero or test-only after move

BLOCKERS: NONE identified for Option A. Option H blocked only if aggregate must be public (must stay package-private).

RECOMMEND: Implement A first (1 PR), then H (2 PR) if architects want fewer facade fields — feasibility HIGH.
```

---

## 14. GPT / Final Decision Memo Input Block

```text
DECISION MEMO REQUEST: PlatformTracingControl second-stage refactoring direction.

SITUATION:
Phase 1 delegate extraction succeeded logically but failed encapsulation: 6 public *Operations classes in subpackage jmx.operations; facade still 354 lines / 56 MBean forwards. Docs incorrectly claim package-private.

DECISION CRITERIA (weighted):
1) JMX contract safety (must not break SamplingControlClient string names) — weight HIGH
2) Encapsulation (no public internal operation API) — weight HIGH
3) Architect perception (non-cosmetic structural improvement) — weight MEDIUM
4) Implementation risk / diff size — weight MEDIUM
5) Facade line count reduction — weight LOW (may be impossible without codegen or split MBean)

RECOMMENDED DEFAULT (pending multi-model review):
- PRIMARY: Option H (hybrid) — Phase 2a: Option A immediately (package-private restore); Phase 2b: optional package-private aggregate to simplify facade wiring.
- REJECT for now: Option G split MBean, Option F codegen.
- DEFER: early-registration timing.

SUCCESS METRICS:
- Zero public *Operations classes in production src/main.
- PlatformTracingControl remains sole public MBean impl + unchanged MBean interface.
- All Phase 1 behavioral tests green; optional ArchUnit rule added.

ESCALATION: If architects demand <200 lines in PlatformTracingControl, escalate to codegen (F) or split MBean (G) with explicit client migration program — do not pretend nested delegates alone solve forwarder boilerplate.

OUTPUT NEEDED: Single chosen option + 2-sentence rationale + PR sequencing (1 or 2 PRs).
```

---

## 15. Stop Conditions For Future Implementation

Stop and escalate if:

- `PlatformTracingControlMBean` must change to proceed.
- `OBJECT_NAME` must change.
- Operation/attribute **names or signatures** must change.
- Operation delegates **remain public** without explicit architect approval and documented rationale.
- Tests are **weakened** (assertions reduced) to pass refactor.
- **Public helper/registration API** reappears (e.g. `PlatformTracingControlRegistration`).
- **Early-registration timing** changes unintentionally during readability refactor.
- **ArchUnit rules break** without justification or rule update.
- **Circular dependency** between `jmx`, `factory`, and new aggregate types.
- **Split MBean** proposed without full `SamplingControlClient` migration inventory.

---

## 16. Validation Expectations For Future PR

### Tests (Gradle)

```bash
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*PlatformTracingControlTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*SamplingPolicyRuntimeUpdateJmxTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*ScrubbingPolicyRuntimeUpdateJmxTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*ValidationPolicyRuntimeUpdateJmxTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*ValidationStrictRuntimeGuardTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*PlatformAutoConfigurationCustomizerProcessorsTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*PlatformSpiAutoconfigureIntegrationTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --continue
./gradlew pr4ArchitectureFitnessVerify --continue
```

### Grep checks (expected after Option A/H)

```bash
# No public operation delegates
rg "public final class .*Operations|public class .*Operations" platform-tracing-otel-javaagent-extension/src/main/java

# MBean contract anchors unchanged
rg "interface PlatformTracingControlMBean|OBJECT_NAME" platform-tracing-otel-javaagent-extension/src/main/java/.../jmx

# Registration ownership unchanged
rg "registerMBean|new PlatformTracingControl" platform-tracing-otel-javaagent-extension/src/main/java

# Subpackage removed (if Option A/H)
rg "jmx\.operations" platform-tracing-otel-javaagent-extension/src/main/java
```

### Docs sync (required) — ✅ DONE 2026-06-18

- `platform-tracing-control-refactoring-dossier.md` — Phase 2 IMPLEMENTED banner; Phase 1 marked superseded.
- `platform-tracing-control-second-stage-refactoring-dossier.md` — Phase 2 IMPLEMENTED banner; final status updated.
- `platform-tracing-classes.puml`, `Components_v1.puml` — six domain MBeans, `PlatformTracingJmxClient`, `RuntimeConfigApplier`.
- `runtime-policy-control-architecture.md` — six-domain JMX, client availability, applier `[REJECTED]`/`[PARTIAL]`, batch registration.

---

## 17. Final Status

```text
Second-stage refactoring dossier status: COMPLETED
Phase 2 implementation: DONE (SPLIT_DOMAIN_MBEANS, 2026-06-18)
  Chosen path: aggressive six-domain reset (not Option A/H from analysis)
  Deleted: PlatformTracingControl, PlatformTracingControlMBean, jmx.operations, SamplingControlClient
  Created: 6 domain MBean pairs, PlatformTracingJmxClient, ConfigApplyResult, batch registrar
  Tests: otel-extension + autoconfigure green; pr4ArchitectureFitnessVerify green
  Docs synced: runtime-policy-control-architecture.md, platform-tracing-classes.puml, Components_v1.puml
Historical analysis below preserved for audit trail.
```
