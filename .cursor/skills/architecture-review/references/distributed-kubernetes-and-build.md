# Distributed Systems, Kubernetes, and Build Architecture

## Distributed Systems Applicability

Do not introduce distributed infrastructure without proving need.

For Redis, Kafka, database coordination, leases, or external state, define:

```text
Problem:
Consistency model:
Owner:
State lifecycle:
Failure policy:
Idempotency:
Retry owner:
Recovery:
Security:
Observability:
Kubernetes behavior:
Test evidence:
```

Redis/KeyDB is not a default tracing dependency.

Follow `redis.md`.

## Kubernetes Architecture

The solution must tolerate:

- pod restart
- rolling deployment
- rescheduling
- transient network failure
- DNS changes
- optional telemetry backend outage

Avoid:

- fixed pod/node identity
- local filesystem dependency
- cluster-admin assumptions
- Docker socket mounts
- public JMX exposure
- service code managing deployment infrastructure

Readiness/liveness must reflect business-service semantics, not optional telemetry convenience.

## Build Architecture

Build logic must reflect module ownership.

Use the repository's existing Gradle wrapper, DSL, dependency management, and build-logic model.

Do not perform incidental:

- Groovy-to-Kotlin migration
- version-catalog introduction
- convention-plugin reorganization
- wrapper upgrade
- publication redesign

Dependency scope and publication metadata are architecture.

Architecture fitness tasks are release gates.

Follow `gradle.md`.

