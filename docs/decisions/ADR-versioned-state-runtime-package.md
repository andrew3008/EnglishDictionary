# ADR: Runtime State Package (`api.runtime.state`)

**Status:** Accepted  
**Date:** 2026-07-02  
**Context:** Pre-production cleanup of misleading `api.config` primitives.

## Decision

Replace:

- `space.br1440.platform.tracing.api.config.Versioned`
- `space.br1440.platform.tracing.api.config.DomainConfigHolder`

With:

- `space.br1440.platform.tracing.api.runtime.state.VersionedState`
- `space.br1440.platform.tracing.api.runtime.state.VersionedStateHolder`

Delete `api.config` entirely. No compatibility shims. No `package-info.java`.

## Why `api.config` was wrong

The package contained only a CAS/LKG runtime-state publication primitive, not application configuration, Spring properties, or wire schema. The name conflicted with `core.sampling.properties`, `@ConfigurationProperties`, and `api.control.wire`.

## Why `api.runtime.state`

Honest semantics: immutable runtime snapshots published atomically via holder. Lives in `platform-tracing-api` for dual classloader visibility (App CL + OTel Agent CL).

## Why `VersionedState` / `VersionedStateHolder`

- `VersionedState` — role marker (like `Comparable`), not generic `HasVersion`.
- `VersionedStateHolder` — names the generic bound; pairs with marker.
- Rejected: `AtomicRuntimeStateHolder` (leaks `AtomicReference`), `RuntimeStateHolder` (loses versioning), separate `platform-tracing-runtime-api` module (YAGNI for 2 types).

## Why `VersionedState` is intentionally closed (ArchUnit allowlist)

`VersionedState` is **not** a universal marker for any object with `version()`. It is the contract for state objects **directly managed** by `VersionedStateHolder` (CAS + LKG).

Allowed production implementers:

1. `SamplerState`
2. `ScrubbingSnapshot`
3. `ValidationSnapshot`

To add a new holder-managed state: implement `VersionedState`, wrap in `VersionedStateHolder<T>`, update ArchUnit allowlist + ADR.

## SamplingPolicySnapshot decoupling

`SamplingPolicySnapshot` is nested compiled policy inside `SamplerState`, not holder-managed. Removed:

- `implements VersionedState` / former `Versioned`
- `version` field and `version()` method
- `SamplingPolicyProperties.version` component

**Source of truth for sampling version:** `SamplerState.version()` only.

## Behavior invariants (unchanged)

- CAS / last-known-good semantics in `VersionedStateHolder`
- Side-effect-free builder retry under contention
- `prev.version() + 1` increment convention
- JMX / wire schema behavior
- Sampling / scrubbing / validation runtime behavior
- `defaultRatio` fail-fast in `SamplingPolicySnapshotFactory`
- Lenient route-ratio compilation

## Guardrails

ArchUnit rules in `ModuleTaxonomyArchRules`:

- `VERSIONED_STATE_IMPLS_ALLOWLIST`
- `SAMPLING_MODEL_NOT_DEPEND_ON_RUNTIME_STATE`
- `APP_MODULES_NOT_DEPEND_ON_RUNTIME_STATE`
- `NO_API_CONFIG_PACKAGE`
- `SNAPSHOT_FIELDS_ARE_FINAL` (+ scrubbing/validation variants)
- Stale `..core.sampling.config..` globs fixed to `..core.sampling.properties..`
