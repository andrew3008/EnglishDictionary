# ADR: X-Request-Id равен trace-id

| Поле | Значение |
|------|----------|
| Статус | **Superseded by [ADR-request-id-correlation-id.md](./ADR-request-id-correlation-id.md)** (2026-06-09, Фаза 12) |
| Дата | 2026-05-24 |
| Контекст | Wave 4 (N-04), `TraceResponseHeaderServletFilter`, `TraceResponseHeaderWebFilter` |
| Связанные ADR | [ADR-response-headers.md](./ADR-response-headers.md), [ADR-request-id-correlation-id.md](./ADR-request-id-correlation-id.md) |

> **Superseded (Фаза 12).** Решение «response `X-Request-Id` = trace-id» отменено: оно противоречит
> требованию `Traces Requests.txt` («X-Request-Id — это не trace_id»). Новый контракт —
> `X-Request-Id` = edge-stable correlation id (`request_id ≠ trace_id`), trace-id остаётся в `X-Trace-Id`.
> См. [ADR-request-id-correlation-id.md](./ADR-request-id-correlation-id.md). Решение не было в проде —
> переход чистый, без migration-compat.

## Проблема

Платформа возвращает клиенту HTTP-заголовок `X-Request-Id` (настраиваемый через
`platform.tracing.response.header-name`) для корреляции запроса с логами и трассировкой.
Нужно зафиксировать семантику: что именно попадает в этот заголовок.

## Решение

**`X-Request-Id` = W3C trace-id активного span'а запроса** (32 lowercase hex-символа).

Дополнительно всегда выставляется `X-Trace-Id` с тем же значением для обратной совместимости
(см. [ADR-response-headers.md](./ADR-response-headers.md)).

Источник значения: `TraceOperations.currentTraceId()` — capture-before-chain в Servlet,
`beforeCommit` в WebFlux.

### Что это не означает

| Утверждение | Статус |
|-------------|--------|
| `X-Request-Id` = span-id | **Нет** — только trace-id |
| `X-Request-Id` = произвольный UUID приложения | **Нет** — не генерируется отдельно |
| Клиент может задавать trace-id через входящий заголовок | Зависит от propagation (W3C/B3); response всегда отражает **активный** trace запроса |

### Почему trace-id, а не отдельный request-id

1. Единая корреляция: лог (`%X{traceId}`), Jaeger/Tempo и HTTP-ответ используют один идентификатор.
2. Нет второго UUID на каждый запрос — меньше путаницы в support-инцидентах.
3. Соответствует industry-практике «request id = trace id» в distributed tracing (Stripe, Google Cloud Trace docs).

## Конфигурация

```yaml
platform:
  tracing:
    response:
      expose-request-id-header: true   # default: true
      header-name: X-Request-Id      # значение = trace-id
```

Отключение: `expose-request-id-header: false` — заголовок primary не выставляется;
`X-Trace-Id` по-прежнему добавляется для compat (см. Javadoc фильтров).

## Последствия

- Клиенты, ожидающие opaque request-id (не равный trace-id), должны использовать свой заголовок
  на уровне приложения — платформенный контракт фиксирован.
- При head-sampling DROP span не создаётся — заголовок может отсутствовать (корректный no-op).

## Альтернативы

| Альтернатива | Почему отклонена |
|--------------|------------------|
| Отдельный UUID в `X-Request-Id`, trace-id только в `X-Trace-Id` | Дублирование идентификаторов, сложнее support |
| `X-Request-Id` = span-id | Span-id меняется внутри trace; не подходит для end-to-end корреляции |
