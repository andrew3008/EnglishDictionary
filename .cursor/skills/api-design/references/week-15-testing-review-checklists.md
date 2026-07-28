# Week 15 — Testing, review, checklists

## Контекст

Extension contract требует matrix (cardinality, selection, order, lifecycle, proxy, events, AOT, security), не одного test с custom bean.

## Тестовая матрица

| Измерение | Сценарии |
|---|---|
| Cardinality | 0, 1, N, duplicate id |
| Selection | primary, fallback, qualifier, ambiguity |
| Ordering | negative, equal, unordered, priority |
| Replacement | user bean, parent bean, unrelated bean |
| Lifecycle | eager, lazy, init failure, shutdown |
| Proxy | JDK, class proxy, final type |
| Events | sync, async, exception, context propagation |
| Post-processing | early dependency, wrapped instance |
| AOT | hints, precise type, definition registration |
| Security | secret redaction, forbidden mutation |

`ApplicationContextRunner` + user configuration: graph **и** invocation order + exact effective result. Compatibility suite: sample extensions compiled against previous version on new runtime.

## Extension Contract Review

1. **Inventory** — supported и accidental: types, annotations, names, qualifiers, events, customizers, registrars, post-processors, scopes, diagnostics.
2. **Mode** — replace / augment / customize / observe; разделить многоролевые seams.
3. **Discovery/eligibility** — type, generic, qualifier, name, hierarchy, conditions.
4. **Cardinality/selection** — 0..1/1/N, ambiguity, primary/fallback, duplicate identity.
5. **Ordering** — dimension, tie policy, platform/user phases.
6. **Lifecycle/ownership** — scope, eager/lazy, invocation count, thread safety, create/destroy.
7. **Failure/ops** — exception policy, diagnostics, metrics, redaction.
8. **Compatibility** — Java signatures, bean graph, events, AOT/native.
9. **Evidence** — context / proxy / lifecycle / artifact/native tests.

Verdict per seam: keep / redesign / close / document.

## Анти-паттерны

1. «Объявите любой bean этого типа» без role/cardinality/ambiguity.
2. SDK builder как основной SPI.
3. `@Order` как универсальный lifecycle.
4. Event как обязательная команда.
5. `@Fallback` вместо back-off.
6. Bean name как domain identity.
7. Post-processor с business dependencies.
8. Lazy как сокрытие startup failure.
9. Несортированный order-sensitive chain.
10. User customizer после security invariant (может отменить policy).

## Контрольный чек-лист

**Семантика**

- [ ] Режим replace/augment/customize/observe
- [ ] Минимальная сила mechanism
- [ ] Нет утечки SDK/internal без решения
- [ ] Supported vs accidental разделены
- [ ] Mutable callback: ownership + retention

**Discovery / selection**

- [ ] Eligibility (type/generic/qualifier/hierarchy)
- [ ] Cardinality формальна; ambiguity документирована
- [ ] Back-off target = injection role
- [ ] Primary/Fallback ≠ resource ownership
- [ ] Bean name опубликован только при нужде

**Ordering / composition**

- [ ] Order dimension назван; equal-order policy
- [ ] Platform phases ≠ user numeric order
- [ ] Conflicts обнаруживаются; effective chain в diagnostics

**Lifecycle / failure / security / AOT**

- [ ] Scope совместим; ownership/destruction ясны
- [ ] Failure policy по роли; secrets redacted
- [ ] Trust: least authority; invariants после customize
- [ ] AOT hints; native tests для conditional seams
- [ ] SemVer включает Spring behavior matrix

## Ключевые тезисы

- Matrix + effective result, не только `hasBean`.
- Review = inventory accidental surface first.
- Accidental seams закрывать или легализовать до production.
