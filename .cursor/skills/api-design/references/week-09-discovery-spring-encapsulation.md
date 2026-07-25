# Неделя 09 — Discovery, Spring, encapsulation, evolution

## Discovery ≠ SPI

SPI = what provider must do. Discovery = how runtime finds providers. **Must** separate.

| Mechanism | When |
|---|---|
| Explicit registration (builder) | caller controls multiplicity, order, config |
| Spring DI (`List<Extension>`, `ObjectProvider`) | starter composition; document order/duplicates |
| `ServiceLoader` | deploy-time plugins; classpath/module boundary |

**Must-not** mandatory singleton via `ServiceLoader` without explicit selection policy.

ServiceLoader pitfalls: classpath order ≠ priority; classloader boundaries; global cache retention.

**Rule:** ServiceLoader for real plugin requirement, **not** hiding ordinary DI.

## Multiplicity, ordering, conflicts

**Must fix cardinality:** zero | zero-or-one | exactly-one | one-or-more | many.

Order **must** document: lower/higher first, tie-break, reserved ranges.

Duplicate id policy: reject | replace | compose | last-wins. **Must-not** silent classpath-order wins.

Incompatible extensions → **fail startup** with candidate names and action.

## Spring bean surface as contract

| Element | Becomes contract when |
|---|---|
| Bean type | injection, conditions, replacement |
| Bean name | `@Qualifier`, conditions, tests, diagnostics |
| `@Order` / `@Primary` | composition behavior changes |
| Scope/lifecycle | identity, thread safety, ownership |

**Must:** stable interface as injection point, not concrete impl.

`@ConditionalOnMissingBean` = **replacement SPI** even without `spi` package.

Backoff **must** disable unnecessary default config/validation after user replacement.

Return type in `@Bean` **must** balance condition matching vs leakage (`Object` hides; concrete leaks).

## Auto-configuration boundary

Class in `AutoConfiguration.imports` = framework-facing, **not** user import.

Conditions (classpath, property, bean) = observable protocol — `matchIfMissing` changes = behavioral.

Ordering `before`/`after` = integration contract; publish anchors only for real extension use cases.

Metadata registrations (analyzer, env post-processor) — filenames and rules **must** be tested.

## Configuration vs programmatic SPI

| Channel | Expresses |
|---|---|
| Properties | data policy, defaults, units, validation |
| User bean / customizer | behavior, algorithms |

**Must** define precedence when property + customizer + replacement bean coexist.

`@ConfigurationProperties` bind-only → immutable domain options at boundary. Runtime mutation = stateful API.

## Container-level SPI

`BeanPostProcessor`, `BeanFactoryPostProcessor` — powerful, early, wide impact.

**Must-not** publish as ordinary customization. Expose domain-level extension instead.

Container SPI justified only for framework integrators — document phase, ordering, bean access limits.

## Java access и JPMS

| Modifier | Meaning |
|---|---|
| `public` | accessibility, not support |
| `protected` | subclass SPI |
| package-private | hidden unless same-package test abuse |
| `private` | reflection/serialization may still expose |

**Must:** `exports` for public packages; internal packages **not** exported.

`opens` — least privilege, qualified to framework modules. **Must-not** blanket `opens`.

Classpath (unnamed modules): package convention + build rules remain critical.

Export removal = binary compatibility impact if previously exported.

## Physical artifacts

Separate `api` artifact **when** consumers compile without runtime/vendor deps.

**Must-not** module explosion — jar per package without dependency/release benefit.

Typical starter set: library (api+spi+impl), autoconfigure, starter (composition), test-support if supported fixtures.

## How internal becomes effective API

| Leak path | Fix |
|---|---|
| return concrete type | stable interface/facade |
| public constructor | factory + restricted visibility |
| reflection class name config | stable id + documented adapter |
| broad `@ConditionalOnMissingBean` | meaningful seam only |
| consumer tests import internal | test-support API |

## Compatibility — four dimensions

| Dimension | Question |
|---|---|
| Binary | old bytecode links? |
| Source | old source recompiles? |
| Behavioral | same documented meaning? |
| Implementor | old provider still valid? |

**Asymmetry:** new abstract SPI method = breaking implementors; new API concrete method often ok.

Extending SPI **input domain** may break old providers. Strengthening provider requirements = breaking without signature change.

## Evolution matrix (кратко)

| Change | Consumer API | Provider SPI |
|---|---|---|
| new concrete method | usually ok | n/a |
| new abstract method | caller ok | **breaking** |
| conservative default | usually ok | conditional |
| new allowed input | improvement | possibly breaking |
| bean order/default change | behavioral risk | behavioral risk |

SemVer **only after** supported API/SPI defined.

## Must / Must-not

**Must:** explicit composition root; deterministic provider selection; list official extension points; black-box consumer tests; SPI compatibility kit.

**Must-not:** first provider wins; replace every bean; public impl constructors; blanket opens; API diff only (ignore behavior/defaults).

## Supported customization guide (starter)

**Must** publish short official list:
- properties (data policy)
- replacement beans (backoff seams)
- customizers (pre-build hooks)
- qualifiers / ordering rules

Everything else = implementation detail + technical enforcement.

## Internal freedom — enforcement stack

1. Reduce accessibility (private, package-private, final, no public ctor)
2. JPMS non-exported packages
3. Architecture/build rules (forbid `.internal` imports)
4. API diff baseline with explicit exclude policy
5. Module-path reflection tests for intended opens only

Documentation alone **does not** compensate for easy public path.

## Ключевые тезисы

1. Discovery separate from SPI semantics.
2. `@ConditionalOnMissingBean` publishes replacement SPI.
3. Bean type/name/order/scope = Spring API.
4. JPMS exports enforce; classpath needs build rules.
5. Four compatibility dimensions — implementor is separate.
