---
name: security-review
description: Performs threat-model-driven security and privacy reviews for the enterprise Platform Tracing repository. Use when Codex reviews, designs, refactors, implements, or validates propagation, runtime control, JMX, OpenTelemetry Agent/SDK integration, sampling, scrubbing, telemetry attributes, exporters, Spring Boot configuration, classloader boundaries, SPIs, dependencies, deployment, or any change that can expose secrets or PII, permit unauthorized mutation, amplify resource usage, or weaken fail-closed behavior.
---

# Platform Tracing Security Review

## Objective

Verify that Platform Tracing changes preserve explicit trust boundaries, prevent telemetry data disclosure, reject unauthorized or malformed control input, bound resource amplification, and fail safely.

The solution is pre-production. Breaking API, SPI, protocol, package, bean, property, dependency, or module changes are allowed when they materially improve security, privacy, runtime safety, auditability, or production readiness.

Do not preserve insecure aliases, permissive fallbacks, dual validation paths, hidden test bypasses, deprecated security bridges, or ambiguous behavior for compatibility.

A security review must close a concrete threat or provide executable evidence that the threat is already controlled. Cosmetic security changes are insufficient.

## Default behavior

Perform a read-only review unless the user explicitly authorizes fixes.

Treat plans, documentation, implementation reports, bean presence, MBean presence, and model summaries as claims rather than enforcement evidence.

Preserve unrelated user changes. Do not commit, push, deploy, mutate runtime control, or contact external systems unless explicitly authorized.

## Security priority

When requirements conflict, prefer:

1. prevention of credential, secret, and personal-data disclosure
2. prevention of unauthorized runtime mutation
3. correctness of trace context and control validation
4. least privilege
5. fail-closed behavior for risky operations
6. atomic state and auditability
7. bounded resource usage and cardinality
8. availability and deliberate degradation
9. developer convenience
10. compatibility with pre-production behavior

## Review workflow

1. Read applicable repository instructions, authoritative plans, ADRs, and prior security findings.
2. Establish scope, branch, commit, diff, deployment modes, and do-not-touch areas.
3. Identify assets, actors, entry points, privileges, trust levels, and operational owners.
4. Draw or describe every affected trust boundary and data flow.
5. Classify all external input as untrusted until validated.
6. Classify telemetry fields and diagnostic values by sensitivity and cardinality.
7. Load the applicable files from `references/`.
8. Trace extraction, decoding, normalization, validation, policy, application, propagation, storage, projection, export, logging, and error paths.
9. Verify that rejected input cannot mutate state or reach downstream consumers.
10. Verify startup, ready, disabled, degraded, incompatible, failed, shutdown, and rollback states.
11. Inspect Agent/application classloader isolation and all JMX/wire representations.
12. Review dependency scopes, artifact provenance, extension discovery, and deployment enforcement.
13. Test oversized, duplicated, malformed, forged, stale, unauthorized, high-cardinality, concurrent, and partial-failure inputs.
14. Verify actual exporter, collector, log, metric, and diagnostic observations where disclosure is possible.
15. Assign findings, evidence status, required fixes, and a merge verdict.

## Mandatory security invariants

- Treat trace context, baggage, request/correlation IDs, custom headers, messaging metadata, JMX payloads, configuration, and environment input as untrusted.
- Separate structural decoding, domain validation, authorization/policy, and state application.
- Do not mutate runtime state after any rejected or partially validated request.
- Keep applied multi-field state atomic.
- Do not expose raw secrets, credentials, authorization headers, tokens, cookies, personal data, or unrestricted baggage in telemetry or diagnostics.
- Apply sanitization before export; application-side NoOp is not proof that Agent export stopped.
- Do not use telemetry identifiers as authentication or authorization credentials.
- Bound input length, collection size, nesting, cardinality, event/link counts, retries, queues, and diagnostic payloads.
- Keep API contracts free of OpenTelemetry SDK, Agent, Spring, JMX, serializer, and transport implementation types.
- Use classloader-neutral, versioned, strictly validated protocols across Agent/application and JMX boundaries.
- Do not use shared mutable statics or cross-classloader object injection.
- Require explicit, trusted discovery for privileged SPIs; reject missing, duplicate, incompatible, or failing providers safely.
- Use least-privilege dependency scopes, verified artifacts, pinned versions, and controlled production deployment paths.
- Distinguish availability degradation from security or compliance bypass.
- Fail startup or close the risky capability when mandatory enforcement cannot initialize.
- Make audit logs useful without including rejected raw payloads or sensitive values.
- Require negative, adversarial, concurrency, packaged E2E, and architecture tests proportional to the threat.

