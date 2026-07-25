# Неделя 09 — Review, анти-паттерны, verification

## API/SPI Boundary Review — 8 шагов

1. **Inventory** — public/protected types, exports/opens, beans, properties, metadata, reflection/serialization names
2. **Assign role** — API / SPI / framework / ops / test / internal; caller, implementor, creator, lifecycle owner
3. **Close dependencies** — transitive closure; vendor/Spring leakage
4. **SPI protocol** — lifecycle, threads, reentrancy, ownership, cardinality, ordering, failures, capabilities
5. **Spring seams** — bean types/names, backoff, order, property precedence, conditions, metadata
6. **Enforce internal** — visibility, exports, constructors, return types; architecture/API diff rules
7. **Simulate evolution** — new method/input/result, vendor swap, bean rename; caller vs implementor migration
8. **Test both sides** — black-box consumer, SPI kit, hostile provider, Spring context matrix

## Public Contract Matrix (шаблон)

### Java surface

| Surface | Role | Audience | Verification |
|---|---|---|---|
| `TracingControl` | API | application | consumer tests, API diff |
| `SamplingPolicy` | SPI | extension author | compatibility kit |
| `DefaultTracingControl` | internal | library owner | not exported |
| `ProviderCapabilities` | SPI value | extension + framework | additive rules |

### Spring surface

| Surface | Role | Replacement | Contract |
|---|---|---|---|
| `platform.tracing.*` | config API | precedence | defaults, validation |
| `SamplingPolicy` beans | programmatic SPI | ordered list | cardinality, failures |
| default control bean | API + backoff | missing interface | type, scope, lifecycle |
| auto-config class | framework | imports metadata | conditions, ordering |

## Анти-паттерны

| # | Анти-паттерн | Дефект |
|---|---|---|
| 1 | everything public is API | support without policy |
| 2 | everything interface is SPI | consumer implements test hook |
| 3 | dual-role god interface | every change hits both sides |
| 4 | `impl` + public constructors | bypass validation/lifecycle |
| 5 | `ApplicationContext` in SPI | service locator, hidden deps |
| 6 | mandatory ServiceLoader singleton | hidden classpath composition |
| 7 | default throws unsupported | runtime break old providers |
| 8 | open-ended context map | string keys, downcasts |
| 9 | protected mutable state | subclass coupling |
| 10 | replaceable every bean | consumer assembles impl graph |
| 11 | bean name by accident | method name = qualifier contract |
| 12 | blanket `opens` | no encapsulation |
| 13 | jar per package | module explosion, no benefit |
| 14 | API diff only | defaults/order/input domain ignored |

## Контрольный чек-лист

### Role и audience
- [ ] Role per public/protected element?
- [ ] Caller API vs implementor SPI separated?
- [ ] Framework-facing entry points known?
- [ ] Intended vs effective compared?
- [ ] Dual-role types minimal?

### Dependency и encapsulation
- [ ] Signatures free of accidental vendor types?
- [ ] API artifact minimal dependencies?
- [ ] Internal packages not JPMS-exported?
- [ ] `opens` narrow and justified?
- [ ] Public constructors/concrete returns needed?
- [ ] Serialization/reflection inventoried?

### SPI protocol
- [ ] Lifecycle and repeated calls?
- [ ] Threads, concurrency, blocking, reentrancy?
- [ ] Ownership and retained references?
- [ ] Normal result vs provider defect?
- [ ] Fail-open/fail-closed?
- [ ] Cardinality, ordering, duplicates?
- [ ] Capability/version rules?

### Discovery/composition
- [ ] Discovery separate from SPI semantics?
- [ ] Explicit composition root?
- [ ] ServiceLoader only for plugin requirement?
- [ ] Zero/multiple providers deterministic?
- [ ] Spring DI not leaking into neutral core?

### Spring Boot
- [ ] Official extension points listed?
- [ ] Backoff only on meaningful seams?
- [ ] Bean type/name/qualifier/order policy?
- [ ] Property vs customizer vs bean precedence?
- [ ] Conditions/defaults versioned as behavior?
- [ ] Default config skipped after user backoff?

### Evolution и verification
- [ ] Four compat dimensions checked separately?
- [ ] Default methods conservative?
- [ ] SPI input domain extension assessed?
- [ ] Black-box consumer tests?
- [ ] SPI compatibility kit + hostile provider tests?
- [ ] API diff/architecture/module-path automated?
- [ ] Migration guide: callers vs implementors?

## Compatibility kit (SPI TCK)

**Must test:**
- valid/invalid inputs, null, exceptions
- lifecycle sequences
- concurrent/reentrant calls
- ownership/cleanup
- capability declarations
- cancellation/timeout behavior

**Hostile provider tests** (library side): slow, throws, invalid result, reenters, fails close.

Test kit fixtures **evolve as supported test API**.

## Deprecation

API consumer migration **≠** SPI provider migration — **separate guide sections**.

Bridge adapters **must** declare capability loss and removal timeline.

Removal **requires** usage evidence (search, telemetry), not doc absence alone.

## Agent workflow

```
1. Inventory all surfaces (Java + Spring + metadata)
2. Assign role; flag dual-role
3. Build dependency closure; find leakage
4. For each SPI: write full protocol paragraph
5. Map Spring backoff/replacement/ordering
6. List official extension points (negative statement for rest)
7. Run anti-pattern scan 1–14
8. Simulate: +abstract method, +input value, bean order change
9. Plan consumer tests + provider kit scenarios
```

## Три итоговых списка

Review **must** produce:

1. **Supported consumer API** — types, properties, beans caller may use
2. **Supported provider SPI** — interfaces, registration, protocol
3. **Internal implementation** — enforcement mechanism per item

Sporny element: **enforcement**, not только label.

## Ключевые тезисы

1. Boundary Review ends with enforcement + evolution simulation.
2. 14 anti-patterns — accidental API/SPI leakage scan.
3. Compatibility kit proves SPI, docs do not.
4. Caller and implementor migration are separate.
5. Three lists + enforcement = deliverable.
