# ADR: X-Request-Id как edge-stable correlation id (Вариант A)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Контекст | Фаза 12; пересмотр [ADR-request-id-equals-trace-id.md](./ADR-request-id-equals-trace-id.md) |
| Supersedes | [ADR-request-id-equals-trace-id.md](./ADR-request-id-equals-trace-id.md) |
| Связанные ADR | [ADR-response-headers.md](./ADR-response-headers.md), [ADR-outbound-propagation.md](./ADR-outbound-propagation.md) |

## Проблема

Прежнее решение «response `X-Request-Id` = trace-id» противоречит требованиям (`Traces Requests.txt`): «X-Request-Id — это **не** trace_id; отдельный correlation/support identifier; генерировать при отсутствии; валидировать». Решение ещё не в проде — «золотое окно» для чистого перехода без legacy-compat.

## Решение (Вариант A — edge-stable correlation id)

1. **`X-Request-Id` = edge-stable correlation id.** Генерируется один раз на edge при отсутствии (UUIDv4), валидируется и **форвардится без изменений** через хопы. Пригоден для лог-корреляции (как Envoy/Nginx/AWS ALB), но это correlation id, **не** trace identity.
2. **Авторитетный сквозной идентификатор — trace-id** (W3C `traceparent` / response `X-Trace-Id`). Основной механизм end-to-end поиска (Jaeger/Kibana).
3. **Инвариант: `request_id ≠ trace_id`.** Запрещено класть trace-id в `X-Request-Id` (inbound/outbound/response).
4. **Response:** `X-Request-Id` = correlation id (входящий/сгенерированный), `X-Trace-Id` = trace-id (только для валидного span context, не all-zeros).
5. **Outbound:** `propagate-request-id=true` по умолчанию (форвард — суть модели), на доверенные destination.
6. **Ретраи:** correlation id НЕ меняется при сетевых ретраях; конкретный хоп/попытку различает `span-id` (OTel), а не смена `X-Request-Id`.

## Security (CWE-113/174/180)

Входящее значение — недоверенный ввод. Канонизация (trim) до валидации; allowlist-формат `[A-Za-z0-9_-]`, лимит 128; стратегия **reject-and-regenerate** (не «тихая мутация в `_`»): любое несоответствие/CRLF/control-char/превышение длины -> отбрасываем и генерируем новый UUIDv4 + audit-лог (one-shot/rate-limited, без утечки значения). Реализация — zero-allocation (`space.br1440.platform.tracing.core.propagation.RequestIdSupport`).

## Дополнение: RequestIdSupport как core utility

Изначальный API SPI (`api.propagation.RequestIdSupport` + `RequestIdSupports` holder через `ServiceLoader`) оказался ложной точкой расширения: внешний provider отсутствует, единственная реализация жила в core, а API получал classpath trap через eager `ServiceLoader`. Это также закрепляло constant-interface antipattern для `MAX_LENGTH`.

Решение: свернуть request-id validation/resolve в статическую `@UtilityClass` `core.propagation.RequestIdSupport`, удалить API SPI/holder и `META-INF/services` descriptor, а три production call-site (`DefaultInboundTraceControlExtractor`, servlet filter, webflux filter) перевести на прямые static-вызовы. Совместимые wrappers/deprecated bridges не добавляются, потому что решение ещё не вышло в production.

Последствия: request-id поведение является platform invariant, а не public extension API. Будущие `RequestIdPolicy`, value object `RequestId`, multiple header names или per-service max length требуют отдельных ADR.

Уточнение от 2026-07-19: временное исключение `OtelTraceparentReaders` закрыто Slice C1; holder и provider descriptor удалены, а ArchUnit теперь полностью запрещает `api.. -> ServiceLoader`. Прямой импорт `core.propagation.RequestIdSupport` из webmvc/webflux autoconfigure остаётся допустимым: существующие ArchUnit-ограничения запрещают зависимость на `core.runtime.versioned`, а не на весь core.

## Отвергнутые альтернативы

| Альтернатива | Почему нет |
|--------------|------------|
| `request_id = trace_id` (старый ADR) | Противоречит требованию; X-Request-Id перестаёт быть клиентским correlation id |
| Строго per-hop (новый id на каждом хопе, не форвардить) | Расходится с Envoy/Ingress (ждут forward); невозможна лог-корреляция по correlation id |
| `X-Request-Id` = синоним trace-id (из внешнего отчёта на ошибочной посылке) | Нарушает инвариант `request_id ≠ trace_id` |

## Внутреннее оповещение (QA/Frontend/потребители API)

`X-Request-Id` — correlation id для лог-корреляции; **авторитетный сквозной поиск трейса — по `X-Trace-Id` / `traceparent`**. Migration-compat слой не нужен (нет legacy-клиентов в проде).
