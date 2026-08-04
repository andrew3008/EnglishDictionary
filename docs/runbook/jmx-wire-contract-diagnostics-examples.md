# Примеры JMX wire-contract diagnostics (план wire_contract_constants_use)

Документ фиксирует **конкретные примеры** diagnostics, которые создаёт функциональность
из плана `wire_contract_constants_use` (type-name gate на decode + связанные ранние
отклонения observation).

Источник истины по схеме: [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md).
Операторский контекст `tracingStatus`: [tracing-status-diagnostics](./tracing-status-diagnostics.md).

## Что именно добавляет план

План делает живым kind **`COMPOSITE_TYPE_NAME_MISMATCH`**:

1. `JmxObservationCodec.requireCompositeTypeName(...)` сравнивает
   `CompositeData.getCompositeType().getTypeName()` с нормативной константой
   (`*_COMPOSITE_TYPE_NAME` / `*_VALUES_TYPE_NAME`).
2. Каждый `*JmxView.decode` вызывает gate:
   - top-level — сразу после `asComposite`, **до** `requireSchemaVersion` и item-decode;
   - nested Export/ProcessorMetrics — сразу после `getComposite`, **до** `ownerState`.
3. При несовпадении бросается `JmxContractViolationException(COMPOSITE_TYPE_NAME_MISMATCH)`.
4. `JmxReadExecutor` записывает episode в `JmxReadStateRegistry` и возвращает
   `Optional.empty()` (observation не публикуется).
5. Снимок попадает в `PlatformTracingJmxClient#jmxContractDiagnostics()` →
   секция `tracingStatus.jmxContractDiagnostics`.

Нормативные typeName (11 точек проверки):

| Уровень | Нормативное имя |
|---------|-----------------|
| Sampling | `space.br1440.platform.tracing.SamplingObservation` |
| Scrubbing | `space.br1440.platform.tracing.ScrubbingObservation` |
| Validation | `space.br1440.platform.tracing.ValidationObservation` |
| Diagnostics | `space.br1440.platform.tracing.DiagnosticsObservation` |
| Export | `space.br1440.platform.tracing.ExportObservation` |
| Export / processor | `space.br1440.platform.tracing.ExportProcessorValues` |
| Export / exporter | `space.br1440.platform.tracing.SafeExporterValues` |
| ProcessorMetrics | `space.br1440.platform.tracing.ProcessorMetricsObservation` |
| ProcessorMetrics / watchdog | `space.br1440.platform.tracing.ProcessorWatchdogValues` |
| ProcessorMetrics / composite | `space.br1440.platform.tracing.ProcessorCompositeValues` |
| ProcessorMetrics / metrics | `space.br1440.platform.tracing.ProcessorMetricsValues` |

## Каналы появления diagnostics

| Канал | Форма |
|-------|--------|
| Application log (WARN, once per episode + rate-limit) | `JMX-контракт: CONTRACT_VIOLATION COMPOSITE_TYPE_NAME_MISMATCH на точке SAMPLING_OBSERVATION (первое или повторное после recovery)` |
| `GET …/tracingStatus` → `jmxContractDiagnostics[]` | JSON-снимок `JmxDiagnosticSnapshot` |
| Сопутствующие сигналы (не сам diagnostic record) | `domain.readable{domain}=0`, point-in-time gauges `NaN`, observation-поля домена отсутствуют / пусты |

Поля записи `jmxContractDiagnostics`:

| Поле | Смысл |
|------|--------|
| `point` | `JmxContractPoint` (`SAMPLING_OBSERVATION`, `EXPORT_OBSERVATION`, …) |
| `category` | всегда `CONTRACT_VIOLATION` для type-name / schema-version gate |
| `kind` | `COMPOSITE_TYPE_NAME_MISMATCH` (основной kind плана) |
| `lifetimeCount` | сколько раз kind зафиксирован за жизнь процесса |
| `suppressedWarnCount` | сколько WARN подавлено rate-limit'ом |
| `firstSeen` / `lastSeen` | границы текущего episode |
| `lastAttemptAt` | время последней попытки чтения точки |
| `unrecovered` | `true`, пока после этого failure не было успешного decode той же точки |

---

## Пример 1. Top-level type-name mismatch (Sampling)

**Сценарий.** Агент (или чужой MBean под тем же ObjectName) отдаёт атрибут
`SamplingObservation` как `CompositeData`, у которого
`CompositeType.getTypeName()` = `space.br1440.platform.tracing.NotTheExpectedType`
вместо `…SamplingObservation`.

**WARN (первый episode):**

```text
WARN  … JmxReadStateRegistry : JMX-контракт: CONTRACT_VIOLATION COMPOSITE_TYPE_NAME_MISMATCH на точке SAMPLING_OBSERVATION (первое или повторное после recovery)
```

**Фрагмент `tracingStatus`:**

