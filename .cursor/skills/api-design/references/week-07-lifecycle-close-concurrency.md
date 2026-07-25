# Неделя 07 — Lifecycle, close, concurrency, Spring

## Lifecycle как state machine

**Must** описать явные states и transitions, например:

```
NEW → STARTING → RUNNING → STOPPING → TERMINATED
         ↓                      ↓
       FAILED ←─────────────────┘
```

Для каждого state: разрешённые operations, concurrent transitions, повторные calls, terminal observability.

`start()` synchronous до ready **несовместим** с `start()` только инициирует запуск — **различать** в name/return type.

## Construction и initialization

**Must:** factory возвращает ready-to-use object **или** exception.

**Must-not:** публичный `init()` создаёт partially initialized state.

Constructor/factory с несколькими resources **must** закрыть уже открытые при failure. **Must-not** публиковать `this` из constructor (registry, callback, background thread).

## AutoCloseable protocol

`AutoCloseable` **не определяет:** обязательность close, owner, idempotency, thread safety, ops after close, blocking/timeout.

**Must добавить** в domain contract. Domain interface **should** сужать `throws Exception`.

`Closeable.close()` **requires** idempotency. Для library resources idempotent close **strongly recommended**.

## Try-with-resources

**Must:** основной example resource-returning API — try-with-resources.

Порядок: init слева направо, close **в обратном** порядке.

Suppressed exceptions **must** сохраняться в diagnostics; **must-not** терять при composite cleanup.

## Semantics close()

**Must определить:**
- linearization point (перестать принимать новую работу)
- operations after close (fail fast / read-only metadata / no-op)
- close failure → object **обычно** всё равно closed
- blocking policy — отдельные `stopAccepting()`, `awaitTermination(timeout)`, `closeAsync()`

**Must-not:** бесконечный graceful wait без explicit contract.

Composite close **must:**
1. прекратить приём работы
2. drain/cancel active work
3. закрыть owned children в dependency order
4. собрать secondary failures
5. отметить terminal state

Ownership graph **must** различать owned vs borrowed edges. Wrapper **must-not** закрывать borrowed delegate.

## Async lifecycle и cancellation

**Must** различать initiation и completion (`CompletionStage<Void> stopAsync()`).

Cancellation contract **must** определить: cooperative points, interrupt, partial effects, cleanup, retry, status после race.

**Must** разделять: stop accepting / drain / cancel / release / signal termination.

Timeout **must** определить: clock, phases, reaction on expiry, owner after timeout.

## Thread confinement и thread-safety

| Term | Meaning |
|---|---|
| not thread-safe | concurrent calls запрещены |
| thread-compatible | instances independent; одна instance needs external sync |
| conditionally thread-safe | depends on delegate/config |
| thread-safe | documented ops concurrent |
| immutable | safe after safe publication |

«Thread-safe» **must** уточнять: какие methods concurrent, linearization, callbacks, blocking, reentrancy, close races.

Thread confinement = exclusive ownership одним thread/event loop. **Must** документировать owner thread и violation behavior.

## Atomicity и caches

Отдельно thread-safe methods **≠** atomic compound operation. **Must** предоставлять domain compound ops (`tryAcquire`, не `isAvailable` + `acquire`).

Cache **must** определить: eviction, closing evicted values, duplicate construction, failure caching, key/value mutability.

**Must-not:** weak/soft references как lifecycle protocol для sockets/files/threads.

## Callbacks, subscriptions, workers

Registration **must** иметь unsubscribe protocol: `Subscription subscribe(...)` + caller-owned close.

Callback contract **must:** immutable event?, retain?, buffer ownership, thread, reentrancy, exception handling.

Library thread **must** определить: daemon, naming, uncaught handler, stop/join.

Borrowed executor: library **must-not** shutdown; **must** прекратить submissions и await/cancel **свои** tasks.

Owned executor: **must** shutdown в `close()`; в Spring — отдельный bean с container-managed destruction.

## GC, Cleaner, finalization

Reachability **≠** timely resource release. **Must-not** использовать finalizers. Cleaner — **safety net only**, не замена `AutoCloseable`.

## Spring integration

| Scope | Ownership rule |
|---|---|
| singleton | **не** = immutability или thread safety; mutable state needs concurrency design |
| container singleton | `@Bean` destroyMethod / `AutoCloseable`; starter **must** отдавать resource container |
| prototype | destruction **не** вызывается container → caller **must** cleanup |
| shorter scopes | bean длинного scope **must-not** удерживать shorter-lived instance напрямую |

Auto-configuration resource **must:** register bean, destroy semantics, context lifecycle, no duplicate hidden resource, backoff on user replacement.

**Must-not** закрывать borrowed executor/registry/transport inject-нутый в wrapper.

Configuration: immutable snapshot (`record` + `List.copyOf`); **must-not** mutable `@ConfigurationProperties` как shared runtime control plane без sync.

Runtime reload **must:** atomicity, validation failure, in-flight ops, old/new ownership, rollback, versioning.

## Mutability/Ownership/Lifecycle Review — 8 шагов

1. Inventory stateful types (collections, buffers, builders, streams, resources, beans)
2. Classify mutability
3. Trace aliases (in/out/elements/delegates/callbacks/caches)
4. Assign owners per edge (creator, owner on success/failure, cleanup)
5. Draw lifecycle state machines
6. Verify concurrency (publication, compound ops, close-vs-use)
7. Verify container integration (scopes, init/destroy, backoff paths)
8. Simulate evolution (snapshot↔view, async close, reload, replacement bean)

## Must / Must-not (сводка)

**Must:** explicit state machine; idempotent close; composite close all owned; borrowed executor не закрывается; immutable config snapshot; container-managed starter resources.

**Must-not:** `init()` после construction; blocking forever in close(); library closes injected executor; prototype resource без close protocol; static registries переживают context; in-place setters на singleton config.

## Weak references — не lifecycle

Weak/soft references **must-not** управлять sockets, files, threads, transactions. Допустимы для memory-sensitive caches, **не** как release protocol.

## Evolution triggers (mutability/ownership)

| Change | SemVer concern |
|---|---|
| defensive copy → live view | major behavioral |
| library starts closing caller dependency | major |
| add required `close()` to type | programming model change |
| close becomes blocking/unbounded | operational |
| mutable config bean → immutable snapshot | usually safe; reverse — risky if live updates relied |

## Subscription vs weak listener

**Must:** deterministic `Subscription` handle для registration lifetime.

Weak listener **must-not** заменять unsubscribe для важных events/resources.

## Ключевые тезисы

1. Lifecycle = explicit state machine.
2. `AutoCloseable` делает cleanup discoverable, не определяет full contract.
3. Composite close закрывает all owned, собирает failures.
4. Thread confinement — форма ownership.
5. Spring singleton ≠ thread-safe; prototype cleanup — client responsibility.
6. GC/Cleaner — safety net, не primary lifecycle.
7. Mutability/ownership changes = compatibility changes.
