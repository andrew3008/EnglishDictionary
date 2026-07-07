# План рефакторинга: Tracing Control Protocol

> Целевой файл: `docs/analysis/tracing-control-protocol-refactoring-plan.md`  
> Режим: только планирование. Production-код не редактируется, файлы не перемещаются, пакеты не переименовываются, патчи не генерируются на этом шаге.

> **Статус ревью:** APPROVE — раунды 1–3 завершены, plan готов к implementation prompt.

> **Раунд 1 (architect review), внесено:**
> 1. `INVALID_VALUE` обязателен; malformed vs unsupported version различаются.
> 2. Enum `TracingControlProtocolOperation` + `requiredKeysFor(operation)`.
> 3. Transitional bridge только до Phase 5.
> 4. Scope acceptance-grep (live vs historical docs).
> Subpackages: `version` / `schema` / `validation` / `result`.

> **Раунд 2 (Sonnet 5 + ChatGPT), внесено:**
> 1. **Главная находка (Java visibility):** subpackage split форсил `ControlWireProtocolRegistry` в `public` (нет cross-subpackage package-private), создавая второй параллельный публичный вход `registry.current().validator()` вопреки Q8. Исправлено вариантом (b): registry свёрнут в **private static internals** `TracingControlProtocol`; отдельного public/top-level типа `ControlWireProtocolRegistry` больше нет. Единственный public entrypoint — `TracingControlProtocol`.
> 2. `INVALID_VALUE` **явно ограничен** parsing/coercion `contractVersion`; для всех прочих known полей несовпадение Java-типа = `TYPE_MISMATCH`.
> 3. Phase 3 DoD усилен: явный маппинг operation → старый required-set в equivalence-тесте (gate на merge).
>
> **Критическая заметка (не из ревью):** после выноса registry `.version` subpackage содержит единственный класс `ControlWireProtocolVersion`. Это допустимый минорный tradeoff (место под будущие version-типы); альтернатива — поднять `ControlWireProtocolVersion` в корень `...protocol`. По умолчанию оставляем `.version` (минимальный churn), см. §18.

> **Раунд ревью 3 (External Opus), внесено:**
> 1. **RF-001:** зафиксированы semantics и caching `TracingControlProtocol.current()` — singleton-per-major, identity test (§7).
> 2. **RF-002:** именован transitional bridge `WireV1Bridge` (package-private, Phases 2–4 only, удаляется Phase 5) (§11 Phase 2/5, §4).
> 3. **RF-003:** перечислены equivalence fixtures и assertions для Phase 3 gate (§11 Phase 3, §13).
> 4. **RF-004:** duplicate-key policy → `UNKNOWN_KEY`; 7-й код не вводится (§9).
> 5. **RF-006:** null/empty payload contract для validator (§9).
> 6. **RF-005 (non-blocking):** rationale operation-aware requiredness закрыт (§8, §18).

## 1. Executive Summary

Пакет `space.br1440.platform.tracing.api.control.wire` — это **pre-production, JDK-only wire-контракт control plane на `Map<String,Object>`** с **7 production-типами**, **0 внешних production consumers** и **без runtime-зависимости от MBean**. Поскольку пакет ещё никто не потребляет и breaking changes разрешены, сейчас — правильный момент для **структурной** (не косметической) чистки.

**Цель:** переименовать пакет в `api.control.protocol`; заменить `TracingControlWireContractVersion` (включая публичную опечатку `CURRENT_VERSON_STRING` и `CURRENT_VERSION_NUMBER`) на `record ControlWireProtocolVersion(int major)`; ввести aggregate `TracingControlProtocol` как **единственный** public entrypoint (тонкий immutable registry — private static internals внутри него, не отдельный public тип); сделать schema descriptor-driven с **operation-aware requiredness** (typed `TracingControlProtocolOperation`); добавить enum `WireViolationCode` из 6 значений (вкл. обязательный `INVALID_VALUE`); удалить оба public singleton `V1`. Сохранить boundary `Map<String,Object>` и strict unknown-key rejection. Без shims, без `@Deprecated`, без typed DTO, без OpenMBean-логики внутри API-модуля.

Это redesign на value object + aggregate + registry (набор решений Sonnet pass-4), **а не** вариант Gemini с enum/strict-Integer — он явно отклонён ниже по зафиксированным ограничениям.

## 2. Input Evidence

Прочитано и сведено:
- `docs/analysis/tracing-control-wire-package-inventory.md` (702 строки) — основной inventory.
- Архивные исследования: `Control_Wire_Protocol_1/DeepResearch_проход_1.md`, `Sonnet_проход_2.md`, `Sonnet_проход_3.md`, `Sonnet_Ответы на открытые вопросы_4.md`, `Gemini_проход_4.md`.
- Исходники: `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/control/wire/*` (7 типов).
- Build: `platform-tracing-e2e-tests/build.gradle` (комментарий testJmxWireExtensionJar ссылается на `TracingControlWireValidator.V1`), inventory Gradle-задач root/module.

Проверенные факты inventory (все TRUE, если не указано иное):
- 7 production-типов в `api.control.wire`. TRUE.
- 0 внешних production consumers (`src/main` вне пакета). TRUE.
- Runtime MBeans не используют пакет напрямую. TRUE.
- JDK-only, payload `Map<String,Object>`. TRUE.
- `CURRENT_VERSION_NUMBER = 1`; опечатка `CURRENT_VERSON_STRING = "1"`; `isSupported(Object)` принимает `Integer`/`Long`/`String` (trimmed). TRUE.
- Schema объявляет `contractVersion` как `INTEGER`; validator нормализует принятую версию в canonical `Integer`. TRUE.
- `TracingControlWireSchema.V1` и `TracingControlWireValidator.V1` — hardcoded public singletons. TRUE.
- Нет version registry/range/value object; нет `TracingControlWireContractVersionTest`. TRUE.
- Strict unknown-key rejection; non-throwing result; free-form English violation reasons без stable codes. TRUE.
- **NEEDS_VERIFICATION (уточнённый факт):** `requiredKeysForRuntimeApply()` и `requiredKeysForReadRequest()` сейчас возвращают **идентичные** множества `{contractVersion, operation}` (`TracingControlWireSchema.java:90-101`). **Текущего расхождения по операциям нет.** Operation-aware requiredness — это future-proofing (зафиксированное решение), а не исправление существующего расхождения.
- **NEEDS_VERIFICATION (build tooling):** Gradle-задачи `archTest` не существует. Реальные задачи: root `pr4ArchitectureFitnessVerify`, `pr1ModuleTaxonomyVerify`; ArchUnit-тесты выполняются в `:platform-tracing-api:test`.