```json
{
  "schemaVersion": 2,
  "runtime": {
    "domainMBeansAvailable": true,
    "sampling": null
  },
  "jmxContractDiagnostics": [
    {
      "point": "SAMPLING_OBSERVATION",
      "category": "CONTRACT_VIOLATION",
      "kind": "COMPOSITE_TYPE_NAME_MISMATCH",
      "lifetimeCount": 1,
      "suppressedWarnCount": 0,
      "firstSeen": "2026-08-04T11:15:01.120Z",
      "lastSeen": "2026-08-04T11:15:01.120Z",
      "lastAttemptAt": "2026-08-04T11:15:01.120Z",
      "unrecovered": true
    }
  ]
}
```

**Сопутствующие Micrometer-сигналы (типично):**

```text
platform.tracing.jmx.domain.registered{domain="sampling"} 1.0
platform.tracing.jmx.domain.readable{domain="sampling"}    0.0
platform.tracing.jmx.owner.present{domain="sampling",owner="sampler"} NaN
```

`registered=1` (MBean есть), `readable=0` (decode целого observation провален),
`owner.present=NaN` (presence владельца недоступен, пока домен нечитаем).

Те же top-level примеры для остальных доменов отличаются только `point` и тегом `domain`:

| Домен | `point` | `domain` tag |
|-------|---------|--------------|
| Scrubbing | `SCRUBBING_OBSERVATION` | `scrubbing` |
| Validation | `VALIDATION_OBSERVATION` | `validation` |
| Diagnostics | `DIAGNOSTICS_OBSERVATION` | `diagnostics` |
| Export | `EXPORT_OBSERVATION` | `export` |
| ProcessorMetrics | `PROCESSOR_METRICS_OBSERVATION` | `processor_metrics` |

---

## Пример 2. Nested type-name mismatch (Export / processor)

**Сценарий.** Top-level Export observation валиден
(`typeName = …ExportObservation`, `schemaVersion = 1`), но nested item `processor`
имеет `typeName = space.br1440.platform.tracing.NotExportProcessorValues`
вместо `…ExportProcessorValues`. Gate срабатывает **до** `ownerState` nested-владельца.

```json
{
  "jmxContractDiagnostics": [
    {
      "point": "EXPORT_OBSERVATION",
      "category": "CONTRACT_VIOLATION",
      "kind": "COMPOSITE_TYPE_NAME_MISMATCH",
      "lifetimeCount": 3,
      "suppressedWarnCount": 2,
      "firstSeen": "2026-08-04T11:20:00.010Z",
      "lastSeen": "2026-08-04T11:20:45.880Z",
      "lastAttemptAt": "2026-08-04T11:20:45.880Z",
      "unrecovered": true
    }
  ]
}
```

`lifetimeCount=3` — три scrape попали в тот же kind; `suppressedWarnCount=2` —
повторные WARN внутри rate-limit окна не печатались в лог, но счётчик lifetime
продолжал расти.

Аналогичные nested точки (тот же `point = PROCESSOR_METRICS_OBSERVATION` /
`EXPORT_OBSERVATION`, тот же `kind`):

| Nested item | Ожидаемый typeName |
|-------------|-------------------|
| Export `exporter` | `…SafeExporterValues` |
| ProcessorMetrics `watchdog` | `…ProcessorWatchdogValues` |
| ProcessorMetrics `composite` | `…ProcessorCompositeValues` |
| ProcessorMetrics `metrics` | `…ProcessorMetricsValues` |

Wire JSON **не** содержит actual/expected typeName string — в snapshot уходит только
стабильный `kind`. Различие «какой именно nested сломан» оператор восстанавливает
по симптомам домена + версии агента / golden-контракту, не по payload diagnostic.

---

## Пример 3. Повтор после recovery (episode смонтирован заново)

**Сценарий.**

1. Был mismatch → diagnostic `unrecovered=true`.
2. Агент починил typeName → успешный decode → episode считается recovered
   (`unrecovered` станет `false` на следующем snapshot, либо запись исчезнет из
   «активного» восприятия после success; lifetime-история сохраняется в registry).
3. Опять mismatch → **новый** WARN (новый episode) + `unrecovered=true` снова.

```text
WARN  … JMX-контракт: CONTRACT_VIOLATION COMPOSITE_TYPE_NAME_MISMATCH на точке EXPORT_OBSERVATION (первое или повторное после recovery)
```

```json
{
  "point": "EXPORT_OBSERVATION",
  "category": "CONTRACT_VIOLATION",
  "kind": "COMPOSITE_TYPE_NAME_MISMATCH",
  "lifetimeCount": 4,
  "suppressedWarnCount": 2,
  "firstSeen": "2026-08-04T11:20:00.010Z",
  "lastSeen": "2026-08-04T12:05:11.440Z",
  "lastAttemptAt": "2026-08-04T12:05:11.440Z",
  "unrecovered": true
}
```

`firstSeen` остаётся временем **первого** наблюдения этого `(point, category, kind)`
в процессе; `lastSeen` / `lastAttemptAt` обновляются на каждый новый failure.

---

## Пример 4. Рядом на том же раннем gate-пути: `SCHEMA_VERSION_MISMATCH`

План фиксирует `SCHEMA_VERSION = 1` и явно упорядочивает decode:
`requireCompositeTypeName` → `requireSchemaVersion` → items.

