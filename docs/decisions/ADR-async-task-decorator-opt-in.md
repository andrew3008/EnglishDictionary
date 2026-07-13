# ADR: @Async TaskDecorator — opt-in composition

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-24 |
| Контекст | Фаза 2 — Wave 2; `TracingAsyncContextAutoConfiguration`, `ThreadPoolTaskExecutorContextPropagationBeanPostProcessor`, `PlatformContextTaskDecorator` |
| Стек | Spring Boot 3.x (включая 3.5+), Spring Framework 6.x, Micrometer Context Propagation, OpenTelemetry Java Agent (agent-first) |

## Проблема

При использовании Spring `@Async` с `ThreadPoolTaskExecutor` без явной пропагации контекста OpenTelemetry `Context.current()` и SLF4J MDC теряются на worker-потоке: `Span.current()` возвращает invalid span, `%X{traceId}` в логах пуст, parent-child связь рвётся.

Платформенное решение должно гарантировать пропагацию, но при этом не вмешиваться в работу прикладных executors:
- кастомные `ThreadPoolTaskExecutor` с собственными `TaskDecorator` (security context, tenant, transaction-aware wrappers),
- Spring Boot 3.5+ auto-configured `ContextPropagatingTaskDecorator`,
- OpenTelemetry Java Agent executor instrumentation,
- кастомные rejection policy и pool semantics.

Автоматическое включение собственного `TaskDecorator` при наличии `@EnableAsync` создаст двойную пропагацию, перетрёт существующие decorator'ы и сломает race/debug-сценарии.

## Решение

### Контракт

Платформенный стартер **не модифицирует** executors прикладного приложения по умолчанию. Включение возможно только явно:

```yaml
platform:
  tracing:
    context-propagation:
      async:
        enabled: true              # default false
        mode: propagate-current-context
```

При `enabled=true`:

1. Регистрируется `ContextSnapshotFactory` (Micrometer Context Propagation), если ещё нет в контексте.
2. Регистрируется `ThreadPoolTaskExecutorContextPropagationBeanPostProcessor` с `Ordered.LOWEST_PRECEDENCE`.
3. BPP для каждого `ThreadPoolTaskExecutor` читает существующий `TaskDecorator` (через reflection — Spring не выставляет getter) и оборачивает его в `PlatformContextTaskDecorator`, при этом платформенный остаётся самым внешним слоем:

```text
caller-thread:
  decorate(task) → existing.decorate(task) → snapshot.wrap(decorated)

worker-thread:
  restore OTel/MDC → existing-runtime-logic → user-task
```

### Mode

В v0.1.0 поддерживается единственный режим `propagate-current-context`: переносится только OTel Context + MDC (через `ContextSnapshotFactory.captureAll()`), span автоматически не создаётся. Создание span'а — задача прикладного кода через `@Traced` или `TraceOperations.inSpan`.

Свойство `mode` объявлено как открытый enum (String): неизвестные значения логируются как WARN и fallback'ятся на `propagate-current-context` — это обеспечивает forward-compatibility для будущих режимов в v1.1 (например, `propagate-and-create-span`).

### Ordering

`Ordered.LOWEST_PRECEDENCE` гарантирует, что платформенный BPP запускается после всех остальных (Spring Security, Micrometer executor metrics, Spring Boot 3.5 `ContextPropagatingTaskDecorator` configurer). На момент composition существующий `TaskDecorator` уже представляет финальную цепочку прочих infrastructure beans. Это покрыто отдельным unit-тестом (`ThreadPoolTaskExecutorBppTest#имеет_приоритет_LOWEST_PRECEDENCE`).

### Защита от двойной обёртки

При повторном вызове `postProcessBeforeInitialization` (например, refresh контекста) BPP детектирует, что текущий decorator уже является `PlatformContextTaskDecorator`, и пропускает повторную композицию.

### Spring Boot 3.5 ContextPropagatingTaskDecorator

Spring Boot 3.5 auto-configures `ContextPropagatingTaskDecorator` и компонует его с auto-configured executors. Наш BPP корректно его подхватит как `existing` и обернёт снаружи — это даёт композированную цепочку:

```text
platform(snapshot.wrap) → ContextPropagatingTaskDecorator → user-task
```

Двойной пропагации не происходит: `ContextSnapshot.captureAll()` использует тот же `ContextRegistry`, что и Spring Boot, поэтому повторное восстановление одних и тех же ThreadLocal-носителей идемпотентно. Тем не менее в developer guide зафиксирована рекомендация: при использовании Spring Boot 3.5+ можно отключить SB-decorator (`spring.task.execution.thread-name-prefix`-сабсекция) и положиться только на платформенный.

## Альтернативы

| Альтернатива | Отклонено, потому что |
|---|---|
| Авто-включение при `@EnableAsync` | Конфликты с кастомными executors, существующими decorator'ами, security/MDC/tenant propagation, agent instrumentation; нарушает agent-first модель. |
| `AsyncConfigurer.getAsyncExecutor()` replacement | Заменяет executor целиком, теряет композицию с существующими decorator'ами. |
| Property `decorate-existing-task-decorator` | Лишний bikeshedding; единственный безопасный путь — composition. Удалено из дизайна на review. |
| Чтение `TaskDecorator` через subclass executor'а | Требует от приложения изменения иерархии beans; неприемлемо для starter-уровня. |

## Последствия

**Плюсы:**
- Нулевое вмешательство по умолчанию — backward-compatible с любыми приложениями.
- Корректная композиция с любыми существующими decorator'ами, включая SB 3.5.
- Согласовано с agent-first моделью: starter остаётся тонким UX/config слоем.
- Forward-compatibility режимов через open enum.

**Минусы:**
- Reflection-чтение приватного поля `taskDecorator` — зависимость от внутреннего API Spring. Митигировано graceful fallback'ом (WARN + установка decorator'а без delegate).
- Активация требует ручной правки `application.yml` — не «just works». Митигировано прозрачным INFO-логом и developer guide.

## Связанные документы

- `docs/tracing/context-propagation.md` — developer guide.
- `ADR-reactor-no-inspan-v0.1.0.md` — стратегия для WebFlux/Reactor.
