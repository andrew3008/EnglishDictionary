# Runtime State, Failure, Audit, and Deployment Security

## Concurrency and Runtime State

Runtime state must be:

- immutable or safely published
- updated atomically
- versioned when needed
- recoverable through last-known-good state
- readable without observing partial mutation
- protected from concurrent conflicting applies

Tests must cover:

- concurrent reads during apply
- rejected apply preserving old state
- repeated identical apply
- partial failure rollback
- registration rollback
- no static mutable test leakage

## Failure Semantics

Choose failure behavior explicitly.

### Fail closed

Use for:

- unauthorized/disabled mutation
- invalid control payload
- domain-invalid policy
- malformed security-sensitive configuration
- invalid exporter endpoint mutation
- unknown critical scrubbing rule when policy requires strictness

### Fail startup

Use when:

- a mandatory safety invariant cannot be satisfied
- a required security component is missing
- mutually exclusive critical settings are enabled

### Degrade safely

Use when:

- an optional diagnostic surface is unavailable
- tracing is explicitly disabled
- export failure policy permits application continuation

Do not silently fall back to a permissive or unsafe mode.

## Security Logging and Auditability

Security-relevant events should be structured and bounded.

Audit at least:

- mutation allowed/denied
- operation
- source category
- version or correlation identifier
- result status
- reason code
- timestamp
- state version before/after when safe

Do not audit raw payload values.

Avoid duplicate warning floods. Deduplication must be bounded and test-isolated.

## Kubernetes and Deployment Security

Production deployment guidance should include:

- non-root containers
- read-only root filesystem where practical
- least-privilege RBAC
- network policies
- restricted JMX exposure
- secret injection through approved mechanisms
- no Docker socket mount
- no cluster-admin requirement
- trusted collector/exporter network path
- resource limits for collector/exporter components
- explicit runtime-mutation enablement only where operationally approved

Do not assume Kubernetes network location alone makes JMX or telemetry trusted.

