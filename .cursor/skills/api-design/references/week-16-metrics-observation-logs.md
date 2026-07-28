# Week 16 — Metrics, observation, logs

## Контекст

Metric/observation/log identity — публичный API. Cardinality, duplicate instrumentation и secrets/PII определяют operational cost и safety.

## Metric contract

```text
meter =
    name + type + base unit + description
  + tag keys + tag value domain
  + recording point + lifecycle + expected cardinality
```

Замена counter→gauge или `status=failed`→`outcome=error` = API change. Метрика отвечает на operational question; иначе — implementation noise.

## Имена и единицы

Хорошо: `platform.tracing.export.attempts|dropped|duration`. Плохо: class/method names, `otelHelper.count`.

Правила: стабильный namespace; plural nouns для event counters; unit отдельно; одно понятие = одно имя; backend-normalized name ≠ canonical; description = момент записи и границы.

## Тип meter

| Инструмент | Моделирует | Ошибка |
|---|---|---|
| Counter | монотонные события | queue size |
| Timer | count+duration | незавершённые ops |
| DistributionSummary | распределение величины | использовать для времени |
| Gauge | текущее значение | недолговечный объект |
| LongTaskTimer | активные задачи | замена Timer |

Counter в точке факта; Timer с согласованным error/cancel; Gauge = sampled view.

## Tags / cardinality budget

Низкая: bounded enum outcome, transport, operation category, backend type, role.

Высокая: request/trace/user ID, raw URL, exception message, SQL, unbounded bean name, large tenant set.

```text
max series = product(card(tag_i)) × instances × environments
```

High-cardinality → trace/log, не metric.

## Outcome taxonomy

Одна стабильная: `success | error | timeout | cancelled | rejected`. Exception tag только bounded; чаще `error_code`. Success + exception из handled catch без семантики — запрещён.

## Histograms / SLO

Contract: какие meters; buckets; SLO boundaries; min/max; series cost; backend assumptions. Bucket change → cardinality/cost = operational governance. Случайные boundaries без SLO — стоимость без решения.

## Отсутствующие метрики

Absence ≠ zero (disabled / path unused / binder missing / filtered / export broken / rename). Для critical capability: descriptor; enabled/state gauge; conformance check; registration test. Не создавать enabled=0 gauge на каждую мелочь.

## Meter lifecycle / duplicates

Один Spring `MeterRegistry`. Static global registry → вторая topology.

Риски: refresh re-bind; double decorators; user+auto bind; no unregister; incompatible re-register; gauge retain/lose object.

Тест: ровно одна регистрация; значения после сценария; отсутствие meters на disabled/back-off.

## Observation

Объединяет metrics+traces. Contract: contextual name; low/high cardinality keys; start/stop/error; scope ownership; nested policy; handlers; filters.

Low-cardinality → metrics+traces; high → traces only. Mutable observation context как extension без ownership → last-write-wins semantics.

## Duplicate instrumentation

Owners по boundary (HTTP server, gRPC, JDBC, manual facade). Запрещённый дубль: ручной timer + Spring; agent + proxy без policy; прямой SDK span вместо platform facade.

Тесты: точное число completed observations/spans/meters.

## Context propagation

Servlet handoff; Reactor; `@Async`; custom executors; virtual threads; scheduled; Kafka/gRPC callbacks. Тест: propagation **и** no leakage между requests. ThreadLocal-only может пройти unit и сломаться в WebFlux.

## Logs как schema

Stable event: diagnostic code; fixed event; level semantics; structured fields; bounded values; redaction; rate policy; correlation; ownership.

Levels: operational noise vs actionable. Rate-limit transitions. Secrets/PII вне messages.

## Diagnostic codes / audit / failure / degradation

Codes стабильны и actionable (runbook). Audit: actor, target, outcome, authenticated. Failure semantics согласованы с state model. Degradation + recovery transitions наблюдаемы. Operational budget: series cost, log volume, probe load.

## Ключевые тезисы

- Metric identity шире имени.
- Cardinality budget = product of dimensions.
- One instrumentation owner per boundary.
- Absence ≠ zero; Observation low/high cardinality split.
- Logs/metrics/health — разные вопросы одной state model.
