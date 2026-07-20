# Runtime Control, Actuator, and Web Stacks

## Runtime Control

Runtime control is a high-risk capability.

Rules:

- mutation must fail closed by default
- read operations may remain available when safe
- validation-only operations must not apply changes
- rejected mutations must not change last-known-good state, version, source, or snapshot
- runtime mutation policy belongs in core/OTel integration, not public API
- Spring properties define startup policy; live state must be read from runtime diagnostics
- Actuator and JMX must not implement conflicting authorization semantics

Any mutation-enabling property must:

- default to disabled
- be documented as operationally sensitive
- have startup diagnostics
- have negative tests
- be included in rollout guidance

## Actuator Integration

Actuator endpoints are operational APIs, not convenience controllers.

Actuator endpoints must:

- avoid exposing secrets or raw PII
- distinguish desired configuration from live applied state
- expose read-only state by default
- use explicit mutation guards for write operations
- return actionable, stable diagnostic fields
- not return internal implementation classes
- not depend on JMX implementation types

Do not publish an Actuator endpoint merely because a bean exists.

Every endpoint needs:

- a documented operator use case
- security assumptions
- tests
- cardinality and data-sensitivity review

## JMX Integration

JMX/OpenMBean code belongs outside Spring-facing public API.

Spring auto-configuration may register/configure JMX integration, but:

- JMX types must stay in the JMX/OTel extension module
- Map/OpenType conversion must happen at the adapter boundary
- decode and domain validation must occur before apply
- direct historical domain MBeans must be documented as separate risk surfaces
- startup policy and JVM/network/RBAC assumptions must be explicit

Do not use the Spring `ApplicationContext` as a service locator for JMX handlers.

## Observability of the Starter

The tracing starter itself must be diagnosable.

Where safe, expose:

- whether tracing is active
- selected runtime mode
- enabled instrumentation adapters
- current mutation policy
- last-applied runtime-control metadata
- active scrubbing rule names or safe fingerprints
- skipped/unknown configuration names
- exporter/runtime readiness
- warnings that require operator action

Do not expose:

- raw sensitive attribute values
- secrets
- authorization headers
- internal object dumps
- unbounded lists with high cardinality

Startup logs should be concise and actionable. Avoid logging the same warning repeatedly.

## Servlet and Reactive Separation

Servlet and WebFlux integrations must remain independently conditional.

Servlet configuration must not require:

- Reactor
- WebFlux
- reactive HTTP server classes

WebFlux configuration must not require:

- servlet APIs
- servlet filters
- servlet-specific request context

Shared tracing semantics should be tested across both stacks, including:

- inbound tracing
- outbound propagation
- MDC/context behavior
- error mapping
- no duplicate spans
- disabled behavior

Do not assume thread-local MDC propagation works automatically in reactive flows.

## Optional Classpath Behavior

Optional integrations must be tested with missing classes.

Use:

- `ApplicationContextRunner`
- `FilteredClassLoader`
- focused classpath smoke tests

Verify:

- auto-configuration backs off cleanly
- unrelated beans still start
- no `NoClassDefFoundError`
- no eager loading of optional types
- condition reports are understandable

Avoid referencing optional classes in unconditional method signatures or static initializers.

