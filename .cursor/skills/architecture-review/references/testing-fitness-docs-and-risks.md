# Testing, Fitness Functions, Documentation, and Risks

## Testing Architecture

Tests must exist at the correct layer.

### API tests

Protect:

- public surface
- value/result invariants
- removed FQNs
- dependency purity
- JavaDoc

### Core tests

Protect:

- runtime behavior
- domain validation
- state transition
- no-op
- concurrency
- policy safety

### Spring tests

Protect:

- conditions
- properties
- defaults
- optional classpaths
- diagnostics
- bean graph

### Adapter tests

Protect:

- JMX/OpenMBean conversion
- agent/SDK behavior
- servlet/WebFlux integration
- classloader boundaries

### E2E

Protect:

- real propagation
- export
- sampling
- scrubbing
- JMX wire path
- runtime mutation
- agent/application boundaries

A skipped E2E test is not runtime evidence.

Follow `testing.md` and `testcontainers.md`.

## Architecture Fitness Functions

Architecture decisions must be executable where practical.

Protect at least:

- API does not depend on core
- core does not depend on Spring
- webmvc/webflux isolation
- JMX/OpenMBean absent from API
- OTel SDK implementation absent from API
- exact public surface for sensitive packages
- internal helpers not public
- legacy packages/symbols absent
- no public schema/validator
- no raw wire apply
- no domain rules in wire decoder
- mutation disabled by default
- starters remain thin
- forbidden dependency directions
- wildcard imports absent
- required ServiceLoader descriptors exact
- obsolete descriptors absent

Do not weaken fitness rules to make generated code compile.

A new critical architecture decision should add a corresponding guard when technically feasible.

## Documentation Architecture

Architecture documentation must distinguish:

- current supported architecture
- historical architecture
- superseded plan
- future proposal
- residual risk
- external operational control

Current-looking docs must not use stale names or removed APIs.

Historical docs may retain old names only with a clear banner.

Important decisions must exist in ADRs and current architecture docs, not only in audit reports.

## Warning and Risk Register

A warning register must record:

- risk
- owner
- affected paths
- mitigation
- evidence
- residual risk
- closure trigger

Do not close a warning because one path is fixed when equivalent paths remain.

Do not use warning registers as permanent excuses for avoidable debt.

## Architecture Review and Audit

Choose the correct review mode:

- architecture review
- implementation review
- post-implementation audit
- post-fix audit
- security review
- performance review

Post-implementation audit must treat implementation summaries as claims.

Post-fix audit should verify known findings without reopening all accepted architecture decisions.

Follow `code-review.md`.

