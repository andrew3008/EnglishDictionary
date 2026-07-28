# Week 16 — Operational contract fundamentals

## Контекст

Если поведение стартера нельзя однозначно увидеть, воспроизвести тестом и контролируемо изменить, его API ещё не спроектирован полностью.

## Operational contract

```text
operational contract =
    signal identity + schema + semantics
  + emission conditions + cardinality + timing
  + failure behavior + security + compatibility guarantees
```

Метрика без причины/единицы/tags/момента записи — не контракт. `DEGRADED` без влияния на readiness/HTTP — неоднозначен.

Operational API проектируется вместе с runtime state model. Иначе telemetry отражает implementation details.

## Потребители

| Потребитель | Решение | Нужно |
|---|---|---|
| App developer | подключён ли starter | startup diagnostics, effective mode |
| SRE | реагировать ли | стабильная семантика |
| On-call | где искать причину | codes, bounded details |
| Platform | conformance | adoption signals |
| Kubernetes | traffic / restart | liveness/readiness |
| Monitoring | агрегация | names, units, low cardinality |
| Security/Audit | кто изменил state | authenticated trail |
| CI/CD | можно ли релиз | machine-readable gates |

Один сигнал не закрывает все сценарии. Health ≠ forensic diagnostics; log ≠ alerting metric.

## Observation vs control plane

| Observation (read) | Control (mutate) |
|---|---|
| health, metrics, logs, traces | sampling change, reconnect, queue reset |
| condition report, diagnostic endpoint | debug mode, reload, shutdown |

Control plane требует: authz, audit, concurrency, persistence, rollback, survives restart?

**Must-not:** runtime mutation только потому что Actuator даёт `@WriteOperation`. Immutable deployment config + control plane → configuration drift.

## Operational Contract Inventory

| Поверхность | Фиксировать |
|---|---|
| Startup | events, codes, condition outcomes |
| Health | names, statuses, aggregation, groups, details |
| Metrics | names, type, unit, tags, cardinality, lifecycle |
| Logs | event names, levels, fields, redaction, rate |
| Traces | observation names, attributes, propagation |
| Endpoints | IDs, ops, schema, access, exposure |
| Audit | types, actor, target, outcome |
| Recovery | transitions, retries, reset |
| Compatibility | stability class, migration |

Intended vs effective: health contributor bean name → `/health` component; executor name → metric tag; exception simple name → tag. Inventory — вход docs, compat review, automated checks.

## Таксономия сигналов

| Сигнал | Вопрос | Длительность |
|---|---|---|
| Startup diagnostic | почему активировался | событие |
| Health | может ли сейчас | текущее состояние |
| Metric | как меняется во времени | series |
| Log | что произошло | событие |
| Trace | как прошла операция | цепочка |
| Audit | кто выполнил действие | неизменяемая запись |
| Diagnostic snapshot | effective topology | снимок |

Одна проблема → несколько согласованных проекций (counter + rate-limited WARN + status + bounded detail + span event). Корреляция без бессистемного дублирования.

## Operational state model

Сначала state model, затем проекции:

```java
public enum ExportRuntimeState {
    DISABLED, INITIALIZING, READY, DEGRADED, FAILED, STOPPED
}
```

Для каждого state: смысл; transitions; причина; влияние на операции; health projection; metrics/logs; recovery; terminal?

`DEGRADED` = частичная работоспособность + ожидаемый recovery path. `FAILED` = capability отсутствует, без внешнего вмешательства recovery не ожидается.

## Startup diagnostics

Агрегированный summary из effective state (не сырых properties), один раз, без секретов, стабильный code, disabled-by-policy ≠ missing-dependency, owner ресурсов, тестируемая модель.

**Must-not:** INFO на каждый bean creation. Один summary + точечные WARN/ERROR.

## ConditionEvaluationReport vs domain diagnostics

Condition report — infrastructure diagnostic. Не объясняет: кто owner capability; policy compatibility; почему DEGRADED; effective extension chain; exporter после customizers.

Domain diagnostics поверх container facts: immutable `PlatformRuntimeDescriptor` после wiring.

**Must-not:** парсить текст condition report в production logic.

## Effective configuration

```text
effective =
    bound values + defaults + derived
  + classpath capabilities + selected beans
  + runtime ownership + policy constraints
```

`enabled=true` ≠ работающий exporter (missing dep / user replacement / agent owner / security).

Immutable snapshot; domain vocabulary; source/reason существенных решений; secrets redacted; no mutable SDK; одинаковая семантика в log/endpoint/tests. Один источник effective mode для всех сигналов.

## Ключевые тезисы

- Operational API = контракт с SRE/K8s/monitoring, не побочный эффект.
- Observation ≠ control plane.
- Inventory intended+effective обязателен.
- State model первична; сигналы — проекции.
- Effective configuration — единый authoritative snapshot.
