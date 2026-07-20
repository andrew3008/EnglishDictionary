# Distributed Systems, Kubernetes, and Dependencies

## Distributed Systems

Do not introduce distributed infrastructure without proving the need.

For any distributed state/coordination:

- define consistency model
- define owner
- define idempotency
- define retry owner
- define failure policy
- define recovery
- define observability
- define Kubernetes behavior
- define security
- test partial failure

Do not assume:

- singleton deployment
- stable pod identity
- local locks provide distributed protection
- retries are harmless
- clocks are synchronized
- infrastructure is always available

## Redis/KeyDB Applicability

Redis/KeyDB is not a default dependency for tracing.

Before adding it, prove:

- real distributed shared-state requirement
- correct owner module
- acceptable failure model
- TTL/lifecycle
- namespace
- serialization
- topology support
- Testcontainers evidence
- why database/Kafka/Kubernetes Lease/local state is not better

Follow `redis.md`.

## Kubernetes

Runtime behavior must tolerate:

- pod restart
- rolling deployment
- rescheduling
- transient network failure
- DNS changes
- optional telemetry backend outage

Avoid:

- fixed topology
- pod IP assumptions
- local filesystem dependence
- privileged behavior
- cluster-admin assumptions
- Docker socket mounts
- JMX public exposure

Readiness/liveness must reflect business-service semantics, not optional telemetry availability.

## Dependencies

Use intentional Gradle scopes.

Do not:

- add dependencies only to make compilation pass
- solve cycles with `api`
- hard-code BOM-managed versions
- add broad artifacts for narrow needs
- leak implementation types
- add untrusted repositories
- use dynamic versions

For every new dependency, record:

```text
Artifact:
Version owner:
Scope:
Public API exposure:
Runtime provider:
Transitive impact:
License/governance:
Why needed:
```

Follow `gradle.md`.

