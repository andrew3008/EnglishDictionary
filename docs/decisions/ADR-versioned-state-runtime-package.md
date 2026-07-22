# ADR: Versioned runtime state (`core.runtime.versioned`)

**Status:** Accepted (supersedes prior `api.runtime.state` placement)  
**Date:** 2026-07-15  
**Context:** Module taxonomy cleanup — CAS/LKG holder is agent-internal infrastructure, not public application SDK.

## Decision

Replace deleted package:

- `space.br1440.platform.tracing.api.runtime.state.VersionedState`
- `space.br1440.platform.tracing.api.runtime.state.VersionedStateHolder`

With:

- `space.br1440.platform.tracing.core.runtime.versioned.VersionedState`
- `space.br1440.platform.tracing.core.runtime.versioned.VersionedStateHolder`

Delete `api.runtime.state` entirely. No compatibility shims.

## Why not `api`

- **Single consumer module:** only `platform-tracing-otel-javaagent-extension` domain holders instantiate `VersionedStateHolder` (`SamplerStateHolder`, `ScrubbingPolicyHolder`, `ValidationPolicyHolder`).
- **Application code never uses it:** autoconfigure/starters are ArchUnit-forbidden from depending on `core.runtime.versioned`.
- **Agent visibility:** `platform-tracing-otel-javaagent-extension` already depends on `platform-tracing-otel`; `agentExtensionJar` embeds core classes (same pattern as `core.propagation.control`).
- **Honest taxonomy:** `platform-tracing-api` = public contracts; CAS holder is runtime implementation detail.

Prior ADR rationale («dual classloader visibility via api») is obsolete after embedding core in the agent extension jar.

## Why `core.runtime.versioned` (not `core.runtime.state`)

[`core.runtime.state`](../../platform-tracing-otel/src/main/java/space/br1440/platform/tracing/core/runtime/state/) hosts SDK tracing runtime (`TracingState`, `TracingMode`, `ImmutableTracingState`) for `TracingRuntime` — a different domain. CAS policy snapshots use a dedicated sub-package to avoid confusion.

## Why `VersionedState` is intentionally closed (ArchUnit allowlist)

`VersionedState` is **not** a universal marker for any object with `version()`. It is the contract for state objects **directly managed** by `VersionedStateHolder` (CAS + LKG).

Allowed production implementers:

1. `SamplerState` (otel-extension)
2. `ScrubbingSnapshot` (otel-extension)
3. `ValidationSnapshot` (core.validation)

To add a new holder-managed state: implement `VersionedState`, wrap in `VersionedStateHolder<T>`, update ArchUnit allowlist + this ADR.

## SamplingPolicySnapshot decoupling (unchanged)

`SamplingPolicySnapshot` is nested compiled policy inside `SamplerState`, not holder-managed. **Source of truth for sampling version:** `SamplerState.version()` only.

## Behavior invariants (unchanged)

- CAS / last-known-good semantics in `VersionedStateHolder`
- Side-effect-free builder retry under contention
- `prev.version() + 1` increment convention
- JMX / wire schema behavior
- Sampling / scrubbing / validation runtime behavior

## Guardrails

ArchUnit rules in `ModuleTaxonomyArchRules`:

- `VERSIONED_STATE_IMPLS_ALLOWLIST`
- `SAMPLING_MODEL_NOT_DEPEND_ON_VERSIONED_STATE`
- `APP_MODULES_NOT_DEPEND_ON_CORE_RUNTIME_VERSIONED`
- `NO_API_RUNTIME_STATE_PACKAGE`
- `VERSIONED_STATE_PRIMITIVE_ONLY_IN_CORE`
- `NO_API_CONFIG_PACKAGE` (regression guard: deleted `api.config` package must not reappear)
- `SNAPSHOT_FIELDS_ARE_FINAL` (+ scrubbing/validation variants)