## 3. Final Architecture Decisions (зафиксированы; не переоткрываются)

- **Package:** `api.control.wire` → `api.control.protocol`.
- **Version:** `record ControlWireProtocolVersion(int major)` в `...protocol.version`. **Не enum.**
- **Coercion:** сохранить inbound `Integer`/`Long`/`String` (trimmed); canonical `int`/`Integer`; normalized payload output `Integer`; parsing в `ControlWireProtocolVersion.parse(Object) : Optional<...>` (без exceptions). **Не Integer-only.**
- **Registry:** тонкая immutable static реализация **внутри** `TracingControlProtocol` (private nested `Registry`); **НЕ** public/top-level тип. Capabilities registry (`current/minSupported/maxSupported/find/isSupported`) экспонируются только как static-методы `TracingControlProtocol`. Без dynamic registration. (Исправление раунда 2: отдельный public `ControlWireProtocolRegistry` создавал второй параллельный вход в API — отклонён.)
- **Aggregate:** `TracingControlProtocol` — **единственный public entrypoint**. Instance API: `version()`, `schema()`, `validator()`. Static API: `current()`, `find(ControlWireProtocolVersion)`, `isSupported(ControlWireProtocolVersion)`, `minSupportedVersion()`, `maxSupportedVersion()`. Заменяет `Schema.V1`/`Validator.V1`.
- **Schema:** descriptor-first (light). Переименовать `Schema/Types/Keys` в `TracingControlProtocolSchema/Types/Keys`; вынести `ControlWireFieldCategory` и `ControlWireFieldDescriptor` на top-level с **operation-aware requiredness** через typed `Set<TracingControlProtocolOperation>` (не `Set<String>` — операция часть protocol, а не произвольная строка). Ввести enum `TracingControlProtocolOperation` и public API `requiredKeysFor(TracingControlProtocolOperation)`. Сохранить public String key constants. Без domain-specific schemas.
- **Validator:** переименовать в `TracingControlProtocolValidator` в `...protocol.validation`; доступ только через `TracingControlProtocol.current().validator()`; удалить public `V1`. Сохранить strict unknown-key rejection, non-throwing result, normalized payload, текущую type validation semantics.
- **Violations:** добавить обязательный enum `WireViolationCode` из **6 значений** (`UNSUPPORTED_VERSION`, `INVALID_VALUE`, `UNKNOWN_KEY`, `MISSING_REQUIRED_KEY`, `TYPE_MISMATCH`, `OPERATION_NOT_ALLOWED`). `INVALID_VALUE` **обязателен**: он разделяет malformed/unparseable version (`null`, blank, `"abc"`, произвольный `Object`) и valid-but-unsupported version (`2`, `99` → `UNSUPPORTED_VERSION`). Переименовать result/violation в `TracingControlValidationResult`/`TracingControlViolation`; violation получает поле `WireViolationCode code`; free-text `reason` остаётся non-contractual.
- **Boundary:** сохранить `Map<String,Object>`; без typed DTO; без OpenMBean/CompositeData/TabularData в API-модуле (будущий adapter — в JMX layer `platform-tracing-otel-extension`, out of scope).

**Отклонено (Gemini pass-4):** enum version model и strict Integer-only coercion — противоречат зафиксированным ограничениям и не продиктованы code evidence (`isSupported` уже допускает Long/String, покрыто e2e).

## 4. Target Package and Class Tree

```text
space.br1440.platform.tracing.api.control.protocol/
├── TracingControlProtocol.java                 // единственный public entrypoint;
│                                                //   aggregate (instance: version/schema/validator)
│                                                //   + static: current/find/isSupported/min/maxSupportedVersion
│                                                //   + private static nested Registry (internal)
├── version/
│   └── ControlWireProtocolVersion.java         // record(int major) + parse(Object):Optional
├── schema/
│   ├── TracingControlProtocolKeys.java         // public String constants (renamed)
│   ├── TracingControlProtocolTypes.java        // wire type enum (renamed)
│   ├── TracingControlProtocolOperation.java    // enum: APPLY/VALIDATE runtime, READ applied/schema
│   ├── ControlWireFieldCategory.java           // promoted from nested enum
│   ├── ControlWireFieldDescriptor.java         // promoted record; operation-aware required
│   └── TracingControlProtocolSchema.java       // descriptor-driven (renamed); requiredKeysFor(op)
├── validation/
│   ├── TracingControlProtocolValidator.java    // renamed; no V1
│   └── WireViolationCode.java                   // 6-value enum (incl. INVALID_VALUE)
└── result/
    ├── TracingControlValidationResult.java     // renamed
    └── TracingControlViolation.java            // renamed; + WireViolationCode code

// Transitional only (Phases 2–4, удаляется Phase 5):
internal/legacy/
    └── WireV1Bridge.java                       // package-private; единственный потребитель Schema.V1/Validator.V1
```

**Subpackages (зафиксировано):** финальная структура использует четыре subpackages — `version`, `schema`, `validation`, `result`. После рефакторинга пакет ~11 типов, поэтому subpackages оправданы и сопровождают изменение модели (не косметика). Flat-размещение под `protocol` отклонено.

