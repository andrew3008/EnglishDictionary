# ADR: Единый precedence-контракт конфигурации (Фаза 15)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Стек | OTel SDK **1.62.0**, Agent **2.28.1**, Spring Boot **3.5.5**, Java 21 |
| Связанные | [ADR-resource-merge-precedence.md](ADR-resource-merge-precedence.md), [ADR-dual-channel-properties-v0.1.md](ADR-dual-channel-properties-v0.1.md), [ADR-sampler-compose.md](ADR-sampler-compose.md), [ADR-named-spi-sampler-propagator.md](ADR-named-spi-sampler-propagator.md), [ADR-sdk-mode-detection.md](ADR-sdk-mode-detection.md) |
| Требования | `Traces Requests.txt` §1 (identity), §4 (конфигурируемость без кода / «на лету») |

## Контекст

Конфигурация платформенной трассировки приходит из нескольких источников (OTel `ConfigProperties`:
env / sysprop; Spring `Environment`; Helm/`application.yml`; build-info; runtime JMX) и читается **двумя
каналами** ([ADR-dual-channel-properties-v0.1](ADR-dual-channel-properties-v0.1.md)): Spring-starter и
agent-extension. Фаза 15 добавляет named SPI (`platform` sampler, `platform-trace-control` propagator) и
mode-detection, поэтому precedence нужно свести в **один контракт**, чтобы исключить расхождения между
режимами и подсистемами.

Документ не вводит нового поведения — он формализует и объединяет уже принятые правила.

## Решение

### 1. Identity (resource-атрибуты) — свод [ADR-resource-merge-precedence](ADR-resource-merge-precedence.md)

| Приоритет | Источник | Поведение |
|---|---|---|
| 1 | `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` / `-Dotel.*` | Platform **не пишет** ключ, если OTel задал (per-key omit) |
| 2 | `platform.tracing.service.*` / Helm | Пишет только omit-ключи |
| 3 | `spring.application.name`, `build-info.properties` | Fallback для `service.name`/`service.version` |
| 4 | OTel Agent detectors (Host/Container/Process/Os) | Fallback для infra-attrs |
| 5 | SDK defaults (`unknown_service:java`) | Last resort |

Инвариант закреплён тестом `ResourceMergeIntegrationTest.otel_service_name_побеждает_platform`
(реальный `EnvironmentResourceProvider`, order MAX-1, мержится последним и перетирает).

### 2. Sampling policy — startup vs runtime

| Слой | Источник | Когда |
|---|---|---|
| Startup | `ConfigProperties` (`-D`/`OTEL_*`): enabled / ratio / drop-paths / force-values / route-ratios | На бутстрапе агента/SDK (общий `PlatformSamplerBuilder`) |
| Runtime «на лету» | JMX → `SamplerStateHolder` (один и тот же holder для inline и named SPI путей) | После старта, без рестарта (требование §4) |

Runtime-канал **перекрывает** startup для тех же ключей: оператор/Spring пушит новую политику в
`SamplerStateHolder`, не пересоздавая sampler. И named (`otel.traces.sampler=platform`), и inline-customizer
используют **один** holder — поэтому JMX-управление действует в обоих путях
([ADR-named-spi-sampler-propagator](ADR-named-spi-sampler-propagator.md), [ADR-sampler-compose](ADR-sampler-compose.md)).

### 3. Propagators

| Случай `otel.propagators` | Результат |
|---|---|
| не задан (дефолт OTel `tracecontext,baggage`) | `addPropertiesCustomizer` дописывает `platform-trace-control` → `tracecontext,baggage,platform-trace-control` |
| задан явно (вкл. через ENV) **без** `platform-trace-control` | дописывается `platform-trace-control` (customizer читает уже смерженный конфиг, ENV-aware) |
| задан явно **с** `platform-trace-control` | без дубля (singleton named provider) |
| `none` | платформенный пропагатор **не** добавляется (оператор отключил всё) |

`addPropertiesCustomizer` (а не `addPropertiesSupplier`) выбран сознательно: supplier имеет низший приоритет
и игнорируется при ENV-вводе ([ADR-named-spi-sampler-propagator](ADR-named-spi-sampler-propagator.md) §дефолты).

### 4. SDK mode (`platform.tracing.sdk.mode`)

| Приоритет | Источник | Поведение |
|---|---|---|
| 1 | Явное значение оператора (`AGENT`/`STARTER`/`EXTERNAL`/`DISABLED`) | Уважается без авто-детекта |
| 2 | `AUTO` (дефолт) → авто-детект | agent/global → `AGENT`; иначе user-bean → `EXTERNAL`; иначе → `STARTER` |

Свойство — **диагностика и явность**, не создание SDK (agent-first,
[ADR-sdk-mode-detection](ADR-sdk-mode-detection.md)). `NoopTraceOperations` — только для `DISABLED`.

### 5. Dual-channel (Spring vs Agent)

Spring-starter читает `TracingProperties` (`Environment`); agent-extension читает только `ConfigProperties`
(env/sysprop) + `PLATFORM_TRACING_*` bridge. Bridge Spring→`-Dotel.*` отвергнут как overengineering
([ADR-dual-channel-properties-v0.1](ADR-dual-channel-properties-v0.1.md)). Поэтому в чистом agent-режиме
конфигурация платформы — только через `otel.*`/`platform.tracing.*` в ConfigProperties, а Spring-канал
используется в consume-mode.

## Последствия

- Один справочник precedence для review/runbook; отдельные ADR остаются источником деталей по своим зонам.
- Тест-инвариант identity уже зелёный (`ResourceMergeIntegrationTest`); политики sampler/propagator покрыты
  тестами Фазы 15 (PR-1/PR-2).

## Альтернативы (отклонено)

- Единый «config server» / централизованный override-слой — overengineering для agent-first модели.
- Bridge Spring→agent (`-Dotel.*` из `TracingProperties`) — отвергнут отдельным ADR.
