# ADR: Tracing control protocol version model and unified naming

## Status

**Accepted** — implemented in `space.br1440.platform.tracing.api.control.protocol`

## Context

Platform Tracing control payloads cross classloader boundaries (application vs agent extension). The contract must live in `platform-tracing-api` with JDK-only dependencies, validate `Map<String, Object>` payloads, and expose a single stable entrypoint.

The original PR-2 package `api.control.wire` used mixed `TracingControlWire*` naming. A later protocol refactor introduced `api.control.protocol` but retained hybrid names (`ControlWire*`, `WireViolationCode`) that drifted from the aggregate `TracingControlProtocol` prefix.

## Decision

### Unified naming taxonomy

All **public top-level production** types under `space.br1440.platform.tracing.api.control.protocol..` use the `TracingControlProtocol` prefix:

| Type | Role |
|------|------|
| `TracingControlProtocol` | Single public entrypoint (static + instance API) |
| `TracingControlProtocolVersion` | Version value object (`record`) |
| `TracingControlProtocolKeys` / `Types` / `Operation` | Schema constants and operation enum |
| `TracingControlProtocolFieldCategory` / `FieldDescriptor` | Descriptor-driven schema |
| `TracingControlProtocolSchema` | Field registry and required-key logic |
| `TracingControlProtocolValidator` | Map validation |
| `TracingControlProtocolViolationCode` | Stable violation codes |
| `TracingControlProtocolValidationResult` / `Violation` | Validation outcome |

**Why Wire is removed from production API names:** `Wire` described the transport boundary (Map over JMX), not the protocol semantics. Keeping `Wire` in type names implied a second parallel taxonomy and conflicted with the aggregate name `TracingControlProtocol`. The term **wire** remains acceptable in historical docs, ADR Map-boundary descriptions, and e2e harness names (`WireRoundTrip*`) — not in production type simple names.

### Map boundary rationale

- Payloads are `Map<String, Object>` with open-type values (String, Integer, Boolean, etc.).
- No typed DTOs (`*Command`, `*Dto`, `*Request`) in the API module — raw Java types cannot safely cross classloaders.
- No OpenMBean classes in `platform-tracing-api`; OpenMBean compatibility is a validation constraint on Map values, not an API dependency.

### Record vs enum for version

- `TracingControlProtocolVersion` is a **`record(int major)`**, not an enum.
- Supported versions are determined by a **private static registry** inside `TracingControlProtocol`, not by enum constants or public singletons.
- Parsing is separated from support-check: `parse(Object) → Optional<TracingControlProtocolVersion>`; unsupported parsed versions yield `UNSUPPORTED_VERSION`.

### Parse / coercion policy

- Inbound `contractVersion` accepts `Integer`, `Long`, or trimmed `String`.
- Canonical identity is `int major`; normalized wire output uses `Integer`.
- Malformed/unparseable version → `INVALID_VALUE` (not `UNSUPPORTED_VERSION` or `TYPE_MISMATCH` for version field).
- Valid-but-unsupported major → `UNSUPPORTED_VERSION`.
- All other known fields with wrong Java type → `TYPE_MISMATCH`.

### Violation codes

`TracingControlProtocolViolationCode` is a closed enum (6 values):

- `UNSUPPORTED_VERSION`, `INVALID_VALUE`, `UNKNOWN_KEY`, `MISSING_REQUIRED_KEY`, `TYPE_MISMATCH`, `OPERATION_NOT_ALLOWED`

`TracingControlProtocolViolation` carries `code` (stable) plus free-text `reason` (non-contractual).

### Private registry / single entrypoint

- No public `*Registry` type. Registry is private static nested inside `TracingControlProtocol`.
- Public access: `TracingControlProtocol.current()`, `find(...)`, `isSupported(...)`, instance `version()` / `schema()` / `validator()`.
- No public `Schema.V1` / `Validator.V1` singletons.

### Descriptor-driven schema

- `TracingControlProtocolFieldDescriptor` uses field `type` (type: `TracingControlProtocolTypes`) — **not** Java `Class<?>` and not an arbitrary value type name.
- Operation-aware requiredness via `Set<TracingControlProtocolOperation>` and `requiredKeysFor(TracingControlProtocolOperation)`.
- Strict unknown-key rejection for v1.

### No typed DTO, no shims

- No transitional bridge in production (`TracingControlProtocolLegacyV1Bridge` / `WireV1Bridge` existed only during migration phases and was removed).
- Package `api.control.wire` removed; no `@Deprecated` shims.

## Consequences

- ArchUnit enforces unified prefix and no `Wire` in public top-level production type names (`PROTOCOL_API_TYPES_USE_UNIFIED_PREFIX`, `PROTOCOL_API_TYPES_DO_NOT_USE_WIRE_NAMING`).
- E2e harness keeps `WireRoundTrip*` class names and `jmx.wire` package; only imports updated protocol result/violation types.
- Live architecture docs and fitness functions reference `API_PROTOCOL_*` rules and `api.control.protocol`.

## Related documents

- [platform-tracing-wire-schema-v1.md](../architecture/platform-tracing-wire-schema-v1.md)
- [ADR-jmx-wire-map-contract.md](../architecture/ADR-jmx-wire-map-contract.md)
- [platform-tracing-fitness-functions-implementation.md](../architecture/platform-tracing-fitness-functions-implementation.md)
- [tracing-control-protocol-refactoring-plan.md](../analysis/tracing-control-protocol-refactoring-plan.md)