## Evidence classification

Classify material conclusions as:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Do not claim:

- sanitization from configuration alone
- authorization from network placement alone
- fail-closed behavior from an ERROR log
- stopped Agent export from a NoOp application facade
- runtime safety from unit tests alone
- compatible classloader behavior from shared test classpaths
- passed E2E when the task was skipped
- secure defaults without testing the absence and invalid-value cases

## Reference selection

Read only the references relevant to the review, except for required combinations.

### Security goals, threat model, and trust boundaries

Read [security-foundations-and-trust-boundaries.md](references/security-foundations-and-trust-boundaries.md).

Use it for security goals, explicit non-goals, threat actors and inputs, module ownership, boundary classification, and secure defaults.

### Runtime control, protocols, propagation, and force sampling

Read [runtime-control-and-propagation.md](references/runtime-control-and-propagation.md).

Use it for startup gates, mutation, JMX payloads, validation, schemas, W3C context, baggage, request/correlation IDs, and privileged sampling headers.

### Sampling, telemetry classification, attributes, scrubbing, and signals

Read [telemetry-data-safety.md](references/telemetry-data-safety.md).

Use it for cost controls, span names, attributes, PII, events, links, resources, logs, and metrics.

### Exporters, collectors, secrets, endpoints, Spring, JMX, and classloaders

Read [exporter-secrets-and-runtime-boundaries.md](references/exporter-secrets-and-runtime-boundaries.md).

Use it for export transport, endpoint trust, credentials, Actuator/JMX exposure, Spring Security integration, and Agent/application isolation.

### SPIs, supply chain, serialization, and denial of service

Read [extensions-supply-chain-and-resource-limits.md](references/extensions-supply-chain-and-resource-limits.md).

Use it for ServiceLoader/extensions, artifact integrity, dependency risk, deserialization, parser hardening, amplification, and resource bounds.

### Runtime state, failure semantics, audit, and Kubernetes

Read [state-failure-audit-and-deployment.md](references/state-failure-audit-and-deployment.md).

Use it for concurrency, atomic state, fail-closed/fail-startup/degrade decisions, audit logging, workload identity, network policy, and deployment enforcement.

### Security tests, fitness rules, breaking changes, and report

Read [testing-governance-and-reporting.md](references/testing-governance-and-reporting.md).

Use it for adversarial tests, architecture rules, import/generated-code checks, anti-patterns, verification commands, severity, and the final security report.

## Required reference combinations

For every security review, read:

1. `security-foundations-and-trust-boundaries.md`
2. `testing-governance-and-reporting.md`
3. every domain reference touched by the diff

For runtime control or propagation changes, also read:

1. `runtime-control-and-propagation.md`
2. `state-failure-audit-and-deployment.md`

For telemetry capture or export changes, also read:

1. `telemetry-data-safety.md`
2. `exporter-secrets-and-runtime-boundaries.md`
3. `extensions-supply-chain-and-resource-limits.md`

## Completion standard

Do not report completion until:

- assets, threats, actors, entry points, and trust boundaries are explicit
- sensitive fields and high-cardinality inputs are classified
- validation, policy, state mutation, and export ordering are verified
- failure and degradation behavior cannot bypass mandatory enforcement
- dependency, artifact, SPI, classloader, and deployment boundaries are reviewed
- negative and adversarial tests actually execute
- required packaged E2E is not skipped
- findings identify severity, evidence, exploit/failure path, consequence, and required fix
- residual risks and external enforcement dependencies are explicit
- the final merge/security verdict is explicit

