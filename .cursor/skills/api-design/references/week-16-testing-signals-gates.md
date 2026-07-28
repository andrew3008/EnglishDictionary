# Week 16 — Testing: signals and release gates

## Контекст

Operational signals и compatibility проверяются executable gates. Snapshot/golden — только при intentional reviewable updates.

## Actuator endpoint tests

Уровни: domain mapper; operation; serialization/schema; web exposure; security; JMX (если заявлено); disabled Actuator; sensitive data.

Проверять: ID/path; method/status; params; selectors; content type; ObjectMapper assumptions; bounded payload; stable enums/fields.

## Health tests

Transitions:

```text
INITIALIZING → READY
READY → DEGRADED → READY
READY → FAILED
DEGRADED → STOPPED
```

Component name; status; aggregation; groups; HTTP mapping; detail visibility; redaction; timeout; external probe не вызывает cascading failure; optional degrade ≠ readiness false (если policy так).

## Metrics / traces tests

Точная регистрация; одна регистрация; значения после сценария; tags bounded; absence на disabled; duplicate instrumentation count; Observation low/high cardinality split; context propagation + no leakage.

## Failure injection / concurrency / time

Inject: exporter fail; timeout; invalid config; missing optional. Concurrency: singleton strategies; meter updates. Time/randomness: controllable clocks. Resource lifecycle: close on shutdown; no leak after refresh/disable.

## Contract / golden

Wire schema snapshots reviewable. Golden auto-rewrite без review — запрещён. Consumer-driven: sample apps на previous API compile+run.

## Compatibility / dependency / architecture gates

Java API binary/source diff; dependency metadata (api vs implementation leakage); ArchUnit package/module rules; publication consumer resolves starter.

## AOT / native / performance

Hints present; conditional extensions native; no unreproducible suppliers. Perf/regression: cardinality budget; probe cost; startup time bounds — где operational budget заявлен.

## Release gate stack (пример)

```text
compile + unit
  → context matrix
  → architecture
  → API/dependency diff
  → integration + operational contracts
  → publication consumer
  → AOT/native
  → release approval
```

Каждый gate: risk; owner; machine-readable result; failure message; bounded runtime; exception rule; evidence retention.

`ignoreFailures=true` регулярный → ложный контроль, хуже отсутствия gate.

## Ключевые тезисы

- Signals тестируются transitions и redaction, не «endpoint 200».
- Duplicate instrumentation и cardinality — first-class assertions.
- Gates layered; suppressions точечные и expire.
- Golden files — intentional, не автоматический шум.
