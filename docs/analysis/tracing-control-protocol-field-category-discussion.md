# TracingControlProtocolFieldCategory — материал для обсуждения Javadoc

Документ собран для архитектурного обсуждения **комментариев к enum-классу и его константам**.  
Содержит фактическое состояние кода, семантику category в v1 schema и validator, смежные типы и открытые вопросы.

**Дата сбора:** 2026-07-03  
**Scope:** `space.br1440.platform.tracing.api.control.protocol.schema` + потребители category в validation/tests/docs.

---

## 1. Текущее состояние исходника

### 1.1 `TracingControlProtocolFieldCategory.java`

Публичный enum, **без class-level и constant-level Javadoc**, без аннотаций.

```java
public enum TracingControlProtocolFieldCategory {

    ENVELOPE,
    RUNTIME_POLICY,
    STARTUP_TOPOLOGY,
    DIAGNOSTIC

}
```

| Метрика | Значение |
|---------|----------|
| Файл | `platform-tracing-api/src/main/java/.../schema/TracingControlProtocolFieldCategory.java` |
| Строк | 10 |
| Констант | 4 |
| Методов | 0 (только унаследованные `values()` / `valueOf()`) |
| Зависимостей | нет |

### 1.2 Историческое имя

| Было (wire package) | Стало (protocol schema) |
|---------------------|-------------------------|
| `TracingControlWireSchema.FieldCategory` (nested) | `TracingControlProtocolFieldCategory` (top-level) |

Источники: [tracing-control-protocol-refactoring-plan.md](tracing-control-protocol-refactoring-plan.md), [ADR-control-protocol-version-model.md](../decisions/ADR-control-protocol-version-model.md).

---

## 2. Роль category в модели protocol schema

Category — **метаданные поля schema**, не wire type и не domain prefix в key string.

```text
TracingControlProtocolFieldDescriptor
├── key: String                    // wire key, напр. "sampling.ratio"
├── type: TracingControlProtocolTypes   // wire type (INTEGER, DOUBLE, …)
├── category: TracingControlProtocolFieldCategory  // ← этот enum
└── requiredForOperations: Set<TracingControlProtocolOperation>
```

**Отличие от смежных понятий:**

| Понятие | Где живёт | Что описывает |
|---------|-----------|---------------|
| **category** | `TracingControlProtocolFieldCategory` | Класс поля для **entrypoint policy** и группировки в schema |
| **type** | `TracingControlProtocolTypes` | Формат wire value (coercion, normalization) |
| **domain prefix** | `TracingControlProtocolKeys` (комментарии в keys по секциям) | Логическая группа имён (`sampling.*`, `exporter.*`) — **не 1:1 с category** |

Пример расхождения: ключ `exporter.endpoint` имеет prefix `exporter.*`, но category = `STARTUP_TOPOLOGY`, не отдельная category «exporter».

---

## 3. Смежный код

### 3.1 `TracingControlProtocolFieldDescriptor`

Public record, хранит `category` как обязательный компонент (non-null).

```java
public record TracingControlProtocolFieldDescriptor(
    String key,
    TracingControlProtocolTypes type,
    TracingControlProtocolFieldCategory category,
    Set<TracingControlProtocolOperation> requiredForOperations) { ... }
```

Javadoc у record **отсутствует**. Category не документирован на уровне descriptor.

### 3.2 `TracingControlProtocolSchema`

Единственное место **назначения** category для v1 — `buildV1Fields()`:

- `putRequired(...)` — ENVELOPE required keys (`contractVersion`, `operation`)
- `put(...)` — optional keys с `requiredForOperations = Set.of()`

Public API, использующий category:

| Method | Поведение |
|--------|-----------|
| `categoryOf(String key)` | `descriptor.category()` или `null`, если key неизвестен |
| `isTopologyKey(key)` | `category == STARTUP_TOPOLOGY` |
| `isRuntimePolicyKey(key)` | `category == RUNTIME_POLICY` |
| `isDiagnosticKey(key)` | `category == DIAGNOSTIC` |
| `isEnvelopeKey(key)` | `category == ENVELOPE` |

**Замечание:** helper-методы `is*Key` покрывают все 4 category; отдельного `isDiagnosticKey` vs «не diagnostic» в validator category policy не используется явно — DIAGNOSTIC проходит без category-отклонения.

### 3.3 `TracingControlProtocolOperation`

Enum из 4 operation; **не связан напрямую с FieldCategory**, но определяет entrypoint validator:

