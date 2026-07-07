# ADR — PlatformTracing SpanSpec Governance

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | PlatformTracing v3 refactoring |

## Context

v1 escape hatches (`advanced()`, raw span builders, untyped attributes) became dumping grounds for ungoverned instrumentation. Risk R03 in the refactoring plan: uncontrolled `spanFromSpec` usage bypasses semconv validation.

## Decision

Governed manual spans that semantic builders cannot express MUST use:

```text
manual().spanFromSpec(SpanSpec spec)
```

with mandatory governance metadata on every spec.

## Why `spanFromSpec(spec)` replaced `advanced` / `rawSpan` / `escapeHatch`

- Single governed entry point instead of multiple ambiguous wide APIs.
- Immutable `SpanSpec` validates **final state** before span creation.
- All paths still route to `TracingImplementation.startSpan(SpanSpec)` — one creation boundary.
- ArchUnit and runtime validation can enforce reason/reference policy centrally.

## Why `reason(SpanSpecReason)` + `reference` replaced `justification`

- v1 free-text `justification` was not machine-auditable.
- `SpanSpecReason` enum provides **closed, reviewable categories**:
  - `UNSUPPORTED_PROTOCOL`
  - `UNSUPPORTED_LIBRARY`
  - `LEGACY_INTEGRATION`
  - `PLATFORM_EDGE_CASE`
  - `TEMPORARY_WORKAROUND`
- Generic catch-all values (`OTHER`, `UNKNOWN`, `CUSTOM`, `MISC`) are **forbidden**.
- `TEMPORARY_WORKAROUND` **requires** a non-blank `reference` (ticket, ADR id, or tracked work item).

## Attribute typing

- `SpanSpecBuilder` exposes typed scalar and homogeneous list attribute methods only.
- **No** `attribute(String, Object)` — prevents uncontrolled type coercion and OTel wire surprises.
- `SpanAttributeValue` sealed whitelist mirrors OTel-compatible types.

## Consequences

### Positive

- Auditable escape hatch with searchable reason/reference.
- Temporary workarounds are explicitly tagged for fleet review.

### Negative

- More ceremony for edge cases (intentional friction).

## References

- [platform-tracing-v3-manual-api.md](../tracing/platform-tracing-v3-manual-api.md)
- [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) — R03