**Почему registry НЕ в `.version` subpackage (Java visibility):** если бы `ControlWireProtocolRegistry` жил в `...protocol.version`, а `TracingControlProtocol` — в родительском `...protocol`, то сделать registry видимым для aggregate можно только через `public` (в Java нет cross-subpackage package-private). Public registry = второй равноправный публичный путь (`registry.current().validator()`), который Q8 отклонил. Поэтому registry — private static nested внутри `TracingControlProtocol`, а не отдельный тип в subpackage.

**Минорный tradeoff:** `.version` теперь содержит единственный класс `ControlWireProtocolVersion`. Принято осознанно (место под будущие version-related типы); допустимая альтернатива — поднять его в корень `...protocol` (см. §18, не блокер).

## 5. Deleted vs Renamed/Moved Types

**Удаляется (без shim, без `@Deprecated`):**
- `TracingControlWireContractVersion` (весь класс)
- `CURRENT_VERSION_NUMBER`, `CURRENT_VERSON_STRING`
- `TracingControlWireSchema.V1`, `TracingControlWireValidator.V1`
- `WireV1Bridge` и пакет `internal.legacy` (transitional, Phase 5)
- Дублирующие raw version literals в путях конструирования protocol → заменяются на `ControlWireProtocolVersion`/registry
- Старые методы `requiredKeysForRuntimeApply()`/`requiredKeysForReadRequest()` — **осознанное API-breaking удаление** (после прохождения equivalence test). Новый public API: `requiredKeysFor(TracingControlProtocolOperation)`. Все tests/usages переводятся на него. Это НЕ молчаливое удаление — это замена двух per-operation методов одним operation-параметризованным.

**Переименовывается/перемещается (функционально сохраняется, НЕ удаляется):**
- `TracingControlWireKeys` → `TracingControlProtocolKeys`
- `TracingControlWireTypes` → `TracingControlProtocolTypes`
- `TracingControlWireSchema` → `TracingControlProtocolSchema`
- `TracingControlWireValidator` → `TracingControlProtocolValidator`
- `TracingControlWireValidationResult` → `TracingControlValidationResult`
- `TracingControlWireViolation` → `TracingControlViolation`
- Nested `FieldCategory`/`FieldDescriptor` → top-level `ControlWireFieldCategory`/`ControlWireFieldDescriptor`

**Новые типы (не renamed, добавляются):**
- `ControlWireProtocolVersion` (record) — value object версии
- `TracingControlProtocol` — aggregate / **единственный** public entrypoint; registry живёт как private static nested внутри него (НЕ отдельный public тип)
- `TracingControlProtocolOperation` (enum) — типизация операций protocol
- `WireViolationCode` (enum, 6 значений) — stable machine-readable коды violation

**Явно НЕ создаётся как public тип:** `ControlWireProtocolRegistry` (свёрнут в private static internals `TracingControlProtocol` — см. §7).

## 6. Versioning Model

- `record ControlWireProtocolVersion(int major)` — canonical identity: `int major`.
- **Форма зафиксирована (Java 21, без exceptions в обычном validation flow):**
```java
public static Optional<ControlWireProtocolVersion> parse(Object raw)
```
Метод называется `parse` (не `fromWire`); возвращает `Optional.empty()` для malformed, `Optional.of(...)` для корректно распарсенной версии. Parsing и support-check **разделены**: `parse` отвечает только за «является ли это версией», поддержку решает `TracingControlProtocol.isSupported(...)` (static facade над internal registry).
- **Два исхода строго различаются** (см. использование в validator §9):
  - **malformed / unparseable** (`null`, blank `String`, `"abc"`, произвольный `Object`, не-целочисленный `String`) → `parse` возвращает `Optional.empty()` → `WireViolationCode.INVALID_VALUE`. Это НЕ `UNSUPPORTED_VERSION` — значение вообще не является версией.
  - **parsed-but-unsupported** (значение корректно распарсено, например `2`/`99`, но registry его не поддерживает) → `WireViolationCode.UNSUPPORTED_VERSION`.
- Sealed result / exceptions отклонены как избыточные для текущего scope. Если позже понадобятся детали причины parse-failure — добавить package-private helper, не усложняя `parse` сейчас.
- Normalized payload сохраняет `contractVersion` как `Integer`.
- Constructor защищён (package-private или registry-validated), чтобы произвольный `new ControlWireProtocolVersion(999)` не считался supported. Поддержка определяется registry, а не конструированием.
- `equals`/`hashCode` от record; ordering через `major` для min/max range checks.

## 7. Protocol Aggregate and Registry

**`TracingControlProtocol` — единственный public entrypoint.** Registry не является отдельным public типом: он свёрнут в private static nested-класс внутри aggregate. Все registry-capabilities доступны как static-методы `TracingControlProtocol`.

```java
public final class TracingControlProtocol {

    // --- static registry facade (единственный публичный доступ к registry-capabilities) ---
    public static TracingControlProtocol current();
    public static Optional<TracingControlProtocol> find(ControlWireProtocolVersion version);
    public static boolean isSupported(ControlWireProtocolVersion version);
    public static ControlWireProtocolVersion minSupportedVersion();
    public static ControlWireProtocolVersion maxSupportedVersion();

    // --- instance API (matched triple) ---
    public ControlWireProtocolVersion version();
    public TracingControlProtocolSchema schema();
    public TracingControlProtocolValidator validator();

    // --- internal: immutable v1-only registry, недоступен извне пакета/класса ---
    private static final class Registry {
        // immutable Map<ControlWireProtocolVersion, TracingControlProtocol>, построенный один раз
    }
}
```

