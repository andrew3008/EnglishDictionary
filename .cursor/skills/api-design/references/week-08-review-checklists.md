# Неделя 08 — Review, анти-паттерны, чек-лист

## Анти-паттерны

| # | Анти-паттерн | Дефект |
|---|---|---|
| 1 | `throws Exception` | нет taxonomy; lambda/override pain |
| 2 | one exception to rule them all | message/cause parsing; mixed categories |
| 3 | message parsing | fragile; localization breaks |
| 4 | catch-log-wrap everywhere | noise; lost semantics |
| 5 | lost cause | `new Ex(ex.getMessage())` |
| 6 | swallowed interruption | broken shutdown/cancellation |
| 7 | retry all exceptions | validation/auth/unknown outcome |
| 8 | `Optional` for all failures | outage = not-found |
| 9 | exception for normal branch | stack traces; hidden control flow |
| 10 | boolean + log | reason unavailable to caller |
| 11 | vendor exception leakage | transitive coupling |
| 12 | secret in exception | logs/analyzer/actuator exposure |
| 13 | startup catch-and-disable | false healthy app |
| 14 | background failure log-only | green health, dead worker |

## Контрольный чек-лист

### Taxonomy и representation
- [ ] Negative outcomes перечислены per operation?
- [ ] Programmer error / domain rejection / environment разделены?
- [ ] Expected outcome → data где caller обязан branch?
- [ ] `Optional` не перегружен несколькими причинами?
- [ ] Stable discriminator вместо message parsing?
- [ ] Hierarchy согласована по library?

### Exceptions
- [ ] Checked/unchecked choice обоснован?
- [ ] Standard precondition exceptions?
- [ ] Cause сохранён?
- [ ] No double wrapping?
- [ ] Suppressed preserved?
- [ ] Custom fields immutable и safe?
- [ ] No vendor type leakage?

### State и recovery
- [ ] State after each failure?
- [ ] Partial progress documented?
- [ ] Remote outcome may be unknown?
- [ ] Retry учитывает idempotency/deadline?
- [ ] Recovery hint defined?
- [ ] Programmer/config failures not retried?
- [ ] Resource usable after failure?

### Async/concurrency
- [ ] Immediate throw vs exceptional completion разделены?
- [ ] Stage causes documented?
- [ ] Sibling async failures not lost?
- [ ] Cancellation direction defined?
- [ ] Interrupt rethrown/restored?
- [ ] Callback exception isolation per policy?

### Diagnostics и security
- [ ] Message actionable, not machine contract?
- [ ] No secrets/PII/raw payload?
- [ ] Final logging boundary identified?
- [ ] Metric labels bounded?
- [ ] Attempt vs logical failure counted separately?
- [ ] Correlation id without vendor coupling?

### Spring Boot
- [ ] Invalid requested feature fails startup?
- [ ] Optional absence → condition?
- [ ] Config paths/violations clear?
- [ ] FailureAnalyzer for complex startup error?
- [ ] No default validation after user backoff?
- [ ] Partial startup resources closed?
- [ ] Background failure changes health/state?
- [ ] Shutdown preserves callback/diagnostics?

## Agent workflow

```
1. Inventory all failure surfaces
2. Classify each outcome (6 categories)
3. Map representation: exception | result | Optional | state
4. For each exception path: state guarantee + retry?
5. Scan: message parsing, lost cause, double wrap, swallow interrupt
6. Async: document CompletionStage causes
7. Spring: startup policy + analyzer + conditions
8. Security: secrets in messages/fields
9. Fill Error Contract Matrix for top-8 outcomes
10. Flag anti-patterns 1–14
```

## Разделение дефектов

| Mechanics | Contract |
|---|---|
| catch, wrapper, retry loop | taxonomy, state guarantee |
| log statement | recovery hint, public discriminator |
| try-with-resources | startup fail vs degrade policy |

## Контрольные вопросы (выборка)

1. Почему return type — половина contract?
2. Programmer error vs domain rejection?
3. Когда result variant лучше exception?
4. Почему message ≠ error code?
5. Strong vs basic exception safety?
6. Unknown outcome после timeout?
7. Что после `InterruptedException`?
8. `CompletableFuture.cancel` guarantees?
9. FailureAnalyzer vs recovery?
10. Когда configuration error fail startup?

## Ключевые тезисы

1. Error Contract Review начинается с inventory outcomes.
2. 14 анти-паттернов — быстрый scan.
3. Mechanics fix ≠ contract fix.
4. Matrix связывает category, representation, recovery.
5. Examples must include negative paths.
