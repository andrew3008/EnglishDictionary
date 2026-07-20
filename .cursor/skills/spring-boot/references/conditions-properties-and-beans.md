# Conditions, Properties, and Bean Design

## Conditional Bean Rules

Do not apply `@ConditionalOnClass`, `@ConditionalOnMissingBean`, and `@ConditionalOnProperty` mechanically to every bean.

Choose conditions according to the actual contract.

Use `@ConditionalOnClass` when:

- an optional library or framework type is required
- the module must back off cleanly when that dependency is absent
- servlet and reactive classpaths must remain isolated

Use `@ConditionalOnMissingBean` when:

- user/platform override is intentionally supported
- there is one clear fallback implementation
- backing off does not violate required platform invariants

Do not use `@ConditionalOnMissingBean` to make mandatory safety components silently disappear.

Use `@ConditionalOnProperty` when:

- enablement is explicitly configurable
- the default is documented
- disabled behavior is tested
- property semantics are unambiguous

Never register unconditional infrastructure beans when the required runtime or classpath is absent.

Never rely on bean registration order as hidden control flow.

## Configuration Properties

Use:

- `@ConfigurationProperties`
- strongly typed fields
- immutable configuration where practical
- constructor binding or record-style models where supported by the project
- explicit defaults
- JavaDoc and generated configuration metadata
- validation for startup-owned configuration

Avoid:

- scattered `@Value`
- direct `Environment` reads in ordinary components
- stringly typed booleans/enums/numbers
- hidden fallback values in implementation code
- multiple property sources defining the same platform decision

Property classes describe **desired startup configuration**.

Runtime-applied state must not be inferred solely from property objects after JMX/control-plane mutation.

## Property Naming

Property names must:

- use one stable platform prefix
- reflect the domain capability
- distinguish read-only diagnostics from mutation enablement
- distinguish startup configuration from runtime state
- avoid ambiguous terms such as `enabled` without a clear owner

Example:

```yaml
platform:
  tracing:
    control:
      runtime-mutation:
        enabled: false
```

Pre-production property renames are allowed when they improve taxonomy.

Do not keep old aliases, deprecated keys, or dual binding paths unless an ADR explicitly approves them.

When renaming:

- update configuration metadata
- update samples
- update docs
- update Helm/environment mappings
- add a negative test proving the old property is no longer active when appropriate

## Defaults

Defaults are part of platform behavior and require tests.

Safe defaults include:

- tracing integration can initialize without external network calls
- risky runtime mutation is disabled
- read-only diagnostics remain available where safe
- absent optional dependencies do not fail unrelated application startup
- invalid critical configuration fails clearly
- disabled capabilities do not create unnecessary beans

Do not hide unsafe behavior behind a permissive default.

A default that changes sampling, export, scrubbing, or runtime mutation must be justified in an ADR or warning register.

## Bean Design

Beans should be singleton unless lifecycle or scope semantics require otherwise.

Prefer:

- immutable collaborators
- constructor injection
- explicit bean names only when names are part of a documented integration contract
- narrow interfaces at module boundaries
- final implementation classes where extension is not supported
- no-op implementations only when disabled behavior is an intentional product capability

Avoid:

- field injection
- static application context holders
- mutable singleton configuration
- service locator patterns
- hidden bean lookup from domain code
- optional dependencies injected through `Object` or reflection without a documented reason

`ObjectProvider` may be used for optional/lazy integration, but not to hide required dependencies or ambiguous bean graphs.

## Bean Override Policy

Decide explicitly whether a bean is:

- platform-mandatory
- user-replaceable
- internally replaceable for tests only

For user-replaceable beans:

- document the extension contract
- use `@ConditionalOnMissingBean`
- verify replacement with `ApplicationContextRunner`
- ensure replacement cannot bypass safety invariants

Do not expose internal runtime components as override points merely because Spring can replace them.

## Lazy and Eager Initialization

Avoid eager initialization of:

- exporters
- network clients
- JMX mutation paths
- expensive registries
- classpath scanners
- optional integrations

Eager initialization is acceptable when needed to fail fast on unsafe or invalid startup configuration.

Do not mark everything `@Lazy` globally. Lazy behavior must be deliberate and tested.

No startup bean may perform an unbounded wait or external network call.