| Operation | Entry method validator |
|-----------|------------------------|
| `APPLY_RUNTIME_POLICY`, `VALIDATE_RUNTIME_POLICY` | `validateRuntimePolicy()` → `allowRuntimePolicyFields = true` |
| `READ_APPLIED_STATE`, `READ_SCHEMA` | `validateReadRequest()` → `allowRuntimePolicyFields = false` |

Required keys (`contractVersion`, `operation`) одинаковы для всех operation (через `ALL_OPERATIONS` в `putRequired`).

---

## 4. Полная карта v1: key → category → type

Источник: `TracingControlProtocolSchema.buildV1Fields()`. Всего **26 keys**.

### 4.1 `ENVELOPE` (3)

| Key constant | Wire string | Type | Required |
|--------------|-------------|------|----------|
| `CONTRACT_VERSION` | `contractVersion` | `INTEGER` | да (все operation) |
| `OPERATION` | `operation` | `STRING` | да (все operation) |
| `SOURCE` | `source` | `STRING` | optional |

### 4.2 `RUNTIME_POLICY` (16)

| Key constant | Wire string | Type |
|--------------|-------------|------|
| `SAMPLING_RATIO` | `sampling.ratio` | `DOUBLE` |
| `SAMPLING_ROUTE_RATIOS` | `sampling.routeRatios` | `ROUTE_RATIOS_MAP` |
| `SAMPLING_KILL_SWITCH_ENABLED` | `sampling.killSwitch.enabled` | `BOOLEAN` |
| `SAMPLING_QA_TRACE_ENABLED` | `sampling.qaTrace.enabled` | `BOOLEAN` |
| `SAMPLING_FORCE_HEADER_ENABLED` | `sampling.forceHeader.enabled` | `BOOLEAN` |
| `SAMPLING_FORCE_HEADER_VALUES` | `sampling.forceHeader.values` | `STRING_ARRAY` |
| `SAMPLING_DROP_PATH_PREFIXES` | `sampling.dropPathPrefixes` | `STRING_ARRAY` |
| `SCRUBBING_ENABLED` | `scrubbing.enabled` | `BOOLEAN` |
| `SCRUBBING_MODE` | `scrubbing.mode` | `STRING` |
| `SCRUBBING_RULE_NAMES` | `scrubbing.ruleNames` | `STRING_ARRAY` |
| `VALIDATION_ENABLED` | `validation.enabled` | `BOOLEAN` |
| `VALIDATION_MODE` | `validation.mode` | `STRING` |
| `VALIDATION_STRICT` | `validation.strict` | `BOOLEAN` |
| `ENRICHING_ENABLED` | `enriching.enabled` | `BOOLEAN` |
| `EXPORT_ENABLED` | `export.enabled` | `BOOLEAN` |
| `PROPAGATION_ENABLED` | `propagation.enabled` | `BOOLEAN` |

### 4.3 `DIAGNOSTIC` (2)

| Key constant | Wire string | Type |
|--------------|-------------|------|
| `DIAGNOSTICS_REQUEST_ID` | `diagnostics.requestId` | `STRING` |
| `DIAGNOSTICS_TIMESTAMP` | `diagnostics.timestamp` | `LONG` |

### 4.4 `STARTUP_TOPOLOGY` (5)

| Key constant | Wire string | Type |
|--------------|-------------|------|
| `TOPOLOGY_EXPORTER_ENDPOINT` | `exporter.endpoint` | `STRING` |
| `TOPOLOGY_EXPORTER_PROTOCOL` | `exporter.protocol` | `STRING` |
| `TOPOLOGY_EXPORTER_QUEUE_SIZE` | `exporter.queue.size` | `INTEGER` |
| `TOPOLOGY_SDK_MODE` | `sdk.mode` | `STRING` |
| `TOPOLOGY_QUEUE_SIZE` | `queue.size` | `INTEGER` |

### 4.5 Сводка по domain grouping (из wire inventory)

| Domain (логическая группа keys) | Keys | Category |
|---------------------------------|------|----------|
| Envelope | 3 | `ENVELOPE` |
| Sampling | 7 | `RUNTIME_POLICY` |
| Scrubbing | 3 | `RUNTIME_POLICY` |
| Validation (policy) | 3 | `RUNTIME_POLICY` |
| Enriching | 1 | `RUNTIME_POLICY` |
| Export/propagation | 2 | `RUNTIME_POLICY` |
| Diagnostics | 2 | `DIAGNOSTIC` |
| Startup topology | 5 | `STARTUP_TOPOLOGY` |

