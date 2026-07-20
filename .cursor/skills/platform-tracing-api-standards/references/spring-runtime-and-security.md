# Spring Runtime and Security

## Configuration as External Contract

Spring configuration keys are an external adoption contract, but current pre-production keys may be renamed or removed when architecture improves.

Current policy:

- direct migration
- update tests, docs, samples, metadata, Helm/env mappings
- no aliases by default
- no deprecated property bridge by default
- no dual binding path

Every public property must have:

- clear owner
- type
- default
- safety semantics
- configuration metadata
- tests
- documentation

After production, property compatibility must follow an explicit versioning/deprecation policy. That future policy must not block justified pre-production cleanup now.

## Bean Names

Bean names are not automatically public API.

Treat a bean name as supported contract only when:

- users reference it by qualifier/name
- an integration contract documents it
- tests and docs intentionally protect it

Before production, rename ambiguous bean names directly and update all consumers.

Do not keep duplicate bean aliases unless an ADR requires them.

## No-Op and Disabled Behavior

No-op behavior is part of the API contract when tracing can be disabled.

Requirements:

- same public entry points remain safe
- no unexpected exceptions
- no hidden side effects
- no network calls
- no mutation
- returned handles/results obey documented lifecycle
- behavior is tested independently from the active runtime

Do not let no-op behavior silently diverge from active semantics where callers depend on lifecycle or validation.

## Thread Safety and Lifecycle

Public contracts must document thread-safety and lifecycle when relevant.

Clarify:

- singleton safety
- builder thread confinement
- handle close semantics
- idempotent close/apply behavior
- whether objects may be reused
- callback execution context
- blocking/non-blocking behavior
- reactive context behavior

Do not imply thread safety by omission for mutable builders or runtime handles.

## Security and Privacy

Public APIs must make unsafe behavior difficult.

Do not expose APIs that encourage:

- raw PII attributes
- unbounded high-cardinality values
- arbitrary attribute keys
- raw request/response payloads
- trace metadata as authorization
- runtime mutation without guardrails
- exporter endpoint mutation without threat review

If an escape hatch exists, it requires:

- explicit naming
- narrow visibility
- safe default
- diagnostics
- tests
- scrubbing/governance integration

