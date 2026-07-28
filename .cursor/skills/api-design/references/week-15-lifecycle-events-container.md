# Week 15 — Lifecycle, events, container seams

## Контекст

Scope, destruction, eager/lazy, events и container post-processors — отдельные измерения Extension API. Их нельзя схлопнуть в один `@Order`.

## Scope

Singleton Spring = один instance на definition в container, не JVM-global. Prototype — новый на запрос; destruction callbacks container **не** вызывает для prototype products.

Scope влияет: singleton customizer обычно 1×; prototype state per op; request-scoped нужен web + proxy; hierarchy → несколько singletons; scoped proxy меняет type/equality.

Protocol на singleton **must-not** молча принимать любой scope. Stateless SPI или проверка scope metadata.

### Singleton + prototype dependency

Прямой inject prototype в singleton → prototype создаётся один раз. Для per-operation: `ObjectProvider.getObject()`, `ObjectFactory`, explicit factory SPI.

```java
public interface AuditSessionFactory {
    AuditSession create(SessionContext context);
}
```

## Resource ownership

| Mode | Кто закрывает |
|---|---|
| owned | creator |
| managed | container |
| borrowed | consumer не закрывает |
| transferred | ownership moves once |

Опасности: double-close (starter + `@PreDestroy`); утечка prototype без destroy. Выразить через `AutoCloseable` / destroy method.

## Eager / lazy

Default eager singleton → fail-fast. `@Lazy` переносит creation; dependency eager singleton всё равно создаёт на startup.

Eager полезен: duplicate ids; invalid extension; readiness resource; diagnostics chain. Lazy — действительно optional expensive resource; readiness честно отражает deferred state.

## Init callbacks

`@PostConstruct` / init method / `InitializingBean` — после DI, до полного post-processing. Предпочтительнее constructor + explicit factory.

Failure в callback = failure contract: side effects already?; cleanup?; retry?; extension identity в diagnostics. `InitializingBean` связывает со Spring — для neutral SPI обычный method от adapter.

## Lifecycle / SmartLifecycle

Phase — **отдельное** измерение ≠ customizer order ≠ definition order ≠ auto-config order ≠ `@Order` listeners ≠ creation order.

Публикуя lifecycle extension: phase ranges, readiness moment, shutdown timeout. Strategy/customizer **must-not** реализовывать Lifecycle без нужды.

## Events = observation

Подходит для: initialized, completed, policy changed, diagnostic snapshot.

Contract: publication moment; thread; sync/async; transaction; listener order; error propagation; delivery; re-publication; payload privacy.

**Must-not:** event как command bus — publisher не зависит от обязательного listener side effect. Обязательная collaboration → typed interface.

### Sync events

`publishEvent` блокируется; listener exception влияет на caller; latency/deadlock/reentrancy; transaction может быть активен. Critical section + user listener = небезопасный observation seam. Telemetry → isolated observer chain.

### Async events

Exception обычно не возвращается publisher. ThreadLocals/context не переносятся автоматически. Нужны: executor ownership/capacity; overflow; context propagation; exception handler; ordering; shutdown drain; loss/backpressure. `@Async` alone не отвечает.

### Payload

Immutable snapshot. **Must-not:** mutable builder, live SDK, ApplicationContext, unsanitized credentials. Stable ids, timestamps, outcome, safe summaries. Добавление обязательного record component = source break → versioned payload / class with accessors.

## BeanPostProcessor

Instances до/после init; может вернуть wrapper. Per-container; не кросс-hierarchy.

Дорогой contract: many beans; eligibility; order с другими BPP; early deps без proxy; type expectations; AOT support. Публиковать только для infrastructure, невыразимой advisor/registrar/typed contribution.

## BeanFactoryPostProcessor

Metadata до создания beans. `getBean()` внутри → premature instantiation, обход post-processing.

Подходит: изменить/проверить definitions; scopes; environment post-processing. **Must-not:** business logic с полным graph.

Static registration post-processors; registrars / import selectors — definition-phase seams с точной eligibility.

## Proxy / self-invocation

Exposed type после proxy должен сохранять contract. Self-invocation обходит decorator (same-class call). `proxyBeanMethods` влияет на inter-bean calls в `@Configuration`.

## Context hierarchy

Extension в parent vs child: visibility, SearchStrategy, несколько instances. Auto-config order ≠ extension order ≠ lifecycle phase.

## Ключевые тезисы

- Ownership/destruction явные; prototype ≠ auto-destroy.
- Event = observation, не command.
- Sync/async semantics — часть public contract.
- BPP/BFPP — container infrastructure, не app feature hook.
- Несколько order dimensions не смешивать.
