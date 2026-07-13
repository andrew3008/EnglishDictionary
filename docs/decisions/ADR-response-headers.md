# ADR: HTTP response trace headers — custom filter vs Micrometer native

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-23 |
| Контекст | Step 9.1 (spike), `TraceResponseHeaderServletFilter`, `TraceResponseHeaderWebFilter` |
| Стек | Spring Boot **3.5.5**, Spring Framework **6.2.x**, Micrometer Tracing, OTel Java Agent **2.27.x** |

## Проблема

Нужно вернуть клиенту идентификатор trace'а в HTTP-ответе (`X-Request-Id`, `X-Trace-Id`) для поддержки и корреляции с логами. Варианты:

1. **KEEP** — платформенные `TraceResponseHeader*Filter`
2. **REMOVE** — полагаться на Micrometer/Spring Boot native
3. **HYBRID** — custom filter + будущий стандарт `traceresponse`

## Метод spike

### Spring Boot 3.5.5 / Micrometer

| Механизм | Назначение | Пишет trace-id в **response**? |
|----------|------------|----------------------------------|
| `management.tracing.propagation.produce` | Исходящие HTTP-**клиентские** запросы (`traceparent`, B3) | **Нет** |
| `management.tracing.propagation.consume` | Входящие request headers | **Нет** |
| MDC `traceId` / `spanId` | Логирование | **Нет** (не HTTP header) |
| `TraceHeaderObservationFilter` (SB 3.5 preview) | Response header | **Отменён** — revert [#44752](https://github.com/spring-projects/spring-boot/issues/44752) |

**Вывод:** в Spring Boot **3.5.5 нет** штатной auto-configuration, добавляющей `X-Trace-Id` / `traceresponse` в HTTP-ответ. Обсуждение [#44431](https://github.com/spring-projects/spring-boot/issues/44431) зафиксировало намерение перейти на draft-header `traceresponse`, но consensus в индустрии отсутствует; feature не включён в релиз.

Spring Framework [#30632](https://github.com/spring-projects/spring-framework/issues/30632) предлагает расширять `ServerHttpObservationFilter.onScopeOpened()` — это **application-level** bean, не platform default.

### Сравнение с платформенным фильтром

| Критерий | Platform `TraceResponseHeaderServletFilter` | Micrometer-only (без custom) |
|----------|---------------------------------------------|------------------------------|
| Response `X-Request-Id` / `X-Trace-Id` | **Да** (настраиваемо) | **Нет** |
| Работа с OTel Java Agent | **Да** (capture-before-chain) | N/A |
| Тайминг записи | Capture до chain, write в `finally` | N/A |
| WebFlux parity | `TraceResponseHeaderWebFilter` (`beforeCommit`) | N/A |
| Конфигурация | `platform.tracing.response.*` | — |

### Capture-before-chain (критично для Agent)

OTel Java Agent закрывает SERVER span при выходе из servlet lifecycle **до** возврата из `filterChain.doFilter()`. Поэтому:

- **Нельзя** вызывать `currentTraceId()` только в `finally` после chain — контекст уже invalid.
- **Нужно** захватить trace-id **до** `doFilter`, записать заголовок **после** chain (пока response не committed).

Реализовано в `TraceResponseHeaderServletFilter` (Variant A из architect review). Покрыто `TraceResponseHeaderServletFilterTest`.

### Ограничения (зафиксированы)

- Spring MVC async dispatch — заголовки могут быть locked до commit; documented в Javadoc фильтра.
- `management.tracing.enabled=false` **не** отключает propagation context (SB 3.2+ regression) — не использовать для управления response headers.

## Решение

**KEEP custom filters (вариант HYBRID на перспективу).**

- v1.0: сохраняем `TraceResponseHeaderServletFilter` и `TraceResponseHeaderWebFilter`.
- Заголовки по умолчанию: `X-Request-Id` (primary, `platform.tracing.response.header-name`) + всегда `X-Trace-Id`.
- Источник trace context: `TraceOperations.currentTraceId()` — абстракция над Micrometer Tracer / OTel bridge, **не** прямой доступ к Agent API.

> **Обновлено (Фаза 12, [ADR-request-id-correlation-id.md](./ADR-request-id-correlation-id.md)).** Семантика `X-Request-Id` изменена:
> теперь это **edge-stable correlation id** (входящий валидируется и форвардится без изменений; при отсутствии — UUIDv4),
> а **НЕ** trace-id. Авторитетный trace-id возвращается в `X-Trace-Id` (только для валидного span context).
> Инвариант `request_id ≠ trace_id`. Старая логика «response `X-Request-Id` = currentTraceId()» удалена.
- **Не** удаляем фильтры в пользу Micrometer до появления stable Spring Boot property для response trace header.

### Будущее (v1.1+)

- Отслеживать Spring Boot / W3C draft `traceresponse` — при stable auto-config рассмотреть:
  - dual-write (`traceresponse` + legacy `X-Trace-Id`) на migration period, или
  - делегирование в Spring bean с platform override hook.
- Связанный ADR: [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md) — разделение ролей Agent (spans) vs Micrometer (observations).

## Конфигурация (reference)

```yaml
platform:
  tracing:
    response:
      expose-request-id-header: true   # default: true
      header-name: X-Request-Id      # default
```

## Связанные артефакты

- `TraceResponseHeaderServletFilter.java`
- `TraceResponseHeaderWebFilter.java`
- `PlatformHeaders.java` — `X_REQUEST_ID`, `X_TRACE_ID`
- `TraceResponseHeaderServletFilterTest.java`
