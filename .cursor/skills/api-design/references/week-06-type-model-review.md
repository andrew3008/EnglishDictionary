# Неделя 06 — Type Model Review, evolution, чек-листы

## Type Model Review — 8 шагов

1. **Type inventory** — classes, interfaces, records, enums, sealed, annotations, generics, external dependency types
2. **Semantic role** — value, entity, snapshot, view, capability, policy, state, result, command, event, handle, factory, SPI context
3. **State algebra** — product/sum, nullable dimensions, extra representable states
4. **Extensibility direction** — who adds variants vs operations → closed data+patterns vs open interface+dispatch
5. **Generic relations** — what each parameter proves; variance, inference, erasure, tokens
6. **Nullability, mutability, equality** — absence, collection ownership, snapshot/live
7. **Dependency exposure** — deliberate integration vs leakage
8. **Evolution scenarios** — new variant/field/operation/implementation/external value/framework version → source/binary/behavioral impact

## Type Contract Matrix (шаблон)

### Семантическая форма

| Type | Роль | Форма | Open/closed | Equality |
|---|---|---|---|---|
| `TenantId` | value | final class/record | closed | structural |
| `RetryPolicy` | policy variants | sealed + records | closed | structural |
| `SpanExporter` | capability/SPI | interface | open | identity |
| `RuntimeSnapshot` | snapshot | record | closed | structural |
| `ExportMode` | config choice | enum | closed external | identity |
| `ProviderContext` | SPI context | interface/class | author-controlled | identity |

### Использование и эволюция

| Type | Nullability | Consumer action | Evolution risk |
|---|---|---|---|
| `TenantId` | non-null | create/compare | representation |
| `RetryPolicy` | non-null | exhaustive use | adding variant |
| `SpanExporter` | non-null | implement/call | adding operation |
| `RuntimeSnapshot` | explicit parts | observe/deconstruct | adding component |
| `ExportMode` | non-null/default | bind/switch | new constant |
| `ProviderContext` | non-null accessors | extension reads | adding capability |

## Evolution risks (must classify)

| Change | Typical risk |
|---|---|
| Add method to class | source ambiguity, subclass conflict |
| Add abstract method to interface | breaks implementations |
| Default method | binary safer, semantic not guaranteed |
| Add record component | **major** |
| Add enum constant / sealed variant | exhaustive consumers, assumptions |
| final ↔ open ↔ sealed | extension model change |
| Generic bounds/return change | inference, overrides, erasure |
| Behavioral: equality, ordering, nullability, mutability | behavioral break without signature change |

## Anti-patterns (must not)

| Pattern | Problem |
|---|---|
| Interface for everything | fake extensibility |
| Record for everything | frozen representation |
| Enum + nullable cluster | hidden sum |
| Sealed SPI | contradicts extension |
| Generic without relation | inference pain, no safety |
| Wildcards everywhere | consumer capture hell |
| Optional failure erasure | lost not-found/unavailable/rejected |
| Dependency-shaped API | vendor lock-in accidental |

## Чек-лист: семантика

- [ ] Каждый public type — одна semantic role?
- [ ] Representable ≈ valid?
- [ ] Wrappers prevent repeated significant errors?
- [ ] Product/sum explicit?
- [ ] absence/unknown/failure separated?

## Чек-лист: classes/interfaces

- [ ] Class final unless subclassing supported?
- [ ] Open class — documented hooks + contract tests?
- [ ] Interface = capability, not single impl hide?
- [ ] Consumer-only ≠ accidental SPI?
- [ ] Default methods expressible via old contract?

## Чек-лист: records/enums/sealed

- [ ] Record components = stable full description?
- [ ] Mutable components copied?
- [ ] Enum closed, not open external namespace?
- [ ] Sealed matches closed-world evolution?
- [ ] New variant/constant — compatibility class?
- [ ] Pattern switch exhaustiveness intentional?

## Чек-лист: generics

- [ ] Each type parameter proves relation?
- [ ] Bounds minimal?
- [ ] Input variance by producer/consumer role?
- [ ] Returns avoid unnecessary wildcards?
- [ ] Erasure/tokens accounted?
- [ ] Unchecked casts localized?
- [ ] No raw types in new public API?

## Чек-лист: collections/nullability

- [ ] Collection interface = order/uniqueness semantics?
- [ ] Snapshot/live/mutability defined?
- [ ] Empty collection vs null?
- [ ] Optional only zero-or-one?
- [ ] Nullability policy consistent?

## Чек-лист: Spring/dependencies

- [ ] Bindable ≠ runtime model?
- [ ] Bean interface allows real replacement?
- [ ] Multiple candidates/ordering explicit?
- [ ] Vendor types only deliberate surfaces?
- [ ] Config enum has unknown/evolution strategy?

## Контрольные вопросы (выборка)

1. Type as set of values?
2. Type as proof of invariant?
3. Final class vs record?
4. Contracts of subclassing?
5. Consumer interface vs SPI?
6. Safe default method?
7. Record transparency promise?
8. Shallow immutability?
9. Record patterns coupling?
10. Enum vs sealed?
11. Adding enum constant minor?
12. `non-sealed` branch effect?
13. Pattern switch vs dispatch?
14. Product vs sum?
15. When generic parameter needed?
16. Why `List<Dog>` ⊄ `List<Animal>`?
17. PECS application?
18. Wildcard return problems?
19. Erasure limits?
20. Reifiable vs heap pollution?
21. `Class<T>` vs parameterized token?
22. Optional appropriate uses?
23. Domain result vs `Result<T,E>`?
24. List/Set/Map semantics?
25. Phantom typestate for Spring bean?
26. Vendor type in signature?
27. Risky record/sealed/generic changes?

## Итоговые тезисы

1. Public type = values + operations + evolution contract.
2. Final class hides representation; record publishes it.
3. Enum and sealed model closed sets differently.
4. Pattern matching strengthens data modeling and coupling.
5. Generics prove relations; PECS at boundaries.
6. Erasure and tokens are design constraints.
7. Optional ≠ failure/unknown/state.
8. Collection and nullability are contract facets.
9. Bean and configuration types are effective starter API.
10. Every additive change — source, binary, behavioral review.

## Практическое задание

**Must produce:** Type Contract Matrix + заключение separating cosmetic vs semantic/evolution contract changes (class→record, interface→sealed, enum→unknown wrapper, vendor→domain abstraction).
