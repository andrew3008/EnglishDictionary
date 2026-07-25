# Неделя 08 — Spring, observability, security, evolution

## Spring Boot configuration errors

**Must** explicit policy:

| Situation | Policy |
|---|---|
| feature requested + invalid config | **fail startup** |
| optional integration absent, not requested | condition backoff |
| optional remote down | declared fail / degrade / lazy connect |

**Must-not:** typo → silently disabled feature (catch-all WARN + no-op bean).

`@ConfigurationProperties` + `@Validated`:
- property paths в violations
- cross-field invariants
- aggregate independent violations
- **must-not** expose secrets
- local validation **≠** remote connectivity check

Configuration validator **must-not** depend on runtime clients/network at early lifecycle.

## FailureAnalyzer

**Role:** startup exception → description + **concrete action**. Не меняет root failure.

**Must:**
- узкая ответственность (specific exception type)
- return `null` если cannot analyze correctly
- action с property/dependency/command, не «Contact administrator»

Registration metadata + message format = **starter contract** → integration tests.

## Auto-configuration failures

Optional absence → `@Conditional...`, **not** `ClassNotFoundException` at bean creation.

**Must-not:** catch-all Exception → WARN + no-op hiding invalid config.

User replacement bean → starter **must-not** run default validation/connection for replaced impl.

Linkage errors (`NoSuchMethodError`) → version mismatch analysis, **not** generic connection failure.

## Runtime bean errors

Mandatory invariant fail → fail bean creation; close partial resources in factory.

Background worker death **must:**
- degraded/failed state
- health/metrics update
- error handler notification
- stop accepting work per policy

**Must-not:** only uncaught stack trace in log.

`SmartLifecycle.stop` failure **must still** invoke completion callback.

Shutdown: best effort, preserve multiple failures, bounded wait.

## HTTP/error-schema boundary

Library exception hierarchy **≠** HTTP response schema.

Adapter **must** translate domain failure → stable protocol (`ProblemDetail`, RFC 9457).

**Must-not** serialize Java stack/cause cross-process. Pass: correlation id, public code, safe detail, retry metadata.

## Security и privacy

**Must-not** in messages/fields/`toString()`: passwords, tokens, connection strings, PII, full payloads.

Authentication API **may** merge distinct public causes (enumeration attack).

Machine contract (code/enum) **≠** localized UI presentation.

Exception retention in failed future/cache **must** avoid large graphs.

## Logging contract

**Rule:** intermediate layer — add context and rethrow **or** fully handle.

**Must-not:** log-and-throw duplicate stacks at every boundary.

Library **should not** dictate ERROR/WARN for application policy; prefer metrics/hooks.

Structured logging: bounded fields (`error.kind`, operation, correlation id). **Must-not** use message as metric label.

Rate limiting for repeated outage — preserve first occurrence and state transitions.

## Metrics, tracing, health

Metric labels **must** have bounded cardinality: `error.kind=timeout`, not raw URL/message.

Define count unit: attempt vs logical operation vs batch item.

Span status: domain rejection **≠** always technical error.

Health **must-not** dump full exception/secrets in public actuator response.

## Compatibility и evolution

| Change | Impact |
|---|---|
| add checked exception | source-incompatible |
| new unchecked (predictable) | behavioral — catch may miss |
| hierarchy move | catch behavior changes |
| rename/reuse error code | breaking |
| add closed enum variant | exhaustive switch |
| message text | usually ok if not promised |
| retry semantics change | operational breaking |

## Документирование error contract

`@throws` — **начало**, не конец.

**Operation template must include:**
- normal + expected negative results
- sync exceptions + async exceptional causes
- preconditions
- side effects before failure
- state after failure
- retry/idempotency
- timeout/cancellation
- sensitive diagnostic policy

Examples **must** show negative paths, not only happy path.

## Error Contract Review — 9 шагов

1. Inventory failure surfaces (methods, async, callbacks, SPI, beans, config, close)
2. Build taxonomy (6+ categories)
3. Choose representation per outcome
4. Define state guarantee
5. Define recovery (retry, idempotency, deadline, cancellation)
6. Inspect translation (leakage, lost cause, double wrap, swallowed interrupt)
7. Observability/security (log owner, bounded metrics, redaction)
8. Spring paths (config, conditions, analyzer, partial startup, background)
9. Simulate evolution

## Error Contract Matrix (шаблон)

| Operation/outcome | Category | Representation | Discriminator |
|---|---|---|---|
| key absent | expected absence | `Optional.empty()` | empty |
| lease busy | domain rejection | result variant | `Busy` |
| invalid timeout | programmer error | unchecked | `IllegalArgumentException` |
| remote unavailable | environmental | domain exception | `RemoteFailureKind` |
| publish timed out | timeout/unknown | exception | outcome field |
| SPI violation | extension defect | contract exception | extension id |

## Must / Must-not (Spring + ops)

**Must:** fail startup for invalid requested feature; FailureAnalyzer for complex causes; background failure → state/health; bounded metric labels; separate Java exception from HTTP schema.

**Must-not:** catch-and-disable; secret in analyzer/actuator; vendor leakage through facade; message parsing; background failure log-only.

## Ключевые тезисы

1. Invalid requested feature → fail startup.
2. FailureAnalyzer = description + action.
3. Logs/metrics/traces share bounded taxonomy.
4. Java exception и HTTP schema evolve separately.
5. Error Contract Review starts from outcomes inventory.
