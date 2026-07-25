# Неделя 03 — Lifecycle, concurrency и Spring Boot states

## State machine vs enum поля

**Must not:** публиковать enum состояния без transition contract — это не state machine.

**Must определить для каждого перехода:**

| Элемент | Вопрос |
|---|---|
| Precondition | Из каких состояний вызов разрешён? |
| Trigger | Кто инициирует? |
| Effect | Какие ресурсы/события? |
| Postcondition | Что истинно после успеха? |
| Failure | В каком состоянии объект при ошибке? |
| Concurrency | Два одновременных вызова? |
| Idempotency | Повторный вызов — безопасен, игнор, запрет? |

### Transitional states

**Must публиковать** `STARTING`/`STOPPING`, если потребитель принимает осмысленное решение: ждать, отменять, readiness, запрет операций.

**Must not:** раскрывать промежуточные фазы без устойчивого действия — лучше `CompletionStage<ReadyRuntime>`.

### Typestate / фазовые интерфейсы

**Must рассматривать typestate для:** коротких линейных протоколов с высокой ценой sequencing error.

**Must not:** typestate как bean type долгоживущего Spring-компонента — публиковать готовую capability, lifecycle в адаптере.

## Concurrency расширяет пространство состояний

**Must not:** ограничиваться «thread-safe» без деталей.

**Must определить:**

- thread safety объекта
- atomicity операций
- visibility результатов
- ordering callbacks
- reentrancy
- cancellation semantics
- exactly-once / at-most-once / at-least-once

**Must:** linearization point — логический момент, когда операция считается произошедшей (влияет на гонки register/dispatch).

### Async result states

`CompletionStage<T>` — pending / success / exceptional, но **must** определить отдельно:

- момент принятия операции
- отмена underlying work
- executor continuations
- tracing/security context propagation
- timeout = отказ ожидания или отмена работы
- partial effect после exceptional completion

## Freshness и снимок согласованности

**Must not:** nullable value + timestamp как единственная модель кэша.

**Must:** моделировать fresh/stale/expired/refreshing, если влияет на решение потребителя; иначе библиотека реализует policy сама.

**Must:** snapshot method обещает согласованный срез — определить атомарность и eventually consistent поля.

## Spring Boot: четыре уровня валидности конфигурации

| Уровень | Пример | Механизм |
|---|---|---|
| Синтаксический | строка → Duration | Binder/converter |
| Локальный | timeout > 0 | Bean Validation / value object |
| Межполевой | endpoint обязателен в HTTP mode | class validator / resolver |
| Средовой | endpoint достижим | startup probe / runtime health |

**Must:** разделять уровни; синтаксис → fail-fast детерминированно; среда → degraded по policy продукта.

## Bindable properties ≠ runtime model

```java
// Must not: dependency runtime-компонентов
@ConfigurationProperties("platform.export")
record ExportProperties(Boolean enabled, String endpoint, Duration timeout, String mode) {}

// Must: resolver после binding
sealed interface ExportConfiguration {
    record Disabled() implements ExportConfiguration {}
    record Http(URI endpoint, PositiveDuration timeout) implements ExportConfiguration {}
}
```

## Defaults как генераторы состояния

**Must:** default входит в configuration contract; различать:

- default библиотеки / Spring Boot / classpath / derived / environment-specific
- отсутствие значения как отдельный смысл

**Must not:** context-sensitive default без наблюдаемой effective configuration в diagnostics.

## Aliases и canonical form

**Must:** после binding сводить aliases к canonical model; явный conflict policy (новый приоритетнее / запрет / совпадение значений).

**Must not:** молчаливое «последний источник победил».

## Bean graph как модель композиции

**Must определить:**

- capability целиком или partial graph допустим
- обязательные beans
- где разрешена замена
- несколько candidates — policy
- mutually exclusive conditions
- отсутствие bean = disabled / error / fallback

### Partial auto-configuration

**Must not:** случайная частичность как третий режим между enabled и disabled.

**Must:** атомарный согласованный набор через одну condition + validated model **или** явно optional sub-capabilities.

### ConditionalOnMissingBean

**Must:** определить required semantics пользовательского bean: lifecycle, thread safety, metrics, ownership, failure behavior.

**Must not:** condition по nominal type без semantic contract replacement.

## Согласованность operational signals

**Must:** один semantic state согласованно в Java API, configuration, beans, readiness/health, metrics, logs, migration.

| Противоречие | Must not |
|---|---|
| `enabled=false`, health `UP` с именем capability | ops ≠ config |
| runtime degraded, Java API — обычный success | behavior ≠ ops |

## Canonicalization

**Must:** несколько эквивалентных представлений → одна canonical form (legacy `0`, absent, `enabled=false` → `Disabled`).

**Must:** логировать deprecated representations при совместимости.