---

## 5. Runtime semantics: category × validation entrypoint

Единственный production consumer category policy — `OperationSemanticsValidator.validateCategoryPolicy(...)`.

```text
validateRuntimePolicy(payload)
  allowRuntimePolicyFields = true

validateReadRequest(payload)
  allowRuntimePolicyFields = false
```

### 5.1 Матрица допустимости (наблюдаемое поведение v1)

| Category | `validateRuntimePolicy` | `validateReadRequest` | Violation code | Reason string |
|----------|-------------------------|----------------------|----------------|---------------|
| `ENVELOPE` | allowed | allowed | — | — |
| `RUNTIME_POLICY` | allowed | **rejected** | `OPERATION_NOT_ALLOWED` | `"runtime policy field not allowed in read request"` |
| `STARTUP_TOPOLOGY` | **rejected** | **rejected** | `OPERATION_NOT_ALLOWED` | `"startup topology field rejected for wire control path"` |
| `DIAGNOSTIC` | allowed | allowed | — | — |

**Важно для Javadoc `STARTUP_TOPOLOGY`:** отклонение происходит на **любом** wire-control path (runtime mutation и read), не только на read path.

**Важно для Javadoc `DIAGNOSTIC`:** отдельной category policy нет — поля проходят category check и далее валидируются по type как обычные optional keys.

### 5.2 Порядок в validator pipeline

Category policy выполняется **до** special branches `contractVersion` / `operation` и **до** generic type validation:

```text
known key → category policy → contractVersion → operation → null check → type dispatch
```

Следствие: `STARTUP_TOPOLOGY` INTEGER key (напр. `exporter.queue.size`) **никогда не доходит** до generic INTEGER coercion на wire-control path — отклоняется по category раньше.

---

## 6. Violation diagnostics, зависящие от category

При category rejection в `expectedType` violation подставляется **`descriptor.type().name()`** (wire type enum name), не category name:

| Category rejected | `expectedType` в violation |
|-------------------|----------------------------|
| `STARTUP_TOPOLOGY` | `"STRING"`, `"INTEGER"`, … (type поля) |
| `RUNTIME_POLICY` (read path) | то же |

Текст `expectedType` в reason — `"runtime policy or envelope key"` / `"envelope or diagnostic key"` — **человекочитаемая подсказка**, не имя enum category.

---

## 7. Тесты как спецификация поведения

| Test class | Что фиксирует про category |
|------------|----------------------------|
| `TracingControlProtocolSchemaTest` | `isTopologyKey`, `isRuntimePolicyKey`, `isDiagnosticKey`, `isEnvelopeKey`; descriptor.category для `SAMPLING_RATIO` |
| `OperationSemanticsValidatorTest` | STARTUP_TOPOLOGY → rejected; RUNTIME_POLICY read vs runtime; ENVELOPE never rejected by category |
| `TracingControlProtocolValidatorTest` | char-05 topology на runtime path; read path rejects runtime policy fields; DIAGNOSTIC на read path valid |
| Phase 0 schema mapping table (javadoc в test) | key → category для characterization tests |

---

## 8. Упоминания в документации репозитория

| Документ | Содержание про category |
|----------|-------------------------|
| [platform-tracing-wire-schema-v1.md](../architecture/platform-tracing-wire-schema-v1.md) §12 | enum перечислен в API package tree |
| [tracing-control-wire-package-inventory.md](tracing-control-wire-package-inventory.md) §5.3 | domain → category таблица (исторический wire naming) |
| [tracing-control-protocol-validator-inventory.md](tracing-control-protocol-validator-inventory.md) §4.1 | branch map STARTUP_TOPOLOGY / RUNTIME_POLICY |
| [tracing-control-protocol-refactoring-plan.md](tracing-control-protocol-refactoring-plan.md) | promotion nested → top-level enum |

**Пробел:** ни один doc не даёт формального определения семантики каждой constant enum (только grouping таблицы).

---

## 9. Архитектурный контекст для комментариев

### 9.1 Зачем category существует

1. **Entrypoint policy** — какие поля допустимы на runtime mutation vs read wire path.
2. **Schema classification** — helper API `is*Key` для callers/tests.
3. **Отделение lifecycle** — startup topology vs runtime policy vs envelope vs diagnostic metadata.

Category **не участвует** в:

- wire type coercion (`TracingControlProtocolTypes`);
- required-key sweep (только `requiredForOperations`);
- unknown-key policy (strict v1).

