# Runtime Control and Propagation Security

## Runtime Control Security

Runtime control is a privileged operational capability.

### Required behavior

- `APPLY_RUNTIME_POLICY` must fail closed by default.
- Mutation requires explicit startup enablement.
- `VALIDATE_RUNTIME_POLICY` must never apply changes.
- `READ_APPLIED_STATE` must never mutate state.
- Decode-invalid requests must not reach domain apply.
- Domain-invalid requests must not reach apply.
- Mutation-policy-rejected requests must not reach apply.
- Rejected requests must not modify:
    - last-known-good state
    - applied snapshot
    - version
    - source
    - timestamps representing successful apply
- Apply must be atomic from the reader's perspective.
- Partial failure must not leave mixed old/new state.
- Rollback behavior must be tested where multiple MBeans/resources are registered.

### Startup gate

A mutation-enabling property must:

- default to `false`
- be strongly typed
- be present in configuration metadata
- be documented as security-sensitive
- have startup diagnostics
- have negative and positive tests
- not be changeable through the same untrusted runtime-control path it protects

Example:

```yaml
platform:
  tracing:
    control:
      runtime-mutation:
        enabled: false
```

### JMX boundary

The unified control-protocol MBean must enforce the mutation gate.

Historical domain MBeans are separate risk surfaces. Until removed or hardened, they require:

- JVM/JMX authentication
- network isolation
- RBAC/firewall governance
- explicit warning-register tracking

Do not claim the unified mutation gate protects unrelated historical MBeans.

### Result model

Control results must distinguish at least:

- decode rejected
- domain rejected
- mutation rejected
- applied
- read result
- validation-only result

Machine-readable status must not include raw sensitive payload values.

## Control Protocol Security

The control protocol is a classloader-neutral boundary, not a trusted in-process method call.

Required pipeline:

```text
wire payload -> structural decode -> domain validation -> mutation policy -> apply/read
```

### Structural validation

The API decoder must enforce:

- required envelope fields
- supported contract version
- recognized operation
- operation-specific allowed keys
- strict unknown-key rejection
- String-only map keys
- supported JDK-only value shapes
- deterministic type normalization
- enum-instance rejection as a wire value
- bounded arrays/maps/strings where applicable
- immutable decode result

An invalid decode result must not expose a usable normalized payload for apply.

### Domain validation

Core must enforce:

- sampling-ratio bounds
- route-ratio bounds
- allowed validation modes
- empty-mutation rejection
- cross-field invariants
- kill-switch / force-control conflicts
- limits that protect backend or runtime capacity

Wire errors and domain errors must use different ownership and diagnostics.

### Schema exposure

Internal programmatic schema validation must remain.

Do not expose the internal schema/decoder as public runtime introspection merely for convenience.

Preferred public contract:

- documented wire spec
- public keys/operations/result codes
- executable golden tests
- ADRs
- JMX `MBeanInfo` in the adapter layer when appropriate

Do not reintroduce:

- public `schema()`
- public `validator()`
- `READ_SCHEMA`
- legacy validation facades

## Propagation Security

Trace propagation data is correlation metadata, not identity or authorization.

Never use:

- trace ID
- span ID
- request ID
- baggage
- `traceparent`
- `tracestate`
- force-sampling header

as proof of authentication, tenant ownership, or authorization.

### W3C trace context

Use the approved OpenTelemetry W3C implementation where possible.

Do not maintain ad hoc parsing unless explicitly justified and covered by conformance tests.

Validate:

- syntax
- zero IDs
- version behavior
- flags
- bounded length
- invalid/malformed behavior

Do not log the full malformed header in exceptions or logs. Use sanitized, bounded diagnostics.

### Baggage

Baggage may cross service and organization boundaries.

Rules:

- do not place secrets or raw PII in baggage
- define an allowlist for platform-owned baggage keys
- impose length and entry-count limits
- do not copy arbitrary baggage into span attributes or logs
- do not trust baggage values for authorization
- remove or ignore disallowed keys
- avoid high-cardinality baggage propagation

### Request and correlation IDs

Request IDs are untrusted external strings.

They must be:

- bounded
- character-restricted
- safe for headers and logs
- generated when absent/invalid according to platform policy
- independent from trace IDs

Do not reflect raw invalid request IDs into logs or response headers.

## Force Sampling and Trace-Control Headers

A header that can force sampling can create:

- telemetry amplification
- backend cost spikes
- PII exposure
- denial of service
- targeted collection of sensitive flows

Rules:

- disabled by default unless explicitly required
- use strict allowed values
- bound header length
- do not accept arbitrary truthy strings
- document trusted ingress assumptions
- consider stripping or rewriting at trusted gateways
- do not allow the header to bypass scrubbing
- do not allow force sampling to bypass export kill switches or security policy
- test ratio `0` plus force-header behavior deterministically
- expose safe metrics for accepted/rejected force requests where cardinality is bounded

Do not treat possession of a force header as authorization.