Если typeName корректен, но `schemaVersion = 99`:

```json
{
  "point": "SAMPLING_OBSERVATION",
  "category": "CONTRACT_VIOLATION",
  "kind": "SCHEMA_VERSION_MISMATCH",
  "lifetimeCount": 1,
  "suppressedWarnCount": 0,
  "firstSeen": "2026-08-04T12:10:00.000Z",
  "lastSeen": "2026-08-04T12:10:00.000Z",
  "lastAttemptAt": "2026-08-04T12:10:00.000Z",
  "unrecovered": true
}
```

```text
WARN  … JMX-контракт: CONTRACT_VIOLATION SCHEMA_VERSION_MISMATCH на точке SAMPLING_OBSERVATION (первое или повторное после recovery)
```

Это **не** новый kind плана, но соседний diagnostic того же раннего fail-closed
пути. При одновременном typeName+version drift сработает **type-name gate первым** —
`SCHEMA_VERSION_MISMATCH` в этом scrape не появится.

---

## Пример 5. Несколько доменов одновременно

Несовместимый агент / чужой exporter может сломать несколько observation сразу:

```json
{
  "jmxContractDiagnostics": [
    {
      "point": "EXPORT_OBSERVATION",
      "category": "CONTRACT_VIOLATION",
      "kind": "COMPOSITE_TYPE_NAME_MISMATCH",
      "lifetimeCount": 12,
      "suppressedWarnCount": 10,
      "firstSeen": "2026-08-04T10:00:00.000Z",
      "lastSeen": "2026-08-04T10:05:00.000Z",
      "lastAttemptAt": "2026-08-04T10:05:00.000Z",
      "unrecovered": true
    },
    {
      "point": "PROCESSOR_METRICS_OBSERVATION",
      "category": "CONTRACT_VIOLATION",
      "kind": "COMPOSITE_TYPE_NAME_MISMATCH",
      "lifetimeCount": 12,
      "suppressedWarnCount": 10,
      "firstSeen": "2026-08-04T10:00:00.050Z",
      "lastSeen": "2026-08-04T10:05:00.050Z",
      "lastAttemptAt": "2026-08-04T10:05:00.050Z",
      "unrecovered": true
    },
    {
      "point": "SAMPLING_OBSERVATION",
      "category": "CONTRACT_VIOLATION",
      "kind": "SCHEMA_VERSION_MISMATCH",
      "lifetimeCount": 2,
      "suppressedWarnCount": 0,
      "firstSeen": "2026-08-04T10:01:00.000Z",
      "lastSeen": "2026-08-04T10:04:30.000Z",
      "lastAttemptAt": "2026-08-04T10:04:30.000Z",
      "unrecovered": true
    }
  ]
}
```

Порядок в массиве — стабильная сортировка registry:
`point` → `category` → `kind` (см. `JmxReadStateRegistry#diagnosticSnapshot`).

---

## Что план **не** добавляет как новый diagnostic kind

Следующие эффекты плана видны в **метриках / counters**, но **не** создают новых
записей `jmxContractDiagnostics`:

| Эффект плана | Где видно | Почему не diagnostic record |
|--------------|-----------|------------------------------|
| Producer `decision:reasonCode` (вместо `winningRule`) | `platform.tracing.sampler.decisions{decision,reason}` — 11 FIXED_ENUM серий | Это корректные cumulative counters, не contract violation |
| Sparse map / absent owner | `owner.present=0`, counters остаются на накопленном / 0 | Honest absence, не `CONTRACT_VIOLATION` |
| Golden / classification parity | Gradle/тесты CI (`jmxWireGoldenParityVerify`) | Build-time gate, не runtime diagnostic |

Absent owner (`ownerPresent=false`) **не** порождает `COMPOSITE_TYPE_NAME_MISMATCH`:
placeholder absent-владельца кодируется той же fixed-schema `CompositeType`,
что и present-значение; gate безопасен при `ownerPresent=false`.

---

## Как читать оператору

1. Открыть `tracingStatus.jmxContractDiagnostics`.
2. Найти `kind = COMPOSITE_TYPE_NAME_MISMATCH` → несовместимый agent/autoconfigure
   wire (wrong CompositeType name) на указанном `point`.
3. Проверить `platform.tracing.jmx.domain.readable{domain}` — должен быть `0`
   для затронутого домена, пока `unrecovered=true`.
4. Сверить версии артефактов агента и starter/autoconfigure + golden
   `jmx-observation-wire-contract-golden.properties` (`SCHEMA_VERSION=1`).
5. После выката совместимого агента: дождаться успешного scrape →
   `unrecovered` снимается; `readable` возвращается в `1`.

## Связанные артефакты

- Codec gate: `JmxObservationCodec#requireCompositeTypeName`
- Wiring-доказательство: `JmxViewTypeNameMismatchWiringTest`
- Unit-доказательство: `JmxObservationCodecTypeNameTest`
- ADR: [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md)
- Runbook: [tracing-status-diagnostics](./tracing-status-diagnostics.md)
