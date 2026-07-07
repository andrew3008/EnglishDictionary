# ADR: SDK mode detection и double-SDK guard (Фаза 15)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Стек | OTel SDK **1.62.0**, Agent **2.28.1**, Spring Boot **3.5.5**, Java 21 |
| Связанные | [ADR-otel-direct-integration.md](ADR-otel-direct-integration.md), [ADR-dual-channel-properties-v0.1.md](ADR-dual-channel-properties-v0.1.md), [ADR-config-precedence.md](ADR-config-precedence.md) |
| Требования | `Traces Requests.txt` §4 (динамическое поведение / диагностика) |

## Контекст

Платформа — **agent-first** ([ADR-otel-direct-integration](ADR-otel-direct-integration.md)): starter не создаёт
собственный `SdkTracerProvider`, а потребляет `OpenTelemetry`/`GlobalOpenTelemetry`. До Фазы 15 режим работы
(агент / пользовательский SDK / отсутствие SDK) был неявным — не логировался и не отображался в actuator, что
затрудняло диагностику в проде. Теория Фазы 15 предлагала `platform.tracing.sdk.mode`. Нужно ввести его **как
диагностику и явность**, не превращая в переключатель создания второго SDK (это нарушило бы ADR и породило бы
риск двойной инструментации).

## Решение

### Режимы (`platform.tracing.sdk.mode`)

`enum SdkMode { AUTO, AGENT, STARTER, EXTERNAL, DISABLED }`, дефолт `AUTO`.

| Режим | Смысл |
|---|---|
| `AUTO` | авто-детект (по умолчанию) |
| `AGENT` | обнаружен OTel Java Agent / функциональный `GlobalOpenTelemetry`; фасад поверх global |
| `EXTERNAL` | в контексте есть пользовательский `OpenTelemetry` bean; фасад поверх него |
| `STARTER` | нет ни агента, ни bean (consume-mode, SDK **не** создаётся) |
| `DISABLED` | фасад отключён → `NoOpPlatformTracing` (**единственный** режим с NoOp) |

### Резолвер (`SdkModeResolver`)

Чистая функция от наблюдаемых признаков (agentPresent, globalFunctional, userBeanPresent). Явное значение
оператора (не `AUTO`) уважается без авто-детекта. Приоритет авто-детекта: agent/global → `AGENT`; иначе
user-bean → `EXTERNAL`; иначе → `STARTER`.

«Функциональность» `GlobalOpenTelemetry` проверяется коротким пробным span'ом: у no-op реализации
`SpanContext.isValid()` всегда `false` (надёжнее сравнения по ссылке, т.к. global возвращает обёртку).

### Фасад vs NoOp (правка по ревью архитектора, принято)

В `AGENT`/`EXTERNAL`/`STARTER` starter поднимает **фасад** `PlatformTracing` (`DefaultPlatformTracing`),
делегирующий в `GlobalOpenTelemetry.get()` / пользовательский bean. `NoOpPlatformTracing` — **только** для
`DISABLED`. Бизнес-код приложения сохраняет рабочий `PlatformTracing` поверх агентского SDK; «сырые» OTel
`Tracer`/`Meter` бины предоставляет агент/micrometer-bridge, не platform-starter.

> Примечание: проект и до Фазы 15 возвращал `DefaultPlatformTracing` поверх consume-нутого SDK, а NoOp — лишь
> при отсутствии функционального SDK. PR-3 делает это **явным через режимы** и закрывает edge-case `DISABLED`.

### Double-SDK guard

Starter не создаёт `OpenTelemetry` SDK ни в одном режиме (`@ConditionalOnMissingBean(OpenTelemetry.class)` на
consume-логике). В slice-тесте `agent_mode_does_not_create_sdk` подтверждается отсутствие второго
`OpenTelemetry` bean.

### Диагностика

- `SdkModeDiagnostics(mode, agentDetected)` — иммутабельный снимок, резолвится один раз на старте в
  `TracingCoreAutoConfiguration` и логируется (INFO).
- `/actuator/tracing` → секция `sdk` (`mode`, `configuredMode`, `agentDetected`).
- Dual-channel: Spring `TracingProperties.sdk.mode` (UX) ↔ extension `platform.tracing.sdk.mode` в
  `ConfigProperties` (диагностическая лог-строка на стороне Agent). Согласовано с
  [ADR-dual-channel-properties-v0.1](ADR-dual-channel-properties-v0.1.md): extension читает только
  `ConfigProperties`, не Spring `Environment`.

## Последствия

- Режим виден в логах и actuator; диагностика проблем «почему NoOp / почему нет span'ов» упрощается.
- Поведение не изменилось: SDK по-прежнему не создаётся стартером (agent-first).

## Альтернативы (отклонено)

- `sdk.mode` как переключатель создания `SdkTracerProvider` — противоречит agent-first
  ([ADR-otel-direct-integration](ADR-otel-direct-integration.md)), риск двойного SDK.
- NoOp в `AGENT`/`EXTERNAL` при «неудачном» детекте — лишал бы бизнес-код рабочего фасада.
- Bridge Spring→`-Dotel.*` для синхронизации режима — overengineering
  ([ADR-dual-channel-properties-v0.1](ADR-dual-channel-properties-v0.1.md)).
