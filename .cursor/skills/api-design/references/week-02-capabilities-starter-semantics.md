# Week 02 — Capabilities and Starter Semantics

## Conceptual contours and orthogonality

Contour = boundary where internal rules change together. Example: `RetryPolicy` groups attempts, backoff, retryable failures; DLQ publishing may be separate capability with different lifecycle.

**Orthogonal axes** change independently. In tracing:

- sampling policy
- propagation format
- span processing
- export destination

`TracingMode.FULL/LIGHT/CUSTOM` presets may help simple cases but must not replace independent axes when users need fine control.

## Minimal completeness

Model should be:

- small enough to hold in mind
- complete enough for main scenarios without workarounds
- without overlapping ways to express same thing
- without requiring internal mechanism knowledge

Multiple entry points (`TracingFacade`, `TracingService`, `SpanFactory`, `TracerAdapter`, raw OTel bean) require explicit level distinction or deduplication.

## Capability-oriented vs feature-oriented API

**Capability-oriented:** stable consumer abilities — read active context, capture snapshot, start manual span, provide sampling policy, configure export, observe runtime.

**Feature-oriented:** internal backlog — `enableNewPropagationFeature()`, `useLegacySamplerV2()`, `setExperimentalExportMode()`, feature flag maps.

Feature names age (`new`, `legacy`, version suffixes). Capability names survive mechanism changes.

## Starter publishes activation language

Starter defines how application says:

- enable capability
- change policy
- replace default component
- add customizer
- disable integration
- explain why auto-configuration did not apply

Expressed via properties, bean types, qualifiers, conditions, ordering — **container negotiation semantics**.

## Back-off semantics

`@ConditionalOnMissingBean` semantically means user implementation wins; auto-config provides default.

Incomplete without: type vs name lookup, concrete return type visibility, parent context, partial back-off, multiple candidates, customizers on user bean, dependent beans still created.

Document **replaceability semantics**, not "bean can be overridden".

## Configuration keys as domain language

Group by concepts, not implementation classes.

Bad: `sdk-builder.sampler-class`, `processor-class`, `exporter-class`

Good: `sampling.probability`, `export.endpoint`, `propagation.formats`

Second schema expresses user intent; internal builder graph need not appear in configuration contract.

## Defaults define concept meaning

`enabled` variants:

- default true — classpath opt-in activates
- default false — dependency only provides capability
- absent — inherits global policy
- false — no beans
- false — no-op facade for stable injection

All valid in different systems; not interchangeable.

## Customizer, provider, replacement

Three extension mechanisms — define priority hierarchy:

1. user replacement `TracingRuntime` disables auto-configured runtime entirely
2. user `SamplingPolicy` replaces only default policy
3. ordered `TracerProviderCustomizer` applied to auto-configured builder
4. properties set defaults before customizers
5. operational contributors work via stable view for both runtimes

Document hierarchy in docs and tests.

## Bean types express roles

Avoid `TracingConfiguration` as injectable bean — ambiguous settings vs runtime vs Spring artifact.

Prefer role-based types: `ActiveTraceContextView`, `ManualSpanBuilderFactory`, `SamplingPolicy`, `TracingRuntime`, `TracingRuntimeView`.

Reduces accidental injection of internal SDK types.
