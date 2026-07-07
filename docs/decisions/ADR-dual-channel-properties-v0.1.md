# ADR: Spring `TracingProperties` ↔ OTel Agent configuration — dual-channel contract v0.1

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-25 |
| Контекст | Wave R1+; backlog item «R1 dual-channel» из `SUPPORTED.md`; pre-production v0.1.0 |
| Стек | Spring Boot 3.5.5, OTel Java Agent 2.27.x, OTel SDK 1.61.0 |
| Related | [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md), [SUPPORTED.md](../SUPPORTED.md), [runbook/actuator-tracing-diagnostics.md](../runbook/actuator-tracing-diagnostics.md) |

## Контекст

В платформе сосуществуют два независимых канала конфигурации трассировки:

1. **Spring `TracingProperties`** (`platform.tracing.*`)
   - UX-facing surface: `application.yml`, Spring `@ConfigurationProperties`, `/actuator/tracing`, `@Traced`, response headers, MDC.
   - Lifecycle: появляется **после** старта Spring `ApplicationContext`.
   - Owner: разработчик сервиса.

2. **OTel Agent configuration** (`ExtensionPropertyNames`, `ExtensionDefaults`, `PlatformTracingDefaultsProvider`)
   - SDK policy surface: BSP queue, span limits, sampler, exporter, propagators.
   - Lifecycle: применяется **до** старта Spring context, через `AutoConfigurationCustomizerProvider#addPropertiesSupplier` либо `OTEL_*` env / `-Dotel.*` system properties.
   - Owner: platform runtime / SRE через Helm/Deployment.

Часть значений семантически дублируется (BSP queue size/batch/timeout, span limits). До v0.1.0 default'ы расходились — например, `TracingProperties.Queue.exportTimeout` был `100ms`, а agent BSP export timeout default — `5000ms`. Это вызывало вопрос: «какая настройка реально применяется?».

## Рассмотренные варианты

### A. Alignment + targeted diagnostics (выбранный)

- Выровнять shared default'ы (Spring-сторона → Agent-сторона как source of truth для shared SDK/BSP/limits values).
- Targeted alignment-test по whitelist (не universal drift).
- Diagnostic-only WARN (whitelist + 3 условия), без fail-fast.
- ADR + runbook + CHANGELOG/MIGRATION.

### B. Universal fail-fast drift-test

- Тест, валящий сборку при любом расхождении любых Spring vs Agent свойств.
- **Отвергнут.** Spring-facing и Agent-facing properties не 1:1: у них разные lifecycle и разные scope (response headers vs sampler arg и т.п.). Любая попытка строгого соответствия = ложная строгость и постоянный source of friction.

### C. Полный bridge Spring → `-Dotel.*`

- `TracingProperties.queue/limits` авто-конвертируются в `-Dotel.*` system properties до запуска Agent.
- **Отвергнут.** Agent конфигурируется **до** Spring `ApplicationContext`. Bridge возможен только через JAVA_TOOL_OPTIONS / main-class wrapper / launcher — это уже задача platform runtime layer, а не Spring Boot starter'а. Для v0.1.0 — overengineering и риск.

## Решение

Вариант **A** для v0.1.0. С тремя усилениями («A+»):

1. **Alignment shared defaults.** Spring-сторона приводится к Agent-defaults для семантически дублирующихся значений. Shared OTel SDK defaults поставляются через `PlatformTracingDefaultsProvider` (внутренние значения — `OtelSdkDefaults`). Spring `TracingProperties` остаётся source of truth для Spring-facing UX settings (response headers, sampling по path, AOP, diagnostics).
2. **Targeted alignment-test.** `SharedDefaultsAlignmentTest` валидирует только перечисленный whitelist пар (BSP queue, span limits). Не universal drift.
3. **Diagnostic WARN one-shot at startup.** `DualChannelDriftDiagnostics` логирует WARN ровно при выполнении ВСЕХ трёх условий:
   - `effective.source != "default-platform"` (override действительно есть);
   - `effective.value != springValue` (значения реально расходятся);
   - property входит в whitelist shared values.

   WARN сопровождается фразой: `WARN does not mean application misconfiguration. It means two independent config channels differ.` — чтобы не порождать false alarms в support.

   Управляется флагом `platform.tracing.diagnostics.dual-channel-warn` (default `true`).

## Последствия

### Положительные

- Operator при отсутствии override видит одинаковое значение в `/actuator/tracing.queue` и `/actuator/tracing.otelEffective` (`source: default-platform`).
- Расхождение оператор узнаёт через WARN на startup + через `otelEnvHints` секцию (рекомендуемые `OTEL_*` env).
- Никакого hidden runtime magic: starter не пытается «протолкнуть» Spring properties в Agent classloader.

### Ограничения / known gaps

- **Spring-side override через YAML не применяется к Agent.** Это by design. Документировано в [runbook/actuator-tracing-diagnostics.md](../runbook/actuator-tracing-diagnostics.md): для влияния на BSP/limits использовать `OTEL_*` env (см. `otelEnvHints` в actuator).
- **Полный bridge** (Spring → `-Dotel.*`) откладывается до отдельного architecture decision, требующего решения по platform launcher / JAVA_TOOL_OPTIONS / main-wrapper layer.
- **Priority export / error-first eviction** — не покрывается этим ADR и не предоставляется стандартным BSP; отдельный backlog v1.x.

### Behavior change

- `TracingProperties.Queue.exportTimeout` default: `100ms` → `5000ms` (см. CHANGELOG.md, MIGRATION.md). Это behavior change for default configuration, **not** API breaking change.

## Checklist реализации

- [x] Alignment в `TracingProperties.java` (Queue.exportTimeout 100ms → 5000ms + cross-ref на agent BSP defaults).
- [x] `SharedDefaultsAlignmentTest` в autoconfigure-тестах.
- [x] CHANGELOG.md + `docs/MIGRATION.md` секция «v0.1.0 default changes».
- [x] `DualChannelDriftDiagnostics` + регистрация в `TracingObservationAutoConfiguration` + unit-test (4 кейса).
- [x] Runbook секция «Dual-channel WARN: когда и почему».

## Не делается этим ADR

- Универсальный drift-test (вариант B).
- Bridge Spring → Agent (вариант C, JAVA_TOOL_OPTIONS / wrapper / launcher).
- Custom `SpanProcessor` для гарантированного `DROP_OLDEST` — отдельный ADR-finding по результатам BSP probe-теста.