### 9.2 Связь с будущим v2 (deferred)

Из refactoring plan / validator inventory:

- v2 может потребовать descriptor-attached constraints или path-specific rules;
- category policy сейчас **захардкожена** в `OperationSemanticsValidator`, не data-driven из descriptor;
- при расширении enum новыми category потребуется явная validator policy (не только Javadoc).

---

## 10. Открытые вопросы для архитекторов (Javadoc)

### 10.1 Class-level Javadoc

1. Формулировать category как **«lifecycle / entrypoint class of a schema field»** или как **«validation policy group»**?
2. Указывать ли явно, что category **не сериализуется на wire** и не видна consumer'ам payload?
3. Нужна ли ссылка на `OperationSemanticsValidator` / `validateRuntimePolicy` vs `validateReadRequest`?

### 10.2 Constant-level Javadoc

| Constant | Ключевые решения для текста |
|----------|----------------------------|
| `ENVELOPE` | Обязательные vs optional envelope keys; special-case validation для `contractVersion`/`operation` |
| `RUNTIME_POLICY` | «Mutable runtime configuration» vs «policy fields excluded from read path»; связь с operation APPLY/VALIDATE |
| `STARTUP_TOPOLOGY` | «Helm/env startup-only» vs «never on JMX wire control path»; почему имя TOPOLOGY, а keys — `exporter.*` / `sdk.mode` |
| `DIAGNOSTIC` | Correlation metadata; почему разрешён на read path; отличие от ENVELOPE optional `source` |

### 10.3 Язык комментариев

- Production Javadoc в schema package сейчас минимален (см. `TracingControlProtocolTypes.ratioBounded()` — есть Javadoc на English).
- Validator helpers переведены на **русский + English technical terms**.
- **Нужно ли выровнять язык** Javadoc для `TracingControlProtocolFieldCategory` с validator или оставить English для public API schema?

### 10.4 Граница с `TracingControlProtocolKeys`

В `TracingControlProtocolKeys` domain задаётся **расположением констант и prefix**, без enum.  
Стоит ли в Javadoc category **ссылаться на keys** («см. `TracingControlProtocolKeys` секция sampling») или держать category документ автономным?

### 10.5 `DIAGNOSTIC` vs «diagnostic keys» в reason strings

Reason для read-path runtime policy rejection: `"envelope or diagnostic key"` —  
нужно ли в Javadoc `DIAGNOSTIC` явно описать, что эта category **разрешена** там, где runtime policy **запрещена**?

---

## 11. Черновики формулировок (для обсужения, не approved)

> Не применять в код без решения архитекторов. English draft — baseline для public API.

### 11.1 Class

```text
Classifies each v1 schema field by lifecycle and wire-control entrypoint policy.
Used by TracingControlProtocolFieldDescriptor and TracingControlProtocolSchema;
enforced at validation time by OperationSemanticsValidator.
Not transmitted on the wire.
```

### 11.2 Constants (кратко)

| Constant | Draft one-liner |
|----------|-----------------|
| `ENVELOPE` | Required and optional control envelope fields (contractVersion, operation, source). |
| `RUNTIME_POLICY` | Runtime-mutable policy fields; allowed on validateRuntimePolicy, rejected on validateReadRequest. |
| `STARTUP_TOPOLOGY` | Startup-only topology fields; rejected on all wire-control validation entrypoints. |
| `DIAGNOSTIC` | Correlation/diagnostic metadata; allowed on both runtime and read entrypoints. |

---

## 12. Файлы для review при добавлении Javadoc

| Файл | Действие |
|------|----------|
| `TracingControlProtocolFieldCategory.java` | primary — class + constant Javadoc |
| `TracingControlProtocolFieldDescriptor.java` | optional — `@param category` в record Javadoc |
| `TracingControlProtocolSchema.java` | optional — Javadoc для `categoryOf` / `is*Key` |
| `OperationSemanticsValidator.java` | cross-link или краткая отсылка к category semantics |

---

## 13. Checklist перед merge Javadoc

- [ ] Текст согласован с матрицей §5.1 (особенно STARTUP_TOPOLOGY на обоих entrypoint)
- [ ] Нет противоречия с verbatim reason strings validator
- [ ] Упомянуто отличие category от `TracingControlProtocolTypes`
- [ ] v1 scope явно ограничен (4 constants, 26 keys)
- [ ] Язык (RU/EN) согласован с остальным public schema API
- [ ] `javadoc` task без новых warnings для schema package
