# Неделя 08 — Контракт ошибок: основы

## Контекст

Контракт ошибок — **отрицательное пространство** operation. Signature успеха описывает половину протокола. Consumer строит retry, fallback, shutdown и observability по error semantics.

## Error contract шире exception type

**Must включать:**
- форму отрицательного исхода
- taxonomy и hierarchy
- fields/error codes
- exception safety (state after failure)
- retryability и idempotency
- timing: sync / deferred / callback
- cancellation/interruption
- logging, metrics, tracing
- security/redaction
- compatibility

Один `ClientException` **не решает** эти вопросы.

## Таксономия failures

| Category | Суть | Typical form | Retry? |
|---|---|---|---|
| programmer error | violated precondition/protocol | `IllegalArgumentException`, NPE | fix code |
| domain rejection | valid input, rule rejects | result variant | domain-specific |
| environmental | I/O, network, exhaustion | domain exception | context-dependent |
| configuration | invalid setup | startup failure | reconfigure |
| cancellation/timeout | external stop / budget | `TimeoutException`, cancelled | idempotency-aware |
| protocol/extension | SPI/callback violation | contract exception | fix extension |
| internal/fatal | invariant broken, `Error` | propagate/diagnostics | not for consumer recovery |

**Must-not** смешивать categories в одном catch boundary.

## Exception vs result vs Optional

| Form | Когда |
|---|---|
| **Exception** | abrupt completion; normal contract не выполнен |
| **Result variant** | negative outcome — часть domain protocol; caller **обязан** branch |
| **Optional** | единственный negative = absence; diagnostics не нужны |
| **Partial result** | batch/stream с explicit policy |

```java
sealed interface AcquireResult {
    record Acquired(Lease lease) implements AcquireResult {}
    record Busy(Duration retryAfter) implements AcquireResult {}
}
```

Infrastructure failure при result type **может** оставаться exception.

**Must-not:** exception как обычный iterator sentinel или control flow.

## Две поверхности (согласованные)

```java
Optional<Value> find(Key key);           // expected absence
Value require(Key key) throws MissingValueException;  // absence = failure
```

Разные names, согласованная semantics.

## Checked vs unchecked

| Checked | Unchecked |
|---|---|
| recovery — обязанность immediate caller | violated preconditions, invalid state |
| failure specific to operation | failures caller не исправит локально |
| stable taxonomy | functional interfaces без throws burden |

**Must-not:** считать checked = recoverable, unchecked = unrecoverable. Одна причина recoverable на одном уровне, fatal на другом.

Добавление checked exception в public method = **source-incompatible** (major).

## Hierarchy по meaning

**Must** классифицировать по recovery meaning:
- `AuthenticationException`, `VersionConflictException`, `RemoteUnavailableException`

**Must-not** по mechanism/place:
- `Http500Exception`, `NettyExceptionWrapper`

Root exception **только** если есть общий recovery boundary. Слишком общий catch скрывает programmer errors и cancellation.

## Machine-readable discriminator

**Must:** type, enum/code, typed fields для programmatic branching.

**Must-not:** parse message, regex на `contains("timeout")`, `getCause().getCause()` vendor type.

## Standard exceptions — consistency

| Exception | When |
|---|---|
| `NullPointerException` | null forbidden — `Objects.requireNonNull` |
| `IllegalArgumentException` | invalid parameter value |
| `IllegalStateException` | wrong lifecycle/state |
| `UnsupportedOperationException` | structurally present, not supported |

Одинаковое нарушение **must** бросать одинаковую category во всей library.

## Must / Must-not

**Must:**
- inventory negative outcomes per operation
- разделять programmer error, domain rejection, environment
- expected rejection → result/data где caller обязан branch
- stable discriminator (type/code/field)
- документировать state after failure

**Must-not:**
- `throws Exception`
- one exception to rule them all без kinds
- message parsing для branching
- `Optional` для всех failures (outage = not-found)
- exception для expected domain branch (busy, not-found)

## Потребители контракта

| Consumer | Использует |
|---|---|
| application developer | catch/fallback |
| platform module | translation на следующий уровень |
| extension author | что можно бросать из SPI |
| Spring container | continue startup? |
| SRE | misconfig vs outage vs defect |
| monitoring | bounded grouping |
| tests | state, causes — не console text |

## Validation contract

| Layer | Example |
|---|---|
| syntactic | parse, type, range |
| structural | required combinations |
| semantic | domain invariant |
| environmental | endpoint reachable — **explicit I/O contract** |

Programmer precondition → fail fast. Form/schema validation → aggregate violations с stable order и machine-readable path (`export.retry.max-attempts`).

**Must-not** создавать nominally valid domain type и откладывать deterministic validation до первого `execute()`.

## Sealed result hierarchy

Exhaustive switch делает новый variant **visible evolution event**. Закрытая hierarchy — когда taxonomy контролирует author и готов к major при расширении.

Failure variant **must-not** carry arbitrary `Throwable` через serialization/async boundaries.

## Ключевые тезисы

1. Error contract = negative space operation.
2. Taxonomy по meaning и recovery, не по месту возникновения.
3. Expected domain branch → result type.
4. Message — для человека, не code.
5. Checked addition = source-breaking.
6. Validation at boundary — invariant после construction.
7. Разные consumers читают разные части contract.