- Гарантирует согласованную тройку version/schema/validator (исправляет непринудительную пару `Schema.V1`+`Validator.V1`).
- **Semantics `current()` (RF-001, зафиксировано):** `static TracingControlProtocol current()` возвращает зарегистрированный aggregate для текущей supported version (сегодня v1-only). Private nested `Registry` кэширует instances **singleton-per-major**: два последовательных вызова `current()` возвращают **тот же object identity** (`==`), чтобы `current().validator()` и повторный `current().schema()` не наблюдали drift между вызовами. Тест: `TracingControlProtocolTest.currentReturnsSameInstance()`.
- Registry иниализируется **eager** в static initializer `TracingControlProtocol` (immutable map, построен один раз). Malformed registration (test-only) → `IllegalStateException` at class-init, не `WireViolationCode`.
- Нет public mutators, нет dynamic/plugin registration; поверхность для мутации минимальна, т.к. registry не публичен (усиливает mitigation risk #3).
- **Naming:** нет коллизии `current()/current()` между двумя классами — `current()` существует только на `TracingControlProtocol`. `minSupportedVersion()`/`maxSupportedVersion()` названы явно, чтобы не путать с `version()`.

## 8. Schema and Descriptor Model

- Enum операций protocol (типизирует существующие operation-константы `TracingControlWireKeys`):
```java
public enum TracingControlProtocolOperation {
    APPLY_RUNTIME_POLICY,
    VALIDATE_RUNTIME_POLICY,
    READ_APPLIED_STATE,
    READ_SCHEMA
}
```
- `ControlWireFieldDescriptor` (финальная форма — typed operation set, не `Set<String>`):
```java
record ControlWireFieldDescriptor(
    String key,
    TracingControlProtocolTypes wireType,
    ControlWireFieldCategory category,
    Set<TracingControlProtocolOperation> requiredForOperations
)
```
- Descriptors — единственный source of truth для key/type/category/requiredness; required-key sets **выводятся** через public API:
```java
public Set<String> requiredKeysFor(TracingControlProtocolOperation operation)
```
Этот метод — единственный public entrypoint для required keys; он заменяет `requiredKeysForRuntimeApply()`/`requiredKeysForReadRequest()` (осознанное API-breaking, см. §5).
- Key strings остаются public constants в `TracingControlProtocolKeys`; descriptors ссылаются на них.
- Без единого global `required` boolean. Примечание: текущие required sets идентичны для всех операций (`{contractVersion, operation}`); operation-aware model — forward-looking и должна оставаться behavior-equivalent сегодня (см. equivalence test §11 Phase 3).
- **Rationale operation-aware requiredness (RF-005, закрывает open question):** это не косметика — future protocol versions могут diverge по операциям (например V2 потребует `correlationId` только для `READ_*`, но не для `APPLY_*`). Descriptor с `Set<TracingControlProtocolOperation>` сейчас предотвращает Phase-4 schema rewrite при добавлении V2. Стоимость сегодня: одно поле record с Set размером 1–4; negligible.
- Без domain-specific schema classes в этом PR.

## 9. Validator and Violation Model

- `TracingControlProtocolValidator` использует `ControlWireProtocolVersion.parse(Object)` + static facade `TracingControlProtocol.isSupported(...)`, заменяя `TracingControlWireContractVersion.isSupported(Object)`. Каноничный flow:
```java
Optional<ControlWireProtocolVersion> parsed = ControlWireProtocolVersion.parse(raw);
if (parsed.isEmpty()) {
    // → WireViolationCode.INVALID_VALUE   (malformed, не версия)
} else if (!TracingControlProtocol.isSupported(parsed.get())) {
    // → WireViolationCode.UNSUPPORTED_VERSION   (валидна, но не поддерживается)
}
// else: ok, normalize contractVersion → Integer(parsed.major)
```
- **Сохраняется:** strict unknown-key rejection; non-throwing `TracingControlValidationResult`; normalized payload (`contractVersion` → `Integer`); текущая per-type validation semantics.
- `TracingControlViolation(String key, WireViolationCode code, String reason, String expectedType, String actualType)` — `code` stable/machine-readable; `reason` free-text, явно non-contractual (убрать использование typo-constant в `expectedType`).
- `WireViolationCode` — **6 обязательных значений**:
```java
public enum WireViolationCode {
    UNSUPPORTED_VERSION,   // version распарсена, но registry её не поддерживает (2, 99)
    INVALID_VALUE,         // raw value нельзя распарсить/coerce (null, blank, "abc", чужой тип)
    UNKNOWN_KEY,           // strict unknown-key rejection
    MISSING_REQUIRED_KEY,  // отсутствует required key для операции
    TYPE_MISMATCH,         // значение известного key имеет неверный wire-тип
    OPERATION_NOT_ALLOWED  // operation вне allowlist для данного entrypoint
}
```
`INVALID_VALUE` больше не optional — malformed version не должна попадать под `UNSUPPORTED_VERSION`, а `"abc"` в `contractVersion` не должна маппироваться в `TYPE_MISMATCH` (тип String формально допустим как inbound representation версии, но значение invalid).

**Scope `INVALID_VALUE` (зафиксировано, раунд 2):** `INVALID_VALUE` зарезервирован **исключительно** для malformed `contractVersion`, который нельзя распарсить/coerce в `ControlWireProtocolVersion`. Для **всех остальных** known полей несовпадение Java-типа = **всегда `TYPE_MISMATCH`**, независимо от степени «неправильности» значения. Domain-level семантическая невалидность (например out-of-range ratio), если понадобится позже, моделируется отдельно и явно — не угадывается в этом рефакторинге. Примеры:
```text
contractVersion = "abc"   → INVALID_VALUE
contractVersion = null    → INVALID_VALUE
contractVersion = 99      → UNSUPPORTED_VERSION
samplingRatio   = "abc"   → TYPE_MISMATCH       (не INVALID_VALUE)
droppedRoutes   = 123     → TYPE_MISMATCH
неизвестный key           → UNKNOWN_KEY
operation вне allowlist   → OPERATION_NOT_ALLOWED
```

**Null/empty payload contract (RF-006, зафиксировано):**
```text
validate(null)                    → invalid; violations: MISSING_REQUIRED_KEY для каждого required key операции
validate(Map.of())                → invalid; violations: MISSING_REQUIRED_KEY для contractVersion, operation
validate(map с null value)        → TYPE_MISMATCH для known key с null value
```
Non-throwing: всегда `TracingControlValidationResult`, никогда exception для payload errors. Контракт фиксируется characterization-тестами Phase 0/4; downstream JMX adapters опираются на него.

**Duplicate-key policy (RF-004, зафиксировано):** седьмой код `DUPLICATE_KEY` **не вводится** (enum остаётся 6-value). В стандартном `Map<String,Object>` duplicate keys невозможны (last put wins). Если pre-validation layer (например future OpenMBean adapter при сборке Map из TabularData) обнаруживает явный duplicate key до validator — emit `UNKNOWN_KEY`. Validator на готовом Map работает по Java Map semantics без отдельного duplicate detection.

## 10. JMX / OpenMBean Boundary

- Сохранить `Map<String,Object>`; без typed DTO.
- Ноль `javax.management.openmbean.*` в `api.control.protocol`. Open-type compatibility остаётся by-convention (документировано).
- Будущий OpenMBean/CompositeData adapter — в JMX layer `platform-tracing-otel-extension` (или позже в dedicated module); implementation out of scope. NEEDS_VERIFICATION: точное long-term размещение подтвердить при реальной MBean wiring.

## 11. Migration Phases

Каждая фаза compile-safe (проект собирается зелёным в конце фазы).

### Phase 0 — Подтверждение inventory + characterization tests (без изменений production)
- **Goal:** зафиксировать текущее поведение до изменения кода.
- **Files:** только test sources (`platform-tracing-api/src/test/.../control/wire/`).
- **Tests:** version parsing/coercion matrix (`Integer 1/2`, `Long 1L/2L`, `String "1"/" 1 "/""/"abc"`, `null`, arbitrary object); normalization (Integer/Long/String → Integer); schema key registry snapshot (26 keys + type + category + required-for-operation); strict unknown-key rejection (runtime + read paths); violation baseline (reason/expectedType/actualType для каждого сценария).
- **Risks:** нет (additive tests).
- **Rollback:** удалить добавленные tests.
- **DoD:** новые characterization tests зелёные на текущем коде; `:platform-tracing-api:test` зелёный.
- **Checks:** `./gradlew.bat :platform-tracing-api:test --tests "*Wire*"`.

### Phase 1 — Version value object
- **Goal:** ввести `ControlWireProtocolVersion` + `parse(Object) : Optional<ControlWireProtocolVersion>`.
- **Форма зафиксирована:** `public static Optional<ControlWireProtocolVersion> parse(Object raw)` — без exceptions, без sealed result. `parse` отвечает только за parsing (malformed → `Optional.empty()`); support-check делает registry. Open question по форме закрыт.
- **Files:** новый `...protocol/version/ControlWireProtocolVersion.java`; новый test.
- **Не удалять** `TracingControlWireContractVersion` пока.
- **Tests:** `parse` matrix (`Integer 1/2`, `Long 1L/2L`, `String "1"/" 1 "`, → present; `""`, `"abc"`, `null`, чужой `Object` → `Optional.empty()`), canonical major, equality/hashCode.
- **Risks:** низкий (additive).
- **Rollback:** удалить новые файлы.
- **DoD:** `*ControlWireProtocolVersion*` tests зелёные; модуль компилируется.

### Phase 2 — Protocol aggregate + internal registry
- **Goal:** `TracingControlProtocol` с private static nested `Registry` (НЕ отдельный public тип), transitional bridge через именованный класс `WireV1Bridge`.
- **Transitional bridge (RF-002, зафиксировано):**
  - Класс: `space.br1440.platform.tracing.api.control.protocol.internal.legacy.WireV1Bridge` (**package-private**).
  - Единственный допустимый потребитель `TracingControlWireSchema.V1` / `TracingControlWireValidator.V1` в Phases 2–4.
  - Вызывается **только** из private nested `TracingControlProtocol.Registry` при построении aggregate.
  - Помечен `// TRANSITIONAL: удалить в Phase 5`.
  - Phase 5: класс и пакет `internal.legacy` удаляются полностью; grep `WireV1Bridge` → zero.
- **⚠️ Transitional bridge constraint:** bridge разрешён **ТОЛЬКО** до Phase 5. Aggregate НЕ должен остаться тонким wrapper (иначе косметика). Финальный код конструирует schema/validator через protocol registry internals с **нулём ссылок** на старые V1 singletons и `WireV1Bridge`.
- **Files:** `TracingControlProtocol.java` (с private `Registry`); `internal/legacy/WireV1Bridge.java`; tests.
- **Tests:** `TracingControlProtocol.current()` — same instance identity (RF-001); согласованные version/schema/validator; static facade; unsupported-version; отсутствие public `ControlWireProtocolRegistry`.
- **Risks:** low-medium (bridge wiring); риск забытый bridge — gate Phase 5 + ArchUnit rule 10.
- **Rollback:** удалить новые файлы; откатить bridge.
- **DoD:** `*TracingControlProtocol*` tests зелёные; `WireV1Bridge` существует и помечен TRANSITIONAL; модуль компилируется.

### Phase 3 — Schema / descriptor cleanup
- **Goal:** вынести/переместить `ControlWireFieldCategory`, `ControlWireFieldDescriptor`; переименовать `Keys/Types/Schema`; descriptor-driven required keys.
- **Files:** schema subpackage; internal references; renamed key/type usages.
- **Tests (equivalence с явным маппингом operation → старый метод, RF-003):** класс `RequiredKeysEquivalenceTest` (Phase 3 gate, **до** удаления старых методов):
  - Сравнение через `TreeSet` equality (порядок не контракт).
  - Маппинг:
```text
requiredKeysFor(APPLY_RUNTIME_POLICY)     == old requiredKeysForRuntimeApply()
requiredKeysFor(VALIDATE_RUNTIME_POLICY)  == old requiredKeysForRuntimeApply()
requiredKeysFor(READ_APPLIED_STATE)         == old requiredKeysForReadRequest()
requiredKeysFor(READ_SCHEMA)              == old requiredKeysForReadRequest()
```
  - Fixtures (immutable descriptor snapshots, не full payload maps): зафиксировать expected set `{contractVersion, operation}` для всех 4 операций сегодня; при расхождении между APPLY и VALIDATE без явного намерения — тест падает.
  - Phase 3a: тест против **старого** API (оба метода ещё существуют).
  - Phase 3b: после перехода на descriptor — тест против **нового** API; gate на удаление старых методов.
- **Risks:** medium (rename ripple по tests).
- **Rollback:** git revert commit фазы.
- **DoD (gate на merge):** Phase 3 не считается завершённой, пока equivalence-тест не доказал соответствие по всем 4 операциям выше. Если два descriptor'а дают разные `requiredForOperations` без явного намерения — тест обязан падать. `*TracingControlProtocolSchema*` + equivalence test зелёные.

### Phase 4 — Validator + result/violation migration
- **Goal:** переименовать validator; добавить `WireViolationCode`; переименовать result/violation; validator использует `ControlWireProtocolVersion.parse(...)` + `TracingControlProtocol.isSupported(...)`.
- **Files:** validation + result subpackages; обновить test-only `WireRoundTripTestMBeanImpl`, `SamplingControlClientWireContractTest`, e2e harness; обновить комментарий в `platform-tracing-e2e-tests/build.gradle` со ссылкой на `TracingControlWireValidator.V1`.
- **Tests:** assert violation codes по сценариям; null/empty payload contract (RF-006); портировать validator matrix; e2e round-trip.
- **Risks:** medium-high (behavioral equivalence + e2e harness).
- **Rollback:** git revert commit фазы.
- **DoD:** validator/violation tests + e2e round-trip зелёные.

### Phase 5 — Удаление старых типов и public entrypoints
- **Goal:** удалить `TracingControlWireContractVersion`, `Schema.V1`, `Validator.V1`, `WireV1Bridge`, старые required-key methods; удалить пустые пакеты `api.control.wire` и `internal.legacy`.
- **Обязательная проверка удаления bridge:** `WireV1Bridge` и весь `internal.legacy` удалены. Grep `WireV1Bridge`, `// TRANSITIONAL`, `.V1` в protocol-пакете → zero.
- **Files:** deletions по пакету + stale references.
- **Tests:** full module + arch + e2e.
- **Risks:** medium (пропущенные references; забытый transitional bridge).
- **Rollback:** git revert.
- **DoD:** acceptance greps возвращают zero (Section 16); отсутствуют ссылки на старые `V1` singletons и `// TRANSITIONAL` маркеры; build зелёный.

### Phase 6 — ArchUnit, docs, ADR, acceptance
- **Goal:** добавить/обновить guardrails; обновить live docs; создать ADR version model.
- **⚠️ ArchUnit rules находятся НЕ в одном месте.** Обновить **все** локации, где сейчас живут правила про `api.control.wire` — не считать, что источник один:
  1. API-module arch tests: `platform-tracing-api/src/test/java/space/br1440/platform/tracing/api/control/wire/arch/ApiWirePackagePurityArchTest.java` и `ApiWireNoImplementationDependencyArchTest.java` (пакет `...control.wire.arch` переезжает в `...control.protocol.arch`; правила переориентируются на новый пакет).
  2. Shared rules: `platform-tracing-test/src/main/java/space/br1440/platform/tracing/test/arch/ArchitectureFitnessArchRules.java` (содержит `API_WIRE_PACKAGE_JDK_ONLY`, `API_WIRE_NO_IMPLEMENTATION_MODULES` и ссылку на validator по имени в FF-09a).
  3. Root fitness-задачи, импортирующие эти правила: `pr4ArchitectureFitnessVerify` / `pr1ModuleTaxonomyVerify` (root `build.gradle`) — проверить, что они по-прежнему подхватывают переориентированные правила.
- **Files:** обе локации ArchUnit (выше) + `docs/architecture/platform-tracing-wire-schema-v1.md`, `docs/architecture/ADR-jmx-wire-map-contract.md`, `docs/architecture/platform-tracing-fitness-functions-implementation.md`; новый `docs/decisions/ADR-control-protocol-version-model.md`.
- **Tests:** новые/переориентированные ArchUnit rules зелёные во всех локациях; root fitness-задачи зелёные.
- **Risks:** medium — правило, оставшееся указывать на старый пакет `api.control.wire`, начнёт молча проходить на пустом множестве классов (false-green). Mitigation: rule 2 из §14 (нет классов под старым пакетом) + явная переориентация обоих arch-tests.
- **Rollback:** git revert.
- **DoD:** все guardrails зелёные во всех локациях; ни одно правило не ссылается на старый пакет; docs/ADR merged; `javadoc` clean.

## 12. Characterization Tests

Version coercion matrix; version normalization (→ Integer); 26-key registry snapshot (key/type/category/required-for-operation); strict unknown-key rejection (runtime + read); violation baseline triples. Все написаны и зелёные в Phase 0 до любых production changes.

## 13. Target Tests

`ControlWireProtocolVersionTest`, `TracingControlProtocolTest` (+ `currentReturnsSameInstance` RF-001), `RequiredKeysEquivalenceTest` (Phase 3 gate, RF-003), `TracingControlProtocolSchemaTest`, `TracingControlProtocolValidatorTest` (6 `WireViolationCode` + scope `samplingRatio="abc"` → `TYPE_MISMATCH`; null/empty payload RF-006), `TracingControlViolationTest`, обновлённые `SamplingControlClientWireContractTest`, обновлённые `WireRoundTrip*` e2e.

## 14. ArchUnit Guardrails

1. Нет зависимостей `api.control.protocol..` от `javax.management.openmbean..`, Spring, OTel SDK или `platform-tracing-otel-extension`.
2. Нет классов под `space.br1440.platform.tracing.api.control.wire..`.
3. Нет public raw version constants (`public static final int CURRENT_VERSION_NUMBER`; нет public String version source-of-truth).
4. Нигде нет `CURRENT_VERSON_STRING` (typo guard, regex `CURRENT_VERSON.*`).
5. Нет public singleton `TracingControlProtocolSchema.V1` / `TracingControlProtocolValidator.V1`.
6. Нет API wire types с именами/ролью `*Command`/`*Dto`/`*Request` (guard от typed-DTO scope creep).
7. **Обязательно:** `TracingControlViolation` содержит компонент `WireViolationCode` (не предпочтительно, а mandatory).
8. Нет ссылок из `api.control.protocol..` на `TracingControlWireSchema.V1` / `TracingControlWireValidator.V1` и на старый пакет `api.control.wire..` (guard от того, что transitional bridge останется в финале).
9. Нет public/top-level типа с именем `*Registry` в `api.control.protocol..` (registry обязан быть internal; guard от восстановления второго параллельного публичного входа).
10. После Phase 5: нет класса `WireV1Bridge` и пакета `internal.legacy` в `api.control.protocol..` (guard от забытый transitional bridge).

> **Локации правил (не одна!):** правила выше применяются одновременно в (1) API-module arch tests `platform-tracing-api/src/test/.../control/{wire→protocol}/arch/` и (2) shared `platform-tracing-test/.../arch/ArchitectureFitnessArchRules.java`, а также подхватываются root fitness-задачами (`pr4ArchitectureFitnessVerify`, `pr1ModuleTaxonomyVerify`). Обновлять нужно все три; не предполагать единственный источник.

## 15. Documentation and ADR Plan

- Обновить `platform-tracing-wire-schema-v1.md` (protocol/version/aggregate model; сохранить strict unknown-key).
- Обновить `ADR-jmx-wire-map-contract.md` (package rename; Map boundary сохранён).
- Обновить `platform-tracing-fitness-functions-implementation.md` (FF rules переименованы на protocol package; новые guardrails).
- Создать `docs/decisions/ADR-control-protocol-version-model.md` (record vs enum; registry; coercion; violation codes).

## 16. Acceptance Criteria

Greps по перечисленным паттернам:
```text
CURRENT_VERSON_STRING
CURRENT_VERSION_NUMBER
TracingControlWireContractVersion
TracingControlWireSchema.V1
TracingControlWireValidator.V1
TracingControlWireValidationResult
TracingControlWireViolation
space.br1440.platform.tracing.api.control.wire
WireV1Bridge
// TRANSITIONAL
```

**Scope grep-gate (уточнён):**
- **Должно быть zero** в: `src/` (main + test всех модулей); build-скрипты (`*.gradle`); **live** docs `docs/architecture/**` после обновления; текущие ADR в `docs/decisions/**` после обновления.
- **Допустимо (не является нарушением)** — при явном контексте «old state»: historical analysis (`docs/analysis/tracing-control-wire-package-inventory.md` и подобные, описывающие старое состояние); архивные migration plans; секция «до/после» в финальном migration report; сам этот план (описывает удаляемые сущности). Такие файлы фиксируют исторический пакет намеренно.

Плюс ручной review оставшихся raw `"1"` / `1` рядом с `contractVersion`/protocol-version context.

Build/test gates (Windows `gradlew.bat`; `archTest` **не существует** — использовать обнаруженные задачи):
```text
:platform-tracing-api:test --tests "*ControlWireProtocolVersion*"
:platform-tracing-api:test --tests "*TracingControlProtocol*"
:platform-tracing-api:test --tests "*TracingControlProtocolSchema*"
:platform-tracing-api:test --tests "*TracingControlProtocolValidator*"
:platform-tracing-api:test --tests "*TracingControlViolation*"
:platform-tracing-spring-boot-autoconfigure:test --tests "*SamplingControlClientWireContract*"
:platform-tracing-e2e-tests:test --tests "*WireRoundTrip*"
:platform-tracing-api:test         // запускает ApiWire* ArchUnit tests
pr4ArchitectureFitnessVerify        // root aggregate fitness-function gate (замена archTest)
pr1ModuleTaxonomyVerify             // root taxonomy guardrails
build
:platform-tracing-api:javadoc
```

## 17. Risk Register

| # | Риск | Mitigation |
|---|------|------------|
| 1 | Пропущены renamed references | grep acceptance + ArchUnit rule 2 |
| 2 | Behavioral drift validator при rename | Phase 0 characterization baseline + ported matrix |
| 3 | Registry со временем станет mutable/pluggable | immutable + **не public** (private static nested в `TracingControlProtocol`) → минимальная поверхность мутации; ADR constraint; ArchUnit rule 9 |
| 4 | Descriptor requiredness model over-built | flat Set; equivalence test блокирует удаление старых методов |
| 5 | e2e JMX round-trip harness ломается незаметно | явная задача Phase 4 + обновление комментария `build.gradle` |
| 6 | `WireViolationCode` растёт без контроля | reviewed-change-only enum (6 значений — closed set) |
| 7 | Coercion позже окажется неверной для real MBean client | NEEDS_VERIFICATION; revisit с evidence, не сейчас |
| 8 | Transitional bridge (`WireV1Bridge`) остаётся в финале | Phase-2-only + `// TRANSITIONAL` marker + ArchUnit rule 10 + Phase 5 grep `WireV1Bridge` |
| 9 | malformed version маппится как `UNSUPPORTED_VERSION`/`TYPE_MISMATCH` | обязательный `INVALID_VALUE`; разделение parse vs support-check; per-code tests |
| 10 | ArchUnit rule обновлён только в одной локации → правило на старый пакет даёт false-green на пустом множестве | обновить все 3 локации (API arch tests + shared rules + root fitness tasks); §14 rule 2; Phase 6 gate |

## 18. Open Questions / NEEDS_VERIFICATION

**Закрыто по итогам architect review:**
- ~~Subpackages `schema`/`result` vs flat~~ → **CLOSED:** финальная структура `version`/`schema`/`validation`/`result` (§4).
- ~~`Set<String>` vs typed operation set~~ → **CLOSED:** typed `Set<TracingControlProtocolOperation>` (§8).
- ~~`INVALID_VALUE` optional?~~ → **CLOSED:** обязателен, 6-value enum (§9).
- ~~Судьба старых required-key методов~~ → **CLOSED:** осознанное API-breaking удаление, замена на `requiredKeysFor(operation)` (§5, §8).
- ~~Форма результата `fromWire` (Optional vs sealed vs два метода)~~ → **CLOSED:** `public static Optional<ControlWireProtocolVersion> parse(Object)` без exceptions; parse отделён от support-check (§6, §9, §11 Phase 1).
- ~~Registry как public тип в `.version` vs internal~~ → **CLOSED (раунд 2):** private static nested внутри `TracingControlProtocol`; отдельного public типа нет (§7, §4).
- ~~Scope `INVALID_VALUE` за пределами version-поля~~ → **CLOSED (раунд 2):** только для `contractVersion`; прочие поля → `TYPE_MISMATCH` (§9).
- ~~Operation-aware requiredness rationale~~ → **CLOSED (раунд 3, RF-005):** future V2 divergence по операциям; не косметика (§8).
- ~~External Opus documentation hardening (RF-001–006)~~ → **CLOSED (раунд 3):** current() caching, WireV1Bridge, equivalence fixtures, duplicate-key policy, null/empty contract (§7, §9, §11).

**Остаются открытыми (не блокеры реализации):**
1. Long-term home OpenMBean adapter (`platform-tracing-otel-extension` vs future dedicated module).
2. **(минорный)** `.version` subpackage содержит единственный класс `ControlWireProtocolVersion` — оставить как есть (место под будущие version-типы) или поднять в корень `...protocol`. По умолчанию оставлено `.version`; не блокер.

## 19. Appendix: grep patterns and affected files

**Grep patterns:** `api\.control\.wire`, `TracingControlWire`, `CURRENT_VERSION_NUMBER`, `CURRENT_VERSON_STRING`, `\.V1\b`, `WireV1Bridge`, `// TRANSITIONAL`, `contractVersion`, `isSupported\(`.

**Affected production files (7 renamed/deleted + transitional):** `TracingControlWireContractVersion.java` (delete), `TracingControlWireKeys/Types/Schema/Validator/ValidationResult/Violation.java` (rename/move); `internal/legacy/WireV1Bridge.java` (create Phase 2, delete Phase 5).

**Affected tests:** `TracingControlWireValidatorTest`, `TracingControlWireSchemaTest`, `TracingControlWireKeysTest`, `arch/ApiWirePackagePurityArchTest`, `arch/ApiWireNoImplementationDependencyArchTest`, `SamplingControlClientWireContractTest`, `WireRoundTripInProcessTest`, `MapWireRoundTripMain`, `jmxWireExtension/WireRoundTripTestMBeanImpl`.

**Affected ArchUnit locations (все три):** `platform-tracing-api/src/test/.../control/{wire→protocol}/arch/ApiWirePackagePurityArchTest.java` + `ApiWireNoImplementationDependencyArchTest.java`; `platform-tracing-test/src/main/.../arch/ArchitectureFitnessArchRules.java`; root fitness-задачи `pr4ArchitectureFitnessVerify` / `pr1ModuleTaxonomyVerify`.

**Affected non-code:** комментарий `platform-tracing-e2e-tests/build.gradle`; три architecture docs; новый ADR.

---

## 20. Phase 6 addendum — Unified naming hardening (Variant A)

**Status:** COMPLETE

После основного protocol refactor (Phases 0–5) оставался naming drift: типы `ControlWire*`, `WireViolationCode`, `TracingControlValidationResult`/`TracingControlViolation` не соответствовали правилу единого префикса `TracingControlProtocol*`.

### Финальное правило

- Все public top-level production типы в `api.control.protocol..` → simple name начинается с `TracingControlProtocol`.
- Ни один production тип в `api.control.protocol..` не содержит `Wire` в simple name.
- `Wire` допустим только в historical docs, e2e harness (`WireRoundTrip*`), описании Map boundary в ADR/Javadoc.

### Rename mapping (выполнено)

| Было | Стало |
|------|-------|
| `ControlWireProtocolVersion` | `TracingControlProtocolVersion` |
| `ControlWireFieldCategory` | `TracingControlProtocolFieldCategory` |
| `ControlWireFieldDescriptor` | `TracingControlProtocolFieldDescriptor` |
| `WireViolationCode` | `TracingControlProtocolViolationCode` |
| `TracingControlValidationResult` | `TracingControlProtocolValidationResult` |
| `TracingControlViolation` | `TracingControlProtocolViolation` |
| поле `wireType` | `type` (`TracingControlProtocolTypes`) |

Transitional bridge: исторически `WireV1Bridge` (Phases 2–4); удалён в Phase 5. Не воссоздавать.

### ArchUnit (выполнено)

- `PROTOCOL_API_TYPES_USE_UNIFIED_PREFIX`, `PROTOCOL_API_TYPES_DO_NOT_USE_WIRE_NAMING`
- `API_WIRE_*` → `API_PROTOCOL_*`
- Сломанные правила (`beInterfaces()` hack) заменены reflection/JUnit assertions в `ApiProtocolNoImplementationDependencyArchTest`

### Acceptance grep (live scope)

Zero required в `src/main`, live `src/test` (кроме e2e harness names), `docs/architecture/**`, `docs/decisions/**`.

См. [tracing-control-protocol-implementation-report.md](tracing-control-protocol-implementation-report.md) — секция **Unified Naming Phase**.
