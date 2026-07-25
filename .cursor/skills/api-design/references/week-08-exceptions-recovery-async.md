# Неделя 08 — Exceptions, safety, recovery, async

## Domain exceptions

Custom exception **must be** effectively immutable: final fields, defensive copies, no setters.

**Полезные fields:** stable kind/code, safe resource id, operation name, retry hint, conflicting version, validation violations.

**Must-not:** полный request payload, secrets, PII в message/fields/`toString()`.

**Must:** constructor with cause; chained exceptions.

`Serializable` exception **≠** wire protocol. Remote boundary → explicit error schema.

## Cause chain и translation

| Channel | Role |
|---|---|
| cause | causal predecessor |
| suppressed | sibling failure (e.g. close during propagation) |
| message | human explanation of **this** layer |

**Must** translate на abstraction boundary, добавляя meaning:

```java
catch (VendorTransportException ex) {
    throw new RemoteUnavailableException("Endpoint unavailable", ex);
}
```

**Must-not:**
- wrap на каждом method без новой информации
- `catch (Throwable)` → один `LibraryException`
- `throw new ClientException(ex.getMessage())` — lost cause/stack/suppressed

**Must** обрабатывать отдельно: cancellation, programmer error, domain rejection, dependency failure, `Error`.

## Suppressed и composite failures

Try-with-resources: body exception = primary; close failures = suppressed.

Composite shutdown **must** preserve suppressed list.

Policies для multiple failures: primary + suppressed | `CompositeException` | batch per-item | fail-fast.

**Must** define primary selection rule при competing failures.

## Exception safety guarantee

| Guarantee | State after failure |
|---|---|
| no-throw | не бросает в заявленных условиях |
| strong | observable state unchanged |
| basic | invariants ok, partial change possible |
| failed/terminal | object unusable except diagnostics/close |

**Must** описать конкретно для domain, не только термин.

**Ordering:** validate preconditions → затем irreversible side effects.

Remote persist с unknown outcome: local unchanged **≠** no external effect.

**Must** poison object после sync-breaking failure (parser, session) → terminal state + predictable `IllegalStateException`.

Factory failure: caller не получает partial object; impl **must** close acquired resources.

## Partial success и batch

`void publishAll(List<Event>)` **не сообщает** policy.

| Policy | Representation |
|---|---|
| all-or-nothing | exception or atomic protocol |
| best effort | `BatchResult` with per-item failures |
| stop on first | exception + progress metadata |
| independent async | per-task completion |

**Must** define: ordering, duplicates, retries, ownership of inputs/results.

## Recovery и retryability

Retryable — **свойство operation + context**, не одного exception class.

**Must учитывать:** idempotency, committed effects, attempt count, deadline, server hint, auth, circuit state.

```java
enum RecoveryHint {
    DO_NOT_RETRY, RETRY_SAME_REQUEST, RETRY_AFTER_DELAY,
    REFRESH_CREDENTIALS, RECONFIGURE
}
```

Library **must-not** infinite hidden retry.

Final exception after retries **must** preserve last/aggregate cause + attempt metadata.

## Idempotency и unknown outcome

Timeout **≠** «не выполнено». **Must** различать:

| Outcome | Meaning |
|---|---|
| definitely not started | safe retry |
| definitely rejected | no retry / fix input |
| definitely committed | no duplicate retry |
| outcome unknown | idempotency key required |

**Must** provide idempotency key semantics: scope, retention, conflict rules.

Local idempotency: `close`, `cancel`, unsubscribe — often idempotent. `start` — define consistently.

## Timeouts и deadlines

**Must** specify scope: queue wait, connect, one attempt, all retries, body read, entire operation.

**Prefer** deadline propagation (`Instant deadline`) over stacked full timeouts.

Timeout exception taxonomy **only** if consumer actions differ.

**Must** define: cancellation of underlying work, late completion, resource reuse, unknown remote result.

## Interruption и cancellation

**Must** после `InterruptedException`: rethrow **или** `Thread.currentThread().interrupt()` before translation.

**Must-not** swallow interruption.

`CancellationException` **≠** remote unavailable; **must-not** auto-retry.

`CompletableFuture.cancel` **≠** guaranteed interrupt of computation.

## Async error contract

`CompletionStage<T>`: document **both** normal and exceptional completion channels.

| API | Wrapper |
|---|---|
| `Future.get()` | `ExecutionException` (checked) |
| `CompletableFuture.join()` | `CompletionException` (unchecked) |

Domain cause — inside wrapper. Helpers **may** unwrap known layers only.

**Must-not:** `exceptionally(ex -> null)` без explicit contract.

Combinators **may** lose sibling failures — domain API **must** aggregate if all diagnostics matter.

## Callback/SPI failures

**Must define** для callback exceptions:
- abort vs isolate
- remaining callbacks
- remove faulty listener?
- logging boundary
- thread propagation

Multicast policies: fail-fast | invoke all + aggregate | isolate each | error handler.

SPI checked exceptions **restrict** all implementations. `throws Exception` **must-not** на public SPI.

Extension bug (`null`, contract violation) **≠** normal rejection → contract-violation exception + extension id.

## Resource lifecycle failures

| Phase | Contract |
|---|---|
| open failure | close already-opened children; suppressed cleanup |
| use failure | usable / reset required / terminal |
| close failure | usually still closed; idempotent retry policy |

Try-with-resources rule **must** apply to custom lifecycle orchestration.

Partial startup: started worker **must** stop if next phase fails.

## Must / Must-not (сводка)

**Must:** cause preservation; state guarantee per failure; retry with idempotency/deadline; interrupt restore; async causes documented; SPI failure isolation policy.

**Must-not:** message-only wrapping; retry all exceptions; boolean + log-only reason; lost suppressed; swallow interrupt; `Optional` masking outage.

## Ключевые тезисы

1. State after failure важнее exception type.
2. Translation добавляет meaning на boundary.
3. Timeout → outcome may be unknown.
4. Retry = operation property + idempotency.
5. Async и callbacks требуют отдельного failure contract.
