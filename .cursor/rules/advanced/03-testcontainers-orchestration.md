# Testcontainers Orchestration Rules

## Context
All integration tests must use real infrastructure via Testcontainers.

---

## Container Rules

All containers MUST:
- be reusable
- be deterministic
- be isolated per test class

---

## Forbidden

- embedded Redis
- mocked infrastructure for integration tests
- fixed port binding
- Thread.sleep for synchronization

---

## Startup Rules

Containers MUST:
- start before Spring context initialization
- expose dynamic ports only
- use wait strategies explicitly

---

## Supported Containers

Allowed:
- PostgreSQL
- Redis / KeyDB
- Kafka
- MinIO

---

## Spring Integration Rules

Use:
- @DynamicPropertySource
- ApplicationContextInitializer

Forbidden:
- hardcoded localhost ports
- static configuration of ports

---

## Lifecycle Rules

Containers MUST:
- be static when possible
- be shared across tests safely
- avoid reinitialization overhead

---

## Anti-Patterns
- test interdependence
- flaky timing-based waits
- external environment dependencies