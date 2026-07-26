# Week 16 — Health and Actuator endpoints

## Контекст

Health отвечает: способен ли компонент выполнять значимую функцию сейчас. Actuator endpoint — отдельный wire contract со schema, access и versioning.

## Health ≠ универсальная диагностика

Плохо: properties dump, allBeans, last100Errors. Хорошо — bounded explanation:

```json
{
  "status": "DEGRADED",
  "reasonCode": "EXPORT_QUEUE_SATURATED",
  "since": "2026-07-26T08:15:00Z",
  "recoverable": true
}
```

History/topology/config → отдельный защищённый diagnostic endpoint или логи.

## Liveness vs readiness

| | Вопрос |
|---|---|
| Liveness | процесс необратимо сломан → restart? |
| Readiness | направлять traffic сейчас? |

Типично: временный collector outage ≠ `LivenessState.BROKEN`; обязательная DB → readiness; optional telemetry degrade ≠ remove from traffic; deadlock обязательного scheduler может оправдывать negative health, если restart помогает.

Telemetry exporter → `OUT_OF_SERVICE` всего сервиса = monitoring outage → массовый eviction. Membership в readiness group — продуктовая policy.

## Status semantics

Custom status требует: условие; aggregator ordering; HTTP mapping; groups; orchestration reaction; recovery; dashboard/alert semantics.

| Status | Capability | Recovery | Реакция |
|---|---|---|---|
| UP | полностью | — | наблюдение |
| DEGRADED | частично | ожидается | alert/ticket |
| OUT_OF_SERVICE | намеренно недоступен | оператор | exclude traffic |
| DOWN | обязательная функция нет | возможно | alert, readiness false |
| UNKNOWN | недостаточно данных | — | диагностика |

`DEGRADED` вреден как «что-то не идеально» без конкретной семантики.

## Aggregation

Aggregate = policy, не «худшее слово». Определить: дерево contributors; order статусов; обязательные/опциональные capabilities; groups; exception/timeout indicator; динамическая регистрация; servlet/reactive одинаковость.

Граница contributor = стабильная operational capability. Слишком мелкая декомпозиция → нестабильные names / implementation topology.

## External dependency checks

Health check создаёт нагрузку. Определить: active probe vs local known state; timeout; cache/TTL; частота; concurrency; partial response; rate limit; startup/shutdown; cascading failure protection.

Для exporter часто лучше: last send state, queue pressure, time since success — не network call к collector на каждый `/health`.

Проверка дешевле защищаемой функции.

## Безопасность details

Риски: hostnames, DB names, paths, exception messages, credentials в URL, tenants, vulnerable versions.

Starter отвечает за безопасный payload **до** Boot access policy. Reason codes; allow-list; bounded strings; deep info на protected endpoint; redaction tests; одинаковая очистка HTTP/JMX/logs/audit.

## Actuator endpoint как API

Определить: stable ID; operation types; selectors; params; response schema; absence semantics; error mapping; caching; access/exposure; technology neutrality; versioning.

`@Endpoint` — multi-technology. `@WebEndpoint` — сознательно HTTP. Controller — только при web primitives вне endpoint model. Выбор annotation = integration contract.

## Read / write / delete

Annotation ≠ domain semantics. Для write: preconditions; idempotency; concurrency; actor; audit; validation; atomicity; partial failure; persistence across restart; rollback; effective result response.

`reset()` особенно опасен — уточнить: counters / circuit / connection / config override / queue?

Нет ясного operational workflow → не публиковать write.

## Endpoint schema

`Map<String,Object>` плохо фиксирует contract. Immutable DTO/record:

```java
public record TracingDiagnosticView(
        String mode, String sdkOwner, String state,
        String reasonCode, Instant stateSince,
        List<String> activeCapabilities) {}
```

Wire contract независимо от Java visibility. Тестировать JSON/JMX; отделять от internal runtime model.

## Версионирование

Breaking: rename ID/selector; remove/change type field; enum change; null vs absent; new required input; HTTP status; access defaults.

Additive field обычно tolerant-reader, но строгий consumer может отвергать unknown. Contract tests отражают реальных consumers. `/actuator` ≠ «internal».

## Ключевые тезисы

- Health = текущая capability, не dump.
- Liveness/readiness policy продуктовая.
- Contributor = stable capability boundary.
- Endpoint DTO = wire contract.
- Write ops требуют audit/idempotency/workflow.
