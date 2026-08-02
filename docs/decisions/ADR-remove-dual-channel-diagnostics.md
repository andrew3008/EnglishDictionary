# ADR: Отказ от dual-channel diagnostics

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-08-02 |
| Контекст | Post-P6 cleanup; pre-production |
| Supersedes | [ADR-dual-channel-properties-v0.1.md](./ADR-dual-channel-properties-v0.1.md) |
| Amends | [ADR-spring-owns-only-what-spring-applies.md](./ADR-spring-owns-only-what-spring-applies.md) — раздел «Actuator read-model» |
| Related | [runbook/tracing-status-diagnostics.md](../runbook/tracing-status-diagnostics.md) |

## Контекст

`ADR-dual-channel-properties-v0.1.md` заявлял генерическую diagnostics-модель, сверяющую Spring-конфигурацию (`TracingProperties`) с эффективной конфигурацией OTel Agent через `/actuator/tracing` (секции `otelEffective`, `resourceEffective`, `otelEnvHints`) и drift-WARN (`DualChannelDriftDiagnostics`).

К моменту этого решения фактическое состояние кода таково:

- `TracingActuatorEndpoint` (потребитель `otelEffective`/`resourceEffective`) удалён; актуальный read-model endpoint — `tracingStatus` (`TracingStatusEndpoint` / `TracingStatusAssembler`), который этих секций не публикует.
- `OtelEnvHintsBuilder` и `DualChannelDriftDiagnostics` удалены при принятии `ADR-spring-owns-only-what-spring-applies.md`.
- `OtelEffectiveConfigSnapshot` и `ResourceEffectiveSnapshot` — package-private классы без production-вызывающих сторон; их production-конструкторы использовались только удалённым endpoint'ом. Единственные оставшиеся потребители — собственные unit-тесты этих классов.
- Agent-side источника эффективного `Resource`/`ConfigProperties` не существовало никогда: снапшоты читали `System`/`System.getenv()` в classloader'е приложения, а не реальные значения из classloader'а агента.

Капабилити была заявлена, но не доведена до agent-effective реализации: заявленное сравнение двух каналов конфигурации никогда не сравнивало ничего, кроме process-level входов с самими собой.

## Решение

Generic dual-channel diagnostics удаляется целиком:

- Удалены `ResourceEffectiveSnapshot`, `OtelEffectiveConfigSnapshot` и их тесты.
- `tracingStatus` не публикует generic OTel configuration dump, secция resource-идентичности drift не восстанавливается.
- Инвентарь конфигурации (`tracing-configuration-inventory`) сохраняет 20 стандартных `OTEL_*` ключей как внешний контракт OpenTelemetry (`STANDARD_OTEL_KEY`, `owner=OPEN_TELEMETRY`, `readerStatus=EXTERNAL_CONTRACT`) без production-reader'а в этом репозитории.

## Обоснование

- Нет production wiring: ни один текущий бин не строит и не публикует эти снапшоты.
- Нет agent-side source: заявленное сравнение "Spring desired vs Agent effective" никогда не имело второй стороны — Agent effective state никогда не читался из classloader'а агента.
- Нет подтверждённого SRE-потребителя: не найдено ни одного инцидента, runbook-сценария с измеримым MTTR-выигрышем или именованного consumer'а, ссылающегося на эти секции как на источник данных при расследовании.
- Drift между process-level inputs и SDK-effective state в общем случае неинтерпретируем без нормализации по каждому полю (OTel defaults, resource providers, environment normalization, extension customizers, platform-enforced overrides, sanitization) — универсальный drift-engine создаёт больше шума, чем сигнала.

## Последствия

- `tracingStatus` не содержит и не будет содержать секций `otelEffective`, `resourceEffective`, `otelEnvHints`.
- Оператор диагностирует OTel SDK/Agent конфигурацию через `OTEL_*` env в Helm/Deployment и через специализированные domain MBean (см. ниже), а не через generic dump эффективной конфигурации.
- Runbook переведён с описания удалённого `/actuator/tracing` на `tracingStatus` ([runbook/tracing-status-diagnostics.md](../runbook/tracing-status-diagnostics.md)).

## Что остаётся (retained diagnostics)

`tracingStatus` продолжает отвечать на ограниченный набор operational questions через специализированные domain MBean, уже проецируемые в read-model:

- Agent установлен и READY? — секция `agent` (`state`, `markerVisible`, `readinessEndpointPresent`, `protocolVersion`, `lifecycle`, `failureCode`, `capabilities`).
- Платформенная трассировка включена? — `tracingEnabled`, `authority`.
- Какой runtime state? — секция `runtime` (`implementation`, `mode`, `reason`, `currentTraceId`/`currentSpanId`).
- Какая policy принята? — секция `policy` (`AcceptedPolicyProjection`).
- Каковы effective sampling/validation/scrubbing/export states? — секции `runtime.sampling`, `runtime.validation`, `runtime.scrubbing`, `runtime.export`, `runtime.processorErrorsTotal`/`processorErrorsByName`, `runtime.configurationReload` — все читаются live через `PlatformTracingJmxClient` из реальных domain MBean агента, а не из process-level System properties.
- Есть ли диагностическая ошибка? — `agent.failureCode`, `runtime.domainMBeansAvailable`.

Пункт «какова фактическая resource identity» этими MBean не покрыт — см. критерии пересмотра ниже.

## Критерии пересмотра

Возвращаться к идее agent-effective resource identity (или к dual-channel diagnostics в любом объёме) стоит только при одновременном выполнении:

- зафиксированы реальные инциденты, где расхождение Spring/Agent конфигурации увеличило время диагностики;
- названы конкретные SRE-потребители и их use case;
- измеримый ожидаемый выигрыш MTTR от отображения обоих каналов;
- утверждённый закрытый список полей (не generic `ConfigProperties` dump);
- runbook с конкретными решениями для каждого вида ожидаемого расхождения (severity, expected override, masking);
- подтверждено отсутствие более простого источника данных через Kubernetes/Helm/collector backend.

До появления этих условий минимальный agent-effective resource identity snapshot (`serviceName`, `serviceVersion`, `deploymentEnvironment`, `cGroup`, публикуемый только агентом, без app-plane reconstruction и generic drift) остаётся opportunity record, а не задачей в разработке.
