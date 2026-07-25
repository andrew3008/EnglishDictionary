# Неделя 07 — Review, анти-паттерны, матрица

## Contract Matrix (шаблон)

### Semantics state и sharing

| Type/API | Mutability | Sharing | Consistency |
|---|---|---|---|
| `RouteSet` | deep immutable | shared value | stable |
| `RuntimeSnapshot` | immutable snapshot | owned result | point-in-time |
| `liveMetrics()` | shared mutable view | borrowed | weakly consistent |
| `FrameContext` | scoped mutable | callback borrow | callback-thread only |
| `PolicyBuilder` | confined mutable | exclusive | not thread-safe |
| `ConnectionLease` | lifecycle mutable | exclusive handle | state-machine |

### Ownership и cleanup

| Type/API | Owner | Lifetime | Cleanup | After close |
|---|---|---|---|---|
| `RouteSet` | shared | unbounded | none | n/a |
| `ConnectionLease` | caller | lexical | `close()` → pool | fail fast |
| `liveMetrics()` | registry | registry bean | caller does not close | invalid after registry close |
| `FrameContext` | dispatcher | callback | none | retain forbidden |

Matrix фиксирует **consumer-observable contract**, не mechanism.

## Анти-паттерны

| # | Анти-паттерн | Дефект |
|---|---|---|
| 1 | `final` = immutable | ложная thread-safety guarantee |
| 2 | unmodifiable wrapper как copy | mutation через backing |
| 3 | getter → internal array | writable representation |
| 4 | ambiguous ownership | premature close или leak |
| 5 | resource без `AutoCloseable` | cleanup не discoverable |
| 6 | close only happy path | leak при partial init |
| 7 | blocking forever in close | shutdown hang |
| 8 | library closes injected executor | уничтожение shared infra |
| 9 | prototype resource bean | leak без ownership protocol |
| 10 | background static singleton | переживает context/tests |
| 11 | mutable config control plane | partial config без versioning |
| 12 | Cleaner as primary cleanup | release зависит от GC |

## Контрольный чек-лист

### Mutability
- [ ] Каждый public stateful type классифицирован?
- [ ] Deep vs shallow immutability?
- [ ] Hidden mutation: cursor, cache, lazy init, close?
- [ ] Equality/hashCode стабильны при допустимой mutation?
- [ ] Mutable keys/values безопасны?
- [ ] Builder reusable; built objects независимы?

### Aliasing и copies
- [ ] Caller-owned arrays/collections не сохраняются?
- [ ] Internal mutable references не возвращаются?
- [ ] Copy deep enough?
- [ ] Snapshot vs live view в name/docs?
- [ ] Read-only buffer делит storage — явно?
- [ ] Retain callback argument запрещён или documented?

### Ownership
- [ ] Owner каждого input/output/dependency?
- [ ] Ownership при failure?
- [ ] Wrapper закрывает delegate?
- [ ] Borrowed executor/registry?
- [ ] Narrow capability type у borrowed dependency?
- [ ] Evicted/cancelled resources закрыты?

### Lifecycle
- [ ] Явная state machine?
- [ ] Ready moment defined?
- [ ] Idempotent start/stop/close?
- [ ] Ops after close?
- [ ] Cleanup failures aggregated?
- [ ] Graceful shutdown bounded?
- [ ] Cancellation и in-flight work?

### Concurrency
- [ ] Safe publication?
- [ ] Atomic compound operations?
- [ ] Callback threads?
- [ ] External code под lock?
- [ ] Use-vs-close race?
- [ ] Snapshot internally consistent?

### Spring Boot
- [ ] Owner default bean vs user replacement?
- [ ] Destruction callback у resource bean?
- [ ] Prototype не для unmanaged resource?
- [ ] Background workers stop с context?
- [ ] SmartLifecycle phase и callback?
- [ ] Immutable config snapshot?
- [ ] Properties requiring restart явны?

## Evolution — compatibility triggers

| Change | Impact |
|---|---|
| snapshot → live view | **major** behavioral |
| borrowed → owned close | **major** — premature shutdown |
| owned → borrowed | **major** — leak |
| add `AutoCloseable` | programming model change |
| idempotency change | SemVer concern |
| blocking close | latency/shutdown regression |

**Must:** добавить `snapshot()` и `view()` вместо молчаливой подмены.

## Agent workflow: быстрая проверка API

```
1. Stateful public types → classify mutability
2. Constructor/getter/return → trace aliases
3. Resources → owner + close in type + example
4. Collections → snapshot|view|copy in name
5. Executors/threads → borrowed vs owned
6. Spring beans → scope + destroy + backoff paths
7. Fill 2-row matrix для top-8 types
8. Flag anti-patterns 1–12
```

## Разделение дефектов

| Mechanism fix | Contract fix |
|---|---|
| lock, copy | names, ownership transfer |
| callback annotation | state machine, close semantics |
| defensive copy in impl | snapshot vs view choice |
| synchronized block | container responsibility |

## Ключевые тезисы

1. Review = inventory → classify → alias trace → owners → lifecycle → concurrency → Spring → evolution.
2. Contract Matrix — consumer-observable, не implementation.
3. Изменения mutability/ownership/close = compatibility changes.
4. Implementation fix ≠ contract fix.
5. 12 анти-паттернов — быстрый red-flag scan.
