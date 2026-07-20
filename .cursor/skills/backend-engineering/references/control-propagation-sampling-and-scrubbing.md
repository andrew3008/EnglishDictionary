# Control, Propagation, Sampling, and Scrubbing

## Control Protocol

Protect the approved pipeline:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

Required properties:

- internal programmatic schema remains
- public schema introspection remains removed
- no public `schema()`
- no public `validator()`
- no `READ_SCHEMA`
- strict unknown-key rejection
- operation-specific schemas
- classloader-neutral values
- immutable decode result
- invalid result has no usable apply payload
- domain rules live in core
- empty mutation is rejected
- apply is unreachable after any rejection
- READ and VALIDATE do not mutate
- mutation is fail-closed by default
- rejected mutation preserves snapshot/version/source/LKG

Do not reintroduce legacy packages, facades, aliases, or dual decoder paths.

## Runtime Control

Runtime mutation is privileged.

Rules:

- disabled by default
- explicit startup enablement
- read operations remain available when safe
- validation-only operations do not apply
- rejected mutation does not modify runtime state
- result statuses are machine-readable
- audit metadata is bounded and does not contain raw payloads
- historical domain JMX MBeans remain separate risk surfaces until explicitly hardened or removed

Do not claim JVM/network/RBAC protection is implemented by tracing code.

## Propagation

Use W3C Trace Context and approved OpenTelemetry implementations.

Incoming propagation is untrusted.

Do not:

- use tracing metadata for authentication or authorization
- log full malformed headers
- maintain custom parsers without strong justification
- copy arbitrary baggage into spans/logs
- accept unbounded headers
- create invalid remote context

Test propagation across:

- servlet
- WebFlux
- Kafka/messaging
- executors
- Reactor
- scheduled work
- agent/application classloaders
- remote links

## Sampling

Sampling must be deterministic and explainable.

Require:

- ratio bounds
- route-ratio bounds
- explicit route precedence
- normalized route templates
- deterministic tests
- empty mutation rejection
- fail-closed runtime mutation
- force-sampling trust model
- LKG preservation after rejection
- no conflict between platform and OTel sampler settings
- readable applied state

Force sampling must not bypass scrubbing, export safety, or kill switches.

## Scrubbing and PII

Scrubbing is a production safety mechanism.

Do not claim broad PII protection when only span attributes are processed.

Review separately:

- attributes
- events
- links
- baggage
- resources
- logs
- metrics

Require:

- active rule diagnostics
- skipped unknown rule visibility
- safe rule fingerprint
- deterministic behavior
- critical failure semantics
- no raw sensitive values in diagnostics
- force-sampled spans still scrubbed

