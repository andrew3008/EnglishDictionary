# ADR: Resource merge precedence и dual-mode (Фаза 9)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-08 |
| Стек | OTel SDK **1.62.0**, Agent **2.28.1**, Spring Boot **3.5.5** |
| Supersedes | [ADR-platform-resource-override.md](ADR-platform-resource-override.md) (`order()=0`) |
| Требования | `Traces Requests.txt` §1 (identity-поля), §7 (semconv-lint), §8 (валидация) |

## Контекст

`PlatformResourceProvider` исторически работал с `order()=0` и логикой `firstNonBlank(platform, otel)`
в `createResource()`, из-за чего платформенная конфигурация перетирала явный `OTEL_SERVICE_NAME` /
`OTEL_RESOURCE_ATTRIBUTES`. Это противоречит OTel Resource SDK spec и индустриальному консенсусу.

## Решение

### Merge-семантика (корректная формулировка)

В OTel `Resource.merge(other)` **побеждает `other`**; `ResourceConfiguration` выполняет
`result = result.merge(provider.createResource())`, значит **поздний провайдер (выше `order()`)
перетирает ранний**. Поэтому реальный root cause override-бага — **не `order()=0`**, а
`firstNonBlank(platform, otel)` внутри `createResource()`. Исправляется per-key omit; `order()=100` —
про корректное «гражданство» в pipeline. *(Не использовать ошибочную формулировку «this/current wins».)*

### Приоритет источников

| Приоритет | Источник | Поведение |
|---|---|---|
| 1 | `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` / `-Dotel.*` | Platform **не пишет** ключ, если OTel задал |
| 2 | `platform.tracing.service.*` / Helm | Пишет только omit-ключи |
| 3 | `spring.application.name`, `build-info.properties` | Fallback для `service.name` / `service.version` |
| 4 | OTel Agent detectors (Host/Container/Process/Os) | Fallback для infra-attrs |
| 5 | SDK defaults (`unknown_service:java`) | Last resort |

Механизм: `PlatformResourceProvider implements ConditionalResourceProvider`, `order()=100`;
per-key omit в `ResourceAttributeResolver` через
`ResourceConfiguration.createEnvironmentResource(config)`.

### Контракт `service.version` (вариант C)

Version не задаётся в Helm/CI/config → build-info — **первичный** источник. Цепочка:
`OTEL_*` → `platform.tracing.service.version` → `build.version` (build-info, TCCL-first,
jar-root/BOOT-INF) → `Implementation-Version` (MANIFEST.MF) → отсутствует (STRICT fail / LENIENT warn).

### Dual-mode precedence (Agent vs Spring Starter)

| Режим | Кто побеждает для `service.name` |
|---|---|
| Java Agent + autoconfigure SPI | `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` (Environment provider, order MAX-1) |
| Spring Boot Starter | `otel.resource.attributes` из `application.yml` > env `OTEL_RESOURCE_ATTRIBUTES` |
| Оба режима | `platform.tracing.service.*` через resolver — только omit-ключи |

### ConditionalResourceProvider (controlled risk)

Интерфейс в `io.opentelemetry.sdk.autoconfigure.spi.internal` (нестабильный SPI). Используется осознанно:
`shouldApply` — оптимизация; корректность обеспечивает per-key omit в `createResource()`. Pinned BOM
(SDK 1.62.0). Fallback: при исчезновении SPI провайдер деградирует до обычного `ResourceProvider`.
Adapter layer сознательно не вводится (per-key omit уже и есть fallback).

### Инварианты и запреты

- `order() < Integer.MAX_VALUE - 1` (всегда до `EnvironmentResourceProvider`).
- Платформа не пишет `service.instance.id` (SDK `ServiceInstanceIdResourceProvider`, order=MAX) и
  `telemetry.sdk.*`.
- `deployment.environment` (deprecated) не используется — только `deployment.environment.name`
  (well-known: development/staging/production/test); QA-политика `qa* → test`.
- `platform.c_group` — required в STRICT (governance: атрибуция владельца сервиса).
- `otel.resource.disabled.keys` (стабильно с SDK 1.44.0, доступно в 1.62.0) — операторский механизм
  удаления нежелательных resource-ключей без правки кода.

### Starter vs Collector (зоны ответственности)

| Атрибут | Кто выставляет |
|---|---|
| `service.name`/`version`, `deployment.environment.name`, `platform.c_group`/`id`, policy.version | Application (PlatformResourceProvider) |
| `host.name`, `process.*`, `os.*` | OTel Agent built-in detectors |
| `container.id` | OTel Container detector / explicit (procfs — opt-in) |
| `k8s.pod.name`/`namespace`/`node`/`uid` | Collector `k8sattributes` (DaemonSet + Downward API) |
| `telemetry.sdk.*` | SDK only (запрещено переопределять) |

### Валидация (§8 — на старте, а не на каждом span)

Resource-ключи стабильны per-process → валидируются один раз на старте
(`ResourceValidationDiagnostics`, effective-view = resolved + otelEnv + existing): STRICT → fail-fast,
LENIENT → WARN. `ValidatingSpanProcessor` валидирует только span-specific `platform.trace.type/result`.

## Последствия

- Изменён/удалён старый тест `platform_tracing_service_name_имеет_приоритет_над_otel_service_name`.
- `k8s.pod.uid` удалён из провайдера и из `TracingProperties.Service`.
- Unified Service Tagging: `service.name`/`deployment.environment.name`/`service.version` ↔ Datadog
  `service`/`env`/`version` для корреляции traces/metrics/logs.

## Альтернативы (отклонено)

- `order()=0` глобальный override — конфликтует с OTel merge model.
- Adapter layer вокруг internal SPI — оверинжиниринг.
- Metrics/logs identity e2e в этом репо — нет metrics SDK/exporter (зона Collector / репо метрик).
